# x1 — Secrets & logging (whole repo)

Scope: every path a credential or other sensitive value can take out of the process — the five password
sources, the Basic auth header, URLs, log/exception/stdout/stderr output, the `midgie-state` object, and
every file midgie writes (`--plan`, `-o`, `init --write-policy`). Also `docker/`, `bin/`, `.github/`, `pom.xml`,
tests.

Verified clean (no finding): the resolved password never reaches a log — only the *source* is logged
(`ConnectionOptions:126-157`); the Basic header is never logged and `MidpointRestClient` has no `toString`;
redirects are refused rather than followed, so the auth header is never re-sent to another host
(`MidpointRestClient:262-271`); `ProtectedStringType` deltas are stripped before any rendering — a
`clearValue` diff produces zero occurrences of the cleartext on stdout+stderr even under `--debug` (tested);
`toSummary`/`removalWarning`/`RemovalDetector` report paths and counts, and the removal set comes from a
`DiffEngine.diff` that has already stripped protected values; `midgie-state` rows hold only a git root
commit, a revision and a repo directory name; `init --write-policy` writes selectors and key *names*, never
values; `.github/workflows/*` reference no secret at all and echo only the release tag.

## Findings

major | src/main/java/de/is4it/midpoint/diff/MidpointRestClient.java:285 | a `--url`/`MIDPOINT_URL` carrying userinfo (`https://user:pw@mp/midpoint` — the shape every curl user reaches for) is kept verbatim in `restBase`, so the password is printed to stderr, ungated by `--quiet`/`--debug`, on any connect/timeout failure — verified: `midgie: POST roles/search — cannot connect to http://mpuser:s3cr3t-pw@localhost:1/midpoint/ws/rest (…)` — which is exactly what lands in a CI log when the server is still starting; the same URI also goes to `LOG.debug` at :140/:245, to `ConnectionOptions:114`, and into the prompts at `ConnectionOptions:160` and `DeployCommand:893,913`, while being silently ignored for authentication (auth is always built from `--user`/password) | strip userinfo in `restBaseOf` (:334) and reject-or-warn on it in `ConnectionOptions.url()`, so every printed form of the URL is credential-free

minor | src/main/java/de/is4it/midpoint/diff/PlanStore.java:73 | plan files are written with the process umask (verified 0644) and a `NNN-*.create.xml` is the *whole* desired object — `ApplyCommand.createBody` only strips operational items, so a `<clearValue>` password in the source tree is persisted in cleartext; `deploy --plan /tmp/plan` on a shared host or CI runner leaves it readable by every local user (same for `-o` at `Io.java:48`) | create both with owner-only permissions (`Files.write` with `PosixFilePermissions.asFileAttribute("rw-------")`, falling back silently on non-POSIX filesystems)

minor | src/main/java/de/is4it/midpoint/diff/ConnectionOptions.java:121 | midgie sends `Authorization: Basic` to a plain-`http://` remote host with no warning at all, so a copy-paste of the rig URL shape (`http://…:12080/midpoint`, used throughout `docker/`, `demo.sh` and the README) puts the midPoint administrator password on the wire in the clear on every request | print one stderr note when the scheme is `http` and the host is not loopback ("credentials will be sent unencrypted")

minor | src/main/java/de/is4it/midpoint/diff/Main.java:34 | picocli's default parameter-exception handler echoes unmatched tokens, so one typo prints the secret: `midgie diff a.xml b.xml --pasword hunter2` → `Unknown options: '--pasword', 'hunter2'` on stderr (verified), permanently capturing it in a CI log | install an `IParameterExceptionHandler` on the `CommandLine` that prints only the unmatched *option names* (or redacts the token after any `--pass*`), then delegates to the default usage/exit handling

minor | .gitignore:13 | `.claude/settings.local.json` is not ignored by the repository (only by this machine's `~/.gitignore_global`), and it already holds a credential — `Bash(curl … -u administrator:T3stPw890uio …)` — so on any other clone a `git add -A` commits whatever host/password a contributor's local allow-rules happen to contain | add `.claude/settings.local.json` next to the existing `.vscode/` entry
