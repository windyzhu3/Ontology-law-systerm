import copy
import contextlib
import io
import unittest
from pathlib import Path
import yaml

from scripts.baseline import task9_identity_contract as identity
from scripts.baseline.r1_receipt_recovery_contract import _validate_transport
from scripts.baseline.r2_intake_sources_contract import PATH, SCHEMAS, intake_transport_projection
from scripts.baseline.r2_task_selection_contract import r1_transport_projection


class R2IntakeSourcesContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load((Path(__file__).resolve().parents[3] / identity.API).read_text(encoding='utf-8'))

    def assert_guards(self, document, valid):
        self.assertEqual(not valid, bool(identity.validate_document(copy.deepcopy(document))))
        self.assertEqual(not valid, bool(_validate_transport(yaml.safe_dump(document, allow_unicode=True))))

    def test_complete_addition_and_each_historical_projection_pass(self):
        self.assert_guards(self.document, True)
        self.assert_guards(intake_transport_projection(self.document), True)
        self.assert_guards(r1_transport_projection(self.document), True)

    def test_every_partial_activation_fails_closed(self):
        for component in [PATH, *SCHEMAS]:
            with self.subTest(component=component):
                altered = copy.deepcopy(self.document)
                (altered['paths'] if component == PATH else altered['components']['schemas']).pop(component)
                self.assert_guards(altered, False)

    def test_metadata_authority_bounds_and_existing_r1_mutations_fail_closed(self):
        def op(d): return d['paths'][PATH]['get']
        def item(d): return d['components']['schemas']['LeadIntakeSourceV1']
        def sources(d): return d['components']['schemas']['LeadIntakeSourcesV1']
        mutations = [
            lambda d: op(d).update(security=[]),
            lambda d: op(d).update(security=[{'internalMutualTls': []}]),
            lambda d: op(d).update(operationId='captureLead'),
            lambda d: op(d).update(requestBody={'required': False}),
            lambda d: op(d)['parameters'].pop(),
            lambda d: op(d)['parameters'].append({'name': 'tenantId', 'in': 'query'}),
            lambda d: op(d)['responses']['200']['headers'].clear(),
            lambda d: op(d)['responses'].pop('403'),
            lambda d: d['paths'][PATH].update(post=copy.deepcopy(op(d))),
            lambda d: sources(d)['properties']['sources'].update(maxItems=51),
            lambda d: sources(d)['properties']['sources'].pop('maxItems'),
            lambda d: sources(d).update(additionalProperties=0),
            lambda d: item(d).update(additionalProperties=True),
            lambda d: item(d)['required'].pop(),
            lambda d: item(d)['properties']['displayName'].update({'$ref': '#/components/schemas/SafeText500'}),
            lambda d: item(d)['properties']['sourceAccountCode'].update({'$ref': '#/components/schemas/SafeText200'}),
            lambda d: item(d)['properties']['sourceChannelCode'].update({'$ref': '#/components/schemas/SafeText200'}),
            lambda d: item(d)['properties']['sourceAccountCode'].update(pattern='.*'),
            lambda d: item(d)['properties'].update(leadId={'type': 'string'}),
            lambda d: d['paths']['/api/v1/leads']['post'].update(security=[]),
            lambda d: d['components']['schemas']['Code64'].update(pattern='.*'),
            lambda d: d['components']['headers']['IdentityNoStore']['schema'].update(const='public'),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                altered = copy.deepcopy(self.document)
                mutate(altered)
                self.assert_guards(altered, False)

    def test_whole_development_gate_preserves_seven_nonfatal_readiness_blockers(self):
        from scripts.baseline.verify_baseline import main
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = main(['--r2-development', str(Path(__file__).resolve().parents[3])])
        self.assertEqual(0, status, output.getvalue())
        lines = output.getvalue().splitlines()
        blockers = [line for line in lines if line.startswith('R2 readiness blocker (non-fatal):')]
        self.assertEqual(7, len(blockers), output.getvalue())
        expected = ['R1-OPENAPI', 'R1-BACKEND', 'R1-SPA', 'R1-E2E-GOLDEN', 'R1-E2E-FAILURES',
                    'DB-52P2-PG18-RUNTIME', 'BASE-CURRENT-MVP']
        for name, blocker in zip(expected, blockers):
            self.assertIn(name, blocker)
        self.assertEqual([
            'R2 development admission: PASS; R1 acceptance: PAUSED; R2 release acceptance: NOT_GRANTED',
            'baseline consistency: PASS; R2 readiness: BLOCKED (7 non-fatal blockers)',
        ], lines[7:])

    def test_each_remaining_r1_inventory_guard_rejects_malformed_additions(self):
        from unittest.mock import patch
        from scripts.baseline import r1_business_closure_contract as closure
        from scripts.baseline import r1_projection_readiness_contract as readiness
        root = Path(__file__).resolve().parents[3]
        for validator in (closure, readiness):
            # This test uses the current R2 worktree; frozen defaults are tested separately.
            options = {"allow_r2_schema": True} if validator is closure else {}
            self.assertEqual([], validator.validate(root, **options))
            for mutation in (
                lambda d: d['paths'][PATH]['get'].update(security=[]),
                lambda d: d['components']['schemas']['LeadIntakeSourcesV1']['properties']['sources'].update(maxItems=51),
                lambda d: d['components']['schemas'].pop('LeadIntakeSourceV1'),
            ):
                with self.subTest(validator=validator.__name__, mutation=mutation):
                    altered = copy.deepcopy(self.document)
                    mutation(altered)
                    source = yaml.safe_dump(altered)
                    original_read = validator._read
                    def read(project, relative, findings):
                        return source if relative == identity.API else original_read(project, relative, findings)
                    with patch.object(validator, '_read', side_effect=read):
                        self.assertTrue(any('R2 intake source transport is invalid' in finding
                                            for finding in validator.validate(root, **options)))
