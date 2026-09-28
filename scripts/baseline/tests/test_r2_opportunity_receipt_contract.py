import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline.r2_opportunity_receipt_contract import receipt_transport_projection, NAME, REF
from scripts.baseline import task9_identity_contract as identity
from scripts.baseline.r1_receipt_recovery_contract import _validate_transport
class R2OpportunityReceiptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/identity.API).read_text(encoding='utf-8'))
    def test_exact_addition_and_historical_document_pass_frozen_guards(self):
        for doc in (self.document,receipt_transport_projection(self.document)):
            self.assertEqual([],identity.validate_document(copy.deepcopy(doc)))
            self.assertEqual((),_validate_transport(yaml.safe_dump(doc,allow_unicode=True)))
    def test_partial_mutated_or_reordered_additions_fail_closed(self):
        mutations=[lambda d:d['components']['schemas'].pop(NAME),
                   lambda d:d['components']['schemas'][NAME].update(additionalProperties=True),
                   lambda d:d['components']['schemas'][NAME]['properties']['factType'].update(const='LEAD'),
                   lambda d:d['components']['schemas'][NAME]['required'].append('revision'),
                   lambda d:d['components']['schemas']['PublicFactRef']['oneOf'].remove(REF),
                   lambda d:d['components']['schemas']['PublicFactRef']['oneOf'].insert(0,REF),
                   lambda d:d['components']['schemas']['PublicFactRef']['oneOf'].reverse()]
        for change in mutations:
            doc=copy.deepcopy(self.document);change(doc)
            with self.assertRaises(ValueError):receipt_transport_projection(doc)
    def test_old_transport_mutation_is_not_hidden_by_successor(self):
        doc=copy.deepcopy(self.document);doc['paths']['/api/v1/commands/{commandId}/receipt']['get']['security']=[]
        self.assertTrue(identity.validate_document(doc))
