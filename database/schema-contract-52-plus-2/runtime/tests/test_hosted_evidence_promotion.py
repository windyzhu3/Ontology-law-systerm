"""Behavior tests for promoting one closed hosted runtime artifact."""

from __future__ import annotations

from dataclasses import replace
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from runtime.tests.test_ci_artifact import _passed_summary


class HostedEvidencePromotionTests(unittest.TestCase):
    def _git(self, repository: Path, *arguments: str) -> str:
        completed = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=True,
            capture_output=True,
            text=True,
        )
        return completed.stdout.strip()

    def _fixture(self, *, test_merge_mode: str = "valid"):
        from runtime import verify_runtime

        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        repository = Path(temporary_directory.name)
        schema = repository / "database/schema-contract-52-plus-2"
        migration_directory = schema / "generated/db/migration"
        evidence_directory = repository / "docs/evidence/schema-runtime"
        migration_directory.mkdir(parents=True)
        evidence_directory.mkdir(parents=True)
        field_contract = b"fixture field contract\n"
        field_digest = hashlib.sha256(field_contract).hexdigest()
        contract_digest = "c" * 64
        (repository / ".gitignore").write_text("/source-artifact.zip\n", encoding="utf-8")
        (schema / "generated/field-contract.md").write_bytes(field_contract)
        (schema / "generated/schema-contract-manifest.json").write_text(
            json.dumps(
                {
                    "contractVersion": "52-plus-2-v1",
                    "contractSha256": contract_digest,
                    "fieldContractSha256": field_digest,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        (migration_directory / "V001__fixture.sql").write_text("SELECT 1;\n", encoding="utf-8")
        (evidence_directory / "README.md").write_text("# Evidence\n", encoding="utf-8")
        self._git(repository, "init", "-q")
        self._git(repository, "config", "user.name", "Promotion Test")
        self._git(repository, "config", "user.email", "promotion@example.invalid")
        self._git(repository, "add", ".")
        self._git(repository, "commit", "-qm", "base")
        base = self._git(repository, "rev-parse", "HEAD")
        (repository / "README.md").write_text("fixture\n", encoding="utf-8")
        self._git(repository, "add", "README.md")
        self._git(repository, "commit", "-qm", "head")
        head = self._git(repository, "rev-parse", "HEAD")

        if test_merge_mode == "valid":
            test_merge = self._git(
                repository,
                "commit-tree",
                f"{head}^{{tree}}",
                "-p",
                base,
                "-p",
                head,
                "-m",
                "hosted test merge",
            )
        elif test_merge_mode == "missing":
            test_merge = "d" * 40
        elif test_merge_mode == "reversed":
            test_merge = self._git(
                repository,
                "commit-tree",
                f"{head}^{{tree}}",
                "-p",
                head,
                "-p",
                base,
                "-m",
                "hosted test merge with reversed parents",
            )
        else:
            self.fail(f"unsupported test merge mode: {test_merge_mode}")
        summary = _passed_summary()
        summary["gitCommit"] = test_merge
        summary["contractSummary"]["contractSha256"] = contract_digest
        summary["contractSummary"]["fieldContractSha256"] = field_digest
        pair_directory = repository / "pair"
        verify_runtime.export_ci_runtime_artifact(summary, pair_directory)
        artifact = repository / "source-artifact.zip"
        with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in ("ci-runtime-summary.json", "ci-job-summary.md"):
                archive.write(pair_directory / name, arcname=name)
        for path in pair_directory.iterdir():
            path.unlink()
        pair_directory.rmdir()
        artifact_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        source = verify_runtime.HostedPromotionSource(
            repository="example/ontology-law",
            pull_request=3,
            workflow_run=33405965491,
            artifact_id=9763252627,
            artifact_zip_sha256=artifact_digest,
            base_commit=base,
            head_commit=head,
            test_merge_commit=test_merge,
        )
        return verify_runtime, repository, artifact, source, summary

    def test_missing_test_merge_object_publishes_nothing(self) -> None:
        """Break caught: claimed merge parentage is accepted without the merge object."""
        verify_runtime, repository, artifact, source, _ = self._fixture(
            test_merge_mode="missing"
        )
        targets = verify_runtime.fixed_publication_targets(repository)

        with self.assertRaises(verify_runtime.EvidenceIOError):
            verify_runtime.prepare_hosted_evidence_promotion(
                repository, artifact, source=source
            )

        self.assertTrue(all(not path.exists() for path in targets))

    def test_wrong_test_merge_parent_order_publishes_nothing(self) -> None:
        """Break caught: asserted test-merge parents are not read from the merge commit."""
        verify_runtime, repository, artifact, source, _ = self._fixture(
            test_merge_mode="reversed"
        )
        targets = verify_runtime.fixed_publication_targets(repository)

        with self.assertRaisesRegex(ValueError, "test merge parents"):
            verify_runtime.prepare_hosted_evidence_promotion(
                repository, artifact, source=source
            )

        self.assertTrue(all(not path.exists() for path in targets))

    def test_closed_zip_is_source_bound_and_published_as_one_canonical_pair(self) -> None:
        """Break caught: promotion copies hosted bytes without schema/source validation or provenance."""
        verify_runtime, repository, artifact, source, hosted_summary = self._fixture()

        prepared = verify_runtime.prepare_hosted_evidence_promotion(
            repository, artifact, source=source
        )
        targets = verify_runtime.publish_hosted_evidence_promotion(prepared)

        self.assertEqual(
            [path.name for path in targets],
            [
                "2026-09-01-postgresql-18-v1-summary.json",
                "2026-09-01-postgresql-18-v1-report.md",
            ],
        )
        promoted = json.loads(targets[0].read_text(encoding="utf-8"))
        self.assertEqual(promoted["schemaVersion"], "postgresql-runtime-evidence-promotion-v2")
        self.assertEqual(promoted["status"], "RUNTIME_VERIFIED")
        self.assertEqual(promoted["hostedArtifact"], hosted_summary)
        self.assertEqual(promoted["source"]["workflowRun"], 33405965491)
        self.assertEqual(promoted["source"]["testMergeParents"], [source.base_commit, source.head_commit])
        self.assertEqual(
            promoted["emptyDatabaseRuns"],
            [
                {
                    "catalogFingerprintPublished": False,
                    "runId": "run-01",
                    "verifierOutputSha256": "a" * 64,
                },
                {
                    "catalogFingerprintPublished": False,
                    "runId": "run-02",
                    "verifierOutputSha256": "a" * 64,
                },
            ],
        )
        self.assertEqual(promoted["localExecution"]["status"], "BLOCKED")
        self.assertEqual(promoted["localExecution"]["exitCode"], 5)
        pinned_source = replace(
            source,
            test_merge_object_sha256=promoted["source"]["testMergeObjectSha256"],
        )
        validated = verify_runtime.validate_promoted_evidence(
            repository, expected_source=pinned_source
        )
        self.assertEqual(validated, promoted)

    def test_durable_validation_survives_absent_historical_test_merge_object(self) -> None:
        """Break caught: later validation requires an ephemeral merge object forever."""
        verify_runtime, repository, artifact, source, _ = self._fixture()
        prepared = verify_runtime.prepare_hosted_evidence_promotion(
            repository, artifact, source=source
        )
        targets = verify_runtime.publish_hosted_evidence_promotion(prepared)
        promoted = json.loads(targets[0].read_text(encoding="utf-8"))
        pinned_source = replace(
            source,
            test_merge_object_sha256=promoted["source"]["testMergeObjectSha256"],
        )
        self._git(repository, "add", "docs/evidence/schema-runtime")
        self._git(repository, "commit", "-qm", "durable hosted evidence")
        self._git(repository, "prune", "--expire=now")
        with self.assertRaises(subprocess.CalledProcessError):
            self._git(repository, "cat-file", "-e", source.test_merge_commit)
        self.assertEqual(self._git(repository, "status", "--short"), "")

        validated = verify_runtime.validate_promoted_evidence(
            repository, expected_source=pinned_source
        )

        self.assertRegex(
            validated["source"]["testMergeObjectSha256"], r"^[0-9a-f]{64}$"
        )

    def test_tampered_durable_merge_attestation_fails_without_historical_object(self) -> None:
        """Break caught: a rewritten attestation passes after its merge object expires."""
        verify_runtime, repository, artifact, source, _ = self._fixture()
        prepared = verify_runtime.prepare_hosted_evidence_promotion(
            repository, artifact, source=source
        )
        targets = verify_runtime.publish_hosted_evidence_promotion(prepared)
        promoted = json.loads(targets[0].read_text(encoding="utf-8"))
        pinned_source = replace(
            source,
            test_merge_object_sha256=promoted["source"]["testMergeObjectSha256"],
        )
        self._git(repository, "add", "docs/evidence/schema-runtime")
        self._git(repository, "commit", "-qm", "durable hosted evidence")
        self._git(repository, "prune", "--expire=now")
        promoted["source"]["testMergeObjectSha256"] = "0" * 64
        targets[0].write_text(
            json.dumps(promoted, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        targets[1].write_text(
            verify_runtime._render_promoted_report(promoted), encoding="utf-8"
        )

        with self.assertRaisesRegex(TypeError, "expected_source"):
            verify_runtime.validate_promoted_evidence(repository)
        with self.assertRaisesRegex(ValueError, "source differs"):
            verify_runtime.validate_promoted_evidence(
                repository, expected_source=pinned_source
            )

    def test_present_merge_object_is_recomputed_against_durable_attestation(self) -> None:
        """Break caught: a present merge object is not compared with stored proof."""
        verify_runtime, repository, artifact, source, _ = self._fixture()
        prepared = verify_runtime.prepare_hosted_evidence_promotion(
            repository, artifact, source=source
        )
        targets = verify_runtime.publish_hosted_evidence_promotion(prepared)
        promoted = json.loads(targets[0].read_text(encoding="utf-8"))
        promoted["source"]["testMergeObjectSha256"] = "0" * 64
        targets[0].write_text(
            json.dumps(promoted, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        targets[1].write_text(
            verify_runtime._render_promoted_report(promoted), encoding="utf-8"
        )

        with self.assertRaisesRegex(ValueError, "object SHA-256"):
            verify_runtime.validate_promoted_evidence(
                repository,
                expected_source=replace(
                    source, test_merge_object_sha256="0" * 64
                ),
            )

    def test_wrong_zip_digest_or_extra_member_publishes_nothing(self) -> None:
        """Break caught: an unreviewed or non-exact download is accepted as the closed artifact."""
        verify_runtime, repository, artifact, source, _ = self._fixture()
        targets = verify_runtime.fixed_publication_targets(repository)

        with self.assertRaisesRegex(ValueError, "ZIP SHA-256"):
            verify_runtime.prepare_hosted_evidence_promotion(
                repository,
                artifact,
                source=replace(source, artifact_zip_sha256="0" * 64),
            )
        self.assertTrue(all(not path.exists() for path in targets))

        with zipfile.ZipFile(artifact, "a") as archive:
            archive.writestr("unexpected.txt", "unsafe\n")
        changed_source = replace(
            source,
            artifact_zip_sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
        )
        with self.assertRaisesRegex(ValueError, "exact safe regular-file pair"):
            verify_runtime.prepare_hosted_evidence_promotion(
                repository, artifact, source=changed_source
            )
        self.assertTrue(all(not path.exists() for path in targets))
