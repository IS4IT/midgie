#!/usr/bin/env sh
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# One-shot TLS test: build the jar, generate certs, build the rig images, and run the client's TLS
# smoke tests. `--exit-code-from mp_diff` makes this whole command return the client's exit code.
# Run from anywhere: docker/tls/run.sh
set -eu
cd "$(dirname "$0")/../.."   # repo root

echo ">> building the jar"
mvn -q package -DskipTests

echo ">> generating TLS certs"
docker/tls/gen-certs.sh

echo ">> building images + running the TLS smoke tests"
docker compose -f docker/docker-compose.yml -f docker/compose.tls.yml up --build \
    --abort-on-container-exit --exit-code-from mp_diff
