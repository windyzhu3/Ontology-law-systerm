from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_quote_termination_transport_contract import termination_projection,PATHS,SCHEMAS,CHANGED,PREVIOUS

class QuoteTerminationTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from scripts.baseline.r2_contract_negotiation_transport_contract import negotiation_projection
        cls.document=negotiation_projection(yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8')))
    def test_exact_projection_is_lossless_for_preexisting_contract(self):
        source=deepcopy(self.document);expected=deepcopy(source)
        for key in PATHS:expected['paths'].pop(key)
        for key in SCHEMAS:expected['components']['schemas'].pop(key)
        expected['components']['schemas'].update(deepcopy(PREVIOUS))
        self.assertEqual(expected,termination_projection(source))
        self.assertEqual(expected,termination_projection(expected))
        self.assertEqual(source,self.document)
    def test_missing_or_modified_addition_cannot_hide_from_baseline(self):
        for section,pins in [('paths',PATHS),('schemas',SCHEMAS),('schemas',CHANGED)]:
            for key in pins:
                for remove in [True,False]:
                    with self.subTest(key=key,remove=remove):
                        source=deepcopy(self.document);target=source['paths'] if section=='paths' else source['components']['schemas']
                        if remove:target.pop(key)
                        else:target[key]['unreviewed']=True
                        with self.assertRaises((ValueError,KeyError)):termination_projection(source)
    def test_termination_requires_close_authority_and_exact_reason(self):
        op=self.document['paths']['/api/v1/opportunities/{opportunityId}/quotes/terminations']['post']
        self.assertEqual('OPPORTUNITY_CLOSE',op['x-authority-code'])
        values=self.document['components']['schemas']['EndQuoteNegotiationV1']['properties']['values']
        self.assertFalse(values['additionalProperties']);self.assertEqual(['reasonCode','summary'],values['required'])
