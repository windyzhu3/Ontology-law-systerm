"""Static acceptance of ADR-0013; does not claim runtime authorization works."""
from __future__ import annotations

from collections import Counter
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re

import yaml

try:
    from scripts.baseline.r1_business_closure_contract import _StrictSafeLoader
    from scripts.baseline.r1_contact_evidence_contract import _table, _without_fenced_code
except ModuleNotFoundError:
    from r1_business_closure_contract import _StrictSafeLoader
    from r1_contact_evidence_contract import _table, _without_fenced_code


ADR = 'docs/adr/ADR-0013-r1-command-receipt-recovery.md'
ADR_NAME = 'ADR-0013-r1-command-receipt-recovery.md'
BASELINE = 'docs/baseline/CURRENT-MVP-BASELINE.md'
HTTP = 'docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md'
COMMAND = 'docs/contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md'
WORKBENCH = 'docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md'
RUNTIME = 'database/schema-contract-52-plus-2/docs/runtime-validation-contract.md'
DESIGN = 'docs/superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md'
PLAN = 'docs/superpowers/plans/2026-09-05-r1-business-closure-plan.md'
LEDGER = 'docs/progress/MVP-DELIVERY-LEDGER.md'
API = 'contracts/openapi/ontology-law-api.yaml'
RECEIPT_PATH = '/api/v1/commands/{commandId}/receipt'

