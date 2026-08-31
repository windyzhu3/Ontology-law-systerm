"""Safe, standard-library orchestration for PostgreSQL runtime verification."""

from __future__ import annotations

import argparse
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import html
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import posixpath
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile


_DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
_RUN_ID_PATTERN = re.compile(r"[a-z0-9-]+\Z")
_TAG_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
_LOCK_FIELDS = ("image", "tag", "digest", "resolvedAt")
_REQUIRED_LOCK_IMAGES = ("postgres", "redgate/flyway")
_REQUIRED_IMAGE_TAGS = {"postgres": "18", "redgate/flyway": "13.4.0"}
_DEFAULT_TIMEOUT_SECONDS = 60.0
_MAX_TIMEOUT_SECONDS = 300.0
_POSTGRES_START_TIMEOUT_SECONDS = 180.0
_FLYWAY_TIMEOUT_SECONDS = 240.0
_VERIFIER_TIMEOUT_SECONDS = 240.0
_CLEANUP_TIMEOUT_SECONDS = 120.0
_IDENTITY_TIMEOUT_SECONDS = 120.0
_FLYWAY_OPTIONS = (
    "-connectRetries=30",
    "-validateMigrationNaming=true",
    "-cleanDisabled=true",
    "-baselineOnMigrate=false",
    "-defaultSchema=platform_meta",
    "-schemas=identity,audit,responsibility,execution,external_action,evidence,party,lead,opportunity,conflict,contract,transfer,platform_meta",
    "-locations=filesystem:/flyway/sql",
    "-placeholders.app_command_role=law_app_command",
    "-placeholders.app_worker_role=law_app_worker",
    "-placeholders.app_query_role=law_app_query",
    "-placeholders.audit_append_role=law_audit_append",
)
_FAILURE_SCENARIOS = (
    ("missing-role", None, "configured application database role does not exist", "V830-migrate"),
    ("extra-managed-table", "extra_managed_table.sql", "expected 52 application tables", "V840-migrate"),
    ("forbidden-delete-grant", "forbidden_delete_grant.sql", "forbidden DELETE or TRUNCATE", "V840-migrate"),
    ("missing-mutation-guard", "missing_mutation_guard.sql", "mutation guard coverage mismatch", "V840-migrate"),
)
_SENSITIVE_NAME_PATTERN = r"(?:password|passwd|pwd|pgpassword|token|secret|api[_-]?key|access[_-]?token|client[_-]?secret)"
_ASSIGNMENT_PATTERN = re.compile(
    rf"(?i)(?P<prefix>\b{_SENSITIVE_NAME_PATTERN}\b(?:[\"']|\\[\"'])?\s*(?:=|:)\s*)"
    r"(?P<value>'[^']*'|\"[^\"]*\"|\\\"(?:[^\"\\]|\\.)*\\\"|[^\s,;\"']+)"
)
_URL_USERINFO_PATTERN = re.compile(r"(?i)\b(?P<scheme>[a-z][a-z0-9+.-]*://)[^/\s@]+@")
_BEARER_PATTERN = re.compile(
    r"(?i)(?P<prefix>(?:\bauthorization\s*:\s*)?\bbearer\s+)[^\s,;\"'()[\]{}]+"
)
_POSTGRES_CONNECTION_PATTERN = re.compile(
    r"(?i)\b(?:jdbc:postgresql:(?://)?|postgres(?:ql)?://)[^\s\"'<>\\]+"
)
_RUNTIME_ENV_ASSIGNMENT_START_PATTERN = re.compile(
    r"(?i)(?<![A-Za-z0-9_])(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*"
)
_RUNTIME_ENV_LINE_ASSIGNMENT_PATTERN = re.compile(r"[ \t]*[A-Za-z_][A-Za-z0-9_]*\s*=")
_RUNTIME_ENV_NAME_PATTERN = re.compile(r"(?i)\A\s*(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*=")
_RUNTIME_PATH_OPTIONS = {
    "--env-file": "[RUNTIME_ENV_FILE]",
    "-f": "[RUNTIME_COMPOSE_OVERRIDE]",
    "--file": "[RUNTIME_COMPOSE_OVERRIDE]",
}
_SENSITIVE_FLAG_PATTERN = re.compile(rf"(?i)--{_SENSITIVE_NAME_PATTERN}\Z")
_COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
_FINGERPRINT_PATTERN = re.compile(r"[0-9a-f]{32}\Z")
_UTC_TIMESTAMP_PATTERN = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z")
_POSTGRES_VERSION_PATTERN = re.compile(
    r"PostgreSQL 18\.[0-9]+(?:\.[0-9]+)?(?: \([^)]+\))? on [^\r\n]+, compiled by [^\r\n]+, (?:32|64)-bit\Z"
)
_SERVER_VERSION_PATTERN = re.compile(r"18\.[0-9]+(?:\.[0-9]+)?(?: [^\r\n]+)?\Z")
_FLYWAY_VERSION_PATTERN = re.compile(r"Flyway (?:Community|OSS) Edition 13\.4\.0 by Redgate\Z")
_PUBLISH_SCHEMA_FIELDS = (
    "schemaVersion",
    "status",
    "gitCommit",
    "verifiedAtUtc",
    "images",
    "postgresVersion",
    "flywayVersion",
    "runs",
    "failureScenarios",
    "contractSummary",
)
_PUBLISH_IMAGE_FIELDS = ("image", "lockedTag", "lockedDigest", "actualRepoDigest")
_PUBLISH_RUN_FIELDS = ("id", "fingerprint", "stages", "noopMigrate")
_PUBLISH_STAGE_FIELDS = ("name", "exitCode")
_PUBLISH_NOOP_FIELDS = ("exitCode", "fingerprint")
_PUBLISH_FAILURE_FIELDS = (
    "name",
    "expectedMessage",
    "actualMessage",
    "expectedPhase",
    "actualPhase",
    "expectedResult",
    "actualResult",
    "expectedReturnCode",
    "returnCode",
    "stages",
)
_PUBLISH_CONTRACT_FIELDS = (
    "contractVersion",
    "migrationCount",
    "managedTableCount",
    "managedSchemaCount",
    "physicalForeignKeyCount",
    "mutationGuardCount",
    "contractSha256",
    "fieldContractSha256",
)
_RAW_RESULT_FIELDS = ("failureScenarios", "runs", "runtimeIdentity", "status")
_RAW_RUN_FIELDS = (
    "cleanupReturnCode",
    "flyway",
    "id",
    "initialFingerprint",
    "noopFingerprint",
    "noopMigrateReturnCode",
    "noopVerifier",
    "project",
    "postgresReturnCode",
    "upReturnCode",
    "verifier",
)
_RAW_SERVICE_FIELDS = (
    "exitCode",
    "logsReturnCode",
    "service",
    "startReturnCode",
    "statusReturnCode",
    "summary",
    "waitReturnCode",
)
_RAW_SUMMARY_FIELDS = ("fingerprint", "postgresVersion", "serverVersion", "status")
_RAW_FAILURE_FIELDS = (
    "actualPhase",
    "actualMessage",
    "actualResult",
    "baselineReturnCode",
    "cleanupReturnCode",
    "expectedMessage",
    "expectedPhase",
    "expectedResult",
    "expectedReturnCode",
    "messageMatched",
    "mutationReturnCode",
    "name",
    "postgresReturnCode",
    "project",
    "returnCode",
    "status",
)
_RAW_CHECKSUM_FAILURE_FIELDS = (
    "actualPhase",
    "actualMessage",
    "actualResult",
    "expectedMessage",
    "expectedPhase",
    "expectedResult",
    "expectedReturnCode",
    "messageMatched",
    "name",
    "returnCode",
    "status",
)
_RUN_STAGE_NAMES = (
    (
        "postgres-start",
        "flyway-start",
        "flyway-wait",
        "flyway-status",
        "verifier-start",
        "verifier-wait",
        "verifier-status",
        "verifier-logs",
        "noop-migrate",
        "noop-verifier-start",
        "noop-verifier-wait",
        "noop-verifier-status",
        "noop-verifier-logs",
        "compose-down",
    ),
    (
        "postgres-start",
        "flyway-start",
        "flyway-wait",
        "flyway-status",
        "verifier-start",
        "verifier-wait",
        "verifier-status",
        "verifier-logs",
        "compose-down",
    ),
)
_PUBLISH_FAILURE_DEFINITIONS = (
    (
        "missing-role",
        "configured application database role does not exist",
        "V830-migrate",
        ("postgres-start", "V830-migrate", "compose-down"),
    ),
    (
        "extra-managed-table",
        "expected 52 application tables",
        "V840-migrate",
        ("postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"),
    ),
    (
        "forbidden-delete-grant",
        "forbidden DELETE or TRUNCATE",
        "V840-migrate",
        ("postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"),
    ),
    (
        "missing-mutation-guard",
        "mutation guard coverage mismatch",
        "V840-migrate",
        ("postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"),
    ),
    ("checksum-mismatch", "checksum mismatch", "strict-validate", ("strict-validate",)),
)
_PUBLISH_DIRECTORY = Path("docs/evidence/schema-runtime")
_PUBLISH_SUMMARY_NAME = "2026-08-28-postgresql-18-summary.json"
_PUBLISH_REPORT_NAME = "2026-08-28-postgresql-18-report.md"
_SECRET_SCAN_PATTERN = re.compile(
    r"(?ix)(?:"
    r"\b[A-Z][A-Z0-9_]*(?:PASSWORD|PASSWD|TOKEN|SECRET|API[_-]?KEY|CLIENT[_-]?SECRET)\s*(?:=|:)"
    r"|\b(?:password|passwd|pwd|pgpassword|postgres_password|token|secret|api[_-]?key|access[_-]?token|client[_-]?secret)\b"
    r"|jdbc:postgresql://[^\s]+"
    r"|postgres(?:ql)?://[^\s]+"
    r"|/(?:private/)?tmp/"
    r"|/var/tmp/"
    r"|[A-Z]:\\[^\r\n]*\\(?:temp|tmp)\\"
    r")"
)
_PRODUCT_CLAIM_PATTERN = re.compile(r"(?i)(?:\bAPI\b|\bSPA\b|\bR1\b|授权业务逻辑)")

_CI_SCHEMA_VERSION = "postgresql-runtime-ci-artifact-v1"
_CI_SUMMARY_NAME = "ci-runtime-summary.json"
_CI_MARKDOWN_NAME = "ci-job-summary.md"
_CI_TOP_LEVEL_FIELDS = (
    "schemaVersion",
    "gitCommit",
    "workflowOutcome",
    "reasonCode",
    "runs",
    "failureScenarios",
    "toolchain",
    "contractSummary",
)
_CI_RUN_FIELDS = ("runId", "stages")
_CI_SCENARIO_FIELDS = ("scenarioName", "stages")
_CI_STAGE_FIELDS = (
    "stageName",
    "commandClass",
    "exitCode",
    "timedOut",
    "diagnosticCode",
    "stdoutSha256",
    "stderrSha256",
)
_CI_TOOLCHAIN_FIELDS = ("images", "postgresVersion", "flywayVersion")
_CI_IMAGE_FIELDS = ("imageName", "lockedTag", "lockedDigest")
_CI_CONTRACT_FIELDS = (
    "verified",
    "migrationCount",
    "managedTableCount",
    "managedSchemaCount",
    "physicalForeignKeyCount",
    "mutationGuardCount",
    "contractSha256",
    "fieldContractSha256",
)
_CI_HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
_CI_POSTGRES_VERSION_PATTERN = re.compile(r"18\.[0-9]+(?:\.[0-9]+)?\Z")
_CI_FLYWAY_VERSION = "13.4.0"
_CI_LOCKED_IMAGES = (
    (
        "postgres",
        "18",
        "sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280",
    ),
    (
        "redgate/flyway",
        "13.4.0",
        "sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93",
    ),
)
_CI_RUN_STAGE_DEFINITIONS = (
    (
        "run-01",
        (
            ("postgres-start", "compose_lifecycle", "run-01/postgres-start.json"),
            ("flyway-start", "compose_lifecycle", "run-01/flyway-start.json"),
            ("flyway-wait", "compose_lifecycle", "run-01/flyway-wait.json"),
            ("flyway-status", "compose_lifecycle", "run-01/flyway-status.json"),
            ("verifier-start", "compose_lifecycle", "run-01/verifier-start.json"),
            ("verifier-wait", "compose_lifecycle", "run-01/verifier-wait.json"),
            ("verifier-status", "compose_lifecycle", "run-01/verifier-status.json"),
            ("verifier-logs", "compose_logs", "run-01/verifier-logs.json"),
            ("noop-migrate", "flyway_migrate", "run-01/noop-migrate.json"),
            ("noop-verifier-start", "compose_lifecycle", "run-01/noop-verifier-start.json"),
            ("noop-verifier-wait", "compose_lifecycle", "run-01/noop/verifier-wait.json"),
            ("noop-verifier-status", "compose_lifecycle", "run-01/noop/verifier-status.json"),
            ("noop-verifier-logs", "compose_logs", "run-01/noop-verifier-logs.json"),
            ("compose-down", "compose_lifecycle", "run-01/compose-down.json"),
        ),
    ),
    (
        "run-02",
        (
            ("postgres-start", "compose_lifecycle", "run-02/postgres-start.json"),
            ("flyway-start", "compose_lifecycle", "run-02/flyway-start.json"),
            ("flyway-wait", "compose_lifecycle", "run-02/flyway-wait.json"),
            ("flyway-status", "compose_lifecycle", "run-02/flyway-status.json"),
            ("verifier-start", "compose_lifecycle", "run-02/verifier-start.json"),
            ("verifier-wait", "compose_lifecycle", "run-02/verifier-wait.json"),
            ("verifier-status", "compose_lifecycle", "run-02/verifier-status.json"),
            ("verifier-logs", "compose_logs", "run-02/verifier-logs.json"),
            ("compose-down", "compose_lifecycle", "run-02/compose-down.json"),
        ),
    ),
)
_CI_FAILURE_STAGE_DEFINITIONS = (
    (
        "missing-role",
        "V830-migrate",
        (
            ("postgres-start", "compose_lifecycle", "failure-scenarios/missing-role/postgres-start.json"),
            ("V830-migrate", "flyway_migrate", "failure-scenarios/missing-role/expected-failure.json"),
            ("compose-down", "compose_lifecycle", "failure-scenarios/missing-role/compose-down.json"),
        ),
    ),
    (
        "extra-managed-table",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle", "failure-scenarios/extra-managed-table/postgres-start.json"),
            ("V830-migrate", "flyway_migrate", "failure-scenarios/extra-managed-table/V830-migrate.json"),
            ("mutation", "postgres_mutation", "failure-scenarios/extra-managed-table/mutation.json"),
            ("V840-migrate", "flyway_migrate", "failure-scenarios/extra-managed-table/expected-failure.json"),
            ("compose-down", "compose_lifecycle", "failure-scenarios/extra-managed-table/compose-down.json"),
        ),
    ),
    (
        "forbidden-delete-grant",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle", "failure-scenarios/forbidden-delete-grant/postgres-start.json"),
            ("V830-migrate", "flyway_migrate", "failure-scenarios/forbidden-delete-grant/V830-migrate.json"),
            ("mutation", "postgres_mutation", "failure-scenarios/forbidden-delete-grant/mutation.json"),
            ("V840-migrate", "flyway_migrate", "failure-scenarios/forbidden-delete-grant/expected-failure.json"),
            ("compose-down", "compose_lifecycle", "failure-scenarios/forbidden-delete-grant/compose-down.json"),
        ),
    ),
    (
        "missing-mutation-guard",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle", "failure-scenarios/missing-mutation-guard/postgres-start.json"),
            ("V830-migrate", "flyway_migrate", "failure-scenarios/missing-mutation-guard/V830-migrate.json"),
            ("mutation", "postgres_mutation", "failure-scenarios/missing-mutation-guard/mutation.json"),
            ("V840-migrate", "flyway_migrate", "failure-scenarios/missing-mutation-guard/expected-failure.json"),
            ("compose-down", "compose_lifecycle", "failure-scenarios/missing-mutation-guard/compose-down.json"),
        ),
    ),
    (
        "checksum-mismatch",
        "strict-validate",
        (("strict-validate", "flyway_validate", "run-01/failure-checksum/strict-validate.json"),),
    ),
)
_CI_AUXILIARY_STAGE_PATHS = frozenset(
    {
        "runtime-identity/postgres-image.json",
        "runtime-identity/flyway-image.json",
        "runtime-identity/flyway-version.json",
    }
)
_CI_REASON_OUTCOMES = {
    "runtime_verified": "PASSED",
    "docker_compose_unavailable": "BLOCKED",
    "compose_up_failed": "FAILED",
    "compose_cleanup_failed": "FAILED",
    "failure_scenario_failed": "FAILED",
    "runtime_fingerprint_mismatch": "FAILED",
    "runtime_identity_capture_failed": "FAILED",
    "workflow_step_failed": "FAILED",
    "workflow_step_cancelled": "BLOCKED",
    "workflow_step_skipped": "BLOCKED",
    "ci_artifact_missing": "FAILED",
}
_CI_FALLBACK_OUTCOMES = {
    "failure": ("FAILED", "workflow_step_failed"),
    "cancelled": ("BLOCKED", "workflow_step_cancelled"),
    "skipped": ("BLOCKED", "workflow_step_skipped"),
    "success": ("FAILED", "ci_artifact_missing"),
}
_CI_FALLBACK_REASONS = frozenset(reason for _, reason in _CI_FALLBACK_OUTCOMES.values())
_VERIFIER_ASSERTION_DIAGNOSTICS = {
    "13 managed schemas": ("schema", "verifier_schema_managed_schema_count"),
    "managed schema allowlist": ("schema", "verifier_schema_managed_schema_allowlist"),
    "52 application tables": ("schema", "verifier_schema_application_table_count"),
    "2 platform_meta tables": ("schema", "verifier_schema_platform_meta_table_set"),
    "public schema table count": ("schema", "verifier_schema_public_table_count"),
    "19 successful migrations": ("schema", "verifier_schema_migration_count"),
    "all migrations successful": ("schema", "verifier_schema_migration_success"),
    "maximum migration version": ("schema", "verifier_schema_max_migration_version"),
    "V840 successful": ("schema", "verifier_schema_v840_success"),
    "206 composite foreign keys": ("schema", "verifier_schema_foreign_key_count"),
    "application foreign keys NO ACTION": ("schema", "verifier_schema_foreign_key_actions"),
    "validated MATCH SIMPLE foreign keys": ("schema", "verifier_schema_foreign_key_validation"),
    "tenant_id first in tenant foreign keys": ("schema", "verifier_schema_foreign_key_tenant_prefix"),
    "53 mutation guards": ("schema", "verifier_schema_mutation_guard_count"),
    "four distinct capability roles": ("schema", "verifier_schema_capability_role_count"),
    "capability roles NOLOGIN": ("schema", "verifier_schema_capability_roles_nologin"),
    "capability parent role memberships": ("schema", "verifier_schema_capability_parent_membership"),
    "capability roles cannot obtain migration owner": (
        "schema",
        "verifier_schema_capability_migrator_isolation",
    ),
    "deployment_state PRIMARY/BLOCKED/52-plus-2-v1/revision=0 with 32 zero bytes": (
        "schema",
        "verifier_schema_deployment_state_seed",
    ),
    "cross-tenant organization parent": (
        "capability",
        "verifier_capability_cross_tenant_parent",
    ),
    "deployment no-op update": ("capability", "verifier_capability_deployment_noop_guard"),
    "deployment revision must increment exactly once": (
        "capability",
        "verifier_capability_deployment_revision_guard",
    ),
    "query role INSERT": ("capability", "verifier_capability_query_insert"),
    "query role UPDATE": ("capability", "verifier_capability_query_update"),
    "query role DELETE": ("capability", "verifier_capability_query_delete"),
    "query role direct audit read": ("capability", "verifier_capability_query_audit_read"),
    "query role CREATE SCHEMA": ("capability", "verifier_capability_query_create_schema"),
    "query role CREATE TABLE": ("capability", "verifier_capability_query_create_table"),
    "query role migration owner": (
        "capability",
        "verifier_capability_query_migrator_isolation",
    ),
    "audit append role SELECT": ("capability", "verifier_capability_audit_select"),
    "audit append role UPDATE": ("capability", "verifier_capability_audit_update"),
    "audit append role CREATE SCHEMA": (
        "capability",
        "verifier_capability_audit_create_schema",
    ),
    "audit append role CREATE TABLE": (
        "capability",
        "verifier_capability_audit_create_table",
    ),
    "audit append role migration owner": (
        "capability",
        "verifier_capability_audit_migrator_isolation",
    ),
    "worker frozen outbox column": (
        "capability",
        "verifier_capability_worker_frozen_column",
    ),
    "worker outbox INSERT": ("capability", "verifier_capability_worker_outbox_insert"),
    "worker domain write": ("capability", "verifier_capability_worker_domain_write"),
    "worker role CREATE SCHEMA": (
        "capability",
        "verifier_capability_worker_create_schema",
    ),
    "worker role CREATE TABLE": (
        "capability",
        "verifier_capability_worker_create_table",
    ),
    "worker role migration owner": (
        "capability",
        "verifier_capability_worker_migrator_isolation",
    ),
    "command role DELETE": ("capability", "verifier_capability_command_delete"),
    "command role TRUNCATE": ("capability", "verifier_capability_command_truncate"),
    "command role frozen column": (
        "capability",
        "verifier_capability_command_frozen_column",
    ),
    "command role platform_meta write": (
        "capability",
        "verifier_capability_command_platform_meta_write",
    ),
    "command role CREATE SCHEMA": (
        "capability",
        "verifier_capability_command_create_schema",
    ),
    "command role CREATE TABLE": (
        "capability",
        "verifier_capability_command_create_table",
    ),
    "command role migration owner": (
        "capability",
        "verifier_capability_command_migrator_isolation",
    ),
}
_VERIFIER_PHASE_DIAGNOSTICS = {
    "schema": ("assert_schema_contract.sql", "verifier_schema_assertion_unknown"),
    "capability": ("assert_capabilities.sql", "verifier_capability_assertion_unknown"),
}
_VERIFIER_ERROR_RECORD_PATTERN = re.compile(
    r"^(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,127}[ \t]+\|[ \t]+)?"
    r"psql:/runtime/sql/"
    r"(?P<script>assert_schema_contract\.sql|assert_capabilities\.sql):"
    r"(?P<line>[1-9][0-9]*): ERROR:[ \t]*"
    r"(?P<message>[^\r\n]*)$",
    re.MULTILINE,
)
_VERIFIER_ASSERTION_MESSAGE_PATTERN = re.compile(
    r"^assertion=(?P<label>[A-Za-z0-9][A-Za-z0-9 _./=+-]{0,159}?)[ \t]+"
    r"expected(?:[ \t]+SQLSTATE)?=(?P<expected>\S(?:[^\r\n]*?\S)?)[ \t]+"
    r"actual=(?P<actual>\S[^\r\n]*)$",
)
_VERIFIER_DIAGNOSTIC_CODES = frozenset(
    {
        *(code for _, code in _VERIFIER_ASSERTION_DIAGNOSTICS.values()),
        *(code for _, code in _VERIFIER_PHASE_DIAGNOSTICS.values()),
        "verifier_diagnostic_unknown",
        "verifier_logs_unavailable",
    }
)
_CI_VERIFIER_DIAGNOSTIC_STAGE_PATHS = frozenset(
    {
        "run-01/verifier-wait.json",
        "run-01/noop/verifier-wait.json",
        "run-02/verifier-wait.json",
    }
)
_CI_VERIFIER_DIAGNOSTIC_STAGE_NAMES = frozenset(
    {"verifier-wait", "noop-verifier-wait"}
)


