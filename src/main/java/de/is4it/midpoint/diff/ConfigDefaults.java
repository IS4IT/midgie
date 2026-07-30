/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Defaults for any option, read from the {@code midgie.properties} files on the search path.
 *
 * <p>It exists because the settings that never change for an environment — the server URL, the account, the
 * CA certificate that makes TLS verify — had to be repeated on every command line, and the only alternative
 * was {@code --insecure}. A file is also the only place a container can carry them: a ConfigMap mounts as a
 * file, and {@code $HOME} in the pod can be persistent.
 *
 * <p><b>Where it comes from</b>: {@code /etc/midgie/midgie.properties}, then {@code $HOME/.midgie.properties},
 * then a file named by the {@code midgie.conf} system property or the {@code MIDGIE_CONF} environment
 * variable. All of them are read and <b>merged per key</b>, later layers winning — so a site file holds what
 * is true of the machine (a CA certificate) while a user file adds only what differs (the account), and
 * neither repeats the other. Naming a file explicitly is the most deliberate of the three and sits on top;
 * because it was named on purpose, a path that does not exist is an error rather than a silent skip.
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

    private final List<File> sources;
    private final Properties properties;
    private final Map<String, File> origin;
    private final CommandLine.PropertiesDefaultProvider delegate;
    private final UnaryOperator<String> env;

    private ConfigDefaults(List<File> sources, Properties properties, Map<String, File> origin,
            UnaryOperator<String> env) {
        this.sources = sources;
        this.properties = properties;
        this.origin = origin;
        this.delegate = new CommandLine.PropertiesDefaultProvider(properties);
        this.env = env;
    }

    /** Load and merge the configured files, or {@code null} when there are none. */
    static ConfigDefaults load() throws IOException {
        return load(System::getenv);
    }

    static ConfigDefaults load(UnaryOperator<String> env) throws IOException {
        return load(env, new File(SYSTEM_FILE), new File(System.getProperty("user.home", ""), USER_FILE));
    }

    /**
     * The layers, lowest precedence first: the site file, the user's file, then a file named explicitly.
     * They are <em>merged per key</em> rather than the first one found winning outright, so a site file can
     * hold what is true of the machine (a CA certificate, a scope) while a user file adds only the account —
     * and neither has to repeat the other. A file named by hand sits on top, since naming it is the most
     * deliberate act of the three, and a path that does not exist is an error rather than a silent skip.
     *
     * <p>{@code siteFile} and {@code userFile} are parameters so a test can point them somewhere writable.
     */
    static ConfigDefaults load(UnaryOperator<String> env, File siteFile, File userFile) throws IOException {
        List<File> layers = new ArrayList<>();
        addIfPresent(layers, siteFile);
        addIfPresent(layers, userFile);
        File explicit = explicitPath(env);
        if (explicit != null) {
            if (!explicit.isFile()) {
                throw new IOException("no config file at " + explicit + " (named by "
                        + (System.getProperty(PATH_PROPERTY) != null ? "-D" + PATH_PROPERTY : PATH_ENV)
                        + "); point it at a file or unset it.");
            }
            addIfPresent(layers, explicit);
        }
        if (layers.isEmpty()) {
            // Saying so matters as much as listing what was found: without this line, "no file anywhere" and
            // "the flag did not work" look identical, which is the wrong thing to be guessing about while
            // troubleshooting where a setting came from.
            LOG.debug("config: no midgie.properties on the search path ({}, {}); every option keeps its "
                    + "built-in default", siteFile, userFile);
            return null;
        }

        Properties merged = new Properties();
        Map<String, File> origin = new LinkedHashMap<>();
        for (File file : layers) {
            Properties layer = read(file);
            LOG.debug("config: {} setting(s) from {}", layer.size(), file);
            for (String key : new TreeSet<>(layer.stringPropertyNames())) {
                File previous = origin.get(key);
                if (previous != null) {
                    LOG.debug("config:   {} from {} overrides {}", key, file, previous);
                }
                merged.setProperty(key, layer.getProperty(key));
                origin.put(key, file);   // last layer to set a key owns it, and answers for it
            }
        }
        // Keys and their source, never values: one of these keys is 'password'.
        for (Map.Entry<String, File> entry : new TreeMap<>(origin).entrySet()) {
            LOG.debug("config: {} <- {}", entry.getKey(), entry.getValue());
        }
        return new ConfigDefaults(List.copyOf(layers), merged, origin, env);
    }

    /** Add a readable file, skipping one already in the list — an explicit path may name the user's file. */
    private static void addIfPresent(List<File> layers, File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        if (!file.canRead()) {
            // A file that is there but unreadable is a different problem from one that is absent, and
            // skipping it without a word leaves the settings in it mysteriously not applying.
            LOG.debug("config: {} exists but is not readable; skipped", file);
            return;
        }
        for (File present : layers) {
            if (sameFile(present, file)) {
                return;
            }
        }
        layers.add(file);
    }

    private static boolean sameFile(File one, File other) {
        try {
            return one.getCanonicalFile().equals(other.getCanonicalFile());
        } catch (IOException e) {
            return one.getAbsoluteFile().equals(other.getAbsoluteFile());
        }
    }

    private static File explicitPath(UnaryOperator<String> env) {
        String property = System.getProperty(PATH_PROPERTY);
        if (property != null && !property.isBlank()) {
            return new File(property.trim());
        }
        String variable = env.apply(PATH_ENV);
        return (variable != null && !variable.isBlank()) ? new File(variable.trim()) : null;
    }

    private static Properties read(File file) throws IOException {
        Properties properties = new Properties();
        try (var in = java.nio.file.Files.newInputStream(file.toPath())) {
            properties.load(in);
        } catch (IllegalArgumentException e) {
            // A malformed unicode escape in a value throws this, and the message alone does not name the file.
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
        return properties;
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
                throw new IOException(origin.get(key) + " sets '" + key + "', which has to be given on the command "
                        + "line: it decides whether a run writes without asking (yes) or deletes (prune).");
            }
            if (!known.contains(key)) {
                throw new IOException(origin.get(key) + " sets '" + key + "', which is not a midgie option. Keys are "
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

    /** The files this configuration came from, lowest precedence first. */
    List<File> sources() {
        return sources;
    }
}
