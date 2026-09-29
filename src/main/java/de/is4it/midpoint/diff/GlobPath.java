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
 * exactly one segment, {@code **} matches zero or more segments, at any position. Each is a whole segment —
 * there is no matching within a segment. Literal segments match by local name — a leading {@code prefix:} is
 * stripped so a namespace-qualified selector lines up with the diff's unqualified paths; an {@code oid:} head
 * is kept verbatim, as it is an identity marker rather than a namespace prefix.
 */
final class GlobPath {

    private static final String STAR = "*";
    private static final String GLOBSTAR = "**";

    private enum Kind { LITERAL, ONE_SEGMENT, ANY_DEPTH }

    /**
     * One parsed segment. Whether it is a wildcard is decided once, from the text as written, and kept here:
     * an escaped {@code \*} unescapes to the same text as the wildcard, so the text alone cannot tell them
     * apart afterwards.
     */
    private record Segment(Kind kind, String text) {
        static final Segment STAR_SEGMENT = new Segment(Kind.ONE_SEGMENT, STAR);
        static final Segment GLOBSTAR_SEGMENT = new Segment(Kind.ANY_DEPTH, GLOBSTAR);

        static Segment literal(String text) {
            return new Segment(Kind.LITERAL, text);
        }

        boolean isLiteral() {
            return kind == Kind.LITERAL;
        }

        boolean isGlobstar() {
            return kind == Kind.ANY_DEPTH;
        }

        /** Whether this segment, on its own, matches the input segment {@code name}. */
        boolean matchesOne(String name) {
            return kind == Kind.ONE_SEGMENT || (kind == Kind.LITERAL && text.equals(name));
        }
    }

    private final List<Segment> segments;

    private GlobPath(List<Segment> segments) {
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
     *
     * @throws IllegalArgumentException if a segment mixes a wildcard with other text (see
     *         {@link #rejectPartialWildcard})
     */
    static GlobPath parse(String text, int identitySegments) {
        List<Segment> segs = new ArrayList<>();
        int index = 0;
        for (String raw : splitOnUnescapedSlash(text)) {
            String seg = raw.trim();
            if (seg.isEmpty()) {
                continue;
            }
            rejectPartialWildcard(seg);
            if (STAR.equals(seg)) {
                segs.add(Segment.STAR_SEGMENT);
            } else if (GLOBSTAR.equals(seg)) {
                segs.add(Segment.GLOBSTAR_SEGMENT);
            } else {
                segs.add(Segment.literal(unescapeStars(index < identitySegments ? seg : normalize(seg))));
            }
            index++;
        }
        return new GlobPath(segs);
    }

    /**
     * A segment either <em>is</em> a wildcard or holds none of one.
     *
     * <p>Both ways of getting this wrong used to parse into a literal segment that no name can equal, so the
     * rule matched nothing and every object it was written for silently kept the CLI strategy — a
     * {@code multivalue=merge} written to protect accrued values quietly became replace. One is expecting
     * matching <em>within</em> a segment ({@code App-*} as "roles starting with App-"); the other is writing
     * two globs on one line, which the whitespace-tolerant selector grammar swallows as a single segment
     * holding a space. Neither is supported, so both are refused at load time instead. A name that genuinely
     * contains an asterisk is written {@code \*}.
     */
    private static void rejectPartialWildcard(String seg) {
        if (STAR.equals(seg) || GLOBSTAR.equals(seg) || !containsUnescapedStar(seg)) {
            return;
        }
        throw new IllegalArgumentException("segment '" + seg + "' mixes '*' with other text; a wildcard has to"
                + " be a whole segment ('*' one segment, '**' any depth), and one selector is one glob"
                + " — write '\\*' for a literal asterisk in a name");
    }

    private static boolean containsUnescapedStar(String seg) {
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\\' && i + 1 < seg.length()) {
                i++;    // whatever follows is a literal, including '*'
            } else if (c == '*') {
                return true;
            }
        }
        return false;
    }

    /**
     * Turn {@code \*} into the literal asterisk it stands for. Only ever applied to a segment already known
     * to be a literal, so a name written {@code \*} matches a name that is {@code *} and nothing else.
     */
    private static String unescapeStars(String seg) {
        return (seg.indexOf('\\') < 0) ? seg : seg.replace("\\*", "*");
    }

    /**
     * Split on {@code /}, honouring {@code \/} as a literal slash.
     *
     * <p>A selector is a {@code /}-separated path, so an object whose name contains a slash — midPoint
     * allows it — cannot be written plainly without reading as one more path segment. Escaping is the only
     * unambiguous way to say which is meant.
     */
    private static List<String> splitOnUnescapedSlash(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                current.append('/');
                i++;
            } else if (c == '/') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** An all-literal glob from an item path's local names — for exact {@code --path} rules. */
    static GlobPath ofItemPath(ItemPath path) {
        List<Segment> segs = new ArrayList<>();
        for (Object segment : path.removeIds().getSegments()) {
            if (ItemPath.isName(segment)) {
                segs.add(Segment.literal(ItemPath.toName(segment).getLocalPart()));
            }
        }
        return new GlobPath(segs);
    }

    private static String normalize(String seg) {
        if (seg.startsWith("oid:")) {
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
        return segments.stream().anyMatch(Segment::isGlobstar);
    }

    /** Match this glob against a list of segments ({@code *} = one segment, {@code **} = zero or more). */
    boolean matches(List<String> input) {
        int n = segments.size();
        int m = input.size();
        boolean[][] dp = new boolean[n + 1][m + 1];
        dp[0][0] = true;
        for (int i = 1; i <= n; i++) {
            if (segments.get(i - 1).isGlobstar()) {
                dp[i][0] = dp[i - 1][0];
            }
        }
        for (int i = 1; i <= n; i++) {
            Segment seg = segments.get(i - 1);
            for (int j = 1; j <= m; j++) {
                if (seg.isGlobstar()) {
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else if (seg.matchesOne(input.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[n][m];
    }

    /**
     * Whether this glob could still match something that <em>starts</em> with {@code prefix}.
     *
     * <p>Used to tell an item-level policy rule that is simply about another object from one that is about
     * this object but names a container it does not have: only the second is worth reporting as applied.
     */
    boolean matchesPrefix(List<String> prefix) {
        int i = 0;
        for (Segment segment : segments) {
            if (segment.isGlobstar()) {
                return true; // ** can absorb anything that follows, including the whole prefix
            }
            if (i == prefix.size()) {
                return true; // the glob is longer than the prefix and matched it so far
            }
            if (!segment.matchesOne(prefix.get(i))) {
                return false;
            }
            i++;
        }
        return i >= prefix.size();
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
        return !segments.isEmpty() && segments.get(0).isLiteral() && segments.get(0).text().startsWith("oid:");
    }

    private int literalCount() {
        return (int) segments.stream().filter(Segment::isLiteral).count();
    }

    private int globstarCount() {
        return (int) segments.stream().filter(Segment::isGlobstar).count();
    }

    private int literalPrefixLength() {
        int count = 0;
        for (Segment seg : segments) {
            if (!seg.isLiteral()) {
                break;
            }
            count++;
        }
        return count;
    }
}
