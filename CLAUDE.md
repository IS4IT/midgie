# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

`midgie` — a standalone, schema-aware CLI that reconciles midPoint configuration held as object XML. At its core it computes the minimal `itemDelta` between a desired and a current object and emits the `ObjectModificationType` XML that midPoint's REST `PATCH` consumes, wrapping midPoint's own `PrismObject.diff`; on top of that sit a live diff and a deploy over files and folders (one file is a tree of one). **Read `DESIGN.md` first — it is the full, self-contained brief** (modes, flags, two-layer schema handling, output/exit codes, verification).

## Commands

The first `mvn -q package` downloads the midPoint schema + prism dependencies — sizeable.

```bash
java -jar target/midgie.jar --help
java -jar target/midgie.jar diff examples/a.xml examples/b.xml
```

`mvn -B -ntp verify` is what both workflows run. The live integration tests self-skip unless the `docker/` rig is up, so a green run that reports skips is the normal case, not a problem.

## Things that will bite you

- **Base-schema version coupling.** The `midpoint-schema` jar carries the *compile-time* base object model, so it must match the target midPoint's major line. It is pinned by `<midpoint.version>` in `pom.xml` (default `4.10.5`). Targeting another midPoint version = bump that property **and** `MP_VER` in `docker/.env`, then rebuild. Two guards enforce this: the build fails (maven-antrun in the `validate` phase) if `docker/.env`'s `MP_VER` drifts from `<midpoint.version>`, and at runtime the live modes warn if the server's version (read via `GET /nodes/current`) is a different major line than the bundled schema, or a newer patch release of the same line (`VersionSupport`, using the filtered `build-info.properties`). You cannot feed the base schema in as XML; only *extension* schema is runtime-loadable (`--schema-dir` / `--schema` / live-read). See DESIGN.md "Schema".

- **midgie's own version is the git tag, and the pom's is a placeholder.** `pom.xml`'s `<version>` stays `0.1.0-SNAPSHOT` and is never bumped — a release is cut by a `Release 0.12.0` commit that moves `CHANGELOG.md`'s `[Unreleased]` section and compare links to the new version and sets `version` and `appVersion` in `charts/midgie/Chart.yaml` to it, then tagging that commit (`git tag -a v0.12.0 -m "midgie 0.12.0"`) and pushing both; the tag is the single source of truth. (The release workflow packages the chart with the tag's version anyway; the `Chart.yaml` bump keeps a chart packaged from a checkout installing the same release.) The steps are also at the top of `.github/workflows/release.yml`. Every build stamps `git describe --tags --dirty`, minus the `v`, into `build-info.properties` as `midgie.version` (the `stamp-version-from-git` antrun execution), and that is what `--version` reports: `0.12.0` on a tag, `0.12.0-3-gabc1234-dirty` past one. The pom version is only the fallback for a build that cannot read a tag — a source archive, a shallow clone — and such a build logs Ant's `[ERROR] [exec] Result: 128` while still succeeding. The release workflow additionally applies the tag with `versions:set`, which is what makes that fallback correct there.

- **License is EUPL-1.2, not MIT/Apache.** This links `midpoint-schema` (EUPL). A derived work must stay EUPL-compatible. Do not relicense.

- **Shade caveat.** prism/JAXB ship XSD catalogs and `ServiceLoader` files under `META-INF`. The `maven-shade-plugin` config includes `ServicesResourceTransformer`, but if the shaded jar fails at runtime (missing schema catalog, no prism implementation found), add the matching `AppendingTransformer` entries for the `META-INF/services/*` and any catalog resources. This is a known prism-packaging wrinkle, not a bug in your code.

- **prism is an external dependency.** `com.evolveum.midpoint.prism.*` (`PrismObject`, `PrismContext`, `ObjectDelta`, `EquivalenceStrategy`) is pulled transitively via `midpoint-schema`; it is not in the midPoint source tree. `ObjectModificationType` is in `…common.api_types_3` (not `types_3`).

## Verifying midPoint APIs

The midPoint source is checked out at **`/Volumes/Daten/git/midpoint`**, at the tag matching `<midpoint.version>` (currently `v4.10.5`). When in doubt about a signature, grep there rather than guessing — DESIGN.md's API table lists the exact classes and line numbers already confirmed.

## Conventions

- Java 21, clean readable code over clever code, explicit error handling.
- Keep the offline `diff` core free of any REST/HTTP dependency — that layering is the point (DESIGN.md).
- The source object XML must stay pure midPoint XML. Behaviour comes from the CLI flags and the layers behind them (`midgie.properties`, environment variables, tree-local config), never from custom annotations embedded in the object.
