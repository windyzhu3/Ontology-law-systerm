"""Contract tests for the PostgreSQL runtime-verification harness."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class RuntimeHarnessTests(unittest.TestCase):
    def _successful_runtime_stage(
        self,
        command: list[str],
        *,
        evidence_path: Path,
        cwd: Path,
        timeout_seconds: float,
    ) -> int:
        """Emulate Compose itself while exercising the real orchestration decisions."""
        del cwd, timeout_seconds
        stdout = ""
        stderr = ""
        return_code = 0
        scenario_messages = {
            "missing-role": "configured application database role does not exist",
            "extra-managed-table": "expected 52 application tables",
            "forbidden-delete-grant": "forbidden DELETE or TRUNCATE",
            "missing-mutation-guard": "mutation guard coverage mismatch",
        }
        if "ps" in command:
            stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}])
        elif "logs" in command and command[-1] == "verifier":
            stdout = json.dumps(
                {
                    "fingerprint": "0123456789abcdef0123456789abcdef",
                    "postgresVersion": (
                        "PostgreSQL 18.0 (Debian 18.0-1.pgdg13+3) on x86_64-pc-linux-gnu, "
                        "compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit"
                    ),
                    "serverVersion": "18.0",
                    "status": "PASSED",
                }
            ) + "\n"
        elif evidence_path.name == "postgres-image.json":
            stdout = json.dumps(
                ["docker.io/library/postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"]
            )
        elif evidence_path.name == "flyway-image.json":
            stdout = json.dumps(
                ["docker.io/redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93"]
            )
        elif evidence_path.name == "flyway-version.json":
            stdout = "Flyway Community Edition 13.4.0 by Redgate\n"
        elif evidence_path.name == "expected-failure.json":
            return_code = 17
            stderr = scenario_messages[evidence_path.parent.name]
        elif evidence_path.name == "strict-validate.json":
            return_code = 17
            stderr = "checksum mismatch for migration version 010"
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        evidence_path.write_text(
            json.dumps({"returncode": return_code, "stderr": stderr, "stdout": stdout}),
            encoding="utf-8",
        )
        return return_code

    def test_staged_detached_services_wait_for_each_exit_with_explicit_budgets(self) -> None:
        """Break caught: a one-shot Flyway exit aborts the stack or a stage falls back to 60 seconds."""
        from runtime import verify_runtime

        calls: list[tuple[list[str], float]] = []
        temporary_paths: set[Path] = set()
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"

            def successful_stage(
                command: list[str],
                *,
                evidence_path: Path,
                cwd: Path,
                timeout_seconds: float,
            ) -> int:
                calls.append((command, timeout_seconds))
                if "--env-file" in command:
                    temporary_paths.add(Path(command[command.index("--env-file") + 1]))
                    temporary_paths.add(Path(command[[index for index, value in enumerate(command) if value == "-f"][-1] + 1]))
                return self._successful_runtime_stage(
                    command,
                    evidence_path=evidence_path,
                    cwd=cwd,
                    timeout_seconds=timeout_seconds,
                )

            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                output_directory,
                runs=2,
                stage_runner=successful_stage,
            )

        self.assertEqual(result["status"], "PASSED")
        commands = [command for command, _ in calls]
        self.assertTrue(any(command[-4:] == ["up", "-d", "--wait", "postgres"] for command in commands))
        self.assertTrue(any(command[-3:] == ["up", "-d", "flyway"] for command in commands))
        self.assertTrue(any(command[-2:] == ["wait", "flyway"] for command in commands))
        self.assertTrue(any(command[-1:] == ["flyway"] and "ps" in command for command in commands))
        self.assertTrue(any(command[-4:] == ["up", "-d", "--no-deps", "verifier"] for command in commands))
        self.assertTrue(any(command[-2:] == ["wait", "verifier"] for command in commands))
        self.assertTrue(any(command[-1:] == ["verifier"] and "ps" in command for command in commands))
        verifier_start = next(command for command in commands if command[-1:] == ["verifier"] and "up" in command)
        self.assertIn("--no-deps", verifier_start)
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "up" in command for command in commands))
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "wait" in command for command in commands))
        self.assertEqual(2, sum(command[-1:] == ["flyway"] and "ps" in command for command in commands))
        self.assertTrue(any("migrate" in command and "--entrypoint" in command for command in commands))
        self.assertTrue(any(command[-3:] == ["logs", "--no-color", "verifier"] for command in commands))
        self.assertFalse(any("--abort-on-container-exit" in command for command in commands))
        self.assertTrue(all(timeout != verify_runtime._DEFAULT_TIMEOUT_SECONDS for _, timeout in calls))
        self.assertTrue(all(0 < timeout <= 300 for _, timeout in calls))
        self.assertTrue(all(not path.is_relative_to(PROJECT_ROOT) and not path.exists() for path in temporary_paths))

    def test_temporary_files_are_outside_the_repository_and_removed_when_cleanup_raises(self) -> None:
        """Break caught: a cleanup/evidence exception leaves a password-bearing temp file in the repository."""
        from runtime import verify_runtime

        temporary_paths: set[Path] = set()
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"

            def cleanup_exception_stage(
                command: list[str],
                *,
                evidence_path: Path,
                cwd: Path,
                timeout_seconds: float,
            ) -> int:
                temporary_paths.add(Path(command[command.index("--env-file") + 1]))
                temporary_paths.add(Path(command[[index for index, value in enumerate(command) if value == "-f"][-1] + 1]))
                if "down" in command:
                    raise RuntimeError("evidence writer failed during cleanup")
                stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}]) if "ps" in command else ""
                evidence_path.parent.mkdir(parents=True, exist_ok=True)
                evidence_path.write_text(json.dumps({"stdout": stdout}), encoding="utf-8")
                return 0

            with self.assertRaisesRegex(RuntimeError, "evidence writer failed during cleanup"):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_directory,
                    runs=2,
                    stage_runner=cleanup_exception_stage,
                )

        self.assertTrue(temporary_paths)
        self.assertTrue(all(not path.is_relative_to(PROJECT_ROOT) and not path.exists() for path in temporary_paths))

    def test_compose_rendering_uses_only_locked_images_and_orders_empty_database_stages(self) -> None:
        """Break caught: an unlocked image or validate-before-migrate bypasses the empty-db chain."""
        from runtime import verify_runtime

        lock = verify_runtime.load_toolchain_lock(PROJECT_ROOT / "runtime" / "toolchain.lock.json")
        rendered = json.loads(verify_runtime.render_compose_override(lock))
        expected_images = {
            "postgres": "postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280",
            "flyway": "redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93",
            "verifier": "postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280",
        }
        self.assertEqual(
            {service: config["image"] for service, config in rendered["services"].items()},
            expected_images,
        )
        environment = verify_runtime.render_compose_environment(lock)
        self.assertIn("POSTGRES_IMAGE=postgres@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280", environment)
        self.assertIn("FLYWAY_IMAGE=redgate/flyway@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93", environment)

        compose_path = PROJECT_ROOT / "runtime" / "compose.yaml"
        compose_text = compose_path.read_text(encoding="utf-8")
        self.assertEqual({"postgres", "flyway", "verifier"}, set(verify_runtime.compose_service_names(compose_text)))
        self.assertIn("../generated/db/migration:/flyway/sql:ro", compose_text)
        self.assertIn("pg_isready", compose_text)
        self.assertIn("condition: service_healthy", compose_text)
        self.assertIn("condition: service_completed_successfully", compose_text)
        self.assertLess(compose_text.index(" migrate\n"), compose_text.index(" validate\n"))
        self.assertIn("-validateOnMigrate=true migrate", compose_text)
        self.assertIn("-ignoreMigrationPatterns= validate", compose_text)
        self.assertIn("/runtime/sql/assert_schema_contract.sql", compose_text)
        self.assertIn("/runtime/sql/assert_capabilities.sql", compose_text)
        self.assertIn("/runtime/sql/schema_fingerprint.sql", compose_text)
        self.assertGreaterEqual(compose_text.count("psql -X -v ON_ERROR_STOP=1"), 3)
        self.assertIn("PGPASSWORD: ${RUNTIME_POSTGRES_PASSWORD", compose_text)
        self.assertIn("PGUSER: postgres", compose_text)
        self.assertIn("postgres-data:/var/lib/postgresql", compose_text)
        self.assertNotIn("postgres-data:/var/lib/postgresql/data", compose_text)
        for option in (
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
        ):
            with self.subTest(option=option):
                self.assertIn(option, compose_text)

    def test_missing_docker_is_recorded_as_a_blocked_runtime_attempt_and_still_cleans_up(self) -> None:
        """Break caught: unavailable Docker is mislabeled as verifier failure or skips cleanup."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "evidence"
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                output_directory,
                runs=2,
                compose_command=("definitely-not-a-docker-command", "compose"),
            )

            self.assertEqual(result["status"], "BLOCKED")
            self.assertEqual(result["reason"], "docker_compose_unavailable")
            self.assertEqual(len(result["runs"]), 2)
            for index in range(1, 3):
                up = json.loads((output_directory / f"run-{index:02d}" / "postgres-start.json").read_text(encoding="utf-8"))
                down = json.loads((output_directory / f"run-{index:02d}" / "compose-down.json").read_text(encoding="utf-8"))
                self.assertEqual(up["returncode"], 127)
                self.assertEqual(down["returncode"], 127)
                self.assertIn("could not be started", up["stderr"])
                self.assertEqual(down["command"][-3:], ["down", "--volumes", "--remove-orphans"])

    def test_images_are_digest_pinned(self) -> None:
        from runtime import verify_runtime

        lock_path = PROJECT_ROOT / "runtime" / "toolchain.lock.json"
        lock = verify_runtime.load_toolchain_lock(lock_path)
        self.assertEqual({entry["image"] for entry in lock["images"]}, {"postgres", "redgate/flyway"})
        self.assertTrue(all(entry["tag"] != "latest" for entry in lock["images"]))
        self.assertTrue(all(entry["digest"].startswith("sha256:") for entry in lock["images"]))

        invalid_locks = (
            {"images": [{"image": "postgres", "tag": "latest", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "resolvedAt": "2026-08-28T00:00:00Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00.123Z"}]},
            {"images": [{"image": "postgres", "tag": "18", "digest": "sha256:" + "a" * 64, "resolvedAt": "2026-08-28T00:00:00+00:00"}]},
        )
        for invalid_lock in invalid_locks:
            invalid_path = Path(self._temp_dir()) / "invalid-lock.json"
            invalid_path.write_text(json.dumps(invalid_lock), encoding="utf-8")
            with self.assertRaises(ValueError):
                verify_runtime.load_toolchain_lock(invalid_path)

    def test_run_id_rejects_path_traversal(self) -> None:
        from runtime import verify_runtime

        self.assertEqual(verify_runtime.validate_run_id("run-a-02"), "run-a-02")
        for invalid in ("../run", "run/a", "run_a", "Run-A", "", "run a"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    verify_runtime.validate_run_id(invalid)

    def test_evidence_path_is_workspace_scoped(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            allowed_root = repository / ".artifacts" / "schema-runtime"
            allowed_root.mkdir(parents=True)
            self.assertEqual(
                verify_runtime.evidence_dir(repository, "run-a"),
                allowed_root / "run-a",
            )
            for candidate in ("../outside", "/tmp/outside", "run-a/../../outside"):
                with self.subTest(candidate=candidate):
                    with self.assertRaises(ValueError):
                        verify_runtime.evidence_dir(repository, candidate)

            (allowed_root / "escape").symlink_to(repository.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                verify_runtime.evidence_dir(repository, "escape/outside")

    def test_evidence_path_accepts_the_documented_cli_relative_path_only_inside_the_workspace_root(self) -> None:
        """Break caught: the documented relative evidence path is rejected or escapes containment."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            repository = Path(temporary_directory)
            invocation_directory = repository / "database" / "schema-contract-52-plus-2"
            invocation_directory.mkdir(parents=True)
            self.assertEqual(
                verify_runtime.evidence_dir(
                    repository,
                    "../../.artifacts/schema-runtime",
                    current_directory=invocation_directory,
                ),
                repository / ".artifacts" / "schema-runtime",
            )

    def test_normalized_fingerprints_match(self) -> None:
        from runtime import verify_runtime

        snapshot_a = {"tables": [{"name": "orders", "columns": ["id", "total"]}], "version": 1}
        snapshot_b = {"version": 1, "tables": [{"columns": ["id", "total"], "name": "orders"}]}
        self.assertEqual(
            verify_runtime.normalize_snapshot(snapshot_a),
            verify_runtime.normalize_snapshot(snapshot_b),
        )
        with self.assertRaises(ValueError):
            verify_runtime.normalize_snapshot({"tables": [{1: "non-string key"}]})

    def test_runtime_sql_contracts_cover_catalog_privilege_and_transaction_facts(self) -> None:
        """Break caught: a required 52-plus-2 catalog, capability, or SQLSTATE probe silently disappears."""
        sql_directory = PROJECT_ROOT / "runtime" / "sql"
        required = {
            "assert_schema_contract.sql",
            "assert_capabilities.sql",
            "schema_fingerprint.sql",
            "failures/extra_managed_table.sql",
            "failures/forbidden_delete_grant.sql",
            "failures/missing_mutation_guard.sql",
        }
        self.assertTrue(all((sql_directory / relative).is_file() for relative in required))
        schema_contract = (sql_directory / "assert_schema_contract.sql").read_text(encoding="utf-8")
        capabilities = (sql_directory / "assert_capabilities.sql").read_text(encoding="utf-8")
        fingerprint = (sql_directory / "schema_fingerprint.sql").read_text(encoding="utf-8")

        for literal in (
            "13 managed schemas",
            "52 application tables",
            "2 platform_meta tables",
            "public schema table count",
            "19 successful migrations",
            "maximum migration version",
            "206 composite foreign keys",
            "53 mutation guards",
            "NO ACTION",
            "MATCH SIMPLE",
            "tenant_id",
            "four distinct capability roles",
            "NOLOGIN",
            "parent role memberships",
            "PRIMARY",
            "BLOCKED",
            "52-plus-2-v1",
            "revision=0",
            "32 zero bytes",
        ):
            with self.subTest(literal=literal):
                self.assertIn(literal, schema_contract)
        self.assertIn("platform_meta.flyway_schema_history", schema_contract)
        self.assertIn("version::integer = 840", schema_contract)
        self.assertIn("RAISE EXCEPTION", schema_contract)
        self.assertIn("expected=", schema_contract)
        self.assertIn("actual=", schema_contract)

        for role in ("law_app_query", "law_audit_append", "law_app_worker", "law_app_command"):
            self.assertIn(f"SET LOCAL ROLE {role}", capabilities)
        for sqlstate in ("23503", "55000", "40001", "42501"):
            self.assertIn(sqlstate, capabilities)
        self.assertIn("ROLLBACK", capabilities)
        self.assertIn("GET STACKED DIAGNOSTICS", capabilities)
        self.assertIn("pg_get_functiondef", fingerprint)
        self.assertIn("platform_meta.flyway_schema_history", fingerprint)
        self.assertIn("server_version", fingerprint)
        self.assertIn("'postgresVersion', pg_catalog.version()", fingerprint)
        for unstable in ("oid::text", "installed_on", "execution_time", "current_database()"):
            self.assertNotIn(unstable, fingerprint)

    def test_success_result_captures_runtime_identity_and_normalizes_for_publication(self) -> None:
        """Break caught: a real PASS cannot prove exact images/versions or enter the closed evidence schema."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=self._successful_runtime_stage,
            )

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(
            result["runtimeIdentity"]["flywayVersion"],
            "Flyway Community Edition 13.4.0 by Redgate",
        )
        self.assertEqual(
            [image["image"] for image in result["runtimeIdentity"]["images"]],
            ["postgres", "redgate/flyway"],
        )
        lock = verify_runtime.load_toolchain_lock(PROJECT_ROOT / "runtime" / "toolchain.lock.json")
        manifest = json.loads((PROJECT_ROOT / "generated" / "schema-contract-manifest.json").read_text(encoding="utf-8"))
        normalized = verify_runtime.normalize_runtime_result_for_publication(
            result,
            lock,
            manifest,
            git_commit="a" * 40,
            verified_at_utc="2026-08-28T16:30:00Z",
        )
        self.assertEqual(set(normalized), set(verify_runtime._PUBLISH_SCHEMA_FIELDS))
        self.assertEqual(normalized["gitCommit"], "a" * 40)
        self.assertEqual([run["id"] for run in normalized["runs"]], ["run-01", "run-02"])
        self.assertEqual(normalized["runs"][0]["fingerprint"], normalized["runs"][1]["fingerprint"])
        self.assertEqual(len(normalized["failureScenarios"]), 5)

        mutations = (
            ("top-level", lambda candidate: candidate.update({"apiValidated": True})),
            ("run", lambda candidate: candidate["runs"][0].update({"unknownClaim": True})),
            ("service", lambda candidate: candidate["runs"][0]["flyway"].update({"unknownClaim": True})),
            ("summary", lambda candidate: candidate["runs"][0]["verifier"]["summary"].update({"unknownClaim": True})),
            ("runtime-identity", lambda candidate: candidate["runtimeIdentity"].update({"unknownClaim": True})),
            ("identity-image", lambda candidate: candidate["runtimeIdentity"]["images"][0].update({"unknownClaim": True})),
            ("failure", lambda candidate: candidate["failureScenarios"][0].update({"unknownClaim": True})),
        )
        for context, mutate in mutations:
            candidate = copy.deepcopy(result)
            mutate(candidate)
            with self.subTest(context=context):
                with self.assertRaises(ValueError):
                    verify_runtime.normalize_runtime_result_for_publication(
                        candidate,
                        lock,
                        manifest,
                        git_commit="a" * 40,
                        verified_at_utc="2026-08-28T16:30:00Z",
                    )

    def test_runtime_evidence_path_io_errors_are_structured(self) -> None:
        """Break caught: a regular file at an evidence directory leaks FileExistsError."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_path = Path(temporary_directory) / "evidence"
            output_path.write_text("occupied\n", encoding="utf-8")
            with self.assertRaises(verify_runtime.EvidenceIOError):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_path,
                    runs=2,
                    stage_runner=self._successful_runtime_stage,
                )

        with tempfile.TemporaryDirectory() as temporary_directory:
            output_path = Path(temporary_directory) / "evidence"
            output_path.mkdir()
            (output_path / "run-01").write_text("occupied\n", encoding="utf-8")
            with self.assertRaises(verify_runtime.EvidenceIOError):
                verify_runtime.run_runtime_verification(
                    PROJECT_ROOT,
                    output_path,
                    runs=2,
                    stage_runner=self._successful_runtime_stage,
                )

    def test_cross_tenant_probe_qualifies_its_deferred_constraint_without_search_path(self) -> None:
        """Break caught: PostgreSQL resolves the deferred probe constraint outside the identity schema."""
        capabilities = (PROJECT_ROOT / "runtime" / "sql" / "assert_capabilities.sql").read_text(encoding="utf-8")

        self.assertIn(
            "SET CONSTRAINTS identity.fk_organization_unit__parent_organization_unit IMMEDIATE;",
            capabilities,
        )
        self.assertNotIn("SET search_path", capabilities)

    def test_failure_scenarios_are_phase_and_message_checked_and_run_a_is_noop_stable(self) -> None:
        """Break caught: expected SQL failures, a checksum drift, or a changed run-A fingerprint pass open."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=self._successful_runtime_stage,
            )

        self.assertEqual("PASSED", result["status"])
        self.assertIn("failureScenarios", result)
        self.assertEqual(5, len(result["failureScenarios"]))
        self.assertEqual(
            [
                "missing-role",
                "extra-managed-table",
                "forbidden-delete-grant",
                "missing-mutation-guard",
                "checksum-mismatch",
            ],
            [scenario["name"] for scenario in result["failureScenarios"]],
        )
        self.assertTrue(all(scenario["status"] == "PASSED" for scenario in result["failureScenarios"]))
        self.assertTrue(all(scenario["messageMatched"] for scenario in result["failureScenarios"]))
        self.assertTrue(all(scenario["actualPhase"] == scenario["expectedPhase"] for scenario in result["failureScenarios"]))
        self.assertEqual(
            result["runs"][0]["initialFingerprint"],
            result["runs"][0]["noopFingerprint"],
        )

    def test_different_valid_ab_fingerprints_fail_after_all_five_scenarios(self) -> None:
        """Break caught: two valid but different empty-database fingerprints claim runtime success."""
        from runtime import verify_runtime

        fingerprints = {
            "run-01": "0123456789abcdef0123456789abcdef",
            "run-02": "fedcba9876543210fedcba9876543210",
        }
        identity_captures: list[Path] = []

        def divergent_fingerprints(
            command: list[str],
            *,
            evidence_path: Path,
            cwd: Path,
            timeout_seconds: float,
        ) -> int:
            return_code = self._successful_runtime_stage(
                command,
                evidence_path=evidence_path,
                cwd=cwd,
                timeout_seconds=timeout_seconds,
            )
            if "runtime-identity" in evidence_path.parts:
                identity_captures.append(evidence_path)
            if evidence_path.name in {"verifier-logs.json", "noop-verifier-logs.json"}:
                run_id = "run-02" if "run-02" in evidence_path.parts else "run-01"
                recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                summary = json.loads(recorded["stdout"])
                summary["fingerprint"] = fingerprints[run_id]
                recorded["stdout"] = json.dumps(summary) + "\n"
                evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
            return return_code

        repository_root = PROJECT_ROOT.parents[1]
        fixed_success_targets = (
            repository_root / "docs" / "evidence" / "schema-runtime" / "2026-08-28-postgresql-18-summary.json",
            repository_root / "docs" / "evidence" / "schema-runtime" / "2026-08-28-postgresql-18-report.md",
        )
        self.assertFalse(any(path.exists() for path in fixed_success_targets))
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=divergent_fingerprints,
            )

        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["reason"], "runtime_fingerprint_mismatch")
        self.assertEqual(
            [scenario["name"] for scenario in result["failureScenarios"]],
            [
                "missing-role",
                "extra-managed-table",
                "forbidden-delete-grant",
                "missing-mutation-guard",
                "checksum-mismatch",
            ],
        )
        self.assertTrue(all(scenario["status"] == "PASSED" for scenario in result["failureScenarios"]))
        self.assertNotIn("runtimeIdentity", result)
        self.assertEqual(identity_captures, [])
        self.assertFalse(any(path.exists() for path in fixed_success_targets))

    def test_invalid_or_empty_fingerprints_fail_the_positive_run_gate(self) -> None:
        """Break caught: two equal non-fingerprints are mistaken for a stable A/B schema."""
        from runtime import verify_runtime

        for invalid_fingerprint in ("", "same", "A" * 32, "g" * 32):
            with self.subTest(fingerprint=invalid_fingerprint):
                def invalid_fingerprint_stage(
                    command: list[str],
                    *,
                    evidence_path: Path,
                    cwd: Path,
                    timeout_seconds: float,
                ) -> int:
                    return_code = self._successful_runtime_stage(
                        command,
                        evidence_path=evidence_path,
                        cwd=cwd,
                        timeout_seconds=timeout_seconds,
                    )
                    if evidence_path.name in {"verifier-logs.json", "noop-verifier-logs.json"}:
                        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
                        summary = json.loads(recorded["stdout"])
                        summary["fingerprint"] = invalid_fingerprint
                        recorded["stdout"] = json.dumps(summary) + "\n"
                        evidence_path.write_text(json.dumps(recorded), encoding="utf-8")
                    return return_code

                with tempfile.TemporaryDirectory() as temporary_directory:
                    result = verify_runtime.run_runtime_verification(
                        PROJECT_ROOT,
                        Path(temporary_directory) / "evidence",
                        runs=2,
                        stage_runner=invalid_fingerprint_stage,
                    )

                self.assertEqual(result["status"], "FAILED")
                self.assertEqual(result["reason"], "compose_up_failed")
                self.assertEqual(result["failureScenarios"], [])
                self.assertNotIn("runtimeIdentity", result)

    def test_unexpected_failure_scenario_success_fails_closed(self) -> None:
        """Break caught: a corruption scenario that unexpectedly succeeds is reported as verification success."""
        from runtime import verify_runtime

        def everything_succeeds(
            command: list[str],
            *,
            evidence_path: Path,
            cwd: Path,
            timeout_seconds: float,
        ) -> int:
            del cwd, timeout_seconds
            stdout = ""
            if "ps" in command:
                stdout = json.dumps([{"Service": command[-1], "ExitCode": 0}])
            elif "logs" in command:
                stdout = '{"fingerprint":"0123456789abcdef0123456789abcdef","status":"PASSED"}\n'
            evidence_path.parent.mkdir(parents=True, exist_ok=True)
            evidence_path.write_text(json.dumps({"returncode": 0, "stderr": "", "stdout": stdout}), encoding="utf-8")
            return 0

        with tempfile.TemporaryDirectory() as temporary_directory:
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                Path(temporary_directory) / "evidence",
                runs=2,
                stage_runner=everything_succeeds,
            )

        self.assertEqual("FAILED", result["status"])
        self.assertEqual("failure_scenario_failed", result["reason"])
        self.assertTrue(any(scenario["status"] == "FAILED" for scenario in result["failureScenarios"]))

    def test_failed_stage_is_reported_without_claiming_success(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "failed-stage.json"
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", "import sys; print('stage failed'); sys.exit(17)"],
                evidence_path=evidence,
            )
            self.assertEqual(return_code, 17)
            recorded = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(recorded["returncode"], 17)
            self.assertEqual(recorded["status"], "failed")
            self.assertIn("stage failed", recorded["stdout"])

    def test_timeout_is_bounded_and_persists_evidence(self) -> None:
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "timeout-stage.json"
            started = time.monotonic()
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", "import time; time.sleep(10)"],
                evidence_path=evidence,
                timeout_seconds=0.05,
            )
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual(return_code, 124)
            recorded = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(recorded["returncode"], 124)
            self.assertEqual(recorded["status"], "timed_out")
            self.assertTrue(recorded["timedOut"])

        for invalid_timeout in (0, -1, float("nan"), True, 301):
            with self.subTest(invalid_timeout=invalid_timeout):
                with self.assertRaises(ValueError):
                    verify_runtime.run_checked(
                        [sys.executable, "-c", "pass"],
                        evidence_path=Path("unused.json"),
                        timeout_seconds=invalid_timeout,
                    )

    def test_evidence_redacts_sensitive_command_and_output(self) -> None:
        from runtime import verify_runtime

        secrets = (
            "command-secret",
            "api-command-secret",
            "postgres-secret",
            "output-secret",
            "secret-output",
            "token-output",
            "api-output",
            "stderr-secret",
            "client-output",
            "bearer-secret",
            "url-secret",
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "redacted-stage.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    "import sys; print('ordinary output password=output-secret secret: secret-output "
                    "token=token-output api_key=api-output'); "
                    "print('postgres://user:url-secret@database/test'); "
                    "print('token: stderr-secret {\\\"client_secret\\\": \\\"client-output\\\"} "
                    "Authorization: Bearer bearer-secret', file=sys.stderr); sys.exit(17)",
                    "--password",
                    "command-secret",
                    "--api-key=api-command-secret",
                    "PGPASSWORD=postgres-secret",
                ],
                evidence_path=evidence,
            )
            recorded_text = evidence.read_text(encoding="utf-8")
            recorded = json.loads(recorded_text)

        self.assertEqual(return_code, 17)
        self.assertIn("ordinary output", recorded["stdout"])
        self.assertIn("[REDACTED]", recorded_text)
        for secret in secrets:
            self.assertNotIn(secret, recorded_text)

    def test_evidence_redacts_bare_bearer_credentials(self) -> None:
        from runtime import verify_runtime

        secrets = ("command-bare-bearer", "stdout-bare-bearer", "stderr-bare-bearer")
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence = Path(temporary_directory) / "bare-bearer-stage.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    "import sys; print('Bearer stdout-bare-bearer'); "
                    "print('Bearer stderr-bare-bearer', file=sys.stderr); sys.exit(17)",
                    "Bearer command-bare-bearer",
                ],
                evidence_path=evidence,
            )
            recorded_text = evidence.read_text(encoding="utf-8")
            recorded = json.loads(recorded_text)

        self.assertEqual(return_code, 17)
        self.assertIn("[REDACTED]", recorded_text)
        for secret in secrets:
            self.assertNotIn(secret, recorded_text)
        self.assertNotIn("command-bare-bearer", " ".join(recorded["command"]))
        self.assertNotIn("stdout-bare-bearer", recorded["stdout"])
        self.assertNotIn("stderr-bare-bearer", recorded["stderr"])

    def test_artifact_paths_are_redacted_by_command_context_not_temp_names(self) -> None:
        """Break caught: path redaction trusts /tmp names instead of exact external Compose inputs."""
        from runtime import verify_runtime

        with (
            tempfile.TemporaryDirectory(prefix="schema-runtime-repository-") as repository_directory,
            tempfile.TemporaryDirectory(prefix="custom-compose-context-") as external_directory,
        ):
            repository = Path(repository_directory)
            repository_compose_path = repository / "runtime" / "compose.yaml"
            repository_compose_path.parent.mkdir(parents=True)
            repository_compose_path.write_text("services: {}\n", encoding="utf-8")
            evidence_path = repository / "evidence" / "reviewer-probe.json"
            external = Path(external_directory)
            environment_path = external / "first.env"
            inline_environment_path = external / "second.env"
            override_path = external / "first-override.yaml"
            inline_override_path = external / "second-override.yaml"
            long_override_path = external / "third-override.yaml"
            arguments = [
                "--env-file",
                str(environment_path),
                f"--env-file={inline_environment_path}",
                "-f",
                str(repository_compose_path),
                "-f",
                str(override_path),
                f"--file={inline_override_path}",
                "--file",
                str(long_override_path),
                "config",
            ]
            expected = {
                "arguments": arguments,
                "cwd": str(repository),
                "evidencePath": str(evidence_path),
                "externalDirectory": str(external),
            }
            probe = (
                "import json,os,sys; "
                "expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected['arguments']; "
                "assert os.getcwd() == expected['cwd']; "
                "print(json.dumps(expected))"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(expected), *arguments],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "the executed command must still receive every original argument")
        self.assertIn(str(repository), artifact_text)
        self.assertIn(str(repository_compose_path), artifact_text)
        self.assertIn(str(evidence_path), artifact_text)
        self.assertNotIn(str(external), artifact_text)
        self.assertIn("[RUNTIME_ENV_FILE]", artifact_text)
        self.assertIn("[RUNTIME_COMPOSE_OVERRIDE]", artifact_text)
        self.assertIn("[RUNTIME_TEMP_PATH]", artifact_text)
        self.assertIn("--env-file=[RUNTIME_ENV_FILE]", recorded["command"])
        self.assertIn("--file=[RUNTIME_COMPOSE_OVERRIDE]", recorded["command"])
        self.assertIn("--env-file", recorded["command"])
        self.assertEqual(recorded["command"][recorded["command"].index("--env-file") + 1], "[RUNTIME_ENV_FILE]")
        self.assertIn("--file", recorded["command"])
        self.assertEqual(
            recorded["command"][recorded["command"].index("--file") + 1],
            "[RUNTIME_COMPOSE_OVERRIDE]",
        )

    def test_parent_path_redaction_requires_a_component_boundary(self) -> None:
        """Break caught: an external parent path erases a repository whose component shares its prefix."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "runtime"
            repository = root / "runtime-repository"
            external.mkdir()
            repository.mkdir()
            environment_path = external / "runtime.env"
            external_child = external / "logs" / "postgres.log"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = repository / "evidence" / "path-boundary.json"
            expected = {
                "environmentPath": str(environment_path),
                "externalChild": str(external_child),
                "repositoryCompose": str(repository_compose),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; print(json.dumps(expected))"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(expected),
                    "--env-file",
                    str(environment_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])

        self.assertEqual(return_code, 0)
        self.assertIn(str(repository), artifact_text)
        self.assertIn(str(repository_compose), artifact_text)
        self.assertIn(str(repository_compose), recorded["stdout"])
        for decoded in (decoded_command, decoded_stdout):
            self.assertEqual(decoded["environmentPath"], "[RUNTIME_ENV_FILE]")
            self.assertEqual(decoded["externalChild"], "[RUNTIME_TEMP_PATH]/logs/postgres.log")
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
        self.assertIn("[RUNTIME_TEMP_PATH]", artifact_text)

    def test_path_redaction_uses_only_posix_separator_boundaries(self) -> None:
        """Break caught: punctuation after a sensitive component is treated as a path boundary."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "x" / "runtime"
            repository = root / "x" / "runtime+repository"
            regex_external = root / "x" / "runtime.^$[](){}+?"
            external.mkdir(parents=True)
            repository.mkdir()
            regex_external.mkdir()
            environment_path = external / "runtime.env"
            override_path = regex_external / "override.yaml"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = repository / "evidence" / "posix-boundary.json"
            suffixes = ("+repository", " repository", r"\repository", "(repository)", "[repository]")
            payload = {
                "environmentPath": str(environment_path),
                "evidencePath": str(evidence_path),
                "externalChild": str(external / "logs" / "postgres.log"),
                "externalRoot": str(external),
                "overridePath": str(override_path),
                "regexExternalChild": str(regex_external / "logs" / "flyway.log"),
                "regexExternalRoot": str(regex_external),
                "repositoryCompose": str(repository_compose),
                "siblings": [str(external) + suffix for suffix in suffixes],
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; "
                "assert sys.argv[5] == expected['overridePath']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    str(environment_path),
                    "-f",
                    str(override_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["environmentPath"], "[RUNTIME_ENV_FILE]")
            self.assertEqual(decoded["externalRoot"], "[RUNTIME_TEMP_PATH]")
            self.assertEqual(decoded["externalChild"], "[RUNTIME_TEMP_PATH]/logs/postgres.log")
            self.assertEqual(decoded["overridePath"], "[RUNTIME_COMPOSE_OVERRIDE]")
            self.assertEqual(decoded["regexExternalRoot"], "[RUNTIME_TEMP_PATH]")
            self.assertEqual(decoded["regexExternalChild"], "[RUNTIME_TEMP_PATH]/logs/flyway.log")
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["siblings"], payload["siblings"])
        self.assertNotIn(str(regex_external), artifact_text)

    def test_sensitive_root_path_fails_closed_without_erasing_protected_context(self) -> None:
        """Break caught: root redaction leaks descendants or erases repository, cwd, and evidence paths."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            working_directory = repository / "work"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = root / "evidence" / "root-boundary.json"
            working_directory.mkdir(parents=True)
            repository_compose.parent.mkdir()
            (repository / ".git").mkdir()
            payload = {
                "cwd": str(working_directory),
                "evidencePath": str(evidence_path),
                "repository": str(repository),
                "repositoryCompose": str(repository_compose),
                "root": "/",
                "rootChild": "/outside-review/private/runtime.env",
            }
            probe = (
                "import json,os,sys; expected=json.loads(sys.argv[1]); "
                "assert os.getcwd() == expected['cwd']; assert sys.argv[3] == '/'; "
                "assert sys.argv[5] == expected['repositoryCompose']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=working_directory,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["cwd"], str(working_directory))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["repository"], str(repository))
            self.assertEqual(decoded["repositoryCompose"], str(repository_compose))
            self.assertEqual(decoded["root"], "[RUNTIME_ENV_FILE]")
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["rootChild"])
            self.assertNotIn("/outside-review/private/runtime.env", decoded["rootChild"])
        self.assertIn(str(repository_compose), artifact_text)
        self.assertIn(str(evidence_path), artifact_text)

    def test_path_tokens_are_lexically_normalized_before_protection_is_classified(self) -> None:
        """Break caught: a protected textual prefix hides an absolute or relative path that escapes via dot-dot."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            working_directory = repository / "work"
            repository_compose = repository / "runtime" / "compose.yaml"
            evidence_path = root / "evidence" / "lexical-paths.json"
            working_directory.mkdir(parents=True)
            repository_compose.parent.mkdir()
            (repository / ".git").mkdir()
            absolute_escape = f"{repository}/../outside/private.env"
            relative_escape = "../../outside/private.env"
            payload = {
                "absoluteEscape": absolute_escape,
                "cwdDescendant": str(working_directory / "logs" / "diagnostic.log"),
                "ordinaryUrl": "https://example.invalid/repository/../outside/private.env",
                "relativeEscape": relative_escape,
                "repositoryDescendant": str(repository / "logs" / "diagnostic.log"),
            }
            payload_digest = hashlib.sha256(json.dumps(payload).encode("utf-8")).hexdigest()
            probe = (
                "import hashlib,json,sys; raw=sys.argv[1]; "
                "assert hashlib.sha256(raw.encode()).hexdigest() == sys.argv[2]; "
                "assert sys.argv[4] == '/'; print(raw); print(raw, file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    payload_digest,
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=working_directory,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["cwdDescendant"], payload["cwdDescendant"])
            self.assertEqual(decoded["repositoryDescendant"], payload["repositoryDescendant"])
            self.assertEqual(decoded["ordinaryUrl"], payload["ordinaryUrl"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["absoluteEscape"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["relativeEscape"])
            self.assertNotEqual(decoded["absoluteEscape"], absolute_escape)
            self.assertNotEqual(decoded["relativeEscape"], relative_escape)

    def test_exact_protected_files_do_not_protect_child_tokens(self) -> None:
        """Break caught: exact evidence/Compose protection is extended to file/private descendants."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            repository.mkdir()
            (repository / ".git").mkdir()
            repository_compose = repository / "runtime" / "compose.yaml"
            repository_compose.parent.mkdir()
            evidence_path = root / "evidence" / "exact-file.json"
            evidence_child = f"{evidence_path}/private"
            evidence_sibling = evidence_path.parent / "sibling.log"
            compose_child = f"{repository_compose}/private"
            payload = {
                "composeChild": compose_child,
                "composePath": str(repository_compose),
                "evidenceChild": evidence_child,
                "evidencePath": str(evidence_path),
                "evidenceSibling": str(evidence_sibling),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == '/'; assert sys.argv[5] == expected['composePath']; "
                "print(json.dumps(expected)); "
                "print(json.dumps(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    "/",
                    "-f",
                    str(repository_compose),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            self.assertEqual(decoded["composePath"], str(repository_compose))
            self.assertEqual(decoded["evidencePath"], str(evidence_path))
            self.assertEqual(decoded["evidenceSibling"], str(evidence_sibling))
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["composeChild"])
            self.assertIn("[RUNTIME_ENV_FILE]", decoded["evidenceChild"])
            self.assertNotEqual(decoded["composeChild"], compose_child)
            self.assertNotEqual(decoded["evidenceChild"], evidence_child)

    def test_path_redaction_replaces_only_the_path_token_in_multiline_and_inline_text(self) -> None:
        """Break caught: root handling erases a multiline tail or misses a path between diagnostic headers."""
        from runtime import verify_runtime

        stdout = (
            "/outside-review/path-only.env\r\n"
            "ordinary next line\r\n"
            "HEADER BEFORE /outside-review/inline.env HEADER AFTER\r\n"
        )
        stderr = "ERROR BEFORE /outside-review/error.env ERROR AFTER\nordinary stderr\n"
        argv_digests = [
            hashlib.sha256(value.encode("utf-8")).hexdigest()
            for value in (stdout, stderr, "--env-file", "/")
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "line-bounded-paths.json"
            probe = (
                "import hashlib,json,sys; actual=sys.argv[2:]; expected=json.loads(sys.argv[1]); "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "sys.stdout.write(actual[0]); sys.stderr.write(actual[1])"
            )
            command = [sys.executable, "-c", probe, json.dumps(argv_digests), stdout, stderr, "--env-file", "/"]
            return_code = verify_runtime.run_checked(
                command,
                evidence_path=evidence_path,
            )
            recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
            _, path_context = verify_runtime._runtime_path_redactions(command, None, evidence_path)
            direct_redaction = verify_runtime._redact_text(stdout, path_context)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn("ordinary next line\n", recorded["stdout"])
        self.assertIn("HEADER BEFORE ", recorded["stdout"])
        self.assertIn(" HEADER AFTER\n", recorded["stdout"])
        self.assertIn("ERROR BEFORE ", recorded["stderr"])
        self.assertIn(" ERROR AFTER\nordinary stderr\n", recorded["stderr"])
        self.assertIn("ordinary next line\r\n", direct_redaction)
        self.assertIn(" HEADER AFTER\r\n", direct_redaction)
        self.assertGreaterEqual(recorded["stdout"].count("[RUNTIME_ENV_FILE]"), 2)
        self.assertIn("[RUNTIME_ENV_FILE]", recorded["stderr"])
        for forbidden in (
            "/outside-review/path-only.env",
            "/outside-review/inline.env",
            "/outside-review/error.env",
        ):
            self.assertNotIn(forbidden, json.dumps(recorded, sort_keys=True))

    def test_external_paths_with_quotes_are_redacted_in_raw_and_json_text(self) -> None:
        """Break caught: JSON escaping lets a quote-containing external path survive evidence encoding."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            external = root / 'runtime-"quoted-secret"'
            repository.mkdir()
            external.mkdir()
            environment_path = external / 'database-"credentials".env'
            external_child = external / "logs" / 'postgres-"private".log'
            payload = {
                "environmentPath": str(environment_path),
                "externalChild": str(external_child),
            }
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[3] == expected['environmentPath']; "
                "print(json.dumps(expected)); print(json.dumps(expected), file=sys.stderr)"
            )
            evidence_path = repository / "evidence" / "quoted-path.json"
            return_code = verify_runtime.run_checked(
                [
                    sys.executable,
                    "-c",
                    probe,
                    json.dumps(payload),
                    "--env-file",
                    str(environment_path),
                ],
                evidence_path=evidence_path,
                cwd=repository,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            decoded_command = json.loads(recorded["command"][3])
            decoded_stdout = json.loads(recorded["stdout"])
            decoded_stderr = json.loads(recorded["stderr"])

        self.assertEqual(return_code, 0)
        self.assertNotIn(str(external), artifact_text)
        self.assertNotIn("quoted-secret", artifact_text)
        for decoded in (decoded_command, decoded_stdout, decoded_stderr):
            decoded_text = json.dumps(decoded, sort_keys=True)
            self.assertNotIn(str(external), decoded_text)
            self.assertNotIn("quoted-secret", decoded_text)
            self.assertIn("[RUNTIME_TEMP_PATH]", decoded_text)

    def test_sensitive_env_assignment_consumes_escaped_quoted_value(self) -> None:
        """Break caught: an escaped inner quote ends sensitive assignment redaction early."""
        from runtime import verify_runtime

        assignments = [
            r'PGHOST="head \"inner-secret\" tail-secret"',
            r"PGOPTIONS='head \'inner-option-secret\' tail-option-secret'",
            r'LOG_MESSAGE="head \"inner diagnostic\" tail diagnostic"',
        ]
        assignment_digests = [hashlib.sha256(value.encode("utf-8")).hexdigest() for value in assignments]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "escaped-environment.json"
            probe = (
                "import hashlib,json,sys; expected=json.loads(sys.argv[1]); actual=sys.argv[2:]; "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "print('\\n'.join(actual)); print('\\n'.join(actual), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignment_digests), *assignments],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0)
        self.assertIn(assignments[2], recorded["command"])
        self.assertIn(assignments[2], recorded["stdout"])
        self.assertIn(assignments[2], recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "inner-secret",
            "tail-secret",
            "inner-option-secret",
            "tail-option-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_consumes_backslash_newline_in_quoted_value(self) -> None:
        """Break caught: a backslash-newline leaves the sensitive quoted assignment unredacted."""
        from runtime import verify_runtime

        assignments = [
            'PGHOST="head \\\ninner-host-secret tail-host-secret"',
            "PGOPTIONS='head \\\ninner-option-secret tail-option-secret'",
            'LOG_MESSAGE="head \\\ninner diagnostic tail diagnostic"',
        ]
        assignment_digests = [hashlib.sha256(value.encode("utf-8")).hexdigest() for value in assignments]
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "newline-environment.json"
            probe = (
                "import hashlib,json,sys; expected=json.loads(sys.argv[1]); actual=sys.argv[2:]; "
                "assert [hashlib.sha256(value.encode()).hexdigest() for value in actual] == expected; "
                "print('\\n'.join(actual)); print('\\n'.join(actual), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignment_digests), *assignments],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn(assignments[2], recorded["command"])
        self.assertIn(assignments[2], recorded["stdout"])
        self.assertIn(assignments[2], recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "inner-host-secret",
            "tail-host-secret",
            "inner-option-secret",
            "tail-option-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_does_not_consume_the_next_assignment_opener(self) -> None:
        """Break caught: a continued unterminated value swallows the next assignment name and leaks its value."""
        from runtime import verify_runtime

        transcript = (
            'PGHOST="first-adjacent-secret \\\n'
            "PGPASSWORD='second-adjacent-secret'\n"
            'LOG_MESSAGE="ordinary adjacent diagnostic"\n'
        )
        transcript_digest = hashlib.sha256(transcript.encode("utf-8")).hexdigest()
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "adjacent-environment.json"
            probe = (
                "import hashlib,sys; actual=sys.argv[1]; "
                "assert hashlib.sha256(actual.encode()).hexdigest() == sys.argv[2]; "
                "sys.stdout.write(actual); sys.stderr.write(actual)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, transcript, transcript_digest],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertGreaterEqual(recorded["stdout"].count("[RUNTIME_ENV_ASSIGNMENT]"), 2)
        self.assertGreaterEqual(recorded["stderr"].count("[RUNTIME_ENV_ASSIGNMENT]"), 2)
        self.assertIn('LOG_MESSAGE="ordinary adjacent diagnostic"', recorded["stdout"])
        self.assertIn('LOG_MESSAGE="ordinary adjacent diagnostic"', recorded["stderr"])
        for forbidden in (
            "PGHOST",
            "PGPASSWORD",
            "first-adjacent-secret",
            "second-adjacent-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_sensitive_env_assignment_state_machine_handles_crlf_and_terminal_backslash(self) -> None:
        """Break caught: CRLF continuations or a final lone backslash leave quoted runtime secrets visible."""
        from runtime import verify_runtime

        transcript = (
            'PGHOST="crlf-host-secret \\\r\ncontinued-host-secret"\r\n'
            "PGOPTIONS='crlf-option-secret \\\r\ncontinued-option-secret'\r\n"
            'LOG_MESSAGE="ordinary crlf \\\r\ncontinued diagnostic"\r\n'
            'PGUSER="terminal-backslash-secret\\'
        )
        transcript_digest = hashlib.sha256(transcript.encode("utf-8")).hexdigest()
        ordinary_crlf = 'LOG_MESSAGE="ordinary crlf \\\r\ncontinued diagnostic"'
        ordinary_subprocess = ordinary_crlf.replace("\r\n", "\n")
        with tempfile.TemporaryDirectory() as temporary_directory:
            evidence_path = Path(temporary_directory) / "crlf-environment.json"
            probe = (
                "import hashlib,sys; actual=sys.argv[1]; "
                "assert hashlib.sha256(actual.encode()).hexdigest() == sys.argv[2]; "
                "sys.stdout.write(actual); sys.stderr.write(actual)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, transcript, transcript_digest],
                evidence_path=evidence_path,
            )
            artifact_text = evidence_path.read_text(encoding="utf-8")
            recorded = json.loads(artifact_text)
            direct_redaction = verify_runtime._redact_text(transcript)

        self.assertEqual(return_code, 0, "redaction must not change subprocess argv")
        self.assertIn(ordinary_subprocess, recorded["stdout"])
        self.assertIn(ordinary_subprocess, recorded["stderr"])
        self.assertIn(ordinary_crlf, direct_redaction)
        self.assertIn("\r\n", direct_redaction)
        for forbidden in (
            "PGHOST",
            "PGOPTIONS",
            "PGUSER",
            "crlf-host-secret",
            "continued-host-secret",
            "crlf-option-secret",
            "continued-option-secret",
            "terminal-backslash-secret",
        ):
            self.assertNotIn(forbidden, artifact_text)
            self.assertNotIn(forbidden, direct_redaction)

    def test_artifact_removes_complete_postgresql_connection_uris(self) -> None:
        """Break caught: IPv6, multi-host, or parameter tails survive PostgreSQL URI redaction."""
        from runtime import verify_runtime

        connections = [
            "JDBC:POSTGRESQL://reviewer:uri-secret@[2001:db8::1]:5432,db-two.internal:5433/law"
            "?sslmode=require&target_session_attrs=read-write",
            "PoStGrEsQl://[2001:db8::2]:5432,db-three.internal/law?application_name=review-probe",
            "POSTGRES://reviewer:second-secret@db-four.internal/law?options=-c%20statement_timeout=10",
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            artifact_directory = Path(temporary_directory) / "artifact"
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected; print(json.dumps(expected)); "
                "print(expected[1], file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(connections), *connections],
                evidence_path=artifact_directory / "run-01" / "connection-probe.json",
            )
            artifact_text = "\n".join(
                path.read_text(encoding="utf-8")
                for path in sorted(artifact_directory.rglob("*"))
                if path.is_file()
            )

        self.assertEqual(return_code, 0)
        self.assertIn("[REDACTED_CONNECTION]", artifact_text)
        for forbidden in (
            "uri-secret",
            "second-secret",
            "2001:db8",
            "db-two.internal",
            "db-three.internal",
            "db-four.internal",
            "sslmode",
            "target_session_attrs",
            "application_name",
            "statement_timeout",
        ):
            self.assertNotIn(forbidden, artifact_text)
        self.assertIsNone(re.search(r"(?i)(?:jdbc:postgresql:|postgres(?:ql)?://)", artifact_text))

    def test_artifact_redacts_sensitive_env_assignments_only_case_insensitively(self) -> None:
        """Break caught: quoted mixed-case runtime env leaks while ordinary diagnostics disappear."""
        from runtime import verify_runtime

        assignments = [
            'Flyway_Password="Flyway Secret"',
            "flyway_password='lower secret'",
            "PGHOST='db internal'",
            "PGOPTIONS='-c statement_timeout=1000 -c search_path=law'",
            "LOG_LEVEL=debug",
            "NORMAL_VALUE='diagnostic value'",
        ]
        with tempfile.TemporaryDirectory() as temporary_directory:
            artifact_directory = Path(temporary_directory) / "artifact"
            probe = (
                "import json,sys; expected=json.loads(sys.argv[1]); "
                "assert sys.argv[2:] == expected; "
                "print(json.dumps({'Config': {'Env': expected}})); "
                "print(' '.join(expected), file=sys.stderr)"
            )
            return_code = verify_runtime.run_checked(
                [sys.executable, "-c", probe, json.dumps(assignments), *assignments],
                evidence_path=artifact_directory / "run-01" / "environment-probe.json",
            )
            artifact_text = "\n".join(
                path.read_text(encoding="utf-8")
                for path in sorted(artifact_directory.rglob("*"))
                if path.is_file()
            )

        self.assertEqual(return_code, 0)
        self.assertIn("[RUNTIME_ENV_ASSIGNMENT]", artifact_text)
        self.assertIn("LOG_LEVEL=debug", artifact_text)
        self.assertIn("NORMAL_VALUE='diagnostic value'", artifact_text)
        for forbidden in (
            "Flyway_Password",
            "flyway_password",
            "PGHOST",
            "PGOPTIONS",
            "Flyway Secret",
            "lower secret",
            "db internal",
            "statement_timeout",
            "search_path",
        ):
            self.assertNotIn(forbidden, artifact_text)

    def test_cli_rejects_a_single_run_before_any_runtime_attempt(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-m", "runtime.verify_runtime", "verify", "--runs", "1", "--evidence-dir", "run-a"],
            cwd=PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--runs must be at least 2", completed.stderr)

    def _temp_dir(self) -> str:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        return temporary_directory.name


if __name__ == "__main__":
    unittest.main()
