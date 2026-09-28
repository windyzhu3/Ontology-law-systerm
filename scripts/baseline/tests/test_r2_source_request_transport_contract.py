from pathlib import Path
import unittest,yaml
class SourceRequestTransportTest(unittest.TestCase):
 def test_named_protocol_keeps_frozen_r1_enums(self):
  d=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'));s=d['components']['schemas']
  self.assertIn('R2SourceRequestCurrentCardV1',s)
  self.assertNotIn('RECORD_SOURCE_REQUEST_CONTINUATION',s['ActionCode']['enum'])
  self.assertNotIn('RESOLVE_SOURCE_REQUEST',s['TaskType']['enum'])
  for path in ['/api/v1/tasks/{taskId}/source-request-draft','/api/v1/tasks/{taskId}/commands/record-source-request-continuation','/internal/v1/tasks/commands/reopen-due-source-request-tasks']:self.assertIn(path,d['paths'])
  self.assertEqual(['ASSIGN_SELECTED','SCHEDULE_REVIEW','END_LEAD'],s['RecordSourceRequestContinuationValuesV1']['properties']['decisionCode']['enum'])

 def test_projection_preserves_prior_contract_and_rejects_unreviewed_delta(self):
  from copy import deepcopy
  from scripts.baseline.r2_source_request_transport_contract import source_request_projection,PATHS,SCHEMAS,CHANGED,PREVIOUS,CHANGED_PATHS,PREVIOUS_PATHS
  source=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'));expected=deepcopy(source)
  from scripts.baseline.r2_sales_chain_repair_transport_contract import sales_chain_repair_projection
  expected=sales_chain_repair_projection(expected)
  for key in PATHS:expected['paths'].pop(key)
  for key in SCHEMAS:expected['components']['schemas'].pop(key)
  expected['components']['schemas'].update(deepcopy(PREVIOUS));expected['paths'].update(deepcopy(PREVIOUS_PATHS))
  self.assertEqual(expected,source_request_projection(source));self.assertEqual(expected,source_request_projection(expected))
  for section,pins in [('paths',PATHS),('paths',CHANGED_PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
   for key in pins:
    for remove in [True,False]:
     with self.subTest(key=key,remove=remove):
      altered=deepcopy(source);target=altered['paths'] if section=='paths' else altered['components']['schemas']
      if remove:target.pop(key)
      else:target[key]['unreviewed']=True
      with self.assertRaises((ValueError,KeyError)):source_request_projection(altered)

 def test_acknowledgment_exposes_supervisor_failure_as_422_and_freezes_it(self):
  from copy import deepcopy
  from scripts.baseline.r2_source_request_transport_contract import source_request_projection
  d=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
  path='/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request'
  operation=d['paths'][path]['post']
  self.assertIn('SUPERVISOR_UNRESOLVED',operation['x-error-codes'])
  self.assertEqual({'$ref':'#/components/responses/UnprocessableProblem'},operation['responses']['422'])
  for kind in ('missing_response','wrong_response','missing_code'):
   with self.subTest(kind=kind):
    changed=deepcopy(d);op=changed['paths'][path]['post']
    if kind=='missing_response':op['responses'].pop('422')
    elif kind=='wrong_response':op['responses']['422']={'$ref':'#/components/responses/ConflictProblem'}
    else:op['x-error-codes'].remove('SUPERVISOR_UNRESOLVED')
    with self.assertRaises(ValueError):source_request_projection(changed)
