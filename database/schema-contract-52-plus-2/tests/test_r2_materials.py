import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
class MaterialContractTest(unittest.TestCase):
 def setUp(self):
  self.e=next((e for e in EVOLUTIONS if e.version==930),None)
  self.assertIsNotNone(self.e,'T06 exact successor required')
  self.before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<930:self.before=e.apply(self.before)
  self.after=self.e.apply(self.before);self.sql=self.e.render_sql(self.before,self.after)
 def test_preserves_existing_evidence(self):
  old={t.schema+'.'+t.name:t for s in self.before for t in s.tables};new={t.schema+'.'+t.name:t for s in self.after for t in s.tables}
  for k,v in old.items():self.assertEqual(v,new[k])
  self.assertEqual(set(new)-set(old),{'evidence.material_upload_basis','evidence.material_upload_check','opportunity.material_version'})
 def test_guards_exact_basis_and_chain(self):
  for guard in ('material opportunity differs','material responsibility differs','material predecessor differs','material evidence chain differs','material check transition differs','20971520',"'application/pdf','image/jpeg','image/png'",'V930 requires 52-plus-2-r2-v6'):self.assertIn(guard,self.sql)
  self.assertEqual('52-plus-2-r2-v7',self.e.contract_version)
 def test_metadata_encrypted_no_current_pointer(self):
  for s in self.after:
   for t in s.tables:
    if t.name in ('material_upload_basis','material_version'):
     cols={c.name:c for c in t.columns};self.assertEqual('bytea',cols['body_ciphertext'].sql_type);self.assertNotIn('filename',cols);self.assertNotIn('current_version_id',cols)

 def test_optional_original_task_has_complete_selector(self):
  self.assertIn('original_task_id IS NOT NULL AND original_task_revision IS NOT NULL',self.sql)

 def test_check_serializes_on_mutable_session_without_basis_update_privilege(self):
  self.assertIn('FOR UPDATE OF u', self.sql)
  self.assertNotIn('material_upload_basis WHERE tenant_id=NEW.tenant_id AND material_upload_basis_id=NEW.upload_basis_id FOR UPDATE', self.sql)
  self.assertNotIn('GRANT UPDATE ON evidence.material_upload_basis', self.sql)
