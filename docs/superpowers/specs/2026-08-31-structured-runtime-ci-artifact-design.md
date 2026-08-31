# Structured PostgreSQL Runtime CI Artifact Design

**Status:** Approved
**Date:** 2026-08-31
**Applies to:** `database/schema-contract-52-plus-2/runtime` and `.github/workflows/schema-contract-52-plus-2.yml`

## Context

The PostgreSQL 18 runtime verifier must preserve failure evidence from GitHub Actions without publishing credentials, connection strings, temporary paths, or arbitrary process output. Five reviewed repair rounds showed that block-list redaction of free-form `command`, `stdout`, and `stderr` cannot be the security boundary: valid quoting, continuation, path, and serialization forms still admit disclosure.

The branch must not be pushed with the current workflow because it uploads the full raw evidence tree with `if: always()`.

## Decision

GitHub Actions will upload only a closed, allow-listed structured artifact. Raw subprocess evidence remains ephemeral on the runner and is never included in the upload path.

Existing local raw evidence and redaction remain available as defense in depth for developer diagnosis, but no security or governance decision may depend on proving that arbitrary text was fully sanitized.

## Artifact Boundary

The workflow upload root is fixed to:

```text
.artifacts/schema-runtime-ci/
```

It contains only:

```text
ci-runtime-summary.json
ci-job-summary.md
```

No recursive copy from `.artifacts/schema-runtime/` is permitted.

### JSON Schema

`ci-runtime-summary.json` is generated from typed controller results, not by transforming stored process logs. It has these exact top-level fields:

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | string | Exactly `postgresql-runtime-ci-artifact-v1` |
| `gitCommit` | string | Lowercase 40-hex clean snapshot commit |
| `workflowOutcome` | enum | `PASSED`, `FAILED`, or `BLOCKED` |
| `reasonCode` | enum | Closed diagnostic-code set |
| `runs` | array | Exactly `run-01`, `run-02` in order |
| `failureScenarios` | array | Closed five-scenario result set when reached |
| `toolchain` | object | Locked image names/tags/digests and allow-listed versions only |
| `contractSummary` | object | 19/54/13/206/53 facts and contract hashes when verified |

Each run or scenario stage contains only:

- semantic `stageName` from a fixed enumeration;
- `commandClass` from a fixed enumeration;
- integer `exitCode` or `null` when not started;
- boolean `timedOut`;
- allow-listed `diagnosticCode`;
- SHA-256 hashes of captured stdout and stderr bytes.

The schema excludes command arguments, environment values or names, stdout/stderr text, working directories, evidence paths, Compose project names, container identifiers, connection values, temporary filenames, and exception messages.

Unknown fields, unknown enums, invalid stage order, malformed hashes, or an inconsistent outcome make export fail closed.

### Markdown Summary

`ci-job-summary.md` is rendered only from the validated JSON object. It contains outcome, reason code, stage names, exit codes, timeouts, and hashes. It does not accept free-form runtime strings.

## Data Flow

1. The verifier captures a clean Git snapshot.
2. Runtime subprocesses execute with their original arguments.
3. Raw diagnostic files are written under `.artifacts/schema-runtime/` for the lifetime of the runner.
4. The controller returns typed run, stage, scenario, toolchain, and contract facts.
5. A structured exporter validates the closed schema and atomically writes `.artifacts/schema-runtime-ci/ci-runtime-summary.json`.
6. The Markdown summary is rendered from that validated object and written atomically.
7. The workflow uploads only `.artifacts/schema-runtime-ci/` with `if: always()` and 90-day retention.
8. Runner cleanup discards raw evidence after the job.

If verification fails before typed results exist, the exporter writes a minimal closed artifact containing the clean commit, `FAILED` or `BLOCKED`, an allow-listed reason code, and `not_started` stage records. It never falls back to raw logs or exception text.

## Failure and Security Semantics

- Export failure must not change the runtime step's original failure into success.
- Summary and upload steps remain `if: always()`, but upload failure also fails the job.
- Raw evidence paths must be explicitly excluded from the upload configuration.
- Fork pull requests receive read-only contents permission, no repository secrets, and no persisted checkout credentials.
- Full Git history remains required for baseline ancestry verification.
- The structured exporter uses atomic pair publication and rejects symlink or non-regular targets.
- Successful repository evidence under `docs/evidence/schema-runtime/` remains a separate Task 4 boundary and is created only after the prescribed real runtime gates pass.

## Testing Strategy

Implementation follows test-driven development.

1. Add failing tests proving arbitrary secrets, quoted paths, multiline assignments, raw commands, stdout, and stderr cannot appear because those fields are absent from the schema.
2. Add exact-schema, enum, stage-order, hash-format, failure-path, atomic-write, symlink, and unknown-field tests.
3. Add workflow contract tests proving only `.artifacts/schema-runtime-ci/` is uploaded and the raw tree is never referenced by `upload-artifact`.
4. Preserve baseline, schema, generated-SQL, runtime orchestration, evidence-publication, YAML, and shell syntax suites.
5. Run the local real entry point; in the current executor it must remain truthfully `BLOCKED/docker_compose_unavailable` without creating successful repository evidence.
6. After push, require the hosted runtime job and inspect the structured artifact before any ledger transition.

## Rollout and Git Integration

1. Commit the structured exporter, tests, and workflow upload boundary on `test/schema-contract-postgresql-18`.
2. Complete an independent task review and whole-branch review.
3. Fetch `origin/main` again and require it to remain an ancestor of the feature branch; otherwise integrate the new commits and rerun all gates.
4. Push the feature branch only after the disclosure findings are closed.
5. Open or update the pull request so the Docker-capable hosted job runs.
6. Do not update `DB-52P2-PG18-RUNTIME`, fixed success evidence, or R1 state until the real runtime and hosted CI gates are satisfied.

## Alternatives Considered

### Continue Free-Form Redaction

Rejected. Five review rounds produced new valid encodings and boundary cases. Arbitrary-text sanitization is not a finite, reviewable trust boundary.

### Upload Only a Step Summary

Safe but insufficient. It would remove the durable machine-readable artifact required for review and 90-day retention.

### Closed Structured Artifact

Selected. It preserves reproducible stage outcomes and integrity hashes while making disclosure prevention a finite schema property rather than an open-ended parsing problem.

## Acceptance Criteria

- The upload path contains exactly the two structured files.
- Neither structured file can represent command arguments, raw output, environment data, connection data, or filesystem paths.
- Unknown data fails export instead of being dropped.
- Runtime failure still produces a minimal safe artifact and a failed job.
- Existing static suites pass and successful repository evidence remains absent locally.
- Independent review finds no Critical or Important artifact-disclosure issue.
- The feature branch is based on the latest fetched `origin/main` before push.
