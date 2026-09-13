"""Consume one prepared R1 environment with the existing offline identity bootstrap command.

This runner owns only the two synthetic identity bootstrap operations.  It does
not start applications, issue user tokens, or create identity facts with SQL.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import secrets
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable


RUNTIME_DIRECTORY = Path(__file__).resolve().parent
if str(RUNTIME_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(RUNTIME_DIRECTORY))
import r1_environment as environment


ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP_DIRECTORY = "identity-bootstrap"
LOGGING_CONFIG_SOURCE = "e2e/runtime/r1-bootstrap-logback.xml"
JAVA_MAIN = "io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand"
JAVA_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher"
SEMANTIC_BASELINE = "MVP-2026-09-08.3"
SCHEMA_VERSION = "52-plus-2-v1.2"
OPERATOR_ASSERTION = "Controlled R1 isolated synthetic identity bootstrap"
EXECUTION_NODE = "R1_E2E_BOOTSTRAP"
BOOTSTRAP_KEY_ID = "r1-e2e-bootstrap-v1"
EXPECTED_DELTA = {
    "tenant": 1,
    "organization_unit": 1,
    "principal": 1,
    "appointment": 1,
    "authority_grant": 4,
    "command_execution_slot": 1,
    "command_receipt": 1,
    "audit_entry": 1,
}
MANAGEMENT_CODES = (
    "IDENTITY_PRINCIPAL_MANAGE",
    "IDENTITY_ORGANIZATION_MANAGE",
    "IDENTITY_APPOINTMENT_MANAGE",
    "IDENTITY_AUTHORITY_MANAGE",
)
TENANTS = (
    ("main", "R1_E2E_MAIN", "R1 synthetic firm", "R1 synthetic firm root"),
    ("isolation", "R1_E2E_ISOLATION", "R1 isolation sentinel", "R1 isolation sentinel root"),
)
CommandRunner = Callable[..., subprocess.CompletedProcess]


class ProcessFailure(RuntimeError):
    def __init__(self, label: str, exit_code: int | None, message: str):
        super().__init__(message)
        self.label = label
        self.exit_code = exit_code


def _read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8", errors="strict"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{label} is unavailable or invalid") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} is invalid")
    return value


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _sha256(path: Path) -> str:
    return environment.sha256_file(path)


def _invoke(
    command: list[str], root: Path, label: str, command_runner: CommandRunner,
    *, output_directory: Path | None = None, input_bytes: bytes | None = None,
) -> tuple[int, str, str]:
    try:
        completed = command_runner(
            command, cwd=root, capture_output=True, text=False, input=input_bytes,
        )
    except OSError as error:
        raise ProcessFailure(label, None, f"{label} could not execute") from error
    stdout, stderr = completed.stdout, completed.stderr
    if output_directory is not None:
        try:
            environment._write(output_directory / f"{label}.stdout", stdout if isinstance(stdout, bytes) else b"")
            environment._write(output_directory / f"{label}.stderr", stderr if isinstance(stderr, bytes) else b"")
        except (OSError, RuntimeError) as error:
            raise ProcessFailure(
                label, completed.returncode, f"{label} protected output persistence failed",
            ) from error
    if not isinstance(stdout, bytes) or not isinstance(stderr, bytes):
        raise ProcessFailure(label, completed.returncode, f"{label} capture missing or not bytes")
    try:
        decoded_stdout = stdout.decode("utf-8", errors="strict")
        decoded_stderr = stderr.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProcessFailure(label, completed.returncode, f"{label} output is not valid UTF-8") from error
    return (
        completed.returncode,
        decoded_stdout.replace("\r\n", "\n").replace("\r", "\n"),
        decoded_stderr.replace("\r\n", "\n").replace("\r", "\n"),
    )


def _parse_object(output: str, label: str) -> dict:
    try:
        value = json.loads(output)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{label} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} returned invalid JSON")
    return value


def _compose_prefix(root: Path, runtime: Path, manifest: dict) -> list[str]:
    return [
        "docker", "compose", "--env-file", str(runtime / "compose.env"),
        "-f", str(root / "e2e/compose.yaml"), "-p", manifest["composeProject"],
    ]


def _psql_command(root: Path, runtime: Path, manifest: dict) -> list[str]:
    return _compose_prefix(root, runtime, manifest) + [
        "exec", "-T", "business-db", "sh", "-eu", "-c",
        'export PGPASSWORD="$(tr -d "\\r\\n" </run/r1-secrets/api-db-password)"; '
        "exec psql -X -A -t -q -v ON_ERROR_STOP=1 -U law_api_login -d law_contract_runtime",
    ]


def _deployment_query() -> bytes:
    return (
        "BEGIN READ ONLY;\n"
        "SET LOCAL ROLE law_app_query;\n"
        "SELECT jsonb_build_object("
        "'operatingMode',operating_mode,'schemaVersion',schema_contract_version,"
        "'releaseDigest',encode(active_release_digest,'hex'),"
        "'manifestHash',encode(active_manifest_hash,'hex'))::text "
        "FROM platform_meta.deployment_state WHERE deployment_state_key='PRIMARY';\n"
        "COMMIT;\n"
    ).encode("utf-8")


def _validate_environment_impl(
    root: Path, run: str, command_runner: CommandRunner, expected_phase: str,
) -> tuple[Path, dict, dict, Path]:
    root = Path(root).resolve(strict=True)
    runtime = environment._runtime(root, run)
    state = _read_json(runtime / "state.json", "run state")
    manifest = _read_json(runtime / "manifest.json", "environment manifest")
    if (
        state.get("profile") != "R1_E2E_RUN_STATE_V1"
        or state.get("run") != run
        or state.get("phase") != expected_phase
        or state.get("applicationReady") is not False
    ):
        raise RuntimeError(f"run is not consumable from phase {state.get('phase')}")
    if (
        manifest.get("profile") != "R1_E2E_ENVIRONMENT_V1"
        or manifest.get("run") != run
        or manifest.get("composeProject") != "ontology-law-r1-e2e-" + run
        or manifest.get("issuer") != environment.ISSUER
    ):
        raise RuntimeError("environment manifest identity mismatch")
    environment._assert_unchanged(root, runtime, manifest)
    schema_path = root / "database/schema-contract-52-plus-2/generated/schema-contract-manifest.json"
    if manifest.get("schemaManifestSha256") != _sha256(schema_path):
        raise RuntimeError("schema manifest digest mismatch")
    java_path = Path(manifest.get("hostTools", {}).get("java", {}).get("path", ""))
    environment._plain_file(java_path, "java")
    environment._verify_private_boundary(runtime)

    java_exit, java_stdout, java_stderr = _invoke(
        [str(java_path), "-Xmx256m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-version"],
        root, "java-version", command_runner,
    )
    if java_exit or environment.validate_java_version(java_stderr + java_stdout) != manifest["hostTools"]["java"]["build"]:
        raise RuntimeError("prepared Java build mismatch")
    ps_exit, ps_stdout, _ = _invoke(
        _compose_prefix(root, runtime, manifest) + ["ps", "-a", "--format", "json"],
        root, "compose-ps", command_runner,
    )
    if ps_exit:
        raise RuntimeError("current compose project inspection failed")
    environment._validate_process_snapshot(ps_stdout, manifest["composeProject"])
    deployment_exit, deployment_stdout, _ = _invoke(
        _psql_command(root, runtime, manifest), root, "deployment-read", command_runner,
        input_bytes=_deployment_query(),
    )
    if deployment_exit:
        raise RuntimeError("read-only deployment validation failed")
    deployment = _parse_object(deployment_stdout, "deployment-read")
    expected_deployment = {
        "operatingMode": "ACTIVE",
        "schemaVersion": SCHEMA_VERSION,
        "releaseDigest": manifest["artifacts"]["jar"]["sha256"],
        "manifestHash": manifest["schemaManifestSha256"],
    }
    if deployment != expected_deployment:
        raise RuntimeError("database deployment state does not match prepared Jar and schema")
    environment._assert_unchanged(root, runtime, manifest)
    environment._verify_private_boundary(runtime)
    if _read_json(runtime / "state.json", "run state") != state:
        raise RuntimeError("run state changed during bootstrap preflight")
    return runtime, state, manifest, java_path


def _validate_environment(
    root: Path, run: str, command_runner: CommandRunner, *, verified_phase: bool = False,
) -> tuple[Path, dict, dict, Path]:
    expected_phase = "IDENTITY_BOOTSTRAP_VERIFIED" if verified_phase else "INFRASTRUCTURE_READY"
    return _validate_environment_impl(root, run, command_runner, expected_phase)


def _validate_failed_bootstrap_environment(
    root: Path, run: str, command_runner: CommandRunner,
) -> tuple[Path, dict, dict, Path]:
    return _validate_environment_impl(
        root, run, command_runner, "IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN",
    )


def _fixture_values(root: Path, run: str) -> tuple[str, dict[str, str]]:
    fixture = _read_json(root / "e2e/fixtures/r1-fixture.json", "R1 fixture")
    accounts = fixture.get("accounts")
    tenants = fixture.get("tenants")
    if not isinstance(accounts, list) or not isinstance(tenants, list):
        raise RuntimeError("R1 fixture shape mismatch")
    founder = [account for account in accounts if account.get("key") == "founder"]
    displays = {tenant.get("code"): tenant.get("displayName") for tenant in tenants if isinstance(tenant, dict)}
    if len(founder) != 1 or founder[0].get("usernameStem") != "founder" or founder[0].get("displayName") != "Synthetic Founder":
        raise RuntimeError("R1 founder fixture mismatch")
    if displays != {code: display for _, code, display, _ in TENANTS}:
        raise RuntimeError("R1 tenant fixture mismatch")
    return f"r1-{run}-founder", displays


def _java_command(java_path: Path, jar: Path, logging_config: Path) -> list[str]:
    return [
        str(java_path), "-Xmx256m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        f"-Dlogback.configurationFile={logging_config.resolve()}",
        f"-Dloader.main={JAVA_MAIN}", "-cp", str(jar), JAVA_LAUNCHER,
    ]


def _prepare_logging_config(root: Path, runtime: Path, bootstrap_root: Path) -> tuple[Path, dict]:
    source = environment._plain_file(root / LOGGING_CONFIG_SOURCE, "bootstrap logging configuration")
    encoded = source.read_bytes()
    if not encoded or len(encoded) > 8192:
        raise RuntimeError("bootstrap logging configuration is invalid")
    target = bootstrap_root / "logback.xml"
    environment._write(target, encoded)
    source_digest, target_digest = _sha256(source), _sha256(target)
    if source_digest != target_digest:
        raise RuntimeError("bootstrap logging configuration copy mismatch")
    return target, {
        "loggingConfigSourcePath": LOGGING_CONFIG_SOURCE,
        "loggingConfigSourceSha256": source_digest,
        "loggingConfigPath": target.relative_to(runtime).as_posix(),
        "loggingConfigSha256": target_digest,
    }


def _append_stage(operation_path: Path, mode: str, exit_code: int | None) -> None:
    operation = _read_json(operation_path, "bootstrap operation")
    operation["stages"].append({"mode": mode, "exitCode": exit_code, "at": environment.utc_now()})
    environment._atomic_json(operation_path, operation)


def _stage_output_manifest(runtime: Path, folder: Path) -> dict:
    outputs = {}
    for mode in ("candidate", "dry-run", "execute", "verify", "fact-query"):
        streams = {}
        for stream in ("stdout", "stderr"):
            path = folder / f"{mode}.{stream}"
            if not path.is_file() or environment._is_link(path):
                raise RuntimeError("original stage output is missing or linked")
            streams[f"{stream}Path"] = path.relative_to(runtime).as_posix()
            streams[f"{stream}Sha256"] = _sha256(path)
        outputs[mode] = streams
    return outputs


def _run_stage(
    command: list[str], root: Path, folder: Path, operation_path: Path,
    mode: str, command_runner: CommandRunner,
) -> dict:
    try:
        exit_code, stdout, _ = _invoke(command, root, mode, command_runner, output_directory=folder)
    except ProcessFailure as error:
        _append_stage(operation_path, mode, error.exit_code)
        raise RuntimeError(str(error)) from error
    _append_stage(operation_path, mode, exit_code)
    if exit_code:
        raise RuntimeError(f"{mode} failed or is uncertain; retain the original operation")
    return _parse_object(stdout, mode)


def _settings(
    runtime: Path, manifest: dict, code: str, tenant_id: str, subject_hmac_path: Path,
) -> dict:
    absolute = lambda relative: str((runtime / relative).resolve())
    ca = absolute("certs/ca.pem").replace("\\", "/")
    return {
        "semanticBaseline": SEMANTIC_BASELINE,
        "tenantId": tenant_id,
        "tenantCode": code,
        "identityProviderCode": code,
        "issuer": manifest["issuer"],
        "apiAudience": "r1-e2e-api",
        "directoryClientId": "r1-e2e-directory",
        "directorySecretPath": absolute("secrets/directory-secret.txt"),
        "operatorAssertion": OPERATOR_ASSERTION,
        "node": EXECUTION_NODE,
        "activeBootstrapKeyId": BOOTSTRAP_KEY_ID,
        "bootstrapKeyPaths": {BOOTSTRAP_KEY_ID: absolute("secrets/bootstrap-key.txt")},
        "subjectHmacPath": str(subject_hmac_path.resolve()),
        "identityTrustStorePath": absolute("certs/application-trust.p12"),
        "identityTrustStorePasswordPath": absolute("secrets/trust-password.txt"),
        "database": {
            "url": "jdbc:postgresql://localhost:29446/law_contract_runtime?sslmode=verify-full&sslrootcert=" + ca,
            "username": "law_api_login",
            "passwordPath": absolute("secrets/api-db-password.txt"),
            "schemaVersion": SCHEMA_VERSION,
            "releaseDigest": manifest["artifacts"]["jar"]["sha256"],
            "manifestHash": manifest["schemaManifestSha256"],
        },
    }


def _new_subject_hmac(runtime: Path, target: Path) -> None:
    existing = {path.read_bytes().strip() for path in (runtime / "secrets").glob("*.txt")}
    for path in (runtime / BOOTSTRAP_DIRECTORY).glob("*/subject-hmac.txt"):
        if path.exists():
            existing.add(path.read_bytes().strip())
    for _ in range(16):
        value = base64.b64encode(secrets.token_bytes(32))
        if value not in existing:
            environment._write(target, value + b"\n")
            return
    raise RuntimeError("could not create an independent tenant subject HMAC")


def _original_manifest(code: str, display: str, root_display: str, selector: str) -> dict:
    effective = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat().replace("+00:00", "Z")
    return {
        "profile": "R1_IDENTITY_BOOTSTRAP_V1",
        "commandId": str(uuid.uuid4()),
        "tenantCode": code,
        "tenantDisplayName": display,
        "rootCode": "ROOT",
        "rootDisplayName": root_display,
        "identityProviderCode": code,
        "issuer": environment.ISSUER,
        "providerUserSelector": selector,
        "principalDisplayName": "Synthetic Founder",
        "effectiveFrom": effective,
        "operatorAssertion": OPERATOR_ASSERTION,
    }


def _require_result(result: dict, mode: str) -> None:
    expected_delta = {} if mode == "VERIFIED_ORIGINAL" else EXPECTED_DELTA
    if result.get("mode") != mode or result.get("plannedDelta") != expected_delta:
        raise RuntimeError(f"unexpected {mode} response")
    if mode == "DRY_RUN" and not isinstance(result.get("preview"), dict):
        raise RuntimeError("unexpected DRY_RUN response")


def _require_dry_run_preview(result: dict, settings: dict, original: dict) -> None:
    expected = {
        "tenant": {
            "id": settings["tenantId"],
            "code": original["tenantCode"],
            "displayName": original["tenantDisplayName"],
        },
        "rootOrganization": {"code": "ROOT", "displayName": original["rootDisplayName"]},
        "administrator": {
            "displayName": "Synthetic Founder",
            "principalKind": "HUMAN",
            "identityProviderCode": original["identityProviderCode"],
        },
        "appointment": {
            "roleCode": "IDENTITY_ADMIN",
            "organizationCode": "ROOT",
            "effectiveFrom": original["effectiveFrom"],
        },
        "authorityGrants": [
            {
                "authorityCode": code,
                "path": "DIRECT",
                "scope": "ROOT",
                "scopeOrganizationCode": "ROOT",
            }
            for code in MANAGEMENT_CODES
        ],
    }
    if result.get("preview") != expected:
        raise RuntimeError("dry-run preview does not match the controlled identity delta")


def _fact_query(tenant_id: str, tenant_code: str, command_id: str) -> bytes:
    if not all(re.fullmatch(r"[0-9a-f-]{36}", value) for value in (tenant_id, command_id)):
        raise RuntimeError("invalid protected bootstrap identity")
    if tenant_code not in {code for _, code, _, _ in TENANTS}:
        raise RuntimeError("invalid protected tenant code")
    return (
        "BEGIN READ ONLY;\nSET LOCAL ROLE law_app_query;\n"
        "SELECT jsonb_build_object("
        "'tenantId',tenant_id::text,"
        "'rootOrganizationId',change_summary->>'rootOrganizationId',"
        "'founderPrincipalId',change_summary->>'founderPrincipalId',"
        "'appointmentId',change_summary->>'appointmentId',"
        "'grantIds',change_summary->'grantIds',"
        "'originalEvidenceSha256',encode(authorization_snapshot_digest,'hex'))::text "
        "FROM audit.audit_entry_classified_v AS bootstrap_audit WHERE tenant_id='" + tenant_id + "'::uuid "
        "AND command_id='" + command_id + "'::uuid "
        "AND action_code='BOOTSTRAP_IDENTITY_ADMIN' AND result_code='SUCCEEDED' "
        "AND change_summary->>'profile'='R1_IDENTITY_BOOTSTRAP_V1' "
        "AND change_summary->>'operatorAssertion'='" + OPERATOR_ASSERTION + "' "
        "AND EXISTS (SELECT 1 FROM identity.tenant t WHERE t.tenant_id=bootstrap_audit.tenant_id "
        "AND t.tenant_code='" + tenant_code + "');\nCOMMIT;\n"
    ).encode("utf-8")


def _fact_references(row: dict, tenant_id: str) -> dict:
    keys = ("tenantId", "rootOrganizationId", "founderPrincipalId", "appointmentId")
    if row.get("tenantId") != tenant_id:
        raise RuntimeError("read-only bootstrap facts do not match the original tenant")
    try:
        for key in keys:
            uuid.UUID(row[key])
        grants = row["grantIds"]
        if not isinstance(grants, list) or len(grants) != 4 or len(set(grants)) != 4:
            raise ValueError()
        for value in grants:
            uuid.UUID(value)
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise RuntimeError("read-only bootstrap fact response is invalid") from error
    evidence = row.get("originalEvidenceSha256")
    if not isinstance(evidence, str) or not re.fullmatch(r"[0-9a-f]{64}", evidence):
        raise RuntimeError("original bootstrap evidence digest is invalid")
    typed = [
        {"type": "identity.tenant", "id": row["tenantId"]},
        {"type": "identity.organization_unit", "id": row["rootOrganizationId"]},
        {"type": "identity.principal", "id": row["founderPrincipalId"]},
        {"type": "identity.appointment", "id": row["appointmentId"]},
        *({"type": "identity.authority_grant", "id": grant} for grant in grants),
    ]
    return {
        "profile": "R1_E2E_BOOTSTRAP_FACT_REFERENCES_V1",
        "source": "ORIGINAL_VERIFY_AND_CONSTRAINED_READ_ONLY_QUERY",
        "originalVerificationEvidenceSha256": evidence,
        "facts": typed,
    }


def _verified_tenant_input(facts: dict, settings: dict, subject_path: str) -> dict:
    references = facts.get("facts")
    if not isinstance(references, list):
        raise RuntimeError("original bootstrap fact references are invalid")
    grouped: dict[str, list[str]] = {}
    for reference in references:
        if not isinstance(reference, dict) or set(reference) != {"type", "id"}:
            raise RuntimeError("original bootstrap fact references are invalid")
        grouped.setdefault(reference["type"], []).append(reference["id"])
    required = {
        "identity.tenant": "tenantId",
        "identity.organization_unit": "rootOrganizationId",
        "identity.principal": "founderPrincipalId",
        "identity.appointment": "appointmentId",
    }
    if any(len(grouped.get(fact_type, [])) != 1 for fact_type in required):
        raise RuntimeError("original bootstrap fact references are invalid")
    grants = grouped.get("identity.authority_grant", [])
    if len(grants) != 4 or set(grouped) != {*required, "identity.authority_grant"}:
        raise RuntimeError("original bootstrap fact references are invalid")
    result = {name: grouped[fact_type][0] for fact_type, name in required.items()}
    if result["tenantId"] != settings.get("tenantId"):
        raise RuntimeError("original bootstrap tenant binding mismatch")
    result.update(
        authorityGrantIds=grants,
        subjectHmacPath=subject_path,
        originalVerificationEvidenceSha256=facts.get("originalVerificationEvidenceSha256"),
    )
    return result


def _mark_failed(runtime: Path, state: dict, record_path: Path) -> None:
    if record_path.exists():
        record = _read_json(record_path, "bootstrap record")
        record["phase"] = "FAILED_OR_UNCERTAIN"
        record["updatedAt"] = environment.utc_now()
        environment._atomic_json(record_path, record)
    state.update(
        phase="IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN",
        applicationReady=False,
        applicationStatus="BLOCKED_IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN",
        updatedAt=environment.utc_now(),
    )
    state.setdefault("events", []).append({"phase": state["phase"], "at": state["updatedAt"]})
    environment._atomic_json(runtime / "state.json", state)


def bootstrap_identities(
    root: Path, run: str, command_runner: CommandRunner = subprocess.run,
) -> None:
    root = Path(root).resolve(strict=True)
    runtime = environment._runtime(root, run)
    bootstrap_root = runtime / BOOTSTRAP_DIRECTORY
    if bootstrap_root.exists():
        raise RuntimeError("existing identity bootstrap; refuse overwrite or replay")
    runtime, state, manifest, java_path = _validate_environment(root, run, command_runner)
    founder_identifier, fixture_displays = _fixture_values(root, run)
    jar = runtime / manifest["artifacts"]["jar"]["path"]
    environment._mkdir_secure(bootstrap_root)
    logging_config, logging_record = _prepare_logging_config(root, runtime, bootstrap_root)
    record_path = bootstrap_root / "record.json"
    record = {
        "profile": "R1_E2E_IDENTITY_BOOTSTRAP_RECORD_V1",
        "run": run,
        "environmentManifestSha256": _sha256(runtime / "manifest.json"),
        "phase": "IN_PROGRESS",
        "applicationReady": False,
        "createdAt": environment.utc_now(),
        "updatedAt": environment.utc_now(),
        "tenants": [],
        **logging_record,
    }
    environment._atomic_json(record_path, record)
    try:
        for key, code, display, root_display in TENANTS:
            if fixture_displays[code] != display:
                raise RuntimeError("R1 fixture display mismatch")
            folder = bootstrap_root / key
            environment._mkdir_secure(folder)
            subject_path = folder / "subject-hmac.txt"
            _new_subject_hmac(runtime, subject_path)
            settings = _settings(runtime, manifest, code, str(uuid.uuid4()), subject_path)
            settings_path = folder / "settings.json"
            identifier_path = folder / "founder-identifier.txt"
            original_path = folder / "original-manifest.json"
            operation_path = folder / "operation.json"
            environment._atomic_json(settings_path, settings)
            environment._write(identifier_path, founder_identifier + "\n")
            environment._atomic_json(operation_path, {
                "profile": "R1_E2E_IDENTITY_BOOTSTRAP_OPERATION_V1",
                "tenantCode": code,
                "stages": [],
            })
            base = _java_command(java_path, jar, logging_config)
            candidate = _run_stage(
                [*base, "candidate", str(settings_path), str(identifier_path)],
                root, folder, operation_path, "candidate", command_runner,
            )
            selector = candidate.get("providerUserSelector")
            if set(candidate) != {"providerUserSelector"} or not isinstance(selector, str) or not selector.strip():
                raise RuntimeError("candidate returned invalid JSON")
            original = _original_manifest(code, display, root_display, selector)
            environment._atomic_json(original_path, original)
            dry_run = _run_stage(
                [*base, "dry-run", str(settings_path), str(original_path)],
                root, folder, operation_path, "dry-run", command_runner,
            )
            _require_result(dry_run, "DRY_RUN")
            _require_dry_run_preview(dry_run, settings, original)
            executed = _run_stage(
                [*base, "execute", str(settings_path), str(original_path), "--confirm-bootstrap"],
                root, folder, operation_path, "execute", command_runner,
            )
            _require_result(executed, "CREATED")
            verified = _run_stage(
                [*base, "verify", str(settings_path), str(original_path)],
                root, folder, operation_path, "verify", command_runner,
            )
            _require_result(verified, "VERIFIED_ORIGINAL")
            try:
                fact_exit, fact_stdout, _ = _invoke(
                    _psql_command(root, runtime, manifest), root, "fact-query", command_runner,
                    output_directory=folder,
                    input_bytes=_fact_query(settings["tenantId"], code, original["commandId"]),
                )
            except ProcessFailure as error:
                _append_stage(operation_path, "fact-query", error.exit_code)
                raise RuntimeError(str(error)) from error
            _append_stage(operation_path, "fact-query", fact_exit)
            if fact_exit:
                raise RuntimeError("fact-query failed after original verification")
            facts = _fact_references(_parse_object(fact_stdout, "fact-query"), settings["tenantId"])
            facts_path = folder / "fact-references.json"
            environment._atomic_json(facts_path, facts)
            operation = _read_json(operation_path, "bootstrap operation")
            operation["phase"] = "VERIFIED_ORIGINAL"
            environment._atomic_json(operation_path, operation)
            record["tenants"].append({
                "tenantCode": code,
                "settingsPath": settings_path.relative_to(runtime).as_posix(),
                "settingsSha256": _sha256(settings_path),
                "subjectHmacPath": subject_path.relative_to(runtime).as_posix(),
                "subjectHmacSha256": _sha256(subject_path),
                "originalManifestPath": original_path.relative_to(runtime).as_posix(),
                "originalManifestSha256": _sha256(original_path),
                "operationPath": operation_path.relative_to(runtime).as_posix(),
                "operationSha256": _sha256(operation_path),
                "stageOutputs": _stage_output_manifest(runtime, folder),
                "factReferencesPath": facts_path.relative_to(runtime).as_posix(),
                "factReferencesSha256": _sha256(facts_path),
            })
            record["updatedAt"] = environment.utc_now()
            environment._atomic_json(record_path, record)
        record["phase"] = "IDENTITY_BOOTSTRAP_VERIFIED"
        record["updatedAt"] = environment.utc_now()
        environment._atomic_json(record_path, record)
        environment._verify_private_boundary(runtime)
        state.update(
            phase="IDENTITY_BOOTSTRAP_VERIFIED",
            applicationReady=False,
            applicationStatus="BLOCKED_APPLICATION_ASSEMBLY_REQUIRED",
            identityBootstrapRecordSha256=_sha256(record_path),
            updatedAt=environment.utc_now(),
        )
        state.setdefault("events", []).append({"phase": state["phase"], "at": state["updatedAt"]})
        environment._atomic_json(runtime / "state.json", state)
    except Exception:
        _mark_failed(runtime, state, record_path)
        raise


def verify_original(
    root: Path, run: str, command_runner: CommandRunner = subprocess.run,
) -> dict:
    root = Path(root).resolve(strict=True)
    runtime, state, manifest, java_path = _validate_environment(
        root, run, command_runner, verified_phase=True,
    )
    bootstrap_root = runtime / BOOTSTRAP_DIRECTORY
    record_path = bootstrap_root / "record.json"
    record = _read_json(record_path, "bootstrap record")
    if (
        record.get("profile") != "R1_E2E_IDENTITY_BOOTSTRAP_RECORD_V1"
        or record.get("run") != run
        or record.get("phase") != "IDENTITY_BOOTSTRAP_VERIFIED"
        or record.get("environmentManifestSha256") != _sha256(runtime / "manifest.json")
        or state.get("identityBootstrapRecordSha256") != _sha256(record_path)
    ):
        raise RuntimeError("original bootstrap record mismatch")
    entries = record.get("tenants")
    if not isinstance(entries, list) or [entry.get("tenantCode") for entry in entries] != [item[1] for item in TENANTS]:
        raise RuntimeError("original bootstrap tenant inventory mismatch")
    expected_logging_paths = {
        "loggingConfigSourcePath": LOGGING_CONFIG_SOURCE,
        "loggingConfigPath": (Path(BOOTSTRAP_DIRECTORY) / "logback.xml").as_posix(),
    }
    if any(record.get(key) != value for key, value in expected_logging_paths.items()):
        raise RuntimeError("original bootstrap logging configuration mismatch")
    logging_source = root / LOGGING_CONFIG_SOURCE
    logging_config = runtime / record["loggingConfigPath"]
    for path, digest_key in (
        (logging_source, "loggingConfigSourceSha256"),
        (logging_config, "loggingConfigSha256"),
    ):
        digest = record.get(digest_key)
        if (
            not isinstance(digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
            or not path.is_file()
            or environment._is_link(path)
            or _sha256(path) != digest
        ):
            raise RuntimeError("original bootstrap logging configuration mismatch")
    if record["loggingConfigSourceSha256"] != record["loggingConfigSha256"]:
        raise RuntimeError("original bootstrap logging configuration mismatch")
    jar = runtime / manifest["artifacts"]["jar"]["path"]
    base = _java_command(java_path, jar, logging_config)
    validated_entries = []
    for entry in entries:
        paths = {
            "settings": runtime / entry["settingsPath"],
            "subject": runtime / entry["subjectHmacPath"],
            "original": runtime / entry["originalManifestPath"],
            "operation": runtime / entry["operationPath"],
            "facts": runtime / entry["factReferencesPath"],
        }
        expected_hashes = {
            "settings": entry["settingsSha256"],
            "subject": entry["subjectHmacSha256"],
            "original": entry["originalManifestSha256"],
            "operation": entry["operationSha256"],
            "facts": entry["factReferencesSha256"],
        }
        for key, path in paths.items():
            if not path.is_file() or environment._is_link(path) or _sha256(path) != expected_hashes[key]:
                raise RuntimeError("original bootstrap evidence mismatch")
        stage_outputs = entry.get("stageOutputs")
        expected_modes = {"candidate", "dry-run", "execute", "verify", "fact-query"}
        if not isinstance(stage_outputs, dict) or set(stage_outputs) != expected_modes:
            raise RuntimeError("original bootstrap evidence mismatch")
        evidence_parent = Path(entry["settingsPath"]).parent
        for mode in expected_modes:
            streams = stage_outputs[mode]
            if not isinstance(streams, dict) or set(streams) != {
                "stdoutPath", "stdoutSha256", "stderrPath", "stderrSha256",
            }:
                raise RuntimeError("original bootstrap evidence mismatch")
            for stream in ("stdout", "stderr"):
                expected_relative = (evidence_parent / f"{mode}.{stream}").as_posix()
                if streams[f"{stream}Path"] != expected_relative:
                    raise RuntimeError("original bootstrap evidence mismatch")
                path = runtime / expected_relative
                digest = streams[f"{stream}Sha256"]
                if (
                    not isinstance(digest, str)
                    or not re.fullmatch(r"[0-9a-f]{64}", digest)
                    or not path.is_file()
                    or environment._is_link(path)
                    or _sha256(path) != digest
                ):
                    raise RuntimeError("original bootstrap evidence mismatch")
        validated_entries.append((entry, paths))
    verified_tenants = {}
    for entry, paths in validated_entries:
        exit_code, stdout, _ = _invoke(
            [*base, "verify", str(paths["settings"]), str(paths["original"])],
            root, "verify", command_runner,
        )
        if exit_code:
            raise RuntimeError("verify failed or is uncertain; retain the original operation")
        _require_result(_parse_object(stdout, "verify"), "VERIFIED_ORIGINAL")
        settings = _read_json(paths["settings"], "original bootstrap settings")
        facts = _read_json(paths["facts"], "original bootstrap fact references")
        verified_tenants[entry["tenantCode"]] = _verified_tenant_input(
            facts, settings, entry["subjectHmacPath"],
        )
    return {
        "profile": "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1",
        "run": run,
        "tenants": verified_tenants,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    for operation in ("bootstrap", "verify-original"):
        command = subparsers.add_parser(operation)
        command.add_argument("run")
    args = parser.parse_args(argv)
    try:
        environment.validate_run_id(args.run)
        if args.operation == "bootstrap":
            bootstrap_identities(args.root, args.run)
            print("IDENTITY_BOOTSTRAP_VERIFIED; application remains blocked pending assembly")
        else:
            verify_original(args.root, args.run)
            print("VERIFIED_ORIGINAL; original commands and evidence preserved")
        return 0
    except (RuntimeError, ValueError):
        print(
            "R1_IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN: retain the protected original run; do not retry or replace commands",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
