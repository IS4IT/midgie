/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a note looks on a terminal, and off one. */
class NoteStyleTest {

    @Test
    void aNoteIsDimAndItalicAndEndsInAReset() {
        assertEquals("\u001b[2;3mmidgie: note: x\u001b[0m", NoteStyle.apply("midgie: note: x", true));
        assertEquals("midgie: note: x", NoteStyle.apply("midgie: note: x", false));
    }

    @Test
    void onlyANoteIsANote() {
        assertTrue(NoteStyle.isNote("note: keeps live value(s)"));
        assertFalse(NoteStyle.isNote("warning: this change REMOVES 1 value"));
        assertFalse(NoteStyle.isNote("retry round 1: re-applying 2 failed unit(s)..."));
    }
}
