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
- *Delete detection (three-way merge)* — a two-way diff cannot express a removal: a value dropped from a
  desired file simply stops being mentioned, so under `merge` the delta is empty and the value survives while
  `diff` reports no differences. `deploy` therefore also compares desired against **last-applied**, which for
  a version-controlled tree is `git show <lastRev>:<file>` — so the only persisted state is one revision per
  tree, the analogue of ArgoCD's `status.sync.revision`. The removal set is the *delete side* of an ordinary
  `DiffEngine` run with last-applied in the live slot, so no new diff logic exists. It works per **fragment
  file**, which is what makes it correct for objects assembled from several files: a deleted fragment yields
  exactly the values it contributed (the object is never a deletion candidate — `SystemConfiguration` is
  `indestructible` anyway), minus anything a surviving sibling still declares — checked per removal against
  the surviving fragments themselves, at the removal's own depth, since a survivor set enumerated by diffing
  a fragment against an empty object yields whole-container paths that no nested removal could ever match.
  Reported by default; deleting is opt-in. State lives in a `midgie-state` `LookupTableType` created solely
  by `midgie init` — never implicitly, so midgie leaves no unexplained object behind.

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
- `midgie apply <desired.xml> --url … [--create-if-absent]` → diff then
  `PATCH /ws/rest/{type}/{oid}`; when the search finds nothing and `--create-if-absent` is set, `POST /ws/rest/{type}` the whole
  object (operational/metadata items stripped first). This is the reconciler. Because it writes, it shows
  the pending change and confirms first: it prompts interactively, or refuses in a non-interactive session
  unless `--yes`/`-y` is given. `--dry-run` prints the delta and changes nothing.
- `midgie deploy <tree> [--url …]` → discover a folder tree, sort it by dependency, and print the apply
  order or reconcile it object by object. Its own section below.
- `midgie init --url …` → create the `midgie-state` object that delete detection needs, and optionally seed
  a starter `--policy` file for a tree (`--write-policy`). Never implicit: `deploy` reports that tracking is
  off rather than creating anything.

Connection details default from `MIDPOINT_URL` / `MIDPOINT_USER` / `MIDPOINT_PASSWORD`. To keep the
password off the command line (it leaks into `ps` and shell history), resolve it — in order — from
`--password`, `--password-env <VAR>`, `--password-file <FILE>`, `MIDPOINT_PASSWORD`, or an interactive
prompt. REST failures carry an actionable hint (401 auth, 403 authz, connection refused). `--timeout`
sets the per-request timeout; `--retries <n>` retries idempotent reads (including the `POST {type}/search` every object lookup uses) on 502/503/504 / connection-refused
/ timeout with exponential backoff (rides out a server that is still starting). For https, `--cacert <FILE>`
verifies the server against a CA certificate; `--insecure` trusts any cert and skips hostname verification.
The `docker/tls/` rig (an nginx TLS proxy in front of midPoint + a client container) exercises these
end-to-end.

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

  > **Why `replace` + `real-value` stay the defaults** (considered and rejected: defaulting to `merge`, with
  > `natural-keys` where the schema declares one, to reduce the risk of accidental deletion). `merge` can
  > express neither an edit nor a removal: a content change reads as "old gone, new added" and merge drops
  > the deletion, so values accumulate (`[alpha]` + desired `[beta]` → `[alpha, beta]`); and dropping an
  > entry from the desired file produces an *empty* delta, so `diff` reports "No differences" and exits `0`
  > while the live object still holds it — which silently defeats drift detection and the CI exit-code
  > contract, the very thing that would tell you there is cruft to clean up. `natural-keys` is not a default
  > either: it is prism-`@Experimental`, and the many types that declare a natural key real config never
  > fills in (`assignment`/`inducement`/`authorization` declare `identifier`) make prism pair unrelated
  > values, which the accrued-value guard then correctly refuses — an ordinary assignment change would fail
  > out of the box. A *schema-dependent* equivalence default is not expressible anyway: equivalence is a
  > single input to prism's whole-object diff (`reconcile` → `live.diff(desired, strategy)`), unlike
  > `multivalue`, which midgie varies per path by post-processing the delta. The risk that motivated the
  > question — a partially declared multi-valued container deleting what it does not list — is instead
  > handled by the input warnings (see "Safety guards"), which name the container, the counts, and the fix.
