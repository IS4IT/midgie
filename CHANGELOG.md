# Changelog

All notable changes to midpoint-diff are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/IS4IT/midpoint-diff/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/IS4IT/midpoint-diff/releases/tag/v0.1.0
