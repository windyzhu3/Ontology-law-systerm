import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from tests import test_r1_bootstrap as original_tests


ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "e2e/runtime/r1_bootstrap_continuation.py"


def load_module(bootstrap):
    sys.modules["r1_bootstrap"] = bootstrap
    spec = importlib.util.spec_from_file_location("r1_bootstrap_continuation", CLI)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class ContinuationProcess(original_tests.ControlledProcess):
    def __init__(self, module, manifest, *, absence_override=None, **kwargs):
        super().__init__(module, manifest, **kwargs)
        self.absence_override = absence_override
        self.renewed_selector = "renewed-selector-secret-R1_E2E_ISOLATION"

    def __call__(self, command, **kwargs):
        sql = kwargs.get("input") or b""
        if command[:2] == ["docker", "compose"] and b"R1_ISOLATION_ABSENCE_V1" in sql:
            self.calls.append((list(map(str, command)), sql))
            row = {
                "profile": "R1_ISOLATION_ABSENCE_V1",
                "tenantId": 0,
                "tenantCode": 0,
                "principal": 0,
                "organization": 0,
                "appointment": 0,
                "authorityGrant": 0,
                "commandSlot": 0,
                "receipt": 0,
                "classifiedAudit": 0,
            }
            if self.absence_override:
                row.update(self.absence_override)
            return subprocess.CompletedProcess(
                command, 0, stdout=(json.dumps(row) + "\n").encode(), stderr=b"",
            )
        result = super().__call__(command, **kwargs)
        marker = "org.springframework.boot.loader.launch.PropertiesLauncher"
        if marker in command and command[command.index(marker) + 1] == "candidate" and result.returncode == 0:
            return subprocess.CompletedProcess(
                command, 0,
                stdout=(json.dumps({"providerUserSelector": self.renewed_selector}) + "\n").encode(),
                stderr=result.stderr,
            )
        return result


