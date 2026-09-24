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
 * note exists to prevent. {@code yes} is refused outright: it decides whether a run writes without asking,
 * and a file on disk is not where a human confirms that.
 */
final class ConfigDefaults implements CommandLine.IDefaultValueProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigDefaults.class);

    /** System property naming the file (also settable through JAVA_OPTS, which the launcher passes on). */
    static final String PATH_PROPERTY = "midgie.conf";

    /** Environment variable naming the file. */
    static final String PATH_ENV = "MIDGIE_CONF";

    static final String USER_FILE = ".midgie.properties";
    static final String SYSTEM_FILE = "/etc/midgie/midgie.properties";

    /** The tree-local file, searched for upward from the working directory. */
    static final String TREE_FILE = ".midgie.properties";

    /**
     * Whether {@code target} is a path midgie would read as a <em>tree-local</em> file rather than as the
     * user's own — the same judgement {@link #load} makes, since the two share a name and differ only in
     * where they sit. Used by {@code init --write-properties} to decide which template to write.
     */
    static boolean isTreeFilePath(File target) {
        if (target == null || !TREE_FILE.equals(target.getName())) {
            return false;
        }
        return !sameFile(target, new File(System.getProperty("user.home", ""), USER_FILE));
    }

    /**
     * The filename midgie would <em>read</em> from {@code dir} — so {@code init --write-properties <dir>}
     * writes a file that is actually picked up rather than one that sits there doing nothing.
     *
     * <p>Only the site directory differs: {@code /etc/midgie/midgie.properties} carries no leading dot,
     * while the user's file and a tree-local one are both {@code .midgie.properties}. Derived from the same
     * constants the search path uses, not a convention invented here.
     */
    static String propertiesNameFor(File dir) {
        File siteDir = new File(SYSTEM_FILE).getParentFile();
        if (dir != null && siteDir != null && sameFile(dir, siteDir)) {
            return new File(SYSTEM_FILE).getName();
        }
        return USER_FILE;
    }

    /**
     * Options a file may not set. {@code yes} authorises something irreversible without a person present: it
     * turns off the confirmation that stands between a tree and a live server. A default that quietly makes
     * every later run non-interactive is exactly the accident this tool refuses elsewhere. ({@code prune} was
     * refused for the same reason while deleting was opt-in; removing what a tracked tree dropped is the
     * default now, still behind that confirmation, and {@code no-prune} — which only ever deletes less — may
     * be set anywhere.)
     */
    private static final Set<String> REFUSED = Set.of("yes");

    /**
     * Keys that named a flag which has since become the default. The option still parses on the command
     * line so that existing scripts keep working, which is exactly why a file setting it must be refused
     * rather than ignored: it would sit there meaning the opposite of what its author intended, and this
     * class exists to stop a setting that silently does nothing.
     */
    private static final Map<String, String> NOW_DEFAULT = Map.of(
            "create-if-absent", "no-create-if-absent",
            "match-by-name", "no-match-by-name",
            "prune", "no-prune");

    /**
     * The only options a <b>tree-local</b> file may set.
     *
     * <p>An allowlist, not an extension of {@link #REFUSED}, and the difference matters. The site and user
     * files belong to the operator, so naming the few dangerous keys is enough. A tree file arrives with
     * somebody else's repository: cloning it and running even {@code deploy --dry-run} would authenticate to
     * whatever server that file names, so a denylist that fails <em>open</em> for every option added later is
     * the wrong shape. These keys describe how a deployment wants to be reconciled and where its safety net
     * belongs; {@code url}, {@code user}, {@code password*}, {@code insecure}, {@code cacert} and
     * {@code proxy*} decide where credentials go and are deliberately absent — a proxy most of all, since a
     * cloned repository naming one would route somebody else's Basic header through a machine of its
     * choosing.
     *
     * <p>{@code no-rollback-plan} is absent too, on the rule the class already applies to {@code yes}: a file
     * may say <em>where</em> something goes, never <em>whether</em> a safety gate is there at all. A tree may
     * relocate its net; it may not switch it off. {@code no-prune} is allowed: it only ever deletes less.
     *
     * <p>Package-visible so {@link PropertiesTemplate} filters on the same list this validates against. Two
     * copies would drift, and the drift would be silent in the worse direction: a template offering a key
     * that is then refused at load.
     */
    static final Set<String> TREE_ALLOWED = Set.of(
            "rollback-plan", "policy", "scope", "multivalue", "equivalence", "path", "app",
            "schema", "schema-dir", "no-match-by-name", "no-create-if-absent", "retry", "smart-refs",
            "allow-inplace-merge", "no-live-schema", "no-prune");

    /**
     * Keys whose environment variable, when set, outranks the file.
     *
     * <p>A list rather than one name because the proxy is spelled four ways: scheme-dependent
     * ({@code https_proxy} for an https server, {@code http_proxy} for a plain one) and in either case,
     * conventionally, in either case of letters. Any of them being set is enough to stand the file down —
     * which of them actually applies is {@link ProxyConfig}'s decision, made once the target URL is known,
     * and defaults are resolved before there is a URL to look at. So an environment that sets only the
     * variable for the <em>other</em> scheme suppresses a {@code proxy} in the file without replacing it, and
     * that run goes direct. Deliberate: a process launched with proxy variables has been told how this
     * machine reaches the network, and a file describing a different machine should not overrule it.
     */
    private static final Map<String, List<String>> ENV_OVERRIDES = Map.of(
            "url", List.of("MIDPOINT_URL"),
            "user", List.of("MIDPOINT_USER"),
            "password", List.of("MIDPOINT_PASSWORD"),
            "proxy", List.of("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY"),
            "no-proxy", List.of("no_proxy", "NO_PROXY"));

    private final List<File> sources;
    private final Properties properties;
    private final Map<String, File> origin;
    private final CommandLine.PropertiesDefaultProvider delegate;
    private final UnaryOperator<String> env;
    private final File treeFile;

    private ConfigDefaults(List<File> sources, Properties properties, Map<String, File> origin,
            UnaryOperator<String> env, File treeFile) {
        this.sources = sources;
        this.properties = properties;
        this.origin = origin;
        this.delegate = new CommandLine.PropertiesDefaultProvider(properties);
        this.env = env;
        this.treeFile = treeFile;
    }

    /** Load and merge the configured files, or {@code null} when there are none. */
    static ConfigDefaults load() throws IOException {
        return load(System::getenv);
    }

    static ConfigDefaults load(UnaryOperator<String> env) throws IOException {
        return load(env, new File(SYSTEM_FILE), new File(System.getProperty("user.home", ""), USER_FILE),
                findTreeFile(new File(System.getProperty("user.dir", "."))));
    }

    static ConfigDefaults load(UnaryOperator<String> env, File siteFile, File userFile) throws IOException {
        return load(env, siteFile, userFile, null);
    }

    /**
     * The nearest {@code .midgie.properties} at or above {@code start}, or {@code null}.
     *
     * <p>Upward from the working directory rather than from the deployed path, because the config has to be
     * in hand before the command line is parsed and the path is not known yet. That makes it the same
     * convention {@code .editorconfig} and {@code .gitignore} use, and it is predictable: the file that
     * applies is the one belonging to the checkout you are standing in.
     *
     * <p>The walk stops at a repository root, so it cannot wander out of one checkout and into a parent
     * directory that happens to hold an unrelated file.
     */
    private static File findTreeFile(File start) {
        File dir = start.getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, TREE_FILE);
            if (candidate.isFile()) {
                return candidate;
            }
            if (new File(dir, ".git").exists()) {
                return null; // the repository root, and it has no tree file
            }
            dir = dir.getParentFile();
        }
        return null;
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
    static ConfigDefaults load(UnaryOperator<String> env, File siteFile, File userFile, File treeFile)
            throws IOException {
        // The tree file shares a name with the user's. If the upward walk landed on the user's own file, it
        // is not a tree file at all, and validating it against the allowlist would reject the account the
        // operator legitimately keeps there.
        if (treeFile != null && userFile != null && sameFile(treeFile, userFile)) {
            treeFile = null;
        }
        List<File> layers = new ArrayList<>();
        // Lowest precedence of the file layers: the tree suggests, the operator's own files override.
        addIfPresent(layers, treeFile);
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
            LOG.trace("config: {} <- {}", entry.getKey(), entry.getValue());
        }
        return new ConfigDefaults(List.copyOf(layers), merged, origin, env, treeFile);
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
                        + "line: it decides whether a run writes without asking.");
            }
            String replacement = NOW_DEFAULT.get(option);
            if (replacement != null) {
                throw new IOException(origin.get(key) + " sets '" + key + "', which no longer decides "
                        + "anything: " + option + " is the default now. Remove it, or set '" + replacement
                        + " = true' to turn that behaviour off.");
            }
            if (!known.contains(key)) {
                throw new IOException(origin.get(key) + " sets '" + key + "', which is not a midgie option. Keys are "
                        + "option names without the dashes (url, cacert, scope), optionally qualified with "
                        + "the command (midgie.deploy.scope).");
            }
            if (treeFile != null && sameFile(treeFile, origin.get(key)) && !TREE_ALLOWED.contains(option)) {
                throw new IOException(origin.get(key) + " sets '" + key + "', which a tree-local config file "
                        + "may not set: it travels with the repository, and this key decides where "
                        + "credentials go or whether a safety gate applies. Allowed here: "
                        + String.join(", ", new TreeSet<>(TREE_ALLOWED)) + ". Put the rest in "
                        + "~/" + USER_FILE + " or pass it on the command line.");
            }
        }
    }

    /**
     * The tree-local file and the keys it supplied, or {@code null} when there is none.
     *
     * <p>Worth printing at the top of a run rather than logging at debug: a config file that came with a
     * checkout is the one an operator is least likely to know about, and for a key like {@code rollback-plan}
     * it is also what tells them where to look after a bad deploy.
     */
    String treeFileSummary() {
        if (treeFile == null) {
            return null;
        }
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, File> entry : origin.entrySet()) {
            if (sameFile(treeFile, entry.getValue())) {
                keys.add(entry.getKey());
            }
        }
        return keys.isEmpty() ? null : treeFile + " supplied " + String.join(", ", keys);
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
            for (String variable : ENV_OVERRIDES.getOrDefault(key, List.of())) {
                String value = env.apply(variable);
                if (value != null && !value.isBlank()) {
                    // The environment describes the process this run was launched into; the file describes a
                    // machine. Letting the file win would mean a pod's MIDPOINT_URL was quietly ignored.
                    LOG.trace("config: {} not applied, {} is set", key, variable);
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
    /** Whether the file gives this option any value at all — for the settings that are not simple switches. */
    boolean isSet(String option) {
        for (String key : properties.stringPropertyNames()) {
            if ((key.equals(option) || key.endsWith("." + option))
                    && !properties.getProperty(key).isBlank()) {
                return true;
            }
        }
        return false;
    }

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
