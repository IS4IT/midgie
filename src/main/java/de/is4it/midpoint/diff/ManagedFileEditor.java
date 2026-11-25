/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.SerializationOptions;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;

/**
 * Makes {@link ManagedProjection}'s edits in the text of a managed file, changing the elements they name and not
 * one character elsewhere: the file's comments, layout, namespace prefixes, CDATA sections, line endings and
 * byte-order mark stay as they were.
 *
 * <p>The n-th element of an item in the file is the n-th value prism read for it, which is checked rather than
 * assumed: a count that differs refuses the edit. A deleted element takes its line with it when it stood alone on
 * it. A replaced one is compared with the server's value as prism writes it, element by element, and only what
 * differs is rewritten: a changed script keeps its {@code <expression>} and {@code <code>} elements and their
 * CDATA, and only the code inside changes. Where the structure differs, the element is written whole, indented to
 * where the old one stood.
 *
 * <p><b>Verify, or refuse.</b> The edited text must read back as the file with the edits made
 * ({@link ManagedProjection#applied}); otherwise nothing is written and the file is named. A file in an encoding
 * other than UTF-8 is refused outright.
 */
final class ManagedFileEditor {

    /** Why a file could not be edited; the file is left as it was. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /** The edited text, and what the edit could not keep (a comment inside an element written anew). */
    record Result(String text, List<String> notes) {
    }

    private static final Pattern ENCODING = Pattern.compile("^\\s*<\\?xml[^>]*encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    private final PrismContext prismContext;

    ManagedFileEditor(PrismContext prismContext) {
        this.prismContext = prismContext;
    }

    /** A text replacing {@code [start, end)}. */
    private record Splice(int start, int end, String text) {
    }

    /**
     * {@code text}, the content of a managed file that reads as {@code file}, with {@code edits} made.
     *
     * @throws Refused when an edit cannot be placed, or the result does not read back as intended
     */
    Result edit(String text, PrismObject<? extends Objectable> file, List<ManagedProjection.Edit> edits)
            throws Refused {
        boolean bom = text.startsWith("\uFEFF");
        Matcher encoding = ENCODING.matcher(bom ? text.substring(1) : text);
        if (encoding.find() && !encoding.group(1).equalsIgnoreCase("UTF-8")
                && !encoding.group(1).equalsIgnoreCase("UTF8")) {
            throw new Refused("it is encoded in " + encoding.group(1) + "; pull edits UTF-8 files only");
        }
        String lineEnd = text.contains("\r\n") ? "\r\n" : "\n";
        XmlTree.Element root;
        try {
            root = XmlTree.parse(text);
        } catch (RuntimeException e) {
            throw new Refused("its XML could not be indexed (" + e.getMessage() + ")");
        }
        List<String> notes = new ArrayList<>();
        List<Splice> splices = new ArrayList<>();
        for (ManagedProjection.Edit edit : edits) {
            XmlTree.Element element = locate(root, file, edit);
            if (edit.kind() == ManagedProjection.Kind.DELETE) {
                splices.add(deletion(text, element));
            } else {
                splices.addAll(replacement(text, element, edit, lineEnd, notes));
            }
        }
        splices.sort(Comparator.comparingInt(Splice::start));
        for (int i = 1; i < splices.size(); i++) {
            if (splices.get(i).start() < splices.get(i - 1).end()) {
                throw new Refused("two of its edits overlap");
            }
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = splices.size() - 1; i >= 0; i--) {
            Splice splice = splices.get(i);
            out.replace(splice.start(), splice.end(), splice.text());
        }
        String edited = out.toString();
        verify(edited, file, edits);
        return new Result(edited, notes);
    }

    /** The element of {@code edit}'s value, walking the file's elements and prism's values side by side. */
    private static XmlTree.Element locate(XmlTree.Element root, PrismObject<? extends Objectable> file,
            ManagedProjection.Edit edit) throws Refused {
        XmlTree.Element element = root;
        PrismContainerValue<?> container = file.getValue();
        for (int i = 0; i < edit.path().size(); i++) {
            ManagedProjection.Step step = edit.path().get(i);
            Item<?, ?> item = container.findItem(ItemName.fromQName(step.item()));
            List<XmlTree.Element> elements = element.children(step.item());
            if (item == null || elements.size() != item.getValues().size()) {
                throw new Refused("its " + edit.label() + " elements do not line up with the values read from them ("
                        + elements.size() + " element(s), " + (item == null ? 0 : item.getValues().size())
                        + " value(s))");
            }
            element = elements.get(step.index());
            if (i < edit.path().size() - 1) {
                container = (PrismContainerValue<?>) item.getValues().get(step.index());
            }
        }
        return element;
    }

    /** Remove {@code element}, and its line when nothing else stands on it. */
    private static Splice deletion(String text, XmlTree.Element element) {
        int start = element.start;
        int end = element.end;
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            return new Splice(lineStart, Math.min(lineEnd + 1, text.length()), "");
        }
        return new Splice(start, end, "");
    }