class R1BootstrapContinuationTest(unittest.TestCase):
    def setUp(self):
        self.original_case = original_tests.R1BootstrapTest(
            "test_database_deployment_mismatch_stops_before_candidate"
        )
        self.original_case.setUp()
        self.addCleanup(self.original_case.doCleanups)
        self.bootstrap = self.original_case.module
        self.root = self.original_case.root
        self.run = self.original_case.run
        self.runtime = self.original_case.runtime
        self.manifest = self.original_case.manifest
        failed = original_tests.ControlledProcess(
            self.bootstrap, self.manifest,
            fail_mode="dry-run", fail_tenant_code="R1_E2E_ISOLATION", fail_exit=1,
        )
        with self.assertRaisesRegex(RuntimeError, "dry-run failed"):
            self.bootstrap.bootstrap_identities(self.root, self.run, command_runner=failed)
        self.module = load_module(self.bootstrap)
        self.state_path = self.runtime / "state.json"
        self.record_path = self.runtime / "identity-bootstrap/record.json"
        self.state_digest = self.digest(self.state_path)
        self.record_digest = self.digest(self.record_path)

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def original_snapshot(self):
        root = self.runtime / "identity-bootstrap"
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in root.rglob("*") if path.is_file()
        }

    def continue_isolation(self, process=None):
        process = process or ContinuationProcess(self.bootstrap, self.manifest)
        self.module.continue_isolation(
            self.root, self.run, self.state_digest, self.record_digest,
            command_runner=process,
        )
        return process

    def test_accepts_only_exact_partial_shape_and_preserves_original_tree(self):
        before = self.original_snapshot()
        process = self.continue_isolation()
        after = self.original_snapshot()
        self.assertEqual(before, after)
        self.assertEqual(
            ["verify", "candidate", "dry-run", "execute", "verify"],
            process.java_modes(),
        )
        new_manifest = json.loads((
            self.runtime / "identity-bootstrap-isolation-continuation/isolation-original-manifest.json"
        ).read_text())
        old_manifest = json.loads((
            self.runtime / "identity-bootstrap/isolation/original-manifest.json"
        ).read_text())
        self.assertNotEqual(old_manifest["commandId"], new_manifest["commandId"])
        self.assertNotEqual(old_manifest["providerUserSelector"], new_manifest["providerUserSelector"])
        execute = next(
            command for command, _ in process.calls
            if "org.springframework.boot.loader.launch.PropertiesLauncher" in command and "execute" in command
        )
        self.assertEqual(str((
            self.runtime / "identity-bootstrap-isolation-continuation/isolation-original-manifest.json"
        ).resolve()), execute[-2])
        self.assertEqual("--confirm-bootstrap", execute[-1])
        state = json.loads(self.state_path.read_text())
        self.assertEqual("IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN", state["phase"])
        self.assertFalse(state["applicationReady"])

    def test_origin_digest_mismatch_stops_before_any_subprocess_or_directory(self):
        process = ContinuationProcess(self.bootstrap, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "original state digest mismatch"):
            self.module.continue_isolation(
                self.root, self.run, "0" * 64, self.record_digest, command_runner=process,
            )
        self.assertEqual([], process.calls)
        self.assertFalse((self.runtime / "identity-bootstrap-isolation-continuation").exists())

    def test_wrong_partial_stage_shape_and_duplicate_main_entry_are_rejected(self):
        operation_path = self.runtime / "identity-bootstrap/isolation/operation.json"
        record = json.loads(self.record_path.read_text())
        mutations = (
            (operation_path, lambda value: value["stages"].append({"mode": "execute", "exitCode": 0})),
            (self.record_path, lambda value: value["tenants"].append(value["tenants"][0])),
        )
        for path, mutate in mutations:
            with self.subTest(path=path.name):
                original = path.read_bytes()
                value = json.loads(original)
                mutate(value)
                path.write_text(json.dumps(value), encoding="utf-8")
                try:
                    process = ContinuationProcess(self.bootstrap, self.manifest)
                    with self.assertRaises(RuntimeError):
                        self.module.continue_isolation(
                            self.root, self.run, self.digest(self.state_path), self.digest(self.record_path),
                            command_runner=process,
                        )
                    self.assertEqual([], process.calls)
                finally:
                    path.write_bytes(original)

    def test_main_is_only_verified_then_absence_is_proved_before_new_candidate(self):
        process = self.continue_isolation()
        java = process.java_modes()
        self.assertEqual("verify", java[0])
        self.assertEqual(1, java[:java.index("candidate")].count("verify"))
        calls = process.calls
        absence = next(i for i, (_, body) in enumerate(calls) if body and b"R1_ISOLATION_ABSENCE_V1" in body)
        candidate = next(i for i, (command, _) in enumerate(calls) if "candidate" in command)
        self.assertLess(absence, candidate)
        sql = calls[absence][1]
        self.assertIn(b"BEGIN READ ONLY", sql)
        self.assertIn(b"SET LOCAL ROLE law_app_query", sql)
        self.assertIn(b"audit.audit_entry_classified_v", sql)
        self.assertIn(b"execution.command_execution_slot", sql)
        self.assertNotRegex(sql, rb"\b(?:INSERT|UPDATE|DELETE|GRANT)\b")

    def test_absence_or_collision_rejects_before_new_candidate(self):
        for key in ("tenantId", "tenantCode", "commandSlot", "classifiedAudit"):
            with self.subTest(key=key):
                process = ContinuationProcess(self.bootstrap, self.manifest, absence_override={key: 1})
                with self.assertRaisesRegex(RuntimeError, "isolation absence proof"):
                    self.module.continue_isolation(
                        self.root, self.run, self.state_digest, self.record_digest,
                        command_runner=process,
                    )
                self.assertEqual(["verify"], process.java_modes())
                shutil.rmtree(self.runtime / "identity-bootstrap-isolation-continuation")

    def test_execute_failure_is_retained_without_retry_and_original_is_unchanged(self):
        before = self.original_snapshot()
        process = ContinuationProcess(
            self.bootstrap, self.manifest, fail_mode="execute", fail_tenant_code="R1_E2E_ISOLATION",
        )
        with self.assertRaisesRegex(RuntimeError, "execute failed"):
            self.continue_isolation(process)
        self.assertEqual(["verify", "candidate", "dry-run", "execute"], process.java_modes())
        self.assertEqual(before, self.original_snapshot())
        with self.assertRaisesRegex(RuntimeError, "existing isolation continuation"):
            self.continue_isolation(ContinuationProcess(self.bootstrap, self.manifest))

    def test_origin_change_before_execute_stops_without_execute(self):
        process = ContinuationProcess(self.bootstrap, self.manifest)
        original = process.__call__

        def mutate_after_dry_run(command, **kwargs):
            result = original(command, **kwargs)
            if "org.springframework.boot.loader.launch.PropertiesLauncher" in command and "dry-run" in command:
                self.state_path.write_bytes(self.state_path.read_bytes() + b" ")
            return result

        with self.assertRaisesRegex(RuntimeError, "original state changed"):
            self.module.continue_isolation(
                self.root, self.run, self.state_digest, self.record_digest,
                command_runner=mutate_after_dry_run,
            )
        self.assertEqual(["verify", "candidate", "dry-run"], process.java_modes())

    def test_successful_continuation_rejects_repeat_and_normal_verifier_stays_strict(self):
        self.continue_isolation()
        with self.assertRaisesRegex(RuntimeError, "existing isolation continuation"):
            self.continue_isolation(ContinuationProcess(self.bootstrap, self.manifest))
        normal = original_tests.ControlledProcess(self.bootstrap, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "not consumable"):
            self.bootstrap.verify_original(self.root, self.run, command_runner=normal)
        self.assertEqual([], normal.java_modes())

    def test_combined_verifier_validates_all_evidence_then_invokes_only_verify(self):
        self.continue_isolation()
        process = ContinuationProcess(self.bootstrap, self.manifest)
        result = self.module.verify_combined(self.root, self.run, command_runner=process)
        self.assertEqual(["verify", "verify"], process.java_modes())
        self.assertEqual("R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1", result["profile"])
        self.assertEqual({"R1_E2E_MAIN", "R1_E2E_ISOLATION"}, set(result["tenants"]))
        for tenant in result["tenants"].values():
            self.assertNotIn("commandId", tenant)
            uuid.UUID(tenant["tenantId"])

    def test_combined_verifier_rejects_deleted_or_modified_old_and_new_raw_output_before_java(self):
        self.continue_isolation()
        targets = (
            self.runtime / "identity-bootstrap/main/execute.stdout",
            self.runtime / "identity-bootstrap-isolation-continuation/execute.stdout",
        )
        for target in targets:
            for mutation in ("delete", "modify"):
                with self.subTest(target=target.parent.name, mutation=mutation):
                    original = target.read_bytes()
                    target.unlink() if mutation == "delete" else target.write_bytes(original + b"tampered")
                    try:
                        process = ContinuationProcess(self.bootstrap, self.manifest)
                        with self.assertRaises(RuntimeError):
                            self.module.verify_combined(self.root, self.run, command_runner=process)
                        self.assertEqual([], process.java_modes())
                    finally:
                        target.write_bytes(original)

    def test_origin_change_before_final_promotion_leaves_no_completion(self):
        process = ContinuationProcess(self.bootstrap, self.manifest)
        original = process.__call__

        def mutate_after_fact_query(command, **kwargs):
            result = original(command, **kwargs)
            sql = kwargs.get("input") or b""
            if b"BOOTSTRAP_IDENTITY_ADMIN" in sql:
                self.record_path.write_bytes(self.record_path.read_bytes() + b" ")
            return result

        with self.assertRaisesRegex(RuntimeError, "before final promotion"):
            self.module.continue_isolation(
                self.root, self.run, self.state_digest, self.record_digest,
                command_runner=mutate_after_fact_query,
            )
        self.assertEqual(
            ["verify", "candidate", "dry-run", "execute", "verify"], process.java_modes(),
        )
        self.assertFalse((
            self.runtime / "identity-bootstrap-isolation-continuation/completion.json"
        ).exists())

    def test_combined_verifier_rejects_path_escape_and_private_boundary_failure(self):
        self.continue_isolation()
        continuation = self.runtime / "identity-bootstrap-isolation-continuation"
        record_path = continuation / "record.json"
        completion_path = continuation / "completion.json"
        record_bytes, completion_bytes = record_path.read_bytes(), completion_path.read_bytes()
        record = json.loads(record_bytes)
        record["isolation"]["settingsPath"] = "../outside.json"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        completion = json.loads(completion_bytes)
        completion["recordSha256"] = self.digest(record_path)
        completion_path.write_text(json.dumps(completion), encoding="utf-8")
        process = ContinuationProcess(self.bootstrap, self.manifest)
        with self.assertRaisesRegex(RuntimeError, "continuation path mismatch"):
            self.module.verify_combined(self.root, self.run, command_runner=process)
        self.assertEqual([], process.java_modes())
        record_path.write_bytes(record_bytes)
        completion_path.write_bytes(completion_bytes)
        self.bootstrap.environment._verify_private_boundary.side_effect = RuntimeError("private boundary")
        with self.assertRaisesRegex(RuntimeError, "private boundary"):
            self.module.verify_combined(self.root, self.run, command_runner=process)
        self.assertEqual([], process.java_modes())


if __name__ == "__main__":
    unittest.main()