class EvidenceIOError(ValueError):
    """A structured filesystem or process failure in evidence publication."""

    def __init__(self, operation: str, path: Path, cause: object):
        self.operation = operation
        self.path = Path(path)
        self.cause = str(cause)
        super().__init__(
            json.dumps(
                {"cause": self.cause, "operation": operation, "path": str(self.path)},
                ensure_ascii=True,
                sort_keys=True,
            )
        )


class _VisibleTextHTMLParser(HTMLParser):
    """Collect render-visible data in order while treating markup as zero width."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.fragments: list[str] = []

    def handle_data(self, data: str) -> None:
        self.fragments.append(data)


@dataclass(frozen=True)
class RepositorySnapshot:
    head: str


@dataclass(frozen=True)
class CapturedStageResult:
    """Only integrity facts retained for one subprocess capture in the CI model."""

    exit_code: int
    timed_out: bool
    stdout_sha256: str
    stderr_sha256: str


@dataclass(frozen=True)
class _CiLockedImage:
    image_name: str
    locked_tag: str
    locked_digest: str


class RuntimeVerificationResult(dict[str, object]):
    """Controller result whose CI-only facts never enter raw evidence JSON."""

    def __init__(
        self,
        result: Mapping[str, object],
        *,
        ci_stage_results: Mapping[str, CapturedStageResult],
        ci_locked_images: Sequence[_CiLockedImage],
        ci_stage_diagnostics: Mapping[str, str] | None = None,
    ) -> None:
        super().__init__(result)
        self.ci_stage_results = tuple(sorted(ci_stage_results.items()))
        self.ci_locked_images = tuple(ci_locked_images)
        diagnostics = {} if ci_stage_diagnostics is None else dict(ci_stage_diagnostics)
        if (
            not set(diagnostics) <= _CI_VERIFIER_DIAGNOSTIC_STAGE_PATHS
            or any(
                not isinstance(code, str) or code not in _VERIFIER_DIAGNOSTIC_CODES
                for code in diagnostics.values()
            )
        ):
            raise ValueError("runtime controller returned an invalid verifier diagnostic")
        self.ci_stage_diagnostics = tuple(sorted(diagnostics.items()))


@dataclass(frozen=True)
class PreparedEvidence:
    repository_root: Path
    expected_head: str
    input_digests: tuple[tuple[Path, str], ...]
    summary: dict[str, object]
    json_source: str
    markdown_source: str


@dataclass(frozen=True)
class _PathRedactionContext:
    cwd: str
    protected_directories: tuple[str, ...]
    protected_files: frozenset[str]
    sensitive_directories: tuple[tuple[str, str], ...]
    sensitive_files: tuple[tuple[str, str], ...]


def load_toolchain_lock(lock_path: Path) -> dict[str, list[dict[str, str]]]:
    """Load and strictly validate the digest-pinned image lock file."""
    try:
        raw_lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except OSError as error:
        raise EvidenceIOError("read_toolchain_lock", lock_path, error) from error
    except json.JSONDecodeError as error:
        raise ValueError(f"cannot read toolchain lock {lock_path}: {error}") from error

    if not isinstance(raw_lock, Mapping) or set(raw_lock) != {"images"}:
        raise ValueError("toolchain lock must contain only an images array")
    raw_images = raw_lock["images"]
    if not isinstance(raw_images, list) or not raw_images:
        raise ValueError("toolchain lock images must be a non-empty array")

    images: list[dict[str, str]] = []
    seen_images: set[str] = set()
    for index, raw_image in enumerate(raw_images):
        if not isinstance(raw_image, Mapping) or set(raw_image) != set(_LOCK_FIELDS):
            raise ValueError(f"toolchain lock image {index} must contain {_LOCK_FIELDS}")
        if not all(isinstance(raw_image[field], str) for field in _LOCK_FIELDS):
            raise ValueError(f"toolchain lock image {index} fields must be strings")

        image = raw_image["image"]
        tag = raw_image["tag"]
        digest = raw_image["digest"]
        resolved_at = raw_image["resolvedAt"]
        if not image or image.strip() != image:
            raise ValueError(f"toolchain lock image {index} has an invalid image name")
        if tag.lower() == "latest" or not _TAG_PATTERN.fullmatch(tag):
            raise ValueError(f"toolchain lock image {image!r} must use a non-latest tag")
        if not _DIGEST_PATTERN.fullmatch(digest):
            raise ValueError(f"toolchain lock image {image!r} has an invalid digest")
        _validate_timestamp(resolved_at, image)
        if image in seen_images:
            raise ValueError(f"toolchain lock repeats image {image!r}")
        seen_images.add(image)
        images.append({field: raw_image[field] for field in _LOCK_FIELDS})

    if tuple(image["image"] for image in images) != _REQUIRED_LOCK_IMAGES:
        raise ValueError("toolchain lock must contain exactly postgres then redgate/flyway")
    for image in images:
        expected_tag = _REQUIRED_IMAGE_TAGS[image["image"]]
        if image["tag"] != expected_tag:
            raise ValueError(f"toolchain lock image {image['image']!r} must use tag {expected_tag}")
    return {"images": images}


def render_compose_override(lock: Mapping[str, object]) -> str:
    """Render the only image values supplied to the disposable Compose stack."""
    image_references = _locked_image_references(lock)
    override = {
        "services": {
            "postgres": {"image": image_references["postgres"]},
            "flyway": {"image": image_references["redgate/flyway"]},
            "verifier": {"image": image_references["postgres"]},
        }
    }
    return json.dumps(override, indent=2, sort_keys=True) + "\n"


def render_compose_environment(lock: Mapping[str, object]) -> str:
    """Provide Compose interpolation with the same digest-pinned image references."""
    image_references = _locked_image_references(lock)
    return (
        f"POSTGRES_IMAGE={image_references['postgres']}\n"
        f"FLYWAY_IMAGE={image_references['redgate/flyway']}\n"
    )


def compose_service_names(compose_text: str) -> list[str]:
    """Read service keys from this harness's deliberately flat Compose document."""
    in_services = False
    names: list[str] = []
    for line in compose_text.splitlines():
        if line == "services:":
            in_services = True
            continue
        if in_services and line and not line.startswith((" ", "\t")):
            break
        if in_services:
            match = re.fullmatch(r"  ([a-z][a-z0-9_-]*):", line)
            if match:
                names.append(match.group(1))
    return names


def run_runtime_verification(
    schema_root: Path,
    output_directory: Path,
    *,
    runs: int,
    compose_command: Sequence[str] = ("docker", "compose"),
    stage_runner: Callable[..., int] | None = None,
) -> dict[str, object]:
    """Run verification while converting every filesystem failure to evidence-safe data."""
    try:
        return _run_runtime_verification_impl(
            schema_root,
            output_directory,
            runs=runs,
            compose_command=compose_command,
            stage_runner=stage_runner,
        )
    except EvidenceIOError:
        raise
    except OSError as error:
        raise EvidenceIOError("runtime_evidence_io", Path(output_directory), error) from error


def _run_runtime_verification_impl(
    schema_root: Path,
    output_directory: Path,
    *,
    runs: int,
    compose_command: Sequence[str] = ("docker", "compose"),
    stage_runner: Callable[..., int] | None = None,
) -> dict[str, object]:
    """Run the disposable stack, preserving a failed or blocked attempt as evidence."""
    if isinstance(runs, bool) or not isinstance(runs, int) or runs < 2:
        raise ValueError("runs must be an integer of at least 2")
    if not compose_command or any(not isinstance(part, str) or not part for part in compose_command):
        raise ValueError("compose_command must be a non-empty sequence of non-empty strings")
    requested_schema_root = Path(schema_root)
    try:
        schema_root = requested_schema_root.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise EvidenceIOError("resolve_schema_root", requested_schema_root, error) from error
    runtime_directory = schema_root / "runtime"
    compose_path = runtime_directory / "compose.yaml"
    lock = load_toolchain_lock(runtime_directory / "toolchain.lock.json")
    if not compose_path.is_file():
        raise ValueError(f"runtime compose file does not exist: {compose_path}")

    output_directory = Path(output_directory)
    output_directory.mkdir(parents=True, exist_ok=True)
    ci_stage_results: dict[str, CapturedStageResult] = {}
    ci_stage_diagnostics: dict[str, str] = {}
    allow_injected_stage_evidence_fallback = stage_runner is not None
    if stage_runner is None:
        def capture_stage(
            command: Sequence[str],
            *,
            evidence_path: Path,
            cwd: Path | None = None,
            timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS,
        ) -> int:
            captured = run_checked_result(
                command,
                evidence_path=evidence_path,
                cwd=cwd,
                timeout_seconds=timeout_seconds,
            )
            relative_path = Path(os.path.relpath(Path(evidence_path), output_directory))
            if relative_path.is_absolute() or ".." in relative_path.parts:
                raise ValueError("runtime stage evidence must remain below its output directory")
            ci_stage_results[relative_path.as_posix()] = captured
            return captured.exit_code

        stage_runner = capture_stage

    attempts: list[dict[str, object]] = []
    for index in range(1, runs + 1):
        run_identifier = f"run-{index:02d}"
        run_directory = output_directory / run_identifier
        project_name = f"schema-runtime-{secrets.token_hex(6)}"
        attempts.append(
            _run_compose_attempt(
                schema_root=schema_root,
                runtime_directory=runtime_directory,
                compose_path=compose_path,
                lock=lock,
                run_directory=run_directory,
                project_name=project_name,
                compose_command=compose_command,
                stage_runner=stage_runner,
                verify_noop=index == 1,
                ci_stage_results=ci_stage_results,
                ci_stage_diagnostics=ci_stage_diagnostics,
                allow_injected_stage_evidence_fallback=allow_injected_stage_evidence_fallback,
            )
        )

    up_codes = [attempt["upReturnCode"] for attempt in attempts]
    cleanup_codes = [attempt["cleanupReturnCode"] for attempt in attempts]
    failure_scenarios: list[dict[str, object]] = []
    runtime_identity: dict[str, object] | None = None
    checksum_scenario = attempts[0].pop("checksumScenario", None) if attempts else None
    positive_runs_succeeded = all(code == 0 for code in up_codes) and all(code == 0 for code in cleanup_codes)
    runtime_fingerprints_match = _runtime_attempt_fingerprints_match(attempts)
    if positive_runs_succeeded:
        failure_scenarios.extend(
            _run_failure_scenarios(
                schema_root=schema_root,
                runtime_directory=runtime_directory,
                compose_path=compose_path,
                lock=lock,
                output_directory=output_directory,
                compose_command=compose_command,
                stage_runner=stage_runner,
            )
        )
        if isinstance(checksum_scenario, dict):
            failure_scenarios.append(checksum_scenario)
        if (
            len(failure_scenarios) == 5
            and all(scenario.get("status") == "PASSED" for scenario in failure_scenarios)
            and runtime_fingerprints_match
        ):
            runtime_identity = _capture_runtime_identity(
                schema_root=schema_root,
                lock=lock,
                output_directory=output_directory,
                compose_command=compose_command,
                stage_runner=stage_runner,
            )

    if all(code == 127 for code in up_codes):
        status = "BLOCKED"
        reason = "docker_compose_unavailable"
    elif any(code != 0 for code in up_codes):
        status = "FAILED"
        reason = "compose_up_failed"
    elif any(code != 0 for code in cleanup_codes):
        status = "FAILED"
        reason = "compose_cleanup_failed"
    elif len(failure_scenarios) != 5 or any(scenario["status"] != "PASSED" for scenario in failure_scenarios):
        status = "FAILED"
        reason = "failure_scenario_failed"
    elif not runtime_fingerprints_match:
        status = "FAILED"
        reason = "runtime_fingerprint_mismatch"
    elif runtime_identity is None:
        status = "FAILED"
        reason = "runtime_identity_capture_failed"
    else:
        status = "PASSED"
        reason = None

    result: dict[str, object] = {"failureScenarios": failure_scenarios, "runs": attempts, "status": status}
    if runtime_identity is not None:
        result["runtimeIdentity"] = runtime_identity
    if reason is not None:
        result["reason"] = reason
    _write_evidence_atomically(output_directory / "runtime-summary.json", result)
    locked_images = tuple(
        _CiLockedImage(
            image_name=image["image"],
            locked_tag=image["tag"],
            locked_digest=image["digest"],
        )
        for image in lock["images"]
    )
    return RuntimeVerificationResult(
        result,
        ci_stage_results=ci_stage_results,
        ci_locked_images=locked_images,
        ci_stage_diagnostics=ci_stage_diagnostics,
    )


def validate_run_id(run_id: str) -> str:
    """Return a safe run identifier, rejecting values that can name paths."""
    if not isinstance(run_id, str) or not _RUN_ID_PATTERN.fullmatch(run_id):
        raise ValueError("run ID must fully match [a-z0-9-]+")
    return run_id


def evidence_dir(
    repository_root: Path,
    requested_path: str | Path,
    *,
    current_directory: Path | None = None,
) -> Path:
    """Resolve an evidence path only when it remains inside the repository root."""
    repository = _resolve_evidence_location(Path(repository_root))
    allowed_root = _resolve_evidence_location(repository / ".artifacts" / "schema-runtime")
    if not _is_within(allowed_root, repository):
        raise ValueError("repository evidence root must remain within the repository")

    requested = Path(requested_path)
    if requested.is_absolute():
        candidate = requested
    elif current_directory is not None:
        invoked_candidate = _resolve_evidence_location(Path(current_directory) / requested)
        if _is_within(invoked_candidate, allowed_root):
            candidate = invoked_candidate
        elif requested.parts[:2] == (".artifacts", "schema-runtime"):
            candidate = repository / requested
        else:
            candidate = allowed_root / requested
    elif requested.parts[:2] == (".artifacts", "schema-runtime"):
        candidate = repository / requested
    else:
        candidate = allowed_root / requested
    candidate = _resolve_evidence_location(candidate)
    if not _is_within(candidate, allowed_root):
        raise ValueError("evidence path must remain under .artifacts/schema-runtime")
    return candidate


def _resolve_evidence_location(path: Path) -> Path:
    try:
        return path.resolve(strict=False)
    except (OSError, RuntimeError) as error:
        raise EvidenceIOError("resolve_evidence_directory", path, error) from error


def normalize_snapshot(snapshot: object) -> str:
    """Produce a stable JSON fingerprint for semantically equal snapshots."""
    try:
        _reject_non_string_mapping_keys(snapshot)
        return json.dumps(snapshot, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ValueError(f"snapshot is not canonical JSON data: {error}") from error


def capture_repository_snapshot(repository_root: Path) -> RepositorySnapshot:
    """Return one clean 40-hex Git HEAD while allowing ignored artifacts."""
    repository = _validated_repository_root(repository_root)
    head = _git_output(repository, "rev-parse", "--verify", "HEAD")
    if not _COMMIT_PATTERN.fullmatch(head):
        raise ValueError("repository HEAD must be one lowercase 40-hex commit")
    _verify_tracked_repository_bytes(repository, head)
    status = _git_output(repository, "status", "--porcelain=v1", "--untracked-files=all")
    if status:
        raise ValueError("repository must have no tracked or untracked changes")
    return RepositorySnapshot(head=head)


def _verify_tracked_repository_bytes(repository: Path, head: str) -> None:
    """Compare HEAD, index, and every tracked worktree byte without trusting index hints."""
    flag_entries = _git_bytes(repository, "ls-files", "-v", "-z").split(b"\0")
    for entry in flag_entries:
        if not entry:
            continue
        if len(entry) < 3 or entry[1:2] != b" " or entry[:1] != b"H":
            raise ValueError("repository index must not use assume-unchanged or skip-worktree flags")

    index_entries = _parse_git_tree_entries(
        _git_bytes(repository, "ls-files", "--stage", "-z"),
        index=True,
    )
    head_entries = _parse_git_tree_entries(
        _git_bytes(repository, "ls-tree", "-r", "-z", "--full-tree", head),
        index=False,
    )
    if index_entries != head_entries:
        raise ValueError("repository index must match HEAD exactly")

    for raw_path, (mode, expected_object) in index_entries.items():
        path = repository / os.fsdecode(raw_path)
        try:
            metadata = path.lstat()
            if mode == b"120000":
                if not stat.S_ISLNK(metadata.st_mode):
                    raise ValueError(f"tracked symbolic link changed type: {path}")
                contents = os.fsencode(os.readlink(path))
            elif mode in {b"100644", b"100755"}:
                if not stat.S_ISREG(metadata.st_mode):
                    raise ValueError(f"tracked file changed type: {path}")
                contents = path.read_bytes()
            else:
                raise ValueError(f"unsupported tracked Git mode {mode.decode('ascii', 'replace')}: {path}")
        except ValueError:
            raise
        except OSError as error:
            raise EvidenceIOError("read_tracked_worktree", path, error) from error
        header = f"blob {len(contents)}\0".encode("ascii")
        actual_object = hashlib.sha1(header + contents).hexdigest().encode("ascii")
        if actual_object != expected_object:
            raise ValueError(f"tracked worktree bytes differ from HEAD and index: {path}")


def _parse_git_tree_entries(data: bytes, *, index: bool) -> dict[bytes, tuple[bytes, bytes]]:
    entries: dict[bytes, tuple[bytes, bytes]] = {}
    for entry in data.split(b"\0"):
        if not entry:
            continue
        try:
            metadata, raw_path = entry.split(b"\t", 1)
            fields = metadata.split()
            if index:
                mode, object_name, stage = fields
                if stage != b"0":
                    raise ValueError("repository index contains an unmerged entry")
            else:
                mode, object_type, object_name = fields
                if object_type not in {b"blob", b"commit"}:
                    raise ValueError("repository HEAD contains an unsupported object type")
        except (ValueError, IndexError) as error:
            raise ValueError("Git returned a malformed tracked-file snapshot") from error
        if raw_path in entries:
            raise ValueError("Git returned duplicate tracked paths")
        entries[raw_path] = (mode, object_name)
    return entries


def fixed_publication_targets(repository_root: Path) -> tuple[Path, Path]:
    """Resolve and validate the two non-configurable in-repository evidence targets."""
    repository = _validated_repository_root(repository_root)
    publish_directory = repository / _PUBLISH_DIRECTORY
    _require_directory_chain(repository, publish_directory)
    try:
        resolved_directory = publish_directory.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise EvidenceIOError("resolve_publish_directory", publish_directory, error) from error
    if not _is_within(resolved_directory, repository):
        raise ValueError("publication directory must remain inside the repository")

    targets = (
        publish_directory / _PUBLISH_SUMMARY_NAME,
        publish_directory / _PUBLISH_REPORT_NAME,
    )
    existing: list[bool] = []
    for target in targets:
        try:
            metadata = target.lstat()
        except FileNotFoundError:
            existing.append(False)
            continue
        except OSError as error:
            raise EvidenceIOError("inspect_publish_target", target, error) from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"publication target must be a regular non-symlink file: {target}")
        try:
            resolved_target = target.resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise EvidenceIOError("resolve_publish_target", target, error) from error
        if not _is_within(resolved_target, repository):
            raise ValueError("publication target must remain inside the repository")
        existing.append(True)
    if existing[0] != existing[1]:
        raise ValueError("publishable evidence targets must both exist or both be absent")
    return targets


