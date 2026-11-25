/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

/**
 * The elements of an XML text with where each one starts and ends in it — what an editor needs to change one
 * element and leave every other character of the file as it was. A DOM parser gives the structure but not the
 * offsets, and a serializer gives neither the file's layout nor its comments back.
 *
 * <p>Only for well-formed text that a real parser has already accepted: this reads elements, attributes,
 * namespace declarations, comments, CDATA sections, processing instructions and a doctype, and makes no attempt
 * to report errors. Anything it cannot read throws {@link IllegalArgumentException}.
 */
final class XmlTree {

    private XmlTree() {
    }

    /** One element of the text. Offsets are indexes into the text the tree was read from. */
    static final class Element {
        final String rawName;
        final QName name;
        /** Index of the {@code <} of the start tag. */
        final int start;
        /** Index just past the {@code >} of the start tag. */
        int startTagEnd;
        /** Index of the {@code <} of the end tag; {@link #startTagEnd} for an empty-element tag. */
        int contentEnd;
        /** Index just past the end of the element. */
        int end;
        boolean selfClosing;
        /** The namespace bindings in scope inside the element, its own declarations included; {@code ""} is the default. */
        final Map<String, String> scope;
        /** Attributes other than namespace declarations, raw name to raw (unescaped) value, in document order. */
        final Map<String, String> attributes;
        final List<Element> children = new ArrayList<>();
        final Element parent;

        private Element(String rawName, QName name, int start, Map<String, String> scope,
                Map<String, String> attributes, Element parent) {
            this.rawName = rawName;
            this.name = name;
            this.start = start;
            this.scope = scope;
            this.attributes = attributes;
            this.parent = parent;
        }

        /** The child elements called {@code name}, in document order. */
        List<Element> children(QName name) {
            List<Element> named = new ArrayList<>();
            for (Element child : children) {
                if (child.name.equals(name)) {
                    named.add(child);
                }
            }
            return named;
        }

        /** The namespace bindings in scope where this element stands, before its own declarations. */
        Map<String, String> outerScope() {
            return (parent != null) ? parent.scope : Map.of("xml", "http://www.w3.org/XML/1998/namespace");
        }
    }

