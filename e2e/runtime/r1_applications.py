"""Assemble the existing Jar and SPA for one protected R1 acceptance run.

This unit adds only the SERVICE infrastructure prerequisite, protected runtime
configuration, process registration, and readiness evidence.  It never creates
HUMAN business fixtures and never repairs or replays an uncertain operation.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping


RUNTIME_DIRECTORY = Path(__file__).resolve().parent
if str(RUNTIME_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(RUNTIME_DIRECTORY))
import r1_bootstrap as bootstrap  # noqa: E402
import r1_bootstrap_continuation as bootstrap_continuation  # noqa: E402
import r1_environment as environment  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
PROFILE = "R1_E2E_APPLICATIONS_V1"
STATE_PROFILE = "R1_E2E_APPLICATION_STATE_V1"
BOOTSTRAP_SOURCES = ("original", "continued")
SERVICE_AUTHORITIES = (
    "R1_PROJECTION_CONSUME",
    "CONTACT_TASK_RECOVER",
    "ROUTING_REVIEW_TASK_RECOVER",
)
SOURCE_POLICIES = {
    "R1_AUTO": ("AUTOMATIC", "OWNED_ROOT"),
    "R1_MANUAL": ("MANUAL", "OWNED_ROOT"),
    "R1_ZERO_CANDIDATE": ("AUTOMATIC", "EMPTY_ROOT"),
}
SCHEMA_VERSION = "52-plus-2-v1.2"
SEMANTIC_BASELINE = "MVP-2026-09-08.3"
SERVICE_ISSUER = "urn:r1-e2e:service"
AUDIENCE = "r1-e2e-api"
DIRECTORY_CLIENT = "r1-e2e-directory"
SERVICE_ALIAS = "r1_e2e_service"
PORTS = {"spa": 29444, "api": 29445}
UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")


def _sha256(path: Path) -> str:
    return environment.sha256_file(path)


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _digest_value(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8", errors="strict"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"{label} unavailable or invalid") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} unavailable or invalid")
    return value


def _read_secret(path: Path) -> str:
    if not path.is_file() or environment._is_link(path):
        raise RuntimeError("protected secret missing or linked")
    value = path.read_text(encoding="utf-8", errors="strict").strip()
    if not value or "\n" in value or "\r" in value:
        raise RuntimeError("protected secret invalid")
    return value


def _write_exclusive(path: Path, value: str) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
        output.write(value)
        output.flush()
        os.fsync(output.fileno())
    environment._protect_file(path)


def _protect_application_boundary(folder: Path) -> None:
    environment._verify_private_boundary(folder)


def _verify_bootstrap_source(root: Path, run: str, source: str) -> dict:
    if source not in BOOTSTRAP_SOURCES:
        raise ValueError("bootstrap source must be original or continued")
    if source == "original":
        return bootstrap.verify_original(root, run)
    return bootstrap_continuation.verify_combined(root, run)


def _canonical_uuid(value: object, label: str) -> str:
    if not isinstance(value, str) or not UUID_PATTERN.fullmatch(value):
        raise RuntimeError(f"bootstrap projection {label} invalid")
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except ValueError as error:
        raise RuntimeError(f"bootstrap projection {label} invalid") from error
    return value


def _validate_projection(runtime: Path, run: str, value: object) -> dict:
    if not isinstance(value, dict) or set(value) != {"profile", "run", "tenants"} or \
       value.get("profile") != "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1" or value.get("run") != run:
        raise RuntimeError("bootstrap projection identity mismatch")
    tenants = value.get("tenants")
    if not isinstance(tenants, dict) or set(tenants) != {"R1_E2E_MAIN", "R1_E2E_ISOLATION"}:
        raise RuntimeError("bootstrap projection tenant inventory mismatch")
    required = {
        "tenantId", "rootOrganizationId", "founderPrincipalId", "appointmentId",
        "authorityGrantIds", "subjectHmacPath", "originalVerificationEvidenceSha256",
    }
    seen: set[str] = set()
    for code, tenant in tenants.items():
        if not isinstance(tenant, dict) or set(tenant) != required:
            raise RuntimeError("bootstrap projection tenant shape mismatch")
        for field in ("tenantId", "rootOrganizationId", "founderPrincipalId", "appointmentId"):
            seen.add(_canonical_uuid(tenant[field], field))
        grants = tenant["authorityGrantIds"]
        if not isinstance(grants, list) or len(grants) != 4:
            raise RuntimeError("bootstrap projection authority grants invalid")
        for grant in grants:
            seen.add(_canonical_uuid(grant, "authorityGrantIds"))
        relative = tenant["subjectHmacPath"]
        if not isinstance(relative, str) or Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise RuntimeError("bootstrap projection subject HMAC path invalid")
        subject = runtime / relative
        if not subject.is_file() or environment._is_link(subject) or runtime not in subject.resolve().parents:
            raise RuntimeError("bootstrap projection subject HMAC path invalid")
        _decode_key(_read_secret(subject), "bootstrap subject HMAC")
        evidence = tenant["originalVerificationEvidenceSha256"]
        if not isinstance(evidence, str) or not HASH_PATTERN.fullmatch(evidence):
            raise RuntimeError("bootstrap projection evidence digest invalid")
    if len(seen) != 16:
        raise RuntimeError("bootstrap projection UUID collision")
    return value


def _decode_key(value: str, label: str) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as error:
        raise RuntimeError(f"{label} invalid") from error
    if len(decoded) != 32:
        raise RuntimeError(f"{label} invalid")
    return decoded


def _new_keys(runtime: Path, folder: Path, credential_value: str) -> dict[str, str]:
    existing = {_read_secret(path) for path in (runtime / "secrets").glob("*.txt") if path.is_file()}
    existing.add(credential_value)
    names = (
        "encryption", "phone-hmac", "email-hmac", "source-hmac", "actor-scope-hmac",
        "api-cursor", "online-candidate", "etag", "admin-cursor", "service-p12-password",
    )
    values: dict[str, str] = {}
    secrets_folder = folder / "secrets"
    environment._mkdir_secure(secrets_folder)
    for name in names:
        for _ in range(32):
            candidate = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
            if candidate not in existing and candidate not in values.values():
                break
        else:
            raise RuntimeError("could not create independent application keys")
        values[name] = candidate
        environment._write(secrets_folder / f"{name}.txt", candidate + "\n")
    return values


def _invoke(command: list[str], cwd: Path, folder: Path, label: str,
            *, input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    try:
        completed = subprocess.run(
            command, cwd=cwd, input=input_bytes, capture_output=True, text=False,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
    except OSError as error:
        raise RuntimeError(f"{label} execution result unknown") from error
    logs = folder / "logs"
    if not logs.exists():
        environment._mkdir_secure(logs)
    if not isinstance(completed.stdout, bytes) or not isinstance(completed.stderr, bytes):
        raise RuntimeError(f"{label} output capture invalid")
    environment._write(logs / f"{label}.stdout", completed.stdout)
    environment._write(logs / f"{label}.stderr", completed.stderr)
    try:
        stdout = completed.stdout.decode("utf-8", errors="strict").replace("\r\n", "\n")
        stderr = completed.stderr.decode("utf-8", errors="strict").replace("\r\n", "\n")
    except UnicodeDecodeError as error:
        raise RuntimeError(f"{label} output invalid; result unknown") from error
    return subprocess.CompletedProcess(completed.args, completed.returncode, stdout, stderr)


def _create_service_crypto(folder: Path) -> dict:
    runtime = folder.parent
    manifest = _read_json(runtime / "manifest.json", "environment manifest")
    openssl = Path(manifest.get("hostTools", {}).get("openssl", {}).get("path", ""))
    environment._plain_file(openssl, "openssl")
    certs = folder / "certs"
    environment._mkdir_secure(certs)
    extension = certs / "service.ext"
    request = certs / "service.csr"
    key = certs / "service.key"
    certificate = certs / "service.crt"
    p12 = certs / "service.p12"
    public = certs / "service-public.pem"
    environment._write(
        extension,
        "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=clientAuth\n",
    )
    commands = (
        ("service-request", ["req", "-new", "-newkey", "rsa:3072", "-nodes", "-sha256",
            "-subj", "/CN=r1-e2e-service", "-keyout", str(key), "-out", str(request)]),
        ("service-certificate", ["x509", "-req", "-sha256", "-days", "14", "-in", str(request),
            "-CA", str(runtime / "certs/ca.pem"), "-CAkey", str(runtime / "certs/ca.key"),
            "-set_serial", str(secrets.randbelow(2_000_000_000) + 1), "-extfile", str(extension),
            "-out", str(certificate)]),
        ("service-pkcs12", ["pkcs12", "-export", "-name", SERVICE_ALIAS, "-inkey", str(key),
            "-in", str(certificate), "-certfile", str(runtime / "certs/ca.pem"), "-out", str(p12),
            "-passout", f"file:{folder / 'secrets/service-p12-password.txt'}"]),
        ("service-public-key", ["x509", "-in", str(certificate), "-pubkey", "-noout", "-out", str(public)]),
    )
    try:
        for label, arguments in commands:
            result = _invoke([str(openssl), *arguments], runtime, folder, label)
            if result.returncode:
                raise RuntimeError(f"{label} failed; inspect protected diagnostics")
    finally:
        for temporary in (request, extension):
            if temporary.exists():
                temporary.unlink()
    pem = certificate.read_text(encoding="ascii", errors="strict")
    try:
        fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
    except ValueError as error:
        raise RuntimeError("SERVICE certificate invalid") from error
    for file in certs.iterdir():
        environment._protect_file(file)
    return {"fingerprint": fingerprint, "alias": SERVICE_ALIAS}


def _environment_manifest(runtime: Path, run: str) -> dict:
    manifest = _read_json(runtime / "manifest.json", "environment manifest")
    if manifest.get("profile") != "R1_E2E_ENVIRONMENT_V1" or manifest.get("run") != run:
        raise RuntimeError("environment manifest identity mismatch")
    for key in ("schemaManifestSha256",):
        if not isinstance(manifest.get(key), str) or not HASH_PATTERN.fullmatch(manifest[key]):
            raise RuntimeError("environment deployment digest invalid")
    jar = manifest.get("artifacts", {}).get("jar", {})
    spa = manifest.get("artifacts", {}).get("spa", {})
    if not isinstance(jar.get("sha256"), str) or not HASH_PATTERN.fullmatch(jar["sha256"]) or not isinstance(spa.get("files"), dict):
        raise RuntimeError("environment artifact manifest invalid")
    return manifest


def _service_plan(projection: dict, bootstrap_digest: str) -> dict:
    main = projection["tenants"]["R1_E2E_MAIN"]
    principal = str(uuid.uuid4())
    appointment = str(uuid.uuid4())
    grant_ids = [str(uuid.uuid4()) for _ in SERVICE_AUTHORITIES]
    return {
        "profile": "R1_E2E_SERVICE_INFRASTRUCTURE_V1",
        "bootstrapInputSha256": bootstrap_digest,
        "tenantId": main["tenantId"],
        "rootOrganizationId": main["rootOrganizationId"],
        "founderPrincipalId": main["founderPrincipalId"],
        "founderAppointmentId": main["appointmentId"],
        "bootstrapAuthorityGrantIds": main["authorityGrantIds"],
        "principalId": principal,
        "appointmentId": appointment,
        "identityProviderCode": "LOCAL_SERVICE",
        "grants": [
            {
                "authorityGrantId": grant_id,
                "authorityCode": code,
                "grantorAppointmentId": main["appointmentId"],
                "scopeOrganizationUnitId": main["rootOrganizationId"],
            }
            for code, grant_id in zip(SERVICE_AUTHORITIES, grant_ids, strict=True)
        ],
    }


def _sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _database_command(root: Path, runtime: Path, manifest: dict) -> list[str]:
    return [
        "docker", "compose", "--env-file", str(runtime / "compose.env"),
        "-f", str(root / "e2e/compose.yaml"), "-p", manifest["composeProject"],
        "exec", "-T", "business-db", "sh", "-eu", "-c",
        'export PGPASSWORD="$(tr -d "\\r\\n" </run/r1-secrets/api-db-password)"; '
        "exec psql -X -A -t -q -v ON_ERROR_STOP=1 -U law_api_login -d law_contract_runtime",
    ]


def _service_transaction_sql(service: dict, manifest: dict, subject_hmac: str) -> bytes:
    tenant = service["tenantId"]
    root = service["rootOrganizationId"]
    founder = service["founderPrincipalId"]
    founder_appointment = service["founderAppointmentId"]
    bootstrap_grants = ",".join(_sql_literal(value) for value in service["bootstrapAuthorityGrantIds"])
    grants = json.dumps([
        {
            "tenant_id": tenant,
            "authority_grant_id": grant["authorityGrantId"],
            "grantee_appointment_id": service["appointmentId"],
            "granted_by_appointment_id": founder_appointment,
            "scope_organization_unit_id": root,
            "authority_code": grant["authorityCode"],
        }
        for grant in service["grants"]
    ], separators=(",", ":"))
    sql = f"""BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL ROLE law_app_command;
SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';
LOCK TABLE identity.tenant, identity.organization_unit, identity.principal, identity.appointment,
 identity.authority_grant IN SHARE ROW EXCLUSIVE MODE;
DO $r1$ DECLARE affected integer; BEGIN
 IF (SELECT count(*) FROM identity.tenant WHERE tenant_id={_sql_literal(tenant)}::uuid AND tenant_code='R1_E2E_MAIN' AND state='ACTIVE' AND revision=0)<>1
 OR (SELECT count(*) FROM identity.organization_unit WHERE tenant_id={_sql_literal(tenant)}::uuid AND organization_unit_id={_sql_literal(root)}::uuid AND unit_code='ROOT' AND parent_organization_unit_id IS NULL AND state='ACTIVE' AND revision=0)<>1
 OR (SELECT count(*) FROM identity.principal WHERE tenant_id={_sql_literal(tenant)}::uuid AND principal_id={_sql_literal(founder)}::uuid AND principal_kind='HUMAN' AND identity_provider_code='R1_E2E_MAIN' AND state='ACTIVE' AND revision=0)<>1
 OR (SELECT count(*) FROM identity.appointment WHERE tenant_id={_sql_literal(tenant)}::uuid AND appointment_id={_sql_literal(founder_appointment)}::uuid AND principal_id={_sql_literal(founder)}::uuid AND organization_unit_id={_sql_literal(root)}::uuid AND role_code='IDENTITY_ADMIN' AND state='ACTIVE' AND effective_from<=clock_timestamp() AND effective_until IS NULL AND ended_at IS NULL AND revision=0)<>1
 OR (SELECT count(*)<>4 OR count(DISTINCT authority_code)<>4 FROM identity.authority_grant WHERE tenant_id={_sql_literal(tenant)}::uuid AND authority_grant_id IN ({bootstrap_grants}) AND grantee_appointment_id={_sql_literal(founder_appointment)}::uuid AND scope_organization_unit_id={_sql_literal(root)}::uuid AND authority_code IN ('IDENTITY_PRINCIPAL_MANAGE','IDENTITY_ORGANIZATION_MANAGE','IDENTITY_APPOINTMENT_MANAGE','IDENTITY_AUTHORITY_MANAGE') AND state='ACTIVE' AND valid_from<=clock_timestamp() AND valid_until IS NULL AND revoked_at IS NULL AND revision=0)
 OR (SELECT count(*) FROM platform_meta.deployment_state WHERE deployment_state_key='PRIMARY' AND operating_mode='ACTIVE' AND schema_contract_version={_sql_literal(SCHEMA_VERSION)} AND encode(active_release_digest,'hex')={_sql_literal(manifest['artifacts']['jar']['sha256'])} AND encode(active_manifest_hash,'hex')={_sql_literal(manifest['schemaManifestSha256'])})<>1
 THEN RAISE EXCEPTION 'R1 application assembly prerequisite mismatch'; END IF;
 INSERT INTO identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at)
 VALUES ({_sql_literal(tenant)}::uuid,{_sql_literal(service['principalId'])}::uuid,'SERVICE','LOCAL_SERVICE',decode({_sql_literal(subject_hmac)},'hex'),'R1 isolated infrastructure service','ACTIVE',clock_timestamp());
 GET DIAGNOSTICS affected=ROW_COUNT; IF affected<>1 THEN RAISE EXCEPTION 'SERVICE principal delta mismatch'; END IF;
 INSERT INTO identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at)
 VALUES ({_sql_literal(tenant)}::uuid,{_sql_literal(service['appointmentId'])}::uuid,{_sql_literal(service['principalId'])}::uuid,{_sql_literal(root)}::uuid,'SERVICE',clock_timestamp(),'ACTIVE',clock_timestamp());
 GET DIAGNOSTICS affected=ROW_COUNT; IF affected<>1 THEN RAISE EXCEPTION 'SERVICE appointment delta mismatch'; END IF;
 INSERT INTO identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at)
 SELECT tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,clock_timestamp(),'ACTIVE',clock_timestamp()
 FROM jsonb_populate_recordset(NULL::identity.authority_grant,{_sql_literal(grants)}::jsonb);
 GET DIAGNOSTICS affected=ROW_COUNT; IF affected<>3 THEN RAISE EXCEPTION 'SERVICE authority delta mismatch'; END IF;
