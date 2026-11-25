# Pulling from the server

A tree usually grows on a live server: you change something in the GUI, try it, change it again, and when it works you copy it into the tree by hand, from Studio or the raw XML editor. **`midgie pull`** does the copying in the other direction. It reads the objects from the server, brings the tree's own files in line with it for the items they declare, and writes everything else into a **remainder folder**, one file per object. `git diff` then shows what changed on the server, and `git add -p` takes what you want.

```bash
midgie pull config/ --url https://dev.mp.example/midpoint
git diff
```

## Managed files and the remainder

The tree describes **whole objects**, in two parts:

- the **managed** files you write and curate yourself, as before — the 5% of a system configuration that you care about, split over as many files as you like;
- the **remainder**, which `pull` writes: for each object, everything the managed files leave out. It lives in a folder of its own, `<remainder>/<Type>/<name>.<oid>.xml`:

```
config/
  sysconfig/logging.xml
  sysconfig/gui.xml
  roles/…
remainder/
  SystemConfigurationType/SystemConfiguration.00000000-0000-0000-0000-000000000001.xml
  RoleType/Superuser.00000000-0000-0000-0000-000000000004.xml
```

A file is named as the GUI shows the object, with characters a file system refuses replaced by `_`, and made unique by its OID. A rename on the server renames the file. Type folders use the schema's type name (`RoleType`).

Because the two parts together are the whole object, an addition on the server is a change like any other: a diff against the tree no longer shows 95% of a system configuration as "different", only what actually changed.

The folder is part of the tree's layout, so it is set in the tree's `.midgie.properties` (read relative to that file), or with `--remainder DIR`:

```properties
# .midgie.properties at the repository root
remainder = remainder
```

