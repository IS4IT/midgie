# midgie

[![CI](https://github.com/IS4IT/midgie/actions/workflows/ci.yml/badge.svg)](https://github.com/IS4IT/midgie/actions/workflows/ci.yml)
[![REUSE status](https://api.reuse.software/badge/github.com/IS4IT/midgie)](https://api.reuse.software/info/github.com/IS4IT/midgie)

A schema-aware diff/apply tool for [midPoint](https://evolveum.com/midpoint/). It computes the **minimal
`itemDelta`** between the desired and current state of a **single object** and emits the
`ObjectModificationType` XML that midPoint's REST `PATCH` endpoint consumes — a `kubectl diff`/`apply` for
midPoint config, one object at a time or a whole folder tree (`deploy`).

![midgie deploy over a small tree: first with --dry-run, then with --yes](docs/teaser.gif)

*A tracked tree deployed twice: `--dry-run` shows what would change — a new archetype, an edited role that
drops a value, a file deleted from the tree — and `--yes` applies it.*

> **It compares one object with itself, not two different objects.** Both inputs are the *same* object
> (same OID) in two states: the **desired** state (how you want it) and the **current** state (how it is
> now). The computed delta transforms current → desired — exactly like `git diff` between two revisions of
> one file, not a comparison of two unrelated files. The tool enforces this: if both inputs carry an OID
> and the OIDs differ, it refuses to run.

It is a thin wrapper around midPoint's own `PrismObject.diff`, so the diff is schema-correct: containers,
multi-valued items, PolyStrings, references, and metadata are compared exactly the way midPoint compares
them. All the diff logic runs **locally** — the live modes only add a REST fetch/patch around the same
engine.

`midgie` is an independent tool by [IS4IT](https://www.is4it.de/iam), not an Evolveum product — see [Project status and support](#project-status-and-support).

## Why

midPoint's existing apply paths (`post-initial-objects`, `ninja import`, REST `PUT`) all do a *full object
replace*, which wipes accumulated runtime state (assignments, links, metadata) on any object that isn't
pure config. `midgie` changes **only the fields you manage** and leaves the rest intact. The offline
two-file mode is also useful on its own for drift detection, migration deltas, and review.

## Install

Requires **JDK 21+**. A prebuilt fat JAR is attached to each [release](https://github.com/IS4IT/midgie/releases). To build
from source instead:

```bash
mvn -q package                       # builds target/midgie.jar (a fat JAR)
bin/midgie --help                    # launcher (recommended)
java -jar target/midgie.jar --help   # or run the JAR directly
```

Each release also publishes the **`is4it/midgie`** image on Docker Hub and attaches a Helm chart that runs it
as a workbench pod — see [Container image and Helm chart](docs/container.md). [Installing](docs/install.md)
covers the launcher, the version a build reports, and how to verify a release.

## Quick start

```bash
# Offline: desired vs a saved copy of its current state
bin/midgie diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: desired vs its current state on a running midPoint
bin/midgie diff desired.xml --url https://mp.example/midpoint --user administrator

# A whole tree: preview, apply, and put it back
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --dry-run
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --yes
bin/midgie rollback --url https://mp.example/midpoint --user administrator --dry-run
```

`midgie` has four subcommands:

- **`diff`** shows the change for a single object and never writes.
- **`deploy`** reconciles any number of files and folders — one file is a tree of one — and leaves a rollback
  plan behind.
- **`rollback`** puts back what the last `deploy` changed.
- **`init`** holds the one-off set-up steps, such as turning on removal detection for a server.

`diff` and `deploy --dry-run` exit `1` when something would change, so CI can gate on drift without parsing
output — see [Exit codes](docs/reference.md#exit-codes).

## Documentation

| | |
|---|---|
| [Installing](docs/install.md) | the JAR, the launcher, verifying a release |
| [Container image and Helm chart](docs/container.md) | running midgie in Docker, Kubernetes and OpenShift |
| [Commands](docs/commands.md) | `diff`, `deploy`, `rollback` and `init` in detail |
| [Detecting what you removed](docs/removal-detection.md) | the opt-in three-way comparison that removes what the tree dropped |
| [The policy file](docs/policy.md) | per-object and per-item settings, `apply=on-create`, the built-in rules |
| [Diff strategy](docs/strategy.md) | equivalence and multi-value handling, and the guard rails |
| [Configuration](docs/configuration.md) | `midgie.properties`, the tree-local file, environment variables |
| [Staging across environments](docs/staging.md) | one tree for dev, test and prod: midPoint constants, secrets, rendered trees |
| [Reference](docs/reference.md) | exit codes, every flag, connecting through a proxy |
| [Safety guards](docs/safety.md) | what midgie leaves out or refuses, and why |
| [Schema and midPoint versions](docs/schema.md) | base and extension schema, the midPoint versions a build serves |

[`DESIGN.md`](DESIGN.md) is the design brief behind all of it, and [`CONTRIBUTING.md`](CONTRIBUTING.md)
covers building and testing.

## Project status and support

**`midgie` is not an Evolveum product.** It is an independent open-source tool written and maintained by
[IS4IT GmbH](https://www.is4it.de/iam), an Evolveum partner. It is not developed, endorsed, certified or
supported by Evolveum s.r.o.

*midPoint* and *Evolveum* are trademarks of Evolveum s.r.o. This project uses the names only to say what it
works with, and claims no rights in them.

**Where to take what.**

| | Where |
|---|---|
| A bug, question or feature request about `midgie` | [this repository's issues](https://github.com/IS4IT/midgie/issues) |
| A security finding in `midgie` | privately, per [`SECURITY.md`](SECURITY.md) — not a public issue |
| A bug or question about midPoint itself | [Evolveum's tracker](https://support.evolveum.com) or the [midPoint mailing lists](https://lists.evolveum.com/) |
| Commercial midPoint support | [Evolveum](https://evolveum.com/services/support-subscriptions/) or [an Evolveum partner](https://evolveum.com/partners/find-a-partner/) |

Please do not raise `midgie` issues with Evolveum or in the midPoint development chat: neither Evolveum's
support nor a midPoint subscription covers this tool.

**Maturity.** `midgie` is `0.x` and still moving: the CLI surface
and output may change between minor versions, and the [`CHANGELOG`](CHANGELOG.md) says what changed. Only
the latest release is supported, with no backports. It holds midPoint administrator credentials and writes
to a live identity system — read [Safety guards](docs/safety.md), and preview with `--dry-run` or `--plan`
before you let it write anywhere you care about.

**Contributing** is welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2), so a derived work must remain
EUPL-compatible — it cannot be relicensed as MIT/Apache. Per-file `SPDX-License-Identifier` headers plus a
[`REUSE.toml`](REUSE.toml) make the project [REUSE](https://reuse.software/)-compliant.
