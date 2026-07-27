# Changelog

All notable changes to midgie are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Delete detection — `deploy` can now see values you removed.** Dropping a value from a desired file is invisible to a two-way diff: nothing mentions it any more, so under `multivalue=merge` the delta is empty and the value lives on while `diff` reports "No differences". midgie now compares desired against **last-applied** as well (a three-way merge, as `kubectl apply` does) — a value applied at the recorded revision and no longer declared is reported, while a value the server holds that midgie never applied is left alone as accrued state. **Reporting is the default: without `--prune` nothing is deleted and exit codes are unchanged.** Deleting a whole fragment needs no special case: you get exactly the values that file contributed, the object is never a deletion candidate, and values a sibling fragment still declares are subtracted.
- **`midgie init`** — the one-off, explicit opt-in that enables the above by creating a `midgie-state` lookup table on the server. `deploy` never creates it: until `init` is run it behaves exactly as before and just notes that detection is off. The object describes and protects itself for an administrator who has never heard of midgie — `indestructible` (midPoint refuses the delete), a `description` and `documentation` explaining what it is and that losing it is harmless, and `subtype=midgie`. Setting its `lifecycleState` to `archived` switches tracking off without deleting anything.
- **`deploy --prune`** — actually remove the detected values, rather than only reporting them. Opt-in, previewable with `--dry-run` (exit `1` if anything would go), and gated by its own confirmation, so a non-interactive run without `--yes` refuses even when the apply pass had nothing to do. It runs after the tree is applied, and the delta is built solely from values that were in last-applied — so it is structurally incapable of touching accrued or foreign state.
- **`init --write-policy FILE`** — seed a starter `--policy` for a tree, suggesting `equivalence=natural-keys` for each object that declares a keyed multi-valued container **and fills the whole key in**, plus a `multivalue=merge` line scoped to each such container. The "fills the whole key in" condition is what keeps the advice sound: `assignment`/`inducement` declare an `identifier` real config usually omits, and `schemaHandling/objectType` is keyed by `kind + intent` but often written with only the `kind` — suggesting key matching in either case would make prism pair unrelated values. Never overwrites an existing file.
- Last-applied comes from version control (`git show <lastRev>:<file>`), so the only stored state is one revision per tree. A tree is identified by its repository's **root commit** plus its path within the repo, which every clone agrees on regardless of its remotes — the case a remote URL cannot handle when the same repository is pushed to both a customer's git and your own. Detection is skipped, with a note, outside a git repository or in a **shallow** clone; `--app NAME` pins the identity where that derivation does not fit.

### Fixed

