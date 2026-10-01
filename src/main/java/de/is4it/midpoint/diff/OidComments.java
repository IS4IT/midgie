/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Names the objects an XML payload refers to by OID, in comments, for the person reading it.
 *
 * <p>A plan file, a rollback file or a {@code diff} payload addresses everything by OID: the object being
 * patched ({@code <apti:oid>}), and every reference in it ({@code <targetRef oid="…" type="c:RoleType"/>}).
 * Reviewing one meant looking each OID up by hand. {@link #annotate} puts a comment line above each such
 * element — {@code <!-- RoleType "Superuser" -->} — indented like the element, so the payload reads as what it
 * does. An OID the server does not know is said to be missing — {@code <!-- RoleType not found on the server
 * -->} — which makes a dangling reference visible before it is sent.
 *
 * <p>An object of the tree being deployed is named from the tree, and checked against the server like any
 * other: one that is not there yet is not a dangling reference but one this deploy creates, and says so —
 * {@code <!-- RoleType "Manager" (not on the server yet, deployed with this tree) -->}. The server is asked in
 * one search per object type, cached for the run. Without a server — an offline {@code diff} — only the tree's
 * objects are named. Nothing here ever fails the run: an OID that cannot be named simply gets no comment.
 *
 * <p>Comments are XML the REST interface parses and discards, so an annotated body is still a valid request.
 * {@link #strip} takes them out again anyway before a stored plan or rollback file is sent — a hand-edited
 * file may carry any comment, and what the server receives should be the change and nothing else. Stripping an
 * annotated payload gives back the original byte for byte.
 */
final class OidComments {

    private static final Logger LOG = LoggerFactory.getLogger(OidComments.class);

    /** Looks up the names of objects of one type by OID; the server-backed one is {@link #serverLookup}. */
    interface Lookup {
        /** Name per OID for the given type's objects; an OID absent from the result is not on the server. */
        Map<String, String> names(String typeName, Collection<String> oids) throws Exception;
    }

    // An element carrying an oid attribute. [^<>] spans newlines, so a start tag whose attributes XmlPretty
    // wrapped onto continuation lines is still one match, starting at its '<'.
    private static final Pattern OID_ATTRIBUTE =
            Pattern.compile("<[\\w.:-]+(?=[^<>]*\\soid=\"([0-9a-fA-F-]{36})\")[^<>]*>");
    private static final Pattern TYPE_ATTRIBUTE = Pattern.compile("\\stype=\"(?:[\\w.-]+:)?(\\w+)\"");
    // The object a modification addresses: <apti:oid>…</apti:oid>.
    private static final Pattern OID_ELEMENT = Pattern.compile("<(?:[\\w.-]+:)?oid>([0-9a-fA-F-]{36})</");
    private static final Pattern COMMENT_OR_CDATA = Pattern.compile("(?s)<!--.*?-->|<!\\[CDATA\\[.*?]]>");

    private final Lookup lookup;                                  // null: name only the tree's objects
    private final Map<String, String[]> tree = new HashMap<>();   // oid -> {type, name} of the tree's objects
    private final Map<String, String> labels = new HashMap<>();   // oid -> label, once settled
    private final Set<String> looked = new LinkedHashSet<>();     // type:oid pairs already asked about

    /** Name only what {@link #know} is told — for a run without a server. */
    OidComments() {
        this(null);
    }

    OidComments(Lookup lookup) {
        this.lookup = lookup;
    }

    /** The lookup this run's server answers, one search per object type. */
    static Lookup serverLookup(com.evolveum.midpoint.prism.PrismContext prismContext, MidpointRestClient client) {
        return (typeName, oids) -> {
            com.evolveum.midpoint.schema.constants.ObjectTypes type = com.evolveum.midpoint.schema.constants
                    .ObjectTypes.getObjectTypeFromTypeQName(new javax.xml.namespace.QName(
                            com.evolveum.midpoint.schema.constants.SchemaConstants.NS_C, typeName));
            return LiveLookup.namesByOid(prismContext, client, type.getClassDefinition(), type.getRestType(), oids);
        };
    }

    /** Record an object of the tree, so it is named from the tree rather than from the server. */
    void know(String oid, String typeName, String name) {
        if (oid != null && !oid.isBlank() && typeName != null && name != null) {
            tree.put(oid, new String[] {typeName, name});
            labels.remove(oid);
        }
    }

    /**
     * The tree has been applied: its objects are on the server now. A rollback plan is written after the
     * deploy, and "not on the server yet" would be stale there.
     */
    void treeDeployed() {
        for (Map.Entry<String, String[]> entry : tree.entrySet()) {
            labels.put(entry.getKey(), named(entry.getValue()[0], entry.getValue()[1]));
        }
    }

    /**
     * {@code xml} with a comment above each element that names an object by OID. {@code selfOid} and
     * {@code selfType} describe the object the payload is about, whose own {@code <apti:oid>} (or root
     * {@code oid} attribute) carries no type. Returns {@code xml} unchanged when there is nothing to name or
     * the annotated text would not parse.
     */
    String annotate(String xml, String selfOid, String selfType) {
        if (xml == null || xml.isBlank()) {
            return xml;
        }
        try {
            List<int[]> skipped = skippedRanges(xml);
            List<Mention> mentions = mentions(xml, skipped, selfOid, selfType);
            if (mentions.isEmpty()) {
                return xml;
            }
            resolve(mentions);
            String annotated = insert(xml, mentions);
            if (annotated.equals(xml)) {
                return xml;
            }
            Io.parseXml(annotated); // fail safe: a payload is never made unreadable by a comment
            return annotated;
        } catch (Exception | Error e) {
            LOG.trace("could not annotate a payload with object names; leaving it as it was", e);
            return xml;
        }
    }

    /**
     * {@code xml} without comments. A comment alone on its line takes the line with it, so what
     * {@link #annotate} inserted comes out exactly; CDATA sections are left untouched.
     */
    static String strip(String xml) {
        if (xml == null || !xml.contains("<!--")) {
            return xml;
        }
        StringBuilder out = new StringBuilder(xml.length());
        Matcher matcher = COMMENT_OR_CDATA.matcher(xml);
        int copied = 0;
        while (matcher.find()) {
            if (matcher.group().startsWith("<![CDATA[")) {
                continue;
            }
            int start = matcher.start();
            int end = matcher.end();
            int lineStart = lineStart(xml, start);
            int lineEnd = lineEnd(xml, end);
            boolean aloneOnLine = xml.substring(lineStart, start).isBlank()
                    && xml.substring(end, lineEnd).isBlank() && lineEnd < xml.length();
            if (aloneOnLine && lineStart >= copied) {
                out.append(xml, copied, lineStart);
                copied = lineEnd + (xml.startsWith("\r\n", lineEnd) ? 2 : 1);
            } else {
                out.append(xml, copied, start);
                copied = end;
            }
        }
        out.append(xml, copied, xml.length());
        return out.toString();
    }

    // --- annotation ---

    /** One OID found in the payload: where its element starts, and what type it names, when that is known. */
    private static final class Mention {
        final int elementStart;
        final String oid;
        final String typeName;
        String label;

        Mention(int elementStart, String oid, String typeName) {
            this.elementStart = elementStart;
            this.oid = oid;
            this.typeName = typeName;
        }
    }

    private static List<Mention> mentions(String xml, List<int[]> skipped, String selfOid, String selfType) {
        List<Mention> mentions = new ArrayList<>();
        Matcher element = OID_ATTRIBUTE.matcher(xml);
        while (element.find()) {
            if (inside(skipped, element.start())) {
                continue;
            }
            String oid = element.group(1);
            Matcher type = TYPE_ATTRIBUTE.matcher(element.group());
            String typeName = type.find() ? type.group(1) : (oid.equals(selfOid) ? selfType : null);
            mentions.add(new Mention(element.start(), oid, typeName));
        }
        Matcher own = OID_ELEMENT.matcher(xml);
        while (own.find()) {
            if (!inside(skipped, own.start())) {
                String oid = own.group(1);
                mentions.add(new Mention(own.start(), oid, oid.equals(selfOid) ? selfType : null));
            }
        }
        mentions.sort((a, b) -> Integer.compare(a.elementStart, b.elementStart));
        return mentions;
    }

    /** Label every mention, asking the server once per type about the OIDs not settled yet. */
    private void resolve(List<Mention> mentions) {
        Map<String, Set<String>> unknownByType = new LinkedHashMap<>();
        for (Mention mention : mentions) {
            String[] own = tree.get(mention.oid);
            String typeName = (own != null) ? own[0] : mention.typeName;
            if (!labels.containsKey(mention.oid) && typeName != null
                    && !looked.contains(typeName + ":" + mention.oid)) {
                unknownByType.computeIfAbsent(typeName, type -> new LinkedHashSet<>()).add(mention.oid);
            }
        }
        if (lookup != null) {
            for (Map.Entry<String, Set<String>> entry : unknownByType.entrySet()) {
                String typeName = entry.getKey();
                try {
                    Map<String, String> names = lookup.names(typeName, entry.getValue());
                    for (String oid : entry.getValue()) {
                        labels.put(oid, labelOf(oid, typeName, names.get(oid)));
                    }
                } catch (Exception e) {
                    // An abstract type, no read permission, a hiccup: the payload is still right, only unnamed.
                    LOG.trace("could not look up {} object name(s) of type {}", entry.getValue().size(), typeName, e);
                }
                for (String oid : entry.getValue()) {
                    looked.add(typeName + ":" + oid);
                }
            }
        }
        for (Mention mention : mentions) {
            String label = labels.get(mention.oid);
            String[] own = tree.get(mention.oid);
            // Not asked (no server, or the lookup failed): a tree object is still named from the tree.
            mention.label = (label == null && own != null) ? named(own[0], own[1]) : label;
        }
    }

    /**
     * What to call an OID, given what the server said. A tree object keeps its name from the tree either way;
     * absent from the server it is one this deploy creates rather than a dangling reference.
     */
    private String labelOf(String oid, String typeName, String serverName) {
        String[] own = tree.get(oid);
        if (own != null) {
            return named(own[0], own[1]) + (serverName == null ? " (not on the server yet, deployed with this tree)" : "");
        }
        return (serverName != null) ? named(typeName, serverName) : typeName + " not found on the server";
    }

    private static String named(String typeName, String name) {
        return typeName + " \"" + name + "\"";
    }

    /** Insert one comment line above each line that starts a mentioned element, indented like it. */
    private static String insert(String xml, List<Mention> mentions) {
        Map<Integer, List<String>> byLine = new TreeMap<>();
        int prologEnd = prologEnd(xml);
        for (Mention mention : mentions) {
            if (mention.label == null) {
                continue;
            }
            int lineStart = Math.max(lineStart(xml, mention.elementStart), prologEnd);
            List<String> labels = byLine.computeIfAbsent(lineStart, key -> new ArrayList<>());
            if (!labels.contains(mention.label)) {
                labels.add(mention.label);
            }
        }
        StringBuilder out = new StringBuilder(xml.length() + byLine.size() * 40);
        int copied = 0;
        String newline = xml.contains("\r\n") ? "\r\n" : "\n";
        for (Map.Entry<Integer, List<String>> line : byLine.entrySet()) {
            int start = line.getKey();
            out.append(xml, copied, start);
            String indent = leadingWhitespace(xml, start);
            out.append(indent).append("<!-- ").append(safe(String.join("; ", line.getValue()))).append(" -->")
                    .append(newline);
            copied = start;
        }
        out.append(xml, copied, xml.length());
        return out.toString();
    }

    /** Comment text that cannot end the comment early: no "--", and no trailing '-'. */
    private static String safe(String text) {
        String cleaned = text.replace("--", "- -");
        return cleaned.endsWith("-") ? cleaned + " " : cleaned;
    }

    // --- text helpers ---

    /** Comments and CDATA sections: nothing inside either is markup, so no OID there is an element. */
    private static List<int[]> skippedRanges(String xml) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = COMMENT_OR_CDATA.matcher(xml);
        while (matcher.find()) {
            ranges.add(new int[] {matcher.start(), matcher.end()});
        }
        return ranges;
    }

    private static boolean inside(List<int[]> ranges, int index) {
        for (int[] range : ranges) {
            if (index >= range[0] && index < range[1]) {
                return true;
            }
        }
        return false;
    }

    /** Where the XML declaration ends, so nothing is ever inserted before it. */
    private static int prologEnd(String xml) {
        String trimmed = xml.stripLeading();
        if (!trimmed.startsWith("<?xml")) {
            return 0;
        }
        int end = xml.indexOf("?>");
        if (end < 0) {
            return 0;
        }
        int after = end + 2;
        return (after < xml.length() && xml.charAt(after) == '\n') ? after + 1
                : (xml.startsWith("\r\n", after) ? after + 2 : after);
    }

    private static int lineStart(String xml, int index) {
        int newline = xml.lastIndexOf('\n', index - 1);
        return newline + 1;
    }

    private static int lineEnd(String xml, int index) {
        int newline = xml.indexOf('\n', index);
        if (newline < 0) {
            return xml.length();
        }
        return (newline > 0 && xml.charAt(newline - 1) == '\r') ? newline - 1 : newline;
    }

    private static String leadingWhitespace(String xml, int lineStart) {
        int end = lineStart;
        while (end < xml.length() && (xml.charAt(end) == ' ' || xml.charAt(end) == '\t')) {
            end++;
        }
        return xml.substring(lineStart, end);
    }
}
