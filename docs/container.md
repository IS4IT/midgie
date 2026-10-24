# Container image and Helm chart

## Container image

Each release also publishes **`is4it/midgie`** on Docker Hub, for `linux/amd64` and `linux/arm64` — the JRE, the `bin/midgie` launcher, and **git**, which midgie forks to derive a tree's deployment identity and to recover what it applied last time (without it, `deploy` still applies; only [removal detection](removal-detection.md) goes dark). git can clone over HTTPS and SSH (`curl`, `openssh-client`, `less`), so the tree can be fetched in the container itself rather than by an init container, and `vi` is there to edit a file in place. It is a tool image: the entrypoint is `midgie`, so arguments are subcommands.

```bash
docker run --rm -u "$(id -u):0" --mount type=bind,source="$PWD",target=/work is4it/midgie:0.18.0 diff a.xml b.xml

# In a Job, command: is the entrypoint and args: the subcommand line.
#   args: ["deploy", "/work", "--url", "https://mp/midpoint", "--yes"]
```

Two things in that line are not decoration. **`-u "$(id -u):0"`**: the image runs as UID 1001 in group 0, and a bind mount ignores that entirely — `/work` keeps the *host* directory's ownership and mode, so on a Linux host a tree that is not world-readable is unreadable to the container (`cannot read /work: permission denied — midgie runs as uid 1001 …`). Run as your own UID to get past it, and keep the group at `0` rather than `$(id -g)`: `HOME` and `/work` are group-owned by 0 with `g=u`, which is what makes them writable for a UID the image has never heard of — and `deploy` needs a writable `HOME` for its [rollback plan](commands.md#the-rollback-plan-always-on) (and for a `--plan` without DIR, whose [default folder](commands.md#saved-plans) is lost with the container — name one under `/work` instead). On an SELinux host (Fedora, RHEL — not SLES or Ubuntu) the label denies the mount before the UID ever matters, and the fix there is `-v "$PWD:/work:z"`: relabelling is a `-v` suffix with no `--mount` equivalent, so that one case is worth giving up the flag below for. In Kubernetes none of this applies — `runAsUser` and `fsGroup` do the same job, and the [Helm chart](#helm-chart) sets both.

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

`:latest` stays put unless you ask for it, so rebuilding an older tag cannot drag it backwards (and a pre-release never takes it either way). The consequence for anyone pulling: a version tag names the midgie release, not one immutable build of it — pin the digest if you need the same bytes every time. Each release's notes give the digest it built.

## Helm chart

`charts/midgie` puts that image in a namespace as a workbench you exec into, with a PersistentVolumeClaim for git checkouts so a clone survives restarts and image bumps. Every release attaches the packaged chart, versioned with the release, so it installs without a checkout:

```bash
helm install midgie https://github.com/IS4IT/midgie/releases/download/v0.18.0/midgie-0.18.0.tgz \
  -n midgie --create-namespace \
  --set midpoint.url=https://mp.example/midpoint --set midpoint.user=administrator

kubectl -n midgie exec -it deploy/midgie -- bash
```

From a checkout it is `helm install midgie charts/midgie -n midgie …` instead.

Inside the pod, `/work` is the volume and `midgie` is on `PATH`:

```bash
git clone https://git.example/config.git /work/config    # full history — a shallow clone disables removal detection
midgie deploy /work/config --dry-run                      # --url/--user come from the environment
```

It is a Deployment of exactly one pod (a `ReadWriteOnce` claim cannot be shared, and this is a workbench, not a service) with `strategy: Recreate` — under `RollingUpdate` the replacement pod would wait for a claim the old one still holds and never start. The image's entrypoint is `midgie` itself, so the pod is kept alive by `command: ["sleep"], args: ["infinity"]`; point those at `midgie` and its subcommand to make the pod do a job instead, though a real job belongs in a `Job`, since a Deployment restarts a pod that has completed.

Everything is in [`charts/midgie/values.yaml`](../charts/midgie/values.yaml), with the settings worth knowing:

| value | default | |
|---|---|---|
| `persistence.size` / `.storageClass` / `.mountPath` | `5Gi`, cluster default, `/work` | `persistence.existingClaim` mounts a claim you made yourself |
| `persistence.keepOnUninstall` | `true` | the claim is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` does not throw away work in progress |
| `midpoint.url` / `.user` | unset | become `MIDPOINT_URL` / `MIDPOINT_USER`, so commands in the pod need no `--url` |
| `midpoint.existingSecret` / `.passwordKey` | unset, `password` | `MIDPOINT_PASSWORD` from a Secret you control. `midpoint.password` also works but lands in the release values — fine for a lab, not for a real server |
| `ssh.*` | off | mounts a key Secret into `$HOME/.ssh`, each entry under the name it has in the Secret, and sets `GIT_SSH_COMMAND` to point ssh at it — ssh auto-loads only its own default filenames, so a `deploy-key` would otherwise be mounted and never offered. `ssh.acceptNewHostKeys` adds trust-on-first-use for when you have no `known_hosts` |
| `extraEnv` | `[]` | plain `name`/`value` pairs on the container. `HTTPS_PROXY`/`NO_PROXY` belong here (git and midgie both read them — see [Behind a proxy](reference.md#behind-a-proxy)), as does `JAVA_OPTS` for heap or `-D` settings |

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
