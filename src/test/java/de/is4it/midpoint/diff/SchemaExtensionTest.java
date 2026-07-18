/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;

import javax.xml.datatype.XMLGregorianCalendar;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies runtime extension-schema registration via {@link SchemaOptions#buildContext}. Both input
 * formats are exercised — {@code --schema} with an exported {@code SchemaType} XML, and {@code --schema-dir}
 * with a raw {@code .xsd} (alongside a wrong-format file that must be warned about and skipped). In each
 * case a custom {@code extension} attribute must resolve to its declared type (dateTime ->
 * XMLGregorianCalendar) rather than staying a raw, untyped value.
 */
class SchemaExtensionTest {

    private static final String LCM_NS = "http://is4it.de/midpoint/v1/aieLCM";

    @Test
    void schemaFlagAcceptsSchemaTypeXml() throws Exception {
        SchemaOptions options = new SchemaOptions();
        options.schemaFiles = new File[] { new File("src/test/resources/lcm-extension-schema.xml") };

        assertLcmAttributeResolves(options.buildContext(null));
    }

    @Test
    void schemaDirAcceptsRawXsdAndSkipsWrongFormat() throws Exception {
        // The directory holds lcm.xsd (a raw XSD) plus notaschema.xml (a RoleType, not a SchemaType),
        // which must be warned about and skipped without derailing the load.
        SchemaOptions options = new SchemaOptions();
        options.schemaDir = new File("src/test/resources/schema");

        assertLcmAttributeResolves(options.buildContext(null));
    }

    /** Diff two users differing only in the custom extension attribute and assert it resolves as dateTime. */
    private static void assertLcmAttributeResolves(PrismContext prismContext) throws Exception {
        PrismObject<?> desired = prismContext.parseObject(userWithLcm("2026-02-02T00:00:00.000Z"));
        PrismObject<?> live = prismContext.parseObject(userWithLcm("2026-01-01T00:00:00.000Z"));

        DiffEngine.DiffResult result = new DiffEngine(prismContext).diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll());

        assertEquals(1, result.delta().getModifications().size(), "expected a single extension delta");
        ItemDelta<?, ?> mod = result.delta().getModifications().iterator().next();
        assertTrue(mod.getPath().toString().contains("lcmLastProcessed"),
                "delta must target the custom extension attribute, got " + mod.getPath());
        assertNotNull(mod.getValuesToReplace(), "must be a replace");

        // Without the registered schema the value would be a raw String; with it the dateTime type
        // resolves to XMLGregorianCalendar.
        Object value = mod.getValuesToReplace().iterator().next().getRealValue();
        assertTrue(value instanceof XMLGregorianCalendar,
                "extension attribute must be schema-typed as dateTime, got " + value.getClass().getName());
    }

    private static String userWithLcm(String lcmLastProcessed) {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " xmlns:aielcm=\"" + LCM_NS + "\""
                + " oid=\"eeeeeeee-0000-0000-0000-0000000000e1\">"
                + "<name>zz-ext</name>"
                + "<extension><aielcm:lcmLastProcessed>" + lcmLastProcessed
                + "</aielcm:lcmLastProcessed></extension>"
                + "</user>";
    }
}
