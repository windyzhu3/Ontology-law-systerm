import unittest


class FrozenDomainSemanticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from contract.schema_contract import SCHEMAS
        cls.tables = {
            f"{schema.name}.{table.name}": table
            for schema in SCHEMAS
            for table in schema.tables
        }

    def columns(self, qualified: str) -> set[str]:
        return {column.name for column in self.tables[qualified].columns}

    def constraint(self, qualified: str, name: str):
        return next(item for item in self.tables[qualified].constraints if item.name == name)

    def test_identity_uses_current_org_scope_and_principal_object_limit(self):
        self.assertIn("scope_organization_unit_id", self.columns("identity.authority_grant"))
        self.assertIn("scope_organization_unit_id", self.columns("identity.delegation_grant"))
        self.assertNotIn("scope_subject_type", self.columns("identity.authority_grant"))
        self.assertIn("grantee_principal_id", self.columns("identity.object_access_grant"))
        self.assertNotIn("grantee_appointment_id", self.columns("identity.object_access_grant"))

    def test_audit_freezes_scope_command_authorization_and_execution_context(self):
        required = {
            "audit_scope_code", "actor_principal_id", "actor_appointment_id",
            "on_behalf_of_principal_id", "on_behalf_of_appointment_id",
            "action_code", "command_id", "command_type", "correlation_id",
            "authorization_slot_code", "authorization_path_code",
            "authorization_scope_organization_unit_id",
            "authorization_snapshot_digest", "result_code", "trusted_at",
            "trace_id", "service_role_code", "execution_node_code",
            "change_summary",
        }
        self.assertTrue(required <= self.columns("audit.audit_entry"))

    def test_responsibility_decision_wait_and_draft_preserve_owner_boundaries(self):
        decision = self.columns("responsibility.decision_record")
        self.assertTrue({
            "authority_slot_code", "decision_contract_code",
            "decision_contract_version", "content_digest",
            "decision_subject_type", "decision_subject_id",
            "decision_subject_revision", "decision_subject_hash",
        } <= decision)
        wait = self.columns("responsibility.wait_receipt")
        self.assertTrue({
            "awaited_fact_type", "awaited_fact_id",
            "awaited_fact_revision", "awaited_fact_hash",
        } <= wait)
        draft = self.columns("responsibility.action_draft")
        self.assertIn("confirmed_payload_digest", draft)
        self.assertNotIn("confirmation_fact_type", draft)

    def test_execution_slot_event_and_external_action_freeze_exact_contracts(self):
        slot = self.columns("execution.command_execution_slot")
        self.assertTrue({"envelope_type", "command_scope_digest", "payload_digest"} <= slot)
        event = self.columns("execution.domain_event")
        self.assertTrue({"event_schema_version", "event_payload", "payload_digest", "command_id", "correlation_id"} <= event)
        action = self.columns("external_action.external_action")
        self.assertTrue({
            "subject_type", "subject_id", "subject_revision", "subject_hash",
            "action_contract_code", "action_contract_version", "request_envelope",
            "request_digest", "intent_key", "attempt_no", "provider_idempotency_key",
        } <= action)
        inbox = self.columns("external_action.provider_inbox")
        self.assertTrue({"nonce_digest", "message_schema_version", "normalized_message", "external_action_id"} <= inbox)

    def test_evidence_has_opaque_key_contract_and_finalized_promotion(self):
        session = self.columns("evidence.upload_session")
        self.assertTrue({
            "object_key", "intake_contract_code", "intake_contract_version",
            "intake_contract_digest", "finalized_at",
        } <= session)
        source = self.columns("evidence.received_source_object")
        self.assertTrue({"object_version", "server_sha256", "detected_media_type", "scan_result"} <= source)
        submission = self.columns("evidence.evidence_submission")
        self.assertTrue({"submitted_by_appointment_id", "submission_contract_code", "submission_contract_version"} <= submission)
        self.assertNotIn("subject_type", submission)
        binding = self.columns("evidence.evidence_binding")
        self.assertTrue({"bound_by_appointment_id", "revoked_by_appointment_id", "revocation_authorization_digest"} <= binding)

    def test_lead_captures_structured_protected_sales_intake(self):
        lead = self.columns("lead.lead")
        self.assertTrue({
            "source_account_code", "source_record_key_digest", "captured_name_ciphertext",
            "captured_phone_ciphertext", "captured_email_ciphertext", "city_code",
            "service_category_code", "jurisdiction_code", "urgency_code",
            "legal_need_summary_ciphertext",
        } <= lead)
        self.assertIn("lead_assignment_id", self.columns("lead.lead_contact_result"))

    def test_opportunity_has_exact_connected_contact_source_and_full_participation_set(self):
        opportunity = self.columns("opportunity.opportunity")
        self.assertIn("source_contact_result_id", opportunity)
        participation = self.columns("opportunity.opportunity_participation")
        self.assertTrue({
            "participation_set_revision", "participation_no", "participation_set_size",
            "participation_set_digest", "party_snapshot_digest",
        } <= participation)
        self.assertNotIn("predecessor_participation_id", participation)
        revision = self.columns("opportunity.quote_revision")
        self.assertTrue({"participation_set_revision", "participation_set_digest"} <= revision)

    def test_quote_is_versioned_before_authorization_and_response_targets_issue(self):
        opportunity = self.columns("opportunity.opportunity")
        self.assertIn("source_lead_id", opportunity)
        self.assertNotIn("stage_code", opportunity)
        self.assertNotIn("status_code", opportunity)
        revision = self.columns("opportunity.quote_revision")
        self.assertTrue({"predecessor_quote_revision_id", "confirmed_action_draft_id", "content_digest"} <= revision)
        self.assertNotIn("authorization_decision_record_id", revision)
        issue = self.columns("opportunity.quote_issue")
        self.assertTrue({"authorization_set_digest", "delivery_fact_type", "delivery_fact_id", "replaces_quote_issue_id"} <= issue)
        self.assertNotIn("superseded_by_quote_issue_id", issue)
        response = self.columns("opportunity.quote_response")
        self.assertIn("quote_issue_id", response)
        self.assertNotIn("issue_type", response)

    def test_conflict_separates_initial_conclusion_from_waive_or_block_resolution(self):
        review = self.columns("conflict.conflict_review")
        self.assertTrue({"initial_conclusion_code", "finding_count", "resolution_code", "resolution_digest"} <= review)
        self.assertNotIn("resolution_status_code", review)
        finding = self.columns("conflict.conflict_finding")
        self.assertIn("risk_classification_code", finding)
        self.assertNotIn("authority_slot_code", finding)
        review_party = self.columns("conflict.conflict_review_party")
        self.assertTrue({"source_item_type", "source_item_id", "source_item_revision", "source_item_hash"} <= review_party)

    def test_progress_is_typed_fact_and_conflict_freezes_review_contract(self):
        progress = self.columns("opportunity.opportunity_progress")
        self.assertTrue({"progress_contract_code", "progress_contract_version", "progress_digest"} <= progress)
        self.assertNotIn("progress_summary", progress)
        review = self.columns("conflict.conflict_review")
        self.assertTrue({"legal_need_digest", "review_contract_code", "review_contract_version"} <= review)

    def test_domain_event_outbox_allows_only_authorized_in_place_exhausted_redrive(self):
        outbox = self.tables["execution.domain_event_outbox"]
        self.assertIn(("EXHAUSTED", "PENDING"), outbox.state_transitions)

    def test_external_action_can_mark_possible_network_crossing_unknown(self):
        action = self.tables["external_action.external_action"]
        self.assertIn(("PENDING", "UNKNOWN"), action.state_transitions)

    def test_grant_windows_are_frozen_and_only_revocation_is_mutable(self):
        for qualified in (
            "identity.authority_grant",
            "identity.delegation_grant",
            "identity.object_access_grant",
        ):
            self.assertNotIn("valid_until", self.tables[qualified].mutable_columns, qualified)

    def test_conflict_review_can_be_recreated_after_decision_or_validity_change(self):
        review = self.tables["conflict.conflict_review"]
        trigger_index = next(item for item in review.indexes if item.name == "ix_conflict_review__trigger_scope")
        self.assertFalse(trigger_index.unique)
        self.assertTrue({"trigger_fact_revision", "trigger_fact_hash"} <= set(trigger_index.columns))

    def test_payment_gate_freezes_exact_positive_confirmation_set_and_trusted_sources(self):
        gate = self.columns("contract.payment_gate")
        self.assertTrue({"payment_confirmation_ids", "confirmation_set_digest"} <= gate)
        kind_check = self.constraint("contract.payment_gate", "ck_payment_gate__kind_payload")
        self.assertIn("required_amount_minor > 0", kind_check.expression)
        source_check = self.constraint("contract.payment_confirmation", "ck_payment_confirmation__trusted_source")
        self.assertIn("provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL", source_check.expression)

    def test_contract_approval_can_advance_before_execution_and_signature_revoke_uses_authorization(self):
        contract = self.tables["contract.contract"]
        self.assertNotIn("approved_revision_id", contract.write_once_columns)
        signature = self.columns("contract.contract_signature")
        self.assertIn("revocation_authorization_digest", signature)
        self.assertNotIn("revocation_decision_record_id", signature)

    def test_external_resolution_distinguishes_provider_probe_and_decision(self):
        action = self.columns("external_action.external_action")
        self.assertIn("resolution_method_code", action)
        shape = self.constraint("external_action.external_action", "ck_external_action__completion_shape")
        self.assertIn("resolution_method_code = 'PROBE'", shape.expression)
        self.assertIn("resolution_source_type IS NULL", shape.expression)

    def test_audit_correction_and_provider_nonce_are_single_chain_unique(self):
        audit_indexes = {item.name: item for item in self.tables["audit.audit_entry"].indexes}
        self.assertIn("ux_audit_entry__correction_target", audit_indexes)
        self.assertTrue(audit_indexes["ux_audit_entry__correction_target"].unique)
        self.assertIsNotNone(audit_indexes["ux_audit_entry__correction_target"].where)
        inbox_constraints = {item.name for item in self.tables["external_action.provider_inbox"].constraints}
        self.assertIn("uq_provider_inbox__account_nonce", inbox_constraints)

    def test_stable_fact_chains_use_composite_relationships(self):
        expected = {
            "identity.delegation_grant": {
                ("tenant_id", "source_authority_grant_id", "delegator_appointment_id"),
            },
            "audit.audit_entry": {
                ("tenant_id", "actor_appointment_id", "actor_principal_id"),
                ("tenant_id", "on_behalf_of_appointment_id", "on_behalf_of_principal_id"),
            },
            "opportunity.opportunity": {
                ("tenant_id", "source_assignment_id", "source_lead_id", "owner_appointment_id"),
                ("tenant_id", "source_contact_result_id", "source_lead_id", "source_assignment_id"),
            },
            "contract.contract_signature": {
                ("tenant_id", "signature_plan_id", "contract_revision_id", "signer_party_id"),
            },
            "contract.contract_execution": {
                ("tenant_id", "contract_revision_id", "contract_id"),
            },
            "contract.payment_confirmation": {
                ("tenant_id", "contract_revision_id", "contract_id"),
            },
            "transfer.transfer_request": {
                ("tenant_id", "contract_id", "opportunity_id", "contract_execution_id"),
            },
        }
        for qualified, required in expected.items():
            actual = {tuple(fk.columns) for fk in self.tables[qualified].foreign_keys}
            self.assertTrue(required <= actual, qualified)

    def test_party_revision_is_cas_input_with_frozen_business_snapshot(self):
        self.assertIn("party_snapshot_digest", self.columns("opportunity.opportunity_participation"))
        self.assertIn("party_snapshot_digest", self.columns("contract.contract_participation"))

    def test_transfer_snapshot_keeps_exact_evidence_set_without_new_material_table(self):
        snapshot = self.columns("transfer.transfer_snapshot")
        self.assertTrue({"evidence_submission_ids", "evidence_set_digest"} <= snapshot)


if __name__ == "__main__":
    unittest.main()
