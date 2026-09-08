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
    def test_valid_v1_2_capability_artifacts_do_not_substitute_for_runtime_evidence(self):
        """Break caught: valid v1.2 static artifacts allow historical runtime evidence through R2."""
        from scripts.baseline import r1_business_closure_contract, verify_baseline
        from scripts.baseline.tests.test_verify_baseline import VerifyBaselineTest

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            VerifyBaselineTest().create_valid_repository(root, runtime_version="v1.1")
            shutil.copytree(
                ROOT / "database/schema-contract-52-plus-2/generated",
                root / "database/schema-contract-52-plus-2/generated", dirs_exist_ok=True,
            )
            self.assertEqual([], r1_business_closure_contract.validate_ingress_query_capability(root))
            structural_findings = []
            gates = verify_baseline.verify_delivery_ledger(root, structural_findings)
            self.assertEqual([], structural_findings)
            self.assertEqual([
                "Gate R2 entry unmet: DB-52P2-PG18-RUNTIME must resolve to "
                "DB-52P2-PG18-RUNTIME-V1-1-V1-2 at pg18-52-plus-2-v1.2; "
                "historical runtime evidence cannot satisfy the current baseline"
            ], gates)

    def test_ingress_query_successor_rejects_missing_changed_or_relabelled_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("docs/adr", "docs/contracts/r1", "docs/baseline", "contracts/openapi", "contracts/events", "database/schema-contract-52-plus-2/generated"):
                shutil.copytree(ROOT / folder, root / folder)
            manifest = root / "database/schema-contract-52-plus-2/generated/schema-contract-manifest.json"
            migration = root / "database/schema-contract-52-plus-2/generated/db/migration/V860__lead_ingress_query_read_capability.sql"
            original_manifest, original_sql = manifest.read_text(encoding="utf-8"), migration.read_text(encoding="utf-8")
            # Every mutation must be caught by the capability validator, independently of other contracts.
            for fault in ("old_version", "wrong_hash", "missing_migration", "unauthorized_grant"):
                with self.subTest(fault=fault):
                    manifest.write_text(original_manifest, encoding="utf-8")
                    migration.write_text(original_sql, encoding="utf-8")
                    if fault == "old_version": manifest.write_text(original_manifest.replace('52-plus-2-v1.2', '52-plus-2-v1.1'), encoding="utf-8")
                    if fault == "wrong_hash": manifest.write_text(original_manifest.replace('a4beeb91ed93be455736eafa3abb829f6a94fed3a263be5996832e458b7c4b39', '0' * 64), encoding="utf-8")
                    if fault == "missing_migration": migration.unlink()
                    if fault == "unauthorized_grant": migration.write_text(original_sql + '\nGRANT SELECT ON lead.lead TO law_app_query;\n', encoding="utf-8")
                    self.assertTrue(any('ingress QUERY capability' in finding for finding in validate(root)), fault)

    def test_current_contract_is_consistent(self):
        self.assertEqual([], validate(ROOT))

    def mutation(self, file, old, new):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("docs/adr", "docs/contracts/r1", "docs/baseline", "contracts/openapi", "contracts/events", "database/schema-contract-52-plus-2/generated"):
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

    def test_recovery_type_query_ref_is_exact(self):
        self.mutation(API, "schema: { $ref: '#/components/schemas/RecoveryTypeV1' }", "schema: { type: string }")

    def test_cursor_constraints_are_scoped(self):
        self.mutation(API, "schema: { type: string, minLength: 1, maxLength: 2048 }", "schema: { type: string, minLength: 0, maxLength: 2048 }")

    def test_due_candidate_exact_properties_are_scoped(self):
        self.mutation(API, "required: [recoveryType, taskId, expectedTaskRevision, waitReceiptId, waitReceiptHash, dueCutoff, idempotencyKey]", "required: [recoveryType, taskId, expectedTaskRevision, waitReceiptId, waitReceiptHash, dueCutoff]")

    def test_consume_exact_properties_are_scoped(self):
        self.mutation(API, "required: [domainEventOutboxId, domainEventId, expectedOutboxRevision, leaseOwner, fencingToken]", "required: [domainEventOutboxId, domainEventId, expectedOutboxRevision, leaseOwner]")

    def test_due_page_must_be_closed_and_require_candidates(self):
        self.mutation(API, "DueR1TaskPageV1:\n      type: object\n      additionalProperties: false", "DueR1TaskPageV1:\n      type: object\n      additionalProperties: true")

    def test_candidate_property_refs_are_exact(self):
        self.mutation(API, "waitReceiptHash: { $ref: '#/components/schemas/Digest32' }", "waitReceiptHash: { $ref: '#/components/schemas/Uuid' }")

    def test_internal_problem_enums_are_exact(self):
        self.mutation(API, "enum: ['NO', FIRST_PAGE, AFTER_REAUTH, BACKOFF]", "enum: ['NO', FIRST_PAGE, BACKOFF]")

    def test_internal_problem_type_and_exact_properties_are_required(self):
        self.mutation(API, "InternalProblem:\n      type: object", "InternalProblem:\n      type: string")
        self.mutation(API, "correlationId: { $ref: '#/components/schemas/Uuid' }", "correlationId: { $ref: '#/components/schemas/Uuid' }\n        leakedTenant: { type: string }")

    def test_internal_problem_scalar_schemas_are_exact(self):
        self.mutation(API, "type: { type: string, format: uri }", "type: { type: string }")
        self.mutation(API, "title: { type: string }", "title: { type: integer }")
        self.mutation(API, "status: { type: integer, enum: [400, 401, 403, 404, 409, 422, 429, 500, 503] }", "status: { type: string, enum: [400, 401, 403, 404, 409, 422, 429, 500, 503] }")

    def test_internal_problem_correlation_ref_is_exact(self):
        self.mutation(API, "correlationId: { $ref: '#/components/schemas/Uuid' }", "correlationId: { type: string }")

    def test_internal_operation_response_refs_are_exact(self):
        self.mutation(API, "'409': { $ref: '#/components/responses/InternalConflictProblem' }", "'409': { $ref: '#/components/responses/InternalBadRequestProblem' }")

    def test_due_operation_parameter_refs_are_exact(self):
        self.mutation(API, "- $ref: '#/components/parameters/DueCursorQuery'", "- $ref: '#/components/parameters/DueLimitQuery'")

    def test_consume_operation_body_ref_is_exact(self):
        self.mutation(API, "schema: { $ref: '#/components/schemas/ConsumeR1ProjectionV1' }", "schema: { $ref: '#/components/schemas/DueR1TaskPageV1' }")

    def test_internal_operation_error_allowlist_is_scoped(self):
        self.mutation(API, "x-error-codes: [VALIDATION_FAILED, UNAUTHENTICATED, NOT_AUTHORIZED, NOT_FOUND, STALE_OUTBOX_CLAIM, PROJECTION_EVENT_INVALID, RATE_LIMITED, INTERNAL_ERROR, SERVICE_UNAVAILABLE]", "x-error-codes: [VALIDATION_FAILED, UNAUTHENTICATED, NOT_AUTHORIZED, NOT_FOUND, RATE_LIMITED, INTERNAL_ERROR, SERVICE_UNAVAILABLE]")

    def test_duplicate_yaml_keys_are_rejected(self):
        self.mutation(API, "DueLimitQuery:\n", "DueLimitQuery:\n      name: shadow\n")

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
        self.mutation("docs/baseline/CURRENT-MVP-BASELINE.md", "Baseline ID: MVP-2026-09-08.3", "Baseline ID: MVP-2026-09-06.2")


if __name__ == "__main__":
    unittest.main()
