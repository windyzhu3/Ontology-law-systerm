from pathlib import Path
from copy import deepcopy
import unittest,yaml

class AiCandidateContractTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_three_closed_candidates_cannot_accept_source_or_commands(self):
  d=self.document;p='/api/v1/opportunities/{opportunityId}/ai-candidates/{task}'
  self.assertIn(p,d['paths']);op=d['paths'][p]['post'];self.assertTrue(op['security']);self.assertEqual('AUTHORIZED_AI_EXACT_SOURCES',op['x-subject-binding'])
  s=d['components']['schemas'];self.assertEqual(['FIELDS','SUMMARY','MATERIALS'],s['AiCandidateTaskV1']['enum']);self.assertFalse(s['AiCandidateRequestV1']['additionalProperties']);self.assertEqual({},s['AiCandidateRequestV1']['properties'])
  self.assertFalse(s['AiCandidateResultV1']['additionalProperties']);self.assertEqual(50,s['AiCandidateResultV1']['properties']['sources']['maxItems']);self.assertNotIn('command',s['AiCandidateResultV1']['properties'])
 def test_exact_projection_rejects_extra_route_fields_and_unrestricted_sources(self):
  from scripts.baseline.r25_ai_candidate_contract import ai_projection
  p='/api/v1/opportunities/{opportunityId}/ai-candidates/{task}'
  prior=ai_projection(self.document);self.assertNotIn(p,prior['paths']);self.assertEqual(prior,ai_projection(prior))
  for fault in ('auth','write','source','limit','partial'):
   d=deepcopy(self.document);s=d['components']['schemas']
   if fault=='auth':d['paths'][p]['post']['security']=[]
   elif fault=='write':d['paths'][p+'/execute']={'post':{}}
   elif fault=='source':s['AiCandidateRequestV1']['additionalProperties']=True
   elif fault=='limit':s['AiCandidateResultV1']['properties']['sources']['maxItems']=10000
   else:del s['AiCandidateSourceV1']
   with self.assertRaises(ValueError):ai_projection(d)
