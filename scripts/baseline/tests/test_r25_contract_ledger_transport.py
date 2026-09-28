"""Approved bounded ledger input and fail-closed historical contract projection."""
import unittest
from copy import deepcopy
from pathlib import Path
import yaml
from scripts.baseline.r2_contracts_transport_contract import contracts_projection

class ContractLedgerTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load((Path(__file__).resolve().parents[3] / "contracts/openapi/ontology-law-api.yaml").read_text(encoding="utf-8"))

    def test_public_query_is_bounded_and_preserves_historical_projection(self):
        operation = self.document["paths"]["/api/v1/contracts"]["get"]
        parameters = {p.get("name"): p for p in operation["parameters"]}
        self.assertEqual({"type": "integer", "minimum": 1, "maximum": 100, "default": 20}, parameters["limit"]["schema"])
        self.assertEqual(200, parameters["search"]["schema"]["maxLength"])
        projected = contracts_projection(self.document)
        self.assertNotIn("/api/v1/contracts", projected["paths"])

    def test_query_and_response_changes_cannot_hide_in_the_amendment(self):
        for fault in ("unbounded", "response", "auth", "unregistered"):
            with self.subTest(fault=fault):
                document = deepcopy(self.document)
                operation = document["paths"]["/api/v1/contracts"]["get"]
                if fault == "unbounded":
                    next(p for p in operation["parameters"] if p.get("name") == "limit")["schema"]["maximum"] = 101
                if fault == "response":
                    document["components"]["schemas"]["ContractLedgerPageV1"]["properties"]["items"].pop("maxItems")
                if fault == "auth": operation["security"] = []
                if fault == "unregistered": operation["parameters"].append({"name": "tenantId", "in": "query", "schema": {"type": "string"}})
                with self.assertRaises(ValueError): contracts_projection(document)
