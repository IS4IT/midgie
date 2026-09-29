/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A smart reference inside a list entry the diff compares as a whole value — an id-less inducement whose
 * {@code targetRef} or {@code construction/resourceRef} names its target by filter.
 */
class NestedSmartReferenceTest {

    private static final String ROLE_A = "11111111-0000-0000-0000-00000000000a";
    private static final String ROLE_B = "11111111-0000-0000-0000-00000000000b";
    private static final String RESOURCE = "22222222-0000-0000-0000-000000000001";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void anInducementWhoseFilterTheServerResolvedIsInSync() throws Exception {
        // Used to delete the resolved inducement and add one pointing at nothing, on every run.
        for (boolean merge : List.of(false, true)) {
            DiffEngine.DiffResult result = diff(role("<inducement>" + filterRef("targetRef", "RoleType", "A")
                    + "</inducement>"), role(inducement(3, ROLE_A)), merge, DiffEngine.SmartReferenceCheck.ACCEPT);

            assertFalse(result.hasDrift(), (merge ? "merge" : "replace") + ":\n" + xml(result));
            assertEquals(List.of("inducement/targetRef"), result.smartReferencePaths());
        }
    }

    @Test
    void aResourceRefInsideAConstructionIsResolvedToo() throws Exception {
        DiffEngine.DiffResult result = diff(
                role("<inducement><construction>" + filterRef("resourceRef", "ResourceType", "R")
                        + "</construction></inducement>"),
                role("<inducement id=\"3\"><construction><resourceRef oid=\"" + RESOURCE
                        + "\" relation=\"org:default\" type=\"c:ResourceType\"/></construction></inducement>"),
                false, DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.hasDrift(), xml(result));
        assertEquals(List.of("inducement/construction/resourceRef"), result.smartReferencePaths());
    }

    @Test
    void anEntryThatMatchesSeveralResolutionsIsLeftAsAChange() throws Exception {
        // The server kept no filter, so either inducement could be the resolution.
        DiffEngine.DiffResult result = diff(role("<inducement>" + filterRef("targetRef", "RoleType", "A")
                        + "</inducement>"), role(inducement(3, ROLE_A) + inducement(4, ROLE_B)), true,
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertTrue(result.hasDrift(), xml(result));
        assertTrue(result.keyPairingDeclined().stream().anyMatch(note -> note.contains("--smart-refs verify")),
                result.keyPairingDeclined().toString());
        assertTrue(DeltaOutput.carriesSmartReference(result.delta()),
                "the entry sent whole must ask midPoint to resolve its filter");
    }

    @Test
    void resolveModeSendsTheEntryForTheServerToResolve() throws Exception {
        DiffEngine.DiffResult result = diff(role("<inducement>" + filterRef("targetRef", "RoleType", "A")
                + "</inducement>"), role(inducement(3, ROLE_A)), false, DiffEngine.SmartReferenceCheck.NEVER);

        assertTrue(result.hasDrift(), xml(result));
        assertTrue(DeltaOutput.carriesSmartReference(result.delta()),
                "a filter inside an added inducement needs reevaluateSearchFilters:\n" + xml(result));
    }

    @Test
    void aPlanFileWithAFilterInsideAnAddedEntryIsReplayedWithReevaluation(@TempDir Path dir) throws Exception {
        DiffEngine.DiffResult result = diff(role("<inducement>" + filterRef("targetRef", "RoleType", "A")
                + "</inducement>"), role(""), false, DiffEngine.SmartReferenceCheck.NEVER);
        File planDir = dir.toFile();
        PlanStore.write(planDir, 1, 3, "roles", "44444444-0000-0000-0000-000000000002", "R", PlanStore.Kind.PATCH,
                xml(result));

        PlanStore.Entry entry = PlanStore.read(planDir).get(0);

        assertTrue(PlanStore.setsReferenceByFilter(entry), xml(result));
    }

    // --- helpers ---

    private static DiffEngine.DiffResult diff(String desiredXml, String liveXml, boolean merge,
            DiffEngine.SmartReferenceCheck check) throws Exception {
        PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredXml);
        PrismObject<? extends Objectable> live = prismContext.parseObject(liveXml);
        return new DiffEngine(prismContext).diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT,
                merge ? DiffEngine.MultiValueStrategy.of(true, List.of()) : DiffEngine.MultiValueStrategy.replaceAll(),
                check);
    }

    private static String xml(DiffEngine.DiffResult result) throws Exception {
        return DeltaOutput.toModificationXml(prismContext, result.delta());
    }

    private static String role(String body) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:org=\"http://midpoint.evolveum.com/xml/ns/public/common/org-3\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\""
                + " oid=\"44444444-0000-0000-0000-000000000002\"><name>R</name>" + body + "</role>";
    }

    private static String inducement(int id, String target) {
        return "<inducement id=\"" + id + "\"><targetRef oid=\"" + target
                + "\" relation=\"org:default\" type=\"c:RoleType\"/></inducement>";
    }

    private static String filterRef(String element, String type, String name) {
        return "<" + element + " type=\"c:" + type + "\"><filter><q:equal><q:path>c:name</q:path>"
                + "<q:value>" + name + "</q:value></q:equal></filter></" + element + ">";
    }
}
