from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_contract_negotiation_transport_contract import negotiation_projection,PATHS,SCHEMAS,CHANGED,PREVIOUS

class ContractNegotiationTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from scripts.baseline.r2_source_request_transport_contract import source_request_projection
        cls.document=source_request_projection(yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8')))
    def test_exact_projection_is_lossless_for_preexisting_contract(self):
        source=deepcopy(self.document);expected=deepcopy(source)
        for key in PATHS:expected['paths'].pop(key)
        for key in SCHEMAS:expected['components']['schemas'].pop(key)
        expected['components']['schemas'].update(deepcopy(PREVIOUS))
        self.assertEqual(expected,negotiation_projection(source))
        self.assertEqual(expected,negotiation_projection(expected))
        self.assertEqual(source,self.document)
    def test_missing_or_modified_addition_cannot_hide_from_baseline(self):
        for section,pins in [('paths',PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
            for key in pins:
                for remove in [True,False]:
                    with self.subTest(key=key,remove=remove):
                        source=deepcopy(self.document);target=source['paths'] if section=='paths' else source['components']['schemas']
                        if remove:target.pop(key)
                        else:target[key]['unreviewed']=True
                        with self.assertRaises((ValueError,KeyError)):negotiation_projection(source)
    def test_distinct_sales_exit_and_independent_supervisor_authority(self):
        for tail,code in [('negotiation-end','OPPORTUNITY_CLOSE'),('termination-review-requests','OPPORTUNITY_CLOSE'),('termination-review-decisions','CONTRACT_TERMINATION_REVIEW')]:
            op=self.document['paths']['/api/v1/opportunities/{opportunityId}/contracts/'+tail]['post'];self.assertEqual(code,op['x-authority-code'])
        values=self.document['components']['schemas']['RecordContractTerminationReviewV1']['properties']['values'];self.assertEqual(['STOP','CONTINUE'],values['properties']['decision']['enum']);self.assertFalse(values['additionalProperties']);self.assertIn('expectedTermination',values['required']);self.assertIn('humanConfirmed',values['required'])
    def test_contract_business_rejections_have_a_terminal_receipt_code(self):
        schemas=self.document['components']['schemas']
        terminal=schemas['TerminalRejectionCode']['enum']
        for code in schemas['ContractProblemV1']['properties']['code']['enum']:
            if code.startswith('CONTRACT_') or code=='COMMERCIAL_AUTHORIZATION_REQUIRED':
                with self.subTest(code=code):self.assertIn(code,terminal)
