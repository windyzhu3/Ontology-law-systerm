import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "e2e/runtime/r1_bootstrap.py"
PYTHON = Path(sys.executable)


def load_module():
    spec = importlib.util.spec_from_file_location("r1_bootstrap", CLI)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def process_records(project):
    return [
        {
            "Project": project,
            "Service": service,
            "State": state,
            "ExitCode": 0,
            "Health": health,
            "Command": "synthetic",
        }
        for service, state, health in (
            ("identity-db", "running", "healthy"),
            ("business-db", "running", "healthy"),
            ("keycloak", "running", ""),
            ("keycloak-files", "exited", ""),
            ("flyway", "exited", ""),
            ("runtime-logins", "exited", ""),
        )
    ]


class ControlledProcess:
    def __init__(self, module, manifest, *, fail_mode=None, invalid_utf8_mode=None,
                 deployment_override=None, dry_run_role="IDENTITY_ADMIN", fail_tenant_code=None,
                 fail_exit=19):
        self.module = module
        self.manifest = manifest
        self.fail_mode = fail_mode
        self.invalid_utf8_mode = invalid_utf8_mode
        self.deployment_override = deployment_override
        self.dry_run_role = dry_run_role
        self.fail_tenant_code = fail_tenant_code
        self.fail_exit = fail_exit
        self.calls = []
        self.fact_calls = 0

    def __call__(self, command, **kwargs):
        input_bytes = kwargs.get("input")
        self.calls.append((list(map(str, command)), input_bytes))
        if command[-1] == "-version":
            output = (
                'openjdk version "25.0.4.1" 2026-08-18 LTS\n'
                'OpenJDK Runtime Environment Temurin-25.0.4.1+1 (build 25.0.4.1+1-LTS)\n'
            ).encode()
            return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=output)
        if command[:2] == ["docker", "compose"] and "ps" in command:
            raw = "\n".join(json.dumps(row) for row in process_records(self.manifest["composeProject"]))
            return subprocess.CompletedProcess(command, 0, stdout=raw.encode(), stderr=b"")
        if command[:2] == ["docker", "compose"] and "exec" in command:
            sql = input_bytes or b""
            if b"platform_meta.deployment_state" in sql:
                row = {
                    "operatingMode": "ACTIVE",
                    "schemaVersion": "52-plus-2-v1.2",
                    "releaseDigest": self.manifest["artifacts"]["jar"]["sha256"],
                    "manifestHash": self.manifest["schemaManifestSha256"],
                }
                if self.deployment_override:
                    row.update(self.deployment_override)
            else:
                self.fact_calls += 1
                tenant = "main" if b"R1_E2E_MAIN" in sql else "isolation"
                tenant_id = re.search(rb"tenant_id='([0-9a-f-]{36})'", sql).group(1).decode()
                row = {
                    "tenantId": tenant_id,
                    "rootOrganizationId": str(uuid.uuid5(uuid.NAMESPACE_DNS, tenant + "-root")),
                    "founderPrincipalId": str(uuid.uuid5(uuid.NAMESPACE_DNS, tenant + "-founder")),
                    "appointmentId": str(uuid.uuid5(uuid.NAMESPACE_DNS, tenant + "-appointment")),
                    "grantIds": [str(uuid.uuid5(uuid.NAMESPACE_DNS, tenant + f"-grant-{i}")) for i in range(4)],
                    "originalEvidenceSha256": ("a" if tenant == "main" else "b") * 64,
                }
            return subprocess.CompletedProcess(command, 0, stdout=(json.dumps(row) + "\n").encode(), stderr=b"")

        marker = "org.springframework.boot.loader.launch.PropertiesLauncher"
        mode = command[command.index(marker) + 1]
        settings = json.loads(Path(command[command.index(marker) + 2]).read_text(encoding="utf-8"))
        if mode == self.fail_mode and (
            self.fail_tenant_code is None or settings["tenantCode"] == self.fail_tenant_code
        ):
            return subprocess.CompletedProcess(command, self.fail_exit, stdout=b"", stderr=b"synthetic failure")
        if mode == self.invalid_utf8_mode:
            return subprocess.CompletedProcess(command, 0, stdout=b"\xff", stderr=b"")
        if mode == "candidate":
            result = {"providerUserSelector": "selector-secret-" + settings["tenantCode"]}
        elif mode == "dry-run":
            original = json.loads(Path(command[-1]).read_text(encoding="utf-8"))
            result = {"mode": "DRY_RUN", "plannedDelta": self.module.EXPECTED_DELTA, "preview": {
                "tenant": {"id": settings["tenantId"], "code": original["tenantCode"], "displayName": original["tenantDisplayName"]},
                "rootOrganization": {"code": "ROOT", "displayName": original["rootDisplayName"]},
                "administrator": {"displayName": "Synthetic Founder", "principalKind": "HUMAN", "identityProviderCode": original["identityProviderCode"]},
                "appointment": {"roleCode": self.dry_run_role, "organizationCode": "ROOT", "effectiveFrom": original["effectiveFrom"]},
                "authorityGrants": [
                    {"authorityCode": authority, "path": "DIRECT", "scope": "ROOT", "scopeOrganizationCode": "ROOT"}
                    for authority in (
                        "IDENTITY_PRINCIPAL_MANAGE",
                        "IDENTITY_ORGANIZATION_MANAGE",
                        "IDENTITY_APPOINTMENT_MANAGE",
                        "IDENTITY_AUTHORITY_MANAGE",
                    )
                ],
            }}
        elif mode == "execute":
            result = {"mode": "CREATED", "plannedDelta": self.module.EXPECTED_DELTA}
        elif mode == "verify":
            result = {"mode": "VERIFIED_ORIGINAL", "plannedDelta": {}}
        else:
            raise AssertionError(mode)
        return subprocess.CompletedProcess(command, 0, stdout=(json.dumps(result) + "\n").encode(), stderr=b"")

    def java_modes(self):
        marker = "org.springframework.boot.loader.launch.PropertiesLauncher"
        return [command[command.index(marker) + 1] for command, _ in self.calls if marker in command]


