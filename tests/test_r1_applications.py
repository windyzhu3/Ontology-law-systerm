from __future__ import annotations

import base64
import json
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

from e2e.runtime import r1_applications as applications


RUN = "unit-r1"
MAIN_TENANT = "00000000-0000-4000-8000-000000000001"
MAIN_ROOT = "00000000-0000-4000-8000-000000000002"
MAIN_FOUNDER = "00000000-0000-4000-8000-000000000003"
MAIN_APPOINTMENT = "00000000-0000-4000-8000-000000000004"


class ApplicationFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "e2e" / "runtime").mkdir(parents=True)
        (self.root / "e2e" / "runtime" / "r1_server.mjs").write_bytes(
            (applications.RUNTIME_DIRECTORY / "r1_server.mjs").read_bytes()
        )
        self.runtime = self.root / ".artifacts" / "r1-e2e" / RUN
        (self.runtime / "artifacts" / "workbench-dist").mkdir(parents=True)
        (self.runtime / "artifacts" / "workbench-dist" / "index.html").write_text(
            "r1 workbench", encoding="utf-8"
        )
        (self.runtime / "artifacts" / "app.jar").write_bytes(b"same production jar")
        (self.runtime / "identity-bootstrap" / "main").mkdir(parents=True)
        self.subject_value = base64.b64encode(b"s" * 32).decode("ascii")
        (self.runtime / "identity-bootstrap" / "main" / "subject-hmac.txt").write_text(
            self.subject_value + "\n", encoding="ascii"
        )
        (self.runtime / "secrets").mkdir()
        for name, value in {
            "api-db-password": "api-db",
            "worker-db-password": "worker-db",
            "trust-password": "tls-password",
            "introspection-secret": "introspection",
            "directory-secret": "directory",
        }.items():
            (self.runtime / "secrets" / f"{name}.txt").write_text(value + "\n", encoding="utf-8")
        (self.runtime / "certs").mkdir()
        for name in ("ca.pem", "ca.key", "application-server.p12", "application-trust.p12"):
            (self.runtime / "certs" / name).write_text(name, encoding="utf-8")
        spa_digest, spa_files = applications.environment.tree_digest(
            self.runtime / "artifacts" / "workbench-dist"
        )
        self.environment_manifest = {
            "profile": "R1_E2E_ENVIRONMENT_V1",
            "run": RUN,
            "issuer": "https://localhost:29443/realms/r1-e2e",
            "composeProject": "ontology-law-r1-e2e-" + RUN,
            "schemaManifestSha256": "b" * 64,
            "artifacts": {
                "jar": {
                    "path": "artifacts/app.jar",
                    "sha256": applications._sha256(self.runtime / "artifacts" / "app.jar"),
                },
                "spa": {
                    "path": "artifacts/workbench-dist",
                    "sha256": spa_digest,
                    "files": spa_files,
                },
            },
            "hostTools": {
                "java": {"path": "C:/pinned/java.exe", "build": "25.0.4.1+1-LTS"},
                "node": {"path": "C:/pinned/node.exe", "version": "24.20.0"},
                "openssl": {"path": "C:/pinned/openssl.exe", "version": "OpenSSL 3.5.4"},
            },
        }
        (self.runtime / "manifest.json").write_text(
            json.dumps(self.environment_manifest), encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def projection(self, evidence: str = "a" * 64) -> dict:
        def tenant(offset: int, code: str, subject: str) -> dict:
            return {
                "tenantId": f"00000000-0000-4000-8000-{offset:012d}",
                "rootOrganizationId": f"00000000-0000-4000-8001-{offset:012d}",
                "founderPrincipalId": f"00000000-0000-4000-8002-{offset:012d}",
                "appointmentId": f"00000000-0000-4000-8003-{offset:012d}",
                "authorityGrantIds": [
                    f"00000000-0000-4000-8010-{offset * 10 + index:012d}" for index in range(4)
                ],
                "subjectHmacPath": subject,
                "originalVerificationEvidenceSha256": evidence,
            }

        main = tenant(1, "R1_E2E_MAIN", "identity-bootstrap/main/subject-hmac.txt")
        main.update(
            tenantId=MAIN_TENANT,
            rootOrganizationId=MAIN_ROOT,
            founderPrincipalId=MAIN_FOUNDER,
            appointmentId=MAIN_APPOINTMENT,
        )
        isolation_path = self.runtime / "identity-bootstrap" / "isolation"
        isolation_path.mkdir(exist_ok=True)
        (isolation_path / "subject-hmac.txt").write_text(
            base64.b64encode(b"i" * 32).decode("ascii") + "\n", encoding="ascii"
        )
        return {
            "profile": "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1",
            "run": RUN,
            "tenants": {
                "R1_E2E_MAIN": main,
                "R1_E2E_ISOLATION": tenant(
                    102, "R1_E2E_ISOLATION", "identity-bootstrap/isolation/subject-hmac.txt"
                ),
            },
        }

    def crypto(self, folder: Path) -> dict:
        certs = folder / "certs"
        certs.mkdir(exist_ok=True)
        for name in ("service.key", "service.crt", "service.p12", "service-public.pem"):
            (certs / name).write_text(name, encoding="utf-8")
        return {"fingerprint": "c" * 64, "alias": "r1_e2e_service"}

    def prepared(self, *, source: str = "continued", evidence: str = "a" * 64):
        projection = self.projection(evidence)
        patches = (
            mock.patch.object(applications, "_verify_bootstrap_source", return_value=projection),
            mock.patch.object(applications, "_protect_application_boundary"),
            mock.patch.object(applications, "_create_service_crypto", side_effect=self.crypto),
            mock.patch.object(applications, "_apply_service_transaction", return_value=None),
            mock.patch.object(applications, "_verify_service_fixture", return_value=None),
        )
        with patches[0] as bootstrap, patches[1], patches[2], patches[3] as apply, patches[4]:
            folder = applications.prepare_applications(
                self.root, RUN, bootstrap_source=source
            )
        return folder, projection, bootstrap, apply


class PrepareApplicationsTest(ApplicationFixture):
    def test_bootstrap_failure_or_projection_mismatch_precedes_service_state_and_writes(self) -> None:
        apply = mock.Mock()
        with mock.patch.object(
            applications, "_verify_bootstrap_source", side_effect=RuntimeError("missing")
        ), mock.patch.object(applications, "_apply_service_transaction", apply):
            with self.assertRaisesRegex(RuntimeError, "missing"):
                applications.prepare_applications(
                    self.root, RUN, bootstrap_source="continued"
                )
        self.assertFalse((self.runtime / "applications").exists())
        apply.assert_not_called()

        with mock.patch.object(
            applications, "_verify_bootstrap_source", return_value={"profile": "wrong"}
        ), mock.patch.object(applications, "_apply_service_transaction", apply):
            with self.assertRaisesRegex(RuntimeError, "bootstrap projection"):
                applications.prepare_applications(
                    self.root, RUN, bootstrap_source="continued"
                )
        self.assertFalse((self.runtime / "applications").exists())
        apply.assert_not_called()

    def test_bootstrap_source_is_explicit_and_never_falls_back(self) -> None:
        good = self.projection()
        with mock.patch.object(
            applications.bootstrap, "verify_original", side_effect=RuntimeError("original failed")
        ) as original, mock.patch.object(
            applications.bootstrap_continuation, "verify_combined", return_value=good
        ) as continued:
            with self.assertRaisesRegex(RuntimeError, "original failed"):
                applications._verify_bootstrap_source(self.root, RUN, "original")
            original.assert_called_once_with(self.root, RUN)
            continued.assert_not_called()
        with mock.patch.object(
            applications.bootstrap_continuation, "verify_combined", return_value=good
        ) as continued:
            self.assertEqual(
                applications._verify_bootstrap_source(self.root, RUN, "continued"), good
            )
            continued.assert_called_once_with(self.root, RUN)
        for invalid in ("", "auto", "fallback", None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                applications._verify_bootstrap_source(self.root, RUN, invalid)

    def test_duplicate_prepare_and_unknown_transaction_never_replay(self) -> None:
        folder, _, bootstrap, apply = self.prepared()
        self.assertEqual(folder, self.runtime / "applications")
        self.assertEqual(apply.call_count, 1)
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            applications.prepare_applications(
                self.root, RUN, bootstrap_source="continued"
            )
        self.assertEqual(bootstrap.call_count, 1)
        self.assertEqual(apply.call_count, 1)

        shutil.rmtree(folder)
        projection = self.projection()
        calls = mock.Mock(side_effect=RuntimeError("transaction result unknown"))
        with mock.patch.object(applications, "_verify_bootstrap_source", return_value=projection), \
             mock.patch.object(applications, "_protect_application_boundary"), \
             mock.patch.object(applications, "_create_service_crypto", side_effect=self.crypto), \
             mock.patch.object(applications, "_apply_service_transaction", calls):
            with self.assertRaisesRegex(RuntimeError, "unknown"):
                applications.prepare_applications(
                    self.root, RUN, bootstrap_source="continued"
                )
        self.assertTrue((self.runtime / "applications" / "service-transaction.pending").is_file())
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            applications.prepare_applications(
                self.root, RUN, bootstrap_source="continued"
            )
        self.assertEqual(calls.call_count, 1)

    def test_configuration_is_exactly_scoped_to_main_tenant_sources_and_distinct_keys(self) -> None:
        folder, _, _, _ = self.prepared()
        api = applications._read_properties(folder / "api.properties")
        worker = applications._read_properties(folder / "worker.properties")
        self.assertEqual(api["server.address"], "127.0.0.1")
        self.assertEqual(api["server.port"], "29445")
        self.assertNotIn("R1_E2E_ISOLATION", "\n".join(api))
        self.assertEqual(api["ols.api.human-trusts[0].identity-provider-code"], "R1_E2E_MAIN")
        self.assertFalse(any(key.startswith("ols.api.human-trusts[1]") for key in api))
        self.assertEqual(worker["spring.main.web-application-type"], "none")
        self.assertEqual(worker["ols.worker.database.username"], "law_worker_login")
        self.assertEqual(api["ols.api.database.username"], "law_api_login")
        self.assertIn("sslmode=verify-full", api["ols.api.database.url"])
        self.assertIn("sslmode=verify-full", worker["ols.worker.database.url"])
        prefix = f"ols.api.tenant-keys[{MAIN_TENANT}]."
        purposes = [
            "encryption", "phone-hmac", "email-hmac", "source-hmac",
            "credential-subject-hmac", "actor-scope-hmac",
        ]
        self.assertEqual(len({api[prefix + purpose] for purpose in purposes}), 6)
        self.assertEqual(api[prefix + "credential-subject-hmac"], self.subject_value)
        expected_sources = {
            "R1_AUTO": ("AUTOMATIC", "OWNED_ROOT"),
            "R1_MANUAL": ("MANUAL", "OWNED_ROOT"),
            "R1_ZERO_CANDIDATE": ("AUTOMATIC", "EMPTY_ROOT"),
        }
        for source, (mode, candidate) in expected_sources.items():
            base = f"ols.api.sources[{source}]."
            self.assertEqual(api[base + "assignment-mode"], mode)
            self.assertEqual(api[base + "routing-organization-root-codes[0]"], candidate)
            self.assertEqual(api[base + "routing-supervisor-root-code"], "ROOT")
            self.assertEqual(api[base + "source-intake-root-code"], "ROOT")
            self.assertEqual(api[base + "business-timezone"], "Asia/Shanghai")
        registration = "ols.api.registrations[0].source-account-codes["
        self.assertEqual(
            {api[registration + f"{index}]"] for index in range(3)}, set(expected_sources)
        )
        service = json.loads((folder / "service.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [grant["authorityCode"] for grant in service["grants"]],
            [
                "R1_PROJECTION_CONSUME",
                "CONTACT_TASK_RECOVER",
                "ROUTING_REVIEW_TASK_RECOVER",
            ],
        )
        self.assertTrue(all(grant["grantorAppointmentId"] == MAIN_APPOINTMENT for grant in service["grants"]))
        sql = applications._service_transaction_sql(
            service, self.environment_manifest, service["externalSubjectHmac"]
        ).decode("utf-8")
        self.assertIn("SET LOCAL ROLE law_app_command", sql)
        self.assertIn("R1_APPLICATION_SERVICE_CREATED_5", sql)
        self.assertNotIn("INSERT INTO identity.tenant", sql)
        self.assertNotIn("INSERT INTO identity.organization_unit", sql)
        self.assertEqual(sql.count("INSERT INTO identity.principal"), 1)
        self.assertEqual(sql.count("INSERT INTO identity.appointment"), 1)
        for authority in applications.SERVICE_AUTHORITIES:
            self.assertIn(authority, sql)
        commands = applications._application_commands(self.root, self.runtime, folder)
        self.assertEqual(commands["api"].count("-Xmx384m"), 1)
        self.assertEqual(commands["worker"].count("-Xmx384m"), 1)
        self.assertEqual(commands["api"][0], commands["worker"][0])
        self.assertEqual(commands["api"][5], commands["worker"][5])
        self.assertEqual(len(commands["spa"]), 4)
        self.assertTrue(commands["spa"][1].endswith("r1_server.mjs"))
        self.assertNotIn("1944", "\n".join(" ".join(value) for value in commands.values()))


class StartAndVerifyApplicationsTest(ApplicationFixture):
    def test_readiness_requires_existing_204_empty_no_etag_no_store_contract(self) -> None:
        good = [
            (200, {"Cache-Control": "no-store"}, b"spa"),
            (401, {"Cache-Control": "no-store"}, b""),
            (204, {"Cache-Control": "private, no-store"}, b""),
        ]
        with mock.patch.object(applications, "_http", side_effect=good):
            self.assertEqual(applications._probe_readiness(self.runtime)["serviceReadiness"], 204)
        for bad_service in (
            (200, {"Cache-Control": "no-store"}, b""),
            (204, {"Cache-Control": "no-store", "ETag": '"bad"'}, b""),
            (204, {"Cache-Control": "private"}, b""),
            (204, {"Cache-Control": "no-store"}, b"unexpected"),
        ):
            with self.subTest(bad_service=bad_service), mock.patch.object(
                applications, "_http", side_effect=[good[0], good[1], bad_service]
            ), self.assertRaisesRegex(RuntimeError, "readiness boundary"):
                applications._probe_readiness(self.runtime)

    def test_bootstrap_digest_drift_blocks_before_process_launch(self) -> None:
        folder, projection, _, _ = self.prepared()
        changed = json.loads(json.dumps(projection))
        changed["tenants"]["R1_E2E_MAIN"]["originalVerificationEvidenceSha256"] = "d" * 64
        launch = mock.Mock()
        with mock.patch.object(applications, "_verify_bootstrap_source", return_value=changed), \
             mock.patch.object(applications, "_protect_application_boundary"), \
             mock.patch.object(applications, "_verify_service_fixture"), \
             mock.patch.object(applications, "_launch_process", launch):
            with self.assertRaisesRegex(RuntimeError, "bootstrap input digest drift"):
                applications.start_applications(self.root, RUN)
        launch.assert_not_called()
        self.assertFalse((folder / "start.pending").exists())

    def test_api_failure_does_not_start_worker_or_spa_and_retains_original_evidence(self) -> None:
        folder, projection, _, _ = self.prepared()
        launches = []

        def launch(name, command, root, folder):
            launches.append(name)
            return {"pid": 101, "created": "2026-09-13T00:00:00Z", "command": command}

        with mock.patch.object(applications, "_verify_bootstrap_source", return_value=projection), \
             mock.patch.object(applications, "_protect_application_boundary"), \
             mock.patch.object(applications, "_verify_service_fixture"), \
             mock.patch.object(applications, "_ports_available"), \
             mock.patch.object(applications, "_launch_process", side_effect=launch), \
             mock.patch.object(applications, "_wait_for_api", side_effect=RuntimeError("API unavailable")):
            with self.assertRaisesRegex(RuntimeError, "API unavailable"):
                applications.start_applications(self.root, RUN)
        self.assertEqual(launches, ["api"])
        self.assertTrue((folder / "start.pending").is_file())
        state = json.loads((folder / "state.json").read_text(encoding="utf-8"))
        self.assertEqual(state["phase"], "START_FAILED_OR_UNCERTAIN")
        self.assertEqual(list(state["processes"]), ["api"])

    def test_pid_or_creation_time_mismatch_cannot_publish_ready(self) -> None:
        folder, projection, _, _ = self.prepared()
        counter = iter((101, 102, 103))

        def launch(name, command, root, folder):
            return {
                "pid": next(counter),
                "created": "2026-09-13T00:00:01+00:00",
                "startedAt": "2026-09-13T00:00:00+00:00",
                "command": command,
                "executable": command[0],
                "commandLine": "saved-" + name,
            }

        with mock.patch.object(applications, "_verify_bootstrap_source", return_value=projection), \
             mock.patch.object(applications, "_protect_application_boundary"), \
             mock.patch.object(applications, "_verify_service_fixture"), \
             mock.patch.object(applications, "_ports_available"), \
             mock.patch.object(applications, "_launch_process", side_effect=launch), \
             mock.patch.object(applications, "_wait_for_api"):
            applications.start_applications(self.root, RUN)
        saved = json.loads((folder / "state.json").read_text(encoding="utf-8"))["processes"]

        def snapshot(pid):
            name = next(name for name, item in saved.items() if item["pid"] == pid)
            item = dict(saved[name])
            if name == "worker":
                item["created"] = "2026-09-13T00:00:02+00:00"
            return item

        with mock.patch.object(applications, "_verify_bootstrap_source", return_value=projection), \
             mock.patch.object(applications, "_protect_application_boundary"), \
             mock.patch.object(applications, "_verify_service_fixture"), \
             mock.patch.object(applications, "_process_snapshot", side_effect=snapshot), \
             mock.patch.object(applications, "_listener_count", return_value=0), \
             mock.patch.object(applications, "_probe_readiness"):
            with self.assertRaisesRegex(RuntimeError, "process identity mismatch"):
                applications.verify_applications(self.root, RUN)
        state = json.loads((folder / "state.json").read_text(encoding="utf-8"))
        self.assertNotEqual(state["phase"], "APPLICATION_INFRASTRUCTURE_READY")


class CliTest(unittest.TestCase):
    def test_prepare_requires_explicit_bootstrap_source(self) -> None:
        with redirect_stderr(StringIO()), self.assertRaises(SystemExit):
            applications.main(["prepare", RUN])
        with mock.patch.object(applications, "prepare_applications", return_value=Path("applications")) as prepare:
            with redirect_stdout(StringIO()):
                self.assertEqual(
                    applications.main(["prepare", RUN, "--bootstrap-source", "continued"]), 0
                )
            prepare.assert_called_once_with(applications.ROOT, RUN, bootstrap_source="continued")


if __name__ == "__main__":
    unittest.main()
