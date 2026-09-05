from __future__ import annotations

import json
from collections import Counter
from pathlib import Path


CONTRACT_PATH = Path("docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md")
SCHEMA_PATH = Path("contracts/events/r1-domain-notification-v1.schema.json")

COMMAND_POLICY_HEADERS = (
    "CommandType",
    "Envelope",
    "PrincipalKind",
    "AuthorityPath",
    "AuthoritySlot",
    "AuthorityCode",
    "ScopeSelector",
    "ObjectDeny",
    "TaskType",
    "WaitProfile",
)
COMMAND_POLICIES = {
    "CAPTURE_LEAD": (
        "INTERNAL_ADMIN", "HUMAN", "DIRECT,DELEGATED", "SOURCE_INTAKE_OWNER",
        "LEAD_CAPTURE", "sourceIntakeRootCode", "existing-lead:LEAD_CAPTURE-DENY",
        "NONE", "NONE",
    ),
    "SAVE_ACTION_DRAFT": (
        "INTERNAL_TASK", "HUMAN", "DIRECT,DELEGATED", "taskTypeRegistry",
        "taskTypeRegistry", "taskOwnerOrganization",
        "task-and-lead:taskTypeAuthority-DENY", "persistedTaskType", "NONE",
    ),
    "REOPEN_DUE_CONTACT_TASKS": (
        "SERVICE_ACTOR", "SERVICE", "SYSTEM", "SYSTEM_RECOVERY",
        "CONTACT_TASK_RECOVER", "taskOwnerOrganization",
        "task-and-lead:CONTACT_TASK_RECOVER-DENY", "CONTACT_LEAD", "CONTACT_RETRY_V1",
    ),
    "REOPEN_DUE_ROUTING_REVIEW_TASKS": (
        "SERVICE_ACTOR", "SERVICE", "SYSTEM", "SYSTEM_RECOVERY",
        "ROUTING_REVIEW_TASK_RECOVER", "taskOwnerOrganization",
        "task-and-lead:ROUTING_REVIEW_TASK_RECOVER-DENY", "RESOLVE_LEAD_ROUTING_GAP",
        "R1_ROUTING_REVIEW_WAIT_V1",
    ),
}

DRAFT_AUTHORITY_HEADERS = ("TaskType", "AuthoritySlot", "AuthorityCode")
DRAFT_AUTHORITIES = {
    "RESOLVE_LEAD_DUPLICATE": ("SOURCE_INTAKE_OWNER", "LEAD_INGRESS_RESOLVE"),
    "COMPLETE_LEAD_INGRESS": ("SOURCE_INTAKE_OWNER", "LEAD_INGRESS_COMPLETE"),
    "ASSIGN_LEAD": ("ROUTING_SUPERVISOR", "LEAD_ASSIGN"),
    "RESOLVE_LEAD_ROUTING_GAP": ("ROUTING_SUPERVISOR", "LEAD_ROUTING_DECIDE"),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": (
        "SOURCE_INTAKE_OWNER", "SOURCE_INTAKE_REQUEST_ACK",
    ),
    "CONTACT_LEAD": ("ASSIGNMENT_OWNER", "SALES_CONTACT_OWNER"),
    "REVIEW_LEAD_VALIDITY": ("ROUTING_SUPERVISOR", "LEAD_VALIDITY_REVIEW"),
}

