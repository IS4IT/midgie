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

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.util.exception.SchemaException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combining the files that describe one object into the single desired object {@code deploy} reconciles.
 *
 * <p>The case that matters is the nested one: two files that each declare the <em>same</em> entry of a keyed
 * container while contributing different children to it have to produce one entry, not two. Getting that
 * wrong is not a cosmetic problem — a duplicated {@code objectDetailsPage} is a GUI configuration midPoint
 * cannot make sense of.
 */
class FragmentUnionTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "77777777-0000-0000-0000-00000000eeee";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void oneFragmentIsItsOwnObject() throws Exception {
        Fragment only = fragment("a.xml", role("<subtype>one</subtype>"));

        assertSame(only.object(), FragmentUnion.of(List.of(only)),
                "nothing to combine, so nothing is copied");
    }

    @Test
    void multiValuedPropertiesAreCombined() throws Exception {
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                fragment("a.xml", role("<subtype>one</subtype>")),
                fragment("b.xml", role("<subtype>two</subtype>"))));

        assertEquals(List.of("one", "two"), realValuesAt(union, "subtype"));
    }

    @Test
    void theSameValueWrittenTwiceIsOneValue() throws Exception {
        // Copy-paste across two files is the common way this happens, and it must not double the entry.
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                fragment("a.xml", role("<subtype>shared</subtype>")),
                fragment("b.xml", role("<subtype>shared</subtype><subtype>other</subtype>"))));

        assertEquals(List.of("shared", "other"), realValuesAt(union, "subtype"));
    }

    @Test
    void twoFilesFillingOneSingleValuedContainerBothLand() throws Exception {
        // deploymentInformation holds one value, so combining has to descend into it rather than treat the
        // second file's copy as a competing entry.
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                fragment("a.xml", sysconfig("<deploymentInformation><name>Prod</name></deploymentInformation>")),
                fragment("b.xml", sysconfig(
                        "<deploymentInformation><headerColor>#c00</headerColor></deploymentInformation>"))));

        assertEquals(List.of("Prod"), realValuesAt(union, "deploymentInformation/name"));
        assertEquals(List.of("#c00"), realValuesAt(union, "deploymentInformation/headerColor"));
    }

    @Test
    void twoFilesContributingToOneKeyedEntryProduceOneEntry() throws Exception {
        // Both files describe objectDetailsPage[type=UserType] — the same entry, keyed by 'type' — and each
        // adds a panel to it. Appending blindly would leave midPoint with two pages for one type.
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                fragment("a.xml", detailsPage("basic")),
                fragment("b.xml", detailsPage("assignments"))));

        PrismContainer<?> pages = union.findContainer(
                ItemPath.create(name("adminGuiConfiguration"), name("objectDetails"), name("objectDetailsPage")));
        assertNotNull(pages, "the page container must survive the union");
        assertEquals(1, pages.getValues().size(), "one entry per type, not one per file: " + pages);

        List<String> panels = new ArrayList<>();
        PrismContainer<?> panelContainer = pages.getValues().get(0).findContainer(name("panel"));
        for (PrismContainerValue<?> panel : panelContainer.getValues()) {
            Object identifier = panel.findItem(ItemPath.create(name("identifier"))).getRealValue();
            panels.add(String.valueOf(identifier));
        }
        assertEquals(List.of("basic", "assignments"), panels, "both files' panels belong to that one entry");
    }

    @Test
    void twoKeyedEntriesStayApart() throws Exception {
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                fragment("a.xml", detailsPage("basic")),
                fragment("b.xml", detailsPage("basic").replace("UserType", "RoleType"))));

        PrismContainer<?> pages = union.findContainer(
                ItemPath.create(name("adminGuiConfiguration"), name("objectDetails"), name("objectDetailsPage")));
        assertEquals(2, pages.getValues().size(), "different types are different entries: " + pages);
    }

    @Test
    void aContradictionInsideAKeyedEntryIsAnError() throws Exception {
        // The grouping check descends only through single-valued containers, so this one can only be caught
        // once the two entries have been paired — which is what the union does.
        SchemaException failure = assertThrows(SchemaException.class, () -> FragmentUnion.of(List.of(
                fragment("a.xml", detailsPage("basic").replace("</panel>", "<panelType>formPanel</panelType></panel>")),
                fragment("b.xml", detailsPage("basic").replace("</panel>", "<panelType>otherPanel</panelType></panel>")))));

        assertTrue(failure.getMessage().contains("panelType"), failure.getMessage());
        assertTrue(failure.getMessage().contains("b.xml"), "and it names the file that clashed: "
                + failure.getMessage());
        assertTrue(failure.getMessage().contains("a.xml"), "and the one it clashed with: "
                + failure.getMessage());
    }

    @Test
    void aContradictionNamesBothFilesAndTheirValues() throws Exception {
        // Naming only the incoming file left the reader hunting for the other side of the clash by hand.
        // The value it clashed with sits in the accumulated union and belongs to no single file by then, so
        // it is traced back by asking the already-merged fragments which of them declares it.
        SchemaException failure = assertThrows(SchemaException.class, () -> FragmentUnion.of(List.of(
                fragment("first.xml", role("<description>from the first file</description>")),
                fragment("second.xml", role("<description>from the second file</description>")))));

        String message = failure.getMessage();
        assertTrue(message.contains("first.xml") && message.contains("second.xml"),
                "both files must be named: " + message);
        assertTrue(message.contains("from the first file") && message.contains("from the second file"),
                "and both values shown, since they are short: " + message);
    }

    @Test
    void aMultiLineContradictionIsReportedAsADiff() throws Exception {
        // The case that prompted this: two long descriptions differing in one clause. Printing both in full
        // leaves the reader comparing near-identical blocks by eye, so the message shows the difference.
        String shared = "Fachliche Lebenszyklus-Phase der Orgeinheit.\n"
                + "Beide Stichtage kommen nativ aus activation/.\n";
        SchemaException failure = assertThrows(SchemaException.class, () -> FragmentUnion.of(List.of(
                fragment("org.xml", role("<description>" + shared
                        + "Aktivierung ist activation/validFrom (aus histab_datum_orgeinheit).\n"
                        + "Vier Werte, kein 'deleted'.</description>")),
                fragment("org-loga.xml", role("<description>" + shared
                        + "Aktivierung ist activation/validFrom (aus histab_datum_orgeinheit des A-Satzes).\n"
                        + "Vier Werte, kein 'deleted'.</description>")))));

        String message = failure.getMessage();
        assertTrue(message.contains("org.xml") && message.contains("org-loga.xml"),
                "both files named: " + message);
        assertTrue(message.contains("--- ") && message.contains("+++ "), "a diff header: " + message);
        assertTrue(message.contains("- Aktivierung ist activation/validFrom (aus histab_datum_orgeinheit)."),
                "the line that went: " + message);
        assertTrue(message.contains("+ Aktivierung ist activation/validFrom (aus histab_datum_orgeinheit des A-Satzes)."),
                "and the line that replaced it: " + message);
        assertFalse(message.contains("another fragment of the same object"),
                "the vague wording is gone: " + message);
    }

    @Test
    void aContradictionAgainstSeveralAgreeingFragmentsNamesThemAll() throws Exception {
        // Three files agree, the fourth does not — saying which side is the odd one out is the useful part.
        SchemaException failure = assertThrows(SchemaException.class, () -> FragmentUnion.of(List.of(
                fragment("a.xml", role("<description>agreed</description>")),
                fragment("b.xml", role("<description>agreed</description>")),
                fragment("odd.xml", role("<description>different</description>")))));

        String message = failure.getMessage();
        assertTrue(message.contains("a.xml") && message.contains("b.xml") && message.contains("odd.xml"),
                "every file involved is named: " + message);
    }

    // --- helpers ---

    private static com.evolveum.midpoint.prism.path.ItemName name(String local) {
        return new com.evolveum.midpoint.prism.path.ItemName(NS, local);
    }

    private static List<String> realValuesAt(PrismObject<? extends Objectable> object, String path) {
        List<Object> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            segments.add(name(segment));
        }
        Item<?, ?> item = object.findItem(ItemPath.create(segments.toArray()));
        List<String> values = new ArrayList<>();
        for (PrismValue value : (item != null) ? item.getValues() : List.<PrismValue>of()) {
            Object real = value.getRealValue();
            values.add(String.valueOf(real));
        }
        return values;
    }

    private static Fragment fragment(String fileName, String xml) throws Exception {
        return Fragment.of(prismContext.parseObject(xml), new File(fileName));
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n  <name>United</name>\n  "
                + body + "\n</role>";
    }

    private static String sysconfig(String body) {
        return "<systemConfiguration xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>SystemConfiguration</name>\n  " + body + "\n</systemConfiguration>";
    }

    /** A SystemConfiguration declaring one GUI details page for UserType, holding one named panel. */
    private static String detailsPage(String panelIdentifier) {
        return sysconfig("<adminGuiConfiguration><objectDetails><objectDetailsPage>"
                + "<type>UserType</type>"
                + "<panel><identifier>" + panelIdentifier + "</identifier></panel>"
                + "</objectDetailsPage></objectDetails></adminGuiConfiguration>");
    }
}
