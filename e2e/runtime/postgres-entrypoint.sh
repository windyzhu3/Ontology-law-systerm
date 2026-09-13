#!/bin/sh
set -eu

kind="${1:?identity or business is required}"
shift
case "$kind" in
  identity) names="identity-db-password identity-db-cert identity-db-key" ;;
  business) names="business-superuser-password business-db-cert business-db-key migrator-password api-db-password worker-db-password" ;;
  *) echo "unsupported database kind" >&2; exit 64 ;;
esac

install -d -m 0700 -o postgres -g postgres /run/r1-secrets
for name in $names; do
  install -m 0600 -o postgres -g postgres "/run/secrets/$name" "/run/r1-secrets/$name"
done
exec /usr/local/bin/docker-entrypoint.sh "$@"
