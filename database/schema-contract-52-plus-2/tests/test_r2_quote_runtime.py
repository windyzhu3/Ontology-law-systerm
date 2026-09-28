import hashlib
import unittest
from pathlib import Path
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS


class QuoteRuntimeContractTest(unittest.TestCase):
    def setUp(self):
        self.e = next((e for e in EVOLUTIONS if e.version == 950), None)
        self.assertIsNotNone(self.e, 'T07 runtime must have a named V950 successor')
        self.before = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version < 950:
                self.before = evolution.apply(self.before)
        self.after = self.e.apply(self.before)
        self.sql = self.e.render_sql(self.before, self.after)

    def test_retains_all_original_tables_and_quote_guards(self):
        old = {t.schema+'.'+t.name: t for s in self.before for t in s.tables}
        new = {t.schema+'.'+t.name: t for s in self.after for t in s.tables}
        for name, table in old.items():
            if name not in {"responsibility.task_occurrence", "responsibility.wait_receipt"}:
                self.assertEqual(table, new[name], name)
        self.assertEqual(9, len(new)-len(old))
        self.assertNotIn('DROP TRIGGER', self.sql)
        self.assertNotIn('DISABLE TRIGGER', self.sql)
        self.assertEqual('52-plus-2-r2-v9', self.e.contract_version)

    def test_manual_delivery_and_approval_have_exact_immutable_sources(self):
        for expected in ('quote_approval_policy_signer', 'quote_approval_member', 'material_version_id',
                         'quote_manual_delivery', 'contract_preparation_source', 'FOR UPDATE',
                         'fn_reject_fact_mutation', 'V950 requires 52-plus-2-r2-v8'):
            self.assertIn(expected, self.sql)
        self.assertNotIn('GRANT SELECT, INSERT ON opportunity.quote_approval_policy ', self.sql)
        self.assertNotIn('GRANT SELECT, INSERT ON opportunity.quote_approval_policy_signer ', self.sql)

    def test_v940_is_byte_for_byte_frozen(self):
        path=Path(__file__).resolve().parents[1]/'generated/db/migration/V940__r2_quotes.sql'
        self.assertEqual('f8bc721fa9df8a9c3383e2e56427fbe0b2c743c2898c24edfe4e78ad15533650', hashlib.sha256(path.read_bytes()).hexdigest())

    def test_closure_retains_downstream_guards_and_only_allows_disposition_or_expired_unreplied(self):
        self.assertIn('CREATE OR REPLACE FUNCTION opportunity.fn_check_closure()',self.sql)
        for text in ("w.stage='SALES_DISPOSITION'", "w.stage IN ('DELIVER','AWAIT_REPLY')", "r.response_code='ACCEPTED'", 'contract.contract', 'transfer.transfer_request', 'closure cancellation differs'):
            self.assertIn(text,self.sql)

    def test_quote_handoff_wait_has_exact_causal_ref_and_preserved_guard(self):
        self.assertIn("R2_QUOTE_HANDOFF_WAIT_V1", self.sql)
        self.assertIn("awaited_fact_type='opportunity.quote_response'", self.sql)
        self.assertIn("CREATE OR REPLACE FUNCTION responsibility.fn_guard_r2_handoff_task_initial()", self.sql)
        self.assertIn("CREATE OR REPLACE FUNCTION opportunity.fn_check_responsibility_handoff()", self.sql)
        self.assertIn("handoff successor purpose differs", self.sql)

    def test_approval_policy_org_is_bound_to_quote_creator_not_supervisor_requester(self):
        guard=self.sql.split("IF TG_TABLE_NAME='quote_approval_request' THEN",1)[1].split("END IF;",1)[0]
        self.assertTrue('a.appointment_id=q.created_by_appointment_id' in guard)
        self.assertNotIn('a.appointment_id=NEW.requested_by',guard)
        self.assertIn('a.organization_unit_id=p.organization_unit_id',guard)
        self.assertIn('p.policy_code<>NEW.policy_code OR p.policy_version<>NEW.policy_version',guard)