class R1BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.folder = Path(tempfile.mkdtemp()).resolve()
        self.addCleanup(shutil.rmtree, self.folder)
        self.root = self.folder / "repo"
        self.run = "unit-" + uuid.uuid4().hex[:12]
        self.runtime = self.root / ".artifacts/r1-e2e" / self.run
        (self.runtime / "artifacts").mkdir(parents=True)
        (self.runtime / "secrets").mkdir()
        (self.runtime / "certs").mkdir()
        (self.root / "database/schema-contract-52-plus-2/generated").mkdir(parents=True)
        (self.root / "e2e/fixtures").mkdir(parents=True)
        (self.root / "e2e/runtime").mkdir(parents=True)
        (self.root / "e2e/runtime/r1-bootstrap-logback.xml").write_text(
            "<configuration><appender name=\"STDERR\" class=\"ch.qos.logback.core.ConsoleAppender\">"
            "<target>System.err</target><encoder><pattern>%msg%n</pattern></encoder></appender>"
            "<root level=\"INFO\"><appender-ref ref=\"STDERR\"/></root></configuration>\n",
            encoding="utf-8",
        )
        (self.root / "e2e/fixtures/r1-fixture.json").write_text(json.dumps({
            "profile": "R1_E2E_FIXTURE_INPUT_V1",
            "tenants": [
                {"key": "primary", "code": "R1_E2E_MAIN", "displayName": "R1 synthetic firm"},
                {"key": "isolation", "code": "R1_E2E_ISOLATION", "displayName": "R1 isolation sentinel"},
            ],
            "accounts": [
                {"key": "founder", "usernameStem": "founder", "displayName": "Synthetic Founder"},
            ],
        }), encoding="utf-8")
        self.jar = self.runtime / "artifacts/app.jar"
        self.jar.write_bytes(b"same jar")
        self.schema = self.root / "database/schema-contract-52-plus-2/generated/schema-contract-manifest.json"
        self.schema.write_text('{"contractVersion":"52-plus-2-v1.2"}\n', encoding="utf-8")
        for index, name in enumerate((
            "directory-secret", "api-db-password", "trust-password", "bootstrap-key",
            "tenant-subject-hmac", "actor-scope-hmac",
        ), start=1):
            (self.runtime / f"secrets/{name}.txt").write_text(
                base64.b64encode(bytes([index]) * 32).decode() + "\n", encoding="ascii"
            )
        (self.runtime / "certs/ca.pem").write_text("synthetic ca", encoding="ascii")
        (self.runtime / "certs/application-trust.p12").write_bytes(b"synthetic trust")
        java = self.runtime / "synthetic-java"
        java.write_bytes(b"java")
        self.manifest = {
            "profile": "R1_E2E_ENVIRONMENT_V1",
            "run": self.run,
            "composeProject": "ontology-law-r1-e2e-" + self.run,
            "issuer": "https://localhost:29443/realms/r1-e2e",
            "sourceDigests": {},
            "requiredFiles": {},
            "artifacts": {"jar": {"path": "artifacts/app.jar", "sha256": hashlib.sha256(b"same jar").hexdigest()}},
            "schemaManifestSha256": hashlib.sha256(self.schema.read_bytes()).hexdigest(),
            "hostTools": {"java": {"path": str(java), "build": "25.0.4.1+1-LTS"}},
        }
        (self.runtime / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.write_state("INFRASTRUCTURE_READY")
        self.boundaries = contextlib.ExitStack()
        self.addCleanup(self.boundaries.close)
        self.boundaries.enter_context(patch.object(self.module.environment, "_assert_unchanged"))
        self.boundaries.enter_context(patch.object(self.module.environment, "_verify_private_boundary"))

    def write_state(self, phase):
        (self.runtime / "state.json").write_text(json.dumps({
            "profile": "R1_E2E_RUN_STATE_V1", "run": self.run, "phase": phase,
            "applicationReady": False, "applicationStatus": "BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED",
            "events": [],
        }), encoding="utf-8")

    def run_bootstrap(self, process=None):
        process = process or ControlledProcess(self.module, self.manifest)
        self.module.bootstrap_identities(self.root, self.run, command_runner=process)
        return process

    def test_bootstrap_uses_exact_existing_command_sequence_and_isolated_tenant_inputs(self):
        environment_manifest_before = (self.runtime / "manifest.json").read_bytes()
        process = self.run_bootstrap()

        self.assertEqual(
            ["candidate", "dry-run", "execute", "verify"] * 2,
            process.java_modes(),
        )
        bootstrap = self.runtime / "identity-bootstrap"
        settings = [json.loads((bootstrap / key / "settings.json").read_text()) for key in ("main", "isolation")]
        originals = [json.loads((bootstrap / key / "original-manifest.json").read_text()) for key in ("main", "isolation")]
        self.assertEqual(["R1_E2E_MAIN", "R1_E2E_ISOLATION"], [value["tenantCode"] for value in settings])
        self.assertEqual(["R1_E2E_MAIN", "R1_E2E_ISOLATION"], [value["identityProviderCode"] for value in settings])
        self.assertEqual(["R1 synthetic firm", "R1 isolation sentinel"], [value["tenantDisplayName"] for value in originals])
        self.assertEqual(["R1 synthetic firm root", "R1 isolation sentinel root"], [value["rootDisplayName"] for value in originals])
        self.assertEqual({"ROOT"}, {value["rootCode"] for value in originals})
        self.assertEqual({"Synthetic Founder"}, {value["principalDisplayName"] for value in originals})
        self.assertEqual({"MVP-2026-09-08.3"}, {value["semanticBaseline"] for value in settings})
        self.assertEqual({"52-plus-2-v1.2"}, {value["database"]["schemaVersion"] for value in settings})
        self.assertEqual({"Controlled R1 isolated synthetic identity bootstrap"}, {value["operatorAssertion"] for value in settings})
        self.assertEqual({"R1_E2E_BOOTSTRAP"}, {value["node"] for value in settings})
        self.assertEqual({"r1-e2e-bootstrap-v1"}, {value["activeBootstrapKeyId"] for value in settings})
        self.assertEqual(2, len({value["tenantId"] for value in settings}))
        subject_paths = [Path(value["subjectHmacPath"]) for value in settings]
        subject_values = [path.read_bytes().strip() for path in subject_paths]
        other_values = {path.read_bytes().strip() for path in (self.runtime / "secrets").glob("*.txt")}
        self.assertEqual(2, len(set(subject_values)))
        self.assertTrue(all(value not in other_values for value in subject_values))
        self.assertEqual(
            [str((self.runtime / "secrets/bootstrap-key.txt").resolve())] * 2,
            [value["bootstrapKeyPaths"]["r1-e2e-bootstrap-v1"] for value in settings],
        )
        self.assertEqual(
            [f"r1-{self.run}-founder"] * 2,
            [(bootstrap / key / "founder-identifier.txt").read_text().strip() for key in ("main", "isolation")],
        )
        for command, _ in process.calls:
            if "org.springframework.boot.loader.launch.PropertiesLauncher" in command:
                self.assertIn("-Xmx256m", command)
                self.assertIn("-Dstdout.encoding=UTF-8", command)
                self.assertIn("-Dstderr.encoding=UTF-8", command)
                self.assertFalse(any(value.startswith("-J-D") for value in command))
                logging_options = [value for value in command if value.startswith("-Dlogback.configurationFile=")]
                self.assertEqual(1, len(logging_options))
                self.assertEqual(
                    str((bootstrap / "logback.xml").resolve()),
                    logging_options[0].split("=", 1)[1],
                )
                if "execute" in command:
                    self.assertEqual("--confirm-bootstrap", command[-1])
        state = json.loads((self.runtime / "state.json").read_text())
        self.assertEqual("IDENTITY_BOOTSTRAP_VERIFIED", state["phase"])
        self.assertFalse(state["applicationReady"])
        self.assertNotIn("READY", state.get("applicationStatus", ""))
        self.assertEqual(2, process.fact_calls)
        record = json.loads((bootstrap / "record.json").read_text())
        self.assertEqual("e2e/runtime/r1-bootstrap-logback.xml", record["loggingConfigSourcePath"])
        self.assertEqual("identity-bootstrap/logback.xml", record["loggingConfigPath"])
        self.assertEqual(
            hashlib.sha256((bootstrap / "logback.xml").read_bytes()).hexdigest(),
            record["loggingConfigSha256"],
        )
        self.assertEqual(environment_manifest_before, (self.runtime / "manifest.json").read_bytes())
        sql_inputs = [input_bytes for command, input_bytes in process.calls if command[:2] == ["docker", "compose"] and "exec" in command]
        self.assertTrue(sql_inputs)
        self.assertTrue(all(b"BEGIN READ ONLY" in sql for sql in sql_inputs))
        self.assertTrue(all(not re.search(rb"\b(INSERT|UPDATE|DELETE|GRANT)\b", sql) for sql in sql_inputs))

    def test_bootstrap_preserves_stage_exits_and_redacted_fact_references(self):
        self.run_bootstrap()
        bootstrap = self.runtime / "identity-bootstrap"
        record = json.loads((bootstrap / "record.json").read_text())
        for key, digest in (("main", "a" * 64), ("isolation", "b" * 64)):
            entry = next(value for value in record["tenants"] if value["tenantCode"] == (
                "R1_E2E_MAIN" if key == "main" else "R1_E2E_ISOLATION"
            ))
            self.assertEqual(
                {"candidate", "dry-run", "execute", "verify", "fact-query"},
                set(entry["stageOutputs"]),
            )
            for mode, streams in entry["stageOutputs"].items():
                for stream in ("stdout", "stderr"):
                    path = bootstrap / key / f"{mode}.{stream}"
                    self.assertEqual(path.relative_to(self.runtime).as_posix(), streams[f"{stream}Path"])
                    self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), streams[f"{stream}Sha256"])
            operation = json.loads((bootstrap / key / "operation.json").read_text())
            self.assertEqual(
                [(mode, 0) for mode in ("candidate", "dry-run", "execute", "verify", "fact-query")],
                [(stage["mode"], stage["exitCode"]) for stage in operation["stages"]],
            )
            facts = json.loads((bootstrap / key / "fact-references.json").read_text())
            self.assertEqual("ORIGINAL_VERIFY_AND_CONSTRAINED_READ_ONLY_QUERY", facts["source"])
            self.assertEqual(digest, facts["originalVerificationEvidenceSha256"])
            self.assertEqual(
                ["identity.tenant", "identity.organization_unit", "identity.principal", "identity.appointment"],
                [reference["type"] for reference in facts["facts"][:4]],
            )
            self.assertEqual(4, len([reference for reference in facts["facts"] if reference["type"] == "identity.authority_grant"]))
            self.assertNotIn("providerUserSelector", json.dumps(facts))

    def test_wrong_environment_integrity_or_permissions_stop_before_candidate(self):
        process = ControlledProcess(self.module, self.manifest)
        for problem in ("state", "digest", "permission"):
            with self.subTest(problem=problem):
                self.write_state("PREPARED" if problem == "state" else "INFRASTRUCTURE_READY")
                self.module.environment._assert_unchanged.reset_mock()
                self.module.environment._verify_private_boundary.reset_mock()
                self.module.environment._assert_unchanged.side_effect = RuntimeError("digest drift") if problem == "digest" else None
                self.module.environment._verify_private_boundary.side_effect = RuntimeError("permission mismatch") if problem == "permission" else None
                with self.assertRaises(RuntimeError):
                    self.run_bootstrap(process)
                self.assertEqual([], process.java_modes())
                shutil.rmtree(self.runtime / "identity-bootstrap", ignore_errors=True)

    def test_database_deployment_mismatch_stops_before_candidate(self):
        process = ControlledProcess(self.module, self.manifest, deployment_override={"releaseDigest": "f" * 64})
        with self.assertRaisesRegex(RuntimeError, "deployment state"):
            self.run_bootstrap(process)
        self.assertEqual([], process.java_modes())
        self.assertFalse((self.runtime / "identity-bootstrap").exists())

    def test_fact_query_uses_only_the_existing_query_role_classified_audit_view(self):
        sql = self.module._fact_query(str(uuid.uuid4()), "R1_E2E_MAIN", str(uuid.uuid4())).decode("utf-8")
        self.assertIn("BEGIN READ ONLY", sql)
        self.assertIn("SET LOCAL ROLE law_app_query", sql)
        self.assertIn("FROM audit.audit_entry_classified_v AS bootstrap_audit", sql)
        self.assertNotRegex(sql, r"FROM\s+audit\.audit_entry(?:\s|$)")
        self.assertNotRegex(sql, r"\b(?:INSERT|UPDATE|DELETE|GRANT)\b")

    def test_repeat_entry_refuses_without_replaying_any_command(self):
        self.run_bootstrap()
        before = (self.runtime / "identity-bootstrap/record.json").read_bytes()
        forbidden = ControlledProcess(self.module, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "existing identity bootstrap"):
            self.run_bootstrap(forbidden)
        self.assertEqual([], forbidden.calls)
        self.assertEqual(before, (self.runtime / "identity-bootstrap/record.json").read_bytes())

    def test_candidate_failure_does_not_create_manifest_or_continue(self):
        process = ControlledProcess(self.module, self.manifest, fail_mode="candidate")
        with self.assertRaisesRegex(RuntimeError, "candidate failed"):
            self.run_bootstrap(process)
        self.assertEqual(["candidate"], process.java_modes())
        self.assertFalse((self.runtime / "identity-bootstrap/main/original-manifest.json").exists())

    def test_candidate_output_persistence_failure_records_the_known_exit_and_stops(self):
        process = ControlledProcess(self.module, self.manifest)
        original_write = self.module.environment._write

        def fail_candidate_stdout(path, value):
            if Path(path).name == "candidate.stdout":
                raise OSError("synthetic protected file failure")
            return original_write(path, value)

        with patch.object(self.module.environment, "_write", side_effect=fail_candidate_stdout):
            with self.assertRaisesRegex(RuntimeError, "persist"):
                self.run_bootstrap(process)
        operation = json.loads((self.runtime / "identity-bootstrap/main/operation.json").read_text())
        self.assertEqual([{"mode": "candidate", "exitCode": 0}], [
            {"mode": stage["mode"], "exitCode": stage["exitCode"]} for stage in operation["stages"]
        ])
        self.assertEqual(["candidate"], process.java_modes())
        self.assertFalse((self.runtime / "identity-bootstrap/main/original-manifest.json").exists())

    def test_execute_failure_never_verifies_queries_facts_or_starts_second_tenant(self):
        process = ControlledProcess(self.module, self.manifest, fail_mode="execute")
        with self.assertRaisesRegex(RuntimeError, "execute failed"):
            self.run_bootstrap(process)
        self.assertEqual(["candidate", "dry-run", "execute"], process.java_modes())
        self.assertEqual(0, process.fact_calls)
        self.assertFalse((self.runtime / "identity-bootstrap/isolation").exists())
        self.assertFalse((self.runtime / "identity-bootstrap/main/fact-references.json").exists())

    def test_wrong_dry_run_identity_shape_stops_before_execute(self):
        process = ControlledProcess(self.module, self.manifest, dry_run_role="OWNER")
        with self.assertRaisesRegex(RuntimeError, "dry-run preview"):
            self.run_bootstrap(process)
        self.assertEqual(["candidate", "dry-run"], process.java_modes())

    def test_invalid_utf8_fails_closed_and_retains_exact_protected_bytes(self):
        process = ControlledProcess(self.module, self.manifest, invalid_utf8_mode="candidate")
        with self.assertRaisesRegex(RuntimeError, "valid UTF-8"):
            self.run_bootstrap(process)
        self.assertEqual(b"\xff", (self.runtime / "identity-bootstrap/main/candidate.stdout").read_bytes())
        self.assertFalse((self.runtime / "identity-bootstrap/main/original-manifest.json").exists())

    def test_verify_original_only_reuses_verify_and_does_not_change_evidence(self):
        self.run_bootstrap()
        bootstrap = self.runtime / "identity-bootstrap"
        before = {path.relative_to(bootstrap): path.read_bytes() for path in bootstrap.rglob("*") if path.is_file()}
        process = ControlledProcess(self.module, self.manifest)
        verified = self.module.verify_original(self.root, self.run, command_runner=process)
        after = {path.relative_to(bootstrap): path.read_bytes() for path in bootstrap.rglob("*") if path.is_file()}
        self.assertEqual(["verify", "verify"], process.java_modes())
        self.assertEqual(0, process.fact_calls)
        self.assertEqual(before, after)
        self.assertEqual("R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1", verified["profile"])
        self.assertEqual({"R1_E2E_MAIN", "R1_E2E_ISOLATION"}, set(verified["tenants"]))
        main = verified["tenants"]["R1_E2E_MAIN"]
        self.assertEqual("identity-bootstrap/main/subject-hmac.txt", main["subjectHmacPath"])
        self.assertEqual("a" * 64, main["originalVerificationEvidenceSha256"])
        for key in ("tenantId", "rootOrganizationId", "founderPrincipalId", "appointmentId"):
            uuid.UUID(main[key])
        self.assertNotIn("commandId", json.dumps(verified))

    def test_verify_original_rejects_a_record_not_bound_by_the_run_state(self):
        self.run_bootstrap()
        state_path = self.runtime / "state.json"
        state = json.loads(state_path.read_text())
        state["identityBootstrapRecordSha256"] = "0" * 64
        state_path.write_text(json.dumps(state), encoding="utf-8")
        process = ControlledProcess(self.module, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "record mismatch"):
            self.module.verify_original(self.root, self.run, command_runner=process)
        self.assertEqual([], process.java_modes())

    def test_verify_original_rejects_logging_configuration_drift_before_java(self):
        self.run_bootstrap()
        targets = (
            self.runtime / "identity-bootstrap/logback.xml",
            self.root / "e2e/runtime/r1-bootstrap-logback.xml",
        )
        for target in targets:
            for mutation in ("delete", "modify"):
                with self.subTest(target=target.name, mutation=mutation):
                    original = target.read_bytes()
                    if mutation == "delete":
                        target.unlink()
                    else:
                        target.write_bytes(original + b"tampered")
                    try:
                        process = ControlledProcess(self.module, self.manifest)
                        with self.assertRaisesRegex(RuntimeError, "logging configuration"):
                            self.module.verify_original(self.root, self.run, command_runner=process)
                        self.assertEqual([], process.java_modes())
                    finally:
                        target.write_bytes(original)

    def test_verify_original_rejects_deleted_or_modified_original_stage_output_before_java(self):
        self.run_bootstrap()
        for tenant in ("main", "isolation"):
            folder = self.runtime / f"identity-bootstrap/{tenant}"
            for mode in ("candidate", "dry-run", "execute", "verify"):
                for stream in ("stdout", "stderr"):
                    for mutation in ("delete", "modify"):
                        with self.subTest(tenant=tenant, mode=mode, stream=stream, mutation=mutation):
                            path = folder / f"{mode}.{stream}"
                            original = path.read_bytes()
                            if mutation == "delete":
                                path.unlink()
                            else:
                                path.write_bytes(original + b"tampered")
                            try:
                                process = ControlledProcess(self.module, self.manifest)
                                with self.assertRaisesRegex(RuntimeError, "evidence mismatch"):
                                    self.module.verify_original(self.root, self.run, command_runner=process)
                                self.assertEqual([], process.java_modes())
                            finally:
                                path.write_bytes(original)

    def test_cli_output_never_contains_selector_key_password_or_token_material(self):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(self.module, "bootstrap_identities", return_value=None):
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                exit_code = self.module.main(["--root", str(self.root), "bootstrap", self.run])
        public = output.getvalue() + errors.getvalue()
        self.assertEqual(0, exit_code)
        self.assertIn("IDENTITY_BOOTSTRAP_VERIFIED", public)
        for forbidden in (
            "selector-secret", "r1-e2e-bootstrap-v1",
            (self.runtime / "secrets/bootstrap-key.txt").read_text().strip(), "password", "token",
        ):
            self.assertNotIn(forbidden, public.lower() if forbidden in {"password", "token"} else public)


