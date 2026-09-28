from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_execution_payment_transport_contract import execution_payment_projection,PATHS,SCHEMAS,CHANGED
from scripts.baseline.r2_contract_negotiation_transport_contract import CHANGED as F06,digest
from scripts.baseline.r2_source_request_transport_contract import source_request_projection
class ExecutionPaymentTransportTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=source_request_projection(yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8')))
 def test_restores_every_previous_f06_pin_without_mutating_the_input(self):
  original=deepcopy(self.document);out=execution_payment_projection(original)
  for name,pin in F06.items():self.assertEqual(pin,digest(out['components']['schemas'][name]),name)
  self.assertEqual(out,execution_payment_projection(out));self.assertEqual(original,self.document)
 def test_drift_cannot_be_hidden_by_projection(self):
  for section,pins in [('paths',PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
   for name in pins:
    with self.subTest(name=name):
     changed=deepcopy(self.document);target=changed['paths'] if section=='paths' else changed['components']['schemas'];target[name]['unreviewed']=True
     with self.assertRaises(ValueError):execution_payment_projection(changed)
