/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.schema.constants.ObjectTypes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code midgie pull}: write what the server holds of each object beyond the tree's own files into the remainder
 * folder, one file per object ({@link RemainderLayout}).
 *
 * <p>The tree's files are the <em>managed</em> part of an object, written and curated by hand; the remainder is
 * the rest, generated. Together they are the whole object, which is what lets a deploy of both run under
 * {@code --scope full} and a diff show a change on the server as exactly that change. pull leaves the managed
 * files as they are: where they and the server disagree, it says so, and the deploy settles it.
 *
 * <p>Which objects get a remainder file is the policy's {@code remainder} knob: an object the tree manages gets
 * one unless a rule says {@code remainder=no}; any other object only where a rule says {@code remainder=yes}.
 * Git is the review: pull writes the files and {@code git diff} shows what changed on the server.
 */
@Command(
        name = "pull",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Write what the server holds beyond the tree's files into the remainder folder, one file "
                + "per object."
)
public class PullCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(PullCommand.class);

    private static final int EXIT_CHANGES = 1;
    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0..*", arity = "0..*", paramLabel = "PATH",
            description = "The tree's files or folders: the managed part of each object, which pull leaves out "
                    + "of the remainder.")
    List<File> paths;

    @Mixin
    SchemaOptions schemaOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Option(names = "--remainder", paramLabel = "DIR",
            description = "The folder the remainder files go to, usually set as 'remainder' in the tree's "
                    + ".midgie.properties.")
    File remainder;

    @Option(names = "--policy", paramLabel = "FILE",
            description = "Policy file; its 'remainder' rules decide which objects get a remainder file, and "
                    + "items it marks apply=never are left out of them.")
    File policyFile;

    @Option(names = "--stages", paramLabel = "GLOB", split = ",",
            description = "Folders that each hold one stage's part of the tree, as deploy takes it: another "
                    + "stage's files do not count as managed files outside this run.")
    List<String> stages;

    @Option(names = "--dry-run",
            description = "Show what would change in the remainder folder, as a diff, without writing. Exits 1 "
                    + "if anything would change.")
    boolean dryRun;

    @Option(names = {"--quiet", "-q"},
            description = "Print errors and warnings only.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Log what pull decides per object.")
    boolean debug;

    @Option(names = "--trace", description = "Log everything, HTTP included.")
    boolean trace;

    /** One object pull looked at: what the server holds, and the tree's managed part of it, if any. */
    private static final class Candidate {
        final String typeName;
        final String restType;
        final PrismObject<? extends Objectable> live;
        DeployObject managed;

        Candidate(String typeName, String restType, PrismObject<? extends Objectable> live) {
            this.typeName = typeName;
            this.restType = restType;
            this.live = live;
        }

        String oid() {
            return live.getOid();
        }

        String name() {
            return LiveLookup.nameOf(live);
        }

        String label() {
            return typeName + "/" + name();
        }
    }

    /** A change to one remainder file: written anew, rewritten, or removed ({@code text == null}). */
    private record Change(File file, String before, String text, String reason) {
    }

    private Policy policy;
    private boolean failed;
    private int skipped;

    @Override
    public Integer call() {
        if (trace) {
            Logging.enableTrace();
        } else if (debug) {
            Logging.enableDebug();
        }
        if (!connectionOptions.isLive()) {
            System.err.println("midgie: pull reads from a server; set --url (or MIDPOINT_URL).");
            return EXIT_ERROR;
        }
        if (connectionOptions.oid != null) {
            System.err.println("midgie: pull reads every object of the tree and its policy; --oid does not apply.");
            return EXIT_ERROR;
        }
        if (remainder == null) {
            System.err.println("midgie: pull needs a folder to write to: set 'remainder' in the tree's "
                    + ".midgie.properties, or pass --remainder DIR.");
            return EXIT_ERROR;
        }
        if (paths == null || paths.isEmpty()) {
            System.err.println("midgie: provide at least one PATH: the tree's files, which pull leaves out of "
                    + "the remainder.");
            return EXIT_ERROR;
        }
        RemainderLayout layout = new RemainderLayout(remainder);
        for (File path : paths) {
            if (layout.contains(path)) {
                System.err.println("midgie: " + path + " lies in the remainder folder " + remainder + "; give the "
                        + "tree's own files, which pull leaves out of the remainder.");
                return EXIT_ERROR;
            }
        }
        try {
            return run(layout);
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midgie: " + message);
            LOG.trace("pull failed", e);
            return EXIT_ERROR;
        }
    }

    private Integer run(RemainderLayout layout) throws Exception {
        PrismContext prismContext = SchemaOptions.newBaseContext();
        policy = Policy.effective(policyFile);

        Discovery.ScanResult scan = Discovery.scan(prismContext, paths, List.of(remainder));
        if (!scan.unreadable().isEmpty()) {
            scan.unreadable().forEach(this::warn);
            return EXIT_ERROR;
        }
        Map<String, org.w3c.dom.Element> schemaMap = new LinkedHashMap<>();
        Set<String> schemaOids = new HashSet<>();
        for (SchemaFile schema : scan.schemas()) {
            schemaMap.put(schema.description(), schema.schemaElement());
            if (schema.deployable() && schema.fragment().oid() != null) {
                schemaOids.add(schema.fragment().oid());
            }
        }
        schemaMap.putAll(schemaOptions.collectSchemas(prismContext, null));

        MidpointRestClient client = connectionOptions.newClient();
        if (client == null) {
            return EXIT_ERROR;
        }
        if (!quiet) {
            VersionSupport.warnIfMismatch(prismContext, client);
        }
        schemaMap.putAll(schemaOptions.collectLiveSchemas(prismContext, client));
        SchemaSupport.register(prismContext, schemaMap);

        List<String> warnings = new ArrayList<>(scan.warnings());
        List<String> unreadable = new ArrayList<>();
        List<DeployObject> managed = DeployObject.group(
                DeployCommand.parseObjects(prismContext, scan, warnings, unreadable));
        if (!unreadable.isEmpty()) {
            unreadable.forEach(this::warn);
            return EXIT_ERROR;
        }
        if (!quiet) {
            warnings.forEach(warning -> note("note: " + warning));
        }

        File root = TreeIndex.rootFor(paths);
        TreeIndex index = null;
        TreeIndex.Run run = null;
        if (root != null) {
            index = TreeIndex.build(prismContext, root, (stages != null) ? stages : List.of(), List.of(remainder));
            run = index.run(managed, paths);
        }

        // Everything is read before anything is written: a server that stops answering half way leaves the
        // remainder folder as it was, not half rewritten.
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (DeployObject unit : managed) {
            Candidate candidate = findManaged(prismContext, client, unit);
            if (candidate != null) {
                candidates.put(candidate.oid(), candidate);
            }
        }
        List<String> listedTypes = policy.remainderTypes(typeNames(prismContext));
        for (String typeName : listedTypes) {
            ObjectTypes type = objectTypeNamed(typeName);
            for (PrismObject<? extends Objectable> live
                    : LiveLookup.all(prismContext, client, type.getClassDefinition(), type.getRestType())) {
                candidates.putIfAbsent(live.getOid(), new Candidate(typeName, type.getRestType(), live));
            }
        }
        LOG.debug("pull: {} managed object(s), listed {}, {} candidate(s)", managed.size(), listedTypes,
                candidates.size());

        RemainderWriter writer = new RemainderWriter(prismContext);
        // Only the apply rules of the policy matter here, for the items it marks never; the rest is deploy's.
        StrategyOptions.Resolved baseline = new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.MultiValueStrategy.replaceAll(), false);
        Map<String, File> existing = layout.existing();
        Set<String> settled = new HashSet<>();
        List<Change> changes = new ArrayList<>();
        List<Candidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparing((Candidate c) -> c.typeName).thenComparing(Candidate::name)
                .thenComparing(Candidate::oid));
        int unchanged = 0;
        for (Candidate candidate : ordered) {
            String oid = candidate.oid();
            File current = existing.get(oid);
            if (schemaOids.contains(oid)) {
                LOG.debug("pull: {} is an extension schema of the tree; no remainder", candidate.label());
                settled.add(oid);
                continue;
            }
            Boolean wanted = policy.remainderFor(candidate.typeName, candidate.name(), oid);
            boolean covered = (candidate.managed != null) ? !Boolean.FALSE.equals(wanted) : Boolean.TRUE.equals(wanted);
            if (!covered) {
                // Left for the sweep below, which removes a file the policy no longer asks for.
                LOG.debug("pull: {} remainder={}; no file", candidate.label(), wanted);
                continue;
            }
            if (index != null) {
                List<File> elsewhere = index.outside(run, candidate.typeName, oid, candidate.name());
                if (!elsewhere.isEmpty()) {
                    note("note: " + candidate.label() + " has " + elsewhere.size() + " file(s) outside this run ("
                            + relativeAll(index, elsewhere) + "); pull them together to update its remainder.");
                    settled.add(oid);
                    continue;
                }
            }
            settled.add(oid);
            int skippedBefore = skipped;
            Change change = remainderOf(prismContext, writer, layout, baseline, candidate, current);
            if (change == null) {
                unchanged += (skipped == skippedBefore) ? 1 : 0;
            } else {
                changes.add(change);
                if (current != null && change.text() != null && !sameFile(current, change.file())) {
                    changes.add(new Change(current, read(current), null, "renamed on the server"));
                }
            }
        }
        for (Map.Entry<String, File> entry : existing.entrySet()) {
            if (settled.contains(entry.getKey())) {
                continue;
            }
            String reason = staleReason(prismContext, index, run, listedTypes, candidates, entry.getKey(),
                    entry.getValue());
            if (reason != null) {
                changes.add(new Change(entry.getValue(), read(entry.getValue()), null, reason));
            }
        }
        return finish(layout, client, changes, unchanged);
    }

    /**
     * The server's copy of a managed object, by its OID or else by type and name; {@code null}, with a note,
     * when the server does not hold it (yet) or holds more than one of that name.
     */
    private Candidate findManaged(PrismContext prismContext, MidpointRestClient client, DeployObject unit)
            throws Exception {
        PrismObject<? extends Objectable> desired = unit.object();
        Class<?> clazz = desired.getCompileTimeClass();
        ObjectTypes type = ObjectTypes.getObjectTypeIfKnown(clazz);
        if (type == null || type.getRestType() == null) {
            note("note: " + unit.objectPath() + " has no REST collection to read it from; skipped.");
            return null;
        }
        PrismObject<? extends Objectable> live;
        if (unit.oid() != null && !unit.oid().isBlank()) {
            live = LiveLookup.byOid(prismContext, client, clazz, type.getRestType(), unit.oid());
        } else {
            List<PrismObject<? extends Objectable>> byName =
                    LiveLookup.byName(prismContext, client, clazz, type.getRestType(), unit.name());
            if (byName.size() > 1) {
                warn(unit.objectPath() + " has no OID and the server holds " + byName.size() + " objects of that "
                        + "name; give the file the OID of the one it describes.");
                failed = true;
                return null;
            }
            live = byName.isEmpty() ? null : byName.get(0);
        }
        if (live == null) {
            note("note: " + unit.objectPath() + " is not on the server; nothing to pull for it.");
            return null;
        }
        Candidate candidate = new Candidate(clazz.getSimpleName(), type.getRestType(), live);
        candidate.managed = unit;
        return candidate;
    }

    /** The change to {@code candidate}'s remainder file, or {@code null} when the file is as it should be. */
    private Change remainderOf(PrismContext prismContext, RemainderWriter writer, RemainderLayout layout,
            StrategyOptions.Resolved baseline, Candidate candidate, File current) throws IOException {
        String oid = candidate.oid();
        ApplyRules apply = policy.resolveFor(candidate.typeName, candidate.name(), oid, baseline).apply;
        Exclusions.Stripped stripped = Exclusions.strip(candidate.live, apply);
        if (!stripped.incompletePaths().isEmpty()) {
            note("note: " + candidate.label() + ": the server did not send " + String.join(", ",
                    stripped.incompletePaths()) + ", so the remainder does not hold it.");
        }
        ObjectSplitter.Split split;
        String text;
        try {
            split = ObjectSplitter.split(stripped.object(),
                    (candidate.managed != null) ? candidate.managed.object() : null);
            if (!split.conflicts().isEmpty()) {
                for (String conflict : split.conflicts()) {
                    warn(candidate.label() + ": " + conflict + ". Its remainder is left as it is; deploy the "
                            + "tree or bring the file in line with the server, then pull again.");
                }
                failed = true;
                skipped++;
                return null;
            }
            if (!split.drift().isEmpty()) {
                note("note: " + candidate.label() + ": the server differs from the tree's files at "
                        + String.join(", ", split.drift()) + "; pull leaves those files as they are (a deploy "
                        + "puts the files' version back).");
            }
            if (candidate.managed != null && !split.hasContent()) {
                if (current == null) {
                    return null;
                }
                return new Change(current, read(current), null, "the tree's files declare all of it");
            }
            text = writer.text(split.remainder());
        } catch (com.evolveum.midpoint.util.exception.SchemaException e) {
            warn(candidate.label() + ": " + e.getMessage());
            failed = true;
            skipped++;
            return null;
        }
        File target = layout.fileFor(candidate.typeName, candidate.name(), oid);
        String before = (current != null) ? read(current) : null;
        if (current != null && sameFile(current, target) && text.equals(before)) {
            return null;
        }
        if (current != null && !sameFile(current, target) && text.equals(before)) {
            // Same content under the old name: only the rename is a change.
            return new Change(target, null, text, "renamed on the server");
        }
        return new Change(target, (current != null && sameFile(current, target)) ? before : null, text, null);
    }

    /**
     * Why a remainder file nothing in this run wanted should go, or {@code null} to leave it: removed when the
     * object is gone from a type this run listed in full, or when no file of the tree manages it and the policy
     * no longer asks for it. A file of an object this run cannot judge — managed by files outside it, say — stays.
     */
    private String staleReason(PrismContext prismContext, TreeIndex index, TreeIndex.Run run, List<String> listedTypes,
            Map<String, Candidate> candidates, String oid, File file) {
        String typeName = file.getParentFile().getName();
        Candidate candidate = candidates.get(oid);
        if (candidate != null) {
            return "the policy no longer asks for it";
        }
        if (listedTypes.contains(typeName)) {
            return "gone from the server";
        }
        if (index == null) {
            return null;
        }
        String name = remainderName(file);
        if (!index.outside(run, typeName, oid, name).isEmpty()) {
            return null;
        }
        return Boolean.TRUE.equals(policy.remainderFor(typeName, name, oid)) ? null : "the policy no longer asks for it";
    }

    /** Write (or with {@code --dry-run}, show) the changes, and report. */
    private Integer finish(RemainderLayout layout, MidpointRestClient client, List<Change> changes, int unchanged)
            throws IOException {
        int written = 0;
        int removed = 0;
        for (Change change : changes) {
            String shown = relative(layout, change.file());
            if (dryRun) {
                System.out.println(diffOf(shown, change));
            } else if (change.text() == null) {
                Files.deleteIfExists(change.file().toPath());
                File typeDir = change.file().getParentFile();
                String[] left = typeDir.list();
                if (left != null && left.length == 0) {
                    Files.deleteIfExists(typeDir.toPath());
                }
            } else {
                Files.createDirectories(change.file().getParentFile().toPath());
                Io.writeAtomically(change.file().toPath(), change.text());
            }
            if (change.text() == null) {
                removed++;
            } else {
                written++;
            }
            if (!quiet) {
                String verb = (change.text() == null) ? "remove" : (change.before() == null ? "add" : "update");
                verb = dryRun ? "would " + verb : verb + (verb.endsWith("e") ? "d" : "ed");
                System.err.println("  " + String.format("%-13s", verb) + shown
                        + ((change.reason() != null) ? "  (" + change.reason() + ")" : ""));
            }
        }
        if (!quiet) {
            System.err.println("midgie: pull from " + client.host() + ": " + (dryRun ? "would write " : "wrote ")
                    + written + ", " + (dryRun ? "would remove " : "removed ") + removed + ", unchanged " + unchanged
                    + (skipped > 0 ? ", left as they were " + skipped : "") + " — in " + layout.root());
        }
        if (failed) {
            return EXIT_ERROR;
        }
        return (dryRun && !changes.isEmpty()) ? EXIT_CHANGES : 0;
    }

    private static String diffOf(String shown, Change change) {
        String before = (change.before() != null) ? change.before() : "";
        String after = (change.text() != null) ? change.text() : "";
        String header = "--- " + (change.before() != null ? shown : "/dev/null") + "\n+++ "
                + (change.text() != null ? shown : "/dev/null") + "\n";
        String diff = TextDiff.unified(before, after, 3);
        if (diff == null) {
            return header + "  (too long to show here; pull without --dry-run and use git diff)\n";
        }
        return header + diff;
    }

    /** The concrete object types the server lists, by schema type name ({@code RoleType}). */
    static List<String> typeNames(PrismContext prismContext) {
        List<String> names = new ArrayList<>();
        for (ObjectTypes type : ObjectTypes.values()) {
            Class<?> clazz = type.getClassDefinition();
            if (clazz == null || type.getRestType() == null || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            PrismObjectDefinition<?> definition =
                    prismContext.getSchemaRegistry().findObjectDefinitionByCompileTimeClass(type.getClassDefinition());
            if (definition == null || definition.isAbstract()) {
                continue;
            }
            names.add(clazz.getSimpleName());
        }
        names.sort(null);
        return names;
    }

    private static ObjectTypes objectTypeNamed(String typeName) {
        for (ObjectTypes type : ObjectTypes.values()) {
            if (type.getClassDefinition() != null && type.getClassDefinition().getSimpleName().equals(typeName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("no object type " + typeName);
    }

    /** The object name a remainder file declares, or {@code null} when it cannot be read. */
    private static String remainderName(File file) {
        try {
            Element root = Io.parseXmlFile(file).getDocumentElement();
            for (org.w3c.dom.Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && "name".equals(element.getLocalName())) {
                    return element.getTextContent().strip();
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("pull: cannot read the name in {}: {}", file, e.getMessage());
        }
        return null;
    }

    private static String relativeAll(TreeIndex index, List<File> files) {
        List<String> shown = new ArrayList<>();
        for (File file : files) {
            shown.add(index.relative(file));
        }
        return String.join(", ", shown);
    }

    private static String relative(RemainderLayout layout, File file) {
        java.nio.file.Path root = layout.root().getAbsoluteFile().toPath().normalize();
        java.nio.file.Path path = file.getAbsoluteFile().toPath().normalize();
        return path.startsWith(root) ? root.relativize(path).toString() : file.getPath();
    }

    private static boolean sameFile(File one, File other) {
        return one.getAbsoluteFile().toPath().normalize().equals(other.getAbsoluteFile().toPath().normalize());
    }

    private static String read(File file) throws IOException {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Io.cannotRead(file, e);
        }
    }

    private void note(String message) {
        if (!quiet) {
            String line = "midgie: " + message;
            System.err.println(NoteStyle.isNote(message) ? NoteStyle.of(line) : line);
        }
    }

    private void warn(String message) {
        System.err.println("midgie: warning: " + message);
    }
}
