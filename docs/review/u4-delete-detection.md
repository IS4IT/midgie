# u4 — delete detection (`GitRevision`, `DeploymentIdentity`, `SyncState`, `RemovalDetector`)

Reviewed at `740747f`. Every finding below was reproduced against the real prism context and a real git
repository (see **Evidence**); nothing here is inferred from reading alone.

`SyncState` came out clean: `?include=row` is correct, the row update diffs to `replace row[1]/value` (no
id-carrying add), a new key diffs to a plain `add row`, and concurrent runs touch disjoint rows. Its
findings below are all in the callers' handling of what it stores.

## Findings

blocker | src/main/java/de/is4it/midpoint/diff/RemovalDetector.java:236 | the survivor set is enumerated by diffing each fragment against an *empty* object, which yields only **top-level** item paths with whole-item values, while the removal side yields **deep** paths (`deploymentInformation/headerColor`) — so `stillDeclares` can never match a nested value, and any nested value a surviving sibling still declares is reported as removed and, under `--prune`, PATCH-deleted from the live server (the `stillLive` guard passes: the apply pass just wrote it) | subtract per removal instead of pre-enumerating: for a removal at path `P` with value `V`, scan the other fragments of the same object with `fragment.object().findItem(P.removeIds())` and drop it when any value there is `REAL_VALUE`-equal to `V`

major | src/main/java/de/is4it/midpoint/diff/RemovalDetector.java:181 | a deleted fragment is dismantled item by item including the object's mandatory `name` whenever the surviving sibling identifies the object by OID and omits `<name>` (a legal fragment layout), so the prune delta contains `delete c:name` — midPoint's repo rejects it and the object's *entire* prune group fails with an obscure constraint error, or the object is left nameless | never emit a removal for `name` (better: for any item whose definition has `minOccurs >= 1`), the same exclusion the orphan branch already relies on

major | src/main/java/de/is4it/midpoint/diff/RemovalDetector.java:336 | survivors are indexed by "OID else Type/Name", so an OID-carrying fragment and an OID-less sibling of the same object land under different keys and never subtract from each other — a value both fragments declare is reported as removed from the edited one and pruned, and nothing warns that the two files are the same live object | index each object's declarations under both keys when both are derivable, and look the removal up under both

major | src/main/java/de/is4it/midpoint/diff/GitRevision.java:158 | the revision string read from the server-side `midgie-state` row is interpolated straight into `argv` (`fileAt`, :146, likewise), so a value that is not a sha is parsed by git as an *option*: a planted or hand-edited row of `--output=/path/file` makes `git diff` create/truncate that file as the deploying user on the CI runner (verified, exit 0) | validate the recorded revision against `^[0-9a-f]{7,40}$` (after stripping `-dirty`) before shelling out, and pass `--end-of-options` before the revision

major | src/main/java/de/is4it/midpoint/diff/RemovalDetector.java:150 | when the recorded revision is no longer reachable — squash-merge, rebase, force-push, GC, or `--app` in a shallow clone (which skips the shallow refusal) — every `git show` and the `git diff` fail, `detect` returns an empty list, and the run prints **nothing at all**: `--prune` silently becomes a permanent no-op while the state row keeps advancing | verify the revision once (`git rev-parse --verify <rev>^{commit}`) and report loudly that last-applied cannot be recovered, instead of degrading into "no removals"

minor | src/main/java/de/is4it/midpoint/diff/GitRevision.java:159 | git's path output format is not pinned: a deleted fragment whose filename holds a non-ASCII character comes back C-quoted (`"sub/dir/r\303\244ume.xml"`) and a user with `diff.relative=true` in their git config gets cwd-relative paths — both make the follow-up `git show <rev>:<path>` fail (exit 128), so that fragment's removals are silently missed | run it as `git -c core.quotePath=false -c diff.relative=false diff … -z` and split the output on NUL instead of `\R` + `trim()`

minor | src/main/java/de/is4it/midpoint/diff/GitRevision.java:111 | the identity takes the lexicographically first of the roots *reachable from HEAD*, which flips the moment an unrelated history is merged in (`git subtree add`, a vendored template): verified `fd1c8867…` → `be8aa174…` across one merge, so the state row key changes, tracking silently restarts from zero, and the old row lingers for ever | use the first-parent root (`rev-list --first-parent --max-parents=0 HEAD`), which is invariant under merges of unrelated histories

