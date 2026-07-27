/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import com.evolveum.midpoint.util.DOMUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the target-namespace dedupe that {@link SchemaSupport#register} applies before handing schemas to
 * prism — so an extension schema present in both the deploy tree and on the live server is registered once
 * (local first-in wins), not twice (which makes prism warn about overwriting each identical definition).
 */
class SchemaSupportTest {

    private static Element schema(String targetNamespace) {
        String xsd = (targetNamespace == null)
                ? "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"/>"
                : "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\""
                        + targetNamespace + "\"/>";
        return DOMUtil.parseDocument(xsd).getDocumentElement();
    }

    @Test
    void keepsOneSchemaPerNamespaceFirstWins() {
        Map<String, Element> in = new LinkedHashMap<>();
        Element tree = schema("urn:a");
        in.put("deploy schema a.xml (urn:a)", tree);      // local (tree) source added first
        in.put("live schema A (oid)", schema("urn:a"));   // the server's copy of the same namespace
        in.put("deploy schema b.xml (urn:b)", schema("urn:b"));

        Map<String, Element> out = SchemaSupport.dedupeByNamespace(in);

        assertEquals(2, out.size(), "one entry per namespace");
        assertTrue(out.containsKey("deploy schema a.xml (urn:a)"), "the first (tree) urn:a entry is kept");
        assertFalse(out.containsKey("live schema A (oid)"), "the later (live) urn:a entry is dropped");
        assertSame(tree, out.get("deploy schema a.xml (urn:a)"), "the winning element is the local one");
        assertTrue(out.containsKey("deploy schema b.xml (urn:b)"), "a distinct namespace is kept");
    }

    @Test
    void blankNamespaceSchemasAreNotCollapsed() {
        Map<String, Element> in = new LinkedHashMap<>();
        in.put("one", schema(null));
        in.put("two", schema(null));

        assertEquals(2, SchemaSupport.dedupeByNamespace(in).size(),
                "schemas without a targetNamespace are each kept");
    }
}
