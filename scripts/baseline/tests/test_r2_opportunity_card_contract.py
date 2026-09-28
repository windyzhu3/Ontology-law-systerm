import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline.r2_opportunity_card_contract import card_transport_projection,SCHEMAS
from scripts.baseline import task9_identity_contract as identity
from scripts.baseline.r2_quotes_transport_contract import quotes_projection

class R2OpportunityCardContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.doc=yaml.safe_load((Path(__file__).resolve().parents[3]/identity.API).read_text(encoding='utf-8'))
    def test_exact_delta_and_r1_registration_remain_valid(self):
        self.assertEqual([],identity.validate_document(self.doc))
        # Validate and remove later quote/contract card additions before the exact T02 projection.
        historical = quotes_projection(self.doc)
        self.assertEqual([],identity.validate_document(card_transport_projection(historical)))
        self.assertEqual(7,len(self.doc['components']['schemas']['CurrentCard']['oneOf']))
    def test_rejects_partial_and_widened_cards(self):
        mutations=[lambda s:s.pop(next(iter(SCHEMAS))),lambda s:s['R2OpportunityCurrentCardV1'].update(additionalProperties=True),lambda s:s['R2OpportunitySubjectV1']['properties']['subjectType']['enum'].append('LEAD'),lambda s:s['R2CurrentCardV1']['oneOf'].pop(),lambda s:s['R2OpportunityFormV1']['properties']['fields'].update(maxItems=1)]
        for mutate in mutations:
            doc=quotes_projection(self.doc);mutate(doc['components']['schemas'])
            with self.assertRaises(ValueError):card_transport_projection(doc)
