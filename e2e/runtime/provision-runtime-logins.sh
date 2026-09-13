#!/bin/sh
set -eu

read_secret() {
  value="$(tr -d '\r\n' < "/run/secrets/$1")"
  case "$value" in *[!A-Za-z0-9+/=]*|'') echo "invalid generated database secret" >&2; exit 65;; esac
  printf '%s' "$value"
}
api="$(read_secret api-db-password)"
worker="$(read_secret worker-db-password)"

{
  cat /r1/bootstrap-runtime-logins.sql
  printf "ALTER ROLE law_api_login PASSWORD '%s';\n" "$api"
  printf "ALTER ROLE law_worker_login PASSWORD '%s';\n" "$worker"
  cat /r1/deployment-state.sql
} | PGPASSWORD="$(tr -d '\r\n' < /run/secrets/business-superuser-password)" \
    psql -X -v ON_ERROR_STOP=1 --host business-db --username postgres --dbname law_contract_runtime
unset api worker
