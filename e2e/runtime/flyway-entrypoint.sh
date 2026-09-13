#!/bin/sh
set -eu

FLYWAY_PASSWORD="$(tr -d '\r\n' < /run/secrets/migrator-password)"
export FLYWAY_PASSWORD
exec /flyway/flyway migrate validate
