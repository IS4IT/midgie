# midpoint-diff

A schema-aware diff tool for [midPoint](https://evolveum.com/midpoint/). It computes the **minimal
`itemDelta`** between two versions of a **single object** and emits the `ObjectModificationType` XML that
midPoint's REST `PATCH` endpoint consumes — a `kubectl diff`/`apply` for midPoint config.

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
pure config. `midpoint-diff` changes **only the fields you manage** and leaves the rest intact. The offline
two-file mode is also useful on its own for drift detection, migration deltas, and review.

## Install

```bash
mvn -q package                       # builds target/midpoint-diff.jar (a fat JAR)
bin/midpoint-diff --help             # launcher (recommended)
java -jar target/midpoint-diff.jar --help   # or run the JAR directly
```

Requires **JDK 17+**. Dependencies (midPoint schema + prism) resolve from Evolveum's Nexus, configured in
`pom.xml`. The first build downloads the midPoint schema and prism dependencies, so it is sizeable.

The `bin/midpoint-diff` launcher finds the JAR (override with `MIDPOINT_DIFF_JAR`) and, on **JDK 23+**,
adds `--sun-misc-unsafe-memory-access=allow` to silence the JEP 498 `sun.misc.Unsafe` warnings that
classgraph (a transitive prism dependency) prints during startup. Those warnings go straight to the native
stderr, so that launch flag is the only way to suppress them — running the JAR directly instead? Set
`JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

## Modes

Both subcommands take the object's **desired** state first. Its **current** state — the *same* object as it
is now — is either a second file (offline) or fetched from a running server by OID with `--host`:

```bash
# Offline: diff the desired object against a saved copy of its current state
bin/midpoint-diff diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: diff the desired object against its current state on a running midPoint (fetched by OID)
bin/midpoint-diff diff desired.xml --host https://mp.example/midpoint --user administrator

# Apply: PATCH the minimal change into that same object, after confirmation (create it if absent)
bin/midpoint-diff apply desired.xml --host https://mp.example/midpoint --user administrator --create-if-absent
```

`DESIRED` and `CURRENT` are the same object at two points in time. In live mode the current state is
fetched by OID — the `oid` in the desired object, or `--oid`. Pass `-` for either path to read from stdin.

- **`diff`** emits the `ObjectModificationType`. The current state comes from a local `CURRENT` file
  (offline) or, with `--host`, from a `GET` over REST.
- **`apply`** fetches the object's current state, computes the delta, prints what would change, and — after
  confirmation — `PATCH`es it. It is idempotent (nothing is sent when already in sync) and, with
  `--create-if-absent`, `POST`s the object if it does not exist yet (operational/metadata items stripped
  first). `--dry-run` changes nothing and just reports the delta; `--yes`/`-y` skips the prompt (required
  in non-interactive use).

An explicit `CURRENT` file means offline; `--host` (or `MIDPOINT_URL`) means live. A `CURRENT` file **and**
`--host` together is an error; a host inherited only from `MIDPOINT_URL` is a fallback that an explicit
`CURRENT` file overrides, so an env var never breaks a file diff.

## Exit codes

The contract lets CI gate on drift without parsing output:

| command | `0` | `1` | `2` |
|---|---|---|---|
| `diff` | no differences | differences found (drift) | error |
| `apply --dry-run` | already in sync | would create/patch | error |
| `apply` (real write) | applied, created, in sync, or declined | — | error |

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
a clean union).

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

### Output & apply

| flag | meaning |
|---|---|
| `--format xml\|json\|summary` | `xml` (default) / `json`: the `ObjectModificationType`. `summary`: a human-readable changed-path list. |
| `-o, --output FILE` | Write the delta to `FILE` instead of stdout. |
| `-q, --quiet` | Suppress advisory notes/status; keep the delta payload, errors, and exit code. |
| `-v, --debug` | Raise the framework log level to DEBUG on stderr. |
| `--create-if-absent` | *(apply)* `POST` the whole desired object if it does not exist. |
| `--dry-run` | *(apply)* Compute and print the delta but do not send it; exits `1` if it would change anything. |
| `-y, --yes` | *(apply)* Apply without the interactive confirmation prompt. |

## Environment variables

| var | equivalent of |
|---|---|
| `MIDPOINT_URL` | `--host` |
| `MIDPOINT_USER` | `--user` |
| `MIDPOINT_PASSWORD` | password (after the `--password*` flags, before the prompt) |
| `MIDPOINT_DIFF_JAR` | JAR path used by the `bin/midpoint-diff` launcher |
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
build-time check fails if those two drift apart. `bin/midpoint-diff --version` prints the tool version and
the bundled schema line.

## Testing

```bash
mvn -q test                 # offline unit/command tests (no server needed)
```

A Docker Compose rig under `docker/` brings up a real midPoint for the live integration tests (gated on the
rig being up), and `docker/tls/` adds an nginx TLS proxy + private CA to exercise `--cacert`/`--insecure`
end to end. See [`docker/README.md`](docker/README.md) and [`docker/tls/README.md`](docker/tls/README.md).

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2), so a derived work must remain
EUPL-compatible — it cannot be relicensed as MIT/Apache. Per-file `SPDX-License-Identifier` headers and a
[`.reuse/dep5`](.reuse/dep5) make the project [REUSE](https://reuse.software/)-compliant.
