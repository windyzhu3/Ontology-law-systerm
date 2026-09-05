from __future__ import annotations

import re
from collections import Counter
from pathlib import Path


def _read(root: Path, relative: str, findings: list[str]) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        findings.append(f"R1 closure artifact missing or invalid UTF-8: {relative}")
        return ""


def _require(text: str, value: str, label: str, findings: list[str]) -> None:
    if value not in text:
        findings.append(f"R1 closure contract missing {label}: {value}")


def _indented_block(text: str, marker: str, indent: int, findings: list[str]) -> str:
    lines = text.splitlines()
    matches = [index for index, line in enumerate(lines) if line == " " * indent + marker]
    if len(matches) != 1:
        findings.append(f"R1 OpenAPI must contain exactly one scoped {marker}")
        return ""
    start = matches[0]
    end = start + 1
    while end < len(lines) and (not lines[end].strip() or len(lines[end]) - len(lines[end].lstrip()) > indent):
        end += 1
    return "\n".join(lines[start:end])


def _markdown_rows(text: str, heading: str, findings: list[str]) -> list[list[str]]:
    marker = f"## {heading}"
    parts = text.split(marker)
    if len(parts) != 2:
        findings.append(f"R1 HTTP contract must contain exactly one {marker}")
        return []
    lines = parts[1].splitlines()
    table = [line for line in lines if line.startswith("|")]
    if len(table) < 2:
        findings.append(f"R1 HTTP contract table missing after {marker}")
        return []
    rows = []
    for line in table[2:]:
        if set(line.replace("|", "").replace("-", "").replace(":", "").strip()):
            rows.append([cell.strip() for cell in line.strip("|").split("|")])
    return rows


