from __future__ import annotations

import importlib
import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
COMMAND = "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md"
ADR = "docs/adr/ADR-0008-r1-business-closure-alignment.md"
API = "contracts/openapi/ontology-law-api.yaml"
HTTP = "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md"


def validate(root: Path) -> list[str]:
    module = importlib.util.find_spec("scripts.baseline.r1_business_closure_contract")
    if module is None:
        return ["R1 business closure validator is missing"]
    return importlib.import_module(module.name).validate(root, allow_r2_schema=True)


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
            # This historical evidence test intentionally stays at the exact v1.2 stage.
            from scripts.baseline.r2_schema_successor_contract import historical_projection, MIGRATION
            generated = root / "database/schema-contract-52-plus-2/generated"
            manifest_path = generated / "schema-contract-manifest.json"
            import json
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest_path.write_text(json.dumps(historical_projection(generated, manifest), ensure_ascii=False), encoding="utf-8")
            projected = historical_projection(generated, manifest)
            for artifact in set(manifest['generatedArtifactSha256']) - set(projected['generatedArtifactSha256']):
                (generated / artifact).unlink()
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
                    if fault == "old_version": manifest.write_text(original_manifest.replace('52-plus-2-r2-v1', '52-plus-2-v1.1'), encoding="utf-8")
                    if fault == "wrong_hash":
                        import json
                        original_hash = json.loads(original_manifest)['contractSha256']
                        manifest.write_text(original_manifest.replace(original_hash, '0' * 64), encoding="utf-8")
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

    def api_mutation(self, keys, change, expected_finding):
        """Mutate the named contract node, independent of YAML presentation."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("docs/adr", "docs/contracts/r1", "docs/baseline", "contracts/openapi", "contracts/events", "database/schema-contract-52-plus-2/generated"):
                shutil.copytree(ROOT / folder, root / folder)
            path = root / API
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            target = document
            for key in keys:
                target = target[key]
            change(target)
            path.write_text(yaml.safe_dump(document, sort_keys=False, allow_unicode=True), encoding="utf-8")
            # A different validator failure must not hide an escaped mutation.
            self.assertIn(expected_finding, validate(root))

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
        self.api_mutation(("components", "parameters", "DueLimitQuery", "schema"), lambda node: node.update(maximum=101), "R1 DueLimitQuery differs from exact contract")

    def test_recovery_type_query_ref_is_exact(self):
        self.api_mutation(("components", "parameters", "RecoveryTypeQuery"), lambda node: node.update(schema={"type": "string"}), "R1 RecoveryTypeQuery differs from exact contract")

    def test_cursor_constraints_are_scoped(self):
        self.api_mutation(("components", "parameters", "DueCursorQuery", "schema"), lambda node: node.update(minLength=0), "R1 DueCursorQuery differs from exact contract")

    def test_due_candidate_exact_properties_are_scoped(self):
        self.api_mutation(("components", "schemas", "DueR1TaskCandidateV1", "required"), lambda node: node.remove("idempotencyKey"), "R1 DueR1TaskCandidateV1 differs from exact scoped contract")

    def test_consume_exact_properties_are_scoped(self):
        self.api_mutation(("components", "schemas", "ConsumeR1ProjectionV1", "required"), lambda node: node.remove("fencingToken"), "R1 ConsumeR1ProjectionV1 differs from exact scoped contract")

    def test_due_page_must_be_closed_and_require_candidates(self):
        self.api_mutation(("components", "schemas", "DueR1TaskPageV1"), lambda node: node.update(additionalProperties=True), "R1 DueR1TaskPageV1 differs from exact scoped contract")

    def test_candidate_property_refs_are_exact(self):
        self.api_mutation(("components", "schemas", "DueR1TaskCandidateV1", "properties"), lambda node: node.update(waitReceiptHash={"$ref": "#/components/schemas/Uuid"}), "R1 DueR1TaskCandidateV1 differs from exact scoped contract")

    def test_internal_problem_enums_are_exact(self):
        self.api_mutation(("components", "schemas", "InternalProblem", "properties", "retryPolicy", "enum"), lambda node: node.remove("AFTER_REAUTH"), "R1 InternalProblem differs from exact frozen schema")

    def test_internal_problem_type_and_exact_properties_are_required(self):
        self.api_mutation(("components", "schemas", "InternalProblem"), lambda node: node.update(type="string"), "R1 InternalProblem differs from exact frozen schema")
        self.api_mutation(("components", "schemas", "InternalProblem", "properties"), lambda node: node.update(leakedTenant={"type": "string"}), "R1 InternalProblem differs from exact frozen schema")

    def test_internal_problem_scalar_schemas_are_exact(self):
        for field, change in (("type", lambda node: node.pop("format")), ("title", lambda node: node.update(type="integer")), ("status", lambda node: node.update(type="string"))):
            with self.subTest(field=field):
                self.api_mutation(("components", "schemas", "InternalProblem", "properties", field), change, "R1 InternalProblem differs from exact frozen schema")

    def test_internal_problem_correlation_ref_is_exact(self):
        self.api_mutation(("components", "schemas", "InternalProblem", "properties"), lambda node: node.update(correlationId={"type": "string"}), "R1 InternalProblem differs from exact frozen schema")

    def test_internal_operation_response_refs_are_exact(self):
        self.api_mutation(("paths", "/internal/v1/projections/r1/consume", "post", "responses"), lambda node: node.update({"409": {"$ref": "#/components/responses/InternalBadRequestProblem"}}), "R1 internal operation response set differs: consumeR1Projection")

    def test_due_operation_parameter_refs_are_exact(self):
        self.api_mutation(("paths", "/internal/v1/tasks/due", "get", "parameters", 2), lambda node: node.update({"$ref": "#/components/parameters/DueLimitQuery"}), "R1 listDueR1Tasks parameter/body contract differs")

    def test_consume_operation_body_ref_is_exact(self):
        self.api_mutation(("paths", "/internal/v1/projections/r1/consume", "post", "requestBody", "content", "application/json", "schema"), lambda node: node.update({"$ref": "#/components/schemas/DueR1TaskPageV1"}), "R1 consumeR1Projection request contract differs")

    def test_internal_operation_error_allowlist_is_scoped(self):
        self.api_mutation(("paths", "/internal/v1/projections/r1/consume", "post", "x-error-codes"), lambda node: node.remove("STALE_OUTBOX_CLAIM"), "R1 internal operation error allowlist differs: consumeR1Projection")

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