def prepare_publishable_evidence(
    repository_root: Path,
    raw_summary_path: Path,
    lock_path: Path,
    manifest_path: Path,
    *,
    expected_head: str,
) -> PreparedEvidence:
    """Validate and render a complete PASSED evidence input without publishing it."""
    repository = _validated_repository_root(repository_root)
    if not isinstance(expected_head, str) or not _COMMIT_PATTERN.fullmatch(expected_head):
        raise ValueError("expected_head must be one lowercase 40-hex commit")
    snapshot = capture_repository_snapshot(repository)
    if snapshot.head != expected_head:
        raise ValueError("repository HEAD changed between verification snapshots")
    fixed_publication_targets(repository)

    inputs = tuple(Path(path) for path in (raw_summary_path, lock_path, manifest_path))
    input_bytes = _read_publication_inputs(repository, inputs)
    input_digests = tuple(
        (path, hashlib.sha256(contents).hexdigest())
        for path, contents in zip(inputs, input_bytes, strict=True)
    )
    raw_summary = _decode_json_object(input_bytes[0], inputs[0], "read_runtime_summary")
    raw_lock = _decode_json_object(input_bytes[1], inputs[1], "read_toolchain_lock")
    manifest = _decode_json_object(input_bytes[2], inputs[2], "read_contract_manifest")
    summary = _validate_publishable_summary(raw_summary, raw_lock, manifest, expected_head)
    json_source = json.dumps(summary, ensure_ascii=True, indent=2, sort_keys=True, allow_nan=False) + "\n"
    markdown_source = _render_publishable_report(summary)
    scan_publishable_evidence(json_source, markdown_source)
    return PreparedEvidence(
        repository_root=repository,
        expected_head=expected_head,
        input_digests=input_digests,
        summary=summary,
        json_source=json_source,
        markdown_source=markdown_source,
    )


def publish_prepared_evidence(prepared: PreparedEvidence) -> tuple[Path, Path]:
    """Revalidate snapshots and publish the fixed pair with rollback on any I/O failure."""
    if not isinstance(prepared, PreparedEvidence):
        raise ValueError("prepared evidence must come from prepare_publishable_evidence")
    snapshot = capture_repository_snapshot(prepared.repository_root)
    if snapshot.head != prepared.expected_head:
        raise ValueError("repository HEAD changed before evidence publication")
    current_digests = _input_digest_snapshot(prepared.repository_root, prepared.input_digests)
    if current_digests != prepared.input_digests:
        raise ValueError("evidence inputs changed after validation")
    scan_publishable_evidence(prepared.json_source, prepared.markdown_source)
    targets = fixed_publication_targets(prepared.repository_root)
    _publish_pair_atomically(
        targets,
        (prepared.json_source.encode("utf-8"), prepared.markdown_source.encode("utf-8")),
    )
    return targets


def markdown_visible_text(markdown_source: str) -> str:
    """Approximate rendered Markdown text while never joining a link destination."""
    if not isinstance(markdown_source, str):
        raise ValueError("Markdown source must be text")
    label_only_source = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", markdown_source)
    parser = _VisibleTextHTMLParser()
    parser.feed(label_only_source)
    parser.close()
    visible = "".join(parser.fragments)
    visible = re.sub(r"\\([\\`*{}\[\]()#+.!_=<>-])", r"\1", visible)
    visible = re.sub(r"[`*_~]", "", visible)
    return " ".join(visible.split())


def scan_publishable_evidence(json_source: str, markdown_source: str) -> None:
    """Reject sensitive or out-of-scope claims in source and render-equivalent text."""
    if not isinstance(json_source, str) or not isinstance(markdown_source, str):
        raise ValueError("publishable evidence sources must be text")
    candidates = (
        json_source,
        html.unescape(json_source),
        markdown_source,
        html.unescape(markdown_source),
        markdown_visible_text(markdown_source),
    )
    for candidate in candidates:
        if _SECRET_SCAN_PATTERN.search(candidate):
            raise ValueError("publishable evidence contains sensitive or temporary data")
        if _PRODUCT_CLAIM_PATTERN.search(candidate):
            raise ValueError("publishable evidence contains an out-of-scope product claim")


def _validated_repository_root(repository_root: Path) -> Path:
    requested = Path(repository_root)
    try:
        metadata = requested.lstat()
        repository = requested.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise EvidenceIOError("resolve_repository", requested, error) from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        raise ValueError("repository root must be a real directory")
    return repository


def _git_output(repository: Path, *arguments: str) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise EvidenceIOError("git_snapshot", repository, error) from error
    if completed.returncode != 0:
        cause = completed.stderr.strip() or f"git exited {completed.returncode}"
        raise EvidenceIOError("git_snapshot", repository, cause)
    return completed.stdout.strip()


def _git_bytes(repository: Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=False,
            capture_output=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise EvidenceIOError("git_snapshot", repository, error) from error
    if completed.returncode != 0:
        cause = completed.stderr.decode("utf-8", "replace").strip() or f"git exited {completed.returncode}"
        raise EvidenceIOError("git_snapshot", repository, cause)
    return completed.stdout


def _require_directory_chain(repository: Path, directory: Path) -> None:
    try:
        relative = directory.relative_to(repository)
    except ValueError as error:
        raise ValueError("publication directory must remain inside the repository") from error
    current = repository
    for component in relative.parts:
        current = current / component
        try:
            metadata = current.lstat()
        except OSError as error:
            raise EvidenceIOError("inspect_publish_directory", current, error) from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            raise ValueError(f"publication directory component must be a real directory: {current}")


def _read_publication_inputs(repository: Path, paths: Sequence[Path]) -> tuple[bytes, ...]:
    contents: list[bytes] = []
    for requested in paths:
        path = requested if requested.is_absolute() else repository / requested
        try:
            metadata = path.lstat()
            resolved = path.resolve(strict=True)
        except (OSError, RuntimeError) as error:
            raise EvidenceIOError("read_publication_input", path, error) from error
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"publication input must be a regular non-symlink file: {path}")
        if not _is_within(resolved, repository):
            raise ValueError("publication input must remain inside the repository")
        try:
            contents.append(path.read_bytes())
        except OSError as error:
            raise EvidenceIOError("read_publication_input", path, error) from error
    return tuple(contents)


def _decode_json_object(contents: bytes, path: Path, operation: str) -> dict[str, object]:
    try:
        value = json.loads(contents.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceIOError(operation, path, error) from error
    if not isinstance(value, dict):
        raise ValueError(f"{operation} must contain one JSON object")
    return value


def _input_digest_snapshot(
    repository: Path,
    expected: tuple[tuple[Path, str], ...],
) -> tuple[tuple[Path, str], ...]:
    paths = tuple(path for path, _ in expected)
    contents = _read_publication_inputs(repository, paths)
    return tuple(
        (path, hashlib.sha256(content).hexdigest())
        for path, content in zip(paths, contents, strict=True)
    )


def _validate_publishable_summary(
    raw_summary: Mapping[str, object],
    raw_lock: Mapping[str, object],
    manifest: Mapping[str, object],
    expected_head: str,
) -> dict[str, object]:
    _require_exact_fields(raw_summary, _PUBLISH_SCHEMA_FIELDS, "runtime evidence")
    if raw_summary["schemaVersion"] != "postgresql-runtime-evidence-v1":
        raise ValueError("runtime evidence schemaVersion is not supported")
    if raw_summary["status"] != "PASSED":
        raise ValueError("only PASSED runtime evidence can be published")
    if raw_summary["gitCommit"] != expected_head:
        raise ValueError("runtime evidence Git commit does not match the verified HEAD")
    _validate_utc_timestamp(raw_summary["verifiedAtUtc"], "verifiedAtUtc")
    postgres_version = raw_summary["postgresVersion"]
    if not isinstance(postgres_version, str) or not _POSTGRES_VERSION_PATTERN.fullmatch(postgres_version):
        raise ValueError("postgresVersion must be the complete PostgreSQL 18 version() result")
    flyway_version = raw_summary["flywayVersion"]
    if not isinstance(flyway_version, str) or not _FLYWAY_VERSION_PATTERN.fullmatch(flyway_version):
        raise ValueError("flywayVersion must be the complete locked Flyway 13.4.0 banner")
    _validate_publish_images(raw_summary["images"], raw_lock)
    _validate_publish_runs(raw_summary["runs"])
    _validate_publish_failures(raw_summary["failureScenarios"])
    _validate_contract_summary(raw_summary["contractSummary"], manifest)
    try:
        normalized = json.loads(
            json.dumps(raw_summary, ensure_ascii=True, sort_keys=True, allow_nan=False)
        )
    except (TypeError, ValueError) as error:
        raise ValueError(f"runtime evidence is not canonical JSON data: {error}") from error
    return normalized


def normalize_runtime_result_for_publication(
    result: Mapping[str, object],
    lock: Mapping[str, object],
    manifest: Mapping[str, object],
    *,
    git_commit: str,
    verified_at_utc: str,
) -> dict[str, object]:
    """Convert the controller's successful raw facts into the closed publication schema."""
    result = _require_exact_fields(result, _RAW_RESULT_FIELDS, "raw runtime result")
    if result["status"] != "PASSED":
        raise ValueError("only a PASSED runtime result can be normalized for publication")
    if not _COMMIT_PATTERN.fullmatch(git_commit):
        raise ValueError("publication Git commit must be one lowercase 40-hex commit")
    _validate_utc_timestamp(verified_at_utc, "verified_at_utc")
    identity = _require_exact_fields(result["runtimeIdentity"], ("flywayVersion", "images"), "runtime identity")
    identity_images = identity["images"]
    if not isinstance(identity_images, list) or len(identity_images) != 2:
        raise ValueError("runtime identity must contain exactly two images")
    for index, image in enumerate(identity_images):
        _require_exact_fields(image, _PUBLISH_IMAGE_FIELDS, f"runtime identity image {index}")
    runs = result["runs"]
    if not isinstance(runs, list) or len(runs) != 2:
        raise ValueError("raw runtime result must contain exactly two runs")
    normalized_runs: list[dict[str, object]] = []
    postgres_versions: list[str] = []
    for index, raw_run in enumerate(runs):
        normalized_run, postgres_version = _normalize_runtime_run(raw_run, index)
        normalized_runs.append(normalized_run)
        postgres_versions.append(postgres_version)
    if len(set(postgres_versions)) != 1:
        raise ValueError("both runtime runs must report the same complete PostgreSQL version")

    raw_failures = result["failureScenarios"]
    if not isinstance(raw_failures, list) or len(raw_failures) != 5:
        raise ValueError("raw runtime result must contain exactly five failure scenarios")
    normalized_failures = [
        _normalize_failure_scenario(raw_failures[index], definition)
        for index, definition in enumerate(_PUBLISH_FAILURE_DEFINITIONS)
    ]
    images = identity["images"]
    flyway_version = identity["flywayVersion"]
    contract_summary = {
        "contractVersion": manifest.get("contractVersion"),
        "migrationCount": 19,
        "managedTableCount": manifest.get("physicalTableCountAfterFlywayBootstrap"),
        "managedSchemaCount": len(manifest.get("schemas", ())) if isinstance(manifest.get("schemas"), list) else None,
        "physicalForeignKeyCount": (
            len(manifest.get("physicalForeignKeyWhitelist", ()))
            if isinstance(manifest.get("physicalForeignKeyWhitelist"), list)
            else None
        ),
        "mutationGuardCount": 53,
        "contractSha256": manifest.get("contractSha256"),
        "fieldContractSha256": manifest.get("fieldContractSha256"),
    }
    candidate: dict[str, object] = {
        "schemaVersion": "postgresql-runtime-evidence-v1",
        "status": "PASSED",
        "gitCommit": git_commit,
        "verifiedAtUtc": verified_at_utc,
        "images": images,
        "postgresVersion": postgres_versions[0],
        "flywayVersion": flyway_version,
        "runs": normalized_runs,
        "failureScenarios": normalized_failures,
        "contractSummary": contract_summary,
    }
    return _validate_publishable_summary(candidate, lock, manifest, git_commit)


def _normalize_runtime_run(raw_run: object, index: int) -> tuple[dict[str, object], str]:
    raw_run = _require_exact_fields(raw_run, _RAW_RUN_FIELDS, "raw runtime run")
    expected_id = f"run-{index + 1:02d}"
    if raw_run["id"] != expected_id:
        raise ValueError("raw runtime run IDs must be exact and ordered")
    flyway = _require_exact_fields(raw_run["flyway"], _RAW_SERVICE_FIELDS, "raw Flyway result")
    verifier = _require_exact_fields(raw_run["verifier"], _RAW_SERVICE_FIELDS, "raw verifier result")
    if flyway["service"] != "flyway" or flyway["logsReturnCode"] is not None or flyway["summary"] is not None:
        raise ValueError("raw Flyway result fields are inconsistent")
    if verifier["service"] != "verifier":
        raise ValueError("raw verifier result fields are inconsistent")
    summary = _require_exact_fields(verifier["summary"], _RAW_SUMMARY_FIELDS, "raw verifier summary")
    if summary["status"] != "PASSED" or not isinstance(summary["serverVersion"], str) or not _SERVER_VERSION_PATTERN.fullmatch(summary["serverVersion"]):
        raise ValueError("raw verifier summary must report a complete PostgreSQL 18 server version")
    fingerprint = summary["fingerprint"]
    postgres_version = summary["postgresVersion"]
    if raw_run.get("initialFingerprint") != fingerprint:
        raise ValueError("raw runtime run fingerprint fields are inconsistent")
    if not isinstance(postgres_version, str):
        raise ValueError("raw runtime run must include the complete PostgreSQL version")
    stage_codes: list[tuple[str, object]] = [
        ("postgres-start", raw_run.get("postgresReturnCode")),
        ("flyway-start", flyway.get("startReturnCode")),
        ("flyway-wait", flyway.get("waitReturnCode")),
        ("flyway-status", flyway.get("statusReturnCode")),
        ("verifier-start", verifier.get("startReturnCode")),
        ("verifier-wait", verifier.get("waitReturnCode")),
        ("verifier-status", verifier.get("statusReturnCode")),
        ("verifier-logs", verifier.get("logsReturnCode")),
    ]
    for service in (flyway, verifier):
        if service.get("exitCode") != 0:
            raise ValueError("raw runtime service must have an exact zero exit code")
    noop: dict[str, object] | None = None
    if index == 0:
        noop_verifier = _require_exact_fields(
            raw_run["noopVerifier"],
            _RAW_SERVICE_FIELDS,
            "run-01 no-op verifier result",
        )
        if noop_verifier["service"] != "verifier":
            raise ValueError("run-01 no-op verifier service is inconsistent")
        noop_summary = _require_exact_fields(
            noop_verifier["summary"],
            _RAW_SUMMARY_FIELDS,
            "run-01 no-op verifier summary",
        )
        if noop_summary["status"] != "PASSED" or noop_summary["serverVersion"] != summary["serverVersion"]:
            raise ValueError("run-01 no-op verifier server version changed")
        noop_fingerprint = noop_summary["fingerprint"]
        if noop_summary["postgresVersion"] != postgres_version:
            raise ValueError("run-01 no-op verifier PostgreSQL version changed")
        if raw_run.get("noopFingerprint") != noop_fingerprint:
            raise ValueError("run-01 no-op fingerprint fields are inconsistent")
        stage_codes.extend(
            [
                ("noop-migrate", raw_run.get("noopMigrateReturnCode")),
                ("noop-verifier-start", noop_verifier.get("startReturnCode")),
                ("noop-verifier-wait", noop_verifier.get("waitReturnCode")),
                ("noop-verifier-status", noop_verifier.get("statusReturnCode")),
                ("noop-verifier-logs", noop_verifier.get("logsReturnCode")),
            ]
        )
        if noop_verifier.get("exitCode") != 0:
            raise ValueError("run-01 no-op verifier must have an exact zero exit code")
        noop = {"exitCode": raw_run.get("noopMigrateReturnCode"), "fingerprint": noop_fingerprint}
    elif any(
        raw_run.get(field) is not None
        for field in ("noopMigrateReturnCode", "noopVerifier", "noopFingerprint")
    ):
        raise ValueError("run-02 must not contain no-op results")
    stage_codes.append(("compose-down", raw_run.get("cleanupReturnCode")))
    if raw_run.get("upReturnCode") != 0:
        raise ValueError("raw runtime run aggregate return code must be zero")
    stages: list[dict[str, object]] = []
    for name, code in stage_codes:
        if isinstance(code, bool) or not isinstance(code, int) or code != 0:
            raise ValueError(f"raw runtime stage {name} must have an exact zero exit code")
        stages.append({"name": name, "exitCode": code})
    return (
        {"id": expected_id, "fingerprint": fingerprint, "stages": stages, "noopMigrate": noop},
        postgres_version,
    )


def _normalize_failure_scenario(
    raw_scenario: object,
    definition: tuple[str, str, str, tuple[str, ...]],
) -> dict[str, object]:
    name, message, phase, stage_names = definition
    raw_scenario = _require_exact_fields(
        raw_scenario,
        _RAW_CHECKSUM_FAILURE_FIELDS if name == "checksum-mismatch" else _RAW_FAILURE_FIELDS,
        f"raw failure scenario {name}",
    )
    return_code = raw_scenario.get("returnCode")
    expected_raw = {
        "name": name,
        "expectedMessage": message,
        "actualMessage": message,
        "expectedPhase": phase,
        "actualPhase": phase,
        "expectedResult": "failure",
        "actualResult": "failure",
        "expectedReturnCode": "nonzero",
        "messageMatched": True,
        "status": "PASSED",
    }
    for field, expected in expected_raw.items():
        if raw_scenario.get(field) != expected:
            raise ValueError(f"raw failure scenario {name} has inconsistent {field}")
    if isinstance(return_code, bool) or not isinstance(return_code, int) or return_code == 0:
        raise ValueError(f"raw failure scenario {name} must have a nonzero return code")
    if name == "checksum-mismatch":
        stage_codes = [("strict-validate", return_code)]
    elif name == "missing-role":
        stage_codes = [
            ("postgres-start", raw_scenario.get("postgresReturnCode")),
            ("V830-migrate", return_code),
            ("compose-down", raw_scenario.get("cleanupReturnCode")),
        ]
    else:
        stage_codes = [
            ("postgres-start", raw_scenario.get("postgresReturnCode")),
            ("V830-migrate", raw_scenario.get("baselineReturnCode")),
            ("mutation", raw_scenario.get("mutationReturnCode")),
            ("V840-migrate", return_code),
            ("compose-down", raw_scenario.get("cleanupReturnCode")),
        ]
    if tuple(stage for stage, _ in stage_codes) != stage_names:
        raise ValueError(f"raw failure scenario {name} has an unexpected stage vector")
    stages: list[dict[str, object]] = []
    for stage_name, code in stage_codes:
        if isinstance(code, bool) or not isinstance(code, int):
            raise ValueError(f"raw failure scenario {name} stage code must be an integer")
        if (stage_name == phase) != (code != 0):
            raise ValueError(f"raw failure scenario {name} stage results are inconsistent")
        stages.append({"name": stage_name, "exitCode": code})
    return {
        "name": name,
        "expectedMessage": message,
        "actualMessage": message,
        "expectedPhase": phase,
        "actualPhase": phase,
        "expectedResult": "failure",
        "actualResult": "failure",
        "expectedReturnCode": "nonzero",
        "returnCode": return_code,
        "stages": stages,
    }


def _require_exact_fields(value: object, fields: Sequence[str], context: str) -> Mapping[str, object]:
    if not isinstance(value, Mapping) or set(value) != set(fields):
        raise ValueError(f"{context} must contain exactly: {', '.join(fields)}")
    if not all(isinstance(key, str) for key in value):
        raise ValueError(f"{context} keys must be strings")
    return value


def _validate_utc_timestamp(value: object, context: str) -> None:
    if not isinstance(value, str) or not _UTC_TIMESTAMP_PATTERN.fullmatch(value):
        raise ValueError(f"{context} must use YYYY-MM-DDTHH:MM:SSZ")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ValueError(f"{context} must be a real UTC timestamp") from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
        raise ValueError(f"{context} must be canonical UTC")


def _validate_publish_images(images: object, raw_lock: Mapping[str, object]) -> None:
    _require_exact_fields(raw_lock, ("images",), "toolchain lock")
    lock_images = raw_lock["images"]
    if not isinstance(lock_images, list) or not isinstance(images, list):
        raise ValueError("toolchain and evidence images must be arrays")
    if len(lock_images) != 2 or len(images) != 2:
        raise ValueError("evidence must contain exactly the two locked images")
    required_names = ("postgres", "redgate/flyway")
    for index, required_name in enumerate(required_names):
        lock_image = _require_exact_fields(lock_images[index], _LOCK_FIELDS, f"toolchain image {index}")
        image = _require_exact_fields(images[index], _PUBLISH_IMAGE_FIELDS, f"evidence image {index}")
        if lock_image["image"] != required_name or image["image"] != required_name:
            raise ValueError("evidence image order and names must match the required toolchain")
        if image["lockedTag"] != lock_image["tag"] or image["lockedDigest"] != lock_image["digest"]:
            raise ValueError("evidence image tag and digest must match the toolchain lock")
        tag = lock_image["tag"]
        digest = lock_image["digest"]
        if tag != _REQUIRED_IMAGE_TAGS[required_name]:
            raise ValueError("toolchain evidence tag must be the required PostgreSQL/Flyway tag")
        if not isinstance(digest, str) or not _DIGEST_PATTERN.fullmatch(digest):
            raise ValueError("toolchain evidence digest must be one lowercase sha256 digest")
        resolved_at = lock_image["resolvedAt"]
        if not isinstance(resolved_at, str):
            raise ValueError("toolchain evidence resolvedAt must be text")
        _validate_timestamp(resolved_at, required_name)
        _validate_repo_digest(image["actualRepoDigest"], required_name, digest)


def _validate_repo_digest(value: object, image: str, digest: str) -> None:
    if not isinstance(value, str) or value.count("@") != 1:
        raise ValueError("actualRepoDigest must be one repository@sha256 reference")
    repository, actual_digest = value.rsplit("@", 1)
    if actual_digest != digest:
        raise ValueError("actualRepoDigest must carry the locked digest")
    docker_hub_prefixes = ("", "docker.io/", "index.docker.io/", "registry-1.docker.io/", "registry.hub.docker.com/")
    canonical_name = "library/postgres" if image == "postgres" else "redgate/flyway"
    allowed_repositories = {prefix + canonical_name for prefix in docker_hub_prefixes}
    if image == "postgres":
        allowed_repositories.add("postgres")
    elif image == "redgate/flyway":
        allowed_repositories.add("redgate/flyway")
    if repository not in allowed_repositories:
        raise ValueError("actualRepoDigest repository must identify the locked image")
    if ":" in repository.rsplit("/", 1)[-1]:
        raise ValueError("actualRepoDigest must not contain a tag")


def _validate_publish_runs(runs: object) -> None:
    if not isinstance(runs, list) or len(runs) != 2:
        raise ValueError("runtime evidence must contain exactly two runs")
    fingerprints: list[str] = []
    for index, expected_id in enumerate(("run-01", "run-02")):
        run = _require_exact_fields(runs[index], _PUBLISH_RUN_FIELDS, f"runtime run {index}")
        if run["id"] != expected_id:
            raise ValueError("runtime run IDs must be unique and exactly run-01, run-02")
        fingerprint = run["fingerprint"]
        if not isinstance(fingerprint, str) or not _FINGERPRINT_PATTERN.fullmatch(fingerprint):
            raise ValueError("runtime fingerprints must be lowercase 32-hex values")
        fingerprints.append(fingerprint)
        _validate_stage_vector(run["stages"], _RUN_STAGE_NAMES[index], None, None, "runtime run")
        noop = run["noopMigrate"]
        if index == 0:
            noop_mapping = _require_exact_fields(noop, _PUBLISH_NOOP_FIELDS, "run-01 noopMigrate")
            if noop_mapping["exitCode"] != 0 or noop_mapping["fingerprint"] != fingerprint:
                raise ValueError("run-01 no-op migrate must exit zero without changing the fingerprint")
        elif noop is not None:
            raise ValueError("run-02 must not contain a no-op migrate result")
    if len(set(fingerprints)) != 1:
        raise ValueError("run-01 and run-02 fingerprints must match exactly")


def _validate_stage_vector(
    stages: object,
    expected_names: Sequence[str],
    failing_phase: str | None,
    failure_code: int | None,
    context: str,
) -> None:
    if not isinstance(stages, list) or len(stages) != len(expected_names):
        raise ValueError(f"{context} stage vector has the wrong length")
    for index, expected_name in enumerate(expected_names):
        stage = _require_exact_fields(stages[index], _PUBLISH_STAGE_FIELDS, f"{context} stage {index}")
        exit_code = stage["exitCode"]
        if stage["name"] != expected_name:
            raise ValueError(f"{context} stage vector is not in the required order")
        if isinstance(exit_code, bool) or not isinstance(exit_code, int):
            raise ValueError(f"{context} stage exitCode must be an integer")
        expected_code = failure_code if expected_name == failing_phase else 0
        if exit_code != expected_code:
            raise ValueError(f"{context} stage exitCode is inconsistent with its result")


def _validate_publish_failures(scenarios: object) -> None:
    if not isinstance(scenarios, list) or len(scenarios) != len(_PUBLISH_FAILURE_DEFINITIONS):
        raise ValueError("runtime evidence must contain exactly five failure scenarios")
    for index, (name, message, phase, stage_names) in enumerate(_PUBLISH_FAILURE_DEFINITIONS):
        scenario = _require_exact_fields(
            scenarios[index], _PUBLISH_FAILURE_FIELDS, f"failure scenario {index}"
        )
        if scenario["name"] != name:
            raise ValueError("failure scenarios must use the required unique order")
        if scenario["expectedMessage"] != message or scenario["actualMessage"] != message:
            raise ValueError(f"failure scenario {name} did not match its exact expected message")
        if scenario["expectedPhase"] != phase or scenario["actualPhase"] != phase:
            raise ValueError(f"failure scenario {name} did not fail in its exact expected phase")
        if scenario["expectedResult"] != "failure" or scenario["actualResult"] != "failure":
            raise ValueError(f"failure scenario {name} must record expected and actual failure")
        if scenario["expectedReturnCode"] != "nonzero":
            raise ValueError(f"failure scenario {name} must expect a nonzero return code")
        return_code = scenario["returnCode"]
        if isinstance(return_code, bool) or not isinstance(return_code, int) or return_code == 0:
            raise ValueError(f"failure scenario {name} must record a nonzero integer return code")
        _validate_stage_vector(scenario["stages"], stage_names, phase, return_code, f"failure scenario {name}")


def _validate_contract_summary(summary: object, manifest: Mapping[str, object]) -> None:
    contract = _require_exact_fields(summary, _PUBLISH_CONTRACT_FIELDS, "contract summary")
    expected = {
        "contractVersion": manifest.get("contractVersion"),
        "migrationCount": 19,
        "managedTableCount": manifest.get("physicalTableCountAfterFlywayBootstrap"),
        "managedSchemaCount": len(manifest.get("schemas", ())) if isinstance(manifest.get("schemas"), list) else None,
        "physicalForeignKeyCount": (
            len(manifest.get("physicalForeignKeyWhitelist", ()))
            if isinstance(manifest.get("physicalForeignKeyWhitelist"), list)
            else None
        ),
        "mutationGuardCount": 53,
        "contractSha256": manifest.get("contractSha256"),
        "fieldContractSha256": manifest.get("fieldContractSha256"),
    }
    if manifest.get("applicationTableCount") != 52 or expected["managedTableCount"] != 54:
        raise ValueError("contract manifest does not describe the frozen 52-plus-2 table boundary")
    for field, expected_value in expected.items():
        if contract[field] != expected_value:
            raise ValueError(f"contract summary {field} does not match the exact manifest")
    for digest_field in ("contractSha256", "fieldContractSha256"):
        digest = contract[digest_field]
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"contract summary {digest_field} must be lowercase 64-hex")


