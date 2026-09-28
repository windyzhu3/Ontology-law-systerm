from copy import deepcopy
from pathlib import Path
import unittest,yaml
from scripts.baseline.r25_team_transport_contract import team_projection,PATHS

class TeamTransportContractTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_exact_team_addition_projects_to_unchanged_prior_and_cannot_grant_commands(self):
  prior=team_projection(self.document);self.assertEqual(prior,team_projection(prior))
  self.assertNotIn('TEAM_TASK_READ',prior['components']['schemas']['GrantableAuthorityCodeV1']['enum'])
  for path in PATHS:self.assertEqual({'get'},set(self.document['paths'][path]))
 def test_scope_auth_bound_and_action_substitution_cannot_hide_in_successor(self):
  for fault in ('auth','limit','delegation','command','action','authority'):
   with self.subTest(fault=fault):
    d=deepcopy(self.document);op=d['paths']['/api/v1/team-management/{view}']['get'];schemas=d['components']['schemas']
    if fault=='auth':op['security']=[]
    elif fault=='limit':next(p for p in op['parameters'] if p.get('name')=='limit')['schema']['maximum']=1000
    elif fault=='delegation':schemas['SessionContextV1']['allOf'][3]['then']['properties']['canReadTeamTasks']={'const':True}
    elif fault=='command':d['paths']['/api/v1/team-management/{view}']['post']={}
    elif fault=='action':schemas['TeamDetailV1']['allOf']=[]
    else:schemas['GrantableAuthorityCodeV1']['enum'].remove('TEAM_TASK_READ')
    with self.assertRaises(ValueError):team_projection(d)
