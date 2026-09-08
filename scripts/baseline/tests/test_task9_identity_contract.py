"""Task9 transport acceptance and realistic contract rejection mutations."""
from pathlib import Path
import copy
import importlib
import shutil
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
API = ROOT / 'contracts/openapi/ontology-law-api.yaml'
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}
EXPECTED = {
    ('get', '/session/context'): 'getSessionContext',
    ('get', '/admin/identity/provider-users'): 'listIdentityProviderUsers',
    ('get', '/admin/identity/options'): 'getIdentityAdminOptions',
    ('get', '/admin/identity/principals'): 'listIdentityPrincipals',
    ('post', '/admin/identity/principals'): 'createIdentityPrincipal',
    ('patch', '/admin/identity/principals/{id}/display-name'): 'renameIdentityPrincipal',
    ('post', '/admin/identity/principals/{id}/suspend'): 'suspendIdentityPrincipal',
    ('post', '/admin/identity/principals/{id}/resume'): 'resumeIdentityPrincipal',
    ('post', '/admin/identity/principals/{id}/disable'): 'disableIdentityPrincipal',
    ('get', '/admin/identity/organizations'): 'listOrganizationUnits',
    ('post', '/admin/identity/organizations'): 'createOrganizationUnit',
    ('patch', '/admin/identity/organizations/{id}/display-name'): 'renameOrganizationUnit',
    ('post', '/admin/identity/organizations/{id}/close'): 'closeOrganizationUnit',
    ('get', '/admin/identity/appointments'): 'listAppointments',
    ('post', '/admin/identity/appointments'): 'createAppointment',
    ('post', '/admin/identity/appointments/{id}/suspend'): 'suspendAppointment',
    ('post', '/admin/identity/appointments/{id}/resume'): 'resumeAppointment',
    ('post', '/admin/identity/appointments/{id}/end'): 'endAppointment',
    ('get', '/admin/identity/authority-grants'): 'listAuthorityGrants',
    ('post', '/admin/identity/authority-grants'): 'createAuthorityGrant',
    ('post', '/admin/identity/authority-grants/{id}/revoke'): 'revokeAuthorityGrant',
}