EVENT_HEADERS = (
    "EventType", "SchemaVersion", "SourceType", "SourceSelector", "QueueOwner", "Purpose",
)
EVENTS = {
    "LeadCapturedV1": (
        "1", "lead.lead", "revision:transaction-final", "R1_PROJECTION",
        "Refresh intake and current responsibility projection",
    ),
    "ActionDraftSavedV1": (
        "1", "responsibility.action_draft", "revision:post-write", "R1_PROJECTION",
        "Refresh authorized draft presentation without completing Task",
    ),
    "ContactTaskReopenedV1": (
        "1", "responsibility.task_occurrence", "revision:post-CAS", "R1_PROJECTION",
        "Refresh actionable contact card",
    ),
    "RoutingReviewTaskReopenedV1": (
        "1", "responsibility.task_occurrence", "revision:post-CAS", "R1_PROJECTION",
        "Refresh actionable routing card",
    ),
    "LeadDuplicateResolutionRecordedV1": (
        "1", "responsibility.decision_record", "hash:content", "R1_PROJECTION",
        "Refresh duplicate resolution projection",
    ),
    "LeadIngressCompletedV1": (
        "1", "lead.lead", "revision:post-CAS", "R1_PROJECTION",
        "Refresh completed ingress projection",
    ),
    "LeadAssignedV1": (
        "1", "lead.lead_assignment", "revision:0", "R1_PROJECTION",
        "Refresh assignment projection",
    ),
    "LeadRoutingDispositionRecordedV1": (
        "1", "responsibility.decision_record", "hash:content", "R1_PROJECTION",
        "Refresh routing disposition projection",
    ),
    "SourceIntakeStopRequestedV1": (
        "1", "responsibility.decision_record", "hash:content", "R1_PROJECTION",
        "Refresh source intake stop request projection",
    ),
    "SourceIntakeStopRequestAcknowledgedV1": (
        "1", "responsibility.decision_record", "hash:content", "R1_PROJECTION",
        "Refresh source intake acknowledgement projection",
    ),
    "LeadContactResultRecordedV1": (
        "1", "lead.lead_contact_result", "hash:immutable-row", "R1_PROJECTION",
        "Refresh contact result projection",
    ),
    "LeadContactRetryExhaustedV1": (
        "1", "lead.lead_contact_result", "hash:immutable-row", "R1_PROJECTION",
        "Refresh exhausted contact projection",
    ),
    "LeadValidityReviewedV1": (
        "1", "responsibility.decision_record", "hash:content", "R1_PROJECTION",
        "Refresh lead validity projection",
    ),
    "OpportunityOpened": (
        "1", "opportunity.opportunity", "revision:0", "R1_PROJECTION",
        "R1 boundary projection; retained for delayed R2 activation",
    ),
}

BRANCH_HEADERS = (
    "BranchID", "CommandType", "Outcome", "EventTypes", "EventCount", "OutboxCount",
)
BRANCHES = {
    "CAPTURE_LEAD_CREATED": ("CAPTURE_LEAD", "CREATED", "LeadCapturedV1", "1", "1"),
    "SAVE_ACTION_DRAFT_CHANGED": (
        "SAVE_ACTION_DRAFT", "CREATED_OR_CHANGED", "ActionDraftSavedV1", "1", "1",
    ),
    "REOPEN_DUE_CONTACT_TASKS_REOPENED": (
        "REOPEN_DUE_CONTACT_TASKS", "WAITING_TO_OPEN", "ContactTaskReopenedV1", "1", "1",
    ),
    "REOPEN_DUE_ROUTING_REVIEW_TASKS_REOPENED": (
        "REOPEN_DUE_ROUTING_REVIEW_TASKS", "WAITING_TO_OPEN",
        "RoutingReviewTaskReopenedV1", "1", "1",
    ),
    "P0_01_LINK_EXISTING": (
        "RESOLVE_DUPLICATE_LEAD", "LINK_EXISTING_PARTY",
        "LeadDuplicateResolutionRecordedV1", "1", "1",
    ),
    "P0_01_KEEP_SEPARATE": (
        "RESOLVE_DUPLICATE_LEAD", "KEEP_SEPARATE",
        "LeadDuplicateResolutionRecordedV1", "1", "1",
    ),
    "P0_02_COMPLETE": (
        "COMPLETE_LEAD_INGRESS", "INGRESS_COMPLETED", "LeadIngressCompletedV1", "1", "1",
    ),
    "P0_03_ASSIGN": ("ASSIGN_LEAD", "ASSIGNED", "LeadAssignedV1", "1", "1"),
    "P0_04_SCHEDULE_ROUTING_REVIEW": (
        "RECORD_ROUTING_DISPOSITION", "SCHEDULE_ROUTING_REVIEW",
        "LeadRoutingDispositionRecordedV1", "1", "1",
    ),
    "P0_04_RETRY_ASSIGNMENT_NOW": (
        "RECORD_ROUTING_DISPOSITION", "RETRY_ASSIGNMENT_NOW",
        "LeadRoutingDispositionRecordedV1", "1", "1",
    ),
    "P0_04_REQUEST_SOURCE_INTAKE_STOP": (
        "RECORD_ROUTING_DISPOSITION", "REQUEST_SOURCE_INTAKE_STOP",
        "SourceIntakeStopRequestedV1", "1", "1",
    ),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": (
        "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST", "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED",
        "SourceIntakeStopRequestAcknowledgedV1", "1", "1",
    ),
    "CONTACT_CONNECTED_VALID": (
        "RECORD_CONTACT_RESULT", "CONNECTED_VALID",
        "LeadContactResultRecordedV1,OpportunityOpened", "2", "2",
    ),
    "CONTACT_NOT_CONNECTED_RETRY": (
        "RECORD_CONTACT_RESULT", "NOT_CONNECTED_RETRY", "LeadContactResultRecordedV1", "1", "1",
    ),
    "CONTACT_NOT_CONNECTED_EXHAUSTED": (
        "RECORD_CONTACT_RESULT", "NOT_CONNECTED_EXHAUSTED", "LeadContactRetryExhaustedV1", "1", "1",
    ),
    "CONTACT_SUSPECT_INVALID": (
        "RECORD_CONTACT_RESULT", "SUSPECT_INVALID", "LeadContactResultRecordedV1", "1", "1",
    ),
    "REVIEW_CONFIRM_INVALID": (
        "REVIEW_LEAD_VALIDITY", "CONFIRM_INVALID", "LeadValidityReviewedV1", "1", "1",
    ),
    "REVIEW_CLOSE_UNREACHED": (
        "REVIEW_LEAD_VALIDITY", "CLOSE_UNREACHED", "LeadValidityReviewedV1", "1", "1",
    ),
    "REVIEW_REOPEN_CONTACT": (
        "REVIEW_LEAD_VALIDITY", "REOPEN_CONTACT", "LeadValidityReviewedV1", "1", "1",
    ),
}

