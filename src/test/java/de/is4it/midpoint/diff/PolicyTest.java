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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.prism.path.ItemPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the {@code deploy --policy} strategy file: a unified glob selector over
 * {@code Type/Name/item…} with {@code *}/{@code **} at any level, per-knob most-specific-wins, {@code oid:}
 * selectors, and validation. Strategy is resolved against a baseline (the CLI flags) per object identity.
 */
class PolicyTest {

    private static final String OID = "00000000-0000-0000-0000-000000000001";

    /**
     * {@code ItemPath.fromString} resolves the process-wide {@code PrismContext.get()} singleton, which only
     * exists as a side effect of building a context. Without this the class passes solely because some
     * earlier test class happened to run first: {@code mvn test -Dtest=PolicyTest} on its own fails with an
     * NPE, and surefire's default filesystem run order is not stable across machines.
     */
    @BeforeAll
    static void bootstrapPrism() throws Exception {
        new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    private static StrategyOptions.Resolved baseline() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.MultiValueStrategy.replaceAll(), false);
    }

    private static Policy load(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("policy.txt");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return Policy.load(file.toFile());
    }

    @Test
    void objectSelectorSetsScopeForTheMatchingObjectOnly(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "RoleType/Manager scope=full");
        assertEquals(DiffEngine.Scope.FULL, policy.resolveFor("RoleType", "Manager", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, policy.resolveFor("RoleType", "Other", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, policy.resolveFor("UserType", "Manager", OID, baseline()).scope);
    }

    @Test
    void wildcardsMatchAtAnyLevel(@TempDir Path dir) throws Exception {
        Policy policy = load(dir,
                "RoleType/* multivalue=merge",       // object-default merge for every role
                "**/assignment multivalue=merge");   // an assignment container anywhere

        StrategyOptions.Resolved role = policy.resolveFor("RoleType", "Manager", OID, baseline());
        assertTrue(role.multiValue.isMerge(ItemPath.fromString("assignment")), "**/assignment matched");
        assertTrue(role.multiValue.isMerge(ItemPath.fromString("inducement")), "RoleType/* set the object default");

        StrategyOptions.Resolved user = policy.resolveFor("UserType", "jack", OID, baseline());
        assertTrue(user.multiValue.isMerge(ItemPath.fromString("assignment")), "**/assignment matched the user too");
        assertFalse(user.multiValue.isMerge(ItemPath.fromString("inducement")), "no object default for a non-role");
    }

    @Test
    void mostSpecificSelectorWinsPerKnob(@TempDir Path dir) throws Exception {
        Policy policy = load(dir,
                "**/objectCollectionView multivalue=merge",
                "SystemConfigurationType/SystemConfiguration/adminGuiConfiguration/objectCollectionViews/"
                        + "objectCollectionView multivalue=replace");
        StrategyOptions.Resolved r = policy.resolveFor("SystemConfigurationType", "SystemConfiguration", OID, baseline());

        ItemPath pinned = ItemPath.fromString("adminGuiConfiguration/objectCollectionViews/objectCollectionView");
        assertFalse(r.multiValue.isMerge(pinned), "the fully-qualified rule (more literals) beats **");
        assertTrue(r.multiValue.isMerge(ItemPath.fromString("somewhere/objectCollectionView")),
                "** still applies to a different container path");
    }

    @Test
    void differentKnobsCompose(@TempDir Path dir) throws Exception {
        Policy policy = load(dir,
                "SystemConfigurationType/SystemConfiguration scope=full",
                "**/objectCollectionView multivalue=merge");
        StrategyOptions.Resolved r = policy.resolveFor("SystemConfigurationType", "SystemConfiguration", OID, baseline());
        assertEquals(DiffEngine.Scope.FULL, r.scope, "object-level scope applies");
        assertTrue(r.multiValue.isMerge(
                ItemPath.fromString("adminGuiConfiguration/objectCollectionViews/objectCollectionView")),
                "item-level multivalue applies at the same time");
    }

    @Test
    void oidSelectorMatchesByOid(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "oid:" + OID + " scope=full");
        assertEquals(DiffEngine.Scope.FULL,
                policy.resolveFor("SystemConfigurationType", "SystemConfiguration", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT,
                policy.resolveFor("SystemConfigurationType", "SystemConfiguration", "other", baseline()).scope);
    }

    @Test
    void anOidSelectorPinsOneObjectAndBeatsAPattern(@TempDir Path dir) throws Exception {
        // The point of an oid: rule is to make one object an exception to a broader rule — which is exactly
        // the case that lost. Ranked purely on literal count, 'RoleType/Manager' (two literals) outranked
        // 'oid:X' (one), so the object the operator pinned to present was full-replaced anyway.
        Policy nameFirst = load(dir, "RoleType/Manager scope=full", "oid:" + OID + " scope=present");
        assertEquals(DiffEngine.Scope.PRESENT,
                nameFirst.resolveFor("RoleType", "Manager", OID, baseline()).scope);

        Policy oidFirst = load(dir, "oid:" + OID + " scope=present", "RoleType/Manager scope=full");
        assertEquals(DiffEngine.Scope.PRESENT, oidFirst.resolveFor("RoleType", "Manager", OID, baseline()).scope,
                "and the outcome does not depend on the order of the lines");

        assertEquals(DiffEngine.Scope.FULL,
                oidFirst.resolveFor("RoleType", "Manager", "another-oid", baseline()).scope,
                "a different object still gets the type-wide rule");
    }

    @Test
    void aColonInAnObjectNameIsPartOfTheName(@TempDir Path dir) throws Exception {
        // midPoint's own convention puts colons in names ("Reconciliation: OpenDJ"). Stripping 'prefix:' from
        // the name segment made the rule target an unrelated object called 'Helpdesk'.
        Policy policy = load(dir, "RoleType/IT:Helpdesk scope=full");

        assertEquals(DiffEngine.Scope.FULL, policy.resolveFor("RoleType", "IT:Helpdesk", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, policy.resolveFor("RoleType", "Helpdesk", OID, baseline()).scope,
                "and must not leak onto the role whose name is only the suffix");
    }

    @Test
    void aPolicyRuleOutranksAnEquallySpecificCliFlag(@TempDir Path dir) throws Exception {
        // README calls the CLI flags the baseline the policy is layered over. For item-level multivalue the
        // layering was inverted: ties break on list position, and the baseline was seeded first.
        StrategyOptions.Resolved cliReplace = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                EquivalenceStrategy.REAL_VALUE,
                DiffEngine.MultiValueStrategy.of(false, java.util.List.of(
                        new DiffEngine.MultiValueStrategy.PathRule(ItemPath.fromString("assignment"), false))),
                false);

        Policy policy = load(dir, "*/*/assignment multivalue=merge");

        assertTrue(policy.resolveFor("RoleType", "Manager", OID, cliReplace).multiValue
                        .isMerge(ItemPath.fromString("assignment")),
                "the policy rule must win the tie it was written to win");
    }

    @Test
    void objectNamesWithSpacesAreSupported(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "RoleType/IT Deploy Role scope=full");
        assertEquals(DiffEngine.Scope.FULL,
                policy.resolveFor("RoleType", "IT Deploy Role", OID, baseline()).scope);
    }

    @Test
    void aCommentOnlyPolicyLoadsAsANoOp(@TempDir Path dir) throws Exception {
        // 'init --write-policy' writes exactly this for a tree with nothing to tune, and then tells the user
        // to pass it to deploy — so rejecting it would break the command line midgie itself suggested.
        Policy policy = load(dir, "# nothing to tune here", "");

        StrategyOptions.Resolved resolved = policy.resolveFor("RoleType", "Manager", OID, baseline());
        assertEquals(DiffEngine.Scope.PRESENT, resolved.scope, "the CLI baseline is kept untouched");
    }

    @Test
    void objectOnlyKnobOnAnItemSelectorIsRejected(@TempDir Path dir) {
        IOException ex = assertThrows(IOException.class, () -> load(dir, "**/assignment scope=full"));
        assertTrue(ex.getMessage().contains("object-only knob"), ex.getMessage());
    }

    @Test
    void malformedLinesAreRejected(@TempDir Path dir) {
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager"));             // no settings
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager foo=bar"));     // unknown knob
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager scope=weird")); // bad value
    }
}
