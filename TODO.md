# TODO — possible improvements

Hardening and polish for `midgie`. None of these are bugs — the tool is functional: offline `diff`,
`diff --url`, `apply` with create/patch/idempotency, `deploy` over a folder tree (plan, policy, retry,
delete detection and `--prune`), `init`, and two-layer extension schema. Priority is value × how exposed
the gap is. ⭐ marks a good first cluster (small, independent, high-value).

## A. Safety on `apply` (it mutates a live server) — done
- [x] ⭐ **Confirm before applying** — prompts interactively; `--yes`/`-y` bypasses; refuses in a non-interactive session without `--yes`.
- [x] **Show the delta before applying** — the change summary is echoed to stderr before the prompt.
- [x] **`--create-if-absent` strips operational cruft** — `createBody` runs `removeOperationalItems()` before POST, so metadata/operational items copied from a live GET are not sent.

## B. CI / exit-code semantics — done
- [x] ⭐ **`--dry-run` exits 1 on drift / 0 in sync** — `apply --dry-run` now gates like `diff` (1 = would create/patch, 0 = in sync, 2 = error).
- [x] **Documented, stable exit-code contract** (DESIGN.md "Output & exit codes") across `diff`/`apply`, plus a `--quiet`/`-q` mode that drops advisory/status output while keeping the delta payload, errors, and exit code.

