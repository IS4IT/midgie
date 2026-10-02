# midgie — design

A standalone, schema-aware CLI that computes the **minimal `itemDelta`** between two midPoint
objects and (optionally) applies it through the model REST API. Core value: a thin wrapper around
midPoint's own `PrismObject.diff` that emits the exact `ObjectModificationType` XML the REST `PATCH`
endpoint consumes.

This file is the build-ready brief. It is self-contained — you do not need the conversation it came
from. Verify any API detail against the midPoint source at `/Volumes/Daten/git/midpoint`.

## Why this exists

midPoint has no clean, re-runnable, *field-level* config-promotion path:

- `post-initial-objects` is one-shot (gated by a `.done` rename) — seeding, not update.
- `ninja import -O` is a raw repository write — bypasses model logic / authorizations / audit, and
  leaves a running cluster on stale caches until restart.
- REST `PUT` / ninja `-O` do a **full replace** — they reset everything not in the source XML, which
  wipes accumulated runtime state (assignments, links, metadata) on any object that isn't pure config.

What's missing is a tool that changes **only the fields you manage** and preserves the rest. midPoint
already ships the engine (`PrismObject.diff`); this CLI is the glue. The offline two-file diff mode is
independently useful (drift detection, migration deltas, dry-run, review), which is what makes it worth
open-sourcing rather than burying in a pipeline.

## Engine (all confirmed against midPoint 4.10.x)

The core is ~5 lines:

```java
// Base schema is loaded from the classpath — no Spring, no DB, no running midPoint.
// Extension XSDs (--schema-dir / --schema / read from the live server) are registered afterwards,
// in a single registerDynamicSchemaExtensions batch -- see "Schema" below.
PrismContext ctx = new MidPointPrismContextFactory().createInitializedPrismContext();

PrismObject<?> desired = ctx.parseObject(desiredFile);
PrismObject<?> live    = ctx.parseObject(liveFile);          // file mode; or GET over REST in live mode

// Direction matters: before.diff(after) yields the delta that, applied to `before`, produces `after`
// (prism contract, proven by TestParseDiffPatch: userBefore.diff(userAfter) then applyTo(userBefore)
// == userAfter). The emitted delta is the REST PATCH body applied to the LIVE server object to drive
// it to the desired state, so it must transform live -> desired. Hence live.diff(desired), NOT the
// reverse — desired.diff(live) would emit a PATCH that overwrites your config with the stale live value.
ObjectDelta<?> delta   = live.diff(desired, EquivalenceStrategy.REAL_VALUE); // ignores metadata/operational
ObjectModificationType mod = DeltaConvertor.toObjectModificationType(delta); // == the REST PATCH body
```

Confirmed API facts (source references are into `/Volumes/Daten/git/midpoint`):

| Element | FQN / note |
|---|---|
| `MidPointPrismContextFactory` | `com.evolveum.midpoint.schema.MidPointPrismContextFactory` — ctors `()` and `(File extraSchemaDir)` (infra/schema:56, :60); `createInitializedPrismContext()` (infra/schema:179) throws `SchemaException, SAXException, IOException` |
| `PrismContext.parseObject(File)` | returns `PrismObject<T>` (see `TestParsePasswordPolicy`, `TestParseGenericObject`). Modern form: `ctx.parserFor(file).xml().parse()` |
| `PrismObject.diff(other, EquivalenceStrategy)` | returns `ObjectDelta<T>` (`com.evolveum.midpoint.prism.delta.ObjectDelta`); see `TestDiffEquals:456` |
| `EquivalenceStrategy.REAL_VALUE` | ignores operational/metadata items **and container ids** when comparing values — the default for config diffing (proven in `TestParseDiffPatch`). It does *not* ignore ids when *pairing*: two list values that both carry an id are paired by it under every strategy, and then edited one into the other. That is right for a file exported from the target server and wrong for one from another server, which is why `ContainerIds.trustedOnly` checks a file's ids against the live list first (see "Container ids from another server"). The `_CONSIDER_DIFFERENT_IDS[_NATURAL_KEYS]` variants turn container-id / natural-key matching **on**, which makes the diff *pair* multi-valued entries and edit them in place; id-agnostic plain `REAL_VALUE` instead emits clean whole-value add/delete. The default `--equivalence auto` uses plain `REAL_VALUE` for that reason wherever a container's desired values do not all fill in a natural key, and natural-key pairing where they do (see "Strategy flags"); the id-considering variants are still selectable via `--equivalence` for exploration (`natural-keys`, the experimental preset, warns) |
| `DeltaConvertor.toObjectModificationType(ObjectDelta)` | `com.evolveum.midpoint.schema.DeltaConvertor` (infra/schema:237) → `com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType`. Also `toObjectDeltaType` (:259) and `toItemDeltaTypes` (:429) |
| `Item.diffModifications(other, strategy)` | returns `List<ItemDelta>` — useful for a **scoped** per-item diff (see `TestDiffEquals:565`) |

prism itself (`com.evolveum.midpoint.prism.*`: `PrismObject`, `PrismContext`, `ObjectDelta`,
`EquivalenceStrategy`) is an **external** library pulled transitively by `midpoint-schema`; it is not in
the midPoint source tree.

**Safety guards** applied around that core (`DiffEngine`):
- *Type / OID cross-check* — the diff refuses two objects of different compile-time types, and (new) two
  objects that both carry an OID when the OIDs differ, so a wrong `--oid` or wrong file cannot silently
  produce a delta that rewrites one object into another. A desired object with no OID is an unbound
  template and is allowed (the live OID governs).
- *Protected/encrypted values* — `ProtectedStringType` item deltas (e.g. `credentials/password/value`) are
  dropped from the delta and their paths reported on stderr: the ciphertext re-encrypts on every fetch, so
  diffing it yields spurious deltas *and* would leak the secret into the PATCH. midPoint owns credential
  rotation; a real password change goes through its own API, not a blind PATCH of stale ciphertext.
  (Detection is by schema type, a protected real value, or — for a value with no definition, such as a
  connector's `bindPassword`, which midPoint returns as `<t:encryptedData>` without `xsi:type` and whose
  connector schema the diff does not have — a raw value made of `ProtectedDataType`'s elements alone. A
  protected value nested inside a larger added/deleted container is not walked — rare, and noted in the code.)
- *Fragment union* — several files may describe one object, and they are combined into a single desired
  object before anything is diffed (`FragmentUnion`, built at group time by `DeployObject`). The apply unit is
  therefore the **object**, not the file. This is what makes cross-file composition correct *by default*:
  reconciled file by file, two files each contributing a `panel` produced two PATCHes and the second replaced
  what the first wrote (the "last-one-wins" clobber the grouping used to warn about), and the only workaround
  — `multivalue=merge` — bought that back at the price of removals, since under merge a value deleted from a
  file yields an empty delta and survives unnoticed. Combined first, plain `replace` writes both files'
  contributions and a deleted entry is still a deletion. Entries of a multi-valued container are paired **by
  container id, else by the schema's `a:naturalKey`** (`NaturalKeys.match`, shared with the diff's
  accrued-value guard so both answer "is this the same entry?" identically): two files that each declare
  `objectDetailsPage[type=UserType]` must yield *one* page holding both their panels. Note where that puts
  natural-key matching — file against file, both sides the operator's own, visible in `--plan`, with nothing
  accrued at risk. Values that can be paired by neither are appended, de-duplicated by real value, so the same
  entry written into two files collapses while two merely similar ones are kept apart. Operational items are
  never merged in. A single-valued item that two files disagree on is an error: the grouping check catches
  those it can reach, and the union catches the ones only visible once two entries have been paired (a
  contradiction *inside* a keyed container value), since picking one would make the result depend on the order
  the files were read in.
