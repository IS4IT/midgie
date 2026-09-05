# Changelog

All notable changes to midgie are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.10.0] - 2026-09-05

### Changed

- **`deploy` reconciles one object, not one file.** Several files describing one object are now combined into a single desired object and applied in one request. Until now each file was its own apply unit, which cannot compose a list: two files each contributing a `panel` to `SystemConfiguration` produced two PATCHes, and the second replaced what the first had written. midgie warned about that ("last-one-wins") and named `multivalue=merge` as the fix — but merge buys composition at the price of removals, because a value you delete from a file then yields an *empty* delta, so it is neither applied nor reported as drift, and only `--prune` can still find it. Combining first removes the trade entirely: the desired object holds both panels, plain `replace` writes both, and a deleted entry is still a deletion. The clobber warning is gone with the hazard it warned about, and a tree that added `multivalue=merge` only to make its own fragments compose no longer needs it.

  Entries of a multi-valued container are paired by container id, else by the schema's `a:naturalKey` — the same rule the diff already uses to decide whether a value is accrued — so two files that each declare `objectDetailsPage[type=UserType]` produce *one* page carrying both their panels rather than two competing pages. Values that can be paired by neither are appended, with identical ones collapsed, so the same entry written into two files is one entry. Worth noting where this puts natural-key matching: file against file, both sides your own, visible in `--plan`, with nothing accrued at risk — unlike the same matching aimed at a live object.

  Two consequences beyond composition. A **create is atomic**: the whole object is POSTed, instead of POSTing whatever the first file held and patching the rest onto it, so it no longer exists in a partial state in between (and the local projection `--plan` kept in order to model that is gone). And a file that cannot be applied now **fails its whole object** rather than failing alone while its siblings land — one object is either applied or it is not. Result lines, the apply-order table and the plan files are per object accordingly; the apply order lists an object's other files beneath its step.

- **Delete detection compares whole objects.** It worked per file, subtracting anything a surviving sibling still declared; it now compares the union of the files describing an object against the union of the files that described it at the recorded revision, deleted ones included. Same answer for a deleted fragment, and two cases fall out instead of needing to be compensated for: a value **moved between two files** of one object is no longer reported as a removal, and an object whose files all disappeared is still reported whole rather than taken apart value by value. Each removal still names the individual file it came from, since that is what an operator reviewing a prune acts on.

## [0.9.1] - 2026-09-05

### Changed

- **Live runs read the local inputs before they contact the server.** `deploy` asked the server for its version and then for its extension schema — two round trips — and only walked the tree in between, so a typo'd path, an unreadable bind mount or an empty directory was paid for over the network before midgie said what was wrong. Against a host that is down or firewalled it cost two connect timeouts, and the local mistake arrived dressed as a connection error. The walk, the "is there anything to deploy at all" question, and the `--schema-dir` / `--schema` validation now all happen first, and the socket is opened only once they pass: an empty tree against an unreachable host went from 13s to 3s and reports the tree instead of the network. `diff --url` and `apply` do the same with their one file, checking that DESIRED can be opened and is well-formed XML before the schema read — which is as much as can be checked without the server, since parsing an object properly needs the extension schema first. The version-mismatch warning still prints before any object is parsed, because the misparse it warns about is the parsing, not the walk. A run that succeeds does the same work in a different order, with byte-identical output.
- **The password is resolved last, not first.** Building the REST client is what walks the password ladder, and the client was built before anything else — so a run doomed by an empty tree, a path that does not exist or a plan directory with no plan files still demanded a password first. That is a bad trade when the password is long, or has to be fetched from a password safe on each run: you paid for it and then learned the run could not proceed. Credentials are now resolved at the point the server is actually needed. `--apply-plan --dry-run` never asks at all, since it only reads files, and neither does a run declined at the confirmation prompt. What does not change is that a missing password is still an error before any write.

## [0.9.0] - 2026-09-04

### Added

- **Proxy support for every live mode, with optional authentication.** `--proxy http://proxy.corp:3128` and `--no-proxy` route the REST connection through a forward proxy, and `--proxy-user` with `--proxy-password-env`/`--proxy-password-file` authenticate to one that asks. Until now a proxy worked only by accident: the client never set one, so the JDK's default selector applied and a `-Dhttps.proxyHost` in `JAVA_OPTS` happened to be honoured — while `HTTPS_PROXY`, which every other tool in a container reads, did nothing at all. That was worse than no support, because the `git` in the image *does* read it: a pod given the standard variable cloned its tree and then failed to reach midPoint, in a way that looked like a midgie bug. The variables are now read directly (`https_proxy`/`http_proxy` chosen by the server's scheme, `no_proxy` for exemptions, lowercase preferred as curl reads them), so both halves of the pod behave alike, and they outrank the config file for the same reason `MIDPOINT_URL` does. **Configure nothing and nothing changes**: with no proxy set midgie installs no selector, leaving the JVM properties exactly as they were.

  Authentication needed two things the JDK does not give by default. Credentials go through the account password's own ladder — never as a bare argument — and a `user:pass@` inside `https_proxy` is honoured, since that is how the ecosystem writes it and a variable is not on show in `ps`; the same credentials inside `--proxy` are stripped with a warning, as they already are for `--url`. And reaching an https server means a `CONNECT` tunnel, on which the JDK has refused Basic since 8u111. That setting is read once, on first use of `java.net.http`, so it is decided at startup next to the existing hostname-verification latch — and only for a run that configured credentials, leaving the default protection in place for everyone else. A rejected password is reported as such, naming the proxy: the JDK surfaces it as "too many authentication attempts", which mentions neither.

  `init --write-cacert` tunnels too, which matters more than it sounds: behind a proxy you could not fetch the CA that would let you stop using `--insecure`. It is the one place midgie speaks the proxy protocol by hand — it inspects a handshake rather than making a request, so `java.net.http` cannot do it — and through an intercepting proxy what it captures is that proxy's own chain, which is precisely the CA `--cacert` then needs. SOCKS and TLS-to-the-proxy are refused with a message saying why rather than failing later as something else, `java.net.http` supporting neither. A tree-local `.midgie.properties` may not set any of this, on the existing rule: a file arriving with somebody else's clone must not decide where your credentials travel.

