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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Drives the real {@code deploy} CLI against a stub server where one object's PATCH fails on the first
 * attempt and succeeds afterwards, to prove {@code --retry} re-applies the failed set and converges (and
 * that a single pass without {@code --retry} fails). {@code --quiet}/{@code --no-live-schema} keep the
 * stub down to just search + PATCH.
 */
class DeployRetryTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String NS_API = "http://midpoint.evolveum.com/xml/ns/public/common/api_types_3";
    private static final String GOOD = "aaaaaaaa-0000-0000-0f00-000000000001";
    private static final String FLAKY = "bbbbbbbb-0000-0000-0f00-000000000002";
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private static PrismContext prismContext;

    private HttpServer server;
    private String host;
    private final AtomicInteger flakyFailsRemaining = new AtomicInteger(0);

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
    void retryConvergesAFlakyUnit(@TempDir Path dir) throws Exception {
        flakyFailsRemaining.set(1); // the flaky role's PATCH fails once, then succeeds
        writeTree(dir);
        int exit = deploy(dir, "--retry");
        assertEquals(0, exit, "retry should re-apply the flaky unit and converge");
    }

    @Test
    void withoutRetryAFlakyUnitFailsTheRun(@TempDir Path dir) throws Exception {
        flakyFailsRemaining.set(1);
        writeTree(dir);
        int exit = deploy(dir);
        assertEquals(2, exit, "a single pass leaves the flaky unit failed");
    }

    @Test
    void aPolicyRuleThatAppliedIsNotReportedAsUnmatched(@TempDir Path dir) throws Exception {
        // A rule is marked as having matched when it is offered to an object, which happens inside the apply
        // pass. Reporting the unmatched ones before that pass made every rule look unmatched — including the
        // one about to be applied — so the note cried wolf on exactly the files it was working on.
        writeTree(dir);
        Path policy = dir.resolve("midgie.policy");
        Files.writeString(policy, "RoleType/Role  multivalue=merge\n", StandardCharsets.UTF_8);

        Result result = deployCapturing(dir, "--policy", policy.toString());

        assertEquals(0, result.exit(), result.err());
        assertFalse(result.err().contains("matched no object"),
                "the rule applies to both roles in the tree: " + result.err());
    }

    @Test
    void aPolicyRuleThatMatchesNothingIsStillReported(@TempDir Path dir) throws Exception {
        writeTree(dir);
        Path policy = dir.resolve("midgie.policy");
        Files.writeString(policy, "RoleType/NoSuchRole  multivalue=merge\n", StandardCharsets.UTF_8);

        Result result = deployCapturing(dir, "--policy", policy.toString());

        assertEquals(0, result.exit(), result.err());
        assertTrue(result.err().contains("matched no object"),
                "a typo'd selector must still be named: " + result.err());
        assertTrue(result.err().contains("NoSuchRole"), result.err());
    }

    // --- helpers ---

    private int deploy(Path dir, String... extra) {
        String[] base = {"deploy", dir.toString(), "--url", host, "-u", "administrator", "-p", "x",
                "--yes", "--quiet", "--no-live-schema"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return runCli(args);
    }

    /** As {@link #deploy}, but without {@code --quiet} and keeping the output, since notes are the subject. */
    private Result deployCapturing(Path dir, String... extra) {
        String[] base = {"deploy", dir.toString(), "--url", host, "-u", "administrator", "-p", "x",
                "--yes", "--no-live-schema"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = new CommandLine(new Main()).execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }

    private static int runCli(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            return new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static void writeTree(Path dir) throws IOException {
        Files.writeString(dir.resolve("good.xml"), role(GOOD, "new"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("flaky.xml"), role(FLAKY, "new"), StandardCharsets.UTF_8);
    }

    private static String role(String oid, String description) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + oid + "\"><name>Role</name>"
                + "<description>" + description + "</description></role>";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();

        if ("POST".equals(method) && path.endsWith("/search")) {
            // The object exists (with an older description), so the desired state is a PATCH.
            Matcher matcher = UUID.matcher(new String(requestBody, StandardCharsets.UTF_8));
            String oid = matcher.find() ? matcher.group() : GOOD;
            respond(exchange, 200, searchResult(oid));
        } else if ("PATCH".equals(method)) {
            String oid = path.substring(path.lastIndexOf('/') + 1);
            if (FLAKY.equals(oid) && flakyFailsRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                respond(exchange, 400, "transient failure");
            } else {
                respond(exchange, 204, "");
            }
        } else {
            respond(exchange, 204, "");
        }
    }

    /**
     * The object the stub "server" holds. Never swallows a failure, and since creating became the default
     * it cannot disguise one either: the flakiness is injected on PATCH, so a stub that wrongly returned an
     * empty result would make the unit a CREATE, the POST would succeed, and
     * {@code withoutRetryAFlakyUnitFailsTheRun} would fail its {@code exit == 2} rather than pass having
     * exercised nothing. (Before the default flipped, an absent object failed the unit by itself, which is
     * exactly the disguise this note used to warn about.)
     */
    private static String searchResult(String oid) throws IOException {
        try {
            ObjectListType list = new ObjectListType();
            list.getObject().add((ObjectType) prismContext.parseObject(role(oid, "old")).asObjectable());
            return prismContext.xmlSerializer().serializeRealValue(list, new QName(NS_API, "objectList"));
        } catch (Exception e) {
            // Fail the request rather than answering "no such object": absent is exactly what
            // withoutRetryAFlakyUnitFailsTheRun expects to see, so a broken stub used to pass that test
            // having served nothing and never reached the retry machinery at all.
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
