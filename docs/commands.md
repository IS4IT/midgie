# Commands

`midgie` has four subcommands. **`diff`** shows the change for a **single object** and never writes; **`deploy`** reconciles any number of **files and folders** — one file is a tree of one; **`rollback`** puts back what the last `deploy` changed; **`init`** holds the one-off set-up steps — `--create-state` enables removal detection for a server, `--write-cacert` turns a server's CA into a trust file, `--write-properties` writes a config template, `--write-policy` seeds a policy file for a tree (see [`init`](#init--one-off-set-up)). `diff` and `deploy` read desired-state XML and run the same local diff engine — the current state comes from a local file (offline) or a running server (`--url`); `rollback` replays a saved plan and reads nothing from the tree.

> Each object is compared with **itself**, not another object: its *desired* state (how you want it) against its *current* state (how it is now), and the delta transforms current → desired. If both sides carry an OID and the OIDs differ, the run is refused.

In live mode each object is addressed by the OID in its file (or `--oid`, when the run holds exactly one object); if it has none it is resolved on the server by type + name (a single match). That is the default, since a tree whose files carry no OIDs is the ordinary way to write one; **`--no-match-by-name`** opts out: an OID-less object that already exists is then skipped rather than patched by name. An ambiguous name — two live objects sharing it — is always an error rather than a guess.

## `diff` — the minimal delta

Emits the `ObjectModificationType`. The current state is a local `CURRENT` file (offline) or, with `--url`, the object on a running server. Pass `-` for either path to read from stdin; the input is read as a file would be, by its byte-order mark or declared encoding.

```bash
# Offline: desired vs a saved copy of its current state
bin/midgie diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: desired vs its current state on a running midPoint
bin/midgie diff desired.xml --url https://mp.example/midpoint --user administrator
```

An explicit `CURRENT` file means offline; `--url` (or `MIDPOINT_URL`) means live — the two together are an error, and a URL inherited only from `MIDPOINT_URL` is a fallback that a `CURRENT` file overrides (so an env var never breaks a file diff).

A live `diff` **is** `deploy --dry-run --format xml` for that one object, and runs through the same code: the same lookup, the same `--policy`, the same notes, byte-for-byte the same payload. An object that does not exist yet comes out as the `POST` body that would create it (exit `1`), not as an error — always as XML, whatever `--format` says, since a create has no delta to summarise. What `diff` adds is that it cannot write — it has no flag that leads to a `PATCH` — which is what you want from a drift check in CI or a read-only account. An offline diff compares the two files as they are, with no policy.

**OIDs are named for the reader.** Whatever midgie writes as XML for a person to read — the `diff` payload, `deploy --dry-run --format xml`, `--plan` files and the rollback plan — carries a comment above each element that names an object by OID:

```xml
    <!-- RoleType "End user" -->
    <apti:oid>00000000-0000-0000-0000-000000000008</apti:oid>
    …
            <!-- RoleType "Superuser" -->
            <targetRef oid="00000000-0000-0000-0000-000000000004" type="c:RoleType"/>
```

Objects of the tree are named from the tree; references beyond it are looked up on the server, one search per object type. An OID the server does not know is labelled `RoleType not found on the server`, so a dangling reference shows before it is sent — unless the tree itself holds that object, which then reads `RoleType "Manager" (not on the server yet, deployed with this tree)`. An offline `diff` has no server and names only the object itself. The REST interface ignores comments, and `--apply-plan` and `rollback` strip them before sending anyway, so your own review notes in a plan file never reach the server either. A `--format json` PATCH body carries none; the XML `POST` body for an object that does not exist yet carries them in every format.

## `deploy` — reconcile files and folders

Takes any number of files and folders (folders are walked recursively), sorts the objects by dependency, and either prints the apply order (offline) or reconciles them against a live server (`--url`). A single file is simply a tree of one, which is how you apply one object:

```bash
bin/midgie deploy role.xml --url https://mp.example/midpoint --user administrator   # one object, with a prompt
bin/midgie deploy role.xml --url … --dry-run --format xml > patch.xml             # its PATCH (or POST) body
bin/midgie deploy config/          # offline: print the dependency-ordered apply plan
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --dry-run   # preview the changes
bin/midgie deploy config/ --url https://mp.example/midpoint --user administrator --yes   # apply
bin/midgie deploy config/ --url … --plan out/                      # write the changes to out/ for review
bin/midgie deploy --apply-plan out/ --url … --yes                  # replay a reviewed plan
```

