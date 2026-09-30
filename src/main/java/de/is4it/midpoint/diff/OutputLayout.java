/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.Console;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two-column layout of a run's per-object output: the result in column 1, and everything about the object
 * in column 2 — where it came from and what it is, the change, and the warnings and notes about it.
 *
 * <pre>
 *   would-patch   roles/role-b.xml  RoleType/Role B
 *                 2 change(s): replace description
 *                 note: keeps live value(s) the tree does not list: inducement ×1 (multivalue=merge;
 *                   a '&lt;path&gt;  multivalue=replace' policy rule makes the tree own such a list)
 * </pre>
 *
 * <p>Notes used to be printed on their own, before the row they were about and on another stream, so which
 * note belonged to which object had to be worked out from a repeated object path. A block is printed in one go,
 * on one stream, so it stays together in a terminal and in a CI log alike.
 *
 * <p>Column 2 is word-wrapped to the terminal's width, a paragraph's continuation lines indented a little
 * further so a wrapped note still reads as one. Where there is no terminal — CI, a pipe, a file — nothing is
 * wrapped unless {@code COLUMNS} says how wide to go: a log viewer wraps long lines itself, and a message
 * searched for in a log is easier to find on one line.
 */
final class OutputLayout {

    private static final Logger LOG = LoggerFactory.getLogger(OutputLayout.class);

    /** Column 1's text width: {@code would-restore}, the longest result, fits. */
    static final int LABEL_WIDTH = 13;

    /** Where column 2 starts: two spaces, the label, one space. */
    static final int COLUMN_2 = 2 + LABEL_WIDTH + 1;

    /** How much further a paragraph's continuation lines, and a paragraph's sub-items, are indented. */
    static final int STEP = 2;

    /** Narrower than this, column 2 is not wrapped at all: one word per line helps nobody. */
    static final int MIN_COLUMN_2 = 30;

    private OutputLayout() {
    }

    /** One paragraph of column 2: its text, and how far it is indented past column 2. */
    private record Paragraph(String text, int indent) {
    }

    /**
     * One object's block: a result label, the head line (source and target), the change detail, and the warnings
     * and notes about it, each with optional items indented under it.
     */
    static final class Block {
        private final String label;
        private final String head;
        private final List<Paragraph> paragraphs = new ArrayList<>();

        Block(String label, String head) {
            this.label = label;
            this.head = head;
        }

        /** A paragraph at column 2; blank text adds nothing. */
        Block add(String text) {
            if (text != null && !text.isBlank()) {
                paragraphs.add(new Paragraph(text.strip(), 0));
            }
            return this;
        }

        /** An item under the paragraph before it, indented one step further. */
        Block addItem(String text) {
            if (text != null && !text.isBlank()) {
                paragraphs.add(new Paragraph(text.strip(), STEP));
            }
            return this;
        }

        /** The block's lines for a terminal {@code width} columns wide; {@code width <= 0} means do not wrap. */
        List<String> render(int width) {
            boolean wrap = width - COLUMN_2 >= MIN_COLUMN_2;
            List<String> lines = new ArrayList<>();
            String column1 = "  " + pad(label, LABEL_WIDTH) + " ";
            List<String> headLines = wrap ? wrap(head, width - COLUMN_2, width - COLUMN_2 - STEP) : List.of(head);
            for (int i = 0; i < headLines.size(); i++) {
                lines.add(((i == 0) ? column1 : spaces(COLUMN_2 + STEP)) + headLines.get(i));
            }
            for (Paragraph paragraph : paragraphs) {
                int start = COLUMN_2 + paragraph.indent();
                List<String> chunks = wrap
                        ? wrap(paragraph.text(), Math.max(1, width - start), Math.max(1, width - start - STEP))
                        : List.of(paragraph.text());
                for (int i = 0; i < chunks.size(); i++) {
                    lines.add(spaces((i == 0) ? start : start + STEP) + chunks.get(i));
                }
            }
            return lines;
        }
    }

