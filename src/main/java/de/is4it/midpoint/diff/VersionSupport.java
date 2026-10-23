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
 * jar is version-coupled (see DESIGN.md / docker/README.md): it serves its own line, and the line before it
 * from {@code <midpoint.oldestSupported>} on. Any other server — or one on a newer patch release of the
 * bundled line — can silently misparse objects. Best-effort: if either version cannot be determined it says
 * the check did not run, and otherwise stays quiet.
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
     * The oldest server this build serves on the line before the bundled one ({@code <midpoint.oldestSupported>}),
     * embedded via build-info.properties. {@code null} when it is not set, and the build serves its own line only.
     */
    static String oldestSupportedVersion() {
        return buildProperty("midpoint.oldestSupported");
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
            String xml = client.getCurrentNode();
            NodeType node = prismContext.parserFor(xml).xml().parseRealValue(NodeType.class);
            BuildInformationType build = (node != null) ? node.getBuild() : null;
            return (build != null) ? blankToNull(build.getVersion()) : null;
        } catch (Exception e) {
            LOG.trace("could not read the live server version (GET /nodes/current)", e);
            return null;
        }
    }

    /**
     * Warn on stderr if the live server is on a line the bundled schema does not serve, or on a newer patch
     * release of the bundled line.
     */
    static void warnIfMismatch(PrismContext prismContext, MidpointRestClient client) {
        String compile = compileTimeVersion();
        String oldest = oldestSupportedVersion();
        String live = liveVersion(prismContext, client);
        LOG.debug("version check: bundled schema {} vs live server {}", compile, live);
        if (compile == null) {
            return; // no build-info on the classpath: nothing to compare against
        }
        if (live == null) {
            // Best-effort, but not mute. A least-privilege deploy account has no read on NodeType and gets
            // 403 here, so the guard against parsing a different major line with the bundled schema — the
            // hazard CLAUDE.md calls out — silently never ran.
            System.err.println(NoteStyle.of("midgie: note: could not read the server version (GET /nodes/current); "
                    + "the schema-version check did not run."));
            return;
        }
        if (!sameMajorLine(compile, live)) {
            if (servesVersion(compile, oldest, live)) {
                // The line before the bundled one sends nothing the bundled schema lacks, bar the few
                // long-deprecated items the newer line removed, which fail to parse rather than misparse.
                LOG.debug("server {} is on the earlier line the bundled schema {} also serves ({})", live, compile,
                        servedRange(compile, oldest));
                return;
            }
            System.err.println("midgie: warning: server midPoint version " + live
                    + " differs from the bundled schema version " + compile + ", which serves midPoint "
                    + servedRange(compile, oldest)
                    + "; objects may be misparsed. Rebuild with a matching <midpoint.version>.");
            return;
        }
        // Within a line the direction decides. midPoint patch releases change the schema too — mostly by
        // adding, occasionally by changing a type (4.10.5 made PasswordType/hint a protected string) — so a
        // server on a newer patch may send what this build does not know. A server on an older patch only
        // lacks things the bundled schema knows, which is the safe way round and not worth a line per run.
        int patchOrder = comparePatch(compile, live);
        if (patchOrder < 0) {
            System.err.println("midgie: warning: server midPoint version " + live
                    + " is a newer patch release than the bundled schema version " + compile
                    + "; items it added or changed may be misparsed. Use a midgie built for " + live
                    + " or later.");
        } else if (patchOrder > 0) {
            LOG.debug("server {} is an older patch release than the bundled schema {}; the bundled schema "
                    + "is a superset", live, compile);
        }
    }

    /**
     * Whether a build bundling the schema of {@code bundled} serves a server on {@code live}: every patch of the
     * bundled line and, when {@code oldestSupported} is set, that version and every later patch of its line. A
     * patch on that line that cannot be read counts as served, as {@link #comparePatch} does not call it a
     * mismatch either.
     */
    static boolean servesVersion(String bundled, String oldestSupported, String live) {
        if (sameMajorLine(bundled, live)) {
            return true;
        }
        return oldestSupported != null && sameMajorLine(oldestSupported, live)
                && comparePatch(oldestSupported, live) <= 0;
    }

    /**
     * The servers a build serves, for the messages that name them: {@code "4.10.3 to 4.11.x"}, or {@code "4.11.x"}
     * when no oldest supported version is set.
     */
    static String servedRange(String bundled, String oldestSupported) {
        String line = majorMinor(bundled) + ".x";
        return (oldestSupported != null) ? oldestSupported + " to " + line : line;
    }

    /**
     * Compare the patch numbers of two versions on the same line: negative when {@code bundled} is older than
     * {@code live}, positive when newer, zero when equal or when either cannot be read as a number — an
     * unreadable version is not evidence of a mismatch.
     */
    static int comparePatch(String bundled, String live) {
        Integer a = patchOf(bundled);
        Integer b = patchOf(live);
        return (a == null || b == null) ? 0 : Integer.compare(a, b);
    }

    /**
     * {@code "4.10.5"} / {@code "4.10.5-SNAPSHOT"} -> {@code 5}. A version that names no patch is its line's first
     * release: {@code "4.11"}, {@code "4.11-RC2"}, {@code "4.11-M6"} and {@code "4.11-SNAPSHOT"} -> {@code 0}, the
     * qualifier after the {@code -} not being a patch. {@code null} when the patch is there but not a number
     * ({@code "4.10.x"}), or the version has no line at all.
     */
    static Integer patchOf(String version) {
        int qualifier = version.indexOf('-');
        String[] parts = ((qualifier >= 0) ? version.substring(0, qualifier) : version).split("\\.");
        if (parts.length < 2) {
            return null;
        }
        if (parts.length == 2) {
            return 0;
        }
        try {
            return Integer.valueOf(parts[2]);
        } catch (NumberFormatException e) {
            return null;
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