## C. Credentials & connection
- [x] ⭐ **Keep the password off the CLI** — resolved from `--password` → `--password-env VAR` → `--password-file FILE` → `MIDPOINT_PASSWORD` → interactive prompt.
- [x] **Env-var config** — `MIDPOINT_URL` / `MIDPOINT_USER` / `MIDPOINT_PASSWORD` default the connection (an explicit LIVE file still wins over an env host, so file diffs aren't broken).
- [x] **Clearer REST errors** — 401 (auth) / 403 (authz) / connection-refused / timeout carry an actionable hint; the best-effort schema-read stays quiet on 401/connection so the real error shows once.
- [x] **Configurable timeouts + retry/backoff** — `--timeout <seconds>` (per-request) and `--retries <n>` on 502/503/504/connection-refused/timeout with exponential backoff. Retryability is a property of the call, not the HTTP verb: reads retry (including the `POST {type}/search` every object lookup uses), writes never do.
- [x] **Real TLS verification** — `--cacert <FILE>` verifies the server against a CA cert; `--insecure` now also bypasses hostname mismatch. Verified end-to-end by the `docker/tls/` rig (nginx TLS proxy + private CA + a client container running the smoke tests).

## D. Correctness edges (each also wants a test)
- [x] ⭐ **Protected/encrypted values** — `ProtectedStringType` item deltas are dropped from the delta (ciphertext re-encrypts on every fetch → spurious deltas *and* a secret leak) and the skipped paths are reported on stderr. Detection is by schema type (`credentials/password/value`) or a protected real value; nested protected values inside a larger added container are not walked (rare — documented in `DiffEngine`).
- [x] ⭐ **Round-trip core test** — `roundTripDeltaAppliesToLiveYieldingDesired` computes the delta, `applyTo()`s it to a clone of the live object, and asserts the result equals desired (midPoint's `TestParseDiffPatch` invariant).
- [x] **PolyString / reference diffing** — `polyStringAndReferenceDoNotChurn`: no drift on an expanded-vs-shorthand PolyString name or a `targetRef` under a different container id; real drift on a changed name/ref.
- [x] **Type/OID cross-check** — `DiffEngine.diff` throws if desired and live both carry an OID and they differ (already refused mismatched types); a desired template without an OID is still allowed.
- [x] **Merge natural-keys footgun** — `DiffEngine` now detects per delta when a merge would edit an accrued (live-only) value in place (an id/natural-key-considering strategy pairs it with a desired value and produces a deep in-place edit merge can't preserve). Both `diff` and `apply` **refuse by default** (exit 2) with the offending paths; `--allow-inplace-merge` downgrades to a warning and proceeds. An in-place edit of a value that *is* in desired (managed) is not flagged. Replaces the old imprecise up-front "merge + non-real-value" warning.

## E. I/O ergonomics
- [x] **stdin/stdout plumbing** — desired (and offline live) read from `-`; write with `-o <file>`; `--format json` alongside xml/summary.
- [x] **`--debug` / `-v`** raises the log level to DEBUG on stderr.
- [x] **`--no-live-schema`** — skips the live `GET /schemas` read (speed, or when the account lacks schema-read); extension items then need `--schema`/`--schema-dir` or they diff as raw.

## F. Packaging / docs / release
- [x] ⭐ **Update the root `README.md`** — rewritten: install (launcher + JAR), the three modes, the CI exit-code contract, a grouped flag reference (strategy/connection/schema/output+apply), env vars, safety guards, schema layers, version coupling, and testing.
- [x] **Dynamic version** — `--version` reads the pom `project.version` from the filtered `build-info.properties` (no more hardcoded `0.1.0`) and also prints the bundled midPoint schema line, via a picocli `VersionProvider`.
- [x] **CI workflow** — `.github/workflows/ci.yml`: `mvn verify` on JDK 17 + 21 (Maven-cached; rig-gated live tests self-skip) plus a REUSE compliance lint (`fsfe/reuse-action`), on push/PR to `main`. README carries CI + REUSE badges.
- [x] **CHANGELOG + a tagged release** — `CHANGELOG.md` (Keep a Changelog) documents 0.1.0; `.github/workflows/release.yml` builds on a `v*` tag, stamps the tag version into the build (`versions:set`, pom stays `-SNAPSHOT`), and publishes a GitHub Release with the fat JAR attached and notes pulled from the changelog. Cut one with `git tag -a v0.1.0 -m … && git push origin v0.1.0`.
- [x] **Classgraph `Unsafe` startup warnings** — the `bin/midgie` launcher adds `--sun-misc-unsafe-memory-access=allow` on JDK 23+ (the flag is unknown to JDK ≤ 22, which doesn't warn). The warnings go to the native stderr, bypassing `System.err`, so an in-process filter is impossible — verified empirically. Direct-JAR users: `JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

## G. Test coverage gaps — done
- [x] **Command-layer offline tests** — `CliTest` drives the real CLI offline: missing LIVE, `--url` vs LIVE exclusion, bad `--scope`/`--equivalence`/`--multivalue`/`--path`/`--format` → exit 2, `apply` without `--url`/password → exit 2, plus drift/no-drift exit codes.
- [x] **Golden-file test** (`GoldenOutputTest`) pins the exact `ObjectModificationType` XML; equivalence metadata-sensitivity added to `DiffCommandTest` (`scope=full` delete already covered there).

## H. `deploy` — apply a whole folder tree in dependency order (done)

A new subcommand that discovers desired-state object XML under one or more paths, sorts by dependency, and applies each via the existing engine, **one JVM bootstrap** for the whole tree (a shell loop calling the tool per file would pay the ~1.5 s prism bootstrap each time). **Structure-agnostic**: objects are found by content, so the config tree can use any folder layout. Full design in DESIGN.md → "deploy — apply a folder tree". Reuses schema registration, `DiffEngine`, and the REST/creds/TLS/retry mixins.

**Increment 1 (built): read-only discovery + schema-aware dependency sort.** `deploy <path...>` discovers the tree, sorts it, and prints the apply order; offline it never writes. The order is **two tiers**: extension *schemas* first (raw `.xsd` inputs and deployable `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered — all sources in one batch, since prism's `registerDynamicSchemaExtensions` replaces the dynamic set — so objects using extension attributes parse), then *objects* (grouped by identity — OID, else type+name — and sorted by outbound reference OIDs). Several files may be *fragments* of one object, each its own apply unit; a genuine contradiction (a different type under one OID, or a single-valued item with differing values) is an error, but same-identity non-overlapping fragments are fine. Cycles are broken and warned, not fatal. New classes: `DeployCommand`, `Discovery`, `SchemaFile`, `Fragment`, `DeployObject`, `ReferenceCollector`, `TopoSort`; `SchemaOptions` gained a `collectSchemas`/`newBaseContext` split.

**Increment 2 (built): the apply loop + `--dry-run`.** With `--url`, each unit is reconciled in the sorted order — search for the current object (by OID, or by type+name for `--match-by-name`), diff the fragment (`--scope present`), then PATCH, or POST with `--create-if-absent`. Sequential, so a role is created before the user that references it. Writing is gated: a single up-front confirmation by default (`--yes` to bypass, `--confirm-each-apply` per change, `--dry-run` to preview; non-interactive without `--yes` refuses). New: `Reconciler`, `MidpointRestClient.searchObjects`. Existence is checked by search (not `GET`) so a missing object is an empty result, not a 404 that midPoint logs — shared in `LiveLookup`, and adopted by `diff --url`/`apply` too.

**Increment 3 (built): retry, saved plans, strategy policy.** `--retry` re-applies the failed set round after round until it stops making progress, then fails on anything stuck (catches by-name/filter refs the static graph can't order). `--plan <dir>` writes the ordered per-object changes as `NNN-<type>-<oid>.{create,patch}.xml` files (reconciling against a local projection so a second fragment of a new object becomes a patch); `--apply-plan <dir>` replays them verbatim (POST creates, PATCH patches, in filename order). `--policy <file>` maps `<selector> key=value` lines — a glob over `Type/Name/item…` with `*`/`**` at any level, most-specific wins — to a per-object/item diff strategy. New: `PlanStore`, `Policy`, `GlobPath`; `Reconciler` gained per-call strategy + `planAgainst`.

- [x] ⭐ **Discovery (by content)** — recurse the paths; classify by root element; register raw `.xsd` and exported `SchemaType` `.xml` schema first (all sources in a single batch), then parse the rest as `PrismObject`s. Skip non-object XML with a warning; group same-identity files as fragments of one object; a genuine fragment contradiction is an error (a duplicate OID is *not*).
- [x] ⭐ **Dependency sort** — two tiers. Schemas sort by `<xsd:import>` namespace dependency; objects sort by their outbound `ObjectReferenceType` OIDs (prism reference-visitor); edge A→B when B is in the set; Kahn topo-sort with a deterministic tiebreak; cycles broken arbitrarily and warned.
- [x] ⭐ **Apply loop** — reconcile per object in sorted order (diff → PATCH, or POST with `--create-if-absent`; `--match-by-name` reconciles OID-less objects by type+name). Per-object result (created/patched/in-sync/skipped/failed) + a summary; honours `--quiet`. Per-object/item strategy via the `--policy` file.
- [x] **Retry-until-stable** — `--retry` re-applies the failed set until no progress, then fails non-zero on anything stuck.
- [x] **Plan / intermediate deltas** — `--dry-run` prints the plan + deltas and changes nothing (exit 1 on drift); `--plan <dir>` writes ordered, numbered per-object create/patch files for review; `deploy --apply-plan <dir>` replays them in order (the "apply a saved change" mode).
- [x] **Tests** — unit: sort correctness, discovery skips non-objects, same-identity fragments group, contradictions error, the reconcile decision table, the REST search, policy selector matching / most-specific-wins / validation, plan-file round-trip, and stub-server retry convergence. Integration (`docker/` rig): deploy a small tree with dependency order + idempotent re-run, `--match-by-name`, the non-interactive refuse, the `--plan`→`--apply-plan` round-trip, and a `--policy` per-object override.

## I. Delete detection, `--prune` and `init` (done)

Three-way merge against what midgie last applied, so `merge` can be honest about removals. A two-way diff
cannot see a removal at all: drop a value from a file and the delta comes out empty while the value lives
on. Comparing desired against **last-applied** — `git show <lastRev>:<file>` for a version-controlled tree —
separates "you removed this" from "someone else added it". Full design in DESIGN.md.

- [x] ⭐ **Value-level detection** — per fragment file, so an object assembled from several files works
  without a special case: a deleted fragment yields exactly the values it contributed, minus anything a
  surviving sibling still declares. The object itself is never a deletion candidate, and neither is its
  `name` or any mandatory item.
- [x] **Deployment identity** — the repository's first-parent root commit plus the tree's path within it:
  content-addressed, so every clone and remote agrees. `--app` overrides the key; not-a-repository and
  shallow clones disable detection with a note either way, since recovering last-applied needs history.
- [x] **Server-side state** — a `midgie-state` `LookupTableType`, one row per deployment, created only by
  `midgie init`. Self-describing and `indestructible`; `lifecycleState=archived` switches tracking off.
  The recorded revision is verified before use and validated as a commit sha before reaching git's argv.
- [x] **`--prune`** — applies the detected removals: opt-in, `--dry-run`-able, separately confirmed, run
  after the tree is applied and only when the whole tree really was applied. Its delta is built solely from
  values that were in last-applied, so it cannot reach accrued or foreign state.
- [x] **`init --write-policy`** — seeds a starter policy from the tree, suggesting natural-keys + merge only
  for containers that are keyed *and* carry the whole key.
- [x] **Tests** — `RemovalDetectorTest` (dropped value, still-declared value, nested value moved between
  fragments, deleted fragment, orphaned object, `pruneDelta` shape), `GitRevisionTest` (degradation outside
  a repository, identity derivation, revision verification and the argv guard), plus the rig.

## K. Rollback plans as a deploy safety net (done)

Every `deploy` that writes leaves the delta that puts each object back, and `midgie rollback` replays it. Full design in DESIGN.md → "rollback — undo the last deploy". Investigated and rejected first: midPoint's own object history (`ModelAuditService.reconstructObject`) is not exposed on the REST API at all, `AuditController.rollBackTime` throws on any ADD/DELETE delta in the trail — which `--create-if-absent` always produces — and audit is trimmed to `P3M` by the shipped cleanup policy.

- [x] ⭐ **Old values on every replace** — prism computes the old value and throws it away: `PrismPropertyImpl.fixupDelta` (overridden for single-valued properties alone) calls `delta.clear()` and rewrites the add/delete pair as a bare replace. `createReverseDelta` reverses a replace by substituting `estimatedOldValues` and, finding none, emits an *empty* replace that would clear the item instead of restoring it. `DiffEngine` now fills them from the live object; `DeltaConvertor` round-trips them as `estimatedOldValue` with no change to `PlanStore`. Protected values are never recorded. Verified on the rig: midPoint accepts the larger PATCH and applies it identically.
- [x] ⭐ **Plan files are output, not input** — a `.create.xml` is a whole object, so a plan directory inside a tree was discovered and redeployed (`deploy /config --plan /config/plan` self-poisoned). Three layers: a generated-file marker in the content that `Discovery` skips (survives rename/move/commit/CI artifact, and keeps discovery content-based), canonical-path exclusion of the `--plan`/`--rollback-plan` directories, and no descent into dot-directories.
- [x] ⭐ **`--rollback-plan`** — written on every writing run, in reverse apply order, as ordinary `PlanStore` files plus a `manifest.xml` holding the server, deployment identity, git revision and each object's post-write repository version. Creates are recorded but never undone. `--prune` inverses included. Newest five runs kept per deployment.
- [x] **`midgie rollback`** — replays the newest complete plan. Refuses on provenance mismatch (server or deployment; no override) and on staleness (an object's version moved since the deploy; `--force` proceeds). Patches only, so it can never delete.
- [x] **Tree-local `.midgie.properties`** — the rollback base path is per-deployment (`$XDG_STATE_HOME` is wrong for a k8s Job whose pod FS dies with the net). Read from the nearest file at or above the working directory, lowest precedence of the file layers, gated by an **allowlist** rather than the `REFUSED` denylist because it arrives with somebody else's repository. This also unparks half of the deferred tree-config work; the `url`-in-a-tree-file question stays parked, deliberately.
- [x] **Tests** — `DiffCommandTest` (old values recorded, protected values never, reverse restores a replace and an accrued multi-valued wipe), `RollbackPlanTest`, `RollbackCommandTest` (both refusals), `DeployCliTest` (marker, dot-dirs, nested plan dir), `ConfigDefaultsTest` (allowlist, precedence, the user-file collision).

- [ ] **`--auto-rollback` on failure.** Deliberately not built. midPoint has no cross-object transaction, so unwinding a partly-applied tree can land in a third state that is neither old nor new; the failure that would trigger it is often the one that prevents it (auth, transport, server down); and it collides with `--retry`. Revisit only with a concrete case where a human deciding is too slow.
- [ ] **`rollback --to <rev>`.** The GitOps variant: materialise the tree at an older commit and deploy it with prune, rather than replaying one run's inverses. Needs revision *history* in `midgie-state` (only the current revision is stored today) and it is the one form of rollback that deletes objects.

## J. Optional / deferred

- [ ] **`--test-resources` after an apply/deploy.** midPoint exposes `POST /resources/{oid}/test`, which runs
  the connector test and returns an `OperationResult`. Running it for every `ResourceType` a run touched
  would turn "the PATCH was accepted" into "the resource actually works" — the gap that let a broken
  `connectorRef` sit unnoticed. Opt-in, since it talks to the target system.
- Investigated and rejected: the `exclude` retrieve option. It is honoured for ordinary items
  (`?exclude=assignment` on the administrator: 14353 -> 11704 bytes) but ignored for `_metadata`/`metadata`,
  and `include` is not a projection. Nothing to gain here anyway — midgie diffs the whole object, so
  omitting any part of the live side would make it look absent and, under `--scope full`, invite a deletion.

- [x] **Fragment union engine.** Built. All fragments of one object are combined into a single desired object (`FragmentUnion`, built at group time by `DeployObject`) and reconciled **once**, so the apply unit is the object rather than the file. Cross-file multi-valued composition is now correct by default — the "last-one-wins" clobber warning is gone with the hazard, and a tree that set `multivalue=merge` only to make its own fragments compose no longer needs it (merge bought composition at the price of removals, since a value deleted from a file then yields an empty delta). Entries are paired by container id, else by `a:naturalKey` (`NaturalKeys.match`, shared with the diff's accrued-value guard); unpairable values are appended and de-duplicated by real value. Creates are atomic (one POST for the whole object), the `--plan` local projection that existed only to model POST-then-PATCH is gone, and delete detection compares whole objects on both sides — which also makes a value *moved between files* stop reading as a removal. Deferred trade taken knowingly: one bad file now fails its whole object instead of failing alone.

- [ ] **Re-scope the "auto" strategy defaults.** The original ask was an `auto` mode deriving `--multivalue`/`--equivalence` per object at deploy time — as if `init --write-policy` had just run — so a policy file stops being practically required. The union changes what that needs to do: composition, the most common reason a fragmented tree reaches for `multivalue=merge`, no longer needs merge at all. What is left for merge is **partial ownership** (your file declares 3 of 10 GUI panels because the rest are midPoint's) and **genuine accrual** (lookup-table rows, a logger an admin added). So the likely shape is **equivalence-only**: pick `natural-keys` per object when it has keyed containers whose keys are filled, so matched entries update in place with their container ids and metadata intact, while `multivalue` stays `replace` and merge remains an explicit policy choice. That has no drift-detection hole and no `--prune` dependency. Decisions already taken: spell it as an `auto` value on both knobs (so it works in `midgie.properties` and per object in a policy file) rather than a master switch, and let it default on. Two findings to keep:
  - A **deny list** of containers auto must never merge (`assignment`/`inducement`, `authorization`) can only ever exempt them from *merge*, never from natural-key *pairing*: prism takes one equivalence per whole-object diff (`DiffEngine.reconcile` → `live.diff(desired, strategy)`), so an object-wide `natural-keys` applies to every container in it.
  - **Per-path equivalence** would need the desired object split into buckets, each diffed under its own equivalence and recombined before anything downstream sees the delta. prism's per-item `Item.diff(other, strategy)` cannot be used for it — its contract is "compares item values only — does NOT dive into lower levels", so it yields flat add/delete instead of the deep in-place edits the recipe depends on — and the bucketing relies on `--scope present` (under `full`, absence means deletion, so the buckets need explicit path filtering instead).

> **Naming:** now that `deploy` applies, the tool does more than "diff", so it was renamed **midgie** (a wee Scots midge). The rename covers the artifact/JAR, launcher, main-class command name, message prefix, docs, CI, and the GitHub repo. Left as-is on purpose: the Java package `de.is4it.midpoint.diff`.
