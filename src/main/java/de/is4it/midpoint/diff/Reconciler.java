/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.ArrayList;
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
        final List<String> warnings;  // input advisories + blast radius, for the caller to report
        final List<String> heldBack;  // item paths that differ but the policy's apply= rules leave alone
        final List<String> protectedSkipped; // encrypted values that differ but are never patched

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta,
                List<String> warnings) {
            this(action, restType, oid, changes, detail, modificationXml, createBody, delta, warnings, List.of(),
                    List.of());
        }

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta,
                List<String> warnings, List<String> heldBack, List<String> protectedSkipped) {
            this.action = action;
            this.restType = restType;
            this.oid = oid;
            this.changes = changes;
            this.detail = detail;
            this.modificationXml = modificationXml;
            this.createBody = createBody;
            this.delta = delta;
            this.warnings = List.copyOf(warnings);
            this.heldBack = List.copyOf(heldBack);
            this.protectedSkipped = List.copyOf(protectedSkipped);
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

    private DiffEngine.SmartReferenceCheck smartReferenceCheck(StrategyOptions.Resolved strategy) {
        return SmartReferences.checkFor(strategy, prismContext, client);
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
        } else if (matchByName || createIfAbsent) {
            // --create-if-absent searches too, not just --match-by-name. "Create objects that do not exist
            // yet" has to ask whether they exist: planning a CREATE for an OID-less object without ever
            // looking made every re-run POST it again, and midPoint rejects the duplicate name with 409 —
            // so a pipeline succeeded once and failed on the second run.
            List<PrismObject<? extends Objectable>> matches =
                    LiveLookup.byName(prismContext, client, clazz, restType, LiveLookup.nameOf(desired));
            if (matches.size() > 1) {
                return fail(restType, null, "found " + matches.size() + " objects named '"
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
                .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue,
                        smartReferenceCheck(strategy));
        // The object exists, so an item the policy applies only on create — or only while it is absent — may
        // not be this PATCH's business. Before the in-place check, so a change that is not going to be sent
        // cannot refuse the object.
        ApplyRules.HoldBack held = strategy.apply.holdBack(result.delta(), current);
        if (held.conflict != null) {
            return fail(restType, targetOid, held.conflict);
        }
        List<String> accruedInPlace = new ArrayList<>();
        for (String path : result.accruedInPlaceEditPaths()) {
            if (strategy.apply.modeOf(path) == ApplyRules.Mode.ALWAYS) {
                accruedInPlace.add(path);
            }
        }
        if (!accruedInPlace.isEmpty() && !strategy.allowInplaceMerge) {
            return fail(restType, targetOid, "merge would overwrite accrued value(s) in place at "
                    + String.join(", ", accruedInPlace)
                    + " (use --equivalence real-value or --allow-inplace-merge)");
        }
        if (!result.hasDrift()) {
            LOG.debug("{} {}: IN_SYNC{}", restType, targetOid,
                    held.paths.isEmpty() ? "" : ", left alone: " + held.paths);
            return new Outcome(Action.IN_SYNC, restType, targetOid, 0, "already matches", null, null, null,
                    result.inputWarnings(), held.paths, result.protectedSkippedPaths());
        }
        int changes = result.delta().getModifications().size();
        String modificationXml = DeltaOutput.toModificationXml(prismContext, result.delta());
        String summary = DeltaOutput.toSummary(result.delta()).replace("\n", "; ");
        List<String> warnings = new ArrayList<>(result.inputWarnings());
        for (String path : result.smartReferencePaths()) {
            warnings.add("'" + path + "' is a smart reference; the OID midPoint resolved its filter to is "
                    + "left in place");
        }
        if (result.removalWarning() != null) {
            warnings.add(result.removalWarning().replace("\n", "; "));
        }
        LOG.debug("{} {}: PATCH, {} change(s): {}", restType, targetOid, changes, summary);
        return new Outcome(Action.PATCH, restType, targetOid, changes, summary, modificationXml, null,
                result.delta(), warnings, held.paths, result.protectedSkippedPaths());
    }

    private Outcome planCreate(String restType, String oid, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        if (createIfAbsent) {
            LOG.debug("{} {}: CREATE (create-if-absent)", restType, oid);
            return new Outcome(Action.CREATE, restType, oid, 0, "would create",
                    null, ApplyCommand.createBody(prismContext, desired), null, List.of());
        }
        if (oid != null && !oid.isBlank()) {
            return fail(restType, oid, "does not exist, and --no-create-if-absent rules out creating it");
        }
        if (matchByName) {
            // The search already ran and found nothing, so pointing at --match-by-name — which is on —
            // would read as if the flag were being ignored.
            return skip(restType, null, "no object named '" + LiveLookup.nameOf(desired)
                    + "' on the server, and --no-create-if-absent rules out creating it");
        }
        return skip(restType, null, "no oid, and --no-create-if-absent/--no-match-by-name rule out both "
                + "creating it and matching it by name");
    }

    /** Execute a planned {@code CREATE}/{@code PATCH}. Returns the affected OID (created OID for a POST). */
    String execute(Outcome outcome) throws IOException {
        LOG.trace("execute {} {}/{}", outcome.action, outcome.restType, outcome.oid);
        switch (outcome.action) {
            case CREATE:
                String created = client.createObject(outcome.restType, outcome.createBody);
                return (created != null) ? created : outcome.oid;
            case PATCH:
                client.patchObject(outcome.restType, outcome.oid, outcome.modificationXml,
                        DeltaOutput.carriesSmartReference(outcome.delta));
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
        return new Outcome(Action.SKIP, restType, oid, 0, reason, null, null, null, List.of());
    }

    private static Outcome fail(String restType, String oid, String reason) {
        LOG.debug("{} {}: FAIL — {}", restType, oid, reason);
        return new Outcome(Action.FAIL, restType, oid, 0, reason, null, null, null, List.of());
    }
}
