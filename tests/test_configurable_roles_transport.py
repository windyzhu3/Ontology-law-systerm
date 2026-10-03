import copy,unittest
from pathlib import Path
import yaml
from scripts.baseline.configurable_roles_transport_contract import configurable_roles_projection,ADDED,PATHS
class ConfigurableRolesTransportTest(unittest.TestCase):
 def setUp(self):self.document=yaml.safe_load((Path(__file__).resolve().parents[1]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_exact_successor_retains_frozen_prior_and_unrelated_fields(self):
  projected=configurable_roles_projection(self.document)
  self.assertTrue(all(k not in projected['paths'] for k in PATHS))
  self.assertTrue(all(k not in projected['components']['schemas'] for k in ADDED))
  self.assertIn('enum',projected['components']['schemas']['IdentityRoleCodeV1'])
  self.document['paths']['/unapproved']={'get':{'summary':'unknown'}}
  self.assertIn('/unapproved',configurable_roles_projection(self.document)['paths'])
 def test_modified_security_cas_code_and_partial_delta_fail_closed(self):
  for fault in ('code','security','partial'):
   doc=copy.deepcopy(self.document)
   if fault=='code':doc['components']['schemas']['IdentityRoleCodeV1']['pattern']='.*'
   elif fault=='security':doc['paths'][PATHS[0]]['get']['security']=[]
   else:del doc['components']['schemas'][ADDED[0]]
   with self.assertRaises(ValueError):configurable_roles_projection(doc)
if __name__=='__main__':unittest.main()
