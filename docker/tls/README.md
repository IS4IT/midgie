# TLS test rig

Verifies midgie's TLS options (`--cacert`, `--insecure`) end to end against a real HTTPS endpoint.

midPoint (Spring Boot) serves a single port, so instead of enabling its native TLS this rig puts a tiny
**nginx TLS reverse proxy** in front of the plain-HTTP midPoint from `../docker-compose.yml`:

```
midgie client ──HTTPS(8443)──▶ nginx (mp-tls) ──HTTP(8080)──▶ midPoint (mp_server)
        └─ trusts ca.crt via --cacert                └─ presents server.crt (SAN: mp-tls)
```

The plain-HTTP midPoint (port 8080 / host 12080) is untouched, so the HTTP integration tests keep working.

## Run

```bash
docker/tls/run.sh        # build jar → gen certs → build images → run the smoke tests
```

`run.sh` returns the client container's exit code (0 = all checks passed). Tear down with:

```bash
docker compose -f docker/docker-compose.yml -f docker/compose.tls.yml down
```

## What it checks (`run-tls-tests.sh`, inside the `mp_diff` container)

| check | host | flag | expected |
|---|---|---|---|
| CA-signed server is trusted | `mp-tls` (in SAN) | `--cacert ca.crt` | exit 1 (drift) |
| private CA not in default trust | `mp-tls` | — | exit 2 (TLS reject) |
| insecure ignores the cert | `mp-tls` | `--insecure` | exit 1 |
| cacert enforces the hostname | `mp-other` (not in SAN) | `--cacert ca.crt` | exit 2 (hostname) |
| insecure bypasses the hostname | `mp-other` | `--insecure` | exit 1 |

## Files

`gen-certs.sh` (private CA + SAN server cert), `nginx.conf`, `Dockerfile.nginx`, `Dockerfile.client`,
`run-tls-tests.sh`, `desired.xml` (a drifting administrator), `../compose.tls.yml`. Generated certs
(`ca.*`, `server.*`) are git-ignored — regenerate with `gen-certs.sh`.