EXPECTED_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "properties": {},
    "additionalProperties": False,
}


class _DuplicateJsonMember(ValueError):
    pass


def _reject_duplicate_json_members(
    pairs: list[tuple[str, object]],
) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, member in pairs:
        if key in value:
            raise _DuplicateJsonMember(key)
        value[key] = member
    return value


def _read_utf8(root: Path, relative_path: Path, findings: list[str]) -> str | None:
    path = root / relative_path
    if not path.is_file():
        findings.append(f"Missing R1 command contract artifact: {relative_path.as_posix()}")
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        findings.append(f"Invalid UTF-8 in R1 command contract artifact: {relative_path.as_posix()}")
        return None


def _field(text: str, name: str) -> list[str]:
    prefix = f"{name}:"
    return [line[len(prefix):].strip() for line in text.splitlines() if line.startswith(prefix)]


def _parse_table(
    text: str,
    heading: str,
    expected_headers: tuple[str, ...],
    findings: list[str],
) -> list[tuple[str, ...]] | None:
    marker = f"## {heading}"
    lines = text.splitlines()
    indexes = [index for index, line in enumerate(lines) if line == marker]
    if len(indexes) != 1:
        findings.append(f"R1 command contract must contain exactly one {marker}")
        return None
    index = indexes[0] + 1
    while index < len(lines) and not lines[index].startswith("|"):
        if lines[index].startswith("## "):
            findings.append(f"R1 command contract table missing after {marker}")
            return None
        index += 1
    if index >= len(lines) or not lines[index].startswith("|"):
        findings.append(f"R1 command contract table missing after {marker}")
        return None

    def cells(line: str) -> tuple[str, ...]:
        return tuple(cell.strip() for cell in line.strip().strip("|").split("|"))

    headers = cells(lines[index])
    if headers != expected_headers:
        findings.append(f"R1 command contract headers differ for {heading}")
        return None
    index += 1
    if index >= len(lines):
        findings.append(f"R1 command contract delimiter missing for {heading}")
        return None
    delimiter = cells(lines[index])
    if len(delimiter) != len(headers) or any(
        not value or set(value) - {"-", ":"} for value in delimiter
    ):
        findings.append(f"R1 command contract delimiter malformed for {heading}")
        return None
    index += 1
    rows: list[tuple[str, ...]] = []
    while index < len(lines) and lines[index].startswith("|"):
        row = cells(lines[index])
        if len(row) != len(headers) or any(not value for value in row):
            findings.append(f"R1 command contract row malformed for {heading}")
            return None
        rows.append(row)
        index += 1
    if not rows:
        findings.append(f"R1 command contract table empty for {heading}")
        return None
    return rows