class Task9IdentityContractTest(unittest.TestCase):
    def setUp(self):
        self.api = yaml.safe_load(API.read_text(encoding='utf-8'))

    def assert_inventory(self, document):
        operations = [(method, path, op) for path, item in document['paths'].items()
                      for method, op in item.items() if method in METHODS]
        self.assertEqual(37, len(operations))
        self.assertEqual(37, len({op['operationId'] for _, _, op in operations}))
        self.assertEqual(32, sum(op.get('security') == [{'publicBearer': []}] for _, _, op in operations))
        self.assertEqual(5, sum(op.get('security') == [{'internalMutualTls': []}] for _, _, op in operations))
        actual = {(method, path.removeprefix('/api/v1')): op['operationId']
                  for method, path, op in operations
                  if path.startswith('/api/v1/admin/identity/') or path == '/api/v1/session/context'}
        self.assertEqual(EXPECTED, actual)
        self.assertEqual(21, len(set(actual.values())))
        self.assertIn('NOT_FOUND', document['components']['schemas']['TerminalRejectionCode']['enum'])

    def test_task9_inventory(self):
        self.assert_inventory(self.api)

    def test_inventory_rejects_removed_operation_changed_security_and_missing_not_found(self):
        self.assert_inventory(self.api)
        for mutation in ('remove-operation', 'change-security', 'remove-not-found'):
            with self.subTest(mutation=mutation):
                document = copy.deepcopy(self.api)
                if mutation == 'remove-operation':
                    del document['paths']['/api/v1/admin/identity/principals']['post']
                elif mutation == 'change-security':
                    document['paths']['/api/v1/session/context']['get']['security'] = [{'internalMutualTls': []}]
                else:
                    document['components']['schemas']['TerminalRejectionCode']['enum'].remove('NOT_FOUND')
                with self.assertRaises(AssertionError):
                    self.assert_inventory(document)

    def validator(self):
        try:
            return importlib.import_module('scripts.baseline.task9_identity_contract')
        except ModuleNotFoundError:
            self.fail('Task9 fail-closed contract validator is not implemented')

    def test_successor_contract_is_active(self):
        self.assertEqual([], self.validator().validate(ROOT))

    def test_bootstrap_candidate_profile_and_appointment_root_are_enforced(self):
        module = self.validator()
        identity = (ROOT / module.IDENTITY).read_text(encoding='utf-8')
        self.assertIn('R1_IDENTITY_BOOTSTRAP_CANDIDATE_V1', identity)
        self.assertIn('| createAppointment | CREATE_APPOINTMENT | IDENTITY_APPOINTMENT_MANAGE | CreateAppointmentV1 | AppointmentCommandReceiptV1 | ROOT |', identity)
        self.assertEqual('IDENTITY_ROOT', self.api['paths']['/api/v1/admin/identity/appointments']['post']['x-subject-binding'])
        self.assertEqual([], module.validate_identity_text(identity))
        for original, replacement in [
            ('R1_IDENTITY_BOOTSTRAP_CANDIDATE_V1', 'R1_IDENTITY_ADMIN_ACTOR_V1'),
            ('AppointmentCommandReceiptV1 | ROOT |', 'AppointmentCommandReceiptV1 | SCOPED |'),
            ('different-purpose envelopes cannot cross online/bootstrap boundaries', 'online selectors are also accepted'),
            ('exactly one real account', 'the first account'),
            ('envelope integrity is always checked', 'envelope integrity is optional'),
        ]:
            with self.subTest(mutation=original):
                self.assertIn(original, identity)
                self.assertTrue(module.validate_identity_text(identity.replace(original, replacement)))

    def test_malformed_transport_shapes_report_findings_without_crashing(self):
        validate = self.validator().validate_document
        self.assertEqual([], validate(self.api))
        mutations = [
            lambda d: d['paths'].update({'/api/v1/session/context': None}),
            lambda d: d['paths']['/api/v1/session/context'].update({'get': None}),
            lambda d: d['paths']['/api/v1/session/context']['get'].update({'security': 'publicBearer'}),
            lambda d: d.update({'components': None}),
            lambda d: d['components'].update({'schemas': None}),
            lambda d: d['components']['schemas'].update({'SessionContextV1': None}),
            lambda d: d['components']['schemas']['SessionContextV1'].update({'properties': None}),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                document = copy.deepcopy(self.api)
                mutate(document)
                self.assertTrue(validate(document))

    def test_tenant_self_actor_fake_appointment_and_authority_expansion_are_rejected(self):
        validate = self.validator().validate_document
        self.assertEqual([], validate(self.api))
        mutations = [
            lambda d: d['components']['schemas']['CreateIdentityPrincipalV1']['properties'].update({'tenantId': {'type': 'string'}}),
            lambda d: d['paths']['/api/v1/session/context']['get'].update({'x-tenant-source': 'ACTOR_CONTEXT'}),
            lambda d: d['components']['schemas']['SessionContextV1']['properties']['selectedAppointmentId'].update({'type': 'string'}),
            lambda d: d['components']['schemas']['GrantableAuthorityCodeV1']['enum'].append('IDENTITY_AUTHORITY_MANAGE'),
            lambda d: d['components']['schemas']['CreateAuthorityGrantV1'].update({'additionalProperties': True}),
            lambda d: d['paths']['/api/v1/admin/identity/principals']['post'].update({'x-authority-path': 'DIRECT,DELEGATED'}),
            lambda d: d['paths']['/api/v1/admin/identity/appointments']['post'].update({'x-subject-binding': 'IDENTITY_SCOPED'}),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                document = copy.deepcopy(self.api)
                mutate(document)
                self.assertTrue(validate(document))

    def test_active_successor_rejects_reintroduced_identity_exclusions_and_http_tenant_source(self):
        module = self.validator()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = [module.API, module.IDENTITY, module.ADR,
                     'docs/baseline/CURRENT-MVP-BASELINE.md',
                     'docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md',
                     'docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md']
            for relative in files:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative, target)
            self.assertEqual([], module.validate(root))
            for relative, original, replacement in [
                (files[3], 'Baseline ID: MVP-2026-09-08.2', 'Baseline ID: MVP-2026-09-08.1'),
                (files[3], 'ADR-0014 仅纳入', '生产CRUD不计入R1。ADR-0014 仅纳入'),
                (files[4], 'R1 只交付', '身份管理生产能力不属于 R1。R1 只交付'),
                (files[5], '| AUTHENTICATED_IDENTITY |', '| ACTOR_CONTEXT |'),
            ]:
                path = root / relative
                text = path.read_text(encoding='utf-8')
                self.assertIn(original, text)
                path.write_text(text.replace(original, replacement), encoding='utf-8')
                try:
                    self.assertTrue(module.validate(root))
                finally:
                    path.write_text(text, encoding='utf-8')


if __name__ == '__main__':
    unittest.main()
