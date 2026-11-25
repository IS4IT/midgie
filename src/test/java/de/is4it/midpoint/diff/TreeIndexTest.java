/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which files of the tree describe a run's objects without being in the run — what keeps {@code --scope full}
 * from deleting the part of an object a partial run leaves out.
 */
class TreeIndexTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "77777777-0000-0000-0000-000000000001";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aFileOfTheSameObjectOutsideTheRunIsFound(@TempDir Path tree) throws Exception {
        File a = write(tree, "roles/a.xml", role(OID, "Split", "<description>a</description>"));
        File b = write(tree, "extra/b.xml", role(OID, "Split", "<subtype>b</subtype>"));

        Map<DeployObject, List<File>> outside = outsideRun(tree, List.of(), List.of(a), List.of(a));

        assertEquals(1, outside.size(), outside.toString());
        assertEquals(List.of(b.getCanonicalFile()), canonical(outside.values().iterator().next()));
    }

    @Test
    void aRunHoldingEveryFileIsComplete(@TempDir Path tree) throws Exception {
        File a = write(tree, "roles/a.xml", role(OID, "Split", "<description>a</description>"));
        File b = write(tree, "extra/b.xml", role(OID, "Split", "<subtype>b</subtype>"));

        assertTrue(outsideRun(tree, List.of(), List.of(a, b), List.of(tree.toFile())).isEmpty());
    }

    @Test
    void aFileNamingTheObjectByNameOnlyCounts(@TempDir Path tree) throws Exception {
        // A fragment without an OID belongs to the object by type and name, as grouping combines them.
        File a = write(tree, "a.xml", role(OID, "Split", ""));
        File b = write(tree, "other/b.xml", role(null, "Split", "<subtype>b</subtype>"));

        Map<DeployObject, List<File>> outside = outsideRun(tree, List.of(), List.of(a), List.of(a));

        assertEquals(List.of(b.getCanonicalFile()), canonical(outside.values().iterator().next()));
    }

    @Test
    void anotherObjectOrAnotherTypeIsNotCounted(@TempDir Path tree) throws Exception {
        File a = write(tree, "a.xml", role(OID, "Split", ""));
        write(tree, "b.xml", role("77777777-0000-0000-0000-000000000002", "Other", ""));
        write(tree, "c.xml", "<user xmlns=\"" + NS + "\"><name>Split</name></user>");

        assertTrue(outsideRun(tree, List.of(), List.of(a), List.of(a)).isEmpty());
    }

    @Test
    void xmlThatIsNotAMidpointObjectOrIsAPlanIsNotCounted(@TempDir Path tree) throws Exception {
        File a = write(tree, "a.xml", role(OID, "Split", ""));
        write(tree, "pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><name>Split</name></project>");
        write(tree, "plan/001.xml", "<!-- " + PlanStore.GENERATED_MARKER + " -->\n" + role(OID, "Split", ""));
        write(tree, "broken.xml", "<role xmlns=\"" + NS + "\"");
        write(tree, ".hidden/b.xml", role(OID, "Split", ""));

        assertTrue(outsideRun(tree, List.of(), List.of(a), List.of(a)).isEmpty());
    }

    @Test
    void anotherStagesFilesDoNotCount(@TempDir Path tree) throws Exception {
        File common = write(tree, "common/sys.xml", role(OID, "Split", "<description>common</description>"));
        File prod = write(tree, "stages/prod/sys.xml", role(OID, "Split", "<subtype>prod</subtype>"));
        write(tree, "stages/dev/sys.xml", role(OID, "Split", "<subtype>dev</subtype>"));

        Map<DeployObject, List<File>> outside = outsideRun(tree, List.of("stages/*"), List.of(common, prod),
                List.of(tree.resolve("common").toFile(), tree.resolve("stages/prod").toFile()));

        assertTrue(outside.isEmpty(), outside.toString());
    }

    @Test
    void withoutStageFoldersEveryStageCounts(@TempDir Path tree) throws Exception {
        File common = write(tree, "common/sys.xml", role(OID, "Split", "<description>common</description>"));
        File prod = write(tree, "stages/prod/sys.xml", role(OID, "Split", "<subtype>prod</subtype>"));
        File dev = write(tree, "stages/dev/sys.xml", role(OID, "Split", "<subtype>dev</subtype>"));

        Map<DeployObject, List<File>> outside = outsideRun(tree, List.of(), List.of(common, prod),
                List.of(tree.resolve("common").toFile(), tree.resolve("stages/prod").toFile()));

        assertEquals(List.of(dev.getCanonicalFile()), canonical(outside.values().iterator().next()));
    }

    @Test
    void theStageARunDeploysFromStillCountsWhole(@TempDir Path tree) throws Exception {
        // Deploying one of prod's files is deploying from prod: its other file there is part of this object.
        File common = write(tree, "common/sys.xml", role(OID, "Split", "<description>common</description>"));
        File prodA = write(tree, "stages/prod/a.xml", role(OID, "Split", "<subtype>prod-a</subtype>"));
        File prodB = write(tree, "stages/prod/b.xml", role(OID, "Split", "<documentation>prod-b</documentation>"));
        write(tree, "stages/dev/sys.xml", role(OID, "Split", "<subtype>dev</subtype>"));

        Map<DeployObject, List<File>> outside = outsideRun(tree, List.of("stages/*/"), List.of(common, prodA),
                List.of(common, prodA));

        assertEquals(List.of(prodB.getCanonicalFile()), canonical(outside.values().iterator().next()));
    }

    @Test
    void relativeNamesAreFromTheTreeRoot(@TempDir Path tree) throws Exception {
        File b = write(tree, "extra/b.xml", role(OID, "Split", ""));
        TreeIndex index = TreeIndex.build(prismContext, tree.toFile(), List.of(), List.of());

        assertEquals("extra" + File.separator + "b.xml", index.relative(b));
        assertFalse(index.relative(new File("/elsewhere/c.xml")).startsWith("extra"));
    }

    // --- helpers ---

    private static Map<DeployObject, List<File>> outsideRun(Path tree, List<String> stages, List<File> runFiles,
            List<File> runPaths) throws Exception {
        List<Fragment> fragments = new ArrayList<>();
        for (File file : runFiles) {
            fragments.add(Fragment.of(prismContext.parseObject(file), file));
        }
        TreeIndex index = TreeIndex.build(prismContext, tree.toFile(), stages, List.of());
        return index.outsideRun(DeployObject.group(fragments), runPaths);
    }

    private static List<File> canonical(List<File> files) throws IOException {
        List<File> out = new ArrayList<>();
        for (File file : files) {
            out.add(file.getCanonicalFile());
        }
        return out;
    }

    private static File write(Path tree, String relative, String content) throws IOException {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private static String role(String oid, String name, String body) {
        return "<role xmlns=\"" + NS + "\"" + (oid != null ? " oid=\"" + oid + "\"" : "") + "><name>" + name
                + "</name>" + body + "</role>";
    }
}
