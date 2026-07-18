# midpoint-diff — design

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
// The (File) constructor overload registers extra/extension XSDs from a directory (--schema-dir).
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
| `EquivalenceStrategy.REAL_VALUE` | ignores operational/metadata items **and container ids** — the default for config diffing (proven in `TestParseDiffPatch`). The `_CONSIDER_DIFFERENT_IDS[_NATURAL_KEYS]` variants turn container-id / natural-key matching **on**, which makes the diff *pair* multi-valued entries and edit them in place; id-agnostic plain `REAL_VALUE` instead emits clean whole-value add/delete. Merge defaults to plain `REAL_VALUE` for that reason (see "Strategy flags"); the id-considering variants are still selectable via `--equivalence` for exploration, with a warning |
| `DeltaConvertor.toObjectModificationType(ObjectDelta)` | `com.evolveum.midpoint.schema.DeltaConvertor` (infra/schema:237) → `com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectModificationType`. Also `toObjectDeltaType` (:259) and `toItemDeltaTypes` (:429) |
| `Item.diffModifications(other, strategy)` | returns `List<ItemDelta>` — useful for a **scoped** per-item diff (see `TestDiffEquals:565`) |

prism itself (`com.evolveum.midpoint.prism.*`: `PrismObject`, `PrismContext`, `ObjectDelta`,
`EquivalenceStrategy`) is an **external** library pulled transitively by `midpoint-schema`; it is not in
the midPoint source tree.

## Commands / modes (layered)

**v1 = the offline core.** Live and apply are thin adapters added in a second increment.

- `midpoint-diff diff <desired.xml> <live.xml>` → itemDelta XML on stdout. No REST, no server.
- `midpoint-diff diff <desired.xml> --host <url> --user <u> --password <p> [--oid <oid>]` → GET the
  current object (type + OID read from the desired file if not given), diff, emit itemDelta.
- `midpoint-diff apply <desired.xml> --host … [--create-if-absent]` → diff then
  `PATCH /ws/rest/{type}/{oid}`; on a GET-404 with `--create-if-absent`, `POST /ws/rest/{type}` the whole
  object (operational/metadata items stripped first). This is the reconciler. Because it writes, it shows
  the pending change and confirms first: it prompts interactively, or refuses in a non-interactive session
  unless `--yes`/`-y` is given. `--dry-run` prints the delta and changes nothing.

## Strategy flags

The source XML stays **pure midPoint XML** — no custom in-XML annotations, so the same file still works
with midPoint Studio, ninja, and REST `PUT`. Behaviour is controlled by flags:

- `--scope present|full` (default **present**) — *present*: emit deltas only for items that appear in the
  desired XML (managed = present; items absent from the desired object are left alone → preserves
  accumulated state). *full*: full-replace semantics (also removes items missing from desired).
- `--multivalue replace|merge` (default **replace**), plus per-path `--path <itemPath>=merge|replace` —
  *replace*: the desired container values are authoritative (live-only values are removed). *merge*: union
  by content — merge is designed for and defaults to `REAL_VALUE` (values matched by real content, ids
  ignored), keeps the additions, and drops the deletion of live-only values so accrued runtime state
  survives. Caveats: an in-place edit of a managed value reads as "old value kept + new value added" (keep
  that path on *replace* if you want a true in-place update); a single-valued item, or any item that diffs
  to a whole-value replace, cannot merge and is left as replace (noted on stderr).
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

  `merge` combined with any non-`real-value` strategy is allowed (for exploration) but **warns** on stderr:
  the id-considering strategies pair values and edit them in place, so accrued values may be mutated
  instead of preserved. Use `real-value` for a clean, id-agnostic union.

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
  - *Live mode:* read the `SchemaType` objects off the server (`--host/--user/--password`) and register
    them the same way (best-effort — a read failure warns and falls back to base schema).
  - Note: `--host` supplies *extensions* only. The base jar is always needed locally, because the tool
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
- **License: EUPL-1.2.** The tool links `midpoint-schema`, which is EUPL-1.2 (the standalone prism lib is
  Apache-2.0, but the midPoint schema definitions you need for object-aware diff are EUPL). A derived work
  must be EUPL-compatible — it cannot be MIT/Apache. Confirm the exact module license before publishing.

## How a promotion pipeline consumes it

The promoter is a thin folder-loop: walk `config/**` in order (schema → objects → seed → modifications,
base then env), env-template each file, invoke the CLI per object (`apply --host … --create-if-absent`).
Ordering + templating live in the loop; delta computation lives in the CLI. Packaged as a Kubernetes Job
on the midPoint image, that loop is "item #2" of the midpoint-k8s delivery plan.

## Build order (recommended)

1. **Offline `diff` core** — engine + two-layer schema loading + `--scope`/`--multivalue`/`--equivalence`
   + `ObjectModificationType`/`summary` output + exit codes. Independently testable and useful.
2. **Live fetch** — `--host` GET of the current object, then diff.
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
