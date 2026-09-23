# midgie — design

A standalone, schema-aware CLI that computes the **minimal `itemDelta`** between two midPoint
objects and (optionally) applies it through the model REST API. Core value: a thin wrapper around
midPoint's own `PrismObject.diff` that emits the exact `ObjectModificationType` XML the REST `PATCH`
endpoint consumes.

This file is the build-ready brief. It is self-contained — you do not need the conversation it came
from. Verify any API detail against the midPoint source at `/Volumes/Daten/git/midpoint`.

## Why this exists

midPoint has no clean, re-runnable, *field-level* config-promotion path:

- `post-initial-objects` is one-shot (gated by a `.done` rename) — seeding, not update.
- `ninja import -O` is a raw repository write — bypasses model logic / authorizations / audit, and
  leaves a running cluster on stale caches until restart.
- REST `PUT` / ninja `-O` do a **full replace** — they reset everything not in the source XML, which
  wipes accumulated runtime state (assignments, links, metadata) on any object that isn't pure config.

What's missing is a tool that changes **only the fields you manage** and preserves the rest. midPoint
already ships the engine (`PrismObject.diff`); this CLI is the glue. The offline two-file diff mode is
independently useful (drift detection, migration deltas, dry-run, review), which is what makes it worth
open-sourcing rather than burying in a pipeline.

## Engine (all confirmed against midPoint 4.10.x)

The core is ~5 lines:

```java
// Base schema is loaded from the classpath — no Spring, no DB, no running midPoint.
// Extension XSDs (--schema-dir / --schema / read from the live server) are registered afterwards,
// in a single registerDynamicSchemaExtensions batch -- see "Schema" below.
PrismContext ctx = new MidPointPrismContextFactory().createInitializedPrismContext();

PrismObject<?> desired = ctx.parseObject(desiredFile);
PrismObject<?> live    = ctx.parseObject(liveFile);          // file mode; or GET over REST in live mode

// Direction matters: before.diff(after) yields the delta that, applied to `before`, produces `after`
// (prism contract, proven by TestParseDiffPatch: userBefore.diff(userAfter) then applyTo(userBefore)
// == userAfter). The emitted delta is the REST PATCH body applied to the LIVE server object to drive
// it to the desired state, so it must transform live -> desired. Hence live.diff(desired), NOT the
// reverse — desired.diff(live) would emit a PATCH that overwrites your config with the stale live value.
ObjectDelta<?> delta   = live.diff(desired, EquivalenceStrategy.REAL_VALUE); // ignores metadata/operational
ObjectModificationType mod = DeltaConvertor.toObjectModificationType(delta); // == the REST PATCH body
```

Confirmed API facts (source references are into `/Volumes/Daten/git/midpoint`):

| Element | FQN / note |
|---|---|
| `MidPointPrismContextFactory` | `com.evolveum.midpoint.schema.MidPointPrismContextFactory` — ctors `()` and `(File extraSchemaDir)` (infra/schema:56, :60); `createInitializedPrismContext()` (infra/schema:179) throws `SchemaException, SAXException, IOException` |
| `PrismContext.parseObject(File)` | returns `PrismObject<T>` (see `TestParsePasswordPolicy`, `TestParseGenericObject`). Modern form: `ctx.parserFor(file).xml().parse()` |
| `PrismObject.diff(other, EquivalenceStrategy)` | returns `ObjectDelta<T>` (`com.evolveum.midpoint.prism.delta.ObjectDelta`); see `TestDiffEquals:456` |
| `EquivalenceStrategy.REAL_VALUE` | ignores operational/metadata items **and container ids** — the default for config diffing (proven in `TestParseDiffPatch`). The `_CONSIDER_DIFFERENT_IDS[_NATURAL_KEYS]` variants turn container-id / natural-key matching **on**, which makes the diff *pair* multi-valued entries and edit them in place; id-agnostic plain `REAL_VALUE` instead emits clean whole-value add/delete. Merge defaults to plain `REAL_VALUE` for that reason (see "Strategy flags"); the id-considering variants are still selectable via `--equivalence` for exploration (`natural-keys`, the experimental preset, warns) |
| `DeltaConvertor.toObjectModificationType(ObjectDelta)` | `com.evolveum.midpoint.schema.DeltaConvertor` (infra/schema:237) → `com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType`. Also `toObjectDeltaType` (:259) and `toItemDeltaTypes` (:429) |
| `Item.diffModifications(other, strategy)` | returns `List<ItemDelta>` — useful for a **scoped** per-item diff (see `TestDiffEquals:565`) |

prism itself (`com.evolveum.midpoint.prism.*`: `PrismObject`, `PrismContext`, `ObjectDelta`,
`EquivalenceStrategy`) is an **external** library pulled transitively by `midpoint-schema`; it is not in
the midPoint source tree.

**Safety guards** applied around that core (`DiffEngine`):
- *Type / OID cross-check* — the diff refuses two objects of different compile-time types, and (new) two
  objects that both carry an OID when the OIDs differ, so a wrong `--oid` or wrong file cannot silently
  produce a delta that rewrites one object into another. A desired object with no OID is an unbound
  template and is allowed (the live OID governs).
- *Protected/encrypted values* — `ProtectedStringType` item deltas (e.g. `credentials/password/value`) are
  dropped from the delta and their paths reported on stderr: the ciphertext re-encrypts on every fetch, so
  diffing it yields spurious deltas *and* would leak the secret into the PATCH. midPoint owns credential
  rotation; a real password change goes through its own API, not a blind PATCH of stale ciphertext.
  (Detection is by schema type or a protected real value; a protected value nested inside a larger
  added/deleted container is not walked — rare, and noted in the code.)