    /**
     * {@code text} broken at spaces into lines of at most {@code first} characters for the first and {@code rest}
     * for every other. Runs of spaces inside a line are kept; a break swallows them. A word longer than the line
     * — a long item path, a URL — is cut, since there is nowhere else for it to go.
     */
    static List<String> wrap(String text, int first, int rest) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int limit = first;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int wordStart = i;
            while (wordStart < n && text.charAt(wordStart) == ' ') {
                wordStart++;
            }
            int wordEnd = wordStart;
            while (wordEnd < n && text.charAt(wordEnd) != ' ') {
                wordEnd++;
            }
            if (wordStart == wordEnd) {
                break; // trailing spaces
            }
            String separator = text.substring(i, wordStart);
            String word = text.substring(wordStart, wordEnd);
            i = wordEnd;
            if (line.length() > 0) {
                if (line.length() + separator.length() + word.length() <= limit) {
                    line.append(separator).append(word);
                    continue;
                }
                lines.add(line.toString());
                line.setLength(0);
                limit = rest;
            }
            while (word.length() > limit) {
                lines.add(word.substring(0, limit));
                word = word.substring(limit);
                limit = rest;
            }
            line.append(word);
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Where the width comes from — the environment and the terminal — so tests can stand in for both. */
    interface Probe {
        String env(String name);

        boolean stdoutIsTerminal();

        /** The terminal's width in columns, or {@code 0} when it cannot be told. */
        int terminalColumns();
    }

    private static final Probe SYSTEM = new Probe() {
        @Override
        public String env(String name) {
            return System.getenv(name);
        }

        @Override
        public boolean stdoutIsTerminal() {
            return OutputLayout.stdoutIsTerminal();
        }

        @Override
        public int terminalColumns() {
            return OutputLayout.terminalColumns();
        }
    };

    /** The terminal width last read; {@code null} until read, and again after the window is resized. */
    private static volatile Integer cachedColumns;
    private static volatile boolean resizeWatched;

    /** How wide to lay the output out: see {@link #width(Probe)}. */
    static int width() {
        return width(SYSTEM);
    }

    /**
     * {@code COLUMNS} when it is set to a number; else, on a terminal, the terminal's width; else {@code 0} — do
     * not wrap. A terminal's width is read once and read again after a resize, so a block printed after the
     * window changed uses the new width. Blocks already printed keep theirs.
     */
    static int width(Probe probe) {
        String columns = probe.env("COLUMNS");
        if (columns != null && columns.strip().matches("\\d{1,4}")) {
            return Integer.parseInt(columns.strip());
        }
        if (!probe.stdoutIsTerminal()) {
            return 0;
        }
        if (probe != SYSTEM) {
            return probe.terminalColumns();
        }
        Integer cached = cachedColumns;
        if (cached == null) {
            watchResize();
            cached = probe.terminalColumns();
            cachedColumns = cached;
        }
        return cached;
    }

    /**
     * Whether standard output is a terminal. On JDK 17 {@link System#console()} is present only when it is (and
     * standard input too); from JDK 22 it is present whenever the JVM runs interactively, and
     * {@code Console.isTerminal()} tells the two apart.
     */
    static boolean stdoutIsTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (NoSuchMethodException e) {
            return true; // JDK 17-21: a console at all means a terminal
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("could not ask the console whether it is a terminal", e);
            return false;
        }
    }

    /** {@code stty size} against the controlling terminal: "rows columns". {@code 0} when that fails. */
    private static int terminalColumns() {
        File tty = new File("/dev/tty");
        if (!tty.exists()) {
            return 0;
        }
        try {
            Process process = new ProcessBuilder("stty", "size").redirectInput(tty)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            String[] size = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                    .strip().split("\\s+");
            return (size.length == 2 && size[1].matches("\\d+")) ? Integer.parseInt(size[1]) : 0;
        } catch (Exception e) {
            LOG.debug("could not read the terminal width", e);
            return 0;
        }
    }

    /**
     * Forget the width when the window is resized ({@code SIGWINCH}), so the next block reads it again.
     * {@code sun.misc.Signal} is the only way a Java program hears of a resize; where it or the signal is not
     * available, the width read first stays.
     */
    private static void watchResize() {
        if (resizeWatched) {
            return;
        }
        resizeWatched = true;
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("WINCH"), signal -> cachedColumns = null);
        } catch (Throwable e) {
            LOG.debug("cannot watch for terminal resizes: {}", e.toString());
        }
    }

    private static String pad(String text, int width) {
        return (text.length() >= width) ? text : text + spaces(width - text.length());
    }

    private static String spaces(int count) {
        return " ".repeat(Math.max(0, count));
    }

    /** For tests: the environment of {@link #width(Probe)} as a plain lookup. */
    static Probe probe(UnaryOperator<String> env, boolean terminal, int columns) {
        return new Probe() {
            @Override
            public String env(String name) {
                return env.apply(name);
            }

            @Override
            public boolean stdoutIsTerminal() {
                return terminal;
            }

            @Override
            public int terminalColumns() {
                return columns;
            }
        };
    }
}
