/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.util.exception.SchemaException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // objects: the contradiction check never runs between them, they are never combined, the OID-less
        // half gets no inbound dependency edge, and at apply time it is skipped as "no oid".
        List<DeployObject> objects = DeployObject.group(List.of(
                fragment("with-oid.xml", "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                        + "  <name>Manager</name>\n  <description>defined here</description>\n</role>"),
                fragment("no-oid.xml", "<role xmlns=\"" + NS + "\">\n"
                        + "  <name>Manager</name>\n  <subtype>added here</subtype>\n</role>")));

        assertEquals(1, objects.size(), "both files describe one object: " + objects);
        DeployObject object = objects.get(0);
        assertEquals(2, object.fragments().size(), "and both are kept as its source files");
        assertEquals(OID, object.oid(), "the group is addressed by the OID one of them carries");
        assertEquals("Manager", object.name());
        for (Fragment fragment : object.fragments()) {
            assertEquals(OID, fragment.oid(),
                    fragment.sourceFile() + " must address the object it belongs to");
        }
    }

    @Test
    void bothHalvesOfSuchAnObjectAreCombined() throws Exception {
        // What both halves declare of one multi-valued item has to end up in the object that is applied.
        // Reconciled file by file this was the "last-one-wins" clobber the grouping used to warn about, and
        // it was invisible when the two files identified the object differently — one by OID, one by name.
        List<DeployObject> objects = DeployObject.group(List.of(
                fragment("a.xml", "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n  <name>Manager</name>\n"
                        + "  <subtype>one</subtype>\n</role>"),
                fragment("b.xml", "<role xmlns=\"" + NS + "\">\n  <name>Manager</name>\n"
                        + "  <subtype>two</subtype>\n</role>")));

        assertEquals(1, objects.size(), "one object: " + objects);
        assertEquals(List.of("one", "two"), subtypes(objects.get(0)),
                "both files' values must survive into the object that is applied");
    }

    @Test
    void aSingleFragmentBecomesItsOwnUnit() throws Exception {
        // How a deployable schema object reaches the apply loop: it never groups, so it is wrapped one at a
        // time. The list it arrives in is immutable, which the group sort used to try to reorder in place —
        // an UnsupportedOperationException that failed every live deploy of a tree holding extension schema.
        Fragment only = fragment("schema.xml", "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Alone</name>\n</role>");

        DeployObject unit = DeployObject.of(only);

        assertEquals(OID, unit.oid());
        assertEquals(List.of(only), unit.fragments());
    }

    @Test
    void unrelatedObjectsStayApart() throws Exception {
        List<DeployObject> objects = DeployObject.group(List.of(
                fragment("a.xml", "<role xmlns=\"" + NS + "\">\n  <name>Manager</name>\n</role>"),
                fragment("b.xml", "<role xmlns=\"" + NS + "\">\n  <name>Auditor</name>\n</role>")));

        assertEquals(2, objects.size(), "different names are different objects: " + objects);
    }

    private static List<String> subtypes(DeployObject object) {
        List<String> values = new ArrayList<>();
        Item<?, ?> item = object.object().findItem(ItemPath.create(new QName(NS, "subtype")));
        for (PrismValue value : (item != null) ? item.getValues() : List.<PrismValue>of()) {
            Object real = value.getRealValue();   // typed as <T> T, so String.valueOf needs an Object here
            values.add(String.valueOf(real));
        }
        return values;
    }

    @Test
    void aTopLevelMultiLineContradictionIsReportedAsADiff() throws Exception {
        // The grouping check already named both files; what it did not do was show which part of two long
        // values differs. It reaches top-level items, so a <description> set by two files lands here rather
        // than in FragmentUnion — both must read alike.
        String shared = "Line one, shared by both files.\nLine two, also shared.\n";
        SchemaException failure = assertThrows(SchemaException.class, () -> DeployObject.group(List.of(
                fragment("first.xml", role(shared + "Only the first file says this.")),
                fragment("second.xml", role(shared + "Only the second file says that.")))));

        String message = failure.getMessage();
        assertTrue(message.contains("first.xml") && message.contains("second.xml"),
                "both files named: " + message);
        assertTrue(message.contains("--- ") && message.contains("+++ "), "a diff header: " + message);
        assertTrue(message.contains("- Only the first file says this."), message);
        assertTrue(message.contains("+ Only the second file says that."), message);
        assertTrue(message.contains("  Line two, also shared."), "shared context kept: " + message);
    }

    private static String role(String description) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n  <name>Grouped</name>\n"
                + "  <description>" + description + "</description>\n</role>";
    }

    private static Fragment fragment(String fileName, String xml) throws Exception {
        return Fragment.of(prismContext.parseObject(xml), new File(fileName));
    }
}
