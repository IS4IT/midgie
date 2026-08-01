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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *       the server are read and registered too, unless {@code --no-live-schema} opts out.</li>
 * </ul>
 */
public final class SchemaOptions {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaOptions.class);

    @Option(names = "--schema-dir", paramLabel = "DIR",
            description = "Directory of extension schemas (.xsd raw XSD, .xml exported SchemaType); other files ignored.")
    File schemaDir;

    @Option(names = "--schema", paramLabel = "FILE",
            description = "Extension schema file: .xsd raw XSD or .xml exported SchemaType. Repeatable.")
    File[] schemaFiles;

    @Option(names = "--no-live-schema",
            description = "Skip the live GET /schemas read (faster, or when the account lacks schema-read). "
                    + "Objects using extension items then need --schema/--schema-dir, or they diff as raw.")
    boolean noLiveSchema;

    /**
     * Build the prism context (base schema) and register extension schema from {@code --schema-dir},
     * {@code --schema}, and — when {@code liveClient} is non-null — the server. Files in the wrong format
     * and live-schema failures are non-fatal (warned); base-schema objects still diff.
     */
    public PrismContext buildContext(MidpointRestClient liveClient)
            throws SchemaException, SAXException, IOException {
        PrismContext prismContext = newBaseContext();
        SchemaSupport.register(prismContext, collectSchemas(prismContext, liveClient));
        return prismContext;
    }

    /** A fresh prism context carrying only the compiled-in base schema (no runtime extensions yet). */
    static PrismContext newBaseContext() throws SchemaException, SAXException, IOException {
        return new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    /**
     * Collect — but do <em>not</em> register — the runtime extension schema from {@code --schema-dir},
     * {@code --schema}, and (when {@code liveClient} is non-null and {@code --no-live-schema} is off) the
     * server, as a {@code description -> <xsd:schema>} map. Split out from {@link #buildContext} so a caller
     * that has extra schema to add (e.g. {@code deploy}'s tree-discovered schemas) can merge everything and
     * register once — prism's {@code registerDynamicSchemaExtensions} <em>replaces</em> the dynamic set, so
     * all extension schema must go in a single call.
     */
    Map<String, Element> collectSchemas(PrismContext prismContext, MidpointRestClient liveClient)
            throws SchemaException {
        Map<String, Element> schemas = new LinkedHashMap<>();
        if (schemaDir != null) {
            if (!schemaDir.isDirectory()) {
                throw new SchemaException("--schema-dir is not a directory: " + schemaDir);
            }
            addFromDirectory(prismContext, schemaDir, schemas);
        }
        if (schemaFiles != null) {
            for (File file : schemaFiles) {
                if (!file.isFile()) {
                    // A file named explicitly on the command line is not optional. Warning and carrying on
                    // meant a typo'd path produced a delta computed without that extension schema, with the
                    // affected items diffed as raw and nothing but a warning to say so.
                    throw new SchemaException("--schema file does not exist or is not readable: " + file);
                }
                addFile(prismContext, "--schema " + file.getPath(), file, schemas);
            }
        }
        if (liveClient != null && !noLiveSchema) {
            collectLiveSchemas(prismContext, liveClient, schemas);
        }
        LOG.debug("collected {} extension schema(s) (schemaDir={}, schemaFiles={}, liveSchema={})",
                schemas.size(), schemaDir != null, schemaFiles != null ? schemaFiles.length : 0,
                liveClient != null && !noLiveSchema);
        return schemas;
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
                if (!SchemaSupport.isXsdSchema(root)) {
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

    private static void warnSkip(File file, String reason) {
        String detail = (reason == null || reason.isBlank()) ? "unrecognised format" : reason.strip();
        if (detail.endsWith(".")) {
            detail = detail.substring(0, detail.length() - 1);
        }
        System.err.println("midgie: warning: ignoring schema file '" + file + "' — " + detail + ".");
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
            int found = 0;
            for (ObjectType object : list.getObject()) {
                if (object instanceof SchemaType) {
                    SchemaDefinitionType definition = ((SchemaType) object).getDefinition();
                    if (definition != null && definition.getSchema() != null) {
                        schemas.put("live schema " + object.getName() + " (" + object.getOid() + ")",
                                definition.getSchema());
                        found++;
                    }
                }
            }
            LOG.debug("live schema read: {} SchemaType object(s) registered", found);
        } catch (Exception e) {
            if (isReportedByObjectFetch(e)) {
                LOG.debug("live schema read skipped (the object fetch will report this failure): {}", e.toString());
                return; // the caller's object fetch reports the same failure with a clear message
            }
            System.err.println("midgie: warning: could not read extension schema from the server ("
                    + e.getMessage() + "); continuing with base schema only.");
            LOG.trace("live schema read failed", e);
        }
    }

    /**
     * A 401 (auth), a redirect (a misconfigured {@code --url}), or a connection/timeout failure means the
     * whole live operation is doomed and will be reported clearly by the object fetch, so the schema-read
     * warning would just be noise. A 403 (or anything else) is kept — the object may still be readable while
     * {@code /schemas} is not.
     */
    private static boolean isReportedByObjectFetch(Exception e) {
        if (e instanceof MidpointRestClient.RestException restException) {
            int code = restException.statusCode();
            return code == 401 || (code >= 300 && code < 400);
        }
        return e instanceof MidpointRestClient.TransportException;
    }
}
