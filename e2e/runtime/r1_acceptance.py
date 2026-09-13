"""Verify and project one isolated R1 golden-acceptance run.

The module never starts services, changes trust, creates identity-provider users,
or dispatches a business command.  It exposes only current-run public metadata
and protected relative credential references to the Playwright controller.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import uuid
from pathlib import Path
from typing import Any


RUNTIME_DIRECTORY = Path(__file__).resolve().parent
if str(RUNTIME_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(RUNTIME_DIRECTORY))
import r1_applications as applications  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
INPUT_PROFILE = "R1_ISOLATED_ACCEPTANCE_INPUT_V1"
COMPLETION_PROFILE = "R1_GOLDEN_COMPLETION_V1"
READY_PHASE = "APPLICATION_INFRASTRUCTURE_READY"
UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")
FACT_DIGEST_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")


def _canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _digest(value: object) -> str:
    return hashlib.sha256(_canonical(value)).hexdigest()


def _uuid(value: object) -> bool:
    if not isinstance(value, str) or not UUID_PATTERN.fullmatch(value):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def _exact(value: object, keys: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == keys


def _relative_regular(runtime: Path, relative: str) -> str:
    path = runtime / relative
    if Path(relative).is_absolute() or ".." in Path(relative).parts or not path.is_file() or path.is_symlink():
        raise RuntimeError("protected credential reference invalid")
    resolved = path.resolve(strict=True)
    if runtime != resolved and runtime not in resolved.parents:
        raise RuntimeError("protected credential reference invalid")
    return Path(relative).as_posix()


class _Dependencies:
    verify_applications = staticmethod(applications.verify_applications)
    load_application = staticmethod(applications._load_application)
    verify_bootstrap = staticmethod(applications._verify_bootstrap_source)
    assert_source_unchanged = staticmethod(applications.environment._assert_unchanged)
    process_snapshot = staticmethod(applications._process_snapshot)
    same_process = staticmethod(applications._same_process)


def load_acceptance_environment(
    root: Path, run: str, operation_id: str, *, dependencies: Any | None = None,
) -> dict:
    """Return a sanitized, digest-bound projection of a currently READY run."""
    if not _uuid(operation_id):
        raise RuntimeError("acceptance operation identity invalid")
    root = Path(root).resolve(strict=True)
    dependency = dependencies or _Dependencies()
    runtime, _, state, app_manifest, environment_manifest, service = dependency.load_application(
        root, run, (READY_PHASE,),
    )
    runtime = Path(runtime).resolve(strict=True)
    if state.get("phase") != READY_PHASE or state.get("applicationReady") is not True or \
       state.get("run") != run or app_manifest.get("run") != run or environment_manifest.get("run") != run:
        raise RuntimeError("application readiness state mismatch")

    readiness = dependency.verify_applications(root, run)
    if not _exact(readiness, {
        "profile", "run", "phase", "bootstrapSource", "applicationReady",
        "processes", "probes", "claims",
    }) or readiness.get("profile") != "R1_E2E_APPLICATION_READINESS_V1" or \
       readiness.get("run") != run or readiness.get("phase") != READY_PHASE or \
       readiness.get("applicationReady") is not True or readiness.get("claims") != [READY_PHASE]:
        raise RuntimeError("application readiness unavailable")

    expected_processes = state.get("processes")
    if not isinstance(expected_processes, dict) or set(expected_processes) != {"api", "worker", "spa"}:
        raise RuntimeError("application process identity mismatch")
    if readiness.get("processes") != {
        name: {"pid": value.get("pid"), "created": value.get("created")}
        for name, value in expected_processes.items()
    }:
        raise RuntimeError("application process identity mismatch")
    public_processes: dict[str, dict] = {}
    for name in ("api", "worker", "spa"):
        expected = expected_processes[name]
        actual = dependency.process_snapshot(expected.get("pid"))
        if not dependency.same_process(expected, actual):
            raise RuntimeError(f"{name} process identity mismatch")
        public_processes[name] = {
            "pid": expected["pid"], "created": expected["created"],
            "identitySha256": _digest(expected),
        }

    dependency.assert_source_unchanged(root, runtime, environment_manifest)
    source = state.get("bootstrapSource")
    if source not in {"original", "continued"}:
        raise RuntimeError("bootstrap source invalid")
    bootstrap = dependency.verify_bootstrap(root, run, source)
    if bootstrap.get("profile") != "R1_E2E_VERIFIED_IDENTITY_BOOTSTRAP_INPUT_V1" or \
       bootstrap.get("run") != run or set(bootstrap.get("tenants", {})) != {"R1_E2E_MAIN", "R1_E2E_ISOLATION"}:
        raise RuntimeError("bootstrap projection invalid")
    main = bootstrap["tenants"]["R1_E2E_MAIN"]
    identifiers = [main.get(key) for key in (
        "tenantId", "rootOrganizationId", "founderPrincipalId", "appointmentId",
    )]
    grants = main.get("authorityGrantIds")
    if not all(_uuid(value) for value in identifiers) or not isinstance(grants, list) or \
       len(grants) != 4 or len(set(grants)) != 4 or not all(_uuid(value) for value in grants):
        raise RuntimeError("bootstrap projection invalid")
    if service.get("tenantId") != main["tenantId"] or \
       service.get("rootOrganizationId") != main["rootOrganizationId"] or \
       service.get("founderPrincipalId") != main["founderPrincipalId"] or \
       service.get("founderAppointmentId") != main["appointmentId"]:
        raise RuntimeError("SERVICE bootstrap binding mismatch")

    credentials = {
        account: _relative_regular(runtime, f"secrets/{account}-password.txt")
        for account in ("founder", "sales", "supervisor", "sourceOwner")
    }
    username_stems = {
        "founder": "founder", "sales": "sales", "supervisor": "supervisor",
        "sourceOwner": "source-owner", "revokedAppointment": "revoked",
    }
    commit = environment_manifest.get("sourceGitSnapshot", {}).get("commit")
    clean = environment_manifest.get("sourceGitSnapshot", {}).get("trackedProductSourcesClean")
    if not isinstance(commit, str) or not COMMIT_PATTERN.fullmatch(commit) or clean is not True:
        raise RuntimeError("source git snapshot invalid")
    artifacts = environment_manifest.get("artifacts", {})
    jar = artifacts.get("jar", {})
    spa = artifacts.get("spa", {})
    schema = environment_manifest.get("schemaManifestSha256")
    if not all(isinstance(value, str) and HASH_PATTERN.fullmatch(value) for value in (
        jar.get("sha256"), spa.get("sha256"), schema,
        app_manifest.get("environmentManifestSha256"), app_manifest.get("serverSourceSha256"),
    )):
        raise RuntimeError("artifact binding invalid")
    config_hashes = app_manifest.get("requiredFiles")
    if not isinstance(config_hashes, dict) or not config_hashes or \
       not all(isinstance(key, str) and isinstance(value, str) and HASH_PATTERN.fullmatch(value)
               for key, value in config_hashes.items()):
        raise RuntimeError("application configuration binding invalid")

    result = {
        "profile": INPUT_PROFILE,
        "run": run,
        "operationId": operation_id,
        "origin": environment_manifest.get("spaOrigin"),
        "apiOrigin": environment_manifest.get("apiOrigin"),
        "issuer": environment_manifest.get("issuer"),
        "sourceCommit": commit,
        "bootstrapSource": source,
        "bootstrap": {
            "tenantId": main["tenantId"], "rootId": main["rootOrganizationId"],
            "founderId": main["founderPrincipalId"], "founderAppointmentId": main["appointmentId"],
            "originalGrantIds": list(grants),
        },
        "usernames": {key: f"r1-{run}-{stem}" for key, stem in username_stems.items()},
        "credentialReferences": credentials,
        "artifacts": {"jarSha256": jar["sha256"], "spaSha256": spa["sha256"], "schemaSha256": schema},
        "configurationSha256": _digest(dict(sorted(config_hashes.items()))),
        "sourceSha256": _digest(environment_manifest.get("sourceDigests")),
        "processes": public_processes,
    }
    if not all(isinstance(result[key], str) and result[key] for key in ("origin", "apiOrigin", "issuer")):
        raise RuntimeError("application origin binding invalid")
    result["environmentDigest"] = _digest(result)
    return result


def management_blueprint() -> list[dict]:
    """The only management writes allowed for this golden fixture."""
    principals = [
        {"step": f"principal-{account}", "kind": "principal", "method": "POST",
         "path": "/api/v1/admin/identity/principals", "account": account}
        for account in ("sales", "supervisor", "sourceOwner")
    ]
    organizations = [
        {"step": f"organization-{code}", "kind": "organization", "method": "POST",
         "path": "/api/v1/admin/identity/organizations", "code": code}
        for code in ("OWNED_ROOT", "EMPTY_ROOT")
    ]
    appointments = [
        {"step": f"appointment-{account}", "kind": "appointment", "method": "POST",
         "path": "/api/v1/admin/identity/appointments", "account": account, "organization": organization,
         "roleCode": role}
        for account, organization, role in (
            ("sales", "OWNED_ROOT", "CONTACT_OPERATOR"),
            ("supervisor", "ROOT", "ROUTING_SUPERVISOR"),
            ("sourceOwner", "ROOT", "INTAKE_OPERATOR"),
        )
    ]
    grants = [
        {"step": f"grant-{account}-{authority}", "kind": "grant", "method": "POST",
         "path": "/api/v1/admin/identity/authority-grants", "account": account,
         "authorityCode": authority, "scope": "ROOT"}
        for account, authority in (
            ("sourceOwner", "LEAD_CAPTURE"),
            ("sourceOwner", "LEAD_INGRESS_RESOLVE"),
            ("sourceOwner", "LEAD_INGRESS_COMPLETE"),
            ("sourceOwner", "SOURCE_INTAKE_REQUEST_ACK"),
            ("supervisor", "LEAD_ASSIGN"),
            ("supervisor", "LEAD_ROUTING_DECIDE"),
            ("supervisor", "LEAD_VALIDITY_REVIEW"),
        )
    ]
    grants.append({"step": "grant-sales-SALES_CONTACT_OWNER", "kind": "grant", "method": "POST",
                   "path": "/api/v1/admin/identity/authority-grants", "account": "sales",
                   "authorityCode": "SALES_CONTACT_OWNER", "scope": "OWNED_ROOT"})
    return [*principals, *organizations, *appointments, *grants]


def _single(value: dict, key: str) -> dict:
    rows = value.get(key)
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        raise RuntimeError("golden completion mismatch")
    return rows[0]


def validate_golden_completion(value: object, expected_digest: str) -> dict:
    """Validate the closed CONTACT_CONNECTED_VALID database projection."""
    if not isinstance(value, dict) or value.get("profile") != COMPLETION_PROFILE or \
       not _uuid(value.get("tenantId")) or not _uuid(value.get("commandId")) or \
       not isinstance(expected_digest, str) or not FACT_DIGEST_PATTERN.fullmatch(expected_digest):
        raise RuntimeError("golden completion mismatch")
    try:
        contact = _single(value, "contactResults")
        opportunity = _single(value, "opportunities")
        task = _single(value, "tasks")
        draft = _single(value, "drafts")
        receipt = _single(value, "receipts")
        audit = _single(value, "audits")
        events = value.get("events")
        outboxes = value.get("outboxes")
        if not isinstance(events, list) or len(events) != 2 or not all(isinstance(row, dict) for row in events) or \
           not isinstance(outboxes, list) or len(outboxes) != 2 or not all(isinstance(row, dict) for row in outboxes):
            raise ValueError
        command = value["commandId"]
        contact_id = contact.get("id")
        opportunity_id = opportunity.get("id")
        task_id = task.get("id")
        owner = opportunity.get("ownerAppointmentId")
        if not all(_uuid(item) for item in (
            contact_id, opportunity_id, task_id, owner, contact.get("leadId"),
            contact.get("assignmentId"), draft.get("id"),
        )):
            raise ValueError
        if contact.get("taskId") != task_id or contact.get("contactNo") != 1 or \
           contact.get("resultCode") != "CONNECTED_VALID":
            raise ValueError
        if opportunity.get("leadId") != contact.get("leadId") or \
           opportunity.get("assignmentId") != contact.get("assignmentId") or \
           opportunity.get("sourceContactResultId") != contact_id or opportunity.get("revision") != 0:
            raise ValueError
        if task.get("ownerAppointmentId") != owner or task.get("state") != "DONE" or \
           task.get("revision") != 1 or task.get("completionFactType") != "LEAD_CONTACT_RESULT" or \
           task.get("completionFactId") != contact_id or task.get("completionFactHash") != expected_digest:
            raise ValueError
        if draft.get("taskId") != task_id or draft.get("state") != "CONFIRMED" or \
           draft.get("revision") != 1 or draft.get("confirmedByAppointmentId") != owner:
            raise ValueError
        if receipt != {"commandId": command, "outcome": "SUCCEEDED", "resultFactType": "LEAD_CONTACT_RESULT", "resultFactId": contact_id, "resultFactHash": expected_digest}:
            raise ValueError
        if audit.get("commandId") != command or audit.get("commandType") != "RECORD_CONTACT_RESULT" or \
           audit.get("resultCode") != "SUCCEEDED" or audit.get("actorAppointmentId") != owner or \
           audit.get("onBehalfOfAppointmentId") is not None:
            raise ValueError
        expected_events = {
            ("LeadContactResultRecordedV1", "LEAD_CONTACT_RESULT", contact_id),
            ("OpportunityOpened", "OPPORTUNITY", opportunity_id),
        }
        actual_events = {(row.get("type"), row.get("sourceFactType"), row.get("sourceFactId")) for row in events}
        event_ids = {row.get("id") for row in events}
        if actual_events != expected_events or len(event_ids) != 2 or not all(_uuid(item) for item in event_ids):
            raise ValueError
        actual_outboxes = {(row.get("eventId"), row.get("eventType")) for row in outboxes}
        expected_outboxes = {(row["id"], row["type"]) for row in events}
        if actual_outboxes != expected_outboxes:
            raise ValueError
    except (KeyError, TypeError, ValueError):
        raise RuntimeError("golden completion mismatch") from None
    return value


def completion_query(
    tenant_id: str, command_id: str, task_id: str, draft_id: str,
    owner_id: str, expected_digest: str,
) -> bytes:
    """Build the fixed, read-only closure query without protected content columns."""
    if not all(_uuid(value) for value in (tenant_id, command_id, task_id, draft_id, owner_id)) or \
       not isinstance(expected_digest, str) or not FACT_DIGEST_PATTERN.fullmatch(expected_digest):
        raise RuntimeError("golden completion selector invalid")
    literal = applications._sql_literal
    tenant, command, task, draft, owner = [literal(value) + "::uuid" for value in (
        tenant_id, command_id, task_id, draft_id, owner_id,
    )]
    expected_hash = f"decode(translate({literal(expected_digest)},'-_','+/')||'=','base64')"
    sql = f"""BEGIN READ ONLY;
