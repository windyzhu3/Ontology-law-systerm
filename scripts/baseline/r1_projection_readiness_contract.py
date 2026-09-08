"""Validate the active bounded readiness contract, never runtime readiness itself."""
from __future__ import annotations

from collections import Counter
from pathlib import Path

import yaml

try:
    from scripts.baseline.r1_business_closure_contract import _StrictSafeLoader
    from scripts.baseline.r1_contact_evidence_contract import _table, _without_fenced_code
except ModuleNotFoundError:
    from r1_business_closure_contract import _StrictSafeLoader
    from r1_contact_evidence_contract import _table, _without_fenced_code


PROFILE = "R1_PROJECTION_READINESS_V1"
READINESS_PATH = "/internal/v1/projections/r1/readiness"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
REGISTRY = {
    "BaselineId": "MVP-2026-09-07.1",
    "OpenApiVersion": "1.2.0",
    "OperationCount": "16",
    "PublicBearerOperations": "11",
    "MutualTlsOperations": "5",
    "ReadinessProfile": "R1_PROJECTION_READINESS_V1",
    "Actor": "UNIQUE_TRUSTED_CERTIFICATE_TENANT_SERVICE_APPOINTMENT",
    "Authority": "SYSTEM_PROJECTION/R1_PROJECTION_CONSUME/SYSTEM",
    "Grants": "EFFECTIVE_DIRECT_EXACT_APPOINTMENT_INDEPENDENT_COMPLETE_PER_ORGANIZATION",
    "ForbiddenSubstitutes": "HUMAN,ON_BEHALF,DELEGATION,OBJECT_ALLOW,RECOVERY,LEAD_CAPTURE,STITCHED_GRANTS",
    "CoverageUniverse": "TRUSTED_R1_SOURCE_POLICY_INTAKE_UNION_RETAINED_R1_TASK_ASSIGNMENT_OWNER_ORGANIZATIONS",
    "RetainedLineages": "DONE,CANCELLED,ASSIGNMENT,DECISION,CONTACT,OPPORTUNITY",
    "CoverageDerivation": "OWNER_FACTS_AND_TRUSTED_POLICY_NOT_SERVICE_ORG_GRANT_ROOTS_ALL_TENANT_ORGS_OR_QUEUE_PAGE",
    "UnresolvedAnchor": "FAIL_CLOSED",
    "EmptyUniverse": "VALID_SERVICE_APPOINTMENT_AND_EFFECTIVE_PROJECTION_GRANT_REQUIRED",
    "Capability": "API_QUERY_OWNER_PORTS_SQL_IN_OWNER_INTERNAL_PERSISTENCE",
    "LockOrder": "TRUSTED_ACTOR_THEN_SHARED_R1_BUSINESS_TENANT_LOCK_THEN_SHARED_IDENTITY",
    "Evaluation": "FINAL_LOCKED_READ_COMMITTED_FRESH_CLOCK_TIMESTAMP",
    "Transaction": "LOCKS_THROUGH_SUCCESSFUL_READ_TRANSACTION_COMPLETION_BEFORE_HTTP_SUCCESS",
    "BeforeEvaluation": "OBSERVE_COMMITTED_IDENTITY_POLICY_OWNER_CHANGES",
    "PostEvaluation": "IN_FLIGHT_MUTATION_AND_NATURAL_EXPIRY_RACE_INCLUDING_RESPONSE_TRANSPORT",
    "Atomicity": "NO_ATOMIC_CHECK_AND_CLAIM_OR_INSTANT_CROSS_PROCESS_INVALIDATION",
    "EventSpecificChecks": "DENY_CAUSAL_BINDING_SOURCE_HASH_LEASE_MANDATORY_AT_CONSUME",
    "PermitOrder": "ACQUIRE_AVAILABLE_EXECUTION_PERMITS_BEFORE_READINESS",
    "NoPermits": "NO_READINESS_NO_CLAIM",
    "Serialization": "ONE_READINESS_TO_CLAIM_SEQUENCE_PER_TENANT_BINDING",
    "SuccessUse": "ACTIVE_REQUEST_ONLY_ONE_IMMEDIATE_CLAIM_UP_TO_PERMITS_MAX_FOUR",
    "Invalidation": "AFTER_ONE_CLAIM_INCLUDING_ZERO_ROWS_RETRY_BINDING_CHANGE_ERROR_RESTART",
    "Proof": "NO_CACHE_TOKEN_TTL_PERSISTENCE_OR_PREAUTHORIZED_LOCAL_QUEUE",
    "Timeout": "EXISTING_10_SECONDS_DISCARD_LATE_OR_CANCELLED_RESPONSE",
    "PreflightFailure": "NO_CLAIM_NO_ATTEMPT_INCREMENT",
    "PreflightUnauthorized": "401_403_FREEZE_BINDING",
    "OtherFailure": "WITHHOLD_CLAIM_BOUNDED_EXISTING_BACKOFF_NO_BATCH_PROBE",
    "ConsumeUnauthorized": "401_403_FREEZE_NO_ACK_NO_FAILURE_CAS",
    "GenerationGuard": "REJECT_OLDER_SUCCESS_AFTER_NEWER_AUTHORIZATION_FAILURE",
    "Resume": "NEW_INITIATED_SUCCESS_AGAINST_REPAIRED_BINDING_ONLY",
    "ExistingClaims": "ATTEMPT_RETAINED_REAP_BELOW_EIGHT_PENDING_AT_EIGHT_EXHAUSTED",
    "Reaping": "FROZEN_RETRY_DELAY_NO_AUTOMATIC_EXHAUSTED_REDRIVE",
    "Cache": "ALL_RESPONSES_NO_STORE_NO_ETAG_NO_304",
    "Disclosure": "SAFE_PROBLEM_ONLY_NO_ORG_GRANT_SOURCE_BUSINESS_EXCEPTION_CREDENTIAL_LOGS",
    "Delta": "SLOT_RECEIPT_AUDIT_EVENT_OUTBOX_BUSINESS_ZERO",
    "Worker": "EXECUTION_ONLY",
    "AppRole": "API_OR_WORKER_EXCLUSIVE_ONE_MODULAR_MONOLITH_JAR_ONE_RESPONSIVE_SPA",
    "SchemaCount": "13",
    "ApplicationTableCount": "52",
    "TechnicalTableCount": "2",
    "PhysicalCapability": "52-plus-2-v1.2",
    "FrozenArtifacts": "V001_V860_MANIFEST_FIELD_CONTRACT_GRANTS_JOOQ_EVENT_ROUTES_14",
}
ERROR_CODES = ["VALIDATION_FAILED", "UNAUTHENTICATED", "NOT_AUTHORIZED",
               "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE"]
