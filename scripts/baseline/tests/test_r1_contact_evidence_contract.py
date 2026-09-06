from __future__ import annotations

import importlib.util
import importlib
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
COMMAND = "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md"
TASK = "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
HTTP = "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md"
WORKBENCH = "docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md"
BASELINE = "docs/baseline/CURRENT-MVP-BASELINE.md"
ADR = "docs/adr/ADR-0011-r1-contact-reopen-evidence-read.md"
SPEC = "docs/superpowers/specs/2026-09-06-r1-contact-reopen-evidence-read-design.md"
DESIGN_INDEX = "docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md"
PLAN_INDEX = "docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md"
LEDGER = "docs/progress/MVP-DELIVERY-LEDGER.md"
PROGRESS = "docs/progress/2026-09-06-r1-local-progress.md"
ACTIVE_FILES = (
    COMMAND,
    TASK,
    HTTP,
    WORKBENCH,
    BASELINE,
    ADR,
    SPEC,
    DESIGN_INDEX,
    PLAN_INDEX,
    LEDGER,
    PROGRESS,
)


def validate(root: Path) -> list[str]:
    name = "scripts.baseline.r1_contact_evidence_contract"
    if importlib.util.find_spec(name) is None:
        return ["R1 contact/evidence validator missing"]
    return importlib.import_module(name).validate(root)


