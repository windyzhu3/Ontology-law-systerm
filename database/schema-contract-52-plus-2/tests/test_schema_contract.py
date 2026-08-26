import re
import unittest


EXPECTED_LEDGER = {
    "identity": (
        "tenant", "principal", "organization_unit", "appointment",
        "authority_grant", "delegation_grant", "object_access_grant",
    ),
    "audit": ("audit_entry",),
    "responsibility": (
        "task_occurrence", "decision_record", "wait_receipt", "action_draft",
    ),
    "execution": (
        "command_execution_slot", "command_receipt", "domain_event",
        "domain_event_outbox",
    ),
    "external_action": (
        "external_action", "external_action_outbox", "provider_inbox",
    ),
    "evidence": (
        "upload_session", "received_source_object", "evidence_submission",
        "evidence_binding",
    ),
    "party": ("party",),
    "lead": ("lead", "lead_assignment", "lead_contact_result"),
    "opportunity": (
        "opportunity", "opportunity_participation", "opportunity_progress",
        "quote_revision", "quote_service_scope", "quote_line",
        "quote_payment_term", "quote_issue", "quote_response",
    ),
    "conflict": ("conflict_review", "conflict_review_party", "conflict_finding"),
    "contract": (
        "contract", "contract_revision", "contract_participation",
        "contract_fee_term", "payment_gate", "signature_plan",
        "contract_signature", "contract_execution", "payment_confirmation",
        "contract_termination",
    ),
    "transfer": ("transfer_request", "transfer_snapshot", "transfer_return_item"),
}

EXPECTED_MUTABLE = {
    "identity.tenant", "identity.principal", "identity.organization_unit",
    "identity.appointment", "identity.authority_grant",
    "identity.delegation_grant", "identity.object_access_grant",
    "responsibility.task_occurrence", "responsibility.action_draft",
    "execution.domain_event_outbox",
    "external_action.external_action", "external_action.external_action_outbox",
    "evidence.upload_session", "evidence.evidence_binding",
    "party.party", "lead.lead", "lead.lead_assignment",
    "opportunity.opportunity", "opportunity.quote_issue",
    "conflict.conflict_review", "contract.contract", "contract.payment_gate",
    "contract.contract_signature", "contract.contract_termination",
    "transfer.transfer_request", "platform_meta.deployment_state",
}


