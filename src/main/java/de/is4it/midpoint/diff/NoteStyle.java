/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import picocli.CommandLine;

/**
 * How a note looks: dim and italic on a terminal, so the lines that say what happened stand out from the
 * remarks about them, and plain text everywhere else — a pipe, a file, a CI log.
 *
 * <p>Whether to style is picocli's decision, the one that colours {@code --help}: on when standard input and
 * output are a terminal, off when {@code NO_COLOR} is set or {@code CLICOLOR=0}, and on anywhere with
 * {@code CLICOLOR_FORCE=1} or {@code -Dpicocli.ansi=true}. Java cannot ask whether standard error is a terminal,
 * so a run that redirects only standard error to a file gets the styles in that file. A terminal without the
 * styles shows the text as it is.
 */
final class NoteStyle {

    private static final String DIM_ITALIC = "\u001b[2;3m";
    private static final String RESET = "\u001b[0m";

    private static final boolean ENABLED = CommandLine.Help.Ansi.AUTO.enabled();

    private NoteStyle() {
    }

    /** Whether {@code text} — a message, or a paragraph of an object's block — is a note. */
    static boolean isNote(String text) {
        return text.startsWith("note: ");
    }

    /** {@code text} as a note: styled when this run styles notes, as it is otherwise. */
    static String of(String text) {
        return apply(text, ENABLED);
    }

    static String apply(String text, boolean enabled) {
        return enabled ? DIM_ITALIC + text + RESET : text;
    }
}
