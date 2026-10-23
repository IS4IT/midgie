/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code --version} dynamically from the Maven-filtered {@code build-info.properties}, so the
 * tool version tracks the git tag the build was cut from, and the bundled midPoint schema is shown with
 * the servers it serves (the target server must be one of them — see {@link VersionSupport}).
 */
final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String schema = VersionSupport.compileTimeVersion();
        String served = (schema != null)
                ? ", for midPoint " + VersionSupport.servedRange(schema, VersionSupport.oldestSupportedVersion())
                : "";
        return new String[] {
                "midgie " + orUnknown(VersionSupport.toolVersion()),
                "bundled midPoint schema " + orUnknown(schema) + served,
        };
    }

    private static String orUnknown(String value) {
        return value != null ? value : "(unknown)";
    }
}
