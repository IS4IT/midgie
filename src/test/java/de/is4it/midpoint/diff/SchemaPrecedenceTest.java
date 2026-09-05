/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which copy of an extension schema wins when the same {@code targetNamespace} exists both in the deploy
 * tree and on the server, with different content in each.
 *
 * <p>The local copy does, and it must keep doing so. {@link SchemaSupport#dedupeByNamespace} keeps the
 * <em>first</em> entry per namespace, so precedence is decided purely by the order {@code deploy} inserts
 * the sources into the map: tree, then {@code --schema-dir}/{@code --schema}, then the live read. That
 * collection is now made in two calls rather than one — the local half before the socket is opened, the
 * live half after — and this test is what says the split did not reverse them.
 */
class SchemaPrecedenceTest {

    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api-types-3";

    /** The namespace both copies claim. Their content differs, so which one won is observable. */
    private static final String CONFLICT_NS = "http://is4it.de/midpoint/v1/conflict";

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/schemas", this::serveSchemas);
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theTreeCopyBeatsTheServersCopyOfTheSameNamespace(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("conflict-schema.xml"), treeSchema(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("role.xml"), role(), StandardCharsets.UTF_8);

        // The run fails later, at the object fetch the stub does not serve. That is fine and deliberate:
        // schema registration is decided before any object is touched, which is the whole point of asking
        // the question here rather than after a successful deploy.
        List<String> log = debugLogOf(() -> run("deploy", dir.toString(), "--url", host,
                "-u", "u", "-p", "p", "--dry-run"));

        String registered = line(log, "registering ");
        assertTrue(registered.contains("deploy schema conflict-schema.xml"),
                "the tree's copy is the one registered: " + registered);
        assertFalse(registered.contains("live schema"),
                "and the server's copy of the same namespace is not: " + registered);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("schema live schema") && l.contains("already taken")),
                "the server's copy is the one dropped, and says so under --debug: " + log);
    }

    /** The server's copy: same namespace as the tree's, different content. */
    private void serveSchemas(HttpExchange exchange) throws IOException {
        byte[] body;
        try {
            ObjectListType list = new ObjectListType();
            list.getObject().add((ObjectType) prismContext.parseObject(serverSchema()).asObjectable());
            body = prismContext.xmlSerializer()
                    .serializeRealValue(list, new QName(NS_API, "objectList"))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Answering "no schemas" would let the test pass having never staged the conflict it is about.
            throw new IOException("stub could not build the schema list", e);
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String treeSchema() {
        return schemaObject("44444444-0000-0000-0c00-000000000001", "tree-conflict-schema", "TreeOnlyType");
    }

    private static String serverSchema() {
        return schemaObject("44444444-0000-0000-0c00-000000000002", "server-conflict-schema", "ServerOnlyType");
    }

    private static String schemaObject(String oid, String name, String typeName) {
        return "<schema xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\"\n"
                + "        xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" oid=\"" + oid + "\">\n"
                + "    <name>" + name + "</name>\n"
                + "    <definition>\n"
                + "        <xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "                    elementFormDefault=\"qualified\"\n"
                + "                    targetNamespace=\"" + CONFLICT_NS + "\">\n"
                + "            <xsd:complexType name=\"" + typeName + "\">\n"
                + "                <xsd:sequence>\n"
                + "                    <xsd:element name=\"label\" type=\"xsd:string\"/>\n"
                + "                </xsd:sequence>\n"
                + "            </xsd:complexType>\n"
                + "        </xsd:schema>\n"
                + "    </definition>\n"
                + "</schema>\n";
    }

    private static String role() {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\"\n"
                + "      oid=\"55555555-0000-0000-0c00-000000000001\">\n"
                + "    <name>Conflict Role</name>\n"
                + "</role>\n";
    }

    /** The one DEBUG line starting with {@code prefix}; fails the test rather than returning nothing. */
    private static String line(List<String> log, String prefix) {
        return log.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(
                () -> new AssertionError("no log line starting with '" + prefix + "' in: " + log));
    }

    /**
     * Run {@code body} with this package's logger at DEBUG and collect what it logged.
     *
     * <p>Logback's console appender caches the {@code System.err} it was started with, so a swapped stream
     * does not see these lines — the appender has to be attached to the logger instead.
     */
    private static List<String> debugLogOf(Runnable body) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Io.class.getPackageName());
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level original = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            body.run();
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    /** Drive the real CLI, swallowing both streams: this test reads the log, not the output. */
    private static void run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            Main.commandLine().execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