- *Fragment union* — several files may describe one object, and they are combined into a single desired
  object before anything is diffed (`FragmentUnion`, built at group time by `DeployObject`). The apply unit is
  therefore the **object**, not the file. This is what makes cross-file composition correct *by default*:
  reconciled file by file, two files each contributing a `panel` produced two PATCHes and the second replaced
  what the first wrote (the "last-one-wins" clobber the grouping used to warn about), and the only workaround
  — `multivalue=merge` — bought that back at the price of removals, since under merge a value deleted from a
  file yields an empty delta and survives unnoticed. Combined first, plain `replace` writes both files'
  contributions and a deleted entry is still a deletion. Entries of a multi-valued container are paired **by
  container id, else by the schema's `a:naturalKey`** (`NaturalKeys.match`, shared with the diff's
  accrued-value guard so both answer "is this the same entry?" identically): two files that each declare
  `objectDetailsPage[type=UserType]` must yield *one* page holding both their panels. Note where that puts
  natural-key matching — file against file, both sides the operator's own, visible in `--plan`, with nothing
  accrued at risk. Values that can be paired by neither are appended, de-duplicated by real value, so the same
  entry written into two files collapses while two merely similar ones are kept apart. Operational items are
  never merged in. A single-valued item that two files disagree on is an error: the grouping check catches
  those it can reach, and the union catches the ones only visible once two entries have been paired (a
  contradiction *inside* a keyed container value), since picking one would make the result depend on the order
  the files were read in.
- *Items the server withholds* — midPoint does not return some items unless asked, marking them `incomplete="true"`; `LookupTableType/row` is the case that bites, `jpegPhoto` another. "Not sent" must never be read as "empty", or a lookup table re-proposes its whole contents every run and rows nobody can see are quietly in line for deletion. Which items those are is not in the bundled schema (midPoint decides it in its repository layer), so `LiveLookup` follows the server's own marker: an object that comes back carrying one is re-read with `?include=<path>`, and only such an object pays the second request. Anything still incomplete after that is one the server will not give up at all, and `DiffEngine.stripIncompleteItems` drops its item deltas and reports the paths — the same shape as protected values, for the same reason.
- *Delete detection (three-way merge)* — a two-way diff cannot express a removal: a value dropped from a
  desired file simply stops being mentioned, so under `merge` the delta is empty and the value survives while
  `diff` reports no differences. `deploy` therefore also compares desired against **last-applied**, which for
  a version-controlled tree is `git show <lastRev>:<file>` — so the only persisted state is one revision per
  tree, the analogue of ArgoCD's `status.sync.revision`. The removal set is the *delete side* of an ordinary
  `DiffEngine` run with last-applied in the live slot, so no new diff logic exists. It works per **object**
  on both sides — the union of the files describing it now against the union of the files that described it
  at that revision, deleted ones included — which is what makes it correct for objects assembled from several
  files: a deleted fragment yields exactly the values no surviving file still declares, and a value merely
  *moved* between two files of one object never appears at all, both falling out of the comparison rather
  than needing a subtraction pass to undo. The object itself is never a deletion candidate
  (`SystemConfiguration` is `indestructible` anyway); one whose every file has gone is reported as an orphan.
  Each removal still names the individual file it came from, which is what an operator reviewing a prune acts
  on. Reported by default; deleting is opt-in. State lives in a `midgie-state` `LookupTableType` created solely
  by `midgie init --create-state` — never implicitly, so midgie leaves no unexplained object behind.

  `--prune` then applies the detected removals: `--dry-run`-able, gated by its own confirmation (the apply
  pass returns without prompting when it has nothing to do, so a prune-only run would otherwise delete
  unconfirmed), and run *after* the tree is applied — and only when the tree really was applied, since a
  declined or partly skipped run leaves the server holding something the removal set was not computed
  against. Its delta is assembled only from values that were in last-applied (`RemovalDetector.pruneDelta`),
  which is what makes it structurally unable to reach accrued or foreign state; an object's `name`, and any
  mandatory item, is never a candidate. `init --write-policy` seeds a starter policy from the tree,
  suggesting natural-keys+merge only for containers that are keyed **and carry the whole key** —
  `NaturalKeys` holds that judgement, shared with the input warnings, because a declared-but-unused key
  (`assignment`'s `identifier`) makes prism pair unrelated values, and half a composite key (`kind` without
  `intent`) discriminates nothing. The state object carries `indestructible`, a `description`/`documentation`
  aimed at an admin who has never heard of the tool, `subtype=midgie`, and a `lifecycleState` an admin can
  set to `archived` to switch tracking off. The recorded revision is verified before anything is compared
  against it, and validated as a commit sha before it reaches git's argv — it arrives over the network, and
  an unreachable revision must not read as "nothing was removed". Identity is the repository's root commit
  (along first parents, so merging an unrelated history does not change it) plus the tree's path within it:
  content-addressed, so every clone agrees regardless of its remotes. `--app` overrides the key, not the
  need for history; shallow clones are refused either way, since their apparent root is only the grafted
  boundary. `GitRevision`, `DeploymentIdentity`, `SyncState`, `RemovalDetector`, `InitCommand`.
- *Smart references* — a reference may name its target by search filter instead of OID, which is how a
  resource file stays portable across instances whose connector OIDs differ. midPoint resolves the filter
  **at import** and stores the OID; the file still carries the filter. Diffed literally that is a change, and
  the PATCH strips the OID — and a modify does not re-resolve the filter, so the object ends up pointing at
  nothing (observed: a CSV resource broken exactly this way). So `DiffEngine` drops the paired
  add-filter/delete-resolved delta when the live OID satisfies the desired filter, and any PATCH that does
  set a reference by filter is sent with `?options=reevaluateSearchFilters` so the server rebinds it.
  `--smart-refs accept|verify|resolve` chooses how much evidence is required; `verify` runs the filter
  against the server, which is the only way to settle it when midPoint dropped the filter after resolving.
  The judgement keys on the prism *value kind*, never on an element name — only a `PrismReferenceValue` has
  a filter, so the schema's many unrelated `SearchFilterType` fields cannot be mistaken for one.
  `SmartReferences`, `DiffEngine.SmartReferenceCheck`.
