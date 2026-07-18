# TODO — possible improvements

Hardening and polish for `midpoint-diff`. None of these are bugs — the tool is functional (offline
`diff`, `diff --host`, `apply` with create/patch/idempotency, and two-layer extension schema). Priority
is value × how exposed the gap is. ⭐ marks a good first cluster (small, independent, high-value).

## A. Safety on `apply` (it mutates a live server)
- [ ] ⭐ **Confirm before applying** — prompt when stdout is a TTY; `--yes`/`-y` to bypass. `apply` PATCHes with no prompt today.
- [ ] **Show the delta before applying** — echo the change summary to stderr so the operator sees which fields change, not just "Applied N change(s)".
- [ ] **`--create-if-absent` strips operational cruft** — a desired file copied from a live GET carries metadata/operational items that get POSTed as-is on create; strip or warn.

## B. CI / exit-code semantics
- [ ] ⭐ **`--dry-run` exits 1 on drift / 0 in sync** — currently always 0, so CI can't gate on "would this change anything?".
- [ ] **Documented, stable exit-code contract** (0/1/2) across `diff`/`apply`, plus a `--quiet` mode for clean pipeline output.

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