ERROR_STATUSES = {"400", "401", "403", "429", "500", "503"}
INTERNAL_OPERATIONS = {
    ("get", "/internal/v1/tasks/due"): "listDueR1Tasks",
    ("post", "/internal/v1/projections/r1/consume"): "consumeR1Projection",
    ("post", "/internal/v1/tasks/commands/reopen-due-contact-tasks"): "reopenDueContactTasks",
    ("post", "/internal/v1/tasks/commands/reopen-due-routing-review-tasks"): "reopenDueRoutingReviewTasks",
    ("get", READINESS_PATH): "checkR1ProjectionReadiness",
}
PUBLIC_OPERATIONS = {
    ("post", "/api/v1/leads"): "captureLead",
    ("get", "/api/v1/workcards/current"): "getCurrentWorkCard",
    ("put", "/api/v1/tasks/{taskId}/draft"): "saveActionDraft",
    ("post", "/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead"): "resolveDuplicateLead",
    ("post", "/api/v1/tasks/{taskId}/commands/complete-lead-ingress"): "completeLeadIngress",
    ("post", "/api/v1/tasks/{taskId}/commands/assign-lead"): "assignLead",
    ("post", "/api/v1/tasks/{taskId}/commands/record-routing-disposition"): "recordRoutingDisposition",
    ("post", "/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request"): "acknowledgeSourceIntakeStopRequest",
    ("post", "/api/v1/tasks/{taskId}/commands/record-contact-result"): "recordContactResult",
    ("post", "/api/v1/tasks/{taskId}/commands/review-lead-validity"): "reviewLeadValidity",
    ("get", "/api/v1/commands/{commandId}/receipt"): "getCommandReceipt",
}

