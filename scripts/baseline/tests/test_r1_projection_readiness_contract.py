from __future__ import annotations

import copy
import importlib
import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[3]
API = "contracts/openapi/ontology-law-api.yaml"
ADR = "docs/adr/ADR-0012-r1-projection-readiness-protocol.md"
BASELINE = "docs/baseline/CURRENT-MVP-BASELINE.md"
HTTP = "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md"
PATH = "/internal/v1/projections/r1/readiness"
ACTIVE_FILES = (API, ADR, BASELINE, HTTP)


def validate(root: Path) -> list[str]:
    name = "scripts.baseline.r1_projection_readiness_contract"
    if importlib.util.find_spec(name) is None:
        return ["R1 projection readiness validator missing"]
    return importlib.import_module(name).validate(root)


class R1ProjectionReadinessContractTest(unittest.TestCase):
    def copy_valid_repository(self, root: Path) -> None:
        for relative in ACTIVE_FILES:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if (ROOT / relative).exists():
                shutil.copy2(ROOT / relative, destination)
        self.assertEqual([], validate(root), "copied active contract must be accepted")

    def test_current_contract_activates_approved_readiness(self):
        """Break caught: no active readiness protocol or validator is delivered."""
        self.assertEqual([], validate(ROOT))

    def test_parsed_transport_has_sixteen_operations_and_five_mtls(self):
        """Break caught: the executable contract omits the fifth internal operation."""
        document = yaml.safe_load((ROOT / API).read_text(encoding="utf-8"))
        operations = [operation for item in document["paths"].values()
                      for method, operation in item.items()
                      if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}]
        self.assertEqual(16, len(operations))
        self.assertEqual(5, sum(op["security"] == [{"internalMutualTls": []}] for op in operations))
        operation = document["paths"][PATH]["get"]
        self.assertEqual("checkR1ProjectionReadiness", operation["operationId"])
        self.assertEqual("no-store", operation["responses"]["204"]["headers"]["Cache-Control"]["schema"]["const"])
        self.assertNotIn("content", operation["responses"]["204"])

    def assert_api_mutation_rejected(self, mutation, label):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_valid_repository(root)
            document = yaml.safe_load((root / API).read_text(encoding="utf-8"))
            original = copy.deepcopy(document)
            mutation(document)
            self.assertNotEqual(original, document, label)
            (root / API).write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            self.assertTrue(validate(root), label)
            (root / API).write_text(yaml.safe_dump(original, sort_keys=False), encoding="utf-8")
            self.assertEqual([], validate(root), "semantically identical YAML remains valid")

    def test_operation_identity_and_exact_inventory_mutations_are_rejected(self):
        """Break caught: missing, renamed, duplicated, moved or extra operations pass."""
        cases = {
            "removed": lambda d: d["paths"].pop(PATH),
            "renamed": lambda d: d["paths"][PATH]["get"].update(operationId="wrongReadiness"),
            "duplicate-id": lambda d: d["paths"]["/internal/v1/duplicate"].update(get=copy.deepcopy(d["paths"][PATH]["get"])),
            "wrong-method": lambda d: d["paths"][PATH].update(post=d["paths"][PATH].pop("get")),
            "old-version": lambda d: d["info"].update(version="1.1.0"),
            "public-inventory": lambda d: d["paths"]["/api/v1/leads"]["post"].update(security=[{"internalMutualTls": []}]),
        }
        for label, mutate in cases.items():
            with self.subTest(case=label):
                def mutation(document):
                    if label == "duplicate-id":
                        document["paths"]["/internal/v1/duplicate"] = {}
                    mutate(document)
                self.assert_api_mutation_rejected(mutation, label)

    def test_security_and_caller_input_mutations_are_rejected(self):
        """Break caught: caller authority or optional alternative authentication is accepted."""
        changes = (
            {"security": [{"publicBearer": []}]},
            {"security": [{"internalMutualTls": []}, {"publicBearer": []}]},
            {"security": [{"internalMutualTls": [], "publicBearer": []}]},
            {"requestBody": {"required": False, "content": {"application/json": {"schema": {"type": "object"}}}}},
            {"parameters": [{"name": "tenantId", "in": "query", "schema": {"type": "string"}}]},
            {"parameters": [{"name": "X-Tenant-ID", "in": "header", "schema": {"type": "string"}}]},
            {"x-tenant-source": "CALLER"},
        )
        for change in changes:
            with self.subTest(change=change):
                self.assert_api_mutation_rejected(lambda d: d["paths"][PATH]["get"].update(change), str(change))
        self.assert_api_mutation_rejected(
            lambda d: d["paths"][PATH].update(parameters=[{"name": "organizationId", "in": "query"}]),
            "inherited path parameters")

    def test_same_count_public_inventory_drift_is_rejected(self):
        """Break caught: public operation renaming preserves 16/11/5 and bypasses inventory."""
        self.assert_api_mutation_rejected(
            lambda d: d["paths"]["/api/v1/leads"]["post"].update(operationId="otherPublicOperation"),
            "renamed public operation with same counts")

    def test_http_and_active_successor_references_cannot_drift(self):
        """Break caught: the active HTTP row or baseline authority is disconnected."""
        for relative, old, replacement in (
            (HTTP, "| checkR1ProjectionReadiness | GET |", "| checkR1ProjectionReadiness | POST |"),
            (HTTP, "Contract ID: R1-HTTP-V1.2", "Contract ID: R1-HTTP-V1.1"),
            (BASELINE, "ADR-0012-r1-projection-readiness-protocol.md", "missing-readiness.md"),
        ):
            with self.subTest(relative=relative, old=old), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_valid_repository(root)
                path = root / relative
                text = path.read_text(encoding="utf-8")
                self.assertIn(old, text)
                path.write_text(text.replace(old, replacement, 1), encoding="utf-8")
                self.assertTrue(validate(root))

    def test_response_body_cache_and_error_mutations_are_rejected(self):
        """Break caught: reusable success, sensitive output or an unfrozen error passes."""
        cases = {
            "body": lambda o: o["responses"]["204"].update(content={"application/json": {"schema": {"type": "object"}}}),
            "304": lambda o: o["responses"].update({"304": {"description": "cached"}}),
            "etag": lambda o: o["responses"]["204"]["headers"].update(ETag={"schema": {"type": "string"}}),
            "cacheable-success": lambda o: o["responses"]["204"]["headers"]["Cache-Control"]["schema"].update(const="public, max-age=60"),
            "cacheable-error": lambda o: o["responses"]["403"]["headers"]["Cache-Control"]["schema"].update(const="private"),
            "new-error-code": lambda o: o["x-error-codes"].append("READINESS_PROOF_EXPIRED"),
            "new-error-status": lambda o: o["responses"].update({"409": copy.deepcopy(o["responses"]["403"])}),
            "unsafe-error": lambda o: o["responses"]["403"]["content"]["application/problem+json"].update(schema={"type": "object"}),
        }
        for label, mutate in cases.items():
            with self.subTest(case=label):
                self.assert_api_mutation_rejected(lambda d: mutate(d["paths"][PATH]["get"]), label)

    def test_successor_and_bounded_policy_registry_mutations_are_rejected(self):
        """Break caught: active inventory, authorization boundary or Worker invariant drifts."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_valid_repository(root)
            path = root / ADR
            original = path.read_text(encoding="utf-8")
            rows = [line for line in original.splitlines() if line.startswith("| R1_PROJECTION_READINESS_V1 |")]
            self.assertGreaterEqual(len(rows), 25)
            for row in rows:
                for replacement in ("", row.replace(row.split(" | ")[-2], "INVALID", 1), row + "\n" + row):
                    with self.subTest(row=row, replacement=replacement):
                        path.write_text(original.replace(row, replacement, 1), encoding="utf-8")
                        self.assertTrue(validate(root))
            path.write_text("<!--\n" + original + "\n-->", encoding="utf-8")
            self.assertTrue(validate(root), "inactive registry must not satisfy the gate")
            path.write_text(original, encoding="utf-8")
            baseline = root / BASELINE
            baseline.write_text(baseline.read_text(encoding="utf-8").replace("Baseline ID: MVP-2026-09-07.1", "Baseline ID: MVP-2026-09-06.3"), encoding="utf-8")
            self.assertTrue(validate(root), "old baseline is not an alternative accepted version")

    def test_missing_successor_artifacts_and_duplicate_yaml_are_rejected(self):
        """Break caught: missing active authority or a shadowed duplicate YAML key passes."""
        for relative in ACTIVE_FILES:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.copy_valid_repository(root)
                (root / relative).unlink()
                self.assertTrue(validate(root))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.copy_valid_repository(root)
            path = root / API
            text = path.read_text(encoding="utf-8")
            path.write_text(text.replace("operationId: checkR1ProjectionReadiness", "operationId: wrongReadiness\n      operationId: checkR1ProjectionReadiness"), encoding="utf-8")
            self.assertTrue(validate(root))

    def test_malformed_yaml_shapes_report_findings_instead_of_crashing(self):
        """Break caught: malformed contracts bypass validation by raising exceptions."""
        for label, mutation in (
            ("null-path", lambda d: d["paths"].update({PATH: None})),
            ("null-operation", lambda d: d["paths"][PATH].update(get=None)),
            ("null-responses", lambda d: d["paths"][PATH]["get"].update(responses=None)),
            ("null-security", lambda d: d["paths"][PATH]["get"].update(security=None)),
            ("null-info", lambda d: d.update(info=None)),
            ("null-components", lambda d: d.update(components=None)),
        ):
            with self.subTest(case=label):
                self.assert_api_mutation_rejected(mutation, label)

    def test_total_verifier_rejects_missing_readiness(self):
        """Break caught: CLI's repository verifier omits the new protocol gate."""
        from scripts.baseline.tests.test_verify_baseline import VerifyBaselineTest
        from scripts.baseline.verify_baseline import verify_repository

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            VerifyBaselineTest().create_valid_repository(root)
            self.assertEqual([], verify_repository(root))
            path = root / API
            self.assertTrue(path.is_file(), "successor fixture needs executable OpenAPI")
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            self.assertIn(PATH, document.get("paths", {}), "successor fixture must contain readiness")
            document["paths"].pop(PATH)
            path.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
            self.assertTrue(any("readiness" in finding for finding in verify_repository(root)))

    def test_total_verifier_and_cli_report_malformed_readiness_shapes(self):
        """Break caught: the earlier closure inventory crashes before readiness can reject a null path."""
        from scripts.baseline.tests.test_verify_baseline import VerifyBaselineTest
        from scripts.baseline.verify_baseline import verify_repository

        fixture = VerifyBaselineTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture.create_valid_repository(root)
            # Include the actual predecessor validator's authority and linked
            # artifacts, so this exercises the real order of both contract gates.
            shutil.copytree(ROOT / "docs/adr", root / "docs/adr", dirs_exist_ok=True)
            shutil.copy2(ROOT / HTTP, root / HTTP)
            generated = "database/schema-contract-52-plus-2/generated"
            shutil.copytree(ROOT / generated, root / generated, dirs_exist_ok=True)
            self.assertEqual([], verify_repository(root))
            path = root / API
            original = yaml.safe_load(path.read_text(encoding="utf-8"))
            for case in ("path", "operation", "responses", "security"):
                with self.subTest(case=case):
                    document = copy.deepcopy(original)
                    if case == "path":
                        document["paths"][PATH] = None
                    elif case == "operation":
                        document["paths"][PATH]["get"] = None
                    else:
                        document["paths"][PATH]["get"][case] = None
                    path.write_text(yaml.safe_dump(document, sort_keys=False), encoding="utf-8")
                    try:
                        findings = verify_repository(root)
                    except Exception as error:
                        self.fail(f"malformed readiness {case} must report findings, not {type(error).__name__}: {error}")
                    self.assertTrue(any("readiness" in finding for finding in findings), findings)
                    result = fixture.run_cli(root)
                    self.assertEqual(1, result.returncode)
                    self.assertEqual("", result.stderr)
                    self.assertEqual(findings, result.stdout.strip().splitlines())


if __name__ == "__main__":
    unittest.main()
