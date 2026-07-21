# TODO — possible improvements

Hardening and polish for `midpoint-diff`. None of these are bugs — the tool is functional (offline
`diff`, `diff --host`, `apply` with create/patch/idempotency, and two-layer extension schema). Priority
is value × how exposed the gap is. ⭐ marks a good first cluster (small, independent, high-value).

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
- [x] **Configurable timeouts + retry/backoff** — `--timeout <seconds>` (per-request) and `--retries <n>` (GET-only, on 503/connection-refused/timeout with exponential backoff); writes are never auto-retried.
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
- [x] **Classgraph `Unsafe` startup warnings** — the `bin/midpoint-diff` launcher adds `--sun-misc-unsafe-memory-access=allow` on JDK 23+ (the flag is unknown to JDK ≤ 22, which doesn't warn). The warnings go to the native stderr, bypassing `System.err`, so an in-process filter is impossible — verified empirically. Direct-JAR users: `JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

## G. Test coverage gaps — done
- [x] **Command-layer offline tests** — `CliTest` drives the real CLI offline: missing LIVE, `--host` vs LIVE exclusion, bad `--scope`/`--equivalence`/`--multivalue`/`--path`/`--format` → exit 2, `apply` without `--host`/password → exit 2, plus drift/no-drift exit codes.
- [x] **Golden-file test** (`GoldenOutputTest`) pins the exact `ObjectModificationType` XML; equivalence metadata-sensitivity added to `DiffCommandTest` (`scope=full` delete already covered there).

## H. `deploy` — apply a whole folder tree in dependency order (done)

A new subcommand that discovers desired-state object XML under one or more paths, sorts by dependency, and applies each via the existing engine, **one JVM bootstrap** for the whole tree (a shell loop calling the tool per file would pay the ~1.5 s prism bootstrap each time). **Structure-agnostic**: objects are found by content, so the config tree can use any folder layout. Full design in DESIGN.md → "deploy — apply a folder tree". Reuses schema registration, `DiffEngine`, and the REST/creds/TLS/retry mixins.

**Increment 1 (built): read-only discovery + schema-aware dependency sort.** `deploy <path...>` discovers the tree, sorts it, and prints the apply order; offline it never writes. The order is **two tiers**: extension *schemas* first (raw `.xsd` inputs and deployable `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered — all sources in one batch, since prism's `registerDynamicSchemaExtensions` replaces the dynamic set — so objects using extension attributes parse), then *objects* (grouped by identity — OID, else type+name — and sorted by outbound reference OIDs). Several files may be *fragments* of one object, each its own apply unit; a genuine contradiction (a different type under one OID, or a single-valued item with differing values) is an error, but same-identity non-overlapping fragments are fine. Cycles are broken and warned, not fatal. New classes: `DeployCommand`, `Discovery`, `SchemaFile`, `Fragment`, `DeployObject`, `ReferenceCollector`, `TopoSort`; `SchemaOptions` gained a `collectSchemas`/`newBaseContext` split.

**Increment 2 (built): the apply loop + `--dry-run`.** With `--host`, each unit is reconciled file-by-file in the sorted order — search for the current object (by OID, or by type+name for `--apply-on-match`), diff the fragment (`--scope present`), then PATCH, or POST with `--create-if-absent`. Sequential, so a role is created before the user that references it. Writing is gated: a single up-front confirmation by default (`--yes` to bypass, `--confirm-each-apply` per change, `--dry-run` to preview; non-interactive without `--yes` refuses). New: `Reconciler`, `MidpointRestClient.searchObjects`. Existence is checked by search (not `GET`) so a missing object is an empty result, not a 404 that midPoint logs — shared in `LiveLookup`, and adopted by `diff --host`/`apply` too.

**Increment 3 (built): retry, saved plans, per-path overrides.** `--retry` re-applies the failed set round after round until it stops making progress, then fails on anything stuck (catches by-name/filter refs the static graph can't order). `--plan <dir>` writes the ordered per-object changes as `NNN-<type>-<oid>.{create,patch}.xml` files (reconciling against a local projection so a second fragment of a new object becomes a patch); `--apply-plan <dir>` replays them verbatim (POST creates, PATCH patches, in filename order). `--overrides <file>` maps `glob = <strategy flags>` lines to a per-path diff strategy (first match wins). New: `PlanStore`, `Overrides`; `Reconciler` gained per-call strategy + `planAgainst`.

- [x] ⭐ **Discovery (by content)** — recurse the paths; classify by root element; register raw `.xsd` and exported `SchemaType` `.xml` schema first (all sources in a single batch), then parse the rest as `PrismObject`s. Skip non-object XML with a warning; group same-identity files as fragments of one object; a genuine fragment contradiction is an error (a duplicate OID is *not*).
- [x] ⭐ **Dependency sort** — two tiers. Schemas sort by `<xsd:import>` namespace dependency; objects sort by their outbound `ObjectReferenceType` OIDs (prism reference-visitor); edge A→B when B is in the set; Kahn topo-sort with a deterministic tiebreak; cycles broken arbitrarily and warned.
- [x] ⭐ **Apply loop** — reconcile per fragment in sorted order (file-by-file diff → PATCH, or POST with `--create-if-absent`; `--apply-on-match` reconciles OID-less objects by type+name). Per-object result (created/patched/in-sync/skipped/failed) + a summary; honours `--quiet`. Per-path strategy via the `--overrides` file.
- [x] **Retry-until-stable** — `--retry` re-applies the failed set until no progress, then fails non-zero on anything stuck.
- [x] **Plan / intermediate deltas** — `--dry-run` prints the plan + deltas and changes nothing (exit 1 on drift); `--plan <dir>` writes ordered, numbered per-object create/patch files for review; `deploy --apply-plan <dir>` replays them in order (the "apply a saved change" mode).
- [x] **Tests** — unit: sort correctness, discovery skips non-objects, same-identity fragments group, contradictions error, the reconcile decision table, the REST search, overrides parsing, plan-file round-trip, and stub-server retry convergence. Integration (`docker/` rig): deploy a small tree with dependency order + idempotent re-run, `--apply-on-match`, the non-interactive refuse, the `--plan`→`--apply-plan` round-trip, and a per-path override.

> **Naming:** now that `deploy` applies, the tool does more than "diff". A broader name is under consideration (TBD — `midOps` was floated, not settled). Keep `midpoint-diff` until the name is decided; the rename is mechanical (repo/artifactId/binary/launcher/main class/package/docs/CI) and can be a single commit.
