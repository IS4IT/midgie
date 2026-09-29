/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.DOMUtil;

/**
 * Walks the {@code deploy} paths and classifies each XML/XSD file by its root element, <em>without</em>
 * yet parsing the objects (extension schema must be registered first). A {@code <xsd:schema>} root is a
 * raw XSD (local schema input); a {@code {…common-3}schema} root is an exported {@code SchemaType}
 * (schema input <em>and</em> a deployable object); anything else is a candidate object file, parsed later.
 * A file that is not well-formed XML, or a {@code SchemaType} that does not parse, is {@linkplain
 * ScanResult#unreadable() unreadable}: the caller fails the run rather than deploy the tree without it.
 *
 * <p>Symbolic links are followed, to files and to directories alike: a Kubernetes ConfigMap mounts every key
 * as a link into a hidden {@code ..data} directory, and a tree reached through a linked directory is still the
 * tree. The hidden directories themselves are not walked (see {@link #collectFiles}), so each file is found
 * once, by the name it is mounted under.
 */
final class Discovery {

    private static final Logger LOG = LoggerFactory.getLogger(Discovery.class);

    private static final String COMMON_3_NAMESPACE =
            "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    private Discovery() {
    }

    /** The outcome of a scan: discovered schema files, candidate object files, skip warnings, and failures. */
    static final class ScanResult {
        private final List<SchemaFile> schemas;
        private final List<File> objectFiles;
        private final List<String> warnings;
        private final List<String> unreadable;
        private final int walkedFiles;

        ScanResult(List<SchemaFile> schemas, List<File> objectFiles, List<String> warnings, List<String> unreadable,
                int walkedFiles) {
            this.schemas = schemas;
            this.objectFiles = objectFiles;
            this.warnings = warnings;
            this.unreadable = unreadable;
            this.walkedFiles = walkedFiles;
        }

        /**
         * Files in the tree that cannot be read — not well-formed XML (a merge that left its conflict markers
         * behind), or a {@code SchemaType} that does not parse — each as a printable sentence. Deploying the rest
         * would exit 0 with them missing, and on a tracked tree read their objects as removed.
         */
        List<String> unreadable() {
            return unreadable;
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

        /**
         * Every regular file the walk saw, whatever its name — the count that tells a tree holding nothing
         * deployable apart from a path holding nothing at all, which is what an unmounted volume looks like.
         */
        int walkedFiles() {
            return walkedFiles;
        }
    }

    static ScanResult scan(PrismContext prismContext, List<File> paths) throws IOException {
        return scan(prismContext, paths, List.of());
    }

    /**
     * As {@link #scan(PrismContext, List)}, with {@code excludedDirs} removed from the walk.
     *
     * <p>{@code deploy --plan <dir>} may legitimately be pointed inside the tree it is planning, and the
     * files it writes would then be discovered on the next run. Excluding the directory by canonical path
     * settles that for the current run with positive knowledge; the generated-file marker
     * ({@link PlanStore#GENERATED_MARKER}) is what covers later runs, other operators, and copies.
     */
    static ScanResult scan(PrismContext prismContext, List<File> paths, List<File> excludedDirs)
            throws IOException {
        List<SchemaFile> schemas = new ArrayList<>();
        List<File> objectFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<File> generated = new ArrayList<>();
        int[] walked = { 0 };
        for (File file : collectFiles(paths, excludedDirs, warnings, walked)) {
            classify(prismContext, file, schemas, objectFiles, unreadable, generated);
        }
        if (!generated.isEmpty()) {
            warnings.add("skipped " + generated.size() + " midgie-generated plan file(s) (" + generated.get(0)
                    + (generated.size() > 1 ? ", …" : "") + ") — a plan is midgie's output, not deploy input.");
        }
        return new ScanResult(schemas, objectFiles, warnings, unreadable, walked[0]);
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
            LOG.trace("could not re-read the root element of {}", file, e);
            return false;
        }
    }

    private static void classify(PrismContext prismContext, File file, List<SchemaFile> schemas,
            List<File> objectFiles, List<String> unreadable, List<File> generated) {
        Document document;
        try {
            document = DOMUtil.parseFile(file);
        } catch (Exception e) {
            unreadable.add("cannot read " + file + ": not well-formed XML (" + e.getMessage() + ")");
            LOG.trace("classify {} → not well-formed XML", file, e);
            return;
        }

        if (isGenerated(document)) {
            generated.add(file);
            LOG.trace("classify {} → midgie-generated plan file, not deployable", file);
            return;
        }

        Element root = document.getDocumentElement();
        if (SchemaSupport.isXsdSchema(root)) {
            schemas.add(new SchemaFile(file, root, SchemaSupport.targetNamespace(root),
                    SchemaSupport.importedNamespaces(root), null));
            LOG.trace("classify {} → raw XSD schema", file);
        } else if ("schema".equals(root.getLocalName()) && COMMON_3_NAMESPACE.equals(root.getNamespaceURI())) {
            try {
                PrismObject<? extends Objectable> object = prismContext.parseObject(file);
                Element schemaElement = SchemaSupport.schemaElement(file.getName(), object.asObjectable());
                schemas.add(new SchemaFile(file, schemaElement, SchemaSupport.targetNamespace(schemaElement),
                        SchemaSupport.importedNamespaces(schemaElement), Fragment.of(object, file)));
                LOG.trace("classify {} → SchemaType object (deployable schema)", file);
            } catch (Exception e) {
                unreadable.add("cannot read the schema " + file + ": " + e.getMessage());
                LOG.trace("classify {} → unreadable SchemaType", file, e);
            }
        } else {
            objectFiles.add(file);
            LOG.trace("classify {} → candidate object ({})", file, root.getLocalName());
        }
    }

    /**
     * Every considered file under {@code paths}, deduplicated, minus anything under {@code excludedDirs}.
     *
     * <p>Overlapping arguments are an easy mistake in a pipeline ({@code deploy config config/roles}), and
     * without deduplication each shared file became two apply units: it was reconciled and PATCHed twice,
     * and the cross-fragment clobber check warned that a file contradicts itself.
     *
     * <p>Dot-directories are not descended into. They hold tooling state — {@code .git} above all, and any
     * plan directory named by convention — never deployable configuration. A path given explicitly on the
     * command line is still walked even if it is one, so naming a dot-directory outright still works.
     */
    private static List<File> collectFiles(List<File> paths, List<File> excludedDirs, List<String> warnings,
            int[] walked) throws IOException {
        Map<String, File> byCanonicalPath = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        for (File dir : excludedDirs) {
            excluded.add(canonical(dir));
        }
        List<File> files = new ArrayList<>();
        int skippedByExclusion = 0;
        for (File path : paths) {
            if (!path.exists()) {
                throw new FileNotFoundException("path does not exist: " + path);
            }
            if (path.isDirectory()) {
                skippedByExclusion += walkDirectory(path, excluded, byCanonicalPath, walked, warnings);
                continue;
            }
            walked[0]++;
            if (!isConsidered(path)) {
                warnings.add("skipped " + path + " — not a .xml or .xsd file");
            } else if (isUnder(canonical(path), excluded)) {
                skippedByExclusion++;
            } else {
                byCanonicalPath.putIfAbsent(canonical(path), path);
            }
        }
        if (skippedByExclusion > 0) {
            warnings.add("skipped " + skippedByExclusion + " file(s) inside the plan directory — a plan is "
                    + "midgie's output, not deploy input.");
        }
        files.addAll(byCanonicalPath.values());
        files.sort(Comparator.comparing(File::getPath));
        return files;
    }

    /**
     * Walk {@code root}, collecting the files worth classifying and counting every regular file seen.
     *
     * @return how many considered files were dropped because they sit under an excluded directory
     */
    private static int walkDirectory(File root, List<String> excluded, Map<String, File> byCanonicalPath,
            int[] walked, List<String> warnings) throws IOException {
        Path rootPath = root.toPath();
        int[] skipped = { 0 };
        Files.walkFileTree(rootPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(rootPath) && dir.getFileName().toString().startsWith(".")) {
                    LOG.trace("not descending into dot-directory {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (isUnder(canonical(dir.toFile()), excluded)) {
                    LOG.trace("not descending into excluded directory {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                walked[0]++;
                if (!isConsidered(file.toFile())) {
                    return FileVisitResult.CONTINUE;
                }
                String path = canonical(file.toFile());
                if (isUnder(path, excluded)) {
                    skipped[0]++;
                } else {
                    byCanonicalPath.putIfAbsent(path, file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof FileSystemLoopException) {
                    // A link back to a directory already on the way down: everything below it has been walked.
                    warnings.add("skipped " + file + " — a symbolic link back into the tree above it");
                    return FileVisitResult.CONTINUE;
                }
                // Both the root and any subdirectory that cannot be opened arrive here, so this is the one
                // place that turns the file system's bare-path message into something diagnosable. It still
                // fails the run: a subtree midgie cannot read is not a subtree it may quietly skip, or
                // deploy would apply a partial tree and exit 0, and --prune would read the objects it never
                // saw as removals.
                throw Io.cannotRead(file.toFile(), e);
            }
        });
        return skipped[0];
    }

    /** Whether {@code canonicalPath} is one of, or nested under, the given canonical directories. */
    private static boolean isUnder(String canonicalPath, List<String> canonicalDirs) {
        for (String dir : canonicalDirs) {
            if (canonicalPath.equals(dir) || canonicalPath.startsWith(dir + File.separator)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the document carries midgie's generated-file marker as a comment outside the root element. */
    private static boolean isGenerated(Document document) {
        NodeList children = document.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.COMMENT_NODE && node.getNodeValue() != null
                    && node.getNodeValue().contains(PlanStore.GENERATED_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static boolean isConsidered(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".xml") || name.endsWith(".xsd");
    }
}
