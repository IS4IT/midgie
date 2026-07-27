/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableRowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * midgie's deployment state on the server: a {@code LookupTableType} named {@value #TABLE_NAME} holding one
 * row per deployed tree — {@code key} = the tree's stable identity, {@code value} = the revision last applied,
 * {@code label} = a human hint. It is what lets a later run recover <em>last-applied</em> and so tell a value
 * you deliberately removed from one that merely accrued on the server.
 *
 * <p>The object is <b>never created implicitly</b>: {@code deploy} only uses a table that already exists, and
 * {@code midgie init} is the one thing that creates it. That way midgie never leaves an unexplained object on
 * someone's server, and enabling tracking stays a deliberate act.
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

            Each row records one deployed configuration tree:
              key   - a stable identity for that tree (its repository's root commit plus the path within it)
              value - the configuration revision most recently applied to this server
              label - a human-readable hint of which tree that is

            midgie reads this to work out which configuration values it deployed previously, so that a value \
            you removed from the source tree can be told apart from one that accrued on the server. \
            It is bookkeeping only: no midPoint behaviour depends on it.

            Losing it is harmless. If this object is removed, midgie simply stops detecting removals until \
            'midgie init' recreates it - it never deletes anything as a result. It is marked indestructible \
            only to prevent accidental deletion; clear that flag first if you really want it gone. To switch \
            tracking off without deleting anything, set lifecycleState to 'archived'.""";

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

    /** The revision recorded for {@code key}, or {@code null} when this tree has never been deployed. */
    static String revisionOf(PrismObject<? extends Objectable> table, String key) {
        if (table == null) {
            return null;
        }
        for (LookupTableRowType row : ((LookupTableType) table.asObjectable()).getRow()) {
            if (key.equals(row.getKey())) {
                return row.getValue();
            }
        }
        return null;
    }

    /**
     * Create the state table. Only {@code midgie init} calls this. Returns the new OID, or {@code null} when
     * the server did not report one.
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

        String body = ApplyCommand.createBody(prismContext, table.asPrismObject());
        LOG.debug("creating {}", TABLE_NAME);
        return client.createObject(REST_TYPE, body);
    }

    /**
     * Record {@code revision} for {@code key}, adding the row or updating it in place. The patch is derived
     * by diffing a modified copy against the fetched table, so no delta is hand-built.
     */
    static void record(PrismContext prismContext, MidpointRestClient client,
            PrismObject<? extends Objectable> table, String key, String revision, String label)
            throws SchemaException, IOException {
        @SuppressWarnings("unchecked")
        PrismObject<? extends Objectable> desired = (PrismObject<? extends Objectable>) table.clone();
        LookupTableType desiredTable = (LookupTableType) desired.asObjectable();

        LookupTableRowType target = null;
        for (LookupTableRowType row : desiredTable.getRow()) {
            if (key.equals(row.getKey())) {
                target = row;
                break;
            }
        }
        if (target == null) {
            target = new LookupTableRowType();
            target.setKey(key);
            desiredTable.getRow().add(target);
        }
        target.setValue(revision);
        if (label != null && !label.isBlank()) {
            target.setLabel(new PolyStringType(label));
        }

        DiffEngine.DiffResult result = new DiffEngine(prismContext).diff(desired, table,
                EquivalenceStrategy.REAL_VALUE, DiffEngine.Scope.FULL,
                DiffEngine.MultiValueStrategy.replaceAll());
        if (!result.hasDrift()) {
            LOG.debug("{} already records {} at {}", TABLE_NAME, key, revision);
            return;
        }
        client.patchObject(REST_TYPE, table.getOid(),
                DeltaOutput.toModificationXml(prismContext, result.delta()));
        LOG.debug("recorded {} = {} in {}", key, revision, TABLE_NAME);
    }
}
