import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline.r2_capture_names_contract import capture_names_projection
from scripts.baseline.task9_identity_contract import validate_document

class CaptureNamesContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load((Path(__file__).resolve().parents[3] / 'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))

    def test_current_and_original_transport_pass(self):
        self.assertIn('customerName', self.document['components']['schemas']['CaptureLeadV1']['properties'])
        self.assertEqual([], validate_document(self.document))
        original = capture_names_projection(self.document)
        self.assertIn('capturedName', original['components']['schemas']['CaptureLeadV1']['properties'])
        self.assertEqual([], validate_document(original))

    def test_partial_required_widened_and_legacy_changes_are_rejected(self):
        for kind in ['partial','required','widened','legacy']:
            with self.subTest(kind=kind):
                document=copy.deepcopy(self.document)
                schema=document['components']['schemas']['CaptureLeadV1']
                if kind=='partial': schema['properties'].pop('contactName')
                if kind=='required': schema['required'].append('customerName')
                if kind=='widened': schema['properties']['contactName']={'type':'string'}
                if kind=='legacy': schema['properties']['capturedName']={'type':'string'}
                self.assertTrue(validate_document(document))
