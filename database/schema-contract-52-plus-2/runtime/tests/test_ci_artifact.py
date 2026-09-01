"""Closed-schema tests for the runner-local PostgreSQL CI artifact."""

from __future__ import annotations

import copy
from contextlib import redirect_stderr, redirect_stdout
import errno
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import yaml


PROJECT_ROOT = Path(__file__).resolve().parents[2]
REPOSITORY_ROOT = PROJECT_ROOT.parents[1]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "schema-contract-52-plus-2.yml"
POSTGRES_DIGEST = "sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"
FLYWAY_DIGEST = "sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93"
CONTRACT_DIGEST = "a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a"
FIELD_DIGEST = "be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed"

TOP_LEVEL_FIELDS = {
    "schemaVersion",
    "gitCommit",
    "workflowOutcome",
    "reasonCode",
    "runs",
    "failureScenarios",
    "toolchain",
    "contractSummary",
}
RUN_FIELDS = {"runId", "stages"}
SCENARIO_FIELDS = {"scenarioName", "stages"}
STAGE_FIELDS = {
    "stageName",
    "commandClass",
    "exitCode",
    "timedOut",
    "diagnosticCode",
    "stdoutSha256",
    "stderrSha256",
}
IMAGE_FIELDS = {"imageName", "lockedTag", "lockedDigest"}
TOOLCHAIN_FIELDS = {"images", "postgresVersion", "flywayVersion"}
CONTRACT_FIELDS = {
    "verified",
    "migrationCount",
    "managedTableCount",
    "managedSchemaCount",
    "physicalForeignKeyCount",
    "mutationGuardCount",
    "contractSha256",
    "fieldContractSha256",
}

RUN_01_STAGES = (
    ("postgres-start", "compose_lifecycle"),
    ("flyway-start", "compose_lifecycle"),
    ("flyway-wait", "compose_lifecycle"),
    ("flyway-status", "compose_lifecycle"),
    ("verifier-start", "compose_lifecycle"),
    ("verifier-wait", "compose_lifecycle"),
    ("verifier-status", "compose_lifecycle"),
    ("verifier-logs", "compose_logs"),
    ("noop-migrate", "flyway_migrate"),
    ("noop-verifier-start", "compose_lifecycle"),
    ("noop-verifier-wait", "compose_lifecycle"),
    ("noop-verifier-status", "compose_lifecycle"),
    ("noop-verifier-logs", "compose_logs"),
    ("compose-down", "compose_lifecycle"),
)
RUN_02_STAGES = tuple(stage for stage in RUN_01_STAGES if not stage[0].startswith("noop-"))
FAILURE_DEFINITIONS = (
    (
        "missing-role",
        "V830-migrate",
        (
            ("postgres-start", "compose_lifecycle"),
            ("V830-migrate", "flyway_migrate"),
            ("compose-down", "compose_lifecycle"),
        ),
    ),
    (
        "extra-managed-table",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle"),
            ("V830-migrate", "flyway_migrate"),
            ("mutation", "postgres_mutation"),
            ("V840-migrate", "flyway_migrate"),
            ("compose-down", "compose_lifecycle"),
        ),
    ),
    (
        "forbidden-delete-grant",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle"),
            ("V830-migrate", "flyway_migrate"),
            ("mutation", "postgres_mutation"),
            ("V840-migrate", "flyway_migrate"),
            ("compose-down", "compose_lifecycle"),
        ),
    ),
    (
        "missing-mutation-guard",
        "V840-migrate",
        (
            ("postgres-start", "compose_lifecycle"),
            ("V830-migrate", "flyway_migrate"),
            ("mutation", "postgres_mutation"),
            ("V840-migrate", "flyway_migrate"),
            ("compose-down", "compose_lifecycle"),
        ),
    ),
    (
        "checksum-mismatch",
        "strict-validate",
        (("strict-validate", "flyway_validate"),),
    ),
)


def _stage(
    stage_name: str,
    command_class: str,
    *,
    exit_code: int | None = 0,
    diagnostic_code: str = "ok",
    timed_out: bool = False,
) -> dict[str, object]:
    executed = exit_code is not None
    return {
        "stageName": stage_name,
        "commandClass": command_class,
        "exitCode": exit_code,
        "timedOut": timed_out,
        "diagnosticCode": diagnostic_code,
        "stdoutSha256": "a" * 64 if executed else None,
        "stderrSha256": "b" * 64 if executed else None,
    }


def _passed_summary() -> dict[str, object]:
    runs = [
        {
            "runId": "run-01",
            "stages": [_stage(name, command_class) for name, command_class in RUN_01_STAGES],
        },
        {
            "runId": "run-02",
            "stages": [_stage(name, command_class) for name, command_class in RUN_02_STAGES],
        },
    ]
    scenarios: list[dict[str, object]] = []
    for scenario_name, failure_stage, stage_definitions in FAILURE_DEFINITIONS:
        scenarios.append(
            {
                "scenarioName": scenario_name,
                "stages": [
                    _stage(
                        stage_name,
                        command_class,
                        exit_code=17 if stage_name == failure_stage else 0,
                        diagnostic_code="expected_failure" if stage_name == failure_stage else "ok",
                    )
                    for stage_name, command_class in stage_definitions
                ],
            }
        )
    return {
        "schemaVersion": "postgresql-runtime-ci-artifact-v1",
        "gitCommit": "c" * 40,
        "workflowOutcome": "PASSED",
        "reasonCode": "runtime_verified",
        "runs": runs,
        "failureScenarios": scenarios,
        "toolchain": {
            "images": [
                {
                    "imageName": "postgres",
                    "lockedTag": "18",
                    "lockedDigest": POSTGRES_DIGEST,
                },
                {
                    "imageName": "redgate/flyway",
                    "lockedTag": "13.4.0",
                    "lockedDigest": FLYWAY_DIGEST,
                },
            ],
            "postgresVersion": "18.0",
            "flywayVersion": "13.4.0",
        },
        "contractSummary": {
            "verified": True,
            "migrationCount": 19,
            "managedTableCount": 54,
            "managedSchemaCount": 13,
            "physicalForeignKeyCount": 206,
            "mutationGuardCount": 53,
            "contractSha256": CONTRACT_DIGEST,
            "fieldContractSha256": FIELD_DIGEST,
        },
    }


def _fingerprint_mismatch_summary() -> dict[str, object]:
    summary = _passed_summary()
    summary["workflowOutcome"] = "FAILED"
    summary["reasonCode"] = "runtime_fingerprint_mismatch"
    summary["toolchain"]["postgresVersion"] = None
    summary["toolchain"]["flywayVersion"] = None
    summary["contractSummary"] = {
        "verified": False,
        "migrationCount": None,
        "managedTableCount": None,
        "managedSchemaCount": None,
        "physicalForeignKeyCount": None,
        "mutationGuardCount": None,
        "contractSha256": None,
        "fieldContractSha256": None,
    }
    return summary


def _locked_toolchain() -> dict[str, object]:
    return {
        "images": [
            {
                "image": "postgres",
                "tag": "18",
                "digest": POSTGRES_DIGEST,
                "resolvedAt": "2026-08-28T15:59:34Z",
            },
            {
                "image": "redgate/flyway",
                "tag": "13.4.0",
                "digest": FLYWAY_DIGEST,
                "resolvedAt": "2026-08-28T15:59:34Z",
            },
        ]
    }


