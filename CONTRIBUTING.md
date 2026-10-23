<!--
Copyright (c) 2026 IS4IT

SPDX-License-Identifier: EUPL-1.2
-->

# Contributing to midgie

Thanks for considering it. `midgie` is an independent tool maintained by
[IS4IT GmbH](https://www.is4it.de/); it is **not an Evolveum product** and Evolveum support does not cover
it. Please raise anything about `midgie` here rather than with Evolveum, on the midPoint mailing lists, or
in the midPoint development chat.

## What is useful

- **Bug reports.** The most useful ones name the `midgie` version (`midgie --version`, which also prints the
  bundled schema and the server range it serves), the midPoint version, the exact command, and a *minimal*
  object XML that reproduces it. Strip customer data before you paste anything — names, OIDs, hostnames,
  connector configuration.
- **A failing test.** A reproducer as a test case under `src/test/` is worth more than a paragraph, and it
  stops the bug coming back.
- **Documentation.** Corrections, a confusing passage, a case the README does not cover.
- **Code.** See the conventions below. For anything beyond a small fix, open an issue first so we can agree
  on the approach before you spend your evening on it.

**Security findings do not belong in an issue.** Follow [`SECURITY.md`](SECURITY.md) and report them
privately.

## Licensing of contributions

By opening a pull request you agree that your contribution is licensed under **EUPL-1.2**, the licence of
this project. There is no CLA to sign and copyright stays with you or your employer.

EUPL is not a preference: `midgie` links midPoint's `midpoint-schema` library, which is EUPL-1.2, so the
project cannot be relicensed as MIT or Apache. Any new file needs an `SPDX-License-Identifier: EUPL-1.2`
header (or an entry in [`REUSE.toml`](REUSE.toml) for files that cannot carry one) — CI runs a
[REUSE](https://reuse.software/) lint and will tell you if one is missing.

## Getting set up

Requires **JDK 21 or later**.

```bash
pre-commit install          # once per clone: runs gitleaks on every commit
mvn -B -ntp verify          # what CI runs
```

The first build downloads the midPoint schema and prism dependencies from Evolveum's Nexus and is
sizeable. The live integration tests self-skip unless the Docker Compose rig under `docker/` is up, so a
green run that reports skips is the normal case, not a problem — see [`docker/README.md`](docker/README.md)
to bring up a real midPoint, and [`docker/tls/README.md`](docker/tls/README.md) for the TLS rig.

CI runs the same `verify` on JDK 21 and 25, builds the container image for amd64 and arm64, lints the Helm
chart, runs CodeQL, and scans the **whole history** for secrets. A commit that puts a credential anywhere
near the repository will fail that scan, which is why the pre-commit hook exists.

## Conventions

Read [`DESIGN.md`](DESIGN.md) first — it is the self-contained brief for how the tool is put together, and
it explains the two decisions most likely to trip up a patch.

- **Java 21. Clean, readable code over clever code, and explicit error handling.** A message a person can
  act on beats a stack trace.
- **The offline `diff` core stays free of any REST or HTTP dependency.** That layering is the point: the
  live modes wrap the same engine with a fetch and a patch, and nothing about the network may leak
  downwards.
- **The object XML stays pure midPoint XML.** Behaviour comes from the CLI flags and the layers behind them
  — `midgie.properties`, environment variables, tree-local config, the policy file — never from custom
  annotations embedded in an object. A tree must stay deployable by other midPoint tooling.
- **Schema version coupling.** The base object model is compile-time, from the `midpoint-schema` jar, so it
  is pinned by `<midpoint.version>` in `pom.xml` with `<midpoint.oldestSupported>` naming the oldest server
  it serves. Changing `<midpoint.version>` means changing `MP_VER` in `docker/.env` to match (a build-time
  check fails if they drift apart) and moving `<midpoint.oldestSupported>` to the line before it
  (`VersionSupportTest` fails otherwise).
- **Do not bump the version in `pom.xml`.** It stays a placeholder: the git tag is the single source of
  truth, and every build stamps `git describe` into the JAR. Releases are cut by the maintainers.
- **Changelog.** Anything a user would notice goes under `## [Unreleased]` in
  [`CHANGELOG.md`](CHANGELOG.md), in the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
  categories. Write it for someone upgrading, not for someone reading the diff.

## Pull requests

- One concern per pull request. Two unrelated fixes are two pull requests.
- Add or adjust the tests that cover the change.
- Run `mvn -B -ntp verify` before you push.
- **Commit messages:** imperative mood, and they describe *the change itself* — not future plans, next
  steps, or the alternatives you rejected. `Name an object that left the tree by type and name`, not
  `fixes`.
- A review comment is a question, not a verdict. Push back if we have misread your patch.

## A note on scope

`midgie` deliberately does one thing: compute the minimal delta for a midPoint object and apply it. It is
not a configuration language, not a midPoint client library, and not a replacement for midPoint Studio or
`ninja`. Proposals that pull it towards any of those will probably be declined — kindly, and with reasons.
