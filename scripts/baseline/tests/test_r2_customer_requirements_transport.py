from copy import deepcopy
from pathlib import Path
import unittest
import yaml

class CustomerRequirementsTransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))

    def test_exact_atomic_surface(self):
        prefix='/api/v1/opportunities/{opportunityId}/customer-requirements'
        paths={p for p in self.doc['paths'] if '/customer-requirements' in p}
        self.assertEqual({prefix,prefix+'/parties',prefix+'/draft',prefix+'/confirm'},paths)
        schemas=self.doc['components']['schemas']
        version=schemas['OpportunityCustomerVersionV1']
        self.assertIn('factRef',version['required'])
        self.assertEqual(version['properties']['factRef']['$ref'],'#/components/schemas/OpaqueRef')
        confirm=schemas['ConfirmOpportunityCustomerRequirementsV1']
        self.assertNotIn('document',confirm['properties'])
        self.assertEqual(set(confirm['required']),{'expectedOpportunityRevision','responsibilityBasis','expectedDraft','expectedConfirmation'})
        self.assertFalse(confirm['additionalProperties'])
        for path in paths:
            for operation in self.doc['paths'][path].values():
                self.assertEqual([{'publicBearer':[]}],operation['security'])
                self.assertEqual('REJECT',operation['x-on-behalf-selection'])

    def test_preserves_exact_predecessor_and_rejects_mutation(self):
        from scripts.baseline.r2_customer_requirements_transport_contract import customer_requirements_projection,PATH_DIGESTS,SCHEMA_DIGESTS
        from scripts.baseline.r2_opportunity_closure_transport_contract import closure_projection
        projected=customer_requirements_projection(self.doc)
        self.assertEqual(projected,customer_requirements_projection(projected))
        closure_projection(projected)
        for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS)]:
            for name in pins:
                changed=deepcopy(self.doc)
                target=changed['paths'] if section=='paths' else changed['components']['schemas']
                target.pop(name)
                with self.assertRaises(ValueError):customer_requirements_projection(changed)
