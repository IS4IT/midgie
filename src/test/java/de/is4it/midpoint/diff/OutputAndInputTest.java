/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for stdin input ({@code -}) and the {@code --format} rendering (xml/json/summary). */
class OutputAndInputTest {

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrap() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void parseObjectReadsStdinForDash() throws Exception {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(role("Piped").getBytes(StandardCharsets.UTF_8)));
            PrismObject<? extends Objectable> object = Io.parseObject(prismContext, new File("-"));
            assertEquals("Example Role", object.getName().getOrig());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void renderProducesXmlJsonAndSummaryAndRejectsUnknown() throws Exception {
        ObjectDelta<? extends Objectable> delta = descriptionChangeDelta();

        String xml = DeltaOutput.render(prismContext, delta, "xml");
        assertTrue(xml.strip().startsWith("<"), "xml starts with a tag");
        assertTrue(xml.contains("objectModification"), "xml is an objectModification");

        String json = DeltaOutput.render(prismContext, delta, "json");
        assertTrue(json.contains("{"), "json has braces: " + json);
        assertTrue(json.contains("objectModification"), "json is an objectModification: " + json);

        assertTrue(DeltaOutput.render(prismContext, delta, "summary").contains("replace"));

        assertThrows(IllegalArgumentException.class, () -> DeltaOutput.render(prismContext, delta, "yaml"));
    }

    @Test
    void aRemovingDeltaIsWarnedAboutOnOneLineForTheObjectBlock() throws Exception {
        PrismObject<? extends Objectable> desired = prismContext.parseObject(role("Same"));
        PrismObject<? extends Objectable> live = prismContext.parseObject(role("Same").replace("</role>",
                "<subtype>a</subtype><subtype>b</subtype><requestable>true</requestable></role>"));
        ObjectDelta<? extends Objectable> delta = new DiffEngine(prismContext).diff(desired, live,
                EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL, DiffEngine.MultiValueStrategy.replaceAll())
                .delta();

        assertEquals("this change REMOVES 3 values from the live object: subtype ×2, requestable ×1",
                DeltaOutput.removalLine(delta));
        assertTrue(DeltaOutput.removalWarning(delta).startsWith("WARNING: this change REMOVES 3 values"),
                "the offline diff keeps its own form");
        assertEquals(null, DeltaOutput.removalLine(descriptionChangeDelta()), "a change that removes nothing");
    }

    private ObjectDelta<? extends Objectable> descriptionChangeDelta() throws Exception {
        PrismObject<? extends Objectable> desired = prismContext.parseObject(role("Desired"));
        PrismObject<? extends Objectable> live = prismContext.parseObject(role("Current"));
        return new DiffEngine(prismContext).diff(desired, live, EquivalenceStrategy.REAL_VALUE,
                DiffEngine.Scope.PRESENT, DiffEngine.MultiValueStrategy.replaceAll()).delta();
    }

    private static String role(String description) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"11111111-1111-1111-1111-111111111111\">"
                + "<name>Example Role</name><description>" + description + "</description></role>";
    }
}
