# Configuration

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

Naming a file explicitly is the most deliberate of the three, so it sits on top; and because it was named on purpose, a path that does not exist is an error rather than a quiet skip. In a pod, `/etc/midgie/midgie.properties` is a ConfigMap mount and `$HOME/.midgie.properties` is yours if you [put `$HOME` on the volume](container.md#helm-chart).

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

Three things the file will not do. **A key that is not an option is an error**, naming the file and the key, because a mistyped setting that silently does nothing is the failure mode this tool works hardest to avoid. **A flag that has since become the default is refused** — `create-if-absent`, `match-by-name`, `prune` — with the `no-…` key that does work now, since the old key would sit there meaning nothing. And **`yes` is refused**: it decides whether a run writes without asking, and a file on disk is not where a person confirms that — pass it on the command line. So is **`overwrite`**, which lets `deploy --plan` replace a plan someone may be reviewing: that is a decision about one plan, and set in a file it would also fail every deploy that writes none. (`no-prune` may be set anywhere: it only ever deletes less.)

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
| `XDG_STATE_HOME` | base of the default [rollback plan](commands.md#the-rollback-plan-always-on) directory (`$XDG_STATE_HOME/midgie/…`, else `~/.local/state/midgie/…`) |
| `https_proxy` / `HTTPS_PROXY` | `--proxy`, for an `https://` server. A `user:pass@` in it is used. See [Behind a proxy](reference.md#behind-a-proxy) |
| `http_proxy` / `HTTP_PROXY` | the same, for a plain-`http://` server |
| `no_proxy` / `NO_PROXY` | `--no-proxy` |
| `JAVA_OPTS` | extra JVM options for the `bin/midgie` launcher — where `-D` settings go, including the JDK's own proxy properties |
| `JDK_JAVA_OPTIONS` | set to `--sun-misc-unsafe-memory-access=allow` when running the JAR directly on JDK 23+ |
