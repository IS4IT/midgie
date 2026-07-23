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

        Entry(Kind kind, String restType, String oid, String body) {
            this.kind = kind;
            this.restType = restType;
            this.oid = oid;
            this.body = body;
        }
    }

    private PlanStore() {
    }

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

    /** Write one unit's body. {@code oid} is used verbatim in the name; a null OID falls back to the name. */
    static void write(File dir, int sequence, String restType, String oid, String name, Kind kind, String body)
            throws IOException {
        String token = (oid != null && !oid.isBlank()) ? oid : sanitize(name);
        String fileName = String.format("%03d-%s-%s.%s.xml", sequence, restType, token,
                kind == Kind.CREATE ? "create" : "patch");
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, fileName).toPath(), body + System.lineSeparator(), StandardCharsets.UTF_8);
        LOG.debug("plan file written: {}", fileName);
    }

    /** Read a plan directory into apply-ordered entries (sorted by filename). */
    static List<Entry> read(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("--apply-plan directory does not exist: " + dir);
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
        String body = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return new Entry(kind, parts[1], parts[2], body);
    }

    private static String sanitize(String name) {
        String cleaned = (name != null) ? name.replaceAll("[^A-Za-z0-9]", "") : "";
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }
}