def _render_publishable_report(summary: Mapping[str, object]) -> str:
    images = summary["images"]
    runs = summary["runs"]
    failures = summary["failureScenarios"]
    contract = summary["contractSummary"]
    if not all(isinstance(value, list) for value in (images, runs, failures)) or not isinstance(contract, Mapping):
        raise ValueError("validated evidence has an invalid internal shape")
    lines = [
        "# PostgreSQL 18 Schema runtime evidence",
        "",
        f"- Status: `{summary['status']}`",
        f"- Git commit: `{summary['gitCommit']}`",
        f"- Verified at UTC: `{summary['verifiedAtUtc']}`",
        f"- PostgreSQL: `{summary['postgresVersion']}`",
        f"- Flyway: `{summary['flywayVersion']}`",
        "",
        "## Locked images",
        "",
        "| Image | Locked tag | Locked digest | Actual RepoDigest |",
        "|---|---|---|---|",
    ]
    for image in images:
        lines.append(
            f"| `{image['image']}` | `{image['lockedTag']}` | `{image['lockedDigest']}` | `{image['actualRepoDigest']}` |"
        )
    lines.extend(
        [
            "",
            "## Successful runs",
            "",
            "| Run | Fingerprint | Ordered stages (name=exit) | No-op migrate |",
            "|---|---|---|---|",
        ]
    )
    for run in runs:
        stage_text = ", ".join(f"{stage['name']}={stage['exitCode']}" for stage in run["stages"])
        noop = run["noopMigrate"]
        noop_text = "not applicable" if noop is None else f"exit={noop['exitCode']}, fingerprint={noop['fingerprint']}"
        lines.append(f"| `{run['id']}` | `{run['fingerprint']}` | {stage_text} | {noop_text} |")
    lines.extend(
        [
            "",
            "## Exact contract summary",
            "",
            f"- Migrations: `{contract['migrationCount']}`",
            f"- Managed tables: `{contract['managedTableCount']}`",
            f"- Managed schemas: `{contract['managedSchemaCount']}`",
            f"- Physical foreign keys: `{contract['physicalForeignKeyCount']}`",
            f"- Mutation guards: `{contract['mutationGuardCount']}`",
            f"- Contract version: `{contract['contractVersion']}`",
            f"- Contract SHA-256: `{contract['contractSha256']}`",
            f"- Field contract SHA-256: `{contract['fieldContractSha256']}`",
            "",
            "## Failure-closed scenarios",
            "",
            "| Scenario | Expected message | Phase | Result | Return code | Ordered stages (name=exit) |",
            "|---|---|---|---|---:|---|",
        ]
    )
    for scenario in failures:
        stage_text = ", ".join(f"{stage['name']}={stage['exitCode']}" for stage in scenario["stages"])
        lines.append(
            f"| `{scenario['name']}` | {scenario['expectedMessage']} | `{scenario['actualPhase']}` | "
            f"`{scenario['actualResult']}` | `{scenario['returnCode']}` | {stage_text} |"
        )
    lines.extend(
        [
            "",
            "This report proves only the frozen database schema runtime contract described above.",
            "",
        ]
    )
    return "\n".join(lines)


def _publish_pair_atomically(targets: tuple[Path, Path], contents: tuple[bytes, bytes]) -> None:
    directory = targets[0].parent
    old_contents = tuple(_read_optional_target(target) for target in targets)
    if (old_contents[0] is None) != (old_contents[1] is None):
        raise ValueError("publishable evidence targets must both exist or both be absent")
    transaction_files: list[Path] = []
    try:
        publish_files: list[Path] = []
        for target, content in zip(targets, contents, strict=True):
            publish_file = _write_transaction_file(
                directory, f".{target.name}.publish-", content
            )
            publish_files.append(publish_file)
            transaction_files.append(publish_file)
        backup_files: list[Path] = []
        for target, old in zip(targets, old_contents, strict=True):
            backup_file = _write_transaction_file(
                directory,
                f".{target.name}.backup-",
                old if old is not None else b"",
            )
            backup_files.append(backup_file)
            transaction_files.append(backup_file)
        os.replace(publish_files[0], targets[0])
        transaction_files.remove(publish_files[0])
        os.replace(publish_files[1], targets[1])
        transaction_files.remove(publish_files[1])
        for backup in backup_files:
            _unlink_transaction_file(backup)
            transaction_files.remove(backup)
    except (OSError, EvidenceIOError) as error:
        rollback_errors = _rollback_publish_pair(targets, old_contents, transaction_files)
        cause = str(error)
        if rollback_errors:
            cause += "; rollback=" + "; ".join(rollback_errors)
        raise EvidenceIOError("publish_evidence_pair", targets[1], cause) from error


def _read_optional_target(path: Path) -> bytes | None:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return None
    except OSError as error:
        raise EvidenceIOError("read_existing_target", path, error) from error
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"publication target must be a regular non-symlink file: {path}")
    try:
        return path.read_bytes()
    except OSError as error:
        raise EvidenceIOError("read_existing_target", path, error) from error


def _write_transaction_file(directory: Path, prefix: str, contents: bytes) -> Path:
    path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=directory, prefix=prefix, suffix=".tmp", delete=False
        ) as temporary_file:
            path = Path(temporary_file.name)
            temporary_file.write(contents)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        return path
    except OSError as error:
        if path is not None:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        raise EvidenceIOError("write_transaction_file", directory, error) from error


def _unlink_transaction_file(path: Path) -> None:
    path.unlink(missing_ok=True)


def _rollback_publish_pair(
    targets: tuple[Path, Path],
    old_contents: tuple[bytes | None, bytes | None],
    transaction_files: Sequence[Path],
) -> list[str]:
    errors: list[str] = []
    for target, old in zip(targets, old_contents, strict=True):
        try:
            if old is None:
                _unlink_transaction_file(target)
            else:
                restore = _write_transaction_file(target.parent, f".{target.name}.restore-", old)
                try:
                    os.replace(restore, target)
                finally:
                    _unlink_transaction_file(restore)
        except (OSError, ValueError) as error:
            errors.append(f"restore {target}: {error}")
    for path in transaction_files:
        try:
            _unlink_transaction_file(path)
        except OSError as error:
            errors.append(f"cleanup {path}: {error}")
    return errors


def build_ci_runtime_fallback(
    *,
    git_commit: str,
    workflow_step_outcome: str,
    lock: Mapping[str, object],
) -> dict[str, object]:
    """Build a closed minimal artifact without accepting runtime or exception text."""
    if not isinstance(workflow_step_outcome, str) or workflow_step_outcome not in _CI_FALLBACK_OUTCOMES:
        raise ValueError("workflow step outcome is not supported")
    if not isinstance(git_commit, str) or not _COMMIT_PATTERN.fullmatch(git_commit):
        raise ValueError("CI artifact Git commit must be one lowercase 40-hex commit")
    outcome, reason = _CI_FALLBACK_OUTCOMES[workflow_step_outcome]
    locked_images = _ci_locked_images_from_lock(lock)
    candidate = {
        "schemaVersion": _CI_SCHEMA_VERSION,
        "gitCommit": git_commit,
        "workflowOutcome": outcome,
        "reasonCode": reason,
        "runs": _ci_not_started_runs(),
        "failureScenarios": [],
        "toolchain": _ci_toolchain(locked_images, postgres_version=None, flyway_version=None),
        "contractSummary": _ci_unverified_contract_summary(),
    }
    return _validate_ci_runtime_summary(candidate)


def build_ci_runtime_summary(
    result: RuntimeVerificationResult,
    *,
    git_commit: str,
    manifest: Mapping[str, object],
) -> dict[str, object]:
    """Build the CI schema directly from typed controller facts held in memory."""
    if not isinstance(result, RuntimeVerificationResult):
        raise ValueError("CI artifact requires a typed runtime controller result")
    if not isinstance(git_commit, str) or not _COMMIT_PATTERN.fullmatch(git_commit):
        raise ValueError("CI artifact Git commit must be one lowercase 40-hex commit")
    status = result.get("status")
    if status == "PASSED":
        reason = "runtime_verified"
    else:
        reason = result.get("reason")
    if not isinstance(status, str) or not isinstance(reason, str):
        raise ValueError("runtime controller result has no closed outcome and reason")
    if _CI_REASON_OUTCOMES.get(reason) != status or reason in _CI_FALLBACK_REASONS:
        raise ValueError("runtime controller result outcome and reason are inconsistent")
    if reason in {"runtime_verified", "runtime_fingerprint_mismatch"}:
        _validate_ci_controller_fingerprint_outcome(
            result,
            fingerprints_must_match=reason == "runtime_verified",
        )

    captured = dict(result.ci_stage_results)
    diagnostics = dict(result.ci_stage_diagnostics)
    known_stage_paths = {
        path
        for _, definitions in _CI_RUN_STAGE_DEFINITIONS
        for _, _, path in definitions
    } | {
        path
        for _, _, definitions in _CI_FAILURE_STAGE_DEFINITIONS
        for _, _, path in definitions
    } | set(_CI_AUXILIARY_STAGE_PATHS)
    if not set(captured) <= known_stage_paths:
        raise ValueError("runtime controller returned an unknown CI stage")
    if not set(diagnostics) <= _CI_VERIFIER_DIAGNOSTIC_STAGE_PATHS:
        raise ValueError("runtime controller returned an unknown verifier diagnostic stage")
    runs = [
        {
            "runId": run_id,
            "stages": [
                _ci_stage_record(
                    stage_name,
                    command_class,
                    captured.get(path),
                    verifier_diagnostic=diagnostics.get(path),
                )
                for stage_name, command_class, path in definitions
            ],
        }
        for run_id, definitions in _CI_RUN_STAGE_DEFINITIONS
    ]

    raw_scenarios = result.get("failureScenarios")
    if not isinstance(raw_scenarios, list):
        raise ValueError("runtime controller failure scenarios must be an array")
    scenario_statuses = {
        scenario.get("name"): scenario.get("status")
        for scenario in raw_scenarios
        if isinstance(scenario, Mapping)
        and isinstance(scenario.get("name"), str)
        and isinstance(scenario.get("status"), str)
    }
    reached_scenarios = any(
        path in captured
        for _, _, definitions in _CI_FAILURE_STAGE_DEFINITIONS
        for _, _, path in definitions
    )
    failure_scenarios: list[dict[str, object]] = []
    if reached_scenarios:
        failure_scenarios = [
            {
                "scenarioName": scenario_name,
                "stages": [
                    _ci_stage_record(
                        stage_name,
                        command_class,
                        captured.get(path),
                        expected_failure=(
                            stage_name == failure_stage
                            and scenario_statuses.get(scenario_name) == "PASSED"
                        ),
                    )
                    for stage_name, command_class, path in definitions
                ],
            }
            for scenario_name, failure_stage, definitions in _CI_FAILURE_STAGE_DEFINITIONS
        ]

    postgres_version: str | None = None
    flyway_version: str | None = None
    contract_summary = _ci_unverified_contract_summary()
    if status == "PASSED":
        postgres_version = _ci_postgres_version(result)
        flyway_version = _ci_flyway_version(result)
        contract_summary = _ci_verified_contract_summary(manifest)

    candidate = {
        "schemaVersion": _CI_SCHEMA_VERSION,
        "gitCommit": git_commit,
        "workflowOutcome": status,
        "reasonCode": reason,
        "runs": runs,
        "failureScenarios": failure_scenarios,
        "toolchain": _ci_toolchain(
            result.ci_locked_images,
            postgres_version=postgres_version,
            flyway_version=flyway_version,
        ),
        "contractSummary": contract_summary,
    }
    return _validate_ci_runtime_summary(candidate)


def _ci_stage_record(
    stage_name: str,
    command_class: str,
    captured: CapturedStageResult | None,
    *,
    expected_failure: bool = False,
    verifier_diagnostic: str | None = None,
) -> dict[str, object]:
    if captured is None:
        if verifier_diagnostic is not None:
            raise ValueError("verifier diagnostic cannot target a not-started stage")
        return {
            "stageName": stage_name,
            "commandClass": command_class,
            "exitCode": None,
            "timedOut": False,
            "diagnosticCode": "not_started",
            "stdoutSha256": None,
            "stderrSha256": None,
        }
    if verifier_diagnostic is not None:
        if (
            stage_name not in _CI_VERIFIER_DIAGNOSTIC_STAGE_NAMES
            or verifier_diagnostic not in _VERIFIER_DIAGNOSTIC_CODES
            or captured.timed_out
            or captured.exit_code in {0, 127}
        ):
            raise ValueError("verifier diagnostic is inconsistent with its failed wait stage")
        diagnostic = verifier_diagnostic
    elif captured.timed_out:
        diagnostic = "timed_out"
    elif captured.exit_code == 0:
        diagnostic = "ok"
    elif expected_failure:
        diagnostic = "expected_failure"
    elif captured.exit_code == 127:
        diagnostic = "command_unavailable"
    else:
        diagnostic = "process_failed"
    return {
        "stageName": stage_name,
        "commandClass": command_class,
        "exitCode": captured.exit_code,
        "timedOut": captured.timed_out,
        "diagnosticCode": diagnostic,
        "stdoutSha256": captured.stdout_sha256,
        "stderrSha256": captured.stderr_sha256,
    }


def _ci_locked_images_from_lock(lock: Mapping[str, object]) -> tuple[_CiLockedImage, ...]:
    lock = _require_exact_fields(lock, ("images",), "CI toolchain lock")
    raw_images = lock["images"]
    if not isinstance(raw_images, list) or len(raw_images) != 2:
        raise ValueError("CI toolchain lock must contain exactly two images")
    images: list[_CiLockedImage] = []
    for index, expected in enumerate(_CI_LOCKED_IMAGES):
        raw_image = _require_exact_fields(raw_images[index], _LOCK_FIELDS, f"CI toolchain image {index}")
        image_name, locked_tag, locked_digest = expected
        if (
            raw_image["image"] != image_name
            or raw_image["tag"] != locked_tag
            or raw_image["digest"] != locked_digest
        ):
            raise ValueError("CI toolchain image does not match the reviewed lock")
        resolved_at = raw_image["resolvedAt"]
        if not isinstance(resolved_at, str):
            raise ValueError("CI toolchain image resolvedAt must be text")
        _validate_timestamp(resolved_at, image_name)
        images.append(_CiLockedImage(image_name, locked_tag, locked_digest))
    return tuple(images)


def _ci_toolchain(
    locked_images: Sequence[_CiLockedImage],
    *,
    postgres_version: str | None,
    flyway_version: str | None,
) -> dict[str, object]:
    return {
        "images": [
            {
                "imageName": image.image_name,
                "lockedTag": image.locked_tag,
                "lockedDigest": image.locked_digest,
            }
            for image in locked_images
        ],
        "postgresVersion": postgres_version,
        "flywayVersion": flyway_version,
    }


