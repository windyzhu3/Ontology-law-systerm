import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class ClosureContractTest(unittest.TestCase):
    def test_no_task_closure_is_immutable_and_cancel_basis_is_named(self):
        from contract.reference_registry import TYPED_REFERENCE_ALLOWED_TARGETS
        self.assertIn('opportunity.closure',TYPED_REFERENCE_ALLOWED_TARGETS['identity.object_access_grant.object_subject'])
        self.assertIn(910, [e.version for e in EVOLUTIONS])
        before = BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version < 910: before = e.apply(before)
        e = next(e for e in EVOLUTIONS if e.version == 910)
        after = e.apply(before)
        tables = {t.name:t for s in after for t in s.tables}
        closure = tables['closure']
        self.assertEqual((), closure.mutable_columns)
        self.assertTrue(next(c for c in closure.columns if c.name=='task_occurrence_id').nullable)
        self.assertFalse(next(c for c in tables['decision_record'].columns if c.name=='task_occurrence_id').nullable)
        sql=e.render_sql(before,after)
        self.assertIn('R2_OPPORTUNITY_CLOSE_V1',sql)
        self.assertIn('opportunity.closure',sql)
        self.assertIn('fn_reject_fact_mutation()',sql)
        self.assertIn('closure_summary_ciphertext',sql)
        self.assertIn("schema_contract_version='52-plus-2-r2-v5'",sql)
        self.assertNotIn('TO ${app_worker_role};',sql)
