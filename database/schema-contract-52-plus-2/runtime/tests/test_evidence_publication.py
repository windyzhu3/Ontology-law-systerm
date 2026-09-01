"""Fail-closed contract tests for publishable PostgreSQL runtime evidence."""

from __future__ import annotations

import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_DIGEST = "sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280"
FLYWAY_DIGEST = "sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93"
CONTRACT_DIGEST = "a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a"
FIELD_DIGEST = "be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed"


def _stage(name: str, exit_code: int = 0) -> dict[str, object]:
    return {"name": name, "exitCode": exit_code}


RUN_A_STAGES = [
    _stage("postgres-start"),
    _stage("flyway-start"),
    _stage("flyway-wait"),
    _stage("flyway-status"),
    _stage("verifier-start"),
    _stage("verifier-wait"),
    _stage("verifier-status"),
    _stage("verifier-logs"),
    _stage("noop-migrate"),
    _stage("noop-verifier-start"),
    _stage("noop-verifier-wait"),
    _stage("noop-verifier-status"),
    _stage("noop-verifier-logs"),
    _stage("compose-down"),
]
RUN_B_STAGES = [stage for stage in RUN_A_STAGES if not stage["name"].startswith("noop-")]


FAILURE_DEFINITIONS = (
    (
        "missing-role",
        "configured application database role does not exist",
        "V830-migrate",
        ["postgres-start", "V830-migrate", "compose-down"],
    ),
    (
        "extra-managed-table",
        "expected 52 application tables",
        "V840-migrate",
        ["postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"],
    ),
    (
        "forbidden-delete-grant",
        "forbidden DELETE or TRUNCATE",
        "V840-migrate",
        ["postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"],
    ),
    (
        "missing-mutation-guard",
        "mutation guard coverage mismatch",
        "V840-migrate",
        ["postgres-start", "V830-migrate", "mutation", "V840-migrate", "compose-down"],
    ),
    (
        "checksum-mismatch",
        "checksum mismatch",
        "strict-validate",
        ["strict-validate"],
    ),
)


def _failure_scenario(
    name: str,
    message: str,
    phase: str,
    stage_names: list[str],
) -> dict[str, object]:
    failure_code = 17
    stages = [
        _stage(stage_name, failure_code if stage_name == phase else 0)
        for stage_name in stage_names
    ]
    return {
        "name": name,
        "expectedMessage": message,
        "actualMessage": message,
        "expectedPhase": phase,
        "actualPhase": phase,
        "expectedResult": "failure",
        "actualResult": "failure",
        "expectedReturnCode": "nonzero",
        "returnCode": failure_code,
        "stages": stages,
    }


def complete_summary(commit: str) -> dict[str, object]:
    fingerprint = "0123456789abcdef0123456789abcdef"
    return {
        "schemaVersion": "postgresql-runtime-evidence-v1",
        "status": "PASSED",
        "gitCommit": commit,
        "verifiedAtUtc": "2026-08-28T16:30:00Z",
        "images": [
            {
                "image": "postgres",
                "lockedTag": "18",
                "lockedDigest": POSTGRES_DIGEST,
                "actualRepoDigest": f"docker.io/library/postgres@{POSTGRES_DIGEST}",
            },
            {
                "image": "redgate/flyway",
                "lockedTag": "13.4.0",
                "lockedDigest": FLYWAY_DIGEST,
                "actualRepoDigest": f"docker.io/redgate/flyway@{FLYWAY_DIGEST}",
            },
        ],
        "postgresVersion": (
            "PostgreSQL 18.0 (Debian 18.0-1.pgdg13+3) on x86_64-pc-linux-gnu, "
            "compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit"
        ),
        "flywayVersion": "Flyway Community Edition 13.4.0 by Redgate",
        "runs": [
            {
                "id": "run-01",
                "fingerprint": fingerprint,
                "stages": copy.deepcopy(RUN_A_STAGES),
                "noopMigrate": {"exitCode": 0, "fingerprint": fingerprint},
            },
            {
                "id": "run-02",
                "fingerprint": fingerprint,
                "stages": copy.deepcopy(RUN_B_STAGES),
                "noopMigrate": None,
            },
        ],
        "failureScenarios": [
            _failure_scenario(name, message, phase, stages)
            for name, message, phase, stages in FAILURE_DEFINITIONS
        ],
        "contractSummary": {
            "contractVersion": "52-plus-2-v1",
            "migrationCount": 19,
            "managedTableCount": 54,
            "managedSchemaCount": 13,
            "physicalForeignKeyCount": 206,
            "mutationGuardCount": 53,
            "contractSha256": CONTRACT_DIGEST,
            "fieldContractSha256": FIELD_DIGEST,
        },
    }


