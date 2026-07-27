/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grouping fragments into logical objects. The interesting case is a single object described by files that
 * identify it differently — one by OID, one by name — which is a normal way to write "add these items to
 * object X" next to the file that defines X.
 */
class DeployObjectTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "66666666-0000-0000-0000-00000000dddd";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aFragmentThatOmitsTheOidJoinsTheObjectItNames() throws Exception {
        // Two files, one object. Keyed on "OID else type/name" they land under different keys and become two
        // objects: the contradiction check and the multi-valued clobber warning never run between them, the
        // OID-less half gets no inbound dependency edge, and at apply time it is skipped as "no oid".
        List<String> warnings = new ArrayList<>();
        List<DeployObject> objects = DeployObject.group(List.of(
                fragment("with-oid.xml", "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                        + "  <name>Manager</name>\n  <description>defined here</description>\n</role>"),
                fragment("no-oid.xml", "<role xmlns=\"" + NS + "\">\n"
                        + "  <name>Manager</name>\n  <subtype>added here</subtype>\n</role>")), warnings);

        assertEquals(1, objects.size(), "both files describe one object: " + objects);
        DeployObject object = objects.get(0);
        assertEquals(2, object.fragments().size(), "and both are kept as apply units");
        assertEquals(OID, object.oid(), "the group is addressed by the OID one of them carries");
        assertEquals("Manager", object.name());
        for (Fragment fragment : object.fragments()) {
            assertEquals(OID, fragment.oid(),
                    fragment.sourceFile() + " must address the object it belongs to");
        }
    }

    @Test
    void theClobberWarningNowSeesBothHalvesOfSuchAnObject() throws Exception {
        // The warning exists because fragments apply file-by-file and replace is the default, so the later
        // PATCH overwrites the earlier one. Split across two identity forms it never fired.
        List<String> warnings = new ArrayList<>();
        DeployObject.group(List.of(
                fragment("a.xml", "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n  <name>Manager</name>\n"
                        + "  <subtype>one</subtype>\n</role>"),
                fragment("b.xml", "<role xmlns=\"" + NS + "\">\n  <name>Manager</name>\n"
                        + "  <subtype>two</subtype>\n</role>")), warnings);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("subtype")),
                "the multi-valued clobber must be warned about: " + warnings);
    }

    @Test
    void unrelatedObjectsStayApart() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<DeployObject> objects = DeployObject.group(List.of(
                fragment("a.xml", "<role xmlns=\"" + NS + "\">\n  <name>Manager</name>\n</role>"),
                fragment("b.xml", "<role xmlns=\"" + NS + "\">\n  <name>Auditor</name>\n</role>")), warnings);

        assertEquals(2, objects.size(), "different names are different objects: " + objects);
    }

    private static Fragment fragment(String fileName, String xml) throws Exception {
        return Fragment.of(prismContext.parseObject(xml), new File(fileName));
    }
}
