"""Prepare and start the bounded, isolated R1 end-to-end environment.

Preparation creates a new protected run directory and immutable inputs. It
never consumes the Task 9 runtime. Infrastructure startup is a separate,
explicit operation; application startup remains fail-closed until the later
Task 10 identity/bootstrap consumer exists.
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Mapping


ROOT = Path(__file__).resolve().parents[2]
WINDOWS_TOOLCHAIN_ROOT = Path("C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb")
WINDOWS_OPENSSL = Path("C:/Program Files/Git/usr/bin/openssl.exe")
RUN_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,31}")
PORTS = {"keycloak": 29443, "spa": 29444, "api": 29445, "businessDatabase": 29446}
REALM = "r1-e2e"
ISSUER = "https://localhost:29443/realms/r1-e2e"
SPA_ORIGIN = "https://localhost:29444"
API_ORIGIN = "https://localhost:29445"
BOUNDED_CONCURRENT_LIMIT_BYTES = 3 * 1024 * 1024 * 1024
SECRET_NAMES = (
    "identity-db-password", "business-superuser-password", "migrator-password",
    "api-db-password", "worker-db-password", "introspection-secret", "directory-secret",
    "founder-password", "sales-password", "supervisor-password", "sourceOwner-password",
    "revokedAppointment-password", "trust-password", "tenant-subject-hmac",
    "actor-scope-hmac", "bootstrap-key", "online-candidate-key", "etag-key",
    "api-cursor-key", "admin-cursor-key", "offline-key", "source-key",
)
REQUIRED_RUNTIME_FILES = (
    *(f"secrets/{name}.txt" for name in SECRET_NAMES),
    "certs/ca.key", "certs/ca.pem",
    "certs/identity-db.key", "certs/identity-db.crt",
    "certs/business-db.key", "certs/business-db.crt",
    "certs/keycloak.key", "certs/keycloak.crt",
    "certs/host.key", "certs/host.crt",
    "certs/application-trust.p12", "certs/application-server.p12",
    "keycloak/r1-e2e-realm.json", "deployment-state.sql", "compose.env",
)
SOURCE_PATHS = (
    "e2e/compose.yaml", "e2e/fixtures/r1-fixture.json",
    "e2e/runtime/postgres-entrypoint.sh", "e2e/runtime/init-business-db.sh",
    "e2e/runtime/prepare-keycloak-files.sh", "e2e/runtime/flyway-entrypoint.sh",
    "e2e/runtime/provision-runtime-logins.sh", "deploy/identity/realm-template.json",
    "e2e/runtime/r1_environment.py",
    "deploy/identity/identity-toolchain.lock.json",
    "database/schema-contract-52-plus-2/runtime/toolchain.lock.json",
    "database/schema-contract-52-plus-2/generated/schema-contract-manifest.json",
    "backend/src/test/resources/db/bootstrap-runtime-logins.sql",
)
CommandRunner = Callable[..., subprocess.CompletedProcess]


@dataclass(frozen=True)
class HostToolchain:
    platform: str
    runtime_root: Path
    java: Path
    keytool: Path
    node: Path
    openssl: Path


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_digest(path: Path) -> tuple[str, dict[str, str]]:
    files: dict[str, str] = {}
    for candidate in sorted(path.rglob("*")):
        if _is_link(candidate):
            raise RuntimeError(f"linked artifact rejected: {candidate}")
        if candidate.is_file():
            files[candidate.relative_to(path).as_posix()] = sha256_file(candidate)
    if not files:
        raise RuntimeError(f"empty artifact directory: {path}")
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest(), files


def validate_run_id(value: str) -> str:
    if not RUN_ID.fullmatch(value):
        raise ValueError("invalid run id")
    return value


def _is_link(path: Path) -> bool:
    junction = getattr(path, "is_junction", None)
    return path.is_symlink() or bool(junction and junction())


def _plain_file(path: Path, label: str) -> Path:
    if not path.is_file() or _is_link(path):
        raise RuntimeError(f"{label} executable missing or linked")
    return path


def resolve_host_toolchain(
    environment: Mapping[str, str] | None = None, *, platform: str | None = None,
) -> HostToolchain:
    environment = os.environ if environment is None else environment
    platform = sys.platform if platform is None else platform
    configured_root = environment.get("R1_E2E_TOOLCHAIN_ROOT")
    if platform == "win32":
        runtime_root = Path(configured_root) if configured_root else WINDOWS_TOOLCHAIN_ROOT
        java_name, keytool_name = "java.exe", "keytool.exe"
        node_relative = Path("node-v24.20.0-win-x64/node.exe")
        configured_openssl = environment.get("R1_E2E_OPENSSL")
        openssl = Path(configured_openssl) if configured_openssl else WINDOWS_OPENSSL
        platform_name = "windows-x64"
    elif platform.startswith("linux"):
        if not configured_root or not Path(configured_root).is_absolute():
            raise RuntimeError("Linux requires an explicit absolute pinned runtime root")
        runtime_root = Path(configured_root)
        java_name, keytool_name = "java", "keytool"
        node_relative = Path("node-v24.20.0-linux-x64/bin/node")
        openssl = runtime_root / "openssl-3.5.4/bin/openssl"
        platform_name = "linux-x64"
    else:
        raise RuntimeError(f"unsupported R1 E2E host platform: {platform}")
    if not runtime_root.is_absolute():
        raise RuntimeError("toolchain requires an explicit absolute pinned runtime root")
    if not runtime_root.is_dir() or _is_link(runtime_root):
        raise RuntimeError("pinned runtime root missing or linked")
    if not openssl.is_absolute():
        raise RuntimeError("OpenSSL path must be absolute")
    java_bin = runtime_root / "jdk-25.0.4.1+1/bin"
    return HostToolchain(
        platform_name, runtime_root,
        _plain_file(java_bin / java_name, "java"),
        _plain_file(java_bin / keytool_name, "keytool"),
        _plain_file(runtime_root / node_relative, "node"),
        _plain_file(openssl, "openssl"),
    )


def validate_java_version(output: str) -> str:
    version = re.search(r'(?m)^openjdk version "([^"]+)"(?:\s|$)', output)
    runtime_build = re.search(r'(?m)^OpenJDK Runtime Environment .*\(build ([^)]+)\)$', output)
    if not version or version.group(1) != "25.0.4.1" or not runtime_build or runtime_build.group(1) != "25.0.4.1+1-LTS":
        raise RuntimeError("exact Java build mismatch; require 25.0.4.1+1-LTS")
    return "25.0.4.1+1-LTS"


def _runtime(root: Path, run: str, *, may_not_exist: bool = False) -> Path:
    validate_run_id(run)
    root = root.resolve(strict=True)
    base = root / ".artifacts" / "r1-e2e"
    target = base / run
    if target.parent != base:
        raise RuntimeError("run path escaped the owned directory")
    for item in (root, root / ".artifacts", base, target):
        if item.exists() and _is_link(item):
            raise RuntimeError(f"linked path rejected: {item}")
    if not may_not_exist and not target.is_dir():
        raise RuntimeError("prepared run does not exist")
    return target


def _protect_directory(path: Path) -> None:
    if os.name == "nt":
        sid = _current_windows_sid()
        acl = subprocess.run(
            ["icacls", str(path), "/inheritance:r", "/grant:r", f"*{sid}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"],
            capture_output=True, text=True,
        )
        if acl.returncode:
            raise RuntimeError("cannot protect run directory ACL")
    else:
        path.chmod(0o700)


def _current_windows_sid() -> str:
    result = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("cannot resolve current Windows SID")
    row = next(csv.reader([result.stdout.strip()]), [])
    if len(row) < 2 or not row[1].startswith("S-"):
        raise RuntimeError("cannot resolve current Windows SID")
    return row[1]


def _mkdir_secure(path: Path) -> None:
    path.mkdir()
    if os.name != "nt":
        path.chmod(0o700)


def _verify_private_boundary(runtime: Path) -> None:
    if os.name == "nt":
        environment = os.environ.copy()
        environment["R1_E2E_ACL_PATH"] = str(runtime)
        environment["R1_E2E_ALLOWED_SID"] = _current_windows_sid()
        script = (
            "$root=Get-Item -Force -LiteralPath $env:R1_E2E_ACL_PATH;"
            "$items=@($root)+@(Get-ChildItem -Force -Recurse -LiteralPath $root.FullName);"
            "$allowed=@($env:R1_E2E_ALLOWED_SID,'S-1-5-18');$bad=@();"
            "foreach($item in $items){$a=Get-Acl -LiteralPath $item.FullName;"
            "$ids=@($a.Access|ForEach-Object{$_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value});"
            "if($ids.Count -eq 0 -or @($ids|Where-Object{$_ -notin $allowed}).Count -ne 0){$bad+=$item.FullName}};"
            "$rootAcl=Get-Acl -LiteralPath $root.FullName;"
            "@{protected=$rootAcl.AreAccessRulesProtected;bad=$bad}|ConvertTo-Json -Compress"
        )
        completed = subprocess.run(
            ["pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, text=True, env=environment,
        )
        if completed.returncode or (completed.stderr or "").strip():
            raise RuntimeError("cannot verify protected run directory ACL")
        acl = json.loads(completed.stdout)
        failures = acl.get("bad", [])
        if isinstance(failures, str):
            failures = [failures]
        if not acl.get("protected") or failures:
            raise RuntimeError("run directory ACL permits an unauthorized principal")
        return
    for path in [runtime, *runtime.rglob("*")]:
        expected = 0o700 if path.is_dir() else 0o600
        if path.stat().st_mode & 0o777 != expected:
            raise RuntimeError(f"private mode mismatch: {path}")


def _protect_file(path: Path) -> None:
    if os.name != "nt":
        path.chmod(0o600)


def _write(path: Path, value: str) -> None:
    temporary = path.with_name(path.name + ".new-" + secrets.token_hex(6))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        _protect_file(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _atomic_json(path: Path, value: object) -> None:
    _write(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def _record(state_path: Path, state: dict, phase: str, **extra: object) -> None:
    state.update(phase=phase, updatedAt=utc_now(), **extra)
    state.setdefault("events", []).append({"phase": phase, "at": state["updatedAt"]})
    _atomic_json(state_path, state)


def _run(command: list[str], root: Path, runtime: Path, label: str,
         command_runner: CommandRunner = subprocess.run) -> subprocess.CompletedProcess:
    try:
        completed = command_runner(command, cwd=root, capture_output=True, text=True)
    except OSError as error:
        raise RuntimeError(f"{label} could not execute") from error
    logs = runtime / "logs"
    if not logs.exists():
        _mkdir_secure(logs)
    _write(logs / f"{label}.stdout", completed.stdout or "")
    _write(logs / f"{label}.stderr", completed.stderr or "")
    journal = runtime / "command-stages.jsonl"
    descriptor = os.open(journal, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps({"label": label, "exit": completed.returncode, "at": utc_now()}, sort_keys=True) + "\n")
        output.flush()
        os.fsync(output.fileno())
    _protect_file(journal)
    return completed


def _require_sources(root: Path) -> None:
    for relative in SOURCE_PATHS:
        path = root / relative
        if not path.is_file() or _is_link(path):
            raise RuntimeError(f"required source missing or linked: {relative}")
    tree_digest(root / "database/schema-contract-52-plus-2/generated/db/migration")
    identity = json.loads((root / "deploy/identity/identity-toolchain.lock.json").read_text(encoding="utf-8"))
    database = json.loads((root / "database/schema-contract-52-plus-2/runtime/toolchain.lock.json").read_text(encoding="utf-8"))
    images = {entry["image"]: entry["digest"] for entry in database["images"]}
    if (
        identity["keycloak"]["version"] != "26.7.3"
        or identity["keycloak"]["platformDigest"] != "sha256:88943b6ad06d6293a239f0dfca5acec64218c9b3ab327bf9c936acf408a6ae3b"
        or identity["identityDatabase"]["majorVersion"] != "18"
        or identity["identityDatabase"]["digest"] != "sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"
        or images.get("postgres") != identity["identityDatabase"]["digest"]
        or images.get("redgate/flyway") != "sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93"
    ):
        raise RuntimeError("locked infrastructure version mismatch")


def _check_host_tools(root: Path, runtime: Path, toolchain: HostToolchain) -> dict[str, object]:
    for label, executable in (
        ("java", toolchain.java), ("keytool", toolchain.keytool),
        ("node", toolchain.node), ("openssl", toolchain.openssl),
    ):
        _plain_file(executable, label)
    java = _run([str(toolchain.java), "-version"], root, runtime, "java-version")
    keytool = _run([str(toolchain.keytool), "-J-version"], root, runtime, "keytool-java-version")
    node = _run([str(toolchain.node), "--version"], root, runtime, "node-version")
    openssl = _run([str(toolchain.openssl), "version"], root, runtime, "openssl-version")
    if java.returncode:
        raise RuntimeError("fixed Java executable failed")
    java_build = validate_java_version((java.stderr or "") + (java.stdout or ""))
    if keytool.returncode:
        raise RuntimeError("fixed keytool executable failed")
    keytool_build = validate_java_version((keytool.stderr or "") + (keytool.stdout or ""))
    if node.returncode or (node.stdout or "").strip() != "v24.20.0":
        raise RuntimeError("fixed Node version mismatch")
    openssl_version = (openssl.stdout or "").strip()
    if openssl.returncode or not openssl_version.startswith("OpenSSL 3.5.4 "):
        raise RuntimeError("fixed OpenSSL version mismatch")
    return {
        "platform": toolchain.platform, "runtimeRoot": str(toolchain.runtime_root),
        "java": {"path": str(toolchain.java), "build": java_build},
        "keytool": {"path": str(toolchain.keytool), "javaBuild": keytool_build},
        "node": {"path": str(toolchain.node), "version": "24.20.0"},
        "openssl": {"path": str(toolchain.openssl), "version": openssl_version},
    }


def _create_secrets(runtime: Path) -> dict[str, str]:
    folder = runtime / "secrets"
    _mkdir_secure(folder)
    values = {name: base64.b64encode(secrets.token_bytes(32)).decode("ascii") for name in SECRET_NAMES}
    if len(set(values.values())) != len(values):
        raise RuntimeError("generated secret collision")
    for name, value in values.items():
        _write(folder / f"{name}.txt", value + "\n")
    return values


def _openssl(root: Path, runtime: Path, toolchain: HostToolchain, label: str, arguments: list[str]) -> None:
    completed = _run([str(toolchain.openssl), *arguments], root, runtime, label)
    if completed.returncode:
        raise RuntimeError(f"{label} failed; inspect protected diagnostics")


def _create_crypto(root: Path, runtime: Path, toolchain: HostToolchain) -> None:
    certs = runtime / "certs"
    _mkdir_secure(certs)
    ca_key, ca_cert = certs / "ca.key", certs / "ca.pem"
    _openssl(root, runtime, toolchain, "openssl-ca", [
        "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes", "-days", "14",
        "-subj", "/CN=R1 E2E isolated CA", "-keyout", str(ca_key), "-out", str(ca_cert),
        "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0", "-addext", "keyUsage=critical,keyCertSign,cRLSign",
    ])
    certificates = {
        "identity-db": "subjectAltName=DNS:identity-db\nextendedKeyUsage=serverAuth\n",
        "business-db": "subjectAltName=DNS:business-db,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n",
        "keycloak": "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n",
        "host": "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n",
    }
    for serial, (name, extensions) in enumerate(certificates.items(), start=1000):
        key, request, certificate, extension = (
            certs / f"{name}.key", certs / f"{name}.csr", certs / f"{name}.crt", certs / f"{name}.ext"
        )
        _write(extension, "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n" + extensions)
        _openssl(root, runtime, toolchain, f"openssl-{name}-request", [
            "req", "-new", "-newkey", "rsa:3072", "-nodes", "-sha256", "-subj", f"/CN={name}",
            "-keyout", str(key), "-out", str(request),
        ])
        _openssl(root, runtime, toolchain, f"openssl-{name}-certificate", [
            "x509", "-req", "-sha256", "-days", "14", "-in", str(request), "-CA", str(ca_cert),
            "-CAkey", str(ca_key), "-set_serial", str(serial), "-extfile", str(extension), "-out", str(certificate),
        ])
        request.unlink()
        extension.unlink()
    password_file = runtime / "secrets/trust-password.txt"
    truststore = certs / "application-trust.p12"
    keytool = _run([
        str(toolchain.keytool), "-importcert", "-noprompt", "-alias", "r1-e2e-ca", "-file", str(ca_cert),
        "-keystore", str(truststore), "-storetype", "PKCS12", "-storepass:file", str(password_file),
    ], root, runtime, "keytool-truststore")
    if keytool.returncode:
        raise RuntimeError("keytool truststore failed; inspect protected diagnostics")
    _openssl(root, runtime, toolchain, "openssl-host-keystore", [
        "pkcs12", "-export", "-name", "r1-e2e-host", "-inkey", str(certs / "host.key"),
        "-in", str(certs / "host.crt"), "-certfile", str(ca_cert), "-out", str(certs / "application-server.p12"),
        "-passout", f"file:{password_file}",
    ])
    for file in certs.iterdir():
        _protect_file(file)


def _realm(root: Path, run: str, runtime: Path, values: dict[str, str]) -> None:
    template = (root / "deploy/identity/realm-template.json").read_text(encoding="utf-8")
    replacements = {
        "IDENTITY_REALM": REALM, "SPA_CLIENT_ID": "r1-e2e-spa",
        "SPA_REDIRECT_URI": SPA_ORIGIN + "/auth/callback", "SPA_ORIGIN": SPA_ORIGIN,
        "SPA_LOGOUT_REDIRECT_URI": SPA_ORIGIN + "/login", "API_AUDIENCE": "r1-e2e-api",
        "DIRECTORY_CLIENT_ID": "r1-e2e-directory",
    }
    for name, value in replacements.items():
        template = template.replace("${" + name + "}", value)
    if "${" in template:
        raise RuntimeError("unresolved realm placeholder")
    result = json.loads(template)
    result["clients"][1]["secret"] = values["introspection-secret"]
    result["clients"][2]["secret"] = values["directory-secret"]
    fixture = json.loads((root / "e2e/fixtures/r1-fixture.json").read_text(encoding="utf-8"))
    result["users"] = []
    for account in fixture["accounts"]:
        username = f"r1-{run}-{account['usernameStem']}"
        result["users"].append({
            "username": username, "enabled": True, "emailVerified": True,
            "firstName": "Synthetic", "lastName": account["displayName"],
            "email": username + "@example.invalid",
            "credentials": [{"type": "password", "value": values[account["key"] + "-password"], "temporary": False}],
        })
    result["users"].append({
        "username": "service-account-r1-e2e-directory", "enabled": True,
        "serviceAccountClientId": "r1-e2e-directory",
        "clientRoles": {"realm-management": ["query-users", "view-users"]},
    })
    folder = runtime / "keycloak"
    _mkdir_secure(folder)
    _atomic_json(folder / "r1-e2e-realm.json", result)


def _copy_artifacts(root: Path, runtime: Path) -> tuple[dict, dict]:
    source_jar = root / "backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar"
    source_spa = root / "apps/workbench/dist"
    if not source_jar.is_file() or _is_link(source_jar):
        raise RuntimeError("single backend Jar is missing or linked")
    source_spa_digest, source_spa_files = tree_digest(source_spa)
    artifacts = runtime / "artifacts"
    _mkdir_secure(artifacts)
    target_jar = artifacts / "ontology-law-system-0.1.0-SNAPSHOT.jar"
    shutil.copyfile(source_jar, target_jar)
    target_spa = artifacts / "workbench-dist"
    shutil.copytree(source_spa, target_spa, copy_function=shutil.copyfile)
    if os.name != "nt":
        for folder in [target_spa, *(path for path in target_spa.rglob("*") if path.is_dir())]:
            folder.chmod(0o700)
    jar_digest = sha256_file(target_jar)
    copied_spa_digest, copied_spa_files = tree_digest(target_spa)
    if jar_digest != sha256_file(source_jar) or (copied_spa_digest, copied_spa_files) != (source_spa_digest, source_spa_files):
        raise RuntimeError("artifact snapshot mismatch")
    for file in artifacts.rglob("*"):
        if file.is_file():
            _protect_file(file)
    return (
        {"path": target_jar.relative_to(runtime).as_posix(), "sha256": jar_digest,
         "bytes": target_jar.stat().st_size, "provenance": "UNPROVEN_EXISTING_ARTIFACT"},
        {"path": target_spa.relative_to(runtime).as_posix(), "sha256": copied_spa_digest,
         "fileCount": len(copied_spa_files), "files": copied_spa_files,
         "provenance": "UNPROVEN_EXISTING_ARTIFACT"},
    )


def _source_digests(root: Path) -> dict[str, str]:
    result = {relative: sha256_file(root / relative) for relative in SOURCE_PATHS}
    migration_digest, _ = tree_digest(root / "database/schema-contract-52-plus-2/generated/db/migration")
    result["database/schema-contract-52-plus-2/generated/db/migration/"] = migration_digest
    return result


def _write_compose_environment(runtime: Path) -> None:
    _write(runtime / "compose.env", "R1_RUNTIME=" + runtime.resolve().as_posix() + "\n")


def _write_deployment_state(root: Path, runtime: Path, jar_digest: str) -> str:
    manifest_digest = sha256_file(root / "database/schema-contract-52-plus-2/generated/schema-contract-manifest.json")
    sql = (
        "DO $r1$ DECLARE changed integer; BEGIN\n"
        "UPDATE platform_meta.deployment_state SET operating_mode='ACTIVE', "
        f"active_release_digest=decode('{jar_digest}','hex'), "
        f"active_manifest_hash=decode('{manifest_digest}','hex'), "
        "schema_contract_version='52-plus-2-v1.2', revision=revision+1, changed_at=clock_timestamp() "
        "WHERE deployment_state_key='PRIMARY';\n"
        "GET DIAGNOSTICS changed = ROW_COUNT; IF changed <> 1 THEN RAISE EXCEPTION 'deployment state singleton missing'; END IF;\n"
        "END $r1$;\n"
    )
    _write(runtime / "deployment-state.sql", sql)
    return manifest_digest


def _validate_compose(root: Path, runtime: Path, project: str,
                      command_runner: CommandRunner = subprocess.run) -> None:
    completed = _run([
        "docker", "compose", "--env-file", str(runtime / "compose.env"), "-f", str(root / "e2e/compose.yaml"),
        "-p", project, "config", "--quiet",
    ], root, runtime, "compose-config", command_runner)
    if completed.returncode:
        raise RuntimeError("compose config failed; inspect protected diagnostics")


def _memory_feasibility() -> dict[str, object]:
    return {
        "profile": "R1_E2E_FUNCTIONAL_MEMORY_V1",
        "boundedConcurrentLimitBytes": BOUNDED_CONCURRENT_LIMIT_BYTES,
        "capacityAcceptance": False,
        "note": (
            "Configured Compose limits for a functional local run only; this is not reference capacity acceptance "
            "and does not prove a host pagefile root cause."
        ),
    }


def _git_snapshot(root: Path) -> dict[str, object]:
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    status = subprocess.run(
        ["git", "status", "--porcelain", "--", "backend", "apps/workbench", "contracts"],
        cwd=root, capture_output=True, text=True,
    )
    if head.returncode or status.returncode or not re.fullmatch(r"[0-9a-f]{40}\n?", head.stdout):
        raise RuntimeError("cannot record source git snapshot")
    return {"commit": head.stdout.strip(), "trackedProductSourcesClean": not bool(status.stdout.strip())}


def prepare_environment(
    root: Path, run: str, command_runner: CommandRunner = subprocess.run,
    *, toolchain: HostToolchain | None = None,
) -> Path:
    root = Path(root).resolve(strict=True)
    runtime = _runtime(root, run, may_not_exist=True)
    runtime.parent.mkdir(parents=True, exist_ok=True)
    if runtime.exists():
        raise RuntimeError("existing run; refuse overwrite or takeover")
    runtime.mkdir()
    _protect_directory(runtime)
    state_path = runtime / "state.json"
    state: dict = {
        "profile": "R1_E2E_RUN_STATE_V1", "run": run, "applicationReady": False,
        "applicationStatus": "BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED", "events": [],
    }
    stage = "boundary"
    _record(state_path, state, "PREPARING")
    try:
        _require_sources(root)
        stage = "tools"
        resolved_toolchain = toolchain or resolve_host_toolchain()
        versions = _check_host_tools(root, runtime, resolved_toolchain)
        stage = "secrets"
        values = _create_secrets(runtime)
        stage = "crypto"
        _create_crypto(root, runtime, resolved_toolchain)
        stage = "realm"
        _realm(root, run, runtime, values)
        stage = "artifacts"
        jar, spa = _copy_artifacts(root, runtime)
        schema_manifest_digest = _write_deployment_state(root, runtime, jar["sha256"])
        _write_compose_environment(runtime)
        stage = "compose-config"
        project = "ontology-law-r1-e2e-" + run
        _validate_compose(root, runtime, project, command_runner)
        memory_feasibility = _memory_feasibility()
        _verify_private_boundary(runtime)
        required = {relative: sha256_file(runtime / relative) for relative in REQUIRED_RUNTIME_FILES}
        manifest = {
            "profile": "R1_E2E_ENVIRONMENT_V1", "run": run, "createdAt": utc_now(),
            "composeProject": project, "ports": PORTS, "realm": REALM, "issuer": ISSUER,
            "spaOrigin": SPA_ORIGIN, "apiOrigin": API_ORIGIN, "composeConfigValidated": True,
            "sourceDigests": _source_digests(root), "requiredFiles": required,
            "artifacts": {"jar": jar, "spa": spa}, "schemaManifestSha256": schema_manifest_digest,
            "hostTools": versions,
            "memoryFeasibility": memory_feasibility,
            "sourceGitSnapshot": _git_snapshot(root),
            "secretBoundaryValidated": True,
            "artifactProvenanceNote": "Existing bytes were copied and hashed; this preparation did not prove how they were built.",
            "applicationAssembly": "BLOCKED_PENDING_IDENTITY_BOOTSTRAP_AND_LATER_TASK10_CONSUMER",
        }
        _atomic_json(runtime / "manifest.json", manifest)
        _record(state_path, state, "PREPARED", failedStage=None)
        return runtime
    except Exception as error:
        _record(state_path, state, "FAILED", failedStage=stage, errorType=type(error).__name__)
        if isinstance(error, RuntimeError):
            raise
        raise RuntimeError(f"preparation failed at {stage}; protected run retained") from error


def _load_prepared(root: Path, run: str) -> tuple[Path, dict, dict]:
    runtime = _runtime(root, run)
    state = json.loads((runtime / "state.json").read_text(encoding="utf-8"))
    manifest = json.loads((runtime / "manifest.json").read_text(encoding="utf-8"))
    if state.get("phase") != "PREPARED":
        raise RuntimeError(f"run is not startable from phase {state.get('phase')}")
    if manifest.get("profile") != "R1_E2E_ENVIRONMENT_V1" or manifest.get("run") != run:
        raise RuntimeError("run manifest identity mismatch")
    if manifest.get("composeProject") != "ontology-law-r1-e2e-" + run:
        raise RuntimeError("compose project mismatch")
    return runtime, state, manifest


def _assert_unchanged(root: Path, runtime: Path, manifest: dict) -> None:
    if _source_digests(root) != manifest.get("sourceDigests"):
        raise RuntimeError("required source digest drift")
    required = manifest.get("requiredFiles")
    if not isinstance(required, dict) or set(required) != set(REQUIRED_RUNTIME_FILES):
        raise RuntimeError("required file manifest mismatch")
    for relative in REQUIRED_RUNTIME_FILES:
        expected = required[relative]
        path = runtime / relative
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise RuntimeError("required file manifest mismatch")
        if not path.is_file() or _is_link(path) or sha256_file(path) != expected:
            raise RuntimeError(f"prepared file drift: {relative}")
    jar = runtime / manifest["artifacts"]["jar"]["path"]
    if sha256_file(jar) != manifest["artifacts"]["jar"]["sha256"]:
        raise RuntimeError("prepared Jar drift")
    spa = runtime / manifest["artifacts"]["spa"]["path"]
    digest, files = tree_digest(spa)
    if digest != manifest["artifacts"]["spa"]["sha256"] or files != manifest["artifacts"]["spa"]["files"]:
        raise RuntimeError("prepared SPA drift")


def preflight_start(root: Path, run: str,
                    command_runner: CommandRunner = subprocess.run) -> tuple[Path, dict, dict]:
    root = Path(root).resolve(strict=True)
    runtime, state, manifest = _load_prepared(root, run)
    _assert_unchanged(root, runtime, manifest)
    _verify_private_boundary(runtime)
    for port in PORTS.values():
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"fixed port occupied: {port}") from None
    project = manifest["composeProject"]
    checks = (
        ("container", ["docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={project}", "--format", "{{.ID}}"]),
        ("volume", ["docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={project}", "--format", "{{.Name}}"]),
        ("network", ["docker", "network", "ls", "--filter", f"label=com.docker.compose.project={project}", "--format", "{{.Name}}"]),
    )
    for kind, command in checks:
        completed = _run(command, root, runtime, f"preflight-{kind}s", command_runner)
        if completed.returncode:
            raise RuntimeError(f"cannot inspect existing Docker {kind}s")
        if (completed.stdout or "").strip():
            raise RuntimeError(f"existing compose {kind}; refuse takeover")
    _validate_compose(root, runtime, project, command_runner)
    return runtime, state, manifest


def _probe_keycloak(runtime: Path) -> None:
    context = ssl.create_default_context(cafile=str(runtime / "certs/ca.pem"))
    last_error: Exception | None = None
    for _ in range(60):
        try:
            with urllib.request.urlopen(
                ISSUER + "/.well-known/openid-configuration", context=context, timeout=5,
            ) as response:
                discovery = json.load(response)
            if discovery.get("issuer") != ISSUER:
                raise RuntimeError("Keycloak discovery issuer mismatch")
            return
        except Exception as error:
            last_error = error
            time.sleep(2)
    raise RuntimeError("Keycloak discovery did not become ready") from last_error


def _verify_published_port(
    root: Path, runtime: Path, prefix: list[str], service: str,
    container_port: int, host_port: int, command_runner: CommandRunner,
) -> None:
    selected = _run(
        prefix + ["ps", "-q", service], root, runtime,
        f"compose-{service}-container", command_runner,
    )
    container_ids = (selected.stdout or "").splitlines()
    if (
        selected.returncode
        or len(container_ids) != 1
        or not re.fullmatch(r"[0-9a-f]{12,64}", container_ids[0])
    ):
        raise RuntimeError(f"cannot identify the running {service} container")
    inspected = _run(
        [
            "docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}",
            container_ids[0],
        ],
        root, runtime, f"inspect-{service}-ports", command_runner,
    )
    try:
        actual = json.loads(inspected.stdout or "")
    except json.JSONDecodeError as error:
        raise RuntimeError(f"cannot parse {service} NetworkSettings port mapping") from error
    expected = {
        f"{container_port}/tcp": [
            {"HostIp": "127.0.0.1", "HostPort": str(host_port)},
        ],
    }
    if not isinstance(actual, dict):
        raise RuntimeError(f"{service} NetworkSettings port mapping mismatch")
    published = {port: bindings for port, bindings in actual.items() if bindings not in (None, [])}
    if inspected.returncode or published != expected:
        raise RuntimeError(f"{service} NetworkSettings port mapping mismatch")


def start_infrastructure(
    root: Path, run: str, command_runner: CommandRunner = subprocess.run,
    *, discovery_probe: Callable[[Path], None] | None = None,
) -> None:
    root = Path(root).resolve(strict=True)
    runtime, state, manifest = preflight_start(root, run, command_runner)
    state_path = runtime / "state.json"
    _record(state_path, state, "STARTING_INFRASTRUCTURE", failedStage=None)
    prefix = [
        "docker", "compose", "--env-file", str(runtime / "compose.env"),
        "-f", str(root / "e2e/compose.yaml"), "-p", manifest["composeProject"],
    ]
    def run_stage(arguments: list[str], label: str, failed_stage: str, message: str) -> None:
        completed = _run(prefix + arguments, root, runtime, label, command_runner)
        if completed.returncode:
            _record(
                state_path, state, "START_FAILED", failedStage=failed_stage,
                errorType="ExternalCommandError",
            )
            raise RuntimeError(message + "; protected resources retained without retry")

    def verify_port_stage(service: str, container_port: int, host_port: int) -> None:
        try:
            _verify_published_port(
                root, runtime, prefix, service, container_port, host_port, command_runner,
            )
        except RuntimeError as error:
            _record(
                state_path, state, "START_FAILED", failedStage=f"{service}-port-mapping",
                errorType=type(error).__name__,
            )
            raise RuntimeError(
                f"{service} published port verification failed; "
                "protected resources retained without retry"
            ) from error

    run_stage(
        ["up", "-d", "--wait", "--wait-timeout", "180", "--no-build", "identity-db", "business-db"],
        "compose-databases-ready", "databases-ready", "database services failed readiness",
    )
    verify_port_stage("business-db", 5432, PORTS["businessDatabase"])
    for service in ("keycloak-files", "flyway", "runtime-logins"):
        run_stage(
            ["run", "--no-deps", "-T", service],
            f"compose-{service}", service, f"{service} one-shot failed",
        )
    run_stage(
        ["up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "--no-build", "keycloak"],
        "compose-keycloak-ready", "keycloak-ready", "Keycloak service failed readiness",
    )
    verify_port_stage("keycloak", 8443, PORTS["keycloak"])
    try:
        (discovery_probe or _probe_keycloak)(runtime)
    except Exception as error:
        _record(state_path, state, "START_FAILED", failedStage="keycloak-discovery", errorType=type(error).__name__)
        raise RuntimeError("infrastructure discovery failed; protected resources retained") from error
    ps = _run(prefix + ["ps", "-a", "--format", "json"], root, runtime, "compose-ps", command_runner)
    if ps.returncode:
        _record(state_path, state, "START_FAILED", failedStage="compose-ps", errorType="ExternalCommandError")
        raise RuntimeError("cannot record infrastructure process state")
    _write(runtime / "infrastructure-processes.json", ps.stdout or "[]")
    _record(state_path, state, "INFRASTRUCTURE_READY", failedStage=None, applicationReady=False,
            applicationStatus="BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED")


def start_applications(root: Path, run: str) -> None:
    runtime = _runtime(Path(root), run)
    state = json.loads((runtime / "state.json").read_text(encoding="utf-8"))
    if state.get("applicationReady"):
        raise RuntimeError("invalid state: Task10.2 cannot create application-ready evidence")
    raise RuntimeError(
        "identity bootstrap and its current-run evidence are owned by a later Task10 unit; "
        "Task10.2 cannot start API, Worker or SPA"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    for operation in ("prepare", "start-infra", "start-apps"):
        command = subparsers.add_parser(operation)
        command.add_argument("run")
    args = parser.parse_args(argv)
    try:
        validate_run_id(args.run)
        if args.operation == "prepare":
            runtime = prepare_environment(args.root, args.run)
            print(f"PREPARED {runtime}; application status BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED")
        elif args.operation == "start-infra":
            start_infrastructure(args.root, args.run)
            print("INFRASTRUCTURE_READY; application status BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED")
        else:
            start_applications(args.root, args.run)
        return 0
    except (RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
