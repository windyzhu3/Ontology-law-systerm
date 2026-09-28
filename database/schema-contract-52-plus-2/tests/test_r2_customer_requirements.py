import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
from contract.reference_registry import TYPED_REFERENCE_ALLOWED_TARGETS


class CustomerRequirementsContractTest(unittest.TestCase):
    def setUp(self):
        self.before = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version < 920:
                self.before = evolution.apply(self.before)
        self.evolution = next(e for e in EVOLUTIONS if e.version == 920)
        self.after = self.evolution.apply(self.before)
        self.sql = self.evolution.render_sql(self.before, self.after)
        self.tables = {t.schema+'.'+t.name:t for s in self.after for t in s.tables}

    def test_existing_identity_and_history_are_unchanged(self):
        for schema in self.before:
            for table in schema.tables:
                self.assertEqual(table, self.tables[schema.name+'.'+table.name])
        self.assertNotIn('UPDATE lead.', self.sql)
        self.assertNotIn('UPDATE responsibility.', self.sql)

    def test_draft_is_independent_and_every_version_is_immutable(self):
        names = ('party.profile_version','opportunity.customer_requirement_draft',
                 'opportunity.customer_requirement_confirmation','opportunity.customer_requirement_participant',
                 'opportunity.customer_requirement_draft_party')
        for name in names:
            table = self.tables[name]
            self.assertEqual('IMMUTABLE', table.update_policy)
            self.assertEqual((), table.mutable_columns)
            self.assertIn('BEFORE UPDATE OR DELETE ON '+name, self.sql)
            self.assertIn('GRANT SELECT, INSERT ON '+name+' TO ${app_command_role}', self.sql)
            for slot in ('audit.audit_entry.subject','execution.command_receipt.result_fact','execution.domain_event.source_fact'):
                self.assertIn(name,TYPED_REFERENCE_ALLOWED_TARGETS[slot])
        draft = self.tables[names[1]]
        self.assertNotIn('task_occurrence_id', [c.name for c in draft.columns])
        self.assertNotIn('TO ${app_worker_role};', self.sql)
        self.assertNotIn('GRANT UPDATE', self.sql)

    def test_confirmation_is_complete_current_and_frozen(self):
        for guard in ('FOR UPDATE','o.closed_at IS NOT NULL','requirement handoff differs',
                      'draft predecessor differs','draft initial version exists',
                      'confirmation initial version exists','confirmation draft superseded',
                      'confirmation draft differs','confirmation requires client',
                      'participant profile differs','participant party changed',
                      'participant set already frozen','draft party set already frozen','draft party changed','created_in_transaction=pg_current_xact_id()',
                      'DEFERRABLE INITIALLY DEFERRED'):
            self.assertIn(guard, self.sql)
        self.assertEqual('52-plus-2-r2-v6',self.evolution.contract_version)

    def test_source_set_stamp_uses_top_level_transaction_across_savepoints(self):
        self.assertNotIn('.xmin', self.sql)
        self.assertIn('NEW.created_in_transaction := pg_current_xact_id()', self.sql)
        for name in ('customer_requirement_draft','customer_requirement_confirmation'):
            stamp=next(c for c in self.tables['opportunity.'+name].columns if c.name=='created_in_transaction')
            self.assertEqual('xid8',stamp.sql_type)
            self.assertEqual('pg_current_xact_id()',stamp.default)
            self.assertFalse(stamp.nullable)

    def test_sensitive_requirements_have_no_plaintext_columns(self):
        for name in ('customer_requirement_draft','customer_requirement_confirmation'):
            columns = {c.name:c for c in self.tables['opportunity.'+name].columns}
            self.assertEqual('bytea', columns['body_ciphertext'].sql_type)
            self.assertIn('body_digest', columns)
            for field in ('phone','contact_name','matter_name','customer_goal','service_scope','known_constraints'):
                self.assertNotIn(field, columns)


if __name__ == '__main__':
    unittest.main()
