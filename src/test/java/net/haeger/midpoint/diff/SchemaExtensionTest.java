package net.haeger.midpoint.diff;

import java.io.File;
import java.util.Map;

import javax.xml.datatype.XMLGregorianCalendar;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies runtime extension-schema registration ({@code --schema} file mode): after registering the
 * LCM extension schema, a custom {@code extension} attribute is schema-<em>typed</em> and diffs
 * correctly (rather than being kept as a raw, untyped value).
 */
class SchemaExtensionTest {

    private static final String LCM_NS = "http://is4it.de/midpoint/v1/aieLCM";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapWithExtensionSchema() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        PrismObject<?> schemaObject = prismContext.parseObject(new File("src/test/resources/lcm-extension-schema.xml"));
        SchemaSupport.register(prismContext,
                Map.of("lcm", SchemaSupport.schemaElement("lcm", schemaObject.asObjectable())));
    }

    @Test
    void registeredSchemaTypesAndDiffsACustomExtensionAttribute() throws Exception {
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

        // The clincher: without the registered schema the value would be a raw String; with it, the
        // dateTime type resolves to XMLGregorianCalendar.
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
