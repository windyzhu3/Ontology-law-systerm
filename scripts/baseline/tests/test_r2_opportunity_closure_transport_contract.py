from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_opportunity_closure_transport_contract import closure_projection,PATH_DIGESTS,SCHEMA_DIGESTS,digest
from scripts.baseline.r2_opportunity_ledger_transport_contract import ledger_projection,GRANTABLE_DIGEST,PROJECTED_AUTHORITY_DIGEST
from scripts.baseline.r2_intake_sources_contract import intake_transport_projection

class OpportunityClosureTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'))
    def test_restores_exact_t03_then_historical_r1(self):
        projected=closure_projection(self.document)
        self.assertEqual(projected,closure_projection(projected))
        self.assertEqual(GRANTABLE_DIGEST,digest(projected['components']['schemas']['GrantableAuthorityCodeV1']))
        self.assertEqual(PROJECTED_AUTHORITY_DIGEST,digest(projected['components']['schemas']['AuthorityGrantV1']['properties']['authorityCode']))
        self.assertEqual(ledger_projection(projected),ledger_projection(self.document))
        self.assertEqual(37,sum(method in {'get','post','put','patch','delete'} for item in intake_transport_projection(self.document)['paths'].values() for method in item))
    def test_every_partial_or_mutated_path_and_schema_fails(self):
        for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS)]:
            for name in pins:
                for remove in (False,True):
                    with self.subTest(section=section,name=name,remove=remove):
                        doc=deepcopy(self.document);target=doc['paths'] if section=='paths' else doc['components']['schemas']
                        if remove:target.pop(name)
                        else:target[name]['unreviewed']=True
                        with self.assertRaises(ValueError):closure_projection(doc)
    def test_arbitrary_disclosure_auth_receipt_and_nullable_widening_fails(self):
        mutations=[lambda d:d['paths']['/api/v1/opportunities/{opportunityId}/commands/close']['post'].update(security=[]),
          lambda d:d['components']['schemas']['CloseOpportunityV1']['required'].remove('expectedTask'),
          lambda d:d['components']['schemas']['CloseOpportunityV1']['properties']['summary'].update(maxLength=1001),
          lambda d:d['components']['schemas']['OpportunityCloseContextV1'].pop('allOf'),
          lambda d:d['components']['schemas']['OpportunityClosureFactRefV1']['properties']['revision'].update(const=1),
          lambda d:d['components']['schemas']['OpportunityCloseReasonV1']['enum'].append('WON'),
          lambda d:d['components']['schemas']['GrantableAuthorityCodeV1']['enum'].append('UNREVIEWED'),
          lambda d:d['components']['schemas']['AuthorityGrantV1']['properties']['authorityCode']['enum'].remove('OPPORTUNITY_CLOSE'),
          lambda d:d['components']['schemas']['PublicFactRef']['oneOf'].append({'type':'object'}),
          lambda d:d['components']['schemas']['TerminalRejectionCode']['enum'].append('UNREVIEWED')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                doc=deepcopy(self.document);mutate(doc)
                with self.assertRaises(ValueError):closure_projection(doc)
    def test_unregistered_routes_remain_for_historical_rejection(self):
        doc=deepcopy(self.document);doc['paths']['/api/v1/opportunities/commands/reopen']={'post':{}}
        self.assertIn('/api/v1/opportunities/commands/reopen',closure_projection(doc)['paths'])
        with self.assertRaises(ValueError):ledger_projection(doc)
