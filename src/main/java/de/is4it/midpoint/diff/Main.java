/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the {@code midgie} CLI.
 *
 * <p>See {@code DESIGN.md} for the full design. In short: a schema-aware diff of two midPoint objects
 * that emits the minimal {@code ObjectModificationType} (itemDelta) — the body midPoint's REST
 * {@code PATCH} consumes.
 */
@Command(
        name = "midgie",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Schema-aware diff of midPoint objects into an itemDelta (ObjectModificationType).",
        subcommands = { DiffCommand.class, DeployCommand.class, RollbackCommand.class,
                InitCommand.class }
)
public class Main implements java.util.concurrent.Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private static final int EXIT_ERROR = 2;

    /**
     * No subcommand given. Usage goes to stderr and the exit code is an error: printing help on stdout and
     * exiting 0 means {@code midgie $ARGS > delta.xml} with an empty {@code $ARGS} writes the help text into
     * the payload file and calls it success.
     */
    @Override
    public Integer call() {
        new CommandLine(this).usage(System.err);
        return CommandLine.ExitCode.USAGE;
    }

    /**
     * The configured command line. One factory so the tests drive exactly what {@code main} does — a harness
     * that built its own {@code CommandLine} would not see the handlers below, and so could not catch a
     * regression in either of them.
     */
    static CommandLine commandLine() {
        return commandLine(new String[0]);
    }

    /**
     * As {@link #commandLine()}, but with the argument list to hand — which matters only for logging: the
     * config files are read while this is being built, so {@code --debug} has to be switched on from argv
     * before that happens, or the one question a layered config raises (which file a setting came from) is
     * answered into a logger that is still off.
     */
    static CommandLine commandLine(String[] args) {
        Logging.enableFrom(args);
        CommandLine commandLine = new CommandLine(new Main());
        installConfigDefaults(commandLine);
        // picocli's default for an execution exception is 1, which this tool's contract defines as
        // "differences found". A typo'd --cacert or a malformed MIDPOINT_URL would then report drift to a CI
        // gate — and dump a Java stack trace at the operator. Every failure is a 2.
        commandLine.setExecutionExceptionHandler((exception, parsed, parseResult) -> {
            String message = (exception.getMessage() != null) ? exception.getMessage() : exception.toString();
            System.err.println("midgie: " + message);
            LOG.trace("command failed", exception);
            return EXIT_ERROR;
        });
        // The default parameter-exception handler echoes every unmatched token, so one typo publishes a
        // secret: 'midgie diff a.xml b.xml --pasword hunter2' printed the password into the log.
        commandLine.setParameterExceptionHandler((exception, unparsed) -> {
            System.err.println("midgie: " + safeMessage(exception));
            exception.getCommandLine().usage(System.err);
            return CommandLine.ExitCode.USAGE;
        });
        return commandLine;
    }

    /**
     * Install {@code midgie.properties} as the source of option defaults, when there is one.
     *
     * <p>A file that cannot be trusted fails every run rather than being skipped: a config the tool decided
     * to ignore is worse than none, since the operator goes on believing the settings in it apply. Help is
     * the exception — it is how you look up what a valid key is called.
     */
    private static void installConfigDefaults(CommandLine commandLine) {
        try {
            ConfigDefaults defaults = ConfigDefaults.load();
            if (defaults != null) {
                defaults.validate(commandLine);
                commandLine.setDefaultValueProvider(defaults);
                LOG.debug("defaults from {}", defaults.sources());
                String fromTree = defaults.treeFileSummary();
                if (fromTree != null) {
                    // Named outright, not logged at debug: a config file that arrived with the checkout is
                    // the one nobody remembers is there.
                    System.err.println("midgie: config from the tree: " + fromTree);
                }
            }
        } catch (Exception e) {
            commandLine.setExecutionStrategy(parseResult -> {
                if (helpRequested(parseResult)) {
                    return new CommandLine.RunLast().execute(parseResult);
                }
                System.err.println("midgie: " + e.getMessage());
                LOG.trace("config file rejected", e);
                return EXIT_ERROR;
            });
        }
    }

    /** Whether help or version was asked for at any level of the parsed command line. */
    private static boolean helpRequested(CommandLine.ParseResult parseResult) {
        for (CommandLine.ParseResult level = parseResult; level != null; level = level.subcommand()) {
            if (level.isUsageHelpRequested() || level.isVersionHelpRequested()) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unmatched-argument message with the <em>values</em> withheld.
     *
     * <p>Redacting by option name cannot work here: the token that leaks the secret is the value after an
     * option the parser did not recognise, and it did not recognise it precisely because it was mistyped —
     * {@code --pasword} does not contain "pass". So the shape decides instead. Anything that looks like an
     * option is named, so the operator can see what they got wrong; anything else is withheld. So is the value
     * an option carries in the same token — {@code --pasword=hunter2}, or {@code -Phunter2} for a short one.
     */
    static String safeMessage(CommandLine.ParameterException exception) {
        if (exception instanceof CommandLine.UnmatchedArgumentException unmatched) {
            StringBuilder tokens = new StringBuilder();
            for (String token : unmatched.getUnmatched()) {
                tokens.append(tokens.length() == 0 ? "" : ", ").append(withoutValue(token));
            }
            return "unknown argument(s): " + tokens + " (values withheld)";
        }
        return (exception.getMessage() != null) ? exception.getMessage() : "invalid arguments";
    }

    /** An unmatched token as it may be shown: an option's name without any value attached to it. */
    static String withoutValue(String token) {
        if (!token.startsWith("-")) {
            return "***";
        }
        int equals = token.indexOf('=');
        if (equals >= 0) {
            return token.substring(0, equals) + "=***";
        }
        if (!token.startsWith("--") && token.length() > 2) {
            return token.substring(0, 2) + "***"; // a short option with its value attached
        }
        return token;
    }

    public static void main(String[] args) {
        // Standard output carries the payload — a PATCH body piped into curl or a file — so it is UTF-8 whatever
        // the locale says. On JDK 17 it followed the platform encoding, and under LANG=C every non-ASCII
        // character of a name or a description came out as '?', corrupting the data the body then wrote.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        // Before anything can build an HttpClient: the JDK reads the hostname-verification property once,
        // on first use of java.net.http, so leaving the decision to whichever client is constructed first
        // makes --insecure depend on call order rather than on the flag.
        MidpointRestClient.latchHostnameVerification(insecureRequested(args));
        // The same once-per-process reading applies to proxy authentication over a CONNECT tunnel, which the
        // JDK disables by default. Latched only for a run that configured credentials, so the default keeps
        // protecting everyone else.
        MidpointRestClient.latchProxyTunnelAuth(proxyAuthRequested(args));
        System.exit(run(args));
    }

    /**
     * Run the command line and return its exit code — 2 for anything that escapes it.
     *
     * <p>picocli handles exceptions, not errors. An {@code OutOfMemoryError}, a {@code StackOverflowError} on
     * deeply nested XML, or a {@code NoClassDefFoundError} from a packaging slip used to end the JVM with its
     * own uncaught-throwable status of 1, which this tool's contract defines as "differences found": a CI
     * gate would have reported drift instead of a failure.
     */
    static int run(String[] args) {
        return run(() -> commandLine(args), args);
    }

    /** As {@link #run(String[])}, with the command line built by {@code commandLine} — inside the guard too. */
    static int run(java.util.function.Supplier<CommandLine> commandLine, String[] args) {
        try {
            return commandLine.get().execute(args);
        } catch (Throwable t) {
            System.err.println("midgie: internal error: " + t);
            LOG.trace("uncaught", t);
            return EXIT_ERROR;
        }
    }

    /**
     * Whether {@code --insecure} appears among the options, or is set in the config file. Read straight off
     * the argument list because the decision has to be made before picocli runs a command, and a command is
     * what would build the client — which is also why the file is consulted here rather than left to the
     * default-value provider: a config-file {@code insecure} that only reached the parser would switch off
     * certificate trust while leaving hostname verification on, and fail in a way that looks like a
     * certificate problem. Anything after a {@code --} separator is a positional argument, not an option.
     */
    static boolean insecureRequested(String[] args) {
        for (String arg : args) {
            if ("--".equals(arg)) {
                break;
            }
            if ("--insecure".equals(arg)) {
                return true;
            }
        }
        return configuredInsecure();
    }

    /**
     * Whether this run has proxy credentials, from any of the three places they can come from: the options,
     * the config file, or a {@code user:password@} in one of the proxy environment variables — the form the
     * rest of the toolchain uses, so a container is quite likely to carry it and nothing else.
     *
     * <p>Read off the argument list for the same reason {@link #insecureRequested} is: the property behind it
     * is fixed on first use of {@code java.net.http}, which is long before a command has parsed anything.
     */
    static boolean proxyAuthRequested(String[] args) {
        return proxyAuthOnCommandLine(args) || proxyAuthInEnvironment(System::getenv) || configuredProxyUser();
    }

    private static boolean proxyAuthOnCommandLine(String[] args) {
        for (String arg : args) {
            if ("--".equals(arg)) {
                break;
            }
            if ("--proxy-user".equals(arg) || arg.startsWith("--proxy-user=")) {
                return true;
            }
        }
        return false;
    }

    /** Credentials in {@code https_proxy}/{@code http_proxy}: userinfo before the host, as curl writes it. */
    static boolean proxyAuthInEnvironment(java.util.function.UnaryOperator<String> env) {
        for (String variable : List.of("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")) {
            String value = env.apply(variable);
            if (value == null || value.isBlank()) {
                continue;
            }
            int at = value.lastIndexOf('@');
            int slashes = value.indexOf("//");
            if (at > 0 && (slashes < 0 || at > slashes)) {
                return true;
            }
        }
        return false;
    }

    /** {@code proxy-user} in the config file. A broken file is not diagnosed here; the run will say so. */
    private static boolean configuredProxyUser() {
        try {
            ConfigDefaults defaults = ConfigDefaults.load();
            return defaults != null && defaults.isSet("proxy-user");
        } catch (Exception e) {
            LOG.debug("could not read the config file while latching proxy authentication: {}", e.getMessage());
            return false;
        }
    }

    /** {@code insecure=true} in the config file. A broken file is not diagnosed here; the run will say so. */
    private static boolean configuredInsecure() {
        try {
            ConfigDefaults defaults = ConfigDefaults.load();
            return defaults != null && defaults.isEnabled("insecure");
        } catch (Exception e) {
            // A one-liner, not the stack: nothing else reports this path, and the run itself will fail on the
            // same file a moment later with a message of its own.
            LOG.debug("could not read the config file while latching hostname verification: {}",
                    e.getMessage());
            return false;
        }
    }
}
