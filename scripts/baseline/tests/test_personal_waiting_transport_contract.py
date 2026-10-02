import copy
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]


class PersonalWaitingTransportContractTest(unittest.TestCase):
    def test_exact_approved_delta_and_each_fragment_rejects_drift(self):
        from scripts.baseline.personal_waiting_transport_contract import (
            PATHS, SCHEMAS, PARAMETERS, personal_waiting_projection,
        )
        def snapshot(ref):
            return yaml.safe_load(subprocess.run(
                ['git', 'show', ref + ':contracts/openapi/ontology-law-api.yaml'],
                cwd=ROOT, capture_output=True, check=True).stdout)
        previous = snapshot('c0673b4^')
        approved = snapshot('33ecd7b')
        self.assertEqual(previous, personal_waiting_projection(approved))
        self.assertEqual(previous, personal_waiting_projection(previous))
        for section, names in [('paths', PATHS), ('schemas', SCHEMAS), ('parameters', PARAMETERS)]:
            for name in names:
                for mutation in ['missing', 'changed']:
                    with self.subTest(section=section, name=name, mutation=mutation):
                        altered = copy.deepcopy(approved)
                        target = altered['paths'] if section == 'paths' else altered['components'][section]
                        if mutation == 'missing':
                            del target[name]
                        else:
                            target[name]['unapproved'] = True
                        with self.assertRaises(ValueError):
                            personal_waiting_projection(altered)

    def test_current_chain_preserves_exact_r1_inventory(self):
        from scripts.baseline.r2_task_selection_contract import r1_transport_projection
        current = yaml.safe_load((ROOT / 'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
        projected = r1_transport_projection(current)
        methods = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}
        self.assertEqual(37, sum(method in methods for item in projected['paths'].values() for method in item))
        from scripts.baseline.task9_identity_contract import canonical_hash, OPENAPI_SHA256
        self.assertEqual(OPENAPI_SHA256, canonical_hash(projected))

    def test_haihua_seed_lists_reject_missing_extra_duplicate_and_wrong_order(self):
        from scripts.baseline.configurable_roles_transport_contract import configurable_roles_projection
        from scripts.baseline.adm07_audit_query_contract import audit_query_projection
        from scripts.baseline.haihua_roles_transport_contract import haihua_roles_projection
        current = yaml.safe_load((ROOT / 'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
        roles = configurable_roles_projection(audit_query_projection(current))
        for location in ['IdentityRoleCodeV1', 'AppointmentV1']:
            for mutation in ['missing', 'extra', 'duplicate', 'order']:
                with self.subTest(location=location, mutation=mutation):
                    altered = copy.deepcopy(roles)
                    schema = altered['components']['schemas'][location]
                    values = schema['enum'] if location == 'IdentityRoleCodeV1' else schema['properties']['roleCode']['enum']
                    if mutation == 'missing': values.pop()
                    elif mutation == 'extra': values.append('UNAPPROVED_ROLE')
                    elif mutation == 'duplicate': values.append(values[-1])
                    else: values.reverse()
                    with self.assertRaises(ValueError): haihua_roles_projection(altered)
