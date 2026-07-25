# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

`midgie` — a standalone, schema-aware CLI that computes the minimal `itemDelta` between two
midPoint objects and emits the `ObjectModificationType` XML that midPoint's REST `PATCH` consumes. It is
a thin wrapper around midPoint's own `PrismObject.diff`. **Read `DESIGN.md` first — it is the full,
self-contained brief** (modes, flags, two-layer schema handling, output/exit codes, verification).

Status: **offline core + live modes + extension schema working.** `DiffEngine` computes the reconciling
delta (`--scope`, `--multivalue`/`--path`, `--equivalence`); `diff` emits the `ObjectModificationType`
(offline LIVE file or `--host` GET); `apply` reconciles a live object (`PATCH`, `--create-if-absent`
`POST`, `--dry-run`, idempotent). Extension schema registers from `--schema-dir` (raw XSDs), `--schema`
(SchemaType XML), and — in live mode — automatically from the server (`SchemaOptions.buildContext`). All
HTTP lives in `MidpointRestClient`; the engine stays REST-free. Verified against the `docker/` rig.

## Commands

```bash
# Build the fat JAR (downloads midPoint schema + prism deps on first run — sizeable).
mvn -q package

# Run.
java -jar target/midgie.jar --help
java -jar target/midgie.jar diff examples/a.xml examples/b.xml

# Fast compile / test loop.
mvn -q compile
mvn -q test
```

## Things that will bite you

- **Base-schema version coupling.** The `midpoint-schema` jar carries the *compile-time* base object
  model, so it must match the target midPoint's major line. It is pinned by `<midpoint.version>` in
  `pom.xml` (default `4.10.3`). Targeting another midPoint version = bump that property **and** `MP_VER`
  in `docker/.env`, then rebuild. Two guards enforce this: the build fails (maven-antrun in the `validate`
  phase) if `docker/.env`'s `MP_VER` drifts from `<midpoint.version>`, and at runtime the live modes warn
  if the server's version (read via `GET /nodes`) is a different major line than the bundled schema
  (`VersionSupport`, using the filtered `build-info.properties`). You cannot feed the base schema in as
  XML; only *extension* schema is runtime-loadable (`--schema-dir` / `--schema` / live-read). See
  DESIGN.md "Schema".

- **License is EUPL-1.2, not MIT/Apache.** This links `midpoint-schema` (EUPL). A derived work must stay
  EUPL-compatible. Do not relicense.

- **Shade caveat.** prism/JAXB ship XSD catalogs and `ServiceLoader` files under `META-INF`. The
  `maven-shade-plugin` config includes `ServicesResourceTransformer`, but if the shaded jar fails at
  runtime (missing schema catalog, no prism implementation found), add the matching
  `AppendingTransformer` entries for the `META-INF/services/*` and any catalog resources. This is a known
  prism-packaging wrinkle, not a bug in your code.

- **prism is an external dependency.** `com.evolveum.midpoint.prism.*` (`PrismObject`, `PrismContext`,
  `ObjectDelta`, `EquivalenceStrategy`) is pulled transitively via `midpoint-schema`; it is not in the
  midPoint source tree. `ObjectModificationType` is in `…common.api_types_3` (not `types_3`).

## Verifying midPoint APIs

The midPoint source is checked out at **`/Volumes/Daten/git/midpoint`** (branch `support-4.10`). When in
doubt about a signature, grep there rather than guessing — DESIGN.md's API table lists the exact classes
and line numbers already confirmed.

## Conventions

- Java 17, clean readable code over clever code, explicit error handling.
- Keep the offline `diff` core free of any REST/HTTP dependency — that layering is the point (DESIGN.md).
- The source object XML must stay pure midPoint XML; behaviour is driven by CLI flags, never by custom
  annotations embedded in the object.
