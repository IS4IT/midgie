/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.List;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Reconciles a single desired object (an apply unit) against a live server, reusing the offline
 * {@link DiffEngine}. It <em>plans</em> the action — resolve the current object (search by OID, or a
 * type+name search when {@code --apply-on-match} is set), diff the fragment ({@code --scope present}),
 * and decide {@code CREATE}/{@code PATCH}/{@code IN_SYNC}/{@code SKIP}/{@code FAIL} — and then, separately,
 * <em>executes</em> it (POST/PATCH). Planning is read-only, so it drives both the {@code --dry-run}/preview
 * pass and the real apply pass. All HTTP stays in {@link MidpointRestClient}; this class only orchestrates.
 */
final class Reconciler {

    enum Action { CREATE, PATCH, IN_SYNC, SKIP, FAIL }

    /** A planned (or executed) reconciliation of one unit. */
    static final class Outcome {
        final Action action;
        final String restType;
        final String oid;          // addressed OID (patch) or null (create with no OID / skip / fail)
        final int changes;         // number of item deltas for a PATCH
        final String detail;       // delta summary (patch) or reason (skip/fail)
        final String modificationXml; // PATCH body
        final String createBody;      // POST body

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody) {
            this.action = action;
            this.restType = restType;
            this.oid = oid;
            this.changes = changes;
            this.detail = detail;
            this.modificationXml = modificationXml;
            this.createBody = createBody;
        }
    }

    private final PrismContext prismContext;
    private final MidpointRestClient client;
    private final StrategyOptions.Resolved strategy;
    private final boolean createIfAbsent;
    private final boolean applyOnMatch;

    Reconciler(PrismContext prismContext, MidpointRestClient client, StrategyOptions.Resolved strategy,
            boolean createIfAbsent, boolean applyOnMatch) {
        this.prismContext = prismContext;
        this.client = client;
        this.strategy = strategy;
        this.createIfAbsent = createIfAbsent;
        this.applyOnMatch = applyOnMatch;
    }

    /** Decide what to do with {@code desired}. Read-only. Throws only on a transport/parse failure. */
    Outcome plan(PrismObject<? extends Objectable> desired) throws SchemaException, IOException {
        Class<?> clazz = desired.getCompileTimeClass();
        if (clazz == null) {
            return skip(null, null, "not a recognized midPoint object type");
        }
        String restType;
        try {
            restType = ObjectTypes.getRestTypeFromClass(clazz);
        } catch (IllegalArgumentException e) {
            return skip(null, null, "no REST endpoint for " + clazz.getSimpleName());
        }

        String oid = desired.getOid();
        PrismObject<? extends Objectable> current;
        String targetOid;
        if (oid != null && !oid.isBlank()) {
            current = LiveLookup.byOid(prismContext, client, clazz, restType, oid);
            targetOid = oid;
        } else if (applyOnMatch) {
            List<PrismObject<? extends Objectable>> matches =
                    LiveLookup.byName(prismContext, client, clazz, restType, nameOf(desired));
            if (matches.size() > 1) {
                return fail(restType, null, "--apply-on-match found " + matches.size() + " objects named '"
                        + nameOf(desired) + "'; refusing an ambiguous match");
            }
            current = matches.isEmpty() ? null : matches.get(0);
            targetOid = (current != null) ? current.getOid() : null;
        } else {
            current = null;
            targetOid = null;
        }

        if (current != null) {
            return planPatch(restType, targetOid, desired, current);
        }
        return planCreate(restType, oid, desired);
    }

    private Outcome planPatch(String restType, String targetOid, PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> current) throws SchemaException {
        DiffEngine.DiffResult result = new DiffEngine(prismContext)
                .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);
        if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
            return fail(restType, targetOid, "merge would overwrite accrued value(s) in place at "
                    + String.join(", ", result.accruedInPlaceEditPaths())
                    + " (use --equivalence real-value or --allow-inplace-merge)");
        }
        if (!result.hasDrift()) {
            return new Outcome(Action.IN_SYNC, restType, targetOid, 0, "already matches", null, null);
        }
        int changes = result.delta().getModifications().size();
        String modificationXml = DeltaOutput.toModificationXml(prismContext, result.delta());
        String summary = DeltaOutput.toSummary(result.delta()).replace("\n", "; ");
        return new Outcome(Action.PATCH, restType, targetOid, changes, summary, modificationXml, null);
    }

    private Outcome planCreate(String restType, String oid, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        if (createIfAbsent) {
            return new Outcome(Action.CREATE, restType, oid, 0, "would create",
                    null, ApplyCommand.createBody(prismContext, desired));
        }
        if (oid != null && !oid.isBlank()) {
            return fail(restType, oid, "does not exist (pass --create-if-absent to create it)");
        }
        return skip(restType, null, "no oid; pass --create-if-absent to create, or --apply-on-match to match by name");
    }

    /** Execute a planned {@code CREATE}/{@code PATCH}. Returns the affected OID (created OID for a POST). */
    String execute(Outcome outcome) throws IOException {
        switch (outcome.action) {
            case CREATE:
                String created = client.createObject(outcome.restType, outcome.createBody);
                return (created != null) ? created : outcome.oid;
            case PATCH:
                client.patchObject(outcome.restType, outcome.oid, outcome.modificationXml);
                return outcome.oid;
            default:
                return outcome.oid;
        }
    }

    private static String nameOf(PrismObject<? extends Objectable> object) {
        return (object.getName() != null) ? object.getName().getOrig() : "";
    }

    private static Outcome skip(String restType, String oid, String reason) {
        return new Outcome(Action.SKIP, restType, oid, 0, reason, null, null);
    }

    private static Outcome fail(String restType, String oid, String reason) {
        return new Outcome(Action.FAIL, restType, oid, 0, reason, null, null);
    }
}
