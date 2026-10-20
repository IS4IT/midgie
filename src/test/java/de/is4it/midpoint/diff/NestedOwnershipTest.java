/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff side of ownership below the top level: on a tracked run under {@code --scope present}, a live item
 * inside a container value the tree declares is kept when the tree does not declare the item, instead of being
 * deleted by the two-way diff. Untracked runs and {@code --scope full} must stay exactly as they were.
 */
class NestedOwnershipTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "66666666-0000-0000-0000-00000000dddd";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void anItemAddedOnTheServerInsideAKeyedValueIsKeptOnATrackedRun() throws Exception {
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", ""));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given", "<lifecycleState>draft</lifecycleState>"));

        DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.PRESENT, true);

        assertTrue(result.delta().isEmpty(), "nothing the tree declares differs: " + result.delta());
        assertEquals(1, result.keptNested().size(), "and the kept item is reported: " + result.keptNested());
        assertEquals("mapping/4/lifecycleState", PathText.of(result.keptNested().get(0).path()));
    }

    @Test
    void untrackedTheSameItemIsStillDeleted() throws Exception {
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", ""));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given", "<lifecycleState>draft</lifecycleState>"));

        DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.PRESENT, false);

        assertTrue(deletes(result.delta(), "mapping/4/lifecycleState"), "unchanged untracked: " + result.delta());
        assertTrue(result.keptNested().isEmpty());
    }

    @Test
    void scopeFullKeepsNothingEvenWhenTracked() throws Exception {
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", ""));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given", "<lifecycleState>draft</lifecycleState>"));

        DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.FULL, true);

        assertTrue(deletes(result.delta(), "mapping/4/lifecycleState"), "full replace: " + result.delta());
        assertTrue(result.keptNested().isEmpty());
    }

    @Test
    void theLiveIdIsMappedBackToTheDesiredValueByNaturalKey() throws Exception {
        // Two mappings on the server, ids 4 and 7. The file declares lifecycleState on the second only: its
        // replace is sent, while the first one's server-side lifecycleState — which the file does not declare
        // in that mapping — is kept. Deciding "declared" by id-free path alone would get one of them wrong.
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", "")
                + mapping(null, "family", "<lifecycleState>active</lifecycleState>"));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given", "<lifecycleState>draft</lifecycleState>")
                + mapping(7L, "family", "<lifecycleState>draft</lifecycleState>"));

        DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.PRESENT, true);

        assertEquals(List.of("mapping/7/lifecycleState"), paths(result.delta()),
                "only the declared item changes: " + result.delta());
        assertTrue(result.delta().getModifications().iterator().next().getValuesToReplace() != null,
                "as a replace of a single-valued item desired declares");
        assertEquals(List.of("mapping/4/lifecycleState"),
                result.keptNested().stream().map(k -> PathText.of(k.path())).collect(Collectors.toList()));
    }

    @Test
    void aNestedItemInsideSingleValuedContainersIsReportedAsKept() throws Exception {
        // --scope present already protected an undeclared child of a single-valued container; on a tracked
        // run it is also said, since the tree never deployed it.
        PrismObject<? extends Objectable> desired = archetype("<archetypePolicy><display>"
                + "<icon><cssClass>fa fa-a</cssClass></icon></display></archetypePolicy>");
        PrismObject<? extends Objectable> live = archetype("<archetypePolicy><display><label>Server label</label>"
                + "<icon><cssClass>fa fa-a</cssClass></icon></display></archetypePolicy>");

        DiffEngine.DiffResult tracked = diff(desired, live, DiffEngine.Scope.PRESENT, true);
        DiffEngine.DiffResult untracked = diff(desired, live, DiffEngine.Scope.PRESENT, false);

        assertTrue(tracked.delta().isEmpty(), tracked.delta().toString());
        assertEquals(List.of("archetypePolicy/display/label"),
                tracked.keptNested().stream().map(k -> PathText.of(k.path())).collect(Collectors.toList()));
        assertTrue(untracked.delta().isEmpty(), "untracked keeps it as before: " + untracked.delta());
        assertTrue(untracked.keptNested().isEmpty(), "and says nothing, as before");
    }

    @Test
    void aWholeValueChangeOfAnUnkeyedListIsLeftAlone() throws Exception {
        // inducement declares a key real config never fills in, so auto diffs it by content: the old value is
        // deleted whole and the new one added. That is list membership, at a path the file declares, and is
        // not the nested filter's to touch.
        PrismObject<? extends Objectable> desired = role("<inducement><targetRef oid=\""
                + "aaaaaaaa-0000-0000-0000-000000000001\" type=\"RoleType\"/></inducement>");
        PrismObject<? extends Objectable> live = role("<inducement id=\"1\"><targetRef oid=\""
                + "aaaaaaaa-0000-0000-0000-000000000002\" type=\"RoleType\"/></inducement>");

        // On replace, so the delete side survives for the test to see (merge would keep the old value).
        DiffEngine engine = new DiffEngine(prismContext);
        DiffEngine.DiffResult tracked = engine.diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER, true);
        DiffEngine.DiffResult untracked = engine.diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll(),
                DiffEngine.SmartReferenceCheck.NEVER, false);

        assertEquals(paths(untracked.delta()), paths(tracked.delta()), "tracked: " + tracked.delta());
        ItemDelta<?, ?> inducement = tracked.delta().getModifications().iterator().next();
        assertEquals("inducement", PathText.of(inducement.getPath()));
        assertNotNull(inducement.getValuesToDelete(), "the old value is still deleted whole");
        assertTrue(tracked.keptNested().isEmpty());
    }

    @Test
    void anUndeclaredContainerInsideAKeyedValueIsKeptWhole() throws Exception {
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", ""));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given",
                "<condition><script><code>true</code></script></condition>"));

        DiffEngine.DiffResult tracked = diff(desired, live, DiffEngine.Scope.PRESENT, true);

        assertTrue(tracked.delta().isEmpty(), tracked.delta().toString());
        assertEquals("mapping/4/condition", PathText.of(tracked.keptNested().get(0).path()));
    }

    @Test
    void anItemAddedInsideAKeyedValueIsAddressedByTheServersId() throws Exception {
        // prism builds the path of an item only desired holds from the desired value, which has no id: the
        // add came out as 'mapping/lifecycleState', naming no mapping, and --scope present failed resolving it.
        PrismObject<? extends Objectable> desired = template(mapping(null, "given", "")
                + mapping(null, "family", "<lifecycleState>proposed</lifecycleState>"));
        PrismObject<? extends Objectable> live = template(mapping(4L, "given", "") + mapping(7L, "family", ""));

        for (boolean tracked : new boolean[] {false, true}) {
            DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.PRESENT, tracked);
            assertEquals(List.of("mapping/7/lifecycleState"), paths(result.delta()), "tracked=" + tracked);
        }
        assertEquals(null, desired.getValue().findContainer(
                com.evolveum.midpoint.prism.path.ItemName.from(NS, "mapping")).getValues().get(1).getId(),
                "the caller's desired object is left as it was");
    }

    @Test
    void twoLevelsDownAnItemOnlyTheServerHoldsIsKeptOnATrackedRun() throws Exception {
        // A panel inside an object details page: both keyed, so the live path carries two ids, and each has
        // to be resolved back to its desired value before "the file does not declare it" can be said.
        PrismObject<? extends Objectable> desired = role(gui(null, null, ""));
        PrismObject<? extends Objectable> live = role(gui(5L, 9L, "<description>server</description>"));

        DiffEngine.DiffResult tracked = diff(desired, live, DiffEngine.Scope.PRESENT, true);
        DiffEngine.DiffResult untracked = diff(desired, live, DiffEngine.Scope.PRESENT, false);

        assertTrue(tracked.delta().isEmpty(), tracked.delta().toString());
        assertEquals(List.of("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                tracked.keptNested().stream().map(k -> PathText.of(k.path())).collect(Collectors.toList()));
        assertTrue(deletes(untracked.delta(), "adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                "untracked, the file still decides: " + untracked.delta());
    }

    @Test
    void twoLevelsDownAnAddedItemIsAddressedThroughBothOfTheServersIds() throws Exception {
        PrismObject<? extends Objectable> desired = role(gui(null, null, "<description>mine</description>"));
        PrismObject<? extends Objectable> live = role(gui(5L, 9L, ""));

        for (boolean tracked : new boolean[] {false, true}) {
            DiffEngine.DiffResult result = diff(desired, live, DiffEngine.Scope.PRESENT, tracked);
            assertEquals(List.of("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                    paths(result.delta()), "tracked=" + tracked);
        }
    }

    @Test
    void twoLevelsDownADeclaredItemIsStillReplaced() throws Exception {
        PrismObject<? extends Objectable> desired = role(gui(null, null, "<description>mine</description>"));
        PrismObject<? extends Objectable> live = role(gui(5L, 9L, "<description>server</description>"));

        DiffEngine.DiffResult tracked = diff(desired, live, DiffEngine.Scope.PRESENT, true);

        assertEquals(List.of("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                paths(tracked.delta()));
        assertTrue(tracked.keptNested().isEmpty(), tracked.keptNested().toString());
    }

    // --- helpers ---

    /** A user details page holding one panel, 'first'; ids for a server's copy, none for a file's. */
    private static String gui(Long pageId, Long panelId, String panelExtra) {
        return "<adminGuiConfiguration><objectDetails><objectDetailsPage"
                + (pageId != null ? " id=\"" + pageId + "\"" : "") + ">"
                + "<type xmlns:c=\"" + NS + "\">c:UserType</type>"
                + "<panel" + (panelId != null ? " id=\"" + panelId + "\"" : "") + "><identifier>first</identifier>"
                + panelExtra + "</panel></objectDetailsPage></objectDetails></adminGuiConfiguration>";
    }

    private static DiffEngine.DiffResult diff(PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> live, DiffEngine.Scope scope, boolean tracked) throws Exception {
        return new DiffEngine(prismContext).diff(desired, live, DiffEngine.EquivalencePlan.auto(), scope,
                DiffEngine.MultiValueStrategy.of(tracked, List.of()), DiffEngine.SmartReferenceCheck.NEVER, tracked);
    }

    private static List<String> paths(ObjectDelta<?> delta) {
        return delta.getModifications().stream().map(mod -> PathText.of(mod.getPath())).collect(Collectors.toList());
    }

    private static boolean deletes(ObjectDelta<?> delta, String path) {
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (PathText.of(mod.getPath()).equals(path) && mod.getValuesToDelete() != null
                    && !mod.getValuesToDelete().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    static String mapping(Long id, String name, String extra) {
        return "<mapping" + (id != null ? " id=\"" + id + "\"" : "") + "><name>" + name + "</name>"
                + "<strength>strong</strength>" + extra + "</mapping>";
    }

    private static PrismObject<? extends Objectable> template(String body) throws Exception {
        return prismContext.parseObject("<objectTemplate xmlns=\"" + NS + "\" oid=\"" + OID + "\">"
                + "<name>Nested Probe</name>" + body + "</objectTemplate>");
    }

    private static PrismObject<? extends Objectable> archetype(String body) throws Exception {
        return prismContext.parseObject("<archetype xmlns=\"" + NS + "\" oid=\"" + OID + "\">"
                + "<name>Nested Probe</name>" + body + "</archetype>");
    }

    private static PrismObject<? extends Objectable> role(String body) throws Exception {
        return prismContext.parseObject("<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">"
                + "<name>Nested Probe</name>" + body + "</role>");
    }
}
