/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Writes a remainder as the text of its file, the same bytes for the same object every time, so that git shows
 * a change on the server as a change in the file and nothing else.
 *
 * <p>The text is prism's own serialization, tidied as midgie tidies every body it sends ({@link XmlPretty}), with
 * {@code \n} line endings and a trailing newline, under a fixed comment. The comment carries no timestamp, which
 * would change the file on every pull. It also does not carry {@link PlanStore#GENERATED_MARKER}: discovery
 * skips files with that marker, and a remainder file is meant to be deployed.
 *
 * <p>Items keep the schema's order and list entries the server's, which is what prism serializes in. A text that
 * does not read back as the object it was written from is refused rather than written: a remainder that loses a
 * value would have a deploy under {@code --scope full} delete it.
 */
final class RemainderWriter {

    static final String HEADER = "<!-- Written by 'midgie pull' from the server: the part of this object that no "
            + "other file of the tree declares.\n     pull rewrites this file; to curate an item, move it into "
            + "a file of your own. -->\n";

    private final PrismContext prismContext;

    RemainderWriter(PrismContext prismContext) {
        this.prismContext = prismContext;
    }

    /**
     * The file text for {@code remainder}.
     *
     * @throws SchemaException when prism cannot serialize it, or the text does not read back as the same object
     */
    String text(PrismObject<? extends Objectable> remainder) throws SchemaException {
        // An object read from a search result is named after the list's entries, which prism writes as a
        // placeholder element with an xsi:type; a tree file wants the object's own element.
        PrismObjectDefinition<?> definition = (remainder.getCompileTimeClass() != null)
                ? prismContext.getSchemaRegistry().findObjectDefinitionByCompileTimeClass(
                        remainder.getCompileTimeClass().asSubclass(Objectable.class))
                : null;
        if (definition != null) {
            remainder.setElementName(definition.getItemName());
        }
        String xml = XmlPretty.tidyRoot(prismContext.xmlSerializer().serialize(remainder));
        String text = HEADER + normalized(xml);
        PrismObject<? extends Objectable> reread = prismContext.parseObject(text);
        if (!reread.equals(remainder, EquivalenceStrategy.REAL_VALUE)) {
            throw new SchemaException("the remainder of " + remainder.getOid() + " does not read back as written; "
                    + "it is not written, so that no value is lost");
        }
        return text;
    }

    /**
     * {@code \n} line endings and exactly one newline at the end. Trailing blanks inside the text stay: they may
     * belong to a value, such as a script.
     */
    static String normalized(String xml) {
        String text = xml.replace("\r\n", "\n").replace('\r', '\n');
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\n";
    }
}