`deploy` and a live `diff` read the same key: see [deploying with the remainder](#deploying-with-the-remainder).

## What goes into a remainder file

Whatever the managed files do not declare, item by item:

- An item no managed file declares goes in whole.
- A **single-valued container** a managed file declares is followed into: a file declaring `logging/rootLoggerAppender` leaves the rest of `logging` to the remainder.
- A **list entry** is paired with the file's entries by container id or natural key, the way [fragments](commands.md#apply-order-and-fragments) are combined. A paired entry with a natural key is followed into, and its part in the remainder repeats the key, so the two halves pair up again when the tree is deployed. An entry equal to one the file declares stays with the file. An entry the file has no counterpart for goes to the remainder whole.
- A **multi-valued property or reference** keeps the values the file does not list in the remainder — except a reference the file writes as a [search filter](safety.md), which only a deploy can match with the OID it resolved to.
- A **single-valued item** the file declares, and an item without a schema definition, belong to the file outright.

Left out of every remainder file, as deploy leaves them alone: operational items and value metadata (`_metadata`, `metadata`), the object's `version` and every container id, protected values (passwords and other encrypted strings), items the server did not send (`incomplete`), and items the policy marks [`apply=never`](policy.md#applynever). The `name` always stays, so that a file says which object it holds. Each file starts with a fixed comment and holds no timestamp, so pulling twice from an unchanged server writes the same bytes.

## What pull changes in your files

Where a managed file and the server disagree about an item the file declares, `pull` changes the file to say what the server says — and nothing else in it. A file keeps its shape: an item it declares takes the server's value, one the server no longer holds goes, and nothing is added — what is new on the server goes to the remainder.

- A changed value is rewritten where it stands. For a structured value, the elements are compared with the server's one by one and only what differs is rewritten: a mapping whose script changed on the server keeps its `<expression>`, `<script>` and `<code>` elements, and only the code inside the CDATA section changes.
- A value, list entry or item the server no longer holds is removed, with its line when it stood alone on it. A container left with nothing in it goes whole.
- A list entry without a natural key, paired with the server's by container id alone, is written whole where the old one stood, indented to match.

Everything else stays as it was: comments, blank lines, attribute order, namespace prefixes, CDATA sections, line endings and a byte-order mark. A rewritten element that uses a prefix the file does not bind brings its own declaration. A comment inside a value that is rewritten is lost, and `pull` says so.

```diff
     <logging>
         <classLogger>
             <!-- Projector noise -->
-            <level>INFO</level>
+            <level>DEBUG</level>
             <package>com.evolveum.midpoint.model.impl.lens.projector.Projector</package>
```

Some disagreements stay as they are, and `pull` names them: items the policy marks `apply=on-create` or `apply=if-absent` (the file holds the value the object is created or filled in with, not the one it has now), and references the file writes as a [search filter](safety.md), which only a deploy can judge against the OID the server holds.

**Git is the safety net, so it is required.** `pull` edits only a file that is committed as it stands: a file outside a git repository, or one with changes of its own not yet committed, is left alone — with a warning naming what would change — and so is the rest of that object, its remainder included. Commit or stash first; then `git diff` shows exactly what the pull did, and `git checkout` undoes it.

**Verify, or refuse.** Every edited file must read back as the file with the server's values put in. If it does not — or an edit cannot be placed, or the file is in an encoding other than UTF-8 — `pull` writes nothing for that object, says which file and what would have changed, and exits 2.

**Conflicts.** One disagreement has no right answer: a list where the file holds entries the server does not, **and** the server holds entries the file does not, with nothing to pair them — typically a list entry without a natural key that someone edited on the server. Taking the server's entry into the file would be a guess at which one it replaces. `pull` leaves that object's files as they were, says which list it is, and exits 2. Deploy the tree (the file's version wins) or bring the file in line with the server by hand, then pull again.

## Which objects get a file

The policy's `remainder` knob decides, per object:

| | default | override |
|---|---|---|
| an object the run's files manage | a remainder file | `remainder=no` |
| any other object on the server | none | `remainder=yes` |

```
# every role and archetype, managed or not
RoleType/*       remainder=yes
ArchetypeType/*  remainder=yes
# but not this one
RoleType/Scratchpad  remainder=no
```

A `remainder=yes` rule makes `pull` list every object of the types its selector names; `*/*  remainder=yes` lists every type the server serves, so name what you do *not* want with more specific `no` rules (`UserType/*  remainder=no`, `ShadowType/*  remainder=no`). A type every object of which a more specific `no` rule excludes is not listed at all. A rule naming an object by OID decides for that object but never makes `pull` list a type. One built-in rule ships: `LookupTableType/midgie-state  remainder=no`, since midgie's own [state](removal-detection.md) belongs to the server it describes, not to a tree.

Some objects change on their own: a node checks in every few seconds, a task records its progress. Pulled into the remainder they show as a change on every pull, and a deploy would put the old value back. Keep such objects out with `remainder=no`, or such items with `apply=never` (`TaskType/*/activityState  apply=never`).

A file in the remainder folder is removed when its object is gone from a type `pull` listed in full, when the policy no longer asks for it, or when the managed files now declare all of it. A file `pull` cannot judge this run is left alone.

## Pulling part of a tree, and stages

The run's paths are the managed part. An object whose managed files lie partly outside the run is skipped with a note — its remainder would otherwise take in what those other files declare — so pull the folders that describe an object together. As with deploy, files of a [stage](staging.md) the run does not pull from do not count (`--stages`, or `stages` in the tree's `.midgie.properties`):

```bash
# pull from dev: dev's stage-specific items land in nothing but stages/dev
midgie pull config/ stages/dev/ --url https://dev.mp.example/midpoint
```

## Deploying with the remainder

An object deployed **with its remainder file and every other file of it** runs under `--scope full` and `--multivalue replace`: the tree holds the whole object, so an item or a list entry the tree lacks was added on the server since the last pull, and the deploy removes it. That is what keeps a test or production server to exactly what the tree says. A scope or multivalue you ask for — on the command line, in a config file, or by a policy rule for the object — wins over that, and items marked `apply=never` are never touched.

Deploying the managed files alone leaves the remainder out of the run, so the object is [not complete](strategy.md#full-scope-needs-the-whole-object) and runs under `--scope present` with a note. That is the way to deploy only what you curate, onto a server whose other settings are not yours — a newer midPoint version on dev than on production, say, where the remainder holds the newer version's defaults.

## Reviewing with git

The remainder is ordinary files, so git is the review tool:

```bash
midgie pull config/ stages/dev/
git diff --stat remainder/          # which objects changed on the server
git add -p remainder/               # take what you want
git checkout .                      # throw the rest away
```

Items keep the schema's order and list entries the server's, so a re-ordered list is a real change on the server. `git config diff.colorMoved dimmed-zebra` shows a block that only moved in dimmed colours, so it is not mistaken for an edit.

To curate an item, move it from the remainder into a managed file; the next pull leaves it out of the remainder.

## Options and exit codes

`pull [PATH…] --url URL` takes the [connection](reference.md#connection-live-modes) and [schema](reference.md#schema-extension-items) options, plus:

| flag | meaning |
|---|---|
| `--remainder DIR` | where the remainder files go; `remainder` in the tree's `.midgie.properties` |
| `--policy FILE` | its `remainder` rules choose the objects, its `apply=never` items are left out |
| `--stages GLOB` | folders that each hold one stage's files, as deploy takes it |
| `--dry-run` | print what would change as a diff on stdout; write nothing |
| `-q`, `-v`, `--trace` | quieter, or more detail |

Everything is read before anything is written, so a server that stops answering leaves the folder as it was. Exit `0` when the folder was written or already in step, `1` under `--dry-run` when something would change, `2` on an error or a [conflict](#what-pull-changes-in-your-files).
