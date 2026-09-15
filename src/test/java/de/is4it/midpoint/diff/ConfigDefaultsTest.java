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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Path desired = writeXml(dir, "desired.xml", user(null));
        Path current = writeXml(dir, "current.xml", user("only on the server"));

        assertEquals(0, run("diff", desired.toString(), current.toString()).exit,
                "without a config file the built-in default (present) applies");

        useConfig(dir, "scope = full");
        assertEquals(1, run("diff", desired.toString(), current.toString()).exit,
                "the file's scope=full makes the missing description a difference");
    }

    @Test
    void theCommandLineBeatsTheFile(@TempDir Path dir) throws Exception {
        Path desired = writeXml(dir, "desired.xml", user(null));
        Path current = writeXml(dir, "current.xml", user("only on the server"));
        useConfig(dir, "scope = full");

        assertEquals(0, run("diff", desired.toString(), current.toString(), "--scope", "present").exit,
                "an explicit flag overrides the file");
    }

    @Test
    void aQualifiedKeyAppliesToOneCommandOnly(@TempDir Path dir) throws Exception {
        Path desired = writeXml(dir, "desired.xml", user(null));
        Path current = writeXml(dir, "current.xml", user("only on the server"));
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
    void anyOfTheProxyVariablesStandsTheFileDown(@TempDir Path dir) throws Exception {
        // The proxy is spelled four ways — scheme-dependent, and in either case of letters — so unlike
        // MIDPOINT_URL there is no single variable to check. Any of them being set is what counts.
        Path file = dir.resolve("midgie.properties");
        Files.writeString(file, "proxy = http://from-the-file:3128\n", StandardCharsets.UTF_8);
        System.setProperty(ConfigDefaults.PATH_PROPERTY, file.toString());

        CommandLine.Model.OptionSpec proxy = Main.commandLine().getSubcommands().get("diff")
                .getCommandSpec().findOption("--proxy");

        for (String variable : new String[] {"https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"}) {
            ConfigDefaults defaults = ConfigDefaults.load(
                    name -> variable.equals(name) ? "http://from-the-environment:3128" : null);
            assertEquals(null, defaults.defaultValue(proxy), variable + " must outrank the file");
        }
        assertEquals("http://from-the-file:3128", ConfigDefaults.load(name -> null).defaultValue(proxy),
                "and the file applies when none of them is set");
    }

    @Test
    void aTreeFileMayNotNameAProxy(@TempDir Path dir) throws Exception {
        // A proxy decides where the Basic header travels, so a file arriving with somebody else's clone is
        // the last place it may be set — the same rule that keeps url, user and cacert off that list.
        Path tree = write(dir, "tree.properties", "proxy = http://attacker.example:3128");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), dir.resolve("absent-user").toFile(), tree.toFile());
        IOException error = assertThrows(IOException.class, () -> defaults.validate(Main.commandLine()));

        assertTrue(error.getMessage().contains("proxy"), error.getMessage());
        assertTrue(error.getMessage().contains(tree.toString()), error.getMessage());
    }

    @Test
    void filesOnTheSearchPathAreMergedPerKey(@TempDir Path dir) throws Exception {
        // The point of merging: a site file holds what is true of the machine, a user file adds only what
        // differs, and neither has to repeat the other. First-match-wins made the user file shadow the site
        // file entirely, so the CA certificate had to be copied into every user's file to survive.
        Path site = write(dir, "site.properties", "cacert = /etc/ssl/corp.pem", "scope = full", "user = site");
        Path user = write(dir, "user.properties", "user = deployer", "url = https://mp.example/midpoint");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null, site.toFile(), user.toFile());
        defaults.validate(Main.commandLine());

        assertEquals("/etc/ssl/corp.pem", value(defaults, "diff", "--cacert"), "site-only key survives");
        assertEquals("full", value(defaults, "diff", "--scope"), "site-only key survives");
        assertEquals("https://mp.example/midpoint", value(defaults, "diff", "--url"), "user-only key applies");
        assertEquals("deployer", value(defaults, "diff", "--user"), "the later layer wins the key both set");
        assertEquals(2, defaults.sources().size(), defaults.sources().toString());
    }

    @Test
    void anExplicitlyNamedFileIsTheTopLayer(@TempDir Path dir) throws Exception {
        Path site = write(dir, "site.properties", "cacert = /etc/ssl/corp.pem", "url = https://site/midpoint");
        Path user = write(dir, "user.properties", "url = https://user/midpoint");
        Path named = write(dir, "named.properties", "url = https://named/midpoint");
        System.setProperty(ConfigDefaults.PATH_PROPERTY, named.toString());

        ConfigDefaults defaults = ConfigDefaults.load(name -> null, site.toFile(), user.toFile());

        assertEquals("https://named/midpoint", value(defaults, "diff", "--url"),
                "naming a file is the most deliberate of the three, so it wins");
        assertEquals("/etc/ssl/corp.pem", value(defaults, "diff", "--cacert"),
                "and it still layers on top rather than replacing what is underneath");
    }

    // --- the tree-local layer, which travels with somebody else's repository ---

    @Test
    void aTreeFileMaySetAnAllowlistedKey(@TempDir Path dir) throws Exception {
        Path site = write(dir, "site.properties", "url = https://mp.example/midpoint");
        Path user = write(dir, "user.properties");
        Path tree = write(dir, "tree.properties", "rollback-plan = /mnt/nets", "policy = deploy.policy");

        ConfigDefaults defaults =
                ConfigDefaults.load(name -> null, site.toFile(), user.toFile(), tree.toFile());
        defaults.validate(Main.commandLine());

        assertEquals("/mnt/nets", value(defaults, "deploy", "--rollback-plan"),
                "a deployment is the only thing that knows where its net belongs");
        assertEquals("deploy.policy", value(defaults, "deploy", "--policy"));
    }

    @Test
    void aTreeFileMayNotSetWhereCredentialsGo(@TempDir Path dir) throws Exception {
        // The reason this layer is allowlisted rather than denylisted: cloning an unfamiliar repository and
        // running even a read would otherwise authenticate to whatever server its file names.
        Path tree = write(dir, "tree.properties", "url = https://attacker.example/midpoint");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), dir.resolve("absent-user").toFile(), tree.toFile());
        IOException error = assertThrows(IOException.class, () -> defaults.validate(Main.commandLine()));

        assertTrue(error.getMessage().contains(tree.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("tree-local"), error.getMessage());
    }

    @Test
    void aTreeFileMayNotSwitchOffTheSafetyNet(@TempDir Path dir) throws Exception {
        // A file may say WHERE something goes, never WHETHER a safety gate is there -- the same rule that
        // keeps 'yes' and 'prune' off every config file.
        Path tree = write(dir, "tree.properties", "no-rollback-plan = true");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), dir.resolve("absent-user").toFile(), tree.toFile());

        assertThrows(IOException.class, () -> defaults.validate(Main.commandLine()));
    }

    @Test
    void aKeyThatBecameTheDefaultIsRefusedRatherThanIgnored(@TempDir Path dir) throws Exception {
        // 'create-if-absent' still parses on the command line so old scripts keep working, which is exactly
        // why a file setting it must be refused: it would sit there meaning the opposite of what its author
        // intended, and a setting that silently does nothing is what this class exists to prevent.
        Path user = write(dir, "user.properties", "create-if-absent = false");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), user.toFile(), null);

        IOException failure = assertThrows(IOException.class, () -> defaults.validate(Main.commandLine()));
        assertTrue(failure.getMessage().contains("no-create-if-absent"),
                "and it must name the key that does work now: " + failure.getMessage());
    }

    @Test
    void aTreeMayStillTurnCreatingOff(@TempDir Path dir) throws Exception {
        // The opt-out moves in the conservative direction, so a tree-local file may set it — the allowlist
        // rule is about safety gates, and refusing to create is not switching one off.
        Path tree = write(dir, "tree.properties", "no-create-if-absent = true");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), dir.resolve("absent-user").toFile(), tree.toFile());

        defaults.validate(Main.commandLine());   // must not throw
    }

    @Test
    void theOperatorsOwnFileOutranksTheTree(@TempDir Path dir) throws Exception {
        Path user = write(dir, "user.properties", "scope = full");
        Path tree = write(dir, "tree.properties", "scope = present");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), user.toFile(), tree.toFile());
        defaults.validate(Main.commandLine());

        assertEquals("full", value(defaults, "diff", "--scope"), "the tree suggests; the operator decides");
    }

    @Test
    void theUsersOwnFileIsNeverTreatedAsATreeFile(@TempDir Path dir) throws Exception {
        // Both are named .midgie.properties, so the upward walk can land on the user's own file. Validating
        // it against the tree allowlist would reject the account they legitimately keep there.
        Path user = write(dir, "user.properties", "url = https://mp.example/midpoint");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), user.toFile(), user.toFile());
        defaults.validate(Main.commandLine());

        assertEquals("https://mp.example/midpoint", value(defaults, "diff", "--url"));
        assertNull(defaults.treeFileSummary(), "it is the user's file, not a tree file");
    }

    @Test
    void theTreeFileAndItsKeysAreReported(@TempDir Path dir) throws Exception {
        Path tree = write(dir, "tree.properties", "rollback-plan = /mnt/nets");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null,
                dir.resolve("absent-site").toFile(), dir.resolve("absent-user").toFile(), tree.toFile());

        String summary = defaults.treeFileSummary();
        assertNotNull(summary, "a config file that came with the checkout must announce itself");
        assertTrue(summary.contains(tree.toString()), summary);
        assertTrue(summary.contains("rollback-plan"), summary);
    }

    @Test
    void aRefusedKeyNamesTheFileItCameFrom(@TempDir Path dir) throws Exception {
        // With several files merged, naming "the config file" would send the operator to the wrong one.
        Path site = write(dir, "site.properties", "url = https://mp.example/midpoint");
        Path user = write(dir, "user.properties", "yes = true");

        ConfigDefaults defaults = ConfigDefaults.load(name -> null, site.toFile(), user.toFile());
        IOException error = assertThrows(IOException.class, () -> defaults.validate(Main.commandLine()));

        assertTrue(error.getMessage().contains(user.toString()), error.getMessage());
        assertFalse(error.getMessage().contains(site.toString()), error.getMessage());
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
    void proxyCredentialsAreVisibleBeforeAnyCommandRuns(@TempDir Path dir) throws Exception {
        // Same reason as the insecure latch above: the property that allows Basic on a CONNECT tunnel is read
        // once, on first use of java.net.http, so every place credentials can come from has to be seen here.
        assertTrue(Main.proxyAuthRequested(new String[] {"diff", "a.xml", "--proxy-user", "alice"}));
        assertTrue(Main.proxyAuthRequested(new String[] {"diff", "a.xml", "--proxy-user=alice"}),
                "picocli takes either spelling, so the scan has to as well");

        useConfig(dir, "proxy-user = alice");
        assertTrue(Main.proxyAuthRequested(new String[] {"diff", "a.xml", "b.xml"}),
                "the file's proxy-user must reach the latch");

        useConfig(dir, "url = https://mp.example/midpoint");
        assertFalse(Main.proxyAuthRequested(new String[] {"diff", "a.xml", "b.xml"}),
                "a run with no proxy credentials leaves the JDK default alone");
    }

    @Test
    void credentialsInsideAProxyVariableCountAsRequestingAuth() {
        // https_proxy=http://user:pass@host is how the rest of the toolchain writes it, so a container may
        // well carry that and nothing else.
        assertTrue(Main.proxyAuthInEnvironment(
                name -> "https_proxy".equals(name) ? "http://alice:s3cret@proxy.corp:3128" : null));
        assertFalse(Main.proxyAuthInEnvironment(
                name -> "https_proxy".equals(name) ? "http://proxy.corp:3128" : null));
        assertFalse(Main.proxyAuthInEnvironment(name -> null));
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

    @Test
    void findingNoFileAtAllIsAlsoLogged(@TempDir Path dir) throws Exception {
        // Silence cannot be read: "there is no config file" and "--debug did not work" look the same, which
        // is exactly the wrong ambiguity while working out where a setting came from.
        List<String> logged = capturingConfigLog(() ->
                ConfigDefaults.load(name -> null, dir.resolve("absent-site").toFile(),
                        dir.resolve("absent-user").toFile()));

        assertTrue(logged.stream().anyMatch(line -> line.contains("no midgie.properties")), logged.toString());
        assertTrue(logged.stream().anyMatch(line -> line.contains("absent-site")), logged.toString());
        assertTrue(logged.stream().anyMatch(line -> line.contains("absent-user")), logged.toString());
    }

    @Test
    void theLogNamesKeysAndFilesButNeverValues(@TempDir Path dir) throws Exception {
        // 'password' is a legitimate key, so the provenance log has to stop at the key name.
        Path site = write(dir, "site.properties", "url = https://mp.example/midpoint");
        Path user = write(dir, "user.properties", "password = hunter2", "url = https://other.example/midpoint");

        List<String> logged = capturingConfigLog(() -> ConfigDefaults.load(name -> null, site.toFile(), user.toFile()));

        assertTrue(logged.stream().anyMatch(line -> line.contains("password")), logged.toString());
        assertTrue(logged.stream().anyMatch(line -> line.contains("overrides")), "the shadowed url: " + logged);
        assertFalse(logged.stream().anyMatch(line -> line.contains("hunter2")),
                "a value must never reach the log: " + logged);
    }

    /** Run something with the config logger at TRACE, returning every line it wrote at either level. */
    private static List<String> capturingConfigLog(ThrowingRunnable body) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ConfigDefaults.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        ch.qos.logback.classic.Level original = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            body.run();
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // --- fixtures ---

    /** The default an option would take from the merged files. */
    private static String value(ConfigDefaults defaults, String command, String option) throws Exception {
        return defaults.defaultValue(Main.commandLine().getSubcommands().get(command)
                .getCommandSpec().findOption(option));
    }

    private static Path write(Path dir, String name, String... lines) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static Path writeXml(Path dir, String name, String xml) throws IOException {
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
