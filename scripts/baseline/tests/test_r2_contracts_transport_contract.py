from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_contracts_transport_contract import contracts_projection, PATH_DIGESTS, SCHEMA_DIGESTS, CHANGED_DIGESTS, PREVIOUS_SCHEMAS

class ContractTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'))

    def test_exact_t08_rollback_preserves_prior_transport(self):
        original=deepcopy(self.document)
        from scripts.baseline.r2_contract_generation_transport_contract import generation_projection
        expected=generation_projection(original)
        for key in PATH_DIGESTS: expected['paths'].pop(key)
        for key in SCHEMA_DIGESTS: expected['components']['schemas'].pop(key)
        expected['components']['schemas'].update(deepcopy(PREVIOUS_SCHEMAS))
        actual=contracts_projection(original)
        self.assertEqual(expected,actual)
        self.assertEqual(actual,contracts_projection(actual))
        self.assertEqual(original,self.document)

    def test_drift_or_missing_fragment_cannot_be_masked_as_historical(self):
        for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS),('schemas',CHANGED_DIGESTS)]:
            for key in pins:
                for remove in (False,True):
                    with self.subTest(key=key,remove=remove):
                        changed=deepcopy(self.document)
                        target=changed['paths'] if section=='paths' else changed['components']['schemas']
                        if remove: target.pop(key)
                        else: target[key]['unreviewed']=True
                        with self.assertRaises((ValueError,KeyError)):contracts_projection(changed)

    def test_signature_is_a_read_boundary_and_not_a_command(self):
        # This assertion freezes the historical T08 boundary; T09 is a named successor.
        from scripts.baseline.r2_manual_signature_transport_contract import signature_projection
        schemas=signature_projection(self.document)['components']['schemas']
        card=schemas['R2ContractCurrentCardV1']['properties']
        self.assertEqual(8,len(card['taskType']['enum']))
        self.assertEqual(9,len(card['primaryCommand']['properties']['code']['enum']))
        self.assertFalse(any('SIGN' in command for command in card['primaryCommand']['properties']['code']['enum']))
        self.assertIsNone(card['actionDraft']['const'])
        self.assertNotIn('matchedFacts',schemas['ContractContextV1']['properties']['review']['oneOf'][0]['properties'])

    def test_reconciliation_is_service_only_and_carries_exact_source_selectors(self):
        from scripts.baseline.r2_manual_signature_transport_contract import signature_projection
        historical=signature_projection(self.document)
        paths=historical['paths'];schemas=historical['components']['schemas']
        discovery=paths['/internal/v1/contract-preparation/candidates']['get']
        command=paths['/internal/v1/opportunity-tasks/commands/reconcile-contract-preparation']['post']
        for operation in (discovery,command):self.assertEqual([{'internalMutualTls':[]}],operation['security'])
        self.assertEqual(['limit','cursor'],[p['name'] for p in discovery['parameters']])
        self.assertEqual([{'$ref':'#/components/parameters/IdempotencyKey'}],command['parameters'])
        body=schemas['ReconcileContractPreparationV1']
        self.assertFalse(body['additionalProperties'])
        self.assertEqual({'opportunityId','expectedOpportunityRevision','responsibilityBasis','source','sourceKind','expectedWorkflow'},set(body['required']))
        self.assertEqual(['ACCEPTED_QUOTE','AUTHORITY_RETURN'],body['properties']['sourceKind']['enum'])
        self.assertEqual(0,body['properties']['source']['properties']['revision']['const'])
        self.assertEqual(0,schemas['ContractPreparationWorkflowFactRefV1']['properties']['revision']['const'])

if __name__=='__main__':unittest.main()
