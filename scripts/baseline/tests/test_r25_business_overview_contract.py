from pathlib import Path
from copy import deepcopy
import unittest
import yaml

class BusinessOverviewContractTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_exact_bounded_reads_and_five_closed_metrics(self):
  d=self.document
  for path in ('/api/v1/business-overview','/api/v1/business-overview/{metric}'):
   self.assertTrue(path in d['paths'],path);self.assertEqual({'get'},set(d['paths'][path]));op=d['paths'][path]['get'];self.assertTrue(op['security']);self.assertNotIn('x-authority-code',op);self.assertEqual(['CONTRACT_READ'],op['x-metric-authority-codes']['signedContracts']);self.assertEqual('AUTHORIZED_OVERVIEW_EXACT_SOURCES',op['x-subject-binding'])
  detail=d['paths']['/api/v1/business-overview/{metric}']['get'];limit=next(p for p in detail['parameters'] if p.get('name')=='limit')['schema']
  self.assertEqual((20,100),(limit['default'],limit['maximum']))
  schemas=d['components']['schemas'];self.assertEqual(5,len(schemas['BusinessOverviewMetricKeyV1']['enum']))
  self.assertFalse(schemas['BusinessOverviewSummaryV1']['additionalProperties']);self.assertEqual(5,schemas['BusinessOverviewSummaryV1']['properties']['metrics']['maxItems'])
  self.assertEqual(2,len(schemas['BusinessOverviewMetricV1']['allOf']));self.assertIn('canReadBusinessOverview',schemas['SessionContextV1']['properties'])
 def test_projection_rejects_partial_writes_larger_pages_and_delegation(self):
  from scripts.baseline.r25_business_overview_contract import overview_projection
  prior=overview_projection(self.document);self.assertEqual(prior,overview_projection(prior));self.assertNotIn('/api/v1/business-overview',prior['paths'])
  for fault in ('auth','limit','delegation','write','partial','count'):
   with self.subTest(fault=fault):
    d=deepcopy(self.document);s=d['components']['schemas'];op=d['paths']['/api/v1/business-overview/{metric}']['get']
    if fault=='auth':op['security']=[]
    elif fault=='limit':next(p for p in op['parameters'] if p.get('name')=='limit')['schema']['maximum']=1000
    elif fault=='delegation':s['SessionContextV1']['allOf'][3]['then']['properties']['canReadBusinessOverview']={'const':True}
    elif fault=='write':d['paths']['/api/v1/business-overview']['post']={}
    elif fault=='count':s['BusinessOverviewMetricV1']['allOf']=[]
    else:del s['BusinessOverviewSummaryV1']
    with self.assertRaises(ValueError):overview_projection(d)
