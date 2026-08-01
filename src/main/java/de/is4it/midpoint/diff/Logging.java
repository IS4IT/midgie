/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime log-level control for {@code --debug}. */
final class Logging {

    private Logging() {
    }

    /**
     * Raise <em>this tool's</em> loggers to DEBUG: what midgie is doing, one line per step and per
     * object-level decision. Deliberately not the root logger — prism's own DEBUG output runs to a dozen
     * lines ({@code ClassPathUtil} alone prints one per scanned package) before midgie has done anything,
     * which drowned the flow this switch exists to show. {@code --trace} still turns it on. No-op if the
     * SLF4J binding is not logback.
     */
    static void enableDebug() {
        setLevel(LoggerFactory.getLogger(Logging.class.getPackageName()), ch.qos.logback.classic.Level.DEBUG);
    }

    /**
     * Everything for real debugging: this tool's own loggers at TRACE — per-file and per-value decisions,
     * stack traces, REST request/response bodies — plus the framework at DEBUG, since a prism or schema
     * problem is diagnosed from its own logs. Implied by {@code --trace}. No-op if the SLF4J binding is not
     * logback.
     */
    static void enableTrace() {
        setLevel(LoggerFactory.getLogger(Logging.class.getPackageName()), ch.qos.logback.classic.Level.TRACE);
        setLevel(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME), ch.qos.logback.classic.Level.DEBUG);
    }

    private static void setLevel(Logger logger, ch.qos.logback.classic.Level level) {
        if (logger instanceof ch.qos.logback.classic.Logger logback) {
            logback.setLevel(level);
        }
    }

    /**
     * Switch logging on straight from the argument list, before picocli has parsed anything.
     *
     * <p>Each subcommand does this again for itself, which is enough for everything it does — but not for
     * what happens while the command line is being <em>built</em>: reading and merging the config files. Those
     * decisions were logged into a logger that was still off, so {@code --debug} could never answer the one
     * question a layered config raises, which is where a setting came from. Anything after a {@code --}
     * separator is a positional argument, not an option.
     */
    static void enableFrom(String[] args) {
        for (String arg : args) {
            if ("--".equals(arg)) {
                return;
            }
            if ("--trace".equals(arg)) {
                enableTrace();
                return;
            }
        }
        for (String arg : args) {
            if ("--".equals(arg)) {
                return;
            }
            if ("--debug".equals(arg) || "-v".equals(arg)) {
                enableDebug();
                return;
            }
        }
    }
}
