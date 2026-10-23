# The policy file

**`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container. `deploy` and a live `diff` read it. The selector is a single glob over `Type/Name/item…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level, and the most specific one wins; an `oid:` rule pins exactly one object and outranks any pattern. Note that the object identity is *two* segments, so "every role" is `RoleType/*`, and an item rule scoped to a type is one glob spanning both: `RoleType/*/assignment`. The object XML stays pure. Full design in [`DESIGN.md`](../DESIGN.md#deploy--apply-a-folder-tree).

Two details worth knowing when an object name is awkward: quote the selector if the name contains `=` (`"OrgType/ou=people"  scope=full`), and escape a `/` inside a name (`RoleType/App \/ Admin`), since the selector is itself a `/`-separated path. `init --write-policy` writes both forms itself. `deploy` names the policy file it uses at the start of the run — `midgie: policy: /work/repo/midgie.policy (3 rule(s), plus 9 built-in)` — which matters most when it came from a [config file](configuration.md#a-midgieproperties-in-the-tree) rather than the command line; and it names any policy line that matched no object, so a typo is not mistaken for a rule that ran.

A wildcard is always a *whole* segment: there is no matching inside a segment (`RoleType/App-*` is not "roles starting with App-"), and one selector is one glob (`RoleType/* **/assignment` is not shorthand for two rules). Both are refused when the file loads, with the line number — as written they used to read as a literal segment no name can equal, so the rule quietly matched nothing and the objects it was meant for kept the CLI strategy. Write `\*` for a name that really contains an asterisk.

## `apply=on-create`

Some items belong in the file only so the object is **created** right, and are owned by midPoint or its operators from then on. A task's `executionState` is the everyday case: deploy it suspended, and a redeploy must not flip it back after someone resumed it. **`apply=on-create`** says exactly that:

```
TaskType/*/executionState  apply=on-create
```

The item goes into the POST that creates the object and is left alone by every later PATCH: not changed, not deleted under `--scope full`, and not removed when it drops out of the file. A rule covers everything beneath its path, and when a new container is added to an existing object (a new `activation`, say) an `on-create` item inside it is stripped from what is sent. A change that would take the item away with its container is refused for that object: deleting a single-valued container it sits in (an `activation` the file leaves out under `--scope full`), or replacing a list entry whole — which is how a list compared by content changes an entry whose copy in the file carries no container id. Removing an entry the file no longer declares at all still removes it whole. `--dry-run` and `--plan` name each difference held back, so an object shown `in-sync` while its state plainly differs is not a mystery. `apply` is per-item only; `always` is the default, and a more specific `apply=always` rule carves an exception out of a broader one. An object matched by name that someone else created counts as existing — midgie never writes the item there.

## Built-in rules

Some items are like that in nearly every midPoint deployment, so midgie ships `apply=on-create` rules for them and applies them on every deploy and live diff, with or without a `--policy` file:

| Rule | Why |
|---|---|
| `TaskType/*/executionState` | operators resume and suspend a task; a deploy would flip it back |
| `TaskType/*/schedulingState` | the task manager's side of the same state; kept with `executionState` |
| `UserType/*/credentials` | an initial password and `forceChange` belong in the create; then the user or an operator owns them |
| `UserType/*/activation/administrativeStatus` | operators or an HR feed enable and disable a user; a deploy would re-enable one |
| `UserType/*/activation/lockoutStatus`, `…/lockoutExpirationTimestamp` | set after failed logins; a deploy would unlock a locked-out user |
| `ResourceType/*/administrativeOperationalState` | maintenance mode; a deploy would take a resource out of it |
| `ResourceType/*/schema/definition` | the connector schema midPoint caches and refreshes (`generationConstraints` beside it still deploys) |
| `ResourceType/*/cacheInvalidationTimestamp` | an operator expires cached shadows with it; an older value from the file would make them count as fresh |

None of these is flagged operational in midPoint's schema, which is why midgie would otherwise deploy them. A `--policy` file **extends** the built-ins rather than replacing them: they come after its lines, so a line naming the same item wins the tie and overrides one — for every object, or for one:

```
# deploy every task's state again
TaskType/*/executionState  apply=always
# or just this one's
TaskType/Nightly Recompute/executionState  apply=always
```

The most specific rule wins whichever source it comes from, so a broader line (`TaskType/**  apply=always`) does not override them; name the item. There is no switch to turn them all off. The `--dry-run`/`--plan` note names a built-in as such — `left alone by the policy: executionState (apply=on-create, built-in)` — as does `-v`, and a built-in that reaches no object in the tree is never reported as unmatched (nor is a line restating one, as `init --write-policy` writes them). An offline `diff` of two files applies no policy at all.

## `apply=if-absent`

**`apply=if-absent`** is the gentler sibling, midPoint's *weak* mapping strength: the item is written whenever the server holds **no value** for it and never overwritten or removed once it does — not by a PATCH, not under `--scope full`, not when it drops out of the file. It differs from `on-create` only once someone clears the item: `if-absent` fills it in again on the next deploy, `on-create` leaves it empty. It is judged per item, so a rule on a container fills in each missing child and leaves the present ones alone, and a newly added container carries its `if-absent` items along (nothing can be there yet). Held-back differences are named with their rule in the `--dry-run`/`--plan` note, e.g. `left alone by the policy: description (apply=if-absent)`.

## Seeding one with `init --write-policy`

`init --write-policy` seeds a starter policy for a tree — a separate step, so it neither needs nor implies `--create-state`:

```bash
bin/midgie init config/ --url … --write-policy midgie.policy
```

It suggests **only what the defaults leave open**, and says in its header what they already cover: `--equivalence auto` matches entries by natural key wherever the files fill it in (so no `equivalence` rules), `--scope present` leaves unmentioned items alone, and `--multivalue auto` merges on a tracked tree and replaces on an untracked one. What no default can decide is **who owns a list the server holds more of than the tree**, so that is what it looks for — by comparing each object with its live copy, not by guessing from the schema:

- **Tracked tree (deploy merges):** a list whose live copy holds values the tree does not list gets a **commented-out** `<Type>/<Name>/<path>  multivalue=replace`. Merge keeps those values; uncomment the rule if the tree should own the list, and deploy deletes them.
- **Untracked tree (deploy replaces):** the same finding gets an **active** `multivalue=merge`, because replace would delete those values on the next deploy. Delete the rule if the tree owns the list and they should go.

Each rule's comment names the object, the path and how many live values it is about. After them come midgie's **[built-in rules](#built-in-rules)**, as **active** lines with the reason for each. A deploy applies them with or without the file, so writing them changes nothing; they are there so the file shows the whole policy in effect, and as the starting point for overriding one. Last, **commented examples** of the knobs nothing above used — `scope=full` on the tree's first object, `multivalue=replace`/`merge` on a list, `apply=if-absent`, `smart-refs=verify`, and an `oid:` selector — each one a line `deploy --policy` accepts once uncommented. As written, a seeded file for a tracked tree changes nothing a deploy does.

Re-running it on an existing file **refreshes only its marked block**, so you can add objects to the tree and pick up their suggestions without losing anything you wrote:

```
# your own rules go above the block — ties break to the earlier line, so they win
ResourceType/Prod LDAP/schemaHandling/objectType  multivalue=replace

# --- begin midgie suggestions — regenerated by 'init --write-policy'; edits here are lost ---
# … header: what the defaults cover, and whether this tree is tracked …
# ResourceType/Prod LDAP: 1 live value(s) at schemaHandling/objectType not in the tree
#ResourceType/Prod LDAP/schemaHandling/objectType  multivalue=replace
# … the built-in rules (active), examples …
# --- end midgie suggestions ---
```

A list that stops differing loses its rule instead of lingering; a policy written before the block existed gets one appended once; a start marker with no end is refused rather than guessed at.