# Independently encoded allowlist from approved design sections 3-8. Never derive
# expected rows from the artifact being validated or accept unknown successors.
REGISTRY = {
    'R1_COMMAND_RECEIPT_RECOVERY_V1': {
        'commandAudit': 'R1_COMMAND_AUDIT_V2/2',
        'commands': 'CAPTURE_LEAD,SAVE_ACTION_DRAFT,RESOLVE_DUPLICATE_LEAD,COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,RECORD_ROUTING_DISPOSITION,ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,RECORD_CONTACT_RESULT,REVIEW_LEAD_VALIDITY',
        'outcomes': 'SUCCEEDED,NO_CHANGE,POST_SLOT_REJECTED',
        'summaryFields': 'result,authorizationEvidence,receiptRecovery',
        'recoveryFields': 'profile,scope,binding',
        'scope': 'ORIGINAL_COMMAND_SCOPE_CANONICAL_JSON_OBJECT_CAPTURE_DRAFT_PRIMARY_TASK_ONLY',
        'scopeDigest': 'ORIGINAL_RFC8785_JCS_UTF8_SHA256_EQUALS_SLOT_COMMAND_SCOPE_DIGEST',
        'CAPTURE_LEAD': 'kind=CAPTURE;SOURCE_ACCOUNT_AND_RECORD_KEY_DIGEST_FROM_SCOPE;ORGANIZATION_FROM_AUDIT_FIXED_COLUMNS',
        'SAVE_ACTION_DRAFT': 'kind=DRAFT;lead;taskRevision;draft=NULL_OR_ORIGINAL_EXACT_SELECTOR',
        'taskBinding': 'kind=TASK;evidence=NULL_EXCEPT_RESOLVED_RECORD_CONTACT_RESULT_PAIR',
        'leadSelector': 'type=lead.lead;id;revision',
        'draftSelector': 'type=responsibility.action_draft;id;revision',
        'evidencePair': 'submission={type=evidence.evidence_submission;id;hash};binding={type=evidence.evidence_binding;id;revision}',
        'scalars': 'UUID_LOWERCASE_HYPHENATED;REVISION_JSON_SAFE_INTEGER;HASH_BASE64URL_43_UNPADDED',
        'closed': 'REJECT_DUPLICATE_KEYS_UNKNOWN_FIELDS_WRONG_TYPES_NULL_SELECTOR_PLACEHOLDERS',
        'maxBytes': '8192_CANONICAL_UTF8_INCLUDING_SCOPE',
        'construction': 'SERVER_PARSED_CONTEXT_IMMUTABLE_BEFORE_BUSINESS_SAVEPOINT',
        'consistency': 'UNIQUE_AUDIT_SLOT_RECEIPT_TENANT_COMMAND_ID_TYPE_ENVELOPE_KIND_TERMINAL_RESULT',
        'identity': 'ORIGINAL_PRINCIPAL_APPOINTMENT_NULL_SAFE_ON_BEHALF_PAIR_SUBJECT_SCOPE_FROM_AUDIT_FIXED_COLUMNS',
        'permittedHmac': 'ONLY_SOURCE_RECORD_KEY_DIGEST_INTERNAL_SCOPE_NEVER_RESPONSE_LOG_TRACE_READ_AUDIT_UI',
        'forbiddenData': 'RAW_SOURCE_KEY_PAYLOAD_DRAFT_VALUES_LEGAL_NEED_REASON_CONTACT_CIPHERTEXT_CONTACT_HMAC_TOKEN_SECRET_EXTERNAL_SUBJECT_FULL_REQUEST',
        'atomicity': 'METADATA_FAILURE_ROLLS_BACK_WHOLE_TRANSACTION_NO_INCOMPLETE_V2_NO_ROLLED_BACK_FACT_ASSERTION',
        'internalRecovery': 'ORIGINAL_R1_COMMAND_AUDIT_V1_UNCHANGED',
    },
    'R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1': {
        'caller': 'API_RECEIPT_SERVICE_ONLY_AUDIT_OWNER_NARROW_PORT_QUERY_CLASSIFIED_VIEW',
        'preconditions': 'VALID_BEARER_UNIQUE_TRUSTED_TENANT_PRINCIPAL_APPOINTMENT_KIND_FRESH_ACTIVE_IDENTITY_ORG_CHAIN',
        'locks': 'SHARED_TENANT_BUSINESS_THEN_SHARED_IDENTITY_READ_COMMITTED',
        'predicate': 'TRUSTED_TENANT_EXACT_COMMAND_ID_ORIGINAL_PRINCIPAL_APPOINTMENT_NULL_SAFE_EXACT_ON_BEHALF_PAIR',
        'rows': 'ORIGINAL_COMMAND_EVENT_ONLY_EXCLUDE_CORRECTION_AND_READ_AUDIT_LIMIT_2',
        'projection': 'MATCH_COLUMNS_SCHEMA_VERSION_SUBJECT_SCOPE_COMMAND_TYPE_RESULT_RECOVERY_DIGEST_INPUTS_ONLY',
        'integrity': 'WHOLE_SUMMARY_INTEGRITY_INSIDE_AUDIT_OWNER_NO_CHANGE_SUMMARY_OR_AUTHORIZATION_EVIDENCE_RETURN',
        'exception': 'BEFORE_FULL_BUSINESS_AUTHORIZATION_NO_RECURSIVE_QUERY_AUDIT',
        'forbidden': 'NO_BASE_TABLE_GRANT_EXPANSION_CERT_AUTH_HMAC_UNSCOPED_FALLBACK_BROWSE_PAGINATION_WORKER_INTERNAL_RECOVERY_OTHER_ACTOR',
    },
    'R1_RECEIPT_CURRENT_AUTHORIZATION_V1': {
        'owner': 'SAME_LOCKED_TRANSACTION_UNIQUE_SLOT_RECEIPT_METADATA_CURRENT_NINE_COMMAND_OWNER_PORTS',
        'captureSource': 'CURRENT_TRUSTED_EXACT_ACCOUNT_AND_SERVICE_BINDING_NOT_GENERIC_SOURCE_GRANT',
        'captureOrganization': 'CURRENT_POLICY_ORGANIZATION_EQUALS_ORIGINAL_AUDIT_SUBJECT_TYPE_ID_REVISION_AND_SCOPE_ORGANIZATION_ID',
        'captureLead': 'ORIGINAL_NATURAL_KEY_CURRENT_LEAD_EXACT_TENANT_SELECTOR_LEAD_CAPTURE_DENY_INCLUDING_PRIOR_REJECTED',
        'task': 'ORIGINAL_TASK_ID_REAL_TASK_PERSISTED_LEAD_CURRENT_LEAD_OWNER_APPOINTMENT_PRINCIPAL_ORGANIZATION_EXACT_TYPE_OWNER_OR_ONE_HOP_DELEGATE',
        'draft': 'ORIGINAL_NON_NULL_SELECTOR_SAME_TASK_DRAFT_ID_EXISTS_REVISION_LE_CURRENT;CURRENT_DRAFT_TASK_ACTION_SCHEMA_VERSION_EXACT_EVEN_IF_ORIGINAL_NULL',
        'evidence': 'RESOLVED_EXACT_SUBMISSION_HASH_BINDING_REVISION_NOT_REVOKED_TASK_LEAD_SUBMISSION_BINDING_DENY',
        'authorization': 'CURRENT_GRANT_DELEGATION_ORGANIZATION_VALIDITY_REVOCATION_ALL_SUBJECT_DENY_ONE_COMPLETE_PATH_NO_HISTORICAL_ALLOW_OR_GRANT',
        'attemptedInput': 'SCOPE_DIGEST_INTEGRITY_ONLY_NO_CANDIDATE_ASSIGNEE_PARTY_CAUSAL_ELIGIBILITY_RERUN_NO_HANDLER_VALIDATE_BEFORE_WORK_NO_EXTRA_BODY_READ',
        'terminal': 'DONE_CONFIRMED_RESUMED_ADVANCED_REVISION_ALLOWED_HISTORICAL_SELECTORS_DISTINCT_FROM_CURRENT_AUTHORIZATION',
        'finalEvaluation': 'ALL_SHARED_LOCKS_FRESH_CLOCK_TIMESTAMP_RECHECK_IDENTITY_AUTH_OWNER_NO_POST_COMMIT_QUERY_NO_CACHE_NO_PERSISTENT_PROOF',
        'timeBoundary': 'EXISTING_POST_EVALUATION_NATURAL_EXPIRY_TRANSPORT_RACE_NO_RECEIVE_TIME_GUARANTEE',
    },
    'R1_COMMAND_RECEIPT_DISCLOSURE_V1': {
        'order': 'CURRENT_AUTHORIZATION_THEN_AUDIT_APPEND_SAME_TRANSACTION_COMMIT_ACK_THEN_HTTP_SERIALIZATION',
        'action': 'READ_COMMAND_RECEIPT',
        'fixedColumns': 'ENTRY_TYPE_EVENT_COMMAND_ID_NULL_COMMAND_TYPE_NULL_RESULT_SUCCEEDED_SERVICE_ROLE_API',
        'summarySchema': 'R1_COMMAND_RECEIPT_DISCLOSURE_AUDIT_V1/1',
        'summaryFields': 'profile,version,responseMode,commandId,receiptId,disclosedSource,authorizationAnchor',
        'constants': 'profile=R1_COMMAND_RECEIPT_DISCLOSURE_V1;version=1;responseMode=BODY',
        'disclosedSource': 'execution.command_receipt@hash',
        'receiptHashColumns': 'tenant_id,command_receipt_id,command_execution_slot_id,outcome,rejection_code,completed_at,result_fact_type,result_fact_id,result_fact_revision,result_fact_hash',
        'receiptHashEncoding': 'JCS_SHA256_PHYSICAL_NAMES_UUID_LOWERCASE_BYTEA_BASE64URL_UNPADDED_NULL_JSON_NULL_COMPLETED_AT_UTC_SIX_FRACTION_Z_NO_QUERY_TIME_OR_NEW_COLUMN',
        'authorizationAnchor': 'CURRENT_PRIMARY_SUBJECT_MAY_DIFFER_FROM_DISCLOSED_SOURCE',
        'authorizationDigest': 'ALL_CURRENT_PRIMARY_AND_EXTRA_SUBJECT_DECISIONS_DETERMINISTIC_ORDER_IN_MEMORY_BOUND_BY_AUTHORIZATION_SNAPSHOT_DIGEST',
        'authorizationFact': 'PRIMARY_INDEPENDENT_COMPLETE_PATH_ACTUAL_ACTOR_ON_BEHALF_SERVER_CORRELATION_NO_STITCHING',
        'noCopy': 'NO_RECOVERY_SCOPE_NATURAL_KEY_HMAC_OLD_AUDIT_VALUES_HTTP_BODY_OR_EXTRA_SUMMARY_FIELDS',
        'response': 'ORIGINAL_IMMUTABLE_RECEIPT_FIELD_EQUAL_ACTOR_BOUND_PUBLIC_FACT_REF_NO_NEW_OUTCOME_OR_RECEIPT',
        'success': '200_SUCCEEDED_NO_CHANGE_REJECTED;READ_AUDIT_PLUS_1_ALL_OTHER_DELTA_0',
        'unauthenticated': '401_UNAUTHENTICATED_BEARER_CHALLENGE;DELTA_0',
        'unauthorized': '403_NOT_AUTHORIZED_OR_UNIFORM_HIDDEN_OBJECT_404;DELTA_0_NO_DISCLOSURE',
        'missing': '404_NOT_FOUND_ABSENT_CROSS_TENANT_WRONG_ACTOR_ON_BEHALF_INTERNAL_RECOVERY;DELTA_0',
        'invalidMetadata': '503_SERVICE_UNAVAILABLE_SAME_ACTOR_MISSING_V1_UNSUPPORTED_DUPLICATE_INCONSISTENT;DELTA_0',
        'technicalFailure': '503_SERVICE_UNAVAILABLE_AUDIT_APPEND_LOCK_TIMEOUT;UNCOMMITTED_ROLLBACK_NO_DISCLOSURE',
        'commitAckLoss': '503_SERVICE_UNAVAILABLE;READ_AUDIT_MAY_BE_COMMITTED_NO_CERTAIN_ZERO_DELTA_NO_DISCLOSURE',
        'programmingError': '500_INTERNAL_ERROR_UNCOMMITTED_ROLLBACK_NO_SENSITIVE_RESULT',
        'refusalException': 'NAMED_NON_DISCLOSURE_PROFILE_ONLY_NOT_GENERAL_HIGH_RISK_REFUSAL_EXEMPTION',
        'cache': 'ALL_RESPONSES_NO_STORE_NO_SUCCESS_ETAG_NO_304',
        'failureSafety': 'NO_RECEIPT_RESULT_FACT_RECEIPT_REF_SUCCESS_HEADER_SAFE_DETAIL_EXISTING_ERROR_SHAPE_CODE_RETRY_POLICY',
    },
    'R1_RECEIPT_LEGACY_RECOVERY_V1': {
        'oldRecords': 'NO_REWRITE_BACKFILL_DELETE_TEXT_PARSE_EVENT_ABSENCE_CANDIDATE_TIME_OR_ORGANIZATION_INFERENCE',
        'replay': 'ORIGINAL_ENDPOINT_FULL_ORIGINAL_REQUEST_SAME_IDEMPOTENCY_KEY_CURRENT_AUTHORIZATION_ORIGINAL_TERMINAL_OR_CONFLICT_ALL_DELTA_0',
        'replayForbidden': 'NO_READ_AUDIT_METADATA_REPAIR_BUSINESS_REEXECUTION_RECEIPT_REWRITE',
        'internal': 'ORIGINAL_MTLS_REQUEST_AND_KEY_ONLY_NO_PUBLIC_OR_NEW_MTLS_RECEIPT_GET',
        'getFailure': 'NOT_COMMAND_FAILURE_NO_AUTOMATIC_NEW_KEY_NO_REQUEST_EXTENSION_NO_ADMIN_REPAIR',
    },
    'R1_RECEIPT_RECOVERY_ACTIVATION_V1': {
        'versions': 'MVP-2026-09-08.1;R1-HTTP-V1.3;R1-COMMAND-POLICY-EVENT-V1.2;OPENAPI_1.2.0',
        'inventory': 'OPERATIONS_16_PUBLIC_BEARER_11_INTERNAL_MTLS_5_EVENTS_14',
        'physical': 'SCHEMAS_13_APPLICATION_TABLES_52_TECHNICAL_TABLES_2_52-plus-2-v1.2_V001_V860_MANIFEST_FIELDS_GRANTS_JOOQ_UNCHANGED',
        'application': 'ONE_SPA_ONE_OPENAPI_ONE_JAR_API_WORKER_EXCLUSIVE_NO_NEW_COMMAND_AUTHORITY_SLOT_PROVIDER_AI_R2',
        'acceptance': 'STATIC_FROZEN_ONLY_ORIGINAL_TASK8_JOINT_WRITE_GET_POSTGRES_HTTP_GATE_TASK9_TASK10_CAPACITY_R1_NOT_PROMOTED',
    },
}

