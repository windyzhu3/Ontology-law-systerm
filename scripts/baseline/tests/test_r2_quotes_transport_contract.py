from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_quotes_transport_contract import quotes_projection, PATH_DIGESTS, SCHEMA_DIGESTS, CHANGED_DIGESTS, PREVIOUS_SCHEMAS, digest
from scripts.baseline.r2_contracts_transport_contract import contracts_projection

class QuoteTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document=yaml.safe_load((Path(__file__).resolve().parents[3]/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'))

    def test_exact_t07_rollback_is_nonmutating_and_idempotent(self):
        original=deepcopy(self.document)
        expected=contracts_projection(original)
        for key in PATH_DIGESTS: expected['paths'].pop(key)
        for key in SCHEMA_DIGESTS: expected['components']['schemas'].pop(key)
        expected['components']['schemas'].update(deepcopy(PREVIOUS_SCHEMAS))
        actual=quotes_projection(original)
        self.assertEqual(expected,actual)
        self.assertEqual(actual,quotes_projection(actual))
        self.assertEqual(original,self.document)

    def test_current_runtime_context_includes_records_history_and_nullable_task_state(self):
        context=self.document['components']['schemas']['QuoteContextV1']
        self.assertIn('records',context['required'])
        workflow=context['properties']['workflow']['oneOf'][0]['properties']
        self.assertEqual(['string','null'],workflow['state']['type'])
        self.assertIn('ownerLabel',workflow)
        self.assertIn('dueAt',workflow)
        history=context['properties']['history']['items']
        self.assertEqual({'selector','version','document','totalMinor','validUntil'},set(history['required']))
        self.assertNotIn('createdAt',history['required'])
        self.assertEqual(3,len(context['properties']['records']['items']['oneOf']))

    def test_every_missing_or_changed_t07_fragment_rejects_drift(self):
        for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS),('schemas',CHANGED_DIGESTS)]:
            for key in pins:
                for remove in (False,True):
                    with self.subTest(section=section,key=key,remove=remove):
                        changed=deepcopy(self.document)
                        target=changed['paths'] if section=='paths' else changed['components']['schemas']
                        if remove: target.pop(key)
                        else: target[key]['unreviewed']=True
                        with self.assertRaises((ValueError,KeyError)):quotes_projection(changed)

    def test_unrelated_history_survives_projection_unchanged(self):
        changed=deepcopy(self.document)
        changed['paths']['/unreviewed-route']={'get':{}}
        changed['components']['schemas']['UnreviewedSchema']={'type':'string'}
        projected=quotes_projection(changed)
        self.assertEqual({'get':{}},projected['paths']['/unreviewed-route'])
        self.assertEqual({'type':'string'},projected['components']['schemas']['UnreviewedSchema'])

if __name__=='__main__':unittest.main()