def has_chinese(value: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", value or ""))


class SchemaContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from contract.schema_contract import SCHEMAS
        cls.schemas = SCHEMAS
        cls.by_name = {schema.name: schema for schema in SCHEMAS}

    def test_exact_frozen_ledger_contains_52_application_tables(self):
        actual = {
            name: tuple(table.name for table in self.by_name[name].tables)
            for name in EXPECTED_LEDGER
        }
        self.assertEqual(EXPECTED_LEDGER, actual)
        self.assertEqual(52, sum(len(names) for names in actual.values()))
        self.assertEqual(("deployment_state",), tuple(
            table.name for table in self.by_name["platform_meta"].tables
        ))

    def test_identifiers_are_unquoted_snake_case(self):
        pattern = re.compile(r"^[a-z][a-z0-9_]*$")
        for schema in self.schemas:
            self.assertRegex(schema.name, pattern)
            for table in schema.tables:
                self.assertRegex(table.name, pattern)
                for column in table.columns:
                    self.assertRegex(column.name, pattern)

    def test_required_objects_have_chinese_comments(self):
        for schema in self.schemas:
            self.assertTrue(has_chinese(schema.comment), schema.name)
            for table in schema.tables:
                qualified = f"{schema.name}.{table.name}"
                self.assertTrue(has_chinese(table.comment), qualified)
                for column in table.columns:
                    self.assertTrue(has_chinese(column.comment), f"{qualified}.{column.name}")
                for constraint in table.constraints:
                    self.assertTrue(has_chinese(constraint.comment), constraint.name)
                for index in table.indexes:
                    self.assertTrue(has_chinese(index.comment), index.name)

    def test_all_tenant_tables_use_composite_primary_key_and_tenant_fk(self):
        for schema in self.schemas:
            for table in schema.tables:
                if schema.name == "platform_meta" or (schema.name == "identity" and table.name == "tenant"):
                    continue
                self.assertEqual("tenant_id", table.primary_key[0], f"{schema.name}.{table.name}")
                self.assertIn(table.id_column, table.primary_key)
                tenant_fks = [
                    fk for fk in table.foreign_keys
                    if fk.parent_schema == "identity" and fk.parent_table == "tenant"
                ]
                self.assertEqual(1, len(tenant_fks), f"{schema.name}.{table.name}")
                self.assertEqual(("tenant_id",), tenant_fks[0].columns)

    def test_every_physical_foreign_key_carries_tenant_and_has_no_action(self):
        for schema in self.schemas:
            for table in schema.tables:
                for fk in table.foreign_keys:
                    if fk.parent_schema == "identity" and fk.parent_table == "tenant":
                        self.assertEqual(("tenant_id",), fk.columns)
                    else:
                        self.assertEqual("tenant_id", fk.columns[0], fk.name)
                        self.assertEqual("tenant_id", fk.parent_columns[0], fk.name)
                    self.assertEqual("NO ACTION", fk.on_update, fk.name)
                    self.assertEqual("NO ACTION", fk.on_delete, fk.name)

    def test_typed_references_are_exact(self):
        from contract.reference_registry import APPLICATION_FACT_TYPES, TYPED_REFERENCE_ALLOWED_TARGETS
        actual_fact_types = {
            f"{schema.name}.{table.name}"
            for schema in self.schemas if schema.name != "platform_meta"
            for table in schema.tables
        }
        self.assertEqual(actual_fact_types, set(APPLICATION_FACT_TYPES))
        typed_refs = [
            (f"{schema.name}.{table.name}.{ref.prefix}", ref)
            for schema in self.schemas
            for table in schema.tables
            for ref in table.typed_references
        ]
        self.assertGreater(len(typed_refs), 10)
        self.assertEqual({name for name, _ in typed_refs}, set(TYPED_REFERENCE_ALLOWED_TARGETS))
        for qualified, ref in typed_refs:
            names = {column.name for column in ref.columns}
            self.assertIn(f"{ref.prefix}_type", names)
            self.assertIn(f"{ref.prefix}_id", names)
            self.assertIn(f"{ref.prefix}_revision", names)
            self.assertIn(f"{ref.prefix}_hash", names)
            self.assertTrue(ref.exact_selector_check)
            self.assertTrue(TYPED_REFERENCE_ALLOWED_TARGETS[qualified], qualified)
            check_name = f"ck_{qualified.split('.')[1]}__{ref.prefix}_exact"
            table = next(
                table for schema in self.schemas for table in schema.tables
                if f"{schema.name}.{table.name}" == ".".join(qualified.split(".")[:2])
            )
            exact_check = next(item for item in table.constraints if item.name == check_name)
            self.assertIn(f"{ref.prefix}_revision >= 0", exact_check.expression)

    def test_digest_amount_time_and_revision_types_are_consistent(self):
        for schema in self.schemas:
            for table in schema.tables:
                for column in table.columns:
                    if column.name.endswith(("_digest", "_sha256", "_hash")) and not column.name.endswith("_hash_code"):
                        self.assertEqual("bytea", column.sql_type, f"{schema.name}.{table.name}.{column.name}")
                        self.assertEqual(32, column.byte_length)
                    if column.name.endswith("_minor"):
                        self.assertEqual("bigint", column.sql_type)
                    if column.name.endswith("_at") or column.name.endswith("_from") or column.name.endswith("_until"):
                        self.assertEqual("timestamptz(6)", column.sql_type)
                    if column.name == "revision":
                        self.assertEqual("bigint", column.sql_type)

    def test_mutable_table_whitelist_is_exact(self):
        actual = {
            f"{schema.name}.{table.name}"
            for schema in self.schemas
            for table in schema.tables
            if table.update_policy != "IMMUTABLE"
        }
        self.assertEqual(EXPECTED_MUTABLE, actual)
        for schema in self.schemas:
            for table in schema.tables:
                if table.update_policy != "IMMUTABLE":
                    self.assertIn("revision", {column.name for column in table.columns})
                    self.assertTrue(table.mutable_columns)
                if table.state_column is not None:
                    self.assertIsNotNone(table.initial_state, f"{schema.name}.{table.name}")

    def test_references_indexes_and_mutation_lists_resolve_to_real_columns(self):
        table_map = {
            (schema.name, table.name): table
            for schema in self.schemas
            for table in schema.tables
        }
        index_names_by_schema = {}
        for schema in self.schemas:
            index_names_by_schema.setdefault(schema.name, set())
            for table in schema.tables:
                qualified = f"{schema.name}.{table.name}"
                columns = {column.name for column in table.columns}
                self.assertIn(table.id_column, columns, qualified)
                self.assertTrue(set(table.primary_key) <= columns, qualified)
                self.assertTrue(set(table.mutable_columns) <= columns, qualified)
                self.assertTrue(set(table.write_once_columns) <= set(table.mutable_columns), qualified)

                constraint_names = [f"pk_{table.name}"]
                constraint_names += [constraint.name for constraint in table.constraints]
                constraint_names += [fk.name for fk in table.foreign_keys]
                self.assertEqual(len(constraint_names), len(set(constraint_names)), qualified)

                for item in table.indexes:
                    self.assertTrue(set(item.columns) <= columns, item.name)
                    self.assertNotIn(item.name, index_names_by_schema[schema.name], item.name)
                    index_names_by_schema[schema.name].add(item.name)
                    unique_column_sets = {
                        tuple(part.strip() for part in constraint.expression.split(","))
                        for constraint in table.constraints
                        if constraint.kind == "UNIQUE"
                    }
                    if not item.unique and item.where is None:
                        self.assertNotIn(tuple(item.columns), unique_column_sets, f"冗余索引: {item.name}")

                for fk in table.foreign_keys:
                    self.assertTrue(set(fk.columns) <= columns, fk.name)
                    parent = table_map[(fk.parent_schema, fk.parent_table)]
                    parent_columns = {column.name for column in parent.columns}
                    self.assertTrue(set(fk.parent_columns) <= parent_columns, fk.name)
                    candidate_keys = {tuple(parent.primary_key)}
                    candidate_keys.update(
                        tuple(part.strip() for part in constraint.expression.split(","))
                        for constraint in parent.constraints
                        if constraint.kind == "UNIQUE"
                    )
                    self.assertIn(tuple(fk.parent_columns), candidate_keys, fk.name)


if __name__ == "__main__":
    unittest.main()