ACTIVE_METADATA = {
    ADR: ('Status: Accepted', 'Semantic baseline: MVP-2026-09-08.1',
          'HTTP contract: R1-HTTP-V1.3', 'Command contract: R1-COMMAND-POLICY-EVENT-V1.2', 'OpenAPI version: 1.2.0'),
    BASELINE: ('Baseline ID: MVP-2026-09-08.3',),
    HTTP: ('Contract ID: R1-HTTP-V1.5',),
    COMMAND: ('Contract ID: R1-COMMAND-POLICY-EVENT-V1.3', 'Semantic baseline: MVP-2026-09-08.3'),
}
POINTERS = (BASELINE, HTTP, COMMAND, WORKBENCH, RUNTIME, DESIGN, PLAN)
# Each consumer declares which exact ADR protocol it consumes, separate from a
# narrative link that could refer only to historical evidence.
CONSUMER_PROFILES = {
    BASELINE: 'R1_RECEIPT_RECOVERY_ACTIVATION_V1',
    HTTP: 'R1_COMMAND_RECEIPT_DISCLOSURE_V1',
    COMMAND: 'R1_COMMAND_RECEIPT_RECOVERY_V1',
    WORKBENCH: 'R1_RECEIPT_LEGACY_RECOVERY_V1',
    RUNTIME: 'R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1',
    DESIGN: 'R1_RECEIPT_RECOVERY_ACTIVATION_V1',
    PLAN: 'R1_RECEIPT_RECOVERY_ACTIVATION_V1',
}
# ADR-0015 exact transport successor; ADR-0013's registry itself remains historical and immutable.
OPENAPI_FROZEN_HASH = '5e912176a32b49aaa68f182be7832f8355fa2060082812351c9847624fb42717'
OPENAPI_DESCRIPTION = (
    'ADR-0013 / R1-HTTP-V1.3: only the identical original trusted Actor and on-behalf pair may recover a public command receipt. '
    'R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1 obtains bounded metadata inside Audit Owner; current Owner authorization is recomputed without rerunning terminal command eligibility. '
    'READ_COMMAND_RECEIPT Audit must commit with acknowledgement before serialization. All responses use Cache-Control: no-store; no success ETag or 304. '
    'Missing or invalid same-Actor recovery metadata safely fails with existing SERVICE_UNAVAILABLE; wrong Actor, Tenant or internal recovery is NOT_FOUND. '
    'Legacy recovery requires the complete original request and original Idempotency-Key at its original endpoint, with zero replay delta; never automatically use a new key.'
)