- `--equivalence real-value|consider-different-ids|natural-keys|ignore-metadata|data|literal`
  (default **real-value**), loosest to strictest — the value-equivalence test that decides what counts as
  drift and how entries are matched:
  - *real-value* — ignores container ids, metadata, operational items, element names, ns prefixes, ref
    filters/options. The config-diff default (no churn from midPoint's own bookkeeping).
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
3. **`apply`** — `PATCH`, plus `--create-if-absent` → `POST`.

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
   `--create-if-absent` on a fresh OID creates once, then no-ops.

## deploy — apply a folder tree

> **Status: built.** Read-only discovery + schema-aware dependency sort, the file-by-file apply loop (`--dry-run`, `--create-if-absent`, `--match-by-name`), `--retry`, the `--plan`/`--apply-plan` saved-change modes, and the `--policy` strategy file. See TODO.md section H. Now that `deploy` applies, the tool does more than "diff", so it was renamed **midgie** (a wee Scots midge); only the Java package `de.is4it.midpoint.diff` keeps the old name for now.

Apply a whole tree of desired-state object XML to a live server, in dependency order, reusing the `diff`/`apply` engine — **one JVM bootstrap** for the whole run. midPoint's prism bootstrap costs ~1.5 s, so a shell loop invoking the tool once per file would pay that per object; an in-process subcommand pays it once and can sort with prism's precise reference walk instead of a text heuristic. **Structure-agnostic**: objects are discovered by content, so the config repo can use any folder layout the team likes. The deploy model is **file-by-file**: each source file is diffed and applied individually (one `itemDelta` per file), and *schema extensions follow the same model* — an extension is a `SchemaType` object deployed like any other, so schema files are first-class nodes in the same dependency sort.

`midgie deploy <path...> [--url <url>] [creds/TLS/retry — the same mixins as diff/apply] [flags]` (paths are files or directories, walked recursively).

### Increment 1 (built): read-only discovery + schema-aware dependency sort

`deploy <path...>` discovers the tree, sorts it, and **prints the apply order**. It never writes: offline by default, and `--url` only *reads* the server's extension schema (`--no-live-schema` opts out). Exit `0` when discovered + sorted (warnings are still `0`), `2` on error. The order is **two tiers**:

1. **Schema tier.** Discover raw `.xsd` (local schema input) and exported `SchemaType` `.xml` (schema input *and* a deployable object). Read each schema's `targetNamespace` and `<xsd:import>` namespaces; a schema that imports another discovered schema's namespace sorts after it. Register all extension schema — CLI `--schema`/`--schema-dir`, live, and the tree — in **one batch** (prism's `registerDynamicSchemaExtensions` *replaces* the dynamic set, so it must be a single call), which is also what lets a type one extension defines resolve when another uses it. Raw `.xsd` is registered but not a deployable apply unit; only `SchemaType` objects are.
2. **Object tier.** Parse the remaining `.xml` as `PrismObject`s (extension attributes now resolve). Node identity = OID, else `(objectType, name)` — several files may be *fragments* of one object, each an individual apply unit. Grouping runs a best-effort consistency check: same type, and no single-valued item declared with differing real values across fragments — otherwise error. A multi-valued item declared by two fragments is not an error but is **warned** about: file-by-file apply doesn't merge them, so under the default (replace) a later PATCH overwrites the earlier one (last-one-wins) — combine the files or set a merge policy. Edge A→B when B is in the discovered set and A carries an `ObjectReferenceType` OID for B (out-of-set refs are assumed pre-existing and ignored); references are extracted with a prism reference-visitor (every `PrismReferenceValue` with a non-null OID). Kahn topological sort with a deterministic tiebreak; **cycles** (mutual role inducements, org trees) are broken arbitrarily and warned, not fatal.

The plan prints one line per apply unit (`seq  type  oid-or-(type/name)  name  file`, with `(needs #…)` dependency positions and a `# same object as #n` marker for extra fragments); `--debug` adds the edge lists, `--quiet` keeps only the order. New classes: `DeployCommand`, `Discovery`, `SchemaFile`, `Fragment`, `DeployObject`, `ReferenceCollector`, `TopoSort`; `SchemaOptions` split into `newBaseContext` + `collectSchemas` (collect without registering) so all schema sources merge into one registration.

### Increment 2 (built): the apply loop + `--dry-run`

3. **Apply in order.** With `--url`, reconcile each unit file-by-file in the sorted order: resolve the current object via a `POST {type}/search` (by OID, or by type+name when `--match-by-name` is set — a search so a missing object is an empty result, not a 404 that makes midPoint log an `ObjectNotFoundException`), diff the fragment (`--scope present`), then PATCH — or POST to create with `--create-if-absent`, else a missing OID object fails and a missing OID-less object is skipped. Sequential, so each unit sees prior writes (a role is created before the user that references it; a second fragment patches the object the first created). Writing is gated: a single up-front confirmation by default (`--yes` bypasses, `--confirm-each-apply` prompts per change, `--dry-run` previews without writing; a non-interactive session without `--yes` refuses). Per-unit result lines (created/patched/in-sync/skipped/failed) + a summary; `--quiet` keeps only errors and the exit code. Exit `0`/`2` on apply, `0` in-sync / `1` would-change / `2` on `--dry-run`. A transport/auth failure aborts; a per-object rejection is recorded and the run continues (best-effort). The `Reconciler` reuses `DiffEngine`, `ApplyCommand.createBody`, `LiveTarget`, and the new `MidpointRestClient.searchObjects`.

### Increment 3 (built): retry, saved plans, and the strategy policy

4. **Retry-until-stable (`--retry`).** After the apply pass, re-apply just the failed units round after round until there are no failures or a round makes no progress — catches ordering the static graph can't see (references by name, by search filter, or inside script expressions). Report anything still stuck and exit non-zero. Off by default (a single pass); retry re-applies already-confirmed changes, so it does not re-prompt.
5. **Saved plans (`--plan`/`--apply-plan`).** `--plan <dir>` reconciles each unit against a **local projection** (seeded from the live server and advanced via `ObjectDelta.applyTo` as each create/patch is applied to a local clone, so a second fragment of a to-be-created object becomes a patch, not a second create) and writes one file per change: `NNN-<type>-<oid>.create.xml` (full object) or `NNN-<type>-<oid>.patch.xml` (`ObjectModificationType`). It touches nothing on the server (exit `0`/`1`/`2` like `--dry-run`) and refuses a directory that already holds a plan. `deploy --apply-plan <dir> --url …` reads the files in filename order and replays them **verbatim** (POST creates, PATCH patches) under the same confirmation gating; plain `deploy` recomputes at apply time (safer against drift), `--apply-plan` executes exactly what was reviewed. (`PlanStore` writes/reads the filenames.)
6. **Strategy policy (`--policy <file>`).** One central file of `<selector> key=value` lines (`#` comments, blank lines ignored) keyed on the **object/item**, not the source file, so it survives the fragment model. The selector is a single glob over `Type/Name/item/subitem…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level; an object-depth selector sets `scope`/`equivalence`/`allow-inplace-merge`/a default `multivalue`, a deeper/`**` selector sets `multivalue` for that container (an object-only knob on an item selector is rejected). The most-specific selector wins per knob (more literal segments, then fewer `**`, then a longer literal prefix; ties → earlier line); the CLI flags are the baseline. Object names may contain spaces (the selector is everything before the first `key=value`). Object XML stays pure. (`Policy` parses and resolves it against a `Fragment`'s identity; `GlobPath` does the glob match and the id-free multi-value `PathRule` matching; `Reconciler.plan` takes the strategy per call.)

**Reuse vs. new.** *Reused:* schema registration (`SchemaOptions`), the diff engine (`DiffEngine`), `ApplyCommand.createBody`/`decideConfirmation`, the REST client and the creds/TLS/retry/host option mixins. *New:* content-based discovery, the reference-visitor, the two-tier topological sort, the reconcile loop, `LiveLookup`/`searchObjects`, `PlanStore`, and the strategy policy (`Policy` + `GlobPath`).

**Verification.** Unit: schema import ordering, object schema→role→user ordering, a cycle, external refs ignored, discovery skips non-objects, same-identity fragments group, contradictions error, the reconcile decision table, the REST search, policy selector matching / most-specific-wins / validation, plan-file round-trip, and stub-server retry convergence (`DeployCliTest`, `TopoSortTest`, `ReconcilerTest`, `MidpointRestClientTest`, `PolicyTest`, `PlanStoreTest`, `DeployRetryTest` over `src/test/resources/deploy/`). Integration (`docker/` rig, `LiveIntegrationTest`): deploy a small tree with the dependency order and an **idempotent re-run** (second run all in-sync, exit 0), `--match-by-name` patching by name, the non-interactive refuse, the `--plan`→`--apply-plan` round-trip, and a `--policy` per-object strategy override.

**Consumer.** This is the promoter for the midpoint-k8s delivery pipeline: the chart's config-apply Job becomes `midgie deploy /config --url <route> --user … --password-file …`, run after `helm upgrade`.