## [0.8.3] - 2026-09-04

### Fixed

- **A path midgie cannot read now says who midgie is and who owns the path.** `deploy` against a bind-mounted tree failed with `midgie: /work` and nothing else — the file system throws these carrying the bare path as their entire message, and midgie prints an exception's message verbatim. It now reports `cannot read /work: permission denied — midgie runs as uid 1001 gid 0, and it is owned by uid 31655 gid 100, mode 0750`, which is the whole diagnosis in one line: neither half is visible from where the operator stands, because inside a container the UID belongs to the image while the path's ownership comes from the host. In a container the message adds the fix. The same wording now covers a single object file for `diff` and `apply`, including the `java.io` spelling of a denial — prism's reader raises `FileNotFoundException` with the C library's wording appended to the path, so whether it is a denial is settled by asking the file system rather than by matching that text. An unreadable *subdirectory* names itself rather than the root that was given, and still fails the run: a subtree midgie cannot read is not one it may quietly skip, or `deploy` would apply a partial tree and exit 0, and `--prune` would read the objects it never saw as removals.
- **`--password-file` says why it could not read the file.** The message appended the exception's own text to a line that had already named the path, and that text *is* the path: `cannot read --password-file /etc/mp.pw: /etc/mp.pw`. It now reads `: no such file or directory`, or reports a permission denial the same way every other unreadable path does — the case that matters for a mounted Secret, where the file exists and the running UID is simply not the one it was made readable to.
- **"No midPoint objects found" names the path, and says whether anything was there at all.** The message named neither, so two very different causes looked identical: a tree of the wrong sort of files, and a path with nothing in it. It now reports `no midPoint objects found under /work — it holds no files at all` or `— walked 12 file(s), none of them a deployable midPoint object`. The first is what an unmounted volume looks like — `docker -v` invents an empty directory when the bind source is missing or `$PWD` did not expand, and mounts that instead of failing — so in a container the message says how to check. The README's `docker run` line now carries `-u "$(id -u):0"` and uses `--mount type=bind`, which refuses a missing source outright, with the SELinux and Kubernetes equivalents alongside it.

## [0.8.2] - 2026-08-05

### Fixed

- **The fragment-clobber warning stops once the merge rule it asks for is in place.** When two files set the same multi-valued item on one object, `deploy` warns that a later PATCH overwrites the earlier one and names `multivalue=merge` as the fix — but the code emitting it never saw the strategy, so it went on saying the same thing after the rule had been added. Doing what a message asks and seeing no change is worse than no message: it teaches that the advice does not work. Grouping now asks whether the path is already merged and stays quiet when it is, saying so under `-v`. The check goes through the fully resolved strategy, so `--multivalue merge` and `--path` rules count too, not only the `--policy` file.
- **A bad `--scope`, `--equivalence`, `--multivalue` or `--path` value now fails an offline `deploy`.** The strategy was only resolved for live runs, so offline these were parsed but never validated and a malformed value was silently ignored. (This is also what made the warning above keep firing for anyone who set merge on the command line rather than in a policy file.)

## [0.8.1] - 2026-08-05

### Added

- **`init --write-*` takes a directory, or nothing, or `-`.** A directory writes the conventional name inside it, no value at all means the working directory, and `-` writes to stdout. For `--write-properties` the name is not a convention but a derivation: `.midgie.properties` normally, `midgie.properties` under `/etc/midgie` — the name midgie would actually *read* from that directory, so `--write-properties .` in a config tree cannot produce a file that is silently never loaded. With `-`, the "Wrote …" note moves to stderr so the payload owns stdout, which makes `midgie init --write-properties - | diff - ~/.midgie.properties` the way to see what a new version added without touching your file.

### Changed

- **Re-running `init --write-policy` refreshes its suggestions instead of refusing.** The generated rules now sit in a delimited block, and a second run replaces only that block — anything you wrote outside it is untouched, and a container you dropped from the tree loses its rule instead of lingering for ever. The block goes at the *end* of an existing file, because policy ties break to the earlier line, so your own rules beat the generated ones for the same selector. A file with no markers (written by an earlier version) gets the block appended once; a start marker with no end is refused rather than guessed at. `--write-properties` and `--write-cacert` still refuse to overwrite: they generate a whole file, and both are edited after generation.

- **`init --write-properties` knows where it is writing to.** A path named `.midgie.properties` outside `$HOME` is one midgie reads as a *tree-local* file, which may only set an allowlisted set of keys — so a full template written there would have errored the moment a line was uncommented. It now writes a stripped template for those paths, offering only the keys a tree file may carry. The rest are **omitted rather than commented out**: the template's contract, pinned by its test, is that uncommenting every line yields a file midgie accepts, and a commented-out `url` would break exactly that as well as reading as an invitation. The header names what was left out and where it belongs instead. Which template to write is decided from the same constants the config reader uses, not a flag, so it cannot disagree with how the file will actually be read; and the template filters on the same allowlist `ConfigDefaults` validates against, so the two cannot drift. The full template's header now also names the tree-local layer, which nothing in it previously hinted existed.

## [0.8.0] - 2026-08-05

### Added

