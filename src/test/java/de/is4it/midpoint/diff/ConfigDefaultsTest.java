/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code midgie.properties}: defaults for any option, the precedence around them (command line
 * beats environment beats file), and the two ways a file is refused — a key that is not an option, and a key
 * that would decide on a person's behalf whether a run writes or deletes.
 */
class ConfigDefaultsTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "00000000-0000-0000-0000-000000000042";

    @AfterEach
    void clearPath() {
        System.clearProperty(ConfigDefaults.PATH_PROPERTY);
    }

    /** Point midgie at a config file the way JAVA_OPTS would. */
    private static void useConfig(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("midgie.properties");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        System.setProperty(ConfigDefaults.PATH_PROPERTY, file.toString());
    }

    @Test
    void anOptionTakesItsDefaultFromTheFile(@TempDir Path dir) throws Exception {
        // --scope full also diffs what the desired file does not mention, so the dropped description becomes
        // a delete; under the built-in 'present' the same pair is in sync. The exit code shows which ran.
        Path desired = write(dir, "desired.xml", user(null));
        Path current = write(dir, "current.xml", user("only on the server"));

        assertEquals(0, run("diff", desired.toString(), current.toString()).exit,
                "without a config file the built-in default (present) applies");

        useConfig(dir, "scope = full");
        assertEquals(1, run("diff", desired.toString(), current.toString()).exit,
                "the file's scope=full makes the missing description a difference");
    }

    @Test
    void theCommandLineBeatsTheFile(@TempDir Path dir) throws Exception {
        Path desired = write(dir, "desired.xml", user(null));
        Path current = write(dir, "current.xml", user("only on the server"));
        useConfig(dir, "scope = full");

        assertEquals(0, run("diff", desired.toString(), current.toString(), "--scope", "present").exit,
                "an explicit flag overrides the file");
    }

    @Test
    void aQualifiedKeyAppliesToOneCommandOnly(@TempDir Path dir) throws Exception {
        Path desired = write(dir, "desired.xml", user(null));
        Path current = write(dir, "current.xml", user("only on the server"));
        useConfig(dir, "midgie.apply.scope = full");   // apply, not diff

        assertEquals(0, run("diff", desired.toString(), current.toString()).exit,
                "a key qualified with another command must not reach this one");
    }

    @Test
    void theEnvironmentBeatsTheFile(@TempDir Path dir) throws Exception {
        // MIDPOINT_URL describes the process this run was launched into — a pod's deployment, typically —
        // so a file on the same machine must not quietly redirect the run somewhere else.
        Path file = dir.resolve("midgie.properties");
        Files.writeString(file, "url = https://from-the-file/midpoint\n", StandardCharsets.UTF_8);
        System.setProperty(ConfigDefaults.PATH_PROPERTY, file.toString());

        ConfigDefaults withEnv = ConfigDefaults.load(name ->
                "MIDPOINT_URL".equals(name) ? "https://from-the-environment/midpoint" : null);
        ConfigDefaults withoutEnv = ConfigDefaults.load(name -> null);

        CommandLine command = Main.commandLine();
        CommandLine.Model.OptionSpec url = command.getSubcommands().get("diff")
                .getCommandSpec().findOption("--url");

        assertEquals(null, withEnv.defaultValue(url), "the file stands aside for MIDPOINT_URL");
        assertEquals("https://from-the-file/midpoint", withoutEnv.defaultValue(url),
                "and applies when the variable is not set");
    }

    @Test
    void aKeyThatIsNotAnOptionIsRefused(@TempDir Path dir) throws Exception {
        // A typo'd key is a setting that silently does nothing — the failure the policy file's
        // "matched no object" note exists to prevent, in a file nobody reads twice.
        useConfig(dir, "scpoe = full");

        Result result = run("diff", "a.xml", "b.xml");
        assertEquals(2, result.exit, result.out);
        assertTrue(result.err.contains("scpoe"), result.err);
        assertTrue(result.err.contains("not a midgie option"), result.err);
    }

    @Test
    void aKeyThatWouldWaiveAConfirmationIsRefused(@TempDir Path dir) throws Exception {
        for (String key : new String[] {"yes = true", "midgie.deploy.prune = true"}) {
            useConfig(dir, key);
            Result result = run("deploy", dir.toString());
            assertEquals(2, result.exit, result.out);
            assertTrue(result.err.contains("command line"), key + " -> " + result.err);
        }
    }

    @Test
    void aNamedFileThatDoesNotExistIsAnError(@TempDir Path dir) {
        System.setProperty(ConfigDefaults.PATH_PROPERTY, dir.resolve("absent.properties").toString());

        Result result = run("diff", "a.xml", "b.xml");
        assertEquals(2, result.exit, result.out);
        assertTrue(result.err.contains("no config file at"), result.err);
    }

    @Test
    void helpStillWorksWhenTheFileIsBroken(@TempDir Path dir) throws Exception {
        // --help is how you look up what a valid key is called, so it must not be the thing the broken file
        // takes away.
        useConfig(dir, "scpoe = full");

        Result result = run("--help");
        assertEquals(0, result.exit, result.err);
        assertTrue(result.out.contains("Usage: midgie"), result.out);
    }

    @Test
    void insecureIsVisibleBeforeAnyCommandRuns(@TempDir Path dir) throws Exception {
        // Hostname verification is latched once, before a client exists, so the file has to be read there
        // too — otherwise 'insecure' in a file would half-apply.
        useConfig(dir, "insecure = true");
        assertTrue(Main.insecureRequested(new String[] {"diff", "a.xml", "b.xml"}),
                "the file's insecure must reach the latch");

        useConfig(dir, "midgie.apply.insecure = true");
        assertTrue(Main.insecureRequested(new String[] {"apply", "a.xml"}),
                "including when it is qualified with a command");

        useConfig(dir, "insecure = false");
        assertFalse(Main.insecureRequested(new String[] {"diff", "a.xml", "b.xml"}), "and false stays false");
    }

    @Test
    void aFileWithNoKnownProblemsLoads(@TempDir Path dir) throws Exception {
        useConfig(dir, "# a comment", "", "url = https://mp.example/midpoint", "user = deployer",
                "cacert = /etc/ssl/corp.pem", "scope = present", "midgie.deploy.retry = true");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null);
        defaults.validate(Main.commandLine());   // the assertion is that this does not throw
    }

    @Test
    void aMalformedFileNamesItself(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("midgie.properties");
        Files.writeString(file, "url = \\uZZZZ\n", StandardCharsets.UTF_8);
        System.setProperty(ConfigDefaults.PATH_PROPERTY, file.toString());

        IOException error = assertThrows(IOException.class, () -> ConfigDefaults.load(name -> null));
        assertTrue(error.getMessage().contains(file.toString()), error.getMessage());
    }

    // --- fixtures ---

    private static Path write(Path dir, String name, String xml) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    private static String user(String description) {
        return "<user xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>probe</name>\n"
                + (description != null ? "  <description>" + description + "</description>\n" : "")
                + "</user>";
    }

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
