# Changelog

All notable changes to midpoint-diff are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  dynamic `--version`, and a `bin/midpoint-diff` launcher that silences the JDK 23+ classgraph `Unsafe`
  warnings.

### Packaging

- Fat JAR (Java 17), EUPL-1.2, [REUSE](https://reuse.software/)-compliant. CI runs `mvn verify` on JDK 17
  and 21 plus a REUSE licence lint; tagged releases attach the fat JAR.

[Unreleased]: https://github.com/IS4IT/midpoint-diff/compare/v0.2.2...HEAD
[0.2.2]: https://github.com/IS4IT/midpoint-diff/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/IS4IT/midpoint-diff/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/IS4IT/midpoint-diff/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IS4IT/midpoint-diff/releases/tag/v0.1.0
