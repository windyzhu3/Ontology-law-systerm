"""Continue only an exactly bounded, proven-unwritten R1 isolation bootstrap."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Callable


RUNTIME_DIRECTORY = Path(__file__).resolve().parent
if str(RUNTIME_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(RUNTIME_DIRECTORY))
import r1_bootstrap as bootstrap


ROOT = Path(__file__).resolve().parents[2]
CONTINUATION_DIRECTORY = "identity-bootstrap-isolation-continuation"
CONTINUATION_PROFILE = "R1_E2E_ISOLATION_CONTINUATION_RECORD_V1"
COMPLETION_PROFILE = "R1_E2E_ISOLATION_CONTINUATION_COMPLETION_V1"
FAILED_STATE_PHASE = "IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN"
CommandRunner = Callable[..., subprocess.CompletedProcess]
HEX_DIGEST = re.compile(r"[0-9a-f]{64}")
ORIGINAL_STAGES = ("candidate", "dry-run", "execute", "verify", "fact-query")
CONTINUATION_STAGES = (
    "main-verify", "absence-query", "candidate", "dry-run", "execute", "verify", "fact-query",
)


def _require_digest(value: str, label: str) -> str:
    if not isinstance(value, str) or not HEX_DIGEST.fullmatch(value):
        raise RuntimeError(f"{label} must be a lowercase SHA-256")
    return value


def _exact_file(runtime: Path, relative: str, expected: str, label: str) -> Path:
    if relative != expected:
        raise RuntimeError(f"{label} path mismatch")
    path = runtime / relative
    if not path.is_file() or bootstrap.environment._is_link(path):
        raise RuntimeError(f"{label} is missing or linked")
    if path.resolve().parent != (runtime / expected).resolve().parent:
        raise RuntimeError(f"{label} path mismatch")
    return path


def _hashed_file(runtime: Path, relative: str, expected: str, digest: object, label: str) -> Path:
    path = _exact_file(runtime, relative, expected, label)
    if _require_digest(digest, f"{label} digest") != bootstrap._sha256(path):
        raise RuntimeError(f"{label} evidence mismatch")
    return path


def _validate_stage_outputs(
    runtime: Path, value: object, parent: str, modes: tuple[str, ...], label: str,
) -> dict:
    if not isinstance(value, dict) or set(value) != set(modes):
        raise RuntimeError(f"{label} evidence mismatch")
    for mode in modes:
        streams = value.get(mode)
        if not isinstance(streams, dict) or set(streams) != {
            "stdoutPath", "stdoutSha256", "stderrPath", "stderrSha256",
        }:
            raise RuntimeError(f"{label} evidence mismatch")
        for stream in ("stdout", "stderr"):
            relative = f"{parent}/{mode}.{stream}"
            try:
                _hashed_file(
                    runtime, streams[f"{stream}Path"], relative,
                    streams[f"{stream}Sha256"], label,
                )
            except (KeyError, TypeError, RuntimeError) as error:
                raise RuntimeError(f"{label} evidence mismatch") from error
    return value


def _logging_config(root: Path, runtime: Path, record: dict) -> Path:
    expected = {
        "loggingConfigSourcePath": bootstrap.LOGGING_CONFIG_SOURCE,
        "loggingConfigPath": "identity-bootstrap/logback.xml",
    }
    if any(record.get(key) != value for key, value in expected.items()):
        raise RuntimeError("original bootstrap logging configuration mismatch")
    source = root / bootstrap.LOGGING_CONFIG_SOURCE
    target = runtime / "identity-bootstrap/logback.xml"
    for path, key in ((source, "loggingConfigSourceSha256"), (target, "loggingConfigSha256")):
        digest = record.get(key)
        if (
            not path.is_file() or bootstrap.environment._is_link(path)
            or not isinstance(digest, str) or not HEX_DIGEST.fullmatch(digest)
            or bootstrap._sha256(path) != digest
        ):
            raise RuntimeError("original bootstrap logging configuration mismatch")
    if record["loggingConfigSourceSha256"] != record["loggingConfigSha256"]:
        raise RuntimeError("original bootstrap logging configuration mismatch")
    return target


def _validate_manifest(original: dict, settings: dict, code: str, display: str, root_display: str) -> None:
    expected = {
        "profile": "R1_IDENTITY_BOOTSTRAP_V1",
        "tenantCode": code,
        "tenantDisplayName": display,
        "rootCode": "ROOT",
        "rootDisplayName": root_display,
        "identityProviderCode": code,
        "issuer": bootstrap.environment.ISSUER,
        "principalDisplayName": "Synthetic Founder",
        "operatorAssertion": bootstrap.OPERATOR_ASSERTION,
    }
    if any(original.get(key) != value for key, value in expected.items()):
        raise RuntimeError("original bootstrap manifest mismatch")
    try:
        uuid.UUID(original["commandId"])
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise RuntimeError("original bootstrap manifest mismatch") from error
    if not isinstance(original.get("providerUserSelector"), str) or not original["providerUserSelector"].strip():
        raise RuntimeError("original bootstrap manifest mismatch")
    if not isinstance(original.get("effectiveFrom"), str) or not original["effectiveFrom"].endswith("Z"):
        raise RuntimeError("original bootstrap manifest mismatch")
    if original["tenantCode"] != settings.get("tenantCode"):
        raise RuntimeError("original bootstrap manifest mismatch")


def _validate_settings(runtime: Path, manifest: dict, path: Path, code: str) -> dict:
    settings = bootstrap._read_json(path, "original bootstrap settings")
    tenant_id = settings.get("tenantId")
    try:
        uuid.UUID(tenant_id)
    except (TypeError, ValueError, AttributeError) as error:
        raise RuntimeError("original bootstrap settings mismatch") from error
    subject = runtime / f"identity-bootstrap/{'main' if code == 'R1_E2E_MAIN' else 'isolation'}/subject-hmac.txt"
    if settings != bootstrap._settings(runtime, manifest, code, tenant_id, subject):
        raise RuntimeError("original bootstrap settings mismatch")
    return settings


def _validate_main(root: Path, runtime: Path, manifest: dict, record: dict) -> dict:
    entries = record.get("tenants")
    if not isinstance(entries, list) or len(entries) != 1 or entries[0].get("tenantCode") != "R1_E2E_MAIN":
        raise RuntimeError("original bootstrap tenant inventory mismatch")
    entry = entries[0]
    base = "identity-bootstrap/main"
    main_folder = runtime / base
    expected_names = {
        "settings.json", "subject-hmac.txt", "founder-identifier.txt", "original-manifest.json",
        "operation.json", "fact-references.json",
        *(f"{mode}.{stream}" for mode in ORIGINAL_STAGES for stream in ("stdout", "stderr")),
    }
    if (
        not main_folder.is_dir() or bootstrap.environment._is_link(main_folder)
        or {path.name for path in main_folder.iterdir()} != expected_names
    ):
        raise RuntimeError("original MAIN evidence inventory mismatch")
    fields = {
        "settings": ("settingsPath", "settingsSha256", f"{base}/settings.json"),
        "subject": ("subjectHmacPath", "subjectHmacSha256", f"{base}/subject-hmac.txt"),
        "original": ("originalManifestPath", "originalManifestSha256", f"{base}/original-manifest.json"),
        "operation": ("operationPath", "operationSha256", f"{base}/operation.json"),
        "facts": ("factReferencesPath", "factReferencesSha256", f"{base}/fact-references.json"),
    }
    paths = {}
    try:
        for name, (path_key, digest_key, expected) in fields.items():
            paths[name] = _hashed_file(runtime, entry[path_key], expected, entry[digest_key], "original bootstrap")
    except (KeyError, TypeError) as error:
        raise RuntimeError("original bootstrap evidence mismatch") from error
    _validate_stage_outputs(runtime, entry.get("stageOutputs"), base, ORIGINAL_STAGES, "original bootstrap")
    operation = bootstrap._read_json(paths["operation"], "original bootstrap operation")
    if (
        operation.get("profile") != "R1_E2E_IDENTITY_BOOTSTRAP_OPERATION_V1"
        or operation.get("tenantCode") != "R1_E2E_MAIN"
        or operation.get("phase") != "VERIFIED_ORIGINAL"
        or [(item.get("mode"), item.get("exitCode")) for item in operation.get("stages", [])]
        != [(mode, 0) for mode in ORIGINAL_STAGES]
    ):
        raise RuntimeError("original MAIN operation mismatch")
    settings = _validate_settings(runtime, manifest, paths["settings"], "R1_E2E_MAIN")
    original = bootstrap._read_json(paths["original"], "original MAIN manifest")
    _validate_manifest(original, settings, "R1_E2E_MAIN", "R1 synthetic firm", "R1 synthetic firm root")
    founder = _exact_file(runtime, f"{base}/founder-identifier.txt", f"{base}/founder-identifier.txt", "founder identifier")
    if founder.read_text(encoding="utf-8", errors="strict").strip() != f"r1-{manifest['run']}-founder":
        raise RuntimeError("original founder identifier mismatch")
    outputs = entry["stageOutputs"]
    candidate = bootstrap._parse_object((runtime / outputs["candidate"]["stdoutPath"]).read_text(encoding="utf-8"), "candidate")
    if candidate != {"providerUserSelector": original["providerUserSelector"]}:
        raise RuntimeError("original MAIN candidate mismatch")
    dry_run = bootstrap._parse_object((runtime / outputs["dry-run"]["stdoutPath"]).read_text(encoding="utf-8"), "dry-run")
    bootstrap._require_result(dry_run, "DRY_RUN")
    bootstrap._require_dry_run_preview(dry_run, settings, original)
    for mode, expected in (("execute", "CREATED"), ("verify", "VERIFIED_ORIGINAL")):
        result = bootstrap._parse_object((runtime / outputs[mode]["stdoutPath"]).read_text(encoding="utf-8"), mode)
        bootstrap._require_result(result, expected)
    raw_facts = bootstrap._parse_object((runtime / outputs["fact-query"]["stdoutPath"]).read_text(encoding="utf-8"), "fact-query")
    facts = bootstrap._read_json(paths["facts"], "original MAIN fact references")
    if facts != bootstrap._fact_references(raw_facts, settings["tenantId"]):
        raise RuntimeError("original MAIN fact evidence mismatch")
    projection = bootstrap._verified_tenant_input(facts, settings, entry["subjectHmacPath"])
    return {"entry": entry, "paths": paths, "settings": settings, "original": original, "facts": facts, "projection": projection}


def _validate_partial_isolation(runtime: Path, manifest: dict) -> dict:
    folder = runtime / "identity-bootstrap/isolation"
    expected_names = {
        "subject-hmac.txt", "settings.json", "founder-identifier.txt", "operation.json",
        "candidate.stdout", "candidate.stderr", "original-manifest.json",
        "dry-run.stdout", "dry-run.stderr",
    }
    if not folder.is_dir() or bootstrap.environment._is_link(folder):
        raise RuntimeError("partial isolation evidence mismatch")
    actual_names = {path.name for path in folder.iterdir()}
    if actual_names != expected_names or any(
        not path.is_file() or bootstrap.environment._is_link(path) for path in folder.iterdir()
    ):
        raise RuntimeError("partial isolation evidence mismatch")
    operation = bootstrap._read_json(folder / "operation.json", "partial isolation operation")
    if (
        set(operation) != {"profile", "tenantCode", "stages"}
        or operation.get("profile") != "R1_E2E_IDENTITY_BOOTSTRAP_OPERATION_V1"
        or operation.get("tenantCode") != "R1_E2E_ISOLATION"
        or [(item.get("mode"), item.get("exitCode")) for item in operation.get("stages", [])]
        != [("candidate", 0), ("dry-run", 1)]
    ):
        raise RuntimeError("partial isolation operation mismatch")
    settings = _validate_settings(runtime, manifest, folder / "settings.json", "R1_E2E_ISOLATION")
    original = bootstrap._read_json(folder / "original-manifest.json", "partial isolation manifest")
    _validate_manifest(
        original, settings, "R1_E2E_ISOLATION", "R1 isolation sentinel", "R1 isolation sentinel root",
    )
    candidate = bootstrap._parse_object((folder / "candidate.stdout").read_text(encoding="utf-8"), "candidate")
    if candidate != {"providerUserSelector": original["providerUserSelector"]}:
        raise RuntimeError("partial isolation candidate mismatch")
    if (folder / "founder-identifier.txt").read_text(encoding="utf-8", errors="strict").strip() != f"r1-{manifest['run']}-founder":
        raise RuntimeError("partial isolation founder mismatch")
    return {"folder": folder, "settings": settings, "original": original}


def _validate_origin(
    root: Path, runtime: Path, run: str, state_digest: str, record_digest: str,
) -> dict:
    _require_digest(state_digest, "expected original state digest")
    _require_digest(record_digest, "expected original record digest")
    state_path = runtime / "state.json"
    record_path = runtime / "identity-bootstrap/record.json"
    if bootstrap._sha256(state_path) != state_digest:
        raise RuntimeError("original state digest mismatch")
    if bootstrap._sha256(record_path) != record_digest:
        raise RuntimeError("original record digest mismatch")
    state = bootstrap._read_json(state_path, "original run state")
    record = bootstrap._read_json(record_path, "original bootstrap record")
    if (
        state.get("profile") != "R1_E2E_RUN_STATE_V1" or state.get("run") != run
        or state.get("phase") != FAILED_STATE_PHASE or state.get("applicationReady") is not False
    ):
        raise RuntimeError("original failed run state mismatch")
    if (
        record.get("profile") != "R1_E2E_IDENTITY_BOOTSTRAP_RECORD_V1" or record.get("run") != run
        or record.get("phase") != "FAILED_OR_UNCERTAIN"
        or record.get("environmentManifestSha256") != bootstrap._sha256(runtime / "manifest.json")
    ):
        raise RuntimeError("original failed bootstrap record mismatch")
    manifest = bootstrap._read_json(runtime / "manifest.json", "environment manifest")
    bootstrap_root = runtime / "identity-bootstrap"
    if (
        not bootstrap_root.is_dir() or bootstrap.environment._is_link(bootstrap_root)
        or {path.name for path in bootstrap_root.iterdir()} != {"record.json", "logback.xml", "main", "isolation"}
    ):
        raise RuntimeError("original bootstrap evidence inventory mismatch")
    logging_config = _logging_config(root, runtime, record)
    main = _validate_main(root, runtime, manifest, record)
    isolation = _validate_partial_isolation(runtime, manifest)
    tree_digest, tree_files = bootstrap.environment.tree_digest(runtime / "identity-bootstrap")
    return {
        "state": state, "record": record, "manifest": manifest, "loggingConfig": logging_config,
        "main": main, "isolation": isolation, "treeDigest": tree_digest, "treeFiles": tree_files,
    }


def _assert_origin(runtime: Path, binding: dict, *, promotion: bool = False) -> None:
    suffix = " before final promotion" if promotion else ""
    if bootstrap._sha256(runtime / "state.json") != binding["stateSha256"]:
        raise RuntimeError(f"original state changed{suffix}")
    if bootstrap._sha256(runtime / "identity-bootstrap/record.json") != binding["recordSha256"]:
        raise RuntimeError(f"original record changed{suffix}")
    digest, files = bootstrap.environment.tree_digest(runtime / "identity-bootstrap")
    if digest != binding["treeSha256"] or files != binding["treeFiles"]:
        raise RuntimeError(f"original bootstrap tree changed{suffix}")


def _absence_query(tenant_id: str, tenant_code: str, old_command_id: str, new_command_id: str) -> bytes:
    try:
        uuid.UUID(tenant_id); uuid.UUID(old_command_id); uuid.UUID(new_command_id)
    except (TypeError, ValueError, AttributeError) as error:
        raise RuntimeError("invalid isolation absence identity") from error
    if tenant_code != "R1_E2E_ISOLATION":
        raise RuntimeError("invalid isolation tenant code")
    command_guard = f"('{old_command_id}'::uuid,'{new_command_id}'::uuid)"
    return (
        "BEGIN READ ONLY;\nSET LOCAL ROLE law_app_query;\n"
        "SELECT jsonb_build_object('profile','R1_ISOLATION_ABSENCE_V1',"
        f"'tenantId',(SELECT count(*) FROM identity.tenant WHERE tenant_id='{tenant_id}'::uuid),"
        f"'tenantCode',(SELECT count(*) FROM identity.tenant WHERE tenant_code='{tenant_code}'),"
        f"'principal',(SELECT count(*) FROM identity.principal WHERE tenant_id='{tenant_id}'::uuid),"
        f"'organization',(SELECT count(*) FROM identity.organization_unit WHERE tenant_id='{tenant_id}'::uuid),"
        f"'appointment',(SELECT count(*) FROM identity.appointment WHERE tenant_id='{tenant_id}'::uuid),"
        f"'authorityGrant',(SELECT count(*) FROM identity.authority_grant WHERE tenant_id='{tenant_id}'::uuid),"
        f"'commandSlot',(SELECT count(*) FROM execution.command_execution_slot WHERE tenant_id='{tenant_id}'::uuid OR command_id IN {command_guard}),"
        f"'receipt',(SELECT count(*) FROM execution.command_receipt r JOIN execution.command_execution_slot s USING (tenant_id,command_execution_slot_id) WHERE r.tenant_id='{tenant_id}'::uuid OR s.command_id IN {command_guard}),"
        f"'classifiedAudit',(SELECT count(*) FROM audit.audit_entry_classified_v WHERE tenant_id='{tenant_id}'::uuid OR command_id IN {command_guard}))::text;\n"
        "COMMIT;\n"
    ).encode("utf-8")


def _require_absence(row: dict) -> None:
    keys = {
        "profile", "tenantId", "tenantCode", "principal", "organization", "appointment",
        "authorityGrant", "commandSlot", "receipt", "classifiedAudit",
    }
    if set(row) != keys or row.get("profile") != "R1_ISOLATION_ABSENCE_V1" or any(
        type(row[key]) is not int or row[key] != 0 for key in keys - {"profile"}
    ):
        raise RuntimeError("isolation absence proof is malformed or nonzero")


def _append_stage(record_path: Path, mode: str, exit_code: int | None) -> None:
    record = bootstrap._read_json(record_path, "isolation continuation record")
    record["stages"].append({"mode": mode, "exitCode": exit_code, "at": bootstrap.environment.utc_now()})
    record["updatedAt"] = bootstrap.environment.utc_now()
    bootstrap.environment._atomic_json(record_path, record)


def _run(
    command: list[str], root: Path, folder: Path, record_path: Path, label: str,
    command_runner: CommandRunner, *, input_bytes: bytes | None = None,
) -> dict:
    try:
        exit_code, stdout, _ = bootstrap._invoke(
            command, root, label, command_runner, output_directory=folder, input_bytes=input_bytes,
        )
    except bootstrap.ProcessFailure as error:
        _append_stage(record_path, label, error.exit_code)
        raise RuntimeError(str(error)) from error
    _append_stage(record_path, label, exit_code)
    if exit_code:
        raise RuntimeError(f"{label} failed or is uncertain; continuation cannot be retried")
    return bootstrap._parse_object(stdout, label)


def _stage_manifest(runtime: Path, folder: Path) -> dict:
    result = {}
    for mode in CONTINUATION_STAGES:
        result[mode] = {}
        for stream in ("stdout", "stderr"):
            path = folder / f"{mode}.{stream}"
            if not path.is_file() or bootstrap.environment._is_link(path):
                raise RuntimeError("continuation stage output is missing or linked")
            result[mode][f"{stream}Path"] = path.relative_to(runtime).as_posix()
            result[mode][f"{stream}Sha256"] = bootstrap._sha256(path)
    return result


def continue_isolation(
    root: Path, run: str, expected_original_state_sha256: str,
    expected_original_record_sha256: str, command_runner: CommandRunner = subprocess.run,
) -> None:
    root = Path(root).resolve(strict=True)
    runtime = bootstrap.environment._runtime(root, run)
    folder = runtime / CONTINUATION_DIRECTORY
    if folder.exists():
        raise RuntimeError("existing isolation continuation; refuse overwrite or replay")
    origin = _validate_origin(
        root, runtime, run, expected_original_state_sha256, expected_original_record_sha256,
    )
    runtime_checked, _, manifest, java_path = bootstrap._validate_failed_bootstrap_environment(
        root, run, command_runner,
    )
    if runtime_checked != runtime or manifest != origin["manifest"]:
        raise RuntimeError("continuation environment identity mismatch")
    binding = {
        "statePath": "state.json", "stateSha256": expected_original_state_sha256,
        "recordPath": "identity-bootstrap/record.json", "recordSha256": expected_original_record_sha256,
        "treePath": "identity-bootstrap", "treeSha256": origin["treeDigest"], "treeFiles": origin["treeFiles"],
        "oldIsolationCommandId": origin["isolation"]["original"]["commandId"],
        "oldIsolationSelector": origin["isolation"]["original"]["providerUserSelector"],
    }
    _assert_origin(runtime, binding)
    new_command_id = str(uuid.uuid4())
    bootstrap.environment._mkdir_secure(folder)
    record_path = folder / "record.json"
    bootstrap.environment._atomic_json(record_path, {
        "profile": CONTINUATION_PROFILE, "run": run, "phase": "IN_PROGRESS",
        "applicationReady": False, "createdAt": bootstrap.environment.utc_now(),
        "updatedAt": bootstrap.environment.utc_now(), "origin": binding,
        "environmentManifestSha256": bootstrap._sha256(runtime / "manifest.json"),
        "renewedCommandId": new_command_id, "stages": [],
    })
    try:
        jar = runtime / manifest["artifacts"]["jar"]["path"]
        base = bootstrap._java_command(java_path, jar, origin["loggingConfig"])
        main = origin["main"]
        verified_main = _run(
            [*base, "verify", str(main["paths"]["settings"]), str(main["paths"]["original"])],
            root, folder, record_path, "main-verify", command_runner,
        )
        bootstrap._require_result(verified_main, "VERIFIED_ORIGINAL")
        partial = origin["isolation"]
        absence = _run(
            bootstrap._psql_command(root, runtime, manifest), root, folder, record_path,
            "absence-query", command_runner,
            input_bytes=_absence_query(
                partial["settings"]["tenantId"], "R1_E2E_ISOLATION",
                partial["original"]["commandId"], new_command_id,
            ),
        )
        _require_absence(absence)
        _assert_origin(runtime, binding)
        candidate = _run(
            [*base, "candidate", str(partial["folder"] / "settings.json"), str(partial["folder"] / "founder-identifier.txt")],
            root, folder, record_path, "candidate", command_runner,
        )
        selector = candidate.get("providerUserSelector")
        if set(candidate) != {"providerUserSelector"} or not isinstance(selector, str) or not selector.strip():
            raise RuntimeError("renewed candidate returned invalid JSON")
        if selector == partial["original"]["providerUserSelector"]:
            raise RuntimeError("renewed candidate reused the expired selector")
        original = bootstrap._original_manifest(
            "R1_E2E_ISOLATION", "R1 isolation sentinel", "R1 isolation sentinel root", selector,
        )
        original["commandId"] = new_command_id
        new_original_path = folder / "isolation-original-manifest.json"
        bootstrap.environment._atomic_json(new_original_path, original)
        dry_run = _run(
            [*base, "dry-run", str(partial["folder"] / "settings.json"), str(new_original_path)],
            root, folder, record_path, "dry-run", command_runner,
        )
        bootstrap._require_result(dry_run, "DRY_RUN")
        bootstrap._require_dry_run_preview(dry_run, partial["settings"], original)
        _assert_origin(runtime, binding)
        executed = _run(
            [*base, "execute", str(partial["folder"] / "settings.json"), str(new_original_path), "--confirm-bootstrap"],
            root, folder, record_path, "execute", command_runner,
        )
        bootstrap._require_result(executed, "CREATED")
        verified = _run(
            [*base, "verify", str(partial["folder"] / "settings.json"), str(new_original_path)],
            root, folder, record_path, "verify", command_runner,
        )
        bootstrap._require_result(verified, "VERIFIED_ORIGINAL")
        fact_row = _run(
            bootstrap._psql_command(root, runtime, manifest), root, folder, record_path,
            "fact-query", command_runner,
            input_bytes=bootstrap._fact_query(
                partial["settings"]["tenantId"], "R1_E2E_ISOLATION", new_command_id,
            ),
        )
        facts = bootstrap._fact_references(fact_row, partial["settings"]["tenantId"])
        facts_path = folder / "fact-references.json"
        bootstrap.environment._atomic_json(facts_path, facts)
        _assert_origin(runtime, binding, promotion=True)
        record = bootstrap._read_json(record_path, "isolation continuation record")
        record.update(
            phase="IDENTITY_BOOTSTRAP_CONTINUED_VERIFIED",
            updatedAt=bootstrap.environment.utc_now(),
            stageOutputs=_stage_manifest(runtime, folder),
            main={
                "tenantCode": "R1_E2E_MAIN",
                "settingsPath": main["entry"]["settingsPath"],
                "originalManifestPath": main["entry"]["originalManifestPath"],
                "factReferencesPath": main["entry"]["factReferencesPath"],
            },
            isolation={
                "tenantCode": "R1_E2E_ISOLATION",
                "settingsPath": "identity-bootstrap/isolation/settings.json",
                "settingsSha256": bootstrap._sha256(partial["folder"] / "settings.json"),
                "subjectHmacPath": "identity-bootstrap/isolation/subject-hmac.txt",
                "subjectHmacSha256": bootstrap._sha256(partial["folder"] / "subject-hmac.txt"),
                "originalManifestPath": f"{CONTINUATION_DIRECTORY}/isolation-original-manifest.json",
                "originalManifestSha256": bootstrap._sha256(new_original_path),
                "factReferencesPath": f"{CONTINUATION_DIRECTORY}/fact-references.json",
                "factReferencesSha256": bootstrap._sha256(facts_path),
            },
        )
        bootstrap.environment._atomic_json(record_path, record)
        bootstrap.environment._verify_private_boundary(runtime)
        bootstrap.environment._atomic_json(folder / "completion.json", {
            "profile": COMPLETION_PROFILE, "run": run,
            "recordPath": f"{CONTINUATION_DIRECTORY}/record.json",
            "recordSha256": bootstrap._sha256(record_path),
        })
        bootstrap.environment._verify_private_boundary(runtime)
    except Exception:
        if record_path.exists():
            record = bootstrap._read_json(record_path, "isolation continuation record")
            record["phase"] = "FAILED_OR_UNCERTAIN"
            record["updatedAt"] = bootstrap.environment.utc_now()
            bootstrap.environment._atomic_json(record_path, record)
        raise


def _validate_continuation(runtime: Path, run: str, origin: dict) -> dict:
    folder = runtime / CONTINUATION_DIRECTORY
    expected_names = {
        "record.json", "completion.json", "isolation-original-manifest.json", "fact-references.json",
        *(f"{mode}.{stream}" for mode in CONTINUATION_STAGES for stream in ("stdout", "stderr")),
    }
    if (
        not folder.is_dir() or bootstrap.environment._is_link(folder)
        or {path.name for path in folder.iterdir()} != expected_names
        or any(not path.is_file() or bootstrap.environment._is_link(path) for path in folder.iterdir())
    ):
        raise RuntimeError("continuation evidence mismatch")
    completion_path = _exact_file(
        runtime, f"{CONTINUATION_DIRECTORY}/completion.json",
        f"{CONTINUATION_DIRECTORY}/completion.json", "continuation completion",
    )
    completion = bootstrap._read_json(completion_path, "continuation completion")
    record_path = _exact_file(
        runtime, completion.get("recordPath", ""), f"{CONTINUATION_DIRECTORY}/record.json",
        "continuation",
    )
    if (
        completion.get("profile") != COMPLETION_PROFILE or completion.get("run") != run
        or completion.get("recordSha256") != bootstrap._sha256(record_path)
    ):
        raise RuntimeError("continuation completion mismatch")
    record = bootstrap._read_json(record_path, "isolation continuation record")
    if (
        record.get("profile") != CONTINUATION_PROFILE or record.get("run") != run
        or record.get("phase") != "IDENTITY_BOOTSTRAP_CONTINUED_VERIFIED"
        or record.get("applicationReady") is not False or record.get("origin") != origin
        or record.get("environmentManifestSha256") != bootstrap._sha256(runtime / "manifest.json")
        or [(item.get("mode"), item.get("exitCode")) for item in record.get("stages", [])]
        != [(mode, 0) for mode in CONTINUATION_STAGES]
    ):
        raise RuntimeError("continuation record mismatch")
    _validate_stage_outputs(
        runtime, record.get("stageOutputs"), CONTINUATION_DIRECTORY,
        CONTINUATION_STAGES, "continuation",
    )
    outputs = record["stageOutputs"]
    main_verified = bootstrap._parse_object(
        (runtime / outputs["main-verify"]["stdoutPath"]).read_text(encoding="utf-8"), "main-verify",
    )
    bootstrap._require_result(main_verified, "VERIFIED_ORIGINAL")
    absence = bootstrap._parse_object(
        (runtime / outputs["absence-query"]["stdoutPath"]).read_text(encoding="utf-8"), "absence-query",
    )
    _require_absence(absence)
    if record.get("main") != {
        "tenantCode": "R1_E2E_MAIN",
        "settingsPath": "identity-bootstrap/main/settings.json",
        "originalManifestPath": "identity-bootstrap/main/original-manifest.json",
        "factReferencesPath": "identity-bootstrap/main/fact-references.json",
    }:
        raise RuntimeError("continuation MAIN binding mismatch")
    isolation = record.get("isolation")
    if not isinstance(isolation, dict):
        raise RuntimeError("continuation path mismatch")
    try:
        settings_path = _hashed_file(
            runtime, isolation["settingsPath"], "identity-bootstrap/isolation/settings.json",
            isolation["settingsSha256"], "continuation",
        )
        subject_path = _hashed_file(
            runtime, isolation["subjectHmacPath"], "identity-bootstrap/isolation/subject-hmac.txt",
            isolation["subjectHmacSha256"], "continuation",
        )
        original_path = _hashed_file(
            runtime, isolation["originalManifestPath"],
            f"{CONTINUATION_DIRECTORY}/isolation-original-manifest.json",
            isolation["originalManifestSha256"], "continuation",
        )
        facts_path = _hashed_file(
            runtime, isolation["factReferencesPath"], f"{CONTINUATION_DIRECTORY}/fact-references.json",
            isolation["factReferencesSha256"], "continuation",
        )
    except (KeyError, TypeError) as error:
        raise RuntimeError("continuation path mismatch") from error
    settings = bootstrap._read_json(settings_path, "continued isolation settings")
    original = bootstrap._read_json(original_path, "continued isolation manifest")
    _validate_manifest(
        original, settings, "R1_E2E_ISOLATION", "R1 isolation sentinel", "R1 isolation sentinel root",
    )
    if (
        original.get("commandId") != record.get("renewedCommandId")
        or original.get("commandId") == origin["oldIsolationCommandId"]
        or original.get("providerUserSelector") == origin["oldIsolationSelector"]
    ):
        raise RuntimeError("continued isolation command mismatch")
    candidate = bootstrap._parse_object((runtime / outputs["candidate"]["stdoutPath"]).read_text(encoding="utf-8"), "candidate")
    if candidate != {"providerUserSelector": original["providerUserSelector"]}:
        raise RuntimeError("continuation evidence mismatch")
    dry_run = bootstrap._parse_object((runtime / outputs["dry-run"]["stdoutPath"]).read_text(encoding="utf-8"), "dry-run")
    bootstrap._require_result(dry_run, "DRY_RUN")
    bootstrap._require_dry_run_preview(dry_run, settings, original)
    for mode, expected in (("execute", "CREATED"), ("verify", "VERIFIED_ORIGINAL")):
        bootstrap._require_result(
            bootstrap._parse_object((runtime / outputs[mode]["stdoutPath"]).read_text(encoding="utf-8"), mode), expected,
        )
    facts = bootstrap._read_json(facts_path, "continued isolation facts")
    raw_facts = bootstrap._parse_object((runtime / outputs["fact-query"]["stdoutPath"]).read_text(encoding="utf-8"), "fact-query")
    if facts != bootstrap._fact_references(raw_facts, settings["tenantId"]):
        raise RuntimeError("continuation fact evidence mismatch")
    return {
        "record": record, "settings": settings, "original": original, "facts": facts,
        "subjectPath": isolation["subjectHmacPath"], "originalPath": original_path,
    }


def verify_combined(
    root: Path, run: str, command_runner: CommandRunner = subprocess.run,
) -> dict:
    root = Path(root).resolve(strict=True)
    runtime = bootstrap.environment._runtime(root, run)
    folder = runtime / CONTINUATION_DIRECTORY
    if not folder.is_dir() or bootstrap.environment._is_link(folder):
        raise RuntimeError("continuation evidence mismatch")
    completion_path = _exact_file(
        runtime, f"{CONTINUATION_DIRECTORY}/completion.json",
        f"{CONTINUATION_DIRECTORY}/completion.json", "continuation completion",
    )
    record_path = _exact_file(
        runtime, f"{CONTINUATION_DIRECTORY}/record.json",
        f"{CONTINUATION_DIRECTORY}/record.json", "continuation record",
    )
    bootstrap._read_json(completion_path, "continuation completion")
    record = bootstrap._read_json(record_path, "isolation continuation record")
    stored = record.get("origin")
    if not isinstance(stored, dict):
        raise RuntimeError("continuation origin binding mismatch")
    origin_data = _validate_origin(
        root, runtime, run, stored.get("stateSha256", ""), stored.get("recordSha256", ""),
    )
    expected_origin = {
        "statePath": "state.json", "stateSha256": stored.get("stateSha256"),
        "recordPath": "identity-bootstrap/record.json", "recordSha256": stored.get("recordSha256"),
        "treePath": "identity-bootstrap", "treeSha256": origin_data["treeDigest"],
        "treeFiles": origin_data["treeFiles"],
        "oldIsolationCommandId": origin_data["isolation"]["original"]["commandId"],
        "oldIsolationSelector": origin_data["isolation"]["original"]["providerUserSelector"],
    }
    if set(stored) != set(expected_origin) or stored != expected_origin:
        raise RuntimeError("continuation origin binding mismatch")
    continuation = _validate_continuation(runtime, run, stored)
    runtime_checked, _, manifest, java_path = bootstrap._validate_failed_bootstrap_environment(
        root, run, command_runner,
    )
    if runtime_checked != runtime or manifest != origin_data["manifest"]:
        raise RuntimeError("continuation environment identity mismatch")
    bootstrap.environment._verify_private_boundary(runtime)
    jar = runtime / manifest["artifacts"]["jar"]["path"]
    base = bootstrap._java_command(java_path, jar, origin_data["loggingConfig"])
    verify_inputs = (
        (origin_data["main"]["paths"]["settings"], origin_data["main"]["paths"]["original"]),
        (runtime / "identity-bootstrap/isolation/settings.json", continuation["originalPath"]),
    )
    for settings, original in verify_inputs:
        exit_code, stdout, _ = bootstrap._invoke(
            [*base, "verify", str(settings), str(original)], root, "verify", command_runner,
        )
        if exit_code:
            raise RuntimeError("combined verify failed or is uncertain")
        bootstrap._require_result(bootstrap._parse_object(stdout, "verify"), "VERIFIED_ORIGINAL")
    return {
        "profile": "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1", "run": run,
        "tenants": {
            "R1_E2E_MAIN": origin_data["main"]["projection"],
            "R1_E2E_ISOLATION": bootstrap._verified_tenant_input(
                continuation["facts"], continuation["settings"], continuation["subjectPath"],
            ),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    commands = parser.add_subparsers(dest="operation", required=True)
    continuation = commands.add_parser("continue-isolation")
    continuation.add_argument("run")
    continuation.add_argument("--expected-original-state-sha256", required=True)
    continuation.add_argument("--expected-original-record-sha256", required=True)
    combined = commands.add_parser("verify-combined")
    combined.add_argument("run")
    args = parser.parse_args(argv)
    try:
        bootstrap.environment.validate_run_id(args.run)
        if args.operation == "continue-isolation":
            continue_isolation(
                args.root, args.run, args.expected_original_state_sha256,
                args.expected_original_record_sha256,
            )
            print("ISOLATION_CONTINUATION_VERIFIED; original failed run remains immutable")
        else:
            verify_combined(args.root, args.run)
            print("VERIFIED_COMBINED; original MAIN and continued ISOLATION verified read-only")
        return 0
    except (RuntimeError, ValueError):
        print(
            "R1_IDENTITY_BOOTSTRAP_CONTINUATION_FAILED_OR_UNCERTAIN: retain both protected evidence trees; do not retry",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
