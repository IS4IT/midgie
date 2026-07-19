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
- [ ] ⭐ **Update the root `README.md`** — it still says *"early scaffold, the diff engine is being implemented"*; rewrite with real usage + the full flag reference.
- [x] **Dynamic version** — `--version` reads the pom `project.version` from the filtered `build-info.properties` (no more hardcoded `0.1.0`) and also prints the bundled midPoint schema line, via a picocli `VersionProvider`.
- [ ] **CI workflow** — GitHub Actions running `mvn test` (offline-green); optionally provision the rig for the live tests.
- [ ] **CHANGELOG + a tagged release; suppress/document the classgraph `Unsafe` startup warnings.**

## G. Test coverage gaps — done
- [x] **Command-layer offline tests** — `CliTest` drives the real CLI offline: missing LIVE, `--host` vs LIVE exclusion, bad `--scope`/`--equivalence`/`--multivalue`/`--path`/`--format` → exit 2, `apply` without `--host`/password → exit 2, plus drift/no-drift exit codes.
- [x] **Golden-file test** (`GoldenOutputTest`) pins the exact `ObjectModificationType` XML; equivalence metadata-sensitivity added to `DiffCommandTest` (`scope=full` delete already covered there).
