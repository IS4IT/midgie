# Review pass x2 — resource lifecycle

Scope: streams/readers, subprocesses, the JDK HttpClient, thread pools, temp/partial files, and anything opened inside a loop over a large tree, across `src/main` and `src/test`.

## Findings

major | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:709 | `runPlan` writes plan files one per unit with no per-unit error handling and no cleanup, so any mid-loop failure (HTTP 5xx/timeout on the current-state search that `Reconciler.plan` performs, or an `IOException` out of `PlanStore.write`) exits 2 leaving a partial plan directory — and `deploy --apply-plan` then replays that truncated plan as if it were complete (reproduced: 4-unit tree, stub failing on the 3rd search → 2 `.create.xml` files + exit 2; `--apply-plan` on that directory → "Done: 2 applied, 0 failed", exit 0), while a re-run of `--plan` is refused by `PlanStore.hasPlanFiles` until the directory is deleted by hand | write the plan to a staging directory (or under a `.partial` marker) and rename/publish only after the loop completes; on abort delete what was written or leave the marker so `--apply-plan` refuses it, and catch per-unit failures the way `pass()` already does

minor | src/test/java/de/is4it/midpoint/diff/LiveIntegrationTest.java:295 | `Files.list(plan).anyMatch(...)` never closes the returned stream, so the underlying `DirectoryStream` (a directory descriptor) is held for the life of the test JVM; on Windows the open handle also makes the `@TempDir` cleanup fail the test | wrap it in try-with-resources (`try (Stream<Path> files = Files.list(plan)) { … }`)

minor | src/test/java/de/is4it/midpoint/diff/GitRevisionTest.java:117 | the `git` helper (same code at `RemovalDetectorTest.java:184`) uses an unbounded `process.waitFor()` and, when `readAllBytes()` throws, returns `-1` without waiting for or destroying the child: a git invocation that never exits hangs `mvn test` until the CI job's wall-clock limit, and the exception path orphans the process — the production code deliberately guards both (`GitRevision.TIMEOUT_SECONDS`, `destroyForcibly` in `finally`) | use `waitFor(timeout, TimeUnit)` and `destroyForcibly()` in a `finally`, mirroring `GitRevision.run`

## Checked and clean (verified, not assumed)

- `GitRevision.run` — every early-return/exception path releases the process. Empirically: 300 `git show` invocations from one `GitRevision` left open FDs at 5, thread count unchanged, `ProcessHandle.current().descendants() == 0`. The three pipes are closed by the JDK reaper on child exit even when a drain future is cancelled before it runs; `destroyForcibly` in the `finally` covers the `getOutputStream().close()`, `InterruptedException` and timeout paths. The cached `DRAIN` pool never grows past 2 live tasks because `run` is sequential.
- Per-file loops over a tree: `Discovery.walk` is in try-with-resources; `DOMUtil.parseFile` (JDK `DocumentBuilder.parse(File)`) and prism's `parseObject(File)` (`DomLexicalProcessor` closes via `ParserSource.closeStreamAfterParsing`) both close their own streams. Empirically: scan + parse of 401 files, twice, left open FDs at 5.
- The apply/plan REST loop reuses the single `MidpointRestClient`; a 401-unit `deploy --dry-run` against a stub server made 401 searches with no FD growth and no connection exhaustion.
- `VersionSupport.buildProperty` closes the classpath resource stream; `Io`/`ConnectionOptions`/`Policy`/`PlanStore` use the whole-file `Files.readString`/`readAllLines`/`writeString` forms (no dangling handles).
- The JDK `HttpClient` is not `AutoCloseable` under `maven.compiler.release=17`, and exactly one is built per CLI invocation — nothing to release in a short-lived process.
- No temp files or directories are created by the tool; all test fixtures are written under `@TempDir`, and the `--plan out` validation tests are rejected by the `writeFlags` check before any directory is created.
