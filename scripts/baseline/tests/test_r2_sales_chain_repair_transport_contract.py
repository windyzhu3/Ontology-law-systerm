from copy import deepcopy
from scripts.baseline.r2_transfer_transport_contract import transfer_projection
from scripts.baseline.r2_business_authorities_transport_contract import business_authorities_projection
from pathlib import Path
import unittest,yaml
from scripts.baseline.r2_sales_chain_repair_transport_contract import sales_chain_repair_projection,PATHS,SCHEMAS

class SalesChainRepairTransportTest(unittest.TestCase):
 def load(self):return yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_repair_has_only_two_explicit_mtls_commands_and_no_public_receipt_link(self):
  d=self.load()
  self.assertEqual(2,len(PATHS))
  for path in PATHS:
   p=d['paths'][path]['post'];self.assertEqual([{'internalMutualTls':[]}],p['security'])
   self.assertEqual([{'$ref':'#/components/parameters/IdempotencyKey'}],p['parameters'])
   self.assertNotIn('headers',p['responses']['200'])
  for name in ('RestoreSourceRequestTaskV1','RepairSupersededOpportunityTaskV1'):
   schema=d['components']['schemas'][name];self.assertFalse(schema['additionalProperties']);self.assertEqual(set(schema['properties']),set(schema['required']))
   self.assertNotIn('tenantId',schema['properties']);self.assertNotIn('authority',schema['properties'])
 def test_additions_project_losslessly_and_unreviewed_changes_fail_closed(self):
  d=self.load();expected=business_authorities_projection(transfer_projection(d))
  for k in PATHS:expected['paths'].pop(k)
  for k in SCHEMAS:expected['components']['schemas'].pop(k)
  self.assertEqual(expected,sales_chain_repair_projection(d));self.assertEqual(expected,sales_chain_repair_projection(expected))
  for section,pins in [('paths',PATHS),('schemas',SCHEMAS)]:
   for key in pins:
    for remove in (True,False):
     with self.subTest(key=key,remove=remove):
      changed=deepcopy(d);target=changed['paths'] if section=='paths' else changed['components']['schemas']
      if remove:target.pop(key)
      else:target[key]['unreviewed']=True
      with self.assertRaises(ValueError):sales_chain_repair_projection(changed)
