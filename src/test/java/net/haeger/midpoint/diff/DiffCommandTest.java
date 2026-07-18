package net.haeger.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // --- helpers ---

    private static DiffEngine engine() {
        return new DiffEngine(prismContext);
    }

    private static ItemDelta<?, ?> single(DiffEngine.DiffResult result) {
        assertEquals(1, result.delta().getModifications().size(), "expected a single itemDelta");
        return result.delta().getModifications().iterator().next();
    }

    private static boolean touchesItem(DiffEngine.DiffResult result, String localName) {
        return result.delta().getModifications().stream()
                .anyMatch(mod -> localName.equals(mod.getPath().firstName().getLocalPart()));
    }

    private static String targetRefOid(Object assignmentRealValue) {
        return ((AssignmentType) assignmentRealValue).getTargetRef().getOid();
    }

    private static String role(String description) {
        return role(description, "");
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
