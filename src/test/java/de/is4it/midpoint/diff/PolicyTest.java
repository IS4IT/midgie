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
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.of(EquivalenceStrategy.REAL_VALUE),
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
    void anEquivalenceRuleActuallyChangesTheResolvedStrategy(@TempDir Path dir) throws Exception {
        // Only scope and multivalue layering were covered; equivalence was validated on load and then never
        // asserted to reach the engine, so a policy file could name a strategy that was quietly ignored.
        Policy policy = load(dir,
                "RoleType/Manager equivalence=literal",
                "RoleType/Auto equivalence=auto");

        assertEquals(EquivalenceStrategy.LITERAL,
                policy.resolveFor("RoleType", "Manager", OID, baseline()).equivalence.fixedStrategy());
        assertTrue(policy.resolveFor("RoleType", "Auto", OID, baseline()).equivalence.isAuto(),
                "'equivalence=auto' must resolve to the auto plan, not a fixed strategy");
        assertEquals(EquivalenceStrategy.REAL_VALUE,
                policy.resolveFor("RoleType", "Other", OID, baseline()).equivalence.fixedStrategy(),
                "an unmatched object keeps the CLI baseline");
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
                DiffEngine.EquivalencePlan.of(EquivalenceStrategy.REAL_VALUE),
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
    void anObjectNameMayContainEqualsAndSlashWhenQuoted(@TempDir Path dir) throws Exception {
        // midPoint names really do contain both: an org named 'ou=people', a role named 'App / Admin'.
        // Unquoted, '=' started the settings and '/' read as another path segment, so the error message
        // described the shape of the line rather than the name — and init --write-policy, which emits names
        // verbatim, could generate a file Policy.load then refused.
        Policy equals = load(dir, "\"OrgType/ou=people\" scope=full");
        assertEquals(DiffEngine.Scope.FULL, equals.resolveFor("OrgType", "ou=people", OID, baseline()).scope);

        // A slash inside a name is escaped, since the selector itself is a /-separated path.
        Policy slash = load(dir, "\"RoleType/App \\/ Admin\" scope=full");
        assertEquals(DiffEngine.Scope.FULL, slash.resolveFor("RoleType", "App / Admin", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, slash.resolveFor("RoleType", "App", OID, baseline()).scope);
    }

    @Test
    void aSettingIsOnlyASettingWhenItsKeyIsAKnownKnob(@TempDir Path dir) throws Exception {
        // Unquoted names containing '=' still work as long as the part before it is not a knob.
        Policy policy = load(dir, "OrgType/ou=people scope=full");
        assertEquals(DiffEngine.Scope.FULL, policy.resolveFor("OrgType", "ou=people", OID, baseline()).scope);
    }

    @Test
    void theDebugLogNamesTheItemRulesOfferedAndTheIdentityTheyMatched(@TempDir Path dir) throws Exception {
        // "matched no object" answers which rules reached nothing; this answers the opposite, per object, with
        // the type and name the object was offered under — what such a rule has to be compared with.
        Policy policy = load(dir, "# tasks", "TaskType/*/executionState  apply=on-create");
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Policy.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Level original = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            policy.resolveFor("TaskType", "AD - Abgleich (Reconciliation)", OID, baseline());
            policy.resolveFor("RoleType", "Manager", null, baseline());
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
            appender.stop();
        }
        java.util.List<String> lines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();

        assertEquals(java.util.List.of("policy: TaskType/AD - Abgleich (Reconciliation) [" + OID + "] ← "
                + "TaskType/*/executionState  apply=on-create (" + dir.resolve("policy.txt") + ":2)"), lines,
                "one line for the task, none for the role no rule reached");
    }

    @Test
    void aRuleThatMatchesNothingIsReported(@TempDir Path dir) throws Exception {
        // A typo'd type or a renamed object resolves silently to the CLI baseline, so a merge rule written
        // to protect accrued values quietly becomes replace and the deploy deletes them.
        Policy policy = load(dir, "RoleType/Manager scope=full", "Role/Typo scope=full",
                "**/assignment multivalue=merge");
        policy.resolveFor("RoleType", "Manager", OID, baseline());

        assertTrue(policy.unmatchedRules().stream().anyMatch(r -> r.contains("Role/Typo")),
                "the typo must be named: " + policy.unmatchedRules());
        assertFalse(policy.unmatchedRules().stream().anyMatch(r -> r.contains("RoleType/Manager")),
                "the rule that applied must not be: " + policy.unmatchedRules());
        assertFalse(policy.unmatchedRules().stream().anyMatch(r -> r.contains("assignment")),
                "nor an item rule offered to a matching object: " + policy.unmatchedRules());
    }

    @Test
    void smartRefsResolvesPerObject(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "ResourceType/Prod smart-refs=resolve");

        assertEquals(StrategyOptions.SmartRefs.RESOLVE,
                policy.resolveFor("ResourceType", "Prod", OID, baseline()).smartRefs);
        assertEquals(StrategyOptions.SmartRefs.ACCEPT,
                policy.resolveFor("ResourceType", "Other", OID, baseline()).smartRefs,
                "another object keeps the CLI baseline");
    }

    @Test
    void objectOnlyKnobOnAnItemSelectorIsRejected(@TempDir Path dir) {
        IOException ex = assertThrows(IOException.class, () -> load(dir, "**/assignment scope=full"));
        assertTrue(ex.getMessage().contains("object-only knob"), ex.getMessage());
    }

    @Test
    void aSegmentMixingAWildcardWithOtherTextIsRejected(@TempDir Path dir) throws Exception {
        // Both of these parsed into a literal segment no name can equal, so the rule matched nothing and the
        // objects it was written for silently kept the CLI strategy — the failure mode a merge rule is
        // written to prevent. A load-time error with a line number beats an end-of-run "matched nothing" note.
        IOException withinSegment = assertThrows(IOException.class,
                () -> load(dir, "RoleType/App-* multivalue=merge"));      // not a prefix match
        assertTrue(withinSegment.getMessage().contains("whole segment"), withinSegment.getMessage());
        assertTrue(withinSegment.getMessage().contains("line 1"), withinSegment.getMessage());

        // Two globs on one line: the selector is everything before the first knob, so this reads as one
        // segment holding a space rather than as an object selector plus an item selector.
        IOException twoGlobs = assertThrows(IOException.class,
                () -> load(dir, "RoleType/* **/assignment multivalue=merge"));
        assertTrue(twoGlobs.getMessage().contains("one selector is one glob"), twoGlobs.getMessage());

        // The single-glob form of that intent stays valid.
        assertTrue(load(dir, "RoleType/*/assignment multivalue=merge")
                        .resolveFor("RoleType", "Manager", OID, baseline())
                        .multiValue.isMerge(ItemPath.fromString("assignment")),
                "Type/Name/item in one glob is how an item rule is scoped to a type");
    }

    @Test
    void anAsteriskInAnObjectNameIsEscaped(@TempDir Path dir) throws Exception {
        // Rejecting a partial wildcard would otherwise take away the only way to name such an object, and
        // 'init --write-policy' emits names verbatim, so it needs a form that reads back.
        Policy policy = load(dir, "RoleType/App \\* Admin scope=full");

        assertEquals(DiffEngine.Scope.FULL, policy.resolveFor("RoleType", "App * Admin", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, policy.resolveFor("RoleType", "App X Admin", OID, baseline()).scope,
                "and it stays a literal rather than becoming a wildcard");
    }

    @Test
    void aWholeSegmentEscapedAsteriskIsStillALiteral(@TempDir Path dir) throws Exception {
        // '\*' used to unescape to the same text as the wildcard and then match like one, so a rule written
        // for a role literally named '*' full-replaced every role.
        Policy policy = load(dir, "RoleType/\\* scope=full");

        assertEquals(DiffEngine.Scope.FULL, policy.resolveFor("RoleType", "*", OID, baseline()).scope);
        assertEquals(DiffEngine.Scope.PRESENT, policy.resolveFor("RoleType", "Manager", OID, baseline()).scope,
                "a role named anything else must keep the CLI strategy");
    }

    @Test
    void aMissingPolicyFileSaysWhatIsMissing(@TempDir Path dir) {
        // NoSuchFileException's message is only the path, so the run used to fail with a bare
        // 'midgie: midgie.policy' — indistinguishable from a complaint about the file's contents.
        IOException ex = assertThrows(IOException.class,
                () -> Policy.load(dir.resolve("absent.policy").toFile()));

        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
        assertTrue(ex.getMessage().contains("--policy"), ex.getMessage());
    }

    @Test
    void malformedLinesAreRejected(@TempDir Path dir) {
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager"));             // no settings
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager foo=bar"));     // unknown knob
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager scope=weird")); // bad value
        assertThrows(IOException.class, () -> load(dir, "RoleType/Manager smart-refs=maybe"));
    }
}
