/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.builder.S_ItemEntry;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableRowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * midgie's deployment state on the server: a {@code LookupTableType} named {@value #TABLE_NAME}. It is what lets
 * a later run recover <em>last-applied</em> and so tell a value you deliberately removed from one that merely
 * accrued on the server. Its rows are read and written by {@link FileTracking}:
 * <ul>
 *   <li>one row per deployed <b>file</b> — {@code key} = the repository's root commit plus the file's path in
 *       it, {@code label} = the object the file described, {@code value} = a small JSON document holding the
 *       git blob id of the content last applied;</li>
 *   <li>one {@code deploy:} row per deployed path, recording the last run from it;</li>
 *   <li>rows written by earlier versions, one per deployed tree, whose {@code value} is a bare commit sha —
 *       read once more when their files are first deployed with per-file rows, then removed.</li>
 * </ul>
 *
 * <p>The object is <b>never created implicitly</b>: {@code deploy} only uses a table that already exists, and
 * {@code midgie init --create-state} is the one thing that creates it. That way midgie never leaves an
 * unexplained object on someone's server, and enabling tracking stays a deliberate act.
 *
 * <p>On creation it describes and protects itself for an administrator who has never heard of this tool —
 * {@code indestructible} (midPoint's model layer then refuses to delete it), a {@code description} and
 * {@code documentation} explaining what it is and that losing it is harmless, and {@code subtype=midgie} so
 * every midgie-owned object is findable in one search. Setting {@code lifecycleState} to {@code archived} or
 * {@code suspended} is the administrator's off-switch: midgie then stops reading and writing state, without
 * anyone having to fight the deletion guard.
 */
final class SyncState {

    private static final Logger LOG = LoggerFactory.getLogger(SyncState.class);

    /** Name of the lookup table holding midgie's deployment state. */
    static final String TABLE_NAME = "midgie-state";

    /** REST collection for {@code LookupTableType} (from {@code ObjectTypes.getRestTypeFromClass}). */
    static final String REST_TYPE = "lookupTables";

    /** Marker on every midgie-owned object, so they can all be found with one search. */
    static final String SUBTYPE = "midgie";

    private static final String DESCRIPTION =
            "Deployment state written by midgie; not used by midPoint itself. Do not edit rows by hand.";

    private static final String DOCUMENTATION = """
            Created and maintained by midgie (https://github.com/IS4IT/midgie), a schema-aware configuration \
            deployment tool. midPoint itself never reads this object.

            Each row records one deployed configuration file:
              key   - a stable identity for that file (its repository's root commit plus the path within it)
              value - which version of the file was most recently applied to this server, and when
              label - the object the file describes
            Rows whose key starts with 'deploy:' record the last deployment from a path: when, from which \
            commit, by which account, and how many objects it created, patched or left in sync.

            midgie reads this to work out which configuration values it deployed previously, so that a value \
            you removed from the source tree can be told apart from one that accrued on the server. \
            It is bookkeeping only: no midPoint behaviour depends on it.

            Losing it is harmless. If this object is removed, midgie simply stops detecting removals until \
            'midgie init --create-state' recreates it - it never deletes anything as a result. It is marked \
            indestructible only to prevent accidental deletion; clear that flag first if you really want it \
            gone. To switch tracking off without deleting anything, set lifecycleState to 'archived'.""";

    private SyncState() {
    }

    /**
     * The state table <em>including its rows</em>, or {@code null} when it does not exist (tracking not
     * initialised).
     *
     * <p>Two calls, deliberately: a search finds it by name, then it is fetched by OID with
     * {@code ?include=row}. midPoint returns a lookup table's rows as {@code incomplete="true"} unless they
     * are explicitly requested, and an incomplete container reads as an empty one — which would both hide
     * every recorded revision and make the row update below diff against phantom-empty state.
     */
    static PrismObject<? extends Objectable> find(PrismContext prismContext, MidpointRestClient client)
            throws SchemaException, IOException {
        List<PrismObject<? extends Objectable>> matches =
                LiveLookup.byName(prismContext, client, LookupTableType.class, REST_TYPE, TABLE_NAME);
        if (matches.isEmpty()) {
            LOG.debug("no {} on the server: delete detection unavailable", TABLE_NAME);
            return null;
        }
        String oid = matches.get(0).getOid();
        return prismContext.parseObject(client.getObject(REST_TYPE, oid, "row"));
    }

    /**
     * Whether the table may be used. An administrator can park midgie by setting the object's
     * {@code lifecycleState} to {@code archived} or {@code suspended}; {@code deprecated} means "discouraged",
     * not "off", so it is deliberately still honoured.
     */
    static boolean isEnabled(PrismObject<? extends Objectable> table) {
        if (table == null) {
            return false;
        }
        String lifecycle = ((LookupTableType) table.asObjectable()).getLifecycleState();
        boolean off = "archived".equalsIgnoreCase(lifecycle) || "suspended".equalsIgnoreCase(lifecycle);
        if (off) {
            LOG.debug("{} is {}: tracking disabled by the administrator", TABLE_NAME, lifecycle);
        }
        return !off;
    }

    /** The lifecycle state an administrator set, for reporting why tracking is off. */
    static String lifecycleOf(PrismObject<? extends Objectable> table) {
        return (table != null) ? ((LookupTableType) table.asObjectable()).getLifecycleState() : null;
    }

    /**
     * Create the state table. Only {@code midgie init --create-state} calls this. Returns the new OID, or
     * {@code null} when the server did not report one.
     */
    static String create(PrismContext prismContext, MidpointRestClient client)
            throws SchemaException, IOException {
        LookupTableType table = new LookupTableType();
        table.setName(new PolyStringType(TABLE_NAME));
        table.setDescription(DESCRIPTION);
        table.setDocumentation(DOCUMENTATION);
        table.getSubtype().add(SUBTYPE);
        table.setLifecycleState("active");   // documentary: an unset state already resolves to active
        table.setIndestructible(true);       // midPoint's model layer then refuses a delete

        String body = Reconciler.createBody(prismContext, table.asPrismObject());
        LOG.debug("creating {}", TABLE_NAME);
        return client.createObject(REST_TYPE, body);
    }

    /** One row of the state table, as read. */
    record Row(Long id, String key, String value, String label) {
    }

    /** Every row of {@code table} (fetched with its rows, see {@link #find}), in the order the server gave them. */
    static List<Row> rows(PrismObject<? extends Objectable> table) {
        List<Row> rows = new ArrayList<>();
        if (table == null) {
            return rows;
        }
        for (LookupTableRowType row : ((LookupTableType) table.asObjectable()).getRow()) {
            rows.add(new Row(row.getId(), row.getKey(), row.getValue(),
                    (row.getLabel() != null) ? row.getLabel().getOrig() : null));
        }
        return rows;
    }

    /**
     * Rows to write in one PATCH: each {@code set} adds a row or, when one with that key exists, replaces its
     * value and label; each {@code delete} removes a row by key.
     */
    static final class Changes {
        final Map<String, String[]> set = new LinkedHashMap<>();   // key -> {value, label}
        final Set<String> delete = new LinkedHashSet<>();

        void set(String key, String value, String label) {
            set.put(key, new String[] {value, label});
            delete.remove(key);
        }

        void delete(String key) {
            if (!set.containsKey(key)) {
                delete.add(key);
            }
        }

        boolean isEmpty() {
            return set.isEmpty() && delete.isEmpty();
        }
    }

    /**
     * Write {@code changes} as one PATCH of individual rows — added whole, updated by their container id,
     * removed by it. Each run touches only the rows it has something to say about, so two deploys at the same
     * time do not overwrite each other's rows, as replacing the table's rows wholesale would.
     */
    static void apply(PrismContext prismContext, MidpointRestClient client,
            PrismObject<? extends Objectable> table, Changes changes) throws SchemaException, IOException {
        if (changes.isEmpty()) {
            return;
        }
        // The rows as they are now, not as the run found them at its start: a row another deploy removed or
        // added since would otherwise be addressed by an id it no longer has, and that one row failed the whole
        // PATCH. A writer between this read and the PATCH can still do it, so a failure is tried once more on
        // a fresh read.
        try {
            patch(prismContext, client, reread(prismContext, client, table), changes);
        } catch (IOException first) {
            LOG.debug("writing {} failed; trying once more on a fresh read", TABLE_NAME, first);
            try {
                patch(prismContext, client, reread(prismContext, client, table), changes);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static PrismObject<? extends Objectable> reread(PrismContext prismContext, MidpointRestClient client,
            PrismObject<? extends Objectable> table) throws SchemaException, IOException {
        return prismContext.parseObject(client.getObject(REST_TYPE, table.getOid(), "row"));
    }

    private static void patch(PrismContext prismContext, MidpointRestClient client,
            PrismObject<? extends Objectable> table, Changes changes) throws SchemaException, IOException {
        // Two first deploys of one file at the same time can each add its row. The first is the one read and the
        // one written; the others are removed as soon as a run writes that key, so a later run cannot go on
        // reading one row while writing another.
        Map<String, Row> byKey = new LinkedHashMap<>();
        List<Row> duplicates = new ArrayList<>();
        for (Row row : rows(table)) {
            if (byKey.putIfAbsent(row.key(), row) != null) {
                duplicates.add(row);
            }
        }
        S_ItemEntry delta = prismContext.deltaFor(LookupTableType.class);
        int written = 0;
        for (Map.Entry<String, String[]> entry : changes.set.entrySet()) {
            Row existing = byKey.get(entry.getKey());
            String value = entry.getValue()[0];
            String label = entry.getValue()[1];
            if (existing == null || existing.id() == null) {
                LookupTableRowType row = new LookupTableRowType();
                row.setKey(entry.getKey());
                row.setValue(value);
                if (label != null) {
                    row.setLabel(new PolyStringType(label));
                }
                delta = delta.item(LookupTableType.F_ROW).add(row.asPrismContainerValue());
                written++;
                continue;
            }
            if (!Objects.equals(existing.value(), value)) {
                delta = delta.item(ItemPath.create(LookupTableType.F_ROW, existing.id(), LookupTableRowType.F_VALUE))
                        .replace(value);
                written++;
            }
            if (label != null && !label.equals(existing.label())) {
                delta = delta.item(ItemPath.create(LookupTableType.F_ROW, existing.id(), LookupTableRowType.F_LABEL))
                        .replace(PolyString.fromOrig(label));
                written++;
            }
        }
        for (String key : changes.delete) {
            Row existing = byKey.get(key);
            if (existing != null && existing.id() != null) {
                LookupTableRowType row = new LookupTableRowType();
                row.setId(existing.id());
                delta = delta.item(LookupTableType.F_ROW).delete(row.asPrismContainerValue());
                written++;
            }
        }
        for (Row duplicate : duplicates) {
            if (duplicate.id() != null
                    && (changes.set.containsKey(duplicate.key()) || changes.delete.contains(duplicate.key()))) {
                LookupTableRowType row = new LookupTableRowType();
                row.setId(duplicate.id());
                delta = delta.item(LookupTableType.F_ROW).delete(row.asPrismContainerValue());
                written++;
            }
        }
        if (written == 0) {
            LOG.debug("{} already records everything this run would write", TABLE_NAME);
            return;
        }
        ObjectDelta<LookupTableType> objectDelta = delta.asObjectDelta(table.getOid());
        client.patchObject(REST_TYPE, table.getOid(), DeltaOutput.toModificationXml(prismContext, objectDelta));
        LOG.debug("wrote {} row change(s) to {}", written, TABLE_NAME);
    }
}
