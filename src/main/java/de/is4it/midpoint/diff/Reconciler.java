/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
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
        final List<RemovalDetector.Removal> removing; // values dropped from the tree that this PATCH deletes
        /**
         * Set when removals for this object could not be judged, and so were not sent. The run must not record
         * its revision then: the next run is the only one that can still see them.
         */
        final boolean removalsDeferred;
        /** Multi-valued items on merge whose live values beyond the tree stay, e.g. {@code inducement ×2}. */
        final List<String> kept;
        /**
         * Items below the top level that a tracked run leaves on the server because the tree never deployed
         * them, e.g. {@code mapping/lifecycleState ×10}.
         */
        final List<String> keptNested;
        /**
         * The lists {@code --equivalence auto} compared by content instead of pairing by natural key, one
         * message each ({@link DiffEngine.DiffResult#keyPairingDeclined}); printed under {@code -v}.
         */
        final List<String> keyPairingDeclined;

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta,
                List<String> warnings) {
            this(action, restType, oid, changes, detail, modificationXml, createBody, delta, warnings, List.of(),
                    List.of());
        }

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta,
                List<String> warnings, List<String> heldBack, List<String> protectedSkipped) {
            this(action, restType, oid, changes, detail, modificationXml, createBody, delta, warnings, heldBack,
                    protectedSkipped, List.of(), false, List.of(), List.of(), List.of());
        }

        private Outcome(Action action, String restType, String oid, int changes, String detail,
                String modificationXml, String createBody, ObjectDelta<? extends Objectable> delta,
                List<String> warnings, List<String> heldBack, List<String> protectedSkipped,
                List<RemovalDetector.Removal> removing, boolean removalsDeferred, List<String> kept,
                List<String> keptNested, List<String> keyPairingDeclined) {
            this.kept = List.copyOf(kept);
            this.keptNested = List.copyOf(keptNested);
            this.keyPairingDeclined = List.copyOf(keyPairingDeclined);
            this.removing = List.copyOf(removing);
            this.removalsDeferred = removalsDeferred;
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
        return plan(desired, strategy, List.of());
    }

    /**
     * As above, deleting in the same PATCH the {@code removals} — values this object's files declared at the
     * last deployed revision and no longer do. One request per object means midPoint applies the change and
     * the removal together: an entry edited without a natural key is never held in both its old and its new
     * form, not even between two requests, and never left that way by a second request that failed.
     */
    Outcome plan(PrismObject<? extends Objectable> desired, StrategyOptions.Resolved strategy,
            List<RemovalDetector.Removal> removals) throws SchemaException, IOException {
        return plan(desired, strategy, removals, true);
    }

    /**
     * As above; with {@code prune} unset ({@code --no-prune}) the removals are not sent. They are still
     * passed, because they are what tells an item the tree deployed and dropped apart from one it never
     * deployed: only the latter is reported as kept.
     */
    Outcome plan(PrismObject<? extends Objectable> desired, StrategyOptions.Resolved strategy,
            List<RemovalDetector.Removal> removals, boolean prune) throws SchemaException, IOException {
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
            if (current != null && !matchByName) {
                // The search ran only to keep a create from duplicating a name. Patching what it found
                // would be matching by name, which is exactly what the flag ruled out.
                return skip(restType, null, "an object named '" + LiveLookup.nameOf(desired) + "' exists, "
                        + "and --no-match-by-name rules out patching it by name; give the file its oid");
            }
        } else {
            current = null;
        }
        return decide(desired, current, restType, strategy, removals, prune);
    }

    /** Decide against a caller-supplied current object (or {@code null} = absent); no server read. */
    Outcome planAgainst(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current,
            StrategyOptions.Resolved strategy) throws SchemaException {
        String restType = restTypeOrNull(desired.getCompileTimeClass());
        if (restType == null) {
            return skip(null, null, unaddressable(desired.getCompileTimeClass()));
        }
        return decide(desired, current, restType, strategy, List.of(), true);
    }

    /** As above, carrying {@code removals} the way {@link #plan} does; no server read. */
    Outcome planAgainst(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current,
            StrategyOptions.Resolved strategy, List<RemovalDetector.Removal> removals, boolean prune)
            throws SchemaException {
        String restType = restTypeOrNull(desired.getCompileTimeClass());
        if (restType == null) {
            return skip(null, null, unaddressable(desired.getCompileTimeClass()));
        }
        return decide(desired, current, restType, strategy, removals, prune);
    }

    private Outcome decide(PrismObject<? extends Objectable> desired, PrismObject<? extends Objectable> current,
            String restType, StrategyOptions.Resolved strategy, List<RemovalDetector.Removal> removals,
            boolean prune) throws SchemaException {
        String oid = desired.getOid();
        if (current != null) {
            String targetOid = (oid != null && !oid.isBlank()) ? oid : current.getOid();
            return planPatch(restType, targetOid, desired, current, strategy, removals, prune);
        }
        // An object that is not there holds nothing to remove.
        return planCreate(restType, oid, desired);
    }

    private Outcome planPatch(String restType, String targetOid, PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> current, StrategyOptions.Resolved strategy,
            List<RemovalDetector.Removal> removals, boolean prune) throws SchemaException {
        DiffEngine.DiffResult result = new DiffEngine(prismContext)
                .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue,
                        smartReferenceCheck(strategy), strategy.keepsUndeclaredNested());
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
        ObjectDelta<? extends Objectable> delta = result.delta();
        List<String> warnings = notesOf(result);
        RemovalJudgement judged = prune ? judgeRemovals(delta, current, removals, warnings)
                : new RemovalJudgement(List.of(), false);
        if (!judged.removing.isEmpty()) {
            ObjectDelta<? extends Objectable> pruning =
                    RemovalDetector.pruneDelta(prismContext, targetOid, judged.removing, current);
            if (pruning != null) {
                for (ItemDelta<?, ?> mod : pruning.getModifications()) {
                    delta.addModification(mod.clone());
                }
            }
        }
        String removesRestricted = strategy.apply.removedWithContainer(delta, current);
        if (removesRestricted != null) {
            return fail(restType, targetOid, removesRestricted);
        }
        DiffEngine.Projection projection = DiffEngine.project(current, delta);
        PrismObject<? extends Objectable> after = projection.after();
        if (projection.failure() != null) {
            warnings.add(DiffEngine.unprojectedNote(projection.failure()));
        }
        // Judged on the whole PATCH, removals included, against what the server would hold afterwards. A change
        // prism cannot project locally is not judged here; midPoint has the last word on it anyway.
        List<String> duplicates = NaturalKeys.introducedDuplicates(current, after);
        if (!duplicates.isEmpty()) {
            return fail(restType, targetOid, NaturalKeys.duplicateRefusal(duplicates), result.keyPairingDeclined());
        }
        List<String> kept = keptOnMerge(desired, after, strategy);
        List<String> heldBack = new ArrayList<>(held.paths);
        List<String> keptNested = keptNested(result.keptNested(), current, after, removals, strategy, heldBack);
        if (!result.hasDrift() && judged.removing.isEmpty()) {
            LOG.debug("{} {}: IN_SYNC{}", restType, targetOid,
                    heldBack.isEmpty() ? "" : ", left alone: " + heldBack);
            // The (empty) delta rides along so a caller rendering this one object — 'diff', or 'deploy
            // --dry-run --format xml' — writes an empty ObjectModificationType rather than nothing.
            return new Outcome(Action.IN_SYNC, restType, targetOid, 0, "already matches", null, null,
                    delta, warnings, heldBack, result.protectedSkippedPaths(), List.of(), judged.deferred, kept,
                    keptNested, result.keyPairingDeclined());
        }
        int changes = delta.getModifications().size();
        String modificationXml = DeltaOutput.toModificationXml(prismContext, delta);
        String summary = DeltaOutput.toSummary(delta).replace("\n", "; ");
        // Counted on the delta being sent, removals included: that is the blast radius the operator confirms.
        String removalWarning = DeltaOutput.removalWarning(delta);
        if (removalWarning != null) {
            warnings.add(removalWarning.replace("\n", "; "));
        }
        LOG.debug("{} {}: PATCH, {} change(s) ({} removal(s) from the tree): {}", restType, targetOid, changes,
                judged.removing.size(), summary);
        return new Outcome(Action.PATCH, restType, targetOid, changes, summary, modificationXml, null,
                delta, warnings, heldBack, result.protectedSkippedPaths(), judged.removing, judged.deferred,
                kept, keptNested, result.keyPairingDeclined());
    }

    /**
     * What a {@code merge} leaves standing that the tree does not declare, counted after this PATCH — so the
     * values it removes are not among them. Replace would have deleted these; under merge they stay without a
     * word unless someone says so, and an entry an administrator added to a list the tree means to own is
     * exactly the drift that must not go unseen.
     */
    private static List<String> keptOnMerge(PrismObject<? extends Objectable> desired,
            PrismObject<? extends Objectable> after, StrategyOptions.Resolved strategy) {
        if (!strategy.multiValue.isMergeActive() || after == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (DiffEngine.LiveOnly liveOnly : DiffEngine.liveOnlyValues(desired, after)) {
            if (strategy.multiValue.isMerge(liveOnly.path())) {
                kept.add(liveOnly.pathText() + " ×" + liveOnly.count());
            }
        }
        return kept;
    }

    /**
     * What a tracked run leaves standing below the top level because the tree never deployed it: the live
     * values the diff kept ({@link DiffEngine.DiffResult#keptNested}) that the server still holds after this
     * PATCH, minus the items the tree <em>did</em> deploy — those are the removals' business, and are either
     * removed or left with a note of their own ("changed after it was deployed"). Counted per id-free item
     * path, so ten mappings an administrator gave a {@code lifecycleState} read as one entry,
     * {@code mapping/lifecycleState ×10}.
     *
     * <p>An item a policy {@code apply=} rule restricts is not counted either: the tree may well have deployed
     * it, and its removal was withheld by the rule before it got here, so "it never deployed them" would send
     * the reader looking in the wrong place. It goes to {@code heldBack} instead, named with its rule, which is
     * where every other difference the policy leaves alone is reported.
     */
    private static List<String> keptNested(List<DiffEngine.KeptNested> keptByDiff,
            PrismObject<? extends Objectable> current, PrismObject<? extends Objectable> after,
            List<RemovalDetector.Removal> removals, StrategyOptions.Resolved strategy, List<String> heldBack) {
        if (keptByDiff.isEmpty() || after == null) {
            return List.of();
        }
        List<ItemPath> deployedPaths = new ArrayList<>();
        for (RemovalDetector.Removal removal : removals) {
            ItemPath path = removal.livePath(current);
            if (path != null) {
                deployedPaths.add(path);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DiffEngine.KeptNested kept : keptByDiff) {
            if (deployedPaths.stream().anyMatch(path -> path.equivalent(kept.path()))) {
                continue;
            }
            ApplyRules.Mode mode = strategy.apply.modeOf(kept.path().removeIds());
            if (mode != ApplyRules.Mode.ALWAYS) {
                String label = idFreeText(kept.path()) + " (apply=" + mode.keyword + ")";
                if (!heldBack.contains(label)) {
                    heldBack.add(label);
                }
                continue;
            }
            Item<?, ?> item = after.findItem(kept.path());
            if (item == null) {
                continue;
            }
            int held = 0;
            for (PrismValue value : kept.values()) {
                if (item.getValues().stream().anyMatch(v -> EquivalenceStrategy.REAL_VALUE.equals(v, value))) {
                    held++;
                }
            }
            if (held > 0) {
                counts.merge(idFreeText(kept.path()), held, Integer::sum);
            }
        }
        List<String> kept = new ArrayList<>();
        counts.forEach((path, count) -> kept.add(path + " ×" + count));
        return kept;
    }

    private static String idFreeText(ItemPath path) {
        List<String> names = new ArrayList<>();
        for (Object segment : path.removeIds().getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        return String.join("/", names);
    }

    /** Which of an object's removals its PATCH carries, and whether any had to be left for a later run. */
    private record RemovalJudgement(List<RemovalDetector.Removal> removing, boolean deferred) {
    }

    /**
     * Narrow an object's removals to the ones its PATCH should carry.
     *
     * <p>This is the ownership guard. A removal deletes only a value the server holds in exactly the form midgie
     * deployed it: one that has since been changed or removed by someone else is left alone, with a note. And it
     * deletes only what the diff does not already take care of, which is judged against a <em>projection</em> —
     * the live object with this object's diff applied locally. A value the tree edited rather than dropped also
     * shows up as "no longer declared" (its old form is gone from the file), and deleting that old form beside a
     * diff that updates the entry in place would delete the entry being updated; under {@code replace} the diff
     * deletes the value itself, and a second delete of it would be redundant. The projection answers both,
     * without a round trip, which is what the post-apply re-read this replaces was for.
     */
    private RemovalJudgement judgeRemovals(ObjectDelta<? extends Objectable> delta,
            PrismObject<? extends Objectable> current, List<RemovalDetector.Removal> removals,
            List<String> notes) {
        if (removals.isEmpty()) {
            return new RemovalJudgement(List.of(), false);
        }
        DiffEngine.Projection projection = DiffEngine.project(current, delta);
        PrismObject<? extends Objectable> projected = projection.after();
        if (projected == null) {
            // Without the projection there is no telling which removals the diff already covers, and guessing
            // could delete the entry the diff is updating. Leave them all for a run that can tell.
            notes.add(removals.size() + " value(s) dropped from the tree were not removed this run: the change "
                    + "could not be projected onto the live object (" + projection.failure() + "). The next run "
                    + "offers them again");
            return new RemovalJudgement(List.of(), true);
        }
        List<RemovalDetector.Removal> removing = new ArrayList<>();
        for (RemovalDetector.Removal removal : removals) {
            if (!removal.isPrunable()) {
                continue; // reported by the caller, which also keeps the revision from moving past it
            }
            if (RemovalDetector.heldValue(current, removal) == null) {
                notes.add(RemovalDetector.holdsOtherValue(current, removal)
                        ? "leaving " + removal.path + " alone — the server holds a different value than the one "
                                + "removed from " + removal.sourceFile + " (changed on the server after it was "
                                + "deployed)"
                        : "leaving " + removal.path + " alone — the server no longer holds the value that was "
                                + "removed from " + removal.sourceFile + " (already changed or removed)");
            } else if (RemovalDetector.heldValue(projected, removal) == null) {
                LOG.trace("{} {} is already taken care of by the diff", removal.objectLabel, removal.path);
            } else {
                removing.add(removal);
            }
        }
        return new RemovalJudgement(removing, false);
    }

    /**
     * The advisories a diff produced, worded for the per-object report. Built in one place so a single object
     * reads the same whichever command reconciled it — 'diff' used to name paths the server withheld and
     * merge fallbacks that 'deploy' passed over in silence.
     */
    private static List<String> notesOf(DiffEngine.DiffResult result) {
        List<String> notes = new ArrayList<>(result.inputWarnings());
        for (String path : result.incompleteSkippedPaths()) {
            notes.add("the server did not return '" + path + "' (it came back incomplete), so its values are "
                    + "unknown, not empty — midgie will not patch an item it cannot see. Check the account's "
                    + "read authorization for that item");
        }
        for (String path : result.mergeFallbackPaths()) {
            notes.add("'" + path + "' diffs to a whole-value replace; merge does not apply there, so it was "
                    + "left as replace");
        }
        for (String path : result.smartReferencePaths()) {
            notes.add("'" + path + "' is a smart reference; the OID midPoint resolved its filter to is "
                    + "left in place");
        }
        return notes;
    }

    /** Serialize the desired object for a create POST, stripping operational/metadata items midPoint owns. */
    static String createBody(PrismContext prismContext, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        // On a copy: this runs in the read-only preview pass that deploy does before the real one. Stripping
        // the caller's own object there changed what a later fragment of the same object diffed to, so the
        // emitted PATCH depended on whether --yes had skipped the preview.
        PrismObject<? extends Objectable> body = desired.clone();
        body.getValue().removeOperationalItems();
        return XmlPretty.tidyRoot(prismContext.xmlSerializer().serialize(body));
    }

    private Outcome planCreate(String restType, String oid, PrismObject<? extends Objectable> desired)
            throws SchemaException {
        if (createIfAbsent) {
            LOG.debug("{} {}: CREATE (create-if-absent)", restType, oid);
            return new Outcome(Action.CREATE, restType, oid, 0, "would create",
                    null, createBody(prismContext, desired), null, List.of());
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

    /**
     * The one change a planned outcome stands for, as {@code --format} names it: the PATCH body (empty when
     * the object is in sync), or for an object that does not exist yet the POST body — whatever the format,
     * since a create has no delta to summarise. {@code null} for a unit that was skipped or failed.
     */
    String render(Outcome outcome, String format) throws SchemaException {
        switch (outcome.action) {
            case CREATE:
                return outcome.createBody;
            case PATCH:
            case IN_SYNC:
                return DeltaOutput.render(prismContext, outcome.delta, format);
            default:
                return null;
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
        return fail(restType, oid, reason, List.of());
    }

    /** As above, carrying the lists auto compared by content, which is what {@code -v} explains a refusal with. */
    private static Outcome fail(String restType, String oid, String reason, List<String> keyPairingDeclined) {
        LOG.debug("{} {}: FAIL — {}", restType, oid, reason);
        return new Outcome(Action.FAIL, restType, oid, 0, reason, null, null, null, List.of(), List.of(),
                List.of(), List.of(), false, List.of(), List.of(), keyPairingDeclined);
    }
}