def _ci_not_started_runs() -> list[dict[str, object]]:
    return [
        {
            "runId": run_id,
            "stages": [
                _ci_stage_record(stage_name, command_class, None)
                for stage_name, command_class, _ in definitions
            ],
        }
        for run_id, definitions in _CI_RUN_STAGE_DEFINITIONS
    ]


def _ci_unverified_contract_summary() -> dict[str, object]:
    return {
        "verified": False,
        "migrationCount": None,
        "managedTableCount": None,
        "managedSchemaCount": None,
        "physicalForeignKeyCount": None,
        "mutationGuardCount": None,
        "contractSha256": None,
        "fieldContractSha256": None,
    }


def _ci_verified_contract_summary(manifest: Mapping[str, object]) -> dict[str, object]:
    schemas = manifest.get("schemas")
    foreign_keys = manifest.get("physicalForeignKeyWhitelist")
    candidate = {
        "verified": True,
        "migrationCount": 19,
        "managedTableCount": manifest.get("physicalTableCountAfterFlywayBootstrap"),
        "managedSchemaCount": len(schemas) if isinstance(schemas, list) else None,
        "physicalForeignKeyCount": len(foreign_keys) if isinstance(foreign_keys, list) else None,
        "mutationGuardCount": 53,
        "contractSha256": manifest.get("contractSha256"),
        "fieldContractSha256": manifest.get("fieldContractSha256"),
    }
    if manifest.get("applicationTableCount") != 52:
        raise ValueError("CI contract manifest is outside the 52-plus-2 boundary")
    return candidate


def _ci_postgres_version(result: Mapping[str, object]) -> str:
    runs = result.get("runs")
    if not isinstance(runs, list) or len(runs) != 2:
        raise ValueError("CI runtime result must contain exactly two runs")
    versions: list[str] = []
    for run in runs:
        if not isinstance(run, Mapping):
            raise ValueError("CI runtime run must be an object")
        verifier = run.get("verifier")
        summary = verifier.get("summary") if isinstance(verifier, Mapping) else None
        value = summary.get("serverVersion") if isinstance(summary, Mapping) else None
        if not isinstance(value, str):
            raise ValueError("CI runtime run has no typed PostgreSQL version")
        match = re.fullmatch(r"(18\.[0-9]+(?:\.[0-9]+)?)(?: [^\r\n]+)?", value)
        if match is None:
            raise ValueError("CI PostgreSQL version is outside the allow-list")
        versions.append(match.group(1))
    if len(set(versions)) != 1:
        raise ValueError("CI runtime runs report different PostgreSQL versions")
    return versions[0]


def _validate_ci_controller_fingerprint_outcome(
    result: Mapping[str, object],
    *,
    fingerprints_must_match: bool,
) -> None:
    """Bind the closed pass/mismatch outcome to two complete typed verifier summaries."""
    raw_runs = result.get("runs")
    if not isinstance(raw_runs, list) or len(raw_runs) != 2:
        raise ValueError("CI fingerprint outcome requires exactly two runtime runs")
    fingerprints: list[str] = []
    for index, expected_id in enumerate(("run-01", "run-02")):
        run = _require_exact_fields(raw_runs[index], _RAW_RUN_FIELDS, f"CI controller {expected_id}")
        if run["id"] != expected_id:
            raise ValueError("CI controller runs must be exactly run-01 then run-02")
        for field in ("postgresReturnCode", "upReturnCode", "cleanupReturnCode"):
            if type(run[field]) is not int or run[field] != 0:
                raise ValueError(f"CI controller {expected_id} {field} must be zero")
        flyway = _require_exact_fields(run["flyway"], _RAW_SERVICE_FIELDS, f"CI controller {expected_id} Flyway")
        if (
            flyway["service"] != "flyway"
            or flyway["logsReturnCode"] is not None
            or flyway["summary"] is not None
            or any(type(flyway[field]) is not int or flyway[field] != 0 for field in (
                "startReturnCode",
                "waitReturnCode",
                "statusReturnCode",
                "exitCode",
            ))
        ):
            raise ValueError(f"CI controller {expected_id} Flyway result is incomplete")
        verifier = _require_exact_fields(
            run["verifier"],
            _RAW_SERVICE_FIELDS,
            f"CI controller {expected_id} verifier",
        )
        summary = _validate_ci_controller_verifier(
            verifier,
            context=f"CI controller {expected_id} verifier",
        )
        fingerprint = run["initialFingerprint"]
        if (
            not isinstance(fingerprint, str)
            or not _FINGERPRINT_PATTERN.fullmatch(fingerprint)
            or summary["fingerprint"] != fingerprint
        ):
            raise ValueError(f"CI controller {expected_id} fingerprint is invalid or unbound")
        fingerprints.append(fingerprint)

        if index == 0:
            if type(run["noopMigrateReturnCode"]) is not int or run["noopMigrateReturnCode"] != 0:
                raise ValueError("CI controller run-01 no-op migrate must succeed")
            noop_verifier = _require_exact_fields(
                run["noopVerifier"],
                _RAW_SERVICE_FIELDS,
                "CI controller run-01 no-op verifier",
            )
            noop_summary = _validate_ci_controller_verifier(
                noop_verifier,
                context="CI controller run-01 no-op verifier",
            )
            noop_fingerprint = run["noopFingerprint"]
            if (
                not isinstance(noop_fingerprint, str)
                or not _FINGERPRINT_PATTERN.fullmatch(noop_fingerprint)
                or noop_summary["fingerprint"] != noop_fingerprint
                or noop_fingerprint != fingerprint
                or noop_summary["postgresVersion"] != summary["postgresVersion"]
                or noop_summary["serverVersion"] != summary["serverVersion"]
            ):
                raise ValueError("CI controller run-01 no-op fingerprint is invalid or changed")
        elif any(
            run[field] is not None
            for field in ("noopMigrateReturnCode", "noopVerifier", "noopFingerprint")
        ):
            raise ValueError("CI controller run-02 must not contain no-op results")

    fingerprints_match = fingerprints[0] == fingerprints[1]
    if fingerprints_match != fingerprints_must_match:
        expected = "match" if fingerprints_must_match else "differ"
        raise ValueError(f"CI controller A/B fingerprints must {expected}")


def _validate_ci_controller_verifier(
    value: object,
    *,
    context: str,
) -> Mapping[str, object]:
    verifier = _require_exact_fields(value, _RAW_SERVICE_FIELDS, context)
    if (
        verifier["service"] != "verifier"
        or any(type(verifier[field]) is not int or verifier[field] != 0 for field in (
            "startReturnCode",
            "waitReturnCode",
            "statusReturnCode",
            "exitCode",
            "logsReturnCode",
        ))
    ):
        raise ValueError(f"{context} result is incomplete")
    summary = _require_exact_fields(verifier["summary"], _RAW_SUMMARY_FIELDS, f"{context} summary")
    if summary["status"] != "PASSED":
        raise ValueError(f"{context} summary did not pass")
    return summary


def _ci_flyway_version(result: Mapping[str, object]) -> str:
    identity = result.get("runtimeIdentity")
    value = identity.get("flywayVersion") if isinstance(identity, Mapping) else None
    if value not in {
        "Flyway Community Edition 13.4.0 by Redgate",
        "Flyway OSS Edition 13.4.0 by Redgate",
    }:
        raise ValueError("CI Flyway version is outside the allow-list")
    return _CI_FLYWAY_VERSION


def _validate_ci_runtime_summary(summary: Mapping[str, object]) -> dict[str, object]:
    summary = _require_exact_fields(summary, _CI_TOP_LEVEL_FIELDS, "CI runtime summary")
    if summary["schemaVersion"] != _CI_SCHEMA_VERSION:
        raise ValueError("CI runtime summary schemaVersion is not supported")
    git_commit = summary["gitCommit"]
    if not isinstance(git_commit, str) or not _COMMIT_PATTERN.fullmatch(git_commit):
        raise ValueError("CI runtime summary Git commit must be one lowercase 40-hex commit")
    outcome = summary["workflowOutcome"]
    reason = summary["reasonCode"]
    if not isinstance(outcome, str) or outcome not in {"PASSED", "FAILED", "BLOCKED"}:
        raise ValueError("CI runtime summary workflowOutcome is not supported")
    if not isinstance(reason, str) or _CI_REASON_OUTCOMES.get(reason) != outcome:
        raise ValueError("CI runtime summary outcome and reason are inconsistent")

    runs = summary["runs"]
    if not isinstance(runs, list) or len(runs) != 2:
        raise ValueError("CI runtime summary must contain exactly two runs")
    for index, (run_id, definitions) in enumerate(_CI_RUN_STAGE_DEFINITIONS):
        run = _require_exact_fields(runs[index], _CI_RUN_FIELDS, f"CI runtime run {index}")
        if run["runId"] != run_id:
            raise ValueError("CI runtime runs must be exactly run-01 then run-02")
        _validate_ci_stage_vector(run["stages"], definitions, expected_failure_stage=None)
        _validate_ci_verifier_diagnostic_context(run["stages"])

    scenarios = summary["failureScenarios"]
    if not isinstance(scenarios, list) or len(scenarios) not in {0, 5}:
        raise ValueError("CI failure scenarios must be empty or the complete five-scenario set")
    if scenarios:
        for index, (scenario_name, failure_stage, definitions) in enumerate(_CI_FAILURE_STAGE_DEFINITIONS):
            scenario = _require_exact_fields(
                scenarios[index],
                _CI_SCENARIO_FIELDS,
                f"CI failure scenario {index}",
            )
            if scenario["scenarioName"] != scenario_name:
                raise ValueError("CI failure scenarios must use the required order")
            _validate_ci_stage_vector(
                scenario["stages"],
                definitions,
                expected_failure_stage=failure_stage,
            )

    _validate_ci_toolchain(summary["toolchain"])
    _validate_ci_contract_summary(summary["contractSummary"])
    _validate_ci_outcome_semantics(summary)
    try:
        normalized = json.loads(
            json.dumps(summary, ensure_ascii=True, sort_keys=True, allow_nan=False)
        )
    except (TypeError, ValueError) as error:
        raise ValueError(f"CI runtime summary is not canonical JSON data: {error}") from error
    return normalized


def _validate_ci_stage_vector(
    stages: object,
    definitions: Sequence[tuple[str, str, str]],
    *,
    expected_failure_stage: str | None,
) -> None:
    if not isinstance(stages, list) or len(stages) != len(definitions):
        raise ValueError("CI stage vector has the wrong length")
    for index, (stage_name, command_class, _) in enumerate(definitions):
        stage = _require_exact_fields(stages[index], _CI_STAGE_FIELDS, f"CI stage {index}")
        if stage["stageName"] != stage_name or stage["commandClass"] != command_class:
            raise ValueError("CI stage vector has an unknown or out-of-order stage")
        _validate_ci_stage_result(
            stage,
            expected_failure=stage_name == expected_failure_stage,
        )


def _validate_ci_verifier_diagnostic_context(stages: Sequence[Mapping[str, object]]) -> None:
    by_name = {stage["stageName"]: stage for stage in stages}
    for wait_name, logs_name in (
        ("verifier-wait", "verifier-logs"),
        ("noop-verifier-wait", "noop-verifier-logs"),
    ):
        wait_stage = by_name.get(wait_name)
        logs_stage = by_name.get(logs_name)
        if wait_stage is None or logs_stage is None:
            continue
        diagnostic = wait_stage["diagnosticCode"]
        if diagnostic not in _VERIFIER_DIAGNOSTIC_CODES:
            continue
        logs_diagnostic = logs_stage["diagnosticCode"]
        if diagnostic == "verifier_logs_unavailable":
            if logs_diagnostic not in {"process_failed", "timed_out", "command_unavailable"}:
                raise ValueError("CI unavailable verifier logs diagnostic is inconsistent")
        elif logs_diagnostic != "ok":
            raise ValueError("CI verifier diagnostic requires a successful log capture")


def _validate_ci_stage_result(stage: Mapping[str, object], *, expected_failure: bool) -> None:
    exit_code = stage["exitCode"]
    timed_out = stage["timedOut"]
    diagnostic = stage["diagnosticCode"]
    stdout_hash = stage["stdoutSha256"]
    stderr_hash = stage["stderrSha256"]
    if not isinstance(timed_out, bool) or not isinstance(diagnostic, str):
        raise ValueError("CI stage timeout and diagnostic fields have invalid types")
    if exit_code is None:
        if (
            timed_out
            or diagnostic != "not_started"
            or stdout_hash is not None
            or stderr_hash is not None
        ):
            raise ValueError("CI not-started stage fields are inconsistent")
        return
    if isinstance(exit_code, bool) or not isinstance(exit_code, int):
        raise ValueError("CI executed stage exitCode must be an integer")
    if (
        not isinstance(stdout_hash, str)
        or not _CI_HASH_PATTERN.fullmatch(stdout_hash)
        or not isinstance(stderr_hash, str)
        or not _CI_HASH_PATTERN.fullmatch(stderr_hash)
    ):
        raise ValueError("CI executed stage hashes must be lowercase SHA-256 values")
    if timed_out:
        if exit_code != 124 or diagnostic != "timed_out":
            raise ValueError("CI timed-out stage fields are inconsistent")
    elif exit_code == 0:
        if diagnostic != "ok":
            raise ValueError("CI successful stage diagnostic is inconsistent")
    elif diagnostic == "expected_failure":
        if not expected_failure:
            raise ValueError("CI expected-failure diagnostic is used on the wrong stage")
    elif exit_code == 127:
        if diagnostic != "command_unavailable":
            raise ValueError("CI unavailable-command diagnostic is inconsistent")
    elif diagnostic in _VERIFIER_DIAGNOSTIC_CODES:
        if stage["stageName"] not in _CI_VERIFIER_DIAGNOSTIC_STAGE_NAMES:
            raise ValueError("CI verifier diagnostic is used on the wrong stage")
    elif diagnostic != "process_failed":
        raise ValueError("CI failed stage diagnostic is inconsistent")


def _validate_ci_toolchain(value: object) -> None:
    toolchain = _require_exact_fields(value, _CI_TOOLCHAIN_FIELDS, "CI toolchain")
    images = toolchain["images"]
    if not isinstance(images, list) or len(images) != 2:
        raise ValueError("CI toolchain must contain exactly two images")
    for index, (image_name, tag, digest) in enumerate(_CI_LOCKED_IMAGES):
        image = _require_exact_fields(images[index], _CI_IMAGE_FIELDS, f"CI toolchain image {index}")
        if (
            image["imageName"] != image_name
            or image["lockedTag"] != tag
            or image["lockedDigest"] != digest
        ):
            raise ValueError("CI toolchain image is outside the reviewed lock")
    postgres_version = toolchain["postgresVersion"]
    flyway_version = toolchain["flywayVersion"]
    if postgres_version is not None and (
        not isinstance(postgres_version, str)
        or not _CI_POSTGRES_VERSION_PATTERN.fullmatch(postgres_version)
    ):
        raise ValueError("CI PostgreSQL version is outside the allow-list")
    if flyway_version not in {None, _CI_FLYWAY_VERSION}:
        raise ValueError("CI Flyway version is outside the allow-list")
    if (postgres_version is None) != (flyway_version is None):
        raise ValueError("CI toolchain versions must both be verified or both be absent")


def _validate_ci_contract_summary(value: object) -> None:
    contract = _require_exact_fields(value, _CI_CONTRACT_FIELDS, "CI contract summary")
    if not isinstance(contract["verified"], bool):
        raise ValueError("CI contract verified flag must be boolean")
    fact_fields = (
        "migrationCount",
        "managedTableCount",
        "managedSchemaCount",
        "physicalForeignKeyCount",
        "mutationGuardCount",
        "contractSha256",
        "fieldContractSha256",
    )
    if not contract["verified"]:
        if any(contract[field] is not None for field in fact_fields):
            raise ValueError("CI unverified contract summary must contain only null facts")
        return
    expected = {
        "migrationCount": 19,
        "managedTableCount": 54,
        "managedSchemaCount": 13,
        "physicalForeignKeyCount": 206,
        "mutationGuardCount": 53,
    }
    if any(
        isinstance(contract[field], bool)
        or not isinstance(contract[field], int)
        or contract[field] != expected_value
        for field, expected_value in expected.items()
    ):
        raise ValueError("CI verified contract summary has inconsistent fixed facts")
    for field in ("contractSha256", "fieldContractSha256"):
        digest = contract[field]
        if not isinstance(digest, str) or not _CI_HASH_PATTERN.fullmatch(digest):
            raise ValueError("CI verified contract summary has an invalid hash")


def _validate_ci_outcome_semantics(summary: Mapping[str, object]) -> None:
    outcome = summary["workflowOutcome"]
    reason = summary["reasonCode"]
    run_stages = [stage for run in summary["runs"] for stage in run["stages"]]
    scenarios = summary["failureScenarios"]
    toolchain = summary["toolchain"]
    contract = summary["contractSummary"]
    if outcome == "PASSED":
        if not scenarios or any(stage["diagnosticCode"] != "ok" for stage in run_stages):
            raise ValueError("CI PASSED outcome requires two complete successful runs and five scenarios")
        for scenario, (_, failure_stage, _) in zip(
            scenarios,
            _CI_FAILURE_STAGE_DEFINITIONS,
            strict=True,
        ):
            for stage in scenario["stages"]:
                expected = "expected_failure" if stage["stageName"] == failure_stage else "ok"
                if stage["diagnosticCode"] != expected:
                    raise ValueError("CI PASSED failure scenario diagnostics are inconsistent")
        if toolchain["postgresVersion"] is None or not contract["verified"]:
            raise ValueError("CI PASSED outcome requires verified toolchain and contract facts")
        return
    if toolchain["postgresVersion"] is not None or contract["verified"]:
        raise ValueError("CI non-passed outcome cannot claim verified toolchain or contract facts")
    if reason in _CI_FALLBACK_REASONS:
        if scenarios or any(stage["diagnosticCode"] != "not_started" for stage in run_stages):
            raise ValueError("CI fallback outcome must contain only not-started run stages")
    elif reason == "docker_compose_unavailable":
        diagnostics = {stage["diagnosticCode"] for stage in run_stages}
        if scenarios or "command_unavailable" not in diagnostics or not diagnostics <= {
            "not_started",
            "command_unavailable",
        }:
            raise ValueError("CI Docker-blocked outcome has inconsistent stages")
    elif reason == "compose_up_failed":
        if not any(
            stage["exitCode"] not in {None, 0}
            and stage["diagnosticCode"] in {
                "process_failed",
                "timed_out",
                "command_unavailable",
            } | _VERIFIER_DIAGNOSTIC_CODES
            for stage in run_stages
        ):
            raise ValueError("CI compose startup failure has no failed run stage")
    elif reason == "compose_cleanup_failed":
        cleanup = [stage for stage in run_stages if stage["stageName"] == "compose-down"]
        if not any(stage["diagnosticCode"] not in {"ok", "not_started"} for stage in cleanup):
            raise ValueError("CI cleanup failure has no failed cleanup stage")
    elif reason == "failure_scenario_failed" and len(scenarios) != 5:
        raise ValueError("CI failure-scenario outcome requires the complete scenario set")
    elif reason == "runtime_fingerprint_mismatch":
        if len(scenarios) != 5 or any(stage["diagnosticCode"] != "ok" for stage in run_stages):
            raise ValueError("CI fingerprint mismatch requires two complete successful runs and five scenarios")
        for scenario, (_, failure_stage, _) in zip(
            scenarios,
            _CI_FAILURE_STAGE_DEFINITIONS,
            strict=True,
        ):
            for stage in scenario["stages"]:
                expected = "expected_failure" if stage["stageName"] == failure_stage else "ok"
                if stage["diagnosticCode"] != expected:
                    raise ValueError("CI fingerprint mismatch scenario diagnostics are inconsistent")