class CiArtifactTests(unittest.TestCase):
    def _public(self, name: str):
        from runtime import verify_runtime

        self.assertTrue(
            hasattr(verify_runtime, name),
            f"runtime.verify_runtime must expose the public {name} API",
        )
        return getattr(verify_runtime, name)

    def _export(self, summary: dict[str, object], output_directory: Path) -> tuple[Path, Path]:
        exporter = self._public("export_ci_runtime_artifact")
        return exporter(summary, output_directory)

    def _assert_no_transaction_residue(self, parent: Path) -> None:
        residue = [
            path.name
            for path in parent.iterdir()
            if path.name.startswith(".schema-runtime-ci.")
        ]
        self.assertEqual(residue, [])

    def _fallback_repository(self) -> tuple[Path, Path]:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        repository = Path(temporary_directory.name)
        schema_root = repository / "database" / "schema-contract-52-plus-2"
        runtime_directory = schema_root / "runtime"
        runtime_directory.mkdir(parents=True)
        (repository / ".gitignore").write_text("/.artifacts/\n", encoding="utf-8")
        (runtime_directory / "toolchain.lock.json").write_text(
            json.dumps(_locked_toolchain(), indent=2) + "\n",
            encoding="utf-8",
        )
        subprocess_arguments = (
            ("init", "-q"),
            ("config", "user.name", "CI Artifact Test"),
            ("config", "user.email", "ci-artifact@example.invalid"),
            ("add", "."),
            ("commit", "-qm", "fixture"),
        )
        for arguments in subprocess_arguments:
            completed = subprocess.run(
                ["git", "-C", str(repository), *arguments],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
        return repository, schema_root

    def test_public_exporter_writes_only_the_exact_validated_pair(self) -> None:
        """Break caught: the public exporter leaves schema openings or extra upload files."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_directory = Path(temporary_directory) / "schema-runtime-ci"
            targets = self._export(_passed_summary(), output_directory)

            self.assertEqual(
                [path.name for path in targets],
                ["ci-runtime-summary.json", "ci-job-summary.md"],
            )
            self.assertEqual(
                sorted(path.name for path in output_directory.iterdir()),
                ["ci-job-summary.md", "ci-runtime-summary.json"],
            )
            summary = json.loads(targets[0].read_text(encoding="utf-8"))
            self.assertEqual(set(summary), TOP_LEVEL_FIELDS)
            self.assertEqual(set(summary["runs"][0]), RUN_FIELDS)
            self.assertEqual(set(summary["failureScenarios"][0]), SCENARIO_FIELDS)
            self.assertEqual(set(summary["runs"][0]["stages"][0]), STAGE_FIELDS)
            self.assertEqual(set(summary["toolchain"]), TOOLCHAIN_FIELDS)
            self.assertEqual(set(summary["toolchain"]["images"][0]), IMAGE_FIELDS)
            self.assertEqual(set(summary["contractSummary"]), CONTRACT_FIELDS)
            self._assert_no_transaction_residue(output_directory.parent)

    def test_unknown_fields_enums_order_hashes_and_inconsistent_outcomes_fail_closed(self) -> None:
        """Break caught: malformed or inconsistent typed facts are silently dropped or uploaded."""
        mutations = (
            lambda summary: summary.update({"command": "docker compose --password=secret"}),
            lambda summary: summary["runs"][0].update({"project": "schema-runtime-private"}),
            lambda summary: summary["runs"][0]["stages"][0].update({"stdout": "raw secret"}),
            lambda summary: summary["failureScenarios"][0].update({"exception": "private path"}),
            lambda summary: summary.update({"workflowOutcome": "UNKNOWN"}),
            lambda summary: summary.update({"reasonCode": "exception: arbitrary text"}),
            lambda summary: summary["runs"][0]["stages"][0].update({"stageName": "shell-command"}),
            lambda summary: summary["runs"][0]["stages"][0].update({"commandClass": "raw_argv"}),
            lambda summary: summary["runs"][0]["stages"][0].update({"diagnosticCode": "stderr text"}),
            lambda summary: summary["runs"].reverse(),
            lambda summary: summary["runs"][0]["stages"].reverse(),
            lambda summary: summary["failureScenarios"].reverse(),
            lambda summary: summary["runs"][0]["stages"][0].update({"stdoutSha256": "A" * 64}),
            lambda summary: summary["runs"][0]["stages"][0].update({"stderrSha256": "b" * 63}),
            lambda summary: summary["runs"][0]["stages"][0].update({"exitCode": True}),
            lambda summary: summary["runs"][0]["stages"][0].update({"timedOut": True}),
            lambda summary: summary.update({"reasonCode": "docker_compose_unavailable"}),
            lambda summary: summary.update({"failureScenarios": []}),
            lambda summary: summary["toolchain"].update({"postgresVersion": "17.9"}),
            lambda summary: summary["toolchain"]["images"][0].update({"lockedDigest": FLYWAY_DIGEST}),
            lambda summary: summary["contractSummary"].update({"managedTableCount": 53}),
        )
        for index, mutate in enumerate(mutations):
            with tempfile.TemporaryDirectory() as temporary_directory:
                output_directory = Path(temporary_directory) / "schema-runtime-ci"
                summary = _passed_summary()
                mutate(summary)
                with self.subTest(index=index):
                    with self.assertRaises(ValueError):
                        self._export(summary, output_directory)
                    self.assertFalse(output_directory.exists())
                    self._assert_no_transaction_residue(output_directory.parent)

    def test_compose_up_failure_requires_an_executed_failed_run_stage(self) -> None:
        """Break caught: a claimed compose startup failure contains no observed runtime failure."""
        summary = _passed_summary()
        summary.update(
            {
                "workflowOutcome": "FAILED",
                "reasonCode": "compose_up_failed",
                "failureScenarios": [],
                "toolchain": {
                    **summary["toolchain"],
                    "postgresVersion": None,
                    "flywayVersion": None,
                },
                "contractSummary": {
                    "verified": False,
                    "migrationCount": None,
                    "managedTableCount": None,
                    "managedSchemaCount": None,
                    "physicalForeignKeyCount": None,
                    "mutationGuardCount": None,
                    "contractSha256": None,
                    "fieldContractSha256": None,
                },
            }
        )
        for run in summary["runs"]:
            for stage in run["stages"]:
                stage.update(
                    {
                        "exitCode": None,
                        "timedOut": False,
                        "diagnosticCode": "not_started",
                        "stdoutSha256": None,
                        "stderrSha256": None,
                    }
                )

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaises(ValueError):
                self._export(summary, Path(temporary_directory) / "schema-runtime-ci")

    def test_verifier_diagnostics_are_finite_position_bound_and_failure_only(self) -> None:
        """Break caught: a safe verifier code is accepted on an unrelated or non-failed stage."""
        safe_code = "verifier_fingerprint_sqlstate_undefined_function_operator"

        def verifier_failure_summary(stage_name: str = "verifier-wait") -> dict[str, object]:
            summary = _passed_summary()
            summary.update(
                {
                    "workflowOutcome": "FAILED",
                    "reasonCode": "compose_up_failed",
                    "failureScenarios": [],
                }
            )
            summary["toolchain"].update({"postgresVersion": None, "flywayVersion": None})
            summary["contractSummary"] = {
                field: False if field == "verified" else None
                for field in CONTRACT_FIELDS
            }
            stage = next(
                item
                for item in summary["runs"][0]["stages"]
                if item["stageName"] == stage_name
            )
            stage.update({"exitCode": 3, "diagnosticCode": safe_code})
            return summary

        for stage_name in ("verifier-wait", "noop-verifier-wait"):
            with self.subTest(valid_stage=stage_name), tempfile.TemporaryDirectory() as temporary_directory:
                targets = self._export(
                    verifier_failure_summary(stage_name),
                    Path(temporary_directory) / "schema-runtime-ci",
                )
                exported = json.loads(targets[0].read_text(encoding="utf-8"))
                stage = next(
                    item
                    for item in exported["runs"][0]["stages"]
                    if item["stageName"] == stage_name
                )
                self.assertEqual(stage["diagnosticCode"], safe_code)
                self.assertEqual(set(exported), TOP_LEVEL_FIELDS)
                self.assertEqual(set(stage), STAGE_FIELDS)

        unavailable_summary = verifier_failure_summary()
        unavailable_wait = next(
            item
            for item in unavailable_summary["runs"][0]["stages"]
            if item["stageName"] == "verifier-wait"
        )
        unavailable_logs = next(
            item
            for item in unavailable_summary["runs"][0]["stages"]
            if item["stageName"] == "verifier-logs"
        )
        unavailable_wait["diagnosticCode"] = "verifier_logs_unavailable"
        unavailable_logs.update({"exitCode": 17, "diagnosticCode": "process_failed"})
        with tempfile.TemporaryDirectory() as temporary_directory:
            targets = self._export(
                unavailable_summary,
                Path(temporary_directory) / "schema-runtime-ci",
            )
            exported = json.loads(targets[0].read_text(encoding="utf-8"))
        self.assertEqual(
            next(
                item
                for item in exported["runs"][0]["stages"]
                if item["stageName"] == "verifier-logs"
            )["diagnosticCode"],
            "process_failed",
        )

        invalid_mutations = (
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "postgres-start"
            ).update({"exitCode": 3, "diagnosticCode": safe_code}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-wait"
            ).update({"exitCode": 0, "diagnosticCode": safe_code}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-wait"
            ).update({"exitCode": 124, "timedOut": True, "diagnosticCode": safe_code}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-wait"
            ).update({"exitCode": 127, "diagnosticCode": safe_code}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-wait"
            ).update({"exitCode": 3, "diagnosticCode": "verifier_raw_private_text"}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-logs"
            ).update({"exitCode": 17, "diagnosticCode": "process_failed"}),
            lambda summary: next(
                item for item in summary["runs"][0]["stages"]
                if item["stageName"] == "verifier-wait"
            ).update({"diagnosticCode": "verifier_logs_unavailable"}),
        )
        for index, mutate in enumerate(invalid_mutations):
            with self.subTest(invalid=index), tempfile.TemporaryDirectory() as temporary_directory:
                summary = verifier_failure_summary()
                mutate(summary)
                with self.assertRaises(ValueError):
                    self._export(summary, Path(temporary_directory) / "schema-runtime-ci")

    def test_fingerprint_mismatch_requires_complete_successful_runs_and_five_scenarios(self) -> None:
        """Break caught: the closed mismatch reason omits its completed A/B and scenario evidence."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            targets = self._export(
                _fingerprint_mismatch_summary(),
                Path(temporary_directory) / "schema-runtime-ci",
            )
            exported = json.loads(targets[0].read_text(encoding="utf-8"))

        self.assertEqual(set(exported), TOP_LEVEL_FIELDS)
        self.assertEqual(exported["workflowOutcome"], "FAILED")
        self.assertEqual(exported["reasonCode"], "runtime_fingerprint_mismatch")
        self.assertTrue(
            all(
                stage["diagnosticCode"] == "ok"
                for run in exported["runs"]
                for stage in run["stages"]
            )
        )
        self.assertEqual(len(exported["failureScenarios"]), 5)
        self.assertIsNone(exported["toolchain"]["postgresVersion"])
        self.assertFalse(exported["contractSummary"]["verified"])

        invalid_mutations = (
            lambda summary: summary.update({"failureScenarios": []}),
            lambda summary: summary["runs"][1]["stages"][0].update(
                {
                    "exitCode": 17,
                    "diagnosticCode": "process_failed",
                }
            ),
            lambda summary: summary["failureScenarios"][4]["stages"][0].update(
                {
                    "exitCode": 0,
                    "diagnosticCode": "ok",
                }
            ),
            lambda summary: summary["toolchain"].update(
                {"postgresVersion": "18.0", "flywayVersion": "13.4.0"}
            ),
            lambda summary: summary.update(
                {"contractSummary": copy.deepcopy(_passed_summary()["contractSummary"])}
            ),
        )
        for index, mutate in enumerate(invalid_mutations):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary_directory:
                candidate = _fingerprint_mismatch_summary()
                mutate(candidate)
                with self.assertRaises(ValueError):
                    self._export(candidate, Path(temporary_directory) / "schema-runtime-ci")

    def test_verified_contract_counts_require_real_integers(self) -> None:
        """Break caught: JSON floats or booleans are accepted as immutable contract counts."""
        expected_counts = {
            "migrationCount": 19,
            "managedTableCount": 54,
            "managedSchemaCount": 13,
            "physicalForeignKeyCount": 206,
            "mutationGuardCount": 53,
        }
        for field, expected in expected_counts.items():
            for invalid in (float(expected), True):
                with self.subTest(field=field, invalid=invalid):
                    summary = _passed_summary()
                    summary["contractSummary"][field] = invalid
                    with tempfile.TemporaryDirectory() as temporary_directory:
                        with self.assertRaises(ValueError):
                            self._export(summary, Path(temporary_directory) / "schema-runtime-ci")

    def test_not_started_and_executed_stage_shapes_are_mutually_exclusive(self) -> None:
        """Break caught: null stages carry hashes or executed stages omit captured-byte hashes."""
        invalid_stages = (
            {
                "exitCode": None,
                "timedOut": False,
                "diagnosticCode": "not_started",
                "stdoutSha256": "a" * 64,
                "stderrSha256": None,
            },
            {
                "exitCode": 0,
                "timedOut": False,
                "diagnosticCode": "ok",
                "stdoutSha256": None,
                "stderrSha256": "b" * 64,
            },
            {
                "exitCode": None,
                "timedOut": True,
                "diagnosticCode": "timed_out",
                "stdoutSha256": None,
                "stderrSha256": None,
            },
            {
                "exitCode": 124,
                "timedOut": False,
                "diagnosticCode": "timed_out",
                "stdoutSha256": "a" * 64,
                "stderrSha256": "b" * 64,
            },
        )
        for mutation in invalid_stages:
            with tempfile.TemporaryDirectory() as temporary_directory:
                summary = _passed_summary()
                summary["runs"][0]["stages"][0].update(mutation)
                with self.subTest(mutation=mutation):
                    with self.assertRaises(ValueError):
                        self._export(summary, Path(temporary_directory) / "schema-runtime-ci")

    def test_adversarial_runtime_bytes_are_represented_only_by_hashes(self) -> None:
        """Break caught: secrets, argv, paths, JSON, stdout, or stderr gain a schema slot."""
        secrets = (
            "PGPASSWORD=multiline-secret\\\ncontinued-secret",
            'docker compose --env-file "/tmp/private runtime.env"',
            '{"stdout":"json-secret","stderr":"second-secret"}',
            "postgresql://reviewer:uri-secret@db.internal/law",
            "RuntimeError: /private/tmp/exception-secret",
        )
        summary = _passed_summary()
        encoded = "\n".join(secrets).encode("utf-8")
        summary["runs"][0]["stages"][0]["stdoutSha256"] = hashlib.sha256(encoded).hexdigest()
        summary["runs"][0]["stages"][0]["stderrSha256"] = hashlib.sha256(encoded[::-1]).hexdigest()
        with tempfile.TemporaryDirectory() as temporary_directory:
            targets = self._export(summary, Path(temporary_directory) / "schema-runtime-ci")
            artifact_text = "\n".join(path.read_text(encoding="utf-8") for path in targets)
        for secret in secrets:
            self.assertNotIn(secret, artifact_text)
        for forbidden_field in (
            "argv",
            "command",
            "environment",
            "stdout",
            "stderr",
            "cwd",
            "evidencePath",
            "projectId",
            "containerId",
            "connectionString",
            "filename",
            "exception",
        ):
            self.assertNotIn(f'"{forbidden_field}"', artifact_text)

    def test_markdown_is_rendered_from_validated_safe_fields_only(self) -> None:
        """Break caught: Markdown accepts a free-form message or source absent from validated JSON."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            targets = self._export(_passed_summary(), Path(temporary_directory) / "schema-runtime-ci")
            summary = json.loads(targets[0].read_text(encoding="utf-8"))
            markdown = targets[1].read_text(encoding="utf-8")

        for value in (
            summary["workflowOutcome"],
            summary["reasonCode"],
            summary["runs"][0]["runId"],
            summary["runs"][0]["stages"][0]["stageName"],
            summary["runs"][0]["stages"][0]["stdoutSha256"],
        ):
            self.assertIn(str(value), markdown)
        self.assertNotIn("command", markdown.casefold())
        self.assertNotIn(".artifacts", markdown)
        self.assertNotIn("/tmp", markdown)

        renderer = self._public("render_ci_job_summary")
        mutated = _passed_summary()
        mutated["reasonCode"] = "exception text: /tmp/private-secret"
        with self.assertRaises(ValueError):
            renderer(mutated)

    def test_captured_stage_hashes_exact_subprocess_bytes_and_distinguishes_exit_124_from_timeout(self) -> None:
        """Break caught: hashing decoded text changes bytes or exit 124 is mislabeled as a timeout."""
        run_checked_result = self._public("run_checked_result")
        stdout = b"first\r\nnon-utf8:\xff\n"
        stderr = b"second\r\nnon-utf8:\xfe\n"
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            explicit = run_checked_result(
                [
                    sys.executable,
                    "-c",
                    (
                        "import os,sys; "
                        f"os.write(1, {stdout!r}); os.write(2, {stderr!r}); sys.exit(124)"
                    ),
                ],
                evidence_path=root / "explicit-124.json",
                timeout_seconds=2,
            )
            timed_out = run_checked_result(
                [sys.executable, "-c", "import time; time.sleep(10)"],
                evidence_path=root / "timeout.json",
                timeout_seconds=0.03,
            )

        self.assertEqual(explicit.exit_code, 124)
        self.assertFalse(explicit.timed_out)
        self.assertEqual(explicit.stdout_sha256, hashlib.sha256(stdout).hexdigest())
        self.assertEqual(explicit.stderr_sha256, hashlib.sha256(stderr).hexdigest())
        self.assertEqual(timed_out.exit_code, 124)
        self.assertTrue(timed_out.timed_out)
        self.assertEqual(timed_out.stdout_sha256, hashlib.sha256(b"").hexdigest())
        self.assertEqual(timed_out.stderr_sha256, hashlib.sha256(b"").hexdigest())

    def test_fallback_builder_maps_only_closed_step_outcomes_to_not_started_records(self) -> None:
        """Break caught: fallback embeds exception/step text or invents a successful runtime result."""
        builder = self._public("build_ci_runtime_fallback")
        expected = {
            "failure": ("FAILED", "workflow_step_failed"),
            "cancelled": ("BLOCKED", "workflow_step_cancelled"),
            "skipped": ("BLOCKED", "workflow_step_skipped"),
            "success": ("FAILED", "ci_artifact_missing"),
        }
        for step_outcome, (workflow_outcome, reason_code) in expected.items():
            summary = builder(
                git_commit="d" * 40,
                workflow_step_outcome=step_outcome,
                lock=_locked_toolchain(),
            )
            with self.subTest(step_outcome=step_outcome):
                self.assertEqual(summary["workflowOutcome"], workflow_outcome)
                self.assertEqual(summary["reasonCode"], reason_code)
                self.assertEqual(summary["failureScenarios"], [])
                self.assertFalse(summary["contractSummary"]["verified"])
                self.assertTrue(
                    all(
                        stage["exitCode"] is None
                        and stage["diagnosticCode"] == "not_started"
                        and stage["stdoutSha256"] is None
                        and stage["stderrSha256"] is None
                        for run in summary["runs"]
                        for stage in run["stages"]
                    )
                )
        for invalid in ("unknown", "failure: secret", "", None):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    builder(
                        git_commit="d" * 40,
                        workflow_step_outcome=invalid,
                        lock=_locked_toolchain(),
                    )

    def test_successful_workflow_step_with_missing_artifact_writes_failure_fallback_and_fails(self) -> None:
        """Break caught: a missing artifact after runtime success is exported but leaves the job green."""
        from runtime import verify_runtime

        repository, schema_root = self._fallback_repository()
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            exit_code = verify_runtime.main(
                ["export-ci-fallback", "--workflow-step-outcome", "success"],
                repository_root=repository,
                schema_root=schema_root,
            )
        self.assertEqual(exit_code, 4)
        summary = verify_runtime.validate_ci_runtime_artifact(
            repository / ".artifacts" / "schema-runtime-ci"
        )
        self.assertEqual(summary["workflowOutcome"], "FAILED")
        self.assertEqual(summary["reasonCode"], "ci_artifact_missing")

    def test_blocked_controller_builds_safe_artifact_from_memory_not_raw_logs(self) -> None:
        """Break caught: the CI summary is reconstructed by parsing runner-local evidence files."""
        from runtime import verify_runtime

        builder = self._public("build_ci_runtime_summary")
        manifest = json.loads(
            (PROJECT_ROOT / "generated" / "schema-contract-manifest.json").read_text(encoding="utf-8")
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            raw_directory = root / "schema-runtime"
            safe_directory = root / "schema-runtime-ci"
            result = verify_runtime.run_runtime_verification(
                PROJECT_ROOT,
                raw_directory,
                runs=2,
                compose_command=("definitely-not-a-docker-command", "compose"),
            )
            for raw_file in raw_directory.rglob("*.json"):
                raw_file.write_text(
                    '{"command":["secret-argv"],"stdout":"secret-output",'
                    '"stderr":"/tmp/private-secret"}\n',
                    encoding="utf-8",
                )
            summary = builder(result, git_commit="e" * 40, manifest=manifest)
            targets = self._export(summary, safe_directory)
            artifact_text = "\n".join(path.read_text(encoding="utf-8") for path in targets)

        self.assertEqual(summary["workflowOutcome"], "BLOCKED")
        self.assertEqual(summary["reasonCode"], "docker_compose_unavailable")
        self.assertTrue(
            any(
                stage["diagnosticCode"] == "command_unavailable"
                and stage["exitCode"] == 127
                and stage["stdoutSha256"] is not None
                and stage["stderrSha256"] is not None
                for run in summary["runs"]
                for stage in run["stages"]
            )
        )
        for forbidden in ("secret-argv", "secret-output", "/tmp/private-secret"):
            self.assertNotIn(forbidden, artifact_text)

    def test_existing_pair_replaces_as_a_pair_and_failed_swap_removes_stale_pair(self) -> None:
        """Break caught: failed replacement leaves a stale or new/old pair for always-upload."""
        from runtime import verify_runtime

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            output_directory = root / "schema-runtime-ci"
            old_targets = self._export(_passed_summary(), output_directory)
            self.assertTrue(all(path.is_file() for path in old_targets))
            replacement = copy.deepcopy(_passed_summary())
            replacement["gitCommit"] = "f" * 40
            real_replace = os.replace

            def fail_new_directory_swap(source, destination):
                source_path = Path(source)
                destination_path = Path(destination)
                if (
                    destination_path == output_directory
                    and source_path.name.startswith(".schema-runtime-ci.publish-")
                ):
                    self.assertEqual(source_path.parent, output_directory.parent)
                    raise OSError("injected directory swap failure")
                return real_replace(source, destination)

            with mock.patch.object(verify_runtime.os, "replace", side_effect=fail_new_directory_swap):
                with self.assertRaises(ValueError):
                    self._export(replacement, output_directory)

            self.assertFalse(output_directory.exists())
            self._assert_no_transaction_residue(root)

            new_targets = self._export(replacement, output_directory)
            self.assertEqual(json.loads(new_targets[0].read_text(encoding="utf-8"))["gitCommit"], "f" * 40)
            self.assertEqual(
                sorted(path.name for path in output_directory.iterdir()),
                ["ci-job-summary.md", "ci-runtime-summary.json"],
            )
            self._assert_no_transaction_residue(root)

    def test_write_failure_symlink_nonregular_partial_and_extra_targets_fail_closed(self) -> None:
        """Break caught: unsafe targets are followed or a failed publication leaves temporary content."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            too_long = root / ("x" * 300)
            with self.assertRaises(ValueError):
                self._export(_passed_summary(), too_long)
            self.assertEqual(list(root.iterdir()), [])

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            outside = root / "outside"
            outside.mkdir()
            output_directory = root / "schema-runtime-ci"
            output_directory.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                self._export(_passed_summary(), output_directory)
            self.assertEqual(list(outside.iterdir()), [])
            self.assertFalse(output_directory.exists())

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            loop_a = root / "schema-runtime-ci"
            loop_b = root / "loop-b"
            loop_a.symlink_to(loop_b)
            loop_b.symlink_to(loop_a)
            with self.assertRaises(ValueError):
                self._export(_passed_summary(), loop_a)
            self.assertFalse(loop_a.is_symlink())
            self.assertTrue(loop_b.is_symlink())

        target_shapes = ("regular-directory-with-symlink", "fifo", "partial", "extra", "regular-file")
        for shape in target_shapes:
            with tempfile.TemporaryDirectory() as temporary_directory:
                root = Path(temporary_directory)
                output_directory = root / "schema-runtime-ci"
                if shape == "regular-file":
                    output_directory.write_text("occupied\n", encoding="utf-8")
                else:
                    output_directory.mkdir()
                    summary_target = output_directory / "ci-runtime-summary.json"
                    markdown_target = output_directory / "ci-job-summary.md"
                    if shape == "regular-directory-with-symlink":
                        summary_target.symlink_to(root / "outside.json")
                        markdown_target.write_text("old\n", encoding="utf-8")
                    elif shape == "fifo":
                        os.mkfifo(summary_target)
                        markdown_target.write_text("old\n", encoding="utf-8")
                    elif shape == "partial":
                        summary_target.write_text("{}\n", encoding="utf-8")
                    else:
                        summary_target.write_text("{}\n", encoding="utf-8")
                        markdown_target.write_text("old\n", encoding="utf-8")
                        (output_directory / "raw.log").write_text("secret\n", encoding="utf-8")
                with self.subTest(shape=shape):
                    with self.assertRaises(ValueError):
                        self._export(_passed_summary(), output_directory)
                    self.assertFalse(output_directory.exists())
                    self._assert_no_transaction_residue(root)

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            outside = root / "outside.json"
            outside.write_text("do not overwrite\n", encoding="utf-8")
            output_directory = root / "schema-runtime-ci"
            output_directory.mkdir()
            os.link(outside, output_directory / "ci-runtime-summary.json")
            (output_directory / "ci-job-summary.md").write_text("old\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                self._export(_passed_summary(), output_directory)
            self.assertFalse(output_directory.exists())
            self.assertEqual(outside.read_text(encoding="utf-8"), "do not overwrite\n")
            self._assert_no_transaction_residue(root)

    def test_validation_failure_removes_an_existing_stale_pair_before_always_upload(self) -> None:
        """Break caught: invalid new data leaves a prior run's valid-looking pair at the upload path."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            output_directory = root / "schema-runtime-ci"
            self._export(_passed_summary(), output_directory)
            invalid = _passed_summary()
            invalid["reasonCode"] = "exception: secret text"
            with self.assertRaises(ValueError):
                self._export(invalid, output_directory)
            self.assertFalse(output_directory.exists())
            self._assert_no_transaction_residue(root)

    def test_validate_and_fallback_unlink_an_unsafe_artifacts_parent_without_following_it(self) -> None:
        """Break caught: failed cleanup leaves a symlinked always-upload path pointing outside the checkout."""
        from runtime import verify_runtime

        commands = (
            ("validate", ["validate-ci-artifact"]),
            ("fallback", ["export-ci-fallback", "--workflow-step-outcome", "failure"]),
        )
        for command_name, command in commands:
            for parent_shape in ("external", "dangling"):
                with self.subTest(command=command_name, parent_shape=parent_shape):
                    repository, schema_root = self._fallback_repository()
                    artifacts_parent = repository / ".artifacts"
                    external = repository.parent / f"{repository.name}-{command_name}-{parent_shape}"
                    external_before: dict[str, bytes] | None = None
                    if parent_shape == "external":
                        external.mkdir()
                        unsafe_pair = external / "schema-runtime-ci"
                        unsafe_pair.mkdir()
                        (unsafe_pair / "ci-runtime-summary.json").write_text(
                            '{"secret":"outside-summary"}\n',
                            encoding="utf-8",
                        )
                        (unsafe_pair / "ci-job-summary.md").write_text(
                            "outside-markdown-secret\n",
                            encoding="utf-8",
                        )
                        (external / "sentinel.txt").write_text(
                            "outside-sentinel-secret\n",
                            encoding="utf-8",
                        )
                        external_before = {
                            path.relative_to(external).as_posix(): path.read_bytes()
                            for path in external.rglob("*")
                            if path.is_file()
                        }
                        artifacts_parent.symlink_to(external, target_is_directory=True)
                    else:
                        artifacts_parent.symlink_to(external, target_is_directory=True)

                    self.assertTrue(artifacts_parent.is_symlink())
                    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                        exit_code = verify_runtime.main(
                            command,
                            repository_root=repository,
                            schema_root=schema_root,
                        )
                    self.assertEqual(exit_code, 4)
                    self.assertFalse(artifacts_parent.is_symlink())
                    with self.assertRaises(FileNotFoundError):
                        artifacts_parent.lstat()
                    self.assertFalse((artifacts_parent / "schema-runtime-ci").exists())
                    if external_before is not None:
                        self.assertEqual(
                            {
                                path.relative_to(external).as_posix(): path.read_bytes()
                                for path in external.rglob("*")
                                if path.is_file()
                            },
                            external_before,
                        )

    def test_failed_ci_cleanup_preserves_raw_and_unrelated_artifacts(self) -> None:
        """Break caught: cleaning an invalid CI pair removes raw diagnostics or unrelated artifacts."""
        from runtime import verify_runtime

        repository, schema_root = self._fallback_repository()
        artifacts_parent = repository / ".artifacts"
        raw_sentinel = artifacts_parent / "schema-runtime" / "raw-sentinel.log"
        unrelated_sentinel = artifacts_parent / "other-artifact" / "sentinel.txt"
        unsafe_pair = artifacts_parent / "schema-runtime-ci"
        raw_sentinel.parent.mkdir(parents=True)
        unrelated_sentinel.parent.mkdir()
        unsafe_pair.mkdir()
        raw_sentinel.write_text("raw evidence stays local\n", encoding="utf-8")
        unrelated_sentinel.write_text("unrelated artifact stays local\n", encoding="utf-8")
        (unsafe_pair / "ci-runtime-summary.json").write_text("{}\n", encoding="utf-8")

        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            exit_code = verify_runtime.main(
                ["validate-ci-artifact"],
                repository_root=repository,
                schema_root=schema_root,
            )

        self.assertEqual(exit_code, 4)
        self.assertFalse(unsafe_pair.exists())
        self.assertEqual(raw_sentinel.read_text(encoding="utf-8"), "raw evidence stays local\n")
        self.assertEqual(
            unrelated_sentinel.read_text(encoding="utf-8"),
            "unrelated artifact stays local\n",
        )

    def test_fallback_helper_exception_fails_closed_and_removes_stale_pair(self) -> None:
        """Break caught: fallback helper exceptions traceback, leak markers, or keep stale upload data."""
        from runtime import verify_runtime

        repository, schema_root = self._fallback_repository()
        unsafe_pair = repository / ".artifacts" / "schema-runtime-ci"
        unsafe_pair.mkdir(parents=True)
        (unsafe_pair / "ci-runtime-summary.json").write_text(
            "EXCEPTION_MARKER stale unsafe summary\n",
            encoding="utf-8",
        )
        (unsafe_pair / "ci-job-summary.md").write_text(
            "EXCEPTION_MARKER stale unsafe markdown\n",
            encoding="utf-8",
        )
        stdout = io.StringIO()
        stderr = io.StringIO()
        exception_escaped = False
        with mock.patch.object(
            verify_runtime,
            "build_ci_runtime_fallback",
            side_effect=RuntimeError(
                "EXCEPTION_MARKER /private/tmp/PATH_MARKER "
                "PROJECT_MARKER CONNECTION_MARKER"
            ),
        ):
            try:
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    exit_code = verify_runtime.main(
                        ["export-ci-fallback", "--workflow-step-outcome", "failure"],
                        repository_root=repository,
                        schema_root=schema_root,
                    )
            except RuntimeError:
                exception_escaped = True
                exit_code = None

        self.assertFalse(exception_escaped, "fallback helper leaked an ordinary exception")
        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(
            stderr.getvalue(),
            "structured runtime CI fallback export failed\n",
        )
        self.assertFalse(unsafe_pair.exists())
        for marker in (
            "EXCEPTION_MARKER",
            "PATH_MARKER",
            "PROJECT_MARKER",
            "CONNECTION_MARKER",
            "Traceback",
            "RuntimeError",
            "/private/tmp",
        ):
            self.assertNotIn(marker, stdout.getvalue() + stderr.getvalue())

    def test_validation_helper_exception_fails_closed_and_removes_stale_pair(self) -> None:
        """Break caught: validation helper exceptions traceback, leak markers, or keep stale upload data."""
        from runtime import verify_runtime

        repository, schema_root = self._fallback_repository()
        unsafe_pair = repository / ".artifacts" / "schema-runtime-ci"
        unsafe_pair.mkdir(parents=True)
        (unsafe_pair / "ci-runtime-summary.json").write_text(
            "EXCEPTION_MARKER stale unsafe summary\n",
            encoding="utf-8",
        )
        (unsafe_pair / "ci-job-summary.md").write_text(
            "EXCEPTION_MARKER stale unsafe markdown\n",
            encoding="utf-8",
        )
        stdout = io.StringIO()
        stderr = io.StringIO()
        exception_escaped = False
        with mock.patch.object(
            verify_runtime,
            "validate_ci_runtime_artifact",
            side_effect=RuntimeError(
                "EXCEPTION_MARKER /private/tmp/PATH_MARKER "
                "PROJECT_MARKER CONNECTION_MARKER"
            ),
        ):
            try:
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    exit_code = verify_runtime.main(
                        ["validate-ci-artifact"],
                        repository_root=repository,
                        schema_root=schema_root,
                    )
            except RuntimeError:
                exception_escaped = True
                exit_code = None

        self.assertFalse(exception_escaped, "validation helper leaked an ordinary exception")
        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(
            stderr.getvalue(),
            "structured runtime CI artifact validation failed\n",
        )
        self.assertFalse(unsafe_pair.exists())
        for marker in (
            "EXCEPTION_MARKER",
            "PATH_MARKER",
            "PROJECT_MARKER",
            "CONNECTION_MARKER",
            "Traceback",
            "RuntimeError",
            "/private/tmp",
        ):
            self.assertNotIn(marker, stdout.getvalue() + stderr.getvalue())

    def test_validation_cleanup_ebusy_leaves_invalid_pair_but_workflow_gate_blocks_upload(self) -> None:
        """Break caught: always-upload publishes an invalid pair when validator cleanup is EBUSY."""
        from runtime import verify_runtime

        repository, schema_root = self._fallback_repository()
        unsafe_pair = repository / ".artifacts" / "schema-runtime-ci"
        unsafe_pair.mkdir(parents=True)
        unsafe_summary = b'{"secret":"EBUSY_SECRET_MARKER"}\n'
        unsafe_markdown = b"EBUSY_SECRET_MARKER\n"
        (unsafe_pair / "ci-runtime-summary.json").write_bytes(unsafe_summary)
        (unsafe_pair / "ci-job-summary.md").write_bytes(unsafe_markdown)
        stdout = io.StringIO()
        stderr = io.StringIO()

        with mock.patch.object(
            verify_runtime.shutil,
            "rmtree",
            side_effect=OSError(errno.EBUSY, "device or resource busy", str(unsafe_pair)),
        ):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = verify_runtime.main(
                    ["validate-ci-artifact"],
                    repository_root=repository,
                    schema_root=schema_root,
                )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout.getvalue(), "")
        self.assertEqual(
            stderr.getvalue(),
            "structured runtime CI artifact validation failed\n",
        )
        self.assertTrue(unsafe_pair.is_dir())
        self.assertEqual(
            (unsafe_pair / "ci-runtime-summary.json").read_bytes(),
            unsafe_summary,
        )
        self.assertEqual(
            (unsafe_pair / "ci-job-summary.md").read_bytes(),
            unsafe_markdown,
        )

        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        steps = workflow["jobs"]["runtime-postgresql-18"]["steps"]
        summary_step = next(
            step for step in steps if str(step.get("name", "")).startswith("Summarize")
        )
        upload_step = next(
            step
            for step in steps
            if str(step.get("uses", "")).startswith("actions/upload-artifact@")
        )
        self.assertEqual(summary_step.get("id"), "runtime_summary")
        self.assertEqual(summary_step.get("if"), "${{ always() }}")
        self.assertNotIn("continue-on-error", summary_step)
        self.assertTrue(str(summary_step["run"]).startswith("set -euo pipefail\n"))
        self.assertEqual(
            upload_step.get("if"),
            "${{ always() && steps.runtime_summary.outcome == 'success' }}",
        )
        self.assertNotIn("continue-on-error", upload_step)

    def test_failed_and_blocked_closed_artifacts_remain_upload_eligible_after_validation(self) -> None:
        """Break caught: gating on runtime success discards validated FAILED/BLOCKED artifacts."""
        from runtime import verify_runtime

        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        steps = workflow["jobs"]["runtime-postgresql-18"]["steps"]
        runtime_step = next(step for step in steps if step.get("id") == "runtime")
        summary_step = next(
            step for step in steps if str(step.get("name", "")).startswith("Summarize")
        )
        upload_step = next(
            step
            for step in steps
            if str(step.get("uses", "")).startswith("actions/upload-artifact@")
        )

        self.assertNotIn("continue-on-error", runtime_step)
        self.assertEqual(summary_step.get("id"), "runtime_summary")
        self.assertEqual(summary_step.get("if"), "${{ always() }}")
        self.assertNotIn("continue-on-error", summary_step)
        self.assertEqual(
            upload_step.get("if"),
            "${{ always() && steps.runtime_summary.outcome == 'success' }}",
        )

        for step_outcome, expected_artifact_outcome in (
            ("failure", "FAILED"),
            ("cancelled", "BLOCKED"),
        ):
            with self.subTest(step_outcome=step_outcome):
                repository, schema_root = self._fallback_repository()
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    fallback_exit = verify_runtime.main(
                        ["export-ci-fallback", "--workflow-step-outcome", step_outcome],
                        repository_root=repository,
                        schema_root=schema_root,
                    )
                    validation_exit = verify_runtime.main(
                        ["validate-ci-artifact"],
                        repository_root=repository,
                        schema_root=schema_root,
                    )
                self.assertEqual(fallback_exit, 0)
                self.assertEqual(validation_exit, 0)
                artifact = verify_runtime.validate_ci_runtime_artifact(
                    repository / ".artifacts" / "schema-runtime-ci"
                )
                self.assertEqual(artifact["workflowOutcome"], expected_artifact_outcome)


class HostedCiOnlyVerificationTests(unittest.TestCase):
    _CONSOLE_MARKERS = (
        "EVIDENCE_DIR_MARKER",
        "PROJECT_MARKER",
        "CONNECTION_MARKER",
        "STDOUT_MARKER",
        "STDERR_MARKER",
        "EXCEPTION_MARKER",
    )

    def _repository(self) -> tuple[Path, Path, str]:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        repository = Path(temporary_directory.name)
        schema_root = repository / "database" / "schema-contract-52-plus-2"
        runtime_directory = schema_root / "runtime"
        generated_directory = schema_root / "generated"
        publication_directory = repository / "docs" / "evidence" / "schema-runtime"
        runtime_directory.mkdir(parents=True)
        generated_directory.mkdir()
        publication_directory.mkdir(parents=True)
        (repository / ".gitignore").write_text("/.artifacts/\n", encoding="utf-8")
        (publication_directory / "README.md").write_text(
            "No successful evidence yet.\n",
            encoding="utf-8",
        )
        (runtime_directory / "toolchain.lock.json").write_text(
            json.dumps(_locked_toolchain(), indent=2) + "\n",
            encoding="utf-8",
        )
        (generated_directory / "schema-contract-manifest.json").write_text(
            json.dumps(
                {
                    "applicationTableCount": 52,
                    "physicalTableCountAfterFlywayBootstrap": 54,
                    "schemas": [f"schema-{index}" for index in range(13)],
                    "physicalForeignKeyWhitelist": [f"fk-{index}" for index in range(206)],
                    "contractSha256": CONTRACT_DIGEST,
                    "fieldContractSha256": FIELD_DIGEST,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        for arguments in (
            ("init", "-q"),
            ("config", "user.name", "CI-only verification test"),
            ("config", "user.email", "ci-only@example.invalid"),
            ("add", "."),
            ("commit", "-qm", "fixture"),
        ):
            completed = subprocess.run(
                ["git", "-C", str(repository), *arguments],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
        head = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        return repository, schema_root, head

    def _passed_runtime_result(self):
        from runtime import verify_runtime

        captured: dict[str, object] = {}
        for _, definitions in verify_runtime._CI_RUN_STAGE_DEFINITIONS:
            for _, _, path in definitions:
                captured[path] = verify_runtime.CapturedStageResult(
                    exit_code=0,
                    timed_out=False,
                    stdout_sha256="a" * 64,
                    stderr_sha256="b" * 64,
                )
        for _, expected_failure_stage, definitions in verify_runtime._CI_FAILURE_STAGE_DEFINITIONS:
            for stage_name, _, path in definitions:
                captured[path] = verify_runtime.CapturedStageResult(
                    exit_code=17 if stage_name == expected_failure_stage else 0,
                    timed_out=False,
                    stdout_sha256="a" * 64,
                    stderr_sha256="b" * 64,
                )
        fingerprint = "0123456789abcdef0123456789abcdef"
        postgres_version = (
            "PostgreSQL 18.0 (Debian 18.0-1.pgdg13+3) on x86_64-pc-linux-gnu, "
            "compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit"
        )

        def summary(value: str) -> dict[str, object]:
            return {
                "fingerprint": value,
                "postgresVersion": postgres_version,
                "serverVersion": "18.0 fixture",
                "status": "PASSED",
            }

        def service(name: str, *, verifier_summary: dict[str, object] | None) -> dict[str, object]:
            return {
                "exitCode": 0,
                "logsReturnCode": 0 if verifier_summary is not None else None,
                "service": name,
                "startReturnCode": 0,
                "statusReturnCode": 0,
                "summary": verifier_summary,
                "waitReturnCode": 0,
            }

        runs = [
            {
                "cleanupReturnCode": 0,
                "flyway": service("flyway", verifier_summary=None),
                "id": "run-01",
                "initialFingerprint": fingerprint,
                "noopFingerprint": fingerprint,
                "noopMigrateReturnCode": 0,
                "noopVerifier": service("verifier", verifier_summary=summary(fingerprint)),
                "project": "PROJECT_MARKER",
                "postgresReturnCode": 0,
                "upReturnCode": 0,
                "verifier": service("verifier", verifier_summary=summary(fingerprint)),
            },
            {
                "cleanupReturnCode": 0,
                "flyway": service("flyway", verifier_summary=None),
                "id": "run-02",
                "initialFingerprint": fingerprint,
                "noopFingerprint": None,
                "noopMigrateReturnCode": None,
                "noopVerifier": None,
                "project": "EVIDENCE_DIR_MARKER CONNECTION_MARKER STDOUT_MARKER STDERR_MARKER EXCEPTION_MARKER",
                "postgresReturnCode": 0,
                "upReturnCode": 0,
                "verifier": service("verifier", verifier_summary=summary(fingerprint)),
            },
        ]
        return verify_runtime.RuntimeVerificationResult(
            {
                "status": "PASSED",
                "runs": runs,
                "failureScenarios": [
                    {"name": scenario_name, "status": "PASSED"}
                    for scenario_name, _, _ in verify_runtime._CI_FAILURE_STAGE_DEFINITIONS
                ],
                "runtimeIdentity": {
                    "flywayVersion": "Flyway Community Edition 13.4.0 by Redgate",
                },
            },
            ci_stage_results=captured,
            ci_locked_images=verify_runtime._ci_locked_images_from_lock(_locked_toolchain()),
        )

    def _mismatched_runtime_result(self):
        result = self._passed_runtime_result()
        second_fingerprint = "fedcba9876543210fedcba9876543210"
        result["status"] = "FAILED"
        result["reason"] = "runtime_fingerprint_mismatch"
        result.pop("runtimeIdentity")
        result["runs"][1]["initialFingerprint"] = second_fingerprint
        result["runs"][1]["verifier"]["summary"]["fingerprint"] = second_fingerprint
        return result

    def _nonpassed_runtime_result(self, status: str):
        from runtime import verify_runtime

        reason = {
            "FAILED": "compose_up_failed",
            "BLOCKED": "docker_compose_unavailable",
        }[status]
        exit_code = 17 if status == "FAILED" else 127
        captured = {
            f"run-{index:02d}/postgres-start.json": verify_runtime.CapturedStageResult(
                exit_code=exit_code,
                timed_out=False,
                stdout_sha256="a" * 64,
                stderr_sha256="b" * 64,
            )
            for index in (1, 2)
        }
        return verify_runtime.RuntimeVerificationResult(
            {
                "status": status,
                "reason": reason,
                "failureScenarios": [],
                "runs": [
                    {
                        "evidenceDir": "EVIDENCE_DIR_MARKER",
                        "project": "PROJECT_MARKER",
                        "connection": "CONNECTION_MARKER",
                        "stdout": "STDOUT_MARKER",
                        "stderr": "STDERR_MARKER",
                        "exception": "EXCEPTION_MARKER",
                    }
                ],
            },
            ci_stage_results=captured,
            ci_locked_images=verify_runtime._ci_locked_images_from_lock(_locked_toolchain()),
        )

    def _capture_main(self, verify_runtime, arguments: list[str], **kwargs) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            exit_code = verify_runtime.main(arguments, **kwargs)
        return exit_code, stdout.getvalue(), stderr.getvalue()

    def _run_main(self, verify_runtime, arguments: list[str], **kwargs) -> int:
        exit_code, _, _ = self._capture_main(verify_runtime, arguments, **kwargs)
        return exit_code

    def _seed_stale_ci_pair(self, repository: Path) -> Path:
        output_directory = repository / ".artifacts" / "schema-runtime-ci"
        output_directory.mkdir(parents=True)
        (output_directory / "ci-runtime-summary.json").write_text(
            "EXCEPTION_MARKER stale unsafe summary\n",
            encoding="utf-8",
        )
        (output_directory / "ci-job-summary.md").write_text(
            "EXCEPTION_MARKER stale unsafe markdown\n",
            encoding="utf-8",
        )
        return output_directory

    def _assert_closed_exception_console(self, stdout: str, stderr: str) -> None:
        for marker in (
            *self._CONSOLE_MARKERS,
            "Traceback",
            "RuntimeError",
            "/private/tmp",
        ):
            self.assertNotIn(marker, stdout + stderr)

    def test_typed_verifier_diagnostic_replaces_only_failed_wait_code_without_raw_data(self) -> None:
        """Break caught: runner-local verifier text enters JSON/Markdown or cannot refine process_failed."""
        from runtime import verify_runtime

        captured: dict[str, object] = {}
        for stage_name, _, path in verify_runtime._CI_RUN_STAGE_DEFINITIONS[0][1]:
            if stage_name.startswith("noop-"):
                continue
            captured[path] = verify_runtime.CapturedStageResult(
                exit_code=3 if stage_name == "verifier-wait" else 0,
                timed_out=False,
                stdout_sha256="a" * 64,
                stderr_sha256="b" * 64,
            )
        hostile = (
            "password=hunter2 postgresql://user:secret@db.internal/law "
            "/private/tmp/runtime token=top-secret"
        )
        safe_codes = (
            "verifier_capability_query_insert",
            "verifier_fingerprint_error",
            "verifier_fingerprint_sqlstate_ambiguous_function_operator",
            "verifier_fingerprint_sqlstate_undefined_function_operator",
            "verifier_fingerprint_sqlstate_datatype_mismatch",
            "verifier_fingerprint_sqlstate_cannot_coerce",
            "verifier_fingerprint_sqlstate_indeterminate_datatype",
            "verifier_fingerprint_sqlstate_undefined_column",
            "verifier_fingerprint_sqlstate_undefined_table",
            "verifier_fingerprint_sqlstate_undefined_object",
            "verifier_fingerprint_sqlstate_insufficient_privilege",
            "verifier_fingerprint_sqlstate_syntax_error",
            "verifier_fingerprint_sqlstate_feature_not_supported",
            "verifier_fingerprint_sqlstate_internal_error",
            "verifier_fingerprint_sqlstate_unmapped",
            "verifier_parser_evidence_invalid",
            "verifier_parser_error_missing",
            "verifier_parser_error_multiple",
            "verifier_parser_record_missing",
            "verifier_parser_record_multiple",
            "verifier_parser_assertion_multiple",
            "verifier_parser_assertion_malformed",
            "verifier_parser_phase_conflict",
        )
        for safe_code in safe_codes:
            with self.subTest(safe_code=safe_code):
                result = verify_runtime.RuntimeVerificationResult(
                    {
                        "status": "FAILED",
                        "reason": "compose_up_failed",
                        "failureScenarios": [],
                        "runs": [{"stderr": hostile, "stdout": hostile, "project": hostile}],
                    },
                    ci_stage_results=captured,
                    ci_locked_images=verify_runtime._ci_locked_images_from_lock(_locked_toolchain()),
                    ci_stage_diagnostics={
                        "run-01/verifier-wait.json": safe_code,
                    },
                )

                summary = verify_runtime.build_ci_runtime_summary(
                    result,
                    git_commit="d" * 40,
                    manifest={},
                )
                failed_wait = next(
                    stage
                    for stage in summary["runs"][0]["stages"]
                    if stage["stageName"] == "verifier-wait"
                )
                verifier_logs = next(
                    stage
                    for stage in summary["runs"][0]["stages"]
                    if stage["stageName"] == "verifier-logs"
                )
                self.assertEqual(failed_wait["diagnosticCode"], safe_code)
                self.assertEqual(verifier_logs["diagnosticCode"], "ok")
                self.assertEqual(set(summary), TOP_LEVEL_FIELDS)
                self.assertEqual(set(failed_wait), STAGE_FIELDS)

                with tempfile.TemporaryDirectory() as temporary_directory:
                    targets = verify_runtime.export_ci_runtime_artifact(
                        summary,
                        Path(temporary_directory) / "schema-runtime-ci",
                    )
                    validated = verify_runtime.validate_ci_runtime_artifact(
                        Path(temporary_directory) / "schema-runtime-ci"
                    )
                    artifact_text = "\n".join(
                        path.read_text(encoding="utf-8") for path in targets
                    )
                validated_wait = next(
                    stage
                    for stage in validated["runs"][0]["stages"]
                    if stage["stageName"] == "verifier-wait"
                )
                self.assertEqual(validated_wait["diagnosticCode"], safe_code)
                self.assertIn(safe_code, artifact_text)
                for secret in ("hunter2", "db.internal", "/private/tmp", "top-secret"):
                    self.assertNotIn(secret, artifact_text)

    def test_builder_requires_bound_valid_fingerprints_for_pass_and_mismatch(self) -> None:
        """Break caught: typed controller results can claim PASS/mismatch with inconsistent fingerprints."""
        from runtime import verify_runtime

        repository, schema_root, head = self._repository()
        manifest = json.loads(
            (schema_root / "generated" / "schema-contract-manifest.json").read_text(encoding="utf-8")
        )
        mismatch = self._mismatched_runtime_result()
        summary = verify_runtime.build_ci_runtime_summary(
            mismatch,
            git_commit=head,
            manifest=manifest,
        )
        self.assertEqual(summary["workflowOutcome"], "FAILED")
        self.assertEqual(summary["reasonCode"], "runtime_fingerprint_mismatch")
        self.assertEqual(len(summary["failureScenarios"]), 5)
        self.assertIsNone(summary["toolchain"]["postgresVersion"])
        self.assertFalse(summary["contractSummary"]["verified"])

        inconsistent_pass = self._passed_runtime_result()
        second_fingerprint = "fedcba9876543210fedcba9876543210"
        inconsistent_pass["runs"][1]["initialFingerprint"] = second_fingerprint
        inconsistent_pass["runs"][1]["verifier"]["summary"]["fingerprint"] = second_fingerprint
        with self.assertRaises(ValueError):
            verify_runtime.build_ci_runtime_summary(
                inconsistent_pass,
                git_commit=head,
                manifest=manifest,
            )

        inconsistent_mismatch = self._mismatched_runtime_result()
        first_fingerprint = inconsistent_mismatch["runs"][0]["initialFingerprint"]
        inconsistent_mismatch["runs"][1]["initialFingerprint"] = first_fingerprint
        inconsistent_mismatch["runs"][1]["verifier"]["summary"]["fingerprint"] = first_fingerprint
        with self.assertRaises(ValueError):
            verify_runtime.build_ci_runtime_summary(
                inconsistent_mismatch,
                git_commit=head,
                manifest=manifest,
            )

        for invalid in ("", "same", "A" * 32, "g" * 32):
            with self.subTest(invalid=invalid):
                invalid_pass = self._passed_runtime_result()
                for run in invalid_pass["runs"]:
                    run["initialFingerprint"] = invalid
                    run["verifier"]["summary"]["fingerprint"] = invalid
                invalid_pass["runs"][0]["noopFingerprint"] = invalid
                invalid_pass["runs"][0]["noopVerifier"]["summary"]["fingerprint"] = invalid
                with self.assertRaises(ValueError):
                    verify_runtime.build_ci_runtime_summary(
                        invalid_pass,
                        git_commit=head,
                        manifest=manifest,
                    )

        self.assertEqual(
            sorted(path.name for path in repository.joinpath("docs/evidence/schema-runtime").iterdir()),
            ["README.md"],
        )

    def test_inconsistent_pass_fails_ci_export_and_removes_stale_pair(self) -> None:
        """Break caught: a synthesized cross-run mismatch exports PASS or leaves stale upload data."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()
        stale_pair = self._seed_stale_ci_pair(repository)
        result = self._passed_runtime_result()
        second_fingerprint = "fedcba9876543210fedcba9876543210"
        result["runs"][1]["initialFingerprint"] = second_fingerprint
        result["runs"][1]["verifier"]["summary"]["fingerprint"] = second_fingerprint

        def runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            return result

        exit_code, stdout, stderr = self._capture_main(
            verify_runtime,
            [
                "verify",
                "--ci-only",
                "--runs",
                "2",
                "--evidence-dir",
                ".artifacts/schema-runtime",
            ],
            repository_root=repository,
            schema_root=schema_root,
            runtime_runner=runner,
        )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(stderr, "structured runtime CI artifact export failed\n")
        self.assertFalse(stale_pair.exists())
        self.assertEqual(
            sorted(path.name for path in repository.joinpath("docs/evidence/schema-runtime").iterdir()),
            ["README.md"],
        )

    def test_ci_only_console_emits_only_closed_outcome_for_passed_failed_and_blocked(self) -> None:
        """Break caught: Actions logs receive raw paths, projects, connections, or process text."""
        from runtime import verify_runtime

        cases = (
            ("PASSED", "runtime_verified", 0),
            ("FAILED", "compose_up_failed", 4),
            ("BLOCKED", "docker_compose_unavailable", 5),
        )
        for status, reason_code, expected_exit_code in cases:
            with self.subTest(status=status):
                repository, schema_root, _ = self._repository()
                result = (
                    self._passed_runtime_result()
                    if status == "PASSED"
                    else self._nonpassed_runtime_result(status)
                )

                def runner(_schema_root, _output_directory, *, runs):
                    self.assertEqual(runs, 2)
                    return result

                exit_code, stdout, stderr = self._capture_main(
                    verify_runtime,
                    [
                        "verify",
                        "--ci-only",
                        "--runs",
                        "2",
                        "--evidence-dir",
                        ".artifacts/schema-runtime/EVIDENCE_DIR_MARKER",
                    ],
                    repository_root=repository,
                    schema_root=schema_root,
                    runtime_runner=runner,
                )

                self.assertEqual(exit_code, expected_exit_code)
                self.assertEqual(stderr, "")
                self.assertEqual(
                    json.loads(stdout),
                    {
                        "reasonCode": reason_code,
                        "workflowOutcome": status,
                    },
                )
                self.assertEqual(
                    set(json.loads(stdout)),
                    {"reasonCode", "workflowOutcome"},
                )
                for marker in self._CONSOLE_MARKERS:
                    self.assertNotIn(marker, stdout + stderr)
                summary = verify_runtime.validate_ci_runtime_artifact(
                    repository / ".artifacts" / "schema-runtime-ci"
                )
                self.assertEqual(summary["workflowOutcome"], status)
                self.assertEqual(summary["reasonCode"], reason_code)

    def test_ci_only_value_error_uses_fixed_stderr_without_exception_text(self) -> None:
        """Break caught: a caught ValueError is interpolated into retained Actions stderr."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()

        def failing_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            raise ValueError(
                "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER PROJECT_MARKER"
            )

        exit_code, stdout, stderr = self._capture_main(
            verify_runtime,
            [
                "verify",
                "--ci-only",
                "--runs",
                "2",
                "--evidence-dir",
                ".artifacts/schema-runtime",
            ],
            repository_root=repository,
            schema_root=schema_root,
            runtime_runner=failing_runner,
        )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(stderr, "runtime verifier input error\n")
        for marker in self._CONSOLE_MARKERS:
            self.assertNotIn(marker, stdout + stderr)

    def test_ci_only_runtime_runner_exception_fails_closed(self) -> None:
        """Break caught: an unexpected runner exception escapes into retained Actions logs."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()
        unsafe_pair = self._seed_stale_ci_pair(repository)

        def failing_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            raise RuntimeError(
                "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER "
                "PROJECT_MARKER CONNECTION_MARKER"
            )

        exit_code, stdout, stderr = self._capture_main(
            verify_runtime,
            [
                "verify",
                "--ci-only",
                "--runs",
                "2",
                "--evidence-dir",
                ".artifacts/schema-runtime",
            ],
            repository_root=repository,
            schema_root=schema_root,
            runtime_runner=failing_runner,
        )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(stderr, "runtime verifier input error\n")
        self._assert_closed_exception_console(stdout, stderr)
        self.assertFalse(unsafe_pair.exists())

    def test_ci_only_runtime_controlled_status_exception_fails_closed(self) -> None:
        """Break caught: hostile result/status access emits a traceback or keeps a stale pair."""
        from runtime import verify_runtime

        class ExplodingStatus(dict[str, object]):
            def get(self, key, default=None):
                if key == "status":
                    raise RuntimeError(
                        "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER "
                        "PROJECT_MARKER CONNECTION_MARKER"
                    )
                return super().get(key, default)

        repository, schema_root, _ = self._repository()
        unsafe_pair = self._seed_stale_ci_pair(repository)

        def runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            return ExplodingStatus()

        exit_code, stdout, stderr = self._capture_main(
            verify_runtime,
            [
                "verify",
                "--ci-only",
                "--runs",
                "2",
                "--evidence-dir",
                ".artifacts/schema-runtime",
            ],
            repository_root=repository,
            schema_root=schema_root,
            runtime_runner=runner,
        )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(stderr, "runtime verifier result error\n")
        self._assert_closed_exception_console(stdout, stderr)
        self.assertFalse(unsafe_pair.exists())

    def test_ci_only_failed_and_blocked_export_failure_never_falls_back_to_raw_output(self) -> None:
        """Break caught: a failed safe export leaves FAILED/BLOCKED raw controller JSON in logs."""
        from runtime import verify_runtime

        for status, expected_exit_code in (("FAILED", 4), ("BLOCKED", 5)):
            with self.subTest(status=status):
                repository, schema_root, _ = self._repository()
                result = self._nonpassed_runtime_result(status)

                def runner(_schema_root, _output_directory, *, runs):
                    self.assertEqual(runs, 2)
                    return result

                with mock.patch.object(
                    verify_runtime,
                    "export_ci_runtime_artifact",
                    side_effect=ValueError(
                        "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER PROJECT_MARKER"
                    ),
                ):
                    exit_code, stdout, stderr = self._capture_main(
                        verify_runtime,
                        [
                            "verify",
                            "--ci-only",
                            "--runs",
                            "2",
                            "--evidence-dir",
                            ".artifacts/schema-runtime",
                        ],
                        repository_root=repository,
                        schema_root=schema_root,
                        runtime_runner=runner,
                    )

                self.assertEqual(exit_code, expected_exit_code)
                self.assertEqual(stdout, "")
                self.assertEqual(
                    stderr,
                    "structured runtime CI artifact export failed\n",
                )
                for marker in self._CONSOLE_MARKERS:
                    self.assertNotIn(marker, stdout + stderr)
                self.assertFalse(
                    (repository / ".artifacts" / "schema-runtime-ci").exists()
                )

    def test_ci_only_failed_and_blocked_export_exception_preserves_exit_semantics(self) -> None:
        """Break caught: exporter exceptions traceback or collapse BLOCKED into FAILED."""
        from runtime import verify_runtime

        for status, expected_exit_code in (("FAILED", 4), ("BLOCKED", 5)):
            with self.subTest(status=status):
                repository, schema_root, _ = self._repository()
                unsafe_pair = self._seed_stale_ci_pair(repository)
                result = self._nonpassed_runtime_result(status)

                def runner(_schema_root, _output_directory, *, runs):
                    self.assertEqual(runs, 2)
                    return result

                with mock.patch.object(
                    verify_runtime,
                    "export_ci_runtime_artifact",
                    side_effect=RuntimeError(
                        "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER "
                        "PROJECT_MARKER CONNECTION_MARKER"
                    ),
                ):
                    exit_code, stdout, stderr = self._capture_main(
                        verify_runtime,
                        [
                            "verify",
                            "--ci-only",
                            "--runs",
                            "2",
                            "--evidence-dir",
                            ".artifacts/schema-runtime",
                        ],
                        repository_root=repository,
                        schema_root=schema_root,
                        runtime_runner=runner,
                    )

                self.assertEqual(exit_code, expected_exit_code)
                self.assertEqual(stdout, "")
                self.assertEqual(
                    stderr,
                    "structured runtime CI artifact export failed\n",
                )
                self._assert_closed_exception_console(stdout, stderr)
                self.assertFalse(unsafe_pair.exists())

    def test_ci_only_passed_revalidator_exception_fails_closed(self) -> None:
        """Break caught: PASSED revalidation exceptions leak and retain an unsafe pair."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()

        def passed_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            return self._passed_runtime_result()

        with mock.patch.object(
            verify_runtime,
            "validate_ci_runtime_artifact",
            side_effect=RuntimeError(
                "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER "
                "PROJECT_MARKER CONNECTION_MARKER"
            ),
        ):
            exit_code, stdout, stderr = self._capture_main(
                verify_runtime,
                [
                    "verify",
                    "--ci-only",
                    "--runs",
                    "2",
                    "--evidence-dir",
                    ".artifacts/schema-runtime",
                ],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=passed_runner,
            )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(
            stderr,
            "structured runtime CI artifact validation failed\n",
        )
        self._assert_closed_exception_console(stdout, stderr)
        self.assertFalse(
            (repository / ".artifacts" / "schema-runtime-ci").exists()
        )

    def test_ci_only_closed_console_rendering_exception_fails_closed(self) -> None:
        """Break caught: final allow-listed rendering exceptions retain the artifact or leak."""
        from runtime import verify_runtime

        class ExplodingConsoleSummary(dict[str, object]):
            def __getitem__(self, key):
                if key == "reasonCode":
                    raise RuntimeError(
                        "EXCEPTION_MARKER /private/tmp/EVIDENCE_DIR_MARKER "
                        "PROJECT_MARKER CONNECTION_MARKER"
                    )
                return super().__getitem__(key)

        repository, schema_root, _ = self._repository()
        original_validator = verify_runtime.validate_ci_runtime_artifact

        def passed_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            return self._passed_runtime_result()

        def exploding_validator(output_directory):
            return ExplodingConsoleSummary(original_validator(output_directory))

        with mock.patch.object(
            verify_runtime,
            "validate_ci_runtime_artifact",
            side_effect=exploding_validator,
        ):
            exit_code, stdout, stderr = self._capture_main(
                verify_runtime,
                [
                    "verify",
                    "--ci-only",
                    "--runs",
                    "2",
                    "--evidence-dir",
                    ".artifacts/schema-runtime",
                ],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=passed_runner,
            )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(
            stderr,
            "structured runtime CI artifact console rendering failed\n",
        )
        self._assert_closed_exception_console(stdout, stderr)
        self.assertFalse(
            (repository / ".artifacts" / "schema-runtime-ci").exists()
        )

    def test_default_verify_keeps_detailed_local_value_error_diagnostic(self) -> None:
        """Guardrail: closing CI logs must not remove default developer diagnostics."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()

        def failing_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            raise ValueError("EXCEPTION_MARKER local diagnostic")

        exit_code, stdout, stderr = self._capture_main(
            verify_runtime,
            ["verify", "--runs", "2", "--evidence-dir", ".artifacts/schema-runtime"],
            repository_root=repository,
            schema_root=schema_root,
            runtime_runner=failing_runner,
        )

        self.assertEqual(exit_code, 4)
        self.assertEqual(stdout, "")
        self.assertEqual(
            stderr,
            "runtime verifier input error: EXCEPTION_MARKER local diagnostic\n",
        )

    def test_default_verify_propagates_unexpected_non_value_error(self) -> None:
        """Guardrail: local developer mode retains unexpected exception traceability."""
        from runtime import verify_runtime

        repository, schema_root, _ = self._repository()

        def failing_runner(_schema_root, _output_directory, *, runs):
            self.assertEqual(runs, 2)
            raise RuntimeError("EXCEPTION_MARKER local unexpected diagnostic")

        with self.assertRaisesRegex(RuntimeError, "EXCEPTION_MARKER"):
            self._capture_main(
                verify_runtime,
                ["verify", "--runs", "2", "--evidence-dir", ".artifacts/schema-runtime"],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=failing_runner,
            )

    def test_ci_only_passed_verify_keeps_safe_pair_without_fixed_docs_and_standalone_validation(self) -> None:
        """Break caught: hosted success publishes docs, invalidates its pair, or leaves no uploadable artifact."""
        from runtime import verify_runtime

        repository, schema_root, head = self._repository()

        def passed_runner(_schema_root, output_directory, *, runs):
            self.assertEqual(runs, 2)
            output_directory.mkdir(parents=True, exist_ok=True)
            return self._passed_runtime_result()

        self.assertEqual(
            self._run_main(
                verify_runtime,
                [
                    "verify",
                    "--ci-only",
                    "--runs",
                    "2",
                    "--evidence-dir",
                    ".artifacts/schema-runtime",
                ],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=passed_runner,
            ),
            0,
        )

        safe_directory = repository / ".artifacts" / "schema-runtime-ci"
        safe_targets = (
            safe_directory / "ci-runtime-summary.json",
            safe_directory / "ci-job-summary.md",
        )
        self.assertTrue(all(path.is_file() for path in safe_targets))
        self.assertEqual(
            verify_runtime.validate_ci_runtime_artifact(safe_directory)["workflowOutcome"],
            "PASSED",
        )
        self.assertEqual(
            verify_runtime.validate_ci_runtime_artifact(safe_directory)["gitCommit"],
            head,
        )
        before_validation = tuple(path.read_bytes() for path in safe_targets)

        fixed_targets = verify_runtime.fixed_publication_targets(repository)
        self.assertTrue(all(not path.exists() for path in fixed_targets))
        self.assertEqual(
            self._run_main(
                verify_runtime,
                ["validate-ci-artifact"],
                repository_root=repository,
                schema_root=schema_root,
            ),
            0,
        )
        self.assertEqual(tuple(path.read_bytes() for path in safe_targets), before_validation)
        self.assertEqual(
            subprocess.run(
                ["git", "-C", str(repository), "status", "--porcelain=v1", "--untracked-files=all"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout,
            "",
        )


class WorkflowCiArtifactContractTests(unittest.TestCase):
    def _runtime_steps(self) -> list[dict[str, object]]:
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        return workflow["jobs"]["runtime-postgresql-18"]["steps"]

    def test_every_upload_step_uses_only_the_exact_safe_directory(self) -> None:
        """Break caught: upload-artifact receives a raw tree, glob, parent, or multiline path."""
        steps = self._runtime_steps()
        uploads = [step for step in steps if str(step.get("uses", "")).startswith("actions/upload-artifact@")]
        self.assertEqual(len(uploads), 1)
        for upload in uploads:
            path = upload["with"]["path"]
            self.assertEqual(
                upload["with"],
                {
                    "name": "schema-runtime-postgresql-18-${{ github.run_id }}-${{ github.run_attempt }}",
                    "path": ".artifacts/schema-runtime-ci/",
                    "if-no-files-found": "error",
                    "retention-days": 90,
                    "include-hidden-files": True,
                },
            )
            self.assertEqual(path, ".artifacts/schema-runtime-ci/")
            self.assertNotIn("\n", path)
            self.assertNotIn("*", path)
            self.assertNotIn("..", Path(path).parts)
            self.assertEqual(
                upload.get("if"),
                "${{ always() && steps.runtime_summary.outcome == 'success' }}",
            )
            self.assertEqual(upload["uses"], "actions/upload-artifact@v4")
            self.assertEqual(upload["with"]["if-no-files-found"], "error")
            self.assertEqual(upload["with"]["retention-days"], 90)
            self.assertNotIn("continue-on-error", upload)

    def test_repository_wide_upload_inventory_has_no_hidden_raw_artifact_path(self) -> None:
        """Break caught: another workflow upload step bypasses the reviewed safe path."""
        inventory: list[tuple[str, str, object]] = []
        for workflow_path in sorted((REPOSITORY_ROOT / ".github" / "workflows").glob("*.y*ml")):
            workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
            for job_name, job in workflow.get("jobs", {}).items():
                for step in job.get("steps", []):
                    if str(step.get("uses", "")).startswith("actions/upload-artifact@"):
                        inventory.append((workflow_path.name, job_name, step.get("with", {}).get("path")))
        self.assertEqual(
            inventory,
            [("schema-contract-52-plus-2.yml", "runtime-postgresql-18", ".artifacts/schema-runtime-ci/")],
        )

    def test_summary_fallback_and_upload_preserve_failure_semantics_without_copying_raw_data(self) -> None:
        """Break caught: always-summary parses/copies raw logs or masks runtime/upload failure."""
        steps = self._runtime_steps()
        runtime_step = next(step for step in steps if step.get("id") == "runtime")
        summary_step = next(step for step in steps if str(step.get("name", "")).startswith("Summarize"))
        upload_step = next(step for step in steps if str(step.get("uses", "")).startswith("actions/upload-artifact@"))

        self.assertNotIn("continue-on-error", runtime_step)
        self.assertEqual(summary_step.get("if"), "${{ always() }}")
        self.assertEqual(summary_step.get("id"), "runtime_summary")
        self.assertEqual(
            upload_step.get("if"),
            "${{ always() && steps.runtime_summary.outcome == 'success' }}",
        )
        self.assertNotIn("continue-on-error", summary_step)
        self.assertNotIn("continue-on-error", upload_step)
        summary_script = summary_step["run"]
        self.assertIn("export-ci-fallback", summary_script)
        self.assertIn(".artifacts/schema-runtime-ci/ci-job-summary.md", summary_script)
        self.assertNotIn(".artifacts/schema-runtime/", summary_script)
        self.assertNotIn("cp ", summary_script)
        self.assertNotIn("rsync", summary_script)

    def test_runtime_job_preserves_checkout_permissions_pins_and_existing_coverage(self) -> None:
        """Break caught: the hosted gate omits artifact tests or uses local evidence publication mode."""
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        steps = workflow["jobs"]["runtime-postgresql-18"]["steps"]
        checkout = next(step for step in steps if step.get("uses") == "actions/checkout@v4")
        self.assertEqual(checkout["with"]["fetch-depth"], 0)
        self.assertFalse(checkout["with"]["persist-credentials"])
        uses = [step["uses"] for step in steps if "uses" in step]
        self.assertEqual(
            uses,
            ["actions/checkout@v4", "actions/setup-python@v5", "actions/upload-artifact@v4"],
        )
        runtime_script = next(step["run"] for step in steps if step.get("id") == "runtime")
        commands = (
            "python3 scripts/baseline/verify_baseline.py",
            "python3 generate.py --check",
            "python3 -m unittest discover -s tests -v",
            "python3 scripts/verify_generated_sql.py",
            "python3 -m unittest discover -s runtime/tests -v",
            "python3 runtime/verify_runtime.py validate-promoted-evidence",
            "python3 runtime/verify_runtime.py verify --ci-only --runs 2 --evidence-dir ../../.artifacts/schema-runtime",
        )
        positions = [runtime_script.index(command) for command in commands]
        self.assertEqual(positions, sorted(positions))
        self.assertNotIn("runtime.tests.test_runtime_harness", runtime_script)

    def test_runtime_job_pins_yaml_for_semantic_workflow_contract_tests(self) -> None:
        """Break caught: hosted runtime discovery imports yaml only because the runner happened to preinstall it."""
        requirements_path = PROJECT_ROOT / "requirements-dev.txt"
        requirements = [
            line.strip()
            for line in requirements_path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertIn("PyYAML==6.0.3", requirements)
        self.assertEqual(requirements.count("PyYAML==6.0.3"), 1)


if __name__ == "__main__":
    unittest.main()
