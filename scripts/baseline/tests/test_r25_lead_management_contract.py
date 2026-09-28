from pathlib import Path
import unittest
import yaml
from copy import deepcopy
from scripts.baseline.r25_lead_management_contract import lead_management_projection,PATHS

class LeadManagementContractTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_lead_management_has_bounded_independent_gets_and_exact_action_target(self):
  paths=self.document['paths'];schemas=self.document['components']['schemas']
  for path in ('/api/v1/lead-management/leads','/api/v1/lead-management/leads/{leadId}','/api/v1/lead-management/sources'):
   self.assertTrue(path in paths,path);self.assertEqual({'get'},set(paths[path]));self.assertTrue(paths[path]['get']['security'])
  operation=paths['/api/v1/lead-management/leads']['get']
  limit=next(p for p in operation['parameters'] if p.get('name')=='limit')['schema']
  self.assertEqual((20,100),(limit['default'],limit['maximum']))
  self.assertIn('LEAD_MANAGEMENT_READ',schemas['GrantableAuthorityCodeV1']['enum'])
  self.assertIn('canReadLeadManagement',schemas['SessionContextV1']['properties'])
  self.assertFalse(schemas['LeadManagementDetailV1']['additionalProperties'])
  self.assertGreaterEqual(len(schemas['LeadManagementDetailV1']['allOf']),2)
  self.assertEqual(50,schemas['LeadManagementSourcesV1']['properties']['items']['maxItems'])
 def test_projection_is_exact_optional_for_older_contract_and_rejects_partial_activation(self):
  prior=lead_management_projection(self.document);self.assertEqual(prior,lead_management_projection(prior))
  for path in PATHS:self.assertNotIn(path,prior['paths'])
  for fault in ('auth','limit','delegation','write','selector','authority','partial'):
   with self.subTest(fault=fault):
    d=deepcopy(self.document);schemas=d['components']['schemas'];operation=d['paths']['/api/v1/lead-management/leads']['get']
    if fault=='auth':operation['security']=[]
    elif fault=='limit':next(p for p in operation['parameters'] if p.get('name')=='limit')['schema']['maximum']=1000
    elif fault=='delegation':schemas['SessionContextV1']['allOf'][3]['then']['properties']['canReadLeadManagement']={'const':True}
    elif fault=='write':d['paths']['/api/v1/lead-management/leads']['post']={}
    elif fault=='selector':schemas['LeadManagementDetailV1']['allOf']=[]
    elif fault=='authority':schemas['GrantableAuthorityCodeV1']['enum'].remove('LEAD_MANAGEMENT_READ')
    else:del schemas['LeadManagementSourcesV1']
    with self.assertRaises(ValueError):lead_management_projection(d)