def render_ci_job_summary(summary: Mapping[str, object]) -> str:
    """Revalidate and render Markdown from closed safe fields only."""
    validated = _validate_ci_runtime_summary(summary)
    lines = [
        "# PostgreSQL 18 runtime CI summary",
        "",
        f"- Outcome: `{validated['workflowOutcome']}`",
        f"- Reason code: `{validated['reasonCode']}`",
        f"- Git commit: `{validated['gitCommit']}`",
        "",
        "## Runs",
        "",
        "| Run | Stage | Exit code | Timed out | Diagnostic | Stdout SHA-256 | Stderr SHA-256 |",
        "|---|---|---:|---|---|---|---|",
    ]
    for run in validated["runs"]:
        for stage in run["stages"]:
            exit_code = "not started" if stage["exitCode"] is None else str(stage["exitCode"])
            stdout_hash = stage["stdoutSha256"] or "not captured"
            stderr_hash = stage["stderrSha256"] or "not captured"
            lines.append(
                f"| `{run['runId']}` | `{stage['stageName']}` | `{exit_code}` | "
                f"`{str(stage['timedOut']).lower()}` | `{stage['diagnosticCode']}` | "
                f"`{stdout_hash}` | `{stderr_hash}` |"
            )
    lines.extend(["", "## Failure scenarios", ""])
    if not validated["failureScenarios"]:
        lines.append("Not reached.")
    else:
        lines.extend(
            [
                "| Scenario | Stage | Exit code | Timed out | Diagnostic | Stdout SHA-256 | Stderr SHA-256 |",
                "|---|---|---:|---|---|---|---|",
            ]
        )
        for scenario in validated["failureScenarios"]:
            for stage in scenario["stages"]:
                exit_code = "not started" if stage["exitCode"] is None else str(stage["exitCode"])
                stdout_hash = stage["stdoutSha256"] or "not captured"
                stderr_hash = stage["stderrSha256"] or "not captured"
                lines.append(
                    f"| `{scenario['scenarioName']}` | `{stage['stageName']}` | `{exit_code}` | "
                    f"`{str(stage['timedOut']).lower()}` | `{stage['diagnosticCode']}` | "
                    f"`{stdout_hash}` | `{stderr_hash}` |"
                )
    toolchain = validated["toolchain"]
    lines.extend(
        [
            "",
            "## Toolchain",
            "",
            "| Image | Locked tag | Locked digest |",
            "|---|---|---|",
        ]
    )
    for image in toolchain["images"]:
        lines.append(
            f"| `{image['imageName']}` | `{image['lockedTag']}` | `{image['lockedDigest']}` |"
        )
    lines.extend(
        [
            "",
            f"- PostgreSQL version: `{toolchain['postgresVersion'] or 'not verified'}`",
            f"- Flyway version: `{toolchain['flywayVersion'] or 'not verified'}`",
            "",
            "## Contract summary",
            "",
        ]
    )
    contract = validated["contractSummary"]
    lines.append(f"- Verified: `{str(contract['verified']).lower()}`")
    for label, field in (
        ("Migrations", "migrationCount"),
        ("Managed tables", "managedTableCount"),
        ("Managed schemas", "managedSchemaCount"),
        ("Physical foreign keys", "physicalForeignKeyCount"),
        ("Mutation guards", "mutationGuardCount"),
        ("Contract SHA-256", "contractSha256"),
        ("Field contract SHA-256", "fieldContractSha256"),
    ):
        lines.append(f"- {label}: `{contract[field] if contract[field] is not None else 'not verified'}`")
    lines.append("")
    return "\n".join(lines)


def export_ci_runtime_artifact(
    summary: Mapping[str, object],
    output_directory: Path,
) -> tuple[Path, Path]:
    """Publish one closed JSON/Markdown pair without leaving stale upload content."""
    output = _prepare_ci_output_path(output_directory)
    stale_path: Path | None = None
    staging_path: Path | None = None
    published = False
    try:
        stale_path, existing_is_safe = _quarantine_ci_output(output)
        if stale_path is not None and not existing_is_safe:
            raise ValueError("existing CI artifact target is not one exact regular-file pair")

        validated = _validate_ci_runtime_summary(summary)
        json_source = json.dumps(
            validated,
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        ) + "\n"
        markdown_source = render_ci_job_summary(validated)
        staging_path = Path(
            tempfile.mkdtemp(prefix=f".{output.name}.publish-", dir=output.parent)
        )
        _write_ci_artifact_file(staging_path / _CI_SUMMARY_NAME, json_source.encode("utf-8"))
        _write_ci_artifact_file(staging_path / _CI_MARKDOWN_NAME, markdown_source.encode("utf-8"))
        if not _ci_directory_is_exact_safe_pair(staging_path):
            raise ValueError("staged CI artifact is not one exact regular-file pair")
        os.replace(staging_path, output)
        staging_path = None
        published = True
        if stale_path is not None:
            _remove_ci_entry(stale_path)
            stale_path = None
        if not _ci_directory_is_exact_safe_pair(output):
            raise ValueError("published CI artifact is not one exact regular-file pair")
        return output / _CI_SUMMARY_NAME, output / _CI_MARKDOWN_NAME
    except Exception as error:
        cleanup_errors: list[str] = []
        for path in (output if published else None, staging_path, stale_path):
            if path is None:
                continue
            try:
                _remove_ci_entry(path)
            except (OSError, ValueError) as cleanup_error:
                cleanup_errors.append(str(cleanup_error))
        if cleanup_errors:
            raise EvidenceIOError(
                "cleanup_ci_artifact_transaction",
                output,
                "; ".join(cleanup_errors),
            ) from error
        if isinstance(error, ValueError):
            raise
        if isinstance(error, OSError):
            raise EvidenceIOError("publish_ci_artifact", output, error) from error
        raise


