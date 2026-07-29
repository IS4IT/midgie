/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

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
        subcommands = { DiffCommand.class, ApplyCommand.class, DeployCommand.class, InitCommand.class }
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
        CommandLine commandLine = new CommandLine(new Main());
        installConfigDefaults(commandLine);
        // picocli's default for an execution exception is 1, which this tool's contract defines as
        // "differences found". A typo'd --cacert or a malformed MIDPOINT_URL would then report drift to a CI
        // gate — and dump a Java stack trace at the operator. Every failure is a 2.
        commandLine.setExecutionExceptionHandler((exception, parsed, parseResult) -> {
            String message = (exception.getMessage() != null) ? exception.getMessage() : exception.toString();
            System.err.println("midgie: " + message);
            LOG.debug("command failed", exception);
            return EXIT_ERROR;
        });
        // The default parameter-exception handler echoes every unmatched token, so one typo publishes a
        // secret: 'midgie diff a.xml b.xml --pasword hunter2' printed the password into the log.
        commandLine.setParameterExceptionHandler((exception, args) -> {
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
                LOG.debug("defaults from {}", defaults.file());
            }
        } catch (Exception e) {
            commandLine.setExecutionStrategy(parseResult -> {
                if (helpRequested(parseResult)) {
                    return new CommandLine.RunLast().execute(parseResult);
                }
                System.err.println("midgie: " + e.getMessage());
                LOG.debug("config file rejected", e);
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
     * option is named, so the operator can see what they got wrong; anything else is withheld.
     */
    private static String safeMessage(CommandLine.ParameterException exception) {
        if (exception instanceof CommandLine.UnmatchedArgumentException unmatched) {
            StringBuilder tokens = new StringBuilder();
            for (String token : unmatched.getUnmatched()) {
                tokens.append(tokens.length() == 0 ? "" : ", ")
                        .append(token.startsWith("-") ? token : "***");
            }
            return "unknown argument(s): " + tokens + " (values withheld)";
        }
        return (exception.getMessage() != null) ? exception.getMessage() : "invalid arguments";
    }

    public static void main(String[] args) {
        // Before anything can build an HttpClient: the JDK reads the hostname-verification property once,
        // on first use of java.net.http, so leaving the decision to whichever client is constructed first
        // makes --insecure depend on call order rather than on the flag.
        MidpointRestClient.latchHostnameVerification(insecureRequested(args));
        System.exit(commandLine().execute(args));
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

    /** {@code insecure=true} in the config file. A broken file is not diagnosed here; the run will say so. */
    private static boolean configuredInsecure() {
        try {
            ConfigDefaults defaults = ConfigDefaults.load();
            return defaults != null && defaults.isEnabled("insecure");
        } catch (Exception e) {
            LOG.debug("could not read the config file while latching hostname verification", e);
            return false;
        }
    }
}
