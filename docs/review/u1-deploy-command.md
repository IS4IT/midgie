# u1 — `DeployCommand`, `InitCommand`

Reviewed at `740747f` (working tree). Scope: every write path, the confirmation gates, apply/prune/tracking/plan
orchestration, and the `deploy`/`init` user-facing output.

Format: `SEVERITY | file:line | failure | fix`

## Findings

blocker | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:266 | The prune gate only tests `exit == 0`, and a declined up-front confirmation returns `0` — so `deploy --prune` on an interactive terminal asks "Apply 12 change(s)?" → `N` ("nothing was applied"), then immediately asks "Remove 5 previously deployed value(s)?"; answering `y` deletes live values although the operator refused the write and none of the desired state was applied, inverting the documented "prune runs *after* the tree is applied" ordering. | Gate the block on the decline too: `if (prune && !removals.isEmpty() && planDir == null && !declined)`; the same flag already guards `recordRevision`.

major | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:280 | `recordRevision` fires on any `exit == 0`, including runs that wrote nothing: `--confirm-each-apply` with every unit declined (counted as `skipped`, so `failedUnits` is empty), a run where every unit was `SKIP` (OID-less, no `-c`/`-m`), and a declined prune (`confirmPrune` returns `0` without setting `declined`). The recorded revision becomes the new baseline, so the removals that run just reported are never detected by any later run — the pending deletions are silently and permanently lost. | Only record when the run actually landed the tree: carry the pass's declined/skipped counts (and set `declined` in `confirmPrune`'s `ABORTED` branch) and skip `recordRevision` unless everything was created/patched/in-sync.

major | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:647 | `pass()` discards the OID `Reconciler.execute` returns, so each fragment is planned from scratch. For an OID-less object described by two fragment files (a first-class case — `DeployObject` groups by `type/name`), `deploy --create-if-absent` without `--match-by-name` plans `CREATE` for both and POSTs the object twice: the second POST either collides on midPoint's unique `name` and fails the unit (exit 2 for a half-applied tree) or duplicates the object. DESIGN increment 2 explicitly promises "a second fragment patches the object the first created" — which only holds when the fragments carry an OID. | Keep a `Map<identityKey, createdOid>` in `pass()`, fill it from `execute`'s return value, and set that OID on the later fragment's object (or force a by-name resolve) before calling `reconciler.plan`.

major | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:726 | In the `--plan` projection the second fragment of an OID-less object is planned via `planAgainst` against `desired.clone()`, whose OID is null, so `Reconciler.decide` yields a `PATCH` with `outcome.oid == null`; `PlanStore.write` then falls back to the sanitised *name* as the filename token and `--apply-plan` replays it as `PATCH /ws/rest/users/<name>` → 404. The saved plan is reviewed as valid and only fails when it is executed against the real server. | Refuse to write (or fail the unit in) a `PATCH` plan entry with a null OID — an OID-less create must either be given a generated OID in the projection or be reported as unplannable.

major | src/main/java/de/is4it/midpoint/diff/InitCommand.java:174 | `seedPolicy` registers a *second* schema batch on the context already built by `schemaOptions.buildContext(client)`; because `registerDynamicSchemaExtensions` **replaces** the dynamic set and `collectSchemas(prismContext, null)` deliberately omits the live read, every extension schema read from the server is dropped as soon as the tree contains one schema file (or `--schema` is passed). Objects using a server-registered extension attribute then fail to parse, print "could not read … while seeding the policy" and are missing from the suggestions — exactly the failure the method's own comment sets out to prevent. | Follow `DeployCommand`: build with `SchemaOptions.newBaseContext()` and register tree + CLI + live schemas in one `SchemaSupport.register` call (at minimum, pass `client` to `collectSchemas` here).

minor | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:195 | `--apply-plan` returns before `--prune`, `--policy`, `--create-if-absent`, `--match-by-name` and the `PATH` arguments are ever read; all of them are accepted and silently ignored, so `deploy tree --apply-plan plan/ --prune --url …` reports success while never detecting or removing anything. | Reject the ignored combinations up front, next to the existing `--plan`/`--apply-plan` mutual-exclusion check.

minor | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:391 | With `--plan` the prune block is skipped (`planDir == null` guard) but `reportRemovals` still prints "(--prune: removing)", telling the operator values were deleted that are still on the server; `--dry-run` and `--retry` are likewise ignored in the `--plan` path (plan files are written despite `--dry-run`). | Make the removal wording mode-aware (`--plan` → "reported only") and reject `--plan` together with `--dry-run`/`--retry`.

minor | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:800 | `applyEntries` drops entries the operator declines under `--confirm-each-apply` from neither the applied nor the failed set, so `--apply-plan --confirm-each-apply` with every entry declined prints "Done: 7 applied, 0 failed." and exits 0 — the log claims writes that never happened. | Count declines separately and report them ("n declined"), as the tree path does with `tally.skipped`.

minor | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:612 | The `Done: … n failed.` summary is emitted *before* `--retry` runs, so a run whose retry rounds fix everything ends with a summary saying units failed and an exit code of 0; a pipeline (or human) reading the last summary line disagrees with the exit code. | Emit `emitSummary` after the retry loop, or re-emit a final line once the failed set is known.

minor | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:342 | Two paths disable removal tracking with no user-visible word: a failure reading `midgie-state` (e.g. the deploy account lacks read on `lookupTables`) is only `LOG.debug`ged, and `--app` on a tree outside git yields `currentRevision == null` so `canRecord()` is false. Every other untracked case prints a `note:`; here a run silently stops detecting and recording removals and looks identical to a healthy one. | Emit the same `note:` in the `catch` and when `git.head()` is null under `--app`, naming the reason.

minor | src/main/java/de/is4it/midpoint/diff/InitCommand.java:119 | `--write-policy` is validated (missing `PATH`, target file already exists) only *after* `SyncState.create` has already created the state object on the server, so a mistyped invocation exits 2 having half-completed — the operator sees an error for a command that did in fact change the server. | Validate `writePolicy`/`path` at the top of `call()`, before the client work.

## Checked and found sound

- Non-interactive refusals: `--confirm-each-apply` without a console (both `runApply` and `runApplyPlan`) and
  `decideConfirmation` → `NON_INTERACTIVE` correctly exit 2 rather than writing.
- Exit-code contract per branch: `--dry-run`/`--plan` 0 in-sync / 1 would-change / 2 on any unit error; real
  apply 0/2; declined write 0.
- `isFatal` — `RestException` is an `IOException` subclass, but the pattern-match branch returns first, so only
  401 aborts while a per-object 4xx/5xx is recorded and the run continues; connection/timeout arrive as a plain
  `IOException` and correctly abort.
- Prune ordering and the ownership guard: prune runs after the apply pass, `stillLive` re-reads the object and
  drops anything the server no longer holds by `REAL_VALUE`, and a partial prune failure returns 2 so the
  revision is *not* recorded and the leftovers are re-detected next run.
- `retryUntilStable` terminates (no-progress break plus `MAX_RETRY_ROUNDS`), and `--retry` does not re-prompt
  the up-front gate.
- Schema registration in `deploy` is a single `SchemaSupport.register` batch with tree/CLI sources ahead of the
  live read, so the per-namespace dedupe keeps the local copy.