class R1BootstrapRealLoggingTest(unittest.TestCase):
    def test_fixed_java_routes_project_logging_to_stderr_and_keeps_stdout_single_json(self):
        module = load_module()
        toolchain = module.environment.resolve_host_toolchain()
        javac = toolchain.java.with_name("javac.exe" if sys.platform == "win32" else "javac")
        jar = ROOT / "backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar"
        config = ROOT / "e2e/runtime/r1-bootstrap-logback.xml"
        source = ROOT / "tests/fixtures/R1BootstrapLoggingProbe.java"
        with tempfile.TemporaryDirectory() as folder:
            classes = Path(folder).resolve()
            compiled = subprocess.run(
                [str(javac), "-J-Xmx64m", "-d", str(classes), str(source)],
                cwd=ROOT, capture_output=True, text=False,
            )
            self.assertEqual(0, compiled.returncode, compiled.stderr.decode("utf-8", errors="replace"))
            command = module._java_command(toolchain.java, jar, config)
            main_index = command.index(f"-Dloader.main={module.JAVA_MAIN}")
            command[main_index] = "-Dloader.main=R1BootstrapLoggingProbe"
            command.insert(main_index, f"-Dloader.path={classes}")
            completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=False)
        stdout = completed.stdout.decode("utf-8", errors="strict")
        stderr = completed.stderr.decode("utf-8", errors="strict")
        self.assertEqual(0, completed.returncode, stderr)
        self.assertEqual({"mode": "PROBE"}, json.loads(stdout))
        self.assertEqual(1, len(stdout.splitlines()))
        self.assertIn("r1-bootstrap-probe-log", stderr)
        self.assertNotIn("r1-bootstrap-probe-log", stdout)


if __name__ == "__main__":
    unittest.main()
