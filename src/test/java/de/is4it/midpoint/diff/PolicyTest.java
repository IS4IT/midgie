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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
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
    void objectNamesWithSpacesAreSupported(@TempDir Path dir) throws Exception {
        Policy policy = load(dir, "RoleType/IT Deploy Role scope=full");
        assertEquals(DiffEngine.Scope.FULL,
                policy.resolveFor("RoleType", "IT Deploy Role", OID, baseline()).scope);
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
