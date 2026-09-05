from __future__ import annotations

import importlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CONTRACT = Path("docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md")
SCHEMA = Path("contracts/events/r1-domain-notification-v1.schema.json")


class R1CommandContractTest(unittest.TestCase):
    def validator(self):
        module = importlib.util.find_spec("scripts.baseline.r1_command_contract")
        self.assertIsNotNone(module, "R1 command contract validator must exist")
        return importlib.import_module(
            "scripts.baseline.r1_command_contract"
        ).validate_r1_command_contract

    def copy_contract_fixture(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative_path in (CONTRACT, SCHEMA):
            source = REPOSITORY_ROOT / relative_path
            self.assertTrue(source.is_file(), f"missing canonical fixture: {relative_path}")
            destination = root / relative_path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return temporary, root

    def replace_contract(self, root: Path, old: str, new: str) -> None:
        path = root / CONTRACT
        text = path.read_text(encoding="utf-8")
        self.assertEqual(text.count(old), 1, f"fixture must contain exactly one {old!r}")
        path.write_text(text.replace(old, new), encoding="utf-8")

    def assert_contract_mutation_fails(self, old: str, new: str) -> None:
        temporary, root = self.copy_contract_fixture()
        with temporary:
            self.replace_contract(root, old, new)
            self.assertTrue(self.validator()(root))

    def test_canonical_contract_is_consistent(self) -> None:
        temporary, root = self.copy_contract_fixture()
        with temporary:
            self.assertEqual(self.validator()(root), [])

    def test_capture_policy_cannot_be_removed(self) -> None:
        self.assert_contract_mutation_fails(
            "| CAPTURE_LEAD | INTERNAL_ADMIN | HUMAN | DIRECT,DELEGATED | "
            "SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | "
            "existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |\n",
            "",
        )

    def test_recovery_authority_codes_cannot_be_swapped(self) -> None:
        self.assert_contract_mutation_fails(
            "| REOPEN_DUE_CONTACT_TASKS | SERVICE_ACTOR | SERVICE | SYSTEM | "
            "SYSTEM_RECOVERY | CONTACT_TASK_RECOVER | taskOwnerOrganization | "
            "task-and-lead:CONTACT_TASK_RECOVER-DENY | CONTACT_LEAD | CONTACT_RETRY_V1 |\n",
            "| REOPEN_DUE_CONTACT_TASKS | SERVICE_ACTOR | SERVICE | SYSTEM | "
            "SYSTEM_RECOVERY | ROUTING_REVIEW_TASK_RECOVER | taskOwnerOrganization | "
            "task-and-lead:CONTACT_TASK_RECOVER-DENY | CONTACT_LEAD | CONTACT_RETRY_V1 |\n",
        )

    def test_connected_branch_cannot_lose_opportunity_notification(self) -> None:
        self.assert_contract_mutation_fails(
            "LeadContactResultRecordedV1,OpportunityOpened | 2 | 2 |",
            "LeadContactResultRecordedV1 | 2 | 2 |",
        )

    def test_opportunity_event_requires_exact_source_selector(self) -> None:
        self.assert_contract_mutation_fails(
            "| OpportunityOpened | 1 | opportunity.opportunity | revision:0 | "
            "R1_PROJECTION |",
            "| OpportunityOpened | 1 | opportunity.opportunity | revision:any | "
            "R1_PROJECTION |",
        )

    def test_opportunity_event_requires_exact_queue_owner(self) -> None:
        self.assert_contract_mutation_fails(
            "| OpportunityOpened | 1 | opportunity.opportunity | revision:0 | "
            "R1_PROJECTION |",
            "| OpportunityOpened | 1 | opportunity.opportunity | revision:0 | "
            "R2_PROJECTION |",
        )

    def test_duplicate_event_descriptor_is_rejected(self) -> None:
        row = (
            "| OpportunityOpened | 1 | opportunity.opportunity | revision:0 | "
            "R1_PROJECTION | R1 boundary projection; retained for delayed R2 activation |"
        )
        self.assert_contract_mutation_fails(row, f"{row}\n{row}")

    def test_duplicate_branch_descriptor_is_rejected(self) -> None:
        row = (
            "| CONTACT_CONNECTED_VALID | RECORD_CONTACT_RESULT | CONNECTED_VALID | "
            "LeadContactResultRecordedV1,OpportunityOpened | 2 | 2 |"
        )
        self.assert_contract_mutation_fails(row, f"{row}\n{row}")

    def test_payload_schema_cannot_declare_nonempty_properties(self) -> None:
        temporary, root = self.copy_contract_fixture()
        with temporary:
            schema_path = root / SCHEMA
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            schema["properties"] = {"details": {"type": "string"}}
            schema_path.write_text(json.dumps(schema), encoding="utf-8")
            self.assertTrue(self.validator()(root))

    def test_payload_schema_cannot_allow_additional_properties(self) -> None:
        temporary, root = self.copy_contract_fixture()
        with temporary:
            schema_path = root / SCHEMA
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            schema["additionalProperties"] = True
            schema_path.write_text(json.dumps(schema), encoding="utf-8")
            self.assertTrue(self.validator()(root))

    def test_payload_schema_rejects_duplicate_object_members(self) -> None:
        temporary, root = self.copy_contract_fixture()
        with temporary:
            schema_path = root / SCHEMA
            text = schema_path.read_text(encoding="utf-8")
            old = '  "type": "object",'
            self.assertEqual(text.count(old), 1)
            schema_path.write_text(
                text.replace(old, '  "type": "string",\n  "type": "object",'),
                encoding="utf-8",
            )
            self.assertTrue(self.validator()(root))

    def test_payload_schema_rejects_numeric_zero_as_additional_properties(self) -> None:
        for numeric_zero in ("0", "0.0"):
            with self.subTest(numeric_zero=numeric_zero):
                temporary, root = self.copy_contract_fixture()
                with temporary:
                    schema_path = root / SCHEMA
                    text = schema_path.read_text(encoding="utf-8")
                    old = '"additionalProperties": false'
                    self.assertEqual(text.count(old), 1)
                    schema_path.write_text(
                        text.replace(old, f'"additionalProperties": {numeric_zero}'),
                        encoding="utf-8",
                    )
                    self.assertTrue(self.validator()(root))

    def test_connected_branch_counts_are_two_events_and_two_outboxes(self) -> None:
        self.assert_contract_mutation_fails(
            "LeadContactResultRecordedV1,OpportunityOpened | 2 | 2 |",
            "LeadContactResultRecordedV1,OpportunityOpened | 1 | 1 |",
        )


if __name__ == "__main__":
    unittest.main()
