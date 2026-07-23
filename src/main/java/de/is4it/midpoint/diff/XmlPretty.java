/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Tidies serialized XML for humans: drops the namespace declarations prism piles onto the root element
 * that nothing actually references, and puts each remaining attribute on its own line when the opening
 * tag is still long. Child element formatting (already indented by the serializer) is left untouched.
 *
 * <p>A declaration is kept only if its prefix appears as {@code prefix:} somewhere else in the document —
 * element/attribute names <em>and</em> QName content such as an itemDelta path ({@code c:description}) or
 * an {@code xsi:type} value — so a genuinely used prefix is never removed. Only namespace declarations and
 * insignificant whitespace change, so the result is equivalent XML and is safe as a PATCH/POST body.
 * Fail-safe: any parsing surprise returns the input unchanged, so a payload is never corrupted.
 */
final class XmlPretty {

    private static final int WRAP_THRESHOLD = 100;        // only wrap a genuinely long opening tag
    private static final String CONTINUATION = "        "; // 8 spaces for the wrapped attributes

    private XmlPretty() {
    }

    static String tidyRoot(String xml) {
        try {
            if (xml == null) {
                return null;
            }
            int start = rootTagStart(xml);
            if (start < 0) {
                return xml;
            }
            int end = openingTagEnd(xml, start);
            if (end < 0) {
                return xml;
            }
            boolean selfClosing = xml.charAt(end - 1) == '/';
            String inner = xml.substring(start + 1, selfClosing ? end - 1 : end).trim();
            int firstWs = firstWhitespace(inner);
            if (firstWs < 0) {
                return xml; // no attributes
            }
            String name = inner.substring(0, firstWs);
            List<String> attrs = splitAttributes(inner.substring(firstWs).trim());
            if (attrs.isEmpty()) {
                return xml;
            }

            List<String> kept = keepReferenced(xml, attrs);
            boolean strippedSome = kept.size() < attrs.size();

            StringBuilder inline = new StringBuilder("<").append(name);
            for (String attr : kept) {
                inline.append(' ').append(attr);
            }
            inline.append(selfClosing ? "/>" : ">");
            boolean wrap = inline.length() > WRAP_THRESHOLD && !kept.isEmpty();
            if (!strippedSome && !wrap) {
                return xml; // nothing worth changing
            }

            String rebuilt;
            if (wrap) {
                String indent = leadingIndent(xml, start) + CONTINUATION;
                StringBuilder tag = new StringBuilder("<").append(name);
                for (String attr : kept) {
                    tag.append('\n').append(indent).append(attr);
                }
                tag.append(selfClosing ? "/>" : ">");
                rebuilt = tag.toString();
            } else {
                rebuilt = inline.toString();
            }
            return xml.substring(0, start) + rebuilt + xml.substring(end + 1);
        } catch (RuntimeException e) {
            return xml; // never corrupt the payload
        }
    }

    /** Keep every non-namespace attribute, the default {@code xmlns}, and any {@code xmlns:p} whose prefix
     *  {@code p} is referenced elsewhere. The search runs on the document with the namespace declarations
     *  themselves removed, so their URIs can't create a false match. */
    private static List<String> keepReferenced(String xml, List<String> attrs) {
        String scan = xml;
        for (String attr : attrs) {
            if (attr.startsWith("xmlns")) {
                scan = scan.replace(attr, " ");
            }
        }
        List<String> kept = new ArrayList<>();
        for (String attr : attrs) {
            if (!attr.startsWith("xmlns:")) {
                kept.add(attr); // a normal attribute, or the default xmlns="…"
                continue;
            }
            int equals = attr.indexOf('=');
            String prefix = attr.substring("xmlns:".length(), equals).trim();
            if (scan.contains(prefix + ":")) {
                kept.add(attr);
            }
        }
        return kept;
    }

    /** Index of the root element's {@code <}, skipping any XML declaration, comments, or doctype. */
    private static int rootTagStart(String xml) {
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                return -1;
            }
            char next = (lt + 1 < xml.length()) ? xml.charAt(lt + 1) : '\0';
            if (next == '?') {
                i = xml.indexOf("?>", lt);
                if (i < 0) {
                    return -1;
                }
                i += 2;
            } else if (next == '!') {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    return -1;
                }
                i = gt + 1;
            } else if (Character.isLetter(next) || next == '_') {
                return lt;
            } else {
                i = lt + 1;
            }
        }
        return -1;
    }

    /** Index of the {@code >} that closes the opening tag starting at {@code start} (quote-aware). */
    private static int openingTagEnd(String xml, int start) {
        char quote = 0;
        for (int i = start + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitAttributes(String region) {
        List<String> attrs = new ArrayList<>();
        int i = 0;
        int n = region.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(region.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int nameStart = i;
            while (i < n && region.charAt(i) != '=' && !Character.isWhitespace(region.charAt(i))) {
                i++;
            }
            while (i < n && Character.isWhitespace(region.charAt(i))) {
                i++;
            }
            if (i >= n || region.charAt(i) != '=') { // a value-less attribute — keep the bare token
                attrs.add(region.substring(nameStart, i).trim());
                continue;
            }
            i++; // '='
            while (i < n && Character.isWhitespace(region.charAt(i))) {
                i++;
            }
            if (i < n && (region.charAt(i) == '"' || region.charAt(i) == '\'')) {
                char q = region.charAt(i++);
                while (i < n && region.charAt(i) != q) {
                    i++;
                }
                if (i < n) {
                    i++; // closing quote
                }
            }
            attrs.add(region.substring(nameStart, i).trim());
        }
        return attrs;
    }

    private static String leadingIndent(String xml, int start) {
        int lineStart = xml.lastIndexOf('\n', start - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < start; i++) {
            char c = xml.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }
}
