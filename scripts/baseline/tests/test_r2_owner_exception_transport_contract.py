from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_owner_exception_transport_contract import owner_exception_projection, PATH_DIGESTS, SCHEMA_DIGESTS
from scripts.baseline.r2_intake_sources_contract import intake_transport_projection

class OwnerExceptionTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load((Path(__file__).resolve().parents[3] / 'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
    def test_exact_registered_addition_projects_to_original_37_operations_and_nine_self_fields(self):
        projected = intake_transport_projection(self.document)
        self.assertEqual(37, sum(method in {'get','post','put','patch','delete'} for item in projected['paths'].values() for method in item))
        self.assertEqual(9, len(projected['components']['schemas']['SessionContextV1']['properties']))
        self.assertFalse(set(PATH_DIGESTS) & projected['paths'].keys())
        self.assertFalse(set(SCHEMA_DIGESTS) & projected['components']['schemas'].keys())
        self.assertEqual(projected, owner_exception_projection(projected))
    def test_unknown_routes_insecure_auth_and_partial_successor_are_rejected(self):
        mutations = [lambda d: d['paths'].update({'/api/v1/opportunity-owner-exceptions/grants': {'post': {}}}),
                     lambda d: d['paths'][next(iter(PATH_DIGESTS))]['get'].update(security=[]),
                     lambda d: d['paths'].pop(next(iter(PATH_DIGESTS))),
                     lambda d: d['components']['schemas'].pop('OwnerExceptionDetailV1')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                doc=deepcopy(self.document);mutate(doc)
                with self.assertRaises(ValueError):owner_exception_projection(doc)
    def test_unknown_fields_sensitive_operations_dto_and_widened_bounds_are_rejected(self):
        mutations = [lambda s: s['OwnerExceptionOperationsSummaryV1']['properties'].update(customerName={'type':'string'}),
                     lambda s: s['TransferOpportunityResponsibilityV1'].update(additionalProperties=True),
                     lambda s: s['GrantableAuthorityCodeV1']['enum'].append('UNAPPROVED_ADMIN'),
                     lambda s: s['AuthorityGrantV1']['properties']['authorityCode']['enum'].append('UNAPPROVED_ADMIN'),
                     lambda s: s['AuthorityGrantV1']['properties']['authorityCode']['enum'].pop(),
                     lambda s: s['OwnerExceptionObservationPageV1']['properties']['diagnostics'].update(maximum=101),
                     lambda s: s['OwnerExceptionPageV1']['properties']['items'].update(maxItems=101),
                     lambda s: s['SessionContextV1']['properties'].update(canGrantAuthority={'type':'boolean'}),
                     lambda s: s['SessionContextV1']['allOf'][0]['then']['properties']['canManageOwnerExceptions'].update(const=True)]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                doc=deepcopy(self.document);mutate(doc['components']['schemas'])
                with self.assertRaises(ValueError):owner_exception_projection(doc)
    def test_unrelated_additions_are_not_silently_erased_before_historical_validation(self):
        doc=deepcopy(self.document);doc['paths']['/api/v1/rogue']={'post':{'operationId':'rogue'}}
        self.assertIn('/api/v1/rogue',intake_transport_projection(doc)['paths'])
