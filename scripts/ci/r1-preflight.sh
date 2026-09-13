#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

printf '%s\n' '[r1-preflight] baseline'
python3 scripts/baseline/verify_baseline.py

(
  cd database/schema-contract-52-plus-2
  printf '%s\n' '[r1-preflight] schema static'
  python3 generate.py --check
  python3 -m unittest discover -s tests -v
  python3 scripts/verify_generated_sql.py
  python3 -m unittest discover -s runtime/tests -v

  printf '%s\n' '[r1-preflight] PostgreSQL 18 runtime (two clean runs)'
  python3 runtime/verify_runtime.py validate-promoted-evidence
  python3 runtime/verify_runtime.py \
    verify --ci-only --runs 2 --evidence-dir ../../.artifacts/schema-runtime
  python3 runtime/verify_runtime.py validate-ci-artifact
)

printf '%s\n' '[r1-preflight] backend unit, architecture, integration'
./mvnw -f backend/pom.xml verify -Pit
backend/scripts/generate-jooq.sh --check

printf '%s\n' '[r1-preflight] OpenAPI generation drift'
npm run openapi:check

printf '%s\n' '[r1-preflight] SPA typecheck, test, build'
npm run typecheck
npm test
npm run build

printf '%s\n' '[r1-preflight] offline Playwright'
npm run test:e2e:offline

printf '%s\n' 'R1 preflight passed (not runtime acceptance)'
