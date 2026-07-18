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
- [ ] ⭐ **Keep the password off the CLI** — `--password-env VAR`, `--password-file`, or an interactive prompt; `-p` leaks into `ps` and shell history.
- [ ] **Env-var config** — `MIDPOINT_URL` / `_USER` / `_PASSWORD` for CI convenience.
- [ ] **Clearer REST errors** — map 401 / 403 / connection-refused to actionable messages instead of a generic "HTTP nnn" + body.
- [ ] **Configurable timeouts + retry/backoff** on 503 (server still starting) and transient blips; `--cacert`/truststore for real TLS verification (note: `--insecure` trusts the cert but not hostname mismatch).

## D. Correctness edges (each also wants a test)
- [ ] ⭐ **Protected/encrypted values** — `<protectedString>` ciphertext differs on every fetch → spurious deltas *and* secrets in the output. Decide policy: ignore protected strings, or warn loudly.
- [ ] ⭐ **Round-trip core test** — compute the delta, `applyTo()` it to the live object locally, assert it equals desired (midPoint's own `TestParseDiffPatch` pattern). Strongest confidence the emitted PATCH is correct.
- [ ] **PolyString / reference diffing** — verify no churn on `fullName` (orig/norm) and `targetRef` (filter/relation); add fixtures.
- [ ] **Type/OID cross-check** between the desired file and the fetched live object, so you can't diff the wrong object.
- [ ] **Merge natural-keys footgun** — pair-and-edit-in-place can mutate accrued values; make the risk visible per-delta or refuse by default.

## E. I/O ergonomics
- [ ] **stdin/stdout plumbing** — read desired from `-`, write with `-o <file>`, add `--format json` (midPoint REST also accepts JSON PATCH bodies).
- [ ] **`--debug` / `-v`** to raise the logback level for troubleshooting.
- [ ] **`--no-live-schema`** — every `--host` call does `GET /schemas`; opt-out for speed or when the user lacks schema-read.

## F. Packaging / docs / release
- [ ] ⭐ **Update the root `README.md`** — it still says *"early scaffold, the diff engine is being implemented"*; rewrite with real usage + the full flag reference.
- [ ] **Dynamic version** — inject the pom version into the manifest (`Main` hardcodes `0.1.0`); have `--version` also print the bundled `midpoint-schema` (target midPoint line).
- [ ] **CI workflow** — GitHub Actions running `mvn test` (offline-green); optionally provision the rig for the live tests.
- [ ] **CHANGELOG + a tagged release; suppress/document the classgraph `Unsafe` startup warnings.**

## G. Test coverage gaps
- [ ] **Command-layer offline tests** — bad flags → exit 2, format handling, `--host` vs LIVE mutual exclusion (today only the engine and the rig-gated tests exercise the command layer).
- [ ] **Golden-file test** of the exact `ObjectModificationType` XML; more `scope=full` delete, `--path` parsing, and equivalence-mapping cases.
