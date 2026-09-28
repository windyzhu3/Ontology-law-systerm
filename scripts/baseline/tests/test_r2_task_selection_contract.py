import copy
import unittest
from pathlib import Path
import yaml

from scripts.baseline import task9_identity_contract as identity
from scripts.baseline.r1_receipt_recovery_contract import _validate_transport
from scripts.baseline.r2_task_selection_contract import r1_transport_projection


class R2TaskSelectionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = yaml.safe_load((Path(__file__).resolve().parents[3] / identity.API).read_text(encoding='utf-8'))

    def test_exact_named_successor_preserves_both_r1_guards(self):
        self.assertEqual([], identity.validate_document(copy.deepcopy(self.document)))
        self.assertEqual((), _validate_transport(yaml.safe_dump(self.document, allow_unicode=True)))

    def test_historical_r1_document_still_passes_without_any_additions(self):
        original = r1_transport_projection(self.document)
        self.assertEqual([], identity.validate_document(copy.deepcopy(original)))
        self.assertEqual((), _validate_transport(yaml.safe_dump(original, allow_unicode=True)))

    def test_addition_and_existing_security_mutations_are_rejected(self):
        def component(doc):
            return doc['components']
        mutations = [
            lambda d: component(d)['parameters']['SelectedTaskId'].update(required=True),
            lambda d: component(d)['parameters']['SelectedTaskId'].update(required=0),
            lambda d: component(d)['parameters']['SelectedTaskId']['schema'].update(type='integer'),
            lambda d: component(d)['schemas']['CurrentWorkCardEnvelope']['properties']['myTasks'].update(maxItems=99),
            lambda d: component(d)['schemas']['CurrentWorkCardEnvelope']['properties'].pop('recommendedTaskId'),
            lambda d: component(d)['schemas']['CurrentWorkCardEnvelope']['required'].append('myTasks'),
            lambda d: component(d)['schemas']['NextSummary']['properties']['subjectTitle'].update({'$ref': '#/components/schemas/SafeText500'}),
            lambda d: d['paths']['/api/v1/workcards/current']['get'].update(security=[]),
            lambda d: d['paths']['/api/v1/leads']['post'].update(security=[]),
            lambda d: component(d)['schemas']['CaptureLeadV1']['properties']['phone'].update(maxLength=64),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                altered = copy.deepcopy(self.document)
                mutate(altered)
                self.assertTrue(identity.validate_document(copy.deepcopy(altered)))
                self.assertTrue(_validate_transport(yaml.safe_dump(altered, allow_unicode=True)))
