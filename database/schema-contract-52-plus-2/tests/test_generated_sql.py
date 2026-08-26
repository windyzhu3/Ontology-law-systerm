import re
import json
import tempfile
import unittest
from pathlib import Path


class GeneratedSqlTest(unittest.TestCase):
    def render(self, root: Path):
        from contract.render import generate_all
        generate_all(root)

    def test_generation_is_byte_for_byte_deterministic(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_root, second_root = Path(first), Path(second)
            self.render(first_root)
            self.render(second_root)
            first_files = sorted(path.relative_to(first_root) for path in first_root.rglob("*") if path.is_file())
            second_files = sorted(path.relative_to(second_root) for path in second_root.rglob("*") if path.is_file())
            self.assertEqual(first_files, second_files)
            for relative in first_files:
                self.assertEqual((first_root / relative).read_bytes(), (second_root / relative).read_bytes(), str(relative))

    def test_generated_migrations_create_only_frozen_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted(root.rglob("*.sql")))
            created = re.findall(r"CREATE TABLE ([a-z_]+\.[a-z_]+)", sql)
            app_tables = [name for name in created if not name.startswith("platform_meta.")]
            self.assertEqual(52, len(app_tables))
            self.assertEqual(["platform_meta.deployment_state"], [name for name in created if name.startswith("platform_meta.")])
            self.assertNotIn("CREATE TABLE platform_meta.flyway_schema_history", sql)

    def test_generated_sql_contains_no_forbidden_referential_actions_or_business_enum(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = "\n".join(path.read_text(encoding="utf-8") for path in root.rglob("*.sql")).upper()
            self.assertNotIn("ON DELETE CASCADE", sql)
            self.assertNotIn("ON UPDATE CASCADE", sql)
            self.assertNotIn("ON DELETE SET NULL", sql)
            self.assertNotIn("ON DELETE SET DEFAULT", sql)
            self.assertNotIn("CREATE TYPE", sql)

    def test_every_create_table_has_table_and_column_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted(root.rglob("*.sql")))
            created = re.findall(r"CREATE TABLE ([a-z_]+\.[a-z_]+)", sql)
            for qualified in created:
                self.assertRegex(sql, rf"COMMENT ON TABLE {re.escape(qualified)} IS '[^']*[\u4e00-\u9fff][^']*'", qualified)
                self.assertRegex(sql, rf"COMMENT ON TABLE {re.escape(qualified)} IS 'Fact Owner：[^；]+；", qualified)

    def test_constraint_backed_and_explicit_indexes_have_chinese_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted(root.rglob("*.sql")))
            created_tables = re.findall(r"CREATE TABLE ([a-z_]+)\.([a-z_]+)", sql)
            for schema, table in created_tables:
                self.assertRegex(
                    sql,
                    rf"COMMENT ON INDEX {schema}\.pk_{table} IS '[^']*[\u4e00-\u9fff][^']*'",
                    f"{schema}.pk_{table}",
                )
            unique_constraints = re.findall(r"CONSTRAINT ([a-z_]+) UNIQUE \(", sql)
            for name in unique_constraints:
                self.assertRegex(
                    sql,
                    rf"COMMENT ON INDEX [a-z_]+\.{name} IS '[^']*[\u4e00-\u9fff][^']*'",
                    name,
                )

    def test_every_generated_schema_function_trigger_and_view_has_chinese_comment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = "\n".join(path.read_text(encoding="utf-8") for path in sorted(root.rglob("*.sql")))
            for schema in re.findall(r"CREATE SCHEMA IF NOT EXISTS ([a-z_]+);", sql):
                self.assertRegex(
                    sql,
                    rf"COMMENT ON SCHEMA {schema} IS '[^']*[\u4e00-\u9fff][^']*'",
                    schema,
                )
            for function in re.findall(r"CREATE FUNCTION ([a-z_]+\.[a-z_]+)\(\)", sql):
                self.assertRegex(
                    sql,
                    rf"COMMENT ON FUNCTION {re.escape(function)}\(\) IS\s*'[^']*[\u4e00-\u9fff][^']*'",
                    function,
                )
            trigger_pattern = re.compile(
                r"CREATE (?:CONSTRAINT )?TRIGGER ([a-z_]+)\s+.*?\sON ([a-z_]+\.[a-z_]+)\s",
                re.DOTALL,
            )
            for trigger, table in trigger_pattern.findall(sql):
                self.assertRegex(
                    sql,
                    rf"COMMENT ON TRIGGER {trigger} ON {re.escape(table)} IS\s*'[^']*[\u4e00-\u9fff][^']*'",
                    f"{table}.{trigger}",
                )
            for view in re.findall(r"CREATE VIEW ([a-z_]+\.[a-z_]+)", sql):
                self.assertRegex(
                    sql,
                    rf"COMMENT ON VIEW {re.escape(view)} IS\s*'[^']*[\u4e00-\u9fff][^']*'",
                    view,
                )

    def test_cross_row_guards_cover_owned_pointers_and_evidence_finalization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = (root / "db" / "migration" / "V810__update_guards.sql").read_text(encoding="utf-8")
            self.assertIn("CREATE FUNCTION platform_meta.fn_assert_owned_pointer()", sql)
            self.assertIn("CREATE FUNCTION platform_meta.fn_assert_evidence_finalization()", sql)
            for trigger in (
                "ctrg_lead__current_assignment_owner",
                "ctrg_opportunity__current_quote_owner",
                "ctrg_contract__current_revision_owner",
                "ctrg_contract__approved_revision_owner",
                "ctrg_contract__execution_owner",
                "ctrg_contract__termination_owner",
                "ctrg_transfer_request__accepted_snapshot_owner",
                "ctrg_upload_session__finalization",
                "ctrg_evidence_submission__promotion_member",
                "ctrg_evidence_binding__promotion_member",
                "ctrg_lead_assignment__chain",
                "ctrg_lead__current_assignment",
                "ctrg_opportunity__qualified_source",
                "ctrg_quote_revision__complete_package",
                "ctrg_opportunity_participation__quoted_set",
                "ctrg_opportunity__lifecycle",
                "ctrg_contract_revision__complete_package",
                "ctrg_contract__lifecycle",
                "ctrg_contract_execution__complete_package",
                "ctrg_contract_termination__anchor",
                "ctrg_transfer_return_item__unaccepted",
                "ctrg_transfer_request__acceptance_leaf",
            ):
                self.assertIn(f"CREATE CONSTRAINT TRIGGER {trigger}", sql)
            self.assertIn("CREATE TRIGGER trg_contract_signature__lifecycle", sql)
            self.assertGreaterEqual(sql.count("DEFERRABLE INITIALLY DEFERRED"), 8)

    def test_controlled_guard_rejects_noop_terminal_and_nonmonotonic_queue_updates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = (root / "db" / "migration" / "V810__update_guards.sql").read_text(encoding="utf-8")
            self.assertIn("controlled update requires a semantic column change", sql)
            self.assertIn("terminal state % rejects further updates", sql)
            self.assertIn("queue fencing_token must increment on claim", sql)
            self.assertIn("queue counters cannot decrease", sql)
            self.assertIn("exhausted queue row rejects in-place mutation", sql)
            self.assertIn("CREATE FUNCTION platform_meta.fn_guard_initial_state()", sql)
            self.assertIn("initial state must be", sql)
            self.assertIn("initial revision must be zero", sql)
            self.assertIn("initial queue counters must be zero", sql)
            self.assertIn("CREATE FUNCTION platform_meta.fn_guard_initial_nulls()", sql)
            for trigger in (
                "trg_evidence_binding__initial_active",
                "trg_lead__initial_unassigned",
                "trg_opportunity__initial_open",
                "trg_conflict_review__initial_unresolved",
                "trg_contract_signature__initial_active",
            ):
                self.assertIn(f"CREATE TRIGGER {trigger}", sql)
            self.assertIn("current quote pointer must advance to the direct successor", sql)

    def test_privileges_are_reset_then_granted_to_narrow_roles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = (root / "db" / "migration" / "V830__application_privileges.sql").read_text(encoding="utf-8")
            self.assertIn("REVOKE ALL ON ALL TABLES IN SCHEMA", sql)
            self.assertIn("FROM ${app_command_role}, ${app_worker_role}, ${app_query_role}, ${audit_append_role}", sql)
            self.assertIn("CREATE VIEW audit.audit_entry_classified_v", sql)
            self.assertNotIn("GRANT SELECT ON audit.audit_entry TO ${app_query_role}", sql)
            self.assertIn("CREATE FUNCTION platform_meta.fn_guard_domain_event_redrive()", sql)
            self.assertIn("only the command role may redrive an exhausted domain outbox", sql)
            self.assertIn("must not be members of any parent role", sql)
            self.assertIn("REVOKE ALL ON SCHEMA public", sql)
            self.assertIn("capability role has LOGIN or forbidden cluster capability", sql)
            self.assertNotIn("GRANT CONNECT ON DATABASE", sql)

    def test_install_time_validation_closes_54_table_and_comment_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            sql = (root / "db" / "migration" / "V840__schema_contract_validation.sql").read_text(encoding="utf-8")
            self.assertIn("expected 2 platform_meta tables", sql)
            self.assertIn("expected 54 managed tables", sql)
            self.assertIn("constraint comments missing", sql)
            self.assertIn("index comments missing", sql)
            self.assertIn("function comments missing", sql)
            self.assertIn("trigger comments missing", sql)
            self.assertIn("tenant_id must be the first column of every tenant foreign key", sql)
            self.assertIn("mutation guard coverage mismatch", sql)
            self.assertIn("physical foreign key whitelist mismatch", sql)
            self.assertIn("application capability role has forbidden database CONNECT/CREATE/TEMPORARY", sql)
            self.assertIn("application role has forbidden DELETE or TRUNCATE", sql)
            self.assertIn("column UPDATE whitelist mismatch", sql)
            self.assertIn("table SELECT/INSERT privilege matrix mismatch", sql)
            self.assertIn("schema USAGE privilege matrix mismatch", sql)
            self.assertIn("application role has forbidden TRIGGER or REFERENCES", sql)
            self.assertIn("foreign key must be validated with MATCH SIMPLE", sql)
            self.assertIn("application role has a forbidden parent role membership", sql)
            self.assertIn("application capability role must be NOLOGIN", sql)
            self.assertIn("unexpected user schema violates the dedicated database contract", sql)
            self.assertIn("unexpected user table exists outside the 52 plus 2 ledger", sql)

    def test_field_contract_includes_reference_and_runtime_guard_matrices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            markdown = (root / "field-contract.md").read_text(encoding="utf-8")
            self.assertIn("## 跨域复合外键矩阵", markdown)
            self.assertIn("## 类型化准确引用矩阵", markdown)
            self.assertIn("## 跨行守卫与运行时重验边界", markdown)
            self.assertIn("Evidence晋级", markdown)

    def test_manifest_freezes_the_complete_contract_and_reference_registry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            manifest = json.loads((root / "schema-contract-manifest.json").read_text(encoding="utf-8"))
            self.assertRegex(manifest["contractSha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(19, len(manifest["generatedArtifactSha256"]))
            for digest in manifest["generatedArtifactSha256"].values():
                self.assertRegex(digest, r"^[0-9a-f]{64}$")
            self.assertEqual(52, manifest["applicationTableCount"])
            self.assertGreater(len(manifest["physicalForeignKeyWhitelist"]), 52)
            self.assertEqual(22, len(manifest["typedReferenceRegistry"]))
            for slot, entry in manifest["typedReferenceRegistry"].items():
                self.assertTrue(entry["allowedTargetTypes"], slot)
            tables = [table for schema in manifest["schemas"] for table in schema["tables"]]
            self.assertEqual(53, len(tables))
            sample = next(table for table in tables if table["qualifiedName"] == "evidence.evidence_binding")
            self.assertIn("columns", sample)
            self.assertIn("constraints", sample)
            self.assertIn("foreignKeys", sample)
            self.assertIn("indexes", sample)
            self.assertIn("updatePolicy", sample)
            self.assertEqual("EvidenceRuntime", sample["owner"])
            owners = {table["qualifiedName"]: table["owner"] for table in tables}
            self.assertEqual("EvidenceIngress", owners["evidence.upload_session"])
            self.assertEqual("ProviderIngress", owners["external_action.provider_inbox"])
            self.assertEqual("OutboxDispatcher", owners["execution.domain_event_outbox"])

    def test_expected_migration_order_is_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.render(root)
            names = sorted(path.name for path in (root / "db" / "migration").glob("*.sql"))
            self.assertEqual([
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
            ], names)


if __name__ == "__main__":
    unittest.main()
