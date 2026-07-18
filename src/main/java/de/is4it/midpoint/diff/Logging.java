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
}