    /** The splices that turn {@code element} into the edit's value, as few and as small as they can be. */
    private List<Splice> replacement(String text, XmlTree.Element element, ManagedProjection.Edit edit,
            String lineEnd, List<String> notes) throws Refused {
        String serialized;
        try {
            serialized = prismContext.xmlSerializer().options(new SerializationOptions().skipContainerIds(true))
                    .serialize(edit.value(), element.name);
            serialized = withoutDeclaredType(serialized, edit.value());
        } catch (Exception e) {
            throw new Refused("the server's " + edit.label() + " could not be written as XML (" + e.getMessage() + ")");
        }
        XmlTree.Element fresh;
        try {
            fresh = XmlTree.parse(serialized);
        } catch (RuntimeException e) {
            throw new Refused("the server's " + edit.label() + " could not be indexed (" + e.getMessage() + ")");
        }
        List<Splice> splices = new ArrayList<>();
        merge(text, element, serialized, fresh, lineEnd, edit.label(), notes, splices);
        return splices;
    }

    private static final Pattern XSI_TYPE = Pattern.compile("\\s+([\\w.-]+):type\\s*=\\s*\"([^\"]*)\"");

    /**
     * {@code serialized} without the {@code xsi:type} prism puts on a value written on its own, when it names the
     * type the item declares anyway: in the file the element stands where the schema expects it, and a type
     * attribute there would be noise the old element did not have.
     */
    private static String withoutDeclaredType(String serialized, com.evolveum.midpoint.prism.PrismValue value) {
        if (!(value.getParent() instanceof Item<?, ?> item) || item.getDefinition() == null) {
            return serialized;
        }
        XmlTree.Element root = XmlTree.parse(serialized);
        Matcher type = XSI_TYPE.matcher(serialized.substring(root.start, root.startTagEnd));
        while (type.find()) {
            if (!"http://www.w3.org/2001/XMLSchema-instance".equals(root.scope.get(type.group(1)))) {
                continue;
            }
            String written = type.group(2);
            int colon = written.indexOf(':');
            String namespace = root.scope.get(colon >= 0 ? written.substring(0, colon) : "");
            QName declared = item.getDefinition().getTypeName();
            if (declared != null && declared.getLocalPart().equals(written.substring(colon + 1))
                    && declared.getNamespaceURI().equals(namespace)) {
                return serialized.substring(0, root.start + type.start())
                        + serialized.substring(root.start + type.end());
            }
        }
        return serialized;
    }

    /**
     * Compare an element of the file with the one prism wrote for the server's value: alike in name, attributes
     * and child elements, follow into the children; both holding text only, rewrite the text if it differs;
     * otherwise write the element whole.
     */
    private static void merge(String text, XmlTree.Element old, String serialized, XmlTree.Element fresh,
            String lineEnd, String label, List<String> notes, List<Splice> splices) {
        if (!old.name.equals(fresh.name) || !sameAttributes(old, fresh)) {
            splices.add(whole(text, old, serialized, fresh, lineEnd, label, notes));
            return;
        }
        if (old.children.isEmpty() && fresh.children.isEmpty()) {
            String before = XmlTree.textOf(text, old);
            String after = XmlTree.textOf(serialized, fresh);
            if (before.equals(after)) {
                return;
            }
            if (old.selfClosing) {
                splices.add(whole(text, old, serialized, fresh, lineEnd, label, notes));
                return;
            }
            boolean cdata = XmlTree.isCdata(text, old) && !after.contains("]]>");
            String content = cdata ? "<![CDATA[" + after + "]]>" : XmlTree.escape(after);
            if (text.substring(old.startTagEnd, old.contentEnd).contains("<!--")) {
                notes.add("a comment inside " + label + " was dropped with its old value");
            }
            splices.add(new Splice(old.startTagEnd, old.contentEnd, content.replace("\n", lineEnd)));
            return;
        }
        if (old.children.size() != fresh.children.size()) {
            splices.add(whole(text, old, serialized, fresh, lineEnd, label, notes));
            return;
        }
        for (int i = 0; i < old.children.size(); i++) {
            if (!old.children.get(i).name.equals(fresh.children.get(i).name)) {
                splices.add(whole(text, old, serialized, fresh, lineEnd, label, notes));
                return;
            }
        }
        for (int i = 0; i < old.children.size(); i++) {
            merge(text, old.children.get(i), serialized, fresh.children.get(i), lineEnd, label, notes, splices);
        }
    }

