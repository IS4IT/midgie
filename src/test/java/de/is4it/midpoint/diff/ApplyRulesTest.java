/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy's {@code apply} knob: {@code on-create} items go into the POST that creates an object and are
 * left alone by every later PATCH. Planned with {@link Reconciler#planAgainst}, so no server is involved.
 */
class ApplyRulesTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String TASK_OID = "22222222-0000-0000-0000-000000000001";
    private static final String USER_OID = "22222222-0000-0000-0000-000000000002";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    // --- policy parsing ---

    @Test
    void anItemRuleLoadsAndResolvesToOnCreate(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "TaskType/*/executionState apply=on-create");
        ApplyRules apply = policy.resolveFor("TaskType", "Recompute", TASK_OID, baseline(DiffEngine.Scope.PRESENT)).apply;

        assertTrue(apply.isOnCreate(ItemPath.create(new QName(NS, "executionState"))));
        assertFalse(apply.isOnCreate(ItemPath.create(new QName(NS, "description"))));
        assertFalse(policy.resolveFor("RoleType", "Recompute", TASK_OID, baseline(DiffEngine.Scope.PRESENT))
                .apply.isOnCreate("executionState"));
    }

    @Test
    void theDoubleSlashFormReadsLikeTheSingleOne(@TempDir Path dir) throws Exception {
        // Blank segments are dropped by the selector parser, so 'TaskType/*//executionState' is the same rule.
        Policy policy = load(dir, "TaskType/*//executionState apply=on-create");
        assertTrue(policy.resolveFor("TaskType", "Recompute", TASK_OID, baseline(DiffEngine.Scope.PRESENT))
                .apply.isOnCreate("executionState"));
    }

    @Test
    void applyOnAnObjectSelectorIsRefused(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> load(dir, "TaskType/* apply=on-create"));
        assertTrue(e.getMessage().contains("per-item"), e.getMessage());
        assertTrue(e.getMessage().contains("line 1"), e.getMessage());
    }

    @Test
    void anUnknownApplyValueIsRefused(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class,
                () -> load(dir, "TaskType/*/executionState apply=never"));
        assertTrue(e.getMessage().contains("invalid value for 'apply'"), e.getMessage());
    }

    @Test
    void anUnknownKnobMessageListsApply(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> load(dir, "TaskType/*/executionState foo=bar"));
        assertTrue(e.getMessage().contains("apply"), e.getMessage());
    }

    @Test
    void renderedDeltaPathsMatchWithIdsAndPrefixesDropped(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "UserType/*/assignment/activation apply=on-create");
        ApplyRules apply = policy.resolveFor("UserType", "jdoe", USER_OID, baseline(DiffEngine.Scope.PRESENT)).apply;
        assertTrue(apply.isOnCreate("assignment/3/activation/administrativeStatus"));
        assertTrue(apply.isOnCreate("c:assignment/3/c:activation"));
        assertFalse(apply.isOnCreate("assignment/3/targetRef"));
    }

    // --- planning ---

    @Test
    void aPatchLeavesAnOnCreateItemAloneAndSendsTheRest(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "TaskType/*/executionState apply=on-create");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT,
                task("new description", "runnable"), task("old description", "suspended"));

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        assertEquals(1, outcome.changes, outcome.detail);
        assertFalse(outcome.modificationXml.contains("executionState"), outcome.modificationXml);
        assertTrue(outcome.modificationXml.contains("new description"), outcome.modificationXml);
        assertEquals(List.of("executionState"), outcome.heldBack);
    }

    @Test
    void anObjectWhoseOnlyDifferenceIsHeldBackIsInSync(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "TaskType/*/executionState apply=on-create");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT,
                task("same", "runnable"), task("same", "suspended"));

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertEquals(List.of("executionState"), outcome.heldBack);
    }

    @Test
    void aCreateStillCarriesTheOnCreateItem(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "TaskType/*/executionState apply=on-create");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT, task("d", "suspended"), null);

        assertEquals(Reconciler.Action.CREATE, outcome.action, outcome.detail);
        assertTrue(outcome.createBody.contains("executionState"), outcome.createBody);
        assertTrue(outcome.heldBack.isEmpty());
    }

    @Test
    void fullScopeDoesNotDeleteAnOnCreateItemTheFileLeavesOut(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "TaskType/*/executionState apply=on-create");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.FULL,
                task("same", null), task("same", "suspended"));

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertEquals(List.of("executionState"), outcome.heldBack);
    }

    @Test
    void aMoreSpecificAlwaysRuleWins(@TempDir Path dir) throws Exception {
        Policy policy = load(dir,
                "TaskType/*/executionState apply=on-create",
                "TaskType/Recompute/executionState apply=always");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT,
                task("same", "runnable"), task("same", "suspended"));

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        assertTrue(outcome.modificationXml.contains("executionState"), outcome.modificationXml);
        assertTrue(outcome.heldBack.isEmpty());
    }

    @Test
    void anAddedContainerComesWithoutItsOnCreateItem(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "UserType/*/activation/administrativeStatus apply=on-create");
        PrismObject<? extends Objectable> desired = parse(user(
                "<activation><administrativeStatus>disabled</administrativeStatus>"
                + "<validTo>2030-01-01T00:00:00Z</validTo></activation>"));
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT, desired, parse(user("")));

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
        assertTrue(outcome.modificationXml.contains("validTo"), outcome.modificationXml);
        assertFalse(outcome.modificationXml.contains("administrativeStatus"), outcome.modificationXml);
        assertEquals(List.of("activation/administrativeStatus"), outcome.heldBack);
        assertNotNull(desired.findItem(ItemPath.create(new QName(NS, "activation"),
                new QName(NS, "administrativeStatus"))),
                "the desired object itself must not be edited — the create body and prune read it too");
    }

    @Test
    void anAddedContainerHoldingOnlyOnCreateItemsIsNotSentAtAll(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "UserType/*/activation/administrativeStatus apply=on-create");
        Reconciler.Outcome outcome = plan(policy, DiffEngine.Scope.PRESENT,
                parse(user("<activation><administrativeStatus>disabled</administrativeStatus></activation>")),
                parse(user("")));

        assertEquals(Reconciler.Action.IN_SYNC, outcome.action, outcome.detail);
        assertEquals(List.of("activation/administrativeStatus"), outcome.heldBack);
    }

    // --- helpers ---

    private static StrategyOptions.Resolved baseline(DiffEngine.Scope scope) {
        return new StrategyOptions.Resolved(scope, DiffEngine.EquivalencePlan.of(EquivalenceStrategy.REAL_VALUE),
                DiffEngine.MultiValueStrategy.replaceAll(), false);
    }

    private static Policy load(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("policy.txt");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return Policy.load(file.toFile());
    }

    private static Reconciler.Outcome plan(Policy policy, DiffEngine.Scope scope, String desiredXml,
            String currentXml) throws Exception {
        return plan(policy, scope, parse(desiredXml), currentXml != null ? parse(currentXml) : null);
    }

    private static Reconciler.Outcome plan(Policy policy, DiffEngine.Scope scope,
            PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current) throws Exception {
        String type = desired.getCompileTimeClass().getSimpleName();
        String name = desired.getName().getOrig();
        StrategyOptions.Resolved strategy = policy.resolveFor(type, name, desired.getOid(), baseline(scope));
        return new Reconciler(prismContext, null, true, true).planAgainst(desired, current, strategy);
    }

    private static PrismObject<? extends Objectable> parse(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static String task(String description, String executionState) {
        return "<task xmlns=\"" + NS + "\" oid=\"" + TASK_OID + "\"><name>Recompute</name>"
                + "<description>" + description + "</description>"
                + (executionState != null ? "<executionState>" + executionState + "</executionState>" : "")
                + "</task>";
    }

    private static String user(String body) {
        return "<user xmlns=\"" + NS + "\" oid=\"" + USER_OID + "\"><name>jdoe</name>" + body + "</user>";
    }
}