- **`--prune` could delete a value the tree still declares.** Values a surviving fragment declares are subtracted from the removal set, but the survivor list was built by diffing each fragment against an empty object, which yields whole-container paths while removals are reported at their full depth. Nothing nested ever matched, so moving a value between two fragments of one object made `--prune` delete it immediately after the apply had written it. Top-level items happened to work, which is why it went unnoticed.
- **`--prune` ran after a declined apply**, because the gate tested only the exit code and a declined apply exits `0`. Refusing the write now stops the prune too, as does any run that skipped or declined individual units — the removal set describes a tree the server does not hold yet.
- **The recorded revision no longer advances past removals nobody acted on.** A run that skipped or declined units, or declined the prune, used to record itself as a completed deploy, so the next run compared against it and the pending removals became permanently undetectable.
- **An unrecoverable last-applied revision is reported instead of read as "nothing was removed".** A squash-merge, rebase or `gc` makes `git show <rev>:<file>` fail exactly as it does for a file that did not exist yet; midgie now verifies the revision first, refuses to prune, and leaves the baseline alone.
- **An object's `name` is never offered for deletion.** When a deleted fragment carried `<name>` and its surviving sibling identified the object by OID alone, dismantling it put `delete name` in the prune delta, which midPoint's repository rejects — failing the object's whole prune group.
- **The recorded revision is validated as a commit sha** before it reaches git's argument list. It is read back from the server, and git treats a leading `-` as an option.
- **`--multivalue merge` no longer corrupts a changed single-valued reference.** prism emits a whole-value replace only for single-valued *properties*, so a changed `connectorRef`/`resourceRef`/`targetRef` diffs to delete-plus-add; merge dropped the delete and left an add on an item that holds one value, which midPoint rejects outright. Merge now falls back to replace on cardinality, and says so.
- **The accrued-value guard sees nested containers.** It examined only the outermost container of a delta path while `merge` is honoured at any depth, so a merge rule naming an inner container dropped that container's deletes without ever arming the refusal.
- **`--retries` covers the read it exists for.** Retryability was keyed on the HTTP verb, but every object lookup is a `POST {type}/search`, so a still-starting server failed the very first read however high the setting.
- **One object described by two files is one object.** A file carrying the OID and a file carrying only the name were treated as two: the contradiction check and the clobber warning never ran between them, nothing referencing the object ordered itself before the OID-less half, that half was skipped at apply time as "no oid", and a `--policy` rule naming the object applied to only one of them.
- **`--create-if-absent` is idempotent for OID-less objects.** It planned a create without asking the server, so a second pipeline run re-POSTed the same object and midPoint rejected the duplicate name.
- **A hard error exits `2`, not `1`.** picocli's default meant a typo'd `--cacert` or a malformed `MIDPOINT_URL` reported "differences found" to a CI gate, with a stack trace attached. Running `midgie` with no subcommand likewise prints usage to stderr and exits `2` instead of writing help into a redirected payload file.
- **A file that claims to be a midPoint object and cannot be parsed fails the run** instead of being skipped with an advisory that `--quiet` then hid, which let a pipeline go green with an object never deployed.
- **An interrupted `--plan` cannot be replayed as if it were whole.** Plan directories are marked incomplete while they are written; `--apply-plan` refuses one still carrying the marker rather than applying part of a tree and reporting success.
- **Credentials embedded in the server URL are stripped.** `java.net.http` accepts them and never authenticates with them, so they only ever travelled — into the connection errors printed regardless of `--quiet`, the debug log, and the confirmation prompts.
- **`--insecure` is decided once per process**, before any client exists. The JDK reads the hostname-verification property once, on first use of `java.net.http`, so leaving it to whichever client was built first made the flag depend on call order — and left it silently off for every later connection.
- **A single file is no longer keyed by the tree it sits in.** `deploy config/roles/x.xml` recorded the whole `config` tree as applied at that revision, so the next full deploy compared against a point at which most of it was never applied.
- Also: an `oid:` policy selector outranks a type-wide rule; a colon in an object name is part of the name; policy rules outrank the CLI flags they are meant to override; a cycle is broken only at a node that is on one; `init --write-policy` no longer discards the schema it read from the server; overlapping `PATH` arguments are deduplicated; `-o` and plan files are created owner-only; and a TLS handshake failure names `--cacert` and `--insecure` instead of surfacing a bare PKIX message.

### Changed

- **`--oid` is rejected by `deploy`** rather than accepted and ignored; it applies to `diff` and `apply`, which address a single object.
- **`--apply-plan` rejects flags it does not read** (`--prune`, `--policy`, `-c`, `-m`, and `PATH`), and `--plan` rejects `--dry-run`/`--retry`, instead of silently ignoring them.
- **A `--schema` file or `--schema-dir` that does not exist is an error.** A typo'd path used to produce a delta computed without that schema, with the affected items diffed as raw.
- **The partial-declaration warning counts matched live values**, not desired ones; the old wording mixed denominators and could print sums that did not add up.
- **A policy selector can express an awkward object name**: quote it when the name contains `=`, escape a `/` inside a name. Only a known knob starts the settings now, so `OrgType/ou=people scope=full` parses. `init --write-policy` emits both forms, closing a round trip where midgie could generate a policy file midgie then refused to load.
- **`deploy` names any policy line that matched nothing**, instead of letting a typo'd selector resolve silently to the CLI baseline.
- Transport failures are a typed exception rather than a message prefix other code matched on, so rewording an error is no longer a behaviour change.
- The rig's containers bind to loopback, and all GitHub Actions are pinned to commit SHAs.

