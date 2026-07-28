# u7 — CLI surface: `Main`, `DiffCommand`, `ApplyCommand`, `Io`, `Logging`, `VersionProvider`

Reviewed at `740747f` (working tree). Scope: the single-object CLI — `diff` (offline two-file / live), `apply`
(fetch, diff, confirm, PATCH/POST), the exit-code contract on every branch, offline-vs-live mode selection and
`MIDPOINT_URL`, confirmation gating, stdin/`-` handling, stdout/stderr routing, and `--version`.

Every claim below was reproduced against `target/midgie.jar` unless marked otherwise.

Format: `SEVERITY | file:line | failure | fix`

## Findings

major | src/main/java/de/is4it/midpoint/diff/Main.java:34 | picocli's default `exitCodeOnExecutionException` (**1**) is left in place, and both commands do their mode selection and client construction *outside* the `try` that maps errors to 2 — so a hard error exits `1`, which the documented contract defines as "differences found (drift)", and dumps a raw Java stack trace at the operator. Verified: `diff DESIRED --url … --cacert /tmp/no-such-ca.pem` → exit 1 (`IllegalStateException` from `MidpointRestClient.caCertSslContext` via `ConnectionOptions.newClient`, called at DiffCommand:125 / ApplyCommand:120), and a malformed `MIDPOINT_URL` → exit 1 (`URI.create` `IllegalArgumentException` via `isLive()` at DiffCommand:114 / ApplyCommand:116). A CI gate keyed on `1 = drift` reports phantom drift for a typo'd `--cacert`; for `apply`, `1` is not in the contract at all. | Install a `setExecutionExceptionHandler` in `Main.main` that prints `midgie: <message>` and returns 2 (or set `exitCodeOnExecutionException = 2` on every `@Command`), and pull DiffCommand:107-129 / ApplyCommand:116-123 inside the existing try/catch.

minor | src/main/java/de/is4it/midpoint/diff/ApplyCommand.java:158 | `--dry-run` writes no delta payload on two of its three outcomes: the absent-object branch returns `1` after only a stderr note (:158), and the in-sync branch returns `0` after `status(...)`, which puts prose ("No changes: RoleType/… already matches desired.") on **stdout**, the payload stream (:205). So `apply --dry-run -o delta.xml` leaves `delta.xml` holding the *previous* run's delta whenever the object is now in sync or absent, and a consumer that parses dry-run stdout gets a plain-text line instead of an `ObjectModificationType`. `diff` behaves the opposite way — it always emits a payload, empty modification included (verified). | In dry-run, always `Io.writePayload` (an empty `ObjectModificationType` when in sync) and route the in-sync notice through `note()` to stderr; for the create branch either write the create body or say plainly that `-o` is not produced for a create.

minor | src/main/java/de/is4it/midpoint/diff/Io.java:48 | `Files.writeString` to a `-o` path whose parent directory does not exist throws `NoSuchFileException`, whose `getMessage()` is the bare path, and both commands print only `e.getMessage()` (DiffCommand:177, ApplyCommand:140) — the operator sees `midgie: /tmp/nope-dir-xyz/out.xml` and nothing else (verified, exit 2). Same shape for a permission-denied target. | Wrap the write in `Io.writePayload`: `catch (IOException e) { throw new IOException("cannot write " + output + ": " + e.getMessage(), e); }` (or `Files.createDirectories(parent)` first).

minor | src/main/java/de/is4it/midpoint/diff/Io.java:38 | `-` is accepted for both `DESIRED` and `CURRENT`, but stdin can only be drained once: `midgie diff - -` reads the object, then parses an empty string and fails with `midgie: Index 0 out of bounds for length 0` (verified) — an internal prism error that names neither stdin nor the real mistake. | Reject two `-` inputs in `DiffCommand`'s mode check with "only one input can be read from stdin".

minor | src/main/java/de/is4it/midpoint/diff/Main.java:30 | `midgie` with no subcommand prints the usage text to **stdout** and exits **0** (verified), i.e. "no differences" under the exit-code contract; `midgie $ARGS > delta.xml` with an empty `$ARGS` writes 645 bytes of help into the payload file and reports success. | Print `usage(System.err)` and return `CommandLine.ExitCode.USAGE` (2) — make `Main` a `Callable<Integer>`.

## Checked and clean (so the next reviewer does not redo it)

- **Confirmation gating.** `System.console()` really is `null` in every non-interactive shape tested — stdin+stdout
  redirected, and stdin redirected with stdout on a pty — on JDK 17, 21, 25 and 26, so `NON_INTERACTIVE` → exit 2
  holds; the JDK 22+ "console even when redirected" change does not fire here. Both write paths (`applyCreate`,
  `applyPatch`) pass through `confirmOrExit` before any POST/PATCH, and `--dry-run` never prompts or writes.
- **Exit codes on the remaining branches.** `diff` 0/1/2, `apply --dry-run` 0/1/2 (including the accrued-in-place
  refusal → 2 and "absent without `--create-if-absent`" → 2), real `apply` 0/2 with a REST failure surfacing as 2.
- **Mode selection.** `CURRENT` + explicit `--url` → error; `CURRENT` beats an inherited `MIDPOINT_URL`; no
  `CURRENT` and no URL → exit 2 with an actionable message; live schema is only read when live.
- **`--quiet`.** Never suppresses the `diff` payload, the dry-run payload, errors, or the exit code.
- **`--version`.** `VersionProvider` reads the Maven-filtered `build-info.properties`; the pom's `0.1.0-SNAPSHOT`
  is stamped from the tag by `.github/workflows/release.yml` (`versions:set`), so released JARs report correctly.
