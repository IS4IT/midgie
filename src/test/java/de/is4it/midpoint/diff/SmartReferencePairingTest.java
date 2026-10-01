/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several smart references in one multi-valued reference item, a template's {@code includeRef}. Under {@code accept} any OID can be a filter's
 * resolution, so the first live value that passed was taken — and with more values than filters, which one was
 * left to delete was arbitrary.
 */
class SmartReferencePairingTest {

    private static final String X = "33333333-0000-0000-0000-00000000000a";
    private static final String Y = "33333333-0000-0000-0000-00000000000b";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void moreValuesThanFiltersUnderReplaceLetsTheServerResolveThem() throws Exception {
        // One filter, two live OIDs, no stored filter: either could be its resolution. Paired with the first,
        // the other was deleted, whichever it was.
        DiffEngine.DiffResult result = diff(template(filterRef("A")), template(ref(X) + ref(Y)), false,
                DiffEngine.SmartReferenceCheck.ACCEPT);

        ItemDelta<?, ?> mod = only(result);
        assertNotNull(mod.getValuesToReplace(), "a replace of the item: " + mod);
        assertEquals(1, mod.getValuesToReplace().size(), mod.toString());
        assertNull(((PrismReferenceValue) mod.getValuesToReplace().iterator().next()).getOid(),
                "the filter, for midPoint to resolve");
        assertTrue(DeltaOutput.carriesSmartReference(result.delta()), "sent with reevaluateSearchFilters");
    }

    @Test
    void moreValuesThanFiltersUnderMergeKeepsThemAll() throws Exception {
        DiffEngine.DiffResult result = diff(template(filterRef("A")), template(ref(X) + ref(Y)), true,
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.hasDrift(), result.delta().toString());
    }

    @Test
    void fewerValuesThanFiltersUnderReplaceLetsTheServerResolveThem() throws Exception {
        // Two filters, one live OID: which filter's resolution is missing cannot be told. Paired with the first,
        // the second was sent — and midPoint 4.10 fails the whole PATCH when a filter added resolves to a value
        // the object holds already.
        DiffEngine.DiffResult result = diff(template(filterRef("A") + filterRef("B")), template(ref(X)), false,
                DiffEngine.SmartReferenceCheck.ACCEPT);

        ItemDelta<?, ?> mod = only(result);
        assertNotNull(mod.getValuesToReplace(), "a replace of the item: " + mod);
        assertEquals(2, mod.getValuesToReplace().size(), mod.toString());
    }

    @Test
    void fewerValuesThanFiltersUnderMergeSendsNothingAndSaysWhy() throws Exception {
        DiffEngine.DiffResult result = diff(template(filterRef("A") + filterRef("B")), template(ref(X)), true,
                DiffEngine.SmartReferenceCheck.ACCEPT);

        assertFalse(result.hasDrift(), result.delta().toString());
        assertTrue(result.inputWarnings().stream().anyMatch(w -> w.contains("--smart-refs verify")),
                result.inputWarnings().toString());
    }

    @Test
    void asManyValuesAsFiltersIsInSyncWhicheverWayTheyPair() throws Exception {
        for (boolean merge : List.of(false, true)) {
            DiffEngine.DiffResult result = diff(template(filterRef("A") + filterRef("B")), template(ref(X) + ref(Y)), merge,
                    DiffEngine.SmartReferenceCheck.ACCEPT);

            assertFalse(result.hasDrift(), (merge ? "merge: " : "replace: ") + result.delta());
        }
    }

    @Test
    void aCheckThatNamesTheResolutionPairsExactly() throws Exception {
        // verify asks the server, so it is never in doubt: A is X, and Y is the value to go.
        Map<String, String> resolution = Map.of("A", X);
        DiffEngine.SmartReferenceCheck exact = (live, desired) ->
                live.getOid().equals(resolution.get(filterName(desired)));

        DiffEngine.DiffResult result = diff(template(filterRef("A")), template(ref(X) + ref(Y)), false, exact);

        ItemDelta<?, ?> mod = only(result);
        assertEquals(0, size(mod.getValuesToAdd()), mod.toString());
        assertEquals(List.of(Y), mod.getValuesToDelete().stream()
                .map(value -> ((PrismReferenceValue) value).getOid()).toList());
    }

    // --- helpers ---

    private static DiffEngine.DiffResult diff(String desiredXml, String liveXml, boolean merge,
            DiffEngine.SmartReferenceCheck check) throws Exception {
        PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredXml);
        PrismObject<? extends Objectable> live = prismContext.parseObject(liveXml);
        return new DiffEngine(prismContext).diff(desired, live, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.Scope.PRESENT,
                merge ? DiffEngine.MultiValueStrategy.of(true, List.of()) : DiffEngine.MultiValueStrategy.replaceAll(),
                check);
    }

    private static ItemDelta<?, ?> only(DiffEngine.DiffResult result) {
        assertEquals(1, result.delta().getModifications().size(), result.delta().toString());
        return result.delta().getModifications().iterator().next();
    }

    private static int size(java.util.Collection<? extends PrismValue> values) {
        return (values != null) ? values.size() : 0;
    }

    private static String filterName(PrismReferenceValue desired) {
        try {
            String xml = prismContext.xmlSerializer().serializeRealValue(desired.getFilter());
            return xml.contains(">A<") ? "A" : xml.contains(">B<") ? "B" : "?";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String template(String body) {
        return "<objectTemplate xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:c=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:org=\"http://midpoint.evolveum.com/xml/ns/public/common/org-3\""
                + " xmlns:q=\"http://prism.evolveum.com/xml/ns/public/query-3\""
                + " oid=\"44444444-0000-0000-0000-000000000003\"><name>T</name>" + body + "</objectTemplate>";
    }

    private static String ref(String oid) {
        return "<includeRef oid=\"" + oid + "\" relation=\"org:default\" type=\"c:ObjectTemplateType\"/>";
    }

    private static String filterRef(String name) {
        return "<includeRef type=\"c:ObjectTemplateType\"><filter><q:equal><q:path>c:name</q:path>"
                + "<q:value>" + name + "</q:value></q:equal></filter></includeRef>";
    }
}
