/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact {@code ObjectModificationType} XML the tool emits, so a change in prism serialization
 * (or our conversion) that would alter the REST PATCH body is caught. Regenerate the golden with
 * {@code java -jar target/midgie.jar diff examples/a.xml examples/b.xml} if a change is intended.
 */
class GoldenOutputTest {

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrap() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void objectModificationXmlMatchesGolden() throws Exception {
        PrismObject<? extends Objectable> desired = prismContext.parseObject(new File("examples/a.xml"));
        PrismObject<? extends Objectable> live = prismContext.parseObject(new File("examples/b.xml"));
        ObjectDelta<? extends Objectable> delta = new DiffEngine(prismContext).diff(
                desired, live, EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.replaceAll()).delta();

        String actual = DeltaOutput.toModificationXml(prismContext, delta).strip();
        String golden = Files.readString(Path.of("src/test/resources/golden/role-description-replace.xml"),
                StandardCharsets.UTF_8).strip();

        assertEquals(golden, actual, "serialized ObjectModificationType drifted from the golden file");
    }
}
