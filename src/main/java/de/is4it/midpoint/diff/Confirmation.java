/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

/** Outcome of a pre-write confirmation, decided without any I/O so it can be unit-tested. */
enum Confirmation {
    PROCEED, ABORTED, NON_INTERACTIVE;

    /**
     * {@code --yes} proceeds; a session without a console refuses rather than assumes; otherwise only an
     * explicit {@code y}/{@code yes} proceeds, so the default answer is no.
     */
    static Confirmation decide(boolean autoYes, boolean interactive, String answer) {
        if (autoYes) {
            return PROCEED;
        }
        if (!interactive) {
            return NON_INTERACTIVE;
        }
        String normalized = (answer == null) ? "" : answer.trim().toLowerCase();
        return (normalized.equals("y") || normalized.equals("yes")) ? PROCEED : ABORTED;
    }
}
