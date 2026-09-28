import copy
import unittest
from pathlib import Path
import yaml
from scripts.baseline.r2_internal_opportunity_contract import PATHS, SCHEMAS, internal_opportunity_projection

class InternalOpportunityContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.doc = yaml.safe_load((Path(__file__).resolve().parents[3] / 'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
    def test_registered_exact_closed_transport(self):
        for name, value in PATHS.items(): self.assertEqual(value, self.doc['paths'].get(name))
        for name, value in SCHEMAS.items(): self.assertEqual(value, self.doc['components']['schemas'].get(name))
    def test_maintenance_commands_have_service_auth_and_no_human_receipt_headers(self):
        for path, item in PATHS.items():
            operation = next(iter(self.doc['paths'][path].values()))
            self.assertEqual([{'internalMutualTls': []}], operation['security'])
            self.assertNotIn('headers', operation['responses']['200'])
            if 'post' in item:
                self.assertEqual([{'$ref': '#/components/parameters/IdempotencyKey'}], operation['parameters'])
        schemas = self.doc['components']['schemas']
        self.assertEqual(9, len(schemas['ReopenDueOpportunityTaskV1']['required']))
        self.assertEqual({'opportunityId', 'expectedOpportunityRevision'}, set(schemas['ActivateInitialOpportunityTaskV1']['required']))
        self.assertNotIn('receiptRef', schemas['R2OpportunityTaskProblemV1']['properties'])
        self.assertNotIn('currentETag', schemas['R2OpportunityTaskProblemV1']['properties'])
        self.assertNotIn('STALE_PROGRESS', schemas['ErrorCode']['enum'])
    def test_partial_or_insecure_transport_rejected(self):
        for mutation in [lambda d: d['paths'].pop(next(iter(PATHS))), lambda d: d['paths'][next(iter(PATHS))]['get'].update(security=[]), lambda d: d['components']['schemas']['ActivateInitialOpportunityTaskV1'].update(additionalProperties=True), lambda d: d['components']['schemas']['R2OpportunityTaskPageV1']['properties']['candidates'].update(maxItems=101)]:
            changed = copy.deepcopy(self.doc); mutation(changed)
            with self.assertRaises(ValueError): internal_opportunity_projection(changed)