class R1ContactEvidenceContractTest(unittest.TestCase):
    def copy_active_contract(self, root: Path) -> None:
        for relative in ACTIVE_FILES:
            source = ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)

    def assert_mutation_fails(
        self,
        relative: str,
        old: str,
        new: str,
        expected_fragment: str,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_active_contract(root)
            self.assertEqual([], validate(root), "the copied active contract must be valid")
            path = root / relative
            text = path.read_text(encoding="utf-8")
            self.assertIn(old, text, f"mutation source missing: {relative}: {old}")
            path.write_text(text.replace(old, new, 1), encoding="utf-8")
            findings = validate(root)
            self.assertTrue(
                any(expected_fragment in finding for finding in findings),
                f"mutation escaped: {relative}: {old} -> {new}; findings={findings}",
            )

    def test_current_contract_has_complete_approved_amendment(self):
        """Break caught: the approved amendment is absent or incomplete."""
        self.assertEqual([], validate(ROOT))

    def test_each_registry_value_is_enforced_independently(self):
        """Break caught: a profile value can drift while the registry still passes."""
        mutations = (
            ("contactNo>=3", "contactNo=3"),
            ("LEAD_GLOBAL_MONOTONIC", "RESET_ON_REVIEW"),
            ("MAX_INITIAL_CONTACT_NO_3", "THREE_PER_REVIEW_CYCLE"),
            ("NEW_OPEN_TASK", "REOPEN_TERMINAL_TASK"),
            ("CURRENT_LEAD_EXACT_REVISION", "SAME_LEAD_ID_ONLY"),
            ("ACTIVE_NOT_REVOKED", "BINDING_OPTIONAL"),
            ("TASK,LEAD,SUBMISSION,BINDING", "TASK,LEAD"),
            ("DIRECT,DELEGATED", "DIRECT,DELEGATED,OBJECT"),
            ("QUERY_ONLY", "COMMAND_FALLBACK"),
            ("AUDIT_BEFORE_200_AND_304", "AUDIT_ONLY_200"),
            ("R1_BUSINESS_TENANT_LOCK", "IDENTITY_LOCK_ONLY"),
            ("evidence_submission,evidence_binding", "evidence.*"),
        )
        for old, new in mutations:
            with self.subTest(old=old, new=new), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_active_contract(root)
                self.assertEqual([], validate(root))
                path = root / COMMAND
                text = path.read_text(encoding="utf-8")
                start = text.index("## R1 contact ordinal registry")
                prefix, registries = text[:start], text[start:]
                self.assertIn(old, registries)
                path.write_text(prefix + registries.replace(old, new, 1), encoding="utf-8")
                self.assertTrue(any("registry" in finding for finding in validate(root)))

    def test_registry_parser_rejects_structural_bypasses(self):
        """Break caught: malformed, duplicate, unknown, or historical tables are accepted."""
        ordinal_row = "| R1_CONTACT_ORDINAL_V1 | ordinal | LEAD_GLOBAL_MONOTONIC |"
        unknown_row = "| R1_CONTACT_ORDINAL_V1 | hiddenReset | RESET_ON_REVIEW |"
        cases = (
            ("duplicate-row", ordinal_row, f"{ordinal_row}\n{ordinal_row}"),
            ("unknown-row", ordinal_row, f"{ordinal_row}\n{unknown_row}"),
            ("missing-row", ordinal_row, ""),
            ("changed-header", "| Profile | Key | Value |", "| Profile | Key | Value | Notes |"),
            (
                "code-block-only",
                "## R1 contact ordinal registry",
                "## Historical note\n\n```markdown\n## R1 contact ordinal registry",
            ),
        )
        for name, old, new in cases:
            with self.subTest(case=name):
                self.assert_mutation_fails(COMMAND, old, new, "registry")

    def test_required_artifacts_and_versions_are_enforced(self):
        """Break caught: missing authority or a stale active version is accepted."""
        cases = (
            (ADR, None, None, "ADR-0011"),
            (BASELINE, "Baseline ID: MVP-2026-09-06.3", "Baseline ID: MVP-2026-09-06.2", "baseline"),
            (TASK, "Contract ID: R1-TASK-COMPLETION-V1.2", "Contract ID: R1-TASK-COMPLETION-V1.1", "Task contract"),
            (SPEC, "状态：APPROVED", "状态：DRAFT", "approved specification"),
        )
        for relative, old, new, expected in cases:
            with self.subTest(relative=relative):
                if old is not None:
                    self.assert_mutation_fails(relative, old, new, expected)
                    continue
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    self.copy_active_contract(root)
                    self.assertEqual([], validate(root))
                    (root / relative).unlink()
                    self.assertTrue(any(expected in finding for finding in validate(root)))

    def test_cross_document_security_and_scope_regressions_are_rejected(self):
        """Break caught: the documents activate a broader or incomplete runtime contract."""
        cases = (
            (TASK, "第3次及以后不得自动创建CONTACT重试", "第4次仍自动创建CONTACT重试", "automatic retry"),
            (HTTP, "Task、Lead、Submission、Binding四个准确Subject的DENY", "Task、Lead两个准确Subject的DENY", "four-subject DENY"),
            (COMMAND, "`lead→evidence`、`api→evidence`、`evidence→identity`", "`lead→evidence`、`api→evidence`、`evidence→identity`、`evidence→lead`", "dependency DAG"),
            (WORKBENCH, "不得返回文件内容、文件名、对象位置或下载URL", "可返回文件名和下载URL", "content or locator"),
            (BASELINE, "`52-plus-2-v1.2`", "`52-plus-2-v1.3`", "physical capability"),
            (LEDGER, "| R1-CONTACT-EVIDENCE-CONTRACT | R1 | Contact ordinal and Evidence reference contract | Docs |", "| R1-CONTACT-EVIDENCE-CONTRACT | R1 | Contact ordinal and Evidence reference contract | Backend |", "contract-only delivery"),
            (PROGRESS, "原Task 6仍未完成", "原Task 6已经IMPLEMENTED并RUNTIME_VERIFIED", "Task 6 remains incomplete"),
            (COMMAND, "不得以`contactNo<=3`限制事件合法性", "仅`contactNo<=3`事件合法", "event ordinal"),
        )
        for relative, old, new, expected in cases:
            with self.subTest(relative=relative, old=old):
                self.assert_mutation_fails(relative, old, new, expected)

    def test_integrated_repository_verifier_calls_the_new_contract(self):
        """Break caught: the standalone validator is not wired into verify_repository."""
        from scripts.baseline.tests.test_verify_baseline import VerifyBaselineTest
        from scripts.baseline.verify_baseline import verify_repository

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            VerifyBaselineTest().create_valid_repository(root)
            self.assertEqual([], verify_repository(root))
            command = root / COMMAND
            command.write_text(
                command.read_text(encoding="utf-8").replace(
                    "| R1_CONTACT_EVIDENCE_REF_V1 | capability | QUERY_ONLY |",
                    "| R1_CONTACT_EVIDENCE_REF_V1 | capability | COMMAND_FALLBACK |",
                    1,
                ),
                encoding="utf-8",
            )
            self.assertTrue(
                any("contact/evidence" in finding for finding in verify_repository(root))
            )


if __name__ == "__main__":
    unittest.main()
