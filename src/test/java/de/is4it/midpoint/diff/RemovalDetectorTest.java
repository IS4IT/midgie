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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        if (first == null) {
            return; // git unavailable here
        }
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
        if (first == null) {
            return;
        }

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
        if (first == null) {
            return;
        }
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
        if (first == null) {
            return;
        }
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
        if (first == null) {
            return;
        }
        Files.delete(extra);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-qm", "drop the extra fragment");

        List<RemovalDetector.Removal> removals = detect(dir, first, kept);

        assertEquals(1, removals.size(), "the long subtype is genuinely removed: " + removals);
        assertTrue(removals.get(0).path.contains("subtype"), removals.get(0).toString());
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
