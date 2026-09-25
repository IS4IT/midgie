#!/usr/bin/env sh
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# One-shot TLS test: build the jar, generate certs, build the rig images, and run the client's TLS
# smoke tests. The script returns the client's exit code; the rig is left running afterwards.
# Run from anywhere: docker/tls/run.sh
#
# The stack is started detached and the client run on its own, rather than 'up --abort-on-container-exit':
# that stops everything at the first container to exit, and data_init is a one-shot that exits by design,
# so on an already-running rig it took midPoint down before the proxy could resolve it.
set -eu
cd "$(dirname "$0")/../.."   # repo root

COMPOSE="docker compose -f docker/docker-compose.yml -f docker/compose.tls.yml"

echo ">> building the jar"
mvn -q package -DskipTests

echo ">> generating TLS certs"
docker/tls/gen-certs.sh

echo ">> starting midPoint and the TLS proxy"
$COMPOSE up -d --build mp_tls

echo ">> running the TLS smoke tests"
$COMPOSE run --rm --build mp_diff
