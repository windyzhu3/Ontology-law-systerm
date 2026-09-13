import hashlib
import importlib.util
import inspect
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "e2e/runtime/r1_acceptance.py"
RUN = "unit-r1"
OPERATION = "00000000-0000-4000-8000-000000000901"
TENANT = "00000000-0000-4000-8000-000000000001"
ROOT_ORGANIZATION = "00000000-0000-4000-8000-000000000002"
FOUNDER = "00000000-0000-4000-8000-000000000003"
FOUNDER_APPOINTMENT = "00000000-0000-4000-8000-000000000004"
RESULT_DIGEST = "A" * 43


def load_module(test_case: unittest.TestCase):
    if not MODULE_PATH.is_file():
        test_case.fail("r1_acceptance implementation is missing")
    spec = importlib.util.spec_from_file_location("r1_acceptance", MODULE_PATH)
    if spec is None or spec.loader is None:
        test_case.fail("r1_acceptance implementation cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ControlledDependencies:
    def __init__(self, fixture, *, phase="APPLICATION_INFRASTRUCTURE_READY"):
        self.fixture = fixture
        self.phase = phase
        self.calls = []
        self.changed_pid = False

    def verify_applications(self, root, run):
        self.calls.append("verify-applications")
        return {
            "profile": "R1_E2E_APPLICATION_READINESS_V1",
            "run": run,
            "phase": self.phase,
            "bootstrapSource": "original",
            "applicationReady": self.phase == "APPLICATION_INFRASTRUCTURE_READY",
            "processes": {
                name: {"pid": process["pid"], "created": process["created"]}
                for name, process in self.fixture[2]["processes"].items()
            },
            "probes": {
                "spa": 200,
                "unauthenticatedSelf": 401,
                "serviceReadiness": 204,
                "serviceEmptyBody": True,
                "serviceNoEtag": True,
                "serviceNoStore": True,
            },
            "claims": ["APPLICATION_INFRASTRUCTURE_READY"],
        }

    def load_application(self, root, run, phases):
        self.calls.append(("load-application", phases))
        return self.fixture

    def verify_bootstrap(self, root, run, source):
        self.calls.append(("verify-bootstrap", source))
        return {
            "profile": "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1",
            "run": run,
            "tenants": {
                "R1_E2E_MAIN": {
                    "tenantId": TENANT,
                    "rootOrganizationId": ROOT_ORGANIZATION,
                    "founderPrincipalId": FOUNDER,
                    "appointmentId": FOUNDER_APPOINTMENT,
                    "authorityGrantIds": [
                        f"00000000-0000-4000-8010-{index:012d}" for index in range(1, 5)
                    ],
                    "subjectHmacPath": "identity-bootstrap/main/subject-hmac.txt",
                    "originalVerificationEvidenceSha256": "a" * 64,
                },
                "R1_E2E_ISOLATION": {
                    "tenantId": "00000000-0000-4000-8000-000000000101",
                    "rootOrganizationId": "00000000-0000-4000-8000-000000000102",
                    "founderPrincipalId": "00000000-0000-4000-8000-000000000103",
                    "appointmentId": "00000000-0000-4000-8000-000000000104",
                    "authorityGrantIds": [
                        f"00000000-0000-4000-8020-{index:012d}" for index in range(1, 5)
                    ],
                    "subjectHmacPath": "identity-bootstrap/isolation/subject-hmac.txt",
                    "originalVerificationEvidenceSha256": "b" * 64,
                },
            },
        }

    def assert_source_unchanged(self, root, runtime, manifest):
        self.calls.append("assert-source-unchanged")

    def process_snapshot(self, pid):
        self.calls.append(("process", pid))
        for process in self.fixture[2]["processes"].values():
            if process["pid"] == pid:
                if self.changed_pid:
                    return {**process, "created": "2026-09-13T01:00:00Z"}
                return dict(process)
        return None

    def same_process(self, expected, actual):
        return expected == actual


class R1AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module(self)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.runtime = self.root / ".artifacts/r1-e2e" / RUN
        (self.runtime / "secrets").mkdir(parents=True)
        for account in ("founder", "sales", "supervisor", "sourceOwner", "revokedAppointment"):
            (self.runtime / "secrets" / f"{account}-password.txt").write_text(
                f"private-{account}\n", encoding="utf-8"
            )
        (self.runtime / "identity-bootstrap/main").mkdir(parents=True)
        (self.runtime / "identity-bootstrap/main/subject-hmac.txt").write_text("hidden\n")
        (self.runtime / "identity-bootstrap/isolation").mkdir(parents=True)
        (self.runtime / "identity-bootstrap/isolation/subject-hmac.txt").write_text("hidden\n")
        processes = {
            "api": self.process(101, "api"),
            "worker": self.process(102, "worker"),
            "spa": self.process(103, "spa"),
        }
        self.folder = self.runtime / "applications"
        self.fixture = (
            self.runtime,
            self.folder,
            {
                "profile": "R1_E2E_APPLICATION_STATE_V1",
                "run": RUN,
                "phase": "APPLICATION_INFRASTRUCTURE_READY",
                "bootstrapSource": "original",
                "bootstrapInputSha256": "c" * 64,
                "applicationReady": True,
                "processes": processes,
            },
            {
                "profile": "R1_E2E_APPLICATIONS_V1",
                "run": RUN,
                "bootstrapSource": "original",
                "bootstrapInputSha256": "c" * 64,
                "environmentManifestSha256": "d" * 64,
                "schemaManifestSha256": "e" * 64,
                "serverSourceSha256": "f" * 64,
                "artifacts": {
                    "jar": {"path": "artifacts/app.jar", "sha256": "1" * 64},
                    "spa": {"path": "artifacts/workbench-dist", "sha256": "2" * 64, "files": {}},
                },
                "requiredFiles": {"api.properties": "3" * 64},
            },
            {
                "profile": "R1_E2E_ENVIRONMENT_V1",
                "run": RUN,
                "issuer": "https://localhost:29443/realms/r1-e2e",
                "spaOrigin": "https://localhost:29444",
                "apiOrigin": "https://localhost:29445",
                "schemaManifestSha256": "e" * 64,
                "artifacts": {
                    "jar": {"path": "artifacts/app.jar", "sha256": "1" * 64},
                    "spa": {"path": "artifacts/workbench-dist", "sha256": "2" * 64, "files": {}},
                },
                "sourceDigests": {"e2e/fixtures/r1-fixture.json": "4" * 64},
                "sourceGitSnapshot": {"commit": "5" * 40, "trackedProductSourcesClean": True},
            },
            {
                "profile": "R1_E2E_SERVICE_INFRASTRUCTURE_V1",
                "bootstrapInputSha256": "c" * 64,
                "tenantId": TENANT,
                "rootOrganizationId": ROOT_ORGANIZATION,
                "founderPrincipalId": FOUNDER,
                "founderAppointmentId": FOUNDER_APPOINTMENT,
            },
        )

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def process(pid, name):
        return {
            "pid": pid,
            "startedAt": "2026-09-13T00:00:00Z",
            "created": "2026-09-13T00:00:01Z",
            "executable": f"C:/pinned/{name}.exe",
            "commandLine": f"C:/pinned/{name}.exe --isolated",
        }

    def test_verified_environment_is_required_before_publication_and_pid_is_rechecked(self):
        dependencies = ControlledDependencies(self.fixture, phase="STARTED_UNVERIFIED")
        with self.assertRaisesRegex(RuntimeError, "application readiness"):
            self.module.load_acceptance_environment(
                self.root, RUN, OPERATION, dependencies=dependencies
            )
        self.assertEqual([
            ("load-application", ("APPLICATION_INFRASTRUCTURE_READY",)),
            "verify-applications",
        ], dependencies.calls)

        dependencies = ControlledDependencies(self.fixture)
        dependencies.changed_pid = True
        with self.assertRaisesRegex(RuntimeError, "process identity"):
            self.module.load_acceptance_environment(
                self.root, RUN, OPERATION, dependencies=dependencies
            )
        self.assertNotIn(("verify-bootstrap", "original"), dependencies.calls)

    def test_non_ready_saved_state_uses_real_loader_before_verifier_without_mutation(self):
        self.folder.mkdir()
        state_path = self.folder / "state.json"
        state_path.write_text(json.dumps({
            "profile": "R1_E2E_APPLICATION_STATE_V1", "run": RUN,
            "phase": "STARTED_UNVERIFIED", "applicationReady": False,
        }), encoding="utf-8")
        (self.folder / "manifest.json").write_text("{}", encoding="utf-8")
        original = state_path.read_bytes()

        class RealLoaderDependencies:
            def __init__(nested):
                nested.verifier_calls = []

            def load_application(nested, root, run, phases):
                return self.module.applications._load_application(root, run, phases)

            def verify_applications(nested, root, run):
                nested.verifier_calls.append((root, run))
                raise RuntimeError("forbidden non-ready verifier call")

        dependencies = RealLoaderDependencies()
        with self.assertRaisesRegex(RuntimeError, "applications not consumable"):
            self.module.load_acceptance_environment(
                self.root, RUN, OPERATION, dependencies=dependencies,
            )
        self.assertEqual([], dependencies.verifier_calls)
        self.assertEqual(original, state_path.read_bytes())

    def test_public_environment_contains_only_current_run_references_and_stable_binding(self):
        dependencies = ControlledDependencies(self.fixture)
        value = self.module.load_acceptance_environment(
            self.root, RUN, OPERATION, dependencies=dependencies
        )
        self.assertEqual("R1_ISOLATED_ACCEPTANCE_INPUT_V1", value["profile"])
        self.assertEqual(RUN, value["run"])
        self.assertEqual(OPERATION, value["operationId"])
        self.assertEqual(
            {
                "founder": "secrets/founder-password.txt",
                "sales": "secrets/sales-password.txt",
                "supervisor": "secrets/supervisor-password.txt",
                "sourceOwner": "secrets/sourceOwner-password.txt",
            },
            value["credentialReferences"],
        )
        self.assertEqual(
            {
                "founder": f"r1-{RUN}-founder",
                "sales": f"r1-{RUN}-sales",
                "supervisor": f"r1-{RUN}-supervisor",
                "sourceOwner": f"r1-{RUN}-source-owner",
                "revokedAppointment": f"r1-{RUN}-revoked",
            },
            value["usernames"],
        )
        serialized = json.dumps(value, sort_keys=True)
        self.assertNotIn("private-", serialized)
        self.assertNotIn("subject-hmac", serialized)
        binding = value.pop("environmentDigest")
        self.assertEqual(
            hashlib.sha256(
                json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
            ).hexdigest(),
            binding,
        )
        self.assertEqual(
            [
                ("load-application", ("APPLICATION_INFRASTRUCTURE_READY",)),
                "verify-applications",
                ("process", 101),
                ("process", 102),
                ("process", 103),
                "assert-source-unchanged",
                ("verify-bootstrap", "original"),
            ],
            dependencies.calls,
        )

    def test_fixed_management_blueprint_preserves_fifteen_then_adds_owned_root_sales_authority(self):
        plan = self.module.management_blueprint()
        self.assertEqual(
            [
                "principal-sales", "principal-supervisor", "principal-sourceOwner",
                "organization-OWNED_ROOT", "organization-EMPTY_ROOT",
                "appointment-sales", "appointment-supervisor", "appointment-sourceOwner",
                "grant-sourceOwner-LEAD_CAPTURE", "grant-sourceOwner-LEAD_INGRESS_RESOLVE",
                "grant-sourceOwner-LEAD_INGRESS_COMPLETE", "grant-sourceOwner-SOURCE_INTAKE_REQUEST_ACK",
                "grant-supervisor-LEAD_ASSIGN", "grant-supervisor-LEAD_ROUTING_DECIDE",
                "grant-supervisor-LEAD_VALIDITY_REVIEW",
                "grant-sales-SALES_CONTACT_OWNER",
            ],
            [entry["step"] for entry in plan],
        )
        self.assertEqual(["POST"] * 16, [entry["method"] for entry in plan])
        self.assertEqual(
            ["CONTACT_OPERATOR", "ROUTING_SUPERVISOR", "INTAKE_OPERATOR"],
            [entry["roleCode"] for entry in plan if entry["kind"] == "appointment"],
        )
        self.assertNotIn("revokedAppointment", json.dumps(plan))
        self.assertEqual(
            {"account": "sales", "authorityCode": "SALES_CONTACT_OWNER", "scope": "OWNED_ROOT"},
            {key: plan[-1][key] for key in ("account", "authorityCode", "scope")},
        )

    def test_golden_completion_requires_unique_exact_facts_and_original_receipt(self):
        completion = self.completion()
        self.module.validate_golden_completion(completion, RESULT_DIGEST)
        for collection in ("contactResults", "opportunities", "tasks", "drafts", "receipts", "audits"):
            broken = self.completion()
            broken[collection] = []
            with self.subTest(collection=collection, problem="missing"):
                with self.assertRaisesRegex(RuntimeError, "golden completion"):
                    self.module.validate_golden_completion(broken, RESULT_DIGEST)
            broken = self.completion()
            broken[collection].append(dict(broken[collection][0]))
            with self.subTest(collection=collection, problem="duplicate"):
                with self.assertRaisesRegex(RuntimeError, "golden completion"):
                    self.module.validate_golden_completion(broken, RESULT_DIGEST)
        broken = self.completion()
        broken["receipts"][0]["commandId"] = "00000000-0000-4000-8000-000000000999"
        with self.assertRaisesRegex(RuntimeError, "golden completion"):
            self.module.validate_golden_completion(broken, RESULT_DIGEST)

    def test_ingress_binding_rejects_wrong_actor_task_and_causal_lead(self):
        value = self.ingress_binding()
        expected = {
            "phase": "COMPLETED", "captureCommandId": value["captureReceipt"]["commandId"],
            "ingressCommandId": value["ingressReceipt"]["commandId"],
            "ingressTaskId": value["ingressTask"]["id"], "ingressDraftId": value["ingressDraft"]["id"],
            "sourceOwnerAppointmentId": value["ingressTask"]["ownerAppointmentId"],
            "salesOwnerAppointmentId": value["contactTask"]["ownerAppointmentId"],
            "contactTaskId": value["contactTask"]["id"],
        }
        self.module.validate_ingress_binding(value, expected)
        for mutate in (
            lambda item: item["ingressTask"].__setitem__("ownerAppointmentId", "00000000-0000-4000-8000-000000000799"),
            lambda item: item["ingressDraft"].__setitem__("taskId", "00000000-0000-4000-8000-000000000799"),
            lambda item: item["ingressReceipt"].__setitem__("resultFactId", "00000000-0000-4000-8000-000000000799"),
            lambda item: item["contactTask"].__setitem__("subjectFactId", "00000000-0000-4000-8000-000000000799"),
        ):
            broken = self.ingress_binding(); mutate(broken)
            with self.assertRaisesRegex(RuntimeError, "ingress binding"):
                self.module.validate_ingress_binding(broken, expected)

    def test_ingress_binding_query_is_read_only_and_excludes_contact_values(self):
        value = self.ingress_binding()
        sql = self.module.ingress_binding_query(
            TENANT, value["captureReceipt"]["commandId"], value["ingressTask"]["id"],
            value["ingressTask"]["ownerAppointmentId"], value["ingressReceipt"]["commandId"],
            value["ingressDraft"]["id"], value["contactTask"]["id"],
            value["contactTask"]["ownerAppointmentId"],
        ).decode("utf-8")
        self.assertIn("BEGIN READ ONLY", sql)
        self.assertIn("SET LOCAL ROLE law_app_query", sql)
        for table in ("execution.command_receipt", "responsibility.task_occurrence", "responsibility.action_draft", "lead.lead_assignment"):
            self.assertIn(table, sql)
        for forbidden in ("candidate_payload", "phone", "email", "ciphertext", "hmac", "password", "jwt"):
            self.assertNotIn(forbidden, sql.lower())

    def test_captured_binding_requires_an_uncompleted_open_ingress_task(self):
        value = self.ingress_binding()
        value.update({"phase": "CAPTURED", "ingressDraft": None, "ingressReceipt": None, "assignment": None, "contactTask": None})
        value["ingressTask"].update({"state": "OPEN", "revision": 0, "completionFactType": None, "completionFactId": None, "completionFactRevision": None})
        expected = {
            "phase": "CAPTURED", "captureCommandId": value["captureReceipt"]["commandId"],
            "ingressTaskId": value["ingressTask"]["id"],
            "sourceOwnerAppointmentId": value["ingressTask"]["ownerAppointmentId"],
        }
        self.module.validate_ingress_binding(value, expected)
        value["ingressTask"]["completionFactId"] = value["captureReceipt"]["resultFactId"]
        with self.assertRaisesRegex(RuntimeError, "ingress binding"):
            self.module.validate_ingress_binding(value, expected)

    def test_golden_completion_rejects_http_task_or_receipt_digest_mismatch(self):
        if len(inspect.signature(self.module.validate_golden_completion).parameters) != 2:
            self.fail("expected digest closure implementation is missing")
        for target in ("expected", "task", "receipt"):
            completion = self.completion()
            expected = RESULT_DIGEST
            if target == "expected":
                expected = "B" * 43
            elif target == "task":
                completion["tasks"][0]["completionFactHash"] = "B" * 43
            else:
                completion["receipts"][0]["resultFactHash"] = "B" * 43
            with self.subTest(target=target):
                with self.assertRaisesRegex(RuntimeError, "golden completion"):
                    self.module.validate_golden_completion(completion, expected)

    def test_golden_completion_requires_two_exact_events_and_outboxes(self):
        for collection in ("events", "outboxes"):
            for mutation in ("missing", "duplicate"):
                broken = self.completion()
                if mutation == "missing":
                    broken[collection].pop()
                else:
                    broken[collection].append(dict(broken[collection][0]))
                with self.subTest(collection=collection, mutation=mutation):
                    with self.assertRaisesRegex(RuntimeError, "golden completion"):
                        self.module.validate_golden_completion(broken, RESULT_DIGEST)

    def test_completion_query_is_read_only_uses_allowed_audit_view_and_excludes_sensitive_columns(self):
        completion = self.completion()
        if not hasattr(self.module, "completion_query"):
            self.fail("completion_query implementation is missing")
        sql = self.module.completion_query(
            TENANT, completion["commandId"], completion["tasks"][0]["id"],
            completion["drafts"][0]["id"], completion["tasks"][0]["ownerAppointmentId"], RESULT_DIGEST,
            completion["ingress"]["captureReceipt"]["commandId"], completion["ingress"]["ingressReceipt"]["commandId"],
            completion["ingress"]["ingressTask"]["id"], completion["ingress"]["ingressDraft"]["id"],
            completion["ingress"]["ingressTask"]["ownerAppointmentId"],
        ).decode("utf-8")
        self.assertIn("BEGIN READ ONLY", sql)
        self.assertIn("SET LOCAL ROLE law_app_query", sql)
        self.assertIn("audit.audit_entry_classified_v", sql)
        self.assertIn("completion_fact_hash", sql)
        self.assertIn("result_fact_hash", sql)
        self.assertNotIn("FROM audit.audit_entry ", sql)
        for forbidden in (
            "candidate_payload", "legal_need_ciphertext", "legal_need_digest",
            "result_summary", "event_payload", "change_summary", "jwt", "password",
        ):
            self.assertNotIn(forbidden, sql.lower())
        for identifier in (
            TENANT, completion["commandId"], completion["tasks"][0]["id"],
            completion["drafts"][0]["id"], completion["tasks"][0]["ownerAppointmentId"],
        ):
            self.assertIn(identifier, sql)

    def test_golden_completion_rejects_broken_supplemented_ingress_chain(self):
        for target in ("captureReceipt", "ingressTask", "ingressDraft", "ingressReceipt", "assignment", "contactTask"):
            broken = self.completion()
            broken["ingress"][target]["resultFactId" if target in ("captureReceipt", "ingressReceipt") else "leadId" if target == "assignment" else "subjectFactId" if target in ("ingressTask", "contactTask") else "taskId"] = "00000000-0000-4000-8000-000000000799"
            with self.subTest(target=target):
                with self.assertRaisesRegex(RuntimeError, "golden completion"):
                    self.module.validate_golden_completion(broken, RESULT_DIGEST)

    def test_closed_report_whitelists_public_evidence_and_redacts_nested_secrets(self):
        report = self.module.closed_report({
            "run": RUN,
            "operationId": OPERATION,
            "status": "GOLDEN_VERIFIED",
            "environmentDigest": "a" * 64,
            "managementCommandCount": 16,
            "flowProfile": "CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1",
            "goldenCommandId": self.completion()["commandId"],
            "resultFact": {"factType": "LEAD_CONTACT_RESULT", "factRef": "opaque-ref"},
            "counts": {"contactResult": 1, "opportunity": 1, "event": 2, "outbox": 2, "receipt": 1, "audit": 1},
            "credentials": {"password": "secret-value", "jwt": "ey.private"},
            "contact": {"phone": "+8613800000000", "ciphertext": "private-cipher"},
            "rawError": "secret stack",
        })
        self.assertEqual(
            {"profile", "run", "operationId", "status", "environmentDigest", "managementCommandCount", "flowProfile", "goldenCommandId", "resultFact", "counts"},
            set(report),
        )
        serialized = json.dumps(report)
        for forbidden in ("secret-value", "ey.private", "+8613800000000", "private-cipher", "secret stack"):
            self.assertNotIn(forbidden, serialized)

    def completion(self):
        command = "00000000-0000-4000-8000-000000000701"
        contact = "00000000-0000-4000-8000-000000000702"
        opportunity = "00000000-0000-4000-8000-000000000703"
        task = "00000000-0000-4000-8000-000000000704"
        draft = "00000000-0000-4000-8000-000000000705"
        lead = "00000000-0000-4000-8000-000000000706"
        assignment = "00000000-0000-4000-8000-000000000707"
        owner = "00000000-0000-4000-8000-000000000708"
        event_contact = "00000000-0000-4000-8000-000000000709"
        event_opportunity = "00000000-0000-4000-8000-000000000710"
        ingress = self.ingress_binding()
        return {
            "profile": "R1_GOLDEN_COMPLETION_V1",
            "tenantId": TENANT,
            "commandId": command,
            "flowProfile": "CAPTURE_INGRESS_AUTOASSIGN_CONTACT_V1",
            "ingress": {
                **ingress,
                "contactTask": {**ingress["contactTask"], "state": "DONE", "revision": 1},
            },
            "contactResults": [{"id": contact, "leadId": lead, "assignmentId": assignment, "taskId": task, "contactNo": 1, "resultCode": "CONNECTED_VALID"}],
            "opportunities": [{"id": opportunity, "leadId": lead, "assignmentId": assignment, "ownerAppointmentId": owner, "sourceContactResultId": contact, "revision": 0}],
            "tasks": [{"id": task, "ownerAppointmentId": owner, "state": "DONE", "revision": 1, "completionFactType": "lead.lead_contact_result", "completionFactId": contact, "completionFactHash": RESULT_DIGEST}],
            "drafts": [{"id": draft, "taskId": task, "state": "CONFIRMED", "revision": 1, "confirmedByAppointmentId": owner}],
            "receipts": [{"commandId": command, "outcome": "SUCCEEDED", "resultFactType": "lead.lead_contact_result", "resultFactId": contact, "resultFactHash": RESULT_DIGEST}],
            "audits": [{"commandId": command, "commandType": "RECORD_CONTACT_RESULT", "resultCode": "SUCCEEDED", "actorAppointmentId": owner, "onBehalfOfAppointmentId": None}],
            "events": [
                {"id": event_contact, "type": "LeadContactResultRecordedV1", "sourceFactType": "lead.lead_contact_result", "sourceFactId": contact},
                {"id": event_opportunity, "type": "OpportunityOpened", "sourceFactType": "opportunity.opportunity", "sourceFactId": opportunity},
            ],
            "outboxes": [
                {"eventId": event_contact, "eventType": "LeadContactResultRecordedV1"},
                {"eventId": event_opportunity, "eventType": "OpportunityOpened"},
            ],
        }

    def ingress_binding(self):
        lead = "00000000-0000-4000-8000-000000000706"
        ingress_task = "00000000-0000-4000-8000-000000000711"
        ingress_draft = "00000000-0000-4000-8000-000000000712"
        source_owner = "00000000-0000-4000-8000-000000000713"
        sales_owner = "00000000-0000-4000-8000-000000000708"
        contact_task = "00000000-0000-4000-8000-000000000704"
        capture_command = "00000000-0000-4000-8000-000000000714"
        ingress_command = "00000000-0000-4000-8000-000000000715"
        assignment = "00000000-0000-4000-8000-000000000707"
        return {
            "profile": "R1_INGRESS_BINDING_V1", "phase": "COMPLETED", "tenantId": TENANT,
            "captureReceipt": {"commandId": capture_command, "outcome": "SUCCEEDED", "resultFactType": "lead.lead", "resultFactId": lead, "resultFactRevision": 0},
            "ingressTask": {"id": ingress_task, "ownerAppointmentId": source_owner, "primaryCommandCode": "COMPLETE_LEAD_INGRESS", "state": "DONE", "revision": 1, "subjectFactType": "lead.lead", "subjectFactId": lead, "subjectFactRevision": 0, "completionFactType": "lead.lead", "completionFactId": lead, "completionFactRevision": 1},
            "ingressDraft": {"id": ingress_draft, "taskId": ingress_task, "actionCode": "COMPLETE_LEAD_INGRESS", "state": "CONFIRMED", "revision": 1, "createdByAppointmentId": source_owner, "confirmedByAppointmentId": source_owner},
            "ingressReceipt": {"commandId": ingress_command, "outcome": "SUCCEEDED", "resultFactType": "lead.lead", "resultFactId": lead, "resultFactRevision": 1},
            "assignment": {"id": assignment, "leadId": lead, "ownerAppointmentId": sales_owner, "assignmentNo": 1, "reasonCode": "SOURCE_POLICY_AUTOMATIC", "state": "OPEN", "revision": 0},
            "contactTask": {"id": contact_task, "ownerAppointmentId": sales_owner, "primaryCommandCode": "RECORD_CONTACT_RESULT", "state": "OPEN", "revision": 0, "subjectFactType": "lead.lead", "subjectFactId": lead, "subjectFactRevision": 1},
        }


if __name__ == "__main__":
    unittest.main()
