import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline.r2_opportunity_submit_contract import submit_transport_projection,PATHS,SCHEMAS
from scripts.baseline import task9_identity_contract as identity
class R2OpportunitySubmitContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.doc=yaml.safe_load((Path(__file__).resolve().parents[3]/identity.API).read_text(encoding='utf-8'))
    def test_exact_delta_and_previous_contract_remain_valid(self):
        for document in [self.doc,submit_transport_projection(self.doc)]:self.assertEqual([],identity.validate_document(document))
    def test_mutated_partial_or_unprotected_delta_is_rejected(self):
        changes=[lambda d:d['paths'].pop(next(iter(PATHS))),lambda d:d['components']['schemas'].pop(next(iter(SCHEMAS))),lambda d:d['paths'][next(iter(PATHS))]['put'].update(security=[]),lambda d:d['components']['schemas']['RecordOpportunityProgressV1'].update(additionalProperties=True),lambda d:d['components']['schemas']['OpportunityProgressValuesV1']['properties']['progressTypeCode']['enum'].append('QUOTE_SENT')]
        for mutate in changes:
            document=copy.deepcopy(self.doc);mutate(document)
            with self.assertRaises(ValueError):submit_transport_projection(document)
