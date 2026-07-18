#!/usr/bin/env sh
# Copyright (c) 2026 IS4IT
#
# SPDX-License-Identifier: EUPL-1.2
#
# Generate a private CA and a server certificate for the TLS test rig:
#   ca.crt / ca.key      — the private CA (ca.crt is what the client trusts via --cacert)
#   server.crt/server.key — the nginx TLS cert, signed by the CA, SAN=mp-tls (+ localhost, 127.0.0.1)
# Idempotent: skips if the files already exist. Everything lands in this directory (git-ignored).
set -eu
cd "$(dirname "$0")"

if [ -f ca.crt ] && [ -f server.crt ] && [ -f server.key ]; then
    echo "TLS certs already present (ca.crt, server.crt, server.key) — skipping"
    exit 0
fi

echo "Creating CA (CN=midpoint-diff-test-CA)..."
openssl genrsa -out ca.key 4096
openssl req -x509 -new -noenc -key ca.key -sha256 -days 3650 -subj '/CN=midpoint-diff-test-CA' -out ca.crt

echo "Creating server cert (CN=mp-tls, SAN=mp-tls,localhost,127.0.0.1)..."
openssl req -new -noenc -newkey rsa:2048 -keyout server.key -out server.csr -subj '/CN=mp-tls'
cat > server-ext.cnf <<'EXT'
[v3]
subjectAltName=DNS:mp-tls,DNS:localhost,IP:127.0.0.1
EXT
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 3650 -sha256 \
    -extfile server-ext.cnf -extensions v3 -out server.crt
rm -f server-ext.cnf server.csr

echo "Done:"
openssl x509 -in server.crt -noout -subject -ext subjectAltName
