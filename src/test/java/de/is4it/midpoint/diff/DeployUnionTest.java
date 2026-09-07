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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the union is for, asserted where it can be seen: the requests {@code deploy} actually sends.
 *
 * <p>An object described by two files used to produce two PATCHes, the second replacing what the first had
 * written into any multi-valued item they shared — the "last-one-wins" clobber, whose only workaround was
 * {@code multivalue=merge} and the silent-removal problem that comes with it. One request carrying both
 * files' contributions is the whole point of combining them first, and a unit test on the union object
 * cannot prove the apply loop actually sends it.
 */
class DeployUnionTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String OID = "cccccccc-0000-0000-0f00-000000000003";

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;
    private final List<String> patched = new CopyOnWriteArrayList<>();
    private final List<String> created = new CopyOnWriteArrayList<>();
    private final List<String> createdTypes = new CopyOnWriteArrayList<>();
    private volatile boolean objectExists = true;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/roles", this::handle);
        server.createContext("/midpoint/ws/rest/schemas", this::handle);
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void twoFilesOfOneObjectBecomeOnePatchCarryingBoth(@TempDir Path dir) throws Exception {
        writeFragments(dir);

        assertEquals(0, deploy(dir), "the deploy should succeed");

        assertEquals(1, patched.size(), "one object, one PATCH: " + patched);
        assertTrue(patched.get(0).contains("from-a"), "the first file's value must be in it: " + patched);
        assertTrue(patched.get(0).contains("from-b"), "and so must the second's: " + patched);
    }

    @Test
    void anObjectSplitAcrossFilesIsCreatedInOneRequest(@TempDir Path dir) throws Exception {
        // Creating used to POST whatever the first file held and then PATCH the rest onto it, so the object
        // existed in a partial state in between — and a failure there left it that way.
        objectExists = false;
        writeFragments(dir);

        assertEquals(0, deploy(dir, "--create-if-absent"), "the deploy should succeed");

        assertEquals(1, created.size(), "one object, one POST: " + created);
        assertEquals(0, patched.size(), "and nothing patched on top of it: " + patched);
        assertTrue(created.get(0).contains("from-a") && created.get(0).contains("from-b"),
                "the created object carries both files: " + created);
    }

    @Test
    void aTreeHoldingADeployableSchemaObjectReachesTheApplyLoop(@TempDir Path dir) throws Exception {
        // A deployable SchemaType object is the one apply unit that does not come out of grouping: it is
        // wrapped one at a time, in an immutable single-fragment list. The group sort tried to reorder that
        // list in place, so every live deploy of a tree holding extension schema died with an
        // UnsupportedOperationException before its first request. Offline runs never showed it — they print
        // the apply order and return before the units are assembled — and no live test deployed such a tree.
        objectExists = false;
        writeFragments(dir);
        Files.writeString(dir.resolve("schema.xml"), schemaObject(), StandardCharsets.UTF_8);

        assertEquals(0, deploy(dir, "--create-if-absent"), "the deploy should succeed");

        assertTrue(createdTypes.contains("schemas"), "the schema object is applied: " + createdTypes);
        assertTrue(createdTypes.contains("roles"), "and so is the object beside it: " + createdTypes);
    }

    // --- helpers ---

    /** An exported extension schema — a SchemaType object, so a deployable unit in its own right. */
    private static String schemaObject() {
        return "<schema xmlns=\"" + NS + "\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" "
                + "oid=\"dddddddd-0000-0000-0f00-000000000004\">"
                + "<name>union-test-schema</name>"
                + "<definition><xsd:schema elementFormDefault=\"qualified\" "
                + "targetNamespace=\"http://is4it.de/midpoint/v1/unionTest\">"
                + "<xsd:complexType name=\"UnionThingType\">"
                + "<xsd:sequence><xsd:element name=\"label\" type=\"xsd:string\"/></xsd:sequence>"
                + "</xsd:complexType></xsd:schema></definition></schema>";
    }

    private static void writeFragments(Path dir) throws IOException {
        Files.writeString(dir.resolve("a.xml"), role("<subtype>from-a</subtype>"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.xml"), role("<subtype>from-b</subtype>"), StandardCharsets.UTF_8);
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>Split Role</name>" + body + "</role>";
    }

    private int deploy(Path dir, String... extra) {
        List<String> args = new ArrayList<>(List.of("deploy", dir.toString(), "--url", host,
                "-u", "administrator", "-p", "x", "--yes", "--quiet", "--no-live-schema"));
        args.addAll(List.of(extra));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            return new CommandLine(new Main()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if ("POST".equals(method) && path.endsWith("/search")) {
            respond(exchange, 200, objectExists ? searchResult() : emptyResult());
        } else if ("POST".equals(method)) {
            created.add(body);
            createdTypes.add(path.substring(path.lastIndexOf('/') + 1));
            exchange.getResponseHeaders().add("Location", host + "/ws/rest/roles/" + OID);
            respond(exchange, 201, "");
        } else if ("PATCH".equals(method)) {
            patched.add(body);
            respond(exchange, 204, "");
        } else {
            respond(exchange, 204, "");
        }
    }

    /** The object as the server holds it: the same role, carrying neither file's subtype yet. */
    private static String searchResult() throws IOException {
        return objectList(role(""));
    }

    private static String emptyResult() throws IOException {
        return objectList(null);
    }

    private static String objectList(String objectXml) throws IOException {
        try {
            ObjectListType list = new ObjectListType();
            if (objectXml != null) {
                list.getObject().add((ObjectType) prismContext.parseObject(objectXml).asObjectable());
            }
            return prismContext.xmlSerializer().serializeRealValue(list, new QName(NS_API, "objectList"));
        } catch (Exception e) {
            // Failing the request beats answering "no such object": absent is a meaningful answer here, so a
            // broken stub would otherwise disguise itself as the case under test.
            throw new IOException("stub could not build the search response", e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
