# 99 — synthesis

Sources: `00-inventory.md`, eight per-unit reviews (`u1`–`u8`), five cross-cutting passes (`x1`–`x5`).
Reviewed at `740747f` (working tree, four unpushed commits included).

**114 raw findings → 98 unique** after collapsing 16 duplicate reports: **2 blocker · 35 major · 61 minor**.
Nearly every finding was reproduced by running something — a probe against `target/midgie.jar`, a stub server,
a real git repository, or the docker rig — rather than inferred from reading.

The duplicates are the useful signal. Three independent reviewers with no shared context found the GET-only
retry; four found the revision being recorded for a tree that was never applied. Those are the two most
certain findings in the set.

---

## Themes

### A. The prune path can delete state it should not (2 blocker, 4 major)

The newest code in the repo, and where both blockers landed. These compose: each is bad alone and worse
together.

- **`DeployCommand.java:266`** — the prune gate tests `exit == 0`, but a *declined* apply also returns 0.
  Decline "Apply 12 change(s)?" → still prompted "Remove 5 previously deployed value(s)?" → `y` deletes,
  inverting the ordering the code's own comment states. The `declined` flag exists two lines below. *(u1)*
- **`RemovalDetector.java:236`** — the survivor set is enumerated by diffing each fragment against an *empty*
  object, which yields **top-level** paths; the removal side yields **deep** ones. `stillDeclares` therefore
  never matches a nested value, so moving `deploymentInformation/headerColor` between two `examples/branding`
  fragments makes `--prune` delete the value the sibling declares and the apply just wrote. `stillLive` does
  not save it — the server does hold it. *(u4)*
- `RemovalDetector.java:181` — a deleted fragment is dismantled including the object's mandatory `name` when
  the surviving sibling identifies by OID and omits `<name>`. *(u4)*
- `RemovalDetector.java:150` / `:306` — an unrecoverable last-applied revision (squash-merge, force-push, GC,
  git timeout) is indistinguishable from "nothing was removed": no output, exit 0, and the baseline still
  advances, so those removals become permanently undetectable. *(u4, x3)*
- `RemovalDetector.java:256` — a swallowed declaration-diff failure leaves a partial survivor set, with the
  same consequence as the blocker. *(x3)*
- `RemovalDetector.java:336` — survivors keyed "OID else Type/Name", so an OID-carrying and an OID-less
  fragment of one object never subtract from each other. *(u4 — see theme B)*

### B. Fragment identity has no bridge between OID and Type/Name (1 major + 5 across units)

The single highest-leverage structural finding, and nobody was looking for it: five reviewers hit the same
root cause from five directions. `Fragment` identity is "OID, else `type/name`", with nothing joining the two
forms, so one object described by an OID-carrying fragment plus an OID-less one becomes **two of everything**.

| site | consequence |
|---|---|
| `Fragment.java:64` *(u5)* | two graph nodes: no cross-fragment contradiction check, no clobber warning, no inbound edge |
| `Reconciler.java:97` *(u5)* | `-c` without `-m` never resolves an OID-less object → second pipeline run re-POSTs it → 409, exit 2 |
| `DeployCommand.java:647` *(u1)* | `pass()` discards the OID `execute` returns → two fragments of one OID-less object both POST |
| `RemovalDetector.java:336` *(u4)* | the two keys never subtract → a value both declare is pruned |
| `Policy.java:178` *(u6)* | the OID-only fragment matches no `Type/Name` selector → resolves to a *different strategy* than its sibling, so its multi-valued items are replaced instead of merged |

One fix — index groups by `(type, name)` as well as OID and let an OID-less fragment join the OID-carrying
group — closes all five.

### C. Delta correctness (2 major, 2 minor)

- **`DiffEngine.java:580`** — `applyMerge`'s "cannot merge, leave it as replace" test is
  `getValuesToReplace() != null`, but prism only emits a whole-value replace for single-valued *properties*
  (`fixupDelta` is overridden in `PrismPropertyImpl` only). A changed single-valued **reference** diffs to
  delete+add; merge strips the delete and emits an `add`-only delta on a single-valued item, silently. The
  server rejects it (`Attempt to put more than one value to single-valued item`) and `--plan` throws the same
  exception locally. Hits `connectorRef`, `resourceRef`, `objectRef`/`ownerRef`, nested `targetRef`. *(u2)*
- **`DiffEngine.java:293`** — the accrued-in-place guard tests only the *outermost* container while
  `applyMerge` matches the full id-free path, so a per-path merge rule on a nested container never arms the
  guard for the edits underneath it (and the inverse gives spurious exit-2s). *(u2)*
