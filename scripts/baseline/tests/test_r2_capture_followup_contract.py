import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline import task9_identity_contract as identity

class CaptureFollowupContractTest(unittest.TestCase):
    def test_exact_followup_reference_and_reject_widening(self):
        doc = yaml.safe_load((Path(__file__).resolve().parents[3] / identity.API).read_text(encoding="utf-8"))
        ref = doc["components"]["schemas"]["NextSummary"]["properties"].get("subjectFactRef")
        self.assertEqual({"type": "string", "pattern": "^[A-Za-z0-9_-]{43}$"}, ref)
        self.assertEqual([], identity.validate_document(copy.deepcopy(doc)))
        for mutation in (lambda d: d["components"]["schemas"]["NextSummary"]["properties"]["subjectFactRef"].update(pattern=".*"), lambda d: d["components"]["schemas"]["NextSummary"]["required"].append("subjectFactRef")):
            changed = copy.deepcopy(doc); mutation(changed)
            self.assertTrue(identity.validate_document(changed))
