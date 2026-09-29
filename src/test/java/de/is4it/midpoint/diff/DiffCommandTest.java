/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the offline diff engine. Fixtures follow midPoint's own {@code TestParseDiffPatch} /
 * {@code TestDiffEquals} (see /Volumes/Daten/git/midpoint/infra/schema/src/test).
 */
class DiffCommandTest {

    /** Bootstrapping the base schema is expensive, so share one context across the class. */
    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void diffOfTwoRolesEmitsReplaceForChangedProperty(@TempDir Path dir) throws Exception {
        File desired = write(dir, "desired.xml", role("Desired description"));
        File live = write(dir, "live.xml", role("Current description"));

        DiffEngine.DiffResult result = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());

        // Exactly one itemDelta: a replace of <description> carrying the DESIRED value. The delta
        // reconciles live -> desired, so the replace must set the desired string, not the live one.
        assertEquals(1, result.delta().getModifications().size(), "expected a single itemDelta");
        ItemDelta<?, ?> mod = result.delta().getModifications().iterator().next();
        assertEquals("description", mod.getPath().firstName().getLocalPart(), "delta must target <description>");
        assertNotNull(mod.getValuesToReplace(), "must be a replace");
        assertEquals(1, mod.getValuesToReplace().size());
        assertEquals("Desired description",
                mod.getValuesToReplace().iterator().next().getRealValue(),
                "replace must carry the desired value (delta reconciles live -> desired)");

