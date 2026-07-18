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
    void compileTimeVersionIsFilteredIntoTheJar() {
        String version = VersionSupport.compileTimeVersion();
        assertNotNull(version, "build-info.properties must be on the classpath");
        assertFalse(version.contains("$"), "the ${midpoint.version} token must be filtered, got: " + version);
        assertTrue(version.matches("\\d+\\.\\d+.*"), "should look like a version, got: " + version);
    }

    @Test
    void projectVersionIsFilteredIntoTheJar() {
        String version = VersionSupport.projectVersion();
        assertNotNull(version, "build-info.properties must be on the classpath");
        assertFalse(version.contains("$"), "the ${project.version} token must be filtered, got: " + version);
        assertTrue(version.matches("\\d+\\.\\d+.*"), "should look like a version, got: " + version);
    }
}