### Apply order and fragments

The order is two-tier: extension **schemas** first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then **objects** (sorted by their reference OIDs). Objects are identified by OID, or by type+name when they carry no OID, so several files may declare *fragments* of one object — and those files are **combined into one desired object**, which is the apply unit. Two files each contributing a `panel`, an `inducement` or an attribute definition therefore compose: entries of a multi-valued container are paired by container id or by the schema's natural key (so two files describing `objectDetailsPage[type=UserType]` yield one page with both their panels), and anything that cannot be paired is appended, with identical values collapsed. A genuine contradiction between fragments (a different type under one OID, or a single-valued item set to two values) is an error; same-object non-overlapping fragments are fine. The error names **both** files, and where the clashing values run to several lines it shows a diff of them rather than printing both in full — with a long `description` or expression, the one clause that differs is otherwise buried in two near-identical blocks.

### Against a live server

With `--url`, each unit is reconciled in that order — look up the current object by a search (so a missing object doesn't 404), diff the combined object (`--scope present`), then `PATCH`, or `POST` to create it if it is not there (an OID-less object is reconciled by type+name); `--no-create-if-absent` and `--no-match-by-name` opt out of either half. One request per object, so a create is atomic rather than a partial `POST` patched into shape afterwards. It is sequential, so a role is created before the user that references it, and re-running is idempotent. Two extension schemas declaring one namespace, two units with one OID (a schema and an object included), and a file without an OID naming a type and name that two objects of the tree share are refused before anything is read from the server. Writing is gated: a single confirmation prompt by default (`--yes` to bypass, `--confirm-each-apply` to prompt per change, `--dry-run` to preview without writing; a non-interactive session without `--yes` refuses). What is confirmed is what is sent: an object whose change is different by the time it is written — someone edited it on the server while the prompt waited — is not sent and fails the run, and a run whose preview finds nothing to change records its state without asking. Exit codes: offline `0`/`2`; apply `0` (all created/patched/in-sync/skipped or declined) / `2` (any unit failed); `--dry-run` `0` in-sync / `1` would change something / `2` error.

### Reading the output

Each object gets one block: its result alone in the first column, and everything about it in the second — the file and the object, the change, then the warnings and notes about that object:

```
  would-patch   roles/role-b.xml  RoleType/Role B
                2 change(s): replace description; add subtype
                note: left alone by the policy: documentation (apply=on-create)
  in-sync       roles/role-a.xml  RoleType/Role A
```

A block is printed whole, on one stream — stdout, or stderr for a failed object or when a payload goes to stdout — so it stays together in a terminal and in a CI log. `--quiet` leaves notes out; notes about the run as a whole (tracking, the policy file) stay on stderr as `midgie: note: …`. In a terminal the second column is word-wrapped to the window, continuation lines indented a little further; a resize applies from the next block on. Anywhere else — a CI job, a pipe, a file — nothing is wrapped, so a log viewer wraps lines itself and a message stays on one line for searching; set `COLUMNS` to wrap there too. `rollback` uses the same first column. In a terminal, notes are dim and italic so the result rows and warnings stand out; `NO_COLOR` turns that off, and `CLICOLOR_FORCE=1` turns it on in a CI log that shows ANSI styles. The note naming an object that left the tree is not dimmed while the object is still on the server.

### Retries, saved plans and the policy file

Three more flags round it out: **`--retry`** re-applies the failed units until they stabilise (catches references the static OID graph can't order — by name, filter, or expression); **`--plan <dir>`** writes the ordered changes as numbered `create`/`patch` files for review or GitOps, and **`--apply-plan <dir>`** replays them verbatim, minus comments — leaving a rollback plan like any deploy, and, on a tracked tree, recording the tracking rows the plan computed so the next plan detects what this one's tree dropped; **`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container; [The policy file](policy.md) describes it. Full design in [`DESIGN.md`](../DESIGN.md#deploy--apply-a-folder-tree).

### The rollback plan (always on)

Every `deploy` that writes leaves a **rollback plan** behind: for each object it touched, the delta that puts it back. Nothing is recomputed at rollback time — the net holds the inverse of each change as it was actually applied.

```bash
bin/midgie deploy config/ --url … --yes         # ... Rollback plan: ~/.local/state/midgie/<deployment>/20260805-091216
bin/midgie rollback --url … --dry-run           # what would be restored
bin/midgie rollback --url … --yes               # restore it
```

This is the answer to the ordinary disaster: a missing line in the policy file leaves a multi-valued container on `replace`, the wholesale replace wipes accrued associations or part of the `SystemConfiguration`, and the run reports success. Those deletes carry the wiped values themselves, so the inverse restores them exactly.

`rollback` refuses rather than guesses:

- **Provenance.** The plan records which server and which deployment it came from. A mismatch is an error with no override — a plan written against staging must not reach production because somebody answered yes.
- **Staleness.** The plan records each object's repository version after the write. If an object has moved since, someone else changed it and replaying would revert their change too; `--force` proceeds anyway. An object whose version could not be recorded counts as changed.

A rollback that fails part way marks each object it did restore, so running it again sends only the rest — no `--force` needed for the ones whose versions it moved itself. When the tree's deployment cannot be told (no checkout, a shallow clone), the plan's deployment is not compared, and the run says so.

Objects the deploy **created** are named in the plan but never removed: the inverse of an add is an object delete, and an object created an hour ago may already carry links and shadows. Delete those by hand.

On a [tracked](removal-detection.md) tree the plan also records the `midgie-state` rows the deploy moved, and `rollback` moves them back for every object it restored — only where a row still holds what the deploy wrote, so a later run's rows are left alone. Without that, the rows kept saying the rolled-back revision was applied, and deploying it again kept the values it was meant to remove beside the new ones.

Plans go to `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/` by default, keeping the newest five runs; rotation removes only run directories midgie wrote, never anything else under the base path. `--rollback-plan DIR` chooses somewhere else — necessary in a Kubernetes Job, whose filesystem dies with the pod that needed the net, so point it at a mounted volume. `--no-rollback-plan` suppresses it.

> There is no `--auto-rollback`. midPoint has no cross-object transaction, so unwinding a partly-applied tree can land you in a third state that is neither old nor new — and the failure that would trigger it is often the one that prevents it. The plan sitting on disk is what makes a considered rollback cheap.

### Removing what the tree dropped

On a tracked tree, `deploy` also removes the values the tree has dropped since the last deploy, in each object's own change and behind the same confirmation. It is opt-in, per server: see [Detecting what you removed](removal-detection.md).

## `init` — one-off set-up

Every `init` step is opt-in, because each one writes something: `init` on its own prints its help and exits 2 rather than picking a step for you.

One `init` run may take several steps; every refusal any of them would meet is checked before the first one writes anything, and only one of the files written may go to stdout, which then holds that file alone.

### `init --create-state`

Creates the `midgie-state` lookup table that turns on removal detection for a server. [Detecting what you removed](removal-detection.md) covers what it records and how to switch it off again.

### `init --write-cacert`

`init --write-cacert FILE` fetches the certificate chain the server presents — `openssl s_client -showcerts`, without the openssl — shows each certificate with its SHA-256 fingerprint, and on confirmation writes the **issuing CA(s)** as the PEM `--cacert` reads:

```bash
bin/midgie init --write-cacert corp-ca.pem --url https://mp.example/midpoint
```

It needs no account: you cannot authenticate to a server you do not trust yet. The leaf is deliberately left out — trusting it would pin one certificate and break at its next renewal — so what lands in the file is what keeps working. A server that sends only its own certificate, not self-signed, leaves nothing else to take; that is written with a warning saying so, and the better fix is the issuing CA's certificate from its owner. **The confirmation is the security**: fetching a certificate means trusting whatever answered the connection, so check the fingerprints against what the CA's owner publishes before saying yes. A non-interactive run refuses rather than assuming (`--yes` overrides, for when the fingerprints are already known).

### `init --write-properties`

`init --write-properties FILE` writes an annotated [config file](configuration.md#config-file--midgieproperties) template — every option that can carry a default, commented out, with its help text, generated from the command model so it cannot describe a version of midgie that no longer exists. It needs no server at all, since it writes the file that holds the URL:

```bash
bin/midgie init --write-properties ~/.midgie.properties
```

For the tree-local template it writes when the file is named `.midgie.properties`, see [A `.midgie.properties` in the tree](configuration.md#a-midgieproperties-in-the-tree).

### `init --write-policy`

Seeds a starter policy file for a tree: see [Seeding one with `init --write-policy`](policy.md#seeding-one-with-init---write-policy).
