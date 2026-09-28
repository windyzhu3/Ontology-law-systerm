from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_manual_signature_transport_contract import signature_projection,PATHS,SCHEMAS,CHANGED,PREVIOUS

class ManualSignatureTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))

    def test_exact_projection_restores_previous_contract_without_mutating_input(self):
        original=deepcopy(self.document)
        from scripts.baseline.r2_quote_preparation_transport_contract import preparation_projection
        expected=preparation_projection(original)
        for key in PATHS:expected['paths'].pop(key)
        for key in SCHEMAS:expected['components']['schemas'].pop(key)
        expected['components']['schemas'].update(deepcopy(PREVIOUS))
        actual=signature_projection(original)
        self.assertEqual(expected,actual)
        self.assertEqual(actual,signature_projection(actual))
        self.assertEqual(original,self.document)

    def test_missing_or_drifted_signing_contract_cannot_be_projected_away(self):
        for section,pins in [('paths',PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
            for key in pins:
                for remove in [False,True]:
                    with self.subTest(key=key,remove=remove):
                        changed=deepcopy(self.document)
                        target=changed['paths'] if section=='paths' else changed['components']['schemas']
                        if remove:target.pop(key)
                        else:target[key]['unreviewed']=True
                        with self.assertRaises((ValueError,KeyError)):signature_projection(changed)

    def test_signature_is_manual_and_has_no_execution_or_case_commands(self):
        self.assertEqual(7,len(PATHS))
        for key in PATHS:
            op=self.document['paths'][key]['post']
            self.assertNotIn('EXECUTION',op['x-command-type'])
            self.assertNotIn('CASE',op['x-command-type'])
            self.assertIn(op['x-authority-code'],['CONTRACT_PREPARE','CONTRACT_SIGNATURE_VERIFY'])
        schemas=self.document['components']['schemas']
        values=schemas['R2RecordContractSignatureVerificationV1']['properties']['values']
        self.assertFalse(values['additionalProperties'])
        self.assertIn('expectedSignatureWorkflow',values['required'])
        self.assertIn('arrangementComplete',values['properties'])
        self.assertNotIn('allComplete',values['properties'])

if __name__=='__main__':unittest.main()
