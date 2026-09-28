# T01 schema validation — 2026-09-15

- Red: `python -m unittest discover -s tests -p test_r2_owner_exception.py -v` failed because evolution 900 did not exist.
- Green: `python generate.py` succeeded.
- Determinism: `python generate.py --check` succeeded.
- Regression: `python -m unittest discover -s tests -v` — 64 tests, OK.
- Historical v1/v1.1/v1.2 contract byte/hash assertions remain unchanged and pass; new slots are filtered by actual schema when rendering historical manifests.
- Docker was restarted by the user. Live PostgreSQL 18.6 applied all 25 migrations; V900 jOOQ generation completed with the three named new tables and 27 POJOs. Evidence: `../../output/t01-jooq-v900-generation.log`. This does not grant production activation.

Current manifest hash (including exact audit resolution hash): `6d0eec2e6672f882de768502ca25f5b5453e85b9e4cd4d02d538b6949963004d`

V900 SQL SHA-256: `366e370330858db713bcd04a53c4542ba47175e53527f581cff29d14f61a9039`

Field contract SHA-256: `bd3518d89a3b35b6f7bdce4f455ed816dcf44c3320c99a3c09ef5a0d1b01c4da`.

The updated 64 schema tests and deterministic generation pass in `../../output/t01-schema-validation-evidence-tests.log` and `../../output/t01-schema-validation-evidence-generation.log`. The earlier hashes below other historical reports describe the pre-audit-evidence implementation and are not the current pins.

- Development projection: 7 exact current/historical/tamper tests pass; see `../../output/t01-schema-gate-tests.log`.
- Full `python scripts/baseline/verify_baseline.py --r2-development`: consistency/admission PASS, R1 PAUSED, R2 release NOT_GRANTED, seven non-fatal readiness blockers; see `../../output/t01-baseline.log`.
- Schema logs: `../../output/t01-schema-tests.log`, `../../output/t01-schema-generation-check.log`.
