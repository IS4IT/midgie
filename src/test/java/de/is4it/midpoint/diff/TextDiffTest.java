/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The line diff used to report which part of two long values actually differs. */
class TextDiffTest {

    @Test
    void aChangedLineIsShownAsARemovalAndAnAddition() {
        String diff = TextDiff.unified("alpha\nbeta\ngamma", "alpha\nBETA\ngamma", 2);

        assertTrue(diff.contains("- beta"), diff);
        assertTrue(diff.contains("+ BETA"), diff);
        assertTrue(diff.contains("  alpha") && diff.contains("  gamma"), "context is kept: " + diff);
    }

    @Test
    void unchangedStretchesAreCollapsedSoTheChangeStandsOut() {
        // The reason the diff exists: one changed line in a long value must not be buried in the rest of it.
        StringBuilder left = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            left.append("line ").append(i).append('\n');
        }
        String right = left.toString().replace("line 20", "line twenty");

        String diff = TextDiff.unified(left.toString(), right, 2);

        assertTrue(diff.contains("- line 20") && diff.contains("+ line twenty"), diff);
        assertTrue(diff.contains("unchanged line(s)"), "the untouched bulk is collapsed: " + diff);
        assertTrue(diff.lines().count() < 15, "and the message stays short: " + diff.lines().count());
    }

    @Test
    void anAddedBlockIsAllPluses() {
        String diff = TextDiff.unified("one\ntwo", "one\nextra\ntwo", 2);

        assertTrue(diff.contains("+ extra"), diff);
        assertFalse(diff.contains("- "), "nothing was removed: " + diff);
    }

    @Test
    void identicalTextProducesNoChangeMarkers() {
        String diff = TextDiff.unified("same\ntext", "same\ntext", 2);

        assertFalse(diff.contains("- ") || diff.contains("+ "), diff);
    }

    @Test
    void aValueTooLargeToDiffIsRefusedRatherThanDumped() {
        // The caller falls back to naming the sizes; the point is that it is told, not left to print a wall.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            huge.append("line ").append(i).append('\n');
        }

        assertNull(TextDiff.unified(huge.toString(), huge + "extra", 2));
    }

    @Test
    void singleLineValuesAreNotWorthDiffing() {
        assertFalse(TextDiff.isMultiLine("foo", "bar"));
        assertTrue(TextDiff.isMultiLine("foo\nbar", "baz"));
    }

    @Test
    void aWhitespaceOnlyDifferenceIsRecognised() {
        // Otherwise the diff shows lines marked changed that look identical, which reads as a tool bug.
        assertTrue(TextDiff.differsOnlyInWhitespace("a  b\nc", "a b\n  c"));
        assertFalse(TextDiff.differsOnlyInWhitespace("a b", "a c"));
        assertFalse(TextDiff.differsOnlyInWhitespace("same", "same"), "equal is not 'differs'");
    }

    @Test
    void everyDiffLineCarriesAMarker() {
        String diff = TextDiff.unified("a\nb", "a\nc", 2);

        for (String line : diff.split("\n")) {
            assertTrue(line.startsWith("    ") || line.startsWith("      ⋮"),
                    "every line is indented and marked: '" + line + "'");
        }
        assertEquals(3, diff.lines().count(), diff);
    }
}