## [0.3.1] - 2026-07-27

### Added

- **Destructive changes announce themselves.** A delta that removes values now says so: each summary line carries the count it removes (`delete … (removes 2 values)`), and `diff`/`apply`/`deploy` print a prominent `WARNING: this change REMOVES n values from the live object` listing the paths. Advisory only — midgie reports the blast radius, it does not block; use `--dry-run` to inspect first. Suppressed by `--quiet`.
- **Static input checks on the desired object**, reported before anything is written. midgie warns when a value of a keyed multi-valued container **omits its natural key** (so it cannot be matched to a live entry and degrades to a whole-value change), and when desired **declares only some** of a keyed container's live values while that path is on `replace` — naming the fix: *"desired declares 1 of 4 live value(s) … the other 3 will be DELETED. Set '…/panel multivalue=merge' (with equivalence=natural-keys)"*. Kept precise so the warnings mean something: only containers with a schema-declared `a:naturalKey` are checked, the key must be in use by the live values (many types declare one that real config never fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value restated verbatim in desired counts as declared. The documented key-matched merge recipe is warning-free.

### Changed

- **Renamed `--host` to `--url`.** The flag has always taken a full base URL (scheme, port and the application context path, e.g. `https://mp.example/midpoint`), not a hostname, and the new name matches the `MIDPOINT_URL` environment variable it defaults from. **Breaking:** `--host` is no longer accepted — update scripts and CI to `--url`. The integration-test system properties were renamed to match: `-Dmidgie.it.url` / `.user` / `.password` (were `-Dmpdiff.it.*`).

### Fixed

- **A natural-key-matched edit is no longer mistaken for an accrued-value overwrite.** Combining `equivalence=natural-keys` with `multivalue=merge` — the way to sync a single entry nested inside multi-valued containers, e.g. one GUI `panel` inside `objectDetailsPage` — was refused as an "accrued in-place edit" and needed `--allow-inplace-merge`, which switches the guard off for the whole object. The check now resolves each container-id segment of the delta path against the desired object by container id **or** by the schema's `a:naturalKey` (`panel` → `identifier`, `objectDetailsPage` → `type`, …); an edit inside a value desired declares is a managed update and passes. A key only counts when both values actually carry its constituents, so a container whose natural key is absent (e.g. an `assignment` with no `identifier`) still triggers the guard as before.

- **Extension schema is deduped by target namespace before registration.** A schema present both in the deploy tree (or `--schema`) and on the live server was collected and registered twice, so prism logged a wall of `Overwriting existing definition …` warnings on `deploy` / `diff --url` / `apply`. It is now registered once — the local/tree copy winning over the live read — so that noise is gone; any genuine cross-import re-registration warnings still surface.

## [0.3.0] - 2026-07-25

### Added

- **`deploy` warns when two files that are fragments of one object both set the same multi-valued item** (e.g. both add an `inducement`). They apply file-by-file and are not merged, so under the default (replace) the later PATCH overwrites the earlier one (last-one-wins); the warning points at combining the files or a `--policy '<path> multivalue=merge'`. Non-fatal, and it honours `--quiet`.

### Changed

