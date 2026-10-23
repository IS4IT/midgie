# Detecting what you removed (opt-in)

Dropping a value from a file is invisible to an ordinary diff: `desired` simply stops mentioning it, and under `multivalue=merge` the delta comes out empty, so `diff` finds nothing to change while the value lives on. midgie closes that with a **three-way** comparison — desired, live, and *last-applied* — the merge `kubectl apply` performs, but with last-applied tracked in git rather than as a copy of every object stored on the object itself. A value present in last-applied but absent now is one you removed on purpose; a value the server holds that midgie never applied is accrued state and is left alone.

## Turning it on

Last-applied comes from **version control**: for every deployed file midgie records the git **blob id** of the content it applied, and `git cat-file` gives that content back. A blob id is the content's own hash — the same in every clone and worktree, and changed only when the file is — so that id is all that must be stored. Enable it once per server:

```bash
bin/midgie init --create-state --url https://mp.example/midpoint --user administrator config/
```

That creates a `midgie-state` lookup table which records, **per file**, what was last applied — whatever path you deploy: the whole repository, a folder, or one file. Deploys of different paths therefore never disagree about a file, and `deploy role.xml` is tracked like the folder around it. `deploy` never creates it — until you run `init --create-state`, the tree is untracked: deploy runs `--multivalue auto` as `replace` and notes that detection is off. Afterwards each deploy records what it applied, runs on `merge`, and **removes what the tree has dropped since** — with each object's change, behind the same confirmation:

```
midgie: 1 value(s) deployed earlier are no longer declared by the tree (each object's change would remove its own).
midgie: note: RoleType/Manager: removing 1 value(s) no longer declared by the tree:
midgie:   subtype = legacy (was in roles/manager.xml)
```

```bash
bin/midgie deploy config/ --url … --dry-run          # preview: each object lists what it removes, exits 1 if anything would go
bin/midgie deploy config/ --url … --yes              # apply, removals included
bin/midgie deploy config/ --url … --no-prune --yes   # apply, and only report the count of dropped values
```

## How a removal is sent

**A removal travels in its object's own PATCH**, next to the object's other changes — one request, one confirmation, one entry in the plan and in the rollback plan. So midPoint applies the change and the removal together: an `inducement` retargeted under `multivalue=merge` is added and its old form deleted in the same request, and the role never holds both, not even between two requests — which a recompute could otherwise have provisioned from. `--plan` writes the removals into each object's plan file, and `--dry-run --format xml` shows them in the one PATCH body. It is structurally safe: a removal is built solely from a value that was in last-applied, and it is sent only while the server still holds that value exactly as it was deployed — one someone has since changed or removed is left alone with a note. A value the object's diff already takes care of (an entry edited in place, or deleted anyway under `replace`) is not deleted a second time; that is judged against the live object with the diff applied locally, so no second round trip is needed. The server's own copy of a container value is what gets deleted, id included, rather than a lookup by content.

**Removals are item by item inside a value your file still declares.** A `<mapping>` (or `<panel>`, or any value of a list with a filled-in natural key) that only lost its `lifecycleState` is not reported as "the whole old mapping was removed" — which, with the rest of the mapping untouched on the server, would delete the entire mapping. The old and new file are paired the way the diff pairs values, by container id or natural key, so the removal is `mapping[name=Given name]/lifecycleState`, and it deletes that item from the server's copy of the mapping, which keeps its id. A mapping dropped from the file entirely, or an edited list value without a key (an `inducement`), is still removed as a whole value. A nested item the server changed after the deploy is left alone: `leaving mapping[name=Given name]/lifecycleState alone — the server holds a different value than the one removed from template.xml (changed on the server after it was deployed)`. `--no-prune` withholds these removals like any other.

Deleting a *fragment* is handled the same way and needs no special case: both sides are compared as whole objects — every file describing the object now against every file that described it then, deleted ones included — so you get exactly the values no surviving file still declares, a value merely moved from one file to another is not a removal at all, and the object itself is never a deletion candidate. Each reported value still names the file it came from.

## Where last-applied is kept

Rows are keyed by the repository's **root commit** plus the file's path inside it (e.g. `de5ad41d2b9b/poc/roles/manager.xml`). The root commit is part of the history, so every clone agrees on it no matter which remotes it has — which matters when the same repo is pushed to both a customer's git and your own. Detection is skipped, with a note, when the tree is not in a git repository or the clone is **shallow** (a `--depth 1` checkout cannot recover previous content; fetch full history, e.g. `fetch-depth: 0`). `--app NAME` replaces the root commit in the keys, for setups where that derivation does not fit.

With a `PATH`, `init --create-state` also reports the identity the tree will be tracked under — pass the same `--app` as deploy, if deploy uses one. One `init` run may take several steps; every refusal any of them would meet is checked before the first one writes anything, and only one of the files written may go to stdout, which then holds that file alone.

A file row looks like this; its label names the object the file described, its value is a small JSON document (`at` is when that content reached the server, and changes only when the content does):

```
key    de5ad41d2b9b/poc/roles/manager.xml
label  RoleType "Manager"
value  {"blob":"59d3a3d2314d0a4342232509f060be47f6f759bf","at":"2026-09-25T12:10:53Z","oid":"…"}
```

Each real deploy also writes a `deploy:` row per path it deployed, with the same words as its summary line — when, from which commit, by which midPoint account, with which midgie, and what it did:

```
key    deploy:de5ad41d2b9b/poc/roles
value  {"at":"2026-09-25T12:10:53Z","commit":"9c41…","by":"administrator","midgie":"0.15.0",
        "created":2,"patched":3,"in-sync":37,"removed":4,"skipped":0,"failed":0}
```

Rows move forward **per object**: the files of every object the run applied — created, patched or found in sync — get their rows updated, while an object that failed, was skipped or declined, or kept a removal back keeps its files' rows as they were, so what it dropped stays detectable on the next run. When what an object looked like at its last deploy cannot be read or compared, the run says so in a warning `--quiet` does not hide and keeps that object's rows for the next run; when its files' records contradict each other — one of them was deployed on its own since — the warning says values dropped from it were not detected this once, and its rows move on so the next run can. The `deploy:` row is written by every run that sent something, failures included; a deploy declined at the prompt writes nothing.

## Moves and partial deploys

**Moves and partial deploys.** An object's last-applied is the combination of its files, each as its own row records it. A file that no longer exists anywhere still counts — the old path of a renamed file — so renaming a file and dropping a value in the same commit is applied correctly even when you deploy only the new path. A file that still exists *outside* what you deploy counts on neither side: deploying `roles/` when the role has a second file in `extra/` applies only that part, says so (`… also has 1 file(s) outside the deployed path`), and never reads the other file's values as removed. An object that left the deployed files is reported as gone — or, when a row shows where it lives now, as moved there.

Rows written by earlier versions recorded a whole tree at one commit. A file without a row of its own is read once at the commit of the nearest such row above it; the old row is removed when a deploy covers its path.

## The state object

The state object describes and protects itself: it is `indestructible` (midPoint refuses to delete it — clear the flag first if you really mean to), carries a `description`/`documentation` explaining it to an admin who has never heard of midgie, and is marked `subtype=midgie`. To switch tracking off without deleting anything, set its `lifecycleState` to `archived`. Losing it is harmless — detection simply stops until `init --create-state` recreates it; midgie never deletes anything as a result.
