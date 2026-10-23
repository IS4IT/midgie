# Test rig: a real midPoint server (Docker Compose)

Spins up a midPoint server backed by PostgreSQL so `midgie` can be exercised
against a live repository over REST — the target for the `diff --url` and `deploy`
live modes. Adapted from the `connector-sap` rig (itself from
[Evolveum/midpoint-docker](https://github.com/Evolveum/midpoint-docker)), stripped to
just the midPoint + PostgreSQL stack.

## Quick start

```bash
# from the project root
docker compose -f docker/docker-compose.yml up -d      # first start (DB init + repo init + server)
```

First start pulls the midPoint (~1 GB) and PostgreSQL images and initialises the native
repository schema, then boots the server — allow a few minutes. Watch progress with:

```bash
docker compose -f docker/docker-compose.yml logs -f mp_server
```

- GUI: http://localhost:12080/midpoint — `administrator` / `T3stPw890uio`
- REST base: `http://localhost:12080/midpoint/ws/rest`
- Logs: `docker compose -f docker/docker-compose.yml logs -f mp_server`
- DB: `docker compose -f docker/docker-compose.yml exec mp_data psql -U midpoint` (password `db.secret.pw.007`)
- Stop (keep data): `docker compose -f docker/docker-compose.yml down`
- Tear down: `docker compose -f docker/docker-compose.yml down -v` (`-v` wipes volumes)

The GUI/REST host port is **MP_PORT** (default 12080) and PostgreSQL is intentionally
**not** published on the host, so this rig can run alongside other midPoint/Postgres
test stacks (e.g. `connector-sap` on 11080) without port clashes.

## Version coupling (read before bumping)

`midgie` bundles midPoint's **compile-time base schema** for the version pinned by
`<midpoint.version>` in `pom.xml`. It serves that line and the line before it from
`<midpoint.oldestSupported>` on; a server outside that range can be misparsed. The rig runs
the bundled version: both are pinned to **4.11.0** — to target another version, bump `MP_VER`
in `docker/.env` **and** `midpoint.version` in `pom.xml` together, then rebuild.

To run the live tests against the earlier line as well, start a second rig under its own
project name with `MP_VER` overridden for that run only (the build's version check reads
`docker/.env`, which stays as it is). Run one rig at a time — they share the host port:

```bash
docker compose -f docker/docker-compose.yml stop                              # the 4.11.0 rig
MP_VER=4.10.5 docker compose -f docker/docker-compose.yml -p midgie_410 up -d  # the 4.10.5 rig
mvn verify
docker compose -f docker/docker-compose.yml -p midgie_410 stop
```

A rig's volumes belong to the midPoint version that created them. Moving a rig to a new
line needs `down -v` first: the 4.11 image runs as the non-root user `midpoint` (UID 1000)
and cannot write a volume a 4.10 container created as root, and 4.11 changed the repository's
database schema.

Versions and the port are pinned in `docker/.env`: `MP_VER` (midPoint image tag),
`PG_VER` (PostgreSQL major version), `MP_PORT` (GUI/REST host port).

## Smoke test (the exact call `--url` will make)

Once `mp_server` is up, fetch the built-in `administrator` user over REST — the same
`GET /ws/rest/{type}/{oid}` the live diff mode will use:

```bash
curl -sk -u administrator:T3stPw890uio \
  -H 'Accept: application/xml' \
  http://localhost:12080/midpoint/ws/rest/users/00000000-0000-0000-0000-000000000002
```

A `200` with a `<user>` document means REST is ready. (`00000000-0000-0000-0000-000000000002`
is the well-known OID of `administrator`.) On first boot REST may briefly return `503`/refuse
the connection while midPoint finishes starting — retry until the `<user>` comes back.

## Using it with midgie

Point the live modes straight at the rig — they GET/PATCH over REST for you:

```bash
# diff a desired object against the live one
java -jar target/midgie.jar diff desired.xml \
  --url http://localhost:12080/midpoint -u administrator -p T3stPw890uio

# reconcile it (PATCH, or POST to create it if it is not there — the default)
java -jar target/midgie.jar deploy desired.xml \
  --url http://localhost:12080/midpoint -u administrator -p T3stPw890uio
```

Or diff two local files offline (no server): `java -jar target/midgie.jar diff desired.xml live.xml`.

## Live integration tests

`LiveIntegrationTest` drives the real CLI over REST against this rig (create → diff → deploy →
idempotent re-deploy, plus a no-drift check), covering the live paths the offline unit tests cannot. It is
**self-gating**: when the rig is unreachable the tests skip, so `mvn test` stays green without docker.

```bash
docker compose -f docker/docker-compose.yml up -d   # rig up  -> live tests run
mvn test                                            # rig down -> they report Skipped
```

Connection defaults match `docker/.env`; override with `-Dmidgie.it.url=…`, `-Dmidgie.it.user=…`,
`-Dmidgie.it.password=…`. The tests use one throwaway user (`zz-it-user`), deleted before and after the run.
