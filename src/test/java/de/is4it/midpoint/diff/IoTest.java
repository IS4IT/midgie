/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading XML the way a parser would, from bytes whose encoding only the bytes themselves tell. */
class IoTest {

    private static final String BODY = "<role xmlns=\"x\"><name>Grüße</name></role>";

    @Test
    void aByteOrderMarkIsReadAndDropped() {
        byte[] utf8 = ("﻿" + BODY).getBytes(StandardCharsets.UTF_8);

        assertEquals(BODY, Io.xmlText(utf8));
        assertEquals(BODY, Io.xmlText(("﻿" + BODY).getBytes(StandardCharsets.UTF_16BE)));
    }

    @Test
    void aDeclaredEncodingIsHonouredAndThenDropped() {
        // Read as UTF-8 regardless, every accent was lost; left in the text, prism decoded the accents twice.
        String declared = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + BODY;

        String text = Io.xmlText(declared.getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(text.contains("Grüße"), text);
        assertFalse(text.contains("encoding"), text);
    }

    @Test
    void withoutEitherItIsUtf8() {
        assertEquals(BODY, Io.xmlText(BODY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyStandardInputIsRefusedByName() {
        // It used to fail deep in prism with "String index out of range: 0".
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(" \n".getBytes(StandardCharsets.UTF_8)));
            IOException e = assertThrows(IOException.class, Io::readStdin);
            assertTrue(e.getMessage().contains("standard input is empty"), e.getMessage());
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void aMalformedFileIsReportedInTheExceptionAndNotPrinted(@TempDir Path dir) throws Exception {
        // The parser printed "[Fatal Error] …" to stderr itself, past --quiet, ahead of midgie's own message.
        File bad = dir.resolve("bad.xml").toFile();
        Files.writeString(bad.toPath(), "<role><name>x</name>", StandardCharsets.UTF_8);
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        IllegalStateException e;
        try {
            System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
            e = assertThrows(IllegalStateException.class, () -> Io.parseXmlFile(bad));
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(e.getMessage().startsWith("line 1, column"), e.getMessage());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aDoctypeIsRefused(@TempDir Path dir) throws Exception {
        File withDoctype = dir.resolve("doctype.xml").toFile();
        Files.writeString(withDoctype.toPath(), "<!DOCTYPE role [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<role>&x;</role>", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> Io.parseXmlFile(withDoctype));
    }
}
