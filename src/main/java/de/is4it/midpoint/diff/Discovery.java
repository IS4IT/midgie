/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.DOMUtil;

/**
 * Walks the {@code deploy} paths and classifies each XML/XSD file by its root element, <em>without</em>
 * yet parsing the objects (extension schema must be registered first). A {@code <xsd:schema>} root is a
 * raw XSD (local schema input); a {@code {…common-3}schema} root is an exported {@code SchemaType}
 * (schema input <em>and</em> a deployable object); anything else is a candidate object file, parsed later.
 * Files that are not well-formed XML are skipped with a warning.
 */
final class Discovery {

    private static final Logger LOG = LoggerFactory.getLogger(Discovery.class);

    private static final String COMMON_3_NAMESPACE =
            "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private Discovery() {
    }

    /** The outcome of a scan: discovered schema files, candidate object files, and skip warnings. */
    static final class ScanResult {
        private final List<SchemaFile> schemas;
        private final List<File> objectFiles;
        private final List<String> warnings;

        ScanResult(List<SchemaFile> schemas, List<File> objectFiles, List<String> warnings) {
            this.schemas = schemas;
            this.objectFiles = objectFiles;
            this.warnings = warnings;
        }

        List<SchemaFile> schemas() {
            return schemas;
        }

        List<File> objectFiles() {
            return objectFiles;
        }

        List<String> warnings() {
            return warnings;
        }
    }

    static ScanResult scan(PrismContext prismContext, List<File> paths) throws IOException {
        List<SchemaFile> schemas = new ArrayList<>();
        List<File> objectFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (File file : collectFiles(paths, warnings)) {
            classify(prismContext, file, schemas, objectFiles, warnings);
        }
        return new ScanResult(schemas, objectFiles, warnings);
    }

    /**
     * Whether {@code file}'s root element is in midPoint's common-3 namespace — i.e. whether the file claims
     * to be a midPoint object rather than some other XML that happens to live in the tree. Used to tell an
     * object that cannot be parsed (an error) from an unrelated file (an advisory).
     */
    static boolean declaresMidpointObject(File file) {
        try {
            return COMMON_3_NAMESPACE.equals(DOMUtil.parseFile(file).getDocumentElement().getNamespaceURI());
        } catch (Exception e) {
            LOG.debug("could not re-read the root element of {}", file, e);
            return false;
        }
    }

    private static void classify(PrismContext prismContext, File file, List<SchemaFile> schemas,
            List<File> objectFiles, List<String> warnings) {
        Element root;
        try {
            root = DOMUtil.parseFile(file).getDocumentElement();
        } catch (Exception e) {
            warnings.add("skipped " + file + " — not well-formed XML (" + e.getMessage() + ")");
            LOG.debug("classify {} → not well-formed XML", file, e);
            return;
        }

        if (SchemaSupport.isXsdSchema(root)) {
            schemas.add(new SchemaFile(file, root, SchemaSupport.targetNamespace(root),
                    SchemaSupport.importedNamespaces(root), null));
            LOG.debug("classify {} → raw XSD schema", file);
        } else if ("schema".equals(root.getLocalName()) && COMMON_3_NAMESPACE.equals(root.getNamespaceURI())) {
            try {
                PrismObject<? extends Objectable> object = prismContext.parseObject(file);
                Element schemaElement = SchemaSupport.schemaElement(file.getName(), object.asObjectable());
                schemas.add(new SchemaFile(file, schemaElement, SchemaSupport.targetNamespace(schemaElement),
                        SchemaSupport.importedNamespaces(schemaElement), Fragment.of(object, file)));
                LOG.debug("classify {} → SchemaType object (deployable schema)", file);
            } catch (Exception e) {
                warnings.add("skipped schema " + file + " — " + e.getMessage());
                LOG.debug("classify {} → skipped SchemaType", file, e);
            }
        } else {
            objectFiles.add(file);
            LOG.debug("classify {} → candidate object ({})", file, root.getLocalName());
        }
    }

    /** Walk the given paths, gathering {@code .xml}/{@code .xsd} regular files in a stable order. */
    private static List<File> collectFiles(List<File> paths, List<String> warnings) throws IOException {
        List<File> files = new ArrayList<>();
        for (File path : paths) {
            if (!path.exists()) {
                throw new FileNotFoundException("path does not exist: " + path);
            }
            if (path.isDirectory()) {
                try (Stream<Path> walk = Files.walk(path.toPath())) {
                    walk.filter(Files::isRegularFile).map(Path::toFile).filter(Discovery::isConsidered)
                            .forEach(files::add);
                }
            } else if (isConsidered(path)) {
                files.add(path);
            } else {
                warnings.add("skipped " + path + " — not a .xml or .xsd file");
            }
        }
        files.sort(Comparator.comparing(File::getPath));
        return files;
    }

    private static boolean isConsidered(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".xml") || name.endsWith(".xsd");
    }
}
