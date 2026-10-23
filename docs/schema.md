# Schema and midPoint versions

## Schema handling

Two layers (full detail in [`DESIGN.md`](../DESIGN.md#schema-two-layers-two-knobs)):

- **Base** model (`UserType`, `RoleType`, … and their item definitions) is **compile-time**, from the
  `midpoint-schema` jar. It cannot be supplied as XML.
- **Extension** schema (custom `extension` attributes, `SchemaType` objects) is **runtime**: from
  `--schema`/`--schema-dir` (raw XSD or exported `SchemaType`), and in live mode read from the server
  automatically unless `--no-live-schema` is set.

The flags are listed under [Schema](reference.md#schema-extension-items) in the reference.

## Which midPoint versions a build serves

The bundled `midpoint-schema` jar is the *compile-time* object model, so it decides which servers midgie can read: its own line, built against the **newest** patch, and the line before it from an oldest supported patch on — today **midPoint 4.11.x and 4.10.3 or later** (`midgie --version` says so). Patch releases change the schema too — mostly by adding items, occasionally by changing a type, as 4.10.5 did for `PasswordType/hint` — and the newest patch knows everything an older one sends; the newer line, likewise, knows everything the older line sends. A live `deploy` and `init` read the server's version (`GET /nodes/current`; not under `--quiet`) and warn when it is outside that range, or on a **newer patch** than the bundled schema; an older patch, or a server on the earlier line, is the safe direction and is only logged under `--debug`. Against a 4.10 server, two things are on you: a desired object that uses an item new in 4.11 (`UserType/displayName`, `credentials/otp`) diffs fine and is rejected by the server at deploy, and an object that still uses the eight OIDC resource-server items 4.11 removed (deprecated since 4.8) does not parse — move them to the `jwt`/`opaqueToken` token definition, which 4.10 already reads.

## Targeting another midPoint version

The bundled base schema is version-specific. To target a different midPoint major line, bump
`<midpoint.version>` in `pom.xml` (default **4.11.0**) **and** `MP_VER` in `docker/.env`, move
`<midpoint.oldestSupported>` (default **4.10.3**) to the line before it, then rebuild. A build-time check
fails if `<midpoint.version>` and `MP_VER` drift apart. `bin/midgie --version` prints the tool version
(from the git tag the build was cut from), the bundled schema and the servers it serves.
