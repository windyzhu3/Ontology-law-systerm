import hashlib
import json
import re
import shutil
import tempfile
import unittest
from pathlib import Path


CONTRACT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
EXPECTED_MIGRATIONS = (
    "V001__bootstrap_schemas.sql",
    "V002__deployment_state.sql",
    "V010__identity_tables.sql",
    "V020__audit_tables.sql",
    "V030__responsibility_tables.sql",
    "V040__execution_tables.sql",
    "V050__external_action_tables.sql",
    "V060__evidence_tables.sql",
    "V070__party_tables.sql",
    "V080__lead_tables.sql",
    "V090__opportunity_tables.sql",
    "V100__conflict_tables.sql",
    "V110__contract_tables.sql",
    "V120__transfer_tables.sql",
    "V800__cross_domain_foreign_keys.sql",
    "V810__update_guards.sql",
    "V820__indexes.sql",
    "V830__application_privileges.sql",
    "V840__schema_contract_validation.sql",
)
EXPECTED_V1_1_MIGRATIONS = (
    *EXPECTED_MIGRATIONS,
    "V850__lead_ingress_completion_slot.sql",
)
EXPECTED_MIGRATION_SHA256 = {
    "V001__bootstrap_schemas.sql": "5f0b866c7f9f4adcfc1e658053859b068b88cca6f476f376deec50f283c816e7",
    "V002__deployment_state.sql": "66fee9505dc4f5a0e9d4d180d0979867e00321af57c37c76376fbe49df635833",
    "V010__identity_tables.sql": "7d8de2e97a8cb20ce7242262989b4648d03baddb49cf5d5c21b91ddf5a3bd222",
    "V020__audit_tables.sql": "921cb77c29279ebe21be27db29f3843858bb3b980b345010efec18e5c4619d27",
    "V030__responsibility_tables.sql": "81656311fabaa2cd591ea25365bda5e7f95af30d0d08776ff66004ef6b7f3cb7",
    "V040__execution_tables.sql": "408f0c066aabec3e9b8c45765c1723464db894dee1e290708bd3732638abe67d",
    "V050__external_action_tables.sql": "bf9b80bfa3825856eb738a4c8b78491bccdc825436a4769b41e6c2d590ff0f23",
    "V060__evidence_tables.sql": "a42a5cc0f09274e3ab7e34192324de94ee0344bdbd7906d1efbfefc5792e31fa",
    "V070__party_tables.sql": "56716e2b519629f2d7311c8dd58609462b9dce444adbf998146851553a15e75f",
    "V080__lead_tables.sql": "5e096ecbb6a72f0a25fe380edbb80ce3fb7024c93d0d360c7841eac84e1198b8",
    "V090__opportunity_tables.sql": "b33f5a6abaebc387249b534acb10f8569da808adaa673543a409ae23bf51bc9a",
    "V100__conflict_tables.sql": "91810fee670d380e31582400441e4985f3ec0056441d546dec4a35389741d22e",
    "V110__contract_tables.sql": "8badb97821333736aea2dfff16a7fc95de9d5df9136d4f0064052bc4626c23b4",
    "V120__transfer_tables.sql": "9ee22813c4e71312f94a0762084989a5e0aea9a78ae47168a677e762bd325128",
    "V800__cross_domain_foreign_keys.sql": "6e4d2b23c33179e03b801cf17a5b83be3709be55d0047cde9e8c48cad4a14cde",
    "V810__update_guards.sql": "bc95f1b0a80924848162388f9e9162d8a628091e93c5112579bd33f7ebeab0b7",
    "V820__indexes.sql": "1c6968f012d5085fae5fcb0dbcee17cd9225034b9c81e8ef9de717d96e5f820f",
    "V830__application_privileges.sql": "4292e7294b40211b3d141cf7b0b1d5c1e09582056bf27b9c3bde39bacb34e821",
    "V840__schema_contract_validation.sql": "0919a6047fdb94879aa2fce18ce3df8d22eaf2de5d27f9cbb26f04c27dd2b2ad",
}
EXPECTED_V1_1_MIGRATION_SHA256 = {
    **EXPECTED_MIGRATION_SHA256,
    "V850__lead_ingress_completion_slot.sql": "6f784b95ae823bf5d97ef742d5494396911828c9ddc88ff07d35a6bc816e488b",
}
EXPECTED_CONTRACT_SHA256 = (
    "0c04d48ddae6891b53fdacabdba34d1124e757b070a4c9018597e4e0a4674301"
)
EXPECTED_FIELD_CONTRACT_SHA256 = (
    "f4c17c4c0a8697820b30adb61b8cdb209666a4672393d4f8fc9d73a5f169addf"
)
EXPECTED_TRANSFER_ACCEPTANCE_CONSTRAINT = (
    "(accepted_snapshot_id IS NULL AND accept_decision_record_id IS NULL AND "
    "matter_id IS NULL AND matter_no IS NULL AND matter_type_code IS NULL AND "
    "matter_capability_pack_code IS NULL AND matter_capability_pack_version IS "
    "NULL AND matter_created_at IS NULL) OR (accepted_snapshot_id IS NOT NULL "
    "AND accept_decision_record_id IS NOT NULL AND matter_id IS NOT NULL AND "
    "matter_no IS NOT NULL AND matter_type_code IS NOT NULL AND "
    "matter_capability_pack_code IS NOT NULL AND matter_capability_pack_version "
    "> 0 AND matter_created_at IS NOT NULL)"
)
INGRESS_COMPLETION_COLUMN_CONTRACT = {
    "ingress_completion_phone_ciphertext": ("bytea", True, None),
    "ingress_completion_phone_hmac": ("bytea", True, 32),
    "ingress_completion_email_ciphertext": ("bytea", True, None),
    "ingress_completion_email_hmac": ("bytea", True, 32),
    "ingress_completion_source_code": ("varchar(64)", True, None),
    "ingress_completion_source_summary_ciphertext": ("bytea", True, None),
    "ingress_completed_by_appointment_id": ("uuid", True, None),
    "ingress_completed_at": ("timestamptz(6)", True, None),
    "ingress_completion_digest": ("bytea", True, 32),
}
INGRESS_COMPLETION_SLOT_EXPRESSION = (
    "(ingress_completion_phone_ciphertext IS NULL AND "
    "ingress_completion_phone_hmac IS NULL AND "
    "ingress_completion_email_ciphertext IS NULL AND "
    "ingress_completion_email_hmac IS NULL AND "
    "ingress_completion_source_code IS NULL AND "
    "ingress_completion_source_summary_ciphertext IS NULL AND "
    "ingress_completed_by_appointment_id IS NULL AND "
    "ingress_completed_at IS NULL AND ingress_completion_digest IS NULL) OR "
    "(captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL AND "
    "captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL AND "
    "(ingress_completion_phone_ciphertext IS NOT NULL OR "
    "ingress_completion_email_ciphertext IS NOT NULL) AND "
    "ingress_completion_source_code IS NOT NULL AND "
    "ingress_completion_source_summary_ciphertext IS NOT NULL AND "
    "ingress_completed_by_appointment_id IS NOT NULL AND "
    "ingress_completed_at IS NOT NULL AND ingress_completion_digest IS NOT NULL)"
)


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

    def assert_frozen_migration_contract(
        self, contract_root: Path, baseline_path: Path
    ) -> None:
        migration_root = contract_root / "generated/db/migration"
        actual_migrations = tuple(
            path.name for path in sorted(migration_root.glob("*.sql"))
        )
        manifest = json.loads(
            (contract_root / "generated/schema-contract-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        baseline = baseline_path.read_text(encoding="utf-8")
        actual_hashes = {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(migration_root.glob("*.sql"))
        }
        expected_manifest_hashes = {
            f"db/migration/{name}": digest
            for name, digest in EXPECTED_V1_1_MIGRATION_SHA256.items()
        }

        self.assertEqual(EXPECTED_V1_1_MIGRATIONS, actual_migrations)
        self.assertEqual(
            EXPECTED_V1_1_MIGRATION_SHA256,
            actual_hashes,
        )
        self.assertEqual(
            expected_manifest_hashes,
            manifest["generatedArtifactSha256"],
        )
        self.assertEqual(EXPECTED_CONTRACT_SHA256, manifest["contractSha256"])
        self.assertEqual(
            EXPECTED_FIELD_CONTRACT_SHA256,
            manifest["fieldContractSha256"],
        )
        self.assertIn(
            f"当前52＋2合同摘要：`{EXPECTED_CONTRACT_SHA256}`",
            baseline,
        )
        self.assertIn(
            f"字段合同摘要：`{EXPECTED_FIELD_CONTRACT_SHA256}`",
            baseline,
        )

    def assert_complete_transfer_acceptance_constraint(self, expression: str) -> None:
        def normalize(value: str) -> str:
            return re.sub(r"\s+", " ", value).strip()

        self.assertEqual(
            normalize(EXPECTED_TRANSFER_ACCEPTANCE_CONSTRAINT),
            normalize(expression),
        )

    def test_task_occurrence_initial_state_is_open(self):
        task = self.tables["responsibility.task_occurrence"]

        self.assertEqual("state", task.state_column)
        self.assertEqual("OPEN", task.initial_state)

    def test_complete_migration_set_matches_current_baseline_contract_hashes(self):
        self.assert_frozen_migration_contract(
            CONTRACT_ROOT,
            REPOSITORY_ROOT / "docs/baseline/CURRENT-MVP-BASELINE.md",
        )

    def test_v850_is_only_append_and_v1_migrations_keep_exact_sha256(self):
        from contract.render import generate_all

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            generate_all(root)
            migration_root = root / "db/migration"
            migrations = tuple(path.name for path in sorted(migration_root.glob("*.sql")))
            hashes = {
                path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sorted(migration_root.glob("*.sql"))
            }
            manifest = json.loads(
                (root / "schema-contract-manifest.json").read_text(encoding="utf-8")
            )

        self.assertEqual(EXPECTED_V1_1_MIGRATIONS, migrations)
        self.assertEqual(
            EXPECTED_MIGRATION_SHA256,
            {name: hashes[name] for name in EXPECTED_MIGRATIONS},
        )
        self.assertEqual("52-plus-2-v1.1", manifest["contractVersion"])
        self.assertEqual(20, len(manifest["generatedArtifactSha256"]))
        self.assertEqual(52, manifest["applicationTableCount"])
        self.assertEqual(54, manifest["physicalTableCountAfterFlywayBootstrap"])
        self.assertEqual(13, len(manifest["schemas"]))
        self.assertEqual(
            207,
            sum(
                len(table["foreignKeys"])
                for schema in manifest["schemas"]
                for table in schema["tables"]
            ),
        )

    def test_migration_bytes_and_manifest_digest_cannot_move_together(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            contract_root = root / "database/schema-contract-52-plus-2"
            shutil.copytree(
                CONTRACT_ROOT / "generated/db/migration",
                contract_root / "generated/db/migration",
            )
            shutil.copy2(
                CONTRACT_ROOT / "generated/schema-contract-manifest.json",
                contract_root / "generated/schema-contract-manifest.json",
            )
            baseline_path = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline_path.parent.mkdir(parents=True)
            shutil.copy2(
                REPOSITORY_ROOT / "docs/baseline/CURRENT-MVP-BASELINE.md",
                baseline_path,
            )
            migration_path = (
                contract_root
                / "generated/db/migration/V120__transfer_tables.sql"
            )
            migration_path.write_bytes(
                migration_path.read_bytes() + b"\n-- arbitrary mutation\n"
            )
            mutated_digest = hashlib.sha256(migration_path.read_bytes()).hexdigest()
            manifest_path = (
                contract_root / "generated/schema-contract-manifest.json"
            )
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["generatedArtifactSha256"][
                "db/migration/V120__transfer_tables.sql"
            ] = mutated_digest
            manifest["contractSha256"] = "0" * 64
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            baseline_path.write_text(
                baseline_path.read_text(encoding="utf-8").replace(
                    EXPECTED_CONTRACT_SHA256,
                    "0" * 64,
                ),
                encoding="utf-8",
            )

            with self.assertRaises(AssertionError):
                self.assert_frozen_migration_contract(contract_root, baseline_path)

    def test_wait_receipt_is_immutable_and_unique_per_positive_task_revision(self):
        wait_receipt = self.tables["responsibility.wait_receipt"]
        task_revision_unique = self.constraint(
            "responsibility.wait_receipt",
            "uq_wait_receipt__task_revision",
        )
        positive_revision = self.constraint(
            "responsibility.wait_receipt",
            "ck_wait_receipt__positive_task_revision",
        )

        self.assertEqual("IMMUTABLE", wait_receipt.update_policy)
        self.assertEqual((), wait_receipt.mutable_columns)
        self.assertEqual("UNIQUE", task_revision_unique.kind)
        self.assertEqual(
            "tenant_id, task_occurrence_id, task_revision",
            task_revision_unique.expression,
        )
        self.assertEqual("task_revision > 0", positive_revision.expression)

    def test_matter_core_schema_and_table_are_absent(self):
        from contract.schema_contract import SCHEMAS

        self.assertNotIn("matter_core", {schema.name for schema in SCHEMAS})
        self.assertNotIn(
            "matter_core",
            {table.name for schema in SCHEMAS for table in schema.tables},
        )

    def test_transfer_request_matter_ref_is_complete_all_or_none_and_write_once(self):
        transfer_request = self.tables["transfer.transfer_request"]
        matter_ref_columns = {
            "matter_id",
            "matter_no",
            "matter_type_code",
            "matter_capability_pack_code",
            "matter_capability_pack_version",
            "matter_created_at",
        }
        acceptance = self.constraint(
            "transfer.transfer_request",
            "ck_transfer_request__accept_complete",
        ).expression
        positive_snapshot_no = self.constraint(
            "transfer.transfer_snapshot",
            "ck_transfer_snapshot__snapshot_no",
        ).expression

        self.assertTrue(matter_ref_columns <= self.columns("transfer.transfer_request"))
        self.assertTrue(
            matter_ref_columns <= set(transfer_request.write_once_columns)
        )
        self.assert_complete_transfer_acceptance_constraint(acceptance)
        self.assertEqual("snapshot_no > 0", positive_snapshot_no)

    def test_transfer_acceptance_fragments_in_wrong_composition_are_rejected(self):
        wrong_constraint = (
            "accepted_snapshot_id IS NULL OR accept_decision_record_id IS NULL OR "
            "matter_id IS NULL OR matter_no IS NULL OR matter_type_code IS NULL OR "
            "matter_capability_pack_code IS NULL OR "
            "matter_capability_pack_version IS NULL OR matter_created_at IS NULL OR "
            "accepted_snapshot_id IS NOT NULL OR accept_decision_record_id IS NOT "
            "NULL OR matter_id IS NOT NULL OR matter_no IS NOT NULL OR "
            "matter_type_code IS NOT NULL OR matter_capability_pack_code IS NOT "
            "NULL OR matter_capability_pack_version > 0 OR matter_created_at IS NOT "
            "NULL"
        )

        with self.assertRaises(AssertionError):
            self.assert_complete_transfer_acceptance_constraint(wrong_constraint)

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

    def test_v1_1_lead_has_exact_ingress_completion_slot(self):
        from contract.schema_contract import BASE_SCHEMAS

        base_lead = next(
            table
            for schema in BASE_SCHEMAS
            for table in schema.tables
            if f"{schema.name}.{table.name}" == "lead.lead"
        )
        lead = self.tables["lead.lead"]
        base_column_names = {column.name for column in base_lead.columns}
        evolved_columns = {
            column.name: (column.sql_type, column.nullable, column.byte_length)
            for column in lead.columns
            if column.name not in base_column_names
        }

        self.assertEqual(INGRESS_COMPLETION_COLUMN_CONTRACT, evolved_columns)

    def test_ingress_completion_slot_is_all_or_complete_and_write_once(self):
        from contract.schema_contract import BASE_SCHEMAS

        base_lead = next(
            table
            for schema in BASE_SCHEMAS
            for table in schema.tables
            if f"{schema.name}.{table.name}" == "lead.lead"
        )
        lead = self.tables["lead.lead"]
        ingress_columns = set(INGRESS_COMPLETION_COLUMN_CONTRACT)
        constraints = {constraint.name: constraint for constraint in lead.constraints}

        self.assertEqual(
            "(ingress_completion_phone_ciphertext IS NULL AND ingress_completion_phone_hmac IS NULL) OR "
            "(ingress_completion_phone_ciphertext IS NOT NULL AND ingress_completion_phone_hmac IS NOT NULL)",
            constraints["ck_lead__ingress_completion_phone_pair"].expression,
        )
        self.assertEqual(
            "(ingress_completion_email_ciphertext IS NULL AND ingress_completion_email_hmac IS NULL) OR "
            "(ingress_completion_email_ciphertext IS NOT NULL AND ingress_completion_email_hmac IS NOT NULL)",
            constraints["ck_lead__ingress_completion_email_pair"].expression,
        )
        self.assertEqual(
            INGRESS_COMPLETION_SLOT_EXPRESSION,
            constraints["ck_lead__ingress_completion_slot"].expression,
        )
        self.assertEqual(
            ingress_columns,
            set(lead.mutable_columns) - set(base_lead.mutable_columns),
        )
        self.assertEqual(
            ingress_columns,
            set(lead.write_once_columns) - set(base_lead.write_once_columns),
        )
        self.assertTrue(
            {
                "captured_phone_ciphertext",
                "captured_phone_hmac",
                "captured_email_ciphertext",
                "captured_email_hmac",
            }.isdisjoint(lead.mutable_columns)
        )
        added_foreign_keys = {
            foreign_key.name: foreign_key
            for foreign_key in lead.foreign_keys
            if foreign_key not in base_lead.foreign_keys
        }
        self.assertEqual({"fk_lead__ingress_completed_by_appointment"}, set(added_foreign_keys))
        appointment = added_foreign_keys["fk_lead__ingress_completed_by_appointment"]
        self.assertEqual(
            ("tenant_id", "ingress_completed_by_appointment_id"),
            appointment.columns,
        )
        self.assertEqual("identity", appointment.parent_schema)
        self.assertEqual("appointment", appointment.parent_table)
        self.assertEqual(("tenant_id", "appointment_id"), appointment.parent_columns)

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