- **`NaturalKeys.java:56`** — *promoted from minor.* `carries` never checks that *all* declared constituents
  are present, so a value carrying half a composite key passes. midPoint's most-edited keyed container is
  composite (`ResourceObjectTypeDefinitionType` = `kind` + `intent`); omitting `<intent>` is routine, the
  warning that would explain the outcome never fires, and under `replace` the live `objectType` is deleted.
  The same leniency makes `init --write-policy` recommend `natural-keys + merge` for a container whose key
  cannot discriminate. *(u2)*
- `DiffEngine.java:486` — the partial-declaration warning mixes denominators and can print arithmetically
  impossible counts ("declares 1 of 2 … the other 2 will be DELETED"), understating what is at risk. *(u2)*

### D. Success reported for work that did not happen (1 major theme, 5 sites)

- **`DeployCommand.java:280`** *(u1, x3, x4 — four independent reports)* — `recordRevision` fires on any
  `exit == 0`: every unit `SKIP`ped, every unit declined under `--confirm-each-apply`, or the prune declined
  (`:900`). The recorded revision becomes the new baseline, so the removals just reported are never detected
  again — permanently lost.
- `DeployCommand.java:532` *(x3)* — a file Discovery classified as an object but prism cannot parse is
  dropped with a warning and `deploy` exits 0; under `--quiet` nothing is printed at all.
  **Reviewer disagreement:** u5 lists this as "checked and found sound" (warnings, not aborts). I side with
  x3 for `deploy` — silently not deploying a file is the failure this tool exists to prevent.
- `DeployCommand.java:800` — `--apply-plan --confirm-each-apply` with entries declined prints
  "Done: 7 applied, 0 failed." *(u1, x3)*
- `DeployCommand.java:612` — the `Done: … n failed.` summary is emitted *before* `--retry` runs.  *(u1)*
- `DeployCommand.java:341` — a `midgie-state` read failure (403 on `lookupTables`) is DEBUG-only, silently
  killing detection *and* recording, unlike all three neighbouring branches which `note(...)`.
  **Reviewer disagreement:** u1 minor vs x3 major; x3 is right — it is indistinguishable from a healthy run.

### E. Exit-code contract (1 major, several minor)

- **`Main.java:34`** — picocli's default `exitCodeOnExecutionException` (**1**) is left in place and both
  commands construct their client *outside* the try that maps errors to 2. A typo'd `--cacert` or a malformed
  `MIDPOINT_URL` exits **1** — which the contract defines as "drift found" — with a raw stack trace. A CI gate
  keyed on `1 = drift` reports phantom drift. *(u7)*
- `Main.java:30` — bare `midgie` prints help to **stdout** and exits 0. *(u7)*

### F. REST client (2 major, 5 minor)

- **`MidpointRestClient.java:241`** *(u3, x3, x5 — three independent reports)* — `retryable` is
  `"GET".equals(method)`, but every object read is now `POST {type}/search`. `--retries` does not cover the
  tool's primary read; with `--quiet --no-live-schema` it is a complete no-op, and the "rides out a server
  that is still starting" promise in README:242 / DESIGN:133 is not kept.
- **`MidpointRestClient.java:285`** *(u3, x1)* — a URL carrying userinfo is kept verbatim in `restBase` and
  printed to **stderr** on any connect/timeout failure, ungated by `--quiet`/`--debug` — straight into an
  archived CI log — while being ignored for authentication. Pure leak, zero function.
- `MidpointRestClient.java:134` — `--insecure` sets a JVM-global property the JDK latches into a
  `static final` on first `java.net.http` use, so it is order-dependent in both directions.
  **Reviewer disagreement:** u3 minor vs x4 major. x4 verified the latching empirically and found it already
  bites the test JVM (`LiveIntegrationTest:65` disables verification for every later test).
- `VersionSupport.java:85` — the version guard silently never runs for a least-privilege account (403 on
  `GET /nodes`) — the exact silent-misparse hazard CLAUDE.md calls out.
- Plus: TLS/PKIX errors with no `--cacert` hint, transport errors classified by message text, backoff
  overflow at `--retries 55`, dead `LiveTarget.resolve`.

### G. Plan-mode integrity (2 major, 2 minor)

- **`PlanStore.java:69`** *(u1, u5)* — a `PATCH` entry with a null OID falls back to the sanitised *name* as
  the filename token; `--apply-plan` then issues `PATCH /ws/rest/users/<Name>` → 404. The plan reviews as
  valid and fails only at execution. Reachable today via any OID-less second fragment.
