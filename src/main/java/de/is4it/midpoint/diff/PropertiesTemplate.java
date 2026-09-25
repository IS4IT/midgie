/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The annotated {@code midgie.properties} that {@code init --write-properties} writes: every option that can
 * carry a default, commented out, with the help text that belongs to it.
 *
 * <p>It is generated from the command model rather than kept as a literal, so an option added later appears
 * here without anyone remembering to — a hand-maintained template would drift into describing a version of
 * midgie that no longer exists, which is worse than none. Options that only make sense per run are left out
 * (help, version, the debug switches), as are the two a file may not set at all.
 */
final class PropertiesTemplate {

    /** Never worth a default: they answer "what is this run doing", not "how is this machine set up". */
    private static final Set<String> SKIPPED = Set.of("help", "version", "debug", "trace", "dry-run", "yes",
            "confirm-each-apply", "format", "output", "write-policy", "write-properties", "write-cacert", "create-state",
            "plan", "apply-plan",
            // Parse for compatibility but do nothing; offering them here would generate a file that
            // ConfigDefaults then refuses. The '--no-…' forms carry the setting instead.
            "create-if-absent", "match-by-name", "prune");

    private PropertiesTemplate() {
    }

    /** Render the template for the whole command hierarchy under {@code root}. */
    static String render(CommandSpec root) {
        return render(root, false);
    }

    /**
     * Render the template, optionally for a <b>tree-local</b> file.
     *
     * <p>The tree variant offers only the keys {@link ConfigDefaults#TREE_ALLOWED} permits, and it
     * <em>omits</em> the rest rather than commenting them out. The template's contract — pinned by
     * {@code InitCommandTest} — is that uncommenting every line yields a file midgie accepts; a commented-out
     * {@code url} would break exactly that, and would read as an invitation besides. What was left out, and
     * where it belongs, is said in the header instead.
     */
    static String render(CommandSpec root, boolean forTree) {
        Map<String, OptionSpec> options = new TreeMap<>();       // option name -> its spec (first seen)
        Map<String, Set<String>> commands = new LinkedHashMap<>(); // option name -> commands offering it
        List<String> subcommands = new ArrayList<>();

        for (CommandLine subcommand : root.subcommands().values()) {
            CommandSpec spec = subcommand.getCommandSpec();
            subcommands.add(spec.name());
            for (OptionSpec option : spec.options()) {
                String key = stripPrefix(option.longestName());
                if (SKIPPED.contains(key) || (forTree && !ConfigDefaults.TREE_ALLOWED.contains(key))) {
                    continue;
                }
                options.putIfAbsent(key, option);
                commands.computeIfAbsent(key, name -> new LinkedHashSet<>()).add(spec.name());
            }
        }

        StringBuilder out = new StringBuilder();
        if (forTree) {
            treeHeader(out, root.name());
        } else {
            header(out, root.name());
        }

        // Shared first, with bare keys. "Shared" is more than one command rather than all of them: a bare
        // key only reaches a command that has such an option anyway, so writing 'scope' once beats writing
        // it under diff, apply and deploy separately just because init has no --scope.
        out.append("\n# ---- any command that has the option ---------------------------------------\n");
        for (Map.Entry<String, OptionSpec> entry : options.entrySet()) {
            if (commands.get(entry.getKey()).size() > 1) {
                setting(out, entry.getKey(), entry.getValue());
            }
        }

        for (String subcommand : subcommands) {
            List<Map.Entry<String, OptionSpec>> own = options.entrySet().stream()
                    .filter(entry -> commands.get(entry.getKey()).contains(subcommand))
                    .filter(entry -> commands.get(entry.getKey()).size() == 1)
                    .toList();
            if (own.isEmpty()) {
                continue;
            }
            out.append("\n# ---- ").append(subcommand).append(' ')
                    .append("-".repeat(Math.max(3, 70 - subcommand.length()))).append('\n');
            for (Map.Entry<String, OptionSpec> entry : own) {
                // Qualified, since the key means something only for this command; the bare form would apply
                // it to every command that happens to share the name.
                setting(out, root.name() + "." + subcommand + "." + entry.getKey(), entry.getValue());
            }
        }
        return out.toString();
    }

