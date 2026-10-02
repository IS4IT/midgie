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

Requires **JDK 21+**. Dependencies (midPoint schema + prism) resolve from Evolveum's Nexus, configured in
`pom.xml`. The first build downloads the midPoint schema and prism dependencies, so it is sizeable.

The build takes its version from the git tag (`git describe`), so `--version` says what the JAR was built from: `0.11.2` on a release tag, `0.11.2-1-g4c278ae-dirty` one commit past it with a modified working tree. Building outside a git checkout — a source archive, say — falls back to the placeholder version in `pom.xml`.

The `bin/midgie` launcher finds the JAR (override with `MIDGIE_JAR`) and, on **JDK 23+**,
adds `--sun-misc-unsafe-memory-access=allow` to silence the JEP 498 `sun.misc.Unsafe` warnings that
classgraph (a transitive prism dependency) prints during startup. Those warnings go straight to the native
stderr, so that launch flag is the only way to suppress them — running the JAR directly instead? Set
`JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

### Container image

Each release also publishes **`is4it/midgie`** on Docker Hub, for `linux/amd64` and `linux/arm64` — the JRE, the `bin/midgie` launcher, and **git**, which midgie forks to derive a tree's deployment identity and to recover what it applied last time (without it, `deploy` still applies; only [removal detection](#detecting-what-you-removed-opt-in) goes dark). git can clone over HTTPS and SSH (`curl`, `openssh-client`, `less`), so the tree can be fetched in the container itself rather than by an init container, and `vi` is there to edit a file in place. It is a tool image: the entrypoint is `midgie`, so arguments are subcommands.

```bash
docker run --rm -u "$(id -u):0" --mount type=bind,source="$PWD",target=/work is4it/midgie:0.18.0 diff a.xml b.xml

# In a Job, command: is the entrypoint and args: the subcommand line.
#   args: ["deploy", "/work", "--url", "https://mp/midpoint", "--yes"]
```

Two things in that line are not decoration. **`-u "$(id -u):0"`**: the image runs as UID 1001 in group 0, and a bind mount ignores that entirely — `/work` keeps the *host* directory's ownership and mode, so on a Linux host a tree that is not world-readable is unreadable to the container (`cannot read /work: permission denied — midgie runs as uid 1001 …`). Run as your own UID to get past it, and keep the group at `0` rather than `$(id -g)`: `HOME` and `/work` are group-owned by 0 with `g=u`, which is what makes them writable for a UID the image has never heard of — and `deploy` needs a writable `HOME` for its [rollback plan](#the-rollback-plan-always-on). On an SELinux host (Fedora, RHEL — not SLES or Ubuntu) the label denies the mount before the UID ever matters, and the fix there is `-v "$PWD:/work:z"`: relabelling is a `-v` suffix with no `--mount` equivalent, so that one case is worth giving up the flag below for. In Kubernetes none of this applies — `runAsUser` and `fsGroup` do the same job, and the [Helm chart](#helm-chart) sets both.

**`--mount` rather than `-v`**: when the source path does not exist, or `$PWD` was not expanded because the command was pasted in single quotes, `-v` does not fail — it invents an empty directory (or a named volume) and mounts *that*. The run then reports `no midPoint objects found under /work — it holds no files at all`, which is a truthful answer to a question you did not mean to ask. `--mount` refuses instead. A host home directory on NFS/autofs can produce the same empty mount for a different reason (the daemon's mount namespace may not carry the automount); `docker run --entrypoint ls <image> -la /work` settles which of the two you have.

For a shell — to run midgie by hand against a checkout in the pod — the container needs something long-running, since the entrypoint would otherwise print help and exit:

```bash
kubectl run midgie --image=is4it/midgie:0.18.0 --restart=Never --command -- sleep infinity
kubectl exec -it midgie -- bash          # then: midgie deploy /work --url ...
docker run -d --name midgie --entrypoint sleep is4it/midgie:0.18.0 infinity   # the docker equivalent
```

`docker run <image> bash` does *not* give a shell — it runs `midgie bash`, which is rejected as an unknown subcommand. Use `--entrypoint bash`.

Cloning over SSH needs a key ssh will accept: mount the Secret with `defaultMode: 0400` and set `fsGroup` to the UID the pod runs as. Kubernetes then lands the file at `0440` owned `root:<fsGroup>` — readable by the pod, and still accepted by ssh, whose "unprotected private key" refusal only fires for a file owned by the calling UID. An unknown host key fails the clone by default, so either mount a `known_hosts` or add `-o StrictHostKeyChecking=accept-new` via `GIT_SSH_COMMAND`. The [Helm chart](#helm-chart) does all of this for you.

Two things about the git side. **Clone with full history**: midgie refuses a shallow clone for tracking, so the usual `--depth 1` / `git-sync` default silently disables removal detection. And the image sets `safe.directory=*` system-wide, because a tree cloned by an init container is owned by a different UID than the one midgie runs as, and git's ownership refusal surfaces only as "the tree is not in a git repository" — tracking then goes unavailable without anything failing. If you would rather keep git's check, drop that line from `docker/midgie/Dockerfile` and run both containers as the same UID.

The image runs as UID 1001 (group 0, so an OpenShift-assigned UID can still write `$HOME` and `/work`) and builds from `docker/midgie/Dockerfile`, which copies in a JAR you have already built:

```bash
mvn -q package && docker build -f docker/midgie/Dockerfile -t is4it/midgie:dev .
```

An image-only fix — a patched base image, a tool the image turns out to need — does not cost a midgie version. Rebuilding a released tag's image is a manual run of the release workflow, which checks out that tag and builds it with the same steps, so the rebuilt image cannot drift from the released one; the GitHub Release and its JAR are left alone.

```bash
gh workflow run Release -f tag=v0.4.3                        # rebuild that tag's image
gh workflow run Release -f tag=v0.4.3 -f update_latest=true  # ...and move :latest to it
```

`:latest` stays put unless you ask for it, so rebuilding an older tag cannot drag it backwards (and a pre-release never takes it either way). The consequence for anyone pulling: a version tag names the midgie release, not one immutable build of it — pin the digest if you need the same bytes every time.

### Helm chart

`charts/midgie` puts that image in a namespace as a workbench you exec into, with a PersistentVolumeClaim for git checkouts so a clone survives restarts and image bumps. Every release attaches the packaged chart, versioned with the release, so it installs without a checkout:

```bash
gh release download v0.18.0 -R IS4IT/midgie -p 'midgie-*.tgz'

helm install midgie ./midgie-0.18.0.tgz -n midgie --create-namespace \
  --set midpoint.url=https://mp.example/midpoint --set midpoint.user=administrator

