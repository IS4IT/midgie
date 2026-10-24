# Installing midgie

## The JAR

A prebuilt fat JAR is attached to each [release](https://github.com/IS4IT/midgie/releases). The release
notes end with a Downloads section that says what each file is for: the JAR, the Helm chart, the SBOM, and
the container image on Docker Hub with the digest that release built. To build from source instead:

```bash
mvn -q package                       # builds target/midgie.jar (a fat JAR)
bin/midgie --help                    # launcher (recommended)
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

To run it in a container or in Kubernetes instead, see [Container image and Helm chart](container.md).

## Verifying a release

Releases after 0.18.3 can be checked against the workflow that built them. The JAR and the chart carry a build-provenance attestation, the JAR also an attestation of its SBOM (`midgie-<version>.cdx.json`, attached to the release: every library the JAR bundles, with versions, hashes and licences), and the image is signed and attested in Docker Hub. All are signed keylessly through Sigstore, so there is no key to fetch: a signature names the workflow and the git ref it ran from.

```bash
# The JAR, downloaded from the release (the chart's .tgz the same way)
gh attestation verify midgie-<version>.jar --repo IS4IT/midgie \
  --signer-workflow IS4IT/midgie/.github/workflows/release.yml
# ... and that the attached SBOM is the one built with it
gh attestation verify midgie-<version>.jar --repo IS4IT/midgie \
  --signer-workflow IS4IT/midgie/.github/workflows/release.yml --predicate-type https://cyclonedx.org/bom

# The image
gh attestation verify oci://docker.io/is4it/midgie:<version> --repo IS4IT/midgie \
  --signer-workflow IS4IT/midgie/.github/workflows/release.yml
cosign verify docker.io/is4it/midgie:<version> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/IS4IT/midgie/\.github/workflows/release\.yml@refs/(tags/v.+|heads/main)$'
```

The cosign form is the one a cluster admission policy (Kyverno, Sigstore policy-controller) can enforce. The identity admits `heads/main` as well as the version tags because an image rebuilt for an existing release (a base-image fix) is built by a run started by hand from `main`.
