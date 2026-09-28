import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
class QuoteContractTest(unittest.TestCase):
 def setUp(self):
  self.e=next((e for e in EVOLUTIONS if e.version==940),None)
  self.assertIsNotNone(self.e,'T07 protected quote successor required')
  before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<940:before=e.apply(before)
  self.before=before;self.after=self.e.apply(before);self.sql=self.e.render_sql(before,self.after)
 def test_preserves_existing_quote_truth(self):
  old={t.schema+'.'+t.name:t for s in self.before for t in s.tables};new={t.schema+'.'+t.name:t for s in self.after for t in s.tables}
  for k,v in old.items():self.assertEqual(v,new[k])
  self.assertEqual({'opportunity.quote_draft','opportunity.quote_package_basis'},set(new)-set(old))
 def test_encrypted_documents_and_immutable_exact_sources(self):
  for text in ['body_ciphertext','customer_confirmation_id','previous_draft_id','quote_revision_id','quote draft predecessor differs','quote customer confirmation differs','quote package differs','fn_reject_fact_mutation','FOR UPDATE','V940 requires 52-plus-2-r2-v7']:
   self.assertIn(text,self.sql)
  self.assertNotIn('GRANT UPDATE ON opportunity.quote_',self.sql)
