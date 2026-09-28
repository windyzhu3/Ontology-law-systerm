from copy import deepcopy
from pathlib import Path
import unittest,yaml
from scripts.baseline.r2_classification_correction_contract import correction_projection,PATH,SCHEMA
class CorrectionContractTest(unittest.TestCase):
 def test_q1_is_exact_additive_and_idempotent(self):
  original=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'));result=correction_projection(original);self.assertNotIn(PATH,result['paths']);self.assertNotIn(SCHEMA,result['components']['schemas']);self.assertEqual(result,correction_projection(result));self.assertIn(PATH,original['paths'])
  changed=deepcopy(original);changed['components']['schemas'][SCHEMA]['properties']['category']['enum'].append('UNAPPROVED');self.assertRaises(ValueError,correction_projection,changed)
  changed=deepcopy(original);changed['components']['schemas']['ContractContextV1']['properties']['transfer']['properties']['canCorrectClassification']={'type':'string'};self.assertRaises(ValueError,correction_projection,changed)
