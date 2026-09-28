import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class TransferWorkflowSchemaTest(unittest.TestCase):
 def test_pending_submission_and_responsibility_are_immutable_and_separate_from_accepted_snapshot(self):
  evolution=next((e for e in EVOLUTIONS if e.version==1050),None)
  self.assertIsNotNone(evolution)
  before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   if e.version<1050:before=e.apply(before)
  after=evolution.apply(before)
  tables={t.schema+'.'+t.name:t for s in after for t in s.tables}
  for name in ('submission','workflow'):
   self.assertEqual('IMMUTABLE',tables['transfer.'+name].update_policy)
  self.assertTrue(any(i.unique and i.columns==('tenant_id','transfer_request_id') for i in tables['transfer.workflow'].indexes))
  submission=tables['transfer.submission']
  self.assertTrue(any(f.parent_table=='action_draft' for f in submission.foreign_keys))
  self.assertTrue(any(f.parent_table=='customer_requirement_confirmation' for f in submission.foreign_keys))
  self.assertFalse(any(c.name=='pre_transfer_review_id' for c in submission.columns))
  sql=evolution.render_sql(before,after)
  for message in ('transfer original deadline changed','transfer submission exact draft required','transfer material basis differs','transfer submission completion differs'):
   self.assertIn(message,sql)
  self.assertNotIn('INSERT INTO conflict.conflict_review',sql)
  self.assertNotIn('UPDATE transfer.transfer_request SET matter_id',sql)
 def test_submission_can_be_resolved_as_exact_completion_and_receipt_fact(self):
  from contract.reference_registry import TYPED_REFERENCE_ALLOWED_TARGETS
  for slot in ('responsibility.task_occurrence.completion_fact','execution.command_receipt.result_fact','execution.domain_event.source_fact','audit.audit_entry.subject'):
   self.assertIn('transfer.submission',TYPED_REFERENCE_ALLOWED_TARGETS[slot])

 def test_independent_review_is_a_distinct_immutable_task_completion(self):
  before=BASE_SCHEMAS
  for e in EVOLUTIONS:
   before=e.apply(before)
  tables={t.schema+'.'+t.name:t for s in before for t in s.tables}
  self.assertIn('transfer.review',tables)
  self.assertEqual('IMMUTABLE',tables['transfer.review'].update_policy)
  self.assertTrue(any(f.parent_table=='conflict_review' for f in tables['transfer.review'].foreign_keys))

 def test_correction_keeps_the_exact_return_and_previous_submission(self):
  schemas=BASE_SCHEMAS
  for e in EVOLUTIONS:schemas=e.apply(schemas)
  tables={t.schema+'.'+t.name:t for s in schemas for t in s.tables}
  self.assertIn('transfer.review_return_item',tables)
  self.assertEqual('IMMUTABLE',tables['transfer.review_return_item'].update_policy)
  self.assertIn('previous_submission_id',[c.name for c in tables['transfer.submission'].columns])
  self.assertIn('previous_review_id',[c.name for c in tables['transfer.submission'].columns])

 def test_intake_is_an_immutable_link_to_the_actual_accepted_snapshot(self):
  schemas=BASE_SCHEMAS
  for e in EVOLUTIONS:schemas=e.apply(schemas)
  tables={t.schema+'.'+t.name:t for s in schemas for t in s.tables}
  self.assertIn('transfer.intake',tables)
  self.assertEqual('IMMUTABLE',tables['transfer.intake'].update_policy)
  self.assertTrue(any(f.parent_table=='transfer_snapshot' for f in tables['transfer.intake'].foreign_keys))
  self.assertTrue(any(f.parent_table=='decision_record' for f in tables['transfer.intake'].foreign_keys))

 def test_submission_freezes_evidence_including_corrections_for_intake(self):
  schemas=BASE_SCHEMAS
  for e in EVOLUTIONS:schemas=e.apply(schemas)
  tables={t.schema+'.'+t.name:t for s in schemas for t in s.tables}
  self.assertIn('evidence_submission_ids',[c.name for c in tables['transfer.submission'].columns])

 def test_classification_is_an_immutable_fact_after_intake(self):
  schemas=BASE_SCHEMAS
  for e in EVOLUTIONS:schemas=e.apply(schemas)
  tables={t.schema+'.'+t.name:t for s in schemas for t in s.tables}
  self.assertIn('transfer.classification',tables)
  self.assertEqual('IMMUTABLE',tables['transfer.classification'].update_policy)
  self.assertTrue(any(f.parent_table=='intake' for f in tables['transfer.classification'].foreign_keys))
