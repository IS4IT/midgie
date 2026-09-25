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
        List<RemovalDetector.Removal> removals = detector.detect(DeployObject.group(
                List.of(Fragment.of(prismContext.parseObject(other.toFile()), other.toFile()))));

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

    @Test
    void aRemovedValueHoldingAMultiValuedContainerIsStillReported(@TempDir Path dir) throws Exception {
        // Each removal is reported with a one-line preview of the value. Building it used to ask every item
        // of that value for "the" real value, which prism refuses for a multi-valued one — so dropping a GUI
        // details page that held two panels threw "Attempt to get single value from a multivalued container
        // panel" out of findRemovals and killed the whole deploy, before anything was applied and even under
        // --dry-run. A preview must never be able to do that.
        Path file = dir.resolve("sysconfig.xml");
        Files.writeString(file, sysconfig("<adminGuiConfiguration><objectDetails><objectDetailsPage>"
                + "<type>UserType</type>"
                + "<panel><identifier>basic</identifier></panel>"
                + "<panel><identifier>extra</identifier></panel>"
                + "</objectDetailsPage></objectDetails></adminGuiConfiguration>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, sysconfig("<adminGuiConfiguration><objectDetails/></adminGuiConfiguration>"),
                StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), "the dropped page is one removal: " + removals);
        RemovalDetector.Removal removal = removals.get(0);
        assertTrue(removal.path.contains("objectDetailsPage"), removal.toString());
        assertTrue(removal.value.contains("type=UserType"), "the preview names what it can: " + removal);
        assertTrue(removal.value.contains("panel ×2"),
                "and says how many of what it cannot: " + removal);
    }

    @Test
    void aMappingThatOnlyLostOneNestedItemIsNeverRemovedWhole(@TempDir Path dir) throws Exception {
        // The hazard the item-granular walk exists for. Matched by content, a mapping that lost its
        // lifecycleState reads as "the whole old mapping was removed". Once a tracked diff leaves undeclared
        // nested items alone, the live mapping can still equal that old form exactly — and the removal would
        // delete the entire mapping. It has to be "lifecycleState inside mapping[name=given]", nothing more.
        Path file = dir.resolve("template.xml");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given",
                "<lifecycleState>draft</lifecycleState>") + NestedOwnershipTest.mapping(null, "family", "")),
                StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", "")
                + NestedOwnershipTest.mapping(null, "family", "")), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), "one nested item, not a mapping: " + removals);
        RemovalDetector.Removal removal = removals.get(0);
        assertTrue(removal.isNested(), removal.toString());
        assertEquals("mapping/lifecycleState", removal.itemPath.toString());
        assertEquals("mapping[name=given]/lifecycleState", removal.path);
        assertEquals("draft", removal.value);
        assertFalse(removals.stream().anyMatch(r -> r.itemPath.size() == 1),
                "no removal may name the mapping itself: " + removals);

        // Against a server holding exactly the deployed mapping, the delete reaches its own copy by id.
        PrismObject<? extends com.evolveum.midpoint.prism.Objectable> live = prismContext.parseObject(template(
                NestedOwnershipTest.mapping(4L, "given", "<lifecycleState>draft</lifecycleState>")
                        + NestedOwnershipTest.mapping(7L, "family", "")));
        ObjectDelta<?> prune = RemovalDetector.pruneDelta(prismContext, OID, removals, live);
        assertEquals(1, prune.getModifications().size(), prune.toString());
        ItemDelta<?, ?> only = prune.getModifications().iterator().next();
        assertEquals("mapping/4/lifecycleState", only.getPath().toString());
    }

    @Test
    void aMappingDroppedEntirelyIsStillRemovedWhole(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("template.xml");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", "")
                + NestedOwnershipTest.mapping(null, "family", "")), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "family", "")), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), removals.toString());
        assertFalse(removals.get(0).isNested(), "a dropped value is a list-level removal, as before");
        assertEquals("mapping", removals.get(0).path);
    }

    @Test
    void anEditedUnkeyedListValueIsStillRemovedWhole(@TempDir Path dir) throws Exception {
        // No key, no id: nothing to pair the old inducement with, so its old form is removed whole, as before.
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role("<inducement><targetRef oid=\"aaaaaaaa-0000-0000-0000-000000000001\" "
                + "type=\"RoleType\"/></inducement>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, role("<inducement><targetRef oid=\"aaaaaaaa-0000-0000-0000-000000000002\" "
                + "type=\"RoleType\"/></inducement>"), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), removals.toString());
        assertEquals("inducement", removals.get(0).path);
        assertFalse(removals.get(0).isNested());
    }

    @Test
    void aSingleValuedNestedItemDroppedFromTheTreeIsARemovalOfThatItem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("archetype.xml");
        Files.writeString(file, archetype("<archetypePolicy><display><label>Old</label><icon><cssClass>fa</cssClass>"
                + "</icon></display></archetypePolicy>"), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, archetype("<archetypePolicy><display><icon><cssClass>fa</cssClass></icon>"
                + "</display></archetypePolicy>"), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), removals.toString());
        assertEquals("archetypePolicy/display/label", removals.get(0).path);
    }

    // --- the three-way rule below the top level, through the whole plan (desired, last-applied, live) ---

    @Test
    void anItemTheTreeDroppedIsRemovedAndTheMappingSurvivesWithItsId(@TempDir Path dir) throws Exception {
        // D absent, A draft, L draft: dropped by the tree, so removed — addressed to the server's mapping 4.
        Reconciler.Outcome outcome = threeWay(dir, "<lifecycleState>draft</lifecycleState>", "",
                "<lifecycleState>draft</lifecycleState>", true);

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        List<String> paths = outcome.delta.getModifications().stream().map(m -> m.getPath().toString()).toList();
        assertEquals(List.of("mapping/4/lifecycleState"), paths, "only the item, never the mapping: " + paths);
        assertEquals(1, outcome.removing.size());
        assertTrue(outcome.keptNested.isEmpty(), "the tree deployed it, so it is not reported as kept: "
                + outcome.keptNested);
    }

    @Test
    void anItemAddedOnTheServerIsKeptAndReported(@TempDir Path dir) throws Exception {
        // D absent, A absent, L draft: never deployed by the tree.
        Reconciler.Outcome outcome = threeWay(dir, "", "", "<lifecycleState>draft</lifecycleState>", true);

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertEquals(List.of("mapping/lifecycleState ×1"), outcome.keptNested);
    }

    @Test
    void anItemChangedOnTheServerAfterTheDeployIsKeptWithANote(@TempDir Path dir) throws Exception {
        // D absent, A draft, L active: someone changed it after the deploy, so it is theirs now.
        Reconciler.Outcome outcome = threeWay(dir, "<lifecycleState>draft</lifecycleState>", "",
                "<lifecycleState>active</lifecycleState>", true);

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertTrue(outcome.removing.isEmpty());
        assertTrue(outcome.warnings.stream().anyMatch(w -> w.contains("mapping[name=given]/lifecycleState")
                && w.contains("changed on the server after it was deployed")), outcome.warnings.toString());
        assertTrue(outcome.keptNested.isEmpty(), "said once, in the note: " + outcome.keptNested);
    }

    @Test
    void aDeclaredItemIsStillReplacedWhateverLastAppliedSaid(@TempDir Path dir) throws Exception {
        Reconciler.Outcome outcome = threeWay(dir, "<lifecycleState>draft</lifecycleState>",
                "<lifecycleState>active</lifecycleState>", "<lifecycleState>proposed</lifecycleState>", true);

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        ItemDelta<?, ?> only = outcome.delta.getModifications().iterator().next();
        assertEquals("mapping/4/lifecycleState", only.getPath().toString());
        assertTrue(only.getValuesToReplace() != null, "a replace with the declared value: " + only);
    }

    @Test
    void noPruneWithholdsTheNestedRemovalWithoutCallingItKept(@TempDir Path dir) throws Exception {
        Reconciler.Outcome outcome = threeWay(dir, "<lifecycleState>draft</lifecycleState>", "",
                "<lifecycleState>draft</lifecycleState>", false);

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertTrue(outcome.removing.isEmpty());
        assertTrue(outcome.keptNested.isEmpty(), "dropped by the tree, not someone else's: " + outcome.keptNested);
    }

    /**
     * Commit a template whose mapping 'given' holds {@code applied}, change the file so it holds
     * {@code desired}, and plan the change against a server whose mapping (id 4) holds {@code live}, the way a
     * tracked deploy does.
     */
    private static Reconciler.Outcome threeWay(Path dir, String applied, String desired, String live,
            boolean prune) throws Exception {
        Path file = dir.resolve("template.xml");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", applied)), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", desired)), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);
        PrismObject<? extends com.evolveum.midpoint.prism.Objectable> current =
                prismContext.parseObject(template(NestedOwnershipTest.mapping(4L, "given", live)));
        StrategyOptions.Resolved strategy = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.MultiValueStrategy.replaceAll(), false,
                StrategyOptions.SmartRefs.ACCEPT, ApplyRules.none(), true)
                .settleMultiValue(true).settleTracking(true);
        return new Reconciler(prismContext, null, true, true).planAgainst(
                prismContext.parseObject(file.toFile()), current, strategy, removals, prune);
    }

    // --- policy rules on nested items ---

    @Test
    void anOnCreateRuleOnANestedItemKeepsThatRemovalAndOnlyThatOne(@TempDir Path dir) throws Exception {
        // Policy selectors match id-free paths, so a nested removal has to carry one: a rule written for
        // mapping/lifecycleState must reach the lifecycleState dropped from a mapping, and nothing beside it.
        List<RemovalDetector.Removal> removals = droppedFromMapping(dir,
                "<lifecycleState>draft</lifecycleState><description>d</description>");
        assertEquals(2, removals.size(), removals.toString());

        List<RemovalDetector.Removal> kept = DeployCommand.withoutRestricted(
                policy(dir, "ObjectTemplateType/*/mapping/lifecycleState apply=on-create"), baseline(), removals);

        assertEquals(List.of("mapping[name=given]/description"), kept.stream().map(r -> r.path).toList(),
                "the on-create item stays on the server, the other one is still removed");
    }

    @Test
    void anIfAbsentRuleOnANestedItemKeepsThatRemovalAndOnlyThatOne(@TempDir Path dir) throws Exception {
        List<RemovalDetector.Removal> removals = droppedFromMapping(dir,
                "<lifecycleState>draft</lifecycleState><description>d</description>");

        List<RemovalDetector.Removal> kept = DeployCommand.withoutRestricted(
                policy(dir, "ObjectTemplateType/*/mapping/description apply=if-absent"), baseline(), removals);

        assertEquals(List.of("mapping[name=given]/lifecycleState"), kept.stream().map(r -> r.path).toList());
    }

    @Test
    void aRuleScopedToAnotherObjectDoesNotReachTheNestedRemoval(@TempDir Path dir) throws Exception {
        List<RemovalDetector.Removal> removals = droppedFromMapping(dir, "<lifecycleState>draft</lifecycleState>");

        List<RemovalDetector.Removal> kept = DeployCommand.withoutRestricted(
                policy(dir, "ObjectTemplateType/Some Other Template/mapping/lifecycleState apply=on-create"),
                baseline(), removals);

        assertEquals(1, kept.size(), "a rule for another template must not protect this one: " + kept);
    }

    @Test
    void aNestedItemAPolicyProtectsIsNeitherDeletedByTheDiffNorByTheRemoval(@TempDir Path dir) throws Exception {
        // Through the whole plan, as deploy runs it: the removal is filtered by the policy first, and the diff
        // itself must not delete the item either — tracked, it is undeclared nested state and kept.
        List<RemovalDetector.Removal> removals = droppedFromMapping(dir, "<lifecycleState>draft</lifecycleState>");
        Policy policy = policy(dir, "ObjectTemplateType/*/mapping/lifecycleState apply=on-create");
        StrategyOptions.Resolved strategy = policy.resolveFor("ObjectTemplateType", "Removal Probe Template", OID,
                trackedStrategy());
        List<RemovalDetector.Removal> filtered = DeployCommand.withoutRestricted(policy, strategy, removals);

        Reconciler.Outcome outcome = new Reconciler(prismContext, null, true, true).planAgainst(
                prismContext.parseObject(dir.resolve("template.xml").toFile()),
                prismContext.parseObject(template(NestedOwnershipTest.mapping(4L, "given",
                        "<lifecycleState>draft</lifecycleState>"))),
                strategy, filtered, true);

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, "nothing is sent: " + outcome.detail);
        assertTrue(outcome.removing.isEmpty(), outcome.removing.toString());        // The tree deployed it; the rule, not "someone else's", is why it stays — and the note says so.
        assertTrue(outcome.keptNested.isEmpty(), "not reported as never deployed: " + outcome.keptNested);
        assertEquals(List.of("mapping/lifecycleState (apply=on-create)"), outcome.heldBack);
    }

    // --- keyed values nested two levels deep: a panel inside an object details page ---

    @Test
    void aPanelThatOnlyLostOneItemTwoLevelsDownIsNeverRemovedWhole(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role(gui(null, null, "<description>old</description>", null)), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, role(gui(null, null, "", null)), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), "one item, not a panel or a page: " + removals);
        RemovalDetector.Removal removal = removals.get(0);
        assertEquals(2, removal.steps.size(), "paired through the page and then the panel: " + removal.steps);
        assertTrue(removal.path.contains("objectDetailsPage[type=")
                && removal.path.endsWith("panel[identifier=first]/description"), removal.path);

        // The delete is addressed through both of the server's ids.
        ObjectDelta<?> prune = RemovalDetector.pruneDelta(prismContext, OID, removals,
                prismContext.parseObject(role(gui(5L, 9L, "<description>old</description>", 10L))));
        assertEquals(List.of("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                prune.getModifications().stream().map(m -> m.getPath().toString()).toList());
    }

    @Test
    void aPanelDroppedFromAPageIsRemovedWholeInsideThatPage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role(gui(null, null, "", null)), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, role(onlySecondPanel()), StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);

        assertEquals(1, removals.size(), removals.toString());
        assertEquals(1, removals.get(0).steps.size(), "inside the page, which stays: " + removals.get(0).steps);
        ObjectDelta<?> prune = RemovalDetector.pruneDelta(prismContext, OID, removals,
                prismContext.parseObject(role(gui(5L, 9L, "", 10L))));
        ItemDelta<?, ?> only = prune.getModifications().iterator().next();
        assertEquals("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel", only.getPath().toString());
        assertEquals(9L, ((com.evolveum.midpoint.prism.PrismContainerValue<?>)
                only.getValuesToDelete().iterator().next()).getId(), "the server's own panel, by its id");
    }

    @Test
    void twoLevelsDownThePlanFollowsTheSameThreeWayRule(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("role.xml");
        Files.writeString(file, role(gui(null, null, "<description>old</description>", null)), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, role(gui(null, null, "", null)), StandardCharsets.UTF_8);
        List<RemovalDetector.Removal> removals = detect(dir, first, file);
        PrismObject<? extends com.evolveum.midpoint.prism.Objectable> desired =
                prismContext.parseObject(file.toFile());
        Reconciler reconciler = new Reconciler(prismContext, null, true, true);

        // Dropped by the tree, still as deployed on the server: removed — only the item, through both ids.
        Reconciler.Outcome dropped = reconciler.planAgainst(desired,
                prismContext.parseObject(role(gui(5L, 9L, "<description>old</description>", 10L))),
                trackedStrategy(), removals, true);
        assertEquals(List.of("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description"),
                dropped.delta.getModifications().stream().map(m -> m.getPath().toString()).toList());

        // Changed on the server after the deploy: kept, with a note.
        Reconciler.Outcome changed = reconciler.planAgainst(desired,
                prismContext.parseObject(role(gui(5L, 9L, "<description>theirs</description>", 10L))),
                trackedStrategy(), removals, true);
        assertEquals(Reconciler.Action.IN_SYNC, changed.action, changed.detail);
        assertTrue(changed.warnings.stream().anyMatch(w -> w.contains("changed on the server after it was deployed")),
                changed.warnings.toString());

        // Never deployed by the tree (nothing in last-applied names it): kept and reported.
        Reconciler.Outcome foreign = reconciler.planAgainst(desired,
                prismContext.parseObject(role(gui(5L, 9L, "", 10L).replace("<identifier>second</identifier>",
                        "<identifier>second</identifier><description>theirs</description>"))),
                trackedStrategy(), removals, true);
        assertEquals(Reconciler.Action.IN_SYNC, foreign.action, foreign.detail);
        assertTrue(foreign.keptNested.stream().anyMatch(k -> k.startsWith(
                "adminGuiConfiguration/objectDetails/objectDetailsPage/panel/description")), foreign.keptNested.toString());
    }

    // --- helpers ---

    /**
     * Commit a template whose mapping 'given' holds {@code items}, then drop them from the file, and return
     * what detection reports.
     */
    private static List<RemovalDetector.Removal> droppedFromMapping(Path dir, String items) throws Exception {
        Path file = dir.resolve("template.xml");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", items)), StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, template(NestedOwnershipTest.mapping(null, "given", "")), StandardCharsets.UTF_8);
        return detect(dir, first, file);
    }

    private static Policy policy(Path dir, String rules) throws Exception {
        Path policyFile = dir.resolve("midgie.policy");
        Files.writeString(policyFile, rules + "\n", StandardCharsets.UTF_8);
        return Policy.load(policyFile.toFile());
    }

    private static StrategyOptions.Resolved baseline() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.MultiValueStrategy.replaceAll(), false);
    }

    private static StrategyOptions.Resolved trackedStrategy() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.MultiValueStrategy.replaceAll(), false, StrategyOptions.SmartRefs.ACCEPT,
                ApplyRules.none(), true).settleMultiValue(true).settleTracking(true);
    }

    /**
     * A user details page holding panel 'first' (with {@code firstExtra}) and panel 'second'. Ids are given
     * for a server's copy and left out for a file's.
     */
    private static String gui(Long pageId, Long firstId, String firstExtra, Long secondId) {
        return "<adminGuiConfiguration><objectDetails><objectDetailsPage" + id(pageId) + ">"
                + "<type xmlns:c=\"" + NS + "\">c:UserType</type>"
                + "<panel" + id(firstId) + "><identifier>first</identifier>" + firstExtra + "</panel>"
                + "<panel" + id(secondId) + "><identifier>second</identifier></panel>"
                + "</objectDetailsPage></objectDetails></adminGuiConfiguration>";
    }

    private static String onlySecondPanel() {
        return "<adminGuiConfiguration><objectDetails><objectDetailsPage>"
                + "<type xmlns:c=\"" + NS + "\">c:UserType</type>"
                + "<panel><identifier>second</identifier></panel>"
                + "</objectDetailsPage></objectDetails></adminGuiConfiguration>";
    }

    private static String id(Long id) {
        return (id != null) ? " id=\"" + id + "\"" : "";
    }

    /** Run detection for the files currently present, comparing against {@code revision}. */
    @Test
    void aValueDroppedFromAnOnCreateItemIsNotARemoval(@TempDir Path dir) throws Exception {
        // Once the object exists midgie does not write an on-create item at all, and pruning is a write: a
        // task whose file stops declaring executionState must keep the state operators gave it.
        Path file = dir.resolve("task.xml");
        Files.writeString(file, task("<description>d</description>\n  <executionState>suspended</executionState>"),
                StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, task(""), StandardCharsets.UTF_8);
        Path policyFile = dir.resolve("midgie.policy");
        Files.writeString(policyFile, "TaskType/*/executionState apply=on-create\n", StandardCharsets.UTF_8);

        List<RemovalDetector.Removal> removals = detect(dir, first, file);
        assertTrue(removals.stream().anyMatch(r -> r.path.contains("executionState")),
                "detection itself still sees the dropped value: " + removals);

        StrategyOptions.Resolved baseline = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.MultiValueStrategy.replaceAll(), false);
        List<RemovalDetector.Removal> kept =
                DeployCommand.withoutRestricted(Policy.load(policyFile.toFile()), baseline, removals);

        assertFalse(kept.stream().anyMatch(r -> r.path.contains("executionState")), "kept: " + kept);
        assertTrue(kept.stream().anyMatch(r -> r.path.contains("description")),
                "an ordinary item is still pruned: " + kept);
    }

    @Test
    void aValueDroppedFromAnIfAbsentItemIsNotARemoval(@TempDir Path dir) throws Exception {
        // if-absent never removes what the server holds, so pruning it would break the same promise.
        Path file = dir.resolve("task.xml");
        Files.writeString(file, task("<description>d</description>\n  <executionState>suspended</executionState>"),
                StandardCharsets.UTF_8);
        String first = commit(dir);
        Assumptions.assumeTrue(first != null, "git is not usable here");
        Files.writeString(file, task(""), StandardCharsets.UTF_8);
        Path policyFile = dir.resolve("midgie.policy");
        Files.writeString(policyFile, "TaskType/*/description apply=if-absent\n", StandardCharsets.UTF_8);

        StrategyOptions.Resolved baseline = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.MultiValueStrategy.replaceAll(), false);
        List<RemovalDetector.Removal> kept = DeployCommand.withoutRestricted(
                Policy.load(policyFile.toFile()), baseline, detect(dir, first, file));

        assertFalse(kept.stream().anyMatch(r -> r.path.contains("description")), "kept: " + kept);
        assertTrue(kept.stream().anyMatch(r -> r.path.contains("executionState")),
                "an item without a rule is still pruned: " + kept);
    }

    private static List<RemovalDetector.Removal> detect(Path dir, String revision, Path... present)
            throws Exception {
        java.util.List<Fragment> fragments = new java.util.ArrayList<>();
        for (Path file : present) {
            fragments.add(Fragment.of(prismContext.parseObject(file.toFile()), file.toFile()));
        }
        // Grouped exactly as deploy groups them: detection compares whole objects, so files describing one
        // object have to arrive as one unit here too.
        return new RemovalDetector(prismContext, GitRevision.of(dir.toFile()), revision)
                .detect(DeployObject.group(fragments));
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Removal Probe</name>\n  " + body + "\n</role>";
    }

    private static String template(String body) {
        return "<objectTemplate xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Removal Probe Template</name>\n  " + body + "\n</objectTemplate>";
    }

    private static String archetype(String body) {
        return "<archetype xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Removal Probe Archetype</name>\n  " + body + "\n</archetype>";
    }

    private static String task(String body) {
        return "<task xmlns=\"" + NS + "\" oid=\"" + OID + "\">\n"
                + "  <name>Removal Probe Task</name>\n  " + body + "\n</task>";
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

    /**
     * Run a command in {@code dir}, bounded and without orphaning the child — the production code guards
     * both (see {@code GitRevision.run}). An unbounded waitFor hangs the whole build until CI's wall clock
     * gives up, and the exception path used to leave the process running.
     */
    private static int run(Path dir, String... command) throws Exception {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (java.io.IOException e) {
            return -1; // no git on this machine
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
