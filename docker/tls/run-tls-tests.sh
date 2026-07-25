#!/usr/bin/env sh
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# TLS smoke tests, run inside the client container against the nginx TLS proxy (mp-tls / mp-other:8443).
# Asserts exit codes; a non-zero container exit means a check failed.
set -u

JAR=/tls/midgie.jar
CA=/tls/ca.crt
DESIRED=/tls/desired.xml
MP_USER=administrator
MP_PW=T3stPw890uio
BASE=https://mp-tls:8443/midpoint      # hostname IS in the cert SAN
OTHER=https://mp-other:8443/midpoint   # same server, hostname NOT in the SAN

# diff the (drifting) desired admin against the live one; prints nothing, we only read the exit code.
run() { java -jar "$JAR" diff "$DESIRED" -u "$MP_USER" -p "$MP_PW" --quiet "$@" >/dev/null 2>&1; }

fails=0
check() { # label expected actual
    if [ "$2" -eq "$3" ]; then
        echo "PASS: $1 (exit $3)"
    else
        echo "FAIL: $1 (expected $2, got $3)"
        fails=$((fails + 1))
    fi
}

echo ">> waiting for midPoint via the TLS proxy (rides out startup 502s/connection-refusals)..."
run --host "$BASE" --insecure --retries 30 || true

echo ">> TLS checks"
run --host "$BASE"  --cacert "$CA"; check "cacert trusts the CA-signed server"          1 $?
run --host "$BASE"                ; check "default trust store rejects our private CA"   2 $?
run --host "$BASE"  --insecure    ; check "insecure ignores the untrusted cert"          1 $?
run --host "$OTHER" --cacert "$CA"; check "cacert enforces hostname (mp-other not SAN)"  2 $?
run --host "$OTHER" --insecure    ; check "insecure bypasses the hostname mismatch"      1 $?

echo ">> $fails failure(s)"
exit "$fails"