    private static void header(StringBuilder out, String program) {
        out.append("# ").append(program).append(".properties — option defaults, written by '")
                .append(program).append(" init --write-properties'.\n")
                .append("#\n")
                .append("# Every setting is commented out: uncomment the ones this machine should not have to\n")
                .append("# repeat on the command line. Keys are option names without the dashes. A key under a\n")
                .append("# command heading is qualified with that command, so it applies there only.\n")
                .append("# A relative file or directory is read from the directory this file is in.\n")
                .append("#\n")
                .append("# Read from, and merged in this order (a later file wins a key it sets):\n")
                .append("#   .midgie.properties in the deployed tree, or above it\n")
                .append("#   /etc/midgie/midgie.properties\n")
                .append("#   $HOME/.midgie.properties\n")
                .append("#   the file named by $MIDGIE_CONF or -Dmidgie.conf\n")
                .append("#\n")
                .append("# The tree-local file is the odd one out: it travels with the repository, so it may\n")
                .append("# only set a fixed list of keys and it loses to every file above. Write one with\n")
                .append("# 'midgie init --write-properties <tree>/.midgie.properties' and it lists just those.\n")
                .append("#\n")
                .append("# Precedence: an option on the command line wins, then MIDPOINT_URL / MIDPOINT_USER /\n")
                .append("# MIDPOINT_PASSWORD, then this file, then the built-in default. 'midgie <command> -v'\n")
                .append("# names the file each setting came from.\n")
                .append("#\n")
                .append("# 'yes' cannot be set here: whether a run writes without asking is not something a\n")
                .append("# file on disk gets to answer.\n");
    }

    /**
     * The header for a tree-local file. It has to answer a question the other one does not: why the keys you
     * were looking for are not here.
     */
    private static void treeHeader(StringBuilder out, String program) {
        out.append("# ").append(ConfigDefaults.TREE_FILE)
                .append(" — settings that belong to THIS deployment, written by\n")
                .append("# '").append(program).append(" init --write-properties ")
                .append(ConfigDefaults.TREE_FILE).append("'.\n")
                .append("#\n")
                .append("# This file travels with the repository, so it is read for anyone who checks the tree\n")
                .append("# out. That is the whole reason it may set only the keys below: a file that arrives\n")
                .append("# with somebody else's clone must not be able to decide where your credentials go.\n")
                .append("# Anything not listed here is an error naming this file, not a setting that is\n")
                .append("# quietly ignored.\n")
                .append("#\n")
                .append("# A relative path is read from the directory this file is in, so 'policy = midgie.policy'\n")
                .append("# names the same file from anywhere in the checkout.\n")
                .append("#\n")
                .append("# NOT settable here, and where they belong instead:\n")
                .append("#   url, user, password, password-env, password-file, insecure, cacert,\n")
                .append("#   proxy, no-proxy, proxy-user, proxy-password, proxy-password-env,\n")
                .append("#   proxy-password-file\n")
                .append("#       — they decide where credentials go: ~/").append(ConfigDefaults.USER_FILE)
                .append(", $MIDGIE_CONF, or the\n")
                .append("#         MIDPOINT_URL / MIDPOINT_USER / MIDPOINT_PASSWORD / https_proxy\n")
                .append("#         environment variables.\n")
                .append("#   yes, no-rollback-plan\n")
                .append("#       — a file may say WHERE something goes, never WHETHER a safety gate applies:\n")
                .append("#         pass them on the command line.\n")
                .append("#\n")
                .append("# This is the lowest-precedence file: /etc/midgie/midgie.properties, your own\n")
                .append("# ~/").append(ConfigDefaults.USER_FILE)
                .append(" and $MIDGIE_CONF all override it, as do the environment\n")
                .append("# and the command line. The tree suggests; the operator decides. Each run prints\n")
                .append("# this file and the keys it supplied.\n");
    }

    private static void setting(StringBuilder out, String key, OptionSpec option) {
        out.append('\n');
        for (String line : wrap(String.join(" ", option.description()), 88)) {
            out.append("# ").append(line).append('\n');
        }
        String value = defaultOf(option);
        out.append('#').append(key).append(" =").append(value.isEmpty() ? "" : " " + value).append('\n');
    }

    /**
     * What the option does when nobody sets it — so uncommenting a line changes nothing until its value is
     * edited, and the file doubles as a record of the defaults.
     *
     * <p>Two sources, because picocli only reports a {@code defaultValue} that was declared in the annotation:
     * a flag like {@code --insecure} has none, and its real default lives in the field's initial value
     * ({@code false}). A repeatable option has no single default to state, so it stays blank rather than
     * showing an empty array.
     */
    private static String defaultOf(OptionSpec option) {
        String declared = option.defaultValue();
        if (declared != null) {
            return declared;
        }
        Object initial = option.hasInitialValue() ? option.initialValue() : null;
        if (initial == null || initial.getClass().isArray() || initial instanceof Iterable<?>) {
            return "";
        }
        return String.valueOf(initial);
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String stripPrefix(String name) {
        int i = 0;
        while (i < name.length() && !Character.isLetterOrDigit(name.charAt(i))) {
            i++;
        }
        return name.substring(i);
    }
}