- **Renamed the tool `midpoint-diff` → `midgie`.** The launcher (`bin/midgie`), the built artifact (`midgie.jar`), the command name (`midgie …`), the `--version`/message output, the docs, the release workflow, and the GitHub repository all use the new name. The Java package `de.is4it.midpoint.diff` is kept for now. If you build from source, the JAR is now `target/midgie.jar` and the launcher env override is `MIDGIE_JAR`.
- **`--scope present` now preserves live items the desired object doesn't mention at *any* depth**, not just whole top-level items. Two files that are fragments of one object can each fill a *different* child of the same single-valued container (e.g. one file sets `deploymentInformation/headerColor`, another `deploymentInformation/name`) without the second file's diff deleting the first's child — and single-valued mid-levels (e.g. `internals/tracing`) are protected too. Protection can only be as fine as the first multi-valued container (a live-side container id can't be matched to id-less desired XML), which is where per-value semantics take over.
- **Replaced `deploy --overrides` with `deploy --policy`.** A single central policy file keyed on the **object/item** (`<selector> key=value` lines), not the source file, so it survives the fragment model. The selector is one glob over `Type/Name/item/subitem…` (or `oid:<oid>/item…`) with `*` (exactly one segment) and `**` (zero or more segments) at any level. A selector that stops at the object identity sets object-level knobs (`scope`, `equivalence`, `allow-inplace-merge`, a default `multivalue`); a deeper/`**` selector sets `multivalue` for that container (an object-only knob on an item selector is rejected). When several rules set the same knob for one target, the most-specific selector wins (more literal segments, then fewer `**`, then a longer literal prefix; ties → earlier line). The object XML stays pure midPoint XML. **Breaking:** the file-glob `--overrides` is removed.
- **`deploy`'s report reads source → target.** Each row is now `<source file>  <Type/Name>` — the object path is the same identity form the `--policy` selectors use, replacing the old type + `(type/name)` + name columns — and the OID is shown only under `--debug`/`--trace`. Applies to both the live apply report and the offline plan table.

## [0.2.2] - 2026-07-23

### Added

- **`-c` / `-m` short flags** for `--create-if-absent` and `--match-by-name`, on each subcommand that has them: `-c` on `apply` and `deploy`, `-m` on `diff`, `apply`, and `deploy`.
- **`--debug`/`-v` now traces this tool's own decisions**, not just framework logs. It records the mode (offline/live), the resolved connection (host/user/TLS/timeout, and the password *source* — never the value), every REST request and response (method, URL, status, body size, retries, redirects), schema collection, live lookups and their match counts, the per-object reconcile decision (create/patch/in-sync/skip/fail), the diff result summary, deploy discovery/sort order/apply units/retry rounds, and a full stack trace at the point any subcommand fails. Every log line is timestamped (`yyyy-MM-dd HH:mm:ss.SSS`).
- **`--trace`** — one level beyond `--debug` (which it implies): additionally logs the full REST request and response bodies and the full HTTP error body (not just the status code). May include sensitive values, so it is a deliberate opt-in separate from `--debug`.

## [0.2.1] - 2026-07-23

### Changed

