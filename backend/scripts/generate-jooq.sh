#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
case "${1:---check}" in
  --check) mode=check ;;
  --write) mode=write ;;
  *) printf 'Usage: %s [--check|--write]\n' "$0" >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then exit 2; fi
cd "$root"
./mvnw -f backend/pom.xml -Pjooq-generation test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=JooqGenerationIT -DfailIfNoTests=true "-Djooq.generation=$mode"
