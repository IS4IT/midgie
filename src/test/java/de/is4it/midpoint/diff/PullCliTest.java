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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code midgie pull} against a stub server: what lands in the remainder folder, and what does not.
 */
class PullCliTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String OID = "cccccccc-0000-0000-0f00-00000000a001";
    private static final String OTHER_OID = "cccccccc-0000-0000-0f00-00000000a002";

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;
    /** The roles the server holds, by OID. */
    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private String stdout = "";
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
    void theRemainderHoldsWhatTheTreeDoesNotDeclare(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<description>managed</description>"
                + "<documentation>live only</documentation><subtype>x</subtype>"));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", "<description>managed</description>"));
        Path remainder = dir.resolve("remainder");

        assertEquals(0, pull(managed.getParent(), remainder), stderr);

        Path file = remainder.resolve("RoleType/Pulled Role." + OID + ".xml");
        String text = Files.readString(file);
        assertTrue(text.contains("<documentation>live only</documentation>") && text.contains("<subtype>x</subtype>"),
                text);
        assertFalse(text.contains("managed"), "the tree's own file declares it: " + text);
        assertTrue(stderr.contains("added") && stderr.contains("wrote 1 file(s)"), stderr);

        assertEquals(0, pull(managed.getParent(), remainder), stderr);
        assertEquals(text, Files.readString(file), "a second pull writes the same bytes");
        assertTrue(stderr.contains("1 object(s) unchanged"), stderr);
    }

    @Test
    void aDryRunShowsTheChangeWritesNothingAndExitsOne(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<documentation>live only</documentation>"));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", ""));
        Path remainder = dir.resolve("remainder");

        assertEquals(1, pull(managed.getParent(), remainder, "--dry-run"), stderr);

        assertFalse(Files.exists(remainder), "nothing is written");
        assertTrue(stdout.contains("RoleType/Pulled Role." + OID + ".xml\n") && stdout.contains("+++ ")
                && stdout.contains("live only"), stdout);
        assertTrue(stderr.contains("would add"), stderr);
    }

    @Test
    void aYesRuleTakesInObjectsNoFileDeclaresAndTheirFileGoesWithThem(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", ""));
        roles.put(OTHER_OID, role(OTHER_OID, "Unmanaged Role", "<description>server only</description>"));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", ""));
        Path policy = write(dir, "midgie.policy", "RoleType/*  remainder=yes\n");
        Path remainder = dir.resolve("remainder");

        assertEquals(0, pull(managed.getParent(), remainder, "--policy", policy.toString()), stderr);

        Path other = remainder.resolve("RoleType/Unmanaged Role." + OTHER_OID + ".xml");
        assertTrue(Files.readString(other).contains("server only"));
        assertFalse(Files.exists(remainder.resolve("RoleType/Pulled Role." + OID + ".xml")),
                "the tree's file declares all there is of the managed role");

        roles.remove(OTHER_OID);
        assertEquals(0, pull(managed.getParent(), remainder, "--policy", policy.toString()), stderr);

        assertFalse(Files.exists(other), "gone from the server, so gone from the tree");
        assertTrue(stderr.contains("removed") && stderr.contains("gone from the server"), stderr);
    }

    @Test
    void aRenameOnTheServerRenamesTheFile(@TempDir Path dir) throws Exception {
        roles.put(OTHER_OID, role(OTHER_OID, "Old Name", "<description>d</description>"));
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path policy = write(dir, "midgie.policy", "RoleType/*  remainder=yes\n");
        Path remainder = dir.resolve("remainder");
        assertEquals(0, pull(tree, remainder, "--policy", policy.toString()), stderr);

        roles.put(OTHER_OID, role(OTHER_OID, "New Name", "<description>d</description>"));
        assertEquals(0, pull(tree, remainder, "--policy", policy.toString()), stderr);

        assertFalse(Files.exists(remainder.resolve("RoleType/Old Name." + OTHER_OID + ".xml")));
        assertTrue(Files.readString(remainder.resolve("RoleType/New Name." + OTHER_OID + ".xml"))
                .contains("<name>New Name</name>"));
    }

    @Test
    void aConflictLeavesTheFileAsItWasAndExitsTwo(@TempDir Path dir) throws Exception {
        // One keyless entry, edited on the server: nothing pairs the file's version with the server's.
        roles.put(OID, role(OID, "Pulled Role", inducement("new")));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", inducement("old")));
        Path remainder = dir.resolve("remainder");
        Path file = write(dir, "remainder/RoleType/Pulled Role." + OID + ".xml", "as it was");

        assertEquals(2, pull(managed.getParent(), remainder), stderr);

        assertEquals("as it was", Files.readString(file));
        assertTrue(stderr.contains("inducement") && stderr.contains("left as they are"), stderr);
    }

    @Test
    void aNoRuleKeepsAManagedObjectOutOfTheRemainder(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<documentation>live only</documentation>"));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", ""));
        Path policy = write(dir, "midgie.policy", "RoleType/Pulled Role  remainder=no\n");
        Path remainder = dir.resolve("remainder");
        Path file = write(dir, "remainder/RoleType/Pulled Role." + OID + ".xml", "stale");

        assertEquals(0, pull(managed.getParent(), remainder, "--policy", policy.toString()), stderr);

        assertFalse(Files.exists(file), stderr);
        assertTrue(stderr.contains("the policy no longer asks for it"), stderr);
    }

    @Test
    void withoutARemainderFolderPullRefuses(@TempDir Path dir) throws Exception {
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", ""));

        assertEquals(2, run(List.of("pull", managed.toString(), "--url", host, "-u", "administrator", "-p", "x")));

        assertTrue(stderr.contains("remainder"), stderr);
    }

    @Test
    void aManagedFileTakesTheServersValueForWhatItDeclares(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<description>changed on the server</description>"
                + "<documentation>live only</documentation>"));
        Path managed = write(dir, "tree/roles/a.xml", """
                <!-- curated by hand -->
                <role xmlns="%s" oid="%s">
                    <name>Pulled Role</name>
                    <description>as deployed</description>
                </role>
                """.formatted(NS, OID));
        gitCommit(dir.resolve("tree"));
        Path remainder = dir.resolve("tree/remainder");

        assertEquals(0, pull(managed.getParent(), remainder), stderr);

        assertEquals("""
                <!-- curated by hand -->
                <role xmlns="%s" oid="%s">
                    <name>Pulled Role</name>
                    <description>changed on the server</description>
                </role>
                """.formatted(NS, OID), Files.readString(managed));
        String text = Files.readString(remainder.resolve("RoleType/Pulled Role." + OID + ".xml"));
        assertTrue(text.contains("live only") && !text.contains("description"), text);
        assertTrue(stderr.contains("changed description"), stderr);
    }

    @Test
    void aManagedFileWithChangesOfItsOwnIsNotTouched(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<description>changed on the server</description>"));
        Path managed = write(dir, "tree/roles/a.xml", role(OID, "Pulled Role", "<description>as deployed</description>"));
        gitCommit(dir.resolve("tree"));
        String uncommitted = role(OID, "Pulled Role", "<description>edited, not committed</description>");
        Files.writeString(managed, uncommitted);
        Path remainder = dir.resolve("tree/remainder");

        assertEquals(2, pull(managed.getParent(), remainder), stderr);

        assertEquals(uncommitted, Files.readString(managed));
        assertTrue(stderr.contains("not committed"), stderr);
        assertFalse(Files.exists(remainder), "nothing of the object is written");
    }

    @Test
    void aManagedFileOutsideGitIsNotTouched(@TempDir Path dir) throws Exception {
        roles.put(OID, role(OID, "Pulled Role", "<description>changed on the server</description>"));
        String original = role(OID, "Pulled Role", "<description>as deployed</description>");
        Path managed = write(dir, "tree/roles/a.xml", original);

        assertEquals(2, pull(managed.getParent(), dir.resolve("remainder")), stderr);

        assertEquals(original, Files.readString(managed));
        assertTrue(stderr.contains("not in a git repository"), stderr);
    }

    // --- helpers ---

    private static void gitCommit(Path repo) throws Exception {
        for (List<String> command : List.of(List.of("git", "init", "-q", "."), List.of("git", "add", "-A"),
                List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.org", "commit", "-q", "-m", "tree"))) {
            Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0, "git not available: " + command);
        }
    }

    private int pull(Path tree, Path remainder, String... extra) {
        List<String> args = new ArrayList<>(List.of("pull", tree.toString(), "--remainder", remainder.toString(),
                "--url", host, "-u", "administrator", "-p", "x", "--no-live-schema"));
        args.addAll(List.of(extra));
        return run(args);
    }

    private int run(List<String> args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return new CommandLine(new Main()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            stdout = out.toString(StandardCharsets.UTF_8);
            stderr = err.toString(StandardCharsets.UTF_8);
        }
    }

    private static String inducement(String description) {
        return "<inducement><description>" + description + "</description>"
                + "<targetRef oid=\"cccccccc-0000-0000-0f00-00000000a0ff\" type=\"RoleType\"/></inducement>";
    }

    private static String role(String oid, String name, String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + oid + "\"><name>" + name + "</name>" + body + "</role>";
    }

    private static Path write(Path dir, String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Search answers with the roles the query names by OID (a lookup by OID), or with every role (a listing). The
     * query is not parsed: an OID in it is enough to tell the two apart.
     */
    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/search")) {
            List<String> matches = new ArrayList<>();
            boolean byOid = false;
            for (Map.Entry<String, String> role : roles.entrySet()) {
                if (body.contains(role.getKey())) {
                    matches.add(role.getValue());
                    byOid = true;
                }
            }
            if (!byOid && !body.contains("inOid")) {
                matches.addAll(roles.values());
            }
            respond(exchange, 200, objectList(matches));
        } else {
            respond(exchange, 404, "");
        }
    }

    private static String objectList(List<String> objects) throws IOException {
        try {
            ObjectListType list = new ObjectListType();
            for (String object : objects) {
                list.getObject().add((ObjectType) prismContext.parseObject(object).asObjectable());
            }
            return prismContext.xmlSerializer().serializeRealValue(list, new QName(NS_API, "objectList"));
        } catch (Exception e) {
            throw new IOException("stub could not build the search response", e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
