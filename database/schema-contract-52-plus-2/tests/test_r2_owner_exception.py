import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class OwnerExceptionContractTest(unittest.TestCase):
    def test_registered_history_and_narrow_capabilities(self):
        self.assertIn(900, [e.version for e in EVOLUTIONS])
        old = BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version < 900: old = e.apply(old)
        e = next(e for e in EVOLUTIONS if e.version == 900)
        after = e.apply(old)
        tables = {t.name:t for s in after for t in s.tables}
        self.assertEqual(57, len(tables)) # Flyway bootstrap is the 58th physical table
        self.assertEqual(('tenant_id','owner_exception_id','revision'), tables['owner_exception'].primary_key)
        self.assertEqual(('is_current',), tables['owner_exception'].mutable_columns)
        for name in ('owner_exception','owner_exception_disposition','responsibility_handoff'):
            self.assertTrue(all(f.columns[0] == 'tenant_id' for f in tables[name].foreign_keys))
        sql = e.render_sql(old, after)
        self.assertIn('9007199254740991', sql)
        self.assertIn('WHERE is_current AND state IN', sql)
        self.assertIn('fn_reject_fact_mutation()', sql)
        self.assertIn('GRANT UPDATE (is_current)', sql)
        self.assertIn('OWNER_EXCEPTION', sql)
        self.assertNotIn('TO ${app_worker_role};', sql)
        self.assertIn('DEFERRABLE INITIALLY DEFERRED', sql)

if __name__ == '__main__': unittest.main()