kubectl -n midgie exec -it deploy/midgie -- bash
```

The download is a separate step because **this repository is private**: `helm install <release URL>` cannot authenticate and gets a 404. Once it is public, the URL can be passed to `helm install` directly. From a checkout it is `helm install midgie charts/midgie -n midgie …` instead.

Inside the pod, `/work` is the volume and `midgie` is on `PATH`:

```bash
git clone https://git.example/config.git /work/config    # full history — a shallow clone disables removal detection
midgie deploy /work/config --dry-run                      # --url/--user come from the environment
```

It is a Deployment of exactly one pod (a `ReadWriteOnce` claim cannot be shared, and this is a workbench, not a service) with `strategy: Recreate` — under `RollingUpdate` the replacement pod would wait for a claim the old one still holds and never start. The image's entrypoint is `midgie` itself, so the pod is kept alive by `command: ["sleep"], args: ["infinity"]`; point those at `midgie` and its subcommand to make the pod do a job instead, though a real job belongs in a `Job`, since a Deployment restarts a pod that has completed.

Everything is in [`charts/midgie/values.yaml`](charts/midgie/values.yaml), with the settings worth knowing:

| value | default | |
|---|---|---|
| `persistence.size` / `.storageClass` / `.mountPath` | `5Gi`, cluster default, `/work` | `persistence.existingClaim` mounts a claim you made yourself |
| `persistence.keepOnUninstall` | `true` | the claim is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` does not throw away work in progress |
| `midpoint.url` / `.user` | unset | become `MIDPOINT_URL` / `MIDPOINT_USER`, so commands in the pod need no `--url` |
| `midpoint.existingSecret` / `.passwordKey` | unset, `password` | `MIDPOINT_PASSWORD` from a Secret you control. `midpoint.password` also works but lands in the release values — fine for a lab, not for a real server |
| `ssh.*` | off | mounts a key Secret into `$HOME/.ssh`, each entry under the name it has in the Secret, and sets `GIT_SSH_COMMAND` to point ssh at it — ssh auto-loads only its own default filenames, so a `deploy-key` would otherwise be mounted and never offered. `ssh.acceptNewHostKeys` adds trust-on-first-use for when you have no `known_hosts` |
| `extraEnv` | `[]` | plain `name`/`value` pairs on the container. `HTTPS_PROXY`/`NO_PROXY` belong here (git and midgie both read them — see [Behind a proxy](#behind-a-proxy)), as does `JAVA_OPTS` for heap or `-D` settings |

The SSH Secret is yours to create — no private key goes through values:

```bash
ssh-keygen -t ed25519 -N '' -C midgie@cluster -f ./deploy-key    # no passphrase: nothing in the pod can answer a prompt
ssh-keyscan github.com > ./known_hosts

kubectl -n <namespace> create secret generic git-ssh \
  --from-file=deploy-key=./deploy-key --from-file=known_hosts=./known_hosts

helm upgrade --install midgie charts/midgie -n <namespace> \
  --set ssh.enabled=true --set ssh.existingSecret=git-ssh --set ssh.privateKeyKey=deploy-key
```

Register `deploy-key.pub` as a **read-only** deploy key on the repository — midgie only clones and reads. Two details: you ask for mode `0400` but Kubernetes lands the file at `0440 root:<fsGroup>`, so the pod can read it and ssh still accepts it (its "unprotected private key" check only fires for a file owned by the calling UID); and a rotated key needs no restart, since Kubernetes refreshes mounted Secrets in place and ssh re-reads the file per invocation.

Reinstalling under the **same** release name re-adopts a kept claim, so the checkouts come back. Under a different name it will not — pass `persistence.existingClaim` to pick the old one up.

**On OpenShift, add `-f charts/midgie/values-openshift.yaml`** — or, installing from the packaged chart where that path does not exist, `--set podSecurityContext.runAsUser=null --set podSecurityContext.runAsGroup=null --set podSecurityContext.fsGroup=null`, which is exactly what the preset contains. The `restricted-v2` SCC gives each namespace its own UID range and requires `fsGroup` to come from it, so the chart's defaults (`runAsUser: 1001`, `fsGroup: 1001`) are rejected before the pod is ever scheduled — `1001 is not an allowed group … must be in the ranges: [1004170000, 1004179999]`. The preset nulls those three fields so the SCC assigns its own, and keeps the four things `restricted-v2` does want: `runAsNonRoot`, `RuntimeDefault` seccomp, no privilege escalation, all capabilities dropped. Nothing is lost — the image puts its user in group 0 and leaves `$HOME` group-writable (`770 1001:0`), which is exactly what an arbitrary assigned UID needs.

One thing to watch there: `/work` is then writable only because OpenShift injects an `fsGroup` and the CSI driver chowns the volume to it. Storage that ignores `fsGroup` — NFS-backed classes often do — leaves the claim root-owned and the pod unable to write it. If that happens, read the range the namespace was given and pin a value from it rather than an invented one:

```bash
kubectl get ns <namespace> -o jsonpath='{.metadata.annotations.openshift\.io/sa\.scc\.supplemental-groups}{"\n"}'
helm upgrade midgie charts/midgie -n <namespace> -f charts/midgie/values-openshift.yaml --set podSecurityContext.fsGroup=<from that range>
```

## Commands

`midgie` has four subcommands. **`diff`** shows the change for a **single object** and never writes; **`deploy`** reconciles any number of **files and folders** — one file is a tree of one; **`rollback`** puts back what the last `deploy` changed; **`init`** holds the one-off set-up steps — `--create-state` enables removal detection for a server, `--write-cacert` turns a server's CA into a trust file, `--write-properties` writes a config template, `--write-policy` seeds a policy file for a tree (see below). `diff` and `deploy` read desired-state XML and run the same local diff engine — the current state comes from a local file (offline) or a running server (`--url`); `rollback` replays a saved plan and reads nothing from the tree.

Every `init` step is opt-in, because each one writes something: `init` on its own prints its help and exits 2 rather than picking a step for you.

> Each object is compared with **itself**, not another object: its *desired* state (how you want it) against its *current* state (how it is now), and the delta transforms current → desired. If both sides carry an OID and the OIDs differ, the run is refused.

In live mode each object is addressed by the OID in its file (or `--oid`, when the run holds exactly one object); if it has none it is resolved on the server by type + name (a single match). That is the default, since a tree whose files carry no OIDs is the ordinary way to write one; **`--no-match-by-name`** opts out: an OID-less object that already exists is then skipped rather than patched by name. An ambiguous name — two live objects sharing it — is always an error rather than a guess.

### `diff` — the minimal delta

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

### `deploy` — reconcile files and folders

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

#### The rollback plan (always on)

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

On a [tracked](#detecting-what-you-removed-opt-in) tree the plan also records the `midgie-state` rows the deploy moved, and `rollback` moves them back for every object it restored — only where a row still holds what the deploy wrote, so a later run's rows are left alone. Without that, the rows kept saying the rolled-back revision was applied, and deploying it again kept the values it was meant to remove beside the new ones.

Plans go to `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/` by default, keeping the newest five runs; rotation removes only run directories midgie wrote, never anything else under the base path. `--rollback-plan DIR` chooses somewhere else — necessary in a Kubernetes Job, whose filesystem dies with the pod that needed the net, so point it at a mounted volume. `--no-rollback-plan` suppresses it.

> There is no `--auto-rollback`. midPoint has no cross-object transaction, so unwinding a partly-applied tree can land you in a third state that is neither old nor new — and the failure that would trigger it is often the one that prevents it. The plan sitting on disk is what makes a considered rollback cheap.

#### Detecting what you removed (opt-in)

Dropping a value from a file is invisible to an ordinary diff: `desired` simply stops mentioning it, and under `multivalue=merge` the delta comes out empty, so `diff` finds nothing to change while the value lives on. midgie closes that with a **three-way** comparison — desired, live, and *last-applied* — the merge `kubectl apply` performs, but with last-applied tracked in git rather than as a copy of every object stored on the object itself. A value present in last-applied but absent now is one you removed on purpose; a value the server holds that midgie never applied is accrued state and is left alone.

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

**A removal travels in its object's own PATCH**, next to the object's other changes — one request, one confirmation, one entry in the plan and in the rollback plan. So midPoint applies the change and the removal together: an `inducement` retargeted under `multivalue=merge` is added and its old form deleted in the same request, and the role never holds both, not even between two requests — which a recompute could otherwise have provisioned from. `--plan` writes the removals into each object's plan file, and `--dry-run --format xml` shows them in the one PATCH body. It is structurally safe: a removal is built solely from a value that was in last-applied, and it is sent only while the server still holds that value exactly as it was deployed — one someone has since changed or removed is left alone with a note. A value the object's diff already takes care of (an entry edited in place, or deleted anyway under `replace`) is not deleted a second time; that is judged against the live object with the diff applied locally, so no second round trip is needed. The server's own copy of a container value is what gets deleted, id included, rather than a lookup by content.

**Removals are item by item inside a value your file still declares.** A `<mapping>` (or `<panel>`, or any value of a list with a filled-in natural key) that only lost its `lifecycleState` is not reported as "the whole old mapping was removed" — which, with the rest of the mapping untouched on the server, would delete the entire mapping. The old and new file are paired the way the diff pairs values, by container id or natural key, so the removal is `mapping[name=Given name]/lifecycleState`, and it deletes that item from the server's copy of the mapping, which keeps its id. A mapping dropped from the file entirely, or an edited list value without a key (an `inducement`), is still removed as a whole value. A nested item the server changed after the deploy is left alone: `leaving mapping[name=Given name]/lifecycleState alone — the server holds a different value than the one removed from template.xml (changed on the server after it was deployed)`. `--no-prune` withholds these removals like any other.

Deleting a *fragment* is handled the same way and needs no special case: both sides are compared as whole objects — every file describing the object now against every file that described it then, deleted ones included — so you get exactly the values no surviving file still declares, a value merely moved from one file to another is not a removal at all, and the object itself is never a deletion candidate. Each reported value still names the file it came from.

Rows are keyed by the repository's **root commit** plus the file's path inside it (e.g. `de5ad41d2b9b/poc/roles/manager.xml`). The root commit is part of the history, so every clone agrees on it no matter which remotes it has — which matters when the same repo is pushed to both a customer's git and your own. Detection is skipped, with a note, when the tree is not in a git repository or the clone is **shallow** (a `--depth 1` checkout cannot recover previous content; fetch full history, e.g. `fetch-depth: 0`). `--app NAME` replaces the root commit in the keys, for setups where that derivation does not fit.

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

**Moves and partial deploys.** An object's last-applied is the combination of its files, each as its own row records it. A file that no longer exists anywhere still counts — the old path of a renamed file — so renaming a file and dropping a value in the same commit is applied correctly even when you deploy only the new path. A file that still exists *outside* what you deploy counts on neither side: deploying `roles/` when the role has a second file in `extra/` applies only that part, says so (`… also has 1 file(s) outside the deployed path`), and never reads the other file's values as removed. An object that left the deployed files is reported as gone — or, when a row shows where it lives now, as moved there.

Rows written by earlier versions recorded a whole tree at one commit. A file without a row of its own is read once at the commit of the nearest such row above it; the old row is removed when a deploy covers its path.

`init --write-cacert FILE` fetches the certificate chain the server presents — `openssl s_client -showcerts`, without the openssl — shows each certificate with its SHA-256 fingerprint, and on confirmation writes the **issuing CA(s)** as the PEM `--cacert` reads:

```bash
bin/midgie init --write-cacert corp-ca.pem --url https://mp.example/midpoint
```

It needs no account: you cannot authenticate to a server you do not trust yet. The leaf is deliberately left out — trusting it would pin one certificate and break at its next renewal — so what lands in the file is what keeps working. A server that sends only its own certificate, not self-signed, leaves nothing else to take; that is written with a warning saying so, and the better fix is the issuing CA's certificate from its owner. **The confirmation is the security**: fetching a certificate means trusting whatever answered the connection, so check the fingerprints against what the CA's owner publishes before saying yes. A non-interactive run refuses rather than assuming (`--yes` overrides, for when the fingerprints are already known).

`init --write-properties FILE` writes an annotated [config file](#config-file--midgieproperties) template — every option that can carry a default, commented out, with its help text, generated from the command model so it cannot describe a version of midgie that no longer exists. It needs no server at all, since it writes the file that holds the URL:

```bash
bin/midgie init --write-properties ~/.midgie.properties
```

`init` also seeds a starter policy for a tree — a separate step, so it neither needs nor implies `--create-state`:

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

With a `PATH`, `init --create-state` also reports the identity the tree will be tracked under — pass the same `--app` as deploy, if deploy uses one. One `init` run may take several steps; every refusal any of them would meet is checked before the first one writes anything, and only one of the files written may go to stdout, which then holds that file alone.

The state object describes and protects itself: it is `indestructible` (midPoint refuses to delete it — clear the flag first if you really mean to), carries a `description`/`documentation` explaining it to an admin who has never heard of midgie, and is marked `subtype=midgie`. To switch tracking off without deleting anything, set its `lifecycleState` to `archived`. Losing it is harmless — detection simply stops until `init --create-state` recreates it; midgie never deletes anything as a result.

The order is two-tier: extension **schemas** first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then **objects** (sorted by their reference OIDs). Objects are identified by OID, or by type+name when they carry no OID, so several files may declare *fragments* of one object — and those files are **combined into one desired object**, which is the apply unit. Two files each contributing a `panel`, an `inducement` or an attribute definition therefore compose: entries of a multi-valued container are paired by container id or by the schema's natural key (so two files describing `objectDetailsPage[type=UserType]` yield one page with both their panels), and anything that cannot be paired is appended, with identical values collapsed. A genuine contradiction between fragments (a different type under one OID, or a single-valued item set to two values) is an error; same-object non-overlapping fragments are fine. The error names **both** files, and where the clashing values run to several lines it shows a diff of them rather than printing both in full — with a long `description` or expression, the one clause that differs is otherwise buried in two near-identical blocks.

With `--url`, each unit is reconciled in that order — look up the current object by a search (so a missing object doesn't 404), diff the combined object (`--scope present`), then `PATCH`, or `POST` to create it if it is not there (an OID-less object is reconciled by type+name); `--no-create-if-absent` and `--no-match-by-name` opt out of either half. One request per object, so a create is atomic rather than a partial `POST` patched into shape afterwards. It is sequential, so a role is created before the user that references it, and re-running is idempotent. Two extension schemas declaring one namespace, two units with one OID (a schema and an object included), and a file without an OID naming a type and name that two objects of the tree share are refused before anything is read from the server. Writing is gated: a single confirmation prompt by default (`--yes` to bypass, `--confirm-each-apply` to prompt per change, `--dry-run` to preview without writing; a non-interactive session without `--yes` refuses). What is confirmed is what is sent: an object whose change is different by the time it is written — someone edited it on the server while the prompt waited — is not sent and fails the run, and a run whose preview finds nothing to change records its state without asking. Exit codes: offline `0`/`2`; apply `0` (all created/patched/in-sync/skipped or declined) / `2` (any unit failed); `--dry-run` `0` in-sync / `1` would change something / `2` error.

**Reading the output.** Each object gets one block: its result alone in the first column, and everything about it in the second — the file and the object, the change, then the warnings and notes about that object:

```
  would-patch   roles/role-b.xml  RoleType/Role B
                2 change(s): replace description; add subtype
                note: left alone by the policy: documentation (apply=on-create)
  in-sync       roles/role-a.xml  RoleType/Role A
```

A block is printed whole, on one stream — stdout, or stderr for a failed object or when a payload goes to stdout — so it stays together in a terminal and in a CI log. `--quiet` leaves notes out; notes about the run as a whole (tracking, the policy file) stay on stderr as `midgie: note: …`. In a terminal the second column is word-wrapped to the window, continuation lines indented a little further; a resize applies from the next block on. Anywhere else — a CI job, a pipe, a file — nothing is wrapped, so a log viewer wraps lines itself and a message stays on one line for searching; set `COLUMNS` to wrap there too. `rollback` uses the same first column.

Three more flags round it out: **`--retry`** re-applies the failed units until they stabilise (catches references the static OID graph can't order — by name, filter, or expression); **`--plan <dir>`** writes the ordered changes as numbered `create`/`patch` files for review or GitOps, and **`--apply-plan <dir>`** replays them verbatim, minus comments — leaving a rollback plan like any deploy, and, on a tracked tree, recording the tracking rows the plan computed so the next plan detects what this one's tree dropped; **`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container. The selector is a single glob over `Type/Name/item…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level, and the most specific one wins; an `oid:` rule pins exactly one object and outranks any pattern. Note that the object identity is *two* segments, so "every role" is `RoleType/*`, and an item rule scoped to a type is one glob spanning both: `RoleType/*/assignment`. The object XML stays pure. Full design in [`DESIGN.md`](DESIGN.md#deploy--apply-a-folder-tree).

Two details worth knowing when an object name is awkward: quote the selector if the name contains `=` (`"OrgType/ou=people"  scope=full`), and escape a `/` inside a name (`RoleType/App \/ Admin`), since the selector is itself a `/`-separated path. `init --write-policy` writes both forms itself. `deploy` names the policy file it uses at the start of the run — `midgie: policy: /work/repo/midgie.policy (3 rule(s), plus 9 built-in)` — which matters most when it came from a [config file](#a-midgieproperties-in-the-tree) rather than the command line; and it names any policy line that matched no object, so a typo is not mistaken for a rule that ran.

A wildcard is always a *whole* segment: there is no matching inside a segment (`RoleType/App-*` is not "roles starting with App-"), and one selector is one glob (`RoleType/* **/assignment` is not shorthand for two rules). Both are refused when the file loads, with the line number — as written they used to read as a literal segment no name can equal, so the rule quietly matched nothing and the objects it was meant for kept the CLI strategy. Write `\*` for a name that really contains an asterisk.

Some items belong in the file only so the object is **created** right, and are owned by midPoint or its operators from then on. A task's `executionState` is the everyday case: deploy it suspended, and a redeploy must not flip it back after someone resumed it. **`apply=on-create`** says exactly that:

```
TaskType/*/executionState  apply=on-create
```

The item goes into the POST that creates the object and is left alone by every later PATCH: not changed, not deleted under `--scope full`, and not removed when it drops out of the file. A rule covers everything beneath its path, and when a new container is added to an existing object (a new `activation`, say) an `on-create` item inside it is stripped from what is sent. A change that would take the item away with its container is refused for that object: deleting a single-valued container it sits in (an `activation` the file leaves out under `--scope full`), or replacing a list entry whole — which is how a list compared by content changes an entry whose copy in the file carries no container id. Removing an entry the file no longer declares at all still removes it whole. `--dry-run` and `--plan` name each difference held back, so an object shown `in-sync` while its state plainly differs is not a mystery. `apply` is per-item only; `always` is the default, and a more specific `apply=always` rule carves an exception out of a broader one. An object matched by name that someone else created counts as existing — midgie never writes the item there.

#### Built-in rules

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

**`apply=if-absent`** is the gentler sibling, midPoint's *weak* mapping strength: the item is written whenever the server holds **no value** for it and never overwritten or removed once it does — not by a PATCH, not under `--scope full`, not when it drops out of the file. It differs from `on-create` only once someone clears the item: `if-absent` fills it in again on the next deploy, `on-create` leaves it empty. It is judged per item, so a rule on a container fills in each missing child and leaves the present ones alone, and a newly added container carries its `if-absent` items along (nothing can be there yet). Held-back differences are named with their rule in the `--dry-run`/`--plan` note, e.g. `left alone by the policy: description (apply=if-absent)`.

## Exit codes

The contract lets CI gate on drift without parsing output:

| command | `0` | `1` | `2` |
|---|---|---|---|
| `diff` | no differences | differences found (drift), or the object would be created | error, or the object was skipped |
| `deploy --dry-run` / `--plan` | nothing to change | would change something | error |
| `deploy` (real apply) | all applied/in-sync/skipped/declined | — | a unit failed, or error |
| `deploy` (offline, no `--url`) | printed the apply order | — | error |
| `rollback` | restored, nothing to roll back, or declined | `--dry-run`: something would be restored | error, a refused plan (wrong server, stale), or a failed restore |
| `init` | the step ran (created, already present, or written), or declined at the prompt | — | error, or no step asked for |

## Flags

### Diff strategy

| flag | default | meaning |
|---|---|---|
| `--scope present\|full` | `present` | *present*: only diff items present in `DESIRED` (unmanaged items are left alone → accrued state is preserved). *full*: full-replace (also removes items missing from desired). |
| `--multivalue auto\|merge\|replace` | `auto` | Multi-valued container handling. *replace*: desired values are authoritative (live-only values removed). *merge*: add desired values, keep live-only ones. *auto*: merge when deploy tracks the tree (it then removes what the tree dropped itself), else replace. |
| `--path <itemPath>=merge\|replace` | — | Per-path override of `--multivalue`. Repeatable (e.g. `--path assignment=merge`). |
| `--equivalence <strategy>` | `auto` | What counts as "the same value". `auto` decides per container (see below); the rest, loosest to strictest, are `real-value` · `consider-different-ids` · `natural-keys` (prism-experimental) · `ignore-metadata` · `data` · `literal`. See [`DESIGN.md`](DESIGN.md#strategy-flags). |
| `--smart-refs accept\|verify\|resolve` | `accept` | How a reference the file writes as a **search filter** is judged against the OID midPoint resolved it to. See below. |
| `--allow-inplace-merge` | off | Proceed even when a merge would edit an **accrued (live-only)** value in place (can happen under `natural-keys`). Off by default: such a run is refused so accrued state is not silently overwritten. |

`merge` is designed for `real-value`. Under an id/natural-key-considering strategy prism can pair a
live-only value with a desired one and emit a deep in-place edit that merge cannot preserve; that is
detected per delta and **refused by default** (use `--allow-inplace-merge` to proceed, or `real-value` for
a clean union). An edit inside a value that desired *declares* — matched by container id, or by the schema's
natural key — is a managed update and is not refused (see below).

#### Choosing a strategy

**Equivalence needs no choosing.** The default `--equivalence auto` decides per multi-valued container: where the schema declares a natural key and every value in your file fills it in, entries are matched by that key and updated **in place**, keeping their container ids and the metadata hanging off them; everywhere else it falls back to `real-value`, which is what midgie did before `auto` existed. The one thing you can do to get more out of it is fill in the keys — a `<panel>` with its `<identifier>`, a `schemaHandling/objectType` with both its `<kind>` and `<intent>` — and midgie's input warnings tell you when a value is missing one. A key left empty *inside* an entry costs less: with one `<addFocus/>` without its `<name>` in an object type's synchronization reaction, the object types are still paired by `kind` + `intent` and edited in place, and only the `addFocus` actions are compared by content — a change to one replaces that action whole. Run with `-v` to see which lists were compared that way and the key that decided it: `note: ResourceType/HR: schemaHandling/objectType/synchronization/reaction/actions/addFocus does not fill in its key (name), so its entries are compared as whole values: … schemaHandling/objectType itself is still paired by its key`.

**Container ids from another server are recognised.** A list entry that carries an `id` is paired with the server's entry of the same id, whatever the strategy — right for a file exported from the server you deploy to, and wrong for one exported elsewhere, where the same number can name an entry someone added on this server. Before diffing, midgie checks each list's ids against the server's: a matching id whose filled-in natural key differs, an entry the server already holds under another id, or a server entry another file entry declares, all mean the numbering came from somewhere else, and that list's entries are then paired by key or content instead (`-v` says so per list). midPoint never reuses a container id, so none of these happens with a file exported from the same server. One case cannot be told apart: a single edited entry whose foreign id happens to be taken here, with no other entry in the list to show the mismatch. Exporting without ids, or filling in natural keys, avoids it.

Setting `--equivalence natural-keys` by hand is almost always worse than leaving it on `auto`: prism takes one equivalence for a whole-object diff, so it applies to *every* container of that object — including the many types that declare a key real config never fills in (an `assignment` rarely carries an `<identifier>`), where prism then pairs unrelated entries positionally and edits one into the other. `auto` avoids that by diffing the object under both strategies and keeping each change from the pass its path belongs to. The other strategies are for inspection, not reconciliation: `data` and `literal` compare operational data and metadata, so against a live server they never report "no differences" at all.

**Multi-value handling mostly needs no choosing either.** The default `--multivalue auto` depends on whether the tree is **tracked** (in a full git clone, on a server with `init --create-state`):

- **Tracked: `merge`.** Values a list holds on the server beyond your files — entries midPoint ships, an admin added, another tree owns — stay. What *your* tree dropped is still removed, because the three-way comparison against the last deployed revision finds it and each object's PATCH carries the delete; an entry edited without a natural key is added and its old form deleted in that same request. So is an entry whose key is filled in but whose values were compared by content: a live value that carries the container id or natural key of an entry your file declares is that entry's old form, not someone else's, and merge deletes it rather than keep it beside the new one. So merge's two weaknesses on its own — it can express neither a removal nor such an edit — do not apply. What merge keeps is named per object (`keeps live value(s) the tree does not list: inducement ×1 (multivalue=merge; …)`), so an entry someone added to a list the tree means to own does not go unseen; a `<path>  multivalue=replace` policy rule makes the tree own that list.
- **Untracked: `replace`.** Without the history, merge could express neither a removal nor an edit (it would keep `[alpha, beta]` for live `[alpha]` and desired `[beta]`), so the tree is authoritative for every list it declares, and deploy says so in a note. A `multivalue=merge` rule, or `init --write-policy`'s suggestion, keeps a list open where the server holds more.

**Inside a value the tree declares, a tracked tree owns only what it deployed.** Merge and replace decide which values of a list survive; they say nothing about what is *inside* a matched value — one `<mapping>` of an object template paired by its `name`, or a single-valued container such as `archetypePolicy/display`. A two-way diff deletes whatever the server's copy holds there beyond your file, whoever put it there: an admin who set `lifecycleState` on ten mappings would see ten deletes. On a tracked tree with `--scope present` (the default) midgie compares three ways instead, for every item below the top level, at any depth:

| your file now | last deployed | server | result |
|---|---|---|---|
| value *v* | anything | anything | set to *v* (replaced or added) |
| absent | absent | *w* | **kept**, and named in the "keeps live value(s)" note |
| absent | *v* | *v* | removed — your tree dropped it |
| absent | *v* | *w* ≠ *v* | **kept**, with a note that it was changed on the server after the deploy |

That is the rule list values already follow — only remove what midgie deployed, in the form it deployed it — carried to every depth. The first deploy of a tracked tree has nothing to compare against and removes nothing below the top level. An untracked tree, an offline `diff`, and `--scope full` keep the two-way behaviour: the file is authoritative for everything inside the values it declares.

`--multivalue merge|replace` on the command line (or in the policy, per object or path) overrides either. `--no-prune` withholds the removals but leaves the default alone: flipping the run to replace because of it would delete more, not less. Note that *your own* tree splitting a list across several files needs neither: `deploy` combines the files describing one object before diffing. `diff` reads the same history on a live run, so it shows the same change deploy would send; an offline `diff` of two files has none, and uses replace.

#### Syncing an item nested inside a multi-valued container

To manage **one entry** deep inside multi-valued containers — say a single GUI panel at
`adminGuiConfiguration/objectDetails/objectDetailsPage[type=UserType]/panel[identifier=my-panel]` — combine
`multivalue=merge` with the natural-key matching `--equivalence auto` already gives you:

```
# midgie.policy
SystemConfigurationType/SystemConfiguration/**/panel  multivalue=merge
```

Earlier versions needed an `equivalence=natural-keys` line here as well. They no longer do, and adding one
is a step backwards — it would widen key matching to every container of that object, `assignment` included.
What the recipe does still require is that your file fills the keys in: `<type>` on the `objectDetailsPage`
and `<identifier>` on the `panel`, or `auto` will decline to match them and warn that it did.

Scoped to the container that needs it rather than to the whole object — the same shape
`init --write-policy` generates. On a tracked tree merge is already the default and the line is not needed.

The midPoint schema declares an `a:naturalKey` for these containers (`panel` → `identifier`,
`objectDetailsPage` → `type`, and ~100 more), so prism matches your id-less fragment against the live values
by key and emits a single deep delta — e.g. `replace …/objectDetailsPage[84]/panel[284]/displayOrder` —
leaving sibling entries untouched — and, on a tracked tree, the items inside the panel that your file does not mention (see [Choosing a strategy](#choosing-a-strategy)). Your fragment only needs the key items plus what
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

A partial fragment can destroy live state, so midgie reports the danger before anything is written. These are **advisories, not blocks** — the run proceeds; use `--dry-run` to inspect first, and `--quiet` to silence them.

- **Blast radius.** Every summary line states what it removes (`delete …/objectDetailsPage (removes 2 values)`), and a removing delta prints `WARNING: this change REMOVES n values from the live object` with the paths. A removed *container* value takes everything nested in it, so this is the number that matters.
- **A value missing its natural key.** `'…/panel' value #1 does not carry its natural key (identifier), so it cannot be matched to a live value` — it would be applied as a whole-value change instead of updating the matching entry.
- **A partially declared container.** `'…/panel': desired matches 1 of 4 live value(s), and this path is on multivalue=replace, so the other 3 will be DELETED. Set '…/panel multivalue=merge' to update just the entries you list.` When the key is not filled in on every value it adds that, since without it the entries can only be matched by content.

These stay quiet unless they mean something: only containers with a schema-declared natural key are checked, the key must actually be *in use* by the live values (plenty of types declare one that real config never fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value that desired restates verbatim counts as declared. The key-matched merge recipe above produces no warnings at all.

**One check does block: a natural key held twice.** A change that would leave a keyed list with more values sharing one filled-in key than the server holds now is refused for that object (exit 2), in `deploy`, a live `diff` and an offline one alike: `refusing a change that would leave schemaHandling/objectType[kind=GENERIC, intent=task] ×2 — values sharing one natural key, which is meant to name a single entry …`. The key names one entry, so two values carrying it mean the tree declares the entry twice, or an entry was added beside its live copy instead of replacing it — and midPoint rejects some of these outright: a resource with two object types of one kind and intent no longer loads its schema. A duplicate the server already holds does not block a change; it is only never added to.

### Connection (live modes)

| flag | meaning |
|---|---|
| `--url URL` | midPoint base URL (or `MIDPOINT_URL`), e.g. `https://mp.example/midpoint`. A trailing `/ws/rest` is accepted. |
| `--user USER` | REST user (or `MIDPOINT_USER`). |
| `--password`, `--password-env VAR`, `--password-file FILE` | Password sources; resolved in the order **`--password` → `--password-env` → `--password-file` → `MIDPOINT_PASSWORD` → interactive prompt**. Prefer anything but `--password` (it is visible in `ps`/shell history). |
| `--oid OID` | *(diff, deploy)* Object to fetch/patch when the run holds exactly one object. Defaults to the OID in its file. |
| `--cacert FILE` | Verify the server's TLS certificate against this CA cert (PEM). |
| `--insecure` | Trust any TLS certificate and skip hostname verification (self-signed dev servers). |
| `--proxy URL` | Forward proxy, `http://proxy.corp:3128` or `proxy.corp:3128` (or `https_proxy`/`http_proxy`). |
| `--no-proxy HOSTS` | Comma-separated hosts that bypass it (or `no_proxy`). See below — it *replaces* the loopback default. |
| `--proxy-user USER` | User for a proxy that demands Basic authentication. |
| `--proxy-password`, `--proxy-password-env VAR`, `--proxy-password-file FILE` | Its password, in the account password's order minus `MIDPOINT_PASSWORD`: a `user:pass@` in `https_proxy`/`http_proxy` takes that place, and only when `--proxy-user` is not given. |
| `--timeout SECONDS` | Per-request timeout, which also bounds connecting (default 30, at most a day). |
| `--retries N` | Retry idempotent reads on `502`/`503`/`504`/connection-refused/timeout, `N` times with backoff (writes are never auto-retried). |

#### Behind a proxy

```bash
midgie deploy config/ --url https://mp.example/midpoint \
  --proxy http://proxy.corp:3128 --proxy-user alice --proxy-password-file /run/secrets/proxy-pw
```

**Where the setting comes from**: `--proxy`, then `https_proxy`/`HTTPS_PROXY` for an https server or `http_proxy`/`HTTP_PROXY` for a plain one (either spelling; lowercase wins if both are set, as curl reads them), then the [config file](#config-file--midgieproperties) — which any of the four variables stands down, even the one for the other scheme. The two schemes are **separate settings and neither falls back to the other** — `http_proxy` alone leaves an `https://` server direct, exactly as it does for curl and git, so midgie and the tree's clone always agree on what gets proxied. The variables matter because the JVM reads none of them on its own — but the `git` inside the [container image](#container-image) does, so a pod given only `HTTPS_PROXY` used to clone its tree happily and then fail to reach midPoint. midgie now reads them itself, and the two halves behave the same.

**Set nothing and nothing changes**: with no proxy configured midgie leaves the JDK's own proxy selector alone, so `JAVA_OPTS="-Dhttps.proxyHost=… -Dhttps.proxyPort=…"` still works as it always did. That route cannot carry credentials, though — `java.net.http` never reads `http.proxyUser` — so an authenticating proxy needs `--proxy`.

**The bypass list replaces the default, it does not extend it.** With no list, `localhost`, `127.*` and `::1` go direct, which is the JDK's own default and keeps a local rig working. Give one and that exemption is gone unless you repeat it — `--no-proxy ''` deliberately sends even loopback through the proxy. Entries are curl's: `*` bypasses everything, `.corp.example` matches subdomains only, a bare `corp.example` matches the domain and everything under it, and a `:port` on an entry is accepted and ignored.

**Credentials.** `--proxy-user` with one of `--proxy-password-env` / `--proxy-password-file`, or the interactive prompt; `--proxy-password` exists and is discouraged for the reason `--password` is. A `user:pass@` inside `https_proxy` is honoured — it is how the rest of the toolchain writes it and an environment variable is not on show in `ps` — but the same thing inside `--proxy` is stripped with a warning, exactly as it is for `--url`. Reaching an **https** server through a proxy means a `CONNECT` tunnel, and the JDK refuses to authenticate one unless told otherwise; midgie clears that restriction at startup, but only for a run that actually configured credentials.

**Limits worth knowing.** HTTP proxies only: `socks5://` and an `https://` proxy (TLS to the proxy itself) are refused with a message saying so, because `java.net.http` supports neither. A proxy that **intercepts** TLS is not a proxy problem but a trust one — it presents its own certificate, so point `--cacert` at its CA, and `init --write-cacert` fetches exactly that chain through the tunnel.

### Schema (extension items)

| flag | meaning |
|---|---|
| `--schema FILE` | Extension schema: `.xsd` raw XSD or `.xml` exported `SchemaType`. Repeatable. |
| `--schema-dir DIR` | Directory of extension schemas (`.xsd`/`.xml`); other files ignored. |
| `--no-live-schema` | Skip the live `GET /schemas` read (faster, or when the account lacks schema-read). Extension items then need `--schema`/`--schema-dir` or they diff as raw. |

### Output, logging & writes

| flag | meaning |
|---|---|
| `--format xml\|json\|summary` | *(diff; deploy with `--dry-run` and one object)* `xml` (diff's default) / `json`: the `ObjectModificationType`. `summary`: a human-readable changed-path list. For an object that does not exist yet every format gives the `POST` body, as XML. Under `deploy` the plan rows move to stderr. |
| `-o, --output FILE` | *(diff, deploy)* Write the `--format` output to `FILE` instead of stdout. |
| `-q, --quiet` | Suppress advisory notes/status; keep the delta payload, errors, and exit code. |
| `-v, --debug` | Follow what midgie is doing on stderr: one line per step (mode, connection, config files, schema registration, each REST call) and per object-level decision (the diff summary, the reconcile outcome, which policy rule applied). midgie's own loggers only — the prism/framework logs stay quiet. Also a `note:` per object for each list `--equivalence auto` compared as whole values rather than by natural key, naming the key inside it that is not filled in. |
| `--trace` | Everything `--debug` shows plus the detail behind it: per-file and per-value decisions, stack traces, full REST request/response bodies, and the framework logs. The content of `clearValue`, `cipherValue` and `digestValue` is shown as `***`; other values a file keeps in plain text are not recognisable as secrets and **may still show**, so treat a trace log as sensitive. |
| `--no-create-if-absent` | *(deploy)* Fail on an object that does not exist instead of creating it (and skip an OID-less one). Creating is the default. |
| `--no-match-by-name` | *(diff, deploy)* Do not patch an OID-less object found by type + name — skip it instead. Resolving it by name (a single match) is the default. |
| `--dry-run` | *(deploy, rollback)* Compute and print the change(s) but do not send them; exits `1` if anything would change. |
| `-y, --yes` | *(deploy, rollback)* Apply without the interactive confirmation prompt (required non-interactively). *(init)* Answer the `--write-cacert` trust prompt. |

`deploy` adds tree-level flags — `--retry`, `--plan <dir>` / `--apply-plan <dir>`, `--rollback-plan <dir>` / `--no-rollback-plan`, `--confirm-each-apply` and `--app <name>` — and shares `--policy <file>` and `--no-prune` with a live `diff`; all are described in the `deploy` section above.

## Config file — `midgie.properties`

The settings that never change for an environment — the server, the account, the CA certificate that makes TLS verify instead of needing `--insecure` — can live in a properties file instead of on every command line:

```properties
# midgie.properties
url    = https://mp.example/midpoint
user   = deployer
cacert = /etc/ssl/corp-ca.pem
scope  = present

# qualified with a command: only 'deploy' gets it
midgie.deploy.retry = true
```

```bash
midgie init --create-state          # no --url, --user or --cacert needed
```

**Where it comes from**: `/etc/midgie/midgie.properties`, then `$HOME/.midgie.properties`, then a file named by `MIDGIE_CONF` or the `midgie.conf` system property (`JAVA_OPTS=-Dmidgie.conf=…`). All of them are read and **merged per key**, later layers winning — so a site file can hold what is true of the machine and a user file add only what differs, without either repeating the other:

```properties
# /etc/midgie/midgie.properties — the machine
cacert = /etc/ssl/corp-ca.pem
retries = 3

# ~/.midgie.properties — you
url  = https://mp-test.example/midpoint
user = deployer
```

Naming a file explicitly is the most deliberate of the three, so it sits on top; and because it was named on purpose, a path that does not exist is an error rather than a quiet skip. In a pod, `/etc/midgie/midgie.properties` is a ConfigMap mount and `$HOME/.midgie.properties` is yours if you [put `$HOME` on the volume](#helm-chart).

A file that holds `password` or `proxy-password` should be readable by you alone: midgie warns at the start of every run when other users can read it (`chmod 600` it, or keep the password in `password-file` or `MIDPOINT_PASSWORD` instead).

**Keys** are option names without the dashes — `url`, `cacert`, `no-create-if-absent` — applying to every subcommand that has that option, or qualified with the command to narrow them (`midgie.deploy.scope`). **Precedence**: an option on the command line wins, then the `MIDPOINT_*` environment variables, then this file, then the option's built-in default. Environment beats file deliberately: a variable describes the process this run was launched into — a pod's deployment, typically — and a file on the same machine should not quietly redirect it somewhere else. That holds for the password however a file spells it: `MIDPOINT_PASSWORD` stands aside a file's `password`, `password-env` and `password-file` alike, while `--password-env`/`--password-file` on the command line still come first.

**Paths**: a relative file or directory in a config file — `cacert = ca.pem`, `policy = midgie.policy` — is read from **the directory that file is in**, not from wherever midgie is run, the way `.editorconfig` and `.gitignore` work. So `cacert = ca.pem` in `~/.midgie.properties` means `~/ca.pem`. A path on the command line stays relative to the working directory. (Up to 0.15, a relative path in a file was read from the working directory; use an absolute path if you relied on that.)

`midgie <command> -v` names every file it read, in the order it read them, and each key a later file overrides; `--trace` adds which file every key came from. Keys and their source only, never values, since one of those keys is `password`:

```
config: 2 setting(s) from /etc/midgie/midgie.properties
config: 3 setting(s) from /home/you/.midgie.properties
config:   user from /home/you/.midgie.properties overrides /etc/midgie/midgie.properties
config: cacert <- /etc/midgie/midgie.properties        (--trace)
config: url <- /home/you/.midgie.properties            (--trace)
```

Three things the file will not do. **A key that is not an option is an error**, naming the file and the key, because a mistyped setting that silently does nothing is the failure mode this tool works hardest to avoid. **A flag that has since become the default is refused** — `create-if-absent`, `match-by-name`, `prune` — with the `no-…` key that does work now, since the old key would sit there meaning nothing. And **`yes` is refused**: it decides whether a run writes without asking, and a file on disk is not where a person confirms that — pass it on the command line. (`no-prune` may be set anywhere: it only ever deletes less.)

### A `.midgie.properties` in the tree

Some settings belong to the *deployment* rather than to the machine or the person — where its rollback plans go, which policy file it uses. midgie reads the nearest `.midgie.properties` at or above the working directory, stopping at the repository root — or, outside a git checkout (a ConfigMap-mounted tree, say), only the one in the working directory itself. A file any user may change, or one owned by another user who is not root, is not read, with a warning. It sits at the **lowest** precedence of the file layers: the tree suggests, your own files override. The file and the keys it supplied are printed at the start of the run, because a config that arrived with a checkout is the one nobody remembers is there.

That is also how a tree brings its policy file along without anyone typing `--policy`:

```properties
# .midgie.properties at the repository root; both paths are read from here,
# whichever subdirectory midgie runs in
policy        = midgie.policy
rollback-plan = .midgie/nets
```

`init --write-properties` writes the right template for wherever it lands — any path named `.midgie.properties` other than your own `~/.midgie.properties` gets the stripped, tree-local one, anything else gets the full one. Give it a directory (or nothing, meaning `$PWD`) and it picks the name midgie would actually *read* there, so it cannot write a file that is silently never loaded:

```bash
bin/midgie init --write-properties config/    # -> config/.midgie.properties, tree template
bin/midgie init --write-properties ~          # -> ~/.midgie.properties, full template
bin/midgie init --write-properties /etc/midgie  # -> midgie.properties, no dot
bin/midgie init --write-properties - | diff - ~/.midgie.properties   # what did this version add?
```

`-` writes the template to stdout and moves the status note to stderr, so the payload stays pipeable. The same directory/`-` handling applies to `--write-policy` and `--write-cacert`, though their default names (`midgie.policy`, `midgie-ca.pem`) are conventions rather than something midgie derives — `--policy` and `--cacert` accept any path.

The disallowed keys are **omitted rather than commented out** — uncommenting a line in a midgie template must always yield a file midgie accepts, and a commented-out `url` would break that and read as an invitation. The header names them and says where they belong instead.

Because it travels with somebody else's repository, this layer is **allowlisted** rather than denylisted — cloning an unfamiliar repo and running even a read would otherwise authenticate to whatever server its file names. It may set `rollback-plan`, `policy`, `scope`, `multivalue`, `equivalence`, `path`, `app`, `schema`, `schema-dir`, `no-match-by-name`, `no-create-if-absent`, `retry`, `smart-refs`, `allow-inplace-merge`, `no-live-schema` and `no-prune`. Anything else is an error naming the file — `url`, `user`, `password*`, `insecure`, `cacert` and `proxy*`/`no-proxy` decide where credentials go, and `no-rollback-plan` would let a tree switch off its own safety net. A file may say *where* something goes, never *whether* a safety gate applies.

A CA certificate is the case worth spelling out. With `cacert` in the file, TLS verifies properly for every command with nothing extra typed, so `--insecure` stops being the path of least resistance.

## Environment variables

| var | equivalent of |
|---|---|
| `MIDPOINT_URL` | `--url` |
| `MIDPOINT_USER` | `--user` |
| `MIDPOINT_PASSWORD` | password (after the `--password*` flags on the command line, before the prompt; outranks any password setting in a config file) |
| `MIDGIE_CONF` | path to the [config file](#config-file--midgieproperties) (also `-Dmidgie.conf`) |
| `MIDGIE_JAR` | JAR path used by the `bin/midgie` launcher |
| `XDG_STATE_HOME` | base of the default [rollback plan](#the-rollback-plan-always-on) directory (`$XDG_STATE_HOME/midgie/…`, else `~/.local/state/midgie/…`) |
| `https_proxy` / `HTTPS_PROXY` | `--proxy`, for an `https://` server. A `user:pass@` in it is used. See [Behind a proxy](#behind-a-proxy) |
| `http_proxy` / `HTTP_PROXY` | the same, for a plain-`http://` server |
| `no_proxy` / `NO_PROXY` | `--no-proxy` |
| `JAVA_OPTS` | extra JVM options for the `bin/midgie` launcher — where `-D` settings go, including the JDK's own proxy properties |
| `JDK_JAVA_OPTIONS` | set to `--sun-misc-unsafe-memory-access=allow` when running the JAR directly on JDK 23+ |

## Safety guards

- **Protected/encrypted values** — `ProtectedStringType` deltas (e.g. `credentials/password/value`, or a connector's `bindPassword`, which midPoint returns without a type) are dropped from the delta and their paths reported: the ciphertext re-encrypts on every fetch, so diffing it yields spurious deltas *and* would leak the secret. Rotate credentials through midPoint's own API. Since midPoint 4.10.5 this includes the password **hint** (`credentials/password/hint`): a hint in the tree is still written when midgie creates the user, but no longer patched on an existing one. `diff` and `deploy` each name every protected value they skipped, so an ignored change does not pass for an applied one.
- **Items the server withholds** — midPoint does not return some items unless they are asked for, and says so by sending `<row incomplete="true"/>`. A `LookupTableType`'s `row` is the everyday case (a `jpegPhoto` another): read as written it means "no rows", so a lookup table would re-propose its whole contents on every run and never reach in-sync, and a row an admin had added would be in line for deletion without ever being named. midgie re-reads a live object asking for whatever came back incomplete, so the diff sees the real thing. Anything the server still will not hand over — no read authorization on it, usually — is **left out of the delta** and reported rather than diffed as empty: unknown is not absent, and a delta built on that would overwrite content midgie never saw.
- **Type/OID cross-check** — enforces the "same object" contract: the diff refuses inputs of different
  types, or inputs that both carry an OID when the OIDs differ, so you cannot accidentally diff the wrong
  object. A desired object with no OID is an unbound template and is allowed (the current object's OID
  governs).
- **Accrued in-place merge edits** — see `--allow-inplace-merge` above; refused by default.
- **Smart references** — a reference written as a search filter instead of an OID, so the file stays portable across instances where the target's OID differs (`connectorRef` is the everyday case). midPoint resolves the filter **at import** and stores the OID; your file still says "filter". Diffed literally that reads as a change, and the resulting PATCH strips the OID — and a modify does *not* re-resolve the filter, so the object is left pointing at nothing. midgie therefore treats the resolved OID as satisfying the filter and reports **in-sync** — also for a reference inside a list entry without an id, such as an inducement's `targetRef` or `construction/resourceRef` — and any PATCH it does send for such a reference, an inducement added whole included, carries `?options=reevaluateSearchFilters` so midPoint rebinds it instead. `--smart-refs verify` confirms the OID by running the filter against the server first (a mismatch is then a real change, and a search the server refuses is a change with a warning saying why); `--smart-refs resolve` always sends the file's form and lets midPoint re-resolve. Settable per object in `--policy` as `smart-refs=`.

  With several filters in one multi-valued reference, `accept` cannot tell which live OID belongs to which filter. When that matters it does not guess: under `replace` the item is sent as a replace of the file's values for midPoint to resolve; under `merge` nothing is deleted anyway, and when the server holds fewer values than there are filters, none of them is sent, with a warning — midPoint 4.10 fails a PATCH that adds a filter whose resolution the object already holds. `verify` settles both.
- **Base-schema version coupling** — the bundled `midpoint-schema` jar is the *compile-time* object model, so it must match the target midPoint's line (4.10, 4.11, …). Patch releases change the schema too — mostly by adding items, occasionally by changing a type, as 4.10.5 did for `PasswordType/hint` — so midgie is built against the **newest** patch of its line, which knows everything an older patch of that line sends. A live `deploy` and `init` read the server's version (`GET /nodes/current`; not under `--quiet`) and warn when it is on a different line, or on a **newer patch** than the bundled schema; an older patch on the same line is the safe direction and is only logged under `--debug`.

## Schema handling

Two layers (full detail in [`DESIGN.md`](DESIGN.md#schema-two-layers-two-knobs)):

- **Base** model (`UserType`, `RoleType`, … and their item definitions) is **compile-time**, from the
  `midpoint-schema` jar. It cannot be supplied as XML.
- **Extension** schema (custom `extension` attributes, `SchemaType` objects) is **runtime**: from
  `--schema`/`--schema-dir` (raw XSD or exported `SchemaType`), and in live mode read from the server
  automatically unless `--no-live-schema` is set.

## Targeting another midPoint version

The bundled base schema is version-specific. To target a different midPoint major line, bump
`<midpoint.version>` in `pom.xml` (default **4.10.5**) **and** `MP_VER` in `docker/.env`, then rebuild. A
build-time check fails if those two drift apart. `bin/midgie --version` prints the tool version (from the
git tag the build was cut from) and the bundled schema line.

## Testing

```bash
mvn -q test                 # offline unit/command tests (no server needed)
```

A Docker Compose rig under `docker/` brings up a real midPoint for the live integration tests (gated on the
rig being up), and `docker/tls/` adds an nginx TLS proxy + private CA to exercise `--cacert`/`--insecure`
end to end. See [`docker/README.md`](docker/README.md) and [`docker/tls/README.md`](docker/tls/README.md).

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` on JDK 21 and 25
(the rig-gated live tests self-skip), builds the container image for amd64 and arm64, lints the Helm chart,
and runs a [REUSE](https://reuse.software/) licence-compliance lint on every push to `main` and every pull
request against it.

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2), so a derived work must remain
EUPL-compatible — it cannot be relicensed as MIT/Apache. Per-file `SPDX-License-Identifier` headers plus a
[`REUSE.toml`](REUSE.toml) make the project [REUSE](https://reuse.software/)-compliant.
