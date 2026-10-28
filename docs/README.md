# midgie documentation

One page per topic. The [project README](../README.md) has the overview and a quick start.

| | |
|---|---|
| [Installing](install.md) | the JAR, the launcher, verifying a release |
| [Container image and Helm chart](container.md) | running midgie in Docker, Kubernetes and OpenShift |
| [Commands](commands.md) | `diff`, `deploy`, `rollback` and `init` in detail |
| [Detecting what you removed](removal-detection.md) | the opt-in three-way comparison that removes what the tree dropped |
| [The policy file](policy.md) | per-object and per-item settings, `apply=on-create`, the built-in rules |
| [Diff strategy](strategy.md) | equivalence and multi-value handling, and the guard rails |
| [Configuration](configuration.md) | `midgie.properties`, the tree-local file, environment variables |
| [Staging across environments](staging.md) | one tree for dev, test and prod: midPoint constants, secrets, rendered trees |
| [Reference](reference.md) | exit codes, every flag, connecting through a proxy |
| [Safety guards](safety.md) | what midgie leaves out or refuses, and why |
| [Schema and midPoint versions](schema.md) | base and extension schema, the midPoint versions a build serves |

[`DESIGN.md`](../DESIGN.md) is the design brief behind all of it, and [`CONTRIBUTING.md`](../CONTRIBUTING.md)
covers building and testing.
