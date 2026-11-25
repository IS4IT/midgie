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
import java.util.concurrent.TimeUnit;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code --scope full} applies only when the run holds every file of the object. Seen where it matters — in the
 * requests {@code deploy} sends: a partial run under full scope used to delete whatever the files it left out
 * declare, since full scope reads every item the run's files do not mention as removed.
 */
class DeployScopeTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String OID = "cccccccc-0000-0000-0f00-0000000000f5";

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;
    private final List<String> patched = new CopyOnWriteArrayList<>();
    private String stderr = "";

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
    void aPartialRunUnderFullScopeDeletesNothingTheOtherFilesDeclare(@TempDir Path tree) throws Exception {
        gitInit(tree);
        Path a = write(tree, "roles/a.xml", role("<subtype>from-a</subtype>"));
        write(tree, "extra/b.xml", role("<description>from-b</description>"));

        assertEquals(0, deploy(a, "--scope", "full"), stderr);

        assertTrue(patched.isEmpty(), "nothing may be deleted, nothing differs otherwise: " + patched);
        assertTrue(stderr.contains("deployed with --scope present"), stderr);
        assertTrue(stderr.contains("extra/b.xml"), stderr);
    }

    @Test
    void aCompleteRunUnderFullScopeStillDeletesWhatNoFileDeclares(@TempDir Path tree) throws Exception {
        gitInit(tree);
        write(tree, "roles/a.xml", role("<subtype>from-a</subtype>"));
        write(tree, "extra/b.xml", role("<description>from-b</description>"));

        assertEquals(0, deploy(tree, "--scope", "full"), stderr);

        assertEquals(1, patched.size(), "full scope removes the live-only item: " + patched);
        assertTrue(patched.get(0).contains("live-only"), patched.get(0));
        assertTrue(!patched.get(0).contains("from-b"), "what a file declares stays: " + patched.get(0));
        assertTrue(!stderr.contains("--scope present"), stderr);
    }

    @Test
    void anotherStagesFileDoesNotHoldBackFullScope(@TempDir Path tree) throws Exception {
        gitInit(tree);
        write(tree, "common/a.xml", role("<subtype>from-a</subtype>"));
        write(tree, "stages/prod/b.xml", role("<description>from-b</description>"));
        write(tree, "stages/dev/b.xml", role("<description>dev only</description>"));

        assertEquals(0, deploy(List.of(tree.resolve("common"), tree.resolve("stages/prod")),
                "--scope", "full", "--stages", "stages/*"), stderr);

        assertEquals(1, patched.size(), "full scope applies, dev's file is not this run's: " + patched);
        assertTrue(patched.get(0).contains("live-only"), patched.get(0));
    }

    @Test
    void withoutStageFoldersAnotherStagesFileHoldsBackFullScope(@TempDir Path tree) throws Exception {
        gitInit(tree);
        write(tree, "common/a.xml", role("<subtype>from-a</subtype>"));
        write(tree, "stages/prod/b.xml", role("<description>from-b</description>"));
        write(tree, "stages/dev/b.xml", role("<description>dev only</description>"));

        assertEquals(0, deploy(List.of(tree.resolve("common"), tree.resolve("stages/prod")),
                "--scope", "full"), stderr);

        assertTrue(patched.isEmpty(), "nothing deleted while dev's file counts as missing: " + patched);
        assertTrue(stderr.contains("stages/dev/b.xml"), stderr);
    }

    @Test
    void aPartialRunUnderPresentScopeOnlyNotesTheSplit(@TempDir Path tree) throws Exception {
        gitInit(tree);
        Path a = write(tree, "roles/a.xml", role("<subtype>from-a</subtype>"));
        write(tree, "extra/b.xml", role("<description>from-b</description>"));

        assertEquals(0, deploy(a), stderr);

        assertTrue(patched.isEmpty(), patched.toString());
        assertTrue(stderr.contains("also has 1 file(s) outside this run"), stderr);
        assertTrue(!stderr.contains("--scope present"), stderr);
    }

    // --- helpers ---

    /** The role as the server holds it: both files' items, plus one no file declares. */
    private static String live() {
        return role("<description>from-b</description><documentation>live-only</documentation>"
                + "<subtype>from-a</subtype>");
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>Scoped Role</name>" + body + "</role>";
    }

    private static Path write(Path tree, String relative, String content) throws IOException {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void gitInit(Path dir) throws Exception {
        Process process = new ProcessBuilder("git", "init", "-q", ".").directory(dir.toFile())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "git not available");
    }

    private int deploy(Path path, String... extra) {
        return deploy(List.of(path), extra);
    }

    private int deploy(List<Path> paths, String... extra) {
        List<String> args = new ArrayList<>(List.of("deploy"));
        paths.forEach(path -> args.add(path.toString()));
        args.addAll(List.of("--url", host, "-u", "administrator", "-p", "x", "--yes", "--no-live-schema",
                "--no-rollback-plan"));
        args.addAll(List.of(extra));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new CommandLine(new Main()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            stderr = err.toString(StandardCharsets.UTF_8);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("POST".equals(method) && path.endsWith("/search")) {
            respond(exchange, 200, objectList(live()));
        } else if ("PATCH".equals(method)) {
            patched.add(body);
            respond(exchange, 204, "");
        } else {
            respond(exchange, 204, "");
        }
    }

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
