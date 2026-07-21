/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Per-object diff-strategy overrides for {@code deploy}, loaded from a {@code glob = <strategy flags>}
 * file. Each line maps a path glob to a full strategy (the same {@code --scope}/{@code --multivalue}/
 * {@code --path}/{@code --equivalence}/{@code --allow-inplace-merge} grammar as the global flags, reused
 * verbatim via picocli). For a given source file the <b>first</b> matching glob wins; unmatched files use
 * the global strategy. Blank lines and {@code #} comments are ignored.
 *
 * <p>Globs match the source path as given on the command line, so a tree-relative rule normally starts
 * with {@code **}, e.g. {@code **}{@code /roles/*.xml = --scope full}.
 */
final class Overrides {

    private static final class Rule {
        final PathMatcher matcher;
        final StrategyOptions.Resolved strategy;

        Rule(PathMatcher matcher, StrategyOptions.Resolved strategy) {
            this.matcher = matcher;
            this.strategy = strategy;
        }
    }

    /** Holder so picocli can parse a line's flags with the real {@link StrategyOptions} grammar. */
    @Command(name = "override")
    private static final class StrategyHolder {
        @Mixin
        StrategyOptions strategy = new StrategyOptions();
    }

    private final List<Rule> rules;
    private final StrategyOptions.Resolved global;

    private Overrides(List<Rule> rules, StrategyOptions.Resolved global) {
        this.rules = rules;
        this.global = global;
    }

    /** Parse the overrides file; {@code global} is the fallback for files no rule matches. */
    static Overrides load(File file, StrategyOptions.Resolved global) throws IOException {
        List<Rule> rules = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new IOException("overrides line " + lineNumber + " must be 'glob = flags': " + raw);
            }
            String glob = line.substring(0, equals).strip();
            String flags = line.substring(equals + 1).strip();
            StrategyOptions.Resolved strategy = parseStrategy(flags);
            if (strategy == null) {
                throw new IOException("overrides line " + lineNumber + " has invalid strategy flags: " + flags);
            }
            rules.add(new Rule(FileSystems.getDefault().getPathMatcher("glob:" + glob), strategy));
        }
        return new Overrides(rules, global);
    }

    /** The strategy for {@code sourceFile}: the first matching rule, else the global default. */
    StrategyOptions.Resolved strategyFor(File sourceFile) {
        Path path = sourceFile.toPath();
        for (Rule rule : rules) {
            if (rule.matcher.matches(path)) {
                return rule.strategy;
            }
        }
        return global;
    }

    private static StrategyOptions.Resolved parseStrategy(String flags) {
        StrategyHolder holder = new StrategyHolder();
        try {
            new CommandLine(holder).parseArgs(flags.isBlank() ? new String[0] : flags.split("\\s+"));
        } catch (Exception e) {
            return null;
        }
        return holder.strategy.resolve();
    }
}