def _validate_registry(
    label: str,
    rows: list[tuple[str, ...]] | None,
    expected: dict[str, tuple[str, ...]],
    findings: list[str],
) -> dict[str, tuple[str, ...]] | None:
    if rows is None:
        return None
    keys = [row[0] for row in rows]
    duplicates = sorted(key for key, count in Counter(keys).items() if count > 1)
    if duplicates:
        findings.append(f"R1 {label} duplicate key: {duplicates[0]}")
        return None
    actual = {row[0]: row[1:] for row in rows}
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    if missing:
        findings.append(f"R1 {label} missing key: {missing[0]}")
        return None
    if extra:
        findings.append(f"R1 {label} unknown key: {extra[0]}")
        return None
    for key, expected_values in expected.items():
        if actual[key] != expected_values:
            findings.append(f"R1 {label} differs from frozen values: {key}")
            return None
    return actual


def validate_r1_command_contract(root: Path) -> list[str]:
    findings: list[str] = []
    text = _read_utf8(root, CONTRACT_PATH, findings)
    schema_text = _read_utf8(root, SCHEMA_PATH, findings)
    if text is None or schema_text is None:
        return findings

    metadata = {
        "Contract ID": "R1-COMMAND-POLICY-EVENT-V1",
        "Status": "FROZEN",
        "Semantic baseline": "MVP-2026-09-05.2",
        "Shared payload Schema": SCHEMA_PATH.as_posix(),
    }
    for name, expected in metadata.items():
        if _field(text, name) != [expected]:
            findings.append(f"R1 command contract must declare {name}: {expected}")

    policies = _validate_registry(
        "command policy",
        _parse_table(text, "Command policy registry", COMMAND_POLICY_HEADERS, findings),
        COMMAND_POLICIES,
        findings,
    )
    draft_authorities = _validate_registry(
        "draft authority",
        _parse_table(text, "Draft authority registry", DRAFT_AUTHORITY_HEADERS, findings),
        DRAFT_AUTHORITIES,
        findings,
    )
    events = _validate_registry(
        "event descriptor",
        _parse_table(text, "Event descriptor registry", EVENT_HEADERS, findings),
        EVENTS,
        findings,
    )
    branches = _validate_registry(
        "success branch",
        _parse_table(text, "Success branch event registry", BRANCH_HEADERS, findings),
        BRANCHES,
        findings,
    )

    if policies is not None and draft_authorities is not None:
        draft_policy = policies["SAVE_ACTION_DRAFT"]
        if draft_policy[3:5] != ("taskTypeRegistry", "taskTypeRegistry"):
            findings.append("R1 draft policy must resolve both authority fields from TaskType")

    if events is not None and branches is not None:
        for branch_id, values in branches.items():
            event_types = values[2].split(",")
            if len(event_types) != len(set(event_types)):
                findings.append(f"R1 success branch repeats an event: {branch_id}")
                continue
            unknown = sorted(set(event_types) - set(events))
            if unknown:
                findings.append(f"R1 success branch references unknown event: {unknown[0]}")
                continue
            event_count = len(event_types)
            outbox_count = sum(len(events[event][3].split(",")) for event in event_types)
            if values[3:] != (str(event_count), str(outbox_count)):
                findings.append(f"R1 success branch cardinality mismatch: {branch_id}")

    try:
        schema = json.loads(
            schema_text,
            object_pairs_hook=_reject_duplicate_json_members,
        )
    except (json.JSONDecodeError, _DuplicateJsonMember):
        findings.append(f"Malformed R1 notification payload Schema: {SCHEMA_PATH.as_posix()}")
    else:
        if schema != EXPECTED_SCHEMA or schema["additionalProperties"] is not False:
            findings.append(
                "R1 notification payload Schema must allow only the empty object under "
                "JSON Schema 2020-12"
            )
    return findings
