/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for the root-tag tidier: drops unreferenced namespaces and wraps long tags. */
class XmlPrettyTest {

    private static final String A = "http://example.com/ns/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "http://example.com/ns/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void aLongRootWrapsEachAttributeOnItsOwnLine() {
        String xml = "<root xmlns:a=\"" + A + "\" xmlns:b=\"" + B + "\">\n    <a:x>b:val</a:x>\n</root>";
        String out = XmlPretty.tidyRoot(xml);

        assertTrue(out.startsWith("<root\n        xmlns:a=\""), out);
        assertTrue(out.contains("\n        xmlns:b=\""), out);
        assertTrue(out.contains("\">\n    <a:x>b:val</a:x>\n</root>"), "children stay untouched:\n" + out);
        assertWellFormed(out);
    }

    @Test
    void unreferencedNamespacesAreDroppedButUsedOnesKept() {
        // a: used as an element prefix; c: used only inside QName content; ri: used nowhere.
        String xml = "<a:mod xmlns:a=\"" + A + "\" xmlns:c=\"http://example.com/ns/common\" "
                + "xmlns:ri=\"http://example.com/ns/resource-instance\">\n"
                + "    <a:path>c:description</a:path>\n</a:mod>";
        String out = XmlPretty.tidyRoot(xml);

        assertTrue(out.contains("xmlns:a="), "the element prefix is kept:\n" + out);
        assertTrue(out.contains("xmlns:c="), "a prefix used in QName content is kept:\n" + out);
        assertFalse(out.contains("xmlns:ri="), "an unreferenced prefix is dropped:\n" + out);
        assertTrue(out.contains("<a:path>c:description</a:path>"), out);
        assertWellFormed(out);
    }

    @Test
    void aShortRootWithOnlyUsedNamespacesIsLeftInline() {
        String xml = "<role xmlns=\"http://x/common-3\">\n    <name>r</name>\n</role>";
        assertEquals(xml, XmlPretty.tidyRoot(xml), "nothing to strip and short enough to stay inline");
    }

    @Test
    void aSelfClosingLongRootWraps() {
        String xml = "<ref xmlns:a=\"" + A + "\" oid=\"11111111-1111-1111-1111-111111111111\" type=\"a:RoleType\"/>";
        String out = XmlPretty.tidyRoot(xml);

        assertTrue(out.startsWith("<ref\n        xmlns:a=\""), out);
        assertTrue(out.contains("\n        oid=\"11111111-1111-1111-1111-111111111111\""), out);
        assertTrue(out.contains("xmlns:a="), "a used in the type attribute value is kept:\n" + out);
        assertTrue(out.endsWith("/>"), out);
        assertWellFormed(out);
    }

    @Test
    void malformedOrNullInputIsReturnedUnchanged() {
        assertEquals("not xml at all", XmlPretty.tidyRoot("not xml at all"));
        assertNull(XmlPretty.tidyRoot(null));
    }

    private static void assertWellFormed(String xml) {
        assertDoesNotThrow(() -> {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }, "reformatted XML must still parse: " + xml);
    }
}
