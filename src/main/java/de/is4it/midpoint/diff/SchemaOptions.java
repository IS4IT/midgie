/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SchemaType;
import com.evolveum.prism.xml.ns._public.types_3.SchemaDefinitionType;

import picocli.CommandLine.Option;

/**
 * Shared schema-loading flags for the {@code diff} and {@code apply} subcommands, plus the logic that
 * builds a {@link PrismContext} with runtime extension schema registered. Two layers (see DESIGN.md):
 *
 * <ul>
 *   <li><b>Base</b> schema comes from the {@code midpoint-schema} jar (version-pinned, not runtime).</li>
 *   <li><b>Extension</b> schema is runtime: {@code --schema-dir} of raw XSDs (via the factory ctor),
 *       {@code --schema} exported {@code SchemaType} XML files, and — in live mode — the {@code SchemaType}
 *       objects read off the server.</li>
 * </ul>
 */
public final class SchemaOptions {

    @Option(names = "--schema-dir", paramLabel = "DIR",
            description = "Directory of extension XSDs to register (raw .xsd files).")
    File schemaDir;

    @Option(names = "--schema", paramLabel = "FILE",
            description = "Exported SchemaType XML to register as an extension schema. Repeatable.")
    File[] schemaFiles;

    /**
     * Build the prism context (base schema + any {@code --schema-dir} XSDs) and register extension schema
     * from {@code --schema} files and, when {@code liveClient} is non-null, from the server. Live-schema
     * failures are non-fatal (warned) — base-schema objects still diff.
     */
    public PrismContext buildContext(MidpointRestClient liveClient)
            throws SchemaException, SAXException, IOException {
        MidPointPrismContextFactory factory = (schemaDir != null)
                ? new MidPointPrismContextFactory(schemaDir)
                : new MidPointPrismContextFactory();
        PrismContext prismContext = factory.createInitializedPrismContext();

        Map<String, Element> schemas = new LinkedHashMap<>();
        if (schemaFiles != null) {
            for (File file : schemaFiles) {
                String description = "--schema " + file.getName();
                PrismObject<?> object = prismContext.parseObject(file);
                schemas.put(description, SchemaSupport.schemaElement(description, object.asObjectable()));
            }
        }
        if (liveClient != null) {
            collectLiveSchemas(prismContext, liveClient, schemas);
        }

        SchemaSupport.register(prismContext, schemas);
        return prismContext;
    }

    /** Read {@code SchemaType} objects off the server and add their schemas. Best-effort: warns on failure. */
    private static void collectLiveSchemas(PrismContext prismContext, MidpointRestClient client,
            Map<String, Element> schemas) {
        try {
            String listXml = client.listObjects("schemas");
            ObjectListType list = prismContext.parserFor(listXml).xml().parseRealValue(ObjectListType.class);
            if (list == null) {
                return;
            }
            for (ObjectType object : list.getObject()) {
                if (object instanceof SchemaType) {
                    SchemaDefinitionType definition = ((SchemaType) object).getDefinition();
                    if (definition != null && definition.getSchema() != null) {
                        schemas.put("live schema " + object.getName() + " (" + object.getOid() + ")",
                                definition.getSchema());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("midpoint-diff: warning: could not read extension schema from the server ("
                    + e.getMessage() + "); continuing with base schema only.");
        }
    }
}
