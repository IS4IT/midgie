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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for version comparison and the embedded compile-time version. */
class VersionSupportTest {

    @Test
    void majorMinorTakesTheFirstTwoComponents() {
        assertEquals("4.10", VersionSupport.majorMinor("4.10.3"));
        assertEquals("4.10", VersionSupport.majorMinor("4.10.3-SNAPSHOT"));
        assertEquals("4.11", VersionSupport.majorMinor("4.11"));
        assertEquals("4.11", VersionSupport.majorMinor("4.11-RC2"));
        assertEquals("4.11", VersionSupport.majorMinor("4.11.0-SNAPSHOT"));
    }

    @Test
    void patchOfReadsEveryFormMidpointVersionsComeIn() {
        assertEquals(5, VersionSupport.patchOf("4.10.5"));
        assertEquals(5, VersionSupport.patchOf("4.10.5-SNAPSHOT"));
        assertEquals(0, VersionSupport.patchOf("4.11.0-SNAPSHOT"));
        // A release that names no patch is the line's first: the GA (4.10 shipped as "4.10"), its milestones,
        // release candidates and snapshots.
        assertEquals(0, VersionSupport.patchOf("4.11"));
        assertEquals(0, VersionSupport.patchOf("4.11-RC2"));
        assertEquals(0, VersionSupport.patchOf("4.11-M6"));
        assertEquals(0, VersionSupport.patchOf("4.11-SNAPSHOT"));
        assertNull(VersionSupport.patchOf("4.10.x"), "a patch that is not a number is unreadable");
        assertNull(VersionSupport.patchOf("4"), "no line at all");
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
        assertTrue(VersionSupport.comparePatch("4.10.5", "4.10") > 0, "the line's first release is older");
        assertTrue(VersionSupport.comparePatch("4.11-RC2", "4.11.1") < 0,
                "a schema bundled from a release candidate is older than the line's first patch");
        assertEquals(0, VersionSupport.comparePatch("4.10.5", "4.10.x"), "an unreadable patch is not a mismatch");
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
