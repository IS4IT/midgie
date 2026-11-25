/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObjectDefinition;

/**
 * Which objects of the whole tree are described by files a run leaves out.
 *
 * <p>{@code --scope full} reads every item the desired object does not declare as one to delete. That is right
 * when the run holds all of the object's files, and wrong when it holds only some: deploying one fragment of a
 * system configuration whose other fragments sit in another folder would delete everything those other files
 * declare. Tracking knows only the files deployed before, and only on a tracked run, so the question is answered
 * here from the working tree itself: every object file under the tree root is read for its identity (type, OID,
 * name) — without prism, since only the root element and its {@code name} are needed — and matched against the
 * run's objects the way {@link DeployObject#group} groups fragments.
 *
 * <p>The tree may hold one folder per stage, for the parts constants cannot reach. A stage that is not being
 * deployed is not part of this run's object at all, so its files are not counted as missing; a stage the run
 * deploys from is counted like any other folder.
 */
final class TreeIndex {

    private static final Logger LOG = LoggerFactory.getLogger(TreeIndex.class);

    private static final String COMMON_3_NAMESPACE = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    /** One object file of the tree, as far as its identity goes. */
    record Entry(File file, String typeName, String oid, String name) {
    }

    private final Path root;
    private final List<Entry> entries;
    private final List<PathMatcher> stages;

    private TreeIndex(Path root, List<Entry> entries, List<PathMatcher> stages) {
        this.root = root;
        this.entries = List.copyOf(entries);
        this.stages = List.copyOf(stages);
    }

