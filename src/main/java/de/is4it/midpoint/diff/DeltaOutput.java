/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
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
        return XmlPretty.tidyRoot(
                prismContext.xmlSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION));
    }

    /** The same {@code objectModification} as JSON (midPoint's REST {@code PATCH} also accepts JSON). */
    static String toModificationJson(PrismContext prismContext, ObjectDelta<? extends Objectable> delta)
            throws SchemaException {
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(delta);
        return prismContext.jsonSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
    }

    /** A human-readable, one-line-per-change list (or "No differences."), with the removed-value count. */
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
            int removed = size(mod.getValuesToDelete());
            if (removed > 0) {
                // Spell out the blast radius: "delete <path>" alone reads like one small change even when it
                // takes whole container values (and everything nested in them) with it.
                out.append("  (removes ").append(removed).append(removed == 1 ? " value" : " values").append(')');
            }
        }
        return out.toString();
    }

    /**
     * How many values this delta removes outright ({@code valuesToDelete} across every modification). A
     * removed <em>container</em> value takes everything nested inside it, so this is the number that matters
     * when judging whether a change is destructive.
     */
    /**
     * Whether {@code delta} sets any reference by search filter rather than by OID — a <b>smart
     * reference</b>. Such a PATCH must carry {@code ?options=reevaluateSearchFilters}, or midPoint stores
     * the reference unresolved and the object is left pointing at nothing.
     *
     * <p>Keyed on the prism value kind, never on an element name: only a {@link PrismReferenceValue} has
     * {@code getFilter()}. The schema has many unrelated {@code SearchFilterType} fields — an object
     * collection's filter, a GUI view's, a policy constraint's — and scanning the body for {@code <filter>}
     * would treat those as references.
     */
    static boolean carriesSmartReference(ObjectDelta<? extends Objectable> delta) {
        if (delta == null) {
            return false;
        }
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            for (Collection<?> side : List.of(
                    orEmpty(mod.getValuesToAdd()), orEmpty(mod.getValuesToReplace()))) {
                for (Object value : side) {
                    // Inside an added container value too: an inducement added whole carries its targetRef.
                    if (value instanceof PrismValue prismValue && NestedSmartReferences.setsReferenceByFilter(prismValue)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Collection<?> orEmpty(Collection<?> values) {
        return (values != null) ? values : List.of();
    }

    static int removedValueCount(ObjectDelta<? extends Objectable> delta) {
        int removed = 0;
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            removed += size(mod.getValuesToDelete());
        }
        return removed;
    }

    /**
     * A loud warning when the delta removes values, or {@code null} when it removes nothing. Advisory only —
     * midgie reports the blast radius but does not block; use {@code --dry-run} to inspect first.
     */
    static String removalWarning(ObjectDelta<? extends Objectable> delta) {
        int removed = removedValueCount(delta);
        if (removed == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder("WARNING: this change REMOVES " + removed
                + (removed == 1 ? " value" : " values") + " from the live object");
        for (ItemDelta<?, ?> mod : delta.getModifications()) {
            int count = size(mod.getValuesToDelete());
            if (count > 0) {
                out.append("\n    - ").append(mod.getPath()).append(" (").append(count)
                        .append(count == 1 ? " value" : " values").append(')');
            }
        }
        return out.toString();
    }

    private static int size(java.util.Collection<?> values) {
        return (values != null) ? values.size() : 0;
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
