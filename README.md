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

The build takes its version from the git tag (`git describe`), so `--version` says what the JAR was built from: `0.11.2` on a release tag, `0.11.2-1-g4c278ae-dirty` one commit past it with a modified working tree. Building outside a git checkout — a source archive, say — falls back to the placeholder version in `pom.xml`.

The `bin/midgie` launcher finds the JAR (override with `MIDGIE_JAR`) and, on **JDK 23+**,
adds `--sun-misc-unsafe-memory-access=allow` to silence the JEP 498 `sun.misc.Unsafe` warnings that
classgraph (a transitive prism dependency) prints during startup. Those warnings go straight to the native
stderr, so that launch flag is the only way to suppress them — running the JAR directly instead? Set
`JDK_JAVA_OPTIONS=--sun-misc-unsafe-memory-access=allow`.

### Container image

Each release also publishes **`is4it/midgie`** on Docker Hub, for `linux/amd64` and `linux/arm64` — the JRE, the `bin/midgie` launcher, and **git**, which midgie forks to derive a tree's deployment identity and to recover what it applied last time (without it, `deploy` still applies; only [removal detection](#detecting-what-you-removed-opt-in) goes dark). git can clone over HTTPS and SSH (`curl`, `openssh-client`, `less`), so the tree can be fetched in the container itself rather than by an init container, and `vi` is there to edit a file in place. It is a tool image: the entrypoint is `midgie`, so arguments are subcommands.

```bash
docker run --rm -u "$(id -u):0" --mount type=bind,source="$PWD",target=/work is4it/midgie:0.7.1 diff a.xml b.xml

# In a Job, command: is the entrypoint and args: the subcommand line.
#   args: ["deploy", "/work", "--url", "https://mp/midpoint", "--yes"]
```

Two things in that line are not decoration. **`-u "$(id -u):0"`**: the image runs as UID 1001 in group 0, and a bind mount ignores that entirely — `/work` keeps the *host* directory's ownership and mode, so on a Linux host a tree that is not world-readable is unreadable to the container (`cannot read /work: permission denied — midgie runs as uid 1001 …`). Run as your own UID to get past it, and keep the group at `0` rather than `$(id -g)`: `HOME` and `/work` are group-owned by 0 with `g=u`, which is what makes them writable for a UID the image has never heard of — and `deploy` needs a writable `HOME` for its [rollback plan](#the-rollback-plan-always-on). On an SELinux host (Fedora, RHEL — not SLES or Ubuntu) the label denies the mount before the UID ever matters, and the fix there is `-v "$PWD:/work:z"`: relabelling is a `-v` suffix with no `--mount` equivalent, so that one case is worth giving up the flag below for. In Kubernetes none of this applies — `runAsUser` and `fsGroup` do the same job, and the [Helm chart](#helm-chart) sets both.

