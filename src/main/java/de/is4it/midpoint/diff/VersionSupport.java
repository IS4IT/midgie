/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.BuildInformationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.NodeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * Compares the bundled compile-time midPoint schema version against a live server's version. The schema
 * jar is version-coupled (see DESIGN.md / docker/README.md), so a mismatched major line can silently
 * misparse objects. Best-effort: if either version cannot be determined it stays quiet.
 */
final class VersionSupport {

    private static final Logger LOG = LoggerFactory.getLogger(VersionSupport.class);

    private VersionSupport() {
    }

    /** The {@code <midpoint.version>} the schema jar was built against, embedded via build-info.properties. */
    static String compileTimeVersion() {
        return buildProperty("midpoint.version");
    }

    /** This tool's own version (the pom {@code project.version}), embedded via build-info.properties. */
    static String projectVersion() {
        return buildProperty("project.version");
    }

    private static String buildProperty(String key) {
        try (InputStream in = VersionSupport.class.getResourceAsStream("/build-info.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return blankToNull(props.getProperty(key));
        } catch (IOException e) {
            return null;
        }
    }

    /** The running server's version, from its node build info ({@code GET /nodes}). Null if unavailable. */
    static String liveVersion(PrismContext prismContext, MidpointRestClient client) {
        try {
            String xml = client.listObjects("nodes");
            ObjectListType list = prismContext.parserFor(xml).xml().parseRealValue(ObjectListType.class);
            if (list == null) {
                return null;
            }
            for (ObjectType object : list.getObject()) {
                if (object instanceof NodeType) {
                    BuildInformationType build = ((NodeType) object).getBuild();
                    if (build != null && blankToNull(build.getVersion()) != null) {
                        return build.getVersion();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            LOG.debug("could not read the live server version (GET /nodes)", e);
            return null;
        }
    }

    /** Warn on stderr if the live server's major line differs from the bundled schema version. */
    static void warnIfMismatch(PrismContext prismContext, MidpointRestClient client) {
        String compile = compileTimeVersion();
        String live = liveVersion(prismContext, client);
        LOG.debug("version check: bundled schema {} vs live server {}", compile, live);
        if (compile == null || live == null) {
            return; // can't tell — stay quiet
        }
        if (!sameMajorLine(compile, live)) {
            System.err.println("midpoint-diff: warning: server midPoint version " + live
                    + " differs from the bundled schema version " + compile
                    + "; objects may be misparsed. Rebuild with a matching <midpoint.version>.");
        }
    }

    static boolean sameMajorLine(String a, String b) {
        return majorMinor(a).equals(majorMinor(b));
    }

    /** {@code "4.10.3"} / {@code "4.10.3-SNAPSHOT"} -> {@code "4.10"}. */
    static String majorMinor(String version) {
        String[] parts = version.split("[.-]");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
