/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Site defaults for any option, read from a {@code midgie.properties} file.
 *
 * <p>It exists because the settings that never change for an environment — the server URL, the account, the
 * CA certificate that makes TLS verify — had to be repeated on every command line, and the only alternative
 * was {@code --insecure}. A file is also the only place a container can carry them: a ConfigMap mounts as a
 * file, and {@code $HOME} in the pod can be persistent.
 *
 * <p><b>Where it comes from</b>, first match wins: the {@code midgie.conf} system property, the
 * {@code MIDGIE_CONF} environment variable, {@code $HOME/.midgie.properties}, {@code /etc/midgie/midgie.properties}.
 * The first two name the file explicitly, so a path that does not exist is an error rather than a silent
 * fall-through to a different file.
 *
 * <p><b>Precedence</b>: an option given on the command line wins; then the {@code MIDPOINT_*} environment
 * variables, which describe the environment a process was launched into and so outrank a file on disk; then
 * this file; then the option's built-in default.
 *
 * <p><b>Keys</b> are option names without the dashes — {@code url}, {@code cacert}, {@code create-if-absent}
 * — applying to every subcommand that has that option, or qualified with the command to narrow them:
 * {@code midgie.deploy.scope}. That is picocli's own format ({@code PropertiesDefaultProvider}), which looks
 * up the qualified key first and falls back to the bare one; this class adds the precedence rule above and
 * the validation below.
 *
 * <p><b>Every key is checked</b> against the options that actually exist, because a mistyped key is
 * otherwise a setting that silently does nothing — the same failure the policy file's "matched no object"
 * note exists to prevent. Two keys are refused outright: {@code yes} and {@code prune} decide whether a run
 * writes without asking and whether it deletes, and a file on disk is not where a human confirms that.
 */
