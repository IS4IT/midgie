# Full code review — 00 inventory

Repository: `midgie` (folder `midpoint-diff`), single Java package `de.is4it.midpoint.diff`, Java 17, EUPL-1.2.
Reviewed at `740747f`. Excluded: `target/`, `assets/`, `examples/`, `demo.sh`, and the four unpushed
working-tree commits are **included** (they are the newest code and the most likely to hold defects).

## Phase 0 notes

**Static analysis: none configured.** `pom.xml` declares only `maven-resources-plugin`,
`maven-shade-plugin`, `maven-surefire-plugin` and `maven-antrun-plugin` (the `MP_VER` drift guard). No
spotbugs / pmd / checkstyle / errorprone / jacoco. Nothing was installed.

**`mvn -o dependency:analyze` — treat as already reported, will not be repeated as findings:**
- *Used undeclared:* `ch.qos.logback:logback-classic`, `com.evolveum.prism:prism-api`,
  `org.slf4j:slf4j-api`, `com.evolveum.commons:util`, `org.junit.jupiter:junit-jupiter-api` — all ride in
  transitively via `midpoint-schema` / `junit-jupiter`.
- *Unused declared:* `org.junit.jupiter:junit-jupiter` (the aggregator).

**Churn is weakly discriminating here.** The repository is 10 days old (2026-07-17 → 2026-07-27, 55
commits), so every commit falls inside the 12-month window and "commits" below is really "commits ever".
Risk is therefore rated mostly on blast radius, invariant density and recency of change, not churn alone.

**Deliberate conventions extracted from CLAUDE.md / DESIGN.md — never to be reported as findings:**
- `live.diff(desired)` direction, and `EquivalenceStrategy.REAL_VALUE` as the diff default.
- `--scope present`, `--multivalue replace`, `--equivalence real-value` defaults — alternatives were
  explicitly considered and rejected (DESIGN.md "Why replace + real-value stay the defaults").
- Source object XML stays **pure midPoint XML**; behaviour comes from flags/policy, never in-XML annotations.
- The offline diff core stays free of REST/HTTP; all HTTP lives in `MidpointRestClient`.
- Extension schema must be registered in a **single** `registerDynamicSchemaExtensions` batch.
- Exit codes: `diff`/`--dry-run` `0`/`1`/`2`; real `apply` `0`/`2` (a user-declined write is `0`).
- Cycles are broken and warned, not fatal; a per-object rejection is recorded and the run continues, while
  transport/auth failure aborts.
- `--quiet` drops advisories; `XmlPretty` returns its input unchanged on any surprise.
- The base-schema jar is version-coupled to the target midPoint (`midpoint.version` ↔ `docker/.env MP_VER`).

## Inventory

