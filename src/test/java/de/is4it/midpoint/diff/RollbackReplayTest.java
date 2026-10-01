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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code midgie rollback} replaying a plan against a stub server: the staleness refusal and {@code --force}, a
 * dry run, an object deployed without a recorded version, and a replay that fails part way and is run again.
 * Before this only the live integration test replayed a plan, and CI skips it.
 */
class RollbackReplayTest {

    private static final String A = "aaaaaaaa-0000-0000-0000-00000000000a";
    private static final String B = "bbbbbbbb-0000-0000-0000-00000000000b";

    private HttpServer server;
    private String host;
    private String rest;
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final List<String> patched = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest/roles", this::handle);
        server.start();
        host = "http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint";
        rest = String.valueOf(new MidpointRestClient(URI.create(host), "u", "p", false).host());
        versions.put(A, 3);
        versions.put(B, 7);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anObjectChangedSinceTheDeployIsRefusedUnlessForced(@TempDir Path dir) throws IOException {
        File run = plan(dir, version(A, 2), version(B, 7));

        Result refused = rollback(run);
        assertEquals(2, refused.exit(), refused.err());
        assertTrue(refused.err().contains("changed since the deploy: roles/" + A), refused.err());
        assertTrue(patched.isEmpty(), "nothing may be sent: " + patched);

        Result forced = rollback(run, "--force");
        assertEquals(0, forced.exit(), forced.err());
        assertEquals(List.of(B, A), patched, "both restored, in the plan's order");
    }

    @Test
    void aDryRunSendsNothingAndExitsOne(@TempDir Path dir) throws IOException {
        Result result = rollback(plan(dir, version(A, 3), version(B, 7)), "--dry-run");

        assertEquals(1, result.exit(), result.err());
        assertTrue(result.out().contains("would-restore"), result.out());
        assertTrue(patched.isEmpty(), patched.toString());
    }

    @Test
    void anObjectWithoutARecordedVersionNeedsForce(@TempDir Path dir) throws IOException {
        // It used to be replayed with a warning --quiet hid, as if it were known not to have changed.
        Result result = rollback(plan(dir, version(A, 3), "<object type=\"roles\" oid=\"" + B
                + "\" name=\"b\" action=\"patched\"/>"), "--quiet");

        assertEquals(2, result.exit(), result.err());
        assertTrue(result.err().contains("no version was recorded"), result.err());
        assertTrue(patched.isEmpty(), patched.toString());
    }

    @Test
    void aRollbackThatFailedPartWaySendsOnlyTheRestWhenRunAgain(@TempDir Path dir) throws IOException {
        // The objects it did restore have new versions now, which used to read as someone else's change, so the
        // second run needed --force — and --force switched the check off for the object that had failed too.
        File run = plan(dir, version(A, 3), version(B, 7));
        failing.add(A);

        Result first = rollback(run);
        assertEquals(2, first.exit(), first.err());
        assertEquals(List.of(B), patched);

        failing.clear();
        patched.clear();
        Result second = rollback(run);
        assertEquals(0, second.exit(), second.out() + second.err());
        assertEquals(List.of(A), patched, "only the one that failed is sent again");
        assertTrue(second.out().contains("restored by an earlier run"), second.out());

        patched.clear();
        Result third = rollback(run);
        assertEquals(0, third.exit(), third.err());
        assertTrue(third.out().contains("Nothing left to roll back"), third.out());
        assertTrue(patched.isEmpty(), patched.toString());
    }

    @Test
    void aPlanOfAnotherDeploymentIsNamedWhenThisOneCannotBeTold(@TempDir Path dir) throws IOException {
        // Outside a repository there is no identity to compare: not a refusal, since a pod with the net on a
        // volume must still be able to roll back, but said whatever --quiet asks.
        File run = plan(dir, "<deployment>abc123/config</deployment>", version(A, 3), version(B, 7));
        Path notARepository = Files.createDirectories(dir.resolve("elsewhere"));

        Result result = rollback(run, notARepository, "--quiet");

        assertEquals(0, result.exit(), result.err());
        assertTrue(result.err().contains("belongs to deployment abc123/config")
                && result.err().contains("only the server was checked"), result.err());
    }

    @Test
    void anUnfinishedPlanIsRefusedInRollbacksOwnWords(@TempDir Path dir) throws IOException {
        File run = plan(dir, version(A, 3), version(B, 7));
        PlanStore.markIncomplete(run);

        Result result = rollback(run);

        assertEquals(2, result.exit(), result.err());
        assertTrue(result.err().contains("rollback plan") && result.err().contains("never finished"), result.err());
        assertFalse(result.err().contains("--plan"), "not the wording of --apply-plan: " + result.err());
    }

    // --- helpers ---

    private static String version(String oid, int version) {
        return "<object type=\"roles\" oid=\"" + oid + "\" name=\"r\" action=\"patched\" version=\"" + version
                + "\"/>";
    }

    /** A run directory with a manifest naming this server, and an inverse patch per object: B first, then A. */
    private File plan(Path dir, String... manifestParts) throws IOException {
        File run = new File(dir.toFile(), "20260101-000000-000");
        Files.createDirectories(run.toPath());
        Files.writeString(new File(run, RollbackPlan.MANIFEST).toPath(), PlanStore.markGenerated(
                "<rollbackManifest><server>" + rest + "</server>" + String.join("", manifestParts)
                        + "</rollbackManifest>"), StandardCharsets.UTF_8);
        PlanStore.write(run, 1, 1, "roles", B, "b", PlanStore.Kind.PATCH, inverse(B));
        PlanStore.write(run, 2, 1, "roles", A, "a", PlanStore.Kind.PATCH, inverse(A));
        return run;
    }

    private static String inverse(String oid) {
        return "<objectModification xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/api-types-3\">"
                + "<oid>" + oid + "</oid></objectModification>";
    }

    private Result rollback(File run, String... extra) {
        return rollback(run, null, extra);
    }

    private Result rollback(File run, Path tree, String... extra) {
        List<String> args = new ArrayList<>();
        args.add("rollback");
        if (tree != null) {
            args.add(tree.toString());
        }
        args.addAll(List.of("--url", host, "-u", "u", "-p", "p", "--yes", "--rollback-plan", run.getPath()));
        args.addAll(List.of(extra));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new Main()).execute(args.toArray(new String[0]));
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String oid = path.substring(path.lastIndexOf('/') + 1);
        exchange.getRequestBody().readAllBytes();
        if ("GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                    + " oid=\"" + oid + "\" version=\"" + versions.getOrDefault(oid, 1) + "\"><name>r</name></role>");
        } else if ("PATCH".equals(exchange.getRequestMethod())) {
            if (failing.contains(oid)) {
                respond(exchange, 500, "boom");
                return;
            }
            patched.add(oid);
            versions.merge(oid, 1, Integer::sum);
            respond(exchange, 204, "");
        } else {
            respond(exchange, 405, "");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