- **`DeployCommand.java:709`** *(x2)* — plan files are written in an unguarded loop, so a mid-loop failure
  exits 2 leaving a **partial** plan that `--apply-plan` replays reporting success (reproduced: 2 of 4 units,
  then "Done: 2 applied, 0 failed", exit 0). A re-plan is refused until the directory is deleted by hand.
- `PlanStore.java:110` — `parse()` validates nothing, so a hand-edited or CI-shipped plan can retarget a write.
- `PlanStore.java:70` — `%03d` breaks ordering past 999 units.

### H. Policy / glob resolution (3 major, 4 minor)

`GlobPath.java:103` (an `oid:` rule loses to a type-wide one — the pinned object gets full-replaced);
`GlobPath.java:60` (`normalize` strips up to the first `:` in the *name* segment, so colon-named objects
mis-target); `Policy.java:178` (per-fragment resolution — theme B); `Policy.java:194` (CLI baseline rules win
ties over the policy meant to override them); `DiffEngine.java:749` (identity-scoped and `--path` rules
compared on incommensurable literal counts). Plus: no rule is ever reported as unmatched, so a typo'd
selector silently degrades to the baseline; `init --write-policy` can emit lines `Policy.load` rejects.

### I. The test suite is less honest than it looks (4 major)

- **`PolicyTest.java:59`** — no prism bootstrap; passes only because another class left the static
  `PrismContext` set. `mvn -o test -Dtest=PolicyTest` → **3 errors**.
- **`RemovalDetectorTest.java:49`** *(u8, x3)* — all five tests (and 3 of 6 in `GitRevisionTest`) bail with a
  bare `return` when git is unusable, and **JUnit reports a bare return as PASSED**. On a box without git — or
  with `commit.gpgsign` set — the entire prune-safety suite is green having asserted nothing.
- **`RemovalDetector.pruneDelta`** — the code that builds the `delete` itemDeltas `--prune` PATCHes has
  **no test at all**. DESIGN's central safety claim rests on two lines nothing exercises.
- **`InitCommand`** — a top-level subcommand with zero tests.
- `pom.xml:127` *(x4, u8)* — the suite reads ambient `MIDPOINT_URL`/`MIDPOINT_PASSWORD`; `MIDPOINT_URL=… mvn
  test` fails 20 tests and turns offline tests into live reads.

### J. Ops & supply chain (2 major, 3 minor)

`release.yml:63` — the one job holding `contents: write` calls `softprops/action-gh-release@v2` and
`actions/checkout@v4` by floating tag (the tj-actions pattern). `docker-compose.yml:83` publishes an
admin-credentialed midPoint on **every** interface, in a file that already explains why Postgres is not
published. Plus: RCs published as "Latest", `bin/midgie` mis-parses an EA JDK version, CI never tests JDK ≥ 23
though the maintainer builds on 26, and the shaded JAR drops the Apache-2.0 `NOTICE` files it must
redistribute — on a project running a REUSE-compliance job.

### K. Documentation drift (4 major, 16 minor)

Two are self-inflicted and known: DESIGN:82 still says `--prune` "would be a later, opt-in step", and
DESIGN:83 holds a spliced sentence that no longer parses. `DeploymentIdentity.java:58` — `--app` returns
*before* the shallow-clone/not-a-repo guards, contradicting README:119 and DESIGN:95 and silently detecting
nothing. The rest are stale-by-a-release: README's delete-detection sample output, the `merge` "never
reported" passage that now argues against a gap the tool closed, `--oid` accepted and ignored by `deploy`,
`init` missing from the exit-code table and the modes list, TODO's preamble predating the whole feature area.

---

## The 10 to fix first

Ranked by severity, then by blast radius (how much live config a failure can destroy), then by how easily an
ordinary invocation reaches it.

