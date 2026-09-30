/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes a {@code deploy --plan} directory: one file per changing unit, named
 * {@code NNN-<type>-<token>.<create|patch>.xml} where {@code NNN} is the zero-padded apply position,
 * {@code <type>} the REST collection (e.g. {@code roles}), {@code <token>} the OID (or a sanitised name
 * for an OID-less create), and the extension the kind (a full object body to POST, or an
 * {@code ObjectModificationType} to PATCH). Filenames sort into apply order, and encode the type and OID
 * that {@code --apply-plan} replays against. REST-collection names are camelCase (no {@code -}) and the
 * sequence is digits, so a 3-way split on {@code -} recovers the OID intact.
 */
final class PlanStore {

    private static final Logger LOG = LoggerFactory.getLogger(PlanStore.class);

    enum Kind { CREATE, PATCH }

    /** One entry read back from a plan directory. */
    static final class Entry {
        final Kind kind;
        final String restType;
        final String oid; // the address for a PATCH; unused for a CREATE
        final String body;
        final String fileName;

        Entry(Kind kind, String restType, String oid, String body, String fileName) {
            this.kind = kind;
            this.restType = restType;
            this.oid = oid;
            this.body = body;
            this.fileName = fileName;
        }
    }

    private PlanStore() {
    }

    /**
     * Marks a plan that is being written. Present on disk only between the first file and the last, so a
     * plan whose run died half way through is recognisable as the partial thing it is.
     *
     * <p>Without it, an aborted {@code --plan} left a directory of valid-looking files that
     * {@code --apply-plan} replayed to the end and reported as a complete success — half a tree deployed,
     * exit 0.
     */
    private static final String INCOMPLETE_MARKER = ".midgie-plan-incomplete";

    /**
     * Stamped into every plan file so {@code deploy} can recognise its own output and refuse to treat it as
     * a source object. A {@code .create.xml} is a whole object with a valid root element, so a plan
     * directory that ends up inside a deployed tree is otherwise discovered and <em>redeployed</em>.
     *
     * <p>The marker travels with the content, which is what makes it worth having on top of the path-based
     * exclusions: it survives the file being renamed, moved out of its directory, committed, or shipped
     * through CI as an artifact. It is a guard against accident, not a security boundary — anyone can strip
     * a comment — and that is the right level for the mistake it prevents.
     */
    static final String GENERATED_MARKER = "midgie-generated";

    private static final String GENERATED_COMMENT = "<!-- " + GENERATED_MARKER
            + ": plan file, do not deploy -->";

    /** True if {@code dir} already holds plan files (so a fresh plan is never mixed into a stale one). */
    static boolean hasPlanFiles(File dir) {
        String[] names = dir.list();
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (name.endsWith(".create.xml") || name.endsWith(".patch.xml")) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code dir} holds a plan that was never finished. */
    static boolean isIncomplete(File dir) {
        return new File(dir, INCOMPLETE_MARKER).isFile();
    }

    /** Start writing a plan: nothing in {@code dir} counts as usable until {@link #markComplete}. */
    static void markIncomplete(File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, INCOMPLETE_MARKER).toPath(),
                "midgie is writing this plan; it is incomplete until this file is gone."
                        + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /** The plan is whole and may be replayed. */
    static void markComplete(File dir) throws IOException {
        Files.deleteIfExists(new File(dir, INCOMPLETE_MARKER).toPath());
    }

    /** Delete the files of an unfinished plan, so a re-run does not need manual cleanup. */
    static int discardIncomplete(File dir) throws IOException {
        Files.deleteIfExists(new File(dir, PlanState.FILE).toPath());
        File[] files = dir.listFiles((d, name) -> name.endsWith(".create.xml") || name.endsWith(".patch.xml"));
        int removed = 0;
        for (File file : (files != null) ? files : new File[0]) {
            if (Files.deleteIfExists(file.toPath())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Write one unit's body.
     *
     * <p>{@code oid} names the file for a PATCH, because that is the address {@code --apply-plan} will send
     * it to; a create has no OID yet and falls back to the sanitised object name. A PATCH without an OID is
     * refused rather than silently named after the object: the plan would read as valid under review and
     * then issue {@code PATCH /ws/rest/roles/<Name>}, which is not an address, at replay time.
     *
     * <p>The sequence is padded to {@code width} digits so that filename order — which is what
     * {@link #read} sorts on — is apply order for a plan of any size. At a fixed three digits, unit 1000
     * sorts before unit 999 and a large tree replays out of dependency order.
     */
    static String write(File dir, int sequence, int width, String restType, String oid, String name, Kind kind,
            String body) throws IOException {
        if (kind == Kind.PATCH && (oid == null || oid.isBlank())) {
            throw new IOException("cannot plan a patch of " + restType + "/" + name
                    + " without an OID: a patch is addressed by OID, and the plan would fail on replay.");
        }
        if (oid != null && !oid.isBlank() && !MidpointRestClient.isOid(oid)) {
            // Checked here, not only at replay: the OID names the file, and one that cannot be replayed should
            // not make a plan that reads as valid under review.
            throw new IOException("cannot plan " + restType + "/" + name + ": '" + oid + "' is not a midPoint OID "
                    + "(a UUID).");
        }
        String token = (oid != null && !oid.isBlank()) ? oid : sanitize(name);
        String fileName = String.format("%0" + Math.max(3, width) + "d-%s-%s.%s.xml", sequence, restType, token,
                kind == Kind.CREATE ? "create" : "patch");
        Files.createDirectories(dir.toPath());
        // A create entry is the whole desired object, so it can carry a clearValue password; the process
        // umask would leave the plan readable to every local user of a shared host or CI runner.
        java.nio.file.Path target = new File(dir, fileName).toPath();
        Io.restrictToOwner(target);
        Files.writeString(target, markGenerated(body) + System.lineSeparator(), StandardCharsets.UTF_8);
        LOG.trace("plan file written: {}", fileName);
        return fileName;
    }

    /**
     * Prefix {@code body} with the generated-file marker.
     *
     * <p>An XML declaration has to stay the very first thing in the document, so the comment goes after it
     * when there is one. Getting this wrong would produce plan files that are not well-formed, which
     * {@code --apply-plan} would only discover at replay time.
     */
    static String markGenerated(String body) {
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<?xml")) {
            int end = trimmed.indexOf("?>");
            if (end >= 0) {
                return trimmed.substring(0, end + 2) + System.lineSeparator() + GENERATED_COMMENT
                        + System.lineSeparator() + trimmed.substring(end + 2).stripLeading();
            }
        }
        return GENERATED_COMMENT + System.lineSeparator() + trimmed;
    }

    /** Read a plan directory into apply-ordered entries (sorted by filename). */
    static List<Entry> read(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("--apply-plan directory does not exist: " + dir);
        }
        if (isIncomplete(dir)) {
            throw new IOException("the plan in " + dir + " was never finished — the run that wrote it failed "
                    + "part way through. Re-run --plan; replaying it would apply only part of the tree.");
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".create.xml") || name.endsWith(".patch.xml"));
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files);
        List<Entry> entries = new ArrayList<>();
        for (File file : files) {
            entries.add(parse(file));
        }
        return entries;
    }

