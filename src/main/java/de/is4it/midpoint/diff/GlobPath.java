/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.prism.path.ItemPath;

/**
 * A {@code /}-separated glob over path segments, used by the deploy strategy {@link Policy} and by per-path
 * multi-value rules ({@link DiffEngine.MultiValueStrategy.PathRule}). Two wildcards: {@code *} matches
 * exactly one segment, {@code **} matches zero or more segments, at any position. Literal segments match by
 * local name — a leading {@code prefix:} is stripped so a namespace-qualified selector lines up with the
 * diff's unqualified paths; an {@code oid:} head is kept verbatim, as it is an identity marker rather than a
 * namespace prefix.
 */
final class GlobPath {

    private static final String STAR = "*";
    private static final String GLOBSTAR = "**";

    private final List<String> segments;

    private GlobPath(List<String> segments) {
        this.segments = segments;
    }

    /** Parse a pure item-path glob, where every segment is an item name. */
    static GlobPath parse(String text) {
        return parse(text, 0);
    }

    /**
     * Parse a {@code /}-separated glob whose first {@code identitySegments} segments name an <em>object</em>
     * ({@code Type/Name}) rather than an item. Blank segments are dropped.
     *
     * <p>The distinction matters because {@code prefix:} stripping must not reach an object name. midPoint's
     * own naming convention puts colons in names ({@code Reconciliation: OpenDJ}), and stripping there made
     * a rule for {@code RoleType/IT:Helpdesk} silently target an unrelated role called {@code Helpdesk} —
     * or, with a space after the colon, match nothing at all.
     */
    static GlobPath parse(String text, int identitySegments) {
        List<String> segs = new ArrayList<>();
        int index = 0;
        for (String raw : text.split("/")) {
            String seg = raw.trim();
            if (seg.isEmpty()) {
                continue;
            }
            segs.add(index < identitySegments ? seg : normalize(seg));
            index++;
        }
        return new GlobPath(segs);
    }

    /** An all-literal glob from an item path's local names — for exact {@code --path} rules. */
    static GlobPath ofItemPath(ItemPath path) {
        List<String> segs = new ArrayList<>();
        for (Object segment : path.removeIds().getSegments()) {
            if (ItemPath.isName(segment)) {
                segs.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        return new GlobPath(segs);
    }

    private static String normalize(String seg) {
        if (STAR.equals(seg) || GLOBSTAR.equals(seg) || seg.startsWith("oid:")) {
            return seg;
        }
        int colon = seg.indexOf(':');
        return (colon >= 0) ? seg.substring(colon + 1) : seg;
    }

    boolean isEmpty() {
        return segments.isEmpty();
    }

    int size() {
        return segments.size();
    }

    boolean containsGlobstar() {
        return segments.contains(GLOBSTAR);
    }

    /** Match this glob against a list of segments ({@code *} = one segment, {@code **} = zero or more). */
    boolean matches(List<String> input) {
        int n = segments.size();
        int m = input.size();
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int i = 1; i <= n; i++) {
            if (GLOBSTAR.equals(segments.get(i - 1))) {
                dp[i][0] = dp[i - 1][0];
            }
        }
        for (int i = 1; i <= n; i++) {
            String seg = segments.get(i - 1);
            for (int j = 1; j <= m; j++) {
                if (GLOBSTAR.equals(seg)) {
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else if (STAR.equals(seg) || seg.equals(input.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[n][m];
    }

    /**
     * Positive when {@code this} is more specific than {@code other}: an {@code oid:} anchor first, then more
     * literal segments, then fewer {@code **}, then a longer literal prefix before the first wildcard. Zero
     * when equally specific.
     */
    int compareSpecificity(GlobPath other) {
        // An oid: head names exactly one object, so it is more specific than any pattern however many
        // literals that pattern has. Without this tier 'RoleType/Manager scope=full' outranked
        // 'oid:<X> scope=present' — the object the operator pinned was full-replaced anyway.
        int byOid = Boolean.compare(pinsOneObject(), other.pinsOneObject());
        if (byOid != 0) {
            return byOid;
        }
        int byLiteral = Integer.compare(literalCount(), other.literalCount());
        if (byLiteral != 0) {
            return byLiteral;
        }
        int byGlobstar = Integer.compare(other.globstarCount(), globstarCount());
        if (byGlobstar != 0) {
            return byGlobstar;
        }
        return Integer.compare(literalPrefixLength(), other.literalPrefixLength());
    }

    /** Whether this selector is anchored to a single object by OID. */
    private boolean pinsOneObject() {
        return !segments.isEmpty() && segments.get(0).startsWith("oid:");
    }

    private int literalCount() {
        int count = 0;
        for (String seg : segments) {
            if (!STAR.equals(seg) && !GLOBSTAR.equals(seg)) {
                count++;
            }
        }
        return count;
    }

    private int globstarCount() {
        int count = 0;
        for (String seg : segments) {
            if (GLOBSTAR.equals(seg)) {
                count++;
            }
        }
        return count;
    }

    private int literalPrefixLength() {
        int count = 0;
        for (String seg : segments) {
            if (STAR.equals(seg) || GLOBSTAR.equals(seg)) {
                break;
            }
            count++;
        }
        return count;
    }
}