- *Blast-radius reporting and input checks* — a partial fragment can silently destroy live state, so the
  danger is reported before any write (advisory, never blocking; `--quiet` silences it). Every summary line
  carries the number of values it removes, and a removing delta prints a prominent `WARNING: this change
  REMOVES n values`. On top of that, `DiffEngine.findInputWarnings` statically walks desired against live,
  pairing keyed container values, and flags (a) a value of a keyed multi-valued container that does not carry
  its `a:naturalKey` — it cannot be matched, so it degrades to a whole-value change — and (b) a keyed
  container that desired only partially declares while that path is on `replace`, naming `multivalue=merge`
  as the fix. Only keyed containers are checked, which keeps it precise and leaves the key-matched merge
  recipe warning-free. Surfaced through `DiffResult.inputWarnings()` / `removalWarning()`.
- *Accrued in-place merge edits* — when `merge` runs under an id/natural-key-considering strategy, prism can
  pair an accrued (live-only) value with a desired value and edit it in place rather than doing a clean
  add-and-keep; merge cannot preserve it. Detected per delta and refused by default (`--allow-inplace-merge`
  to proceed). See "Strategy flags". An edit is only *accrued* when desired does not declare the value the
  path descends through: each container-id segment is resolved against desired by container id, or — since a
  hand-written fragment carries no ids — by the schema's `a:naturalKey`, and a key only counts when both
  values actually carry its constituents (an absent key would otherwise match everything and disable the
  guard). This is what makes `equivalence=natural-keys` + `multivalue=merge` the way to sync one entry nested
  inside multi-valued containers (e.g. a single GUI `panel` inside an `objectDetailsPage`) without needing
  `--allow-inplace-merge`.

## Commands / modes (layered)

**v1 = the offline core.** Live and apply are thin adapters added in a second increment; `deploy` and
`init` came later still. All four subcommands are listed here.

- `midgie diff <desired.xml> <live.xml>` → itemDelta XML on stdout. No REST, no server.
- `midgie diff <desired.xml> --url <url> --user <u> --password <p> [--oid <oid>]` → GET the
  current object (type + OID read from the desired file if not given), diff, emit itemDelta.
- `midgie apply <desired.xml> --url … [--no-create-if-absent]` → diff then
  `PATCH /ws/rest/{type}/{oid}`; when the search finds nothing, `POST /ws/rest/{type}` the whole
  object (operational/metadata items stripped first). This is the reconciler. Because it writes, it shows
  the pending change and confirms first: it prompts interactively, or refuses in a non-interactive session
  unless `--yes`/`-y` is given. `--dry-run` prints the delta and changes nothing.
- `midgie deploy <tree> [--url …]` → discover a folder tree, sort it by dependency, and print the apply
  order or reconcile it object by object. Its own section below.
- **Config file (`midgie.properties`)** → defaults for any option, so the settings that never change for an
  environment (server, account, CA certificate) leave the command line. Read from `/etc/midgie/midgie.properties`, then
  `$HOME/.midgie.properties`, then a file named by `$MIDGIE_CONF`/`-Dmidgie.conf`, and **merged per key** with
  later layers winning — a site file holds what is true of the machine, a user file adds only what differs.
  A named path that does not exist is an error rather than a silent skip, and each key remembers which file
  it came from so a validation error names the right one. Keys are option names without dashes, optionally qualified
  with a command; precedence is CLI > `MIDPOINT_*` env > file > built-in default. `ConfigDefaults` wraps
  picocli's `PropertiesDefaultProvider` (which looks up `<qualified.command>.<key>` then `<key>`) and adds the
  env rule plus validation: an unknown key is an error, since a mistyped setting that silently does nothing is
  the failure mode the policy file's "matched no object" note exists to prevent, and `yes`/`prune` are refused
  because a file is not where a person confirms a write or a delete. `insecure` is also read in `Main.main`,
  before the hostname-verification latch, or it would half-apply.
- `midgie init …` → the one-off set-up steps, each behind its own flag: `--create-state` creates the
  `midgie-state` object that delete detection needs, `--write-policy` seeds a starter `--policy` file for a
  tree, `--write-cacert` fetches the chain a server presents and writes its issuers as the PEM `--cacert`
  takes (trust-on-first-use, so fingerprints are shown and confirmed; no account needed, since you cannot
  authenticate to a server you do not trust yet), and `--write-properties` writes an annotated config
  template generated from the command model. The last two need no server, so the live check applies only
  to the first two. Never implicit, at two levels: `deploy` reports that tracking is off rather than creating anything,
  and `init` with no step prints its help and exits 2 rather than picking one — every step writes something,
  to a server or to the working copy.

Connection details default from `MIDPOINT_URL` / `MIDPOINT_USER` / `MIDPOINT_PASSWORD`. To keep the password off the command line (it leaks into `ps` and shell history), resolve it — in order — from `--password`, `--password-env <VAR>`, `--password-file <FILE>`, `MIDPOINT_PASSWORD`, or an interactive prompt. REST failures carry an actionable hint (401 auth, 403 authz, 407 proxy, connection refused). `--timeout` sets the per-request timeout; `--retries <n>` retries idempotent reads (including the `POST {type}/search` every object lookup uses) on 502/503/504 / connection-refused / timeout with exponential backoff (rides out a server that is still starting). For https, `--cacert <FILE>` verifies the server against a CA certificate; `--insecure` trusts any cert and skips hostname verification. The `docker/tls/` rig (an nginx TLS proxy in front of midPoint + a client container) exercises these end-to-end.

