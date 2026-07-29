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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code init} against a stub server: creating the {@code midgie-state} object, its idempotence,
 * and — the contract that spans two classes — that the policy file {@code --write-policy} generates is one
 * {@link Policy#load} accepts.
 */
class InitCommandTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private HttpServer server;
    private String url;

    /** Requests the stub received, so a test can assert what init did rather than only what it printed. */
    private final List<String> requests = new ArrayList<>();

    /** Whether the state object already exists (drives the search response). */
    private volatile boolean stateExists;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/midpoint/ws/rest", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/midpoint";
        requests.clear();
        stateExists = false;
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createStateCreatesTheStateObjectAndIsIdempotent(@TempDir Path dir) {
        Result created = run("init", "--create-state", "--url", url, "-u", "administrator", "-p", "s3cret");

        assertEquals(0, created.exit, created.err);
        assertTrue(requests.contains("POST /midpoint/ws/rest/lookupTables"),
                "the state object must be created: " + requests);
        assertTrue(created.out.contains(SyncState.TABLE_NAME), created.out);

        stateExists = true;
        requests.clear();
        Result again = run("init", "--create-state", "--url", url, "-u", "administrator", "-p", "s3cret");

        assertEquals(0, again.exit, again.err);
        assertFalse(requests.contains("POST /midpoint/ws/rest/lookupTables"),
                "a second init must change nothing: " + requests);
        assertTrue(again.out.contains("already exists"), again.out);
    }

    @Test
    void initWithoutAStepPrintsHelpAndChangesNothing() {
        // Every step writes something. Creating the state object because no flag was given would put an
        // object on someone's server for a command they may only have been reading the help of — and a
        // pipeline whose '--create-state' went missing must fail rather than pass having done nothing.
        Result result = run("init", "--url", url, "-u", "administrator", "-p", "s3cret");

        assertEquals(2, result.exit, result.out);
        assertTrue(requests.isEmpty(), "the server must not be touched: " + requests);
        assertTrue(result.err.contains("--create-state"), result.err);
        assertTrue(result.err.contains("Usage:"), "the help must be printed: " + result.err);
    }

    @Test
    void writingAPolicyDoesNotCreateTheStateObject(@TempDir Path dir) throws Exception {
        // The two steps are independent: seeding a policy file says nothing about wanting a server opted
        // into tracking.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);

        Result result = run("init", tree.toString(), "--url", url, "-u", "administrator", "-p", "s3cret",
                "--write-policy", dir.resolve("midgie.policy").toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(Files.exists(dir.resolve("midgie.policy")), "the policy file must be written");
        assertFalse(requests.contains("POST /midpoint/ws/rest/lookupTables"),
                "no state object without --create-state: " + requests);
    }

    @Test
    void theSeededPolicyIsOneThatPolicyLoadAccepts(@TempDir Path dir) throws Exception {
        // The round trip is the point: init tells the operator to pass the file to 'deploy --policy', so a
        // file it writes and midgie then rejects would break the command line midgie itself suggested.
        // Object names here carry the characters the selector grammar gives its own meaning to.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod / LDAP *"), StandardCharsets.UTF_8);
        Files.writeString(tree.resolve("org.xml"), org("ou=people"), StandardCharsets.UTF_8);
        Path policy = dir.resolve("midgie.policy");

        Result result = run("init", tree.toString(), "--url", url, "-u", "administrator", "-p", "s3cret",
                "--write-policy", policy.toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(Files.exists(policy), "the policy file must be written");
        String content = Files.readString(policy, StandardCharsets.UTF_8);
        assertTrue(content.contains("schemaHandling/objectType"),
                "a keyed container that carries its whole key is suggested:\n" + content);

        Policy loaded = Policy.load(policy.toFile());
        StrategyOptions.Resolved resolved = loaded.resolveFor("ResourceType", "Prod / LDAP *",
                "11111111-1111-1111-1111-111111111111", baseline());
        assertTrue(resolved.multiValue.isMerge(
                        com.evolveum.midpoint.prism.path.ItemPath.fromString("schemaHandling/objectType")),
                "and the rule applies to the object it was written for:\n" + content);
    }

    @Test
    void anExistingPolicyFileIsNeverOverwrittenAndNothingIsCreatedFirst(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("midgie.policy");
        Files.writeString(policy, "# mine\n", StandardCharsets.UTF_8);

        Result result = run("init", dir.toString(), "--url", url, "-u", "administrator", "-p", "s3cret",
                "--write-policy", policy.toString());

        assertEquals(2, result.exit, result.err);
        assertEquals("# mine\n", Files.readString(policy, StandardCharsets.UTF_8), "left untouched");
        assertTrue(requests.isEmpty(),
                "a rejected invocation must not have changed the server first: " + requests);
    }

    private static StrategyOptions.Resolved baseline() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy.REAL_VALUE,
                DiffEngine.MultiValueStrategy.replaceAll(), false);
    }

    /** A resource whose schemaHandling/objectType carries the whole kind+intent natural key. */
    private static String resource(String name) {
        return "<resource xmlns=\"" + NS + "\" oid=\"11111111-1111-1111-1111-111111111111\">\n"
                + "  <name>" + name + "</name>\n"
                + "  <schemaHandling><objectType>\n"
                + "    <kind>account</kind><intent>default</intent>\n"
                + "  </objectType></schemaHandling>\n"
                + "</resource>";
    }

    private static String org(String name) {
        return "<org xmlns=\"" + NS + "\" oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>" + name + "</name>\n</org>";
    }

    // --- stub server ---

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        requests.add(method + " " + path);

        if (path.endsWith("/lookupTables/search")) {
            respond(exchange, 200, stateExists
                    ? "<objectList xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/api-types-3\">"
                            + "<object xmlns=\"" + NS + "\" xsi:type=\"LookupTableType\""
                            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                            + " oid=\"33333333-3333-3333-3333-333333333333\">"
                            + "<name>" + SyncState.TABLE_NAME + "</name></object></objectList>"
                    : "<objectList xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/api-types-3\"/>");
        } else if (path.contains("/lookupTables/33333333")) {
            respond(exchange, 200, "<lookupTable xmlns=\"" + NS + "\""
                    + " oid=\"33333333-3333-3333-3333-333333333333\"><name>" + SyncState.TABLE_NAME
                    + "</name><lifecycleState>active</lifecycleState></lookupTable>");
        } else if ("POST".equals(method) && path.endsWith("/lookupTables")) {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1/midpoint/ws/rest/lookupTables/33333333-3333-3333-3333-333333333333");
            respond(exchange, 201, "");
        } else if (path.endsWith("/nodes") || path.endsWith("/schemas")) {
            respond(exchange, 200, "<objectList xmlns=\"http://midpoint.evolveum.com/xml/ns/public/"
                    + "common/api-types-3\"/>");
        } else {
            respond(exchange, 404, "not found");
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

    // --- CLI harness ---

    private static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = Main.commandLine().execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }
}
