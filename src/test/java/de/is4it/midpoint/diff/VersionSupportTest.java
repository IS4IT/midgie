/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for version comparison and the embedded compile-time version. */
class VersionSupportTest {

    @Test
    void majorMinorTakesTheFirstTwoComponents() {
        assertEquals("4.10", VersionSupport.majorMinor("4.10.3"));
        assertEquals("4.10", VersionSupport.majorMinor("4.10.3-SNAPSHOT"));
        assertEquals("4.11", VersionSupport.majorMinor("4.11"));
    }

    @Test
    void sameMajorLineIgnoresThePatchButNotTheMinor() {
        assertTrue(VersionSupport.sameMajorLine("4.10.3", "4.10.4"), "patch differences are compatible");
        assertFalse(VersionSupport.sameMajorLine("4.10.3", "4.11.0"), "a different minor is a different line");
    }

    @Test
    void comparePatchTellsWhichSideIsNewer() {
        // Patch releases change the schema too (4.10.5 made PasswordType/hint a protected string), so the
        // direction within a line matters: a server on a newer patch is the one that can send the unknown.
        assertTrue(VersionSupport.comparePatch("4.10.5", "4.10.6") < 0, "server on a newer patch");
        assertTrue(VersionSupport.comparePatch("4.10.5", "4.10.3") > 0, "server on an older patch");
        assertEquals(0, VersionSupport.comparePatch("4.10.5", "4.10.5-SNAPSHOT"));
        assertEquals(0, VersionSupport.comparePatch("4.10.5", "4.10"), "no patch to compare is not a mismatch");
        assertEquals(0, VersionSupport.comparePatch("4.10.5", "4.10.x"));
    }

    @Test
    void compileTimeVersionIsFilteredIntoTheJar() {
        String version = VersionSupport.compileTimeVersion();
        assertNotNull(version, "build-info.properties must be on the classpath");
        assertFalse(version.contains("$"), "the ${midpoint.version} token must be filtered, got: " + version);
        assertTrue(version.matches("\\d+\\.\\d+.*"), "should look like a version, got: " + version);
    }

    @Test
    void toolVersionIsFilteredIntoTheJar() {
        String version = VersionSupport.toolVersion();
        assertNotNull(version, "build-info.properties must be on the classpath");
        assertFalse(version.contains("$"), "the ${midgie.version} token must be filtered, got: " + version);
        // Whether it came from the git tag or the pom fallback, the 'v' prefix is stripped and a version
        // number leads: 0.11.2, 0.11.2-1-gabc1234-dirty, 0.1.0-SNAPSHOT.
        assertTrue(version.matches("\\d+\\.\\d+.*"), "should look like a version, got: " + version);
    }
}
