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

`midgie` has four subcommands. **`diff`** and **`apply`** work on a **single object**; **`deploy`** reconciles a **folder tree** of them; **`init`** is a one-off that enables removal detection for a server (see below). All read desired-state XML and run the same local diff engine — the current state comes from a local file (offline) or a running server (`--url`).

> Each object is compared with **itself**, not another object: its *desired* state (how you want it) against its *current* state (how it is now), and the delta transforms current → desired. If both sides carry an OID and the OIDs differ, the run is refused.

In live mode `diff` and `apply` address the object by OID (its `oid`, or `--oid`), and `deploy` by the OID in each file; if it has none, **`--match-by-name`** resolves it on the server by type + name (a single match) — opt-in everywhere, since resolving a write target by name is deliberately explicit.

### `diff` — the minimal delta

Emits the `ObjectModificationType`. The current state is a local `CURRENT` file (offline) or, with `--url`, a `GET` over REST (fetched by the desired object's `oid`, or `--oid`). Pass `-` for either path to read from stdin.

```bash
# Offline: desired vs a saved copy of its current state
bin/midgie diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: desired vs its current state on a running midPoint
bin/midgie diff desired.xml --url https://mp.example/midpoint --user administrator
```

An explicit `CURRENT` file means offline; `--url` (or `MIDPOINT_URL`) means live — the two together are an error, and a URL inherited only from `MIDPOINT_URL` is a fallback that a `CURRENT` file overrides (so an env var never breaks a file diff).

### `apply` — reconcile one live object

Fetches the object's current state, computes the delta, shows what would change, and — after confirmation — `PATCH`es it. Idempotent (nothing is sent when already in sync); `--create-if-absent` `POST`s the object if it does not exist yet (operational/metadata items stripped first); `--dry-run` reports without writing (exit `1` if it would change anything); `--yes`/`-y` skips the prompt (required in non-interactive use).

```bash
bin/midgie apply desired.xml --url https://mp.example/midpoint --user administrator --create-if-absent
```

### `deploy` — reconcile a folder tree

Walks a folder tree of desired-state object XML, sorts it by dependency, and either prints the apply order (offline) or reconciles it against a live server (`--url`).

```bash
bin/midgie deploy config/          # offline: print the dependency-ordered apply plan
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --dry-run   # preview the changes
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --create-if-absent --yes   # apply
bin/midgie deploy config/ --url … --create-if-absent --plan out/   # write the changes to out/ for review
bin/midgie deploy --apply-plan out/ --url … --yes                  # replay a reviewed plan
```

#### Detecting what you removed (opt-in)

Dropping a value from a file is invisible to an ordinary diff: `desired` simply stops mentioning it, and under `multivalue=merge` the delta comes out empty, so `diff` reports `No differences.` while the value lives on. midgie closes that with a **three-way** comparison — desired, live, and *last-applied* — the merge `kubectl apply` performs, but with last-applied tracked as ArgoCD does: one synced git revision per deployment, rather than a copy of every object stored on the object itself. A value present in last-applied but absent now is one you removed on purpose; a value the server holds that midgie never applied is accrued state and is left alone.

Last-applied comes from **version control**: `git show <lastRev>:<file>` is the fragment as it was when midgie last deployed it, so the only thing that must be stored is the revision. Enable it once per server:

```bash
bin/midgie init --url https://mp.example/midpoint --user administrator config/
```

That creates a `midgie-state` lookup table which records, per tree, the revision last applied. `deploy` never creates it — until you run `init`, deploy behaves exactly as before and simply notes that detection is off. Afterwards each deploy records what it applied and reports what has since disappeared:

```
midgie: 1 value(s) deployed at 2d58fd57e470… are no longer declared by the tree (reported only — pass --prune to remove them):
midgie:   33333333-…-aaaa  subtype = beta (was in roles/manager.xml)
```

By default it **reports only**. Add **`--prune`** to actually remove them:

```bash
bin/midgie deploy config/ --url … --prune --dry-run   # preview: exits 1 if anything would go
bin/midgie deploy config/ --url … --prune --yes       # remove them
```

Pruning is confirmation-gated in its own right (a non-interactive run without `--yes` refuses, even when the apply pass had nothing to do), runs *after* the tree is applied so the desired state lands first, and is structurally safe: the delta is built solely from values that were in last-applied, so it cannot reach accrued or foreign state however wrong the rest of the tree is.

Deleting a *fragment* is handled the same way and needs no special case: you get exactly the values that file contributed, the object itself is never a deletion candidate, and anything a sibling fragment still declares is left out.

A tree is identified by its repository's **root commit** plus its path inside the repo (e.g. `df7f507/config`). The root commit is part of the history, so every clone agrees on it no matter which remotes it has — which matters when the same repo is pushed to both a customer's git and your own. Detection is skipped, with a note, when the tree is not in a git repository or the clone is **shallow** (a `--depth 1` checkout cannot recover previous content; fetch full history, e.g. `fetch-depth: 0`). `--app NAME` pins the identity for setups where that derivation does not fit.

`init` can also seed a starter policy for the tree:

```bash
bin/midgie init config/ --url … --write-policy midgie.policy
```

It walks the tree and, for each object that declares a multi-valued container the schema gives a natural key **and whose values actually carry the whole key**, writes one object-level line (`<Type>/<Name>  equivalence=natural-keys`) plus a per-container line (`<Type>/<Name>/<container>  multivalue=merge`) — commenting which containers prompted each line. The merge rules are deliberately scoped to those paths rather than to the whole object. That "actually carry it" condition matters: `assignment` and `inducement` declare an `identifier` that real config usually omits, and a composite key like `kind + intent` is often written with only the `kind` — suggesting key matching in either case would be actively wrong, since prism then pairs unrelated values and the accrued-value guard refuses the result. The file is a starting point to review, not a finished policy, and an existing file is never overwritten.

The state object describes and protects itself: it is `indestructible` (midPoint refuses to delete it — clear the flag first if you really mean to), carries a `description`/`documentation` explaining it to an admin who has never heard of midgie, and is marked `subtype=midgie`. To switch tracking off without deleting anything, set its `lifecycleState` to `archived`. Losing it is harmless — detection simply stops until `init` recreates it; midgie never deletes anything as a result.

The order is two-tier: extension **schemas** first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then **objects** (sorted by their reference OIDs). Objects are identified by OID, or by type+name when they carry no OID, so several files may declare *fragments* of one object — each applied individually. A genuine contradiction between fragments (a different type under one OID, or a single-valued item set to two values) is an error; same-object non-overlapping fragments are fine.

With `--url`, each unit is reconciled file-by-file in that order — look up the current object by a search (so a missing object doesn't 404), diff the fragment (`--scope present`), then `PATCH`, or `POST` to create with `--create-if-absent` (an OID-less object is reconciled by type+name with `--match-by-name`). It is sequential, so a role is created before the user that references it, and re-running is idempotent. Writing is gated: a single confirmation prompt by default (`--yes` to bypass, `--confirm-each-apply` to prompt per change, `--dry-run` to preview without writing; a non-interactive session without `--yes` refuses). Exit codes: offline `0`/`2`; apply `0` (all created/patched/in-sync/skipped or declined) / `2` (any unit failed); `--dry-run` `0` in-sync / `1` would change something / `2` error.

Three more flags round it out: **`--retry`** re-applies the failed units until they stabilise (catches references the static OID graph can't order — by name, filter, or expression); **`--plan <dir>`** writes the ordered changes as numbered `create`/`patch` files for review or GitOps, and **`--apply-plan <dir>`** replays them verbatim; **`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container. The selector is a single glob over `Type/Name/item…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level, and the most specific one wins; an `oid:` rule pins exactly one object and outranks any pattern. The object XML stays pure. Full design in [`DESIGN.md`](DESIGN.md#deploy--apply-a-folder-tree).

Two details worth knowing when an object name is awkward: quote the selector if the name contains `=` (`"OrgType/ou=people"  scope=full`), and escape a `/` inside a name (`RoleType/App \/ Admin`), since the selector is itself a `/`-separated path. `init --write-policy` writes both forms itself. `deploy` names any policy line that matched no object, so a typo is not mistaken for a rule that ran.

## Exit codes

The contract lets CI gate on drift without parsing output:

| command | `0` | `1` | `2` |
|---|---|---|---|
| `diff` | no differences | differences found (drift) | error |
| `apply --dry-run` | already in sync | would create/patch | error |
| `apply` (real write) | applied, created, in sync, or declined | — | error |
| `deploy --dry-run` / `--plan` | nothing to change | would change something | error |
| `deploy` (real apply) | all applied/in-sync/skipped/declined | — | a unit failed, or error |
| `deploy` (offline, no `--url`) | printed the apply order | — | error |
| `init` | created, or already present | — | error |

## Flags

### Diff strategy

| flag | default | meaning |
|---|---|---|
| `--scope present\|full` | `present` | *present*: only diff items present in `DESIRED` (unmanaged items are left alone → accrued state is preserved). *full*: full-replace (also removes items missing from desired). |
| `--multivalue replace\|merge` | `replace` | Multi-valued container handling. *replace*: desired values are authoritative (live-only values removed). *merge*: union by content — add desired values, keep live-only ones. |
| `--path <itemPath>=merge\|replace` | — | Per-path override of `--multivalue`. Repeatable (e.g. `--path assignment=merge`). |
| `--equivalence <strategy>` | `real-value` | What counts as "the same value", loosest to strictest: `real-value` · `consider-different-ids` · `natural-keys` (prism-experimental) · `ignore-metadata` · `data` · `literal`. See [`DESIGN.md`](DESIGN.md#strategy-flags). |
| `--smart-refs accept\|verify\|resolve` | `accept` | How a reference the file writes as a **search filter** is judged against the OID midPoint resolved it to. See below. |
| `--allow-inplace-merge` | off | Proceed even when a merge would edit an **accrued (live-only)** value in place (can happen under `natural-keys`). Off by default: such a run is refused so accrued state is not silently overwritten. |

`merge` is designed for `real-value`. Under an id/natural-key-considering strategy prism can pair a
live-only value with a desired one and emit a deep in-place edit that merge cannot preserve; that is
detected per delta and **refused by default** (use `--allow-inplace-merge` to proceed, or `real-value` for
a clean union). An edit inside a value that desired *declares* — matched by container id, or by the schema's
natural key — is a managed update and is not refused (see below).

#### Choosing a strategy

The defaults (`replace` + `real-value`) make a run **converge** and keep **drift visible**, which is why they are the defaults. `merge` buys safety from accidental deletion, but it gives up both, so turn it on deliberately and per path rather than globally:

- **It cannot express an edit.** A content change reads as *old value gone, new value added*, and merge drops the deletion — so it keeps both. With live `subtype=[alpha]` and desired `[beta]` you get `[alpha, beta]`, not `[beta]`.
- **It cannot express a removal.** Dropping an entry from your file no longer removes it from the server, and because the delta comes out empty `diff` reports `No differences.` and exits `0`. Drift detection and the CI exit-code gate stop seeing anything the file does not declare. `deploy` closes this for a version-controlled tree — it compares against what it last applied and reports the values you dropped (`--prune` removes them) — but `diff` and `apply`, which have no such history, still cannot see a removal at all.

Rule of thumb: keep a path on **replace** when your file is authoritative for that list, and use **merge** where your file is a *fragment* contributing entries to a list that others (or the server) also fill — which is exactly the nested-container case below. `natural-keys` is likewise opt-in: it is prism-`@Experimental`, and many types declare a natural key that real config never fills in (an `assignment` rarely carries an `<identifier>`), so as a blanket setting it makes prism pair unrelated values.

#### Syncing an item nested inside a multi-valued container

To manage **one entry** deep inside multi-valued containers — say a single GUI panel at
`adminGuiConfiguration/objectDetails/objectDetailsPage[type=UserType]/panel[identifier=my-panel]` — combine
`equivalence=natural-keys` with `multivalue=merge`:

```
# midgie.policy
SystemConfigurationType/SystemConfiguration  equivalence=natural-keys
SystemConfigurationType/SystemConfiguration/**/panel  multivalue=merge
```

Scoped to the container that needs it rather than to the whole object — the same shape
`init --write-policy` generates, and what the guidance above means by "turn it on deliberately and per
path".

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
- **A partially declared container.** `'…/panel': desired matches 1 of 4 live value(s), and this path is on
  multivalue=replace, so the other 3 will be DELETED. Set '…/panel multivalue=merge' (with
  equivalence=natural-keys) to update just the entries you list.`

These stay quiet unless they mean something: only containers with a schema-declared natural key are checked,
the key must actually be *in use* by the live values (plenty of types declare one that real config never
fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value that desired
restates verbatim counts as declared. The key-matched merge recipe above produces no warnings at all.

### Connection (live modes)

| flag | meaning |
|---|---|
| `--url URL` | midPoint base URL (or `MIDPOINT_URL`), e.g. `https://mp.example/midpoint`. A trailing `/ws/rest` is accepted. |
| `--user USER` | REST user (or `MIDPOINT_USER`). |
| `--password`, `--password-env VAR`, `--password-file FILE` | Password sources; resolved in the order **`--password` → `--password-env` → `--password-file` → `MIDPOINT_PASSWORD` → interactive prompt**. Prefer anything but `--password` (it is visible in `ps`/shell history). |
| `--oid OID` | *(diff, apply)* Object to fetch/patch. Defaults to the OID in the desired object. |
| `--cacert FILE` | Verify the server's TLS certificate against this CA cert (PEM). |
| `--insecure` | Trust any TLS certificate and skip hostname verification (self-signed dev servers). |
| `--timeout SECONDS` | Per-request timeout (default 30). |
| `--retries N` | Retry idempotent reads on `502`/`503`/`504`/connection-refused/timeout, `N` times with backoff (writes are never auto-retried). |

### Schema (extension items)

| flag | meaning |
|---|---|
| `--schema FILE` | Extension schema: `.xsd` raw XSD or `.xml` exported `SchemaType`. Repeatable. |
| `--schema-dir DIR` | Directory of extension schemas (`.xsd`/`.xml`); other files ignored. |
| `--no-live-schema` | Skip the live `GET /schemas` read (faster, or when the account lacks schema-read). Extension items then need `--schema`/`--schema-dir` or they diff as raw. |

### Output, logging & writes

| flag | meaning |
|---|---|
| `--format xml\|json\|summary` | *(diff, apply)* `xml` (default) / `json`: the `ObjectModificationType`. `summary`: a human-readable changed-path list. |
| `-o, --output FILE` | *(diff, apply)* Write the delta to `FILE` instead of stdout. |
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
| `MIDPOINT_URL` | `--url` |
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
- **Smart references** — a reference written as a search filter instead of an OID, so the file stays portable across instances where the target's OID differs (`connectorRef` is the everyday case). midPoint resolves the filter **at import** and stores the OID; your file still says "filter". Diffed literally that reads as a change, and the resulting PATCH strips the OID — and a modify does *not* re-resolve the filter, so the object is left pointing at nothing. midgie therefore treats the resolved OID as satisfying the filter and reports **in-sync**, and any PATCH it does send for such a reference carries `?options=reevaluateSearchFilters` so midPoint rebinds it instead. `--smart-refs verify` confirms the OID by running the filter against the server first (a mismatch is then a real change); `--smart-refs resolve` always sends the file's form and lets midPoint re-resolve. Settable per object in `--policy` as `smart-refs=`.
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