SET LOCAL ROLE law_app_query;
WITH selected_contact AS (
 SELECT lead_contact_result_id,lead_id,lead_assignment_id,contact_task_id,contact_no,result_code
 FROM lead.lead_contact_result WHERE tenant_id={tenant} AND contact_task_id={task}
), selected_opportunity AS (
 SELECT o.opportunity_id,o.source_lead_id,o.source_assignment_id,o.owner_appointment_id,o.source_contact_result_id,o.revision
 FROM opportunity.opportunity o JOIN selected_contact c ON o.source_contact_result_id=c.lead_contact_result_id
 WHERE o.tenant_id={tenant}
), selected_slot AS (
 SELECT tenant_id,command_execution_slot_id,command_id,command_type FROM execution.command_execution_slot
 WHERE tenant_id={tenant} AND command_id={command} AND command_type='RECORD_CONTACT_RESULT'
), selected_events AS (
 SELECT domain_event_id,event_type,source_fact_type,source_fact_id FROM execution.domain_event
 WHERE tenant_id={tenant} AND command_id={command}
)
SELECT jsonb_build_object(
 'profile','R1_GOLDEN_COMPLETION_V1','tenantId',{tenant}::text,'commandId',{command}::text,
 'contactResults',coalesce((SELECT jsonb_agg(jsonb_build_object('id',lead_contact_result_id,'leadId',lead_id,'assignmentId',lead_assignment_id,'taskId',contact_task_id,'contactNo',contact_no,'resultCode',result_code)) FROM selected_contact),'[]'::jsonb),
 'opportunities',coalesce((SELECT jsonb_agg(jsonb_build_object('id',opportunity_id,'leadId',source_lead_id,'assignmentId',source_assignment_id,'ownerAppointmentId',owner_appointment_id,'sourceContactResultId',source_contact_result_id,'revision',revision)) FROM selected_opportunity),'[]'::jsonb),
 'tasks',coalesce((SELECT jsonb_agg(jsonb_build_object('id',task_occurrence_id,'ownerAppointmentId',owner_appointment_id,'state',state,'revision',revision,'completionFactType',completion_fact_type,'completionFactId',completion_fact_id,'completionFactHash',translate(rtrim(encode(completion_fact_hash,'base64'),'='),'+/','-_'))) FROM responsibility.task_occurrence WHERE tenant_id={tenant} AND task_occurrence_id={task} AND owner_appointment_id={owner} AND completion_fact_hash={expected_hash}),'[]'::jsonb),
 'drafts',coalesce((SELECT jsonb_agg(jsonb_build_object('id',action_draft_id,'taskId',task_occurrence_id,'state',state,'revision',revision,'confirmedByAppointmentId',confirmed_by_appointment_id)) FROM responsibility.action_draft WHERE tenant_id={tenant} AND action_draft_id={draft} AND task_occurrence_id={task} AND confirmed_by_appointment_id={owner}),'[]'::jsonb),
 'receipts',coalesce((SELECT jsonb_agg(jsonb_build_object('commandId',s.command_id,'outcome',r.outcome,'resultFactType',r.result_fact_type,'resultFactId',r.result_fact_id,'resultFactHash',translate(rtrim(encode(r.result_fact_hash,'base64'),'='),'+/','-_'))) FROM selected_slot s JOIN execution.command_receipt r USING(tenant_id,command_execution_slot_id) WHERE r.result_fact_hash={expected_hash}),'[]'::jsonb),
 'audits',coalesce((SELECT jsonb_agg(jsonb_build_object('commandId',command_id,'commandType',command_type,'resultCode',result_code,'actorAppointmentId',actor_appointment_id,'onBehalfOfAppointmentId',on_behalf_of_appointment_id)) FROM audit.audit_entry_classified_v WHERE tenant_id={tenant} AND command_id={command} AND command_type='RECORD_CONTACT_RESULT'),'[]'::jsonb),
 'events',coalesce((SELECT jsonb_agg(jsonb_build_object('id',domain_event_id,'type',event_type,'sourceFactType',source_fact_type,'sourceFactId',source_fact_id) ORDER BY event_type) FROM selected_events),'[]'::jsonb),
 'outboxes',coalesce((SELECT jsonb_agg(jsonb_build_object('eventId',e.domain_event_id,'eventType',e.event_type) ORDER BY e.event_type) FROM selected_events e JOIN execution.domain_event_outbox o ON o.tenant_id={tenant} AND o.domain_event_id=e.domain_event_id),'[]'::jsonb)
)::text;
COMMIT;
"""
    return sql.encode("utf-8")


def read_golden_completion(
    root: Path, run: str, operation_id: str, command_id: str,
    task_id: str, draft_id: str, owner_id: str, expected_digest: str,
) -> dict:
    """Re-verify the current run, then return its closed read-only golden projection."""
    environment = load_acceptance_environment(root, run, operation_id)
    runtime, folder, _, _, manifest, _ = applications._load_application(
        Path(root).resolve(strict=True), run, (READY_PHASE,),
    )
    result = applications._invoke(
        applications._database_command(Path(root).resolve(strict=True), runtime, manifest),
        Path(root).resolve(strict=True), folder, f"acceptance-completion-{operation_id}",
        input_bytes=completion_query(
            environment["bootstrap"]["tenantId"], command_id, task_id, draft_id, owner_id,
            expected_digest,
        ),
    )
    if result.returncode:
        raise RuntimeError("golden completion unavailable")
    try:
        value = json.loads(result.stdout.strip())
    except json.JSONDecodeError as error:
        raise RuntimeError("golden completion unavailable") from error
    validate_golden_completion(value, expected_digest)
    if value["commandId"] != command_id or value["tenantId"] != environment["bootstrap"]["tenantId"]:
        raise RuntimeError("golden completion mismatch")
    return value


def closed_report(value: object) -> dict:
    """Project only the public acceptance fields; all unlisted material is dropped."""
    if not isinstance(value, dict):
        raise RuntimeError("closed report input invalid")
    keys = (
        "run", "operationId", "status", "environmentDigest", "managementCommandCount",
        "goldenCommandId", "resultFact", "counts",
    )
    result = {"profile": "R1_ISOLATED_ACCEPTANCE_REPORT_V1"}
    for key in keys:
        if key not in value:
            raise RuntimeError("closed report input invalid")
        result[key] = value[key]
    if result["status"] != "GOLDEN_VERIFIED" or result["managementCommandCount"] != 16 or \
       not _uuid(result["operationId"]) or not _uuid(result["goldenCommandId"]) or \
       not isinstance(result["environmentDigest"], str) or not HASH_PATTERN.fullmatch(result["environmentDigest"]):
        raise RuntimeError("closed report input invalid")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    commands = parser.add_subparsers(dest="command", required=True)
    environment = commands.add_parser("environment")
    environment.add_argument("run")
    environment.add_argument("--operation-id", required=True)
    completion = commands.add_parser("completion")
    completion.add_argument("run")
    completion.add_argument("--operation-id", required=True)
    completion.add_argument("--command-id", required=True)
    completion.add_argument("--task-id", required=True)
    completion.add_argument("--draft-id", required=True)
    completion.add_argument("--owner-appointment-id", required=True)
    completion.add_argument("--result-fact-digest", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "environment":
            value = load_acceptance_environment(args.root, args.run, args.operation_id)
        else:
            value = read_golden_completion(
                args.root, args.run, args.operation_id, args.command_id,
                args.task_id, args.draft_id, args.owner_appointment_id, args.result_fact_digest,
            )
        print(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        return 0
    except (RuntimeError, ValueError, OSError):
        print("R1_ACCEPTANCE_BOUNDARY", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
