#!/bin/sh
set -eu

install -d -m 0700 -o 1000 -g 0 /prepared/config /prepared/import
install -m 0600 -o 1000 -g 0 /run/secrets/identity-db-password /prepared/config/db-password
install -m 0644 -o 1000 -g 0 /run/secrets/ca /prepared/config/ca.pem
install -m 0644 -o 1000 -g 0 /run/secrets/keycloak-cert /prepared/config/server.crt
install -m 0600 -o 1000 -g 0 /run/secrets/keycloak-key /prepared/config/server.key
install -m 0600 -o 1000 -g 0 /run/secrets/realm /prepared/import/r1-e2e-realm.json
