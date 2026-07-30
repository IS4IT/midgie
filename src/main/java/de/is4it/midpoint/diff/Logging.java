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
     * Raise the root logger to DEBUG for troubleshooting (framework logs still go to stderr via logback.xml).
     * No-op if the SLF4J binding is not logback.
     */
    static void enableDebug() {
        Logger root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logbackRoot) {
            logbackRoot.setLevel(ch.qos.logback.classic.Level.DEBUG);
        }
    }

    /**
     * Everything {@link #enableDebug()} does (framework at DEBUG), plus this tool's own loggers at TRACE, so
     * the extra {@code LOG.trace} detail — REST request/response bodies and full HTTP error bodies — is
     * emitted <em>without</em> also unleashing the framework's very verbose TRACE logging. Implied by
     * {@code --trace}. No-op if the SLF4J binding is not logback.
     */
    static void enableTrace() {
        enableDebug();
        Logger ours = LoggerFactory.getLogger(Logging.class.getPackageName());
        if (ours instanceof ch.qos.logback.classic.Logger logbackOurs) {
            logbackOurs.setLevel(ch.qos.logback.classic.Level.TRACE);
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
