# Task 9.6j implementation report

## Result

Implemented the approved read-only `release-verify-current-runtime` gate. The
command runs inside the existing `release-operation.lock`, accepts no arguments,
binds API/SPA/Worker registrations and live processes to the exact current
package, invokes the real `LocalWorker.health` and `runtime_grants_current`
validation path, repeats release/material/process checks, and emits only the
fixed status plus safe release/build/gate/Worker-health fields.

Both existing controlled package forms are covered: `controlled-local-release`
binds the gate to `provenance`, while the already approved
`controlled-local-source-release` binds it to `sourceRelease` and derives the
business binary `sourceCommit` from its preserved `binaryProvenance`. Existing
source-release staging/removal guards were not changed.

## TDD evidence

All commands ran from
`C:\Users\Jacob\.cache\codex-worktrees\ontology-law-r1-business` with
`PYTHONPATH=C:\Users\Jacob\.cache\codex-worktrees\ontology-law-r1-business\deploy\local-login`.

### RED 1: missing consumer

Command:

```powershell
& D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p 'test_local_runtime_verification.py' -v
```

Output (exit 1):

```text
test_current_controlled_release_with_approved_root_rename_is_verified_read_only ... FAIL
AssertionError: current-release runtime verifier is missing
Ran 1 test in 0.001s
FAILED (failures=1)
EXIT_CODE=1
```

The failure was caused by the expected `ModuleNotFoundError` before
`local_runtime_verification.py` existed.

### RED 2: missing named CLI route

Same focused command, output (exit 1):

```text
test_current_controlled_release_with_approved_root_rename_is_verified_read_only ... ok
test_named_release_operation_prints_only_safe_result_and_rejects_arguments ... ERROR
test_old_package_stopped_changed_and_unknown_processes_are_refused ... ok
test_release_gate_schema_package_material_and_pending_state_are_refused ... ok
test_release_or_process_change_during_health_cannot_report_success ... ok
test_worker_facts_certificate_mtls_loops_and_grants_must_all_be_current ... ok
RuntimeError: unknown release operation
Ran 6 tests in 0.872s
FAILED (errors=1)
EXIT_CODE=1
```

### RED 3: current approved source-release compatibility

Same focused command after self-review added the existing source-package shape,
output (exit 1):

```text
test_current_controlled_release_with_approved_root_rename_is_verified_read_only ... ok
test_current_controlled_source_release_uses_binary_source_commit ... ERROR
test_named_release_operation_prints_only_safe_result_and_rejects_arguments ... ok
test_old_package_stopped_changed_and_unknown_processes_are_refused ... ok
test_release_gate_schema_package_material_and_pending_state_are_refused ... ok
test_release_or_process_change_during_health_cannot_report_success ... ok
test_worker_facts_certificate_mtls_loops_and_grants_must_all_be_current ... ok
RuntimeError: current package is not a controlled release
Ran 7 tests in 1.068s
FAILED (errors=1)
EXIT_CODE=1
```

### Final focused GREEN

Same focused command, output (exit 0):

```text
test_current_controlled_release_with_approved_root_rename_is_verified_read_only ... ok
test_current_controlled_source_release_uses_binary_source_commit ... ok
test_named_release_operation_prints_only_safe_result_and_rejects_arguments ... ok
test_old_package_stopped_changed_and_unknown_processes_are_refused ... ok
test_release_gate_schema_package_material_and_pending_state_are_refused ... ok
test_release_or_process_change_during_health_cannot_report_success ... ok
test_worker_facts_certificate_mtls_loops_and_grants_must_all_be_current ... ok
Ran 7 tests in 1.206s
OK
EXIT_CODE=0
```

The success consumer imports `fixture()` and `renamed_runtime_facts()` from
`test_local_worker`, uses real `LocalRelease`, `RuntimeBoundary.processes`,
`LocalWorker.health`, SQL query construction, and `runtime_grants_current`, and
replaces only synthetic SQL/certificate/mTLS/listener/process external edges.
It also proves every temporary protected file remains byte-identical.

## Regression evidence

Focused neighboring suites before the final full run:

```text
test_local_release.py: Ran 27 tests in 47.065s; OK; EXIT_CODE=0
test_local_worker.py:  Ran 32 tests in 10.000s; OK; EXIT_CODE=0
py_compile plus git diff --check: PRECHECK_EXIT_CODE=0
```

Required full command:

```powershell
& D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p 'test_*.py' -q
```

The first full run was at base codehead
`f421df23cf3a1378bb5e15438dc17a16f05d12ff` plus the uncommitted six-test gate,
before self-review added source-release compatibility:

```text
----------------------------------------------------------------------
Ran 89 tests in 65.866s

OK
EXIT_CODE=0
```

Because the compatibility change followed that run, the authoritative final run
was repeated at the same base codehead plus the final seven-test implementation:

```text
----------------------------------------------------------------------
Ran 90 tests in 65.607s

OK
EXIT_CODE=0
```

## Files owned

- `deploy/local-login/local_runtime_verification.py` (new)
- `deploy/local-login/tests/test_local_runtime_verification.py` (new)
- `deploy/local-login/local_login.py` (named CLI wiring only)
- `deploy/local-login/README.md` (operator distinction only)
- `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/task-9.6j-report.md` (this report)

No Java, SPA, SQL, existing Worker validator, dependency, E2E, plan, design,
progress, or acceptance file was changed by this implementation.

## Self-review

- Success output has exactly `status`, `releaseId`, `sourceCommit`,
  `gateRevision`, and the fixed five-field `workerHealth`; it never includes
  original verification status, identities, grants, paths, credentials, or
  private material.
- Exact current-package commands are checked independently of the broader
  lifecycle `RuntimeBoundary.processes` recovery allowance. The test constructs
  a valid historical controlled package that the lifecycle boundary accepts and
  proves this gate rejects it.
- Current pointer/descriptor, package bytes, gate, schema, live config,
  deployment, original materials, exact three-process registration and liveness
  are checked before Worker health and checked again afterward.
- Missing ROOT rename evidence, damaged evidence, original Slot/Receipt/Audit or
  other original-fact drift, grant drift, database/certificate/mTLS/loop/listener
  failures, stopped/unknown/pending/historical processes, and changes during the
  check all fail without a success result.
- The command never calls grant, prepare, start, bootstrap, CAS, migration, or a
  repair path. Existing bootstrap-original functions and result parser are
  unchanged; their existing strict regression tests remain green.

## Remaining risks and handoff

- Tests intentionally use synthetic external adapters and do not establish live
  Windows process, PostgreSQL, certificate, mTLS, browser, or business
  acceptance. Root must run the new command against the already deployed package
  under the protected runtime and retain that evidence.
- The function is a release-operation composition and assumes its public CLI
  caller holds the existing lock; direct unit invocation is not an operator
  entrypoint.
- Like any liveness observation, a process can fail after the final check; the
  result binds the verified snapshot and does not promise future availability.
- No build, deploy, restart, database/service/browser operation, private-runtime
  inspection, or push was performed in this task.
