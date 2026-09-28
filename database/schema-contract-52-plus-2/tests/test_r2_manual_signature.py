import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
class ManualSignatureSchemaTest(unittest.TestCase):
 def setUp(self):
  self.e=next((e for e in EVOLUTIONS if e.version==990),None)
  self.assertIsNotNone(self.e,'manual signing needs a named V990 successor')
  self.before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<990:self.before=e.apply(self.before)
  self.after=self.e.apply(self.before)
  self.tables={t.schema+'.'+t.name:t for s in self.after for t in s.tables}
  self.sql=self.e.render_sql(self.before,self.after)
 def test_signed_facts_reuse_existing_plan_and_signature(self):
  for table,column in [('signature_plan','arrangement_id'),('contract_signature','verification_id')]:
   self.assertIn(column,[c.name for c in self.tables['contract.'+table].columns])
  for name in ('signature_arrangement','signature_draft','signature_submission','signature_verification','signature_archive','signature_revision_return','signature_workflow','signature_handoff'):
   self.assertEqual('IMMUTABLE',self.tables['contract.'+name].update_policy)
 def test_frozen_participation_is_unchanged(self):
  old=next(t for s in self.before for t in s.tables if s.name=='contract' and t.name=='contract_participation')
  self.assertEqual(old,self.tables['contract.contract_participation'])
  self.assertNotIn('UPDATE contract.contract_participation',self.sql)
  self.assertNotIn('CREATE OR REPLACE FUNCTION contract.fn_check_r2_participation',self.sql)
 def test_serial_chains_and_terminal_handoff(self):
  for name,owner,prev in [('signature_workflow','readiness_id','previous_workflow_id'),('signature_arrangement','readiness_id','previous_arrangement_id'),('signature_draft','readiness_id','previous_draft_id'),('signature_submission','signature_plan_id','previous_submission_id')]:
   t=self.tables['contract.'+name]
   self.assertTrue(any(i.unique and i.columns==('tenant_id',owner) and i.where==prev+' IS NULL' for i in t.indexes))
   self.assertTrue(any(c.kind=='UNIQUE' and c.expression=='tenant_id, '+prev for c in t.constraints))
  self.assertIn('AWAITING_EXECUTION_CONDITIONS',self.sql)
  self.assertNotIn('INSERT INTO contract.contract_execution',self.sql)
 def test_database_validates_exact_basis_and_materials(self):
  for term in ('manual signature readiness is not current','manual signature arrangement superseded','manual signature collection sealed','manual signature verification basis differs','manual signature archive incomplete','manual signature handoff differs','contract.fn_assert_r2_material'):
   self.assertIn(term,self.sql)
 def test_complete_sets_and_workflow_links_are_enforced(self):
  for term in ('manual signature arrangement set incomplete','manual signature verified fact missing','manual signature workflow task differs','manual signature draft predecessor differs','ARRANGE_CONTRACT_SIGNATURE','VERIFY_CONTRACT_SIGNATURE'):
   self.assertIn(term,self.sql)
 def test_archive_rechecks_evidence_and_handoff_seals_atomically(self):
  self.assertIn('FOR proof IN SELECT',self.sql)
  self.assertIn('proof.authority_material_sha256',self.sql)
  self.assertIn('archive.created_in_transaction<>pg_current_xact_id()',self.sql)
 def test_nonpassing_revision_return_can_close_stale_readiness(self):
  self.assertIn('passing boolean DEFAULT true',self.sql)
  self.assertIn('IF NOT passing THEN RETURN; END IF;',self.sql)
  self.assertIn("passing=NEW.decision_code<>'REVISION_REQUIRED'",self.sql)
  self.assertIn("IF NEW.decision_code='VERIFIED' THEN",self.sql)
 def test_named_r2_versions_cannot_bypass_manual_arrangement(self):
  self.assertIn('R2 signature plan requires manual arrangement',self.sql)
 def test_firm_binding_is_sealed_with_approved_template_and_exact_profile(self):
  self.assertIn('contract.template_signing_party',self.tables)
  self.assertEqual('IMMUTABLE',self.tables['contract.template_signing_party'].update_policy)
  self.assertIn('template_signing_party_id',[c.name for c in self.tables['contract.signature_plan'].columns])
  for term in ('manual template signer binding sealed','manual template signer profile differs','manual signature template signer differs'):
   self.assertIn(term,self.sql)
if __name__=='__main__':unittest.main()