def _read(root: Path, relative: str, findings: list[str]) -> str:
    try:
        return (root / relative).read_text(encoding='utf-8')
    except (OSError, UnicodeError):
        findings.append(f'R1 receipt recovery artifact missing or invalid UTF-8: {relative}')
        return ''


@lru_cache(maxsize=8)
def _validate_transport(source: str) -> tuple[str, ...]:
    """Memoize only immutable source text parsing, never file paths or decisions.

    Registry mutation suites reuse the large frozen transport document. A changed
    byte string is always checked anew; this has no runtime authorization cache.
    """
    findings: list[str] = []
    try:
        document = yaml.load(source, Loader=_StrictSafeLoader)
        operation = document['paths'][RECEIPT_PATH]['get']
        if operation.pop('description', None) != OPENAPI_DESCRIPTION:
            findings.append('R1 receipt recovery OpenAPI must describe the exact authorization, audit, cache and legacy semantics')
        if operation['responses']['200'].pop('description', None) != 'Original terminal receipt projection, disclosed only after READ_COMMAND_RECEIPT Audit commit acknowledgement; Cache-Control: no-store.':
            findings.append('R1 receipt recovery success description must require audited no-store disclosure')
        digest = hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
        if digest != OPENAPI_FROZEN_HASH:
            findings.append('R1 receipt recovery OpenAPI parsed inventory, DTO, error, security and request/response shapes must remain frozen')
    except (yaml.YAMLError, ValueError, TypeError, KeyError, AttributeError):
        findings.append('R1 receipt recovery OpenAPI must be a valid strict document with the existing receipt operation')
    return tuple(findings)


