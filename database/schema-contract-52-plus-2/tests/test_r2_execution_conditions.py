import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class ExecutionConditionsSchemaTest(unittest.TestCase):
 def test_execution_responsibility_is_durable_and_separate_from_execution_fact(self):
  evolution=next((e for e in EVOLUTIONS if e.version==1040),None)
  self.assertIsNotNone(evolution)
  before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<1040:before=e.apply(before)
  after=evolution.apply(before)
  tables={t.schema+'.'+t.name:t for s in after for t in s.tables}
  for name in ('execution_workflow','execution_verification'):
   self.assertEqual('IMMUTABLE',tables['contract.'+name].update_policy)
  workflow=tables['contract.execution_workflow']
  self.assertTrue(any(i.unique and i.columns==('tenant_id','handoff_id') for i in workflow.indexes))
  sql=evolution.render_sql(before,after)
  for word in ('original execution deadline changed','execution handoff differs','fn_reject_fact_mutation','OWNER_EXCEPTION'):
   self.assertIn(word,sql)
  self.assertNotIn('INSERT INTO contract.contract_execution',sql)

 def test_finance_requests_reviews_and_responsibilities_are_separate_immutable_facts(self):
  before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<1040:before=e.apply(before)
  evolution=next(e for e in EVOLUTIONS if e.version==1040)
  after=evolution.apply(before)
  tables={t.schema+'.'+t.name:t for s in after for t in s.tables}
  for name in ('payment_request','payment_review','payment_workflow'):
   self.assertEqual('IMMUTABLE',tables['contract.'+name].update_policy)
  sql=evolution.render_sql(before,after)
  for fragment in ('payment review confirmation differs','payment original deadline changed','payment workflow predecessor differs','payment evidence differs'):
   self.assertIn(fragment,sql)
