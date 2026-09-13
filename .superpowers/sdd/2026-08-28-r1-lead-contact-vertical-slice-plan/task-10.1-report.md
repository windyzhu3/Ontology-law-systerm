# Task 10.1 implementation report

## Scope and result

Implemented the bounded R1 CI preflight wiring only. The new Bash driver runs the
existing gates in this fixed fail-fast order: baseline; schema static checks;
PostgreSQL 18 two-run verification and evidence validation; one Maven
`verify -Pit` lifecycle plus jOOQ drift check; OpenAPI drift check; SPA
typecheck/test/build; and the two explicitly offline Playwright projects.

The workflow uses the approved action SHAs, Python 3.12.14, Java 25.0.4.1,
Node 24.20.0, npm 11.9.0, and the repository's pinned Python/npm dependencies.
It has read-only contents permission, full checkout history, no persisted checkout
credential, no deployment/push operation, and no artifact upload.

## TDD evidence

Initial RED, before the driver and workflow existed:

```text
Command: D:\soft\python3\python.exe -m unittest tests.test_r1_preflight -v
Exit: 1
Result: 3 tests failed as intended: R1 preflight driver is missing (2),
        R1 preflight workflow is missing (1).
```

The first GREEN attempt exposed only a Windows test-harness issue: `bash` resolved
to `C:\Windows\system32\bash.exe` (the WSL launcher), which emitted a non-UTF-8
diagnostic. `Get-Command bash -All` and `shutil.which('bash')` confirmed that root
cause. The test harness was then bound to the supplied Git Bash executable; no
production behavior or assertion was relaxed.

GREEN after the minimal implementation and harness correction:

```text
Command: D:\soft\python3\python.exe -m unittest tests.test_r1_preflight -v
Exit: 0
Result: Ran 3 tests in 1.557s; OK.
```

The behavior tests execute the real copied Bash driver against controlled external
commands in a temporary repository. They assert the literal call order and bounded
paths, exact exit-code 23 propagation at the Maven stage, absence of every later
call after that failure, and a preflight-only success statement.

Offline-entry mutation RED:

```text
Command: D:\soft\python3\python.exe -m unittest \
         tests.test_r1_preflight.R1PreflightWorkflowTest -v
Temporary mutation: offline-harness -> approved-local in test:e2e:offline
Exit: 1
Result: semantic package-script assertion rejected the approved-local selection.
```

The required `offline-harness` value was restored before final verification.

## Bounded verification

```text
Command: C:\Program Files\Git\bin\bash.exe -n scripts/ci/r1-preflight.sh
Exit: 0
Output: none

Command: <fixed-node> node_modules/@playwright/test/cli.js test \
         --config playwright.config.ts --project offline-harness --list --reporter list
Exit: 0
Output: Total: 34 tests in 1 file; all listed under [offline-harness].

Command: <fixed-node> node_modules/@playwright/test/cli.js test \
         --config e2e/business.config.ts --project offline-business --list --reporter list
Exit: 0
Output: Total: 220 tests in 4 files; all listed under [offline-business].

Command: D:\soft\python3\python.exe scripts/verify_topology.py
Exit: 0
Output: R1 topology verification passed
```

`<fixed-node>` is
`C:\Users\Jacob\.cache\codex-runtimes\ontology-law-prb\node-v24.20.0-win-x64\node.exe`.
`NO_COLOR=1` was set and `FORCE_COLOR` cleared for both discovery commands.

## Limitations and acceptance boundary

- The complete driver was not run locally because that would start the database,
  backend, SPA, and existing broad suites excluded by this task's verification
  boundary. Its orchestration behavior was tested with controlled real subprocesses.
- The two Playwright commands used `--list`; they proved controlled project
  discovery, not browser execution or real R1 E2E acceptance.
- GitHub Actions was not executed locally. A hosted runner must execute the full
  workflow before any claim about that CI run can be made.
- No private Task 9 runtime, account, certificate, service, evidence, or
  `deploy/local-login/local_login.py` path was read or invoked.
- Real golden/failure browser paths remain unimplemented here. W09 remains deferred
  by the user. A green preflight is not R1 runtime acceptance, does not mark any row
  `RUNTIME_VERIFIED`, and does not admit R2.

## Review round 1 correction

Review 01 found that the schema/static and runtime unittest discovery commands ran
from the repository root, where their top-level `contract` and `runtime` imports
are unavailable. The schema block now runs in
`database/schema-contract-52-plus-2` inside a subshell. The runtime evidence argument
is `../../.artifacts/schema-runtime`, which still resolves to the repository-root
artifact directory, and the parent shell remains at the repository root afterward.

Regression RED against commit `b0ffd32`:

```text
Command: D:\soft\python3\python.exe -m unittest \
         tests.test_r1_preflight.R1PreflightDriverTest -v
Exit: 1
Result: 3 failures. Expected schema CWD differed from actual root CWD; the injected
        runtime-test discovery failure was not reached and returned 0 instead of 19.
```

GREEN after the minimal CWD correction:

```text
Command: D:\soft\python3\python.exe -m unittest \
         tests.test_r1_preflight.R1PreflightDriverTest -v
Exit: 0
Result: Ran 3 tests in 2.057s; OK.

Command: D:\soft\python3\python.exe -c \
         "import contract.schema_contract; import runtime.tests.test_hosted_evidence_promotion; print('schema package imports: OK')"
Working directory: database/schema-contract-52-plus-2
Exit: 0
Output: schema package imports: OK

Command: C:\Program Files\Git\bin\bash.exe -n scripts/ci/r1-preflight.sh
Exit: 0
Output: none
```

No full schema suite, database runtime, Playwright run/listing, topology suite,
private runtime, account, service, or hosted workflow was executed in this fix round.

Final fix-round test and diff evidence (captured before commit, not rerun for this
report-only update):

```text
Command: D:\soft\python3\python.exe -m unittest tests.test_r1_preflight -v
Exit: 0
Output:
test_intermediate_failure_preserves_exit_code_and_stops_later_stages (tests.test_r1_preflight.R1PreflightDriverTest.test_intermediate_failure_preserves_exit_code_and_stops_later_stages)
Break caught: a failed backend gate is masked or later consumers still run. ... ok
test_schema_failure_preserves_exit_code_and_stops_before_runtime (tests.test_r1_preflight.R1PreflightDriverTest.test_schema_failure_preserves_exit_code_and_stops_before_runtime)
Break caught: a schema import failure is masked or runtime verification starts. ... ok
test_success_runs_the_fixed_bounded_order_and_claims_only_preflight (tests.test_r1_preflight.R1PreflightDriverTest.test_success_runs_the_fixed_bounded_order_and_claims_only_preflight)
Break caught: a stage is skipped, reordered, widened, or reported as acceptance. ... ok
test_workflow_uses_pinned_tools_and_the_single_driver_without_write_authority (tests.test_r1_preflight.R1PreflightWorkflowTest.test_workflow_uses_pinned_tools_and_the_single_driver_without_write_authority)
Break caught: CI bypasses the driver, floats a toolchain, or gains write authority. ... ok

----------------------------------------------------------------------
Ran 4 tests in 1.922s

OK

Command: git diff --check; git status --short
Combined exit: 0
git diff --check output: none
git status --short output:
 M docs/superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md
```

The status line is the pre-existing Root-owned plan modification; it was not staged
or changed by this task.
