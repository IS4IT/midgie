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
            "prune", "confirm-each-apply", "write-policy", "write-properties", "write-cacert", "create-state",
            "plan", "apply-plan");

    private PropertiesTemplate() {
    }

    /** Render the template for the whole command hierarchy under {@code root}. */
    static String render(CommandSpec root) {
        Map<String, OptionSpec> options = new TreeMap<>();       // option name -> its spec (first seen)
        Map<String, Set<String>> commands = new LinkedHashMap<>(); // option name -> commands offering it
        List<String> subcommands = new ArrayList<>();

        for (CommandLine subcommand : root.subcommands().values()) {
            CommandSpec spec = subcommand.getCommandSpec();
            subcommands.add(spec.name());
            for (OptionSpec option : spec.options()) {
                String key = stripPrefix(option.longestName());
                if (SKIPPED.contains(key)) {
                    continue;
                }
                options.putIfAbsent(key, option);
                commands.computeIfAbsent(key, name -> new LinkedHashSet<>()).add(spec.name());
            }
        }

        StringBuilder out = new StringBuilder();
        header(out, root.name());

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
                .append("#\n")
                .append("# Read from, and merged in this order (a later file wins a key it sets):\n")
                .append("#   /etc/midgie/midgie.properties\n")
                .append("#   $HOME/.midgie.properties\n")
                .append("#   the file named by $MIDGIE_CONF or -Dmidgie.conf\n")
                .append("#\n")
                .append("# Precedence: an option on the command line wins, then MIDPOINT_URL / MIDPOINT_USER /\n")
                .append("# MIDPOINT_PASSWORD, then this file, then the built-in default. 'midgie <command> -v'\n")
                .append("# names the file each setting came from.\n")
                .append("#\n")
                .append("# 'yes' and 'prune' cannot be set here: whether a run writes without asking, or\n")
                .append("# deletes, is not something a file on disk gets to answer.\n");
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