- *Items the server withholds* — midPoint does not return some items unless asked, marking them `incomplete="true"`; `LookupTableType/row` is the case that bites, `jpegPhoto` another. "Not sent" must never be read as "empty", or a lookup table re-proposes its whole contents every run and rows nobody can see are quietly in line for deletion. Which items those are is not in the bundled schema (midPoint decides it in its repository layer), so `LiveLookup` follows the server's own marker: an object that comes back carrying one is re-read with `?include=<path>`, and only such an object pays the second request. Anything still incomplete after that is one the server will not give up at all, and `DiffEngine.stripIncompleteItems` drops its item deltas and reports the paths — the same shape as protected values, for the same reason.
- *Delete detection (three-way merge)* — a two-way diff cannot express a removal: a value dropped from a
  desired file simply stops being mentioned, so under `merge` the delta is empty and the value survives while
  `diff` reports no differences. `deploy` therefore also compares desired against **last-applied**, recovered from git: the only
  persisted state is, **per file**, the git blob id of the content last applied (`FileTracking`, rows in
  `midgie-state`), and `git cat-file blob <id>` gives the content back. A blob id is content-addressed — the
  same in every clone and worktree — and changes only when the file does, so a run writes only the rows of
  files that changed. Per file rather than per tree because the tree is whatever path a run deploys: a
  subfolder or a single file keyed by its own path would be a second, independent record of the same files,
  and one per-tree revision cannot express that part of a tree was applied later than the rest. Last-applied
  for an object is the combination of its files, each at its own recorded blob, by three rules
  (`FileTracking.history`): a file of this run contributes its row's blob; a file whose row remains but which
  exists nowhere contributes too (the old path of a rename — found wherever it lies, so a rename plus a drop
  deployed at the new path alone still removes the drop), counted in scope for orphan reporting only inside
  the run; a file that exists outside the run is on neither side, so deploying part of an object never reads
  the rest as removed (and `DeployCommand` notes the split). Rows of gone files are deleted once a run has
  compared against them; an orphan whose object a row shows elsewhere is reported as moved and its row kept for
  the deploy of the new place. Rows are written per row (`SyncState.apply`: add, replace by container id,
  delete by id) so two concurrent deploys do not overwrite each other's rows. Earlier versions' per-tree rows
  (a bare commit sha) are read once for files without a row (`git show <rev>:<path>` plus `deletedSince` for
  files deleted since) and deleted when a run covers their path. A `deploy:` row per deployed path records the
  last run — when, commit, account, midgie version, and the summary counts — for every real deploy that sent
  something. File rows move forward **per object**: only the files of an object this run applied completely
  (created, patched or in sync, no removal deferred or inexpressible — `DeployCommand.appliedUnits`) get new
  rows; a gone file's row is deleted only if the object that compared against it was applied, and an earlier
  version's tree row only if no object under it was left out, since a left-out object may still need it. A blob or commit the
  repository no longer holds stops detection for the run, as an unreachable revision did. Detection that fails
  for one object is never read as "nothing removed" (`RemovalDetector.unjudged`, reported by `warn`, which
  `--quiet` does not silence): an object whose last-applied content no longer parses, or whose comparison
  throws, keeps its rows so the next run tries again; one whose last-applied files contradict each other (a
  partial deploy moved one on) lets its rows move to this run's consistent content, since that history would
  fail the same way every run. The removal set is what last-applied holds and desired no longer does, compared item by item (`RemovalDetector.collect`, see "Ownership below the top level" below). It works per **object**
  on both sides — the union of the files describing it now against the union of the files that described it
  as last applied, deleted ones included — which is what makes it correct for objects assembled from several
  files: a deleted fragment yields exactly the values no surviving file still declares, and a value merely
  *moved* between two files of one object never appears at all, both falling out of the comparison rather
  than needing a subtraction pass to undo. The object itself is never a deletion candidate
  (`SystemConfiguration` is `indestructible` anyway); one whose every file has gone is reported as an orphan.
  Last-applied files are one object when they share its OID or its type and name, whatever order they come in
  (a file naming it by OID, one by name and one by both merge into one group), but never across two different
  OIDs: a new object given an old one's name is not paired with the old one's history, and the old one is
  reported as gone. The `apply` rules a removal is filtered by are the object's as the tree has it now, so a
  rule naming a renamed object by its new name covers the removals of the renaming commit.
  Each removal still names the individual file it came from, which is what an operator reviewing a prune acts
  on. Removed by default (`--no-prune` only reports the count). State lives in a `midgie-state` `LookupTableType` created solely
  by `midgie init --create-state` — never implicitly, so midgie leaves no unexplained object behind. The rows
  a run writes are built against a fresh read of the table, not the copy read at the start of the run, and a
  write that fails is tried once more on another fresh read, so a row another deploy moved in between does
  not fail the whole PATCH. Where two runs added the same key, the first row is read and written and the
  others are removed the next time that key is written. A run without `--yes` whose preview finds nothing to
  change records its rows like one with it.

  The detected removals are applied **in each object's own PATCH**: detection runs before the
  apply pass, each `Removal` carries its `DeployObject`, and `Reconciler.plan` merges that object's deletes
  into its delta. One request per object means midPoint applies change and removal together, so an entry
  edited without a natural key under `merge` (add new, delete old) is never held in both forms — not between
  two requests, and not after a failed second one — and there is one confirmation, one plan file and one
  rollback inverse per object. It used to be a second pass after the tree, gated by its own prompt, with
  the ownership guard (`stillLive`) re-reading each object afterwards; that guard is now
  `Reconciler.judgeRemovals`, evaluated against a *projection* — the live object with the diff applied
  locally (`ObjectDelta.applyTo` on a clone). A removal is sent only if the server holds the value now
  (else it was changed or removed by someone else: note, dropped) and still would after the diff (else the
  diff covers it: an in-place edit of the same entry, or a `replace` that deletes it — sending the delete
  too would remove the entry being updated). If the projection fails, the object's removals are deferred
  and its files' rows are not moved forward. The delete takes the server's own value (`RemovalDetector.heldValue`),
  container id included, instead of the id-less copy from git. The delta is assembled only from values
  that were in last-applied (`RemovalDetector.pruneDelta`), which is what makes it structurally unable to
  reach accrued or foreign state; an object's `name`, and any mandatory item, is never a candidate. A
  removal with no item definition fails the run and keeps that object's rows where they are. `init --write-policy` seeds a starter policy holding only what the
  defaults leave open. The defaults settle matching (`--equivalence auto`) and unmentioned items (`--scope
  present`), and `--multivalue auto` settles merge-vs-replace by tracking; what none can settle is who owns a
  list the server holds more of than the tree. So each object is compared with its live copy
  (`DiffEngine.liveOnlyValues`, the same "declared by id, key or content" test as the partial-declaration
  warning), and each such list is suggested *against* the default in effect: a commented-out
  `multivalue=replace` on a tracked tree (merge keeps the values; owning the list deletes them, the operator's
  call), an active `multivalue=merge` on an untracked one (replace would delete them next deploy). It used to
  suggest merge for every container carrying a schema natural key, which was the schema's view rather than
  the server's and said nothing about whether a rule changed anything. `apply=on-create` candidates are
  offered only for items a file declares, and commented examples cover the knobs no suggestion used. The state object carries `indestructible`, a `description`/`documentation`
  aimed at an admin who has never heard of the tool, `subtype=midgie`, and a `lifecycleState` an admin can
  set to `archived` to switch tracking off. A row's blob id (or an earlier version's tree-row commit) is validated as a bare git object id before it
  reaches git's argv — it arrives over the network — and one the repository cannot resolve stops detection,
  since an unreachable blob must not read as "nothing was removed". Row keys are the repository's root commit
  (along first parents, so merging an unrelated history does not change it) plus the file's path within it:
  content-addressed, so every clone agrees regardless of its remotes. `--app` replaces the root commit in the
  keys, not the need for history; shallow clones are refused either way, since their apparent root is only the grafted
  boundary. `GitRevision`, `DeploymentIdentity`, `FileTracking`, `SyncState`, `RemovalDetector`, `InitCommand`.

  **Ownership below the top level.** Merge and replace decide which values of a multi-valued item survive; neither says anything about what is *inside* a value the diff pairs — one `mapping` of a template matched by its `name`, or a single-valued container such as `archetypePolicy/display`. There the two-way diff deleted whatever the server's copy held beyond the file, whoever had put it there; the case that surfaced it was ten `delete mapping/N/lifecycleState` because an administrator had set `lifecycleState` on the server. On a tracked run (the condition that settles `--multivalue auto` to merge: `tracking.canRecord() && !detectionBroken`, recorded on the strategy by `StrategyOptions.Resolved.settleTracking` whatever `--multivalue` says) and under `--scope present`, the rule list values already follow — remove only what midgie deployed, in the form it deployed it — applies at every depth. `DiffEngine.keepUndeclaredNested` drops each delete of live values of an item below the top level that desired does not declare at that path, and hands back what it kept. A delta path carries the *live* container ids (`mapping/4/lifecycleState`), so each id is mapped back to the desired value it was paired with, by id or natural key (`NaturalKeys.match`); an item desired declares keeps its delta (a replace, or list membership for merge/replace to judge), and so does every top-level delta and the whole-value add/delete that `auto`'s real-value half emits for a list without keys, since its path names the list itself. It runs before `applyMerge` and `retainItemsPresentIn`, which would otherwise swallow some of those deletes silently. What the tree deployed and then dropped comes back as a removal from the three-way comparison and is deleted there; what it never deployed stays and joins the per-object "keeps live value(s)" note (`Reconciler.keptNested`: the kept values the projected object still holds, minus items a removal of this object addresses, counted per id-free path). A removal whose value the server changed after the deploy is left alone with a note of its own. `--no-prune` withholds the removals as before; they are still passed to the planner, only so that an item the tree dropped is not reported as "never deployed". An untracked run, an offline diff and `--scope full` are unchanged, and a first deploy of a tracked tree has no last-applied, so it removes nothing below the top level.

  **The whole-value hazard, and why removals are item-granular.** The removal set used to be the delete side of a `real-value` diff with last-applied in the live slot. `real-value` matches values of a multi-valued container by content, so a mapping that only lost its `lifecycleState` came out as "the whole old mapping was removed". That was harmless while the two-way diff rewrote the mapping in place anyway: the projection in `judgeRemovals` no longer found the old form and the removal was dropped as already handled. Once the diff leaves undeclared nested items alone, the live mapping can still equal last-applied's exactly, and the same removal would delete the entire mapping. So `RemovalDetector` now walks desired against last-applied itself, pairing container values as the diff does — by container id, else by natural key — and reports what a paired value lost item by item: a `Removal` carries the chain of keyed values it lies in (`Removal.Step`, last-applied's copy of each), an id-free `itemPath` for the policy's `apply` rules, and a report path such as `mapping[name=Given name]/lifecycleState`. `Removal.livePath` finds each step again in the live object (git copies usually carry no ids) and addresses the delete to the server's own value, whose id it keeps; when last-applied's ids contradict the server's (`ContainerIds.contradiction`, the file was exported from another server), a step is paired by natural key or content instead, never by an id that names another entry here. A value with no counterpart — a mapping dropped entirely, an edited list value without a key or id — is still removed whole, and a single-valued property desired still declares is a replace in the diff, never a removal, exactly as before. prism could not do this pairing for us: its natural-key match requires an id on the "before" side, which a git copy does not have.

  A related prism quirk had to be fixed for the same feature: in a pair matched by natural key, prism builds the path of an item only desired holds from the *desired* value, which has no id, so an added `lifecycleState` came out as `mapping/lifecycleState` and could not be resolved. The natural-keys pass therefore diffs a copy of desired whose keyed values have adopted the ids of the live values they pair with (`DiffEngine.adoptLiveIds`); the real-value pass never sees those ids.
