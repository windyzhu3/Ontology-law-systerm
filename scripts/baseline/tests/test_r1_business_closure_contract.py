from __future__ import annotations

import importlib
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COMMAND = "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md"
ADR = "docs/adr/ADR-0008-r1-business-closure-alignment.md"
API = "contracts/openapi/ontology-law-api.yaml"
HTTP = "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md"


def validate(root: Path) -> list[str]:
    module = importlib.util.find_spec("scripts.baseline.r1_business_closure_contract")
    if module is None:
        return ["R1 business closure validator is missing"]
    return importlib.import_module(module.name).validate(root)


class R1BusinessClosureContractTest(unittest.TestCase):
    def test_current_contract_is_consistent(self):
        self.assertEqual([], validate(ROOT))

    def mutation(self, file, old, new):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("docs/adr", "docs/contracts/r1", "docs/baseline", "contracts/openapi", "contracts/events"):
                shutil.copytree(ROOT / folder, root / folder)
            path = root / file
            self.assertTrue(path.is_file(), f"missing active artifact: {file}")
            text = path.read_text(encoding="utf-8")
            self.assertIn(old, text)
            path.write_text(text.replace(old, new, 1), encoding="utf-8")
            self.assertTrue(validate(root), f"mutation escaped: {old}")

    def test_service_capture_row_is_required(self):
        self.mutation(COMMAND, "| CAPTURE_LEAD | SERVICE_ACTOR | SERVICE | SYSTEM | SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |\n", "")

    def test_service_direct_is_rejected(self):
        self.mutation(COMMAND, "| SERVICE_ACTOR | SERVICE | SYSTEM | SOURCE_INTAKE_OWNER |", "| SERVICE_ACTOR | SERVICE | DIRECT | SOURCE_INTAKE_OWNER |")

    def test_obsolete_human_only_capture_prose_is_rejected(self):
        self.mutation(COMMAND, "The registry key is the composite `(CommandType, PrincipalKind)`.", "CAPTURE_LEAD 只接受 HUMAN。")

    def test_http_operations_table_requires_due_row(self):
        self.mutation(HTTP, "| listDueR1Tasks | GET | /internal/v1/tasks/due |", "| wrongDueOperation | GET | /internal/v1/tasks/due |")

    def test_http_security_table_requires_all_four_internal_operations(self):
        self.mutation(HTTP, "listDueR1Tasks,consumeR1Projection,reopenDueContactTasks,reopenDueRoutingReviewTasks", "reopenDueContactTasks,reopenDueRoutingReviewTasks")

    def test_capture_envelope_mismatch_is_rejected(self):
        self.mutation(COMMAND, "| CAPTURE_LEAD | SERVICE_ACTOR |", "| CAPTURE_LEAD | INTERNAL_ADMIN |")

    def test_duplicate_composite_policy_is_rejected(self):
        row = "| CAPTURE_LEAD | SERVICE_ACTOR | SERVICE | SYSTEM | SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |"
        self.mutation(COMMAND, row, row + "\n" + row)

    def test_both_internal_operations_are_required(self):
        for operation in ("listDueR1Tasks", "consumeR1Projection"):
            with self.subTest(operation=operation):
                self.mutation(API, "operationId: " + operation, "operationId: wrongOperation")

    def test_public_error_allowlist_cannot_expand(self):
        self.mutation(API, "      x-error-codes:\n", "      x-error-codes:\n        - STALE_OUTBOX_CLAIM\n")

    def test_due_limit_bounds_are_scoped_to_due_parameter(self):
        self.mutation(API, "schema: { type: integer, minimum: 1, maximum: 100, default: 50 }", "schema: { type: integer, minimum: 1, maximum: 101, default: 50 }")

    def test_due_candidate_exact_properties_are_scoped(self):
        self.mutation(API, "required: [recoveryType, taskId, expectedTaskRevision, waitReceiptId, waitReceiptHash, dueCutoff, idempotencyKey]", "required: [recoveryType, taskId, expectedTaskRevision, waitReceiptId, waitReceiptHash, dueCutoff]")

    def test_consume_exact_properties_are_scoped(self):
        self.mutation(API, "required: [domainEventOutboxId, domainEventId, expectedOutboxRevision, leaseOwner, fencingToken]", "required: [domainEventOutboxId, domainEventId, expectedOutboxRevision, leaseOwner]")

    def test_projection_cannot_be_mutable(self):
        self.mutation(ADR, "| ProjectionStorage | NONE |", "| ProjectionStorage | MATERIALIZED |")

    def test_read_must_follow_audit_commit(self):
        self.mutation(ADR, "| DisclosureReturn | AFTER_AUDIT_COMMIT |", "| DisclosureReturn | BEFORE_AUDIT |")

    def test_304_cannot_bypass_audit(self):
        self.mutation(ADR, "| SensitiveResponseModes | BODY,CACHE_REVALIDATED |", "| SensitiveResponseModes | BODY |")

    def test_max_attempts_cannot_drift(self):
        self.mutation(ADR, "| MaxAttempts | 8 |", "| MaxAttempts | 9 |")

    def test_capacity_profile_is_required(self):
        self.mutation(ADR, "| CapacityProfile | R1-CAPACITY-V1 |\n", "")

    def test_baseline_mismatch_is_rejected(self):
        self.mutation("docs/baseline/CURRENT-MVP-BASELINE.md", "Baseline ID: MVP-2026-09-05.3", "Baseline ID: MVP-2026-09-05.2")


if __name__ == "__main__":
    unittest.main()
