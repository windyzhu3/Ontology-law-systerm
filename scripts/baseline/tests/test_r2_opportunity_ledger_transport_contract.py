from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_opportunity_ledger_transport_contract import ledger_projection, PATH_DIGESTS, SCHEMA_DIGESTS
from scripts.baseline.r2_owner_exception_transport_contract import owner_exception_projection, SESSION_DIGEST, digest
from scripts.baseline.r2_intake_sources_contract import intake_transport_projection

class OpportunityLedgerTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'))
    def test_addition_restores_exact_t01_and_r1(self):
        projected=ledger_projection(self.document)
        self.assertEqual(SESSION_DIGEST,digest(projected['components']['schemas']['SessionContextV1']))
        self.assertEqual(projected,ledger_projection(projected))
        self.assertEqual(37,sum(method in {'get','post','put','patch','delete'} for item in intake_transport_projection(self.document)['paths'].values() for method in item))
        self.assertEqual(owner_exception_projection(projected),owner_exception_projection(self.document))
    def test_every_partial_or_mutated_named_path_and_schema_fails(self):
        for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS)]:
            for name in pins:
                for remove in (False,True):
                    with self.subTest(section=section,name=name,remove=remove):
                        doc=deepcopy(self.document);target=doc['paths'] if section=='paths' else doc['components']['schemas']
                        if remove:target.pop(name)
                        else:target[name]['unreviewed']=True
                        with self.assertRaises(ValueError):ledger_projection(doc)
    def test_widened_auth_disclosure_state_and_bounds_fail(self):
        mutations=[lambda d:d['paths']['/api/v1/opportunities']['get'].update(security=[]),
            lambda d:d['paths'].update({'/api/v1/opportunities/commands/close':{'post':{}}}),
            lambda d:d['components']['schemas']['OpportunityLedgerPageV1']['properties']['items'].update(maxItems=101),
            lambda d:d['components']['schemas']['OpportunityLedgerDetailV1']['properties'].update(phone={'type':'string'}),
            lambda d:d['components']['schemas']['OpportunityLedgerDetailV1'].pop('allOf'),
            lambda d:d['components']['schemas']['SessionContextV1']['allOf'][3]['then']['properties']['canReadOpportunityLedger'].update(const=True),
            lambda d:d['components']['schemas']['GrantableAuthorityCodeV1']['enum'].append('UNREVIEWED'),
            lambda d:d['components']['schemas']['AuthorityGrantV1']['properties']['authorityCode']['enum'].remove('OPPORTUNITY_LEDGER_READ')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                doc=deepcopy(self.document);mutate(doc)
                with self.assertRaises(ValueError):ledger_projection(doc)
    def test_unrelated_additions_survive_for_historical_rejection(self):
        doc=deepcopy(self.document);doc['paths']['/api/v1/rogue']={'post':{}}
        self.assertIn('/api/v1/rogue',intake_transport_projection(doc)['paths'])
