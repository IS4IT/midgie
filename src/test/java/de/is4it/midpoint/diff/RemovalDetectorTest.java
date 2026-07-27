/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-way removal set, driven through a real git repository: deploy-time detection of values that were
 * applied at an earlier revision and are no longer declared.
 *
 * <p>This is what makes {@code multivalue=merge} honest — a two-way diff cannot see a removal at all, so
 * without this a value dropped from a file lives on the server forever while {@code diff} reports
 * "No differences".
 */
class RemovalDetectorTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "44444444-0000-0000-0000-00000000bbbb";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aValueDroppedFromAFileIsDetected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role("<subtype>alpha</subtype>\n  <subtype>beta</subtype>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, role("<subtype>alpha</subtype>"), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), "exactly the dropped value: " + removals);
        assertTrue(removals.get(0).path.contains("subtype"), removals.get(0).toString());
        assertEquals("beta", removals.get(0).value);
    }

    @Test
    void aValueStillDeclaredIsNotReported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role("<subtype>alpha</subtype>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");

        assertTrue(detect(dir, first, file).isEmpty(), "an unchanged file removes nothing");
    }

    @Test
    void aDeletedFragmentYieldsItsValuesButNotTheSurvivingSiblingS(@TempDir Path dir) throws Exception {
        // Two fragments of one object. Deleting one must report only what *it* contributed — the object
        // itself is never a deletion candidate, and anything the sibling still declares stays.
        Path kept = dir.resolve("role.xml");
        Path extra = dir.resolve("role-extra.xml");
        Files.writeString(kept, role("<subtype>alpha</subtype>"), StandardCharsets.UTF_8);
        Files.writeString(extra, role("<description>from the extra fragment</description>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.delete(extra);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "drop the extra fragment");

        List<RemovalDetector.Removal> removals = detect(dir, first, kept);

        assertEquals(1, removals.size(), "only the deleted fragment's own value: " + removals);
        assertTrue(removals.get(0).path.contains("description"), removals.get(0).toString());
        assertTrue(removals.get(0).sourceFile.contains("role-extra.xml"), removals.get(0).toString());
        assertFalse(removals.stream().anyMatch(r -> r.path.contains("name")),
                "the name is still declared by the surviving fragment: " + removals);
        assertFalse(removals.stream().anyMatch(r -> r.path.contains("subtype")),
                "and so is the subtype: " + removals);
    }

    @Test
    void anObjectWhoseLastFragmentIsDeletedIsReportedWholeNotTakenApart(@TempDir Path dir) throws Exception {
        // The dangerous case: with no surviving fragment, diffing last-applied against an empty object makes
        // *every* item a "removal" — including <name>. Pruning that would strip the object's name (or have
        // midPoint reject the whole PATCH). Deleting objects is out of scope, so it must be reported whole.
        Path only = dir.resolve("role.xml");
        Path other = dir.resolve("other.xml");
        Files.writeString(only, role("<subtype>alpha</subtype>"), StandardCharsets.UTF_8);
        Files.writeString(other, "<role xmlns=\"" + NS + "\" oid=\"55555555-0000-0000-0000-00000000cccc\">\n"
                + "  <name>Other</name>\n</role>", StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.delete(only);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "delete the only fragment");

        RemovalDetector detector = new RemovalDetector(prismContext, GitRevision.of(dir.toFile()), first);
        List<RemovalDetector.Removal> removals = detector.detect(
                List.of(Fragment.of(prismContext.parseObject(other.toFile()), other.toFile())));

        assertTrue(removals.isEmpty(), "an orphaned object must not be taken apart value by value: " + removals);
        assertFalse(removals.stream().anyMatch(r -> r.path.contains("name")),
                "and its name must never be offered for deletion");
        assertEquals(1, detector.orphaned().size(), "it is reported as orphaned instead");
        assertTrue(detector.orphaned().get(0).sourceFile.contains("role.xml"));
    }

    @Test
    void aNestedValueMovedToASiblingFragmentIsNotARemoval(@TempDir Path dir) throws Exception {
        // The survivor subtraction has to work at the depth the removal is reported at. Diffing a fragment
        // against an empty object yields whole-container adds ('deploymentInformation'), while the removal
        // side recurses into the container ('deploymentInformation/headerColor') — so a survivor set built
        // from the former can never match the latter, and --prune deletes a value the sibling declares and
        // the apply pass just wrote. Top-level items (name, subtype, description) happen to work either way,
        // which is exactly why this went unnoticed.
        Path a = dir.resolve("sysconfig-a.xml");
        Path b = dir.resolve("sysconfig-b.xml");
        Files.writeString(a, sysconfig("<deploymentInformation><name>Prod</name></deploymentInformation>"),
                StandardCharsets.UTF_8);
        Files.writeString(b, sysconfig("<deploymentInformation><headerColor>navy</headerColor>"
                + "</deploymentInformation>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        // The operator moves headerColor from b to a — nothing is removed from the object as a whole.
        Files.writeString(a, sysconfig("<deploymentInformation><name>Prod</name>"
                + "<headerColor>navy</headerColor></deploymentInformation>"), StandardCharsets.UTF_8);
        Files.writeString(b, sysconfig("<deploymentInformation><customerName>ACME</customerName>"
                + "</deploymentInformation>"), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, a, b);

        assertTrue(removals.isEmpty(), "the sibling fragment still declares headerColor: " + removals);
    }

    @Test
    void anObjectsNameIsNeverOfferedForDeletion(@TempDir Path dir) throws Exception {
        // A deleted fragment that carried <name> alongside a sibling identifying the object by OID only:
        // dismantling it item by item puts 'delete name' in the prune delta, which midPoint's repo rejects —
        // failing the whole prune group — or, worse, leaves the object nameless.
        Path gone = dir.resolve("role-named.xml");
        Path kept = dir.resolve("role-extra.xml");
        Files.writeString(gone, role("<description>from the named fragment</description>"), StandardCharsets.UTF_8);
        Files.writeString(kept, "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <subtype>alpha</subtype>\n</role>", StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.delete(gone);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "drop the named fragment");

        List<RemovalDetector.Removal> removals = detect(dir, first, kept);

        assertFalse(removals.stream().anyMatch(r -> r.path.contains("name")),
                "the object's name must never be a removal candidate: " + removals);
        assertTrue(removals.stream().anyMatch(r -> r.path.contains("description")),
                "but its own description still is: " + removals);
    }

    @Test
    void aLongValueIsComparedByContentNotByATruncatedString(@TempDir Path dir) throws Exception {
        // The survivor subtraction must compare real values, not rendered text. Two descriptions that agree
        // for the first 120 characters and differ after would collapse to the same rendered key, hiding a
        // genuine removal — and under --prune, leaving it on the server for ever.
        String shared = "x".repeat(130);
        Path kept = dir.resolve("role.xml");
        Path extra = dir.resolve("role-extra.xml");
        Files.writeString(kept, role("<description>" + shared + "A</description>"), StandardCharsets.UTF_8);
        Files.writeString(extra, role("<subtype>" + shared + "B</subtype>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.delete(extra);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "drop the extra fragment");

        List<RemovalDetector.Removal> removals = detect(dir, first, kept);

        assertEquals(1, removals.size(), "the long subtype is genuinely removed: " + removals);
        assertTrue(removals.get(0).path.contains("subtype"), removals.get(0).toString());
    }

    @Test
    void thePruneDeltaOnlyEverDeletesAndOnlyWhatItCanAddress() throws Exception {
        // This builds the itemDeltas --prune PATCHes into a live server. DESIGN's central safety claim — that
        // pruning is "structurally unable to reach accrued or foreign state" — rests on it emitting nothing
        // but deletes, and only for removals that carry a definition.
        PrismObject<? extends com.evolveum.midpoint.prism.Objectable> object =
                prismContext.parseObject(role("<subtype>alpha</subtype>"));
        com.evolveum.midpoint.prism.Item<?, ?> subtype =
                object.findItem(com.evolveum.midpoint.prism.path.ItemPath.fromString("subtype"));
        com.evolveum.midpoint.prism.path.ItemPath path =
                com.evolveum.midpoint.prism.path.ItemPath.fromString("subtype");

        RemovalDetector.Removal prunable = new RemovalDetector.Removal("RoleType/Removal Probe", "subtype",
                "alpha", "role.xml", RoleType.class, OID, "Removal Probe", subtype.getDefinition(), path,
                subtype.getValues().get(0));
        RemovalDetector.Removal noDefinition = new RemovalDetector.Removal("RoleType/Removal Probe",
                "extension/weird", "?", "role.xml", RoleType.class, OID, "Removal Probe", null, path,
                subtype.getValues().get(0));

        assertNull(RemovalDetector.pruneDelta(prismContext, OID, List.of()), "nothing to prune, no delta");
        assertNull(RemovalDetector.pruneDelta(prismContext, OID, List.of(noDefinition)),
                "a removal with no definition cannot be turned into a delta");

        ObjectDelta<?> delta = RemovalDetector.pruneDelta(prismContext, OID, List.of(prunable, noDefinition));
        assertEquals(1, delta.getModifications().size(), "only the prunable removal: " + delta);
        ItemDelta<?, ?> only = delta.getModifications().iterator().next();
        assertEquals(1, only.getValuesToDelete().size(), "one value deleted");
        assertNull(only.getValuesToAdd(), "a prune never adds");
        assertNull(only.getValuesToReplace(), "and never replaces");
        assertEquals(OID, delta.getOid());
    }

    // --- helpers ---

    /** Run detection for the files currently present, comparing against {@code revision}. */
    private static List<RemovalDetector.Removal> detect(Path dir, String revision, Path... present)
            throws Exception {
        java.util.List<Fragment> units = new java.util.ArrayList<>();
        for (Path file : present) {
            units.add(Fragment.of(prismContext.parseObject(file.toFile()), file.toFile()));
        }
        return new RemovalDetector(prismContext, GitRevision.of(dir.toFile()), revision).detect(units);
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Removal Probe</name>\n  " + body + "\n</role>";
    }

    private static String sysconfig(String body) {
        return "<systemConfiguration xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>SystemConfiguration</name>\n  " + body + "\n</systemConfiguration>";
    }

    /** Commit everything in {@code dir}, returning the resulting revision, or null when git is unusable. */
    private static String commit(Path dir) throws Exception {
        if (run(dir, "git", "init", "-q", ".") != 0) {
            return null;
        }
        run(dir, "git", "config", "user.email", "test@example.invalid");
        run(dir, "git", "config", "user.name", "midgie test");
        run(dir, "git", "add", "-A");
        if (run(dir, "git", "commit", "-qm", "initial") != 0) {
            return null;
        }
        return GitRevision.of(dir.toFile()).head();
    }

    private static int run(Path dir, String... command) throws Exception {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
