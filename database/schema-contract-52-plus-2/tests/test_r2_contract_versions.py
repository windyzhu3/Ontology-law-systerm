import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS

class ContractVersionEvolutionTest(unittest.TestCase):
    def setUp(self):
        self.e=next((e for e in EVOLUTIONS if e.version==980),None)
        self.assertIsNotNone(self.e,'T08 preparation versions must extend existing contract truth')
        self.before=BASE_SCHEMAS
        for e in EVOLUTIONS:
            if e.version<980:self.before=e.apply(self.before)
        self.after=self.e.apply(self.before)
        self.tables={t.schema+'.'+t.name:t for s in self.after for t in s.tables}
        self.sql=self.e.render_sql(self.before,self.after)
    def test_dual_source_has_exclusive_origin_and_unique_consumption(self):
        t=self.tables['contract.contract']
        self.assertTrue(next(c for c in t.columns if c.name=='accepted_quote_response_id').nullable)
        self.assertIn('direct_preparation_decision_id',[c.name for c in t.columns])
        self.assertIn('ck_contract__r2_source',self.sql)
        self.assertIn('uq_contract__direct_source',self.sql)
    def test_first_formation_can_use_new_authorization_without_rewriting_origin(self):
        self.assertNotIn('contract initial source differs',self.sql)
        self.assertIn('contract source already consumed by another contract',self.sql)
    def test_preparation_is_immutable_and_review_is_post_version(self):
        t=self.tables['contract.contract_revision']
        self.assertTrue(next(c for c in t.columns if c.name=='pre_contract_review_id').nullable)
        for name in ['preparation_draft','template_version','clause_version','revision_clause','revision_review_request','revision_review_decision','revision_review_binding','revision_approval_requirement','revision_approval_decision','signature_readiness']:
            table=self.tables['contract.'+name]
            self.assertEqual('IMMUTABLE',table.update_policy)
            self.assertIn('created_in_transaction',[c.name for c in table.columns])
        for term in ['R2_CONTRACT_PREPARATION_V1','legacy contract revision requires complete review','current contract approval incomplete','NEED_INFO','BLOCKED','READY_FOR_SIGNATURE','pg_current_xact_id()']:
            self.assertIn(term,self.sql)
    def test_no_template_seed_or_legacy_execution_replacement(self):
        self.assertNotIn('INSERT INTO contract.template_version',self.sql)
        self.assertNotIn('CREATE OR REPLACE FUNCTION platform_meta.fn_assert_contract_execution_package',self.sql)
        self.assertNotIn('DROP TRIGGER',self.sql)

    def test_preparation_readiness_cannot_execute_with_zero_legacy_signature_plans(self):
        self.assertIn('contract.fn_reject_r2_preparation_execution()',self.sql)
        self.assertIn('R2 preparation requires a separately activated signing protocol',self.sql)
        self.assertIn('BEFORE INSERT ON contract.contract_execution',self.sql)
    def test_exact_references_and_ciphertext_are_registered(self):
        checkpoint=self.tables['platform_meta.r2_opportunity_checkpoint']
        self.assertEqual('varchar(32)',next(c.sql_type for c in checkpoint.columns if c.name=='scan_kind'))
        self.assertIn("'CONTRACT_PREPARATION'",next(c.expression for c in checkpoint.constraints if c.name=='ck_r2_opportunity_checkpoint__kind'))
        self.assertIn('package_ciphertext',[c.name for c in self.tables['contract.contract_revision'].columns])
        for table in self.tables.values():
            if table.schema=='contract':
                for fk in table.foreign_keys:self.assertEqual('tenant_id',fk.columns[0])

    def test_review_supplement_and_workflow_are_single_root_chains(self):
        for name in ('body_ciphertext','body_digest'):
            self.assertFalse(next(c for c in self.tables['contract.revision_review_request'].columns if c.name==name).nullable)
        self.assertFalse(next(c for c in self.tables['contract.preparation_workflow'].columns if c.name=='created_by_appointment_id').nullable)
        for name, owner, predecessor in [('revision_review_request','contract_revision_id','previous_request_id'),('preparation_workflow','opportunity_id','previous_workflow_id'),('preparation_draft','opportunity_id','previous_draft_id')]:
            t=self.tables['contract.'+name]
            self.assertTrue(any(i.unique and i.columns==('tenant_id',owner) and i.where==predecessor+' IS NULL' for i in t.indexes))
            self.assertTrue(any(c.kind=='UNIQUE' and c.expression=='tenant_id, '+predecessor for c in t.constraints))
        self.assertIn("d.decision_code='NEED_INFO'",self.sql)
        self.assertIn('contract review request superseded',self.sql)

    def test_approval_set_and_document_collection_are_sealed(self):
        self.assertIn('contract preparation participants differ',self.sql)
        self.assertIn('contract preparation participants sealed',self.sql)
        self.assertIn('contract version collection sealed',self.sql)
        self.assertIn('contract policy requirements incomplete',self.sql)
        self.assertIn('contract approval organization differs',self.sql)
        self.assertIn('contract sealed requirements incomplete',self.sql)
        self.assertIn('contract readiness requires current approval pointer',self.sql)
        self.assertIn("mode='REQUIRE_APPROVAL'",self.sql)
        root=self.tables['contract.contract']
        check=next(c.expression for c in root.constraints if c.name=='ck_contract__r2_source')
        self.assertIn("preparation_contract_code IS NOT NULL AND preparation_contract_code='R2_CONTRACT_PREPARATION_V1'",check)
        self.assertIn("R2_CONTRACT_REVIEW_CLEAR_V1|",self.sql)
        self.assertNotIn('NEW.resolution_digest IS DISTINCT FROM NEW.body_digest',self.sql)

    def test_handoff_keeps_independent_reviewers_and_closure_sees_contract_tasks(self):
        task=self.tables['responsibility.task_occurrence']
        predicate=next(c.expression for c in task.constraints if c.name=='ck_task_occurrence__handoff_predecessor')
        for purpose in ('REQUEST_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','SUBMIT_CONTRACT_APPROVAL','SUPPLEMENT_CONTRACT_REVIEW'):
            self.assertIn("'"+purpose+"'",predicate)
        for purpose in ('DECIDE_CONTRACT_PREPARATION','REVIEW_CONTRACT','APPROVE_CONTRACT'):
            self.assertNotIn("'"+purpose+"'",predicate)
        closure=self.sql[self.sql.index('CREATE OR REPLACE FUNCTION opportunity.fn_check_closure()'):]
        for purpose in ('REQUEST_CONTRACT_PREPARATION','DECIDE_CONTRACT_PREPARATION','PREPARE_CONTRACT','SUBMIT_CONTRACT_REVIEW','REVIEW_CONTRACT','SUBMIT_CONTRACT_APPROVAL','APPROVE_CONTRACT','SUPPLEMENT_CONTRACT_REVIEW'):
            self.assertIn("'"+purpose+"'",closure)
    def test_latest_direct_request_can_be_returned_without_approving_changed_basis(self):
        self.assertIn('CREATE OR REPLACE FUNCTION contract.fn_check_preparation_decision()',self.sql)
        fn=self.sql[self.sql.index('CREATE OR REPLACE FUNCTION contract.fn_check_preparation_decision()'):]
        self.assertLess(fn.index("IF NEW.decision_code='RETURNED'"),fn.index('previous_confirmation_id=r.customer_confirmation_id'))
        self.assertIn('pending preparation request is not current',fn)
    def test_authority_recovery_resume_is_only_a_taskless_owner_exception(self):
        t=self.tables['contract.preparation_workflow']
        self.assertTrue(next(c for c in t.columns if c.name=='recovery_resume_stage').nullable)
        predicate=next(c.expression for c in t.constraints if c.name=='ck_preparation_workflow__recovery_resume')
        for term in ("stage_code='OWNER_EXCEPTION'","'DIRECT_RETURNED'","'RETURNED'",'task_id IS NULL','prior_task_id IS NOT NULL'):
            self.assertIn(term,predicate)
        self.assertIn('contract authority recovery basis differs',self.sql)
        self.assertIn("cancellation_reason_code='CONTRACT_AUTHORITY_MISSING'",self.sql)

if __name__=='__main__':unittest.main()