- **`midgie rollback` — undo the last deploy.** Every `deploy` that writes now leaves a **rollback plan** behind: for each object it touched, the delta that puts it back. Nothing is recomputed at rollback time. This is the answer to the ordinary disaster — a missing line in the policy file leaves a multi-valued container on `replace`, the wholesale replace wipes accrued associations or part of the `SystemConfiguration`, and the run reports success. Those deletes carry the wiped values themselves, so the inverse restores them exactly. The command refuses rather than guesses: **provenance** (the plan records which server and which deployment it came from, and a mismatch is an error with no override, so a staging plan cannot reach production because somebody answered yes) and **staleness** (it records each object's repository version after the write, and an object that has moved since was changed by someone else, so replaying would revert their change too — `--force` proceeds anyway). Objects the deploy *created* are named in the plan but never removed: the inverse of an add is an object delete, and one created an hour ago may already carry links and shadows. Plans go to `$XDG_STATE_HOME/midgie/<deployment>/<timestamp>/`, newest five kept; `--rollback-plan DIR` moves them, `--no-rollback-plan` suppresses them. There is deliberately no `--auto-rollback`: midPoint has no cross-object transaction, so unwinding a partly-applied tree can land you in a third state that is neither old nor new, and the failure that would trigger it is often the one that prevents it.
- **A tree-local `.midgie.properties`.** Some settings belong to the deployment rather than the machine or the person — where its rollback plans go, which policy file it uses. midgie reads the nearest one at or above the working directory, stopping at the repository root, at the **lowest** precedence of the file layers: the tree suggests, your own files override. It is **allowlisted** rather than denylisted, because it travels with somebody else's repository and cloning an unfamiliar repo should not let its file decide where your credentials go — `url`, `user`, `password*`, `insecure` and `cacert` are absent from the list, and so is `no-rollback-plan`, on the rule that already keeps `yes` and `prune` off every config file: a file may say *where* something goes, never *whether* a safety gate applies. The file and the keys it supplied are printed at the start of the run.

### Changed

- **Every `replace` item delta now carries the value it overwrites.** prism computes the old value and discards it: `ItemImpl.diffInternal` builds a clean add/delete pair, and `PrismPropertyImpl.fixupDelta` — overridden for single-valued *properties* alone — calls `delta.clear()` and rewrites it as a bare replace without copying the old value into `estimatedOldValues`. So the delta said where to go but not where it came from. It is now filled from the live object and travels as `ItemDeltaType`'s `estimatedOldValue`, which `DeltaConvertor` serializes and parses back on its own. Two things follow: a saved plan is reviewable (a reader sees what is being overwritten, not just the new value), and `ObjectDelta.createReverseDelta` becomes correct — it reverses a replace by substituting those old values and, finding none, fell back to an *empty* replace that would clear the item rather than restore it. midPoint treats the field as informational; its own delta visualizer reads it, the repository write path does not. Protected values are never recorded — ciphertext re-encrypts on every fetch and has no business in a file on disk.

### Fixed

- **`deploy` no longer treats its own plan files as source objects.** A `.create.xml` is a whole object with a valid root element, so a plan directory inside a deployed tree was discovered on the next run and **redeployed** — `midgie deploy /config --plan /config/plan` self-poisoned, and pointing `deploy` at a plan directory created every object in it. Three layers now prevent that: `PlanStore` stamps every plan file with a marker that `Discovery` skips (it travels with the content, so it survives a rename, a move out of its directory, a commit, or a CI artifact — which also keeps discovery content-based rather than path-based); the `--plan`/`--apply-plan`/`--rollback-plan` directories are excluded by canonical path; and the walk no longer descends into dot-directories, `.git` included. A path named explicitly on the command line is still walked even if it is one.

## [0.7.1] - 2026-08-03

### Fixed

- **Arrow keys work in `vi` in the container image.** `vim-tiny` starts in vi-compatible mode, where an arrow key in insert mode arrives as its escape sequence: `ESC` leaves insert, then `[`, then `A`/`B`/`C`/`D` run append/back/change/delete — so the keys typed letters and opened lines instead of moving the cursor. The image now sets `nocompatible` (and `backspace=indent,eol,start`, the same compatibility stopping backspace from crossing an indent or a line break) in `/etc/vim/vimrc.tiny`, which is the file `vim.tiny` reads and the one that set `compatible` in the first place — the usual `/etc/vim/vimrc.local` hook belongs to full vim and would have looked right while changing nothing.

## [0.7.0] - 2026-08-01

### Added

- **`init --write-cacert FILE`** — fetch the certificate chain a server presents, show each certificate with its SHA-256 fingerprint, and on confirmation write the issuing CA(s) as the PEM `--cacert` reads. What `openssl s_client -showcerts` was for, without leaving midgie or hand-editing PEM blocks. It needs no account, since you cannot authenticate to a server you do not trust yet, and it writes the issuers rather than the leaf, which would pin one certificate and break at its next renewal. The confirmation is the security: fetching a certificate means trusting whatever answered, so the fingerprints are printed, the prompt defaults to no, and a non-interactive run refuses unless `--yes` says the fingerprints are already known.
- **`init --write-properties FILE`** — an annotated `midgie.properties` template: every option that can carry a default, commented out, with its help text, the search path, the precedence rules, and the two keys a file may not set. Generated from the command model, so an option added later appears in it without anyone remembering; a test uncomments the lot and hands it back to the config reader, so what midgie writes is always something midgie accepts. Needs no server — it writes the file that holds the URL.

### Changed

- **`-v` is a flow you can follow, and `--trace` is the detail behind it.** `-v` used to raise the *root* logger, so prism's own DEBUG came with it — `ClassPathUtil` alone prints a line per scanned package, a dozen of them before midgie has done anything — and everything midgie logged sat at that one level, per file and per value alike. Now `-v` raises only midgie's loggers and carries one line per step and per object-level decision; per-file/per-value detail, stack traces, REST bodies and the framework logs moved to `--trace`. On a 19-object tree that is 146 lines with no framework noise, against ~200 before.
- **A policy message says where the rule lives**, `(midgie.policy:1)`, instead of quoting the rule back — the quote was longer than the file and did not say where to change it. The user-facing "matched no object in this tree" note gained the same locator.
- **`--debug` now shows where each setting came from** — every file read, every key that one layer overrode in another, and which file each effective key came from. Keys and their source only, never values, since one of them is `password`. Finding no file at all is logged too, naming the paths searched — silence could not be told apart from the flag not working. The lines existed before but were written into a logger that was still off: each subcommand switches logging on for itself, and the config files are read earlier than that, while the command line is being built.
- **The `midgie.properties` files on the search path are merged instead of the first one found winning.** `/etc/midgie/midgie.properties`, then `$HOME/.midgie.properties`, then a file named by `$MIDGIE_CONF`/`-Dmidgie.conf`, layered per key with the later one winning — so a site file can hold what is true of the machine (a CA certificate, a retry count) while a user file adds only what differs (the server, the account), and neither has to repeat the other. Previously a user file shadowed the site file outright, so anything in it had to be copied into every user's file to survive. Each key remembers which file it came from, so a validation error names the file actually at fault.

## [0.6.1] - 2026-07-30

### Fixed

- **A hostname that does not resolve says so**, instead of `cannot connect to … (is the server up and the URL correct? null)`. The JDK reports a DNS failure as a `ConnectException` carrying no message, with `UnresolvedAddressException` two levels down the cause chain, so the generic wording sent the operator to check a server that was never contacted — and the `null` was the exception's absent message. The error now names the host and points at DNS, adding that a container does not inherit the resolver of the shell that started it, which is where this usually bites.

- **A `--policy` rule that did apply is no longer reported as having matched nothing.** The "matched no object in this tree" note was emitted before the apply pass, but a rule is only marked as having matched when it is offered to an object, which happens inside that pass — so every rule looked unmatched, on every run that used `--policy`, including the ones being applied. The note now runs after the pass, and still names a selector that genuinely matched nothing.

- **A missing `--policy` file says what is missing.** `NoSuchFileException` carries only the path as its message, so the run failed with a bare `midgie: midgie.policy` — which reads like a complaint about the file's contents rather than its absence. The message now matches the one `--schema` already gave, and covers a directory passed by mistake.

## [0.6.0] - 2026-07-29

### Added

- **A config file, `midgie.properties`, supplying defaults for any option.** The settings that never change for an environment — the server, the account, the CA certificate that makes TLS verify rather than needing `--insecure` — had to be repeated on every command line, and in a container there was nowhere to put them at all. Found at `-Dmidgie.conf`, `$MIDGIE_CONF`, `$HOME/.midgie.properties` or `/etc/midgie/midgie.properties` (a ConfigMap mount, in a pod); keys are option names without the dashes, optionally qualified with a command (`midgie.deploy.scope`). Precedence is command line, then the `MIDPOINT_*` environment variables, then the file, then the built-in default — a variable describes the process a run was launched into, so it outranks a file on the machine. A key that is not an option is an error naming the file, since a mistyped setting that silently does nothing is the failure this tool exists to prevent; `yes` and `prune` are refused outright, because a file is not where someone confirms that a run may write without asking or delete.

### Changed

- **The container image has `vi`** (`vim-tiny`), for editing a file in the pod you shell into.

## [0.5.1] - 2026-07-29

### Added

- **The packaged Helm chart is attached to each release**, so it installs without a checkout: `gh release download v<version> -R IS4IT/midgie -p 'midgie-*.tgz'`, then `helm install midgie ./midgie-<version>.tgz`. (Passing the release URL straight to `helm install` needs the repository to be public; while it is private, helm cannot authenticate and gets a 404.) CI packages it with the release's version as both `version` and `appVersion` — every publish is then uniquely versioned without anyone remembering to bump `Chart.yaml`, and two different charts can never go out as the same version. The chart's `values-openshift.yaml` travels inside the package; installing straight from the URL, pass its three settings with `--set` instead of `-f`.

### Changed

- **The chart mounts an SSH key under the name it has in the Secret**, instead of renaming every key to `id_rsa`. ssh only auto-loads its own default filenames, so the chart now sets `GIT_SSH_COMMAND` to point at the key (with `IdentitiesOnly`, and `UserKnownHostsFile` when a `known_hosts` entry is mounted) — a Secret entry called `deploy-key` is used as one rather than having to be called something it is not.

### Fixed

- **The Helm chart can be installed on OpenShift**, with `-f charts/midgie/values-openshift.yaml`. The `restricted-v2` SCC assigns each namespace its own UID range and requires `fsGroup` to come from it, so the chart's `runAsUser: 1001` / `fsGroup: 1001` were rejected before the pod could be scheduled (`1001 is not an allowed group`). The preset nulls those fields and lets the SCC assign, keeping what `restricted-v2` does require — `runAsNonRoot`, `RuntimeDefault` seccomp, no privilege escalation, all capabilities dropped. The image was already built for an arbitrary UID: its user is in group 0 and `$HOME` is group-writable. CI now renders the preset and fails if a UID or GID reappears in it.

## [0.5.0] - 2026-07-29

### Added

- **A Helm chart, `charts/midgie`**, that runs the image in a namespace as a workbench to `kubectl exec` into, with a PersistentVolumeClaim mounted at `/work` so git checkouts survive a pod restart or an image bump. One pod, `strategy: Recreate` (a `ReadWriteOnce` claim cannot be handed to a second pod during a rolling update), kept alive with `sleep infinity` because the image's entrypoint is midgie itself. The claim is annotated `helm.sh/resource-policy: keep`, so `helm uninstall` does not throw away work in progress. Optional: `MIDPOINT_URL`/`MIDPOINT_USER` from values and `MIDPOINT_PASSWORD` from a Secret, and an SSH key mounted at `0400` for cloning private repositories.

### Changed

- **`init` no longer creates the state object unless asked: that step is now `--create-state`.** `init` is becoming the home for several one-off set-up steps, and each of them writes something — to a server or to a working copy — so none should be what happens by default. `init` with no step now prints its help and exits 2 rather than opting a server into tracking because someone was reading the command's help. **Breaking**: `midgie init --url …` used to create `midgie-state`; it now does nothing. Add `--create-state`. The messages that point at it (`deploy`'s "removals cannot be detected" note, `--prune`'s help, the state object's own documentation) name the flag.