| module/package | purpose (1 line) | files | LOC | commits (12mo) | risk H/M/L + why |
|---|---|---:|---:|---:|---|
| `cli-commands` — `Main`, `DiffCommand`, `ApplyCommand`, `DeployCommand`, `InitCommand` | picocli entry points; flag validation, confirmation gating, orchestration, all user-facing output | 5 | 1844 | 58 | **H** — `DeployCommand` alone is 1085 LOC and orchestrates apply + prune + tracking + plan/apply-plan + retry; every write path and every confirmation gate lives here, and it changed in 4 of the last 4 commits |
| `diff-engine` — `DiffEngine`, `DeltaOutput`, `XmlPretty`, `NaturalKeys` | the core: prism diff plus scope/merge post-processing, safety detectors, delta rendering | 4 | 1265 | 14 | **H** — decides what is added *and deleted* on a live server; `DiffEngine` is 777 LOC with four interacting post-passes (scope retention, merge, protected-value strip, accrued-edit detection, input warnings); `XmlPretty` hand-parses XML that becomes the PATCH body |
| `rest-live` — `MidpointRestClient`, `ConnectionOptions`, `LiveLookup`, `LiveTarget`, `VersionSupport` | the only HTTP; credential resolution, TLS, retry/backoff, object lookup, version guard | 5 | 886 | 29 | **H** — handles credentials and TLS (`--insecure` sets a JVM-wide system property), is the sole write path to a live server, and any bug here is remote-state damage or a leaked secret |
| `deploy-pipeline` — `Discovery`, `Fragment`, `DeployObject`, `TopoSort`, `ReferenceCollector`, `Reconciler`, `PlanStore` | content-based discovery, fragment grouping, two-tier dependency sort, per-unit reconcile decision, plan files | 7 | 901 | 15 | **M** — decides *order* and the create/patch/skip/fail verdict; mistakes here mis-order writes rather than corrupt content, and it is well covered by `DeployCliTest`/`TopoSortTest`/`ReconcilerTest` |
| `delete-detection` — `GitRevision`, `DeploymentIdentity`, `SyncState`, `RemovalDetector` | three-way merge against last-applied: git plumbing, deployment identity, server-side state, removal set | 4 | 912 | 9 | **H** — newest code in the repo (all of it landed in the last 4 commits), it is what `--prune` deletes from, it shells out to a subprocess, and a review round already found 6 defects in it including two data-loss class |
| `strategy-policy` — `StrategyOptions`, `Policy`, `GlobPath` | flag/policy parsing and per-object strategy resolution, glob matching, most-specific-wins | 3 | 643 | 7 | **M** — silently selects the semantics everything else runs under (a mis-resolved `merge` changes whether values are deleted), but it is pure, offline and directly unit-tested |
| `schema` — `SchemaOptions`, `SchemaSupport`, `SchemaFile` | two-layer schema loading; collect from flags/tree/live and register in one batch | 3 | 385 | 13 | **M** — a mis-registration makes extension items diff as raw (wrong deltas), and the single-batch invariant is easy to break, but failures are loud and mostly local |
| `support` — `Io`, `Logging`, `VersionProvider` | stdin/stdout helpers, log-level control, `--version` | 3 | 120 | 6 | **L** — small, no branching of consequence |
| `tests` — `src/test/java/**` (21 files) | offline unit + CLI + stub-server + rig-gated live integration | 21 | 3388 | 83 | **M** — the safety net for everything above; `PolicyTest` is known to depend on another class having bootstrapped the static `PrismContext`, so isolation is already suspect |
| `ops` — `pom.xml`, `.github/workflows/*`, `docker/**`, `bin/midgie` | build, shading, release automation, test rigs, launcher | ~10 | 815 | 40 | **M** — the release workflow publishes artefacts and the shade config governs whether the fat JAR works at all; `docker/` holds test credentials |

## Proposed split into review units

Eight units for Phase 2, sized so each is one coherent read (≈400–1900 LOC) and no file is reviewed twice:

| unit | files |
|---|---|
| `u1-deploy-command` | `DeployCommand`, `InitCommand` |
| `u2-diff-engine` | `DiffEngine`, `DeltaOutput`, `NaturalKeys`, `XmlPretty` |
| `u3-rest-live` | `MidpointRestClient`, `ConnectionOptions`, `LiveLookup`, `LiveTarget`, `VersionSupport` |
| `u4-delete-detection` | `GitRevision`, `DeploymentIdentity`, `SyncState`, `RemovalDetector` |
| `u5-deploy-pipeline` | `Discovery`, `Fragment`, `DeployObject`, `TopoSort`, `ReferenceCollector`, `Reconciler`, `PlanStore` |
| `u6-strategy-schema` | `StrategyOptions`, `Policy`, `GlobPath`, `SchemaOptions`, `SchemaSupport`, `SchemaFile` |
| `u7-cli-surface` | `Main`, `DiffCommand`, `ApplyCommand`, `Io`, `Logging`, `VersionProvider` |
| `u8-tests-and-ops` | `src/test/java/**`, `pom.xml`, `.github/workflows/**`, `docker/**`, `bin/midgie` |

Then Phase 3's five whole-repo passes (secrets/logging, resource lifecycle, error handling, concurrency/state,
design drift), and Phase 4 synthesis with the skeptic re-read.

**One caveat worth your decision:** the last review round (`/code-review`) already found and I already fixed
15 findings concentrated in `u1` and `u4`. Those units are therefore the *most* recently churned and the
least settled — I have kept them as separate units rather than merging them, so the fixes get a fresh
adversarial read rather than a confirmatory one.