| # | finding | why first |
|---|---|---|
| 1 | `DeployCommand.java:266` — declined apply still prompts to prune | **blocker**; deletes after the operator refused the write. One-line fix, flag already exists |
| 2 | `RemovalDetector.java:236` — nested survivor never matches | **blocker**; `--prune` deletes a value a sibling declares. Composes with #1 into unrecoverable loss |
| 3 | `RemovalDetector.java:181` — `name` offered for deletion | corrupts or fails the whole prune group on a legal fragment layout |
| 4 | `DeployCommand.java:280`/`:900` — revision recorded for a tree never applied | permanently destroys the removal set; found independently four times |
| 5 | `RemovalDetector.java:150` + `DeploymentIdentity.java:58` — unrecoverable/skipped detection reads as "nothing removed" | silent *and* permanent; `--app` bypasses the guards both docs promise |
| 6 | `DiffEngine.java:580` — single-valued reference under merge | every `connectorRef`/`targetRef`; server rejects the PATCH, `--plan` throws locally |
| 7 | `GitRevision.java:158` — server-side revision string interpolated into `argv` | remote-controlled data reaching git as an *option*; `--output=` file write verified |
| 8 | `MidpointRestClient.java:241` — `--retries` misses the only read path | a documented feature is a no-op; found independently three times |
| 9 | `MidpointRestClient.java:285` — credentials in the URL printed to stderr | password into archived CI logs, ungated by `--quiet` |
| 10 | theme B — fragment identity, one fix at `Fragment`/`DeployObject` grouping | closes five majors across five units at once |

Just missed: `Main.java:34` (hard errors exit 1 = "drift"), the test-suite honesty cluster (§I — it is what
would have caught #2 and #4), and `DiffEngine.java:293`.

---

## Skeptic pass

Re-read of every blocker and of the majors that made the top 10, arguing the other side. Two were talked
down; the rest survived and are noted with what I tried.

**Talked down — severity reduced:**

- `DeployCommand.java:266` was reported as deleting silently. It does not: `confirmPrune` issues its own
  explicit "Remove N previously deployed value(s)?" prompt, so the operator must type `y` a second time.
  On its own that makes it a major, not a blocker. **It stays a blocker only because of what it composes
  with** — with #2 unfixed, a declined apply followed by an accepted prune deletes a nested value that the
  desired tree still declares and that the declined apply never re-wrote. That is unrecoverable from the
  tree. Kept at blocker, reasoning recorded.
- `x5`'s `pom.xml:15` claim (that a `0.1.0-SNAPSHOT` version contradicts TODO's "no more hardcoded 0.1.0")
  misreads TODO: the claim there is about the *mechanism* — `VersionProvider` reading the filtered
  `build-info.properties` instead of a constant — which is true. Downgraded to a cosmetic note about local
  builds.

**Survived, and what I tried to kill them with:**

- **`RemovalDetector.java:236`** — I checked whether `emptyLike` might produce an object that already carries
  the container (it cannot — it is an empty object of the same type) and whether the removal side ever
  produces a top-level path (it does: when a fragment drops the container *entirely*, which is precisely the
  top-level-only case `RemovalDetectorTest` covers and why the tests are green). The asymmetry is structural.
  Confirmed by reading `declarationsByObject:245` and `stillDeclares:279` directly.
- **`DeployCommand.java:280`** — I looked for a decline path returning non-zero that would keep
  `recordRevision` away. `NON_INTERACTIVE` returns 2, but an *interactive* decline returns 0 by documented
  design, and the per-unit decline branch never touches `declined` at all. No escape.
- **`DiffEngine.java:580`** — the strongest counter-argument is that it needs opt-in `--multivalue merge`.
  But merge is exactly what README's natural-keys recipe instructs, and what the "sensible defaults"
  direction points at; a latent trap in the mode being recommended is worth more, not less.
- **`MidpointRestClient.java:241`** — I tried "the `GET /schemas` and `GET /nodes` calls absorb the startup
  wait anyway". They do, which is why nobody has hit it — but `--no-live-schema` skips the first and
  `--quiet` skips the second, and both are normal in CI. The mitigation is accidental.
- **`GitRevision.java:158`** — I tried "the state object is `indestructible` and only midgie writes it". Both
  true and neither is a defence: `indestructible` blocks *deletion*, not row edits, and any account with
  write access to `lookupTables` can plant the value. The trust boundary is real — the revision comes off the
  network and goes into `argv`.
- **`TopoSort.java:87`** *(not in the top 10)* — the deliberate-conventions list blesses "arbitrary cycle
  break", which almost excludes it. It does not: the convention covers nodes *on* a cycle, and the finding is
  that an acyclic node gets mis-ordered and the warning names an object that is in no cycle.

**Not re-litigated:** the ~60 minors and the doc drift, which are mostly mechanical.

---

## Note on scope

This review changed no code. The two blockers and findings #3–#5 are all in the delete-detection work from
the last two rounds, which the inventory flagged as the least-settled area — that prediction held. The prune
path should not be considered safe to recommend until #1–#5 are fixed and §I's test gaps are closed, since
those gaps are precisely why #2 and #4 shipped green.
