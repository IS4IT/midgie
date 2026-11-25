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
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a live object into what the managed files declare and the remainder {@code pull} writes. The
 * property everything rests on: the managed files and the remainder, combined as a deploy combines them, give the
 * live object back — so a deploy of both under full scope changes nothing.
 */
class ObjectSplitterTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String ROLE_OID = "77777777-0000-0000-0000-00000000aaaa";
    private static final String SYSCONFIG_OID = "00000000-0000-0000-0000-000000000001";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void withoutManagedFilesTheRemainderIsTheWholeObject() throws Exception {
        PrismObject<? extends Objectable> live = parse(role("<description>d</description><subtype>a</subtype>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, null);

        assertTrue(split.remainder().equals(live, EquivalenceStrategy.REAL_VALUE));
        assertTrue(split.drift().isEmpty() && split.conflicts().isEmpty());
    }

    @Test
    void whatNoFileDeclaresGoesToTheRemainderAndTheUnionGivesTheObjectBack() throws Exception {
        PrismObject<? extends Objectable> live = parse(role("<description>d</description>"
                + "<documentation>live only</documentation><subtype>a</subtype><subtype>b</subtype>"));
        PrismObject<? extends Objectable> managed = parse(role("<description>d</description><subtype>a</subtype>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        PrismObject<? extends Objectable> remainder = split.remainder();
        assertNull(remainder.findItem(ItemPath.create("description")), "the file declares it");
        assertEquals(List.of("live only"), values(remainder, "documentation"));
        assertEquals(List.of("b"), values(remainder, "subtype"), "the value the file does not list");
        assertEquals("Splittable Role", remainder.getName().getOrig(), "the name always stays");
        assertTrue(split.drift().isEmpty(), split.drift().toString());
        assertRebuilds(live, managed, remainder);
    }

    @Test
    void aSingleValuedContainerIsFollowedInto() throws Exception {
        PrismObject<? extends Objectable> live = parse(sysconfig("<logging><rootLoggerAppender>A</rootLoggerAppender>"
                + "<rootLoggerLevel>INFO</rootLoggerLevel></logging>"));
        PrismObject<? extends Objectable> managed =
                parse(sysconfig("<logging><rootLoggerAppender>A</rootLoggerAppender></logging>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        assertEquals(List.of("INFO"), values(split.remainder(), "logging", "rootLoggerLevel"));
        assertTrue(split.remainder().findItem(ItemPath.create("logging", "rootLoggerAppender")) == null);
        assertRebuilds(live, managed, split.remainder());
    }

    @Test
    void aKeyedEntryIsFollowedIntoAndItsPartRepeatsTheKey() throws Exception {
        PrismObject<? extends Objectable> live = parse(sysconfig("<logging>"
                + "<classLogger id=\"1\"><level>DEBUG</level><package>p.one</package><appender>A</appender></classLogger>"
                + "<classLogger id=\"2\"><level>INFO</level><package>p.two</package></classLogger>"
                + "</logging>"));
        PrismObject<? extends Objectable> managed = parse(sysconfig("<logging>"
                + "<classLogger><level>DEBUG</level><package>p.one</package></classLogger></logging>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        Item<?, ?> loggers = split.remainder().findItem(ItemPath.create("logging", "classLogger"));
        assertNotNull(loggers);
        assertEquals(2, loggers.getValues().size(), "p.one's appender, and all of p.two");
        String text = new RemainderWriter(prismContext).text(split.remainder());
        assertTrue(text.contains("<package>p.one</package>") && text.contains("<appender>A</appender>"), text);
        assertFalse(text.contains("<level>DEBUG</level>"), "the file declares p.one's level: " + text);
        assertFalse(text.contains(" id=\""), "no container ids in a tree file: " + text);
        assertTrue(split.conflicts().isEmpty(), split.conflicts().toString());
        assertRebuilds(live, managed, split.remainder());
    }

    @Test
    void anEntryEqualToTheFilesStaysWithTheFile() throws Exception {
        PrismObject<? extends Objectable> live = parse(role(inducement("11111111-0000-0000-0000-000000000001")
                + inducement("11111111-0000-0000-0000-000000000002")));
        PrismObject<? extends Objectable> managed = parse(role(inducement("11111111-0000-0000-0000-000000000001")));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        Item<?, ?> inducements = split.remainder().findItem(ItemPath.create("inducement"));
        assertEquals(1, inducements.getValues().size(), "only the one the file does not hold");
        assertTrue(split.conflicts().isEmpty() && split.drift().isEmpty());
        assertRebuilds(live, managed, split.remainder());
    }

    @Test
    void entriesNothingPairsOnBothSidesAreAConflict() throws Exception {
        // One keyless entry edited on the server: the file's version and the server's both have no counterpart,
        // and combining them would hold both.
        PrismObject<? extends Objectable> live = parse(role("<inducement><description>new</description>"
                + "<targetRef oid=\"11111111-0000-0000-0000-000000000001\" type=\"RoleType\"/></inducement>"));
        PrismObject<? extends Objectable> managed = parse(role("<inducement><description>old</description>"
                + "<targetRef oid=\"11111111-0000-0000-0000-000000000001\" type=\"RoleType\"/></inducement>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        assertEquals(1, split.conflicts().size(), split.conflicts().toString());
        assertTrue(split.conflicts().get(0).startsWith("inducement:"), split.conflicts().get(0));
    }

    @Test
    void aSingleValuedItemTheServerChangedIsDriftAndStaysWithTheFile() throws Exception {
        PrismObject<? extends Objectable> live = parse(role("<description>server</description>"));
        PrismObject<? extends Objectable> managed = parse(role("<description>file</description>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        assertEquals(List.of("description"), split.drift());
        assertNull(split.remainder().findItem(ItemPath.create("description")));
        assertFalse(split.hasContent(), "nothing but the name is left");
    }

    @Test
    void aValueTheFileListsAndTheServerLacksIsDrift() throws Exception {
        PrismObject<? extends Objectable> live = parse(role("<subtype>a</subtype>"));
        PrismObject<? extends Objectable> managed = parse(role("<subtype>a</subtype><subtype>c</subtype>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        assertEquals(List.of("subtype"), split.drift());
        assertNull(split.remainder().findItem(ItemPath.create("subtype")));
    }

    @Test
    void aReferenceTheFileWritesAsAFilterStaysWithTheFile() throws Exception {
        // Only the deploy can tell that the live OID is what the filter resolved to; a value left in the
        // remainder would be a second reference beside it.
        PrismObject<? extends Objectable> live = parse(role(
                "<archetypeRef oid=\"11111111-0000-0000-0000-000000000009\" type=\"ArchetypeType\"/>"));
        PrismObject<? extends Objectable> managed = parse(role("<archetypeRef type=\"ArchetypeType\"><filter>"
                + "<q:equal xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\"><q:path>name</q:path>"
                + "<q:value>Business</q:value></q:equal></filter></archetypeRef>"));

        ObjectSplitter.Split split = ObjectSplitter.split(live, managed);

        assertNull(split.remainder().findItem(ItemPath.create("archetypeRef")));
        assertTrue(split.drift().isEmpty(), split.drift().toString());
    }

    @Test
    void theDeployFixturesSplitAndCombineBack() throws Exception {
        // Two files of one role: the server holds both, and more. With one of them managed, the other's item and
        // the server's own end up in the remainder, and all of it combines back into the server's object.
        PrismObject<? extends Objectable> manager = prismContext.parseObject(fixture("roles/manager.xml"));
        PrismObject<? extends Objectable> acls = prismContext.parseObject(fixture("roles/manager-acls.xml"));
        PrismObject<? extends Objectable> live = FragmentUnion.of(List.of(Fragment.of(manager, fixture("roles/manager.xml")),
                Fragment.of(acls, fixture("roles/manager-acls.xml")))).clone();
        live.findOrCreateProperty(ItemPath.create("subtype")).setRealValue("from-the-server");

        ObjectSplitter.Split split = ObjectSplitter.split(live, manager);

        assertEquals(List.of("Line-manager entitlements (ACL fragment)."), values(split.remainder(), "description"));
        assertNull(split.remainder().findItem(ItemPath.create("inducement")), "manager.xml declares it");
        assertRebuilds(live, manager, split.remainder());
    }

    // --- helpers ---

    /**
     * The managed object and the remainder — written out and read back, as a deploy would read the file — combine
     * into the live object.
     */
    private static void assertRebuilds(PrismObject<? extends Objectable> live, PrismObject<? extends Objectable> managed,
            PrismObject<? extends Objectable> remainder) throws Exception {
        PrismObject<? extends Objectable> written =
                prismContext.parseObject(new RemainderWriter(prismContext).text(remainder));
        PrismObject<? extends Objectable> union = FragmentUnion.of(List.of(
                Fragment.of(managed, new File("managed.xml")), Fragment.of(written, new File("remainder.xml"))));
        PrismObject<? extends Objectable> expected = live.clone();
        Exclusions.dropIds(expected);
        PrismObject<? extends Objectable> actual = union.clone();
        Exclusions.dropIds(actual);
        assertTrue(actual.equals(expected, EquivalenceStrategy.REAL_VALUE),
                "managed + remainder must give the live object back:\n" + actual.debugDump() + "\nvs\n"
                        + expected.debugDump());
    }

    private static List<Object> values(PrismObject<? extends Objectable> object, Object... path) {
        Item<?, ?> item = object.findItem(ItemPath.create(path));
        List<Object> values = new ArrayList<>();
        if (item != null) {
            for (PrismValue value : item.getValues()) {
                Object real = value.getRealValue();
                values.add(String.valueOf(real));
            }
        }
        return values;
    }

    private static File fixture(String relative) {
        return new File("src/test/resources/deploy", relative);
    }

    private static PrismObject<? extends Objectable> parse(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static String inducement(String target) {
        return "<inducement><targetRef oid=\"" + target + "\" type=\"RoleType\"/></inducement>";
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + ROLE_OID + "\"><name>Splittable Role</name>" + body + "</role>";
    }

    private static String sysconfig(String body) {
        return "<systemConfiguration xmlns=\"" + NS + "\" oid=\"" + SYSCONFIG_OID + "\"><name>SystemConfiguration</name>"
                + body + "</systemConfiguration>";
    }
}
