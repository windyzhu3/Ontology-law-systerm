import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class ContractPreparationTest(unittest.TestCase):
    def test_direct_source_facts_are_additive_encrypted_and_immutable(self):
        evolution=next((e for e in EVOLUTIONS if e.version==970),None)
        self.assertIsNotNone(evolution,'T08 direct preparation source evolution required')
        before=BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version<970: before=e.apply(before)
        after=evolution.apply(before)
        old={t.schema+'.'+t.name:t for s in before for t in s.tables}
        new={t.schema+'.'+t.name:t for s in after for t in s.tables}
        for name,table in old.items():self.assertEqual(table,new[name])
        self.assertEqual({'contract.preparation_request','contract.preparation_decision'},set(new)-set(old))
        sql=evolution.render_sql(before,after)
        for text in ['commercial_digest','customer_confirmation_id','previous_request_id','body_ciphertext','APPROVED','RETURNED','FOR UPDATE','fn_reject_fact_mutation','request predecessor differs','request basis changed','decision source changed','52-plus-2-r2-v11']:
            self.assertIn(text,sql)
        self.assertNotIn('DROP NOT NULL',sql)
        self.assertNotIn('GRANT UPDATE',sql)
        self.assertNotIn('INSERT INTO opportunity.quote_response',sql)

    def test_request_root_is_physically_unique_even_outside_read_committed(self):
        e=next(e for e in EVOLUTIONS if e.version==970)
        before=BASE_SCHEMAS
        for old in EVOLUTIONS:
            if old.version<970:before=old.apply(before)
        after=e.apply(before)
        request=next(t for s in after for t in s.tables if s.name=='contract' and t.name=='preparation_request')
        self.assertTrue(any(i.unique and i.columns==('tenant_id','opportunity_id') and i.where=='previous_request_id IS NULL' for i in request.indexes))
        self.assertIn('CREATE UNIQUE INDEX uq_preparation_request__root',e.render_sql(before,after))

if __name__=='__main__':unittest.main()