## [0.4.3] - 2026-07-28

### Changed

- **The container image can clone the tree itself.** It gained `openssh-client` (so `git clone` over SSH works at all — `ssh` was absent, and only HTTPS could be used), `less` (git's pager, so an interactive `git log` or `git diff` pages instead of failing), and an explicit `curl`, which the base image happens to ship but nothing guaranteed. Cloning over SSH still needs a key mounted at `0400` that the pod's UID can read, and a `known_hosts` or `StrictHostKeyChecking=accept-new`.

## [0.4.2] - 2026-07-28

### Added

- **A container image, `is4it/midgie`**, published to Docker Hub for `linux/amd64` and `linux/arm64` on every release tag. It carries the fat JAR, the `bin/midgie` launcher and git — which midgie forks for the deployment identity and for recovering previously applied content, so an image without it would apply fine and quietly detect no removals. The entrypoint is `midgie` itself, so a Kubernetes Job passes the subcommand line as `args:`; an interactive shell needs `--command -- sleep infinity` plus `kubectl exec`. `git safe.directory` is relaxed system-wide so a tree cloned by an init container under another UID stays readable rather than disabling tracking silently. Built from `docker/midgie/Dockerfile`; CI builds it for both platforms on every PR without pushing.

### Changed

- **A `--policy` selector segment that mixes a wildcard with other text is now refused when the file loads**, naming the line. `RoleType/App-*` is not a prefix match and `RoleType/* **/assignment` is not two rules on one line — both parsed into a literal segment no object name can equal, so the rule matched nothing and every object it was written for silently kept the CLI strategy, turning a `multivalue=merge` meant to protect accrued values back into replace. A wildcard has to be a whole segment (`RoleType/*` for every role, `RoleType/*/assignment` for one container of every role). An object name that really contains an asterisk is written `\*`, as a slash is `\/`, and `init --write-policy` emits that escape itself.

## [0.4.1] - 2026-07-28

### Added

- **`--smart-refs accept|verify|resolve`** (also `smart-refs=` per object in `--policy`), for how a filter-written reference is judged against the OID midPoint resolved it to. `accept` (default) compares the filters when the server kept one and otherwise takes the live OID as the resolution; `verify` confirms it by running the filter against the server, treating a mismatch as a real change; `resolve` always sends the file's form for midPoint to re-resolve.

### Changed

- **The schema-version check reads `GET /nodes/current`** instead of listing every node: one object rather
  than a collection, the node actually serving the request, and readable by an account that cannot enumerate
  the cluster — which is exactly the least-privilege deployment account whose version check used to go
  silently missing.

### Fixed

- **A resource whose `connectorRef` is a smart reference is no longer broken by reconciling it.** midPoint resolves a reference written as a search filter *at import* and stores the OID; the desired file still carries only the filter, so a literal diff read that as a change and emitted a PATCH that stripped the OID. A modify does not re-resolve the filter — the docs are explicit that smart references work "only during object import" — so the reference was left unresolved and the resource stopped working. midgie now treats the resolved OID as satisfying the filter and reports the object in-sync, naming the path so the decision is visible.
- **A PATCH that does set a reference by filter carries `?options=reevaluateSearchFilters`**, so midPoint resolves it instead of storing it unresolved. This is what makes a *genuine* change — re-pointing the filter at another connector — work rather than break the object. Sent only for deltas that actually carry such a reference: for non-raw operations midPoint records a warning that filters already on the object are untouched, so it is not something to send blindly.
- **midPoint's own partial/handled-error statuses are no longer read as plain success.** The server answers
  `250` for a partial error and `240` for a handled one; both are 2xx, so an "is it 2xx" test treated them as
  clean. A write that came back that way was reported as applied, and a read was diffed as though the object
  had arrived whole. Writes now fail with midPoint's own message. Reads warn and continue, because a resource
  whose schema midPoint cannot complete answers 250 to every read while remaining perfectly deployable —
  verified against a live server, where such a resource returns 250 on GET and search but 204 on PATCH.

## [0.4.0] - 2026-07-28

### Added

- **Delete detection — `deploy` can now see values you removed.** Dropping a value from a desired file is invisible to a two-way diff: nothing mentions it any more, so under `multivalue=merge` the delta is empty and the value lives on while `diff` reports "No differences". midgie now compares desired against **last-applied** as well (a three-way merge, as `kubectl apply` does) — a value applied at the recorded revision and no longer declared is reported, while a value the server holds that midgie never applied is left alone as accrued state. **Reporting is the default: without `--prune` nothing is deleted and exit codes are unchanged.** Deleting a whole fragment needs no special case: you get exactly the values that file contributed, the object is never a deletion candidate, and values a sibling fragment still declares are subtracted.
- **`midgie init`** — the one-off, explicit opt-in that enables the above by creating a `midgie-state` lookup table on the server. `deploy` never creates it: until `init` is run it behaves exactly as before and just notes that detection is off. The object describes and protects itself for an administrator who has never heard of midgie — `indestructible` (midPoint refuses the delete), a `description` and `documentation` explaining what it is and that losing it is harmless, and `subtype=midgie`. Setting its `lifecycleState` to `archived` switches tracking off without deleting anything.
- **`deploy --prune`** — actually remove the detected values, rather than only reporting them. Opt-in, previewable with `--dry-run` (exit `1` if anything would go), and gated by its own confirmation, so a non-interactive run without `--yes` refuses even when the apply pass had nothing to do. It runs after the tree is applied, and the delta is built solely from values that were in last-applied — so it is structurally incapable of touching accrued or foreign state.
- **`init --write-policy FILE`** — seed a starter `--policy` for a tree, suggesting `equivalence=natural-keys` for each object that declares a keyed multi-valued container **and fills the whole key in**, plus a `multivalue=merge` line scoped to each such container. The "fills the whole key in" condition is what keeps the advice sound: `assignment`/`inducement` declare an `identifier` real config usually omits, and `schemaHandling/objectType` is keyed by `kind + intent` but often written with only the `kind` — suggesting key matching in either case would make prism pair unrelated values. Never overwrites an existing file.
- Last-applied comes from version control (`git show <lastRev>:<file>`), so the only stored state is one revision per tree. A tree is identified by its repository's **root commit** plus its path within the repo, which every clone agrees on regardless of its remotes — the case a remote URL cannot handle when the same repository is pushed to both a customer's git and your own. Detection is skipped, with a note, outside a git repository or in a **shallow** clone; `--app NAME` pins the identity where that derivation does not fit.

### Fixed

- **`--prune` could delete a value the tree still declares.** Values a surviving fragment declares are subtracted from the removal set, but the survivor list was built by diffing each fragment against an empty object, which yields whole-container paths while removals are reported at their full depth. Nothing nested ever matched, so moving a value between two fragments of one object made `--prune` delete it immediately after the apply had written it. Top-level items happened to work, which is why it went unnoticed.
- **`--prune` ran after a declined apply**, because the gate tested only the exit code and a declined apply exits `0`. Refusing the write now stops the prune too, as does any run that skipped or declined individual units — the removal set describes a tree the server does not hold yet.
- **The recorded revision no longer advances past removals nobody acted on.** A run that skipped or declined units, or declined the prune, used to record itself as a completed deploy, so the next run compared against it and the pending removals became permanently undetectable.
- **An unrecoverable last-applied revision is reported instead of read as "nothing was removed".** A squash-merge, rebase or `gc` makes `git show <rev>:<file>` fail exactly as it does for a file that did not exist yet; midgie now verifies the revision first, refuses to prune, and leaves the baseline alone.
- **An object's `name` is never offered for deletion.** When a deleted fragment carried `<name>` and its surviving sibling identified the object by OID alone, dismantling it put `delete name` in the prune delta, which midPoint's repository rejects — failing the object's whole prune group.
- **The recorded revision is validated as a commit sha** before it reaches git's argument list. It is read back from the server, and git treats a leading `-` as an option.
- **`--multivalue merge` no longer corrupts a changed single-valued reference.** prism emits a whole-value replace only for single-valued *properties*, so a changed `connectorRef`/`resourceRef`/`targetRef` diffs to delete-plus-add; merge dropped the delete and left an add on an item that holds one value, which midPoint rejects outright. Merge now falls back to replace on cardinality, and says so.
- **The accrued-value guard sees nested containers.** It examined only the outermost container of a delta path while `merge` is honoured at any depth, so a merge rule naming an inner container dropped that container's deletes without ever arming the refusal.
- **`--retries` covers the read it exists for.** Retryability was keyed on the HTTP verb, but every object lookup is a `POST {type}/search`, so a still-starting server failed the very first read however high the setting.
- **One object described by two files is one object.** A file carrying the OID and a file carrying only the name were treated as two: the contradiction check and the clobber warning never ran between them, nothing referencing the object ordered itself before the OID-less half, that half was skipped at apply time as "no oid", and a `--policy` rule naming the object applied to only one of them.
- **`--create-if-absent` is idempotent for OID-less objects.** It planned a create without asking the server, so a second pipeline run re-POSTed the same object and midPoint rejected the duplicate name.
- **A hard error exits `2`, not `1`.** picocli's default meant a typo'd `--cacert` or a malformed `MIDPOINT_URL` reported "differences found" to a CI gate, with a stack trace attached. Running `midgie` with no subcommand likewise prints usage to stderr and exits `2` instead of writing help into a redirected payload file.
- **A file that claims to be a midPoint object and cannot be parsed fails the run** instead of being skipped with an advisory that `--quiet` then hid, which let a pipeline go green with an object never deployed.
- **An interrupted `--plan` cannot be replayed as if it were whole.** Plan directories are marked incomplete while they are written; `--apply-plan` refuses one still carrying the marker rather than applying part of a tree and reporting success.
- **Credentials embedded in the server URL are stripped.** `java.net.http` accepts them and never authenticates with them, so they only ever travelled — into the connection errors printed regardless of `--quiet`, the debug log, and the confirmation prompts.
- **`--insecure` is decided once per process**, before any client exists. The JDK reads the hostname-verification property once, on first use of `java.net.http`, so leaving it to whichever client was built first made the flag depend on call order — and left it silently off for every later connection.
- **A single file is no longer keyed by the tree it sits in.** `deploy config/roles/x.xml` recorded the whole `config` tree as applied at that revision, so the next full deploy compared against a point at which most of it was never applied.
- Also: an `oid:` policy selector outranks a type-wide rule; a colon in an object name is part of the name; policy rules outrank the CLI flags they are meant to override; a cycle is broken only at a node that is on one; `init --write-policy` no longer discards the schema it read from the server; overlapping `PATH` arguments are deduplicated; `-o` and plan files are created owner-only; and a TLS handshake failure names `--cacert` and `--insecure` instead of surfacing a bare PKIX message.

### Changed

- **`--oid` is rejected by `deploy`** rather than accepted and ignored; it applies to `diff` and `apply`, which address a single object.
- **`--apply-plan` rejects flags it does not read** (`--prune`, `--policy`, `-c`, `-m`, and `PATH`), and `--plan` rejects `--dry-run`/`--retry`, instead of silently ignoring them.
- **A `--schema` file or `--schema-dir` that does not exist is an error.** A typo'd path used to produce a delta computed without that schema, with the affected items diffed as raw.
- **The partial-declaration warning counts matched live values**, not desired ones; the old wording mixed denominators and could print sums that did not add up.
- **A policy selector can express an awkward object name**: quote it when the name contains `=`, escape a `/` inside a name. Only a known knob starts the settings now, so `OrgType/ou=people scope=full` parses. `init --write-policy` emits both forms, closing a round trip where midgie could generate a policy file midgie then refused to load.
- **`deploy` names any policy line that matched nothing**, instead of letting a typo'd selector resolve silently to the CLI baseline.
- Transport failures are a typed exception rather than a message prefix other code matched on, so rewording an error is no longer a behaviour change.
- The rig's containers bind to loopback, and all GitHub Actions are pinned to commit SHAs.

## [0.3.1] - 2026-07-27

### Added

- **Destructive changes announce themselves.** A delta that removes values now says so: each summary line carries the count it removes (`delete … (removes 2 values)`), and `diff`/`apply`/`deploy` print a prominent `WARNING: this change REMOVES n values from the live object` listing the paths. Advisory only — midgie reports the blast radius, it does not block; use `--dry-run` to inspect first. Suppressed by `--quiet`.
- **Static input checks on the desired object**, reported before anything is written. midgie warns when a value of a keyed multi-valued container **omits its natural key** (so it cannot be matched to a live entry and degrades to a whole-value change), and when desired **declares only some** of a keyed container's live values while that path is on `replace` — naming the fix: *"desired declares 1 of 4 live value(s) … the other 3 will be DELETED. Set '…/panel multivalue=merge' (with equivalence=natural-keys)"*. Kept precise so the warnings mean something: only containers with a schema-declared `a:naturalKey` are checked, the key must be in use by the live values (many types declare one that real config never fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value restated verbatim in desired counts as declared. The documented key-matched merge recipe is warning-free.

### Changed

- **Renamed `--host` to `--url`.** The flag has always taken a full base URL (scheme, port and the application context path, e.g. `https://mp.example/midpoint`), not a hostname, and the new name matches the `MIDPOINT_URL` environment variable it defaults from. **Breaking:** `--host` is no longer accepted — update scripts and CI to `--url`. The integration-test system properties were renamed to match: `-Dmidgie.it.url` / `.user` / `.password` (were `-Dmpdiff.it.*`).

### Fixed

- **A natural-key-matched edit is no longer mistaken for an accrued-value overwrite.** Combining `equivalence=natural-keys` with `multivalue=merge` — the way to sync a single entry nested inside multi-valued containers, e.g. one GUI `panel` inside `objectDetailsPage` — was refused as an "accrued in-place edit" and needed `--allow-inplace-merge`, which switches the guard off for the whole object. The check now resolves each container-id segment of the delta path against the desired object by container id **or** by the schema's `a:naturalKey` (`panel` → `identifier`, `objectDetailsPage` → `type`, …); an edit inside a value desired declares is a managed update and passes. A key only counts when both values actually carry its constituents, so a container whose natural key is absent (e.g. an `assignment` with no `identifier`) still triggers the guard as before.

- **Extension schema is deduped by target namespace before registration.** A schema present both in the deploy tree (or `--schema`) and on the live server was collected and registered twice, so prism logged a wall of `Overwriting existing definition …` warnings on `deploy` / `diff --url` / `apply`. It is now registered once — the local/tree copy winning over the live read — so that noise is gone; any genuine cross-import re-registration warnings still surface.

## [0.3.0] - 2026-07-25

### Added

- **`deploy` warns when two files that are fragments of one object both set the same multi-valued item** (e.g. both add an `inducement`). They apply file-by-file and are not merged, so under the default (replace) the later PATCH overwrites the earlier one (last-one-wins); the warning points at combining the files or a `--policy '<path> multivalue=merge'`. Non-fatal, and it honours `--quiet`.

### Changed

- **Renamed the tool `midpoint-diff` → `midgie`.** The launcher (`bin/midgie`), the built artifact (`midgie.jar`), the command name (`midgie …`), the `--version`/message output, the docs, the release workflow, and the GitHub repository all use the new name. The Java package `de.is4it.midpoint.diff` is kept for now. If you build from source, the JAR is now `target/midgie.jar` and the launcher env override is `MIDGIE_JAR`.
- **`--scope present` now preserves live items the desired object doesn't mention at *any* depth**, not just whole top-level items. Two files that are fragments of one object can each fill a *different* child of the same single-valued container (e.g. one file sets `deploymentInformation/headerColor`, another `deploymentInformation/name`) without the second file's diff deleting the first's child — and single-valued mid-levels (e.g. `internals/tracing`) are protected too. Protection can only be as fine as the first multi-valued container (a live-side container id can't be matched to id-less desired XML), which is where per-value semantics take over.
- **Replaced `deploy --overrides` with `deploy --policy`.** A single central policy file keyed on the **object/item** (`<selector> key=value` lines), not the source file, so it survives the fragment model. The selector is one glob over `Type/Name/item/subitem…` (or `oid:<oid>/item…`) with `*` (exactly one segment) and `**` (zero or more segments) at any level. A selector that stops at the object identity sets object-level knobs (`scope`, `equivalence`, `allow-inplace-merge`, a default `multivalue`); a deeper/`**` selector sets `multivalue` for that container (an object-only knob on an item selector is rejected). When several rules set the same knob for one target, the most-specific selector wins (more literal segments, then fewer `**`, then a longer literal prefix; ties → earlier line). The object XML stays pure midPoint XML. **Breaking:** the file-glob `--overrides` is removed.
- **`deploy`'s report reads source → target.** Each row is now `<source file>  <Type/Name>` — the object path is the same identity form the `--policy` selectors use, replacing the old type + `(type/name)` + name columns — and the OID is shown only under `--debug`/`--trace`. Applies to both the live apply report and the offline plan table.