PUBLIC_OPERATIONS.update({
    ("get", "/api/v1/session/context"): "getSessionContext",
    ("get", "/api/v1/admin/identity/provider-users"): "listIdentityProviderUsers",
    ("get", "/api/v1/admin/identity/options"): "getIdentityAdminOptions",
    ("get", "/api/v1/admin/identity/principals"): "listIdentityPrincipals",
    ("post", "/api/v1/admin/identity/principals"): "createIdentityPrincipal",
    ("patch", "/api/v1/admin/identity/principals/{id}/display-name"): "renameIdentityPrincipal",
    ("post", "/api/v1/admin/identity/principals/{id}/suspend"): "suspendIdentityPrincipal",
    ("post", "/api/v1/admin/identity/principals/{id}/resume"): "resumeIdentityPrincipal",
    ("post", "/api/v1/admin/identity/principals/{id}/disable"): "disableIdentityPrincipal",
    ("get", "/api/v1/admin/identity/organizations"): "listOrganizationUnits",
    ("post", "/api/v1/admin/identity/organizations"): "createOrganizationUnit",
    ("patch", "/api/v1/admin/identity/organizations/{id}/display-name"): "renameOrganizationUnit",
    ("post", "/api/v1/admin/identity/organizations/{id}/close"): "closeOrganizationUnit",
    ("get", "/api/v1/admin/identity/appointments"): "listAppointments",
    ("post", "/api/v1/admin/identity/appointments"): "createAppointment",
    ("post", "/api/v1/admin/identity/appointments/{id}/suspend"): "suspendAppointment",
    ("post", "/api/v1/admin/identity/appointments/{id}/resume"): "resumeAppointment",
    ("post", "/api/v1/admin/identity/appointments/{id}/end"): "endAppointment",
    ("get", "/api/v1/admin/identity/authority-grants"): "listAuthorityGrants",
    ("post", "/api/v1/admin/identity/authority-grants"): "createAuthorityGrant",
    ("post", "/api/v1/admin/identity/authority-grants/{id}/revoke"): "revokeAuthorityGrant",
})


def _read(root: Path, relative: str, findings: list[str]) -> str:
    try:
        return (root / relative).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        findings.append(f"R1 readiness artifact missing or invalid UTF-8: {relative}")
        return ""


def _mapping(value) -> dict:
    return value if isinstance(value, dict) else {}


def _rows(text: str, heading: str, header: tuple[str, ...], findings: list[str]):
    errors: list[str] = []
    rows = _table(text, heading, header, errors)
    findings.extend(error.replace("R1 contact/evidence", "R1 readiness") for error in errors)
    return rows