    /** The root element of {@code text}. */
    static Element parse(String text) {
        Element root = null;
        List<Element> open = new ArrayList<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c != '<') {
                int next = text.indexOf('<', i);
                i = (next < 0) ? length : next;
                continue;
            }
            if (text.startsWith("<!--", i)) {
                i = after(text, "-->", i + 4);
            } else if (text.startsWith("<![CDATA[", i)) {
                i = after(text, "]]>", i + 9);
            } else if (text.startsWith("<?", i)) {
                i = after(text, "?>", i + 2);
            } else if (text.startsWith("<!", i)) {
                i = skipDeclaration(text, i);
            } else if (text.startsWith("</", i)) {
                if (open.isEmpty()) {
                    throw new IllegalArgumentException("end tag without a start tag at " + i);
                }
                Element element = open.remove(open.size() - 1);
                element.contentEnd = i;
                element.end = after(text, ">", i + 2);
                i = element.end;
            } else {
                Element parent = open.isEmpty() ? null : open.get(open.size() - 1);
                Element element = startTag(text, i, parent);
                if (parent != null) {
                    parent.children.add(element);
                } else if (root == null) {
                    root = element;
                }
                if (element.selfClosing) {
                    element.contentEnd = element.startTagEnd;
                    element.end = element.startTagEnd;
                } else {
                    open.add(element);
                }
                i = element.startTagEnd;
            }
        }
        if (root == null || !open.isEmpty()) {
            throw new IllegalArgumentException("not a complete XML document");
        }
        return root;
    }

    private static Element startTag(String text, int start, Element parent) {
        int i = start + 1;
        int nameEnd = i;
        while (nameEnd < text.length() && !Character.isWhitespace(text.charAt(nameEnd))
                && text.charAt(nameEnd) != '>' && text.charAt(nameEnd) != '/') {
            nameEnd++;
        }
        String rawName = text.substring(i, nameEnd);
        i = nameEnd;
        Map<String, String> declared = new LinkedHashMap<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        while (true) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= text.length()) {
                throw new IllegalArgumentException("unterminated start tag at " + start);
            }
            char c = text.charAt(i);
            if (c == '>' || c == '/') {
                break;
            }
            int eq = text.indexOf('=', i);
            String attribute = text.substring(i, eq).strip();
            int quoteAt = eq + 1;
            while (Character.isWhitespace(text.charAt(quoteAt))) {
                quoteAt++;
            }
            char quote = text.charAt(quoteAt);
            int close = text.indexOf(quote, quoteAt + 1);
            String value = decode(text.substring(quoteAt + 1, close));
            if (attribute.equals("xmlns")) {
                declared.put("", value);
            } else if (attribute.startsWith("xmlns:")) {
                declared.put(attribute.substring(6), value);
            } else {
                attributes.put(attribute, value);
            }
            i = close + 1;
        }
        boolean selfClosing = text.charAt(i) == '/';
        int tagEnd = after(text, ">", i);
        Map<String, String> scope = new HashMap<>((parent != null) ? parent.scope
                : Map.of("xml", "http://www.w3.org/XML/1998/namespace"));
        scope.putAll(declared);
        int colon = rawName.indexOf(':');
        String prefix = (colon >= 0) ? rawName.substring(0, colon) : "";
        String local = (colon >= 0) ? rawName.substring(colon + 1) : rawName;
        String namespace = scope.getOrDefault(prefix, "");
        Element element = new Element(rawName, new QName(namespace, local), start, scope, attributes, parent);
        element.startTagEnd = tagEnd;
        element.selfClosing = selfClosing;
        return element;
    }

    /** Skip a {@code <!DOCTYPE …>}, internal subset included. */
    private static int skipDeclaration(String text, int start) {
        int depth = 0;
        for (int i = start + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("unterminated declaration at " + start);
    }

    private static int after(String text, String terminator, int from) {
        int at = text.indexOf(terminator, from);
        if (at < 0) {
            throw new IllegalArgumentException("missing '" + terminator + "' after " + from);
        }
        return at + terminator.length();
    }

    /**
     * The character data of {@code element} as a parser reports it: CDATA sections as written, entity and
     * character references resolved, comments and processing instructions left out. {@code null} when the
     * element holds child elements.
     */
    static String textOf(String text, Element element) {
        if (!element.children.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = element.startTagEnd;
        int end = element.contentEnd;
        while (i < end) {
            if (text.startsWith("<![CDATA[", i)) {
                int close = text.indexOf("]]>", i + 9);
                out.append(text, i + 9, close);
                i = close + 3;
            } else if (text.startsWith("<!--", i)) {
                i = text.indexOf("-->", i + 4) + 3;
            } else if (text.startsWith("<?", i)) {
                i = text.indexOf("?>", i + 2) + 2;
            } else {
                int next = text.indexOf('<', i);
                int stop = (next < 0 || next > end) ? end : next;
                out.append(decode(text.substring(i, stop).replace("\r\n", "\n").replace('\r', '\n')));
                i = stop;
            }
        }
        return out.toString();
    }

    /** Whether the content of {@code element} is one CDATA section, give or take whitespace around it. */
    static boolean isCdata(String text, Element element) {
        String content = text.substring(element.startTagEnd, element.contentEnd).strip();
        return content.startsWith("<![CDATA[") && content.endsWith("]]>")
                && content.indexOf("]]>") == content.length() - 3;
    }

    /** Resolve the five predefined entities and character references. */
    static String decode(String raw) {
        if (raw.indexOf('&') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            int semicolon = (c == '&') ? raw.indexOf(';', i) : -1;
            if (semicolon < 0) {
                out.append(c);
                i++;
                continue;
            }
            String entity = raw.substring(i + 1, semicolon);
            switch (entity) {
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "amp" -> out.append('&');
                case "quot" -> out.append('"');
                case "apos" -> out.append('\'');
                default -> {
                    if (entity.startsWith("#x")) {
                        out.appendCodePoint(Integer.parseInt(entity.substring(2), 16));
                    } else if (entity.startsWith("#")) {
                        out.appendCodePoint(Integer.parseInt(entity.substring(1)));
                    } else {
                        throw new IllegalArgumentException("unknown entity &" + entity + ";");
                    }
                }
            }
            i = semicolon + 1;
        }
        return out.toString();
    }

    /** {@code value} as element content: markup characters escaped, a carriage return kept as a reference. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;");
    }
}