END $r1$;
COMMIT;
SELECT 'R1_APPLICATION_SERVICE_CREATED_5';
"""
    return sql.encode("utf-8")


def _apply_service_transaction(root: Path, runtime: Path, folder: Path,
                               manifest: dict, service: dict, subject_hmac: str) -> None:
    result = _invoke(
        _database_command(root, runtime, manifest), root, folder, "service-transaction",
        input_bytes=_service_transaction_sql(service, manifest, subject_hmac),
    )
    if result.returncode or result.stdout.strip() != "R1_APPLICATION_SERVICE_CREATED_5":
        raise RuntimeError("SERVICE transaction result unknown; retain protected operation inventory")


def _service_query(service: dict, manifest: dict) -> bytes:
    grants = ",".join(_sql_literal(item["authorityGrantId"]) for item in service["grants"])
    grant_pairs = " OR ".join(
        "(authority_grant_id=" + _sql_literal(item["authorityGrantId"]) +
        "::uuid AND authority_code=" + _sql_literal(item["authorityCode"]) + ")"
        for item in service["grants"]
    )
    return f"""BEGIN READ ONLY; SET LOCAL ROLE law_app_query;
SELECT jsonb_build_object(
 'tenant',coalesce((SELECT count(*)=1 FROM identity.tenant WHERE tenant_id={_sql_literal(service['tenantId'])}::uuid AND tenant_code='R1_E2E_MAIN' AND state='ACTIVE' AND revision=0),false),
 'root',coalesce((SELECT count(*)=1 FROM identity.organization_unit WHERE tenant_id={_sql_literal(service['tenantId'])}::uuid AND organization_unit_id={_sql_literal(service['rootOrganizationId'])}::uuid AND unit_code='ROOT' AND parent_organization_unit_id IS NULL AND state='ACTIVE' AND revision=0),false),
 'founder',coalesce((SELECT count(*)=1 FROM identity.principal p JOIN identity.appointment a USING(tenant_id,principal_id) WHERE p.tenant_id={_sql_literal(service['tenantId'])}::uuid AND p.principal_id={_sql_literal(service['founderPrincipalId'])}::uuid AND p.principal_kind='HUMAN' AND p.identity_provider_code='R1_E2E_MAIN' AND p.state='ACTIVE' AND p.revision=0 AND a.appointment_id={_sql_literal(service['founderAppointmentId'])}::uuid AND a.organization_unit_id={_sql_literal(service['rootOrganizationId'])}::uuid AND a.role_code='IDENTITY_ADMIN' AND a.state='ACTIVE' AND a.effective_from<=clock_timestamp() AND a.effective_until IS NULL AND a.ended_at IS NULL AND a.revision=0),false),
 'service',coalesce((SELECT count(*)=1 FROM identity.principal p JOIN identity.appointment a USING(tenant_id,principal_id) WHERE p.tenant_id={_sql_literal(service['tenantId'])}::uuid AND p.principal_id={_sql_literal(service['principalId'])}::uuid AND p.principal_kind='SERVICE' AND p.identity_provider_code='LOCAL_SERVICE' AND encode(p.external_subject_hmac,'hex')={_sql_literal(service['externalSubjectHmac'])} AND p.display_name='R1 isolated infrastructure service' AND p.state='ACTIVE' AND p.revision=0 AND a.appointment_id={_sql_literal(service['appointmentId'])}::uuid AND a.organization_unit_id={_sql_literal(service['rootOrganizationId'])}::uuid AND a.role_code='SERVICE' AND a.state='ACTIVE' AND a.effective_from<=clock_timestamp() AND a.effective_until IS NULL AND a.ended_at IS NULL AND a.revision=0),false),
 'grants',coalesce((SELECT count(*)=3 AND count(DISTINCT authority_code)=3 AND count(*)=(SELECT count(*) FROM identity.authority_grant all_grants WHERE all_grants.tenant_id={_sql_literal(service['tenantId'])}::uuid AND all_grants.grantee_appointment_id={_sql_literal(service['appointmentId'])}::uuid) FROM identity.authority_grant WHERE tenant_id={_sql_literal(service['tenantId'])}::uuid AND authority_grant_id IN ({grants}) AND ({grant_pairs}) AND grantee_appointment_id={_sql_literal(service['appointmentId'])}::uuid AND granted_by_appointment_id={_sql_literal(service['founderAppointmentId'])}::uuid AND scope_organization_unit_id={_sql_literal(service['rootOrganizationId'])}::uuid AND state='ACTIVE' AND valid_from<=clock_timestamp() AND valid_until IS NULL AND revoked_at IS NULL AND revocation_reason_code IS NULL AND revision=0),false),
 'deployment',coalesce((SELECT count(*)=1 FROM platform_meta.deployment_state WHERE deployment_state_key='PRIMARY' AND operating_mode='ACTIVE' AND schema_contract_version={_sql_literal(SCHEMA_VERSION)} AND encode(active_release_digest,'hex')={_sql_literal(manifest['artifacts']['jar']['sha256'])} AND encode(active_manifest_hash,'hex')={_sql_literal(manifest['schemaManifestSha256'])}),false))::text;
