import tempfile
import unittest
from pathlib import Path

from scripts.baseline import verify_baseline as validator
from scripts.baseline.tests import test_verify_baseline as fixtures


TASK = "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
POLICY = "R1_DUPLICATE_AUTOMATIC_ASSIGNMENT_V1"
POLICY_HEADERS = (
    "PolicyID", "Eligibility", "PointerTransition", "AssignmentBinding",
    "OwnerSelection", "LeadCAS", "OtherOutcomes", "SelectorEvaluation",
    "CompletionProof", "Atomicity",
)
POLICY_VALUES = (
    POLICY,
    "R1_LEAD_NEXT_RESPONSIBILITY_V1:AUTOMATIC-valid-candidate;no-existing-OPEN-assignment",
    "current_assignment_id:null-to-exact-new-assignment-id;no-repoint-reuse-reassign",
    "NEW-OPEN-revision0;same-command-transaction;same-tenant;same-Lead;exact-selected-Owner",
    "existing-source-policy-order;current-authority-and-no-DENY;final-identity-revalidation;no-caller-selected-Owner",
    "single-final-CAS;resolution-and-conditional-pointer;revision=old+1",
    "manual-nonautomatic-empty-candidate:pointer-unchanged;assignment:+0",
    "resolved-prospective-Lead-under-existing-locks;successor-freezes-final-post-CAS-revision",
    "Decision-only-completion-Receipt-Event;resolution-only-digest;Assignment-independent-successor-Fact;no-LeadAssigned-event",
    "Draft-confirmation+Decision+conditional-Assignment+Lead-CAS+Task-DONE+exactly-one-successor+Receipt+Audit+Event+Outbox;rejection-frozen-deltas;technical-failure-all-0;replay-all-0",
)


class P0ContractAmendmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = fixtures.VerifyBaselineTest()
        self.fixture.create_valid_repository(self.root)

    def findings(self):
        findings = []
        validator.verify_r1_contracts(self.root, findings)
        return findings

    def mutate(self, row, column, value):
        path = self.root / TASK
        lines = path.read_text(encoding="utf-8").splitlines()
        headers = []
        for index, line in enumerate(lines):
            if not line.startswith("|"):
                headers = []
                continue
            cells = [cell.strip() for cell in line.strip("|").split("|")]
            if not headers:
                headers = cells
            elif cells[0] == row and column in headers:
                cells[headers.index(column)] = value
                lines[index] = fixtures.markdown_row(*cells)
                path.write_text("\n".join(lines) + "\n", encoding="utf-8")
                return
        self.fail(f"missing structured cell {row}/{column}")

    def test_exact_bounded_automatic_contract_is_accepted(self):
        self.assertEqual([], self.findings())

    def test_old_unconditional_pointer_prohibition_is_rejected(self):
        for branch, forbidden in (
            ("P0_01_LINK_EXISTING", "current_assignment_id,capture_fields,ingress_slot"),
            ("P0_01_KEEP_SEPARATE", "parsed_party_id,party_resolution_code,current_assignment_id,capture_fields,ingress_slot"),
        ):
            with self.subTest(branch=branch):
                self.fixture.write_r1_contract_fixture(self.root)
                self.mutate(branch, "ForbiddenCurrentLeadChanges", forbidden)
                self.assertTrue(self.findings())

    def test_each_assignment_bound_is_required_in_its_structured_cell(self):
        for column in POLICY_HEADERS[1:]:
            with self.subTest(column=column):
                self.fixture.write_r1_contract_fixture(self.root)
                self.mutate(POLICY, column, "NONE")
                self.assertTrue(self.findings())

    def test_arbitrary_pointer_and_two_revision_updates_are_rejected(self):
        for column, value in (
            ("PointerTransition", "current_assignment_id:any-existing-assignment"),
            ("LeadCAS", "two-CAS;revision=old+2"),
            ("SelectorEvaluation", "post-CAS-only;second-pass-required"),
            ("CompletionProof", "Assignment-completion-Receipt-Event;LeadAssignedV1"),
        ):
            with self.subTest(column=column):
                self.fixture.write_r1_contract_fixture(self.root)
                self.mutate(POLICY, column, value)
                self.assertTrue(self.findings())

    def test_resolution_fields_and_candidate_immutability_remain_closed(self):
        for branch in ("P0_01_LINK_EXISTING", "P0_01_KEEP_SEPARATE"):
            for column, value in (
                ("ForbiddenCurrentLeadChanges", "NONE"),
                ("CandidateLeadPartyMutation", "candidateLead.revision=old+1"),
                ("CurrentLeadCAS", "revision=old+2"),
                ("DecisionDigest", "assignmentId-only"),
                ("ConditionalAssignment", "ANY"),
            ):
                with self.subTest(branch=branch, column=column):
                    self.fixture.write_r1_contract_fixture(self.root)
                    self.mutate(branch, column, value)
                    self.assertTrue(self.findings())

    def test_completion_event_successor_and_e2e_counts_remain_closed(self):
        for column, value in (
            ("CompletionFactType", "lead.lead_assignment"),
            ("EventType", "LeadAssignedV1"),
            ("AllowedSuccessorTaskTypes", "NONE"),
        ):
            with self.subTest(column=column):
                self.fixture.write_r1_contract_fixture(self.root)
                self.mutate("P0_01_LINK_EXISTING", column, value)
                self.assertTrue(self.findings())
        for column, value in (
            ("FactDelta", "`decision_record:+1; lead revision:+2`"),
            ("SuccessorDelta", "`CONTACT_LEAD:+2`"),
            ("ReceiptEventOutboxAudit", "`receipt:+1,event:+2,outbox:+2,audit:+1`"),
        ):
            with self.subTest(column=column):
                self.fixture.write_r1_contract_fixture(self.root)
                self.mutate("E2E_P0_01_LINK", column, value)
                self.assertTrue(self.findings())

    def test_policy_registry_is_closed_and_cannot_be_replaced_by_prose(self):
        path = self.root / TASK
        original = path.read_text(encoding="utf-8")
        row = fixtures.markdown_row(*POLICY_VALUES)
        for replacement in ("", row + "\n" + row, row.replace(POLICY, "ANY_POLICY", 1)):
            with self.subTest(replacement=replacement):
                path.write_text(original.replace(row, replacement), encoding="utf-8")
                self.assertTrue(self.findings())
