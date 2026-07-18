/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;

/** Renders a computed delta for humans and for the REST PATCH: {@code ObjectModificationType} XML/JSON or a summary. */
final class DeltaOutput {

    private DeltaOutput() {
    }

    /** Render the delta in the requested {@code --format} (xml | json | summary). */
    static String render(PrismContext prismContext, ObjectDelta<? extends Objectable> delta, String format)
            throws SchemaException {
        switch (format.toLowerCase()) {
            case "xml":
                return toModificationXml(prismContext, delta);
            case "json":
                return toModificationJson(prismContext, delta);
            case "summary":
                return toSummary(delta);
            default:
                throw new IllegalArgumentException("--format must be xml|json|summary, got: " + format);
        }
    }

    /** The {@code objectModification} XML that midPoint's REST {@code PATCH} consumes. */
    static String toModificationXml(PrismContext prismContext, ObjectDelta<? extends Objectable> delta)
            throws SchemaException {
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(delta);
        return prismContext.xmlSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
    }

    /** The same {@code objectModification} as JSON (midPoint's REST {@code PATCH} also accepts JSON). */
    static String toModificationJson(PrismContext prismContext, ObjectDelta<? extends Objectable> delta)
            throws SchemaException {
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(delta);
        return prismContext.jsonSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
    }

    /** A human-readable, one-line-per-change list (or "No differences."). */
    static String toSummary(ObjectDelta<? extends Objectable> delta) {
        if (delta.isEmpty()) {
            return "No differences.";
        }
        StringBuilder out = new StringBuilder();
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(operationLabel(mod)).append(' ').append(mod.getPath());
        }
        return out.toString();
    }

    private static String operationLabel(ItemDelta<?, ?> mod) {
        if (mod.getValuesToReplace() != null) {
            return "replace";
        }
        StringBuilder label = new StringBuilder();
        if (mod.getValuesToAdd() != null) {
            label.append("add");
        }
        if (mod.getValuesToDelete() != null) {
            label.append(label.length() > 0 ? "+delete" : "delete");
        }
        return label.length() > 0 ? label.toString() : "modify";
    }
}
