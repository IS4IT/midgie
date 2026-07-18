/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.util.DOMUtil;
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
 *   <li><b>Extension</b> schema is runtime. Both {@code --schema} (files) and {@code --schema-dir}
 *       (a directory) accept either format, chosen by file extension: a {@code .xsd} is a raw XSD, a
 *       {@code .xml} is an exported {@code SchemaType} object (its embedded {@code <xsd:schema>} is
 *       used). Files of another extension, or {@code .xsd}/{@code .xml} files that are not in the
 *       expected format, are warned about and skipped. In live mode the {@code SchemaType} objects on
 *       the server are read and registered too.</li>
 * </ul>
 */
public final class SchemaOptions {

    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    @Option(names = "--schema-dir", paramLabel = "DIR",
            description = "Directory of extension schemas (.xsd raw XSD, .xml exported SchemaType); other files ignored.")
    File schemaDir;

    @Option(names = "--schema", paramLabel = "FILE",
            description = "Extension schema file: .xsd raw XSD or .xml exported SchemaType. Repeatable.")
    File[] schemaFiles;

    /**
     * Build the prism context (base schema) and register extension schema from {@code --schema-dir},
     * {@code --schema}, and — when {@code liveClient} is non-null — the server. Files in the wrong format
     * and live-schema failures are non-fatal (warned); base-schema objects still diff.
     */
    public PrismContext buildContext(MidpointRestClient liveClient)
            throws SchemaException, SAXException, IOException {
        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();

        Map<String, Element> schemas = new LinkedHashMap<>();
        if (schemaDir != null) {
            addFromDirectory(prismContext, schemaDir, schemas);
        }
        if (schemaFiles != null) {
            for (File file : schemaFiles) {
                addFile(prismContext, "--schema " + file.getName(), file, schemas);
            }
        }
        if (liveClient != null) {
            collectLiveSchemas(prismContext, liveClient, schemas);
        }

        SchemaSupport.register(prismContext, schemas);
        return prismContext;
    }

    private static void addFromDirectory(PrismContext prismContext, File dir, Map<String, Element> schemas)
            throws SchemaException {
        if (!dir.isDirectory()) {
            throw new SchemaException("--schema-dir is not a directory: " + dir);
        }
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".xsd") || lower.endsWith(".xml");
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            addFile(prismContext, "--schema-dir " + file.getName(), file, schemas);
        }
    }

    /** Resolve one file to its {@code <xsd:schema>} element and add it, or warn and skip on the wrong format. */
    private static void addFile(PrismContext prismContext, String description, File file, Map<String, Element> schemas) {
        String lower = file.getName().toLowerCase();
        try {
            if (lower.endsWith(".xsd")) {
                Element root = DOMUtil.parseFile(file).getDocumentElement();
                if (!isXsdSchema(root)) {
                    warnSkip(file, "its root element is not <xsd:schema>");
                    return;
                }
                schemas.put(description, root);
            } else if (lower.endsWith(".xml")) {
                schemas.put(description,
                        SchemaSupport.schemaElement(file.getName(), prismContext.parseObject(file).asObjectable()));
            } else {
                warnSkip(file, "unsupported extension (expected .xsd or .xml)");
            }
        } catch (Exception e) {
            warnSkip(file, e.getMessage());
        }
    }

    private static boolean isXsdSchema(Element root) {
        return root != null
                && "schema".equals(root.getLocalName())
                && XSD_NAMESPACE.equals(root.getNamespaceURI());
    }

    private static void warnSkip(File file, String reason) {
        String detail = (reason == null || reason.isBlank()) ? "unrecognised format" : reason.strip();
        if (detail.endsWith(".")) {
            detail = detail.substring(0, detail.length() - 1);
        }
        System.err.println("midpoint-diff: warning: ignoring schema file '" + file + "' — " + detail + ".");
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
