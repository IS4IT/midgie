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

        // The seeded file must not pin equivalence. '--equivalence auto' already matches by natural key
        // per container, from the same evidence this file was seeded from; an object-wide
        // 'equivalence=natural-keys' line here would widen that to containers whose key nothing fills in.
        assertFalse(content.lines().anyMatch(l -> !l.startsWith("#") && l.contains("equivalence=")),
                "the seeded policy must leave equivalence to the auto default:\n" + content);
        assertTrue(resolved.equivalence.isAuto(),
                "so an object it names still resolves to auto:\n" + content);
    }

    @Test
    void writePropertiesNeedsNoServerAndProducesAFileMidgieAccepts(@TempDir Path dir) throws Exception {
        // It writes the file that holds the URL, so requiring a URL to write it would be a circle. And the
        // template is generated from the command model, so this is the guard that the model and the config
        // reader agree: every key it offers has to be one ConfigDefaults will take.
        Path template = dir.resolve("midgie.properties");

        Result result = run("init", "--write-properties", template.toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(requests.isEmpty(), "no server was needed: " + requests);

        String written = Files.readString(template, StandardCharsets.UTF_8);
        assertTrue(written.contains("#url ="), written);
        // Each line carries what the option does when nobody sets it, so uncommenting one changes nothing
        // until its value is edited — and the template doubles as a record of the defaults.
        assertTrue(written.contains("#insecure = false"), "a flag's default comes from its field: " + written);
        assertTrue(written.contains("#scope = present"), written);
        assertTrue(written.contains("#timeout = 30"), written);
        assertFalse(written.contains("\n#yes = "), "a file may not waive the confirmation: " + written);
        assertFalse(written.contains("\n#prune = "), "nor decide to delete: " + written);

        // Uncomment everything and hand it back: what midgie writes, midgie must read.
        Path uncommented = dir.resolve("uncommented.properties");
        Files.writeString(uncommented, uncomment(written), StandardCharsets.UTF_8);
        System.setProperty(ConfigDefaults.PATH_PROPERTY, uncommented.toString());
        try {
            ConfigDefaults.load(name -> null).validate(Main.commandLine());
        } finally {
            System.clearProperty(ConfigDefaults.PATH_PROPERTY);
        }
    }

    @Test
    void writingIntoATreeProducesTheAllowlistedTemplate(@TempDir Path dir) throws Exception {
        // Which template to write is decided by how midgie would READ the path, not by a flag: a file named
        // .midgie.properties outside $HOME is a tree file. Offering it keys a tree file may not set would
        // produce a template that errors the moment a line is uncommented.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Path target = tree.resolve(".midgie.properties");

        Result result = run("init", "--write-properties", target.toString());
        assertEquals(0, result.exit, result.err);

        String written = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(written.contains("#rollback-plan ="), "the key a deployment most needs: " + written);
        assertTrue(written.contains("#scope = present"), written);
        assertTrue(written.contains("#midgie.deploy.policy ="), written);

        // The keys that decide where credentials go are omitted, not commented out -- a commented '#url ='
        // would break the uncomment-everything contract and read as an invitation besides.
        for (String forbidden : new String[] {"#url =", "#user =", "#password =", "#insecure =", "#cacert =",
                "#no-rollback-plan ="}) {
            assertFalse(written.contains(forbidden), forbidden + " must not be offered here:\n" + written);
        }
        // But the header still says they exist and where they belong, so nobody concludes they are gone.
        assertTrue(written.contains("NOT settable here"), written);
        assertTrue(written.contains("MIDPOINT_PASSWORD"), written);

        // Uncomment everything and load it AS a tree file: what midgie writes, midgie must read -- and this
        // is the guard that the template's filter and the allowlist it validates against stay one list.
        Path uncommented = tree.resolve(".midgie.properties.all");
        Files.writeString(uncommented, uncomment(written), StandardCharsets.UTF_8);
        ConfigDefaults.load(name -> null, dir.resolve("absent-site").toFile(),
                dir.resolve("absent-user").toFile(), uncommented.toFile()).validate(Main.commandLine());
    }

    @Test
    void aPlainlyNamedFileInATreeStillGetsTheFullTemplate(@TempDir Path dir) throws Exception {
        // midgie only reads '.midgie.properties' as a tree file, so a differently-named one in the same
        // folder is an ordinary config file and must not be stripped.
        Path target = Files.createDirectories(dir.resolve("config")).resolve("midgie.properties");

        Result result = run("init", "--write-properties", target.toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("#url ="),
                "only the tree-local name triggers the stripped template");
    }

    @Test
    void reRunningWritePolicyRefreshesOnlyTheMarkedBlock(@TempDir Path dir) throws Exception {
        // Blind appending degrades: a rule you narrowed no longer matches by text, so the broad one comes
        // back beside it, and a container dropped from the tree keeps its rule for ever. Replacing a
        // delimited block keeps the suggestions equal to what the tree implies now.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);
        Path policy = dir.resolve("midgie.policy");
        assertEquals(0, seedPolicy(tree, policy).exit);

        // Write a rule of your own above the block, and edit something inside it.
        String edited = "# mine\nResourceType/Prod/schemaHandling/objectType  multivalue=replace\n\n"
                + Files.readString(policy, StandardCharsets.UTF_8).replace("#   key:", "#   MY EDIT key:");
        Files.writeString(policy, edited, StandardCharsets.UTF_8);

        // A second object appears in the tree; re-run.
        Files.writeString(tree.resolve("resource2.xml"), resource("Test"), StandardCharsets.UTF_8);
        Result again = seedPolicy(tree, policy);
        assertEquals(0, again.exit, again.err);

        String after = Files.readString(policy, StandardCharsets.UTF_8);
        assertTrue(after.contains("# mine"), "a rule outside the block survives:\n" + after);
        assertTrue(after.contains("ResourceType/Test"), "the new object is picked up:\n" + after);
        assertFalse(after.contains("MY EDIT"), "an edit inside the block is replaced, as the marker warns");
        assertEquals(1, countOf(after, "begin midgie suggestions"),
                "re-running must not stack a second block:\n" + after);
    }

    @Test
    void aRuleAboveTheBlockWinsTheTieAgainstTheGeneratedOne(@TempDir Path dir) throws Exception {
        // This is what makes the block go at the END: policy ties break to the earlier line, so what you
        // wrote beats what init suggests for the same selector. Asserted through Policy, not by reading
        // the file, because the ordering only matters if resolution actually honours it.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);
        Path policy = dir.resolve("midgie.policy");
        assertEquals(0, seedPolicy(tree, policy).exit);

        Files.writeString(policy, "ResourceType/Prod/schemaHandling/objectType  multivalue=replace\n\n"
                + Files.readString(policy, StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        StrategyOptions.Resolved resolved = Policy.load(policy.toFile())
                .resolveFor("ResourceType", "Prod", "11111111-1111-1111-1111-111111111111", baseline());
        assertFalse(resolved.multiValue.isMerge(
                        com.evolveum.midpoint.prism.path.ItemPath.fromString("schemaHandling/objectType")),
                "the hand-written replace must beat the generated merge");
    }

    @Test
    void aFileWithNoMarkersGetsTheBlockAppended(@TempDir Path dir) throws Exception {
        // Migration: a policy written before the block existed must not be clobbered.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);
        Path policy = dir.resolve("midgie.policy");
        Files.writeString(policy, "# hand-written, from before\nUserType/*  scope=full\n", StandardCharsets.UTF_8);

        Result result = seedPolicy(tree, policy);

        assertEquals(0, result.exit, result.err);
        String after = Files.readString(policy, StandardCharsets.UTF_8);
        assertTrue(after.startsWith("# hand-written, from before"), "the old content stays first:\n" + after);
        assertTrue(after.contains("begin midgie suggestions"), after);
        assertTrue(result.out.contains("Appended"), result.out);
    }

    @Test
    void aStartMarkerWithNoEndIsRefused(@TempDir Path dir) throws Exception {
        // Guessing where the generated section stops would either eat hand-written rules or duplicate the
        // block. Neither is recoverable by looking at the file afterwards, so refuse and say so.
        Path tree = Files.createDirectories(dir.resolve("config"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);
        Path policy = dir.resolve("midgie.policy");
        Files.writeString(policy, "# --- begin midgie suggestions — regenerated by 'init --write-policy'; "
                + "edits here are lost ---\nResourceType/X  scope=full\n", StandardCharsets.UTF_8);

        Result result = seedPolicy(tree, policy);

        assertEquals(2, result.exit, result.out);
        assertTrue(result.err.contains("no matching end marker"), result.err);
    }

    private Result seedPolicy(Path tree, Path policy) {
        return run("init", tree.toString(), "--url", url, "-u", "administrator", "-p", "s3cret",
                "--write-policy", policy.toString());
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void aDirectoryTargetGetsTheNameMidgieWouldReadThere(@TempDir Path dir) throws Exception {
        // The point is not typing convenience: guessing 'midgie.properties' for a tree would write a file
        // midgie never reads, which is the failure this tool works hardest to avoid.
        Path tree = Files.createDirectories(dir.resolve("config"));

        Result result = run("init", "--write-properties", tree.toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(Files.exists(tree.resolve(".midgie.properties")), "a tree gets the dotted, read-able name");
        assertTrue(Files.readString(tree.resolve(".midgie.properties"), StandardCharsets.UTF_8)
                .contains("NOT settable here"), "and therefore the tree-local template");
    }

    @Test
    void writingToStdoutLeavesThePayloadAloneOnStdout(@TempDir Path dir) {
        // '-' means the payload owns stdout, so the "Wrote ..." note has to move to stderr -- otherwise
        // 'init --write-properties - | diff - ~/.midgie.properties' compares a line midgie itself added.
        Result result = run("init", "--write-properties", "-");

        assertEquals(0, result.exit, result.err);
        assertTrue(result.out.startsWith("# midgie.properties"), "stdout is the template: " + result.out);
        assertFalse(result.out.contains("Wrote "), "no status line in the payload: " + result.out);
        assertTrue(result.err.contains("Wrote stdout"), "the note names stdout, not '-': " + result.err);
        assertFalse(Files.exists(Path.of("-")), "'-' must never become a file on disk");
    }

    @Test
    void aDirectoryTargetUsesTheConventionalNameForPolicyAndCacert(@TempDir Path dir) throws Exception {
        // --policy and --cacert take any path, so these names are a convenience rather than something
        // midgie derives; the test pins them so the help text and the behaviour cannot drift apart.
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Files.writeString(tree.resolve("resource.xml"), resource("Prod"), StandardCharsets.UTF_8);

        Result result = run("init", tree.toString(), "--url", url, "-u", "administrator", "-p", "s3cret",
                "--write-policy", tree.toString());

        assertEquals(0, result.exit, result.err);
        assertTrue(Files.exists(tree.resolve(InitCommand.DEFAULT_POLICY_NAME)),
                "expected " + InitCommand.DEFAULT_POLICY_NAME + " in " + tree);
    }

    private static String uncomment(String template) {
        return template.lines()
                .filter(line -> line.matches("#[a-z][^ ]* =.*"))
                .map(line -> line.substring(1))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void writeCacertNeedsAUrlToFetchFrom(@TempDir Path dir) {
        Result result = run("init", "--write-cacert", dir.resolve("ca.pem").toString());

        assertEquals(2, result.exit, result.out);
        assertTrue(result.err.contains("--url"), result.err);
    }

    @Test
    void neitherNewFileIsEverOverwritten(@TempDir Path dir) throws Exception {
        for (String option : new String[] {"--write-properties", "--write-cacert"}) {
            Path existing = dir.resolve("mine" + option + ".txt");
            Files.writeString(existing, "mine\n", StandardCharsets.UTF_8);

            Result result = run("init", option, existing.toString(), "--url", url, "-u", "a", "-p", "b");

            assertEquals(2, result.exit, option + ": " + result.out);
            assertEquals("mine\n", Files.readString(existing, StandardCharsets.UTF_8), option);
            assertTrue(result.err.contains("refusing to overwrite"), result.err);
        }
    }

    @Test
    void aRefusedStepChangesNothingOnTheServerFirst(@TempDir Path dir) throws Exception {
        // One run can do several steps, and the refusals all happen before any of them. Without that, a
        // mistyped path exited 2 having already created the state object, so the operator saw an error for
        // a command that had in fact changed the server.
        Path existing = dir.resolve("midgie.properties");
        Files.writeString(existing, "# mine\n", StandardCharsets.UTF_8);

        Result result = run("init", "--create-state", "--write-properties", existing.toString(),
                "--url", url, "-u", "administrator", "-p", "s3cret");

        assertEquals(2, result.exit, result.err);
        assertEquals("# mine\n", Files.readString(existing, StandardCharsets.UTF_8), "left untouched");
        assertTrue(requests.isEmpty(),
                "a rejected invocation must not have changed the server first: " + requests);
    }

    private static StrategyOptions.Resolved baseline() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), // the CLI default the seeded policy is layered over
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