    /**
     * Read the identity of every object file under {@code root}, skipping {@code excludedDirs} (a plan
     * directory inside the tree) and dot-directories as discovery does.
     *
     * @param stageGlobs folders holding one stage's files each, as globs relative to {@code root}
     *                   ({@code stages/*}); empty when the tree has none
     * @throws IOException when a folder of the tree cannot be read — completeness cannot be shown then
     */
    static TreeIndex build(PrismContext prismContext, File root, List<String> stageGlobs, List<File> excludedDirs)
            throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (File file : Discovery.consideredFiles(List.of(root), excludedDirs)) {
            Entry entry = identityOf(prismContext, file);
            if (entry != null) {
                entries.add(entry);
            }
        }
        List<PathMatcher> stages = new ArrayList<>();
        for (String glob : stageGlobs) {
            stages.add(FileSystems.getDefault().getPathMatcher("glob:" + trimSlashes(glob)));
        }
        LOG.debug("tree index of {}: {} object file(s), stage folders {}", root, entries.size(), stageGlobs);
        return new TreeIndex(canonical(root).toPath(), entries, stages);
    }

    /**
     * For each of {@code units} that the tree describes with more files than the run holds, those other files
     * — minus the files of stages the run does not deploy from.
     *
     * @param runPaths the paths given to the run, whose stage folders count as deployed even when every file
     *                 of the object in them is among {@code runFiles} already
     */
    Map<DeployObject, List<File>> outsideRun(List<DeployObject> units, List<File> runPaths) {
        Run run = run(units, runPaths);
        Map<DeployObject, List<File>> outside = new LinkedHashMap<>();
        for (DeployObject unit : units) {
            List<File> others = outside(run, unit.typeName(), unit.oid(), unit.name());
            if (!others.isEmpty()) {
                outside.put(unit, others);
            }
        }
        return outside;
    }

    /** What a run holds of the tree: its files, and the stage folders it deploys from. */
    record Run(Set<String> files, Set<String> stages) {
    }

    /** The files and stages of a run of {@code units}, given {@code runPaths}. */
    Run run(List<DeployObject> units, List<File> runPaths) {
        Set<String> inRun = new HashSet<>();
        Set<String> activeStages = new HashSet<>();
        for (File path : runPaths) {
            String stage = stageOf(path);
            if (stage != null) {
                activeStages.add(stage);
            }
        }
        for (DeployObject unit : units) {
            for (File file : unit.sourceFiles()) {
                inRun.add(canonical(file).getPath());
                String stage = stageOf(file);
                if (stage != null) {
                    activeStages.add(stage);
                }
            }
        }
        return new Run(inRun, activeStages);
    }

    /**
     * The files of the tree describing the object {@code typeName}/{@code oid}/{@code name} that {@code run} does
     * not hold, minus those of stages it does not deploy from.
     */
    List<File> outside(Run run, String typeName, String oid, String name) {
        List<File> others = new ArrayList<>();
        for (Entry entry : entries) {
            if (run.files().contains(canonical(entry.file()).getPath()) || !describes(entry, typeName, oid, name)) {
                continue;
            }
            String stage = stageOf(entry.file());
            if (stage != null && !run.stages().contains(stage)) {
                LOG.debug("{}/{}: {} belongs to stage {}, which this run does not deploy", typeName, name,
                        entry.file(), stage);
                continue;
            }
            others.add(entry.file());
        }
        return others;
    }

    /**
     * The folder a run over {@code paths} looks up its objects' files in: the tree-local config file's folder when
     * every path lies inside it, else the git work tree of the paths, else {@code null} — then nothing shows
     * that an object has files elsewhere, and every run counts as complete.
     */
    static File rootFor(List<File> paths) {
        File treeFile = ConfigDefaults.locateTreeFile(new File(System.getProperty("user.dir", "."))).file();
        if (treeFile != null && containsAll(treeFile.getAbsoluteFile().getParentFile(), paths)) {
            return treeFile.getAbsoluteFile().getParentFile();
        }
        File first = paths.get(0).getAbsoluteFile();
        GitRevision git = GitRevision.of(first.isDirectory() ? first : first.getParentFile());
        File top = git.isRepository() ? git.topLevel() : null;
        return (top != null && containsAll(top, paths)) ? top : null;
    }

    private static boolean containsAll(File dir, List<File> paths) {
        try {
            Path root = dir.getCanonicalFile().toPath();
            for (File path : paths) {
                if (!path.getCanonicalFile().toPath().startsWith(root)) {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /** {@code file} relative to the tree root, for messages; the path as given when it lies elsewhere. */
    String relative(File file) {
        Path path = canonical(file).toPath();
        return path.startsWith(root) ? root.relativize(path).toString() : file.getPath();
    }

    /**
     * Whether {@code entry} describes the object: by OID where both carry one, otherwise by type and name — the
     * same identity {@link DeployObject#group} combines fragments by.
     */
    private static boolean describes(Entry entry, String typeName, String oid, String name) {
        if (!entry.typeName().equals(typeName)) {
            return false;
        }
        if (isSet(entry.oid()) && isSet(oid)) {
            return entry.oid().equals(oid);
        }
        return isSet(entry.name()) && entry.name().equals(name);
    }

    /**
     * The stage folder {@code file} lies in (itself, for a folder given as a run path), relative to the root,
     * or {@code null} when it lies in none. The shortest matching ancestor wins, so a stage folder holding
     * subfolders is one stage, not several.
     */
    private String stageOf(File file) {
        if (stages.isEmpty()) {
            return null;
        }
        Path path = canonical(file).toPath();
        if (!path.startsWith(root)) {
            return null;
        }
        Path relative = root.relativize(path);
        for (int depth = 1; depth <= relative.getNameCount(); depth++) {
            Path candidate = relative.subpath(0, depth);
            if (depth == relative.getNameCount() && !file.isDirectory()) {
                break; // a file's own name is not a stage folder
            }
            for (PathMatcher stage : stages) {
                if (stage.matches(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /**
     * The identity {@code file} declares, or {@code null} when it is not a midPoint object (another kind of
     * XML, a raw schema, a plan midgie wrote) or cannot be read. Unreadable files are discovery's to report;
     * one the run includes has already stopped it.
     */
    private static Entry identityOf(PrismContext prismContext, File file) {
        Element root;
        try {
            org.w3c.dom.Document document = Io.parseXmlFile(file);
            if (Discovery.isGenerated(document)) {
                return null;
            }
            root = document.getDocumentElement();
        } catch (RuntimeException e) {
            LOG.debug("tree index: cannot read {}: {}", file, e.getMessage());
            return null;
        }
        if (!COMMON_3_NAMESPACE.equals(root.getNamespaceURI())) {
            return null;
        }
        PrismObjectDefinition<?> definition = prismContext.getSchemaRegistry()
                .findObjectDefinitionByElementName(new QName(root.getNamespaceURI(), root.getLocalName()));
        if (definition == null || definition.getCompileTimeClass() == null) {
            return null;
        }
        String oid = root.getAttribute("oid");
        return new Entry(file, definition.getCompileTimeClass().getSimpleName(), oid.isBlank() ? null : oid.strip(),
                nameOf(root));
    }

    /** The text of the object's own {@code name} element, or {@code null} when it has none. */
    private static String nameOf(Element root) {
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && "name".equals(element.getLocalName())
                    && COMMON_3_NAMESPACE.equals(element.getNamespaceURI())) {
                // A PolyString may be written plain or with <orig>; either way its orig is the text in it.
                Element orig = firstChild(element, "orig");
                String text = ((orig != null) ? orig : element).getTextContent();
                return (text != null && !text.isBlank()) ? text.strip() : null;
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimSlashes(String glob) {
        String trimmed = glob.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        return trimmed;
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }
}