def validate(root: Path) -> list[str]:
    findings: list[str] = []
    command = _read(root, "docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md", findings)
    adr = _read(root, "docs/adr/ADR-0008-r1-business-closure-alignment.md", findings)
    baseline = _read(root, "docs/baseline/CURRENT-MVP-BASELINE.md", findings)
    api = _read(root, "contracts/openapi/ontology-law-api.yaml", findings)
    http = _read(root, "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md", findings)
    for value in (
        "| CAPTURE_LEAD | INTERNAL_ADMIN | HUMAN | DIRECT,DELEGATED |",
        "| CAPTURE_LEAD | SERVICE_ACTOR | SERVICE | SYSTEM | SOURCE_INTAKE_OWNER | LEAD_CAPTURE | sourceIntakeRootCode | existing-lead:LEAD_CAPTURE-DENY | NONE | NONE |",
    ):
        _require(command, value, "capture composite policy", findings)
    capture_rows = re.findall(r"(?m)^\| CAPTURE_LEAD \|.*$", command)
    keys = []
    for row in capture_rows:
        cells = [cell.strip() for cell in row.strip("|").split("|")]
        if len(cells) >= 3:
            keys.append((cells[0], cells[2]))
    if len(keys) != 2 or any(count != 1 for count in Counter(keys).values()):
        findings.append("R1 capture policies must contain exactly one HUMAN and one SERVICE composite row")
    if "CAPTURE_LEAD 只接受 HUMAN" in command or "SERVICE、SYSTEM、CUSTOMER_GRANT" in command:
        findings.append("R1 command contract retains obsolete HUMAN-only capture prose")
    _require(command, "The registry key is the composite `(CommandType, PrincipalKind)`.", "composite policy prose", findings)
    for value in (
        "BaselineId | MVP-2026-09-05.3",
        "ProjectionStorage | NONE",
        "DisclosureReturn | AFTER_AUDIT_COMMIT",
        "SensitiveResponseModes | BODY,CACHE_REVALIDATED",
        "MaxAttempts | 8",
        "CapacityProfile | R1-CAPACITY-V1",
        "EventRouteCount | 14",
        "R1_TRUSTED_SERVICE_SOURCE_BINDING_V1",
        "R1_WORKER_TENANT_BINDING_V1",
        "private, no-cache",
        "Vary: Authorization",
    ):
        _require(adr, value, "ADR decision", findings)
    _require(baseline, "Baseline ID: MVP-2026-09-05.3", "active baseline id", findings)
    _require(api, "version: 1.1.0", "OpenAPI version", findings)
    operations = re.findall(r"(?m)^\s+operationId: (\S+)\s*$", api)
    if len(operations) != 15 or len(set(operations)) != 15:
        findings.append("R1 OpenAPI must expose exactly 15 unique operations")
    for operation in ("listDueR1Tasks", "consumeR1Projection"):
        _require(api, f"operationId: {operation}", "internal operation", findings)
    if api.count("- internalMutualTls: []") != 4 or api.count("- publicBearer: []") != 11:
        findings.append("R1 OpenAPI security split must be exactly 11 Bearer and 4 mutualTLS")
    due_parameter = _indented_block(api, "DueLimitQuery:", 4, findings)
    for value in ("minimum: 1", "maximum: 100", "default: 50"):
        _require(due_parameter, value, "due limit constraint", findings)
    candidate = _indented_block(api, "DueR1TaskCandidateV1:", 4, findings)
    consume = _indented_block(api, "ConsumeR1ProjectionV1:", 4, findings)
    for block, required, properties in (
        (candidate, "required: [recoveryType, taskId, expectedTaskRevision, waitReceiptId, waitReceiptHash, dueCutoff, idempotencyKey]", ("recoveryType", "taskId", "expectedTaskRevision", "waitReceiptId", "waitReceiptHash", "dueCutoff", "idempotencyKey")),
        (consume, "required: [domainEventOutboxId, domainEventId, expectedOutboxRevision, leaseOwner, fencingToken]", ("domainEventOutboxId", "domainEventId", "expectedOutboxRevision", "leaseOwner", "fencingToken")),
    ):
        _require(block, "additionalProperties: false", "closed internal DTO", findings)
        _require(block, required, "exact internal DTO required fields", findings)
        actual = re.findall(r"(?m)^        ([A-Za-z][A-Za-z0-9]*):", block)
        if actual != list(properties):
            findings.append("R1 internal DTO properties differ from exact scoped contract")
    operations_rows = _markdown_rows(http, "Operations", findings)
    operations = {row[0]: row for row in operations_rows if len(row) == 9}
    expected_http = {
        "listDueR1Tasks": ["GET", "/internal/v1/tasks/due", "ACTOR_CONTEXT", "NONE", "RECOVERY_TYPE_LIMIT_CURSOR", "DUE_TASK_OWNER_SCOPE", "200", "VALIDATION_FAILED,UNAUTHENTICATED,NOT_AUTHORIZED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"],
        "consumeR1Projection": ["POST", "/internal/v1/projections/r1/consume", "ACTOR_CONTEXT", "NONE", "OUTBOX_REVISION_LEASE_FENCE", "EVENT_OUTBOX_CURRENT_OWNER_FACTS", "204", "VALIDATION_FAILED,UNAUTHENTICATED,NOT_AUTHORIZED,NOT_FOUND,STALE_OUTBOX_CLAIM,PROJECTION_EVENT_INVALID,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"],
    }
    for name, expected in expected_http.items():
        if operations.get(name, [])[1:] != expected:
            findings.append(f"R1 HTTP Operations row differs from frozen values: {name}")
    mtls_operations = "listDueR1Tasks,consumeR1Projection,reopenDueContactTasks,reopenDueRoutingReviewTasks"
    if http.count(mtls_operations) != 2:
        findings.append("R1 HTTP security and authentication tables must both bind all four internal operations")
    public_prefix = api.split("  /internal/v1/tasks/due:", 1)[0]
    if "STALE_OUTBOX_CLAIM" in public_prefix or "PROJECTION_EVENT_INVALID" in public_prefix:
        findings.append("internal projection errors must not broaden public operation allowlists")
    return findings
