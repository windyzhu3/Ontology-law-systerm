import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
import uuid
from dataclasses import replace
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PYTHON = Path(sys.executable)
CLI = ROOT / "e2e/runtime/r1_environment.py"


def load_module():
    spec = importlib.util.spec_from_file_location("r1_environment", CLI)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class R1EnvironmentCliTest(unittest.TestCase):
    def test_prepare_rejects_a_run_id_that_could_escape_the_owned_directory(self) -> None:
        completed = subprocess.run(
            [str(PYTHON), str(CLI), "prepare", "../outside"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )

        self.assertEqual(2, completed.returncode)
        self.assertIn("invalid run id", completed.stderr)
        self.assertFalse((ROOT / ".artifacts/outside").exists())


class R1EnvironmentPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.module = load_module()
        cls.run_id = "unit-" + uuid.uuid4().hex[:12]
        cls.runtime = ROOT / ".artifacts/r1-e2e" / cls.run_id
        cls.module.prepare_environment(ROOT, cls.run_id)

    @classmethod
    def tearDownClass(cls) -> None:
        base = (ROOT / ".artifacts/r1-e2e").resolve()
        target = cls.runtime.resolve()
        if target.parent == base and target.exists():
            shutil.rmtree(target)

    def test_prepare_generates_real_crypto_and_exact_nonsecret_topology(self) -> None:
        manifest = json.loads((self.runtime / "manifest.json").read_text(encoding="utf-8"))
        state = json.loads((self.runtime / "state.json").read_text(encoding="utf-8"))
        realm = json.loads((self.runtime / "keycloak/r1-e2e-realm.json").read_text(encoding="utf-8"))
        secrets = [path.read_text(encoding="utf-8") for path in (self.runtime / "secrets").glob("*.txt")]

        self.assertEqual("R1_E2E_ENVIRONMENT_V1", manifest["profile"])
        self.assertEqual("PREPARED", state["phase"])
        self.assertEqual("BLOCKED_IDENTITY_BOOTSTRAP_REQUIRED", state["applicationStatus"])
        self.assertFalse(state["applicationReady"])
        self.assertEqual("ontology-law-r1-e2e-" + self.run_id, manifest["composeProject"])
        self.assertEqual(
            {"keycloak": 29443, "spa": 29444, "api": 29445, "businessDatabase": 29446},
            manifest["ports"],
        )
        self.assertTrue(manifest["composeConfigValidated"])
        self.assertTrue(manifest["secretBoundaryValidated"])
        self.assertEqual("r1-e2e", realm["realm"])
        self.assertEqual(["https://localhost:29444/auth/callback"], realm["clients"][0]["redirectUris"])
        self.assertEqual("r1-e2e-api", realm["clients"][1]["clientId"])
        self.assertEqual("r1-e2e-directory", realm["clients"][2]["clientId"])
        human_users = [user for user in realm["users"] if "credentials" in user]
        self.assertEqual(5, len(human_users))
        self.assertTrue(all(user["username"].startswith("r1-") for user in human_users))
        self.assertEqual(
            ["query-users", "view-users"],
            realm["users"][-1]["clientRoles"]["realm-management"],
        )
        self.assertGreaterEqual(len(secrets), 15)
        self.assertEqual(len(secrets), len(set(secrets)))
        self.assertTrue(all(len(value.strip()) >= 32 for value in secrets))
        self.assertTrue((self.runtime / "certs/ca.pem").read_text(encoding="utf-8").startswith("-----BEGIN CERTIFICATE-----"))
        self.assertGreater((self.runtime / "certs/application-trust.p12").stat().st_size, 0)
        self.assertEqual("UNPROVEN_EXISTING_ARTIFACT", manifest["artifacts"]["jar"]["provenance"])
        self.assertEqual("UNPROVEN_EXISTING_ARTIFACT", manifest["artifacts"]["spa"]["provenance"])

    def test_generated_secrets_do_not_leak_into_nonsecret_run_records(self) -> None:
        public_bytes = b"".join(
            (self.runtime / name).read_bytes()
            for name in ("manifest.json", "state.json", "compose.env", "deployment-state.sql")
        )
        for path in (self.runtime / "secrets").glob("*.txt"):
            self.assertNotIn(path.read_bytes().strip(), public_bytes)

    def test_prepare_refuses_to_replace_or_take_over_the_existing_run(self) -> None:
        before = (self.runtime / "manifest.json").read_bytes()
        with self.assertRaisesRegex(RuntimeError, "existing run"):
            self.module.prepare_environment(ROOT, self.run_id)
        self.assertEqual(before, (self.runtime / "manifest.json").read_bytes())

    def test_preflight_stops_before_docker_when_any_fixed_port_is_occupied(self) -> None:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 29443))
            with self.assertRaisesRegex(RuntimeError, "fixed port occupied: 29443"):
                self.module.preflight_start(ROOT, self.run_id)
        state = json.loads((self.runtime / "state.json").read_text(encoding="utf-8"))
        self.assertEqual("PREPARED", state["phase"])

    def test_start_apps_is_fail_closed_without_the_owned_identity_consumer(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "identity bootstrap.*later Task10 unit"):
            self.module.start_applications(ROOT, self.run_id)
        state = json.loads((self.runtime / "state.json").read_text(encoding="utf-8"))
        self.assertFalse(state["applicationReady"])

    def test_preflight_rejects_consumed_secret_or_private_key_drift_before_docker(self) -> None:
        def forbidden_command(*args, **kwargs):
            raise AssertionError("Docker must not be invoked after protected input drift")

        for relative in ("secrets/api-db-password.txt", "certs/keycloak.key"):
            with self.subTest(relative=relative):
                target = self.runtime / relative
                original = target.read_bytes()
                try:
                    target.write_bytes(original + b"drift")
                    with self.assertRaisesRegex(RuntimeError, "prepared file drift"):
                        self.module.preflight_start(ROOT, self.run_id, command_runner=forbidden_command)
                finally:
                    target.write_bytes(original)

    def test_preflight_rejects_missing_or_extra_required_file_manifest_entries(self) -> None:
        def forbidden_command(*args, **kwargs):
            raise AssertionError("Docker must not be invoked after manifest-set drift")

        manifest_path = self.runtime / "manifest.json"
        original = manifest_path.read_bytes()
        for mutation in ("missing", "extra"):
            with self.subTest(mutation=mutation):
                manifest = json.loads(original)
                if mutation == "missing":
                    del manifest["requiredFiles"]["certs/ca.pem"]
                else:
                    manifest["requiredFiles"]["secrets/not-consumed.txt"] = "0" * 64
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                try:
                    with self.assertRaisesRegex(RuntimeError, "required file manifest mismatch"):
                        self.module.preflight_start(ROOT, self.run_id, command_runner=forbidden_command)
                finally:
                    manifest_path.write_bytes(original)

    def test_preflight_revalidates_actual_private_file_acl_before_docker(self) -> None:
        def forbidden_command(*args, **kwargs):
            raise AssertionError("Docker must not be invoked after ACL drift")

        target = self.runtime / "secrets/api-db-password.txt"
        if os.name == "nt":
            changed = subprocess.run(
                ["icacls", str(target), "/grant", "*S-1-5-32-545:R"], capture_output=True, text=True
            )
            self.assertEqual(0, changed.returncode, changed.stderr)
        else:
            target.chmod(0o644)
        try:
            with self.assertRaisesRegex(RuntimeError, "private.*mismatch|unauthorized principal"):
                self.module.preflight_start(ROOT, self.run_id, command_runner=forbidden_command)
        finally:
            if os.name == "nt":
                reset = subprocess.run(["icacls", str(target), "/reset"], capture_output=True, text=True)
                self.assertEqual(0, reset.returncode, reset.stderr)
            else:
                target.chmod(0o600)

    def test_runtime_login_psql_uses_verify_full_with_the_run_ca(self) -> None:
        completed = subprocess.run(
            [
                "docker", "compose", "--env-file", str(self.runtime / "compose.env"),
                "-f", str(ROOT / "e2e/compose.yaml"), "-p", "ontology-law-r1-e2e-test",
                "config", "--format", "json",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        service = json.loads(completed.stdout)["services"]["runtime-logins"]
        self.assertEqual("verify-full", service["environment"]["PGSSLMODE"])
        self.assertEqual("/r1/ca.pem", service["environment"]["PGSSLROOTCERT"])
        self.assertTrue(any(volume["target"] == "/r1/ca.pem" and volume["read_only"] for volume in service["volumes"]))


class R1EnvironmentFailureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_module()
        self.run_id = "fail-" + uuid.uuid4().hex[:12]
        self.runtime = ROOT / ".artifacts/r1-e2e" / self.run_id

    def tearDown(self) -> None:
        base = (ROOT / ".artifacts/r1-e2e").resolve()
        target = self.runtime.resolve()
        if target.parent == base and target.exists():
            shutil.rmtree(target)

    def test_crypto_failure_keeps_the_protected_failed_run_and_does_not_retry(self) -> None:
        missing = Path(tempfile.gettempdir()) / ("missing-openssl-" + uuid.uuid4().hex + ".exe")
        toolchain = replace(self.module.resolve_host_toolchain(), openssl=missing)
        with self.assertRaisesRegex(RuntimeError, "openssl executable missing"):
            self.module.prepare_environment(ROOT, self.run_id, toolchain=toolchain)
        state = json.loads((self.runtime / "state.json").read_text(encoding="utf-8"))
        self.assertEqual("FAILED", state["phase"])
        self.assertEqual("tools", state["failedStage"])
        with self.assertRaisesRegex(RuntimeError, "existing run"):
            self.module.prepare_environment(ROOT, self.run_id)

    def test_prepare_rejects_a_linked_run_path_without_touching_its_target(self) -> None:
        self.runtime.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as target:
            sentinel = Path(target) / "sentinel.txt"
            sentinel.write_text("preserve", encoding="utf-8")
            try:
                os.symlink(target, self.runtime, target_is_directory=True)
            except OSError as error:
                junction = subprocess.run(
                    ["cmd.exe", "/d", "/c", "mklink", "/J", str(self.runtime), target],
                    capture_output=True,
                    text=True,
                )
                if junction.returncode:
                    self.fail(f"neither symlink nor junction creation is available: {error}; {junction.stderr}")
            try:
                with self.assertRaisesRegex(RuntimeError, "linked path rejected"):
                    self.module.prepare_environment(ROOT, self.run_id)
                self.assertEqual("preserve", sentinel.read_text(encoding="utf-8"))
            finally:
                if getattr(self.runtime, "is_junction", lambda: False)():
                    self.runtime.rmdir()
                else:
                    self.runtime.unlink()

    def test_preflight_rejects_prepared_file_drift_before_any_start(self) -> None:
        self.module.prepare_environment(ROOT, self.run_id)
        with (self.runtime / "deployment-state.sql").open("a", encoding="utf-8") as output:
            output.write("-- drift\n")
        with self.assertRaisesRegex(RuntimeError, "prepared file drift"):
            self.module.preflight_start(ROOT, self.run_id)

    def test_compose_failure_is_recorded_once_without_deleting_the_run(self) -> None:
        self.module.prepare_environment(ROOT, self.run_id)

        def failed_compose(*args, **kwargs):
            command = args[0]
            if "up" in command:
                return subprocess.CompletedProcess(command, 17, stdout="", stderr="synthetic compose failure")
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        with self.assertRaisesRegex(RuntimeError, "compose up failed"):
            self.module.start_infrastructure(ROOT, self.run_id, command_runner=failed_compose)
        state = json.loads((self.runtime / "state.json").read_text(encoding="utf-8"))
        self.assertEqual("START_FAILED", state["phase"])
        self.assertEqual("compose-up", state["failedStage"])
        self.assertTrue(self.runtime.exists())


class R1EnvironmentToolchainTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_module()

    def test_linux_toolchain_requires_an_explicit_absolute_runtime_root(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "explicit absolute.*runtime root"):
            self.module.resolve_host_toolchain({}, platform="linux")
        with self.assertRaisesRegex(RuntimeError, "explicit absolute.*runtime root"):
            self.module.resolve_host_toolchain({"R1_E2E_TOOLCHAIN_ROOT": "relative"}, platform="linux")

    def test_linux_toolchain_uses_the_exact_pinned_layout(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            runtime_root = Path(folder).resolve()
            files = (
                runtime_root / "jdk-25.0.4.1+1/bin/java",
                runtime_root / "jdk-25.0.4.1+1/bin/keytool",
                runtime_root / "node-v24.20.0-linux-x64/bin/node",
                runtime_root / "openssl-3.5.4/bin/openssl",
            )
            for path in files:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()

            toolchain = self.module.resolve_host_toolchain(
                {"R1_E2E_TOOLCHAIN_ROOT": str(runtime_root)}, platform="linux"
            )

            self.assertEqual(files, (toolchain.java, toolchain.keytool, toolchain.node, toolchain.openssl))

    def test_java_version_validation_rejects_the_wrong_exact_build(self) -> None:
        wrong = (
            'openjdk version "25.0.4.1" 2026-08-18 LTS\n'
            'OpenJDK Runtime Environment Temurin-25.0.4.1+2 (build 25.0.4.1+2-LTS)\n'
        )
        with self.assertRaisesRegex(RuntimeError, "exact Java build mismatch"):
            self.module.validate_java_version(wrong)


if __name__ == "__main__":
    unittest.main()
