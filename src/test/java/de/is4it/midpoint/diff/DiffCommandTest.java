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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static String role(String description, String extra) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"11111111-1111-1111-1111-111111111111\">\n"
                + "  <name>Example Role</name>\n"
                + "  <description>" + description + "</description>\n"
                + extra
                + "</role>";
    }

    private static String user(String... assignments) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"22222222-2222-2222-2222-222222222222\">\n"
                + "  <name>jack</name>\n"
                + String.join("", assignments)
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