A **forward proxy** is `--proxy <URL>` with `--no-proxy <HOSTS>`, or `https_proxy`/`http_proxy`/`no_proxy` — the JVM reads none of those variables itself, but the `git` in the container image does, so a pod configured the usual way would otherwise clone its tree and then fail to reach the server. Authentication is Basic, from `--proxy-user` plus the same password ladder, or from a `user:pass@` in the environment's URL (accepted there, stripped from `--proxy` for the reason it is stripped from `--url`). Two things are decided once per process, before any client exists, because the JDK reads both properties on first use of `java.net.http`: hostname verification (`--insecure`) and whether Basic is allowed on a `CONNECT` tunnel — the latter only for a run that configured credentials, so the JDK's default protection stays put otherwise. Configure nothing and no selector is installed at all, leaving `-Dhttps.proxyHost` to work as before. It stays inside `MidpointRestClient` and `ServerCertificates`; the engine remains REST-free, and `ServerCertificates` is the one place that speaks `CONNECT` by hand, because it inspects a handshake rather than making a request.

## Strategy flags

The source XML stays **pure midPoint XML** — no custom in-XML annotations, so the same file still works
with midPoint Studio, ninja, and REST `PUT`. Behaviour is controlled by flags:

- `--scope present|full` (default **present**) — *present*: emit deltas only for items that appear in the
  desired XML (managed = present; items absent from the desired object are left alone → preserves
  accumulated state). Presence is judged at the **full item path** (via the delta's id-free prefix), not
  just the top-level item, so an unmentioned *child* of a single-valued container is preserved too — this
  lets separate files fill different children of one container without clobbering each other; protection
  collapses only at the first multi-valued container (a live-side container id can't be matched to id-less
  desired XML). *full*: full-replace semantics (also removes items missing from desired).
- `--multivalue replace|merge` (default **replace**), plus per-path `--path <itemPath>=merge|replace` —
  *replace*: the desired container values are authoritative (live-only values are removed). *merge*: union
  by content — merge is designed for and defaults to `REAL_VALUE` (values matched by real content, ids
  ignored), keeps the additions, and drops the deletion of live-only values so accrued runtime state
  survives. Caveats: an in-place edit of a managed value reads as "old value kept + new value added" (keep
  that path on *replace* if you want a true in-place update); a single-valued item, or any item that diffs
  to a whole-value replace, cannot merge and is left as replace (noted on stderr).

  > **Why `replace` stays the default** (considered and rejected: defaulting to `merge` to reduce the risk of accidental deletion). `merge` can express neither an edit nor a removal: a content change reads as "old gone, new added" and merge drops the deletion, so values accumulate (`[alpha]` + desired `[beta]` → `[alpha, beta]`); and dropping an entry from the desired file produces an *empty* delta, so `diff` reports "No differences" and exits `0` while the live object still holds it — which silently defeats drift detection and the CI exit-code contract, the very thing that would tell you there is cruft to clean up. The risk that motivated the question — a partially declared multi-valued container deleting what it does not list — is instead handled by the input warnings (see "Safety guards"), which name the container, the counts, and the fix.
  >
  > Equivalence *is* schema-dependent, and `auto` is the default that makes it so. An earlier version of this note said a schema-dependent equivalence default was "not expressible anyway", because equivalence is a single input to prism's whole-object diff (`live.diff(desired, strategy)`) unlike `multivalue`, which midgie varies per path by post-processing the delta. That is true *within one diff* and remains the reason per-**path** equivalence is not offered; it is not a reason to diff the object only once. See `--equivalence auto` below.
- `--equivalence auto|real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal`
  (default **auto**) — the value-equivalence test that decides what counts as drift and how entries are
  matched. `auto` chooses between the first two of the named strategies per container; the rest are listed
  loosest to strictest:
  - *auto* — **the default.** Per multi-valued container, `natural-keys` where the schema declares a key and every desired value fills it in, `real-value` everywhere else. The object is diffed under both and each item delta is kept from the pass its path belongs to, because prism takes one equivalence per whole-object diff. Buckets are cut only at multi-valued containers reachable through single-valued ones, and a whole subtree is rejected if anything inside it has an unfilled key — the two strategies express a change inside a multi-valued container at different depths (`objectDetailsPage/84/panel` vs a whole-value replace of `objectDetailsPage`), so a cut inside one would lose or duplicate it. With nothing to pair this is a single `real-value` pass, identical to the pre-`auto` default.

    What it buys: a keyed entry is updated **in place**, keeping its container id and the metadata hanging off it, where `real-value` alone replaces the whole container. What it avoids: prism's `DefaultNaturalKeyDefinitionImpl.valuesMatch` treats a key constituent absent from *both* values as matching, so an object-wide `natural-keys` pairs unrelated values positionally on the many types that declare a key real config never fills in — `assignment`, `inducement` and `authorization` among them. Deciding per container from whether the key is actually filled in keeps those out on their own evidence, with no hardcoded deny list.

    Only `real-value` and `natural-keys` are ever chosen. `consider-different-ids` makes no difference for an id-free desired file (its rule is that ids must not *contradict*, and `null` vs `123` does not); `ignore-metadata` re-considers reference filters, which would fight `--smart-refs`; and `data`/`literal` consider operational data and metadata, so against a live server they never diff empty and would break the exit-code contract. Note that `natural-keys` is built from `REAL_VALUE_CONSIDER_DIFFERENT_IDS`, so that bucket carries the non-contradicting-ids rule too.
  - *real-value* — ignores container ids, metadata, operational items, element names, ns prefixes, ref
    filters/options. No churn from midPoint's own bookkeeping; what `auto` falls back to.
  - *consider-different-ids* — as *real-value*, but PCV ids must not contradict (pairs/edits entries by id).
  - *natural-keys* — as *consider-different-ids*, plus container natural keys. Maps to
    `REAL_VALUE_CONSIDER_DIFFERENT_IDS_NATURAL_KEYS`; prism-`@Experimental`, so it prints a note on stderr
    when used. Exposed for exploration against a real server.
  - *ignore-metadata* — ignores metadata/operational/ids, but keeps element names and reference filters/options.
  - *data* — full parsed data incl. metadata and exact ids; ignores only serialization noise (prefixes).
  - *literal* — as *data*, but ns prefixes matter too (≈ comparing raw XML).

  `merge` is designed for `real-value` (clean, id-agnostic union). It is still allowed under the other
  strategies for exploration, but an id/natural-key-considering strategy can pair a live-only (accrued)
  value with a desired value and emit a deep **in-place edit** (e.g. `assignment/2/targetRef`) that merge
  cannot express as a union — so the accrued value is silently overwritten. The engine detects that
  **per delta** (`DiffResult.accruedInPlaceEditPaths`, comparing the edited container-id against the desired
  object) and both `diff` and `apply` **refuse by default** (exit 2), naming the offending paths.
  `--allow-inplace-merge` downgrades the refusal to a warning and proceeds. An in-place edit of a value that
  *is* present in desired is a legitimate managed update and is not flagged.

## Schema (two layers, two knobs)

- **Base** model (UserType, RoleType, SystemConfigurationType, … and their item definitions) is
  **compile-time** — generated Java bound to XSD, loaded from the `midpoint-schema` jar. It **cannot** be
  supplied as XML. The bundled jar version (`midpoint.version` in `pom.xml`) must match the target's major
  line. This is why a fat JAR is version-specific (ninja and midPoint Studio are too).
- **Extension** schema (custom `extension` attributes, custom `SchemaType` objects) is **runtime**:
  - *File mode:* `--schema <file>` (repeatable) and/or `--schema-dir <dir>`. Both accept either format,
    chosen by extension: a `.xsd` is a raw XSD, a `.xml` is an exported `SchemaType` object (its embedded
    `<xsd:schema>` is used). Other extensions — and `.xsd`/`.xml` files not in the expected format — are
    warned about and skipped. Everything collected is registered via
    `schemaRegistry.registerDynamicSchemaExtensions(...)` + `prismContext.reload()` — the same call
    midPoint's `repo-common/SchemaCache.java` (:81, :89) makes at startup.
  - *Live mode:* read the `SchemaType` objects off the server (`--url/--user/--password`) and register
    them the same way (best-effort — a read failure warns and falls back to base schema). `--no-live-schema`
    skips this `GET /schemas` read (faster, or for accounts without schema-read); extension items then need
    `--schema`/`--schema-dir` or they diff as raw.
  - Note: `--url` supplies *extensions* only. The base jar is always needed locally, because the tool
    parses both objects into `PrismObject`s locally to run the diff.

## Output & exit codes (CI-friendly)

- `--format xml` (default) / `json` emit the `ObjectModificationType` (both consumable by REST `PATCH`);
  `summary` is a human-readable changed-path list. Output goes to stdout, or to `-o <file>`.
- The desired (and offline live) object may be read from stdin by passing `-` as the path.
- `--debug` / `-v` raises the log level to DEBUG on stderr for troubleshooting (stdout stays the payload).
- Exit-code contract (lets CI gate on drift without parsing output):
  - `diff` and `apply --dry-run`: `0` = no differences · `1` = differences found / would change · `2` = error.
  - `apply` (real write): `0` = success (applied, created, already in sync, or user-declined) · `2` = error.
- `--quiet` / `-q` drops advisory notes/warnings and (for `apply`) status lines, keeping the delta payload,
  error messages, and the exit code — for clean pipeline output.

## Packaging & license

- Maven, Java 17, fat JAR via `maven-shade-plugin` (needs a JVM; GraalVM native is impractical given
  prism's reflection/JAXB). Dependencies: `com.evolveum.midpoint.infra:schema` (pulls prism), picocli,
  JUnit 5; live/apply use the JDK 11+ `java.net.http` client — no extra dependency.
- **Launcher `bin/midgie`.** On JDK 23+, classgraph (a transitive prism dependency) calls the
  terminally-deprecated `sun.misc.Unsafe::invokeCleaner` to unmap memory-mapped schema jars during prism
  bootstrap, which prints JEP 498 warnings. Those go to the *native* stderr (fd 2), bypassing `System.err`,
  so they cannot be filtered in-process — the only suppression is the launch flag
  `--sun-misc-unsafe-memory-access=allow`, which can't live in a `-jar` manifest. The launcher adds it, but
  **only when the running JVM is major ≥ 23** (the flag is unknown to JDK ≤ 22, which does not emit the
  warning). Running `java -jar` directly instead? Set `JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.
  Forward-looking: once a JDK defaults this to `deny`, the flag becomes *required* to run at all (the Unsafe
  call would throw) until prism ships a classgraph that no longer uses it.
- **License: EUPL-1.2.** The tool links `midpoint-schema`, which is EUPL-1.2 (the standalone prism lib is
  Apache-2.0, but the midPoint schema definitions you need for object-aware diff are EUPL). A derived work
  must be EUPL-compatible — it cannot be MIT/Apache. Confirm the exact module license before publishing.

## How a promotion pipeline consumes it

That folder-loop is now the in-tool **`deploy` subcommand** (see "deploy — apply a folder tree" below, built through the apply, plan and policy increments), so it runs in one JVM with a schema-aware dependency sort instead of an external per-file loop that re-pays the prism bootstrap each time. Env-specific value templating stays a pipeline pre-step (render the tree, then `deploy` it). Packaged as a Kubernetes Job on the midPoint image, `deploy` is "item #2" of the midpoint-k8s delivery plan.

## Build order (recommended)

1. **Offline `diff` core** — engine + two-layer schema loading + `--scope`/`--multivalue`/`--equivalence`
   + `ObjectModificationType`/`summary` output + exit codes. Independently testable and useful.
2. **Live fetch** — `--url` GET of the current object, then diff.
3. **`apply`** — `PATCH`, and `POST` when the object is absent (`--no-create-if-absent` fails instead).

## Verification

1. *Unit (offline):* two hand-authored objects — a `RoleType` differing in one property; a `UserType`
   with an added assignment → assert the emitted `ObjectModificationType` (a `replace` for the property;
   an add-by-container-value for the assignment). Model on midPoint's own `TestParseDiffPatch` /
   `TestDiffEquals` fixtures.
2. *`--scope present`:* desired holds a subset of items → deltas only for present paths; absent items
   untouched.
3. *`--multivalue`:* `merge` on `assignment` preserves existing values and adds the new one; `replace` is
   authoritative.
4. *Extension schema:* an object using a custom `extension` attribute diffs correctly with
   `--schema-dir`/`--schema`, and falls back to raw handling without it (document the fallback).
5. *Live/apply (increment 2+3):* against a running midPoint, `apply` a one-field change to
   `administrator` → only `fullName` changes, assignments/metadata intact; re-run → no-op (idempotent).
   a fresh OID creates once, then no-ops.

## deploy — apply a folder tree

> **Status: built.** Read-only discovery + schema-aware dependency sort, the per-object apply loop (`--dry-run`, `--create-if-absent`, `--match-by-name`), `--retry`, the `--plan`/`--apply-plan` saved-change modes, and the `--policy` strategy file. See TODO.md section H. Now that `deploy` applies, the tool does more than "diff", so it was renamed **midgie** (a wee Scots midge); only the Java package `de.is4it.midpoint.diff` keeps the old name for now.

Apply a whole tree of desired-state object XML to a live server, in dependency order, reusing the `diff`/`apply` engine — **one JVM bootstrap** for the whole run. midPoint's prism bootstrap costs ~1.5 s, so a shell loop invoking the tool once per file would pay that per object; an in-process subcommand pays it once and can sort with prism's precise reference walk instead of a text heuristic. **Structure-agnostic**: objects are discovered by content, so the config repo can use any folder layout the team likes. The deploy model is **per object**: the files describing one object are combined and applied together (one `itemDelta` per object, however many files it took), and *schema extensions follow the same model* — an extension is a `SchemaType` object deployed like any other, so schema files are first-class nodes in the same dependency sort.

`midgie deploy <path...> [--url <url>] [creds/TLS/retry — the same mixins as diff/apply] [flags]` (paths are files or directories, walked recursively).

### Increment 1 (built): read-only discovery + schema-aware dependency sort

`deploy <path...>` discovers the tree, sorts it, and **prints the apply order**. It never writes: offline by default, and `--url` only *reads* the server's extension schema (`--no-live-schema` opts out). Exit `0` when discovered + sorted (warnings are still `0`), `2` on error. The order is **two tiers**:

1. **Schema tier.** Discover raw `.xsd` (local schema input) and exported `SchemaType` `.xml` (schema input *and* a deployable object). Read each schema's `targetNamespace` and `<xsd:import>` namespaces; a schema that imports another discovered schema's namespace sorts after it. Register all extension schema — CLI `--schema`/`--schema-dir`, live, and the tree — in **one batch** (prism's `registerDynamicSchemaExtensions` *replaces* the dynamic set, so it must be a single call), which is also what lets a type one extension defines resolve when another uses it. Raw `.xsd` is registered but not a deployable apply unit; only `SchemaType` objects are.
2. **Object tier.** Parse the remaining `.xml` as `PrismObject`s (extension attributes now resolve). Node identity = OID, else `(objectType, name)` — several files may be *fragments* of one object, and they are **combined into one desired object** which is the apply unit (`FragmentUnion`; see "Fragment union" below). Grouping runs a best-effort consistency check first: same type, and no single-valued item declared with differing real values across fragments — otherwise error. A multi-valued item declared by two fragments needs no check, since combining is exactly what happens to it. Edge A→B when B is in the discovered set and A carries an `ObjectReferenceType` OID for B (out-of-set refs are assumed pre-existing and ignored); references are extracted with a prism reference-visitor (every `PrismReferenceValue` with a non-null OID). Kahn topological sort with a deterministic tiebreak; **cycles** (mutual role inducements, org trees) are broken arbitrarily and warned, not fatal.

The plan prints one line per apply unit — that is, per object (`seq  type  oid-or-(type/name)  name  file`, with `(needs #…)` dependency positions, and the object's other files listed under it as `+ path`); `--debug` adds the edge lists, `--quiet` keeps only the order. New classes: `DeployCommand`, `Discovery`, `SchemaFile`, `Fragment`, `DeployObject`, `ReferenceCollector`, `TopoSort`; `SchemaOptions` split into `newBaseContext` + `collectSchemas` (collect without registering) so all schema sources merge into one registration.

### Increment 2 (built): the apply loop + `--dry-run`

3. **Apply in order.** With `--url`, reconcile each unit — one per **object** — in the sorted order: resolve the current object via a `POST {type}/search` (by OID, or by type+name when it has none, unless `--no-match-by-name` — a search so a missing object is an empty result, not a 404 that makes midPoint log an `ObjectNotFoundException`), diff the combined desired object (`--scope present`), then PATCH — or POST to create it, which is the default; under `--no-create-if-absent` a missing OID object fails and a missing OID-less object is skipped instead. One request per object, so a create is atomic (the whole object is POSTed, not a partial one patched into shape afterwards) and there is no window in which half of it is applied. Sequential, so each unit sees prior writes (a role is created before the user that references it). Writing is gated: a single up-front confirmation by default (`--yes` bypasses, `--confirm-each-apply` prompts per change, `--dry-run` previews without writing; a non-interactive session without `--yes` refuses). Per-unit result lines (created/patched/in-sync/skipped/failed) + a summary; `--quiet` keeps only errors and the exit code. Exit `0`/`2` on apply, `0` in-sync / `1` would-change / `2` on `--dry-run`. A transport/auth failure aborts; a per-object rejection is recorded and the run continues (best-effort). The `Reconciler` reuses `DiffEngine`, `ApplyCommand.createBody`, `LiveTarget`, and the new `MidpointRestClient.searchObjects`.

### Increment 3 (built): retry, saved plans, and the strategy policy

4. **Retry-until-stable (`--retry`).** After the apply pass, re-apply just the failed units round after round until there are no failures or a round makes no progress — catches ordering the static graph can't see (references by name, by search filter, or inside script expressions). Report anything still stuck and exit non-zero. Off by default (a single pass); retry re-applies already-confirmed changes, so it does not re-prompt.
5. **Saved plans (`--plan`/`--apply-plan`).** `--plan <dir>` reconciles each unit against the server as it stands and writes one file per change — one per object, since that is the unit (it used to keep a local projection advanced by each applied delta, purely so that a second fragment of a to-be-created object became a patch rather than a second create; combining the fragments first leaves that with nothing to answer for): `NNN-<type>-<oid>.create.xml` (full object) or `NNN-<type>-<oid>.patch.xml` (`ObjectModificationType`). It touches nothing on the server (exit `0`/`1`/`2` like `--dry-run`) and refuses a directory that already holds a plan. `deploy --apply-plan <dir> --url …` reads the files in filename order and replays them **verbatim** (POST creates, PATCH patches) under the same confirmation gating; plain `deploy` recomputes at apply time (safer against drift), `--apply-plan` executes exactly what was reviewed. (`PlanStore` writes/reads the filenames.)
6. **Strategy policy (`--policy <file>`).** One central file of `<selector> key=value` lines (`#` comments, blank lines ignored) keyed on the **object/item**, not the source file, so it survives the fragment model. The selector is a single glob over `Type/Name/item/subitem…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level; an object-depth selector sets `scope`/`equivalence`/`allow-inplace-merge`/a default `multivalue`, a deeper/`**` selector sets `multivalue` for that container (an object-only knob on an item selector is rejected). The most-specific selector wins per knob (more literal segments, then fewer `**`, then a longer literal prefix; ties → earlier line); the CLI flags are the baseline. Object names may contain spaces (the selector is everything before the first `key=value`). Each wildcard is a whole segment: a segment mixing `*` with other text is refused at load with the line number, since both ways of writing that — matching within a segment (`RoleType/App-*`) and two globs on one line (`RoleType/* **/assignment`, which the space-tolerant grammar swallows as one segment) — otherwise parse into a literal no name can equal, so the rule silently matches nothing and the objects it was written for keep the CLI strategy. `\*` escapes a literal asterisk in a name, as `\/` does a slash; `init --write-policy` emits both. Object XML stays pure. (`Policy` parses and resolves it against a `Fragment`'s identity; `GlobPath` does the glob match and the id-free multi-value `PathRule` matching; `Reconciler.plan` takes the strategy per call.)

   **`apply=on-create`** (item-level only; `always` is the default) marks an item that is written only in the POST that creates the object — `TaskType/*/executionState` is the motivating case, owned by operators and the task manager once the task exists. `ApplyRules.holdBack` runs in `Reconciler.planPatch` right after the diff: a delta at or beneath such a path is dropped (so `--scope full` cannot delete the item either), an *added* container value has the item stripped from a clone (a value left empty is not sent), and a *replace* of a container is refused for that object when the server holds the item beneath it, since the replace would overwrite it whatever the new value carries. Held-back paths ride on the `Outcome`; `--dry-run`/`--plan` print them, an object whose only differences were held back is `in-sync`, and the accrued-in-place refusal ignores paths that will not be sent. `--prune` skips removals under such paths (`DeployCommand.withoutOnCreate`), because pruning is a write too. An object matched by name counts as existing. **`apply=if-absent`** is midPoint's weak strength: `holdBack` keeps a delta at or beneath such an item when `live` holds no value at the delta's own path, and drops it when it does, so the item is filled in whenever missing and never overwritten or removed; `--prune` skips it for the same reason. Added container values keep their `if-absent` items (nothing can be under a container that is only now being added); a container replace is refused when the server holds such an item beneath it, as for `on-create`. `never` (exempt a path from `--scope full`) was considered and deferred until a use case appears. `init --write-policy` offers a fixed catalogue of candidates (`InitCommand.ON_CREATE_SUGGESTIONS`: task `executionState`/`schedulingState`, user `credentials` and `activation/administrativeStatus`, resource `administrativeOperationalState` and `schema`) for the types the tree holds, **commented out** so seeding a policy never changes what a deploy does.

**Reuse vs. new.** *Reused:* schema registration (`SchemaOptions`), the diff engine (`DiffEngine`), `ApplyCommand.createBody`/`decideConfirmation`, the REST client and the creds/TLS/retry/host option mixins. *New:* content-based discovery, the reference-visitor, the two-tier topological sort, the reconcile loop, `LiveLookup`/`searchObjects`, `PlanStore`, and the strategy policy (`Policy` + `GlobPath`).

**Verification.** Unit: schema import ordering, object schema→role→user ordering, a cycle, external refs ignored, discovery skips non-objects, same-identity fragments group and combine (keyed entries paired, keyless values appended and de-duplicated, contradictions error), the reconcile decision table, the REST search, policy selector matching / most-specific-wins / validation, plan-file round-trip, and stub-server retry convergence (`DeployCliTest`, `FragmentUnionTest`, `TopoSortTest`, `ReconcilerTest`, `MidpointRestClientTest`, `PolicyTest`, `PlanStoreTest`, `DeployRetryTest` over `src/test/resources/deploy/`). `DeployUnionTest` drives the CLI against a stub server to assert what the union is *for*: an object described by two files produces one PATCH carrying both, and one POST when it is created. Integration (`docker/` rig, `LiveIntegrationTest`): deploy a small tree with the dependency order and an **idempotent re-run** (second run all in-sync, exit 0), `--match-by-name` patching by name, the non-interactive refuse, the `--plan`→`--apply-plan` round-trip, and a `--policy` per-object strategy override.

**Consumer.** This is the promoter for the midpoint-k8s delivery pipeline: the chart's config-apply Job becomes `midgie deploy /config --url <route> --user … --password-file …`, run after `helm upgrade`.

## rollback — undo the last deploy

> **Status: built.** `DiffEngine` records old values, `deploy` writes a rollback plan on every run, and `midgie rollback` replays it. See TODO.md section K.

A deploy that writes leaves a **rollback plan** behind: for each object it touched, the delta that puts it back. The motivating failure is ordinary — a missing line in the `--policy` file leaves a multi-valued container on `replace`, the wholesale replace wipes accrued associations or part of the `SystemConfiguration`, and the run reports success.

**Why the inverse is nearly free.** prism's `fixupDelta` is overridden in `PrismPropertyImpl` *alone*, so multi-valued containers and single-valued references diff to add/delete pairs — and in the wipe case those deletes carry the accrued values themselves. `ObjectDelta.createReverseDelta()` swaps them back.

**The one gap, and why it had to be closed first.** A single-valued *property* collapses to a bare `replace`: `ItemImpl.diffInternal` builds the add/delete pair, then `fixupDelta` calls `delta.clear()` and rewrites it, discarding the old value without copying it into `estimatedOldValues`. `ItemDeltaImpl.createReverseDelta` reverses a replace by substituting those old values and, finding none, falls back to an **empty** `valuesToReplace` — prism's own comment calls this "not entirely correct" — so an undo would *clear* the item instead of restoring it. `DiffEngine` therefore fills `estimatedOldValues` from the live object it already holds. `ItemDeltaType` carries it as `estimatedOldValue`, `DeltaConvertor` serializes and parses it back unchanged, and midPoint treats it as informational (its own delta visualizer reads it; the repository write path does not). Protected values are never recorded.

**Not midPoint's object history.** `ModelAuditService.reconstructObject` does the same thing server-side — current state plus reverse deltas — but it is unreachable and unsuitable here: no audit mapping exists anywhere on the REST API (none in `ModelRestController`, `MidpointFunctions`, or the bulk-action evaluators), `AuditController.rollBackTime` throws outright on any ADD or DELETE delta in the trail, which is exactly what `--create-if-absent` produces, and the shipped `cleanupPolicy/auditRecords/maxAge` is `P3M`.

**Shape.** Entries are collected in apply order and written in **reverse** — the undo of a sequence is the reverse sequence of inverses. (An object assembled from several files contributes one entry, since it is applied once.) The files are ordinary `PlanStore` plan files. `manifest.xml` records the server, the `DeploymentIdentity`, the git revision, and each object's repository version after the write. Objects the run *created* are named there but never undone: the inverse of an add is an object delete, and one created an hour ago may already carry links and shadows. `--prune` removals are included, their inverse being exactly the values it removed.

**Refusals, not prompts.** `rollback` checks *provenance* (server and deployment must match, no override — a staging plan must not reach production because somebody answered yes) and *staleness* (an object whose version has moved was changed by someone else, so replaying would revert their change too; `--force` proceeds). The staleness check is read-then-compare, so it races a concurrent writer — acceptable for a config pipeline, and the honest alternative to silently reverting a colleague's edit.

**No `--auto-rollback`.** midPoint has no cross-object transaction, so unwinding a partly-applied tree can land in a third state that is neither old nor new; the failure that would trigger it is often the one that prevents it (auth, transport, server down); and it collides with `--retry`. The plan on disk is what makes a considered rollback cheap.

**Where nets live.** `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/` by default, newest five kept. That default is wrong for a Kubernetes Job, whose filesystem dies with the pod that needed it, which is why the base path is one of the keys a **tree-local `.midgie.properties`** may set (see "Config file"): the deployment is the only thing that knows whether it is a laptop, a runner, or a pod with a mounted volume. That layer is allowlisted rather than denylisted, since it travels with somebody else's repository; `url`/`user`/`password*`/`insecure`/`cacert` are absent from it, and so is `no-rollback-plan` — a file may say *where* something goes, never *whether* a safety gate applies, the same rule that keeps `yes` and `prune` off every config file.

**Plan files are never deploy input.** A `.create.xml` is a whole object with a valid root element, so a plan directory inside a deployed tree used to be discovered and redeployed. `PlanStore` now stamps every plan file with a generated-file marker that `Discovery` skips; the marker travels with the content, so it survives a rename, a move, a commit, or a CI artifact. Two path-based guards back it up: the `--plan`/`--rollback-plan` directories are excluded by canonical path, and the walk no longer descends into dot-directories.

New classes: `RollbackPlan` (collector, manifest, rotation), `RollbackCommand`. Changed: `DiffEngine.recordOldValues`, `Discovery`, `PlanStore`, `ConfigDefaults`, `MidpointRestClient.host()`, `Io.restrictDirToOwner`.
