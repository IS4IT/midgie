# Security policy

## Reporting a vulnerability

Please do not report a vulnerability in a public issue. Use GitHub's private vulnerability reporting instead: on the repository's **Security** tab, choose **Report a vulnerability**. The report is visible only to the maintainers until an advisory is published.

midgie holds midPoint administrator credentials and writes configuration into a live identity system, so the reports that matter most are the ones that touch that: credentials leaking into output, logs, plan or rollback files; TLS verification that can be bypassed; a tree file or server response that makes midgie address, change or delete an object it should not; and anything in the container image or Helm chart that widens what the pod can reach.

A useful report names the midgie version (`midgie --version`), the midPoint version, how to reproduce it, and what an attacker gains.

## Supported versions

midgie is in 0.x: fixes go into the next release, and only the latest release is supported. There are no backports to older versions.

## Checking what you run

Releases are signed and come with build provenance and an SBOM; [Verifying a release](docs/install.md#verifying-a-release) shows how to check them.
