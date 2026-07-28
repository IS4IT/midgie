# x4 — Concurrency & mutable state

Whole-repo pass over `src/main` and `src/test` (repo root `/Volumes/Daten/git/midpoint-diff`). Scope: mutable
statics and process-wide singletons, instance state whose mutation makes call order load-bearing, in-place
mutation of objects a caller still holds, and test pollution. Threading issues are only reported where they
bite the current single-threaded CLI or the shared surefire JVM.

## Findings

```
major | src/main/java/de/is4it/midpoint/diff/MidpointRestClient.java:134 | `--insecure` sets the process-wide JDK property `jdk.internal.httpclient.disableHostnameVerification`, but the JDK latches it into `static final Utils.isHostnameVerificationDisabled` on first use of `java.net.http` — verified: setting it after any earlier request in the same JVM has no effect (reads `false`), and once set it is never cleared, so a later `--cacert` client in the same JVM silently skips hostname verification | latch the decision once per process (set it from `Main.main` before any `HttpClient` exists) and have `MidpointRestClient` fail loudly when a second client asks for the opposite setting, since the JDK cannot honour it; a per-client `sslParameters()` fix is not possible — `AbstractAsyncSSLConnection.createSSLParameters` overwrites `endpointIdentificationAlgorithm` on a copy
major | src/main/java/de/is4it/midpoint/diff/DeployCommand.java:280 | `declined` is only set by the single up-front `confirmOnce`, so a `deploy --confirm-each-apply` run where the operator answers "n" to every unit writes nothing yet still exits 0 and calls `recordRevision`, poisoning `midgie-state` with a revision that was never applied — the next run then diffs against it and silently never detects (or, with `--prune`, never removes) anything dropped from the tree in that generation | set `declined` (or count applied units in `Tally`) in the per-unit decline branch at DeployCommand.java:641 and gate `recordRevision` on "something was actually written", not on "no prompt was refused"
major | src/main/java/de/is4it/midpoint/diff/ApplyCommand.java:237 | `createBody` calls `desired.getValue().removeOperationalItems()` on the caller's object rather than a copy, and `Reconciler.planCreate` calls it from the *preview* pass that `DeployCommand.runApply` runs before the real pass ("Planning is read-only", Reconciler javadoc) — verified: after `createBody` a `DATA`-equivalence diff of the same object drops a modification (2 → 1), so `deploy --create-if-absent --equivalence data` emits a different PATCH for a second fragment depending only on whether `--yes` skipped the preview | clone first: `PrismObject<?> body = desired.clone(); body.getValue().removeOperationalItems();` — also removes the unstated requirement that `findRemovals` run before `runApply`
major | pom.xml:127 | the test suite reads the ambient `MIDPOINT_URL`/`MIDPOINT_USER`/`MIDPOINT_PASSWORD` that `ConnectionOptions` honours, and nothing isolates it — verified: `MIDPOINT_URL=… mvn test` fails 20 tests (2 in `CliTest`, 18 in `DeployCliTest`) and turns the "offline" `deploy` plan tests into live reads against whatever server the developer/CI has exported | add `<environmentVariables><MIDPOINT_URL/><MIDPOINT_USER/><MIDPOINT_PASSWORD/></environmentVariables>` to the surefire configuration (blank is treated as unset by `ConnectionOptions.url()`), so the CLI harnesses in CliTest.java:157 and DeployCliTest.java:283 always start from a known-offline state
minor | src/test/java/de/is4it/midpoint/diff/LiveIntegrationTest.java:65 | the `@BeforeAll` client is built with `insecure=true` unconditionally (before the rig-reachability check), so every `mvn test` run permanently disables hostname verification for the whole surefire JVM; whether that even takes effect depends on JUnit's unspecified class order (it currently runs 2nd, before `MidpointRestClientTest`), so an https rig via `-Dmidgie.it.url=https://…` would pass or fail on class order alone, and any future `--cacert` end-to-end test would pass even when broken | pass `false` — the rig is plain http (`docker/.env`, port 12080) and does not need it; if an https rig is ever wanted, gate the whole class with `@EnabledIf` and give it its own forked JVM
minor | src/main/java/de/is4it/midpoint/diff/InitCommand.java:174 | `seedPolicy` calls `SchemaSupport.register` a second time on the context already registered by `buildContext(client)` at InitCommand.java:100, and `registerDynamicSchemaExtensions` *replaces* the dynamic set (confirmed in prism-impl bytecode: `putfield dynamicSchemaExtensions`), so whenever the tree carries any schema file the live-read server schemas are dropped — objects that use a server-only extension type then parse as `RawType` (or lose their compile-time class and are skipped), and `init --write-policy` silently omits rules for exactly the keyed containers most likely to need them | follow `DeployCommand`: build with `SchemaOptions.newBaseContext()`, merge tree + CLI + live schemas into one map, and register once
```

## Checked and clear

- **`PrismContext` / `PrismService` static singleton.** Every `MidPointPrismContextFactory` construction overwrites
  the process-wide `PrismService` holder (`PrismContextImpl.<init>` ends with `PrismService.get().prismContext(this)`),
  and the CLI harness tests create one per invocation. Probed directly: after a second, base-only context replaces the
  global, objects parsed with the first context still diff, serialize and re-parse with their extension schema intact.
  No observable failure — not reported.
- **`GitRevision.DRAIN`** (daemon pool, deliberate) and its per-instance `ConcurrentHashMap` cache: negative results are
  cached, but only within one `GitRevision`, and the failure direction is "detect nothing", not "delete something".
- **In-place mutation across diff sides.** `DiffEngine` mutates only the delta it just produced (`clearValuesToDelete`,
  `removeModification`); `SyncState.record` and `DeployCommand.applyDelta` clone before mutating; `RemovalDetector`
  clones values into the prune delta. `ApplyCommand.createBody` is the sole exception (reported above).
- **Stub-server tests.** `ReconcilerTest`/`DeployRetryTest`/`MidpointRestClientTest` cross-thread fields are `volatile`
  or `AtomicInteger`, and all `System.setOut/setErr/setIn` swaps are restored in `finally`.
- **`Logging.enableDebug/enableTrace`** mutate the logback root logger globally and never restore it, but no test passes
  `--debug`/`--trace`, so there is no failure to name today.