- *Smart references* — a reference may name its target by search filter instead of OID, which is how a
  resource file stays portable across instances whose connector OIDs differ. midPoint resolves the filter
  **at import** and stores the OID; the file still carries the filter. Diffed literally that is a change, and
  the PATCH strips the OID — and a modify does not re-resolve the filter, so the object ends up pointing at
  nothing (observed: a CSV resource broken exactly this way). So `DiffEngine` drops the paired
  add-filter/delete-resolved delta when the live OID satisfies the desired filter, and any PATCH that does
  set a reference by filter is sent with `?options=reevaluateSearchFilters` so the server rebinds it.
  `--smart-refs accept|verify|resolve` chooses how much evidence is required; `verify` runs the filter
  against the server, which is the only way to settle it when midPoint dropped the filter after resolving.
  The judgement keys on the prism *value kind*, never on an element name — only a `PrismReferenceValue` has
  a filter, so the schema's many unrelated `SearchFilterType` fields cannot be mistaken for one.
  `SmartReferences`, `DiffEngine.SmartReferenceCheck`.

  That strip sees a reference only when its difference is a delta on the reference itself. Inside an entry
  of a list compared by content — an id-less `inducement` whose `targetRef` or `construction/resourceRef` is
  a filter — the whole entry differs instead, and the diff deleted the resolved inducement and added one
  naming nothing, on every run. `NestedSmartReferences.resolve` runs before the diff: each such entry is tried
  against the live entries with its filter-only references (directly in it or through single-valued
  containers) replaced by the live references the same `SmartReferenceCheck` accepts; if exactly one live
  entry then equals it, a copy of desired takes those references and the entry is in sync. Several matches
  mean the server kept no filter to tell them apart; the entry stays a change and `-v` suggests `verify`.
  Any entry still sent whole carries its filter, and `DeltaOutput.carriesSmartReference` and
  `PlanStore.setsReferenceByFilter` both look inside added container values, so such a PATCH goes out with
  `reevaluateSearchFilters` — live and on `--apply-plan` replay.
- *Blast-radius reporting and input checks* — a partial fragment can silently destroy live state, so the danger is reported before any write (advisory, never blocking; `--quiet` silences it). Every summary line carries the number of values it removes, and a removing delta prints a prominent `WARNING: this change REMOVES n values`. On top of that, `DiffEngine.findInputWarnings` statically walks desired against live, pairing keyed container values, and flags (a) a value of a keyed multi-valued container that does not carry its `a:naturalKey` — it cannot be matched, so it degrades to a whole-value change — and (b) a keyed container that desired only partially declares while that path is on `replace`, naming `multivalue=merge` as the fix. Only keyed containers are checked, which keeps it precise and leaves the key-matched merge recipe warning-free. Surfaced through `DiffResult.inputWarnings()` / `removalWarning()`.
- *Accrued in-place merge edits* — when `merge` runs under an id/natural-key-considering strategy, prism can pair an accrued (live-only) value with a desired value and edit it in place rather than doing a clean add-and-keep; merge cannot preserve it. Detected per delta and refused by default (`--allow-inplace-merge` to proceed). See "Strategy flags". An edit is only *accrued* when desired does not declare the value the path descends through: each container-id segment is resolved against desired by container id, or — since a hand-written fragment carries no ids — by the schema's `a:naturalKey`, and a key only counts when both values actually carry its constituents (an absent key would otherwise match everything and disable the guard). This is what makes natural-key pairing (which the default `--equivalence auto` applies to every container whose desired values fill in a key) + `multivalue=merge` the way to sync one entry nested inside multi-valued containers (e.g. a single GUI `panel` inside an `objectDetailsPage`) without needing `--allow-inplace-merge`.
- *Duplicate natural keys* — blocking, unlike the input checks. A natural key names one entry, so a list holding it twice is what a failed pairing looks like: an entry added beside its live copy instead of replacing it, or declared twice by the tree. midPoint rejects some of these outright (two object types of one kind and intent and the resource no longer loads its schema). `NaturalKeys.introducedDuplicates` projects the whole PATCH, removals included, onto the live object (`DiffEngine.project`) and counts, per keyed list, the values sharing one filled-in key; an object whose PATCH holds more of them than the live object did is failed with `refusing a change that would leave …[kind=GENERIC, intent=task] ×2`. A duplicate the server already held does not block, or an unrelated change could never be deployed; each group is found again in the live object by its path with container ids and counted with the key's own `valuesMatch`, since a key's text is no identity (a `filter` key prints as an object hash). A change prism cannot project is not judged. The offline `diff` applies the same check (exit 2).

## Commands / modes (layered)

**v1 = the offline core.** Live mode was a thin adapter added in a second increment; `deploy` and `init`
came later still. There used to be a separate single-object `apply` as well; it duplicated `deploy`'s
lookup, diff and output with small differences nobody had chosen, and was folded into it — one file is a
tree of one.

- `midgie diff <desired.xml> <live.xml>` → itemDelta XML on stdout. No REST, no server, no policy.
- `midgie diff <desired.xml> --url <url> --user <u> --password <p> [--oid <oid>] [--policy <file>]` →
  exactly `deploy <desired.xml> --dry-run --format xml` (`DiffCommand.diffLive` builds that `DeployCommand`
  with `asDiff` set, which drops the plan rows and the tracking-setup notes from the output). So the lookup,
  the policy, the reconciliation and the payload cannot drift apart from `deploy`'s; an absent object shows
  as its `POST` body (exit 1). `diff` itself has no writing flag.