**`--mount` rather than `-v`**: when the source path does not exist, or `$PWD` was not expanded because the command was pasted in single quotes, `-v` does not fail — it invents an empty directory (or a named volume) and mounts *that*. The run then reports `no midPoint objects found under /work — it holds no files at all`, which is a truthful answer to a question you did not mean to ask. `--mount` refuses instead. A host home directory on NFS/autofs can produce the same empty mount for a different reason (the daemon's mount namespace may not carry the automount); `docker run --entrypoint ls <image> -la /work` settles which of the two you have.

For a shell — to run midgie by hand against a checkout in the pod — the container needs something long-running, since the entrypoint would otherwise print help and exit:

```bash
kubectl run midgie --image=is4it/midgie:0.7.1 --restart=Never --command -- sleep infinity
kubectl exec -it midgie -- bash          # then: midgie deploy /work --url ...
docker run -d --name midgie --entrypoint sleep is4it/midgie:0.7.1 infinity   # the docker equivalent
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
gh release download v0.7.1 -R IS4IT/midgie -p 'midgie-*.tgz'

helm install midgie ./midgie-0.7.1.tgz -n midgie --create-namespace \
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

`midgie` has four subcommands. **`diff`** shows the change for a **single object** and never writes; **`deploy`** reconciles any number of **files and folders** — one file is a tree of one; **`rollback`** puts back what the last `deploy` changed; **`init`** holds the one-off set-up steps — `--create-state` enables removal detection for a server, `--write-cacert` turns a server's CA into a trust file, `--write-properties` writes a config template, `--write-policy` seeds a policy file for a tree (see below). All read desired-state XML and run the same local diff engine — the current state comes from a local file (offline) or a running server (`--url`).

Every `init` step is opt-in, because each one writes something: `init` on its own prints its help and exits 2 rather than picking a step for you.

> Each object is compared with **itself**, not another object: its *desired* state (how you want it) against its *current* state (how it is now), and the delta transforms current → desired. If both sides carry an OID and the OIDs differ, the run is refused.

In live mode each object is addressed by the OID in its file (or `--oid`, when the run holds exactly one object); if it has none it is resolved on the server by type + name (a single match). That is the default, since a tree whose files carry no OIDs is the ordinary way to write one; **`--no-match-by-name`** opts out: an OID-less object that already exists is then skipped rather than patched by name. An ambiguous name — two live objects sharing it — is always an error rather than a guess.

### `diff` — the minimal delta

Emits the `ObjectModificationType`. The current state is a local `CURRENT` file (offline) or, with `--url`, the object on a running server. Pass `-` for either path to read from stdin.

```bash
# Offline: desired vs a saved copy of its current state
bin/midgie diff desired.xml current.xml        # -> itemDelta on stdout (exit 1 if they differ)

# Live: desired vs its current state on a running midPoint
bin/midgie diff desired.xml --url https://mp.example/midpoint --user administrator
```

An explicit `CURRENT` file means offline; `--url` (or `MIDPOINT_URL`) means live — the two together are an error, and a URL inherited only from `MIDPOINT_URL` is a fallback that a `CURRENT` file overrides (so an env var never breaks a file diff).

A live `diff` **is** `deploy --dry-run --format xml` for that one object, and runs through the same code: the same lookup, the same `--policy`, the same notes, byte-for-byte the same payload. An object that does not exist yet comes out as the `POST` body that would create it (exit `1`), not as an error. What `diff` adds is that it cannot write — it has no flag that leads to a `PATCH` — which is what you want from a drift check in CI or a read-only account. An offline diff compares the two files as they are, with no policy.

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
- **Staleness.** The plan records each object's repository version after the write. If an object has moved since, someone else changed it and replaying would revert their change too; `--force` proceeds anyway.

Objects the deploy **created** are named in the plan but never removed: the inverse of an add is an object delete, and an object created an hour ago may already carry links and shadows. Delete those by hand.

Plans go to `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/` by default, keeping the newest five runs. `--rollback-plan DIR` chooses somewhere else — necessary in a Kubernetes Job, whose filesystem dies with the pod that needed the net, so point it at a mounted volume. `--no-rollback-plan` suppresses it.

> There is no `--auto-rollback`. midPoint has no cross-object transaction, so unwinding a partly-applied tree can land you in a third state that is neither old nor new — and the failure that would trigger it is often the one that prevents it. The plan sitting on disk is what makes a considered rollback cheap.

#### Detecting what you removed (opt-in)

Dropping a value from a file is invisible to an ordinary diff: `desired` simply stops mentioning it, and under `multivalue=merge` the delta comes out empty, so `diff` reports `No differences.` while the value lives on. midgie closes that with a **three-way** comparison — desired, live, and *last-applied* — the merge `kubectl apply` performs, but with last-applied tracked as ArgoCD does: one synced git revision per deployment, rather than a copy of every object stored on the object itself. A value present in last-applied but absent now is one you removed on purpose; a value the server holds that midgie never applied is accrued state and is left alone.

Last-applied comes from **version control**: `git show <lastRev>:<file>` is the fragment as it was when midgie last deployed it, so the only thing that must be stored is the revision. Enable it once per server:

```bash
bin/midgie init --create-state --url https://mp.example/midpoint --user administrator config/
```

That creates a `midgie-state` lookup table which records, per tree, the revision last applied. `deploy` never creates it — until you run `init --create-state`, deploy behaves exactly as before and simply notes that detection is off. Afterwards each deploy records what it applied and reports what has since disappeared:

```
midgie: 1 value(s) deployed at 2d58fd5 are no longer declared by the tree (reported only — pass --prune with --dry-run or --plan for details).
```

By default it **reports only**, and only the count. Add **`--prune`** to list and actually remove them:

```bash
bin/midgie deploy config/ --url … --prune --dry-run   # preview: lists each value, exits 1 if anything would go
bin/midgie deploy config/ --url … --prune --yes       # remove them
```

Pruning is confirmation-gated in its own right (a non-interactive run without `--yes` refuses, even when the apply pass had nothing to do), runs *after* the tree is applied so the desired state lands first, and is structurally safe: the delta is built solely from values that were in last-applied, so it cannot reach accrued or foreign state however wrong the rest of the tree is.

Deleting a *fragment* is handled the same way and needs no special case: both sides are compared as whole objects — every file describing the object now against every file that described it then, deleted ones included — so you get exactly the values no surviving file still declares, a value merely moved from one file to another is not a removal at all, and the object itself is never a deletion candidate. Each reported value still names the file it came from.

A tree is identified by its repository's **root commit** plus its path inside the repo (e.g. `df7f507/config`). The root commit is part of the history, so every clone agrees on it no matter which remotes it has — which matters when the same repo is pushed to both a customer's git and your own. Detection is skipped, with a note, when the tree is not in a git repository or the clone is **shallow** (a `--depth 1` checkout cannot recover previous content; fetch full history, e.g. `fetch-depth: 0`). `--app NAME` pins the identity for setups where that derivation does not fit.

`init --write-cacert FILE` fetches the certificate chain the server presents — `openssl s_client -showcerts`, without the openssl — shows each certificate with its SHA-256 fingerprint, and on confirmation writes the **issuing CA(s)** as the PEM `--cacert` reads:

```bash
bin/midgie init --write-cacert corp-ca.pem --url https://mp.example/midpoint
```

It needs no account: you cannot authenticate to a server you do not trust yet. The leaf is deliberately left out — trusting it would pin one certificate and break at its next renewal — so what lands in the file is what keeps working. **The confirmation is the security**: fetching a certificate means trusting whatever answered the connection, so check the fingerprints against what the CA's owner publishes before saying yes. A non-interactive run refuses rather than assuming (`--yes` overrides, for when the fingerprints are already known).

`init --write-properties FILE` writes an annotated [config file](#config-file--midgieproperties) template — every option that can carry a default, commented out, with its help text, generated from the command model so it cannot describe a version of midgie that no longer exists. It needs no server at all, since it writes the file that holds the URL:

```bash
bin/midgie init --write-properties ~/.midgie.properties
```

`init` also seeds a starter policy for a tree — a separate step, so it neither needs nor implies `--create-state`:

```bash
bin/midgie init config/ --url … --write-policy midgie.policy
```

It walks the tree and, for each object that declares a multi-valued container the schema gives a natural key **and whose values actually carry the whole key**, writes a per-container line (`<Type>/<Name>/<container>  multivalue=merge`) under a comment naming the object and the key that prompted it. It deliberately writes **no** `equivalence` rules: the default `--equivalence auto` already matches entries by natural key wherever the key is filled in, deciding it per container from the same evidence this file is seeded from. That "actually carry it" condition matters: `assignment` and `inducement` declare an `identifier` that real config usually omits, and a composite key like `kind + intent` is often written with only the `kind` — pinning key matching for the whole object in either case would be actively wrong, since prism then pairs unrelated values and the accrued-value guard refuses the result. The file is a starting point to review, not a finished policy.

After those it offers **`apply=on-create`** suggestions for the object types the tree holds — a task's `executionState` and `schedulingState`, a user's `credentials` (initial password, `forceChange`) and `activation/administrativeStatus`, a resource's `administrativeOperationalState` (maintenance mode) and cached connector `schema` — each with a line saying why and how many of the tree's files declare it. They are written **commented out**, so a seeded file behaves exactly like one without them; uncomment the ones that fit (see [`apply=on-create`](#deploy--reconcile-a-folder-tree) below).

Re-running it on an existing file **refreshes only its marked block**, so you can add objects to the tree and pick up their suggestions without losing anything you wrote:

```
# your own rules go above the block — ties break to the earlier line, so they win
ResourceType/Prod LDAP/schemaHandling/objectType  multivalue=replace

# --- begin midgie suggestions — regenerated by 'init --write-policy'; edits here are lost ---
# ResourceType/Prod LDAP
#   key: kind + intent
ResourceType/Prod LDAP/schemaHandling/objectType  multivalue=merge
# --- end midgie suggestions ---
```

A container you drop from the tree loses its rule instead of lingering; a policy written before the block existed gets one appended once; a start marker with no end is refused rather than guessed at.

The state object describes and protects itself: it is `indestructible` (midPoint refuses to delete it — clear the flag first if you really mean to), carries a `description`/`documentation` explaining it to an admin who has never heard of midgie, and is marked `subtype=midgie`. To switch tracking off without deleting anything, set its `lifecycleState` to `archived`. Losing it is harmless — detection simply stops until `init --create-state` recreates it; midgie never deletes anything as a result.

The order is two-tier: extension **schemas** first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then **objects** (sorted by their reference OIDs). Objects are identified by OID, or by type+name when they carry no OID, so several files may declare *fragments* of one object — and those files are **combined into one desired object**, which is the apply unit. Two files each contributing a `panel`, an `inducement` or an attribute definition therefore compose: entries of a multi-valued container are paired by container id or by the schema's natural key (so two files describing `objectDetailsPage[type=UserType]` yield one page with both their panels), and anything that cannot be paired is appended, with identical values collapsed. A genuine contradiction between fragments (a different type under one OID, or a single-valued item set to two values) is an error; same-object non-overlapping fragments are fine. The error names **both** files, and where the clashing values run to several lines it shows a diff of them rather than printing both in full — with a long `description` or expression, the one clause that differs is otherwise buried in two near-identical blocks.

With `--url`, each unit is reconciled in that order — look up the current object by a search (so a missing object doesn't 404), diff the combined object (`--scope present`), then `PATCH`, or `POST` to create it if it is not there (an OID-less object is reconciled by type+name); `--no-create-if-absent` and `--no-match-by-name` opt out of either half. One request per object, so a create is atomic rather than a partial `POST` patched into shape afterwards. It is sequential, so a role is created before the user that references it, and re-running is idempotent. Writing is gated: a single confirmation prompt by default (`--yes` to bypass, `--confirm-each-apply` to prompt per change, `--dry-run` to preview without writing; a non-interactive session without `--yes` refuses). Exit codes: offline `0`/`2`; apply `0` (all created/patched/in-sync/skipped or declined) / `2` (any unit failed); `--dry-run` `0` in-sync / `1` would change something / `2` error.

Three more flags round it out: **`--retry`** re-applies the failed units until they stabilise (catches references the static OID graph can't order — by name, filter, or expression); **`--plan <dir>`** writes the ordered changes as numbered `create`/`patch` files for review or GitOps, and **`--apply-plan <dir>`** replays them verbatim; **`--policy <file>`** is a central file of `<selector> key=value` lines keyed on the object/item — for when part of the tree needs, say, `scope=full` on one object or `multivalue=merge` on a container. The selector is a single glob over `Type/Name/item…` (or `oid:<oid>/item…`) with `*` (one segment) and `**` (any depth) at any level, and the most specific one wins; an `oid:` rule pins exactly one object and outranks any pattern. Note that the object identity is *two* segments, so "every role" is `RoleType/*`, and an item rule scoped to a type is one glob spanning both: `RoleType/*/assignment`. The object XML stays pure. Full design in [`DESIGN.md`](DESIGN.md#deploy--apply-a-folder-tree).

Two details worth knowing when an object name is awkward: quote the selector if the name contains `=` (`"OrgType/ou=people"  scope=full`), and escape a `/` inside a name (`RoleType/App \/ Admin`), since the selector is itself a `/`-separated path. `init --write-policy` writes both forms itself. `deploy` names any policy line that matched no object, so a typo is not mistaken for a rule that ran.

A wildcard is always a *whole* segment: there is no matching inside a segment (`RoleType/App-*` is not "roles starting with App-"), and one selector is one glob (`RoleType/* **/assignment` is not shorthand for two rules). Both are refused when the file loads, with the line number — as written they used to read as a literal segment no name can equal, so the rule quietly matched nothing and the objects it was meant for kept the CLI strategy. Write `\*` for a name that really contains an asterisk.

Some items belong in the file only so the object is **created** right, and are owned by midPoint or its operators from then on. A task's `executionState` is the everyday case: deploy it suspended, and a redeploy must not flip it back after someone resumed it. **`apply=on-create`** says exactly that:

```
TaskType/*/executionState  apply=on-create
```

The item goes into the POST that creates the object and is left alone by every later PATCH: not changed, not deleted under `--scope full`, and not removed by `--prune` when it drops out of the file. A rule covers everything beneath its path, and when a new container is added to an existing object (a new `activation`, say) an `on-create` item inside it is stripped from what is sent. `--dry-run` and `--plan` name each difference held back, so an object shown `in-sync` while its state plainly differs is not a mystery. `apply` is per-item only; `always` is the default, and a more specific `apply=always` rule carves an exception out of a broader one. An object matched by name that someone else created counts as existing — midgie never writes the item there.

**`apply=if-absent`** is the gentler sibling, midPoint's *weak* mapping strength: the item is written whenever the server holds **no value** for it and never overwritten or removed once it does — not by a PATCH, not under `--scope full`, not by `--prune`. It differs from `on-create` only once someone clears the item: `if-absent` fills it in again on the next deploy, `on-create` leaves it empty. It is judged per item, so a rule on a container fills in each missing child and leaves the present ones alone, and a newly added container carries its `if-absent` items along (nothing can be there yet). Held-back differences are named with their rule in the `--dry-run`/`--plan` note, e.g. `left alone by the policy: description (apply=if-absent)`.

## Exit codes

The contract lets CI gate on drift without parsing output:

| command | `0` | `1` | `2` |
|---|---|---|---|
| `diff` | no differences | differences found (drift), or the object would be created | error, or the object was skipped |
| `deploy --dry-run` / `--plan` | nothing to change | would change something | error |
| `deploy` (real apply) | all applied/in-sync/skipped/declined | — | a unit failed, or error |
| `deploy` (offline, no `--url`) | printed the apply order | — | error |
| `init` | the step ran (created, already present, or written) | — | error, or no step asked for |

## Flags

### Diff strategy

| flag | default | meaning |
|---|---|---|
| `--scope present\|full` | `present` | *present*: only diff items present in `DESIRED` (unmanaged items are left alone → accrued state is preserved). *full*: full-replace (also removes items missing from desired). |
| `--multivalue replace\|merge` | `replace` | Multi-valued container handling. *replace*: desired values are authoritative (live-only values removed). *merge*: union by content — add desired values, keep live-only ones. |
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

**Equivalence needs no choosing.** The default `--equivalence auto` decides per multi-valued container: where the schema declares a natural key and every value in your file fills it in, entries are matched by that key and updated **in place**, keeping their container ids and the metadata hanging off them; everywhere else it falls back to `real-value`, which is what midgie did before `auto` existed. The one thing you can do to get more out of it is fill in the keys — a `<panel>` with its `<identifier>`, a `schemaHandling/objectType` with both its `<kind>` and `<intent>` — and midgie's input warnings tell you when a value is missing one.

Setting `--equivalence natural-keys` by hand is almost always worse than leaving it on `auto`: prism takes one equivalence for a whole-object diff, so it applies to *every* container of that object — including the many types that declare a key real config never fills in (an `assignment` rarely carries an `<identifier>`), where prism then pairs unrelated entries positionally and edits one into the other. `auto` avoids that by diffing the object under both strategies and keeping each change from the pass its path belongs to. The other strategies are for inspection, not reconciliation: `data` and `literal` compare operational data and metadata, so against a live server they never report "no differences" at all.

**Multi-value handling still needs choosing.** The default `replace` makes a run **converge** and keeps **drift visible**. `merge` buys safety from accidental deletion, but it gives up both, so turn it on deliberately and per path rather than globally:

- **It cannot express an edit.** A content change reads as *old value gone, new value added*, and merge drops the deletion — so it keeps both. With live `subtype=[alpha]` and desired `[beta]` you get `[alpha, beta]`, not `[beta]`.
- **It cannot express a removal.** Dropping an entry from your file no longer removes it from the server, and because the delta comes out empty `diff` reports `No differences.` and exits `0`. Drift detection and the CI exit-code gate stop seeing anything the file does not declare. `deploy` closes this for a version-controlled tree — it compares against what it last applied and reports the values you dropped (`--prune` removes them) — but `diff`, which does not read that history, still cannot see a removal at all.

Rule of thumb: keep a path on **replace** when your tree is authoritative for that list, and use **merge** where it is not — where the list also holds entries midPoint ships, an admin added live, or another team's tree owns. Note that *your own* tree splitting a list across several files is **not** one of those cases: `deploy` combines the files describing one object before diffing, so composition needs no merge and keeps removals working.

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
  multivalue=replace, so the other 3 will be DELETED. Set '…/panel multivalue=merge' to update just the
  entries you list.` When the key is not filled in on every value it adds that, since without it the entries
  can only be matched by content.

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
| `--oid OID` | *(diff, deploy)* Object to fetch/patch when the run holds exactly one object. Defaults to the OID in its file. |
| `--cacert FILE` | Verify the server's TLS certificate against this CA cert (PEM). |
| `--insecure` | Trust any TLS certificate and skip hostname verification (self-signed dev servers). |
| `--proxy URL` | Forward proxy, `http://proxy.corp:3128` or `proxy.corp:3128` (or `https_proxy`/`http_proxy`). |
| `--no-proxy HOSTS` | Comma-separated hosts that bypass it (or `no_proxy`). See below — it *replaces* the loopback default. |
| `--proxy-user USER` | User for a proxy that demands Basic authentication. |
| `--proxy-password`, `--proxy-password-env VAR`, `--proxy-password-file FILE` | Its password, same ladder and same preference as the account password. |
| `--timeout SECONDS` | Per-request timeout (default 30). |
| `--retries N` | Retry idempotent reads on `502`/`503`/`504`/connection-refused/timeout, `N` times with backoff (writes are never auto-retried). |

#### Behind a proxy

```bash
midgie deploy config/ --url https://mp.example/midpoint \
  --proxy http://proxy.corp:3128 --proxy-user alice --proxy-password-file /run/secrets/proxy-pw
```

**Where the setting comes from**: `--proxy`, then `https_proxy`/`HTTPS_PROXY` for an https server or `http_proxy`/`HTTP_PROXY` for a plain one (either spelling; lowercase wins if both are set, as curl reads them), then the [config file](#config-file--midgieproperties). The two schemes are **separate settings and neither falls back to the other** — `http_proxy` alone leaves an `https://` server direct, exactly as it does for curl and git, so midgie and the tree's clone always agree on what gets proxied. The variables matter because the JVM reads none of them on its own — but the `git` inside the [container image](#container-image) does, so a pod given only `HTTPS_PROXY` used to clone its tree happily and then fail to reach midPoint. midgie now reads them itself, and the two halves behave the same.

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
| `--format xml\|json\|summary` | *(diff; deploy with `--dry-run` and one object)* `xml` (diff's default) / `json`: the `ObjectModificationType`, or the `POST` body for an object that does not exist yet. `summary`: a human-readable changed-path list. Under `deploy` the plan rows move to stderr. |
| `-o, --output FILE` | *(diff, deploy)* Write the `--format` output to `FILE` instead of stdout. |
| `-q, --quiet` | Suppress advisory notes/status; keep the delta payload, errors, and exit code. |
| `-v, --debug` | Follow what midgie is doing on stderr: one line per step (mode, connection, config files, schema registration, each REST call) and per object-level decision (the diff summary, the reconcile outcome, which policy rule applied). midgie's own loggers only — the prism/framework logs stay quiet. |
| `--trace` | Everything `--debug` shows plus the detail behind it: per-file and per-value decisions, stack traces, full REST request/response bodies (**may include sensitive values**), and the framework logs. |
| `--no-create-if-absent` | *(deploy)* Fail on an object that does not exist instead of creating it (and skip an OID-less one). Creating is the default. |
| `--no-match-by-name` | *(diff, deploy)* Do not patch an OID-less object found by type + name — skip it instead. Resolving it by name (a single match) is the default. |
| `--dry-run` | *(deploy)* Compute and print the change(s) but do not send them; exits `1` if anything would change. |
| `-y, --yes` | *(deploy)* Apply without the interactive confirmation prompt (required non-interactively). |

`deploy` adds tree-level flags — `--retry`, `--plan <dir>` / `--apply-plan <dir>`, `--policy <file>`, `--rollback-plan <dir>` / `--no-rollback-plan`, and `--confirm-each-apply` — described in the `deploy` section above.

## Config file — `midgie.properties`

The settings that never change for an environment — the server, the account, the CA certificate that makes TLS verify instead of needing `--insecure` — can live in a properties file instead of on every command line:

```properties
# midgie.properties
url    = https://mp.example/midpoint
user   = deployer
cacert = /etc/ssl/corp-ca.pem
scope  = present

midgie.deploy.retry = true      # qualified with a command: only 'deploy' gets it
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

**Keys** are option names without the dashes — `url`, `cacert`, `no-create-if-absent` — applying to every subcommand that has that option, or qualified with the command to narrow them (`midgie.deploy.scope`). **Precedence**: an option on the command line wins, then the `MIDPOINT_*` environment variables, then this file, then the option's built-in default. Environment beats file deliberately: a variable describes the process this run was launched into — a pod's deployment, typically — and a file on the same machine should not quietly redirect it somewhere else.

`midgie <command> -v` names every file it read and which key came from which — keys and their source only, never values, since one of those keys is `password`:

```
config: 3 setting(s) from /home/you/.midgie.properties
config: 2 setting(s) from /etc/midgie/midgie.properties
config:   user from /home/you/.midgie.properties overrides /etc/midgie/midgie.properties
config: cacert <- /etc/midgie/midgie.properties
config: url    <- /home/you/.midgie.properties
```

Two things the file will not do. **A key that is not an option is an error**, naming the file and the key, because a mistyped setting that silently does nothing is the failure mode this tool works hardest to avoid. And **`yes` and `prune` are refused**: they decide whether a run writes without asking and whether it deletes, and a file on disk is not where a person confirms that — pass them on the command line.

### A `.midgie.properties` in the tree

Some settings belong to the *deployment* rather than to the machine or the person — where its rollback plans go, which policy file it uses. midgie reads the nearest `.midgie.properties` at or above the working directory, stopping at the repository root, and it sits at the **lowest** precedence of the file layers: the tree suggests, your own files override. The file and the keys it supplied are printed at the start of the run, because a config that arrived with a checkout is the one nobody remembers is there.

`init --write-properties` writes the right template for wherever it lands — a path named `.midgie.properties` outside `$HOME` gets the stripped, tree-local one, anything else gets the full one. Give it a directory (or nothing, meaning `$PWD`) and it picks the name midgie would actually *read* there, so it cannot write a file that is silently never loaded:

```bash
bin/midgie init --write-properties config/    # -> config/.midgie.properties, tree template
bin/midgie init --write-properties ~          # -> ~/.midgie.properties, full template
bin/midgie init --write-properties /etc/midgie  # -> midgie.properties, no dot
bin/midgie init --write-properties - | diff - ~/.midgie.properties   # what did this version add?
```

`-` writes the template to stdout and moves the status note to stderr, so the payload stays pipeable. The same directory/`-` handling applies to `--write-policy` and `--write-cacert`, though their default names (`midgie.policy`, `midgie-ca.pem`) are conventions rather than something midgie derives — `--policy` and `--cacert` accept any path.

The disallowed keys are **omitted rather than commented out** — uncommenting a line in a midgie template must always yield a file midgie accepts, and a commented-out `url` would break that and read as an invitation. The header names them and says where they belong instead.

Because it travels with somebody else's repository, this layer is **allowlisted** rather than denylisted — cloning an unfamiliar repo and running even a read would otherwise authenticate to whatever server its file names. It may set `rollback-plan`, `policy`, `scope`, `multivalue`, `equivalence`, `path`, `app`, `schema`, `schema-dir`, `no-match-by-name`, `no-create-if-absent`, `retry`, `smart-refs`, `allow-inplace-merge` and `no-live-schema`. Anything else is an error naming the file — `url`, `user`, `password*`, `insecure` and `cacert` decide where credentials go, and `no-rollback-plan` would let a tree switch off its own safety net. A file may say *where* something goes, never *whether* a safety gate applies.

A CA certificate is the case worth spelling out. With `cacert` in the file, TLS verifies properly for every command with nothing extra typed, so `--insecure` stops being the path of least resistance.

## Environment variables

| var | equivalent of |
|---|---|
| `MIDPOINT_URL` | `--url` |
| `MIDPOINT_USER` | `--user` |
| `MIDPOINT_PASSWORD` | password (after the `--password*` flags, before the prompt) |
| `MIDGIE_CONF` | path to the [config file](#config-file--midgieproperties) (also `-Dmidgie.conf`) |
| `MIDGIE_JAR` | JAR path used by the `bin/midgie` launcher |
| `https_proxy` / `HTTPS_PROXY` | `--proxy`, for an `https://` server. A `user:pass@` in it is used. See [Behind a proxy](#behind-a-proxy) |
| `http_proxy` / `HTTP_PROXY` | the same, for a plain-`http://` server |
| `no_proxy` / `NO_PROXY` | `--no-proxy` |
| `JAVA_OPTS` | extra JVM options for the `bin/midgie` launcher — where `-D` settings go, including the JDK's own proxy properties |
| `JDK_JAVA_OPTIONS` | set to `--sun-misc-unsafe-memory-access=allow` when running the JAR directly on JDK 23+ |

## Safety guards

- **Protected/encrypted values** — `ProtectedStringType` deltas (e.g. `credentials/password/value`) are dropped from the delta and their paths reported: the ciphertext re-encrypts on every fetch, so diffing it yields spurious deltas *and* would leak the secret. Rotate credentials through midPoint's own API. Since midPoint 4.10.5 this includes the password **hint** (`credentials/password/hint`): a hint in the tree is still written when midgie creates the user, but no longer patched on an existing one. `diff` and `deploy` each name every protected value they skipped, so an ignored change does not pass for an applied one.
- **Items the server withholds** — midPoint does not return some items unless they are asked for, and says so by sending `<row incomplete="true"/>`. A `LookupTableType`'s `row` is the everyday case (a `jpegPhoto` another): read as written it means "no rows", so a lookup table would re-propose its whole contents on every run and never reach in-sync, and a row an admin had added would be in line for deletion without ever being named. midgie re-reads a live object asking for whatever came back incomplete, so the diff sees the real thing. Anything the server still will not hand over — no read authorization on it, usually — is **left out of the delta** and reported rather than diffed as empty: unknown is not absent, and a delta built on that would overwrite content midgie never saw.
- **Type/OID cross-check** — enforces the "same object" contract: the diff refuses inputs of different
  types, or inputs that both carry an OID when the OIDs differ, so you cannot accidentally diff the wrong
  object. A desired object with no OID is an unbound template and is allowed (the current object's OID
  governs).
- **Accrued in-place merge edits** — see `--allow-inplace-merge` above; refused by default.
- **Smart references** — a reference written as a search filter instead of an OID, so the file stays portable across instances where the target's OID differs (`connectorRef` is the everyday case). midPoint resolves the filter **at import** and stores the OID; your file still says "filter". Diffed literally that reads as a change, and the resulting PATCH strips the OID — and a modify does *not* re-resolve the filter, so the object is left pointing at nothing. midgie therefore treats the resolved OID as satisfying the filter and reports **in-sync**, and any PATCH it does send for such a reference carries `?options=reevaluateSearchFilters` so midPoint rebinds it instead. `--smart-refs verify` confirms the OID by running the filter against the server first (a mismatch is then a real change); `--smart-refs resolve` always sends the file's form and lets midPoint re-resolve. Settable per object in `--policy` as `smart-refs=`.
- **Base-schema version coupling** — the bundled `midpoint-schema` jar is the *compile-time* object model, so it must match the target midPoint's line (4.10, 4.11, …). Patch releases change the schema too — mostly by adding items, occasionally by changing a type, as 4.10.5 did for `PasswordType/hint` — so midgie is built against the **newest** patch of its line, which knows everything an older patch of that line sends. In live mode the tool reads the server's version (`GET /nodes/current`) and warns when it is on a different line, or on a **newer patch** than the bundled schema; an older patch on the same line is the safe direction and is only logged under `--debug`.

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

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` on JDK 17 and 21
(the rig-gated live tests self-skip) plus a [REUSE](https://reuse.software/) licence-compliance lint on
every push and pull request.

## License

[EUPL-1.2](LICENSE). This tool links midPoint's schema library (EUPL-1.2), so a derived work must remain
EUPL-compatible — it cannot be relicensed as MIT/Apache. Per-file `SPDX-License-Identifier` headers plus a
[`REUSE.toml`](REUSE.toml) make the project [REUSE](https://reuse.software/)-compliant.
