/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code --version} dynamically from the Maven-filtered {@code build-info.properties}, so the
 * tool version tracks the pom and the bundled midPoint schema line is shown (it must match the target
 * server — see {@link VersionSupport}).
 */
final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {
                "midgie " + orUnknown(VersionSupport.projectVersion()),
                "bundled midPoint schema " + orUnknown(VersionSupport.compileTimeVersion()),
        };
    }

    private static String orUnknown(String value) {
        return value != null ? value : "(unknown)";
    }
}