    /**
     * Whether a stored patch body sets a reference by search filter, so the replay needs
     * {@code ?options=reevaluateSearchFilters}.
     *
     * <p>Read from the DOM rather than through prism: {@code --apply-plan} replays a reviewed body verbatim
     * and deliberately never pays the prism bootstrap. It still does not guess from the element name — a
     * {@code filter} counts only when the value carrying it declares itself a reference, by
     * {@code xsi:type="…ObjectReferenceType"}, by the {@code oid}/{@code relation}/{@code type} attributes only a
     * reference has, or by a reference's {@code …Ref} element name. The last two matter for a reference inside
     * an added container value — an inducement's {@code targetRef} — which is written without {@code xsi:type}
     * and, set by filter, without an {@code oid}. The unrelated {@code SearchFilterType} fields in the schema
     * (an object collection's filter, a GUI view's) sit in values that declare none of those.
     *
     * <p>Erring toward {@code true} is the safe direction: a missed one leaves the reference unresolved and
     * breaks the object, while a spurious one only makes midPoint record a warning that filters already on
     * the object were not touched.
     */
    static boolean setsReferenceByFilter(Entry entry) {
        if (entry.kind != Kind.PATCH || entry.body == null) {
            return false;
        }
        try {
            org.w3c.dom.NodeList filters =
                    com.evolveum.midpoint.util.DOMUtil.parseDocument(entry.body).getElementsByTagName("*");
            for (int i = 0; i < filters.getLength(); i++) {
                org.w3c.dom.Node node = filters.item(i);
                if (!"filter".equals(node.getLocalName())
                        || !(node.getParentNode() instanceof org.w3c.dom.Element parent)) {
                    continue;
                }
                String declaredType = parent.getAttributeNS(
                        "http://www.w3.org/2001/XMLSchema-instance", "type");
                if (declaredType.endsWith("ObjectReferenceType")
                        || parent.hasAttribute("oid") || parent.hasAttribute("relation")
                        || parent.hasAttribute("type") || String.valueOf(parent.getLocalName()).endsWith("Ref")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // An unreadable body is about to fail the replay anyway; assume the safe direction meanwhile.
            LOG.trace("could not inspect a plan body for reference filters", e);
            return true;
        }
    }

    private static Entry parse(File file) throws IOException {
        String name = file.getName();
        Kind kind;
        String stem;
        if (name.endsWith(".create.xml")) {
            kind = Kind.CREATE;
            stem = name.substring(0, name.length() - ".create.xml".length());
        } else {
            kind = Kind.PATCH;
            stem = name.substring(0, name.length() - ".patch.xml".length());
        }
        String[] parts = stem.split("-", 3); // NNN - type - token(oid, may contain '-')
        if (parts.length < 3) {
            throw new IOException("not a plan file (expected NNN-<type>-<oid>.<kind>.xml): " + name);
        }
        // The point of --apply-plan is that what was reviewed is what runs, and both of these go into the
        // request URI by plain concatenation. A plan directory that was renamed, hand-edited or shipped
        // through CI as an artifact must not be able to retarget a write to another collection or a
        // truncated OID.
        if (!parts[1].matches("[A-Za-z]+")) {
            throw new IOException("not a REST collection name in plan file '" + name + "': " + parts[1]);
        }
        if (!parts[2].matches("[A-Za-z0-9._-]+")) {
            throw new IOException("not a usable object token in plan file '" + name + "': " + parts[2]);
        }
        String body = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return new Entry(kind, parts[1], parts[2], body, name);
    }

    private static String sanitize(String name) {
        String cleaned = (name != null) ? name.replaceAll("[^A-Za-z0-9]", "") : "";
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }
}