    private static boolean sameAttributes(XmlTree.Element old, XmlTree.Element fresh) {
        if (old.attributes.size() != fresh.attributes.size()) {
            return false;
        }
        for (Map.Entry<String, String> attribute : old.attributes.entrySet()) {
            String other = fresh.attributes.get(attribute.getKey());
            if (other == null || !other.equals(attribute.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code old} replaced by {@code fresh} as prism wrote it: with the namespace declarations it needs where the
     * file does not already bind its prefixes the same way, and its lines indented to where {@code old} stood —
     * except the lines of a value, which are content.
     */
    private static Splice whole(String text, XmlTree.Element old, String serialized, XmlTree.Element fresh,
            String lineEnd, String label, List<String> notes) {
        if (text.substring(old.start, old.end).contains("<!--")) {
            notes.add("a comment inside " + label + " was dropped with its old value");
        }
        String element = serialized.substring(fresh.start, fresh.end);
        int nameEnd = 1 + fresh.rawName.length();
        String startTag = serialized.substring(fresh.start, fresh.startTagEnd);
        String withoutDeclarations = startTag.replaceAll("\\s+xmlns(:[\\w.-]+)?\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        StringBuilder declarations = new StringBuilder();
        Map<String, String> target = old.outerScope();
        for (Map.Entry<String, String> binding : new LinkedHashMap<>(fresh.scope).entrySet()) {
            String prefix = binding.getKey();
            if (prefix.equals("xml") || binding.getValue().equals(target.get(prefix)) || !uses(element, prefix)) {
                continue;
            }
            declarations.append(' ').append(prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix).append("=\"")
                    .append(binding.getValue().replace("&", "&amp;").replace("\"", "&quot;")).append('"');
        }
        String rebuilt = withoutDeclarations.substring(0, nameEnd) + declarations
                + withoutDeclarations.substring(nameEnd) + element.substring(startTag.length());
        return new Splice(old.start, old.end, reindented(rebuilt, serialized, fresh, indentOf(text, old.start),
                columnOf(serialized, fresh.start), lineEnd));
    }

    /** Whether {@code element} uses {@code prefix}: in a name, or in a QName written as a value. */
    private static boolean uses(String element, String prefix) {
        if (prefix.isEmpty()) {
            return true; // an unprefixed name is everywhere; keep the default where it differs
        }
        return Pattern.compile("(?<![\\w.-])" + Pattern.quote(prefix) + ":[A-Za-z_]").matcher(element).find();
    }

    /**
     * Shift every line of {@code element} after its first from where prism put it to where the file has it. A line
     * that starts inside the text of a value is part of the value and stays as it is.
     */
    private static String reindented(String element, String serialized, XmlTree.Element fresh, String indent,
            int column, String lineEnd) {
        List<int[]> values = new ArrayList<>();
        collectValues(fresh, values);
        int shift = element.length() - (fresh.end - fresh.start); // the declarations added to the start tag
        StringBuilder out = new StringBuilder();
        String[] lines = element.split("\n", -1);
        int offset = fresh.start - shift;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                out.append(lineEnd);
                int at = offset;
                boolean inValue = false;
                for (int[] range : values) {
                    if (at > range[0] && at < range[1]) {
                        inValue = true;
                        break;
                    }
                }
                if (!inValue) {
                    int strip = 0;
                    while (strip < column && strip < line.length() && line.charAt(strip) == ' ') {
                        strip++;
                    }
                    line = indent + line.substring(strip);
                }
            }
            out.append(line);
            offset += lines[i].length() + 1;
        }
        return out.toString();
    }

    /** The content ranges of the elements under {@code element} that hold text only. */
    private static void collectValues(XmlTree.Element element, List<int[]> ranges) {
        if (element.children.isEmpty()) {
            if (!element.selfClosing) {
                ranges.add(new int[] {element.startTagEnd, element.contentEnd});
            }
            return;
        }
        for (XmlTree.Element child : element.children) {
            collectValues(child, ranges);
        }
    }

    /** The whitespace before {@code at} on its line, or nothing when something else stands there. */
    private static String indentOf(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        String before = text.substring(lineStart, at);
        return before.isBlank() ? before.replace("\uFEFF", "") : "";
    }

    private static int columnOf(String text, int at) {
        return at - (text.lastIndexOf('\n', at - 1) + 1);
    }

    /** The edited text must read back as the file with the edits made, or it is not written. */
    private void verify(String edited, PrismObject<? extends Objectable> file, List<ManagedProjection.Edit> edits)
            throws Refused {
        PrismObject<? extends Objectable> expected = ManagedProjection.applied(file, edits);
        PrismObject<? extends Objectable> reread;
        try {
            reread = prismContext.parseObject(edited.startsWith("\uFEFF") ? edited.substring(1) : edited);
        } catch (Exception e) {
            throw new Refused("the edited file would not parse (" + e.getMessage() + ")");
        }
        if (!reread.equals(expected, EquivalenceStrategy.REAL_VALUE)) {
            throw new Refused("the edited file would not read back as the server's values");
        }
    }
}
