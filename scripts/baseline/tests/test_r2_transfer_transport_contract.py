from scripts.baseline.r2_classification_correction_contract import correction_projection
from copy import deepcopy
from pathlib import Path
import unittest,yaml
from scripts.baseline.r2_transfer_transport_contract import transfer_projection,PATHS,SCHEMAS,CHANGED,PREVIOUS

class TransferTransportTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.document=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_additive_projection_is_exact_idempotent_and_does_not_modify_input(self):
  original=deepcopy(self.document);expected=correction_projection(original)
  for name in PATHS:expected['paths'].pop(name)
  for name in SCHEMAS:expected['components']['schemas'].pop(name)
  expected['components']['schemas'].update(deepcopy(PREVIOUS))
  self.assertEqual(expected,transfer_projection(original));self.assertEqual(expected,transfer_projection(expected));self.assertEqual(original,self.document)
 def test_new_transfer_contracts_cannot_hide_unapproved_drift(self):
  for section,pins in [('paths',PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
   for name in pins:
    with self.subTest(name=name):
     changed=deepcopy(self.document);target=changed['paths'] if section=='paths' else changed['components']['schemas'];target[name]['unexpected']=True
     with self.assertRaises(ValueError):transfer_projection(changed)
 def test_case_generation_remains_an_explicit_human_intake_command(self):
  commands=[operation['post']['x-command-type'] for operation in self.document['paths'].values() if 'post' in operation and operation['post'].get('operationId') in ['submitTransfer','resubmitTransfer','recordTransferConflictReview','recordTransferIntake','classifyMatter']]
  self.assertEqual({'SUBMIT_TRANSFER','RESUBMIT_TRANSFER','RECORD_TRANSFER_CONFLICT_REVIEW','RECORD_TRANSFER_INTAKE','CLASSIFY_MATTER'},set(commands))
  self.assertNotIn('AUTO_ACCEPT_TRANSFER',commands)
  schemas=self.document['components']['schemas'];self.assertNotIn('classification',schemas['R2SubmitTransferV1']['properties']['values']['properties'])
  self.assertIn('matterId',schemas['R2ClassifyMatterV1']['properties']['values']['required'])