## [0.2.2] - 2026-07-23

### Added

- **`-c` / `-m` short flags** for `--create-if-absent` and `--match-by-name`, on each subcommand that has them: `-c` on `apply` and `deploy`, `-m` on `diff`, `apply`, and `deploy`.
- **`--debug`/`-v` now traces this tool's own decisions**, not just framework logs. It records the mode (offline/live), the resolved connection (host/user/TLS/timeout, and the password *source* — never the value), every REST request and response (method, URL, status, body size, retries, redirects), schema collection, live lookups and their match counts, the per-object reconcile decision (create/patch/in-sync/skip/fail), the diff result summary, deploy discovery/sort order/apply units/retry rounds, and a full stack trace at the point any subcommand fails. Every log line is timestamped (`yyyy-MM-dd HH:mm:ss.SSS`).
- **`--trace`** — one level beyond `--debug` (which it implies): additionally logs the full REST request and response bodies and the full HTTP error body (not just the status code). May include sensitive values, so it is a deliberate opt-in separate from `--debug`.

## [0.2.1] - 2026-07-23

### Changed

- **Readable XML output.** Serialized XML — the `ObjectModificationType` itemDelta and full-object create bodies, on the console, in `-o`/`--plan` files, across every subcommand — now **drops** the namespace declarations prism piles onto the root that nothing references (a delta went from 8 to 3), and **wraps** a long root's remaining declarations one per line instead of a single wall of text. A declaration is kept only if its prefix appears as `prefix:` anywhere else (element/attribute names or QName content like the path), so a used prefix is never removed; only namespace declarations and insignificant whitespace change, so PATCH/POST bodies stay valid.
- **`--match-by-name` on `diff` and `apply` too.** An object with no OID can now be resolved on the server by type + name (a single match) in `diff` and `apply`, not only `deploy` — opt-in via `--match-by-name` on all three (renamed from `deploy`'s `--apply-on-match`). Combine with `--create-if-absent` to create when there is no match.
- **Redirects report an actionable hint.** A `3xx` on the REST base now throws with the `Location` header and a hint (the `--host` is usually missing the application context path, e.g. `…/midpoint`, or the wrong scheme) instead of a bare `HTTP 302`; it is not followed, so the Basic-auth header is never sent to an unexpected location.

## [0.2.0] - 2026-07-21

### Added

- **`deploy`** — a new subcommand that discovers a folder tree of desired-state object XML, sorts it by dependency, and either prints the apply order (offline) or reconciles it against a live server (`--host`). The order is two-tier: extension schemas first (raw `.xsd` inputs and exported `SchemaType` objects, sorted among themselves by `<xsd:import>` dependency and registered so extension attributes resolve), then objects (sorted by their reference OIDs). Objects are identified by OID or, when OID-less, by type+name, so several files may declare fragments of one object; a genuine contradiction between fragments (a different type under one OID, or a single-valued item with two values) is rejected. With `--host`, each unit is reconciled file-by-file in order — look up the current object by a search, diff (`--scope present`), then PATCH, or POST with `--create-if-absent` (OID-less objects via type+name with `--apply-on-match`) — sequentially and idempotently. Writing is gated by a single up-front confirmation (`--yes`, `--confirm-each-apply`, or `--dry-run`; non-interactive without `--yes` refuses). Exit `0`/`2` on apply, `0`/`1`/`2` on `--dry-run`. `--retry` re-applies the failed units until they stabilise (catches references the static OID graph can't order); `--plan <dir>` writes the ordered changes as numbered `create`/`patch` files and `--apply-plan <dir>` replays them verbatim; `--overrides <file>` maps `glob = <strategy flags>` lines to a per-path diff strategy.

### Changed

- **Existence checks use a search instead of a `GET`.** `diff --host`, `apply`, and `deploy` resolve the current object with a `POST {type}/search` (by OID) rather than `GET {type}/{oid}`, so a missing object is an empty result instead of a 404 — which stops midPoint from logging an `ObjectNotFoundException` stacktrace on every probe (notably across a `--create-if-absent` deploy of a fresh tree). Shared in a new `LiveLookup` helper.

## [0.1.0] - 2026-07-19

First release. A standalone, schema-aware CLI that computes the minimal `itemDelta` between the desired and
current state of a **single** midPoint object and emits the `ObjectModificationType` XML that midPoint's
REST `PATCH` consumes — a thin wrapper around midPoint's own `PrismObject.diff`.

### Added

- **`diff`** — minimal `itemDelta` between the desired and current state of the same object (same OID),
  offline (two files, or stdin via `-`) or live against a running server (`--host`, current state fetched
  by OID). Output as `xml`/`json` (`ObjectModificationType`) or a human-readable `summary`; `-o` to a file.
- **`apply`** — reconcile a live object: fetch, diff, show the change, then `PATCH` after confirmation.
  Idempotent; `--create-if-absent` `POST`s a missing object; `--dry-run` reports without writing; `--yes`
  for non-interactive use.
- **Diff strategies** — `--scope present|full`, `--multivalue replace|merge` with per-path `--path`
  overrides, and `--equivalence` (`real-value` … `literal`).
- **Merge safety** — a merge that would overwrite an accrued (live-only) value in place is refused by
  default; `--allow-inplace-merge` to proceed.
- **Extension schema** — `--schema` / `--schema-dir` (raw XSD or exported `SchemaType`), plus automatic
  live read from the server (`--no-live-schema` to opt out).
- **Connection** — `--host`/`--user` (and `MIDPOINT_URL`/`MIDPOINT_USER`), password kept off the CLI
  (`--password-env` / `--password-file` / `MIDPOINT_PASSWORD` / interactive prompt), `--oid`, TLS
  (`--cacert` / `--insecure`), `--timeout`, and GET-only `--retries` with backoff.
- **Safety guards** — protected/encrypted values (`ProtectedStringType`) are skipped and reported;
  desired/current type and OID are cross-checked so you cannot diff the wrong object; the bundled base
  schema is version-coupled to the target midPoint (build-time drift check + runtime `GET /nodes` warning).
- **Ergonomics** — CI-friendly exit codes (`0` no diff / `1` drift / `2` error), `--quiet`, `--debug`,
  dynamic `--version`, and a `bin/midgie` launcher that silences the JDK 23+ classgraph `Unsafe`
  warnings.

### Packaging

- Fat JAR (Java 17), EUPL-1.2, [REUSE](https://reuse.software/)-compliant. CI runs `mvn verify` on JDK 17
  and 21 plus a REUSE licence lint; tagged releases attach the fat JAR.

[Unreleased]: https://github.com/IS4IT/midgie/compare/v0.10.0...HEAD
[0.10.0]: https://github.com/IS4IT/midgie/compare/v0.9.1...v0.10.0
[0.9.1]: https://github.com/IS4IT/midgie/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/IS4IT/midgie/compare/v0.8.3...v0.9.0
[0.8.3]: https://github.com/IS4IT/midgie/compare/v0.8.2...v0.8.3
[0.8.2]: https://github.com/IS4IT/midgie/compare/v0.8.1...v0.8.2
[0.8.1]: https://github.com/IS4IT/midgie/compare/v0.8.0...v0.8.1
[0.8.0]: https://github.com/IS4IT/midgie/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/IS4IT/midgie/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/IS4IT/midgie/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/IS4IT/midgie/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/IS4IT/midgie/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/IS4IT/midgie/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/IS4IT/midgie/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/IS4IT/midgie/compare/v0.4.2...v0.4.3
[0.4.2]: https://github.com/IS4IT/midgie/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/IS4IT/midgie/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/IS4IT/midgie/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/IS4IT/midgie/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/IS4IT/midgie/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/IS4IT/midgie/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/IS4IT/midgie/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/IS4IT/midgie/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IS4IT/midgie/releases/tag/v0.1.0
