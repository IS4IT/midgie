# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

`midgie` — a standalone, schema-aware CLI that reconciles midPoint configuration held as object XML. At its core it computes the minimal `itemDelta` between a desired and a current object and emits the `ObjectModificationType` XML that midPoint's REST `PATCH` consumes, wrapping midPoint's own `PrismObject.diff`; on top of that sit a live diff and a deploy over files and folders (one file is a tree of one). **Read `DESIGN.md` first — it is the full, self-contained brief** (modes, flags, two-layer schema handling, output/exit codes, verification).

User documentation lives in `docs/`, one page per topic; `README.md` is only the overview, quick start and documentation index. A change in behaviour updates the `docs/` page it belongs to, not the README. A new page goes into both indexes, the one in `README.md` and the one in `docs/README.md`.

## Commands

The first `mvn -q package` downloads the midPoint schema + prism dependencies — sizeable.

```bash
java -jar target/midgie.jar --help
java -jar target/midgie.jar diff examples/a.xml examples/b.xml
```

`mvn -B -ntp verify` is what the CI and release workflows run. The live integration tests self-skip unless the `docker/` rig is up, so a green run that reports skips is the normal case, not a problem.

Run `pre-commit install` once per clone: it runs gitleaks on every commit, as CI's `Secret scan` job does on every push over the whole history. `.gitleaks.toml` allowlists exactly one value, the `docker/` rig's throwaway admin password; anything else that looks like a secret fails both.

CI's `Licences & vulnerabilities` job reads `target/bom.json`: a bundled library whose licence is not in `.github/sbom-licences.txt` fails it (`python3 -I .github/scripts/check-licences.py target/bom.json .github/sbom-licences.txt` runs it locally), and so does a HIGH or CRITICAL CVE unless `.trivyignore` holds an unexpired exception. A midPoint bump is the usual trigger for both. The release workflow calls the whole CI workflow on the tagged commit before it builds anything, so a red check blocks a release too; never add an entry to either file just to get a release out.

## Things that will bite you

- **Base-schema version coupling.** The `midpoint-schema` jar carries the *compile-time* base object model, so it decides which servers midgie can read: its own line, and the line before it from `<midpoint.oldestSupported>` on. It is pinned by `<midpoint.version>` in `pom.xml` (default `4.11.0`, serving 4.10.3 and later as well). Targeting another midPoint line = bump that property **and** `MP_VER` in `docker/.env`, move `<midpoint.oldestSupported>` to the line before it (`VersionSupportTest` fails otherwise), then rebuild. Two guards enforce this: the build fails (maven-antrun in the `validate` phase) if `docker/.env`'s `MP_VER` drifts from `<midpoint.version>`, and at runtime the live modes warn if the server's version (read via `GET /nodes/current`) is outside the served range, or a newer patch release of the bundled line (`VersionSupport`, using the filtered `build-info.properties`). The live tests run against the bundled version's rig; docker/README.md says how to run them against the earlier line too. You cannot feed the base schema in as XML; only *extension* schema is runtime-loadable (`--schema-dir` / `--schema` / live-read). See DESIGN.md "Schema".

- **midgie's own version is the git tag, and the pom's is a placeholder.** `pom.xml`'s `<version>` stays `0.1.0-SNAPSHOT` and is never bumped — a release is cut by a `Release 0.12.0` commit that moves `CHANGELOG.md`'s `[Unreleased]` section and compare links to the new version and sets `version` and `appVersion` in `charts/midgie/Chart.yaml` to it, then tagging that commit (`git tag -a v0.12.0 -m "midgie 0.12.0"`) and pushing both; the tag is the single source of truth. (The release workflow packages the chart with the tag's version anyway; the `Chart.yaml` bump keeps a chart packaged from a checkout installing the same release.) The steps are also at the top of `.github/workflows/release.yml`. Every build stamps `git describe --tags --dirty`, minus the `v`, into `build-info.properties` as `midgie.version` (the `stamp-version-from-git` antrun execution), and that is what `--version` reports: `0.12.0` on a tag, `0.12.0-3-gabc1234-dirty` past one. The pom version is only the fallback for a build that cannot read a tag — a source archive, a shallow clone — and such a build logs Ant's `[ERROR] [exec] Result: 128` while still succeeding. The release workflow additionally applies the tag with `versions:set`, which is what makes that fallback correct there.

- **License is EUPL-1.2, not MIT/Apache.** This links `midpoint-schema` (EUPL). A derived work must stay EUPL-compatible. Do not relicense.

- **Shade caveat.** prism/JAXB ship XSD catalogs and `ServiceLoader` files under `META-INF`. The `maven-shade-plugin` config includes `ServicesResourceTransformer`, but if the shaded jar fails at runtime (missing schema catalog, no prism implementation found), add the matching `AppendingTransformer` entries for the `META-INF/services/*` and any catalog resources. This is a known prism-packaging wrinkle, not a bug in your code.

- **prism is an external dependency.** `com.evolveum.midpoint.prism.*` (`PrismObject`, `PrismContext`, `ObjectDelta`, `EquivalenceStrategy`) is pulled transitively via `midpoint-schema`; it is not in the midPoint source tree. `ObjectModificationType` is in `…common.api_types_3` (not `types_3`).

## Verifying midPoint APIs

The midPoint source is checked out at **`/Volumes/Daten/git/midpoint`**, at the tag matching `<midpoint.version>` (currently `v4.11.0`). When in doubt about a signature, grep there rather than guessing — DESIGN.md's API table lists the exact classes and line numbers already confirmed.

## Conventions

- Java 21, clean readable code over clever code, explicit error handling.
- Keep the offline `diff` core free of any REST/HTTP dependency — that layering is the point (DESIGN.md).
- The source object XML must stay pure midPoint XML. Behaviour comes from the CLI flags and the layers behind them (`midgie.properties`, environment variables, tree-local config), never from custom annotations embedded in the object.
