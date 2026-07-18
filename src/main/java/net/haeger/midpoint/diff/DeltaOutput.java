package net.haeger.midpoint.diff;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectFactory;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType;

/** Renders a computed delta for humans and for the REST PATCH: {@code ObjectModificationType} XML or a summary. */
final class DeltaOutput {

    private DeltaOutput() {
    }

    /** The {@code objectModification} XML that midPoint's REST {@code PATCH} consumes. */
    static String toModificationXml(PrismContext prismContext, ObjectDelta<? extends Objectable> delta)
            throws SchemaException {
        ObjectModificationType modification = DeltaConvertor.toObjectModificationType(delta);
        return prismContext.xmlSerializer().serializeRealValue(modification, ObjectFactory.F_OBJECT_MODIFICATION);
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