COMMIT;
""".encode("utf-8")


def _verify_service_fixture(root: Path, runtime: Path, folder: Path,
                            manifest: dict, service: dict) -> None:
    result = _invoke(
        _database_command(root, runtime, manifest), root, folder, "service-read-only",
        input_bytes=_service_query(service, manifest),
    )
    try:
        value = json.loads(result.stdout.strip())
    except json.JSONDecodeError as error:
        raise RuntimeError("SERVICE read-only verification unavailable") from error
    if result.returncode or value != {
        "tenant": True, "root": True, "founder": True,
        "service": True, "grants": True, "deployment": True,
    }:
        raise RuntimeError("SERVICE read-only verification mismatch")


def _properties(values: Mapping[str, str]) -> str:
    for key, value in values.items():
        if not key or not isinstance(value, str) or any(character in value for character in "\r\n\\"):
            raise RuntimeError("unsafe application properties")
    return "".join(f"{key}={value}\n" for key, value in values.items())


def _read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="strict").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            raise RuntimeError("invalid application properties")
        key, value = line.split("=", 1)
        if not key or key in values:
            raise RuntimeError("invalid application properties")
        values[key] = value
    return values


def _build_configs(runtime: Path, folder: Path, manifest: dict, projection: dict,
                   service: dict, crypto: dict, keys: dict[str, str]) -> None:
    path = lambda relative: (runtime / relative).resolve().as_posix()
    app_path = lambda relative: (folder / relative).resolve().as_posix()
    main = projection["tenants"]["R1_E2E_MAIN"]
    trust_password = _read_secret(runtime / "secrets/trust-password.txt")
    api = {
        "ols.runtime-role": "api", "server.address": "127.0.0.1", "server.port": "29445",
        "spring.main.banner-mode": "off", "logging.level.root": "WARN",
        "logging.level.io.github.windyzhu3.ontologylaw.api.ApiRuntimeHealth": "INFO",
        "logging.pattern.console": "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %level ${PID} --- [%thread] %logger : %msg%n",
        "server.ssl.client-auth": "want", "server.ssl.key-store": path("certs/application-server.p12"),
        "server.ssl.key-store-password": trust_password, "server.ssl.key-store-type": "PKCS12",
        "server.ssl.trust-store": path("certs/application-trust.p12"),
        "server.ssl.trust-store-password": trust_password, "server.ssl.trust-store-type": "PKCS12",
        "ols.api.semantic-baseline": SEMANTIC_BASELINE, "ols.api.node": "R1_E2E_API",
        "ols.api.cursor-key": keys["api-cursor"],
        "ols.api.database.url": "jdbc:postgresql://localhost:29446/law_contract_runtime?sslmode=verify-full&sslrootcert=" + path("certs/ca.pem"),
        "ols.api.database.username": "law_api_login",
        "ols.api.database.password": _read_secret(runtime / "secrets/api-db-password.txt"),
        "ols.api.database.schema-version": SCHEMA_VERSION,
        "ols.api.database.release-digest": manifest["artifacts"]["jar"]["sha256"],
        "ols.api.database.manifest-hash": manifest["schemaManifestSha256"],
        "ols.api.identity-trust-store-path": path("certs/application-trust.p12"),
        "ols.api.identity-trust-store-password-path": path("secrets/trust-password.txt"),
        "ols.api.trusts[0].issuer": SERVICE_ISSUER, "ols.api.trusts[0].audience": AUDIENCE,
        "ols.api.trusts[0].verification-key-path": app_path("certs/service-public.pem"),
    }
    human = {
        "issuer": manifest["issuer"], "audience": AUDIENCE,
        "identity-provider-code": "R1_E2E_MAIN", "tenant-id": main["tenantId"],
        "introspection-client-id": AUDIENCE,
        "introspection-secret-path": path("secrets/introspection-secret.txt"),
        "directory-client-id": DIRECTORY_CLIENT,
        "directory-secret-path": path("secrets/directory-secret.txt"),
    }
    registration = {
        "issuer": SERVICE_ISSUER, "audience": AUDIENCE, "identity-provider-code": "LOCAL_SERVICE",
        "tenant-id": service["tenantId"], "principal-id": service["principalId"],
        "appointment-id": service["appointmentId"], "principal-kind": "SERVICE",
        **{f"source-account-codes[{index}]": source for index, source in enumerate(SOURCE_POLICIES)},
    }
    certificate = {
        "sha256": crypto["fingerprint"], "identity-provider-code": "LOCAL_SERVICE",
        "tenant-id": service["tenantId"], "principal-id": service["principalId"],
        "appointment-id": service["appointmentId"],
    }
    for prefix, fields in (("human-trusts[0]", human), ("registrations[0]", registration),
                           ("certificates[0]", certificate)):
        api.update({f"ols.api.{prefix}.{key}": value for key, value in fields.items()})
    tenant_keys = {
        "encryption": keys["encryption"], "phone-hmac": keys["phone-hmac"],
        "email-hmac": keys["email-hmac"], "source-hmac": keys["source-hmac"],
        "credential-subject-hmac": _read_secret(runtime / main["subjectHmacPath"]),
        "actor-scope-hmac": keys["actor-scope-hmac"],
    }
    api.update({f"ols.api.tenant-keys[{main['tenantId']}].{key}": value for key, value in tenant_keys.items()})
    for source, (mode, candidate_root) in SOURCE_POLICIES.items():
        prefix = f"ols.api.sources[{source}]."
        api.update({prefix + "assignment-mode": mode,
                    prefix + "routing-organization-root-codes[0]": candidate_root,
                    prefix + "routing-supervisor-root-code": "ROOT",
                    prefix + "source-intake-root-code": "ROOT",
                    prefix + "business-timezone": "Asia/Shanghai"})
    api.update({
        "ols.api.identity-administration.active-candidate-key-id": "r1-online-v1",
        "ols.api.identity-administration.candidate-keys[r1-online-v1]": keys["online-candidate"],
        "ols.api.identity-administration.etag-key": keys["etag"],
        "ols.api.identity-administration.cursor-key": keys["admin-cursor"],
    })
    worker = {
        "ols.runtime-role": "worker", "spring.main.web-application-type": "none",
        "spring.main.banner-mode": "off", "logging.level.root": "WARN",
        "logging.level.io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth": "INFO",
        "logging.pattern.console": "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %level ${PID} --- [%thread] %logger : %msg%n",
        "ols.worker.semantic-baseline": SEMANTIC_BASELINE, "ols.worker.node": "R1_E2E_WORKER",
        "ols.worker.api-origin": "https://localhost:29445",
        "ols.worker.database.url": "jdbc:postgresql://localhost:29446/law_contract_runtime?sslmode=verify-full&sslrootcert=" + path("certs/ca.pem"),
        "ols.worker.database.username": "law_worker_login",
        "ols.worker.database.password": _read_secret(runtime / "secrets/worker-db-password.txt"),
        "ols.worker.database.schema-version": SCHEMA_VERSION,
        "ols.worker.database.release-digest": manifest["artifacts"]["jar"]["sha256"],
        "ols.worker.database.manifest-hash": manifest["schemaManifestSha256"],
        "ols.worker.bindings[0].tenant-id": service["tenantId"],
        "ols.worker.bindings[0].principal-id": service["principalId"],
        "ols.worker.bindings[0].appointment-id": service["appointmentId"],
        "ols.worker.bindings[0].credential-alias": crypto["alias"],
        "ols.worker.bindings[0].certificate-sha256": crypto["fingerprint"],
        "ols.worker.bindings[0].key-store-path": app_path("certs/service.p12"),
        "ols.worker.bindings[0].key-store-password": keys["service-p12-password"],
        "ols.worker.bindings[0].trust-store-path": path("certs/application-trust.p12"),
        "ols.worker.bindings[0].trust-store-password": trust_password,
    }
    environment._write(folder / "api.properties", _properties(api))
    environment._write(folder / "worker.properties", _properties(worker))


def _required_hashes(folder: Path) -> dict[str, str]:
    required = (
        "api.properties", "worker.properties", "service.json", "certs/service.key",
        "certs/service.crt", "certs/service.p12", "certs/service-public.pem",
        *(f"secrets/{name}.txt" for name in (
            "encryption", "phone-hmac", "email-hmac", "source-hmac", "actor-scope-hmac",
            "api-cursor", "online-candidate", "etag", "admin-cursor", "service-p12-password",
        )),
    )
    result = {}
    for relative in required:
        path = folder / relative
        if not path.is_file() or environment._is_link(path):
            raise RuntimeError(f"required application file missing or linked: {relative}")
        result[relative] = _sha256(path)
    return result


def prepare_applications(root: Path, run: str, *, bootstrap_source: str) -> Path:
    root = Path(root).resolve(strict=True)
    environment.validate_run_id(run)
    runtime = environment._runtime(root, run)
    folder = runtime / "applications"
    if folder.exists():
        raise RuntimeError("applications already exists; refuse replay or takeover")
    projection = _validate_projection(runtime, run, _verify_bootstrap_source(root, run, bootstrap_source))
    manifest = _environment_manifest(runtime, run)
    bootstrap_digest = _digest_value(projection)
    folder.mkdir()
    environment._protect_directory(folder)
    state = {
        "profile": STATE_PROFILE, "run": run, "phase": "PREPARING",
        "bootstrapSource": bootstrap_source, "bootstrapInputSha256": bootstrap_digest,
        "applicationReady": False, "processes": {}, "updatedAt": environment.utc_now(),
    }
    environment._atomic_json(folder / "state.json", state)
    database_attempted = False
    try:
        main = projection["tenants"]["R1_E2E_MAIN"]
        credential = _read_secret(runtime / main["subjectHmacPath"])
        keys = _new_keys(runtime, folder, credential)
        service = _service_plan(projection, bootstrap_digest)
        subject_hmac = hmac.new(
            _decode_key(credential, "bootstrap subject HMAC"), b"r1-e2e-infrastructure-service",
            hashlib.sha256,
        ).hexdigest()
        service["externalSubjectHmac"] = subject_hmac
        environment._atomic_json(folder / "service.json", service)
        crypto = _create_service_crypto(folder)
        _build_configs(runtime, folder, manifest, projection, service, crypto, keys)
        _write_exclusive(
            folder / "service-transaction.pending",
            "Original SERVICE transaction result must be reconciled; never replay this preparation.\n",
        )
        database_attempted = True
        _apply_service_transaction(root, runtime, folder, manifest, service, subject_hmac)
        _verify_service_fixture(root, runtime, folder, manifest, service)
        environment._atomic_json(folder / "service-result.json", {
            "profile": "R1_E2E_SERVICE_RESULT_V1", "state": "VERIFIED", "insertedRows": 5,
            "serviceSha256": _sha256(folder / "service.json"),
        })
        server_source = root / "e2e/runtime/r1_server.mjs"
        apps_manifest = {
            "profile": PROFILE, "run": run, "createdAt": environment.utc_now(),
            "bootstrapSource": bootstrap_source, "bootstrapInputSha256": bootstrap_digest,
            "environmentManifestSha256": _sha256(runtime / "manifest.json"),
            "serviceSha256": _sha256(folder / "service.json"),
            "serviceResultSha256": _sha256(folder / "service-result.json"),
            "requiredFiles": _required_hashes(folder),
            "serverSourcePath": "e2e/runtime/r1_server.mjs",
            "serverSourceSha256": _sha256(server_source),
            "ports": PORTS,
            "artifacts": manifest["artifacts"],
            "schemaManifestSha256": manifest["schemaManifestSha256"],
        }
        environment._atomic_json(folder / "manifest.json", apps_manifest)
        state.update(phase="PREPARED", updatedAt=environment.utc_now())
        environment._atomic_json(folder / "state.json", state)
        (folder / "service-transaction.pending").unlink()
        _protect_application_boundary(folder)
        return folder
    except Exception as error:
        state.update(
            phase="SERVICE_TRANSACTION_FAILED_OR_UNCERTAIN" if database_attempted else "PREPARE_FAILED",
            applicationReady=False, errorType=type(error).__name__, updatedAt=environment.utc_now(),
        )
        environment._atomic_json(folder / "state.json", state)
        raise


def _load_application(root: Path, run: str, phases: tuple[str, ...]) -> tuple[Path, Path, dict, dict, dict, dict]:
    root = Path(root).resolve(strict=True)
    runtime = environment._runtime(root, run)
    folder = runtime / "applications"
    if not folder.is_dir() or environment._is_link(folder):
        raise RuntimeError("prepared applications unavailable")
    state = _read_json(folder / "state.json", "application state")
    apps = _read_json(folder / "manifest.json", "application manifest")
    if state.get("profile") != STATE_PROFILE or state.get("run") != run or state.get("phase") not in phases:
        raise RuntimeError(f"applications not consumable from phase {state.get('phase')}")
    if apps.get("profile") != PROFILE or apps.get("run") != run or apps.get("bootstrapSource") != state.get("bootstrapSource"):
        raise RuntimeError("application manifest identity mismatch")
    source = apps.get("bootstrapSource")
    projection = _validate_projection(runtime, run, _verify_bootstrap_source(root, run, source))
    digest = _digest_value(projection)
    if digest != apps.get("bootstrapInputSha256") or digest != state.get("bootstrapInputSha256"):
        raise RuntimeError("bootstrap input digest drift")
    manifest = _environment_manifest(runtime, run)
    service = _read_json(folder / "service.json", "SERVICE inventory")
    _assert_application_unchanged(root, runtime, folder, manifest, apps, service)
    _protect_application_boundary(folder)
    return runtime, folder, state, apps, manifest, service


def _assert_application_unchanged(root: Path, runtime: Path, folder: Path, environment_manifest: dict,
                                  apps: dict, service: dict) -> None:
    if apps.get("environmentManifestSha256") != _sha256(runtime / "manifest.json") or \
       apps.get("schemaManifestSha256") != environment_manifest.get("schemaManifestSha256") or \
       apps.get("artifacts") != environment_manifest.get("artifacts") or \
       apps.get("serviceSha256") != _sha256(folder / "service.json") or \
       apps.get("serviceResultSha256") != _sha256(folder / "service-result.json") or \
       apps.get("serverSourcePath") != "e2e/runtime/r1_server.mjs" or \
       apps.get("serverSourceSha256") != _sha256(root / "e2e/runtime/r1_server.mjs") or \
       apps.get("ports") != PORTS:
        raise RuntimeError("prepared application manifest drift")
    required = apps.get("requiredFiles")
    if not isinstance(required, dict) or set(required) != set(_required_hashes(folder)):
        raise RuntimeError("prepared application file inventory mismatch")
    for relative, digest in required.items():
        path = folder / relative
        if not path.is_file() or environment._is_link(path) or not HASH_PATTERN.fullmatch(str(digest)) or _sha256(path) != digest:
            raise RuntimeError(f"prepared application file drift: {relative}")
    jar = runtime / environment_manifest["artifacts"]["jar"]["path"]
    if _sha256(jar) != environment_manifest["artifacts"]["jar"]["sha256"]:
        raise RuntimeError("prepared Jar drift")
    spa = runtime / environment_manifest["artifacts"]["spa"]["path"]
    tree, files = environment.tree_digest(spa)
    if tree != environment_manifest["artifacts"]["spa"]["sha256"] or files != environment_manifest["artifacts"]["spa"]["files"]:
        raise RuntimeError("prepared SPA drift")
    if service.get("bootstrapInputSha256") != apps.get("bootstrapInputSha256"):
        raise RuntimeError("SERVICE/bootstrap binding drift")


def _application_commands(root: Path, runtime: Path, folder: Path) -> dict[str, list[str]]:
    environment_manifest = _read_json(runtime / "manifest.json", "environment manifest")
    java = environment_manifest["hostTools"]["java"]["path"]
    node = environment_manifest["hostTools"]["node"]["path"]
    jar = str((runtime / environment_manifest["artifacts"]["jar"]["path"]).resolve())
    base = [java, "-Xmx384m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", jar]
    return {
        "api": [*base, "--spring.config.location=" + (folder / "api.properties").resolve().as_uri(), "--ols.runtime-role=api"],
        "worker": [*base, "--spring.config.location=" + (folder / "worker.properties").resolve().as_uri(),
                   "--ols.runtime-role=worker", "--spring.main.web-application-type=none"],
        "spa": [node, str((root / "e2e/runtime/r1_server.mjs").resolve()), str(runtime.resolve()),
                str(folder.resolve())],
    }


def _process_snapshot(pid: int) -> dict | None:
    if not isinstance(pid, int) or pid < 1:
        raise RuntimeError("invalid process PID")
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except OSError:
            return None
        return {"pid": pid}
    script = (
        "$ErrorActionPreference='Stop';$p=Get-CimInstance Win32_Process -Filter \"ProcessId='" + str(pid) + "'\";"
        "if($null -eq $p){'null'}else{@{pid=[int]$p.ProcessId;executable=$p.ExecutablePath;"
        "commandLine=$p.CommandLine;created=$p.CreationDate.ToUniversalTime().ToString('o')}|ConvertTo-Json -Compress}"
    )
    completed = subprocess.run(
        ["pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if completed.returncode or completed.stderr.strip():
        raise RuntimeError("process identity inspection unavailable")
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("process identity inspection unavailable") from error
    return value


def _launch_process(name: str, command: list[str], root: Path, folder: Path) -> dict:
    began = environment.utc_now()
    safe_environment = {
        key: value for key, value in os.environ.items()
        if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH", "COMSPEC", "PATHEXT"}
    }
    with (folder / f"{name}.stdout").open("xb") as stdout, (folder / f"{name}.stderr").open("xb") as stderr:
        try:
            child = subprocess.Popen(
                command, cwd=root, env=safe_environment, stdout=stdout, stderr=stderr,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        except OSError as error:
            raise RuntimeError(f"{name} launch result unknown") from error
    launch = {"pid": child.pid, "startedAt": began, "command": command}
    environment._atomic_json(folder / f"{name}-launch.json", launch)
    actual = _process_snapshot(child.pid)
    if actual is None:
        raise RuntimeError(f"{name} exited during startup")
    if os.name == "nt" and (actual.get("pid") != child.pid or not actual.get("created") or
                            not actual.get("executable") or not actual.get("commandLine")):
        raise RuntimeError(f"{name} process identity unavailable")
    launch.update(actual)
    launch.setdefault("executable", command[0])
    launch.setdefault("commandLine", " ".join(command))
    environment._atomic_json(folder / f"{name}-launch.json", launch)
    return launch


def _wait_for_api(runtime: Path, timeout_seconds: float = 120.0) -> None:
    context = ssl.create_default_context(cafile=str(runtime / "certs/ca.pem"))
    deadline = time.monotonic() + timeout_seconds
    last: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with _urlopen_no_redirect(
                urllib.request.Request("https://localhost:29445/api/v1/session/context", method="GET"),
                context, 5,
            ):
                pass
        except urllib.error.HTTPError as error:
            if error.code == 401:
                error.close()
                return
            last = error
        except Exception as error:
            last = error
        time.sleep(1)
    raise RuntimeError("API unavailable; Worker and SPA were not started") from last


def _ports_available() -> None:
    for port in PORTS.values():
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"fixed application port occupied: {port}") from None


def start_applications(root: Path, run: str) -> None:
    root = Path(root).resolve(strict=True)
    runtime, folder, state, _, manifest, service = _load_application(root, run, ("PREPARED",))
    if state.get("processes") != {} or (folder / "start.pending").exists() or any(
        (folder / f"{name}-launch.json").exists() for name in ("api", "worker", "spa")
    ):
        raise RuntimeError("unexpected prior application launch evidence")
    _verify_service_fixture(root, runtime, folder, manifest, service)
    _ports_available()
    commands = _application_commands(root, runtime, folder)
    _write_exclusive(folder / "start.pending", "Retain original partial launch evidence; never restart automatically.\n")
    state.update(phase="STARTING", applicationReady=False, updatedAt=environment.utc_now())
    environment._atomic_json(folder / "state.json", state)
    try:
        state["processes"]["api"] = _launch_process("api", commands["api"], root, folder)
        environment._atomic_json(folder / "state.json", state)
        _wait_for_api(runtime)
        state["processes"]["worker"] = _launch_process("worker", commands["worker"], root, folder)
        environment._atomic_json(folder / "state.json", state)
        state["processes"]["spa"] = _launch_process("spa", commands["spa"], root, folder)
        environment._atomic_json(folder / "state.json", state)
        state.update(phase="STARTED_UNVERIFIED", updatedAt=environment.utc_now())
        environment._atomic_json(folder / "state.json", state)
        (folder / "start.pending").unlink()
    except Exception as error:
        state.update(phase="START_FAILED_OR_UNCERTAIN", applicationReady=False,
                     errorType=type(error).__name__, updatedAt=environment.utc_now())
        environment._atomic_json(folder / "state.json", state)
        raise


def _same_process(expected: dict, actual: dict | None) -> bool:
    if actual is None or actual.get("pid") != expected.get("pid"):
        return False
    for key in ("created", "executable", "commandLine"):
        if key in expected and actual.get(key) != expected.get(key):
            return False
    try:
        started = datetime.fromisoformat(expected["startedAt"].replace("Z", "+00:00"))
        created = datetime.fromisoformat(expected["created"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError):
        return False
    return started.tzinfo is not None and created.tzinfo is not None and started <= created and (created - started).total_seconds() <= 30


def _ready_log(path: Path, pid: int, created: str, component: str) -> bool:
    try:
        created_at = datetime.fromisoformat(created.replace("Z", "+00:00"))
        text = path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    logger = "api.ApiRuntimeHealth" if component == "API" else "worker.WorkerRuntimeHealth"
    pattern = re.compile(
        r"^(\S+) INFO\s+" + str(pid) + r" --- \[[^\r\n]+\] io\.github\.windyzhu3\.ontologylaw\." +
        re.escape(logger) + r" : R1_" + component + r"_(ASSEMBLY_ISOLATED|READY|UNAVAILABLE)$"
    )
    isolated = ready = False
    for line in text.splitlines():
        match = pattern.fullmatch(line)
        if not match:
            continue
        try:
            at = datetime.fromisoformat(match.group(1).replace("Z", "+00:00"))
        except ValueError:
            return False
        if at.tzinfo is None or at < created_at:
            continue
        if match.group(2) == "ASSEMBLY_ISOLATED":
            isolated, ready = True, False
        else:
            ready = isolated and match.group(2) == "READY"
    return ready


def _listener_count(pid: int) -> int:
    if os.name != "nt":
        raise RuntimeError("Worker listener verification requires the pinned Windows host")
    script = (
        "$ErrorActionPreference='Stop';$tcp=@(Get-NetTCPConnection -State Listen | Where-Object OwningProcess -eq " + str(pid) + ");"
        "$udp=@(Get-NetUDPEndpoint | Where-Object OwningProcess -eq " + str(pid) + ");$tcp.Count+$udp.Count"
    )
    result = subprocess.run(["pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                            capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode or not result.stdout.strip().isdigit():
        raise RuntimeError("Worker listener evidence unavailable")
    return int(result.stdout.strip())


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _urlopen_no_redirect(request: urllib.request.Request, context: ssl.SSLContext, timeout: float):
    opener = urllib.request.build_opener(_NoRedirect(), urllib.request.HTTPSHandler(context=context))
    return opener.open(request, timeout=timeout)


def _http(runtime: Path, url: str, *, service: bool = False) -> tuple[int, dict, bytes]:
    context = ssl.create_default_context(cafile=str(runtime / "certs/ca.pem"))
    if service:
        context.load_cert_chain(
            str(runtime / "applications/certs/service.crt"),
            str(runtime / "applications/certs/service.key"),
        )
    request = urllib.request.Request(url, method="GET")
    try:
        with _urlopen_no_redirect(request, context, 10) as response:
            return response.status, dict(response.headers.items()), response.read()
    except urllib.error.HTTPError as error:
        try:
            return error.code, dict(error.headers.items()), error.read()
        finally:
            error.close()


def _probe_readiness(runtime: Path) -> dict:
    spa_status, _, _ = _http(runtime, "https://localhost:29444/")
    self_status, _, _ = _http(runtime, "https://localhost:29444/api/v1/session/context")
    service_status, headers, body = _http(
        runtime, "https://localhost:29445/internal/v1/projections/r1/readiness", service=True,
    )
    cache_values = [value.strip().lower() for value in headers.get("Cache-Control", "").split(",")]
    if spa_status != 200 or self_status != 401 or service_status != 204 or body != b"" or \
       any(name.lower() == "etag" for name in headers) or "no-store" not in cache_values:
        raise RuntimeError("strict application readiness boundary unavailable")
    return {"spa": 200, "unauthenticatedSelf": 401, "serviceReadiness": 204,
            "serviceEmptyBody": True, "serviceNoEtag": True, "serviceNoStore": True}


def verify_applications(root: Path, run: str) -> dict:
    root = Path(root).resolve(strict=True)
    runtime, folder, state, _, manifest, service = _load_application(
        root, run, ("STARTED_UNVERIFIED", "APPLICATION_INFRASTRUCTURE_READY"),
    )
    _verify_service_fixture(root, runtime, folder, manifest, service)
    processes = state.get("processes")
    if not isinstance(processes, dict) or set(processes) != {"api", "worker", "spa"}:
        raise RuntimeError("application process inventory mismatch")
    for name, expected in processes.items():
        actual = _process_snapshot(expected.get("pid"))
        if not _same_process(expected, actual):
            raise RuntimeError(f"{name} process identity mismatch")
    if not _ready_log(folder / "api.stdout", processes["api"]["pid"], processes["api"]["created"], "API"):
        raise RuntimeError("current API isolation/readiness log unavailable")
    if not _ready_log(folder / "worker.stdout", processes["worker"]["pid"], processes["worker"]["created"], "WORKER"):
        raise RuntimeError("current Worker isolation/readiness log unavailable")
    spa_log = (folder / "spa.stdout").read_text(encoding="utf-8", errors="strict")
    if f"R1_SPA_READY pid={processes['spa']['pid']} host=127.0.0.1 port=29444" not in spa_log.splitlines():
        raise RuntimeError("current SPA readiness log unavailable")
    if _listener_count(processes["worker"]["pid"]) != 0:
        raise RuntimeError("Worker has an unexpected listener")
    probes = _probe_readiness(runtime)
    for name, expected in processes.items():
        if not _same_process(expected, _process_snapshot(expected["pid"])):
            raise RuntimeError(f"{name} process identity changed during readiness")
    result = {
        "profile": "R1_E2E_APPLICATION_READINESS_V1", "run": run,
        "phase": "APPLICATION_INFRASTRUCTURE_READY", "bootstrapSource": state["bootstrapSource"],
        "applicationReady": True, "processes": {name: {"pid": item["pid"], "created": item["created"]}
                                               for name, item in processes.items()},
        "probes": probes, "claims": ["APPLICATION_INFRASTRUCTURE_READY"],
    }
    if state["phase"] != "APPLICATION_INFRASTRUCTURE_READY":
        state.update(phase="APPLICATION_INFRASTRUCTURE_READY", applicationReady=True,
                     readiness=result, updatedAt=environment.utc_now())
        _protect_application_boundary(folder)
        environment._atomic_json(folder / "state.json", state)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    commands = parser.add_subparsers(dest="operation", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("run")
    prepare.add_argument("--bootstrap-source", choices=BOOTSTRAP_SOURCES, required=True)
    for operation in ("start", "verify"):
        command = commands.add_parser(operation)
        command.add_argument("run")
    args = parser.parse_args(argv)
    try:
        environment.validate_run_id(args.run)
        if args.operation == "prepare":
            folder = prepare_applications(args.root, args.run, bootstrap_source=args.bootstrap_source)
            print(f"APPLICATIONS_PREPARED {folder}")
        elif args.operation == "start":
            start_applications(args.root, args.run)
            print("APPLICATIONS_STARTED; readiness remains unverified")
        else:
            verify_applications(args.root, args.run)
            print("APPLICATION_INFRASTRUCTURE_READY")
        return 0
    except (RuntimeError, ValueError, OSError) as error:
        print(f"R1_APPLICATIONS_FAILED_OR_UNCERTAIN: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
