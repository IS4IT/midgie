/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@code --debug}/{@code --trace} take effect straight from the argument list, before picocli has
 * parsed anything. Each subcommand switches logging on for itself, which covers everything it does — but not
 * what happens while the command line is being built, which is where the config files are read and merged.
 * Those decisions were logged into a logger that was still off, so {@code --debug} could not answer the one
 * question a layered config raises: which file a setting came from.
 */
class LoggingTest {

    private Level rootLevel;
    private Level ourLevel;

    @BeforeEach
    void rememberLevels() {
        rootLevel = root().getLevel();
        ourLevel = ours().getLevel();
    }

    @AfterEach
    void restoreLevels() {
        // Without this, one test switching DEBUG on globally would leave every later test logging.
        root().setLevel(rootLevel);
        ours().setLevel(ourLevel);
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static Logger ours() {
        return (Logger) LoggerFactory.getLogger(Logging.class.getPackageName());
    }

    @Test
    void debugIsOnBeforeTheConfigFilesAreRead() {
        root().setLevel(Level.INFO);

        Main.commandLine(new String[] {"deploy", "tree", "-v"});

        assertEquals(Level.DEBUG, root().getLevel(),
                "the factory reads the config files, so it has to switch logging on first");
    }

    @Test
    void traceRaisesOurOwnLoggersFurther() {
        root().setLevel(Level.INFO);
        ours().setLevel(Level.INFO);

        Logging.enableFrom(new String[] {"apply", "x.xml", "--trace"});

        assertEquals(Level.DEBUG, root().getLevel(), "the framework goes to DEBUG");
        assertEquals(Level.TRACE, ours().getLevel(), "and this tool's own loggers to TRACE");
    }

    @Test
    void anArgumentAfterASeparatorIsNotAnOption() {
        root().setLevel(Level.INFO);

        Logging.enableFrom(new String[] {"diff", "--", "-v"});

        assertEquals(Level.INFO, root().getLevel(), "'-v' after '--' is a file name, not a flag");
    }

    @Test
    void withoutTheFlagNothingChanges() {
        root().setLevel(Level.INFO);

        Logging.enableFrom(new String[] {"diff", "a.xml", "b.xml"});

        assertEquals(Level.INFO, root().getLevel());
    }
}
