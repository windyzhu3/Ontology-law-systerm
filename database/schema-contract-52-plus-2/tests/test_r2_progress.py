import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class R2ProgressContractTest(unittest.TestCase):
    def test_progress_body_and_successor_are_immutable_named_additions(self):
        schemas=BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version<880: schemas=e.apply(schemas)
        evolution=next(e for e in EVOLUTIONS if e.version==880)
        old={f'{s.name}.{t.name}':t for s in schemas for t in s.tables}
        new={f'{s.name}.{t.name}':t for s in evolution.apply(schemas) for t in s.tables}
        self.assertEqual(old.keys(),new.keys())
        for name in old.keys()-{'opportunity.opportunity_progress','responsibility.task_occurrence'}:
            self.assertEqual(old[name],new[name])
        progress=new['opportunity.opportunity_progress']
        self.assertEqual('progress_body_ciphertext',progress.columns[-1].name)
        self.assertTrue(progress.columns[-1].nullable)
        self.assertEqual('IMMUTABLE',progress.update_policy)
        task=new['responsibility.task_occurrence']
        self.assertEqual('predecessor_task_occurrence_id',task.columns[-1].name)
        self.assertNotIn('predecessor_task_occurrence_id',task.mutable_columns)
        self.assertTrue(any(c.kind=='UNIQUE' and 'predecessor_task_occurrence_id' in c.expression for c in task.constraints))
        self.assertTrue(any(f.columns==('tenant_id','predecessor_task_occurrence_id') for f in task.foreign_keys))
        self.assertEqual('52-plus-2-r2-v2',evolution.contract_version)
