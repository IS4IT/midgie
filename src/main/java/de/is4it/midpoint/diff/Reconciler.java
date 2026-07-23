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
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Reconciles a single desired object (an apply unit) against a live server, reusing the offline
 * {@link DiffEngine}. It <em>plans</em> the action — resolve the current object (search by OID, or a
 * type+name search when {@code --match-by-name} is set), diff the fragment against the caller's strategy,
 * and decide {@code CREATE}/{@code PATCH}/{@code IN_SYNC}/{@code SKIP}/{@code FAIL} — and then, separately,
 * <em>executes</em> it (POST/PATCH). Planning is read-only. {@link #planAgainst} decides against a caller-
 * supplied current object (no fetch), which the {@code --plan} projection uses to advance a local copy of
 * the tree. All HTTP stays in {@link MidpointRestClient}; this class only orchestrates.
 */
final class Reconciler {

    private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);

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
        final ObjectDelta<? extends Objectable> delta; // the computed delta for a PATCH (for projection)

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta) {
            this.action = action;
            this.restType = restType;
            this.oid = oid;
            this.changes = changes;
            this.detail = detail;
            this.modificationXml = modificationXml;
            this.createBody = createBody;
            this.delta = delta;
        }
    }

    private final PrismContext prismContext;
    private final MidpointRestClient client;
    private final boolean createIfAbsent;
    private final boolean matchByName;

    Reconciler(PrismContext prismContext, MidpointRestClient client, boolean createIfAbsent, boolean matchByName) {
        this.prismContext = prismContext;
        this.client = client;
        this.createIfAbsent = createIfAbsent;
        this.matchByName = matchByName;
    }

    /** Decide what to do with {@code desired}: resolve its current state from the server, then decide. */
    Outcome plan(PrismObject<? extends Objectable> desired, StrategyOptions.Resolved strategy)
            throws SchemaException, IOException {
        Class<?> clazz = desired.getCompileTimeClass();
        String restType = restTypeOrNull(clazz);
        if (restType == null) {
            return skip(null, null, unaddressable(clazz));
        }

        String oid = desired.getOid();
        PrismObject<? extends Objectable> current;
        if (oid != null && !oid.isBlank()) {
            current = LiveLookup.byOid(prismContext, client, clazz, restType, oid);
        } else if (matchByName) {
            List<PrismObject<? extends Objectable>> matches =
                    LiveLookup.byName(prismContext, client, clazz, restType, LiveLookup.nameOf(desired));
            if (matches.size() > 1) {
                return fail(restType, null, "--match-by-name found " + matches.size() + " objects named '"
                        + LiveLookup.nameOf(desired) + "'; refusing an ambiguous match");
            }
            current = matches.isEmpty() ? null : matches.get(0);
        } else {
            current = null;
        }
        return decide(desired, current, restType, strategy);
    }

    /** Decide against a caller-supplied current object (or {@code null} = absent); no server read. */
    Outcome planAgainst(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current,
            StrategyOptions.Resolved strategy) throws SchemaException {
        String restType = restTypeOrNull(desired.getCompileTimeClass());
        if (restType == null) {
            return skip(null, null, unaddressable(desired.getCompileTimeClass()));
        }
        return decide(desired, current, restType, strategy);
    }

    private Outcome decide(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current,
            String restType, StrategyOptions.Resolved strategy) throws SchemaException {
        String oid = desired.getOid();
        if (current != null) {
            String targetOid = (oid != null && !oid.isBlank()) ? oid : current.getOid();
            return planPatch(restType, targetOid, desired, current, strategy);
        }
        return planCreate(restType, oid, desired);
    }

    private Outcome planPatch(String restType, String targetOid, PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> current, StrategyOptions.Resolved strategy) throws SchemaException {
        DiffEngine.DiffResult result = new DiffEngine(prismContext)
                .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);
        if (!result.accruedInPlaceEditPaths().isEmpty() && !strategy.allowInplaceMerge) {
            return fail(restType, targetOid, "merge would overwrite accrued value(s) in place at "
                    + String.join(", ", result.accruedInPlaceEditPaths())
                    + " (use --equivalence real-value or --allow-inplace-merge)");
        }
        if (!result.hasDrift()) {
            LOG.debug("{} {}: IN_SYNC", restType, targetOid);
            return new Outcome(Action.IN_SYNC, restType, targetOid, 0, "already matches", null, null, null);
        }
        int changes = result.delta().getModifications().size();
        String modificationXml = DeltaOutput.toModificationXml(prismContext, result.delta());
        String summary = DeltaOutput.toSummary(result.delta()).replace("\n", "; ");
        LOG.debug("{} {}: PATCH, {} change(s): {}", restType, targetOid, changes, summary);
        return new Outcome(Action.PATCH, restType, targetOid, changes, summary, modificationXml, null,
                result.delta());
    }

    private Outcome planCreate(String restType, String oid, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        if (createIfAbsent) {
            LOG.debug("{} {}: CREATE (create-if-absent)", restType, oid);
            return new Outcome(Action.CREATE, restType, oid, 0, "would create",
                    null, ApplyCommand.createBody(prismContext, desired), null);
        }
        if (oid != null && !oid.isBlank()) {
            return fail(restType, oid, "does not exist (pass --create-if-absent to create it)");
        }
        return skip(restType, null, "no oid; pass --create-if-absent to create, or --match-by-name to match by name");
    }

    /** Execute a planned {@code CREATE}/{@code PATCH}. Returns the affected OID (created OID for a POST). */
    String execute(Outcome outcome) throws IOException {
        LOG.debug("execute {} {}/{}", outcome.action, outcome.restType, outcome.oid);
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

    private static String restTypeOrNull(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        try {
            return ObjectTypes.getRestTypeFromClass(clazz);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String unaddressable(Class<?> clazz) {
        return (clazz == null) ? "not a recognized midPoint object type"
                : "no REST endpoint for " + clazz.getSimpleName();
    }

    private static Outcome skip(String restType, String oid, String reason) {
        LOG.debug("{} {}: SKIP — {}", restType, oid, reason);
        return new Outcome(Action.SKIP, restType, oid, 0, reason, null, null, null);
    }

    private static Outcome fail(String restType, String oid, String reason) {
        LOG.debug("{} {}: FAIL — {}", restType, oid, reason);
        return new Outcome(Action.FAIL, restType, oid, 0, reason, null, null, null);
    }
}