def validate_ci_runtime_artifact(output_directory: Path) -> dict[str, object]:
    """Revalidate an existing exact pair and require canonical JSON/Markdown bytes."""
    output = _prepare_ci_output_path(output_directory)
    if not _ci_directory_is_exact_safe_pair(output):
        raise ValueError("CI artifact directory must contain exactly the safe regular-file pair")
    summary_path = output / _CI_SUMMARY_NAME
    markdown_path = output / _CI_MARKDOWN_NAME
    try:
        raw_summary = json.loads(summary_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceIOError("read_ci_artifact", summary_path, error) from error
    if not isinstance(raw_summary, Mapping):
        raise ValueError("CI artifact JSON must contain one object")
    validated = _validate_ci_runtime_summary(raw_summary)
    expected_json = json.dumps(
        validated,
        ensure_ascii=True,
        indent=2,
        sort_keys=True,
        allow_nan=False,
    ) + "\n"
    expected_markdown = render_ci_job_summary(validated)
    try:
        actual_json = summary_path.read_text(encoding="utf-8")
        actual_markdown = markdown_path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise EvidenceIOError("read_ci_artifact", output, error) from error
    if actual_json != expected_json or actual_markdown != expected_markdown:
        raise ValueError("CI artifact pair is not the canonical validated rendering")
    return validated


def _prepare_ci_output_path(output_directory: Path) -> Path:
    requested = Path(output_directory)
    if requested.name in {"", ".", ".."} or ".." in requested.parts:
        raise ValueError("CI artifact output must name one directory")
    output = requested.absolute()
    try:
        current = Path(output.anchor)
        for component in output.parent.parts[1:]:
            current = current / component
            try:
                metadata = current.lstat()
            except FileNotFoundError:
                current.mkdir(mode=0o700)
                metadata = current.lstat()
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
                raise ValueError(f"CI artifact parent component must be a real directory: {current}")
    except ValueError:
        raise
    except OSError as error:
        raise EvidenceIOError("prepare_ci_artifact_parent", output.parent, error) from error
    return output


def _quarantine_ci_output(output: Path) -> tuple[Path | None, bool]:
    try:
        output.lstat()
    except FileNotFoundError:
        return None, True
    except OSError as error:
        raise EvidenceIOError("inspect_ci_artifact_target", output, error) from error
    existing_is_safe = _ci_directory_is_exact_safe_pair(output)
    stale_path = _unused_ci_transaction_path(output.parent, f".{output.name}.stale-")
    try:
        os.replace(output, stale_path)
    except OSError as error:
        try:
            _remove_ci_entry(output)
        except (OSError, ValueError) as cleanup_error:
            raise EvidenceIOError(
                "quarantine_ci_artifact_target",
                output,
                f"{error}; cleanup={cleanup_error}",
            ) from error
        raise EvidenceIOError("quarantine_ci_artifact_target", output, error) from error
    return stale_path, existing_is_safe


def _unused_ci_transaction_path(parent: Path, prefix: str) -> Path:
    for _ in range(16):
        candidate = parent / f"{prefix}{secrets.token_hex(12)}"
        try:
            candidate.lstat()
        except FileNotFoundError:
            return candidate
        except OSError as error:
            raise EvidenceIOError("inspect_ci_transaction_path", candidate, error) from error
    raise ValueError("cannot allocate a unique CI artifact transaction path")


def _ci_directory_is_exact_safe_pair(directory: Path) -> bool:
    try:
        metadata = directory.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            return False
        entries = list(os.scandir(directory))
    except (OSError, RuntimeError):
        return False
    if {entry.name for entry in entries} != {_CI_SUMMARY_NAME, _CI_MARKDOWN_NAME}:
        return False
    for entry in entries:
        try:
            entry_metadata = entry.stat(follow_symlinks=False)
        except OSError:
            return False
        if not stat.S_ISREG(entry_metadata.st_mode) or entry_metadata.st_nlink != 1:
            return False
    return True


def _write_ci_artifact_file(path: Path, contents: bytes) -> None:
    try:
        with path.open("xb") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
    except OSError as error:
        raise EvidenceIOError("write_ci_artifact", path, error) from error


def _remove_ci_entry(path: Path) -> None:
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return
    except OSError as error:
        raise EvidenceIOError("inspect_ci_artifact_cleanup", path, error) from error
    try:
        if stat.S_ISDIR(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode):
            shutil.rmtree(path)
        else:
            path.unlink()
    except OSError as error:
        raise EvidenceIOError("cleanup_ci_artifact", path, error) from error


def run_checked(
    command: Sequence[str],
    *,
    evidence_path: Path,
    cwd: Path | None = None,
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS,
) -> int:
    """Run one stage, always writing its evidence, and return its exit code."""
    return run_checked_result(
        command,
        evidence_path=evidence_path,
        cwd=cwd,
        timeout_seconds=timeout_seconds,
    ).exit_code


def run_checked_result(
    command: Sequence[str],
    *,
    evidence_path: Path,
    cwd: Path | None = None,
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS,
) -> CapturedStageResult:
    """Run one stage and retain only exact byte hashes in the typed controller result."""
    if not command or any(not isinstance(part, str) or not part for part in command):
        raise ValueError("command must be a non-empty sequence of non-empty strings")
    timeout = _validate_timeout(timeout_seconds)
    timed_out = False

    try:
        completed = subprocess.run(
            list(command),
            cwd=cwd,
            check=False,
            capture_output=True,
            timeout=timeout,
        )
        return_code = completed.returncode
        stdout_bytes = completed.stdout
        stderr_bytes = completed.stderr
    except subprocess.TimeoutExpired as error:
        return_code = 124
        stdout_bytes = _output_bytes(error.output)
        stderr_bytes = _output_bytes(error.stderr)
        timed_out = True
    except OSError as error:
        return_code = 127
        stdout_bytes = b""
        stderr_bytes = f"command could not be started (errno {error.errno})".encode("ascii", "replace")

    stdout = _decode_captured_output(stdout_bytes)
    stderr = _decode_captured_output(stderr_bytes)

    command_replacements, path_context = _runtime_path_redactions(
        command,
        cwd,
        Path(evidence_path),
    )
    evidence = {
        "command": _redact_command(command, command_replacements, path_context),
        "returncode": return_code,
        "status": "timed_out" if timed_out else ("passed" if return_code == 0 else "failed"),
        "stderr": _redact_text(stderr, path_context),
        "stdout": _redact_text(stdout, path_context),
        "timedOut": timed_out,
    }
    if timed_out:
        evidence["timeoutSeconds"] = timeout
    _write_evidence_atomically(Path(evidence_path), evidence)
    return CapturedStageResult(
        exit_code=return_code,
        timed_out=timed_out,
        stdout_sha256=hashlib.sha256(stdout_bytes).hexdigest(),
        stderr_sha256=hashlib.sha256(stderr_bytes).hexdigest(),
    )


def _validate_timeout(value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("timeout_seconds must be a finite number")
    timeout = float(value)
    if not math.isfinite(timeout) or not 0 < timeout <= _MAX_TIMEOUT_SECONDS:
        raise ValueError(f"timeout_seconds must be greater than zero and at most {_MAX_TIMEOUT_SECONDS:g}")
    return timeout


def _reject_non_string_mapping_keys(value: object) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if not isinstance(key, str):
                raise ValueError("snapshot mapping keys must be strings")
            _reject_non_string_mapping_keys(child)
    elif isinstance(value, (list, tuple)):
        for child in value:
            _reject_non_string_mapping_keys(child)


def _output_bytes(value: str | bytes | None) -> bytes:
    if value is None:
        return b""
    if isinstance(value, bytes):
        return value
    return value.encode("utf-8")


def _decode_captured_output(value: bytes) -> str:
    return value.decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")


def _runtime_path_redactions(
    command: Sequence[str],
    cwd: Path | None,
    evidence_path: Path,
) -> tuple[dict[int, str], _PathRedactionContext]:
    process_cwd = Path.cwd()
    requested_cwd = process_cwd if cwd is None else Path(cwd)
    lexical_cwd = _lexical_absolute_path(str(requested_cwd), str(process_cwd))
    effective_cwd = requested_cwd
    try:
        effective_cwd = effective_cwd.resolve(strict=True)
    except (OSError, RuntimeError):
        effective_cwd = effective_cwd.absolute()
    allowed_roots = [effective_cwd]
    for candidate in (effective_cwd, *effective_cwd.parents):
        if (candidate / ".git").exists():
            allowed_roots.append(candidate)
            break

    command_replacements: dict[int, str] = {}
    protected_directories = {
        normalized
        for root in allowed_roots
        for normalized in (
            _lexical_absolute_path(str(root), str(effective_cwd)),
            _resolved_path_text(root),
        )
    }
    protected_directories.add(lexical_cwd)
    protected_files: set[str] = set()
    sensitive_directories: dict[str, str] = {}
    sensitive_files: dict[str, str] = {}

    raw_evidence_path = str(evidence_path)
    requested_evidence_path = evidence_path if evidence_path.is_absolute() else process_cwd / evidence_path
    try:
        resolved_evidence_path = requested_evidence_path.resolve(strict=False)
    except (OSError, RuntimeError):
        resolved_evidence_path = requested_evidence_path.absolute()
    protected_files.update(
        {
            _lexical_absolute_path(raw_evidence_path, str(process_cwd)),
            _resolved_path_text(resolved_evidence_path),
        }
    )
    protected_directories.update(
        directory
        for directory in {
            _lexical_absolute_path(posixpath.dirname(raw_evidence_path) or ".", str(process_cwd)),
            _resolved_path_text(resolved_evidence_path.parent),
        }
        if directory != "/"
    )

    def register(index: int, raw_path: str, placeholder: str, *, inline_flag: str | None = None) -> None:
        if not raw_path or raw_path == "-" or "://" in raw_path:
            return
        candidate = Path(raw_path)
        requested = candidate if candidate.is_absolute() else effective_cwd / candidate
        try:
            resolved = requested.resolve(strict=False)
        except (OSError, RuntimeError):
            resolved = requested.absolute()
        normalized_paths = {
            _lexical_absolute_path(raw_path, str(effective_cwd)),
            _resolved_path_text(resolved),
        }
        if any(_is_within(resolved, root) for root in allowed_roots):
            protected_files.update(normalized_paths)
            return

        command_replacements[index] = f"{inline_flag}={placeholder}" if inline_flag else placeholder
        for normalized in normalized_paths:
            sensitive_files[normalized] = placeholder
            if _path_is_existing_directory(normalized):
                sensitive_directories[normalized] = placeholder

        parent = _resolved_path_text(resolved.parent)
        parent_is_overbroad = parent == "/" or any(
            _lexical_is_within(protected, parent) for protected in protected_directories
        )
        if not parent_is_overbroad:
            sensitive_directories[parent] = "[RUNTIME_TEMP_PATH]"

    index = 0
    while index < len(command):
        argument = command[index]
        if argument in _RUNTIME_PATH_OPTIONS and index + 1 < len(command):
            register(index + 1, command[index + 1], _RUNTIME_PATH_OPTIONS[argument])
            index += 2
            continue
        for option, placeholder in _RUNTIME_PATH_OPTIONS.items():
            prefix = option + "="
            if argument.startswith(prefix):
                register(index, argument[len(prefix):], placeholder, inline_flag=option)
                break
        index += 1

    context = _PathRedactionContext(
        cwd=_resolved_path_text(effective_cwd),
        protected_directories=tuple(sorted(protected_directories, key=len, reverse=True)),
        protected_files=frozenset(protected_files),
        sensitive_directories=tuple(sorted(sensitive_directories.items(), key=lambda item: len(item[0]), reverse=True)),
        sensitive_files=tuple(sorted(sensitive_files.items(), key=lambda item: len(item[0]), reverse=True)),
    )
    return command_replacements, context


def _resolved_path_text(path: Path) -> str:
    return posixpath.normpath(str(path))


def _lexical_absolute_path(path: str, cwd: str) -> str:
    candidate = path if path.startswith("/") else posixpath.join(cwd, path)
    return posixpath.normpath(candidate)


def _lexical_is_within(path: str, directory: str) -> bool:
    if directory == "/":
        return path.startswith("/")
    return path == directory or path.startswith(directory.rstrip("/") + "/")


def _path_is_existing_directory(path: str) -> bool:
    try:
        return Path(path).is_dir()
    except OSError:
        return False


def _redact_path_tokens(value: str, context: _PathRedactionContext) -> str:
    if not context.sensitive_files and not context.sensitive_directories:
        return value
    return "".join(_redact_path_tokens_in_line(line, context) for line in value.splitlines(keepends=True))


def _redact_path_tokens_in_line(line: str, context: _PathRedactionContext) -> str:
    result: list[str] = []
    cursor = 0
    while cursor < len(line):
        quote_index = min(
            (index for quote in ('"', "'") if (index := line.find(quote, cursor)) >= 0),
            default=-1,
        )
        if quote_index < 0:
            result.append(_redact_unquoted_path_fragments(line[cursor:], context))
            break
        result.append(_redact_unquoted_path_fragments(line[cursor:quote_index], context))
        quote = line[quote_index]
        quote_end = _find_line_quote_end(line, quote_index + 1, quote)
        if quote_end is None:
            result.append(quote)
            result.append(_redact_quoted_path_content(line[quote_index + 1 :], context, quote))
            break
        result.append(quote)
        result.append(_redact_quoted_path_content(line[quote_index + 1 : quote_end], context, quote))
        result.append(quote)
        cursor = quote_end + 1
    return "".join(result)


def _find_line_quote_end(line: str, start: int, quote: str) -> int | None:
    index = start
    while index < len(line):
        character = line[index]
        if character in "\r\n":
            return None
        if character == "\\" and index + 1 < len(line):
            index += 2
            continue
        if character == quote:
            return index
        index += 1
    return None


def _redact_quoted_path_content(content: str, context: _PathRedactionContext, quote: str) -> str:
    if _looks_like_standalone_path_token(content):
        replacement = _classify_path_token(content, context)
        return content if replacement is None else _encode_path_replacement(replacement, quote)
    return _redact_unquoted_path_fragments(content, context, quote)


def _redact_unquoted_path_fragments(
    value: str,
    context: _PathRedactionContext,
    quote: str | None = None,
) -> str:
    return re.sub(r"\S+", lambda match: _redact_path_chunk(match.group(0), context, quote), value)


def _redact_path_chunk(chunk: str, context: _PathRedactionContext, quote: str | None = None) -> str:
    if "://" in chunk:
        return chunk
    starts = [0] if _looks_like_path_token(chunk) else []
    for index, character in enumerate(chunk):
        if character == "/" and index > 0 and chunk[index - 1] in "=:(,[{":
            starts.append(index)
        elif chunk.startswith(("../", "./"), index) and (index == 0 or chunk[index - 1] in "=:(,[{"):
            starts.append(index)
    for start in sorted(set(starts)):
        candidate = chunk[start:]
        trailing = ""
        while candidate[-1:] in {",", ";", ")", "]", "}"}:
            trailing = candidate[-1] + trailing
            candidate = candidate[:-1]
        if not candidate:
            continue
        replacement = _classify_path_token(candidate, context)
        if replacement is not None:
            return chunk[:start] + _encode_path_replacement(replacement, quote) + trailing
    return chunk


def _looks_like_standalone_path_token(value: str) -> bool:
    return value.startswith(("/", "./", "../")) or (
        _looks_like_path_token(value) and not any(delimiter in value for delimiter in "=:(,[{")
    )


def _looks_like_path_token(value: str) -> bool:
    return bool(value) and "://" not in value and (
        value.startswith(("/", "./", "../")) or ("/" in value and not any(character.isspace() for character in value))
    )


def _encode_path_replacement(replacement: str, quote: str | None) -> str:
    if quote == '"':
        return json.dumps(replacement)[1:-1]
    if quote == "'":
        return replacement.replace("\\", "\\\\").replace("'", r"\'")
    return replacement


def _classify_path_token(token: str, context: _PathRedactionContext) -> str | None:
    for decoded in _path_token_variants(token):
        normalized = _lexical_absolute_path(decoded, context.cwd)
        if normalized in context.protected_files:
            return None
        protected_file_is_ancestor = any(
            normalized != protected and _lexical_is_within(normalized, protected)
            for protected in context.protected_files
        )
        for sensitive, placeholder in context.sensitive_files:
            if normalized == sensitive:
                return placeholder
        if not protected_file_is_ancestor and any(
            _lexical_is_within(normalized, directory) for directory in context.protected_directories
        ):
            return None
        for sensitive, placeholder in context.sensitive_directories:
            if _lexical_is_within(normalized, sensitive):
                suffix = "" if sensitive == "/" else normalized[len(sensitive) :]
                return placeholder + suffix
    return None


def _path_token_variants(token: str) -> tuple[str, ...]:
    variants = [token]
    if "\\" in token:
        try:
            decoded = json.loads(f'"{token}"')
        except json.JSONDecodeError:
            decoded = token.replace(r"\'", "'")
        if isinstance(decoded, str) and decoded not in variants:
            variants.append(decoded)
    return tuple(variants)


def _redact_command(
    command: Sequence[str],
    command_replacements: Mapping[int, str],
    path_context: _PathRedactionContext,
) -> list[str]:
    redacted: list[str] = []
    redact_next = False
    for index, argument in enumerate(command):
        if index in command_replacements:
            redacted.append(command_replacements[index])
            redact_next = False
        elif redact_next:
            redacted.append("[REDACTED]")
            redact_next = False
        else:
            assignment = _RUNTIME_ENV_NAME_PATTERN.match(argument)
            if assignment is not None and _is_sensitive_runtime_env_name(assignment.group("name")):
                redacted.append("[RUNTIME_ENV_ASSIGNMENT]")
            else:
                redacted.append(_redact_text(argument, path_context))
            redact_next = bool(_SENSITIVE_FLAG_PATTERN.fullmatch(argument))
    return redacted


def _is_sensitive_runtime_env_name(name: str) -> bool:
    normalized = name.casefold()
    return (
        normalized.startswith("pg")
        or normalized.startswith("postgres_")
        or normalized in {"database_url", "jdbc_url", "flyway_url", "flyway_user"}
        or re.search(_SENSITIVE_NAME_PATTERN, normalized, re.IGNORECASE) is not None
    )


def _redact_text(
    value: str,
    path_context: _PathRedactionContext | None = None,
) -> str:
    if path_context is not None:
        value = _redact_path_tokens(value, path_context)
    value = _redact_runtime_environment_assignments(value)
    value = _POSTGRES_CONNECTION_PATTERN.sub("[REDACTED_CONNECTION]", value)
    value = _URL_USERINFO_PATTERN.sub(r"\g<scheme>[REDACTED]@", value)
    value = _BEARER_PATTERN.sub(r"\g<prefix>[REDACTED]", value)

    def replace_assignment(match: re.Match[str]) -> str:
        secret = match.group("value")
        if secret[:1] in {"'", '"'} and secret[-1:] == secret[:1]:
            secret = f"{secret[:1]}[REDACTED]{secret[-1:]}"
        else:
            secret = "[REDACTED]"
        return match.group("prefix") + secret

    return _ASSIGNMENT_PATTERN.sub(replace_assignment, value)


def _redact_runtime_environment_assignments(value: str) -> str:
    redacted: list[str] = []
    cursor = 0
    while (assignment := _RUNTIME_ENV_ASSIGNMENT_START_PATTERN.search(value, cursor)) is not None:
        value_end = _runtime_environment_value_end(value, assignment.end())
        redacted.append(value[cursor : assignment.start()])
        if _is_sensitive_runtime_env_name(assignment.group("name")):
            redacted.append("[RUNTIME_ENV_ASSIGNMENT]")
        else:
            redacted.append(value[assignment.start() : value_end])
        cursor = max(value_end, assignment.end())
    redacted.append(value[cursor:])
    return "".join(redacted)


def _runtime_environment_value_end(value: str, start: int) -> int:
    if start >= len(value):
        return start
    if value.startswith((r'\"', r"\'"), start):
        return _escaped_quoted_runtime_value_end(value, start)
    if value[start] in {'"', "'"}:
        return _quoted_runtime_value_end(value, start, value[start])

    index = start
    while index < len(value) and value[index] not in "\r\n\t ,;\"'":
        index += 1
    return index


def _quoted_runtime_value_end(value: str, start: int, quote: str) -> int:
    index = start + 1
    while index < len(value):
        character = value[index]
        if character == quote:
            return index + 1
        if character == "\\":
            continuation_end = _runtime_continuation_end(value, index)
            if continuation_end is not None:
                if _RUNTIME_ENV_LINE_ASSIGNMENT_PATTERN.match(value, continuation_end):
                    return index + 1
                index = continuation_end
                continue
            if index + 1 >= len(value):
                return len(value)
            index += 2
            continue
        index += 1
    return len(value)


def _escaped_quoted_runtime_value_end(value: str, start: int) -> int:
    quote = value[start + 1]
    index = start + 2
    while index < len(value):
        if value.startswith("\\" + quote, index):
            return index + 2
        if value[index] == "\\":
            continuation_end = _runtime_continuation_end(value, index)
            if continuation_end is not None:
                if _RUNTIME_ENV_LINE_ASSIGNMENT_PATTERN.match(value, continuation_end):
                    return index + 1
                index = continuation_end
                continue
            if index + 1 >= len(value):
                return len(value)
            index += 2
            continue
        index += 1
    return len(value)


def _runtime_continuation_end(value: str, backslash: int) -> int | None:
    if value.startswith("\\\r\n", backslash):
        return backslash + 3
    if value.startswith("\\\n", backslash):
        return backslash + 2
    return None


def _write_evidence_atomically(output_path: Path, evidence: Mapping[str, object]) -> None:
    temporary_path: Path | None = None
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output_path.parent,
            prefix=f".{output_path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            json.dump(evidence, temporary_file, indent=2, sort_keys=True)
            temporary_file.write("\n")
        os.replace(temporary_path, output_path)
        temporary_path = None
    except OSError as error:
        raise EvidenceIOError("write_evidence", output_path, error) from error
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError as error:
                raise EvidenceIOError("cleanup_evidence_temporary", temporary_path, error) from error


def _validate_timestamp(value: str, image: str) -> None:
    try:
        _validate_utc_timestamp(value, f"toolchain lock image {image!r} resolvedAt")
    except ValueError as error:
        raise ValueError(f"toolchain lock image {image!r} has an invalid resolvedAt timestamp") from error


def _is_within(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
    except ValueError:
        return False
    return True


def _locked_image_references(lock: Mapping[str, object]) -> dict[str, str]:
    raw_images = lock.get("images")
    if not isinstance(raw_images, list):
        raise ValueError("toolchain lock images must be a list")
    references: dict[str, str] = {}
    for raw_image in raw_images:
        if not isinstance(raw_image, Mapping):
            raise ValueError("toolchain lock image must be an object")
        image = raw_image.get("image")
        digest = raw_image.get("digest")
        if isinstance(image, str) and isinstance(digest, str):
            references[image] = f"{image}@{digest}"
    missing = [image for image in _REQUIRED_LOCK_IMAGES if image not in references]
    if missing:
        raise ValueError(f"toolchain lock is missing required images: {', '.join(missing)}")
    return references


def _capture_runtime_identity(
    *,
    schema_root: Path,
    lock: Mapping[str, object],
    output_directory: Path,
    compose_command: Sequence[str],
    stage_runner: Callable[..., int],
) -> dict[str, object] | None:
    """Capture actual RepoDigests and the complete Flyway banner after live success."""
    image_references = _locked_image_references(lock)
    lock_images = lock.get("images")
    if not isinstance(lock_images, list):
        return None
    identity_directory = output_directory / "runtime-identity"
    images: list[dict[str, object]] = []
    docker_command = compose_command[0]
    for entry in lock_images:
        if not isinstance(entry, Mapping):
            return None
        image = entry.get("image")
        tag = entry.get("tag")
        digest = entry.get("digest")
        if not isinstance(image, str) or not isinstance(tag, str) or not isinstance(digest, str):
            return None
        evidence_name = "postgres-image.json" if image == "postgres" else "flyway-image.json"
        evidence_path = identity_directory / evidence_name
        return_code = stage_runner(
            [
                docker_command,
                "image",
                "inspect",
                "--format",
                "{{json .RepoDigests}}",
                image_references[image],
            ],
            evidence_path=evidence_path,
            cwd=schema_root,
            timeout_seconds=_IDENTITY_TIMEOUT_SECONDS,
        )
        actual_repo_digest = _read_actual_repo_digest(evidence_path, image, digest) if return_code == 0 else None
        if actual_repo_digest is None:
            return None
        images.append(
            {
                "actualRepoDigest": actual_repo_digest,
                "image": image,
                "lockedDigest": digest,
                "lockedTag": tag,
            }
        )

    flyway_evidence = identity_directory / "flyway-version.json"
    flyway_return_code = stage_runner(
        [
            docker_command,
            "run",
            "--rm",
            "--entrypoint",
            "flyway",
            image_references["redgate/flyway"],
            "-v",
        ],
        evidence_path=flyway_evidence,
        cwd=schema_root,
        timeout_seconds=_IDENTITY_TIMEOUT_SECONDS,
    )
    flyway_version = _read_flyway_banner(flyway_evidence) if flyway_return_code == 0 else None
    if flyway_version is None:
        return None
    return {"flywayVersion": flyway_version, "images": images}


def classify_verifier_log(evidence_path: Path) -> str:
    """Map redacted verifier-log evidence to one closed diagnostic code."""
    try:
        recorded = json.loads(Path(evidence_path).read_text(encoding="utf-8"))
    except (OSError, TypeError, UnicodeError, json.JSONDecodeError):
        return "verifier_diagnostic_unknown"
    if not isinstance(recorded, Mapping):
        return "verifier_diagnostic_unknown"
    return_code = recorded.get("returncode")
    stdout = recorded.get("stdout")
    stderr = recorded.get("stderr")
    if (
        isinstance(return_code, bool)
        or not isinstance(return_code, int)
        or not isinstance(stdout, str)
        or not isinstance(stderr, str)
    ):
        return "verifier_diagnostic_unknown"
    if return_code != 0:
        return "verifier_logs_unavailable"

    output = "\n".join((stdout, stderr))
    if output.count("ERROR:") != 1:
        return "verifier_diagnostic_unknown"
    records = list(_VERIFIER_ERROR_RECORD_PATTERN.finditer(output))
    if len(records) != 1:
        return "verifier_diagnostic_unknown"
    record = records[0]
    phase = next(
        (
            phase_name
            for phase_name, (script_name, _) in _VERIFIER_PHASE_DIAGNOSTICS.items()
            if script_name == record.group("script")
        ),
        None,
    )
    if phase is None:
        return "verifier_diagnostic_unknown"

    assertion_occurrences = output.count("assertion=")
    if assertion_occurrences == 0:
        return _VERIFIER_PHASE_DIAGNOSTICS[phase][1]
    if assertion_occurrences != 1:
        return "verifier_diagnostic_unknown"
    assertion_record = _VERIFIER_ASSERTION_MESSAGE_PATTERN.fullmatch(record.group("message"))
    if assertion_record is None:
        return "verifier_diagnostic_unknown"
    assertion = _VERIFIER_ASSERTION_DIAGNOSTICS.get(assertion_record.group("label"))
    if assertion is None:
        return _VERIFIER_PHASE_DIAGNOSTICS[phase][1]
    assertion_phase, diagnostic = assertion
    return diagnostic if assertion_phase == phase else "verifier_diagnostic_unknown"


def _recorded_stdout(evidence_path: Path) -> str | None:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    stdout = recorded.get("stdout") if isinstance(recorded, Mapping) else None
    return stdout if isinstance(stdout, str) else None


def _read_actual_repo_digest(evidence_path: Path, image: str, digest: str) -> str | None:
    stdout = _recorded_stdout(evidence_path)
    if stdout is None:
        return None
    try:
        repo_digests = json.loads(stdout)
    except json.JSONDecodeError:
        return None
    if not isinstance(repo_digests, list):
        return None
    matches: list[str] = []
    for candidate in repo_digests:
        try:
            _validate_repo_digest(candidate, image, digest)
        except ValueError:
            continue
        matches.append(candidate)
    return matches[0] if len(matches) == 1 else None


def _read_flyway_banner(evidence_path: Path) -> str | None:
    stdout = _recorded_stdout(evidence_path)
    if stdout is None:
        return None
    banners = [line.strip() for line in stdout.splitlines() if _FLYWAY_VERSION_PATTERN.fullmatch(line.strip())]
    return banners[0] if len(banners) == 1 else None


def _run_compose_attempt(
    *,
    schema_root: Path,
    runtime_directory: Path,
    compose_path: Path,
    lock: Mapping[str, object],
    run_directory: Path,
    project_name: str,
    compose_command: Sequence[str],
    stage_runner: Callable[..., int],
    verify_noop: bool,
    ci_stage_results: Mapping[str, CapturedStageResult],
    ci_stage_diagnostics: dict[str, str],
    allow_injected_stage_evidence_fallback: bool,
) -> dict[str, object]:
    run_directory.mkdir(parents=True, exist_ok=True)
    cleanup_return_code = 125
    postgres_return_code = 125
    flyway = _service_result("flyway")
    verifier = _service_result("verifier")
    noop_verifier: dict[str, object] | None = None
    noop_migrate_return_code: int | None = None
    initial_fingerprint: str | None = None
    noop_fingerprint: str | None = None
    checksum_scenario: dict[str, object] | None = None
    with tempfile.TemporaryDirectory(prefix="schema-runtime-") as temporary_directory:
        temporary_directory_path = Path(temporary_directory)
        override_path = _write_temporary_file(
            temporary_directory_path,
            "compose-",
            render_compose_override(lock),
        )
        environment_path = _write_temporary_file(
            temporary_directory_path,
            "environment-",
            render_compose_environment(lock)
            + "RUNTIME_POSTGRES_PASSWORD=" + secrets.token_urlsafe(32) + "\n"
            + "RUNTIME_MIGRATOR_PASSWORD=" + secrets.token_urlsafe(32) + "\n",
        )
        command_prefix = [
            *compose_command,
            "--env-file",
            str(environment_path),
            "-p",
            project_name,
            "-f",
            str(compose_path),
            "-f",
            str(override_path),
        ]
        try:
            postgres_return_code = stage_runner(
                [*command_prefix, "up", "-d", "--wait", "postgres"],
                evidence_path=run_directory / "postgres-start.json",
                cwd=schema_root,
                timeout_seconds=_POSTGRES_START_TIMEOUT_SECONDS,
            )
            if postgres_return_code == 0:
                flyway["startReturnCode"] = stage_runner(
                    [*command_prefix, "up", "-d", "flyway"],
                    evidence_path=run_directory / "flyway-start.json",
                    cwd=schema_root,
                    timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
                )
                if flyway["startReturnCode"] == 0:
                    _wait_for_service_exit(
                        command_prefix,
                        "flyway",
                        run_directory,
                        schema_root,
                        _FLYWAY_TIMEOUT_SECONDS,
                        flyway,
                        stage_runner,
                    )
                    if _service_succeeded(flyway):
                        verifier["startReturnCode"] = stage_runner(
                            [*command_prefix, "up", "-d", "--no-deps", "verifier"],
                            evidence_path=run_directory / "verifier-start.json",
                            cwd=schema_root,
                            timeout_seconds=_VERIFIER_TIMEOUT_SECONDS,
                        )
                        if verifier["startReturnCode"] == 0:
                            _wait_for_service_exit(
                                command_prefix,
                                "verifier",
                                run_directory,
                                schema_root,
                                _VERIFIER_TIMEOUT_SECONDS,
                                verifier,
                                stage_runner,
                            )
                            verifier_diagnostic = _capture_verifier_summary(
                                command_prefix,
                                run_directory / "verifier-logs.json",
                                schema_root,
                                verifier,
                                stage_runner,
                            )
                            _bind_verifier_wait_diagnostic(
                                ci_stage_diagnostics,
                                f"{run_directory.name}/verifier-wait.json",
                                verifier,
                                verifier_diagnostic,
                                ci_stage_results=ci_stage_results,
                                evidence_path=run_directory / "verifier-wait.json",
                                allow_evidence_fallback=allow_injected_stage_evidence_fallback,
                            )
                            if _service_succeeded(verifier):
                                initial_fingerprint = _summary_fingerprint(verifier)
                            if verify_noop and _verified_service_succeeded(verifier):
                                noop_migrate_return_code = stage_runner(
                                    [
                                        *command_prefix,
                                        "run", "--rm", "--no-deps", "--entrypoint", "flyway", "flyway",
                                        *_FLYWAY_OPTIONS, "migrate",
                                    ],
                                    evidence_path=run_directory / "noop-migrate.json",
                                    cwd=schema_root,
                                    timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
                                )
                                if noop_migrate_return_code == 0:
                                    noop_verifier = _service_result("verifier")
                                    noop_verifier["startReturnCode"] = stage_runner(
                                        [
                                            *command_prefix, "up", "-d", "--no-deps",
                                            "--force-recreate", "verifier",
                                        ],
                                        evidence_path=run_directory / "noop-verifier-start.json",
                                        cwd=schema_root,
                                        timeout_seconds=_VERIFIER_TIMEOUT_SECONDS,
                                    )
                                    if noop_verifier["startReturnCode"] == 0:
                                        _wait_for_service_exit(
                                            command_prefix,
                                            "verifier",
                                            run_directory / "noop",
                                            schema_root,
                                            _VERIFIER_TIMEOUT_SECONDS,
                                            noop_verifier,
                                            stage_runner,
                                        )
                                        noop_diagnostic = _capture_verifier_summary(
                                            command_prefix,
                                            run_directory / "noop-verifier-logs.json",
                                            schema_root,
                                            noop_verifier,
                                            stage_runner,
                                        )
                                        _bind_verifier_wait_diagnostic(
                                            ci_stage_diagnostics,
                                            f"{run_directory.name}/noop/verifier-wait.json",
                                            noop_verifier,
                                            noop_diagnostic,
                                            ci_stage_results=ci_stage_results,
                                            evidence_path=run_directory / "noop" / "verifier-wait.json",
                                            allow_evidence_fallback=allow_injected_stage_evidence_fallback,
                                        )
                                        if _service_succeeded(noop_verifier):
                                            noop_fingerprint = _summary_fingerprint(noop_verifier)
                                    if _verified_service_succeeded(noop_verifier) and initial_fingerprint == noop_fingerprint:
                                        checksum_scenario = _run_checksum_scenario(
                                            schema_root=schema_root,
                                            temporary_directory=temporary_directory_path,
                                            command_prefix=command_prefix,
                                            run_directory=run_directory,
                                            stage_runner=stage_runner,
                                        )
            up_return_code = _attempt_return_code(postgres_return_code, flyway, verifier)
            if up_return_code == 0 and not _verified_service_succeeded(verifier):
                up_return_code = 125
            if up_return_code == 0 and verify_noop:
                if noop_migrate_return_code != 0:
                    up_return_code = int(noop_migrate_return_code or 125)
                elif not _verified_service_succeeded(noop_verifier):
                    up_return_code = 125
                elif initial_fingerprint != noop_fingerprint:
                    up_return_code = 126
        finally:
            try:
                cleanup_return_code = stage_runner(
                [*command_prefix, "down", "--volumes", "--remove-orphans"],
                evidence_path=run_directory / "compose-down.json",
                cwd=schema_root,
                timeout_seconds=_CLEANUP_TIMEOUT_SECONDS,
            )
            finally:
                _unlink_runtime_temporary(override_path)
                _unlink_runtime_temporary(environment_path)

    result: dict[str, object] = {
        "cleanupReturnCode": cleanup_return_code,
        "flyway": flyway,
        "id": run_directory.name,
        "initialFingerprint": initial_fingerprint,
        "noopFingerprint": noop_fingerprint,
        "noopMigrateReturnCode": noop_migrate_return_code,
        "noopVerifier": noop_verifier,
        "project": project_name,
        "postgresReturnCode": postgres_return_code,
        "upReturnCode": up_return_code,
        "verifier": verifier,
    }
    if checksum_scenario is not None:
        result["checksumScenario"] = checksum_scenario
    return result


def _capture_verifier_summary(
    command_prefix: Sequence[str],
    evidence_path: Path,
    schema_root: Path,
    result: dict[str, object],
    stage_runner: Callable[..., int],
) -> str:
    result["logsReturnCode"] = stage_runner(
        [*command_prefix, "logs", "--no-color", "verifier"],
        evidence_path=evidence_path,
        cwd=schema_root,
        timeout_seconds=_VERIFIER_TIMEOUT_SECONDS,
    )
    result["summary"] = (
        _read_single_json_summary(evidence_path)
        if result["logsReturnCode"] == 0 and _service_succeeded(result)
        else None
    )
    return classify_verifier_log(evidence_path)


def _bind_verifier_wait_diagnostic(
    diagnostics: dict[str, str],
    wait_stage_path: str,
    result: Mapping[str, object],
    diagnostic: str,
    *,
    ci_stage_results: Mapping[str, CapturedStageResult],
    evidence_path: Path,
    allow_evidence_fallback: bool,
) -> None:
    wait_return_code = result.get("waitReturnCode")
    wait_metadata = _verifier_wait_metadata(
        ci_stage_results.get(wait_stage_path),
        evidence_path=evidence_path,
        expected_return_code=wait_return_code,
        allow_evidence_fallback=allow_evidence_fallback,
    )
    if wait_metadata is None:
        return
    captured_return_code, timed_out = wait_metadata
    if not timed_out and captured_return_code not in {0, 127}:
        diagnostics[wait_stage_path] = diagnostic


def _verifier_wait_metadata(
    captured: CapturedStageResult | None,
    *,
    evidence_path: Path,
    expected_return_code: object,
    allow_evidence_fallback: bool,
) -> tuple[int, bool] | None:
    if isinstance(expected_return_code, bool) or not isinstance(expected_return_code, int):
        return None
    if captured is not None:
        if (
            not isinstance(captured, CapturedStageResult)
            or isinstance(captured.exit_code, bool)
            or not isinstance(captured.exit_code, int)
            or not isinstance(captured.timed_out, bool)
            or captured.exit_code != expected_return_code
            or (captured.timed_out and captured.exit_code != 124)
        ):
            return None
        return captured.exit_code, captured.timed_out
    if not allow_evidence_fallback:
        return None
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None
    if not isinstance(recorded, Mapping):
        return None
    return_code = recorded.get("returncode")
    timed_out = recorded.get("timedOut")
    status = recorded.get("status")
    if (
        isinstance(return_code, bool)
        or not isinstance(return_code, int)
        or not isinstance(timed_out, bool)
        or return_code != expected_return_code
        or (timed_out and (return_code != 124 or status != "timed_out"))
        or (not timed_out and status != ("passed" if return_code == 0 else "failed"))
    ):
        return None
    return return_code, timed_out


def _read_single_json_summary(evidence_path: Path) -> dict[str, object] | None:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
        output = recorded["stdout"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return None
    if not isinstance(output, str):
        return None
    summaries: list[dict[str, object]] = []
    for line in output.splitlines():
        object_start = line.find("{")
        if object_start < 0:
            continue
        try:
            candidate = json.loads(line[object_start:])
        except json.JSONDecodeError:
            continue
        if isinstance(candidate, dict):
            summaries.append(candidate)
    if len(summaries) != 1:
        return None
    summary = summaries[0]
    if summary.get("status") != "PASSED" or not isinstance(summary.get("fingerprint"), str):
        return None
    return summary


def _summary_fingerprint(result: Mapping[str, object]) -> str | None:
    summary = result.get("summary")
    if not isinstance(summary, Mapping):
        return None
    fingerprint = summary.get("fingerprint")
    return (
        fingerprint
        if isinstance(fingerprint, str) and _FINGERPRINT_PATTERN.fullmatch(fingerprint)
        else None
    )


def _runtime_attempt_fingerprints_match(attempts: Sequence[Mapping[str, object]]) -> bool:
    """Require every positive attempt to bind one valid lowercase fingerprint."""
    if len(attempts) < 2:
        return False
    fingerprints: list[str] = []
    for attempt in attempts:
        fingerprint = attempt.get("initialFingerprint")
        verifier = attempt.get("verifier")
        summary = verifier.get("summary") if isinstance(verifier, Mapping) else None
        if (
            not isinstance(fingerprint, str)
            or not _FINGERPRINT_PATTERN.fullmatch(fingerprint)
            or not isinstance(summary, Mapping)
            or summary.get("fingerprint") != fingerprint
        ):
            return False
        fingerprints.append(fingerprint)
    return len(set(fingerprints)) == 1


def _verified_service_succeeded(result: Mapping[str, object] | None) -> bool:
    return (
        result is not None
        and _service_succeeded(result)
        and result.get("logsReturnCode") == 0
        and _summary_fingerprint(result) is not None
    )


def _run_checksum_scenario(
    *,
    schema_root: Path,
    temporary_directory: Path,
    command_prefix: Sequence[str],
    run_directory: Path,
    stage_runner: Callable[..., int],
) -> dict[str, object]:
    scenario_directory = run_directory / "failure-checksum"
    migration_copy = temporary_directory / "checksum-migrations"
    shutil.copytree(schema_root / "generated" / "db" / "migration", migration_copy)
    with (migration_copy / "V010__identity_tables.sql").open("a", encoding="utf-8") as migration_file:
        migration_file.write("\n-- runtime checksum mismatch probe\n")
    override_path = _write_temporary_file(
        temporary_directory,
        "checksum-compose-",
        json.dumps(
            {"services": {"flyway": {"volumes": [f"{migration_copy}:/flyway/sql:ro"]}}},
            indent=2,
            sort_keys=True,
        ) + "\n",
    )
    evidence_path = scenario_directory / "strict-validate.json"
    return_code = stage_runner(
        [
            *command_prefix, "-f", str(override_path),
            "run", "--rm", "--no-deps", "--entrypoint", "flyway", "flyway",
            *_FLYWAY_OPTIONS, "-ignoreMigrationPatterns=", "validate",
        ],
        evidence_path=evidence_path,
        cwd=schema_root,
        timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
    )
    message_matched = return_code != 0 and _stage_contains(evidence_path, "checksum mismatch")
    actual_phase = "strict-validate" if return_code != 0 else "unexpected-success"
    return {
        "actualPhase": actual_phase,
        "actualMessage": "checksum mismatch" if message_matched else "",
        "actualResult": "failure" if return_code != 0 else "success",
        "expectedMessage": "checksum mismatch",
        "expectedPhase": "strict-validate",
        "expectedResult": "failure",
        "expectedReturnCode": "nonzero",
        "messageMatched": message_matched,
        "name": "checksum-mismatch",
        "returnCode": return_code,
        "status": "PASSED" if actual_phase == "strict-validate" and message_matched else "FAILED",
    }


def _run_failure_scenarios(
    *,
    schema_root: Path,
    runtime_directory: Path,
    compose_path: Path,
    lock: Mapping[str, object],
    output_directory: Path,
    compose_command: Sequence[str],
    stage_runner: Callable[..., int],
) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    for name, mutation_file, expected_message, expected_phase in _FAILURE_SCENARIOS:
        scenario_directory = output_directory / "failure-scenarios" / name
        project_name = f"schema-runtime-{secrets.token_hex(6)}"
        cleanup_return_code = 125
        with tempfile.TemporaryDirectory(prefix="schema-runtime-failure-") as temporary_directory:
            temporary_directory_path = Path(temporary_directory)
            override_path = _write_temporary_file(
                temporary_directory_path, "compose-", render_compose_override(lock)
            )
            environment_path = _write_temporary_file(
                temporary_directory_path,
                "environment-",
                render_compose_environment(lock)
                + "RUNTIME_POSTGRES_PASSWORD=" + secrets.token_urlsafe(32) + "\n"
                + "RUNTIME_MIGRATOR_PASSWORD=" + secrets.token_urlsafe(32) + "\n",
            )
            command_prefix = [
                *compose_command,
                "--env-file", str(environment_path),
                "-p", project_name,
                "-f", str(compose_path),
                "-f", str(override_path),
            ]
            actual_phase = "postgres-start"
            postgres_return_code: int | None = None
            baseline_return_code: int | None = None
            mutation_return_code: int | None = None
            expected_failure_return_code: int | None = None
            expected_failure_evidence = scenario_directory / "expected-failure.json"
            message_matched = False
            try:
                postgres_return_code = stage_runner(
                    [*command_prefix, "up", "-d", "--wait", "postgres"],
                    evidence_path=scenario_directory / "postgres-start.json",
                    cwd=schema_root,
                    timeout_seconds=_POSTGRES_START_TIMEOUT_SECONDS,
                )
                if postgres_return_code == 0 and name == "missing-role":
                    expected_failure_return_code = stage_runner(
                        _flyway_run_command(command_prefix, target="830", query_role="law_missing_role"),
                        evidence_path=expected_failure_evidence,
                        cwd=schema_root,
                        timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
                    )
                    actual_phase = expected_phase if expected_failure_return_code != 0 else "unexpected-success"
                elif postgres_return_code == 0:
                    baseline_return_code = stage_runner(
                        _flyway_run_command(command_prefix, target="830"),
                        evidence_path=scenario_directory / "V830-migrate.json",
                        cwd=schema_root,
                        timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
                    )
                    if baseline_return_code != 0:
                        actual_phase = "V830-migrate"
                    else:
                        mutation_return_code = stage_runner(
                            [
                                *command_prefix, "exec", "-T", "postgres",
                                "psql", "-X", "-v", "ON_ERROR_STOP=1",
                                "-U", "postgres", "-d", "law_contract_runtime",
                                "-f", f"/runtime/sql/failures/{mutation_file}",
                            ],
                            evidence_path=scenario_directory / "mutation.json",
                            cwd=schema_root,
                            timeout_seconds=_VERIFIER_TIMEOUT_SECONDS,
                        )
                        if mutation_return_code != 0:
                            actual_phase = "mutation"
                        else:
                            expected_failure_return_code = stage_runner(
                                _flyway_run_command(command_prefix, target="840"),
                                evidence_path=expected_failure_evidence,
                                cwd=schema_root,
                                timeout_seconds=_FLYWAY_TIMEOUT_SECONDS,
                            )
                            actual_phase = expected_phase if expected_failure_return_code != 0 else "unexpected-success"
                if expected_failure_return_code is not None and expected_failure_return_code != 0:
                    message_matched = _stage_contains(expected_failure_evidence, expected_message)
            finally:
                cleanup_return_code = stage_runner(
                    [*command_prefix, "down", "--volumes", "--remove-orphans"],
                    evidence_path=scenario_directory / "compose-down.json",
                    cwd=schema_root,
                    timeout_seconds=_CLEANUP_TIMEOUT_SECONDS,
                )
                _unlink_runtime_temporary(override_path)
                _unlink_runtime_temporary(environment_path)

        passed = actual_phase == expected_phase and message_matched and cleanup_return_code == 0
        results.append(
            {
                "actualPhase": actual_phase,
                "actualMessage": expected_message if message_matched else "",
                "actualResult": "failure" if expected_failure_return_code not in (None, 0) else "success",
                "baselineReturnCode": baseline_return_code,
                "cleanupReturnCode": cleanup_return_code,
                "expectedMessage": expected_message,
                "expectedPhase": expected_phase,
                "expectedResult": "failure",
                "expectedReturnCode": "nonzero",
                "messageMatched": message_matched,
                "mutationReturnCode": mutation_return_code,
                "name": name,
                "postgresReturnCode": postgres_return_code,
                "project": project_name,
                "returnCode": expected_failure_return_code,
                "status": "PASSED" if passed else "FAILED",
            }
        )
    return results


def _flyway_run_command(
    command_prefix: Sequence[str],
    *,
    target: str,
    query_role: str = "law_app_query",
) -> list[str]:
    options = [
        option if not option.startswith("-placeholders.app_query_role=")
        else f"-placeholders.app_query_role={query_role}"
        for option in _FLYWAY_OPTIONS
    ]
    return [
        *command_prefix,
        "run", "--rm", "--no-deps", "--entrypoint", "flyway", "flyway",
        *options, f"-target={target}", "migrate",
    ]


def _stage_contains(evidence_path: Path, expected_message: str) -> bool:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    output = "\n".join(str(recorded.get(field, "")) for field in ("stdout", "stderr"))
    return expected_message.casefold() in output.casefold()


def _service_result(service: str) -> dict[str, object]:
    return {
        "exitCode": None,
        "logsReturnCode": None,
        "service": service,
        "startReturnCode": None,
        "statusReturnCode": None,
        "summary": None,
        "waitReturnCode": None,
    }


def _wait_for_service_exit(
    command_prefix: Sequence[str],
    service: str,
    run_directory: Path,
    schema_root: Path,
    timeout_seconds: float,
    result: dict[str, object],
    stage_runner: Callable[..., int],
) -> None:
    result["waitReturnCode"] = stage_runner(
        [*command_prefix, "wait", service],
        evidence_path=run_directory / f"{service}-wait.json",
        cwd=schema_root,
        timeout_seconds=timeout_seconds,
    )
    result["statusReturnCode"] = stage_runner(
        [*command_prefix, "ps", "--all", "--format", "json", service],
        evidence_path=run_directory / f"{service}-status.json",
        cwd=schema_root,
        timeout_seconds=timeout_seconds,
    )
    if result["statusReturnCode"] == 0:
        result["exitCode"] = _service_exit_code(run_directory / f"{service}-status.json", service)


def _service_exit_code(evidence_path: Path, service: str) -> int | None:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
        output = recorded["stdout"]
        services = json.loads(output)
    except (OSError, KeyError, TypeError, json.JSONDecodeError):
        return None
    if isinstance(services, Mapping):
        services = [services]
    if not isinstance(services, list):
        return None
    matching = [item for item in services if isinstance(item, Mapping) and item.get("Service") == service]
    if len(matching) != 1:
        return None
    exit_code = matching[0].get("ExitCode")
    if isinstance(exit_code, int) and not isinstance(exit_code, bool):
        return exit_code
    if isinstance(exit_code, str) and exit_code.isdecimal():
        return int(exit_code)
    return None


def _service_succeeded(result: Mapping[str, object]) -> bool:
    return result["waitReturnCode"] == 0 and result["statusReturnCode"] == 0 and result["exitCode"] == 0


def _attempt_return_code(postgres_return_code: int, *services: Mapping[str, object]) -> int:
    if postgres_return_code != 0:
        return postgres_return_code
    for service in services:
        for field in ("startReturnCode", "waitReturnCode", "statusReturnCode", "exitCode"):
            code = service[field]
            if code is None:
                return 125
            if code != 0:
                return int(code)
    return 0


def _write_temporary_file(directory: Path, prefix: str, contents: str) -> Path:
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=directory,
            prefix=prefix,
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_file.write(contents)
            return Path(temporary_file.name)
    except OSError as error:
        raise EvidenceIOError("write_runtime_temporary", directory, error) from error


def _unlink_runtime_temporary(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError as error:
        raise EvidenceIOError("cleanup_runtime_temporary", path, error) from error


def _repository_root() -> Path:
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot find repository root")


def _parse_arguments(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate PostgreSQL runtime verification inputs")
    subcommands = parser.add_subparsers(dest="command", required=True)
    verify = subcommands.add_parser("verify", help="preflight runtime verification inputs")
    verify.add_argument("--runs", type=int, required=True)
    verify.add_argument("--evidence-dir", required=True, type=Path)
    verify.add_argument(
        "--ci-only",
        action="store_true",
        help="publish only the closed runner-local CI artifact",
    )
    fallback = subcommands.add_parser(
        "export-ci-fallback",
        help="write a minimal closed CI artifact after an early workflow failure",
    )
    fallback.add_argument(
        "--workflow-step-outcome",
        required=True,
        choices=tuple(_CI_FALLBACK_OUTCOMES),
    )
    subcommands.add_parser(
        "validate-ci-artifact",
        help="revalidate the fixed structured CI artifact pair",
    )
    arguments = parser.parse_args(argv)
    if arguments.command == "verify" and arguments.runs < 2:
        parser.error("--runs must be at least 2")
    return arguments


def _fixed_ci_artifact_directory(repository: Path) -> Path:
    return repository / ".artifacts" / "schema-runtime-ci"


def _remove_failed_ci_artifact(repository_root: Path) -> None:
    """Remove only the fixed CI upload entry without following an unsafe artifacts parent."""
    try:
        repository = _validated_repository_root(repository_root)
        artifacts_parent = repository / ".artifacts"
        metadata = artifacts_parent.lstat()
    except (OSError, RuntimeError, ValueError):
        return
    try:
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            artifacts_parent.unlink()
        else:
            _remove_ci_entry(artifacts_parent / "schema-runtime-ci")
    except (OSError, ValueError):
        return


def _fail_ci_only(
    repository_root: Path | None,
    message: str,
    *,
    status: object = None,
) -> int:
    """Remove the upload pair and emit one fixed diagnostic for a CI-only failure."""
    if repository_root is not None:
        try:
            _remove_failed_ci_artifact(repository_root)
        except Exception:
            pass
    print(message, file=sys.stderr)
    return 5 if status == "BLOCKED" else 4


def _run_ci_fallback_command(
    repository: Path,
    schema: Path,
    workflow_step_outcome: str,
) -> int:
    output_directory = _fixed_ci_artifact_directory(repository)
    try:
        snapshot = capture_repository_snapshot(repository)
        lock = load_toolchain_lock(schema / "runtime" / "toolchain.lock.json")
        summary = build_ci_runtime_fallback(
            git_commit=snapshot.head,
            workflow_step_outcome=workflow_step_outcome,
            lock=lock,
        )
        export_ci_runtime_artifact(summary, output_directory)
    except Exception:
        _remove_failed_ci_artifact(repository)
        print("structured runtime CI fallback export failed", file=sys.stderr)
        return 4
    print(
        json.dumps(
            {
                "reasonCode": summary["reasonCode"],
                "workflowOutcome": summary["workflowOutcome"],
            },
            sort_keys=True,
        )
    )
    return 4 if workflow_step_outcome == "success" else 0


def _run_ci_validation_command(repository: Path) -> int:
    output_directory = _fixed_ci_artifact_directory(repository)
    try:
        snapshot = capture_repository_snapshot(repository)
        summary = validate_ci_runtime_artifact(output_directory)
        if summary["gitCommit"] != snapshot.head:
            raise ValueError("CI artifact commit does not match the clean checkout")
    except Exception:
        _remove_failed_ci_artifact(repository)
        print("structured runtime CI artifact validation failed", file=sys.stderr)
        return 4
    print("structured runtime CI artifact: PASS")
    return 0


def main(
    argv: Sequence[str] | None = None,
    *,
    repository_root: Path | None = None,
    schema_root: Path | None = None,
    runtime_runner: Callable[..., dict[str, object]] | None = None,
) -> int:
    arguments = _parse_arguments(argv)
    try:
        repository = Path(repository_root) if repository_root is not None else _repository_root()
        schema = Path(schema_root) if schema_root is not None else Path(__file__).resolve().parents[1]
    except Exception:
        if arguments.command == "verify" and arguments.ci_only:
            return _fail_ci_only(None, "runtime verifier input error")
        raise
    if arguments.command == "export-ci-fallback":
        return _run_ci_fallback_command(repository, schema, arguments.workflow_step_outcome)
    if arguments.command == "validate-ci-artifact":
        return _run_ci_validation_command(repository)

    runner = runtime_runner if runtime_runner is not None else run_runtime_verification
    try:
        ci_output_directory = _fixed_ci_artifact_directory(repository)
        _remove_ci_entry(_prepare_ci_output_path(ci_output_directory))
        before_snapshot = capture_repository_snapshot(repository)
        output_directory = evidence_dir(
            repository,
            arguments.evidence_dir,
            current_directory=Path.cwd(),
        )
        result = runner(schema, output_directory, runs=arguments.runs)
        if not isinstance(result, Mapping):
            raise ValueError("runtime runner must return one result object")
        after_snapshot = capture_repository_snapshot(repository)
        if after_snapshot.head != before_snapshot.head:
            raise ValueError("repository HEAD changed while runtime verification was running")
    except Exception as error:
        if arguments.ci_only:
            return _fail_ci_only(repository, "runtime verifier input error")
        if not isinstance(error, ValueError):
            raise
        print(f"runtime verifier input error: {error}", file=sys.stderr)
        return 4

    try:
        status = result.get("status")
        status_is_known = type(status) is str and status in {"PASSED", "FAILED", "BLOCKED"}
    except Exception:
        if arguments.ci_only:
            return _fail_ci_only(repository, "runtime verifier result error")
        raise
    if not status_is_known:
        if arguments.ci_only:
            return _fail_ci_only(
                repository,
                "runtime verifier result error: unknown status",
            )
        print("runtime verifier result error: unknown status", file=sys.stderr)
        return 4
    ci_summary: dict[str, object] | None = None
    if isinstance(result, RuntimeVerificationResult):
        try:
            manifest_path = schema / "generated" / "schema-contract-manifest.json"
            manifest = _decode_json_object(
                _read_publication_inputs(repository, (manifest_path,))[0],
                manifest_path,
                "read_contract_manifest",
            )
            ci_summary = build_ci_runtime_summary(
                result,
                git_commit=before_snapshot.head,
                manifest=manifest,
            )
            export_ci_runtime_artifact(ci_summary, ci_output_directory)
        except Exception as error:
            if arguments.ci_only:
                return _fail_ci_only(
                    repository,
                    "structured runtime CI artifact export failed",
                    status=status,
                )
            if not isinstance(error, ValueError):
                raise
            ci_summary = None
            _remove_failed_ci_artifact(repository)
            print("structured runtime CI artifact export failed", file=sys.stderr)
            if status == "PASSED":
                return 4
    elif arguments.ci_only:
        return _fail_ci_only(
            repository,
            "structured runtime CI artifact export failed",
            status=status,
        )
    if arguments.ci_only and status == "PASSED":
        try:
            summary = validate_ci_runtime_artifact(ci_output_directory)
            if summary["gitCommit"] != before_snapshot.head:
                raise ValueError("CI artifact commit does not match the verified checkout")
        except Exception:
            return _fail_ci_only(
                repository,
                "structured runtime CI artifact validation failed",
            )
        ci_summary = summary
    if arguments.ci_only:
        try:
            if ci_summary is not None:
                print(
                    json.dumps(
                        {
                            "reasonCode": ci_summary["reasonCode"],
                            "workflowOutcome": ci_summary["workflowOutcome"],
                        },
                        sort_keys=True,
                    )
                )
        except Exception:
            return _fail_ci_only(
                repository,
                "structured runtime CI artifact console rendering failed",
                status=status,
            )
        return {"PASSED": 0, "FAILED": 4, "BLOCKED": 5}[status]
    if status == "PASSED" and not arguments.ci_only:
        try:
            if result.get("schemaVersion") != "postgresql-runtime-evidence-v1":
                lock = load_toolchain_lock(schema / "runtime" / "toolchain.lock.json")
                manifest_path = schema / "generated" / "schema-contract-manifest.json"
                manifest = _decode_json_object(
                    _read_publication_inputs(repository, (manifest_path,))[0],
                    manifest_path,
                    "read_contract_manifest",
                )
                result = normalize_runtime_result_for_publication(
                    result,
                    lock,
                    manifest,
                    git_commit=before_snapshot.head,
                    verified_at_utc=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                )
                _write_evidence_atomically(output_directory / "runtime-summary.json", result)
            prepared = prepare_publishable_evidence(
                repository,
                output_directory / "runtime-summary.json",
                schema / "runtime" / "toolchain.lock.json",
                schema / "generated" / "schema-contract-manifest.json",
                expected_head=before_snapshot.head,
            )
            targets = publish_prepared_evidence(prepared)
        except ValueError as error:
            print(f"runtime evidence publication error: {error}", file=sys.stderr)
            return 4
        result = {**result, "publishedEvidence": [str(target) for target in targets]}

    print(json.dumps({"evidenceDir": str(output_directory), **result}, sort_keys=True))
    return {"PASSED": 0, "FAILED": 4, "BLOCKED": 5}[status]


if __name__ == "__main__":
    raise SystemExit(main())
