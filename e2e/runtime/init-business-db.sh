#!/bin/sh
set -eu

read_secret() {
  value="$(tr -d '\r\n' < "/run/r1-secrets/$1")"
  case "$value" in *[!A-Za-z0-9+/=]*|'') echo "invalid generated database secret" >&2; exit 65;; esac
  printf '%s' "$value"
}
migrator="$(read_secret migrator-password)"

{
  printf "CREATE ROLE law_schema_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '%s';\n" "$migrator"
  printf '%s\n' "ALTER DATABASE law_contract_runtime OWNER TO law_schema_migrator;"
  for role in law_app_command law_app_query law_audit_append law_app_worker; do
    printf 'CREATE ROLE %s NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n' "$role"
  done
} | psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
unset migrator
