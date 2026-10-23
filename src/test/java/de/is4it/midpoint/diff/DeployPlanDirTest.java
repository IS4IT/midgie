/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@code deploy --plan} writes, and when it may replace what is there, against a stub server.
 *
 * <p>Iterating on a tree means planning it again and again. The default plan directory is midgie's own and is
 * replaced on every run, so that loop leaves no stale directories behind; a directory the operator named may
 * hold a plan under review, and is replaced only under {@code --overwrite}.
 */
class DeployPlanDirTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String OID = "cccccccc-0000-0000-0f00-000000000005";

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;
    private final List<String> patched = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/roles", this::handle);
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theDefaultDirectoryIsReplacedOnEveryRun(@TempDir Path dir) throws Exception {
        writeTree(dir);
        File plan = defaultPlan(dir);

        Result first = deploy(dir, "--plan");
        assertEquals(1, first.exit, first.err);
        assertEquals(1, planFiles(plan).size(), "one object, one plan file");
        assertTrue(first.out.contains("Apply with: midgie deploy " + dir + " --apply-plan --url <url>"),
                "it says how to replay what it wrote:\n" + first.out);

        Result second = deploy(dir, "--plan");
        assertEquals(1, second.exit, second.err);
        assertTrue(second.err.contains("replaced the previous plan in " + plan), second.err);
        assertEquals(1, planFiles(plan).size(), "the old plan is gone, not mixed in");
        assertTrue(patched.isEmpty(), "--plan writes nothing to the server: " + patched);
        if (plan.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(plan.toPath())),
                    "a create entry can carry a password, so the directory is the owner's alone");
        }
    }

    @Test
    void aNamedDirectoryIsReplacedOnlyWithOverwrite(@TempDir Path dir) throws Exception {
        Path tree = dir.resolve("tree");
        writeTree(tree);
        String plan = dir.resolve("plan").toString();

        assertEquals(1, deploy(tree, "--plan", plan).exit);

        Result again = deploy(tree, "--plan", plan);
        assertEquals(2, again.exit, again.err);
        assertTrue(again.err.contains("already holds a plan") && again.err.contains("--overwrite"), again.err);

        Result overwritten = deploy(tree, "--plan", plan, "--overwrite");
        assertEquals(1, overwritten.exit, overwritten.err);
        assertTrue(overwritten.err.contains("replaced the previous plan in " + plan), overwritten.err);
        assertTrue(overwritten.out.contains("Apply with: midgie deploy --apply-plan " + plan), overwritten.out);
    }

    @Test
    void applyPlanWithoutDirReplaysTheDefaultPlanOfTheTree(@TempDir Path dir) throws Exception {
        writeTree(dir);
        assertEquals(1, deploy(dir, "--plan").exit);

        Result applied = deploy(dir, "--apply-plan", "--yes", "--no-rollback-plan");
        assertEquals(0, applied.exit, applied.err);
        assertEquals(1, patched.size(), "the planned PATCH is sent: " + patched);
        assertTrue(patched.get(0).contains("from-plan"), patched.get(0));
    }

    // --- helpers ---

    private static void writeTree(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("role.xml"), role("<subtype>from-plan</subtype>"), StandardCharsets.UTF_8);
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>Planned Role</name>" + body + "</role>";
    }

    private static File defaultPlan(Path tree) {
        return PlanStore.defaultDir(DeploymentIdentity.of(tree.toFile(), null).key(), tree.toFile());
    }

    private static List<String> planFiles(File plan) {
        String[] names = plan.list((d, name) -> name.endsWith(".create.xml") || name.endsWith(".patch.xml"));
        return (names != null) ? List.of(names) : List.of();
    }

    private Result deploy(Path tree, String... extra) {
        List<String> args = new ArrayList<>(List.of("deploy", tree.toString(), "--url", host,
                "-u", "administrator", "-p", "x", "--no-live-schema"));
        args.addAll(List.of(extra));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = Main.commandLine().execute(args.toArray(new String[0]));
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if ("POST".equals(method) && path.endsWith("/search")) {
            respond(exchange, 200, objectList(role("")));
        } else if ("PATCH".equals(method)) {
            patched.add(body);
            respond(exchange, 204, "");
        } else {
            respond(exchange, 204, "");
        }
    }

    /** The server's copy: the same role, without the subtype the tree adds. */
    private static String objectList(String objectXml) throws IOException {
        try {
            ObjectListType list = new ObjectListType();
            list.getObject().add((ObjectType) prismContext.parseObject(objectXml).asObjectable());
            return prismContext.xmlSerializer().serializeRealValue(list, new QName(NS_API, "objectList"));
        } catch (Exception e) {
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
