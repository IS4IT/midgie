/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Container ids in a file exported from another server. prism pairs list values by id under every strategy, so
 * a DEV file's {@code id="4"} used to name whatever PROD holds under 4 and edit it in place — under merge too.
 */
class ForeignContainerIdTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String ROLE_A = "11111111-0000-0000-0000-00000000000a";
    private static final String ROLE_B = "11111111-0000-0000-0000-00000000000b";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aKeyedEntryWithAForeignIdIsNotEditedIntoTheServersEntry() throws Exception {
        // PROD's mapping 4 is someone else's; DEV's mapping 4 is a new one.
        DiffEngine.DiffResult merged = diff(template(mapping(4, "fromDev", "strong")),
                template(mapping(4, "prodOnly", "weak")), true);

        String xml = xml(merged);
        assertFalse(xml.contains("mapping[4]"), "the server's mapping 4 must not be edited:\n" + xml);
        assertTrue(xml.contains("fromDev"), xml);
        assertFalse(xml.contains("prodOnly"), "merge keeps the server's own mapping:\n" + xml);
        assertTrue(merged.keyPairingDeclined().stream().anyMatch(note -> note.contains("do not match this server")),
                merged.keyPairingDeclined().toString());

        DiffEngine.DiffResult replaced = diff(template(mapping(4, "fromDev", "strong")),
                template(mapping(4, "prodOnly", "weak")), false);
        assertFalse(xml(replaced).contains("mapping[4]"), xml(replaced));
        assertEquals(1, replaced.removedValueCount(), "replace deletes the undeclared mapping whole");
    }

    @Test
    void aKeylessEntryHeldUnderAnotherIdDoesNotOverwriteTheOneWithItsId() throws Exception {
        // The file's inducement 5 (to A) is inducement 7 here; this server's 5 (to B) was added by an admin.
        PrismObject<? extends Objectable> desired = parse(role(inducement(5, ROLE_A, null)));
        PrismObject<? extends Objectable> live = parse(role(inducement(5, ROLE_B, null) + inducement(7, ROLE_A, null)));

        DiffEngine.DiffResult merged = diff(desired, live, true);
        assertFalse(merged.hasDrift(), "merge: nothing to do, the file's entry is already there:\n" + xml(merged));

        DiffEngine.DiffResult replaced = diff(desired, live, false);
        String xml = xml(replaced);
        assertFalse(xml.contains("inducement[5]/c:targetRef"), "inducement 5 must not be re-pointed:\n" + xml);
        assertEquals(1, replaced.removedValueCount(), "replace deletes only the undeclared entry:\n" + xml);
        assertTrue(xml.contains(ROLE_B), xml);
    }

    @Test
    void anEntryEditedOnItsOwnServerIsStillEditedInPlace() throws Exception {
        // Ids from the same server are the right pairing, and keep the entry's id and metadata.
        DiffEngine.DiffResult keyless = diff(role(inducement(5, ROLE_A, "edited")),
                role(inducement(5, ROLE_A, null) + inducement(6, ROLE_B, null)), true);
        assertTrue(xml(keyless).contains("inducement[5]/c:description"), xml(keyless));
        assertTrue(keyless.keyPairingDeclined().stream().noneMatch(note -> note.contains("do not match")),
                keyless.keyPairingDeclined().toString());

        DiffEngine.DiffResult keyed = diff(template(mapping(4, "same", "strong")),
                template(mapping(4, "same", "weak")), true);
        assertTrue(xml(keyed).contains("mapping[4]/c:strength"), xml(keyed));
    }

    @Test
    void anIdMatchWithAConflictingKeyFallsBackToTheKey() throws Exception {
        PrismObject<? extends Objectable> live =
                parse(template(mapping(4, "prodOnly", "weak") + mapping(7, "shared", "weak")));
        PrismObject<? extends Objectable> desired = parse(template(mapping(4, "shared", "strong")));
        PrismContainer<?> liveMappings = live.findContainer(ItemPath.create(new QName(NS, "mapping")));
        PrismContainerValue<?> candidate =
                desired.findContainer(ItemPath.create(new QName(NS, "mapping"))).getValues().get(0);

        PrismContainerValue<?> match = NaturalKeys.match(liveMappings, candidate, 4L);

        assertEquals(7L, match.getId(), "the key names mapping 7, whatever the id says");
    }

    @Test
    void anAccruedEntryWhoseIdTheFileReusesIsReportedAsKept() throws Exception {
        PrismObject<? extends Objectable> desired = parse(role(inducement(5, ROLE_A, null)));
        PrismObject<? extends Objectable> live = parse(role(inducement(5, ROLE_B, null) + inducement(7, ROLE_A, null)));

        List<DiffEngine.LiveOnly> liveOnly = DiffEngine.liveOnlyValues(desired, live);

        assertEquals(1, liveOnly.size(), liveOnly.toString());
        assertEquals(1, liveOnly.get(0).count(), "inducement 5 is not the file's: " + liveOnly);
    }

    // --- helpers ---

    private static DiffEngine.DiffResult diff(String desiredXml, String liveXml, boolean merge) throws Exception {
        return diff(parse(desiredXml), parse(liveXml), merge);
    }

    private static DiffEngine.DiffResult diff(PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> live, boolean merge) throws Exception {
        return new DiffEngine(prismContext).diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT,
                merge ? DiffEngine.MultiValueStrategy.of(true, List.of()) : DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.ACCEPT);
    }

    private static String xml(DiffEngine.DiffResult result) throws Exception {
        return DeltaOutput.toModificationXml(prismContext, result.delta());
    }

    private static PrismObject<? extends Objectable> parse(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static String template(String body) {
        return "<objectTemplate xmlns=\"" + NS + "\" oid=\"44444444-0000-0000-0000-000000000001\"><name>T</name>"
                + body + "</objectTemplate>";
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"44444444-0000-0000-0000-000000000002\"><name>R</name>"
                + body + "</role>";
    }

    private static String mapping(int id, String name, String strength) {
        return "<mapping id=\"" + id + "\"><name>" + name + "</name><strength>" + strength + "</strength>"
                + "<target><path>description</path></target></mapping>";
    }

    private static String inducement(int id, String target, String description) {
        return "<inducement id=\"" + id + "\">"
                + (description != null ? "<description>" + description + "</description>" : "")
                + "<targetRef oid=\"" + target + "\" type=\"RoleType\"/></inducement>";
    }
}