- `midgie deploy <path...> [--url …]` → discover files and folders, sort the objects by dependency, and print
  the apply order or reconcile them object by object: `PATCH /ws/rest/{type}/{oid}`, or `POST
  /ws/rest/{type}` the whole object (operational/metadata items stripped first) when the search finds
  nothing. Because it writes, it shows the pending change and confirms first, or refuses in a
  non-interactive session unless `--yes`/`-y` is given. `--dry-run --format xml|json|summary [-o FILE]`
  hands over the change of a run holding exactly one object, and `--oid` addresses such a run's object.
  Its own section below.
- **Config file (`midgie.properties`)** → defaults for any option, so the settings that never change for an
  environment (server, account, CA certificate) leave the command line. Read from the nearest tree-local `.midgie.properties` at or above the working directory (stopping at a
  repository root; allowlisted keys only, `ConfigDefaults.TREE_ALLOWED`), then `/etc/midgie/midgie.properties`,
  then `$HOME/.midgie.properties`, then a file named by `$MIDGIE_CONF`/`-Dmidgie.conf`, and **merged per key**
  with later layers winning — the tree suggests, a site file holds what is true of the machine, a user file
  adds only what differs.
  A named path that does not exist is an error rather than a silent skip, and each key remembers which file
  it came from so a validation error names the right one. Keys are option names without dashes, optionally qualified
  with a command; precedence is CLI > `MIDPOINT_*` env > file > built-in default, and `MIDPOINT_PASSWORD` stands down every
  file key that supplies the password (`password`, `password-env`, `password-file`), not only `password`. `ConfigDefaults` wraps
  picocli's `PropertiesDefaultProvider` (which looks up `<qualified.command>.<key>` then `<key>`) and adds the
  env rule plus validation: an unknown key is an error, since a mistyped setting that silently does nothing is
  the failure mode the policy file's "matched no object" note exists to prevent, `yes` is refused
  because a file is not where a person confirms a write, and a key naming a flag that has since become the
  default (`create-if-absent`, `match-by-name`, `prune`) is refused with the `no-…` key that does work now. `insecure` is also read in `Main.main`,
  before the hostname-verification latch, or it would half-apply. A relative value of a path-typed option
  (declared `File`/`Path`, or an array of them — judged from the type, so a later option is covered) is
  resolved against the directory of the file whose key supplied it, found in picocli's qualified-then-bare
  order; the tree-local file is found by walking upward, so the working directory cannot be its base.
- `midgie init …` → the one-off set-up steps, each behind its own flag: `--create-state` creates the
  `midgie-state` object that delete detection needs, `--write-policy` seeds a starter `--policy` file for a
  tree, `--write-cacert` fetches the chain a server presents and writes its issuers as the PEM `--cacert`
  takes (trust-on-first-use, so fingerprints are shown and confirmed; no account needed, since you cannot
  authenticate to a server you do not trust yet), and `--write-properties` writes an annotated config
  template generated from the command model. The last two need no server, so the live check applies only
  to the first two. Never implicit, at two levels: `deploy` reports that tracking is off rather than creating anything,
  and `init` with no step prints its help and exits 2 rather than picking one — every step writes something,
  to a server or to the working copy.

Connection details default from `MIDPOINT_URL` / `MIDPOINT_USER` / `MIDPOINT_PASSWORD`. To keep the password off the command line (it leaks into `ps` and shell history), resolve it — in order — from `--password`, `--password-env <VAR>`, `--password-file <FILE>`, `MIDPOINT_PASSWORD`, or an interactive prompt. REST failures carry an actionable hint (401 auth, 403 authz, 407 proxy, connection refused). `--timeout` sets the per-request timeout; `--retries <n>` retries idempotent reads (including the `POST {type}/search` every object lookup uses) on 502/503/504 / connection-refused / timeout with exponential backoff (rides out a server that is still starting). For https, `--cacert <FILE>` verifies the server against a CA certificate; `--insecure` trusts any cert and skips hostname verification. The `docker/tls/` rig (an nginx TLS proxy in front of midPoint + a client container) exercises these end-to-end.

A **forward proxy** is `--proxy <URL>` with `--no-proxy <HOSTS>`, or `https_proxy`/`http_proxy`/`no_proxy` — the JVM reads none of those variables itself, but the `git` in the container image does, so a pod configured the usual way would otherwise clone its tree and then fail to reach the server. Authentication is Basic, from `--proxy-user` plus the same password ladder, or from a `user:pass@` in the environment's URL (accepted there, stripped from `--proxy` for the reason it is stripped from `--url`). Two things are decided once per process, before any client exists, because the JDK reads both properties on first use of `java.net.http`: hostname verification (`--insecure`) and whether Basic is allowed on a `CONNECT` tunnel — the latter only for a run that configured credentials, so the JDK's default protection stays put otherwise. Configure nothing and no selector is installed at all, leaving `-Dhttps.proxyHost` to work as before. It stays inside `MidpointRestClient` and `ServerCertificates`; the engine remains REST-free, and `ServerCertificates` is the one place that speaks `CONNECT` by hand, because it inspects a handshake rather than making a request.

## Strategy flags

The source XML stays **pure midPoint XML** — no custom in-XML annotations, so the same file still works
with midPoint Studio, ninja, and REST `PUT`. Behaviour is controlled by flags:

