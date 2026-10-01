# Pre-1.0 code review

Full review of `src/main` (45 files, ~16k lines), `pom.xml` and `.github/workflows`, done against v0.16.3.
Each finding is marked **confirmed** (reproduced by a probe test or a jar run) or **traced**/**plausible** (read end to end, not run).
Probes ran in parallel against a shared `target/`, so reproduce a finding before fixing it.

Tick an item when its fix is committed; add the commit hash after it.

## Blockers

- [x] **B1 — Rollback rotation deletes arbitrary directories.** — `18bad84` `RollbackPlan.java:324-344` (`rotate`), `:385-390` (`sanitizeKey`), called from `DeployCommand.java:1150-1157`. *Confirmed.*
  `rotate(base, 5)` recursively deletes every subdirectory of `base` except the five highest-named, without checking it is a timestamped run. `base` can come from `--rollback-plan` or a tree-local `.midgie.properties` (`rollback-plan` and `app` are allowed keys), and `sanitizeKey` keeps `.` and `..`. A cloned repo with `rollback-plan = .` makes the next `deploy --yes` delete the tree's own `roles/`, `users/`, …; `app = ..` hits `$XDG_STATE_HOME`. Letters sort after digits, so the real net goes first. `listFiles(File::isDirectory)` follows symlinks.
  Fix: only touch directories whose name matches the run-timestamp pattern (and hold a manifest), never follow symlinks, reject `.`/`..`/empty keys.

- [x] **B2 — Smart references nested in keyless containers are never handled.** — `2c4e3f5` (confirmed live against the docker rig). Limit: a reference inside a list *nested in* the entry is not substituted; that entry stays a change and is sent with `reevaluateSearchFilters`. `DiffEngine.java:686-718`, `DeltaOutput.java:93-108`. *Confirmed.*
  `stripResolvedSmartReferences` only looks at references directly in an item delta; a filter-only `targetRef` / `construction/resourceRef` inside an `inducement`/`assignment` diffs as whole-container add/delete. `carriesSmartReference` misses it too, so no `reevaluateSearchFilters`. Under replace every deploy swaps the resolved inducement for an unresolved one; under merge it adds a duplicate every run.

- [x] **B3 — Container ids from another environment pair with unrelated live entries.** — `3710d26`. Broader than reported: prism pairs by id under *every* strategy, `real-value` included, so a DEV `inducement id=5` re-pointed PROD's unrelated inducement 5. Fixed by `ContainerIds` (foreign numbering detected per list). Residual, documented: one edited entry whose foreign id is taken, with no other entry in the list to show the mismatch. `NaturalKeys.java:103-109`, `DiffEngine.java:501` (`adoptLiveIds`), `:969-1001` (accrued guard), `:1355` (`applyMerge`). *Confirmed.*
  The id rung is tried before the key. DEV `<mapping id="4"><name>fromDev</name>` against PROD `<mapping id="4"><name>prodOnly</name>` under auto + merge gives `mapping/4/name REPLACE fromDev` with no refusal. `applyMerge` also keeps the delete of an admin-added PROD assignment whose id matches a desired id.
  Fix: an id match must not count as pairing/ownership when the contents or filled natural keys disagree. Likely also addresses D1.

- [x] **B4 — Escaped `\*` in a policy path becomes a wildcard.** — `4f03375` `GlobPath.java:58, 97-98, 174`. *Confirmed.*
  `RoleType/\* multivalue=replace scope=full` matches every role. Keep the literal distinct from STAR after parsing. `PolicyTest:307` only covers `\*` inside a longer name.

- [x] **B5 — `apply=on-create`/`if-absent` items are deleted with their parent container value.** — `52ef6d0`. Also covers a single-valued container (`activation`) deleted around the item; deleting a whole entry the file no longer declares still goes through. `ApplyRules.java:170-189`; also `DeployCommand` `withoutRestricted`. *Confirmed.*
  Only a replace is checked; an add+delete pair strips `valuesToAdd` but the delete of the live value (restricted item included) goes out. Whole-value removals resolve to ALWAYS and delete the nested protected item too. Contradicts DESIGN ("never overwritten or removed").

## Major

### Deploy and rollback safety

- [x] **M1 — Fatal error mid-apply leaves no rollback plan.** — `d8b95f6` `DeployCommand.java:494-518, 1078-1080`. *Confirmed (stub server).*
  A 401/`IOException` is rethrown out of `runApply`; `writeRollbackPlan` only runs after it returns. Write the plan in a `finally`.

- [x] **M2 — `--apply-plan` writes no rollback plan and records no tracking state.** — `373271b`. The missing state was the bigger half: a plan-only pipeline never moved its rows, so a value added by one plan and dropped by the next stayed. `--plan` now writes `state.xml`; confirmed live. `DeployCommand.java:344-345, 1291-1331`. *Confirmed.*
  Contradicts README:208-210 and DESIGN:407. `--rollback-plan`/`--no-rollback-plan` are silently accepted with it (missing from the "no effect" list at `:303-329`).

- [x] **M3 — `rollback` never updates midgie-state rows.** — `9aa4485` (confirmed live before the fix: redeploying v2 after a rollback kept `beta`). `RollbackCommand.java`. *Traced; consequence plausible.*
  After a rollback, last-applied still points at the rolled-back content; re-deploying it under merge detects no removal and the object ends up holding old and new values.

- [x] **M4 — Removal-detection failures are swallowed and the unit still advances.** — `ff205ad`. Unparseable history and failed comparisons hold the object's rows back; contradicting history lets them move on (it would fail every run), with a `--quiet`-proof warning either way. `RemovalDetector.java:348-352, 428-435, 600-602, 762-768`. *Confirmed.*
  Exceptions in history parse/union/walk yield "no removals" at TRACE; `detectionBroken` is not set and `recordState` marks old paths consumed, so dropped values stay on the server permanently. Treat like unreadable blobs (`DeployCommand:622-630`).

- [x] **M5 — `project()` swallows exceptions, silently skipping the duplicate-key refusal.** `DiffEngine.java:1424`; callers `DiffCommand:227`, `Reconciler:261`. *Confirmed.* — `d6d28ba`
  Report the projection failure as `judgeRemovals` does.

### Input handling

- [x] **M6 — Symlinked files and directories are silently skipped.** `Discovery.java:637, 653`. *Confirmed.* — `87c3be7`
  No `FOLLOW_LINKS`. A symlinked object file is dropped with exit 0; a Kubernetes ConfigMap mount (`..data` symlinks) or a symlinked root gives "holds no files at all", exit 2.

- [x] **M7 — Malformed XML / unparseable SchemaType files are warnings only, hidden by `-q`.** `Discovery.java:546-551, 572-575`. *Confirmed.* — `87c3be7`
  A file with leftover merge markers is skipped, the run exits 0, and under tracking the object reads as "no longer declared". Fail the run like `parseObjects` does (`DeployCommand:417-424`).

- [x] **M8 — An explicitly named malformed `--schema` file is only warned about.** `SchemaOptions.java:600-609, 655-674`. *Confirmed.* — `87c3be7`
  The delta is computed without it (extension items diff as raw). Contradicts the code's own comment at `:603`; DESIGN documents warn-and-skip, so update both.

### Credentials and output

- [x] **M9 — Config-file `password` beats CLI `--password-file`/`--password-env`.** `ConnectionOptions.java:325-347`, same pattern in `resolveProxyPassword` `:294-306`. *Confirmed.* — `906530e`
  Contradicts README:551 and DESIGN:214. The existing test puts `password-file` in the file, so it misses this case.

- [x] **M10 — Connector passwords are not recognised as protected when untyped.** `DiffEngine.java:797-830`. *Confirmed for untyped values; whether midPoint REST omits `xsi:type` there is plausible, check against the docker rig.* — `d6d28ba` (confirmed against 4.10.5: `bindPassword` comes back as `<t:encryptedData>` with no `xsi:type`)
  `bindPassword` cleartext goes into PATCHes, dry-run output, plan and rollback files, and drifts every run.

- [x] **M11 — `--opt=secret` form of an unknown option is echoed.** `Main.java:414-424`. *Confirmed.* — `906530e`
  `--pasword=hunter2` prints the value. Print only the part before `=`.

- [x] **M12 — `java.lang.Error` exits 1 ("differences found").** `Main.java:435`, `DiffCommand.java:270`. *Confirmed.* — `906530e`
  OOM, StackOverflowError, NoClassDefFoundError from shading. Catch `Throwable` in `main`, exit 2.

- [x] **M13 — Failed stdout write is silent.** `Io.java:108`. *Confirmed (`> /dev/full` exits 1).* — `906530e`
  Check `System.out.checkError()` and exit 2.

- [x] **M14 — Stdout uses the platform encoding.** `Io.java:108`. *Confirmed (`LANG=C` turns `Größe` into `Gr??e`).* — `906530e` (checked with the jar under `LANG=C`)
  Wrap stdout in a UTF-8 `PrintStream`.

## Design / duplication

- [x] **D1 — Whole keyed list demoted to real-value because a nested entry lacks its key.** `NaturalKeys.java:201`. Pair the outer entry by its own filled key and compare only the unsafe nested list by content; removes the need for the merge special case, the duplicate refusal and the `-v` note. — `7d95091`. Probe on 4.10.5 showed assigning ids under `natural-keys` does not stop a vacuous key match, so the keyed outer list gets its own pass under `consider-different-ids` instead; confirmed live.
- [x] **D2 — `matchingLiveValue` re-implements `NaturalKeys.match`.** `DiffEngine.java:1243-1262`. Call `NaturalKeys.match` so warnings and the "kept" report pair like the diff. — `09673a1`
- [x] **D3 — Three near-copy path walkers.** `DiffEngine.java:409` (`declarationAt`), `:969` (`desiredDeclaresValueAt`), `:1375` (`desiredContainerAt`); only `declarationAt` checks `hasNoValues`. Unify into one walker. — `468a1e6`
- [x] **D4 — Every PATCH projects the object twice.** `Reconciler.java:403` (`judgeRemovals` inline projection) and `:258` (`planPatch`). Reuse `DiffEngine.project`. — kept deliberately: the duplicated code went with M5 (`d6d28ba`); the second projection stays because the removals merge into the diff's item deltas and the checks must judge that merged PATCH — explained at the call.

## Minor

### Security hardening

- [x] **S1 — OIDs concatenated into REST URLs unescaped.** `MidpointRestClient.java:340, 626-632`. `../roles/x?options=raw` is sent verbatim; a space throws `IllegalArgumentException`. Validate or percent-encode. — `bf0dcda`; also covers C27 (plan files refuse a non-UUID OID)
- [x] **S2 — Proxy parse errors echo credentials.** `ProxyConfig.java:154-179`. Strip userinfo as elsewhere in the class. — `d76fa87`
- [x] **S3 — `isEnabled("insecure")` matches any qualified key.** `ConfigDefaults.java:496-517`, `Main.configuredInsecure`. `midgie.init.insecure=true` disables hostname checks for every command. Also: file `insecure=true` + CLI `--cacert` is refused instead of CLI winning (`ConnectionOptions:165`). — `e52c56b`
- [x] **S4 — Tree-local config walk only stops at `.git`.** `ConfigDefaults.java:230-243`. Outside a checkout it walks to `/` and picks up e.g. `/tmp/.midgie.properties` from another user. — `03d46df` — outside a checkout only the working directory's own file; untrusted files ignored with a warning
- [x] **S5 — No permission check on config files holding `password`.** `ConfigDefaults` / `ConnectionOptions`. *Plausible.* Warn on group/world-readable. — `9569f5c` — warns, does not refuse
- [x] **S6 — `--trace` prints full request/response bodies incl. `clearValue`.** `MidpointRestClient.java:401-411`. Document the risk or redact; `shortBody` (`:429`) also echoes 800 chars of error body regardless of `-q`. — `7502f19` — `clearValue`/`cipherValue`/`digestValue` masked
- [x] **S7 — Release workflow persists a write token during the build.** `release.yml:467-499`. Set `persist-credentials: false` or split build and publish jobs. — `f98693a` — every checkout in release and CI
- [x] **S8 — `-o FILE` rewrites in place and keeps an existing file's mode.** `Io.java:111-113`. Write to a temp file and rename; restrict mode on overwrite too. — `b47fdde`
- [x] **S9 — Live `diff -` leaves the desired object in `/tmp/midgie-stdin-NNN.xml` if killed.** `DiffCommand.java:172-173`, `Io.java:62`. *Plausible.* Messages also name the temp path instead of "stdin". — `e8f8791` — private directory, removed in `finally`; a `kill -9` can still leave it

### Swallowed or under-reported errors

- [x] **E1 — `--smart-refs verify` treats any failure (401/403, transport) as "changed".** `SmartReferences.java:98-103`. Logged only at TRACE. No tests for verify. — diff group
- [x] **E2 — `NaturalKeys.of()`/`carries()` catch `RuntimeException` silently.** `NaturalKeys.java:43-47, 62-80`. — diff group (unsupported stays quiet, anything else warns once per item)
- [x] **E3 — A failed inverse drops the object from the rollback manifest.** — `373271b` `RollbackPlan.java:187, 197-206`. Logged at DEBUG; the Javadoc at `:171-172` claims the manifest shows it. Deploy reports "N object(s) undoable" with no warning.
- [x] **E4 — Failed state write is hidden by `-q`.** `DeployCommand.java:891-894`. Use `warn()`. — small-items group
- [x] **E5 — Unreadable `--schema-dir` is treated as empty.** `SchemaOptions.java:641-647`. — `87c3be7`
- [x] **E6 — Xerces prints raw `[Fatal Error] …` to stderr, ignoring `-q`.** `Io.java:91`, `SchemaOptions.java:659`, discovery. Install a silent error handler. — input group (Io.parseXmlFile/parseXml)
- [x] **E7 — Generic `IOException` from `send` is neither retried for safe reads nor wrapped; `SSLException` is not a `TransportException`.** `MidpointRestClient.java:467, 473-485`. — input group
- [x] **E8 — `isProxyAuthFailure` matches any message containing `exchImpl`.** `MidpointRestClient.java:614-624`. *Plausible.* — input group
- [x] **E9 — Object with no recorded version is replayed without `--force`; `-q` hides the warning.** `RollbackCommand.java:231-234`. Contradicts Javadoc at `:222`. — rollback group
- [x] **E10 — Rollback provenance check silently skipped when local identity can't be derived.** `RollbackCommand.java:205-212`. — rollback group

### Correctness edge cases

- [x] **C1 — Rollback run directories named to the second can collide.** `RollbackPlan.java:259`; `PlanStore.write` overwrites only same-named files, so two runs mix. — rollback group
- [x] **C2 — Different deployment keys can share a rollback directory.** `RollbackPlan.java:387` (`root/a/b_c` vs `root/a_b/c`). — rollback group (rotation and lookup per deployment, no renaming)
- [x] **C3 — `diff --trace` hides key-pairing notes.** `DiffCommand.java:213` gates on `debug && !quiet`; live path uses `debug || trace`. — small-items group
- [x] **C4 — All-in-sync interactive run records no tracking state.** `DeployCommand.java:1012-1017, 512`. `--yes` does record. — tracking group
- [x] **C5 — `--oid` ignored on a schema-only run.** `DeployCommand.java:441-443`. — deploy group
- [x] **C6 — `--dry-run`/`--plan` disagree with a real run on unprunable removals.** `DeployCommand.java:501`. — deploy group
- [x] **C7 — Non-interactive detection breaks on JDK 22+.** `DeployCommand.java:1407-1435`. `System.console()` is non-null when redirected; EOF → ABORTED → exit 0. *Plausible* (image ships JRE 17). — deploy group (Io.console(); not run on JDK 22 here)
- [x] **C8 — Applied plan can differ from the confirmed preview.** `DeployCommand.java:1013-1023`. *Plausible.* Compare `changed()` between passes or document. — deploy group (changed objects are refused, checked against the rig through a pty)
- [x] **C9 — OID-less fragment silently joins the first of two same-named objects.** `DeployObject.java:81-91`. *Plausible.* — deploy group
- [x] **C10 — Duplicate schema `targetNamespace` and cross-tier shared OIDs go unreported.** `DeployCommand.java:939-942`. *Plausible.* — deploy group
- [x] **C11 — History grouping depends on file order.** `RemovalDetector.java:509-526`. *Confirmed.* False orphans and missed removals when identities are split across files. — tracking group
- [x] **C12 — Removal policy resolved against historical name/OID.** `DeployCommand.java:683`, `RemovalDetector.java:687-690`. After a rename the new-name rule misses. — tracking group
- [x] **C13 — `claim()` pairs history with a different object sharing Type/Name.** `RemovalDetector.java:532-541`. *Plausible.* — tracking group
- [x] **C14 — FileTracking reads the last row per key, SyncState updates the first.** `FileTracking.java:97` vs `SyncState.java:209`. *Plausible.* — tracking group
- [x] **C15 — State write built from the run-start table snapshot; one stale row fails the whole PATCH.** `SyncState.java:202-254`. *Plausible.* — tracking group (fresh read, one retry)
- [x] **C16 — Policy selectors collapse whitespace in unquoted names.** `Policy.java:128, 146`. — diff group
- [x] **C17 — Accept mode can keep the wrong live value of a multi-valued reference.** `DiffEngine.java:721-742`. *Plausible.* — diff group (pairs only what can be told; replace path sends a replace, merge path sends nothing and warns; probed on 4.10.5)
- [x] **C18 — Verify cache keyed on `SearchFilterType.toString()` (identity hash).** `SmartReferences.java:55`. — diff group (one search per query)
- [x] **C19 — Undefined items treated as multi-valued in fragment union.** `FragmentUnion.java:230, 250`. *Plausible.* Contradictions become two values. — diff group
- [x] **C20 — `ReferenceCollector` collects operational refs (`linkRef`, `roleMembershipRef`, metadata).** `ReferenceCollector.java:31-35`. *Plausible.* Spurious ordering edges/cycle warnings. — diff group
- [x] **C21 — Config files read as ISO-8859-1.** `ConfigDefaults.java:342-345`. Load via a UTF-8 `Reader`. — input group
- [x] **C22 — Stdin: BOM breaks parsing, empty stdin gives "String index out of range: 0", encoding declaration ignored.** `Io.java:48`. — input group
- [x] **C23 — Connect timeout fixed at 10 s; huge `--timeout` overflows `int`.** `MidpointRestClient.java:151`, `ServerCertificates.java:82, 84, 223-224`. — input group
- [x] **C24 — Lone non-self-signed leaf becomes the trust anchor without saying so.** `ServerCertificates.java:112-117`. `--cacert` then breaks at renewal. — input group (written with a warning)
- [x] **C25 — Retrying a partly failed rollback needs `--force`, which disables staleness protection for all objects.** `RollbackCommand.java:257-276`. — rollback group
- [x] **C26 — Unfinished rollback run gives `--plan`/`--apply-plan` wording.** `PlanStore.java:173, 176`. — rollback group
- [x] **C27 — OID characters not validated when writing plan files.** `PlanStore.java:139-146` vs `:256`. *Plausible.* — `bf0dcda` (with S1)
- [x] **C28 — `init` writes some files, then refuses.** `InitCommand.java:200-202, 231-250, 261-265, 529-535`. — init group
- [x] **C29 — `init --write-cacert -` mixes status text into the PEM on stdout.** `InitCommand.java:331-339`. — init group
- [x] **C30 — `init` ignores `app` when reporting identity; `--app` Javadoc contradicts code.** `InitCommand.java:382, 562`; `DeploymentIdentity.java:29-30` vs `:69-75`. — init group (init --app; Javadoc now says --app replaces only the key)
- [x] **C31 — Git blobs decoded as UTF-8 and stripped; inherited `GIT_DIR`/`GIT_WORK_TREE` override the tree.** `GitRevision.java:373, 396`. *Plausible.* — input group
- [x] **C32 — Offline `diff` silently accepts `--no-prune`, `--no-match-by-name` and connection options.** `DiffCommand.java:94-107, 151-155`. — small-items group (a note, not a refusal: the options are often in wrappers)
- [x] **C33 — OidComments round trip breaks when the root element shares a line with `<?xml?>`.** `OidComments.java:315-319, 428, 487`. *Plausible;* hand-written plan files only. — small-items group
- [x] **C34 — `midgie-state` documentation has a long run of spaces.** `SyncState.java:80` (missing `\` in the text block). — small-items group
- [x] **C35 — DESIGN claims `--rollback-plan` dir is excluded from discovery by path.** `DeployCommand.java:1192-1201` vs DESIGN:425. Harmless in practice (marker skips it); fix code or doc. — small-items group (code now excludes it, as DESIGN says)
- [x] **C36 — TopoSort cycle breaking is O(V²·(V+E)).** `TopoSort.java:368-375`. Performance only. — small-items group (one SCC pass per break)
- [x] **C37 — `RemovalDetector.livePath` pairs history values with live ones through `NaturalKeys.match`'s id step.** `RemovalDetector.java:144`. Found while fixing B3. With ids from another server a nested removal can address the wrong live entry; `heldValue`'s real-value check limits it to values equal to what the tree deployed. Run the history object through `ContainerIds.trustedOnly` against live before pairing. — tracking group (pairs by key or content when the ids contradict, rather than through trustedOnly)
- [x] **C38 — Merge sent an empty `<delete>` item delta beside an add.** `DiffEngine.applyMerge`. Found while fixing B3; every merge that kept all values a delete side would have removed emitted it, and the summary read `add+delete`. — `d5b9643`

### Code hygiene and packaging

- [x] **H1 — Misplaced javadocs.** `StrategyOptions:66`, `ConnectionOptions:108`, `ConfigDefaults:496`, `MidpointRestClient:268, 545`, `DiffEngine:664-671, 1156-1160`, `DeltaOutput:78-82`. — small-items group
- [x] **H2 — Shaded manifest lacks `Multi-Release: true`.** `pom.xml:113-141`. Versioned classes under `META-INF/versions` are ignored. — small-items group
- [x] **H3 — Surefire isolation misses `MIDGIE_CONF`, `~/.midgie.properties`, `/etc/midgie/`, tree-local config above the checkout.** `pom.xml:152-170`. — small-items group (MIDGIE_CONF blanked, user.home and midgie.site.conf redirected; tree files above the checkout were already out of reach)
- [x] **H4 — `TextDiff` comment about trailing empties is wrong; CRLF values keep `\r`.** `TextDiff.java:661-664`. — small-items group (trailing empties kept on purpose; comment fixed)

## Test coverage to add

- [x] T1 — Rollback replay offline (stub server): staleness refusal, `--force`, `--dry-run` exit, partial failure, missing version. — rollback group (RollbackReplayTest)
- [x] T2 — `rotate` with non-run directories, `.`/`..` keys, symlinks. — rollback group (RollbackPlanTest)
- [x] T3 — Nested smart references; `--smart-refs verify`. — diff group (SmartReferencesVerifyTest, SmartReferencePairingTest)
- [x] T4 — Untyped/raw protected values. — rollback group (DiffCommandTest)
- [x] T5 — Desired ids from another environment under merge and replace. — rollback group (ForeignContainerIdTest)
- [x] T6 — `ApplyRules` with add/delete of a parent container value. — rollback group (ApplyRulesTest)
- [x] T7 — `RemovalDetector` exception paths; history grouping with split identities. — tracking group (grouping; the exception paths were covered with S/M fixes)
- [x] T8 — `SyncState.apply` delta construction (add / replace-by-id / delete, duplicate keys). — tracking group (SyncStateTest)
- [x] T9 — Symlinks in discovery; malformed XML files. — rollback group (DeployCliTest)
- [x] T10 — `--insecure` path, hostname mismatch with `--cacert`, no secrets in debug/trace output. — input group (TlsClientTest; masking in MidpointRestClientTest; the name-check switch stays Main's)
- [x] T11 — Password precedence with `password` in the file; OID escaping. — rollback group (ConfigDefaultsTest, MidpointRestClientTest)
- [x] T12 — stdout encoding and write failure, `Error` exit code, `--opt=value` redaction, BOM/empty stdin. — input group (IoTest for BOM/empty stdin)
- [x] T13 — `GlobPath` and `ReferenceCollector` directly; `VersionSupport`/`LiveLookup` without the docker rig. — small-items group (GlobPathTest, ReferenceCollectorTest, LiveReadTest)
