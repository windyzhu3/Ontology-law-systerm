# Task 9.6d implementation report

Status: DONE (implementation and synthetic verification; real publication and UAT remain controller work).

## Scope and result

Implemented the approved fixed local AUTO configuration release from task-9.6d-brief.md. Started from `5b990c52eb4b09b5f6e5022e1963923f59545e7b`; preserved the controller's concurrent documentation commit `8c98755`.

- `stage-local-auto-source <operatorCommit>` accepts exactly one full current clean tool commit. It only stages a new immutable package and prints its ID, under the existing release-operation lock.
- The new single-purpose module validates the original five MANUAL source properties and closed known local configuration keys, rejects duplicate/conflicting/unknown/escaped inputs, and appends exactly the five approved `LOCAL_SYNTHETIC_AUTO` properties. Existing bytes remain unchanged except the already established API gate manifest replacement. The original SERVICE source binding stays `LOCAL_SYNTHETIC` only.
- The closed `LOCAL_SYNTHETIC_SOURCE_RELEASE_V1` manifest binds exact parent ID/descriptor/manifest hashes, original configuration hash, literal fixed delta, separate operator commit, inherited business provenance and identical Jar/SPA/host hashes. It retains the existing release digest and does not fabricate new `buildExitCodes` or a new business build. Bytes come exclusively from the verified saved parent package; live target/dist/server changes cannot influence staging.
- Parent configuration and deployment gate values must match the current gate; current parent, gate/schema, original materials, source schema fingerprint, provenance and copied bytes are checked before sealing. Activation remains the existing exact-parent, three-consumer, seven-field Owner CAS workflow.
- Source removal checks run after `stopped()` and before journal, CAS or installation writes. Normal switch/rollback checks current and live config; interrupted recovery additionally checks both saved journal sides. A malformed or ambiguous AUTO policy fails closed. A potential removal queries any `lead.lead` fact for `LOCAL_SYNTHETIC_AUTO`, irrespective of task state, using the existing read-only migration Owner adapter and exact original operator Tenant. Unknown results and Owner errors propagate as failures. Forward business upgrades preserving the AUTO policy do not perform this unrelated fact check.
- Recovery retains explicit direction selection and the existing lost-response journal states. Cases tested include unapplied CAS, applied CAS with lost response, partially installed files, reverse recovery CAS both applied and unapplied, and a lost response during ordinary rollback. A fact-blocked removal leaves package/pointer/journal files and CAS count unchanged.
- Existing API/Worker lifecycle code remains in place: `paths()` returns the new common manifest, and the already approved registered Worker lifecycle updates its expectations on prepare/start. No Worker lifecycle relaxation or M1 marker clearing was introduced.

No protected real `local-login-runtime` file was read or changed. No real service, DB, IdP, credential, browser, business-fact or build operation was performed. Synthetic fixture files, temporary Git repositories, temporary certificates and new hidden disposable test processes were used by the affected suites. The original Documents checkout was untouched. No Java, SPA, migrations, dependency, product authorization or SERVICE binding code changed; no push occurred.

## TDD evidence

All commands below ran in `C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business`.

Initial focused RED, before adding the implementation module:

```powershell
& D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p test_local_source_release.py -v
```

Actual output excerpt:

```text
AssertionError: False is not true : fixed local source release module is missing
Ran 12 tests in 0.005s
FAILED (failures=12)
```

Exit code: **1**. Expected failure: the fixed-source module did not exist. The first earlier discovery accidentally exposed the imported existing `ReleaseTest` class and ran 39 tests (27 old pass, 12 expected new failures; exit 1). The import was changed to module-qualified fixture reuse, and the bounded 12-test RED above was then rerun before implementation.

Initial GREEN, same focused command after implementing the fixed delta, staging, boundary checks and recovery guard:

```text
Ran 12 tests in 6.039s
OK
```

Exit code: **0**.

Self-review RED after adding a regression for internally consistent file digests but mismatched parent API gate values, same focused command:

```text
FAIL: test_parent_config_gate_mismatch_is_not_carried_into_new_source_package
AssertionError: RuntimeError not raised
Ran 17 tests in 8.534s
FAILED (failures=1)
```

Exit code: **1**. Expected failure: parent API configuration had not yet been explicitly compared with the current gate. Added that comparison for both API properties and deployment metadata.

Final focused GREEN, same command:

```text
Ran 17 tests in 7.838s
OK
```

Exit code: **0**. The additional cases cover linked saved artifacts, exact CLI argument validation and non-activation, untracked tooling, current parent mismatch, reverse recovery branches and explicit abort after unapplied CAS.

## Final affected regression

Used the pinned Python, JDK and Node. Full affected Python suite was run once after the last production change:

```powershell
$env:JAVA_HOME = 'C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1'
$env:PATH = $env:JAVA_HOME + '/bin;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;' + $env:PATH
& D:/soft/python3/python.exe -m unittest discover -s deploy/local-login/tests -p 'test_local_*.py' -v
```

Actual result:

```text
Ran 75 tests in 66.366s
OK
```

Exit code: **0**, no failures or skips. Includes local runner, release, new source release and Worker suites. Existing exact Worker ownership, manifest/config refresh, certificate preservation, grant fences and retained M1 marker tests passed.

```powershell
& C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64/node.exe --test deploy/local-login/tests/server.test.mjs
```

Actual result:

```text
tests 4
suites 0
pass 4
fail 0
cancelled 0
skipped 0
todo 0
duration_ms 171.6795
```

Exit code: **0**. `git diff --check` also returned **0**, with no output.

## Changed files

- `deploy/local-login/local_source_release.py` (new): fixed delta, strict property validation, saved-byte staging and removal guard.
- `deploy/local-login/tests/test_local_source_release.py` (new): 17 synthetic behavioral tests with existing release fixture reuse.
- `deploy/local-login/local_release.py`: switch/recovery guard calls; bounded operator Git provenance and original-Tenant read-only Owner query adapters.
- `deploy/local-login/local_login.py`: the one named fixed source staging command.
- `deploy/local-login/README.md`: reviewed command, provenance semantics and recovery/removal constraints.
- This report.

## Self-review and limits

Read the complete new module, affected release/runner diff and test paths. The review found and fixed the parent configuration/gate consistency gap through an observed RED/GREEN regression. Existing large `local_release.py` was changed only at the narrow switch/recovery and adapter boundaries. No unrelated restructuring was performed.

The properties parser intentionally accepts the original generator's unescaped key=value representation (including CRLF line endings) and fails closed on alternative/ambiguous syntax. Malformed AUTO configuration requires investigation rather than automatic repair. A linked artifact boundary test substitutes the filesystem link predicate for a synthetic file; the production guard uses the real link/junction checks. DB outcomes are synthetic at the existing SQL boundary; this report does not claim live Owner SQL execution or live removal/rollback verification.

No implementation concern remains known. The pre-existing M1 pending-launch marker limitation remains exactly as documented: even after proven owned processes stop, the final stop check can fail until the controller explicitly reconciles exact ownership and clears only the reviewed marker. Real staging, stop/activate/start, matching API/Worker manifest/readiness, login/original closure, and unused-source rollback/republication remain the controller's next reviewed operations. Seven-card business facts and real HUMAN UAT are not established by this unit.