- `--scope present|full` (default **present**) — *present*: emit deltas only for items that appear in the
  desired XML (managed = present; items absent from the desired object are left alone → preserves
  accumulated state). Presence is judged at the **full item path** (via the delta's id-free prefix), not
  just the top-level item, so an unmentioned *child* of a single-valued container is preserved too — this
  lets separate files fill different children of one container without clobbering each other; protection
  collapses only at the first multi-valued container (a live-side container id can't be matched to id-less
  desired XML). On a tracked run it continues below that: each live container id is mapped back to the desired value by id or natural key, and an undeclared item inside a declared value is kept unless the tree deployed it and dropped it since (see "Ownership below the top level" under the safety guards). *full*: full-replace semantics (also removes items missing from desired).
- `--multivalue auto|merge|replace` (default **auto**), plus per-path `--path <itemPath>=merge|replace` — *replace*: the desired container values are authoritative (live-only values are removed). *merge*: union by content — merge is designed for and defaults to `REAL_VALUE` (values matched by real content, ids ignored), keeps the additions, and drops the deletion of live-only values so accrued runtime state survives. Caveats: an in-place edit of a managed value reads as "old value kept + new value added" (keep that path on *replace* if you want a true in-place update) — unless the live value carries the container id or the natural key of a value desired declares: that is the entry's old form, not a value beyond the tree, and its delete is kept (`DiffEngine.applyMerge`, pairing by `NaturalKeys.match`); a single-valued item, or any item that diffs to a whole-value replace, cannot merge and is left as replace (noted on stderr). *auto*: merge when the run tracks the tree, else replace — settled in `DeployCommand.settleMultiValue` once tracking is known (`StrategyOptions.Resolved.settleMultiValue`); an offline diff never asks and gets replace.

  > **Why `auto` and not `merge` or `replace`.** `merge` on its own can express neither an edit nor a removal: a content change reads as "old gone, new added" and merge drops the deletion, so values accumulate (`[alpha]` + desired `[beta]` → `[alpha, beta]`), and a dropped entry produces an *empty* delta. That was the reason `replace` was the default. On a tracked tree both gaps close: the three-way comparison against what each file last applied finds what the tree dropped — the old form of an edited entry included — and the object's own PATCH deletes it, in the same request as the rest of the change. One edit it cannot see as a whole: a *keyed* entry that `auto` compares by content because something inside it leaves a key empty — a `schemaHandling/objectType` whose reaction holds an unnamed `<addFocus/>`. The diff adds the new form whole; the removal detector pairs the entry by its `kind + intent` and finds only the items dropped from inside it. With merge dropping the diff's delete, both forms stayed, and midPoint refuses to load a resource with two object types of one kind and intent. So `applyMerge` keeps the delete of a live value that pairs with a desired one by id or key, and `judgeRemovals`' projection then finds the nested removals already covered. What is left is merge's strength: entries the tree does not own (midPoint's, an admin's, another tree's) survive. Its cost is drift visibility — an entry added on the server to a list the tree means to own is kept silently — which is why `deploy` names what merge keeps per object (`Reconciler.keptOnMerge`, from `DiffEngine.liveOnlyValues` on the projected result), on the same line as the nested items a tracked run keeps (`Reconciler.keptNested`). Untracked, none of that holds, so `auto` is `replace`, said in a note. It depends only on tracking, not on `--no-prune`: withholding removals and flipping to replace in response would delete more, not less. Tracking is per file, so any number of deployed paths inside the repository are tracked; only a path outside it is left untracked, with a note.
  >
  > Equivalence *is* schema-dependent, and `auto` is the default that makes it so. An earlier version of this note said a schema-dependent equivalence default was "not expressible anyway", because equivalence is a single input to prism's whole-object diff (`live.diff(desired, strategy)`) unlike `multivalue`, which midgie varies per path by post-processing the delta. That is true *within one diff* and remains the reason per-**path** equivalence is not offered; it is not a reason to diff the object only once. See `--equivalence auto` below.
- `--equivalence auto|real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal`
  (default **auto**) — the value-equivalence test that decides what counts as drift and how entries are
  matched. `auto` chooses between the first two of the named strategies per container; the rest are listed
  loosest to strictest:
  - *auto* — **the default.** Per multi-valued container, `natural-keys` where the schema declares a key and every desired value fills it in, `real-value` everywhere else. The object is diffed under both and each item delta is kept from the pass its path belongs to, because prism takes one equivalence per whole-object diff. Buckets are cut only at multi-valued containers reachable through single-valued ones — the strategies express a change inside a multi-valued container at different depths (`objectDetailsPage/84/panel` vs a whole-value replace of `objectDetailsPage`), so a cut inside one would lose or duplicate it. A subtree with an unfilled key inside it is not given to `natural-keys`, which would pair the unfilled entries vacuously. If the bucket's own list fills in its key (a `schemaHandling/objectType` holding a reaction with an unnamed `<addFocus/>`, an `objectDetailsPage` holding a panel without `<identifier>`), it goes to a third pass instead (`NaturalKeys.Pairing.IDS`): the keyed entries take the live container ids by key (`adoptLiveIds`) and the subtree is diffed under `consider-different-ids`, which pairs by id and compares whatever has none by content — so the object type is edited in place and only the unnamed list is compared by content. (Assigning ids under `natural-keys` is not enough: probed on 4.10.5, a vacuous key match there pairs values even when their ids differ.) Only a bucket whose own list leaves its key empty (`assignment`, `inducement`) stays on `real-value`. With nothing to pair this is a single `real-value` pass, identical to the pre-`auto` default. A list compared by content costs the in-place edit for its own entries: a change inside one replaces that entry whole, with a new container id. `-v` names each list compared by content and the unfilled key that decided it, per object (`DiffResult.keyPairingDeclined`); the full verdict is in the debug log's `equivalence=` field.

    What it buys: a keyed entry is updated **in place**, keeping its container id and the metadata hanging off it, where `real-value` alone replaces the whole container. What it avoids: prism's `DefaultNaturalKeyDefinitionImpl.valuesMatch` treats a key constituent absent from *both* values as matching, so an object-wide `natural-keys` pairs unrelated values positionally on the many types that declare a key real config never fills in — `assignment`, `inducement` and `authorization` among them. Deciding per container from whether the key is actually filled in keeps those out on their own evidence, with no hardcoded deny list.

    Only `real-value` and `natural-keys` are ever chosen. `consider-different-ids` makes no difference for an id-free desired file (its rule is that ids must not *contradict*, and `null` vs `123` does not); `ignore-metadata` re-considers reference filters, which would fight `--smart-refs`; and `data`/`literal` consider operational data and metadata, so against a live server they never diff empty and would break the exit-code contract. Note that `natural-keys` is built from `REAL_VALUE_CONSIDER_DIFFERENT_IDS`, so that bucket carries the non-contradicting-ids rule too.

    **Container ids from another server.** Whatever the strategy, prism pairs two list values that both carry an id by that id and diffs one into the other. A file exported from the target server is meant to be read that way; one exported from another server is not, because ids are numbered per object on each server — a DEV `<inducement id="5">` names whatever PROD holds under 5, and the diff used to re-point that entry in place, under `merge` too. `ContainerIds.trustedOnly` runs first in `DiffEngine.diff` (and in `liveOnlyValues`) and walks desired against live, recursing through the values it pairs. A list's ids are taken as foreign when the live list shows any of three contradictions: a value with the same id carries a different filled-in natural key; a desired value is already held under a *different* live id (by key where both fill it in, else by content); or the live value with a desired value's id is declared by another desired value. midPoint never reuses a container id, so none of these can occur between a server and a file exported from it. The ids of such a list are dropped from a copy of desired, and everything after that — both auto passes, `adoptLiveIds`, merge, the accrued-value guard, the input checks — pairs its values by natural key or content, as for an id-less hand-written file; `-v` notes the list. `NaturalKeys.match` also refuses an id match whose two values fill in different keys, for the callers that pair without the diff (`FragmentUnion`, `RemovalDetector`). The residual case is one edited entry whose foreign id happens to be taken, with nothing else in the list to show the numbering is foreign: an edit and a collision look identical from the two objects.
  - *real-value* — ignores container ids, metadata, operational items, element names, ns prefixes, ref
    filters/options. No churn from midPoint's own bookkeeping; what `auto` falls back to.
  - *consider-different-ids* — as *real-value*, but PCV ids must not contradict (pairs/edits entries by id).
  - *natural-keys* — as *consider-different-ids*, plus container natural keys. Maps to
    `REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS`; prism-`@Experimental`, so it prints a note on stderr
    when used. Exposed for exploration against a real server.
  - *ignore-metadata* — ignores metadata/operational/ids, but keeps element names and reference filters/options.
  - *data* — full parsed data incl. metadata and exact ids; ignores only serialization noise (prefixes).
  - *literal* — as *data*, but ns prefixes matter too (≈ comparing raw XML).

  `merge` is designed for `real-value` (clean, id-agnostic union). It is still allowed under the other
  strategies for exploration, but an id/natural-key-considering strategy can pair a live-only (accrued)
  value with a desired value and emit a deep **in-place edit** (e.g. `assignment/2/targetRef`) that merge
  cannot express as a union — so the accrued value is silently overwritten. The engine detects that
  **per delta** (`DiffResult.accruedInPlaceEditPaths`, comparing the edited container-id against the desired
  object) and both `diff` and `deploy` **refuse by default** (exit 2), naming the offending paths.
  `--allow-inplace-merge` downgrades the refusal to a warning and proceeds. An in-place edit of a value that
  *is* present in desired is a legitimate managed update and is not flagged.

## Schema (two layers, two knobs)

- **Base** model (UserType, RoleType, SystemConfigurationType, … and their item definitions) is
  **compile-time** — generated Java bound to XSD, loaded from the `midpoint-schema` jar. It **cannot** be
  supplied as XML. The bundled jar version (`midpoint.version` in `pom.xml`) must match the target's major
  line. This is why a fat JAR is version-specific (ninja and midPoint Studio are too). Within a line the
  build tracks the **newest patch release**: patch releases change the schema as well (4.10.4 added natural
  keys and GUI items, 4.10.5 made `PasswordType/hint` a `ProtectedStringType`), and the newest one is a
  superset of what an older patch on the same line sends. `VersionSupport` warns on a different line and on
  a server running a newer patch than the bundled schema; an older patch is only logged. Bundling several
  patch schemas (per-version classloaders, or older XSDs overlaid on the newest classes) was considered and
  rejected: the gain over "newest patch plus handling the rare type change" does not pay for its size and
  fragility.
- **Extension** schema (custom `extension` attributes, custom `SchemaType` objects) is **runtime**:
  - *File mode:* `--schema <file>` (repeatable) and/or `--schema-dir <dir>`. Both accept either format,
    chosen by extension: a `.xsd` is a raw XSD, a `.xml` is an exported `SchemaType` object (its embedded
    `<xsd:schema>` is used). In `--schema-dir`, other extensions — and `.xsd`/`.xml` files not in the
    expected format — are warned about and skipped, since a directory may hold other files; a file named with
    `--schema` fails the run, as a missing one does, and so does a `--schema-dir` that cannot be read. Everything collected is registered via
    `schemaRegistry.registerDynamicSchemaExtensions(...)` + `prismContext.reload()` — the same call
    midPoint's `repo-common/SchemaCache.java` (:81, :89) makes at startup.
  - *Live mode:* read the `SchemaType` objects off the server (`--url/--user/--password`) and register
    them the same way (best-effort — a read failure warns and falls back to base schema). `--no-live-schema`
    skips this `GET /schemas` read (faster, or for accounts without schema-read); extension items then need
    `--schema`/`--schema-dir` or they diff as raw.
  - Note: `--url` supplies *extensions* only. The base jar is always needed locally, because the tool
    parses both objects into `PrismObject`s locally to run the diff.

## Output & exit codes (CI-friendly)

- `--format xml` (default) / `json` emit the `ObjectModificationType` (both consumable by REST `PATCH`);
  `summary` is a human-readable changed-path list. Output goes to stdout, or to `-o <file>`.
- The desired (and offline live) object may be read from stdin by passing `-` as the path.
- `--debug` / `-v` raises the log level to DEBUG on stderr for troubleshooting (stdout stays the payload).
- Exit-code contract (lets CI gate on drift without parsing output):
  - `diff` and `deploy --dry-run`: `0` = no differences · `1` = differences found / would change · `2` = error
    (for a live `diff`, also an object that was skipped and so has no change to show).
  - `deploy` (real write): `0` = success (applied, created, already in sync, or user-declined) · `2` = error.
- `--quiet` / `-q` drops advisory notes/warnings and (for `deploy`) status lines, keeping the delta payload,
  error messages, and the exit code — for clean pipeline output.
- `deploy`/`rollback` result rows use a two-column layout (`OutputLayout`): the result alone in column 1, and in
  column 2 the file and object, the change detail, and that object's warnings and notes, printed as one block on
  the row's stream (stdout; stderr for a failure or when the payload is on stdout) so a CI log that captures the
  streams apart cannot separate a note from its object. Column 2 wraps to the terminal width (`stty size` on
  `/dev/tty`, re-read after `SIGWINCH`) with a hanging indent; with no terminal it is not wrapped unless `COLUMNS`
  is set. Notes about the run as a whole stay on stderr.

## Packaging & license

- Maven, Java 21, fat JAR via `maven-shade-plugin` (needs a JVM; GraalVM native is impractical given
  prism's reflection/JAXB). Dependencies: `com.evolveum.midpoint.infra:schema` (pulls prism), picocli,
  JUnit 5; the live modes use the JDK 11+ `java.net.http` client — no extra dependency.
- **Launcher `bin/midgie`.** On JDK 23+, classgraph (a transitive prism dependency) calls the
  terminally-deprecated `sun.misc.Unsafe::invokeCleaner` to unmap memory-mapped schema jars during prism
  bootstrap, which prints JEP 498 warnings. Those go to the *native* stderr (fd 2), bypassing `System.err`,
  so they cannot be filtered in-process — the only suppression is the launch flag
  `--sun-misc-unsafe-memory-access=allow`, which can't live in a `-jar` manifest. The launcher adds it, but
  **only when the running JVM is major ≥ 23** (the flag is unknown to JDK ≤ 22, which does not emit the
  warning). Running `java -jar` directly instead? Set `JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.
  Forward-looking: once a JDK defaults this to `deny`, the flag becomes *required* to run at all (the Unsafe
  call would throw) until prism ships a classgraph that no longer uses it.
- **License: EUPL-1.2.** The tool links `midpoint-schema`, which is EUPL-1.2 (the standalone prism lib is
  Apache-2.0, but the midPoint schema definitions you need for object-aware diff are EUPL). A derived work
  must be EUPL-compatible — it cannot be MIT/Apache. Confirm the exact module license before publishing.

## How a promotion pipeline consumes it

That folder-loop is now the in-tool **`deploy` subcommand** (see "deploy — apply a folder tree" below, built through the apply, plan and policy increments), so it runs in one JVM with a schema-aware dependency sort instead of an external per-file loop that re-pays the prism bootstrap each time. Env-specific value templating stays a pipeline pre-step (render the tree, then `deploy` it). Packaged as a Kubernetes Job on the midPoint image, `deploy` is "item #2" of the midpoint-k8s delivery plan.

## Build order (recommended)

1. **Offline `diff` core** — engine + two-layer schema loading + `--scope`/`--multivalue`/`--equivalence`
   + `ObjectModificationType`/`summary` output + exit codes. Independently testable and useful.
2. **Live fetch** — `--url` GET of the current object, then diff.
3. **Writing** — `PATCH`, and `POST` when the object is absent (`--no-create-if-absent` fails instead). Built
   as a single-object `apply` first, later folded into `deploy`.

## Verification

1. *Unit (offline):* two hand-authored objects — a `RoleType` differing in one property; a `UserType`
   with an added assignment → assert the emitted `ObjectModificationType` (a `replace` for the property;
   an add-by-container-value for the assignment). Model on midPoint's own `TestParseDiffPatch` /
   `TestDiffEquals` fixtures.
2. *`--scope present`:* desired holds a subset of items → deltas only for present paths; absent items
   untouched.
3. *`--multivalue`:* `merge` on `assignment` preserves existing values and adds the new one; `replace` is
   authoritative.
4. *Extension schema:* an object using a custom `extension` attribute diffs correctly with
   `--schema-dir`/`--schema`, and falls back to raw handling without it (document the fallback).
5. *Live (increment 2+3):* against a running midPoint, `deploy` a one-field change to
   `administrator` → only `fullName` changes, assignments/metadata intact; re-run → no-op (idempotent).
   a fresh OID creates once, then no-ops.

## deploy — apply a folder tree

> **Status: built.** Read-only discovery + schema-aware dependency sort, the per-object apply loop (`--dry-run`; creating a missing object and matching an OID-less one by name, both on by default), `--retry`, the `--plan`/`--apply-plan` saved-change modes, and the `--policy` strategy file. See TODO.md section H. Now that `deploy` applies, the tool does more than "diff", so it was renamed **midgie** (a wee Scots midge); only the Java package `de.is4it.midpoint.diff` keeps the old name for now.

Apply a whole tree of desired-state object XML to a live server, in dependency order, reusing the `diff` engine — **one JVM bootstrap** for the whole run. midPoint's prism bootstrap costs ~1.5 s, so a shell loop invoking the tool once per file would pay that per object; an in-process subcommand pays it once and can sort with prism's precise reference walk instead of a text heuristic. **Structure-agnostic**: objects are discovered by content, so the config repo can use any folder layout the team likes. The deploy model is **per object**: the files describing one object are combined and applied together (one `itemDelta` per object, however many files it took), and *schema extensions follow the same model* — an extension is a `SchemaType` object deployed like any other, so schema files are first-class nodes in the same dependency sort.

`midgie deploy <path...> [--url <url>] [creds/TLS/retry — the same mixins as diff] [flags]` (paths are files or directories, walked recursively, following symbolic links — a Kubernetes ConfigMap mounts each key as a link into a hidden `..data` directory, which the dot-directory rule keeps from being walked twice; a link back into the tree is skipped with a warning). A `.xml` that is not well-formed, or a `SchemaType` that does not parse, fails the run before any server contact, as an unparseable object does.

### Increment 1 (built): read-only discovery + schema-aware dependency sort

`deploy <path...>` discovers the tree, sorts it, and **prints the apply order**. It never writes: offline by default, and `--url` only *reads* the server's extension schema (`--no-live-schema` opts out). Exit `0` when discovered + sorted (warnings are still `0`), `2` on error. The order is **two tiers**:

1. **Schema tier.** Discover raw `.xsd` (local schema input) and exported `SchemaType` `.xml` (schema input *and* a deployable object). Read each schema's `targetNamespace` and `<xsd:import>` namespaces; a schema that imports another discovered schema's namespace sorts after it. Register all extension schema — CLI `--schema`/`--schema-dir`, live, and the tree — in **one batch** (prism's `registerDynamicSchemaExtensions` *replaces* the dynamic set, so it must be a single call), which is also what lets a type one extension defines resolve when another uses it. Raw `.xsd` is registered but not a deployable apply unit; only `SchemaType` objects are.
2. **Object tier.** Parse the remaining `.xml` as `PrismObject`s (extension attributes now resolve). Node identity = OID, else `(objectType, name)` — several files may be *fragments* of one object, and they are **combined into one desired object** which is the apply unit (`FragmentUnion`; see "Fragment union" below). Grouping runs a best-effort consistency check first: same type, and no single-valued item declared with differing real values across fragments — otherwise error. A multi-valued item declared by two fragments needs no check, since combining is exactly what happens to it. Edge A→B when B is in the discovered set and A carries an `ObjectReferenceType` OID for B (out-of-set refs are assumed pre-existing and ignored); references are extracted with a prism reference-visitor (every `PrismReferenceValue` with a non-null OID). Kahn topological sort with a deterministic tiebreak; **cycles** (mutual role inducements, org trees) are broken arbitrarily and warned, not fatal.

The plan prints one line per apply unit — that is, per object (`seq  type  oid-or-(type/name)  name  file`, with `(needs #…)` dependency positions, and the object's other files listed under it as `+ path`); `--debug` adds the edge lists, `--quiet` keeps only the order. New classes: `DeployCommand`, `Discovery`, `SchemaFile`, `Fragment`, `DeployObject`, `ReferenceCollector`, `TopoSort`; `SchemaOptions` split into `newBaseContext` + `collectSchemas` (collect without registering) so all schema sources merge into one registration.

### Increment 2 (built): the apply loop + `--dry-run`

3. **Apply in order.** With `--url`, reconcile each unit — one per **object** — in the sorted order: resolve the current object via a `POST {type}/search` (by OID, or by type+name when it has none, unless `--no-match-by-name` — a search so a missing object is an empty result, not a 404 that makes midPoint log an `ObjectNotFoundException`), diff the combined desired object (`--scope present`), then PATCH — or POST to create it, which is the default; under `--no-create-if-absent` a missing OID object fails and a missing OID-less object is skipped instead. One request per object, so a create is atomic (the whole object is POSTed, not a partial one patched into shape afterwards) and there is no window in which half of it is applied. Sequential, so each unit sees prior writes (a role is created before the user that references it). Writing is gated: a single up-front confirmation by default (`--yes` bypasses, `--confirm-each-apply` prompts per change, `--dry-run` previews without writing; a non-interactive session without `--yes` refuses). Per-unit result lines (created/patched/in-sync/skipped/failed) + a summary; `--quiet` keeps only errors and the exit code. Exit `0`/`2` on apply, `0` in-sync / `1` would-change / `2` on `--dry-run`. A transport/auth failure aborts; a per-object rejection is recorded and the run continues (best-effort). The `Reconciler` reuses `DiffEngine` and looks objects up through `LiveLookup` over the new `MidpointRestClient.searchObjects`, and is the only planner: `diff --url` runs through it too.

### Increment 3 (built): retry, saved plans, and the strategy policy

4. **Retry-until-stable (`--retry`).** After the apply pass, re-apply just the failed units round after round until there are no failures or a round makes no progress — catches ordering the static graph can't see (references by name, by search filter, or inside script expressions). Report anything still stuck and exit non-zero. Off by default (a single pass); retry re-applies changes already confirmed up front, so it does not re-prompt — except under `--confirm-each-apply`, where each retried change is asked about again.
5. **Saved plans (`--plan`/`--apply-plan`).** `--plan <dir>` reconciles each unit against the server as it stands and writes one file per change — one per object, since that is the unit (it used to keep a local projection advanced by each applied delta, purely so that a second fragment of a to-be-created object became a patch rather than a second create; combining the fragments first leaves that with nothing to answer for): `NNN-<type>-<oid>.create.xml` (full object) or `NNN-<type>-<oid>.patch.xml` (`ObjectModificationType`). It touches nothing on the server (exit `0`/`1`/`2` like `--dry-run`) and refuses a directory that already holds a plan. `deploy --apply-plan <dir> --url …` reads the files in filename order and replays them **verbatim** (POST creates, PATCH patches) under the same confirmation gating; plain `deploy` recomputes at apply time (safer against drift), `--apply-plan` executes exactly what was reviewed. (`PlanStore` writes/reads the filenames.) On a tracked tree the plan also carries `state.xml` (`PlanState`): the `midgie-state` rows an apply of it would write, per object — each with the value it replaces — and the run's own rows, computed at plan time where the tree and its history are. `--apply-plan` writes the rows of each object whose entry it applied (and of the objects already in sync), the run's rows only when every entry applied, and each only while the row still holds the value it replaces; a pipeline that only ever plans and applies used to leave its rows at the last plain deploy, so a value added by one plan and dropped by the next was never detected. `--apply-plan` also leaves a rollback net like a deploy: it bootstraps prism with the extension schema (`SchemaOptions.buildContext`), reverses each body it sent (`RollbackPlan.recordPlanPatch` — the bodies carry the old values `DiffEngine` recorded), records the rows it wrote, and puts the net where a deploy of that tree would (`defaultBase` of the plan's deployment key), so `midgie rollback` in the tree finds it. A body whose inverse cannot be computed is reported and marked `unrecorded` in the manifest rather than silently left out. Every OID in a written file is named in a comment above its element — `<!-- RoleType "Superuser" -->` — by `OidComments`: objects of the tree are named from the tree, references beyond it from the server in one search per type (cached for the run), and an OID the server does not know is said to be missing (`RoleType not found on the server`) — unless the tree holds it, which is an object this deploy creates, not a dangling reference, and says `(not on the server yet, deployed with this tree)`. The rollback plan is written after the apply, when those objects exist, so `OidComments.treeDeployed` drops the note there. The same goes for the rollback plan and for an XML payload on stdout (`diff`, `deploy --dry-run --format xml`); an offline `diff` names only the object itself. Comments are ignored by the REST interface, but `--apply-plan` and `rollback` strip them before sending anyway (`OidComments.strip`, which removes exactly what annotation inserted and leaves CDATA alone), so a hand-written review comment never reaches the server. Insertion is text-based, like `XmlPretty`, to keep the serializer's layout; it skips comments and CDATA, and falls back to the unannotated text if the result would not parse. There is no opt-out: comments change nothing a parser sees. A `--format json` PATCH body has none; a create is rendered as its XML `POST` body in every format (`Reconciler.render`), so it carries them.
6. **Strategy policy (`--policy <file>`).** One central file of `<selector> key=value` lines (`#` comments, blank lines ignored) keyed on the **object/item**, not the source file, so it survives the fragment model. The selector is a single glob over `Type/Name/item/subitem…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level; an object-depth selector sets `scope`/`equivalence`/`allow-inplace-merge`/`smart-refs`/a default `multivalue`, a deeper/`**` selector sets `multivalue` for that container (an object-only knob on an item selector is rejected). The most-specific selector wins per knob (more literal segments, then fewer `**`, then a longer literal prefix; ties → earlier line); the CLI flags are the baseline. Object names may contain spaces (the selector is everything before the first `key=value`). Each wildcard is a whole segment: a segment mixing `*` with other text is refused at load with the line number, since both ways of writing that — matching within a segment (`RoleType/App-*`) and two globs on one line (`RoleType/* **/assignment`, which the space-tolerant grammar swallows as one segment) — otherwise parse into a literal no name can equal, so the rule silently matches nothing and the objects it was written for keep the CLI strategy. `\*` escapes a literal asterisk in a name, as `\/` does a slash; `init --write-policy` emits both. Object XML stays pure. (`Policy` parses it and resolves it against the combined object's type, name and OID; `GlobPath` does the glob match and the id-free multi-value `PathRule` matching; `Reconciler.plan` takes the strategy per call.)

   **`apply=on-create`** (item-level only; `always` is the default) marks an item that is written only in the POST that creates the object — `TaskType/*/executionState` is the motivating case, owned by operators and the task manager once the task exists. `ApplyRules.holdBack` runs in `Reconciler.planPatch` right after the diff: a delta at or beneath such a path is dropped (so `--scope full` cannot delete the item either), an *added* container value has the item stripped from a clone (a value left empty is not sent), and a *replace* of a container is refused for that object when the server holds the item beneath it, since the replace would overwrite it whatever the new value carries. Held-back paths ride on the `Outcome`; `--dry-run`/`--plan` print them, an object whose only differences were held back is `in-sync`, and the accrued-in-place refusal ignores paths that will not be sent. Removals of values dropped from the tree skip such paths (`DeployCommand.withoutRestricted`), because a removal is a write too. A *delete* above such an item is judged on the whole PATCH, removals included, by `ApplyRules.removedWithContainer`: when the server's copy of the deleted value holds the item and the object would not keep it — the container is single-valued, or the same PATCH adds a value at that path, i.e. a list entry compared by content is replaced whole — the object is refused. A delete of one entry of a multi-valued container with nothing added in its place removes that entry as a whole and goes through. An object matched by name counts as existing. **`apply=if-absent`** is midPoint's weak strength: `holdBack` keeps a delta at or beneath such an item when `live` holds no value at the delta's own path, and drops it when it does, so the item is filled in whenever missing and never overwritten or removed; removals skip it for the same reason. Added container values keep their `if-absent` items (nothing can be under a container that is only now being added); a container replace is refused when the server holds such an item beneath it, as for `on-create`. `never` (exempt a path from `--scope full`) was considered and deferred until a use case appears. `init --write-policy` offers a fixed catalogue of candidates (`InitCommand.ON_CREATE_SUGGESTIONS`: task `executionState`/`schedulingState`, user `credentials` and `activation/administrativeStatus`, resource `administrativeOperationalState` and `schema`) for the items the tree declares, **commented out** so seeding a policy never changes what a deploy does.

**Reuse vs. new.** *Reused:* schema registration (`SchemaOptions`), the diff engine (`DiffEngine`), `Reconciler.createBody`/`Confirmation.decide` (from the former `apply` subcommand), the REST client and the creds/TLS/retry/host option mixins. *New:* content-based discovery, the reference-visitor, the two-tier topological sort, the reconcile loop, `LiveLookup`/`searchObjects`, `PlanStore`, and the strategy policy (`Policy` + `GlobPath`).

**Verification.** Unit: schema import ordering, object schema→role→user ordering, a cycle, external refs ignored, discovery skips non-objects, same-identity fragments group and combine (keyed entries paired, keyless values appended and de-duplicated, contradictions error), the reconcile decision table, the REST search, policy selector matching / most-specific-wins / validation, plan-file round-trip, and stub-server retry convergence (`DeployCliTest`, `FragmentUnionTest`, `TopoSortTest`, `ReconcilerTest`, `MidpointRestClientTest`, `PolicyTest`, `PlanStoreTest`, `DeployRetryTest` over `src/test/resources/deploy/`). `DeployUnionTest` drives the CLI against a stub server to assert what the union is *for*: an object described by two files produces one PATCH carrying both, and one POST when it is created. Integration (`docker/` rig, `LiveIntegrationTest`): deploy a small tree with the dependency order and an **idempotent re-run** (second run all in-sync, exit 0), patching an OID-less object matched by name, the non-interactive refuse, the `--plan`→`--apply-plan` round-trip, and a `--policy` per-object strategy override.

**Consumer.** This is the promoter for the midpoint-k8s delivery pipeline: the chart's config-apply Job becomes `midgie deploy /config --url <route> --user … --password-file …`, run after `helm upgrade`.

## rollback — undo the last deploy

> **Status: built.** `DiffEngine` records old values, `deploy` writes a rollback plan on every run, and `midgie rollback` replays it. See TODO.md section K.

A deploy that writes leaves a **rollback plan** behind: for each object it touched, the delta that puts it back. That includes a run that ends part way on a rejected login or a lost connection — the net for what was written before the failure is written before the error is reported. The motivating failure is ordinary — a missing line in the `--policy` file leaves a multi-valued container on `replace`, the wholesale replace wipes accrued associations or part of the `SystemConfiguration`, and the run reports success.

**Why the inverse is nearly free.** prism's `fixupDelta` is overridden in `PrismPropertyImpl` *alone*, so multi-valued containers and single-valued references diff to add/delete pairs — and in the wipe case those deletes carry the accrued values themselves. `ObjectDelta.createReverseDelta()` swaps them back.

**The one gap, and why it had to be closed first.** A single-valued *property* collapses to a bare `replace`: `ItemImpl.diffInternal` builds the add/delete pair, then `fixupDelta` calls `delta.clear()` and rewrites it, discarding the old value without copying it into `estimatedOldValues`. `ItemDeltaImpl.createReverseDelta` reverses a replace by substituting those old values and, finding none, falls back to an **empty** `valuesToReplace` — prism's own comment calls this "not entirely correct" — so an undo would *clear* the item instead of restoring it. `DiffEngine` therefore fills `estimatedOldValues` from the live object it already holds. `ItemDeltaType` carries it as `estimatedOldValue`, `DeltaConvertor` serializes and parses it back unchanged, and midPoint treats it as informational (its own delta visualizer reads it; the repository write path does not). Protected values are never recorded.

**Not midPoint's object history.** `ModelAuditService.reconstructObject` does the same thing server-side — current state plus reverse deltas — but it is unreachable and unsuitable here: no audit mapping exists anywhere on the REST API (none in `ModelRestController`, `MidpointFunctions`, or the bulk-action evaluators), `AuditController.rollBackTime` throws outright on any ADD or DELETE delta in the trail, which is exactly what creating a missing object (the default) produces, and the shipped `cleanupPolicy/auditRecords/maxAge` is `P3M`.

**Shape.** Entries are collected in apply order and written in **reverse** — the undo of a sequence is the reverse sequence of inverses. (An object assembled from several files contributes one entry, since it is applied once.) The files are ordinary `PlanStore` plan files. `manifest.xml` records the server, the `DeploymentIdentity`, the git revision, and each object's repository version after the write. Objects the run *created* are named there but never undone: the inverse of an add is an object delete, and one created an hour ago may already carry links and shadows. Removals of dropped values ride in the object's PATCH, so its inverse puts them back too.

**State rows travel with the net.** On a tracked run `recordState` runs before the net is written, and hands the rollback plan each object's moved rows — the rows of its files and of the gone files it compared against, before and after (`RollbackPlan.RowChange`) — plus the rows no one object claims (a gone object's, an earlier version's tree rows). After replaying, `rollback` restores the rows of each object it restored, and the run-level ones only if it restored them all, each only while the row still holds the recorded *after* value. Left alone, the rows said the rolled-back revision was applied: redeploying it found nothing dropped, and merge kept the rolled-back values beside it. The `deploy:` rows are history and are not restored.

**Run directories and progress.** A net's directory is named to the millisecond and created atomically, so two deploys finishing together cannot share one. Rotation and "the newest plan" consider only runs whose manifest names this deployment — two keys that flatten to one directory name keep their own nets. `rollback` marks each entry it restores (`<plan file>.restored`); a run after a partial failure sends the rest only and does not read the versions it moved as someone else's change.

**Refusals, not prompts.** `rollback` checks *provenance* (server and deployment must match, no override — a staging plan must not reach production because somebody answered yes) and *staleness* (an object whose version has moved was changed by someone else, so replaying would revert their change too; `--force` proceeds). The staleness check is read-then-compare, so it races a concurrent writer — acceptable for a config pipeline, and the honest alternative to silently reverting a colleague's edit.

**No `--auto-rollback`.** midPoint has no cross-object transaction, so unwinding a partly-applied tree can land in a third state that is neither old nor new; the failure that would trigger it is often the one that prevents it (auth, transport, server down); and it collides with `--retry`. The plan on disk is what makes a considered rollback cheap.

**Where nets live.** `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/` by default, newest five kept. Rotation touches only directories it can prove are nets — a timestamp name, a real directory rather than a link, and a manifest or unfinished-plan marker inside — because the base path may be any directory, the tree included. That default is wrong for a Kubernetes Job, whose filesystem dies with the pod that needed it, which is why the base path is one of the keys a **tree-local `.midgie.properties`** may set (see "Config file"): the deployment is the only thing that knows whether it is a laptop, a runner, or a pod with a mounted volume. That layer is allowlisted rather than denylisted, since it travels with somebody else's repository; `url`/`user`/`password*`/`insecure`/`cacert` are absent from it, and so is `no-rollback-plan` — a file may say *where* something goes, never *whether* a safety gate applies, the same rule that keeps `yes` off every config file.

**Plan files are never deploy input.** A `.create.xml` is a whole object with a valid root element, so a plan directory inside a deployed tree used to be discovered and redeployed. `PlanStore` now stamps every plan file with a generated-file marker that `Discovery` skips; the marker travels with the content, so it survives a rename, a move, a commit, or a CI artifact. Two path-based guards back it up: the `--plan`/`--rollback-plan` directories are excluded by canonical path, and the walk no longer descends into dot-directories.

New classes: `RollbackPlan` (collector, manifest, rotation), `RollbackCommand`. Changed: `DiffEngine.recordOldValues`, `Discovery`, `PlanStore`, `ConfigDefaults`, `MidpointRestClient.host()`, `Io.restrictDirToOwner`.
