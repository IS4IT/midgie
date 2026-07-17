# midpoint-diff

A schema-aware diff tool for [midPoint](https://evolveum.com/midpoint/) objects. It computes the
**minimal `itemDelta`** between two objects and emits the `ObjectModificationType` XML that midPoint's
REST `PATCH` endpoint consumes — a `kubectl diff`/`apply` for midPoint config.

It is a thin wrapper around midPoint's own `PrismObject.diff`, so the diff is schema-correct (containers,
multi-valued items, metadata handled the way midPoint itself handles them).

> **Status: early scaffold.** The build and CLI skeleton are in place; the diff engine is being
> implemented. See [`DESIGN.md`](DESIGN.md) for the full design and [`CLAUDE.md`](CLAUDE.md) for build
> notes.

## Why

midPoint's existing apply paths (`post-initial-objects`, `ninja import`, REST `PUT`) all do a *full
object replace*, which wipes accumulated runtime state (assignments, links, metadata) on any object that
isn't pure config. `midpoint-diff` changes **only the fields you manage** and leaves the rest intact. The
offline two-file mode is also useful on its own for drift detection, migration deltas, and review.

## Modes

```bash
# Offline: diff two object files -> itemDelta on stdout
midpoint-diff diff desired.xml live.xml

# Live: diff a desired object against the one currently in a running midPoint
midpoint-diff diff desired.xml --host https://mp.example/midpoint --user administrator --password ****

# Apply: compute and PATCH the minimal change (create the object if absent)
midpoint-diff apply desired.xml --host https://mp.example/midpoint --user administrator --password **** --create-if-absent
```

See [`DESIGN.md`](DESIGN.md) for the strategy flags (`--scope`, `--multivalue`, `--equivalence`),
the two-layer schema handling (`--schema-dir` / `--schema`), and CI-friendly exit codes.

## Build

```bash
mvn -q package
java -jar target/midpoint-diff.jar --help
```

Requires JDK 17. Dependencies (midPoint schema + prism) resolve from Evolveum's Nexus, configured in
`pom.xml`.

## Versioning

The bundled `midpoint-schema` (base object model) is version-specific. Pin it to your target midPoint's
major line via `<midpoint.version>` in `pom.xml`.

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2); derived works must remain
EUPL-compatible.