final class ConfigDefaults implements CommandLine.IDefaultValueProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigDefaults.class);

    /** System property naming the file (also settable through JAVA_OPTS, which the launcher passes on). */
    static final String PATH_PROPERTY = "midgie.conf";

    /** Environment variable naming the file. */
    static final String PATH_ENV = "MIDGIE_CONF";

    static final String USER_FILE = ".midgie.properties";
    static final String SYSTEM_FILE = "/etc/midgie/midgie.properties";

    /**
     * Options a file may not set. Both authorise something irreversible without a person present: {@code yes}
     * turns off the confirmation that stands between a tree and a live server, and {@code prune} is what
     * deletes. A default that quietly makes every later run non-interactive is exactly the accident this tool
     * refuses elsewhere.
     */
    private static final Set<String> REFUSED = Set.of("yes", "prune");

    /** Keys whose environment variable, when set, outranks the file. */
    private static final Map<String, String> ENV_OVERRIDES =
            Map.of("url", "MIDPOINT_URL", "user", "MIDPOINT_USER", "password", "MIDPOINT_PASSWORD");

    private final File file;
    private final Properties properties;
    private final CommandLine.PropertiesDefaultProvider delegate;
    private final UnaryOperator<String> env;

    private ConfigDefaults(File file, Properties properties, UnaryOperator<String> env) {
        this.file = file;
        this.properties = properties;
        this.delegate = new CommandLine.PropertiesDefaultProvider(properties);
        this.env = env;
    }

    /** Load the configured file, or {@code null} when there is none. */
    static ConfigDefaults load() throws IOException {
        return load(System::getenv);
    }

    static ConfigDefaults load(UnaryOperator<String> env) throws IOException {
        File explicit = explicitPath(env);
        if (explicit != null) {
            if (!explicit.isFile()) {
                throw new IOException("no config file at " + explicit + " (named by "
                        + (System.getProperty(PATH_PROPERTY) != null ? "-D" + PATH_PROPERTY : PATH_ENV)
                        + "); point it at a file or unset it.");
            }
            return read(explicit, env);
        }
        File userFile = new File(System.getProperty("user.home", ""), USER_FILE);
        if (userFile.isFile()) {
            return read(userFile, env);
        }
        File systemFile = new File(SYSTEM_FILE);
        return systemFile.isFile() ? read(systemFile, env) : null;
    }

    private static File explicitPath(UnaryOperator<String> env) {
        String property = System.getProperty(PATH_PROPERTY);
        if (property != null && !property.isBlank()) {
            return new File(property.trim());
        }
        String variable = env.apply(PATH_ENV);
        return (variable != null && !variable.isBlank()) ? new File(variable.trim()) : null;
    }

    private static ConfigDefaults read(File file, UnaryOperator<String> env) throws IOException {
        Properties properties = new Properties();
        try (var in = java.nio.file.Files.newInputStream(file.toPath())) {
            properties.load(in);
        } catch (IllegalArgumentException e) {
            // A malformed unicode escape in a value throws this, and the message alone does not name the file.
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
        LOG.debug("config: {} setting(s) from {}", properties.size(), file);
        return new ConfigDefaults(file, properties, env);
    }

    /**
     * Refuse a file that cannot mean what it says: an unknown key (a typo, or an option from another version)
     * would sit there doing nothing, and a refused key would weaken a safety gate.
     */
    void validate(CommandLine root) throws IOException {
        Set<String> known = knownKeys(root);
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            String option = key.substring(key.lastIndexOf('.') + 1);
            if (REFUSED.contains(option)) {
                throw new IOException(file + " sets '" + key + "', which has to be given on the command "
                        + "line: it decides whether a run writes without asking (yes) or deletes (prune).");
            }
            if (!known.contains(key)) {
                throw new IOException(file + " sets '" + key + "', which is not a midgie option. Keys are "
                        + "option names without the dashes (url, cacert, scope), optionally qualified with "
                        + "the command (midgie.deploy.scope).");
            }
        }
    }

    /** Every option name, bare and qualified with its command, across the whole command hierarchy. */
    private static Set<String> knownKeys(CommandLine command) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(command, keys);
        return keys;
    }

    private static void collectKeys(CommandLine command, Set<String> keys) {
        CommandSpec spec = command.getCommandSpec();
        for (OptionSpec option : spec.options()) {
            String name = stripPrefix(option.longestName());
            keys.add(name);
            keys.add(spec.qualifiedName(".") + "." + name);
        }
        for (CommandLine subcommand : command.getSubcommands().values()) {
            collectKeys(subcommand, keys);
        }
    }

    private static String stripPrefix(String name) {
        int i = 0;
        while (i < name.length() && !Character.isLetterOrDigit(name.charAt(i))) {
            i++;
        }
        return name.substring(i);
    }

    @Override
    public String defaultValue(ArgSpec argSpec) throws Exception {
        if (argSpec.isOption()) {
            String key = stripPrefix(((OptionSpec) argSpec).longestName());
            if (REFUSED.contains(key)) {
                return null;   // validate() already refused the file; belt and braces
            }
            String variable = ENV_OVERRIDES.get(key);
            if (variable != null) {
                String value = env.apply(variable);
                if (value != null && !value.isBlank()) {
                    // The environment describes the process this run was launched into; the file describes a
                    // machine. Letting the file win would mean a pod's MIDPOINT_URL was quietly ignored.
                    LOG.debug("config: {} not applied, {} is set", key, variable);
                    return null;
                }
            }
        }
        return delegate.defaultValue(argSpec);
    }

    /**
     * Whether a boolean option is set to true anywhere in the file, bare or qualified. Needed for
     * {@code insecure}, whose effect is latched before any command runs (see {@link Main#main}).
     */
    boolean isEnabled(String option) {
        for (String key : properties.stringPropertyNames()) {
            if ((key.equals(option) || key.endsWith("." + option))
                    && "true".equalsIgnoreCase(properties.getProperty(key).trim())) {
                return true;
            }
        }
        return false;
    }

    File file() {
        return file;
    }
}
