import unittest, hashlib
from pathlib import Path
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
class QuoteTransactionContractTest(unittest.TestCase):
    def test_explicit_transaction_markers_are_additive_and_immutable(self):
        schemas=BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version<960: schemas=e.apply(schemas)
        e=next((e for e in EVOLUTIONS if e.version==960),None)
        self.assertIsNotNone(e)
        after=e.apply(schemas);old={t.name:t for s in schemas for t in s.tables}
        targets={'quote_approval_decision','quote_issue','quote_response'}
        for s in after:
            for t in s.tables:
                if t.name in targets:
                    self.assertEqual(old[t.name].columns,t.columns[:-1])
                    c=t.columns[-1]
                    self.assertEqual('created_in_transaction',c.name)
                    self.assertEqual('xid8',c.sql_type)
                    self.assertFalse(c.nullable)
                    self.assertEqual('pg_current_xact_id()',c.default)
                    self.assertEqual(old[t.name].update_policy,t.update_policy)
                    self.assertNotIn(c.name,t.mutable_columns)
                else:self.assertEqual(old[t.name],t)
        sql=e.render_sql(schemas,after)
        self.assertEqual(3,sql.count('ADD COLUMN created_in_transaction'))
        self.assertNotIn('DROP TRIGGER',sql)
        self.assertIn('NEW.created_in_transaction IS DISTINCT FROM pg_current_xact_id()',sql)
        self.assertIn('NEW.created_in_transaction IS DISTINCT FROM OLD.created_in_transaction',sql)
        self.assertEqual(3,sql.count('BEFORE INSERT OR UPDATE'))
        self.assertNotIn('UPDATE opportunity.',sql)
        self.assertEqual('52-plus-2-r2-v10',e.contract_version)
    def test_v950_is_frozen(self):
        p=Path(__file__).resolve().parents[1]/'generated/db/migration/V950__r2_quote_runtime.sql'
        self.assertEqual('d466d2e4f1a2c0119a4989708cc9eb61d8e21a5f31acfb6c8cddac8fffbd5b99',hashlib.sha256(p.read_bytes()).hexdigest())