class EvidencePublicationTests(unittest.TestCase):
    def _git(self, repository: Path, *arguments: str) -> str:
        completed = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=True,
            capture_output=True,
            text=True,
        )
        return completed.stdout.strip()

    def _repository(self, *, old_pair: tuple[bytes, bytes] | None = None) -> tuple[Path, Path, Path, str]:
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        repository = Path(temporary_directory.name)
        schema_root = repository / "database" / "schema-contract-52-plus-2"
        runtime_directory = schema_root / "runtime"
        generated_directory = schema_root / "generated"
        publish_directory = repository / "docs" / "evidence" / "schema-runtime"
        artifact_directory = repository / ".artifacts" / "schema-runtime"
        runtime_directory.mkdir(parents=True)
        generated_directory.mkdir(parents=True)
        publish_directory.mkdir(parents=True)
        artifact_directory.mkdir(parents=True)
        (runtime_directory / "sql").mkdir()
        (repository / ".gitignore").write_text("/.artifacts/\n", encoding="utf-8")
        (publish_directory / "README.md").write_text("No successful evidence yet.\n", encoding="utf-8")
        (runtime_directory / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        (runtime_directory / "sql" / "probe.sql").write_text("SELECT 1;\n", encoding="utf-8")
        lock_path = runtime_directory / "toolchain.lock.json"
        lock_path.write_text(
            json.dumps(
                {
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
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        manifest_path = generated_directory / "schema-contract-manifest.json"
        manifest_path.write_text(
            json.dumps(
                {
                    "contractVersion": "52-plus-2-v1",
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
        if old_pair is not None:
            (publish_directory / "2026-09-01-postgresql-18-v1-summary.json").write_bytes(old_pair[0])
            (publish_directory / "2026-09-01-postgresql-18-v1-report.md").write_bytes(old_pair[1])
        self._git(repository, "init", "-q")
        self._git(repository, "config", "user.name", "Runtime Evidence Test")
        self._git(repository, "config", "user.email", "runtime-evidence@example.invalid")
        self._git(repository, "add", ".")
        self._git(repository, "commit", "-qm", "test fixture")
        head = self._git(repository, "rev-parse", "HEAD")
        raw_path = artifact_directory / "runtime-summary.json"
        raw_path.write_text(json.dumps(complete_summary(head), indent=2) + "\n", encoding="utf-8")
        return repository, schema_root, raw_path, head

    def _prepare(self, repository: Path, schema_root: Path, raw_path: Path, head: str):
        from runtime import verify_runtime

        return verify_runtime.prepare_publishable_evidence(
            repository,
            raw_path,
            schema_root / "runtime" / "toolchain.lock.json",
            schema_root / "generated" / "schema-contract-manifest.json",
            expected_head=head,
        )

    def _run_main(self, verify_runtime, arguments: list[str], **kwargs) -> int:
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            return verify_runtime.main(arguments, **kwargs)

    def test_complete_evidence_is_normalized_scanned_and_published_as_a_fixed_pair(self) -> None:
        """Break caught: complete live facts lose a required field or raw logs leak into committed evidence."""
        from runtime import verify_runtime

        repository, schema_root, raw_path, head = self._repository()
        prepared = self._prepare(repository, schema_root, raw_path, head)
        targets = verify_runtime.publish_prepared_evidence(prepared)

        self.assertEqual(
            [path.name for path in targets],
            ["2026-09-01-postgresql-18-v1-summary.json", "2026-09-01-postgresql-18-v1-report.md"],
        )
        published = json.loads(targets[0].read_text(encoding="utf-8"))
        self.assertEqual(published, complete_summary(head))
        self.assertEqual(list(published), sorted(published))
        report = targets[1].read_text(encoding="utf-8")
        for literal in (
            head,
            "2026-08-28T16:30:00Z",
            "PostgreSQL 18.0",
            "Flyway Community Edition 13.4.0 by Redgate",
            "run-01",
            "run-02",
            "19",
            "54",
            "13",
            "206",
            "53",
            CONTRACT_DIGEST,
            FIELD_DIGEST,
        ):
            self.assertIn(literal, report)
        self.assertNotIn(".artifacts", report)
        self.assertNotIn("stderr", report)
        self.assertEqual(self._git(repository, "status", "--short"), "?? docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md\n?? docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-summary.json")

    def test_top_level_schema_is_closed_and_product_claims_are_rejected(self) -> None:
        """Break caught: incomplete evidence, raw-log metadata, or API/R1 claims become publishable."""
        from runtime import verify_runtime

        mutations = (
            lambda summary: summary.pop("contractSummary"),
            lambda summary: summary.update({"rawLogs": ["run-01.log"]}),
            lambda summary: summary.update({"apiValidated": True}),
            lambda summary: summary.update({"status": "FAILED"}),
        )
        for mutate in mutations:
            repository, schema_root, raw_path, head = self._repository()
            summary = complete_summary(head)
            mutate(summary)
            raw_path.write_text(json.dumps(summary), encoding="utf-8")
            with self.subTest(summary=summary):
                with self.assertRaises(ValueError):
                    self._prepare(repository, schema_root, raw_path, head)

        for claim in ("API passed", "SPA passed", "R1 passed", "授权业务逻辑已验证"):
            with self.subTest(claim=claim):
                with self.assertRaises(ValueError):
                    verify_runtime.scan_publishable_evidence("{}", claim)

    def test_timestamp_versions_and_actual_repo_digests_are_exact(self) -> None:
        """Break caught: partial banners, non-UTC time, or a digest detached from its locked image passes."""
        from runtime import verify_runtime

        mutations = (
            lambda summary: summary.update({"verifiedAtUtc": "2026-08-28T16:30:00.123Z"}),
            lambda summary: summary.update({"verifiedAtUtc": "2026-08-28T16:30:00+00:00"}),
            lambda summary: summary.update({"postgresVersion": "PostgreSQL 18.0"}),
            lambda summary: summary.update({"postgresVersion": summary["postgresVersion"].replace("PostgreSQL 18.0", "PostgreSQL 18")}),
            lambda summary: summary.update({"postgresVersion": summary["postgresVersion"].replace("PostgreSQL 18", "PostgreSQL 17")}),
            lambda summary: summary.update({"flywayVersion": "Flyway 13.4.0"}),
            lambda summary: summary.update({"flywayVersion": "Flyway Teams Edition 13.4.0 by Redgate"}),
            lambda summary: summary["images"][0].update({"lockedTag": "latest"}),
            lambda summary: summary["images"][0].update({"actualRepoDigest": f"docker.io/library/postgres@{FLYWAY_DIGEST}"}),
            lambda summary: summary["images"][0].update({"actualRepoDigest": f"evil.example/library/postgres@{POSTGRES_DIGEST}"}),
            lambda summary: summary["images"][1].update({"actualRepoDigest": f"evil.example/redgate/flyway@{FLYWAY_DIGEST}"}),
            lambda summary: summary["images"].reverse(),
        )
        for mutate in mutations:
            repository, schema_root, raw_path, head = self._repository()
            summary = complete_summary(head)
            mutate(summary)
            raw_path.write_text(json.dumps(summary), encoding="utf-8")
            with self.subTest(mutation=mutate):
                with self.assertRaises(ValueError):
                    self._prepare(repository, schema_root, raw_path, head)

        repository, schema_root, raw_path, head = self._repository()
        summary = complete_summary(head)
        summary["flywayVersion"] = "Flyway OSS Edition 13.4.0 by Redgate"
        raw_path.write_text(json.dumps(summary), encoding="utf-8")
        self._prepare(repository, schema_root, raw_path, head)

        lock = json.loads((schema_root / "runtime" / "toolchain.lock.json").read_text(encoding="utf-8"))
        manifest = json.loads((schema_root / "generated" / "schema-contract-manifest.json").read_text(encoding="utf-8"))
        for index, unlocked_tag in ((0, "18-alpine"), (1, "13.4.0-alpine")):
            unlocked_summary = complete_summary(head)
            unlocked_lock = copy.deepcopy(lock)
            unlocked_summary["images"][index]["lockedTag"] = unlocked_tag
            unlocked_lock["images"][index]["tag"] = unlocked_tag
            with self.subTest(index=index, unlocked_tag=unlocked_tag):
                with self.assertRaises(ValueError):
                    verify_runtime._validate_publishable_summary(
                        unlocked_summary,
                        unlocked_lock,
                        manifest,
                        head,
                    )

    def test_runs_counts_fingerprints_and_noop_contract_fail_closed(self) -> None:
        """Break caught: duplicate runs, reordered/failed stages, wrong counts, or unstable no-op passes."""
        mutations = (
            lambda summary: summary["runs"][1].update({"id": "run-01"}),
            lambda summary: summary["runs"].reverse(),
            lambda summary: summary["runs"][0]["stages"].reverse(),
            lambda summary: summary["runs"][0]["stages"].append(_stage("unknown-stage")),
            lambda summary: summary["runs"][0]["stages"][0].update({"exitCode": 1}),
            lambda summary: summary["runs"][1].update({"fingerprint": "f" * 32}),
            lambda summary: summary["runs"][0]["noopMigrate"].update({"exitCode": 1}),
            lambda summary: summary["runs"][0]["noopMigrate"].update({"fingerprint": "e" * 32}),
            lambda summary: summary["contractSummary"].update({"migrationCount": 18}),
            lambda summary: summary["contractSummary"].update({"managedTableCount": 53}),
            lambda summary: summary["contractSummary"].update({"managedSchemaCount": 12}),
            lambda summary: summary["contractSummary"].update({"physicalForeignKeyCount": 205}),
            lambda summary: summary["contractSummary"].update({"mutationGuardCount": 52}),
            lambda summary: summary["contractSummary"].update({"contractSha256": "0" * 64}),
            lambda summary: summary["contractSummary"].update({"fieldContractSha256": "0" * 64}),
        )
        for mutate in mutations:
            repository, schema_root, raw_path, head = self._repository()
            summary = complete_summary(head)
            mutate(summary)
            raw_path.write_text(json.dumps(summary), encoding="utf-8")
            with self.subTest(mutation=mutate):
                with self.assertRaises(ValueError):
                    self._prepare(repository, schema_root, raw_path, head)

    def test_all_five_failure_scenarios_require_exact_order_and_results(self) -> None:
        """Break caught: reversed, duplicate, unknown, missing, or internally inconsistent negative proof passes."""
        mutations = (
            lambda scenarios: scenarios.reverse(),
            lambda scenarios: scenarios.append(copy.deepcopy(scenarios[-1])),
            lambda scenarios: scenarios[0].update({"name": "unknown"}),
            lambda scenarios: scenarios.pop(),
            lambda scenarios: scenarios[0].update({"expectedMessage": "different"}),
            lambda scenarios: scenarios[0].update({"actualMessage": "different"}),
            lambda scenarios: scenarios[0].update({"actualPhase": "V840-migrate"}),
            lambda scenarios: scenarios[0].update({"expectedResult": "success"}),
            lambda scenarios: scenarios[0].update({"actualResult": "success"}),
            lambda scenarios: scenarios[0].update({"expectedReturnCode": "zero"}),
            lambda scenarios: scenarios[0].update({"returnCode": 0}),
            lambda scenarios: scenarios[0]["stages"].reverse(),
            lambda scenarios: scenarios[0]["stages"].append(_stage("unknown")),
            lambda scenarios: scenarios[0]["stages"][1].update({"exitCode": 0}),
        )
        for mutate in mutations:
            repository, schema_root, raw_path, head = self._repository()
            summary = complete_summary(head)
            mutate(summary["failureScenarios"])
            raw_path.write_text(json.dumps(summary), encoding="utf-8")
            with self.subTest(mutation=mutate):
                with self.assertRaises(ValueError):
                    self._prepare(repository, schema_root, raw_path, head)

    def test_secret_scan_covers_sources_and_render_equivalent_visible_text(self) -> None:
        """Break caught: Markdown/HTML obfuscation, credentials, connection strings, or temp paths evade scanning."""
        from runtime import verify_runtime

        unsafe = (
            "password=visible",
            "PASSWORD=visible",
            "POSTGRES_PASSWORD=visible",
            "RUNTIME_POSTGRES_PASSWORD=visible",
            "PGPASSWORD: visible",
            "CUSTOM_ACCESS_TOKEN=visible",
            "jdbc:postgresql://user:visible@db/name",
            "postgresql://user:visible@db/name",
            "token-value",
            "SECRET=value",
            "/tmp/schema-runtime-value/report.json",
            "<!-- token-value -->",
            "to&#107;en-value",
            "to[ke](https://example.invalid/path)n-value",
            r"password\=visible",
            "pass<!--join-->word=visible",
            "to<span></span>ken=visible",
            "A<!--join-->PI passed",
            'to<span title=">"></span>ken=visible',
            'A<span title=">"></span>PI passed',
        )
        for value in unsafe:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    verify_runtime.scan_publishable_evidence("{}", value)

        visible = verify_runtime.markdown_visible_text(
            "safe [label](https://example.invalid/path-segment) text"
        )
        self.assertEqual(visible, "safe label text")
        self.assertNotIn("example.invalid", visible)
        self.assertEqual(
            verify_runtime.markdown_visible_text(
                'A&amp;B<!--zero-width--><span title=">">C&#68;</span>E '
                '[label](https://example.invalid/hidden-destination)'
            ),
            "A&BCDE label",
        )

    def test_symlink_loop_evidence_path_raises_structured_io_error(self) -> None:
        """Break caught: Path.resolve RuntimeError escapes the path API boundary."""
        from runtime import verify_runtime

        repository, _, _, _ = self._repository()
        evidence_root = repository / ".artifacts" / "schema-runtime"
        loop_a = evidence_root / "loop-a"
        loop_b = evidence_root / "loop-b"
        loop_a.symlink_to(loop_b)
        loop_b.symlink_to(loop_a)
        requested = ".artifacts/schema-runtime/loop-a/output"

        try:
            verify_runtime.evidence_dir(repository, requested)
        except verify_runtime.EvidenceIOError:
            pass
        except RuntimeError as error:
            self.fail(f"raw RuntimeError escaped the path boundary: {error}")
        else:
            self.fail("symlink-loop evidence path was accepted")

    def test_symlink_loop_evidence_path_returns_controlled_cli_failure(self) -> None:
        """Break caught: Path.resolve RuntimeError escapes the CLI boundary."""
        from runtime import verify_runtime

        repository, schema_root, _, _ = self._repository()
        evidence_root = repository / ".artifacts" / "schema-runtime"
        loop_a = evidence_root / "loop-a"
        loop_b = evidence_root / "loop-b"
        loop_a.symlink_to(loop_b)
        loop_b.symlink_to(loop_a)
        requested = ".artifacts/schema-runtime/loop-a/output"

        runner_called = False

        def forbidden_runner(_schema_root, _output_directory, *, runs):
            nonlocal runner_called
            del _schema_root, _output_directory, runs
            runner_called = True
            raise AssertionError("runtime runner must not be called for an invalid path")

        try:
            exit_code = self._run_main(
                verify_runtime,
                ["verify", "--runs", "2", "--evidence-dir", requested],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=forbidden_runner,
            )
        except RuntimeError as error:
            self.fail(f"raw RuntimeError escaped the CLI boundary: {error}")
        self.assertEqual(exit_code, 4)
        self.assertFalse(runner_called)

    def test_git_snapshot_rejects_tracked_untracked_head_and_input_drift_but_allows_ignored_artifacts(self) -> None:
        """Break caught: evidence is published from a different source tree or changed raw input."""
        from runtime import verify_runtime

        repository, schema_root, raw_path, head = self._repository()
        prepared = self._prepare(repository, schema_root, raw_path, head)
        (repository / ".artifacts" / "unrelated.log").write_text("ignored\n", encoding="utf-8")
        verify_runtime.publish_prepared_evidence(prepared)

        for drift in ("tracked", "untracked", "head", "input"):
            repository, schema_root, raw_path, head = self._repository()
            prepared = self._prepare(repository, schema_root, raw_path, head)
            if drift == "tracked":
                (repository / "docs" / "evidence" / "schema-runtime" / "README.md").write_text("changed\n", encoding="utf-8")
            elif drift == "untracked":
                (repository / "untracked.txt").write_text("changed\n", encoding="utf-8")
            elif drift == "head":
                self._git(repository, "commit", "--allow-empty", "-qm", "head drift")
            else:
                raw_path.write_text(json.dumps({"status": "FAILED"}), encoding="utf-8")
            with self.subTest(drift=drift):
                with self.assertRaises(ValueError):
                    verify_runtime.publish_prepared_evidence(prepared)

    def test_git_snapshot_rejects_index_flags_and_hidden_tracked_byte_drift(self) -> None:
        """Break caught: assume-unchanged or skip-worktree hides changed lock, Compose, or SQL bytes."""
        from runtime import verify_runtime

        cases = (
            ("--assume-unchanged", "database/schema-contract-52-plus-2/runtime/toolchain.lock.json"),
            ("--skip-worktree", "database/schema-contract-52-plus-2/runtime/compose.yaml"),
            ("--assume-unchanged", "database/schema-contract-52-plus-2/runtime/sql/probe.sql"),
        )
        for flag, relative_path in cases:
            repository, _, _, _ = self._repository()
            tracked_path = repository / relative_path
            self._git(repository, "update-index", flag, relative_path)
            tracked_path.write_bytes(tracked_path.read_bytes() + b"hidden drift\n")
            self.assertEqual(self._git(repository, "status", "--porcelain=v1", "--untracked-files=all"), "")
            with self.subTest(flag=flag, relative_path=relative_path):
                with self.assertRaises(ValueError):
                    verify_runtime.capture_repository_snapshot(repository)

    def test_fixed_targets_reject_symlinks_nonregular_files_and_repository_escape(self) -> None:
        """Break caught: a fixed target is redirected or replaced by a non-regular filesystem object."""
        from runtime import verify_runtime

        repository, _, _, _ = self._repository()
        publish_directory = repository / "docs" / "evidence" / "schema-runtime"
        summary_target = publish_directory / "2026-09-01-postgresql-18-v1-summary.json"
        summary_target.symlink_to(repository / ".gitignore")
        with self.assertRaises(ValueError):
            verify_runtime.fixed_publication_targets(repository)

        repository, _, _, _ = self._repository()
        publish_directory = repository / "docs" / "evidence" / "schema-runtime"
        summary_target = publish_directory / "2026-09-01-postgresql-18-v1-summary.json"
        os.mkfifo(summary_target)
        with self.assertRaises(ValueError):
            verify_runtime.fixed_publication_targets(repository)

        repository, _, _, _ = self._repository()
        publish_directory = repository / "docs" / "evidence" / "schema-runtime"
        outside = repository.parent / f"{repository.name}-outside"
        outside.mkdir()
        self.addCleanup(lambda: outside.rmdir() if outside.exists() else None)
        (publish_directory / "README.md").unlink()
        publish_directory.rmdir()
        publish_directory.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            verify_runtime.fixed_publication_targets(repository)

    def test_pair_replace_failure_restores_absence_or_old_bytes(self) -> None:
        """Break caught: second-target replacement leaves a new/old mixed evidence pair."""
        from runtime import verify_runtime

        old_pairs = (None, (b"old-summary\n", b"old-report\n"))
        for old_pair in old_pairs:
            repository, schema_root, raw_path, head = self._repository(old_pair=old_pair)
            prepared = self._prepare(repository, schema_root, raw_path, head)
            targets = (
                repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-summary.json",
                repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-report.md",
            )
            real_replace = os.replace
            failed = False

            def fail_second_replace(source, destination):
                nonlocal failed
                if Path(destination) == targets[1] and not failed:
                    failed = True
                    raise OSError("injected second replace failure")
                return real_replace(source, destination)

            with self.subTest(old_pair=old_pair):
                with mock.patch.object(verify_runtime.os, "replace", side_effect=fail_second_replace):
                    with self.assertRaises(ValueError):
                        verify_runtime.publish_prepared_evidence(prepared)
                if old_pair is None:
                    self.assertFalse(targets[0].exists())
                    self.assertFalse(targets[1].exists())
                else:
                    self.assertEqual(targets[0].read_bytes(), old_pair[0])
                    self.assertEqual(targets[1].read_bytes(), old_pair[1])

    def test_pair_cleanup_failure_restores_absence_or_old_bytes(self) -> None:
        """Break caught: cleanup I/O failure commits a pair whose transaction did not finish."""
        from runtime import verify_runtime

        old_pairs = (None, (b"old-summary\n", b"old-report\n"))
        for old_pair in old_pairs:
            repository, schema_root, raw_path, head = self._repository(old_pair=old_pair)
            prepared = self._prepare(repository, schema_root, raw_path, head)
            targets = (
                repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-summary.json",
                repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-report.md",
            )
            real_cleanup = verify_runtime._unlink_transaction_file
            failed = False

            def fail_first_cleanup(path: Path) -> None:
                nonlocal failed
                if ".backup-" in path.name and not failed:
                    failed = True
                    raise OSError("injected cleanup failure")
                real_cleanup(path)

            with self.subTest(old_pair=old_pair):
                with mock.patch.object(verify_runtime, "_unlink_transaction_file", side_effect=fail_first_cleanup):
                    with self.assertRaises(ValueError):
                        verify_runtime.publish_prepared_evidence(prepared)
                if old_pair is None:
                    self.assertFalse(targets[0].exists())
                    self.assertFalse(targets[1].exists())
                else:
                    self.assertEqual(targets[0].read_bytes(), old_pair[0])
                    self.assertEqual(targets[1].read_bytes(), old_pair[1])

    def test_cli_publishes_only_a_complete_passed_result_from_one_stable_clean_head(self) -> None:
        """Break caught: BLOCKED/FAILED/incomplete or source-drifting verification publishes success evidence."""
        from runtime import verify_runtime

        for status, expected_exit in (("BLOCKED", 5), ("FAILED", 4)):
            repository, schema_root, _, head = self._repository()

            def nonpassing_runner(_schema_root, output_directory, *, runs):
                del _schema_root, runs
                output_directory.mkdir(parents=True, exist_ok=True)
                result = {"status": status, "reason": "test"}
                (output_directory / "runtime-summary.json").write_text(json.dumps(result), encoding="utf-8")
                return result

            exit_code = self._run_main(
                verify_runtime,
                ["verify", "--runs", "2", "--evidence-dir", ".artifacts/schema-runtime"],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=nonpassing_runner,
            )
            self.assertEqual(exit_code, expected_exit)
            self.assertFalse((repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-summary.json").exists())
            self.assertEqual(head, self._git(repository, "rev-parse", "HEAD"))

        repository, schema_root, _, head = self._repository()

        def complete_runner(_schema_root, output_directory, *, runs):
            del _schema_root, runs
            output_directory.mkdir(parents=True, exist_ok=True)
            result = complete_summary(head)
            (output_directory / "runtime-summary.json").write_text(json.dumps(result), encoding="utf-8")
            return result

        self.assertEqual(
            self._run_main(
                verify_runtime,
                ["verify", "--runs", "2", "--evidence-dir", ".artifacts/schema-runtime"],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=complete_runner,
            ),
            0,
        )
        fixed_targets = verify_runtime.fixed_publication_targets(repository)
        self.assertTrue(all(path.is_file() for path in fixed_targets))
        self.assertEqual(
            json.loads(fixed_targets[0].read_text(encoding="utf-8"))["status"],
            "PASSED",
        )

        repository, schema_root, _, head = self._repository()

        def drifting_runner(_schema_root, output_directory, *, runs):
            del _schema_root, runs
            output_directory.mkdir(parents=True, exist_ok=True)
            result = complete_summary(head)
            (output_directory / "runtime-summary.json").write_text(json.dumps(result), encoding="utf-8")
            (repository / "tracked-drift.txt").write_text("drift\n", encoding="utf-8")
            return result

        self.assertEqual(
            self._run_main(
                verify_runtime,
                ["verify", "--runs", "2", "--evidence-dir", ".artifacts/schema-runtime"],
                repository_root=repository,
                schema_root=schema_root,
                runtime_runner=drifting_runner,
            ),
            4,
        )
        self.assertFalse((repository / "docs" / "evidence" / "schema-runtime" / "2026-09-01-postgresql-18-v1-summary.json").exists())


if __name__ == "__main__":
    unittest.main()
