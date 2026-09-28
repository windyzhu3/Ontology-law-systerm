import unittest,yaml
from pathlib import Path
from copy import deepcopy
from scripts.baseline.r2_management_transport_contract import management_projection,PATHS,SCHEMAS
class ManagementContract(unittest.TestCase):
 def test_exact_projection_is_idempotent_and_rejects_drift(self):
  d=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'));prior=management_projection(d);self.assertEqual(prior,management_projection(prior))
  for name in PATHS:
   changed=deepcopy(d);changed['paths'][name]['get']['security']=[]
   with self.assertRaises(ValueError):management_projection(changed)
  changed=deepcopy(d);changed['components']['schemas']['SessionContextV1']['allOf'][3]['then']['properties']['canReadBusinessManagement']={'const':True}
  with self.assertRaises(ValueError):management_projection(changed)
if __name__=='__main__':unittest.main()