        // And it converts + serializes to the ObjectModificationType the REST PATCH consumes.
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(result.delta());
        assertEquals(1, modification.getItemDelta().size());
        String xml = prismContext.xmlSerializer()
                .serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
        assertTrue(xml.contains("Desired description"),
                "serialized PATCH must carry the desired value:\n" + xml);
    }

    @Test
    void scopePresentLeavesUnmanagedItemsUntouched(@TempDir Path dir) throws Exception {
        // desired manages only <description>; live also carries an accrued <subtype> not in desired.
        File desired = write(dir, "desired.xml", role("Desired description"));
        File live = write(dir, "live.xml",
                role("Current description", "  <subtype>accrued-at-runtime</subtype>\n"));

        // scope=present: only the managed <description> is touched; the live-only <subtype> is left alone.
        DiffEngine.DiffResult present = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertEquals(1, present.delta().getModifications().size(), "present scope must emit only the managed item");
        assertEquals("description",
                present.delta().getModifications().iterator().next().getPath().firstName().getLocalPart());
        assertFalse(touchesItem(present, "subtype"), "present scope must not touch the live-only <subtype>");

        // scope=full: same diff also removes the live-only <subtype>.
        DiffEngine.DiffResult full = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertEquals(2, full.delta().getModifications().size(), "full scope must also remove the unmanaged item");
        assertTrue(touchesItem(full, "subtype"), "full scope must delete the live-only <subtype>");
    }

    @Test
    void scopePresentLeavesUnmentionedChildrenOfASingleValuedContainer(@TempDir Path dir) throws Exception {
        // Two fragments of one systemConfiguration each fill a DIFFERENT child of the single-valued
        // deploymentInformation container. Diffing the "name" fragment against a live object that already
        // carries a headerColor (set by the other fragment) must NOT delete headerColor: it is absent from
        // this fragment's desired object, so under PRESENT it is a live-only item and is left untouched.
        File desired = write(dir, "desired.xml", sysconfig("<name>Midgie</name>"));
        File live = write(dir, "live.xml", sysconfig("<headerColor>navy</headerColor>"));

        DiffEngine.DiffResult present = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertEquals(1, present.delta().getModifications().size(),
                "present must emit only the managed name, not touch headerColor: " + paths(present));
        assertFalse(deletesPath(present, "headerColor"),
                "present must not delete the live-only headerColor: " + paths(present));

        // scope=full, by contrast, DOES remove the unmentioned headerColor.
        DiffEngine.DiffResult full = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertTrue(deletesPath(full, "headerColor"), "full scope must delete the live-only headerColor");
    }

    @Test
    void scopePresentProtectsSingleValuedMidLevelsBelowTheTop(@TempDir Path dir) throws Exception {
        // A fragment that touches `internals` only via internals/repository must NOT disturb a live-only
        // internals/tracing subtree: the single-valued internals/tracing level is judged at its own depth,
        // not collapsed to the top-level `internals`. (Answers "is anything at mid level safe?" for present.)
        String ns = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
        File desired = write(dir, "desired.xml", "<systemConfiguration xmlns=\"" + ns + "\""
                + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <internals><repository><autoCreatePartitionsOnAdd>true</autoCreatePartitionsOnAdd>"
                + "</repository></internals>\n"
                + "</systemConfiguration>");
        File live = write(dir, "live.xml", "<systemConfiguration xmlns=\"" + ns + "\""
                + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <internals><tracing><profile id=\"1\"><name>perf</name></profile></tracing></internals>\n"
                + "</systemConfiguration>");

        DiffEngine.DiffResult present = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertFalse(deletesPath(present, "tracing"),
                "present must not delete the live-only internals/tracing: " + paths(present));

        DiffEngine.DiffResult full = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertTrue(deletesPath(full, "tracing"), "full scope must delete the live-only internals/tracing");
    }

    @Test
    void multivalueMergePreservesExistingValues(@TempDir Path dir) throws Exception {
        // live carries assignment[1] (managed) and assignment[2] (accrued at runtime).
        File live = write(dir, "live.xml", user(
                assignment("1", "role-1"),
                assignment("2", "role-2")));
        // desired keeps assignment[1] and introduces a new managed assignment. Managed container values
        // carry explicit ids so merge can match them by key (see DiffEngine merge docs re: keyless values).
        File desired = write(dir, "desired.xml", user(
                assignment("1", "role-1"),
                assignment("3", "role-3")));

        DiffEngine.MultiValueStrategy mergeAssignment = DiffEngine.MultiValueStrategy.of(
                false, List.of(new DiffEngine.MultiValueStrategy.PathRule(ItemPath.fromString("assignment"), true)));

        // merge: add role-3, keep the accrued assignment[2] -> no deletion at all.
        DiffEngine.DiffResult merge = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT, mergeAssignment);
        ItemDelta<?, ?> mergeDelta = single(merge);
        assertEquals("assignment", mergeDelta.getPath().firstName().getLocalPart());
        assertEquals(1, mergeDelta.getValuesToAdd().size(), "merge must add the new managed assignment");
        assertEquals("role-3", targetRefOid(mergeDelta.getValuesToAdd().iterator().next().getRealValue()));
        assertTrue(mergeDelta.getValuesToDelete() == null || mergeDelta.getValuesToDelete().isEmpty(),
                "merge must NOT delete the accrued assignment[2]");
        assertTrue(merge.mergeFallbackPaths().isEmpty(), "keyed values must not trigger the replace fallback");

        // replace (control): same inputs, but the accrued assignment[2] is removed.
        DiffEngine.DiffResult replace = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());
        ItemDelta<?, ?> replaceDelta = single(replace);
        assertNotNull(replaceDelta.getValuesToDelete(), "replace must delete the accrued assignment[2]");
        assertEquals("role-2", targetRefOid(replaceDelta.getValuesToDelete().iterator().next().getRealValue()));
    }

    @Test
    void equivalenceControlsMetadataSensitivity(@TempDir Path dir) throws Exception {
        // Two roles identical except one carries operational <metadata>. Under scope=full so the
        // metadata isn't dropped by scope filtering, real-value ignores it while data surfaces it.
        File desired = write(dir, "desired.xml", role("same"));
        File live = write(dir, "live.xml",
                role("same", "  <metadata><createChannel>http://x#init</createChannel></metadata>\n"));

        assertFalse(engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                        DiffEngine.MultiValueStrategy.replaceAll()).hasDrift(),
                "real-value must ignore operational metadata");
        assertTrue(engine().diff(desired, live, EquivalenceStrategy.DATA, DiffEngine.Scope.FULL,
                        DiffEngine.MultiValueStrategy.replaceAll()).hasDrift(),
                "data must surface the metadata difference");
    }

    @Test
    void protectedValuesAreSkippedAndReported(@TempDir Path dir) throws Exception {
        // Two users identical except for the password ciphertext/clearValue. midPoint re-encrypts on every
        // fetch, so this "difference" is noise — the engine must drop it and report the path, not emit a
        // delta that would leak the secret.
        File desired = write(dir, "desired.xml", userWithPassword("new-secret"));
        File live = write(dir, "live.xml", userWithPassword("old-secret"));

        DiffEngine.DiffResult result = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());

        assertFalse(result.hasDrift(), "the only difference is a protected value, which must be skipped");
        assertEquals(1, result.protectedSkippedPaths().size(), "the protected path must be reported");
        assertTrue(result.protectedSkippedPaths().get(0).contains("password"),
                "reported path should point at the password: " + result.protectedSkippedPaths());
    }

    @Test
    void aConnectorPasswordWithoutADefinitionIsProtectedToo(@TempDir Path dir) throws Exception {
        // midPoint returns a connector's bindPassword as <t:encryptedData> with no xsi:type (checked against a
        // 4.10.5 server), and the connector schema that would define it is not loaded: both sides are raw
        // values. The PATCH used to carry the file's cleartext and the server's ciphertext, on every run.
        File desired = write(dir, "desired.xml", ldapResource("<t:clearValue>s3cret</t:clearValue>"));
        File live = write(dir, "live.xml", ldapResource("<t:encryptedData><t:encryptionMethod><t:algorithm>"
                + "http://www.w3.org/2001/04/xmlenc#aes256-cbc</t:algorithm></t:encryptionMethod><t:keyInfo>"
                + "<t:keyName>K</t:keyName></t:keyInfo><t:cipherData><t:cipherValue>AAAA</t:cipherValue>"
                + "</t:cipherData></t:encryptedData>"));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());

        assertFalse(result.hasDrift(), DeltaOutput.toModificationXml(prismContext, result.delta()));
        assertEquals(List.of("connectorConfiguration/configurationProperties/bindPassword"),
                result.protectedSkippedPaths());
    }

    private static String ldapResource(String bindPassword) {
        return "<resource xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:t=\"http://prism.evolveum.com/xml/ns/public/types-3\""
                + " xmlns:icfc=\"http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3\""
                + " xmlns:cfg=\"http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
                + "com.evolveum.polygon.connector-ldap/com.evolveum.polygon.connector.ldap.LdapConnector\""
                + " oid=\"a17e57e5-0000-0000-0000-0000000000e1\"><name>r</name><connectorConfiguration>"
                + "<icfc:configurationProperties><cfg:host>h</cfg:host><cfg:bindPassword>" + bindPassword
                + "</cfg:bindPassword></icfc:configurationProperties></connectorConfiguration></resource>";
    }

    @Test
    void roundTripDeltaAppliesToLiveYieldingDesired(@TempDir Path dir) throws Exception {
        // midPoint's own TestParseDiffPatch invariant: live.diff(desired) applied to live must yield desired.
        // Use full scope so every difference (a changed managed item and a removed live-only item) is in the
        // delta, then apply it locally and assert the result equals desired.
        File desiredFile = write(dir, "desired.xml", role("Desired description"));
        File liveFile = write(dir, "live.xml",
                role("Current description", "  <subtype>accrued-at-runtime</subtype>\n"));

        PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredFile);
        PrismObject<? extends Objectable> live = prismContext.parseObject(liveFile);

        DiffEngine.DiffResult result = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                DiffEngine.MultiValueStrategy.replaceAll());

        @SuppressWarnings("rawtypes")
        PrismObject reconciled = live.clone();
        ((ObjectDelta) result.delta()).applyTo(reconciled);
        assertTrue(desired.equivalent(reconciled),
                "applying the delta to the live object must reproduce the desired object");
    }

    @Test
    void replaceDeltaCarriesTheOldValueItOverwrites(@TempDir Path dir) throws Exception {
        // prism builds an add/delete pair for a single-valued property and PrismPropertyImpl.fixupDelta
        // rewrites it as a bare replace, discarding the old value. DiffEngine puts it back, which is what
        // makes a saved plan reviewable and createReverseDelta correct.
        File desired = write(dir, "desired.xml", role("Desired description"));
        File live = write(dir, "live.xml", role("Current description"));

        ItemDelta<?, ?> mod = single(engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll()));

        assertNotNull(mod.getEstimatedOldValues(), "a replace must record what it overwrites");
        assertEquals(1, mod.getEstimatedOldValues().size());
        assertEquals("Current description", mod.getEstimatedOldValues().iterator().next().getRealValue());
    }

    @Test
    void reverseOfAReplaceRestoresTheOriginalValue(@TempDir Path dir) throws Exception {
        // The rollback invariant for the case that needs the recorded old value: apply the delta, then apply
        // its reverse, and the object is back where it started. Without estimatedOldValues prism's
        // createReverseDelta yields an EMPTY replace here, which clears <description> instead of restoring it.
        PrismObject<? extends Objectable> desired =
                prismContext.parseObject(write(dir, "desired.xml", role("Desired description")));
        PrismObject<? extends Objectable> live =
                prismContext.parseObject(write(dir, "live.xml", role("Current description")));

        ObjectDelta<? extends Objectable> delta = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll()).delta();

        @SuppressWarnings("rawtypes")
        PrismObject object = live.clone();
        ((ObjectDelta) delta).applyTo(object);
        assertEquals("Desired description", roleDescription(object), "the delta must reach the desired state");

        ((ObjectDelta) delta.createReverseDelta()).applyTo(object);
        assertTrue(live.equivalent(object), "the reverse delta must restore the original live object");
    }

    @Test
    void reverseOfAReplaceRestoresAccruedMultiValuedValues(@TempDir Path dir) throws Exception {
        // The motivating case: a path left on multivalue=replace while desired declares only some of the
        // live values, so the unlisted ones are deleted. Those deletes carry the accrued values themselves
        // (fixupDelta is overridden for single-valued properties alone), so the reverse restores them.
        PrismObject<? extends Objectable> desired = prismContext.parseObject(write(dir, "desired.xml",
                user(assignment(null, "aaaaaaaa-0000-0000-0000-000000000001"))));
        PrismObject<? extends Objectable> live = prismContext.parseObject(write(dir, "live.xml",
                user(assignment("1", "aaaaaaaa-0000-0000-0000-000000000001"),
                        assignment("2", "bbbbbbbb-0000-0000-0000-000000000002"))));

        ObjectDelta<? extends Objectable> delta = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll()).delta();
        assertTrue(delta.getModifications().stream().anyMatch(m -> m.getValuesToDelete() != null),
                "replace must delete the accrued assignment — otherwise this test proves nothing");

        @SuppressWarnings("rawtypes")
        PrismObject object = live.clone();
        ((ObjectDelta) delta).applyTo(object);
        assertEquals(1, assignmentCount(object), "the accrued assignment must be gone after the apply");

        ((ObjectDelta) delta.createReverseDelta()).applyTo(object);
        assertEquals(2, assignmentCount(object), "the reverse delta must bring the accrued assignment back");
        assertTrue(live.equivalent(object), "the reverse delta must restore the original live object");
    }

    @Test
    void protectedValuesAreNeverRecordedAsOldValues(@TempDir Path dir) throws Exception {
        // A password delta is dropped outright, so the only way ciphertext could reach a file on disk is via
        // the old value of some other delta. Diff a user whose password AND fullName both differ: the
        // fullName replace must carry its own old value, and nothing anywhere may carry the password.
        File desired = write(dir, "desired.xml", userWithPasswordAndFullName("s3cret", "Desired Name"));
        File live = write(dir, "live.xml", userWithPasswordAndFullName("other", "Current Name"));

        DiffEngine.DiffResult result = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());

        assertEquals(1, result.protectedSkippedPaths().size(), "the password path must be reported as skipped");
        ItemDelta<?, ?> mod = single(result);
        assertEquals("fullName", mod.getPath().firstName().getLocalPart());
        assertNotNull(mod.getEstimatedOldValues(), "the ordinary replace still records its old value");
        for (ItemDelta<?, ?> each : result.delta().getModifications()) {
            for (Object old : (each.getEstimatedOldValues() != null) ? each.getEstimatedOldValues() : List.of()) {
                assertFalse(String.valueOf(old).contains("other"),
                        "a protected value must never be recorded as an old value");
            }
        }
    }

    @Test
    void polyStringAndReferenceDoNotChurn(@TempDir Path dir) throws Exception {
        // The live object arrives from REST with a fully-expanded PolyString name (orig+norm) and an
        // assignment carrying a container id; the desired file uses the shorthand name and a different id.
        // Neither the PolyString form nor the container id is a real difference, so there must be no drift.
        File live = write(dir, "live.xml",
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " oid=\"11111111-1111-1111-1111-111111111111\">\n"
                        + "  <name><orig>Example Role</orig><norm>example role</norm></name>\n"
                        + "  <assignment id=\"7\"><targetRef oid=\"role-1\" type=\"c:RoleType\"/></assignment>\n"
                        + "</role>");
        File same = write(dir, "same.xml",
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " oid=\"11111111-1111-1111-1111-111111111111\">\n"
                        + "  <name>Example Role</name>\n"
                        + "  <assignment id=\"99\"><targetRef oid=\"role-1\" type=\"c:RoleType\"/></assignment>\n"
                        + "</role>");

        assertFalse(engine().diff(same, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                        DiffEngine.MultiValueStrategy.replaceAll()).hasDrift(),
                "equal PolyString (shorthand vs expanded) and same targetRef under a different id must not churn");

        // A genuinely different name and a different targetRef must both surface.
        File changed = write(dir, "changed.xml",
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                        + " oid=\"11111111-1111-1111-1111-111111111111\">\n"
                        + "  <name>Renamed Role</name>\n"
                        + "  <assignment id=\"7\"><targetRef oid=\"role-9\" type=\"c:RoleType\"/></assignment>\n"
                        + "</role>");
        DiffEngine.DiffResult drift = engine().diff(changed, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll());
        assertTrue(drift.hasDrift(), "a changed name and targetRef must produce a delta");
        assertTrue(touchesItem(drift, "name"), "the name change must be in the delta");
        assertTrue(touchesItem(drift, "assignment"), "the targetRef change must be in the delta");
    }

    @Test
    void mergeFlagsInPlaceEditOfAccruedValue(@TempDir Path dir) throws Exception {
        // live carries an accrued assignment[2]; desired replaces it with a new managed assignment[3].
        // Under natural-keys prism pairs the leftover accrued id=2 with the desired id=3 and edits it in
        // place (assignment/2/targetRef) — merge cannot preserve id=2, so the accrued value is overwritten.
        File live = write(dir, "live.xml", user(assignment("1", "role-1"), assignment("2", "role-2")));
        File desired = write(dir, "desired.xml", user(assignment("1", "role-1"), assignment("3", "role-3")));

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, mergeAssignments());

        assertFalse(result.accruedInPlaceEditPaths().isEmpty(),
                "the pair-and-edit of the accrued assignment[2] must be flagged");
        assertTrue(result.accruedInPlaceEditPaths().get(0).contains("assignment"),
                "flagged path should point at the assignment: " + result.accruedInPlaceEditPaths());
    }

    @Test
    void mergeDoesNotFlagKeyMatchedEditNestedInMultiValuedContainers(@TempDir Path dir) throws Exception {
        // Targeting one panel nested in a multi-valued objectDetailsPage: the desired fragment carries no
        // container ids, so under natural-keys prism pairs its values with the live ones (objectDetailsPage
        // by <type>, panel by <identifier> — both schema-declared natural keys) and emits a deep in-place
        // edit. That is the intended managed update, not an accrued value being overwritten, so it must not
        // be flagged — otherwise the recipe needs --allow-inplace-merge, which disables the guard everywhere.
        File live = write(dir, "live.xml", sysconfigPanels("84",
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml",
                sysconfigPanels(null, panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.of(true, List.of()));

        assertTrue(result.accruedInPlaceEditPaths().isEmpty(),
                "a natural-key-matched edit is a managed update, not an accrued overwrite: "
                        + result.accruedInPlaceEditPaths());
        assertTrue(result.hasDrift(), "the displayOrder change must still surface");
        assertTrue(paths(result).stream().anyMatch(p -> p.contains("displayOrder")),
                "expected the deep displayOrder delta, got: " + paths(result));
        assertFalse(deletesPath(result, "panel"), "the sibling panel must not be deleted: " + paths(result));
    }

    @Test
    void partiallyDeclaredKeyedContainerOnReplaceIsWarnedWithTheFix(@TempDir Path dir) throws Exception {
        // The destructive default: desired lists 1 of 2 panels, replace makes it authoritative -> the other
        // is deleted. That must be reported up front, naming multivalue=merge as the fix, and the delta's
        // blast radius must be counted rather than hidden behind "1 change".
        File live = write(dir, "live.xml", sysconfigPanels("84",
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml",
                sysconfigPanels(null, panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());

        // Both numbers count live values: one of the two is matched, so one is deleted. Reporting the
        // desired count against the live total could print sums that do not add up and read as if less were
        // at risk than really is.
        assertTrue(result.inputWarnings().stream().anyMatch(w -> w.contains("matches 1 of 2 live value(s)")
                        && w.contains("so the other 1 will be DELETED")
                        && w.contains("multivalue=merge")),
                "expected a partial-container warning naming the fix: " + result.inputWarnings());
        assertTrue(result.removedValueCount() > 0, "the removal must be counted");
        assertNotNull(result.removalWarning(), "a removing delta must carry a loud warning");
        assertTrue(result.removalWarning().contains("REMOVES"), result.removalWarning());
    }

    @Test
    void aValueMissingItsNaturalKeyIsWarned(@TempDir Path dir) throws Exception {
        // A panel with no <identifier> cannot be matched to a live entry, so it silently degrades to a
        // whole-value change. Flag it statically, naming the key.
        File live = write(dir, "live.xml", sysconfigPanels("84", panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", sysconfigPanels(null,
                "        <panel><displayOrder>16</displayOrder></panel>\n"));

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.of(true, List.of()));

        assertTrue(result.inputWarnings().stream()
                        .anyMatch(w -> w.contains("natural key") && w.contains("identifier")),
                "expected a missing-natural-key warning naming the key: " + result.inputWarnings());
    }

    @Test
    void theKeyMatchedMergeRecipeProducesNoInputWarnings(@TempDir Path dir) throws Exception {
        // The documented recipe (natural-keys + merge) must be quiet — warnings that cry wolf get ignored.
        File live = write(dir, "live.xml", sysconfigPanels("84",
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml",
                sysconfigPanels(null, panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.of(true, List.of()));

        assertTrue(result.inputWarnings().isEmpty(),
                "the key-matched merge recipe must not warn: " + result.inputWarnings());
        assertEquals(0, result.removedValueCount(), "it removes nothing");
        assertNull(result.removalWarning(), "so there is no removal warning");
    }

    @Test
    void aDeclaredButUnusedNaturalKeyDoesNotWarn(@TempDir Path dir) throws Exception {
        // AssignmentType declares naturalKey=identifier, but real assignments almost never carry one — as do
        // inducement and authorization. Warning on every such value (and claiming their live counterparts
        // would be deleted) is pure noise that trains people to ignore warnings, so the key only counts when
        // the live values actually use it, and content equivalence stands in for the match.
        File live = write(dir, "live.xml", user(assignment("1", "role-1"), assignment("2", "role-2")));
        File desired = write(dir, "desired.xml", user(assignment(null, "role-1"), assignment(null, "role-2")));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());

        assertTrue(result.inputWarnings().isEmpty(),
                "a natural key that neither side uses must not warn: " + result.inputWarnings());
    }

    @Test
    void mergeDoesNotFlagInPlaceEditOfManagedValue(@TempDir Path dir) throws Exception {
        // assignment[2] is present in BOTH live and desired (managed); only its targetRef changed. That is a
        // legitimate in-place update of a value the desired state owns, not an accrued-value footgun.
        File live = write(dir, "live.xml", user(assignment("1", "role-1"), assignment("2", "role-2")));
        File desired = write(dir, "desired.xml", user(assignment("1", "role-1"), assignment("2", "role-9")));

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, mergeAssignments());

        assertTrue(result.hasDrift(), "the managed targetRef change must still produce a delta");
        assertTrue(result.accruedInPlaceEditPaths().isEmpty(),
                "an in-place edit of a value present in desired is managed, not accrued: "
                        + result.accruedInPlaceEditPaths());
    }

    @Test
    void diffRefusesObjectsWithDifferentOids(@TempDir Path dir) throws Exception {
        File desired = write(dir, "desired.xml", roleOid("aaaaaaaa-0000-0000-0000-000000000001", "x"));
        File live = write(dir, "live.xml", roleOid("bbbbbbbb-0000-0000-0000-000000000002", "y"));

        SchemaException ex = assertThrows(SchemaException.class, () -> engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll()));
        assertTrue(ex.getMessage().contains("does not match live oid"),
                "the mismatch message must name both oids: " + ex.getMessage());
    }

    @Test
    void diffAllowsDesiredTemplateWithoutOid(@TempDir Path dir) throws Exception {
        // A desired object with no OID is an unbound template; the live OID governs, so it diffs normally.
        File desired = write(dir, "desired.xml",
                "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">\n"
                        + "  <name>Example Role</name>\n"
                        + "  <description>Desired description</description>\n"
                        + "</role>");
        File live = write(dir, "live.xml", role("Current description"));

        DiffEngine.DiffResult result = engine().diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());
        assertTrue(result.hasDrift(), "a template without OID must still diff against the live object");
        assertTrue(touchesItem(result, "description"), "the description change must surface");
    }

    // --- helpers ---

    @Test
    void anItemTheServerDidNotSendIsNotDiffedAsEmpty(@TempDir Path dir) throws Exception {
        // midPoint does not return a LookupTableType's rows unless asked, and says so: <row incomplete="true"/>.
        // Read as written that is "the table has no rows", so every run emitted the whole table as an
        // addition and never converged — and against a table someone had added to, replace would have
        // deleted rows midgie never saw. Unknown is not empty: the item is left out of the delta and named.
        File live = write(dir, "live.xml", lookupTable("<row incomplete=\"true\"/>"));
        File desired = write(dir, "desired.xml", lookupTable(
                "<row><key>active</key><label>Aktiv</label></row>"
                        + "<row><key>expired</key><label>Abgelaufen</label></row>"));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        assertFalse(touchesItem(result, "row"), "an unsent item must not be diffed: " + paths(result));
        assertTrue(result.incompleteSkippedPaths().stream().anyMatch(p -> p.contains("row")),
                "and it must be reported: " + result.incompleteSkippedPaths());
    }

    @Test
    void aCompleteLookupTableStillDiffsNormally(@TempDir Path dir) throws Exception {
        // The other half: once the rows are actually there, nothing is suppressed. Over-suppressing would
        // turn the fix into "midgie never manages lookup tables at all".
        File live = write(dir, "live.xml", lookupTable(
                "<row id=\"2\"><key>active</key><label>Aktiv</label></row>"
                        + "<row id=\"3\"><key>expired</key><label>Abgelaufen</label></row>"));
        File desired = write(dir, "desired.xml", lookupTable(
                "<row><key>active</key><label>Aktiv</label></row>"
                        + "<row><key>expired</key><label>Abgelaufen (LOGA)</label></row>"));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        assertTrue(result.incompleteSkippedPaths().isEmpty(), "nothing to skip: "
                + result.incompleteSkippedPaths());
        // row is keyed by <key>, which the file fills in, so auto pairs the entries and edits in place.
        assertTrue(paths(result).stream().anyMatch(p -> p.contains("label")),
                "the changed label must surface as a deep edit: " + paths(result));
    }

    // ---- --equivalence auto -------------------------------------------------------------------------

    @Test
    void autoPairsTheKeyedContainerWhileLeavingAssignmentsAlone(@TempDir Path dir) throws Exception {
        // The case the whole feature exists for. One object, two answers: objectDetailsPage/panel fill in
        // their declared keys, so natural-keys pairs them and edits the panel in place, keeping the live
        // container ids and the metadata hanging off them. assignment declares identifier and does not carry
        // one, and prism's DefaultNaturalKeyDefinitionImpl treats a constituent absent from BOTH sides as
        // matching — so under an object-wide natural-keys the leftover live assignment would pair
        // positionally with the desired one and be edited into it. Auto must not let that happen.
        File live = write(dir, "live.xml", userWithPanels("84",
                assignment("1", "role-1") + assignment("2", "role-2"),
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", userWithPanels(null,
                assignment(null, "role-1") + assignment(null, "role-3"),
                panel(null, "basic", null), panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        // The panel change is expressed in place, under the live page's container id — not as a wholesale
        // replace of objectDetailsPage, which is what real-value alone would have produced.
        assertTrue(paths(result).stream().anyMatch(p -> p.contains("displayOrder")),
                "the panel edit must be a deep in-place delta: " + paths(result));
        assertTrue(paths(result).stream().anyMatch(p -> p.contains("84")),
                "the in-place edit must run through the live container id: " + paths(result));

        // The assignment change stays a whole-value add/delete: no deep edit reaching into an assignment.
        assertFalse(paths(result).stream().anyMatch(p -> p.contains("assignment/") && p.contains("targetRef")),
                "assignments must not be paired and deep-edited: " + paths(result));

        assertTrue(result.equivalenceChoices().stream()
                        .anyMatch(c -> c.startsWith("assignment=real-value") && c.contains("identifier")),
                "auto must say it declined natural-keys for assignment, and why: " + result.equivalenceChoices());
        assertTrue(result.equivalenceChoices().stream()
                        .anyMatch(c -> c.contains("objectDetailsPage=natural-keys")),
                "auto must say it chose natural-keys for the keyed page: " + result.equivalenceChoices());

        // And the hazard is real, not hypothetical: the same object under an OBJECT-WIDE natural-keys does
        // reach into an assignment and edit it in place. Without this the test above could pass vacuously.
        DiffEngine.DiffResult objectWide = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());
        assertTrue(paths(objectWide).stream().anyMatch(p -> p.contains("assignment/") && p.contains("targetRef")),
                "object-wide natural-keys is expected to deep-edit an assignment: " + paths(objectWide));
    }

    @Test
    void autoIsExactlyRealValueWhenNothingCarriesAKey(@TempDir Path dir) throws Exception {
        // Graceful fallback, and the common case: an object whose containers declare no usable key must
        // diff byte-for-byte as it did before auto existed. Plain properties never consult natural keys at
        // all (prism reads isConsideringNaturalKeys only in PrismContainerValueImpl), and an unkeyed
        // container drops to plain id equality, which an id-free desired file never satisfies.
        File live = write(dir, "live.xml", user(assignment("1", "role-1"), assignment("2", "role-2")));
        File desired = write(dir, "desired.xml", user(assignment(null, "role-1"), assignment(null, "role-3")));

        DiffEngine.DiffResult auto = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);
        DiffEngine.DiffResult realValue = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());

        assertEquals(paths(realValue), paths(auto), "auto must degrade to exactly real-value here");
        assertEquals(realValue.removedValueCount(), auto.removedValueCount(), "same blast radius");
    }

    @Test
    void autoRunsASinglePassWhenEveryContainerIsKeyed(@TempDir Path dir) throws Exception {
        // The other single-pass shortcut: nothing needs protecting, so there is no second diff to combine
        // and the result must equal a plain natural-keys diff. A sysconfig whose only multi-valued
        // containers are the keyed page and its panels is exactly that shape.
        File live = write(dir, "live.xml", sysconfigPanels("84",
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", sysconfigPanels(null,
                panel(null, "basic", null), panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult auto = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);
        DiffEngine.DiffResult naturalKeys = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll());

        assertEquals(paths(naturalKeys), paths(auto), "auto must equal a plain natural-keys diff here");
        assertTrue(auto.equivalenceChoices().stream().allMatch(c -> c.contains("natural-keys")),
                "every container must have been accepted: " + auto.equivalenceChoices());
    }

    @Test
    void aNestedUnfilledKeyLeavesThePageKeyedAndComparesOnlyThePanelsByContent(@TempDir Path dir) throws Exception {
        // objectDetailsPage fills in its own key (type); one panel below it has no <identifier>, and would pair
        // vacuously under natural keys. The page is paired by its type and edited where it stands; the panels
        // are paired by id alone, so without ids they are compared by content. This used to push the whole
        // page to real-value, replacing it — ids and all — for any change inside.
        File live = write(dir, "live.xml", userWithPanels("84", assignment("1", "role-1"),
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", userWithPanels(null, assignment(null, "role-1"),
                panel(null, "basic", null), "        <panel><displayOrder>16</displayOrder></panel>\n"));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        assertTrue(result.equivalenceChoices().stream()
                        .anyMatch(c -> c.contains("objectDetailsPage=ids") && c.contains("panel")
                                && c.contains("identifier")),
                "the page is paired by its key, naming the panel list and its key: " + result.equivalenceChoices());
        assertTrue(result.equivalenceChoices().stream().noneMatch(c -> c.contains("natural-keys")),
                "nothing in this object may claim a natural-keys bucket: " + result.equivalenceChoices());
        assertNoOverlappingPaths(result);
        for (ItemDelta<?, ?> mod : result.delta().getModifications()) {
            String path = mod.getPath().toString();
            if (path.contains("objectDetailsPage")) {
                assertTrue(path.contains("objectDetailsPage/84/panel"),
                        "the page stays; only a panel is added or deleted: " + path);
            }
        }
    }

    @Test
    void autoCombinesTwoPassesWithoutOverlappingOrLosingDeltas(@TempDir Path dir) throws Exception {
        // The invariant that a bad partition breaks: midPoint's own TestParseDiffPatch round trip. Applying
        // the combined delta to a clone of live must yield desired exactly — which fails if a modification
        // was dropped by both passes, or kept by both and merged into nonsense.
        PrismObject<? extends Objectable> live = prismContext.parseObject(write(dir, "live.xml",
                userWithPanels("84", assignment("1", "role-1") + assignment("2", "role-2"),
                        panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15))));
        PrismObject<? extends Objectable> desired = prismContext.parseObject(write(dir, "desired.xml",
                userWithPanels("84", assignment("1", "role-1") + assignment("3", "role-3"),
                        panel("283", "basic", null), panel("284", "aielcm-delimited-values", 16))));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);
        assertNoOverlappingPaths(result);

        PrismObject<? extends Objectable> patched = live.clone();
        ((ObjectDelta) result.delta()).applyTo(patched);
        assertTrue(patched.equivalent(desired),
                "the combined delta must reconcile live to desired:\n" + patched.debugDump());
    }

    @Test
    void autoCombinesThePassByIdWithTheOthersWithoutOverlappingOrLosingDeltas(@TempDir Path dir) throws Exception {
        // The same round trip with a page paired by id: its unnamed panel changes, the other is kept, and an
        // assignment changes beside it in the real-value pass.
        PrismObject<? extends Objectable> live = prismContext.parseObject(write(dir, "live.xml",
                userWithPanels("84", assignment("1", "role-1") + assignment("2", "role-2"),
                        panel("283", "basic", null), "        <panel id=\"284\"><displayOrder>15</displayOrder></panel>\n")));
        PrismObject<? extends Objectable> desired = prismContext.parseObject(write(dir, "desired.xml",
                userWithPanels(null, assignment(null, "role-1") + assignment(null, "role-3"),
                        panel(null, "basic", null), "        <panel><displayOrder>16</displayOrder></panel>\n")));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);
        assertTrue(result.equivalenceChoices().stream().anyMatch(c -> c.contains("objectDetailsPage=ids")),
                result.equivalenceChoices().toString());
        assertNoOverlappingPaths(result);

        PrismObject<? extends Objectable> patched = live.clone();
        ((ObjectDelta) result.delta()).applyTo(patched);
        assertTrue(patched.equivalent(desired),
                "the combined delta must reconcile live to desired:\n" + patched.debugDump());
    }

    @Test
    void autoKeepsTheDeepEditUnderScopePresent(@TempDir Path dir) throws Exception {
        // Auto produces deltas below a multi-valued container far more often than real-value did, and
        // scope=present judges a delta at its id-free prefix (the path truncated at the first container id).
        // So the panel edit is judged at adminGuiConfiguration/objectDetails/objectDetailsPage, which the
        // desired object declares — it must survive rather than be dropped as an unmanaged item.
        File live = write(dir, "live.xml", userWithPanels("84", assignment("1", "role-1"),
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", userWithPanels(null, assignment(null, "role-1"),
                panel(null, "basic", null), panel(null, "aielcm-delimited-values", 16)));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        assertTrue(result.hasDrift(), "the displayOrder change must survive scope=present");
        assertTrue(paths(result).stream().anyMatch(p -> p.contains("displayOrder")),
                "expected the deep displayOrder delta: " + paths(result));
    }

    @Test
    void autoDoesNotChurnOnADesiredFileThatCarriesContainerIds(@TempDir Path dir) throws Exception {
        // natural-keys is not real-value plus keys: prism builds it from REAL_VALUE_CONSIDER_DIFFERENT_IDS,
        // so the natural-keys bucket also requires that container ids do not contradict. A tree exported
        // from a server carries ids, so check the ids auto inherits do not turn an unchanged object into
        // drift — the one way the two buckets differ for a reason other than pairing.
        String body = assignment("1", "role-1");
        File live = write(dir, "live.xml", userWithPanels("84", body,
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));
        File desired = write(dir, "desired.xml", userWithPanels("84", body,
                panel("283", "basic", null), panel("284", "aielcm-delimited-values", 15)));

        DiffEngine.DiffResult result = engine().diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER);

        assertFalse(result.hasDrift(), "an identical id-carrying object must diff empty: " + paths(result));
    }

    /** No two modifications may target the same path: that is what a mis-partitioned two-pass diff looks like. */
    private static void assertNoOverlappingPaths(DiffEngine.DiffResult result) {
        List<String> paths = paths(result);
        assertEquals(paths.size(), paths.stream().distinct().count(),
                "the two passes must not both contribute a delta at one path: " + paths);
    }

    private static DiffEngine engine() {
        return new DiffEngine(prismContext);
    }

    /** Merge just the {@code assignment} container (per-path override), leaving everything else on replace. */
    private static DiffEngine.MultiValueStrategy mergeAssignments() {
        return DiffEngine.MultiValueStrategy.of(false,
                List.of(new DiffEngine.MultiValueStrategy.PathRule(ItemPath.fromString("assignment"), true)));
    }

    private static ItemDelta<?, ?> single(DiffEngine.DiffResult result) {
        assertEquals(1, result.delta().getModifications().size(), "expected a single itemDelta");
        return result.delta().getModifications().iterator().next();
    }

    private static boolean touchesItem(DiffEngine.DiffResult result, String localName) {
        return result.delta().getModifications().stream()
                .anyMatch(mod -> localName.equals(mod.getPath().firstName().getLocalPart()));
    }

    /** True if any modification deletes value(s) at a path mentioning {@code childLocalName}. */
    private static boolean deletesPath(DiffEngine.DiffResult result, String childLocalName) {
        return result.delta().getModifications().stream()
                .anyMatch(mod -> mod.getPath().toString().contains(childLocalName)
                        && mod.getValuesToDelete() != null && !mod.getValuesToDelete().isEmpty());
    }

    private static List<String> paths(DiffEngine.DiffResult result) {
        return result.delta().getModifications().stream().map(mod -> mod.getPath().toString()).toList();
    }

    /**
     * A {@code panel} entry keyed by its natural key {@code identifier}. {@code id} mirrors a real server
     * object (repository-assigned container ids); pass null for a hand-written desired fragment, which has none.
     */
    private static String panel(String id, String identifier, Integer displayOrder) {
        return "        <panel" + (id != null ? " id=\"" + id + "\"" : "") + ">\n"
                + "          <identifier>" + identifier + "</identifier>\n"
                + (displayOrder != null ? "          <displayOrder>" + displayOrder + "</displayOrder>\n" : "")
                + "        </panel>\n";
    }

    /** A systemConfiguration carrying the given panels in the UserType objectDetailsPage (both multi-valued). */
    private static String sysconfigPanels(String pageId, String... panels) {
        return "<systemConfiguration xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <adminGuiConfiguration><objectDetails>\n"
                + "      <objectDetailsPage" + (pageId != null ? " id=\"" + pageId + "\"" : "") + ">\n"
                + "        <type>c:UserType</type>\n"
                + String.join("", panels)
                + "      </objectDetailsPage>\n"
                + "  </objectDetails></adminGuiConfiguration>\n"
                + "</systemConfiguration>";
    }

    /** A systemConfiguration named SystemConfiguration whose single-valued deploymentInformation holds one child. */
    private static String sysconfig(String deploymentInfoChild) {
        return "<systemConfiguration xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"00000000-0000-0000-0000-000000000001\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <deploymentInformation>" + deploymentInfoChild + "</deploymentInformation>\n"
                + "</systemConfiguration>";
    }

    private static String targetRefOid(Object assignmentRealValue) {
        return ((AssignmentType) assignmentRealValue).getTargetRef().getOid();
    }

    @Test
    void aChangedSingleValuedReferenceUnderMergeDoesNotBecomeAnAddOnlyDelta(@TempDir Path dir) throws Exception {
        // prism emits a whole-value replace only for single-valued *properties* (fixupDelta is overridden in
        // PrismPropertyImpl alone); a single-valued *reference* diffs to delete-old + add-new. Dropping the
        // delete under merge would leave an add on a single-valued item, which midPoint rejects with
        // "Attempt to put more than one value to single-valued item" — and --plan throws the same locally
        // while advancing its projection. Cardinality, not delta shape, decides whether merge can apply.
        File desired = write(dir, "desired.xml", resource("aaaaaaaa-1111-1111-1111-111111111111"));
        File live = write(dir, "live.xml", resource("bbbbbbbb-2222-2222-2222-222222222222"));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.of(true, List.of()));

        ItemDelta<?, ?> connectorRef = null;
        for (ItemDelta<?, ?> mod : result.delta().getModifications()) {
            if (mod.getPath().lastName().getLocalPart().equals("connectorRef")) {
                connectorRef = mod;
            }
        }
        assertNotNull(connectorRef, "the changed reference must still be reconciled: " + result.delta());
        boolean addOnly = connectorRef.getValuesToAdd() != null && !connectorRef.getValuesToAdd().isEmpty()
                && (connectorRef.getValuesToDelete() == null || connectorRef.getValuesToDelete().isEmpty())
                && connectorRef.getValuesToReplace() == null;
        assertFalse(addOnly, "a single-valued reference must never end up add-only: " + connectorRef);
        assertTrue(result.mergeFallbackPaths().isEmpty(),
                "one value has nothing for merge to keep, so there is no fallback to report: "
                        + result.mergeFallbackPaths());
    }

    @Test
    void underMergeOnlyMultiValuedItemsTheFileMentionsCanBeReportedAsAFallback(@TempDir Path dir) throws Exception {
        // Merge is the default on a tracked tree, so this note is on every run: a changed single-valued item,
        // or one --scope present drops because the file never mentions it, must not produce one.
        File desired = write(dir, "desired.xml", "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"44444444-4444-4444-4444-444444444444\">\n"
                + "  <name>Merge Noise</name>\n  <description>new</description>\n</role>");
        File live = write(dir, "live.xml", "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"44444444-4444-4444-4444-444444444444\">\n"
                + "  <name>Merge Noise</name>\n  <description>old</description>\n"
                + "  <riskLevel>high</riskLevel>\n</role>");

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.of(true, List.of()));

        assertTrue(result.hasDrift(), "the description change is still sent: " + result.delta());
        assertTrue(result.mergeFallbackPaths().isEmpty(), result.mergeFallbackPaths().toString());
    }

    @Test
    void aMergeRuleOnANestedContainerArmsTheAccruedGuard(@TempDir Path dir) throws Exception {
        // The guard used to read only the outermost container of the delta path, while applyMerge matches
        // the full id-free path. A merge rule on a *nested* container therefore dropped that container's
        // deletes without ever arming the refusal, so an accrued value was overwritten in silence — with a
        // rule that names exactly the container being clobbered.
        File desired = write(dir, "desired.xml", sysconfigPanel("Desired label"));
        File live = write(dir, "live.xml", sysconfigLivePanels());

        DiffEngine.DiffResult result = engine().diff(desired, live,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.of(false, List.of(new DiffEngine.MultiValueStrategy.PathRule(
                        ItemPath.fromString("adminGuiConfiguration/objectDetails/objectDetailsPage/panel"),
                        true))));

        assertFalse(result.accruedInPlaceEditPaths().isEmpty(),
                "a merge rule on the nested container must arm the guard: " + result.delta());
    }

    private static String sysconfigPanel(String label) {
        return "<systemConfiguration xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"44444444-4444-4444-4444-444444444444\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <adminGuiConfiguration><objectDetails><objectDetailsPage>\n"
                + "    <type>c:UserType</type>\n"
                + "    <panel><display><label>" + label + "</label></display></panel>\n"
                + "  </objectDetailsPage></objectDetails></adminGuiConfiguration>\n"
                + "</systemConfiguration>";
    }

    /** The live object: the managed panel plus one accrued alongside it, both carrying container ids. */
    private static String sysconfigLivePanels() {
        return "<systemConfiguration xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"44444444-4444-4444-4444-444444444444\">\n"
                + "  <name>SystemConfiguration</name>\n"
                + "  <adminGuiConfiguration><objectDetails><objectDetailsPage id=\"1\">\n"
                + "    <type>c:UserType</type>\n"
                + "    <panel id=\"10\"><display><label>Set in the GUI</label></display></panel>\n"
                + "    <panel id=\"11\"><identifier>basic</identifier></panel>\n"
                + "  </objectDetailsPage></objectDetails></adminGuiConfiguration>\n"
                + "</systemConfiguration>";
    }

    @Test
    void aResolvedSmartReferenceIsInSync(@TempDir Path dir) throws Exception {
        // The reported case, fixture taken from the emitted plan file. The desired file names the connector
        // by filter so it is portable; midPoint resolved that at import and stored the OID plus
        // relation="org:default". Diffed literally it reads as a change, and the PATCH that follows strips
        // the OID and breaks the resource, because a modify does not re-resolve filters.
        File desired = write(dir, "desired.xml", resourceWithSmartRef(CSV_CONNECTOR));
        File live = write(dir, "live.xml", resourceWithResolvedRef(CSV_CONNECTOR, CONNECTOR_OID));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertTrue(result.delta().isEmpty(), "nothing to reconcile: " + result.delta());
        assertTrue(result.smartReferencePaths().stream().anyMatch(p -> p.contains("connectorRef")),
                "and it is reported, not silently dropped: " + result.smartReferencePaths());
    }

    @Test
    void aChangedSmartReferenceFilterIsStillAChange(@TempDir Path dir) throws Exception {
        // Same shape, but the operator re-pointed the filter at another connector. The live object still
        // carries the old filter, so this is decidable offline and must not be swallowed.
        File desired = write(dir, "desired.xml", resourceWithSmartRef("com.example.OtherConnector"));
        File live = write(dir, "live.xml", resourceWithResolvedRef(CSV_CONNECTOR, CONNECTOR_OID));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.delta().isEmpty(), "a re-pointed filter is a real change");
        assertTrue(result.smartReferencePaths().isEmpty(), result.smartReferencePaths().toString());
    }

    @Test
    void aStrippedFilterIsAcceptedButResolveModeStillReconciles(@TempDir Path dir) throws Exception {
        // The docs say the filter "may not even be stored in the repository after import". Then the two
        // objects cannot settle it, and 'accept' takes the live OID as the resolution; 'resolve' is the
        // escape hatch that sends the desired form for midPoint to re-resolve.
        File desired = write(dir, "desired.xml", resourceWithSmartRef(CSV_CONNECTOR));
        File live = write(dir, "live.xml", resourceWithOidOnlyRef(CONNECTOR_OID));

        assertTrue(engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll(), DiffEngine.SmartReferenceCheck.ACCEPT)
                .delta().isEmpty(), "accept takes the live OID as the resolution");

        assertFalse(engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll(), DiffEngine.SmartReferenceCheck.NEVER)
                .delta().isEmpty(), "resolve never suppresses");
    }

    @Test
    void anExplicitlyDifferentOidIsUntouchedBySmartReferenceHandling(@TempDir Path dir) throws Exception {
        // desired names the connector outright. That is not a smart reference, so a differing OID is drift.
        File desired = write(dir, "desired.xml", resource("aaaaaaaa-1111-1111-1111-111111111111"));
        File live = write(dir, "live.xml", resource(CONNECTOR_OID));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.delta().isEmpty(), "a different OID is a real change");
        assertTrue(result.smartReferencePaths().isEmpty(), result.smartReferencePaths().toString());
    }

    @Test
    void aFilterThatIsNotAReferenceFilterIsNeverSuppressed(@TempDir Path dir) throws Exception {
        // ObjectCollectionType.filter is a SearchFilterType that has nothing to do with references. It is a
        // property value, so it cannot reach the smart-reference pass — the case a naive scan for a <filter>
        // element would get wrong.
        File desired = write(dir, "desired.xml", objectCollection("c:UserType"));
        File live = write(dir, "live.xml", objectCollection("c:RoleType"));

        DiffEngine.DiffResult result = engine().diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.delta().isEmpty(), "a changed collection filter is a real change");
        assertTrue(result.smartReferencePaths().isEmpty(),
                "and must never be treated as a smart reference: " + result.smartReferencePaths());
    }

    private static final String CSV_CONNECTOR = "com.evolveum.polygon.connector.csv.CsvConnector";
    private static final String CONNECTOR_OID = "0fe2d1a8-028f-4a5f-a5a2-33475e8613e6";

    /** The desired form: portable, names the connector by filter, carries no OID. */
    private static String resourceWithSmartRef(String connectorType) {
        return resourceShell("  <connectorRef type=\"c:ConnectorType\">\n" + filterXml(connectorType)
                + "  </connectorRef>\n");
    }

    /** The live form after import: the resolved OID and relation, filter retained. */
    private static String resourceWithResolvedRef(String connectorType, String oid) {
        return resourceShell("  <connectorRef oid=\"" + oid + "\" relation=\"org:default\""
                + " type=\"c:ConnectorType\">\n" + filterXml(connectorType) + "  </connectorRef>\n");
    }

    /** The live form when midPoint dropped the filter after resolving it. */
    private static String resourceWithOidOnlyRef(String oid) {
        return resourceShell("  <connectorRef oid=\"" + oid + "\" relation=\"org:default\""
                + " type=\"c:ConnectorType\"/>\n");
    }

    private static String filterXml(String connectorType) {
        return "    <filter><q:equal><q:path>c:connectorType</q:path>\n"
                + "      <q:value>" + connectorType + "</q:value></q:equal></filter>\n";
    }

    private static String resourceShell(String connectorRef) {
        return "<resource xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:org=\"http://midpoint.evolveum.com/xml/ns/public/common/org-3\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\""
                + " oid=\"33333333-3333-3333-3333-333333333333\">\n"
                + "  <name>Example Resource</name>\n" + connectorRef + "</resource>";
    }

    private static String objectCollection(String type) {
        return "<objectCollection xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\""
                + " oid=\"55555555-5555-5555-5555-555555555555\">\n"
                + "  <name>Example Collection</name>\n"
                + "  <type>" + type + "</type>\n"
                + "  <filter><q:equal><q:path>c:name</q:path><q:value>x</q:value></q:equal></filter>\n"
                + "</objectCollection>";
    }

    private static String resource(String connectorOid) {
        return "<resource xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"33333333-3333-3333-3333-333333333333\">\n"
                + "  <name>Example Resource</name>\n"
                + "  <connectorRef oid=\"" + connectorOid + "\" type=\"c:ConnectorType\"/>\n"
                + "</resource>";
    }

    private static String role(String description) {
        return role(description, "");
    }

    private static String roleOid(String oid, String description) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"" + oid + "\">\n"
                + "  <name>Example Role</name>\n"
                + "  <description>" + description + "</description>\n"
                + "</role>";
    }

    private static String userWithPassword(String clearValue) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n"
                + "  <credentials>\n"
                + "    <password>\n"
                + "      <value>\n"
                + "        <clearValue>" + clearValue + "</clearValue>\n"
                + "      </value>\n"
                + "    </password>\n"
                + "  </credentials>\n"
                + "</user>";
    }

    private static String userWithPasswordAndFullName(String clearValue, String fullName) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n"
                + "  <fullName>" + fullName + "</fullName>\n"
                + "  <credentials>\n"
                + "    <password>\n"
                + "      <value>\n"
                + "        <clearValue>" + clearValue + "</clearValue>\n"
                + "      </value>\n"
                + "    </password>\n"
                + "  </credentials>\n"
                + "</user>";
    }

    private static String roleDescription(PrismObject<?> object) {
        return ((RoleType) object.asObjectable()).getDescription();
    }

    private static int assignmentCount(PrismObject<?> object) {
        return ((UserType) object.asObjectable()).getAssignment().size();
    }

    private static String role(String description, String extra) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"11111111-1111-1111-1111-111111111111\">\n"
                + "  <name>Example Role</name>\n"
                + "  <description>" + description + "</description>\n"
                + extra
                + "</role>";
    }

    /** A lookup table; {@code rows} is the raw row markup, so a test can write {@code incomplete="true"}. */
    private static String lookupTable(String rows) {
        return "<lookupTable xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"88888888-0000-0000-0000-00000000bbbb\">\n"
                + "  <name>zz-lookup</name>\n  " + rows + "\n</lookupTable>";
    }

    private static String user(String... assignments) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n"
                + String.join("", assignments)
                + "</user>";
    }

    /**
     * A user carrying both kinds of multi-valued container at once — the shape {@code --equivalence auto}
     * exists for. {@code objectDetailsPage} and {@code panel} declare natural keys this fills in, so pairing
     * is safe; {@code assignment} declares {@code identifier} and (like real configuration) does not fill it
     * in, so pairing there would match vacuously.
     */
    private static String userWithPanels(String pageId, String assignments, String... panels) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n"
                + assignments
                + "  <adminGuiConfiguration><objectDetails>\n"
                + "      <objectDetailsPage" + (pageId != null ? " id=\"" + pageId + "\"" : "") + ">\n"
                + "        <type>c:UserType</type>\n"
                + String.join("", panels)
                + "      </objectDetailsPage>\n"
                + "  </objectDetails></adminGuiConfiguration>\n"
                + "</user>";
    }

    private static String assignment(String id, String targetOid) {
        String idAttr = (id != null) ? " id=\"" + id + "\"" : "";
        return "  <assignment" + idAttr + "><targetRef oid=\"" + targetOid + "\" type=\"c:RoleType\"/></assignment>\n";
    }

    private static File write(Path dir, String name, String content) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path.toFile();
    }
}
