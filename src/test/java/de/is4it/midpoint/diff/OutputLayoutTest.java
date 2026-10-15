/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two-column layout of per-object output. */
class OutputLayoutTest {

    private static final String COL2 = " ".repeat(OutputLayout.COLUMN_2);

    @Test
    void theResultIsAloneInColumnOneAndEverythingElseInColumnTwo() {
        List<String> lines = new OutputLayout.Block("would-patch", "roles/b.xml  RoleType/B")
                .add("2 change(s): replace description")
                .add("note: removing 1 value(s) no longer declared by the tree:")
                .addItem("subtype: beta (was in roles/b.xml)")
                .render(0);

        assertEquals(List.of(
                "  would-patch   roles/b.xml  RoleType/B",
                COL2 + "2 change(s): replace description",
                COL2 + "note: removing 1 value(s) no longer declared by the tree:",
                COL2 + "  subtype: beta (was in roles/b.xml)"), lines);
    }

    @Test
    void columnTwoWrapsWithinItselfAndIndentsItsContinuationLines() {
        int width = OutputLayout.COLUMN_2 + 30;
        List<String> lines = new OutputLayout.Block("in-sync", "r.xml  RoleType/R")
                .add("note: keeps live value(s) the tree does not list: inducement ×1")
                .render(width);

        assertEquals(List.of(
                "  in-sync       r.xml  RoleType/R",
                COL2 + "note: keeps live value(s) the",
                COL2 + "  tree does not list:",
                COL2 + "  inducement ×1"), lines);
        assertTrue(lines.stream().allMatch(line -> line.length() <= width), lines.toString());
    }

    @Test
    void eachLineOfANoteAndOfTheItemsUnderItIsStyledAfterTheWrap() {
        int width = OutputLayout.COLUMN_2 + 30;
        List<String> lines = new OutputLayout.Block("would-patch", "r.xml  RoleType/R")
                .add("1 change(s)")
                .add("warning: this change REMOVES 1 value")
                .addItem("subtype: alpha")
                .add("note: removing 1 value(s) no longer declared:")
                .addItem("subtype: beta")
                .render(width, line -> "<" + line + ">");

        assertEquals(List.of(
                "  would-patch   r.xml  RoleType/R",
                COL2 + "1 change(s)",
                COL2 + "warning: this change REMOVES 1",
                COL2 + "  value",
                COL2 + "  subtype: alpha",
                COL2 + "<note: removing 1 value(s) no>",
                COL2 + "  <longer declared:>",
                COL2 + "  <subtype: beta>"), lines);
    }

    @Test
    void aWordLongerThanTheColumnIsCut() {
        assertEquals(List.of("abcdefghij", "klmnopqrst", "uv"), OutputLayout.wrap("abcdefghijklmnopqrstuv", 10, 10));
        assertEquals(List.of("a", "bcdefghijk", "lm"), OutputLayout.wrap("a bcdefghijklm", 10, 10));
    }

    @Test
    void spacesInsideALineAreKeptAndSwallowedAtABreak() {
        assertEquals(List.of("source  target", "detail"), OutputLayout.wrap("source  target   detail", 15, 15));
    }

    @Test
    void aTerminalTooNarrowForAUsableColumnIsNotWrapped() {
        String text = "note: this sentence is longer than the narrow column would allow";
        List<String> lines = new OutputLayout.Block("in-sync", "r.xml").add(text).render(OutputLayout.COLUMN_2 + 10);

        assertEquals(COL2 + text, lines.get(1), "one line rather than a column a word wide");
    }

    @Test
    void theWidthComesFromColumnsThenTheTerminalElseNoWrapping() {
        assertEquals(100, OutputLayout.width(OutputLayout.probe(Map.of("COLUMNS", "100")::get, false, 0)),
                "COLUMNS is honoured without a terminal, for CI that wants wrapped output");
        assertEquals(132, OutputLayout.width(OutputLayout.probe(name -> null, true, 132)));
        assertEquals(0, OutputLayout.width(OutputLayout.probe(name -> null, false, 132)),
                "no terminal: nothing is wrapped");
        assertEquals(0, OutputLayout.width(OutputLayout.probe(Map.of("COLUMNS", "wide")::get, false, 0)));
    }
}
