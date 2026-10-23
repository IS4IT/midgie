# Reference

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
| `--equivalence <strategy>` | `auto` | What counts as "the same value". `auto` decides per container (see [Choosing a strategy](strategy.md#choosing-a-strategy)); the rest, loosest to strictest, are `real-value` · `consider-different-ids` · `natural-keys` (prism-experimental) · `ignore-metadata` · `data` · `literal`. See [`DESIGN.md`](../DESIGN.md#strategy-flags). |
| `--smart-refs accept\|verify\|resolve` | `accept` | How a reference the file writes as a **search filter** is judged against the OID midPoint resolved it to. See *Smart references* under [Safety guards](safety.md). |
| `--allow-inplace-merge` | off | Proceed even when a merge would edit an **accrued (live-only)** value in place (can happen under `natural-keys`). Off by default: such a run is refused so accrued state is not silently overwritten. |

`merge` is designed for `real-value`. Under an id/natural-key-considering strategy prism can pair a
live-only value with a desired one and emit a deep in-place edit that merge cannot preserve; that is
detected per delta and **refused by default** (use `--allow-inplace-merge` to proceed, or `real-value` for
a clean union). An edit inside a value that desired *declares* — matched by container id, or by the schema's
natural key — is a managed update and is not refused (see [Choosing a strategy](strategy.md#choosing-a-strategy)).

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

**Where the setting comes from**: `--proxy`, then `https_proxy`/`HTTPS_PROXY` for an https server or `http_proxy`/`HTTP_PROXY` for a plain one (either spelling; lowercase wins if both are set, as curl reads them), then the [config file](configuration.md#config-file--midgieproperties) — which any of the four variables stands down, even the one for the other scheme. The two schemes are **separate settings and neither falls back to the other** — `http_proxy` alone leaves an `https://` server direct, exactly as it does for curl and git, so midgie and the tree's clone always agree on what gets proxied. The variables matter because the JVM reads none of them on its own — but the `git` inside the [container image](container.md#container-image) does, so a pod given only `HTTPS_PROXY` used to clone its tree happily and then fail to reach midPoint. midgie now reads them itself, and the two halves behave the same.

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

`deploy` adds tree-level flags — `--retry`, `--plan <dir>` / `--apply-plan <dir>`, `--rollback-plan <dir>` / `--no-rollback-plan`, `--confirm-each-apply` and `--app <name>` — and shares `--policy <file>` and `--no-prune` with a live `diff`; all are described under [`deploy`](commands.md#deploy--reconcile-files-and-folders), [Detecting what you removed](removal-detection.md) and [The policy file](policy.md).
