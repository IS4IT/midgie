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
- [ ] **Real TLS verification** — `--cacert`/truststore, and make `--insecure` also bypass hostname mismatch. Deferred: can't be verified against the HTTP-only rig (needs an HTTPS midPoint with a private CA).

## D. Correctness edges (each also wants a test)
- [ ] ⭐ **Protected/encrypted values** — `<protectedString>` ciphertext differs on every fetch → spurious deltas *and* secrets in the output. Decide policy: ignore protected strings, or warn loudly.
- [ ] ⭐ **Round-trip core test** — compute the delta, `applyTo()` it to the live object locally, assert it equals desired (midPoint's own `TestParseDiffPatch` pattern). Strongest confidence the emitted PATCH is correct.
- [ ] **PolyString / reference diffing** — verify no churn on `fullName` (orig/norm) and `targetRef` (filter/relation); add fixtures.
- [ ] **Type/OID cross-check** between the desired file and the fetched live object, so you can't diff the wrong object.
- [ ] **Merge natural-keys footgun** — pair-and-edit-in-place can mutate accrued values; make the risk visible per-delta or refuse by default.

## E. I/O ergonomics
- [x] **stdin/stdout plumbing** — desired (and offline live) read from `-`; write with `-o <file>`; `--format json` alongside xml/summary.
- [x] **`--debug` / `-v`** raises the log level to DEBUG on stderr.
- [ ] **`--no-live-schema`** — every `--host` call does `GET /schemas`; opt-out for speed or when the user lacks schema-read.

## F. Packaging / docs / release
- [ ] ⭐ **Update the root `README.md`** — it still says *"early scaffold, the diff engine is being implemented"*; rewrite with real usage + the full flag reference.
- [x] **Dynamic version** — `--version` reads the pom `project.version` from the filtered `build-info.properties` (no more hardcoded `0.1.0`) and also prints the bundled midPoint schema line, via a picocli `VersionProvider`.
- [ ] **CI workflow** — GitHub Actions running `mvn test` (offline-green); optionally provision the rig for the live tests.
- [ ] **CHANGELOG + a tagged release; suppress/document the classgraph `Unsafe` startup warnings.**

## G. Test coverage gaps — done
- [x] **Command-layer offline tests** — `CliTest` drives the real CLI offline: missing LIVE, `--host` vs LIVE exclusion, bad `--scope`/`--equivalence`/`--multivalue`/`--path`/`--format` → exit 2, `apply` without `--host`/password → exit 2, plus drift/no-drift exit codes.
- [x] **Golden-file test** (`GoldenOutputTest`) pins the exact `ObjectModificationType` XML; equivalence metadata-sensitivity added to `DiffCommandTest` (`scope=full` delete already covered there).