- **Readable XML output.** Serialized XML — the `ObjectModificationType` itemDelta and full-object create bodies, on the console, in `-o`/`--plan` files, across every subcommand — now **drops** the namespace declarations prism piles onto the root that nothing references (a delta went from 8 to 3), and **wraps** a long root's remaining declarations one per line instead of a single wall of text. A declaration is kept only if its prefix appears as `prefix:` anywhere else (element/attribute names or QName content like the path), so a used prefix is never removed; only namespace declarations and insignificant whitespace change, so PATCH/POST bodies stay valid.
- **`--match-by-name` on `diff` and `apply` too.** An object with no OID can now be resolved on the server by type + name (a single match) in `diff` and `apply`, not only `deploy` — opt-in via `--match-by-name` on all three (renamed from `deploy`'s `--apply-on-match`). Combine with `--create-if-absent` to create when there is no match.
- **Redirects report an actionable hint.** A `3xx` on the REST base now throws with the `Location` header and a hint (the `--host` is usually missing the application context path, e.g. `…/midpoint`, or the wrong scheme) instead of a bare `HTTP 302`; it is not followed, so the Basic-auth header is never sent to an unexpected location.

## [0.2.0] - 2026-07-21

### Added

- **`deploy`** — a new subcommand that discovers a folder tree of desired-state object XML, sorts it by dependency, and either prints the apply order (offline) or reconciles it against a live server (`--host`). The order is two-tier: extension schemas first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then objects (sorted by their reference OIDs). Objects are identified by OID or, when OID-less, by type+name, so several files may declare fragments of one object; a genuine contradiction between fragments (a different type under one OID, or a single-valued item with two values) is rejected. With `--host`, each unit is reconciled file-by-file in order — look up the current object by a search, diff (`--scope present`), then PATCH, or POST with `--create-if-absent` (OID-less objects via type+name with `--apply-on-match`) — sequentially and idempotently. Writing is gated by a single up-front confirmation (`--yes`, `--confirm-each-apply`, or `--dry-run`; non-interactive without `--yes` refuses). Exit `0`/`2` on apply, `0`/`1`/`2` on `--dry-run`. `--retry` re-applies the failed units until they stabilise (catches references the static OID graph can't order); `--plan <dir>` writes the ordered changes as numbered `create`/`patch` files and `--apply-plan <dir>` replays them verbatim; `--overrides <file>` maps `glob = <strategy flags>` lines to a per-path diff strategy.

### Changed

- **Existence checks use a search instead of a `GET`.** `diff --host`, `apply`, and `deploy` resolve the current object with a `POST {type}/search` (by OID) rather than `GET {type}/{oid}`, so a missing object is an empty result instead of a 404 — which stops midPoint from logging an `ObjectNotFoundException` stacktrace on every probe (notably across a `--create-if-absent` deploy of a fresh tree). Shared in a new `LiveLookup` helper.

## [0.1.0] - 2026-07-19

First release. A standalone, schema-aware CLI that computes the minimal `itemDelta` between the desired and
current state of a **single** midPoint object and emits the `ObjectModificationType` XML that midPoint's
REST `PATCH` consumes — a thin wrapper around midPoint's own `PrismObject.diff`.

### Added

- **`diff`** — minimal `itemDelta` between the desired and current state of the same object (same OID),
  offline (two files, or stdin via `-`) or live against a running server (`--host`, current state fetched
  by OID). Output as `xml`/`json` (`ObjectModificationType`) or a human-readable `summary`; `-o` to a file.
- **`apply`** — reconcile a live object: fetch, diff, show the change, then `PATCH` after confirmation.
  Idempotent; `--create-if-absent` `POST`s a missing object; `--dry-run` reports without writing; `--yes`
  for non-interactive use.
- **Diff strategies** — `--scope present|full`, `--multivalue replace|merge` with per-path `--path`
  overrides, and `--equivalence` (`real-value` … `literal`).
- **Merge safety** — a merge that would overwrite an accrued (live-only) value in place is refused by
  default; `--allow-inplace-merge` to proceed.
- **Extension schema** — `--schema` / `--schema-dir` (raw XSD or exported `SchemaType`), plus automatic
  live read from the server (`--no-live-schema` to opt out).
- **Connection** — `--host`/`--user` (and `MIDPOINT_URL`/`MIDPOINT_USER`), password kept off the CLI
  (`--password-env` / `--password-file` / `MIDPOINT_PASSWORD` / interactive prompt), `--oid`, TLS
  (`--cacert` / `--insecure`), `--timeout`, and GET-only `--retries` with backoff.
- **Safety guards** — protected/encrypted values (`ProtectedStringType`) are skipped and reported;
  desired/current type and OID are cross-checked so you cannot diff the wrong object; the bundled base
  schema is version-coupled to the target midPoint (build-time drift check + runtime `GET /nodes` warning).
- **Ergonomics** — CI-friendly exit codes (`0` no diff / `1` drift / `2` error), `--quiet`, `--debug`,
  dynamic `--version`, and a `bin/midgie` launcher that silences the JDK 23+ classgraph `Unsafe`
  warnings.

### Packaging

- Fat JAR (Java 17), EUPL-1.2, [REUSE](https://reuse.software/)-compliant. CI runs `mvn verify` on JDK 17
  and 21 plus a REUSE licence lint; tagged releases attach the fat JAR.

[Unreleased]: https://github.com/IS4IT/midgie/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/IS4IT/midgie/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/IS4IT/midgie/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/IS4IT/midgie/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/IS4IT/midgie/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/IS4IT/midgie/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IS4IT/midgie/releases/tag/v0.1.0