minor | src/main/java/de/is4it/midpoint/diff/GitRevision.java:231 | git's stderr is drained into a `Future` that is always cancelled and never read, so a failing invocation logs only `git … exited 128` — with several separate paths degrading to "detection off", a misconfigured identity or an unreachable revision cannot be diagnosed even under `--trace` | read the stderr future and log it at DEBUG when the exit code is non-zero

minor | src/main/java/de/is4it/midpoint/diff/DeploymentIdentity.java:52 | deploying a single **file** derives the identity of its parent directory, so `deploy config/roles/x.xml` writes the state row of the whole `config` tree; the next full deploy then compares against a revision at which most of the tree was never applied and silently misses every removal made in between (the multi-path case gets a note, this one does not) | key a single-file deploy by the file's own path, or skip recording when the deployed paths do not cover the tracked tree

minor | src/main/java/de/is4it/midpoint/diff/RemovalDetector.java:346 | a container removal is rendered with prism's internal debug `toString` and cut at 120 chars (`assignment = PCV(null):[PrismReferenceImpl({http://…}targetRef):[PRV(oid=22222222-2…`), and that line is the only preview an operator sees before answering the destructive `Remove N previously deployed value(s)?` prompt | serialize container values as XML (or name their natural-key/identifying items) for the report, and never cut mid-token

## Evidence

Probes were compiled against `target/classes` + the resolved dependency classpath and run outside the
repository (nothing in `src/` was touched).

**Blocker (nested value a sibling declares).** Using the shape of `examples/branding/*.xml` — two OID-less
`SystemConfiguration` fragments — the operator moves `<headerColor>navy</headerColor>` from
`sysconfig-deployment.xml` to `sysconfig-partner.xml` and commits both:

```
removals: 1
   -> SystemConfigurationType/SystemConfiguration | deploymentInformation/headerColor = navy
      | from sysconfig-deployment.xml | prunable=true
```

The same happens when both fragments simply declare `deploymentInformation/name` and one drops it. The
cause is visible in the two delta shapes:

```
declaration side (fragment vs empty):  path=name  add=1
                                       path=deploymentInformation  add=1     <- whole container, top level
removal side (new vs last-applied):    path=deploymentInformation/headerColor  del=1   <- deep
```

`stillDeclares` looks up `"deploymentInformation/headerColor"` in a map whose only relevant key is
`"deploymentInformation"`. It matches for `name`/`subtype`/`description` — exactly the top-level items
`RemovalDetectorTest` covers — and never for anything nested.

**Mandatory `name`.** Fragment A (`oid` + `name` + `description`) deleted, sibling B (`oid` + `subtype`,
no `<name>`) surviving. Not an orphan, so A is dismantled; `RemovalDetector.pruneDelta` produced:

```xml
<apti:itemDelta><t:modificationType>delete</t:modificationType>
  <t:path>c:name</t:path><t:value xsi:type="t:PolyStringType">SystemConfiguration</t:value></apti:itemDelta>
<apti:itemDelta><t:modificationType>delete</t:modificationType>
  <t:path>c:description</t:path><t:value>alpha</t:value></apti:itemDelta>
```

**Identity key.** Fragment A (`oid` + `description=alpha`), sibling B (no oid, `description=alpha`); A
drops the description → `-> 00000000-…-000000000001 | description = alpha | prunable=true`, although B
still declares it.

**Argument injection.** `git diff --diff-filter=D --name-only -M "--output=/…/pwned.txt-2" -- .` → exit 0,
file created. (`git show "--output=…:path"` refused with 128, so only `deletedSince` is directly
exploitable, but both interpolate unvalidated remote data into `argv`.)

**Root-commit flip.** One repo, one `git merge --allow-unrelated-histories`: roots before
`fd1c8867…`; after `fd1c8867… be8aa174…`; sorted-first identity changes to `be8aa174…`, while
`rev-list --first-parent --max-parents=0 HEAD` stays `fd1c8867…`.

**Checked and found sound** (no finding): the `--multivalue`-free `REAL_VALUE` diff never turns a *changed*
value into a removal (it diffs to `replace`, which `collect` correctly skips); several removals at one path
merge into a single `itemDelta` with several `<t:value>`s; `pruneDelta` only ever emits `delete`; the
drain-thread/timeout/`destroyForcibly` handling in `run()` cannot deadlock or leak a live process;
`repoRelative` canonicalises both sides (correct under macOS `/tmp` symlinks); `git show <rev>:<path>` and
`git status --porcelain -- .` are correctly repo-root-relative from a monorepo sub-tree; and `SyncState`'s
read/write round-trip is minimal and id-safe.