def validate(root: Path) -> list[str]:
    findings: list[str] = []
    texts = {relative: _read(root, relative, findings)
             for relative in (ADR, *POINTERS, LEDGER, API)}
    errors: list[str] = []
    rows = _table(texts[ADR], 'Receipt recovery protocol registry', ('Profile', 'Key', 'Value'), errors)
    findings.extend(error.replace('R1 contact/evidence', 'R1 receipt recovery') for error in errors)
    expected = Counter((profile, key, value) for profile, rules in REGISTRY.items() for key, value in rules.items())
    if Counter(rows) != expected:
        findings.append('R1 receipt recovery registry must equal the exact approved closed protocol')
    for relative, declarations in ACTIVE_METADATA.items():
        lines = _without_fenced_code(texts[relative])
        for declaration in declarations:
            key = declaration.split(':', 1)[0] + ':'
            if [line for line in lines if line.startswith(key)] != [declaration]:
                findings.append(f'R1 receipt recovery active metadata must declare exactly {declaration} in {relative}')
    for relative in POINTERS:
        lines = _without_fenced_code(texts[relative])
        declarations = [line for line in lines if line.startswith('Receipt recovery authority: ')]
        valid = len(declarations) == 1
        if valid:
            match = re.fullmatch(r'Receipt recovery authority: \[ADR-0013\]\(([^)]+)\); profile: ([A-Z0-9_]+)', declarations[0])
            valid = bool(match and match[2] == CONSUMER_PROFILES[relative]
                         and (root / relative).parent.joinpath(match[1]).resolve() == (root / ADR).resolve()
                         and (root / ADR).is_file())
        if not valid:
            findings.append(f'R1 receipt recovery requires exact resolved ADR-0013 authority and profile in {relative}')
    # The only new delivery rows are static. Historical and later runtime gates
    # continue to be evaluated by the existing ledger verifier.
    ledger_lines = _without_fenced_code(texts[LEDGER])
    for row_id, version in (('BASE-CURRENT-MVP-2026-09-05-2026-09-06.1-2026-09-06.2-2026-09-06.3-2026-09-07.1-2026-09-08.1', 'MVP-2026-09-08.1'),
                            ('R1-RECEIPT-RECOVERY-CONTRACT', 'r1-receipt-recovery-v1')):
        matching = [line for line in ledger_lines if line.startswith(f'| {row_id} |')]
        cells = [cell.strip() for cell in matching[0].strip('|').split('|')] if len(matching) == 1 else []
        allowed_states = {'FROZEN', 'MERGED'}
        successor = row_id + '-2026-09-08.2' if row_id.startswith('BASE-CURRENT-MVP') else '—'
        if len(cells) != 12 or cells[6] != version or cells[8] not in allowed_states or cells[11] != successor or ADR_NAME not in cells[9]:
            findings.append(f'R1 receipt recovery ledger row {row_id} must remain a static successor with ADR evidence')
    findings.extend(_validate_transport(texts[API]))
    return findings
