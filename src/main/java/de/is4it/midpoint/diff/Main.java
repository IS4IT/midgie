/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for the {@code midpoint-diff} CLI.
 *
 * <p>See {@code DESIGN.md} for the full design. In short: a schema-aware diff of two midPoint objects
 * that emits the minimal {@code ObjectModificationType} (itemDelta) — the body midPoint's REST
 * {@code PATCH} consumes.
 */
@Command(
        name = "midpoint-diff",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Schema-aware diff of midPoint objects into an itemDelta (ObjectModificationType).",
        subcommands = { DiffCommand.class, ApplyCommand.class }
)
public class Main implements Runnable {

    /** No subcommand given — print usage. */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
