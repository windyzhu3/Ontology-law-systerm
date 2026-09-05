from __future__ import annotations

import re
from collections import Counter
from pathlib import Path

import yaml


class _StrictSafeLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader: _StrictSafeLoader, node: yaml.MappingNode, deep: bool = False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(None, None, f"duplicate key: {key}", key_node.start_mark)
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_StrictSafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


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
    try:
        document = yaml.load(api, Loader=_StrictSafeLoader)
    except yaml.YAMLError as error:
        findings.append(f"R1 OpenAPI is not strict YAML: {error}")
        return findings
    if not isinstance(document, dict) or document.get("info", {}).get("version") != "1.1.0":
        findings.append("R1 OpenAPI version must be 1.1.0")
        return findings
    paths = document.get("paths", {})
    operations = [operation.get("operationId") for item in paths.values() for operation in item.values() if isinstance(operation, dict)]
    if len(operations) != 15 or len(set(operations)) != 15:
        findings.append("R1 OpenAPI must expose exactly 15 unique operations")
    components = document.get("components", {})
    schemas = components.get("schemas", {})
    parameters = components.get("parameters", {})
    responses = components.get("responses", {})
    if parameters.get("RecoveryTypeQuery") != {"name": "recoveryType", "in": "query", "required": True, "schema": {"$ref": "#/components/schemas/RecoveryTypeV1"}}:
        findings.append("R1 RecoveryTypeQuery differs from exact contract")
    if parameters.get("DueLimitQuery") != {"name": "limit", "in": "query", "required": False, "schema": {"type": "integer", "minimum": 1, "maximum": 100, "default": 50}}:
        findings.append("R1 DueLimitQuery differs from exact contract")
    if parameters.get("DueCursorQuery") != {"name": "cursor", "in": "query", "required": False, "schema": {"type": "string", "minLength": 1, "maxLength": 2048}}:
        findings.append("R1 DueCursorQuery differs from exact contract")
    if schemas.get("RecoveryTypeV1") != {"type": "string", "enum": ["CONTACT_TASK", "ROUTING_REVIEW_TASK"]}:
        findings.append("R1 RecoveryTypeV1 differs from exact contract")

    def closed_schema(name: str, required: list[str], properties: dict) -> None:
        expected = {"type": "object", "additionalProperties": False, "required": required, "properties": properties}
        if schemas.get(name) != expected:
            findings.append(f"R1 {name} differs from exact scoped contract")

    ref = lambda name: {"$ref": f"#/components/schemas/{name}"}
    candidate_properties = {
        "recoveryType": ref("RecoveryTypeV1"), "taskId": ref("Uuid"),
        "expectedTaskRevision": ref("Revision"), "waitReceiptId": ref("Uuid"),
        "waitReceiptHash": ref("Digest32"), "dueCutoff": {"type": "string", "format": "date-time"},
        "idempotencyKey": ref("Uuid"),
    }
    closed_schema("DueR1TaskCandidateV1", list(candidate_properties), candidate_properties)
    page_properties = {"candidates": {"type": "array", "maxItems": 100, "items": ref("DueR1TaskCandidateV1")}, "nextCursor": {"type": "string", "minLength": 1, "maxLength": 2048}}
    closed_schema("DueR1TaskPageV1", ["candidates"], page_properties)
    if schemas.get("TechnicalIdentifier") != {"type": "string", "minLength": 1, "maxLength": 64, "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]*$"}:
        findings.append("R1 TechnicalIdentifier differs from exact contract")
    consume_properties = {"domainEventOutboxId": ref("Uuid"), "domainEventId": ref("Uuid"), "expectedOutboxRevision": ref("Revision"), "leaseOwner": ref("TechnicalIdentifier"), "fencingToken": {"type": "integer", "format": "int64", "minimum": 1, "maximum": 9007199254740991}}
    closed_schema("ConsumeR1ProjectionV1", list(consume_properties), consume_properties)
    expected_problem_required = ["type", "title", "status", "code", "retryPolicy", "correlationId"]
    expected_problem_properties = {
        "type": {"type": "string", "format": "uri"},
        "title": {"type": "string"},
        "status": {"type": "integer", "enum": [400, 401, 403, 404, 409, 422, 429, 500, 503]},
        "code": {"type": "string", "enum": ["VALIDATION_FAILED", "UNAUTHENTICATED", "NOT_AUTHORIZED", "NOT_FOUND", "STALE_OUTBOX_CLAIM", "PROJECTION_EVENT_INVALID", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE"]},
        "retryPolicy": {"type": "string", "enum": ["NO", "FIRST_PAGE", "AFTER_REAUTH", "BACKOFF"]},
        "correlationId": ref("Uuid"),
    }
    expected_problem = {"type": "object", "additionalProperties": False, "required": expected_problem_required, "properties": expected_problem_properties}
    if schemas.get("InternalProblem") != expected_problem:
        findings.append("R1 InternalProblem differs from exact frozen schema")
    response_components = {400: "InternalBadRequestProblem", 401: "InternalClosureUnauthorizedProblem", 403: "InternalForbiddenProblem", 404: "InternalNotFoundProblem", 409: "InternalConflictProblem", 422: "InternalUnprocessableProblem", 429: "InternalRateLimitedProblem", 500: "InternalServerProblem", 503: "InternalUnavailableProblem"}
    for status, name in response_components.items():
        expected = {"description": responses.get(name, {}).get("description"), "content": {"application/problem+json": {"schema": ref("InternalProblem")}}}
        if responses.get(name) != expected:
            findings.append(f"R1 internal response component differs: {name}")
    expected_operations = {
        ("/internal/v1/tasks/due", "get"): ("listDueR1Tasks", {"200": {"description": "Authorized due selectors ordered by resume due time and task UUID.", "content": {"application/json": {"schema": ref("DueR1TaskPageV1")}}}}, [400, 401, 403, 429, 500, 503]),
        ("/internal/v1/projections/r1/consume", "post"): ("consumeR1Projection", {"204": {"description": "Current facts were validated for the active claim; no response body."}}, [400, 401, 403, 404, 409, 422, 429, 500, 503]),
    }
    for (path, method), (operation_id, successes, errors) in expected_operations.items():
        operation = paths.get(path, {}).get(method, {})
        if operation.get("operationId") != operation_id or operation.get("security") != [{"internalMutualTls": []}]:
            findings.append(f"R1 internal operation identity/security differs: {operation_id}")
        expected_responses = dict(successes)
        expected_responses.update({str(status): {"$ref": f"#/components/responses/{response_components[status]}"} for status in errors})
        if operation.get("responses") != expected_responses:
            findings.append(f"R1 internal operation response set differs: {operation_id}")
        expected_codes = {
            "listDueR1Tasks": ["VALIDATION_FAILED", "UNAUTHENTICATED", "NOT_AUTHORIZED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE"],
            "consumeR1Projection": ["VALIDATION_FAILED", "UNAUTHENTICATED", "NOT_AUTHORIZED", "NOT_FOUND", "STALE_OUTBOX_CLAIM", "PROJECTION_EVENT_INVALID", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE"],
        }[operation_id]
        if operation.get("x-error-codes") != expected_codes:
            findings.append(f"R1 internal operation error allowlist differs: {operation_id}")
        if operation_id == "listDueR1Tasks":
            expected_parameters = [{"$ref": f"#/components/parameters/{name}"} for name in ("RecoveryTypeQuery", "DueLimitQuery", "DueCursorQuery")]
            if operation.get("parameters") != expected_parameters or "requestBody" in operation:
                findings.append("R1 listDueR1Tasks parameter/body contract differs")
        else:
            expected_body = {"required": True, "content": {"application/json": {"schema": ref("ConsumeR1ProjectionV1")}}}
            if operation.get("requestBody") != expected_body or "parameters" in operation:
                findings.append("R1 consumeR1Projection request contract differs")
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
