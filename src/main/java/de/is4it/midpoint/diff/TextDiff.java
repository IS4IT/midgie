/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * A small line-oriented diff, used when reporting that two inputs disagree about a long value.
 *
 * <p>Printing both values in full is fine for a name or an OID and useless for anything longer: a
 * {@code description} or an expression runs to dozens of lines, and the one line that actually differs is
 * buried in two near-identical blocks the reader has to compare by eye. Showing the difference instead is
 * the whole point — the message should say <em>what changed</em>, not restate both versions.
 *
 * <p>Plain LCS over lines, with the unchanged stretches between changes collapsed. Not a general diff
 * library: it formats one error message, so it is bounded ({@link #MAX_LINES}) rather than clever, and
 * falls back to no diff at all when a value is too large for the quadratic table to be free.
 */
final class TextDiff {

    /** Above this, the LCS table stops being free and a diff stops being the readable option anyway. */
    private static final int MAX_LINES = 400;

    private TextDiff() {
    }

    /** Whether a line diff could help at all — it cannot if both values are single lines. */
    static boolean isMultiLine(String left, String right) {
        return left.indexOf('\n') >= 0 || right.indexOf('\n') >= 0;
    }

    /**
     * Report two labelled values that were supposed to agree and did not: inline when they are short, as a
     * diff when they are not. Shared by both places that catch two fragments contradicting each other, so
     * the two read alike — they are the same problem found at different depths.
     *
     * @return the body of the message, to follow a caller-supplied "Contradiction at '…': " prefix
     */
    static String clash(String leftLabel, String leftText, String rightLabel, String rightText) {
        if (!isMultiLine(leftText, rightText)) {
            return leftLabel + " sets '" + leftText + "', " + rightLabel + " sets '" + rightText + "'.";
        }

        // The labels are carried by the diff header rather than repeated in the sentence: they are usually
        // absolute paths, and naming both twice would be most of the message.
        StringBuilder out = new StringBuilder("two fragments of the same object set different values.");
        if (differsOnlyInWhitespace(leftText, rightText)) {
            out.append(" They differ only in whitespace.");
        }
        String diff = unified(leftText, rightText, 2);
        if (diff == null) {
            // Too large to diff: say so rather than dump two walls of text nobody will compare by eye.
            out.append(" The values are ").append(leftText.lines().count()).append(" and ")
                    .append(rightText.lines().count()).append(" lines long — too long to show here.");
        }
        out.append("\n    --- ").append(leftLabel).append("\n    +++ ").append(rightLabel);
        return (diff != null) ? out.append('\n').append(diff).toString() : out.toString();
    }

    /**
     * Whether the two differ only in whitespace. Worth saying out loud: the diff of such a pair shows lines
     * marked as changed that look identical, which reads as a bug in the tool rather than a fact about the
     * input.
     */
    static boolean differsOnlyInWhitespace(String left, String right) {
        return !left.equals(right) && collapse(left).equals(collapse(right));
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * A unified-style diff of {@code left} against {@code right}, each line prefixed {@code -}, {@code +} or
     * a space and indented for a log line; {@code null} when either side is too large to diff.
     *
     * @param context how many unchanged lines to keep either side of a change
     */
    static String unified(String left, String right, int context) {
        List<String> a = lines(left);
        List<String> b = lines(right);
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return null;
        }

        List<String[]> script = script(a, b);
        boolean[] keep = new boolean[script.size()];
        for (int i = 0; i < script.size(); i++) {
            if (!" ".equals(script.get(i)[0])) {
                for (int j = Math.max(0, i - context); j <= Math.min(script.size() - 1, i + context); j++) {
                    keep[j] = true;
                }
            }
        }

        StringBuilder out = new StringBuilder();
        int skipped = 0;
        for (int i = 0; i < script.size(); i++) {
            if (!keep[i]) {
                skipped++;
                continue;
            }
            if (skipped > 0) {
                out.append("      ⋮ ").append(skipped).append(" unchanged line(s)\n");
                skipped = 0;
            }
            out.append("    ").append(script.get(i)[0]).append(' ').append(script.get(i)[1]).append('\n');
        }
        if (skipped > 0) {
            out.append("      ⋮ ").append(skipped).append(" unchanged line(s)\n");
        }
        return out.toString().stripTrailing();
    }

    /** The edit script: each entry is {@code {" "|"-"|"+", line}}. */
    private static List<String[]> script(List<String> a, List<String> b) {
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<String[]> script = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                script.add(new String[] {" ", a.get(i++)});
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                script.add(new String[] {"-", a.get(i++)});
            } else {
                script.add(new String[] {"+", b.get(j++)});
            }
        }
        while (i < a.size()) {
            script.add(new String[] {"-", a.get(i++)});
        }
        while (j < b.size()) {
            script.add(new String[] {"+", b.get(j++)});
        }
        return script;
    }

    /** Split on newlines, keeping trailing empties out of the way (a value's final newline is not a line). */
    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }
}
