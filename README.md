# midgie

[![CI](https://github.com/IS4IT/midgie/actions/workflows/ci.yml/badge.svg)](https://github.com/IS4IT/midgie/actions/workflows/ci.yml)
[![REUSE status](https://api.reuse.software/badge/github.com/IS4IT/midgie)](https://api.reuse.software/info/github.com/IS4IT/midgie)

A schema-aware diff/apply tool for [midPoint](https://evolveum.com/midpoint/). It computes the **minimal
`itemDelta`** between the desired and current state of a **single object** and emits the
`ObjectModificationType` XML that midPoint's REST `PATCH` endpoint consumes — a `kubectl diff`/`apply` for
midPoint config, one object at a time or a whole folder tree (`deploy`).

> **It compares one object with itself, not two different objects.** Both inputs are the *same* object
> (same OID) in two states: the **desired** state (how you want it) and the **current** state (how it is
> now). The computed delta transforms current → desired — exactly like `git diff` between two revisions of
> one file, not a comparison of two unrelated files. The tool enforces this: if both inputs carry an OID
> and the OIDs differ, it refuses to run.

It is a thin wrapper around midPoint's own `PrismObject.diff`, so the diff is schema-correct: containers,
multi-valued items, PolyStrings, references, and metadata are compared exactly the way midPoint compares
them. All the diff logic runs **locally** — the live modes only add a REST fetch/patch around the same
engine.

## Why

midPoint's existing apply paths (`post-initial-objects`, `ninja import`, REST `PUT`) all do a *full object
replace*, which wipes accumulated runtime state (assignments, links, metadata) on any object that isn't
pure config. `midgie` changes **only the fields you manage** and leaves the rest intact. The offline
two-file mode is also useful on its own for drift detection, migration deltas, and review.

## Install

A prebuilt fat JAR is attached to each [release](https://github.com/IS4IT/midgie/releases). To build
from source instead:

```bash
mvn -q package                       # builds target/midgie.jar (a fat JAR)
bin/midgie --help             # launcher (recommended)
java -jar target/midgie.jar --help   # or run the JAR directly
```

Requires **JDK 17+**. Dependencies (midPoint schema + prism) resolve from Evolveum's Nexus, configured in
`pom.xml`. The first build downloads the midPoint schema and prism dependencies, so it is sizeable.

The `bin/midgie` launcher finds the JAR (override with `MIDGIE_JAR`) and, on **JDK 23+**,
adds `--sun-misc-unsafe-memory-access=allow` to silence the JEP 498 `sun.misc.Unsafe` warnings that
classgraph (a transitive prism dependency) prints during startup. Those warnings go straight to the native
stderr, so that launch flag is the only way to suppress them — running the JAR directly instead? Set
`JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

## Commands

`midgie` has three subcommands. **`diff`** and **`apply`** work on a **single object**; **`deploy`** reconciles a **folder tree** of them. All read desired-state XML and run the same local diff engine — the current state comes from a local file (offline) or a running server (`--host`).

> Each object is compared with **itself**, not another object: its *desired* state (how you want it) against its *current* state (how it is now), and the delta transforms current → desired. If both sides carry an OID and the OIDs differ, the run is refused.

In live mode all three address the object by OID (its `oid`, or `--oid`); if it has none, **`--match-by-name`** resolves it on the server by type + name (a single match) — opt-in everywhere, since resolving a write target by name is deliberately explicit.

### `diff` — the minimal delta

Emits the `ObjectModificationType`. The current state is a local `CURRENT` file (offline) or, with `--host`, a `GET` over REST (fetched by the desired object's `oid`, or `--oid`). Pass `-` for either path to read from stdin.

```bash
# Offline: desired vs a saved copy of its current state
bin/midgie diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: desired vs its current state on a running midPoint
bin/midgie diff desired.xml --host https://mp.example/midpoint --user administrator
```

An explicit `CURRENT` file means offline; `--host` (or `MIDPOINT_URL`) means live — the two together are an error, and a host inherited only from `MIDPOINT_URL` is a fallback that a `CURRENT` file overrides (so an env var never breaks a file diff).

### `apply` — reconcile one live object

Fetches the object's current state, computes the delta, shows what would change, and — after confirmation — `PATCH`es it. Idempotent (nothing is sent when already in sync); `--create-if-absent` `POST`s the object if it does not exist yet (operational/metadata items stripped first); `--dry-run` reports without writing (exit `1` if it would change anything); `--yes`/`-y` skips the prompt (required in non-interactive use).

```bash
bin/midgie apply desired.xml --host https://mp.example/midpoint --user administrator --create-if-absent
```

### `deploy` — reconcile a folder tree

Walks a folder tree of desired-state object XML, sorts it by dependency, and either prints the apply order (offline) or reconciles it against a live server (`--host`).

```bash
bin/midgie deploy config/          # offline: print the dependency-ordered apply plan
bin/midgie deploy config/ --host https://mp.example/midpoint --user administrator --dry-run   # preview the changes
bin/midgie deploy config/ --host https://mp.example/midpoint --user administrator --create-if-absent --yes   # apply
bin/midgie deploy config/ --host … --create-if-absent --plan out/   # write the changes to out/ for review
bin/midgie deploy --apply-plan out/ --host … --yes                  # replay a reviewed plan
```

The order is two-tier: extension **schemas** first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then **objects** (sorted by their reference OIDs). Objects are identified by OID, or by type+name when they carry no OID, so several files may declare *fragments* of one object — each applied individually. A genuine contradiction between fragments (a different type under one OID, or a single-valued item set to two values) is an error; same-object non-overlapping fragments are fine.

With `--host`, each unit is reconciled file-by-file in that order — look up the current object by a search (so a missing object doesn't 404), diff the fragment (`--scope present`), then `PATCH`, or `POST` to create with `--create-if-absent` (an OID-less object is reconciled by type+name with `--match-by-name`). It is sequential, so a role is created before the user that references it, and re-running is idempotent. Writing is gated: a single confirmation prompt by default (`--yes` to bypass, `--confirm-each-apply` to prompt per change, `--dry-run` to preview without writing; a non-interactive session without `--yes` refuses). Exit codes: offline `0`/`2`; apply `0` (all created/patched/in-sync/skipped or declined) / `2` (any unit failed); `--dry-run` `0` in-sync / `1` would change something / `2` error.

Three more flags round it out: **`--retry`** re-applies the failed units until they stabilise (catches references the static OID graph can't order — by name, filter, or expression); **`--plan <dir>`** writes the ordered changes as numbered `create`/`patch` files for review or GitOps, and **`--apply-plan <dir>`** replays them verbatim; **`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — a single glob over `Type/Name/item…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level, most-specific selector wins — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container. The object XML stays pure. Full design in [`DESIGN.md`](DESIGN.md#deploy--apply-a-folder-tree).

## Exit codes

The contract lets CI gate on drift without parsing output:

| command | `0` | `1` | `2` |
|---|---|---|---|
| `diff` | no differences | differences found (drift) | error |
| `apply --dry-run` | already in sync | would create/patch | error |
| `apply` (real write) | applied, created, in sync, or declined | — | error |
| `deploy --dry-run` / `--plan` | nothing to change | would change something | error |
| `deploy` (real apply) | all applied/in-sync/skipped/declined | — | a unit failed, or error |

## Flags

### Diff strategy

| flag | default | meaning |
|---|---|---|
| `--scope present\|full` | `present` | *present*: only diff items present in `DESIRED` (unmanaged items are left alone → accrued state is preserved). *full*: full-replace (also removes items missing from desired). |
| `--multivalue replace\|merge` | `replace` | Multi-valued container handling. *replace*: desired values are authoritative (live-only values removed). *merge*: union by content — add desired values, keep live-only ones. |
| `--path <itemPath>=merge\|replace` | — | Per-path override of `--multivalue`. Repeatable (e.g. `--path assignment=merge`). |
| `--equivalence <strategy>` | `real-value` | What counts as "the same value", loosest to strictest: `real-value` · `consider-different-ids` · `natural-keys` (prism-experimental) · `ignore-metadata` · `data` · `literal`. See [`DESIGN.md`](DESIGN.md#strategy-flags). |
| `--allow-inplace-merge` | off | Proceed even when a merge would edit an **accrued (live-only)** value in place (can happen under `natural-keys`). Off by default: such a run is refused so accrued state is not silently overwritten. |

`merge` is designed for `real-value`. Under an id/natural-key-considering strategy prism can pair a
live-only value with a desired one and emit a deep in-place edit that merge cannot preserve; that is
detected per delta and **refused by default** (use `--allow-inplace-merge` to proceed, or `real-value` for
a clean union). An edit inside a value that desired *declares* — matched by container id, or by the schema's
natural key — is a managed update and is not refused (see below).

#### Syncing an item nested inside a multi-valued container

To manage **one entry** deep inside multi-valued containers — say a single GUI panel at
`adminGuiConfiguration/objectDetails/objectDetailsPage[type=UserType]/panel[identifier=my-panel]` — combine
`equivalence=natural-keys` with `multivalue=merge`:

```
# midgie.policy
SystemConfigurationType/SystemConfiguration  equivalence=natural-keys multivalue=merge
```

The midPoint schema declares an `a:naturalKey` for these containers (`panel` → `identifier`,
`objectDetailsPage` → `type`, and ~100 more), so prism matches your id-less fragment against the live values
by key and emits a single deep delta — e.g. `replace …/objectDetailsPage[84]/panel[284]/displayOrder` —
leaving sibling entries and unmentioned items untouched. Your fragment only needs the key items plus what
you manage:

```xml
<systemConfiguration>
  <name>SystemConfiguration</name>
  <adminGuiConfiguration><objectDetails><objectDetailsPage>
    <type>c:UserType</type>
    <panel>
      <identifier>my-panel</identifier>
      <displayOrder>16</displayOrder>
    </panel>
  </objectDetailsPage></objectDetails></adminGuiConfiguration>
</systemConfiguration>
```

`merge` is what keeps the siblings: without it, replace semantics treat the containers you declare as
authoritative and delete every entry you did not list. midgie warns when that is about to happen — see
below.

### Guard rails

A partial fragment can destroy live state, so midgie reports the danger before anything is written. These
are **advisories, not blocks** — the run proceeds; use `--dry-run` to inspect first, and `--quiet` to
silence them.

- **Blast radius.** Every summary line states what it removes (`delete …/objectDetailsPage (removes 2
  values)`), and a removing delta prints `WARNING: this change REMOVES n values from the live object` with
  the paths. A removed *container* value takes everything nested in it, so this is the number that matters.
- **A value missing its natural key.** `'…/panel' value #1 does not carry its natural key (identifier), so
  it cannot be matched to a live value` — it would be applied as a whole-value change instead of updating
  the matching entry.
- **A partially declared container.** `'…/panel': desired declares 1 of 4 live value(s), and this path is on
  multivalue=replace, so the other 3 will be DELETED. Set '…/panel multivalue=merge' (with
  equivalence=natural-keys) to update just the entries you list.`

These stay quiet unless they mean something: only containers with a schema-declared natural key are checked,
the key must actually be *in use* by the live values (plenty of types declare one that real config never
fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value that desired
restates verbatim counts as declared. The key-matched merge recipe above produces no warnings at all.

### Connection (live modes)

| flag | meaning |
|---|---|
| `--host URL` | midPoint base URL (or `MIDPOINT_URL`), e.g. `https://mp.example/midpoint`. A trailing `/ws/rest` is accepted. |
| `--user USER` | REST user (or `MIDPOINT_USER`). |
| `--password`, `--password-env VAR`, `--password-file FILE` | Password sources; resolved in the order **`--password` → `--password-env` → `--password-file` → `MIDPOINT_PASSWORD` → interactive prompt**. Prefer anything but `--password` (it is visible in `ps`/shell history). |
| `--oid OID` | Object to fetch/patch. Defaults to the OID in the desired object. |
| `--cacert FILE` | Verify the server's TLS certificate against this CA cert (PEM). |
| `--insecure` | Trust any TLS certificate and skip hostname verification (self-signed dev servers). |
| `--timeout SECONDS` | Per-request timeout (default 30). |
| `--retries N` | Retry idempotent reads on `503`/connection-refused/timeout, `N` times with backoff (writes are never auto-retried). |

### Schema (extension items)

| flag | meaning |
|---|---|
| `--schema FILE` | Extension schema: `.xsd` raw XSD or `.xml` exported `SchemaType`. Repeatable. |
| `--schema-dir DIR` | Directory of extension schemas (`.xsd`/`.xml`); other files ignored. |
| `--no-live-schema` | Skip the live `GET /schemas` read (faster, or when the account lacks schema-read). Extension items then need `--schema`/`--schema-dir` or they diff as raw. |

### Output, logging & writes

| flag | meaning |
|---|---|
| `--format xml\|json\|summary` | `xml` (default) / `json`: the `ObjectModificationType`. `summary`: a human-readable changed-path list. |
| `-o, --output FILE` | Write the delta to `FILE` instead of stdout. |
| `-q, --quiet` | Suppress advisory notes/status; keep the delta payload, errors, and exit code. |
| `-v, --debug` | Log a full DEBUG trace on stderr — the tool's own decisions (mode, connection, every REST call, per-object outcome) plus framework logs, each line timestamped. |
| `--trace` | Everything `--debug` logs, plus full REST request/response bodies and HTTP error bodies. Implies `--debug`; may include sensitive values. |
| `-c, --create-if-absent` | *(apply, deploy)* `POST` the whole desired object if it does not exist. |
| `-m, --match-by-name` | *(diff, apply, deploy)* Resolve an OID-less object on the server by type + name (a single match). |
| `--dry-run` | *(apply, deploy)* Compute and print the change(s) but do not send them; exits `1` if anything would change. |
| `-y, --yes` | *(apply, deploy)* Apply without the interactive confirmation prompt (required non-interactively). |

`deploy` adds tree-level flags — `--retry`, `--plan <dir>` / `--apply-plan <dir>`, `--policy <file>`, and `--confirm-each-apply` — described in the `deploy` section above.

## Environment variables

| var | equivalent of |
|---|---|
| `MIDPOINT_URL` | `--host` |
| `MIDPOINT_USER` | `--user` |
| `MIDPOINT_PASSWORD` | password (after the `--password*` flags, before the prompt) |
| `MIDGIE_JAR` | JAR path used by the `bin/midgie` launcher |
| `JDK_JAVA_OPTIONS` | set to `--sun-misc-unsafe-memory-access=allow` when running the JAR directly on JDK 23+ |

## Safety guards

- **Protected/encrypted values** — `ProtectedStringType` deltas (e.g. `credentials/password/value`) are
  dropped from the delta and their paths reported: the ciphertext re-encrypts on every fetch, so diffing it
  yields spurious deltas *and* would leak the secret. Rotate credentials through midPoint's own API.
- **Type/OID cross-check** — enforces the "same object" contract: the diff refuses inputs of different
  types, or inputs that both carry an OID when the OIDs differ, so you cannot accidentally diff the wrong
  object. A desired object with no OID is an unbound template and is allowed (the current object's OID
  governs).
- **Accrued in-place merge edits** — see `--allow-inplace-merge` above; refused by default.
- **Base-schema version coupling** — the bundled `midpoint-schema` jar is the *compile-time* object model,
  so it must match the target midPoint's major line. In live mode the tool warns if the server's version
  (read via `GET /nodes`) is a different major line than the bundled schema.

## Schema handling

Two layers (full detail in [`DESIGN.md`](DESIGN.md#schema-two-layers-two-knobs)):

- **Base** model (`UserType`, `RoleType`, … and their item definitions) is **compile-time**, from the
  `midpoint-schema` jar. It cannot be supplied as XML.
- **Extension** schema (custom `extension` attributes, `SchemaType` objects) is **runtime**: from
  `--schema`/`--schema-dir` (raw XSD or exported `SchemaType`), and in live mode read from the server
  automatically unless `--no-live-schema` is set.

## Targeting another midPoint version

The bundled base schema is version-specific. To target a different midPoint major line, bump
`<midpoint.version>` in `pom.xml` (default **4.10.3**) **and** `MP_VER` in `docker/.env`, then rebuild. A
build-time check fails if those two drift apart. `bin/midgie --version` prints the tool version and
the bundled schema line.

## Testing

```bash
mvn -q test                 # offline unit/command tests (no server needed)
```

A Docker Compose rig under `docker/` brings up a real midPoint for the live integration tests (gated on the
rig being up), and `docker/tls/` adds an nginx TLS proxy + private CA to exercise `--cacert`/`--insecure`
end to end. See [`docker/README.md`](docker/README.md) and [`docker/tls/README.md`](docker/tls/README.md).

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` on JDK 17 and 21
(the rig-gated live tests self-skip) plus a [REUSE](https://reuse.software/) licence-compliance lint on
every push and pull request.

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2), so a derived work must remain
EUPL-compatible — it cannot be relicensed as MIT/Apache. Per-file `SPDX-License-Identifier` headers plus a
[`REUSE.toml`](REUSE.toml) make the project [REUSE](https://reuse.software/)-compliant.
