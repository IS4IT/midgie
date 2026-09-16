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

    /**
     * This tool's own version, embedded via build-info.properties: the git tag the build was cut from
     * ({@code 0.11.2}, or {@code 0.11.2-1-gabc1234-dirty} past one), or the pom version when git could
     * not say — which in the release job is the tag, stamped by {@code versions:set}.
     */
    static String toolVersion() {
        return buildProperty("midgie.version");
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

    /**
     * The running server's version, from the node it answers on ({@code GET /nodes/current}).
     * {@code null} when it cannot be determined.
     *
     * <p>{@code /nodes/current} rather than a listing of every node: it is one object instead of a
     * collection, it names the node actually serving the request rather than whichever entry of a cluster
     * happens to carry build info, and it is readable by an account that may not enumerate all nodes —
     * which is the shape of a least-privilege deployment account, and the case where this check used to go
     * silently missing.
     */
    static String liveVersion(PrismContext prismContext, MidpointRestClient client) {
        try {
            String xml = client.getObject("nodes", "current");
            NodeType node = prismContext.parserFor(xml).xml().parseRealValue(NodeType.class);
            BuildInformationType build = (node != null) ? node.getBuild() : null;
            return (build != null) ? blankToNull(build.getVersion()) : null;
        } catch (Exception e) {
            LOG.trace("could not read the live server version (GET /nodes/current)", e);
            return null;
        }
    }

    /** Warn on stderr if the live server's major line differs from the bundled schema version. */
    static void warnIfMismatch(PrismContext prismContext, MidpointRestClient client) {
        String compile = compileTimeVersion();
        String live = liveVersion(prismContext, client);
        LOG.debug("version check: bundled schema {} vs live server {}", compile, live);
        if (compile == null) {
            return; // no build-info on the classpath: nothing to compare against
        }
        if (live == null) {
            // Best-effort, but not mute. A least-privilege deploy account has no read on NodeType and gets
            // 403 here, so the guard against parsing a different major line with the bundled schema — the
            // hazard CLAUDE.md calls out — silently never ran.
            System.err.println("midgie: note: could not read the server version (GET /nodes/current); "
                    + "the schema-version check did not run.");
            return;
        }
        if (!sameMajorLine(compile, live)) {
            System.err.println("midgie: warning: server midPoint version " + live
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