def validate(root: Path) -> list[str]:
    findings: list[str] = []
    adr = _read(root, "docs/adr/ADR-0012-r1-projection-readiness-protocol.md", findings)
    baseline = _read(root, "docs/baseline/CURRENT-MVP-BASELINE.md", findings)
    http = _read(root, "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md", findings)
    source = _read(root, "contracts/openapi/ontology-law-api.yaml", findings)
    rows = _rows(adr, "Readiness protocol registry", ("Profile", "Key", "Value"), findings)
    if Counter(rows) != Counter((PROFILE, key, value) for key, value in REGISTRY.items()):
        findings.append("R1 readiness registry must equal the exact approved successor and bounded policy")
    if "Status: Accepted" not in _without_fenced_code(adr):
        findings.append("R1 readiness ADR-0012 must be active and Accepted")
    visible_baseline = _without_fenced_code(baseline)
    if [line for line in visible_baseline if line.startswith("Baseline ID:")] != ["Baseline ID: MVP-2026-09-08.2"]:
        findings.append("R1 readiness active baseline must be exactly MVP-2026-09-08.2")
    if not any("ADR-0012-r1-projection-readiness-protocol.md" in line for line in visible_baseline):
        findings.append("R1 readiness active baseline must name ADR-0012 supersession")
    if "Contract ID: R1-HTTP-V1.4" not in _without_fenced_code(http):
        findings.append("R1 readiness HTTP contract must activate R1-HTTP-V1.4")
    http_rows = _rows(http, "Operations", ("OperationId", "Method", "Path", "TenantSource", "IdempotencyKey", "Preconditions", "SubjectBinding", "SuccessStatus", "ErrorCodes"), findings)
    expected_row = ("checkR1ProjectionReadiness", "GET", READINESS_PATH, "ACTOR_CONTEXT", "NONE", "NONE", "CURRENT_R1_OWNER_ORGANIZATION_COVERAGE", "204", ",".join(ERROR_CODES))
    if len(http_rows) != 37 or [row for row in http_rows if row[0] == "checkR1ProjectionReadiness"] != [expected_row]:
        findings.append("R1 readiness HTTP operation inventory must contain the exact readiness row among thirty-seven operations")
    try:
        document = yaml.load(source, Loader=_StrictSafeLoader)
    except (yaml.YAMLError, TypeError, ValueError) as error:
        findings.append(f"R1 readiness OpenAPI must be strict YAML: {error}")
        return findings
    document = _mapping(document)
    if _mapping(document.get("info")).get("version") != "1.3.0":
        findings.append("R1 readiness OpenAPI version must be exactly 1.3.0")
    if document.get("security") not in (None, []):
        findings.append("R1 readiness does not allow a global security fallback")
    paths = _mapping(document.get("paths"))
    operations = []
    internal = {}
    public = {}
    for path, item in paths.items():
        if not isinstance(path, str) or not isinstance(item, dict):
            findings.append("R1 readiness OpenAPI path item must be a mapping")
            continue
        for method, operation in item.items():
            if method not in METHODS:
                continue
            if not isinstance(operation, dict) or not isinstance(operation.get("operationId"), str):
                findings.append("R1 readiness OpenAPI operation must be a named mapping")
                continue
            operations.append(operation["operationId"])
            if path.startswith("/internal/"):
                internal[(method, path)] = operation["operationId"]
                security = [{"internalMutualTls": []}]
            else:
                public[(method, path)] = operation["operationId"]
                security = [{"publicBearer": []}]
            if operation.get("security") != security:
                findings.append(f"R1 readiness exact security inventory differs: {operation['operationId']}")
    if len(operations) != 37 or len(set(operations)) != 37 or public != PUBLIC_OPERATIONS or internal != INTERNAL_OPERATIONS:
        findings.append("R1 readiness OpenAPI must expose exactly 37 unique operations: 32 public Bearer and 5 named internal mTLS")
    components = _mapping(document.get("components"))
    schemes = _mapping(components.get("securitySchemes"))
    if _mapping(schemes.get("internalMutualTls")).get("type") != "mutualTLS":
        findings.append("R1 readiness requires the existing internalMutualTls scheme")
    path_item = _mapping(paths.get(READINESS_PATH))
    operation = _mapping(path_item.get("get"))
    if path_item.get("parameters") not in (None, []) or "$ref" in path_item:
        findings.append("R1 readiness must not inherit caller parameters or path references")
    if "requestBody" in operation or operation.get("parameters") not in (None, []):
        findings.append("R1 readiness accepts no request body or caller parameters")
    if operation.get("x-tenant-source") != "ACTOR_CONTEXT" or operation.get("x-subject-binding") != "CURRENT_R1_OWNER_ORGANIZATION_COVERAGE":
        findings.append("R1 readiness Tenant and coverage must derive only from trusted Actor and Owner facts")
    if operation.get("x-error-codes") != ERROR_CODES:
        findings.append("R1 readiness error codes must be the exact existing six-code allowlist")
    responses = _mapping(operation.get("responses"))
    if set(responses) != ERROR_STATUSES | {"204"}:
        findings.append("R1 readiness responses must be exactly 204/400/401/403/429/500/503")
    expected_headers = {"Cache-Control": {"required": True, "schema": {"type": "string", "const": "no-store"}}}
    for status, response in responses.items():
        response = _mapping(response)
        allowed = {"description", "headers"} if status == "204" else {"description", "headers", "content"}
        if set(response) != allowed or not isinstance(response.get("description"), str) or response.get("headers") != expected_headers:
            findings.append(f"R1 readiness response {status} must be an exact no-store response without ETag or extra fields")
        if status != "204" and response.get("content") != {"application/problem+json": {"schema": {"$ref": "#/components/schemas/InternalProblem"}}}:
            findings.append(f"R1 readiness response {status} must use only the existing safe InternalProblem schema")
    return findings
