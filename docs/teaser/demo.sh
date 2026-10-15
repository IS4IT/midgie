#!/usr/bin/env bash
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# On camera: what asciinema records. Run setup.sh first.
set -uo pipefail
source "$(dirname "$0")/env.sh"
cd "$TREE"

PROMPT=$'\e[1;32m~/iam-config\e[0m $ '

type_out() {
    local text=$1
    for ((i = 0; i < ${#text}; i++)); do
        printf '%s' "${text:i:1}"
        sleep 0.045
    done
}

run() {
    printf '%s' "$PROMPT"
    sleep 0.6
    type_out "$1"
    sleep 0.5
    printf '\n'
    eval "$1"
    sleep "${2:-3}"
}

clear
run "midgie deploy . --dry-run" 4
run "midgie deploy . --yes" 6
printf '%s' "$PROMPT"
sleep 1
