import io
import re
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

from scripts.baseline import verify_baseline as verify_baseline_module
from scripts.baseline.verify_baseline import HISTORICAL_BANNER, verify_repository


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "verify_baseline.py"
CANONICAL_BASELINE_ID = "MVP-2026-09-05.1"
HISTORICAL_BASELINE_ID = "MVP-2026-08-28.1"
CANONICAL_MATTER_PUBLICATION_CLAUSE = (
    "同一本地事务必须写入完整MatterRef槽：稳定`matter_id`、`matter_no`、类型、"
    "能力包版本和可信创建时间，并发布`MatterCreated`事实通知供Post-MVP消费者使用。"
)
CANONICAL_MATTER_PROHIBITION_CLAUSE = (
    "Post-MVP不得生成第二Matter身份或反向改写销售历史；"
)
CANONICAL_MATTER_SECTION = "\n".join(
    [
        "## matter-endpoint",
        "",
        "销售MVP终点固定为：",
        "",
        "```text",
        "DecisionRecorded(TRANSFER_REVIEW, ACCEPT)",
        "+ TransferAccepted",
        "+ TransferRequest的一次写入MatterRef",
        "+ 案管Task DONE",
        "+ 销售结果回执",
        "```",
        "",
        CANONICAL_MATTER_PUBLICATION_CLAUSE,
        "",
        (
            "MVP不建Matter业务表、页面或办理责任，不建设登记资料、分类、分案、承办团队、"
            "节点、期限、办理、成果或结案Task。Post-MVP不得生成第二Matter身份或反向改写"
            "销售历史；Matter模块只能消费已接受转案及其稳定MatterRef。MatterRef表示正式"
            "稳定身份已被分配，不表示完整Matter聚合或案件办理能力已经启用。"
        ),
    ]
)
MATTER_ENDPOINT_FINDING = (
    "Current baseline must freeze Matter endpoint with same-transaction "
    "MatterCreated, no second Matter identity, and no reverse rewrite of "
    "sales history: docs/baseline/CURRENT-MVP-BASELINE.md"
)
HISTORICAL_SPECS = [
    "2026-08-17-law-firm-ontology-todo-chatbot-design.md",
    "2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md",
    "2026-08-18-law-firm-overall-architecture-ontology-design.md",
    "2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md",
    "2026-08-18-ontology-law-system-foundation-architecture-v1.0.md",
    "2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md",
    "2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md",
]
LEGACY_ONE_LINE_WARNING = (
    "> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；"
    "与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，"
    "以当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。"
)
EXACT_HISTORICAL_WARNING = (
    "> [!WARNING]\n"
    "> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；"
    "与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，"
    "当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。"
)
HISTORICAL_WARNING = EXACT_HISTORICAL_WARNING
HISTORICAL_REPLACEMENT_HEADING = "## 历史修订记录（已被当前基线替代）"
CONFLICT_SECTIONS = {
    "2026-08-18-law-firm-overall-architecture-ontology-design.md": {
        "### 3.2 MVP终点": "matter-endpoint",
        "### 7.2 Task状态机": "task-waiting-contract",
        "## 9. WAITING、WaitReceipt与Chat状态": "task-waiting-contract",
        "## 17. 《最小Matter身份与后MVP扩展契约 v1.0》（冻结）": "matter-endpoint",
        "## 18. 后MVP Matter扩展契约": "matter-endpoint",
    },
    "2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md": {
        "### 3.1 MVP终点": "matter-endpoint",
        "## 4. 最小Matter本体": "matter-endpoint",
        "## 5. MatterOpeningPort": "matter-endpoint",
        "## 6. 接收事务与因果顺序": "matter-endpoint",
        "## 7. MatterLink": "matter-endpoint",
        "## 13. 验收级不变量": "matter-endpoint",
        "## 14. 版本治理": "matter-endpoint",
    },
    "2026-08-18-ontology-law-system-foundation-architecture-v1.0.md": {
        "## 3. 已冻结的总体方案": "application-topology",
        "## 4. 目标技术基线": "application-topology",
        "## 5. 运行拓扑": "application-topology",
        "## 6. 前端与通道边界": "application-topology",
        "### 12.1 Task不变量": "task-waiting-contract",
        "### 12.4 WAITING与WaitReceipt": "task-waiting-contract",
        "### 28.2 ChangeGate": "application-topology",
        "## 31. 下一层详细设计边界": "application-topology",
    },
    "2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md": {
        "## 2. 冻结决策摘要": "application-topology",
        "### 7.3 五个跨模块原子边界": "matter-endpoint",
        "## 12. OpenAPI与前端工作区": "application-topology",
        "### 16.1 固定检查组": "application-topology",
        "## 18. 完成判据": "application-topology",
    },
    "2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md": {
        "### 1.2 适用优先级": "application-topology",
        "### 1.4 六种表形态": "task-waiting-contract",
        "### 5.2 SemanticKind": "task-waiting-contract",
        "### 6.7 Task与唯一完成事实": "task-waiting-contract",
        "## 11. 冻结声明": "application-topology",
    },
}
LEDGER_HEADERS = [
    "ID",
    "Release",
    "Capability",
    "Layer",
    "Artifact",
    "Owner",
    "Version",
    "Target gate",
    "State",
    "Evidence",
    "Blocker/next gate",
    "Superseded by",
]

R1_TASK_ROWS = [
    ("RESOLVE_LEAD_DUPLICATE", "RESOLVE_DUPLICATE_LEAD", "responsibility.decision_record"),
    ("COMPLETE_LEAD_INGRESS", "COMPLETE_LEAD_INGRESS", "lead.lead"),
    ("ASSIGN_LEAD", "ASSIGN_LEAD", "lead.lead_assignment"),
    ("RESOLVE_LEAD_ROUTING_GAP", "RECORD_ROUTING_DISPOSITION", "responsibility.decision_record"),
    ("ACK_SOURCE_INTAKE_STOP_REQUEST", "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST", "responsibility.decision_record"),
    ("CONTACT_LEAD", "RECORD_CONTACT_RESULT", "lead.lead_contact_result"),
    ("REVIEW_LEAD_VALIDITY", "REVIEW_LEAD_VALIDITY", "responsibility.decision_record"),
]

R1_BRANCH_ROWS = [
    ("P0_01_LINK_EXISTING", "RESOLVE_LEAD_DUPLICATE", "LINK_EXISTING_PARTY"),
    ("P0_01_KEEP_SEPARATE", "RESOLVE_LEAD_DUPLICATE", "KEEP_SEPARATE"),
    ("P0_02_COMPLETE", "COMPLETE_LEAD_INGRESS", "INGRESS_COMPLETED"),
    ("P0_03_ASSIGN", "ASSIGN_LEAD", "ASSIGNED"),
    ("P0_04_SCHEDULE_ROUTING_REVIEW", "RESOLVE_LEAD_ROUTING_GAP", "SCHEDULE_ROUTING_REVIEW"),
    ("P0_04_RETRY_ASSIGNMENT_NOW", "RESOLVE_LEAD_ROUTING_GAP", "RETRY_ASSIGNMENT_NOW"),
    ("P0_04_REQUEST_SOURCE_INTAKE_STOP", "RESOLVE_LEAD_ROUTING_GAP", "REQUEST_SOURCE_INTAKE_STOP"),
    ("ACK_SOURCE_INTAKE_STOP_REQUEST", "ACK_SOURCE_INTAKE_STOP_REQUEST", "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED"),
    ("CONTACT_CONNECTED_VALID", "CONTACT_LEAD", "CONNECTED_VALID"),
    ("CONTACT_NOT_CONNECTED_RETRY", "CONTACT_LEAD", "NOT_CONNECTED"),
    ("CONTACT_NOT_CONNECTED_EXHAUSTED", "CONTACT_LEAD", "NOT_CONNECTED"),
    ("CONTACT_SUSPECT_INVALID", "CONTACT_LEAD", "SUSPECT_INVALID"),
    ("REVIEW_CONFIRM_INVALID", "REVIEW_LEAD_VALIDITY", "CONFIRM_INVALID"),
    ("REVIEW_CLOSE_UNREACHED", "REVIEW_LEAD_VALIDITY", "CLOSE_UNREACHED"),
    ("REVIEW_REOPEN_CONTACT", "REVIEW_LEAD_VALIDITY", "REOPEN_CONTACT"),
]

R1_SUCCESSOR_ROWS = {
    "P0_01_LINK_EXISTING": ("COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP", "R1_LEAD_NEXT_RESPONSIBILITY_V1", "POLICY_SELECTED"),
    "P0_01_KEEP_SEPARATE": ("COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP", "R1_LEAD_NEXT_RESPONSIBILITY_V1", "POLICY_SELECTED"),
    "P0_02_COMPLETE": ("ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP", "R1_LEAD_NEXT_RESPONSIBILITY_V1", "POLICY_SELECTED"),
    "P0_03_ASSIGN": ("CONTACT_LEAD", "DIRECT", "ASSIGNMENT_OWNER"),
    "P0_04_SCHEDULE_ROUTING_REVIEW": ("RESOLVE_LEAD_ROUTING_GAP", "NEXT_BUSINESS_WINDOW", "SAME_ROUTING_SUPERVISOR"),
    "P0_04_RETRY_ASSIGNMENT_NOW": ("CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP", "R1_ASSIGNMENT_RETRY_V1", "POLICY_SELECTED"),
    "P0_04_REQUEST_SOURCE_INTAKE_STOP": ("ACK_SOURCE_INTAKE_STOP_REQUEST", "DIRECT", "SOURCE_INTAKE_OWNER"),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": ("NONE", "NONE", "NONE"),
    "CONTACT_CONNECTED_VALID": ("NONE", "OPPORTUNITY_BOUNDARY_V1", "NONE"),
    "CONTACT_NOT_CONNECTED_RETRY": ("CONTACT_LEAD", "CONTACT_RETRY_V1", "SAME_ASSIGNMENT_OWNER"),
    "CONTACT_NOT_CONNECTED_EXHAUSTED": ("REVIEW_LEAD_VALIDITY", "CONTACT_RETRY_V1", "ROUTING_SUPERVISOR"),
    "CONTACT_SUSPECT_INVALID": ("REVIEW_LEAD_VALIDITY", "DIRECT", "ROUTING_SUPERVISOR"),
    "REVIEW_CONFIRM_INVALID": ("NONE", "NONE", "NONE"),
    "REVIEW_CLOSE_UNREACHED": ("NONE", "NONE", "NONE"),
    "REVIEW_REOPEN_CONTACT": ("CONTACT_LEAD", "DIRECT", "CURRENT_ASSIGNMENT_OWNER"),
}

R1_ERROR_CODES = [
    ("VALIDATION_FAILED", "400", "SAME_KEY_AFTER_FIX", "REQUIRED", "NONE"),
    ("IDEMPOTENCY_KEY_REQUIRED", "400", "SAME_KEY_AFTER_FIX", "NONE", "NONE"),
    ("IDEMPOTENCY_KEY_INVALID", "400", "SAME_KEY_AFTER_FIX", "NONE", "NONE"),
    ("UNAUTHENTICATED", "401", "SAME_KEY_AFTER_REAUTH", "NONE", "NONE"),
    ("NOT_AUTHORIZED", "403", "NO", "NONE", "NONE"),
    ("APPOINTMENT_INACTIVE", "403", "NO", "NONE", "NONE"),
    ("NOT_FOUND", "404", "NO", "NONE", "NONE"),
    ("COMMAND_PAYLOAD_CONFLICT", "409", "NO", "NONE", "NONE"),
    ("TASK_NOT_OPEN", "409", "NO", "NONE", "TASK"),
    ("TASK_ALREADY_COMPLETED", "409", "NO", "NONE", "TASK"),
    ("DRAFT_DIGEST_MISMATCH", "409", "NEW_KEY_AFTER_REFRESH", "NONE", "DRAFT"),
    ("INGRESS_COMPLETION_ALREADY_RECORDED", "409", "NO", "NONE", "SUBJECT"),
    ("STALE_TASK", "412", "NEW_KEY_AFTER_REFRESH", "NONE", "TASK"),
    ("STALE_DRAFT", "412", "NEW_KEY_AFTER_REFRESH", "NONE", "DRAFT"),
    ("STALE_SUBJECT", "412", "NEW_KEY_AFTER_REFRESH", "NONE", "SUBJECT"),
    ("SUPERVISOR_UNRESOLVED", "422", "NEW_KEY_AFTER_ADMIN_FIX", "NONE", "NONE"),
    ("SOURCE_INTAKE_OWNER_UNRESOLVED", "422", "NEW_KEY_AFTER_ADMIN_FIX", "NONE", "NONE"),
    ("DRAFT_PRECONDITION_REQUIRED", "428", "SAME_KEY_AFTER_FIX", "NONE", "DRAFT"),
    ("TASK_PRECONDITION_REQUIRED", "428", "SAME_KEY_AFTER_FIX", "NONE", "TASK"),
    ("RATE_LIMITED", "429", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
    ("INTERNAL_ERROR", "500", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
    ("SERVICE_UNAVAILABLE", "503", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
]


def markdown_row(*cells: str) -> str:
    return "| " + " | ".join(cells) + " |"


def visual_assets() -> list[tuple[str, str]]:
    sales_base = [
        f"docs/design/sales-mvp-workcards/frozen/{index:02d}-{name}.png"
        for index, name in enumerate(
            [
                "contact-lead-review", "opportunity-progress", "prepare-quote",
                "contract-approval", "follow-first-payment", "fix-transfer-upload",
                "conflict-input", "prepare-contract", "submit-signature-evidence",
                "quote-response", "confirm-payment", "transfer-accept",
            ],
            start=1,
        )
    ]
    sales_p0 = [
        f"docs/design/sales-mvp-workcards/p0/P0-{index:02d}-{name}.png"
        for index, name in enumerate(
            [
                "duplicate-lead", "missing-contact", "manual-owner", "zero-candidate",
                "invalid-lead-review", "opportunity-disposition", "quote-approval-request",
                "quote-authorization", "quote-send", "quote-send-correction",
                "quote-unknown-disposition", "conflict-finding-decision", "first-transfer",
                "transfer-return", "transfer-resubmit",
            ],
            start=1,
        )
    ]
    identity = [
        f"docs/design/identity-admin-mvp/frozen/ADM-{index:02d}-{name}.png"
        for index, name in enumerate(
            [
                "identity-principals", "organization-units", "appointments", "authority-grants",
                "delegation-grants", "object-access-grants", "audit-records",
            ],
            start=1,
        )
    ]
    return [(f"VIS-SALES-BASE-{index:02d}", path) for index, path in enumerate(sales_base, 1)] + [
        (f"VIS-SALES-P0-{index:02d}", path) for index, path in enumerate(sales_p0, 1)
    ] + [(f"VIS-IDENTITY-ADM-{index:02d}", path) for index, path in enumerate(identity, 1)]


class VerifyBaselineTest(unittest.TestCase):
    def write_r1_contract_fixture(self, root: Path) -> None:
        task_header = (
            "TaskType", "BusinessPurpose", "SubjectSelector", "OwnerAuthoritySlot",
            "PrimaryCommand", "PayloadSchema", "CompletionFactType", "CompletionBinding",
            "NaturalIdempotencyKey", "LockRoot", "SLA",
        )
        task_lines = [markdown_row(*task_header), markdown_row(*(["---"] * len(task_header)))]
        completion_binding_by_fact = {
            "lead.lead": "revision",
            "lead.lead_assignment": "revision",
            "lead.lead_contact_result": "hash",
            "responsibility.decision_record": "hash",
        }
        for task_type, command, completion_fact in R1_TASK_ROWS:
            task_lines.append(
                markdown_row(
                    task_type, task_type, "lead.lead@revision", "LEAD_INTAKE_OWNER",
                    command, f"{command}_V1@1", completion_fact,
                    completion_binding_by_fact[completion_fact],
                    "tenant+operation+Idempotency-Key", "tenant+lead", "R1_TEST_V1@1s",
                )
            )

        branch_header = (
            "BranchID", "TaskType", "OutcomeCode", "ReceiptResult",
            "CompletionFactType", "CompletionBinding", "EventType", "QueueOwner",
            "AllowedSuccessorTaskTypes", "SuccessorPolicy", "SuccessorOwnerSlot",
        )
        task_fact = {task: fact for task, _, fact in R1_TASK_ROWS}
        branch_lines = [
            markdown_row(*branch_header),
            markdown_row(*(["---"] * len(branch_header))),
        ]
        e2e_header = (
            "ScenarioID", "BranchID", "FactDelta", "TaskDelta", "SuccessorDelta",
            "ReceiptEventOutboxAudit", "IsolationRollback",
        )
        e2e_lines = [
            markdown_row(*e2e_header),
            markdown_row(*(["---"] * len(e2e_header))),
        ]
        for branch_id, task_type, outcome in R1_BRANCH_ROWS:
            completion_fact = task_fact[task_type]
            successor_types, successor_policy, successor_owner = R1_SUCCESSOR_ROWS[branch_id]
            receipt_result, completion_binding, event_type, queue_owner = (
                verify_baseline_module.R1_BRANCH_DETAIL_CONTRACTS[branch_id]
            )
            branch_lines.append(
                markdown_row(
                    branch_id, task_type, outcome, receipt_result, completion_fact,
                    completion_binding, event_type, queue_owner,
                    successor_types, successor_policy, successor_owner,
                )
            )
        for scenario_id, row in verify_baseline_module.R1_E2E_CONTRACTS.items():
            e2e_lines.append(
                markdown_row(scenario_id, *row)
            )
        candidate_payload_lines = [
            markdown_row("PrimaryCommand", "ExactCandidateFields"),
            markdown_row("---", "---"),
            markdown_row(
                "RESOLVE_DUPLICATE_LEAD",
                "decisionCode,candidateLeadId,candidateLeadRevision,partyId,partyRevision,rationaleSummary",
            ),
            markdown_row("COMPLETE_LEAD_INGRESS", "phone?,email?,sourceCode,sourceSummary"),
            markdown_row("ASSIGN_LEAD", "ownerAppointmentId"),
            markdown_row("RECORD_ROUTING_DISPOSITION", "decisionCode,rationaleSummary"),
            markdown_row(
                "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
                "causalDecisionId,causalDecisionHash,rationaleSummary",
            ),
            markdown_row(
                "RECORD_CONTACT_RESULT",
                "leadAssignmentId,leadAssignmentRevision,contactChannelCode,resultCode,resultSummary?,legalNeed?,evidenceSubmissionId?",
            ),
            markdown_row(
                "REVIEW_LEAD_VALIDITY",
                "triggeringContactResultId,triggeringContactResultHash,decisionCode,rationaleSummary",
            ),
        ]
        candidate_condition_lines = [
            markdown_row("PrimaryCommand", "ExactConditionalValidation"),
            markdown_row("---", "---"),
            markdown_row(
                "COMPLETE_LEAD_INGRESS",
                "at-least-one-of-phone-email",
            ),
            markdown_row(
                "RECORD_CONTACT_RESULT",
                "legalNeed-required-when-CONNECTED_VALID-and-forbidden-otherwise",
            ),
        ]
        duplicate_transition_lines = [
            markdown_row(
                "BranchID",
                "RequiredCurrentDisposition",
                "CandidateSelectors",
                "CurrentLeadCAS",
                "ForbiddenCurrentLeadChanges",
                "CandidateLeadPartyMutation",
                "DecisionDigest",
                "SuccessorSelector",
            ),
            markdown_row(*(["---"] * 8)),
            markdown_row(
                "P0_01_LINK_EXISTING",
                "CAPTURED",
                "candidateLead@revision+party@revision:revalidate",
                "parsed_party_id=candidate.parsed_party_id;party_resolution_code=RESOLVED;disposition_code=LINK_EXISTING_PARTY;revision=old+1",
                "current_assignment_id,capture_fields,ingress_slot",
                "NONE",
                "old-current-lead-selector+candidate-lead-party-selectors+new-values+new-revision",
                "post-CAS-lead-revision;duplicate-only-when-CAPTURED",
            ),
            markdown_row(
                "P0_01_KEEP_SEPARATE",
                "CAPTURED",
                "candidateLead@revision+party@revision:revalidate",
                "disposition_code=KEEP_SEPARATE;revision=old+1",
                "parsed_party_id,party_resolution_code,current_assignment_id,capture_fields,ingress_slot",
                "NONE",
                "old-current-lead-selector+candidate-lead-party-selectors+KEEP_SEPARATE+new-revision",
                "post-CAS-lead-revision;duplicate-only-when-CAPTURED",
            ),
        ]
        code_allowlist_lines = [
            markdown_row("Code domain", "Allowed values"),
            markdown_row("---", "---"),
            markdown_row(
                "Lead disposition used by R1",
                "`CAPTURED`, `LINK_EXISTING_PARTY`, `KEEP_SEPARATE`",
            ),
        ]
        self.write(
            root,
            "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
            "\n".join(
                [
                    "# R1 task completion matrix",
                    "",
                    "Contract ID: R1-TASK-COMPLETION-V1",
                    "",
                    "Status: FROZEN",
                    "",
                    "### Persisted subject and secondary bindings",
                    "R1_COMMAND_SCOPE_V1",
                    "### Non-completion command Receipt results",
                    "R1_DUPLICATE_CANDIDATE_V1",
                    "R1_BUSINESS_WINDOW_V1",
                    "**Pre-slot gate：**",
                    "`legalNeed: SafeText2000`",
                    "",
                    "## Task registry",
                    "",
                    *task_lines,
                    "",
                    "## Completion branches",
                    "",
                    *branch_lines,
                    "",
                    "## Candidate payload registry",
                    "",
                    *candidate_payload_lines,
                    "",
                    "## Candidate payload condition registry",
                    "",
                    *candidate_condition_lines,
                    "",
                    "## Duplicate resolution transition registry",
                    "",
                    *duplicate_transition_lines,
                    "",
                    "## R1 code allowlists",
                    "",
                    *code_allowlist_lines,
                    "",
                    "## E2E deltas",
                    "",
                    *e2e_lines,
                    "",
                ]
            ),
        )

        error_header = (
            "ErrorCode", "HttpStatus", "RetryPolicy", "FieldErrors", "CurrentETag", "SafeText",
        )
        error_lines = [
            markdown_row(*error_header),
            markdown_row(*(["---"] * len(error_header))),
        ]
        for code, status, retry, fields, etag in R1_ERROR_CODES:
            error_lines.append(
                markdown_row(code, status, retry, fields, etag, f"Safe {code.lower()} message")
            )
        operation_header = (
            "OperationId", "Method", "Path", "TenantSource", "IdempotencyKey",
            "Preconditions", "SubjectBinding", "SuccessStatus", "ErrorCodes",
        )
        operation_rows = [
            ("captureLead", "POST", "/api/v1/leads", "ACTOR_CONTEXT", "REQUIRED", "NONE", "SOURCE_NATURAL_KEY", "201"),
            ("getCurrentWorkCard", "GET", "/api/v1/workcards/current", "ACTOR_CONTEXT", "NONE", "OPTIONAL_WORKBENCH_ETAG", "ACTOR_SCOPE", "200/304"),
            ("saveActionDraft", "PUT", "/api/v1/tasks/{taskId}/draft", "ACTOR_CONTEXT", "REQUIRED", "IF_NONE_MATCH_STAR_OR_DRAFT_ETAG", "TASK_AND_DRAFT", "200/201"),
            ("resolveDuplicateLead", "POST", "/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_AND_LEAD_REVISION", "200"),
            ("completeLeadIngress", "POST", "/api/v1/tasks/{taskId}/commands/complete-lead-ingress", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_AND_LEAD_REVISION", "200"),
            ("assignLead", "POST", "/api/v1/tasks/{taskId}/commands/assign-lead", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_LEAD_AND_ASSIGNMENT", "200"),
            ("recordRoutingDisposition", "POST", "/api/v1/tasks/{taskId}/commands/record-routing-disposition", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_AND_LEAD_REVISION", "200"),
            ("acknowledgeSourceIntakeStopRequest", "POST", "/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_AND_CAUSAL_DECISION", "200"),
            ("recordContactResult", "POST", "/api/v1/tasks/{taskId}/commands/record-contact-result", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_LEAD_AND_ASSIGNMENT", "200"),
            ("reviewLeadValidity", "POST", "/api/v1/tasks/{taskId}/commands/review-lead-validity", "ACTOR_CONTEXT", "REQUIRED", "TASK_ETAG", "TASK_AND_CAUSAL_RESULT", "200"),
            ("getCommandReceipt", "GET", "/api/v1/commands/{commandId}/receipt", "ACTOR_CONTEXT", "NONE", "NONE", "COMMAND_ID_AND_ACTOR_SCOPE", "200"),
            ("reopenDueContactTasks", "POST", "/internal/v1/tasks/commands/reopen-due-contact-tasks", "ACTOR_CONTEXT", "REQUIRED", "NONE", "DUE_CUTOFF_AND_OWNER_QUEUE", "200"),
            ("reopenDueRoutingReviewTasks", "POST", "/internal/v1/tasks/commands/reopen-due-routing-review-tasks", "ACTOR_CONTEXT", "REQUIRED", "NONE", "DUE_CUTOFF_AND_OWNER_QUEUE", "200"),
        ]
        operation_lines = [
            markdown_row(*operation_header),
            markdown_row(*(["---"] * len(operation_header))),
        ]
        for row in operation_rows:
            errors = ",".join(sorted(verify_baseline_module.R1_OPERATION_ERRORS[row[0]]))
            operation_lines.append(markdown_row(*row, errors))
        idempotency_header = ("Property", "FrozenValue")
        idempotency_lines = [
            markdown_row(*idempotency_header),
            markdown_row("---", "---"),
            markdown_row("Header", "Idempotency-Key"),
            markdown_row("ValueType", "UUID"),
            markdown_row("SlotColumn", "execution.command_execution_slot.command_id"),
            markdown_row("CommandId", "EXACT_CALLER_KEY"),
            markdown_row("ReceiptId", "SERVER_UUIDV7"),
            markdown_row("SlotScope", "TENANT_ENVELOPE_SUBJECT_SCOPE"),
            markdown_row("PayloadConflict", "ORIGINAL_RECEIPT_NO_NEW_WRITES"),
        ]
        authentication_challenge_lines = [
            markdown_row("SecurityScheme", "Operations", "UnauthenticatedTransport"),
            markdown_row("---", "---", "---"),
            markdown_row(
                "publicBearer",
                "/api/v1/**",
                "HTTP_401_PROBLEM_WITH_WWW_AUTHENTICATE_BEARER",
            ),
            markdown_row(
                "internalMutualTls",
                "reopenDueContactTasks,reopenDueRoutingReviewTasks",
                "TLS_REJECTION_OR_HTTP_401_PROBLEM_WITHOUT_WWW_AUTHENTICATE",
            ),
        ]
        self.write(
            root,
            "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
            "\n".join(
                [
                    "# R1 HTTP error and precondition matrix",
                    "",
                    "Contract ID: R1-HTTP-V1",
                    "",
                    "Status: FROZEN",
                    "",
                    "## Request DTO catalog",
                    "`legalNeed: SafeText2000`",
                    "## Successful response projections",
                    "## ETag contract",
                    "## ActionDraft confirmation lifecycle",
                    "## OpenAPI security binding",
                    "",
                    "## Authentication challenge binding",
                    "",
                    *authentication_challenge_lines,
                    "",
                    "## Operations",
                    "",
                    *operation_lines,
                    "",
                    "## Idempotency binding",
                    "",
                    *idempotency_lines,
                    "",
                    "## Error registry",
                    "",
                    *error_lines,
                    "",
                ]
            ),
        )

        envelope_header = ("Field", "Cardinality", "Contract")
        envelope_lines = [
            markdown_row(*envelope_header),
            markdown_row(*(["---"] * len(envelope_header))),
            markdown_row("todaySummary", "1", "one safe sentence"),
            markdown_row("currentCard", "0..1", "only full card"),
            markdown_row("nextSummaries", "0..2", "summary only"),
            markdown_row("waitingCount", "1", "nonnegative integer"),
            markdown_row("chatComposer", "1", "fixed bottom candidate input"),
        ]
        route_header = ("RouteMode", "PathPattern", "Navigation", "Sidebar")
        route_lines = [
            markdown_row(*route_header),
            markdown_row(*(["---"] * len(route_header))),
            markdown_row("WORKBENCH", "/workbench", "NONE", "NONE"),
            markdown_row("IDENTITY_ADMIN", "/admin/identity/*", "IDENTITY_ONLY", "LEFT"),
        ]
        self.write(
            root,
            "docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md",
            "\n".join(
                [
                    "# R1 workbench presentation contract",
                    "",
                    "Contract ID: R1-WORKBENCH-V1",
                    "",
                    "Status: FROZEN",
                    "",
                    "## CurrentWorkCardEnvelope wire projection",
                    "`PreconditionTokens`",
                    "## Command form and Draft projection",
                    "`ActionDraftProjection`",
                    "",
                    "## Envelope fields",
                    "",
                    *envelope_lines,
                    "",
                    "## Route modes",
                    "",
                    *route_lines,
                    "",
                ]
            ),
        )

        decision_header = ("Decision", "FrozenCode", "FrozenValue")
        decisions = [
            (decision, frozen_code, frozen_value)
            for decision, (frozen_code, frozen_value) in
            verify_baseline_module.R1_SCAFFOLD_DECISIONS.items()
        ]
        decision_lines = [
            markdown_row(*decision_header),
            markdown_row(*(["---"] * len(decision_header))),
            *(markdown_row(*row) for row in decisions),
        ]
        self.write(
            root,
            "docs/adr/ADR-0004-r1-scaffold-and-http-contract.md",
            "\n".join(
                [
                    "# ADR-0004 R1 scaffold and HTTP contract",
                    "",
                    "Status: Accepted",
                    "",
                    "Contract ID: R1-SCAFFOLD-V1",
                    "",
                    "## Controlled decisions",
                    "",
                    *decision_lines,
                    "",
                ]
            ),
        )

    def create_valid_repository(self, root: Path) -> None:
        self.write(
            root,
            "README.md",
            "\n".join(
                [
                    "# Repo",
                    "",
                    "最新权威交付物：",
                    "- [当前MVP基线](docs/baseline/CURRENT-MVP-BASELINE.md)",
                    "",
                ]
            ),
        )
        self.write(
            root,
            "docs/baseline/CURRENT-MVP-BASELINE.md",
            "\n".join(
                [
                    "# Current MVP Baseline",
                    "",
                    f"Baseline ID: {CANONICAL_BASELINE_ID}",
                    "",
                    "状态：`FROZEN`",
                    "",
                    "## task-waiting-contract",
                    "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                    "",
                    CANONICAL_MATTER_SECTION,
                    "",
                ]
            ),
        )
        self.write(
            root,
            "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
            "# 收口设计\n\n状态：已确认（`FROZEN`）\n",
        )
        self.write(root, "database/schema-contract-52-plus-2/contract/schema_contract.py", "CONTRACT = True\n")
        self.write(root, "database/schema-contract-52-plus-2/tests/test_schema_contract.py", "# contract test\n")
        self.write(root, "database/schema-contract-52-plus-2/generated/db/migration/V840__schema_contract_validation.sql", "-- migration\n")
        self.write(root, "database/schema-contract-52-plus-2/tests/test_generated_sql.py", "# generated SQL test\n")
        self.write(root, "docs/superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md", "# PR2 plan\n")
        self.write(
            root,
            "docs/superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md",
            "# R1 plan\n\nStatus: FROZEN\n",
        )
        self.write_r1_contract_fixture(root)
        self.write(root, "docs/superpowers/plans/2026-08-28-postgresql-runtime-verification-plan.md", "# Runtime plan\n")
        self.write(root, "contracts/openapi/ontology-law-api.yaml", "openapi: 3.1.0\n")
        self.write(root, "contracts/openapi/tests/test_ontology_law_api.py", "# OpenAPI test\n")
        self.write(root, "backend/src/main.py", "# backend source\n")
        self.write(root, "backend/tests/test_main.py", "# backend test\n")
        self.write(root, "backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java", "// OpenAPI contract test\n")
        self.write(root, "backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java", "// R1 API integration test\n")
        self.write(root, "spa/src/main.ts", "// SPA source\n")
        self.write(root, "spa/tests/main.test.ts", "// SPA test\n")
        self.write(root, "apps/workbench/src/features/workcard/CurrentCard.test.tsx", "// CurrentCard test\n")
        self.write(root, "database/schema-contract-52-plus-2/runtime/verify_runtime.py", "# runtime verifier\n")
        self.write(root, "e2e/tests/r1-golden-path.spec.ts", "// golden test\n")
        self.write(root, "e2e/tests/r1-failure-paths.spec.ts", "// failure test\n")
        self.write(
            root,
            "docs/evidence/ledger/db-runtime.md",
            "ID: DB-52P2-PG18-RUNTIME\n"
            "Version: pg18-52-plus-2-v1\n"
            "Command: python3 runtime/verify_runtime.py verify --ci-only --runs 2 "
            "--evidence-dir ../../.artifacts/schema-runtime\n"
            "Exit code: 0\n",
        )
        self.write(root, "docs/evidence/ledger/r1-openapi.md", "ID: R1-OPENAPI\nVersion: r1\nArtifact: [OpenAPI](../../../contracts/openapi/ontology-law-api.yaml)\nTest: [OpenAPI contract test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java)\n")
        self.write(root, "docs/evidence/ledger/r1-backend.md", "ID: R1-BACKEND\nVersion: r1\nArtifact: [backend](../../../backend/src/main.py)\nTest: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\n")
        self.write(root, "docs/evidence/ledger/r1-spa.md", "ID: R1-SPA\nVersion: r1\nArtifact: [SPA](../../../spa/src/main.ts)\nTest: [CurrentCard test](../../../apps/workbench/src/features/workcard/CurrentCard.test.tsx)\n")
        self.write(root, "docs/evidence/ledger/r1-e2e-golden.md", "ID: R1-E2E-GOLDEN\nVersion: r1\nCommand: npx playwright test e2e/tests/r1-golden-path.spec.ts\nExit code: 0\n")
        self.write(root, "docs/evidence/ledger/r1-e2e-failures.md", "ID: R1-E2E-FAILURES\nVersion: r1\nCommand: npx playwright test e2e/tests/r1-failure-paths.spec.ts\nExit code: 0\n")

        ledger_rows = [
            markdown_row(
                "DB-52P2-CONTRACT", "MVP", "52+2 contract", "Database",
                "[Python contract](../../database/schema-contract-52-plus-2/contract/schema_contract.py)",
                "Database", "52-plus-2-v1", "R2 entry", "MERGED",
                "[contract source](../../database/schema-contract-52-plus-2/contract/schema_contract.py); `merge-commit=abcdef0`",
                "none", "—",
            ),
            markdown_row(
                "DB-52P2-MIGRATIONS", "MVP", "19 migrations", "Database",
                "[V840](../../database/schema-contract-52-plus-2/generated/db/migration/V840__schema_contract_validation.sql)",
                "Database", "52-plus-2-v1", "R2 entry", "MERGED",
                "[V840](../../database/schema-contract-52-plus-2/generated/db/migration/V840__schema_contract_validation.sql); `merge-commit=abcdef0`",
                "none", "—",
            ),
            markdown_row("BASE-CLOSURE-DESIGN", "PR2", "Closure design", "Docs", "[Closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)", "Product", HISTORICAL_BASELINE_ID, "PR2 merge", "MERGED", "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); `merge-commit=abcdef0`", "none", "—"),
            markdown_row("BASE-PR2-CLOSURE-PLAN", "PR2", "Closure plan", "Plan", "[PR2 plan](../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md)", "Product", "2026-08-28", "PR2 merge", "MERGED", "[plan](../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md); `merge-commit=abcdef0`", "none", "—"),
            markdown_row("BASE-CURRENT-MVP", "MVP", "Canonical baseline", "Docs", "[Current baseline](../baseline/CURRENT-MVP-BASELINE.md)", "Product", HISTORICAL_BASELINE_ID, "PR2 merge", "MERGED", "[closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); `merge-commit=abcdef0`", "none", "BASE-CURRENT-MVP-2026-09-05"),
            markdown_row("BASE-CURRENT-MVP-2026-09-05", "MVP", "Canonical baseline", "Docs", "[Current baseline](../baseline/CURRENT-MVP-BASELINE.md)", "Product", CANONICAL_BASELINE_ID, "PR2 merge", "MERGED", "[current baseline](../baseline/CURRENT-MVP-BASELINE.md); `merge-commit=abcdef0`", "none", "—"),
            markdown_row("R1-IMPLEMENTATION-PLAN", "R1", "Lead-contact plan", "Plan", "[R1 plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)", "Engineering", "2026-08-28", "R1 implementation", "FROZEN", "[plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)", "Production code is not yet implemented", "—"),
            markdown_row("R1-IMPLEMENTATION-CONTRACT", "R1", "R1 scaffold, HTTP, task, and workbench contract", "Docs", "[ADR-0004](../adr/ADR-0004-r1-scaffold-and-http-contract.md)", "Engineering", "r1-contract-v1", "R1 implementation", "FROZEN", "[ADR-0004](../adr/ADR-0004-r1-scaffold-and-http-contract.md); [task matrix](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md); [HTTP matrix](../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md); [workbench contract](../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)", "Production scaffold is not yet implemented", "—"),
            markdown_row("DB-52P2-PG18-RUNTIME-PLAN", "MVP", "PostgreSQL runtime plan", "Plan", "[runtime plan](../superpowers/plans/2026-08-28-postgresql-runtime-verification-plan.md)", "Database", "2026-08-28", "R1 implementation", "DRAFT", "[plan](../superpowers/plans/2026-08-28-postgresql-runtime-verification-plan.md)", "Plan is not runtime evidence", "—"),
            markdown_row("DB-52P2-PG18-RUNTIME", "MVP", "PostgreSQL 18 runtime verification", "Runtime", "[runtime verifier](../../database/schema-contract-52-plus-2/runtime/verify_runtime.py)", "Database", "pg18-52-plus-2-v1", "R2 entry", "RUNTIME_VERIFIED", "[runtime record](../evidence/ledger/db-runtime.md)", "none", "—"),
            markdown_row("R1-OPENAPI", "R1", "OpenAPI", "API", "[OpenAPI source](../../contracts/openapi/ontology-law-api.yaml)", "Engineering", "r1", "R2 entry", "IMPLEMENTED", "[structured record](../evidence/ledger/r1-openapi.md)", "none", "—"),
            markdown_row("R1-BACKEND", "R1", "Backend", "Backend", "[backend source](../../backend/src/main.py)", "Engineering", "r1", "R2 entry", "IMPLEMENTED", "[structured record](../evidence/ledger/r1-backend.md)", "none", "—"),
            markdown_row("R1-SPA", "R1", "SPA", "Frontend", "[SPA source](../../spa/src/main.ts)", "Engineering", "r1", "R2 entry", "IMPLEMENTED", "[structured record](../evidence/ledger/r1-spa.md)", "none", "—"),
            markdown_row("R1-E2E-GOLDEN", "R1", "Golden path", "E2E", "[runtime record](../evidence/ledger/r1-e2e-golden.md)", "Engineering", "r1", "R2 entry", "RUNTIME_VERIFIED", "[runtime record](../evidence/ledger/r1-e2e-golden.md)", "none", "—"),
            markdown_row("R1-E2E-FAILURES", "R1", "Failure paths", "E2E", "[runtime record](../evidence/ledger/r1-e2e-failures.md)", "Engineering", "r1", "R2 entry", "RUNTIME_VERIFIED", "[runtime record](../evidence/ledger/r1-e2e-failures.md)", "none", "—"),
        ]
        for row_id, asset in visual_assets():
            relative_asset = "../" + asset.removeprefix("docs/")
            index = "../design/sales-mvp-workcards/README.md" if "SALES" in row_id else "../design/identity-admin-mvp/README.md"
            ledger_rows.append(
                markdown_row(
                    row_id, "PR2", "Visual acceptance", "Design", f"[PNG]({relative_asset})",
                    "Product Design", "visual-bundle-2026-08-27", "PR2 merge", "MERGED",
                    f"[visual index confirmed 2026-08-27]({index}); [PNG]({relative_asset}); `merge-commit=abcdef0`",
                    "none", "—",
                )
            )
        self.write(
            root,
            "docs/progress/MVP-DELIVERY-LEDGER.md",
            "\n".join([
                "# MVP Delivery Ledger", "", markdown_row(*LEDGER_HEADERS),
                markdown_row(*(["---"] * len(LEDGER_HEADERS))), *ledger_rows, "",
            ]),
        )

        for spec_name in HISTORICAL_SPECS:
            conflict_sections = []
            for heading, replacement in CONFLICT_SECTIONS.get(spec_name, {}).items():
                conflict_sections.extend(
                    [
                        heading,
                        "superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md",
                        f"replacement-section: {replacement}",
                        "",
                    ]
                )
            self.write(
                root,
                f"docs/specs/{spec_name}",
                "\n".join(
                    [
                        "# Historical Spec",
                        "",
                        HISTORICAL_WARNING,
                        "",
                        *conflict_sections,
                        HISTORICAL_REPLACEMENT_HEADING,
                        "",
                        "本节仅记录历史修订。",
                        "",
                    ]
                ),
            )

        sales_links = [
            f"- [PNG]({path.removeprefix('docs/design/sales-mvp-workcards/')})"
            for _, path in visual_assets()
            if "sales-mvp-workcards" in path
        ]
        identity_links = [
            f"- [PNG]({path.removeprefix('docs/design/identity-admin-mvp/')})"
            for _, path in visual_assets()
            if "identity-admin-mvp" in path
        ]
        visual_metadata = [
            "> 状态：FROZEN",
            "> Bundle版本：visual-bundle-2026-08-27",
            "> Owner：Product Design",
            "> 确认日期：2026-08-27",
        ]
        self.write(
            root,
            "docs/design/sales-mvp-workcards/README.md",
            "\n".join(["# Sales visual index", "", *visual_metadata, "", *sales_links, ""]),
        )
        self.write(
            root,
            "docs/design/identity-admin-mvp/README.md",
            "\n".join(["# Identity visual index", "", *visual_metadata, "", *identity_links, ""]),
        )
        for _, asset in visual_assets():
            self.write_bytes(root, asset, b"png")
        for command in (
            ["git", "init", "-q"],
            ["git", "config", "user.email", "ledger@example.test"],
            ["git", "config", "user.name", "Ledger Test"],
            ["git", "add", "."],
            ["git", "commit", "-qm", "fixture snapshot"],
        ):
            subprocess.run(command, cwd=root, check=True)
        merge_commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True
        ).stdout.strip()
        ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
        ledger.write_text(
            ledger.read_text(encoding="utf-8").replace("abcdef0", merge_commit), encoding="utf-8"
        )
        subprocess.run(["git", "add", "docs/progress/MVP-DELIVERY-LEDGER.md"], cwd=root, check=True)
        subprocess.run(["git", "commit", "-qm", "cite fixture snapshot"], cwd=root, check=True)

    def write(self, root: Path, relative_path: str, content: str) -> Path:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_bytes(self, root: Path, relative_path: str, content: bytes) -> Path:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def run_cli(
        self, root: Path, *, strict_r2: bool = False
    ) -> subprocess.CompletedProcess[str]:
        arguments = [sys.executable, str(SCRIPT_PATH)]
        if strict_r2:
            arguments.append("--strict-r2")
        arguments.append(str(root))
        return subprocess.run(
            arguments,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_finding(self, root: Path, expected_finding: str) -> None:
        findings = verify_repository(root)
        self.assertEqual(findings, [expected_finding])

        cli_result = self.run_cli(root)
        self.assertEqual(cli_result.returncode, 1)
        self.assertEqual(cli_result.stdout.strip().splitlines(), [expected_finding])
        self.assertEqual(cli_result.stderr, "")

    def assert_gate_finding(self, root: Path, expected_finding: str) -> None:
        findings = verify_repository(root)
        self.assertEqual(findings, [expected_finding])

        cli_result = self.run_cli(root)
        self.assertEqual(cli_result.returncode, 0)
        self.assertEqual(
            cli_result.stdout.strip().splitlines(),
            [
                f"R2 readiness blocker (non-fatal): {expected_finding}",
                "baseline consistency: PASS; R2 readiness: BLOCKED "
                "(1 non-fatal blocker)",
            ],
        )
        self.assertEqual(cli_result.stderr, "")

        strict_result = self.run_cli(root, strict_r2=True)
        self.assertEqual(strict_result.returncode, 1)
        self.assertEqual(
            strict_result.stdout.strip().splitlines(),
            [
                f"R2 readiness blocker (strict): {expected_finding}",
                "baseline consistency: FAIL; R2 readiness: BLOCKED (1 blocker)",
            ],
        )
        self.assertEqual(strict_result.stderr, "")

    def fixture_merge_commit(self, root: Path) -> str:
        return subprocess.run(
            ["git", "rev-parse", "HEAD~1"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

    def replace_ledger_cell(
        self, root: Path, row_id: str, column: str, value: str
    ) -> None:
        ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
        lines = ledger.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if not line.startswith(f"| {row_id} |"):
                continue
            cells = line.removeprefix("| ").removesuffix(" |").split(" | ")
            self.assertEqual(len(cells), len(LEDGER_HEADERS))
            cells[LEDGER_HEADERS.index(column)] = value
            lines[index] = markdown_row(*cells)
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return
        self.fail(f"Missing ledger fixture row: {row_id}")

    def replace_contract_table_cell(
        self,
        root: Path,
        relative_path: str,
        row_id: str,
        column: str,
        value: str,
    ) -> None:
        contract = root / relative_path
        lines = contract.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if not line.startswith(f"| {row_id} |"):
                continue
            cells = line.removeprefix("| ").removesuffix(" |").split(" | ")
            table_header = next(
                candidate.removeprefix("| ").removesuffix(" |").split(" | ")
                for candidate in reversed(lines[:index])
                if candidate.startswith("| ") and column in candidate
            )
            self.assertEqual(len(cells), len(table_header))
            cells[table_header.index(column)] = value
            lines[index] = markdown_row(*cells)
            contract.write_text("\n".join(lines) + "\n", encoding="utf-8")
            return
        self.fail(f"Missing contract fixture row: {row_id}")

    def assert_merged_evidence_cell_is_rejected(self, root: Path) -> None:
        findings = verify_repository(root)
        self.assertEqual(len(findings), 1)
        self.assertTrue(
            findings[0].startswith("Delivery ledger row BASE-CLOSURE-DESIGN "),
            findings,
        )

        cli_result = self.run_cli(root)
        self.assertEqual(cli_result.returncode, 1)
        self.assertEqual(cli_result.stdout.strip().splitlines(), findings)
        self.assertEqual(cli_result.stderr, "")

    def assert_invalid_utf8_is_structural(
        self, root: Path, relative_path: str
    ) -> None:
        self.write_bytes(root, relative_path, b"\xff\xfeinvalid utf-8")
        expected = f"Invalid UTF-8 in governed file: {relative_path}"

        self.assertEqual(verify_repository(root), [expected])
        cli_result = self.run_cli(root)
        self.assertEqual(cli_result.returncode, 1)
        self.assertEqual(cli_result.stdout, expected + "\n")
        self.assertEqual(cli_result.stderr, "")

    def test_missing_canonical_baseline_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            (root / "docs/baseline/CURRENT-MVP-BASELINE.md").unlink()

            expected = [
                "Missing canonical baseline: docs/baseline/CURRENT-MVP-BASELINE.md",
                "Delivery ledger row BASE-CURRENT-MVP Artifact must contain safe Git-tracked in-repository regular-file links",
            ]
            self.assertEqual(verify_repository(root), expected)

            cli_result = self.run_cli(root)
            self.assertEqual(cli_result.returncode, 1)
            self.assertEqual(cli_result.stdout.strip().splitlines(), expected)
            self.assertEqual(cli_result.stderr, "")

    def test_canonical_baseline_requires_the_patch_version_id(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    f"Baseline ID: {CANONICAL_BASELINE_ID}",
                    "Baseline ID: MVP-2026-08-28",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Current baseline must declare Baseline ID: "
                f"{CANONICAL_BASELINE_ID}",
            )

    def test_runtime_baseline_version_accepts_only_the_approved_successor(self) -> None:
        for version, valid in [("MVP-2026-09-05.1", True), ("MVP-2026-08-28.1", False), ("MVP-2026-09-05.2", False), ("MVP-2026-09-05.10", False)]:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
                baseline.parent.mkdir(parents=True)
                baseline.write_text(f"Baseline ID: {version}\n", encoding="utf-8")
                findings = []
                verify_baseline_module.verify_canonical_baseline(root, findings)
                self.assertEqual(not findings, valid)

    def test_current_baseline_cannot_bypass_the_required_successor_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(ledger.read_text(encoding="utf-8").replace(
                "| none | BASE-CURRENT-MVP-2026-09-05 |", "| none | — |"), encoding="utf-8")
            self.assert_finding(root, "Delivery ledger BASE-CURRENT-MVP must point to BASE-CURRENT-MVP-2026-09-05")

    def test_unapproved_closure_spec_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
                "# 收口设计\n\n状态：待复核\n",
            )

            self.assert_finding(
                root,
                "Closure spec must be approved and frozen: docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
            )

    def test_readme_must_link_canonical_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(root, "README.md", "# Repo\n\n没有基线链接。\n")

            self.assert_finding(
                root,
                "README must link docs/baseline/CURRENT-MVP-BASELINE.md exactly once",
            )

    def test_readme_first_link_must_be_the_canonical_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            readme = root / "README.md"
            readme.write_text(
                readme.read_text(encoding="utf-8").replace(
                    "最新权威交付物：",
                    "最新权威交付物：\n- [旧规格](docs/specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md)",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "README first Markdown link must point to docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_every_historical_spec_has_superseded_banner(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                f"docs/specs/{HISTORICAL_SPECS[0]}",
                "# Historical Spec\n\n没有历史横幅。\n",
            )

            self.assert_finding(
                root,
                f"Historical spec missing superseded banner: docs/specs/{HISTORICAL_SPECS[0]}",
            )

    def test_historical_banner_must_be_in_preamble(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                f"docs/specs/{HISTORICAL_SPECS[0]}",
                "\n".join(
                    [
                        "# Historical Spec",
                        "",
                        "前言还没有声明历史状态。",
                        "",
                        "## Late section",
                        f"> {HISTORICAL_BANNER}。与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，以当前基线优先。",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                f"Historical spec missing superseded banner: docs/specs/{HISTORICAL_SPECS[0]}",
            )

    def test_historical_banner_under_h3_is_not_preamble(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                f"docs/specs/{HISTORICAL_SPECS[1]}",
                "\n".join(
                    [
                        "# Historical Spec",
                        "",
                        "前言还没有声明历史状态。",
                        "",
                        "### Late subsection",
                        f"> {HISTORICAL_BANNER}。与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，以当前基线优先。",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                f"Historical spec missing superseded banner: docs/specs/{HISTORICAL_SPECS[1]}",
            )

    def test_historical_warning_block_must_remain_exact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[0]
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    "当前基线及52＋2合同优先", "旧规格优先"
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec must preserve the exact superseded warning block: {spec_path.as_posix()}",
            )

    def test_historical_warning_requires_admonition_sentinel(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[0]
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    EXACT_HISTORICAL_WARNING,
                    EXACT_HISTORICAL_WARNING.removeprefix("> [!WARNING]\n"),
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec must preserve the exact superseded warning block: {spec_path.as_posix()}",
            )

    def test_historical_warning_rejects_the_legacy_one_line_variant(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[1]
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    EXACT_HISTORICAL_WARNING, LEGACY_ONE_LINE_WARNING
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec must preserve the exact superseded warning block: {spec_path.as_posix()}",
            )

    def test_historical_warning_rejects_extra_authority_particle(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[2]
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    EXACT_HISTORICAL_WARNING,
                    EXACT_HISTORICAL_WARNING.replace(
                        "冲突时，当前基线", "冲突时，以当前基线"
                    ),
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec must preserve the exact superseded warning block: {spec_path.as_posix()}",
            )

    def test_historical_warning_accepts_only_the_exact_two_line_preamble_block(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            self.assertEqual(verify_repository(root), [])

    def test_historical_warning_inside_tilde_fence_is_not_governance(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[0]
            self.write(
                root,
                spec_path.as_posix(),
                "\n".join(
                    [
                        "# Historical Spec",
                        "",
                        "~~~~markdown",
                        EXACT_HISTORICAL_WARNING,
                        "~~~",
                        "the shorter run does not close the four-tilde fence",
                        "~~~~",
                        "",
                        HISTORICAL_REPLACEMENT_HEADING,
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                f"Historical spec missing superseded banner: {spec_path.as_posix()}",
            )

    def test_conflict_heading_requires_exact_supersession_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_name = "2026-08-18-law-firm-overall-architecture-ontology-design.md"
            spec_path = Path("docs/specs") / spec_name
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    "### 3.2 MVP终点\nsuperseded-by: docs/baseline/CURRENT-MVP-BASELINE.md\nreplacement-section: matter-endpoint",
                    "### 3.2 MVP终点\nreplacement-section: matter-endpoint",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec conflict heading lacks exact supersession metadata: {spec_path.as_posix()} :: ### 3.2 MVP终点",
            )

    def test_conflict_heading_rejects_the_wrong_replacement_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_name = "2026-08-18-ontology-law-system-foundation-architecture-v1.0.md"
            spec_path = Path("docs/specs") / spec_name
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    "### 12.4 WAITING与WaitReceipt\nsuperseded-by: docs/baseline/CURRENT-MVP-BASELINE.md\nreplacement-section: task-waiting-contract",
                    "### 12.4 WAITING与WaitReceipt\nsuperseded-by: docs/baseline/CURRENT-MVP-BASELINE.md\nreplacement-section: application-topology",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec conflict heading lacks exact supersession metadata: {spec_path.as_posix()} :: ### 12.4 WAITING与WaitReceipt",
            )

    def test_historical_specs_require_the_replacement_appendix_heading(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            spec_path = Path("docs/specs") / HISTORICAL_SPECS[1]
            spec = root / spec_path
            spec.write_text(
                spec.read_text(encoding="utf-8").replace(
                    HISTORICAL_REPLACEMENT_HEADING,
                    "## 2026-08-27 P0一致性补充（v1.2）",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Historical spec must use the replacement appendix heading: {spec_path.as_posix()}",
            )

    def test_visual_indexes_require_exact_frozen_bundle_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            index_path = Path("docs/design/sales-mvp-workcards/README.md")
            index = root / index_path
            index.write_text(
                index.read_text(encoding="utf-8").replace(
                    "> 状态：FROZEN", "> 状态：IMPLEMENTED"
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Visual index must declare exact frozen bundle metadata: {index_path.as_posix()}",
            )

    def test_visual_indexes_must_list_the_exact_frozen_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            index_path = Path("docs/design/identity-admin-mvp/README.md")
            index = root / index_path
            index.write_text(
                index.read_text(encoding="utf-8").replace(
                    "- [PNG](frozen/ADM-07-audit-records.png)\n", ""
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Visual index must list exactly its frozen PNG assets: {index_path.as_posix()}",
            )

    def test_visual_indexes_reject_duplicate_frozen_asset_entries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            index_path = Path("docs/design/identity-admin-mvp/README.md")
            index = root / index_path
            index.write_text(
                index.read_text(encoding="utf-8")
                + "- [duplicate](frozen/ADM-07-audit-records.png)\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                f"Visual index must list exactly its frozen PNG assets: {index_path.as_posix()}",
            )

    def test_waiting_entry_conditions_are_frozen(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 允许进入等待。",
                        "",
                        CANONICAL_MATTER_SECTION,
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze WAITING entry with SYSTEM_RECOVERY: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_waiting_contract_must_include_system_recovery_in_its_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "SYSTEM_RECOVERY 这个术语在前言中被提到，但不属于等待规则。",
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在安全暂停时进入。",
                        "",
                        CANONICAL_MATTER_SECTION,
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze WAITING entry with SYSTEM_RECOVERY: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_waiting_glossary_heading_does_not_satisfy_waiting_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "## waiting-glossary",
                        "SYSTEM_RECOVERY 只是术语解释，不是冻结的等待契约。",
                        "",
                        CANONICAL_MATTER_SECTION,
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze WAITING entry with SYSTEM_RECOVERY: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_endpoint_is_frozen(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-endpoint",
                        "ACCEPT 只写入 MatterRef。",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction MatterCreated, no second Matter identity, and no reverse rewrite of sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_endpoint_accepts_canonical_same_local_transaction_wording(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            self.assertNotIn(
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
                verify_repository(root),
            )

    def test_matter_endpoint_accepts_insignificant_whitespace_and_line_endings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_bytes(
                baseline.read_text(encoding="utf-8")
                .replace(
                    CANONICAL_MATTER_SECTION,
                    CANONICAL_MATTER_SECTION.replace("\n", "  \r\n\t"),
                )
                .encode("utf-8")
            )

            self.assertNotIn(MATTER_ENDPOINT_FINDING, verify_repository(root))

    def test_matter_endpoint_rejects_reversed_frozen_clauses(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            reversed_clauses = (
                CANONICAL_MATTER_SECTION
                .replace(CANONICAL_MATTER_PUBLICATION_CLAUSE, "__PUBLICATION__")
                .replace(
                    CANONICAL_MATTER_PROHIBITION_CLAUSE,
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                )
                .replace("__PUBLICATION__", CANONICAL_MATTER_PROHIBITION_CLAUSE)
            )
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_SECTION,
                    reversed_clauses,
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_publication_clause_followed_by_contradiction(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                    CANONICAL_MATTER_PUBLICATION_CLAUSE + " 但MatterCreated不在同一事务发布。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_prohibition_clause_followed_by_contradiction(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PROHIBITION_CLAUSE,
                    CANONICAL_MATTER_PROHIBITION_CLAUSE
                    + "但Post-MVP允许生成第二Matter身份并反向改写销售历史。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_both_clauses_plus_contradictory_text(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            contradictory_section = (
                CANONICAL_MATTER_SECTION
                + "\n\n例外：MatterCreated可另行发布，且可重建Matter身份和销售历史。"
            )
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_SECTION,
                    contradictory_section,
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_extra_surrounding_paragraph(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    "销售MVP终点固定为：",
                    "销售MVP终点固定为：\n\nMatter范围另见未冻结说明。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_missing_surrounding_paragraph(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    "销售MVP终点固定为：\n\n",
                    "",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_endpoint_rejects_reordered_code_block_line(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    "+ TransferAccepted\n+ TransferRequest的一次写入MatterRef",
                    "+ TransferRequest的一次写入MatterRef\n+ TransferAccepted",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_created_requires_same_local_transaction_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline_path = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline_path.write_text(
                baseline_path.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                    "同一事务必须写入完整MatterRef槽：稳定`matter_id`、"
                    "`matter_no`、类型、能力包版本和可信创建时间，并发布"
                    "`MatterCreated`事实通知供Post-MVP消费者使用。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_endpoint_keywords_must_be_in_matter_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "MatterCreated 与第二Matter身份在这里被顺带提到，但不属于 Matter 终点章节。",
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-endpoint",
                        "ACCEPT 只写入 MatterRef。",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction MatterCreated, no second Matter identity, and no reverse rewrite of sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_glossary_heading_does_not_satisfy_matter_endpoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-glossary",
                        "MatterCreated 与第二Matter身份只是词汇解释，不是冻结终点。",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction MatterCreated, no second Matter identity, and no reverse rewrite of sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_created_same_transaction_phrase_must_be_in_matter_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        CANONICAL_MATTER_PUBLICATION_CLAUSE,
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-endpoint",
                        CANONICAL_MATTER_PROHIBITION_CLAUSE,
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_created_negated_same_transaction_publication_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                    "MatterCreated 不在同一事务发布。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_identity_and_sales_history_permissions_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PROHIBITION_CLAUSE,
                    "Post-MVP 允许生成第二Matter身份，也允许反向改写销售历史。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_matter_publication_clause_rejects_negated_local_transaction_requirement(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                    "不要求同一本地事务写入 MatterRef，并发布 MatterCreated。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_publication_clause_rejects_separate_matter_created_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PUBLICATION_CLAUSE,
                    "同一本地事务先发布 AuditRecorded；MatterCreated 另行记录。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_prohibition_clause_rejects_not_prohibited_wording(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PROHIBITION_CLAUSE,
                    "Post-MVP 不禁止生成第二Matter身份，也不禁止反向改写销售历史。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_matter_prohibition_clause_rejects_wrong_prohibited_actions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            baseline = root / "docs/baseline/CURRENT-MVP-BASELINE.md"
            baseline.write_text(
                baseline.read_text(encoding="utf-8").replace(
                    CANONICAL_MATTER_PROHIBITION_CLAUSE,
                    "Post-MVP 不得删除第二Matter身份，也不得撤销反向改写销售历史。",
                ),
                encoding="utf-8",
            )

            self.assert_finding(root, MATTER_ENDPOINT_FINDING)

    def test_second_matter_identity_prohibition_must_be_in_matter_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        CANONICAL_MATTER_PROHIBITION_CLAUSE,
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-endpoint",
                        CANONICAL_MATTER_PUBLICATION_CLAUSE,
                        "Post-MVP不得反向改写销售历史；",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_sales_history_prohibition_must_be_in_matter_section(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/baseline/CURRENT-MVP-BASELINE.md",
                "\n".join(
                    [
                        "# Current MVP Baseline",
                        "",
                        f"Baseline ID: {CANONICAL_BASELINE_ID}",
                        "",
                        "状态：`FROZEN`",
                        "",
                        CANONICAL_MATTER_PROHIBITION_CLAUSE,
                        "",
                        "## task-waiting-contract",
                        "OPEN → WAITING 只允许在 SYSTEM_RECOVERY 安全暂停时进入。",
                        "",
                        "## matter-endpoint",
                        CANONICAL_MATTER_PUBLICATION_CLAUSE,
                        "Post-MVP不得生成第二Matter身份；",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Current baseline must freeze Matter endpoint with same-transaction "
                "MatterCreated, no second Matter identity, and no reverse rewrite of "
                "sales history: docs/baseline/CURRENT-MVP-BASELINE.md",
            )

    def test_r1_task_registry_rejects_a_missing_required_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
            lines = contract.read_text(encoding="utf-8").splitlines()
            contract.write_text(
                "\n".join(
                    line for line in lines
                    if not line.startswith("| CONTACT_LEAD |")
                )
                + "\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 task registry missing TaskType: CONTACT_LEAD",
            )

    def test_r1_task_registry_rejects_a_duplicate_primary_command(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "COMPLETE_LEAD_INGRESS",
                "PrimaryCommand",
                "RESOLVE_DUPLICATE_LEAD",
            )

            self.assert_finding(
                root,
                "R1 task registry duplicate PrimaryCommand: RESOLVE_DUPLICATE_LEAD",
            )

    def test_r1_completion_branch_rejects_an_unknown_receipt_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "CONTACT_CONNECTED_VALID",
                "ReceiptResult",
                "FAILED",
            )

            self.assert_finding(
                root,
                "R1 completion branch CONTACT_CONNECTED_VALID uses unknown ReceiptResult: FAILED",
            )

    def test_r1_http_operation_rejects_an_unknown_error_code(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
                "recordContactResult",
                "ErrorCodes",
                "VALIDATION_FAILED,UNKNOWN_ERROR",
            )

            self.assert_finding(
                root,
                "R1 HTTP operation recordContactResult references unknown ErrorCode: UNKNOWN_ERROR",
            )

    def test_r1_task_registry_rejects_an_unbound_completion_fact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "CONTACT_LEAD",
                "CompletionFactType",
                "—",
            )

            self.assert_finding(
                root,
                "R1 task registry TaskType CONTACT_LEAD has no bound CompletionFactType",
            )

    def test_r1_e2e_allows_distinct_scenarios_for_one_completion_branch(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
            lines = contract.read_text(encoding="utf-8").splitlines()
            retry_scenarios = [
                line
                for line in lines
                if "| P0_04_RETRY_ASSIGNMENT_NOW |" in line
                and line.startswith("| E2E_")
            ]

            self.assertEqual(len(retry_scenarios), 2)
            self.assertEqual(verify_repository(root), [])

    def test_r1_completion_branch_rejects_changed_successor_policy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "P0_02_COMPLETE",
                "SuccessorPolicy",
                "NONE",
            )

            self.assert_finding(
                root,
                "R1 completion branch P0_02_COMPLETE differs from its frozen successor contract",
            )

    def test_r1_idempotency_binding_rejects_opaque_value(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
                "ValueType",
                "FrozenValue",
                "OPAQUE",
            )

            self.assert_finding(
                root,
                "R1 HTTP idempotency binding differs from the frozen command slot contract",
            )

    def test_r1_http_operation_rejects_changed_subject_binding(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
                "captureLead",
                "SubjectBinding",
                "NONE",
            )

            self.assert_finding(
                root,
                "R1 HTTP operation captureLead differs from its frozen contract",
            )

    def test_r1_plan_status_cannot_be_spoofed_from_a_code_fence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            plan = root / "docs/superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md"
            plan.write_text(
                "```text\nStatus: FROZEN\n```\n\nStatus: DRAFT\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 implementation plan must declare Status: FROZEN",
            )

    def test_r1_executable_contract_marker_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md"
            contract.write_text(
                contract.read_text(encoding="utf-8").replace(
                    "## ActionDraft confirmation lifecycle", "## Draft lifecycle"
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 executable contract marker missing from docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md: ## ActionDraft confirmation lifecycle",
            )

    def test_r1_plan_status_cannot_be_spoofed_from_a_list_nested_code_fence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            plan = root / "docs/superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md"
            plan.write_text(
                "# R1 plan\n\n- ```text\n  Status: FROZEN\n  ```\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 implementation plan must declare Status: FROZEN",
            )

    def test_r1_scaffold_decision_rejects_changed_frozen_code(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/adr/ADR-0004-r1-scaffold-and-http-contract.md",
                "backendProject",
                "FrozenCode",
                "MULTI_MODULE_BACKEND",
            )

            self.assert_finding(
                root,
                "ADR-0004 decision differs from frozen code: backendProject",
            )

    def test_r1_completion_branch_rejects_changed_event_type(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "P0_03_ASSIGN",
                "EventType",
                "WrongEventV1",
            )

            self.assert_finding(
                root,
                "R1 completion branch P0_03_ASSIGN differs from its frozen branch contract",
            )

    def test_r1_e2e_rejects_changed_fact_delta(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "E2E_P0_03",
                "FactDelta",
                "assignment:+0",
            )

            self.assert_finding(
                root,
                "R1 E2E scenario differs from frozen delta contract: E2E_P0_03",
            )

    def test_r1_candidate_payload_requires_legal_need(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "RECORD_CONTACT_RESULT",
                "ExactCandidateFields",
                "leadAssignmentId,leadAssignmentRevision,contactChannelCode,resultCode,resultSummary?,evidenceSubmissionId?",
            )

            self.assert_finding(
                root,
                "R1 candidate payload differs from frozen fields: RECORD_CONTACT_RESULT",
            )

    def test_r1_candidate_payload_freezes_legal_need_branch_condition(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
            contract.write_text(
                contract.read_text(encoding="utf-8").replace(
                    "legalNeed-required-when-CONNECTED_VALID-and-forbidden-otherwise",
                    "legalNeed-optional-for-all-results",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 candidate payload condition differs from frozen contract: RECORD_CONTACT_RESULT",
            )

    def test_r1_duplicate_transition_requires_keep_separate_lead_cas(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
            contract.write_text(
                contract.read_text(encoding="utf-8").replace(
                    "disposition_code=KEEP_SEPARATE;revision=old+1",
                    "NONE",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 duplicate transition differs from frozen contract: P0_01_KEEP_SEPARATE",
            )

    def test_r1_duplicate_transition_keeps_candidate_lead_and_party_immutable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            contract = root / "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md"
            contract.write_text(
                contract.read_text(encoding="utf-8").replace(
                    "| NONE | old-current-lead-selector+candidate-lead-party-selectors+KEEP_SEPARATE+new-revision |",
                    "| candidateLead.revision=old+1 | old-current-lead-selector+candidate-lead-party-selectors+KEEP_SEPARATE+new-revision |",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "R1 duplicate transition differs from frozen contract: P0_01_KEEP_SEPARATE",
            )

    def test_r1_lead_disposition_allowlist_requires_keep_separate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md",
                "Lead disposition used by R1",
                "Allowed values",
                "`CAPTURED`, `LINK_EXISTING_PARTY`",
            )

            self.assert_finding(
                root,
                "R1 Lead disposition allowlist differs from the frozen contract",
            )

    def test_r1_mtls_authentication_must_not_emit_a_bearer_challenge(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md",
                "internalMutualTls",
                "UnauthenticatedTransport",
                "HTTP_401_PROBLEM_WITH_WWW_AUTHENTICATE_BEARER",
            )

            self.assert_finding(
                root,
                "R1 authentication challenge differs from frozen contract: internalMutualTls",
            )

    def test_r1_scaffold_decision_rejects_changed_frozen_value(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_contract_table_cell(
                root,
                "docs/adr/ADR-0004-r1-scaffold-and-http-contract.md",
                "backendProject",
                "FrozenValue",
                "multiple Maven projects",
            )

            self.assert_finding(
                root,
                "ADR-0004 decision differs from frozen value: backendProject",
            )

    def test_ledger_rejects_unknown_delivery_state(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/progress/MVP-DELIVERY-LEDGER.md",
                "\n".join(
                    [
                        "# MVP Delivery Ledger",
                        "",
                        "| ID | Release | Capability | Layer | Artifact | Owner | Version | Target gate | State | Evidence | Blocker/next gate | Superseded by |",
                        "|---|---|---|---|---|---|---|---|---|---|---|---|",
                        "| BL-1 | MVP | Baseline | Docs | [Current baseline](../baseline/CURRENT-MVP-BASELINE.md) | Product | v1 | merge | SHIPPING | [Closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md) | next | — |",
                        "",
                    ]
                ),
            )

            self.assert_finding(
                root,
                "Delivery ledger row BL-1 uses unknown state: SHIPPING",
            )

    def test_ledger_table_inside_backtick_fence_is_not_governance(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            fenced_ledger = "\n".join(
                [
                    "# MVP Delivery Ledger",
                    "",
                    "````markdown",
                    ledger.read_text(encoding="utf-8"),
                    "```",
                    "the shorter run is still fenced",
                    "````",
                    "",
                ]
            )
            ledger.write_text(fenced_ledger, encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger must contain a header and at least one row",
            )

    def test_ledger_baseline_rows_require_the_patch_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Version",
                "MVP-2026-08-28",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN must record Version "
                f"{HISTORICAL_BASELINE_ID}",
            )

    def test_ledger_requires_every_contract_column_and_cell(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(text.replace("Superseded by", "Superseded", 1), encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger missing required columns: Superseded by",
            )

    def test_ledger_rejects_duplicate_ids(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            duplicate = next(line for line in text.splitlines() if "DB-52P2-CONTRACT" in line)
            ledger.write_text(text + duplicate + "\n", encoding="utf-8")

            self.assert_finding(root, "Delivery ledger duplicate ID: DB-52P2-CONTRACT")

    def test_ledger_evidence_must_be_an_existing_relative_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace("[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)", "https://example.test/evidence"),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN Evidence must contain existing in-repository relative Markdown links",
            )

    def test_implemented_row_requires_production_source_and_test_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\nVersion: r1\nArtifact: [backend](../../../backend/src/main.py)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND IMPLEMENTED evidence must link distinct in-repository production source and test",
            )

    def test_runtime_verified_row_requires_version_command_and_exit_code_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/db-runtime.md",
                "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\nExit code: 0\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_postgresql_runtime_row_accepts_only_the_truthful_hosted_command(self) -> None:
        """Break caught: the ledger must not turn the local BLOCKED attempt into a local PASS."""
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.write(
                root,
                "database/schema-contract-52-plus-2/runtime/verify_runtime.py",
                "# verifier\n",
            )
            row = {"ID": "DB-52P2-PG18-RUNTIME"}
            hosted = (
                "python3 runtime/verify_runtime.py verify --ci-only --runs 2 "
                "--evidence-dir ../../.artifacts/schema-runtime"
            )
            local = (
                "python3 runtime/verify_runtime.py verify --runs 2 "
                "--evidence-dir ../../.artifacts/schema-runtime"
            )

            self.assertTrue(
                verify_baseline_module.has_concrete_runtime_command(root, row, hosted)
            )
            self.assertFalse(
                verify_baseline_module.has_concrete_runtime_command(root, row, local)
            )

    def test_postgresql_runtime_report_is_a_controlled_structured_record(self) -> None:
        """Break caught: the required schema-runtime report cannot advance its ledger row."""
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.write(
                root,
                "docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md",
                "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\n",
            )
            subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
            subprocess.run(
                ["git", "-C", str(root), "add", "."],
                check=True,
            )

            records = verify_baseline_module.controlled_evidence_records(
                root,
                ["../evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md"],
            )

            self.assertEqual(
                records,
                [
                    root
                    / "docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md"
                ],
            )

    def test_ledger_enforces_nonvisual_initial_truths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(text.replace("DB-52P2-MIGRATIONS | MVP", "DB-52P2-MIGRATIONS | PR2", 1), encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-MIGRATIONS must record Release MVP",
            )

    def test_ledger_requires_exact_visual_ids_links_and_bundle_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(text.replace("VIS-SALES-BASE-01", "VIS-SALES-BASE-13", 1), encoding="utf-8")

            self.assert_finding(root, "Delivery ledger has unexpected visual row: VIS-SALES-BASE-13")

    def test_frozen_visual_is_not_implemented(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace("| PR2 merge | MERGED | [visual index confirmed", "| PR2 merge | IMPLEMENTED | [visual index confirmed", 1),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 cannot claim IMPLEMENTED; FROZEN visual evidence is not production code",
            )

    def test_missing_r1_production_row_leaves_gate_unmet_and_plan_cannot_substitute(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            ledger.write_text(
                "\n".join(line for line in lines if "R1-OPENAPI" not in line) + "\n",
                encoding="utf-8",
            )

            self.assert_gate_finding(
                root,
                "Gate R2 entry unmet: missing required delivery row R1-OPENAPI; 计划不是生产代码",
            )

    def test_fake_draft_r1_production_row_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            original = next(line for line in text.splitlines() if "R1-OPENAPI" in line)
            fake = markdown_row(
                "R1-OPENAPI", "R1", "OpenAPI", "API",
                "[plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)",
                "Engineering", "r1", "R2 entry", "DRAFT",
                "[plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)",
                "plan", "—",
            )
            ledger.write_text(text.replace(original, fake), encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger row R1-OPENAPI cannot be DRAFT: 计划不是生产代码",
            )

    def test_r2_gate_requires_merged_closure_plan(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "BASE-PR2-CLOSURE-PLAN | PR2 | Closure plan | Plan | [PR2 plan](../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md) | Product | 2026-08-28 | PR2 merge | MERGED",
                    "BASE-PR2-CLOSURE-PLAN | PR2 | Closure plan | Plan | [PR2 plan](../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md) | Product | 2026-08-28 | PR2 merge | FROZEN",
                ),
                encoding="utf-8",
            )

            self.assert_gate_finding(
                root,
                "Gate R2 entry unmet: BASE-PR2-CLOSURE-PLAN is FROZEN, requires MERGED",
            )

    def test_r2_gate_requires_the_separate_postgresql_runtime_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                "\n".join(
                    line
                    for line in ledger.read_text(encoding="utf-8").splitlines()
                    if not line.startswith("| DB-52P2-PG18-RUNTIME |")
                )
                + "\n",
                encoding="utf-8",
            )

            self.assert_gate_finding(
                root,
                "Gate R2 entry unmet: missing required delivery row "
                "DB-52P2-PG18-RUNTIME; a runtime plan is not runtime evidence",
            )

    def test_invalid_utf8_is_structural_for_every_governed_read_path(self) -> None:
        governed_paths = (
            "README.md",
            "docs/baseline/CURRENT-MVP-BASELINE.md",
            "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
            "docs/progress/MVP-DELIVERY-LEDGER.md",
            f"docs/specs/{HISTORICAL_SPECS[0]}",
            "docs/design/sales-mvp-workcards/README.md",
            "docs/evidence/ledger/r1-backend.md",
        )
        for relative_path in governed_paths:
            with self.subTest(relative_path=relative_path), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                self.assert_invalid_utf8_is_structural(root, relative_path)

    def test_general_markdown_links_reject_non_repository_file_targets(self) -> None:
        cases = (
            ("posix absolute", "/etc/hosts", None),
            ("file scheme", "file:///etc/hosts", None),
            (
                "windows backslash absolute",
                r"C:\Windows\System32\drivers\etc\hosts",
                r"C:\Windows\System32\drivers\etc\hosts",
            ),
            (
                "windows slash absolute",
                "C:/Windows/System32/drivers/etc/hosts",
                "C:/Windows/System32/drivers/etc/hosts",
            ),
            ("escaping path", "../outside.md", "../outside.md"),
            ("local scheme", "vscode:README.md", "vscode:README.md"),
            ("directory", "docs/reference", "docs/reference/placeholder.md"),
        )
        for label, target, fixture_path in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp_dir:
                container = Path(temp_dir)
                root = container / "repo"
                root.mkdir()
                self.create_valid_repository(root)
                if fixture_path == "../outside.md":
                    self.write(container, "outside.md", "# Outside\n")
                elif fixture_path is not None:
                    self.write(root, fixture_path, "# Decoy\n")
                readme = root / "README.md"
                readme.write_text(
                    readme.read_text(encoding="utf-8")
                    + f"\n- [unsafe target]({target})\n",
                    encoding="utf-8",
                )

                self.assert_finding(
                    root,
                    f"Broken relative Markdown link in README.md: {target}",
                )

    def test_general_markdown_links_reject_symlink_targets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            alias = root / "docs/baseline/alias.md"
            alias.symlink_to("CURRENT-MVP-BASELINE.md")
            readme = root / "README.md"
            readme.write_text(
                readme.read_text(encoding="utf-8")
                + "\n- [alias](docs/baseline/alias.md)\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Broken relative Markdown link in README.md: docs/baseline/alias.md",
            )

    def test_general_markdown_links_allow_http_https_and_mailto_references(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            readme = root / "README.md"
            readme.write_text(
                readme.read_text(encoding="utf-8")
                + "\n[HTTP](http://example.test) "
                "[HTTPS](https://example.test) "
                "[mail](mailto:owner@example.test)\n",
                encoding="utf-8",
            )

            self.assertEqual(verify_repository(root), [])

    def test_visual_indexes_apply_general_link_containment_to_non_png_links(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            visual_index = root / "docs/design/sales-mvp-workcards/README.md"
            visual_index.write_text(
                visual_index.read_text(encoding="utf-8")
                + "\n- [unsafe local reference](/etc/hosts)\n",
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Broken relative Markdown link in docs/design/sales-mvp-workcards/README.md: /etc/hosts",
            )

    def test_ledger_rejects_absolute_and_outside_evidence_links(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)",
                    "[host](/etc/hosts)",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN Evidence must contain existing in-repository relative Markdown links",
            )

    def test_ledger_rejects_symlink_evidence_that_escapes_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            escaped = root / "docs/evidence/escaped.md"
            escaped.parent.mkdir(parents=True, exist_ok=True)
            escaped.symlink_to("/etc/hosts")
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)",
                    "[escaped](../evidence/escaped.md)",
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN Evidence must contain existing in-repository relative Markdown links",
            )

    def test_implemented_row_rejects_plan_and_unrelated_test_as_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\nVersion: r1\nArtifact: [plan](../../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)\nTest: [unrelated](../../../database/schema-contract-52-plus-2/tests/test_schema_contract.py)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND IMPLEMENTED evidence must link distinct in-repository production source and test",
            )

    def test_implemented_row_rejects_a_test_from_an_unrelated_component(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\nVersion: r1\nArtifact: [backend](../../../backend/src/main.py)\nTest: [unrelated](../../../database/schema-contract-52-plus-2/tests/test_schema_contract.py)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND IMPLEMENTED evidence must link distinct in-repository production source and test",
            )

    def test_runtime_record_rejects_blank_version_and_command_values(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(root, "docs/evidence/ledger/db-runtime.md", "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\nCommand: \nExit code: 0\n")

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_merged_row_requires_concrete_merge_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)",
            )

            self.assert_finding(root, "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact")

    def test_superseded_by_must_reference_an_existing_nonself_row_without_cycles(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace("| none | — |", "| none | NO-SUCH-ROW |", 1),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-CONTRACT Superseded by references missing ID: NO-SUCH-ROW",
            )

    def test_superseded_by_rejects_cycles(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            text = text.replace(
                "| BASE-CLOSURE-DESIGN |", "| BASE-CLOSURE-DESIGN |", 1
            )
            lines = text.splitlines()
            for index, line in enumerate(lines):
                if "BASE-CLOSURE-DESIGN" in line:
                    lines[index] = line.replace("| — |", "| BASE-CURRENT-MVP |")
                if line.startswith("| BASE-CURRENT-MVP |"):
                    lines[index] = line.replace("| BASE-CURRENT-MVP-2026-09-05 |", "| BASE-CLOSURE-DESIGN |")
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger Superseded by cycle: BASE-CLOSURE-DESIGN -> BASE-CURRENT-MVP -> BASE-CLOSURE-DESIGN",
            )

    def test_gate_uses_active_successor_not_superseded_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            original = next(line for line in lines if "R1-OPENAPI" in line)
            lines[lines.index(original)] = original.replace("| — |", "| R1-OPENAPI-V2 |")
            lines.append(
                markdown_row(
                    "R1-OPENAPI-V2", "R1", "OpenAPI", "API",
                    "[plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)",
                    "Engineering", "r2", "R2 entry", "DRAFT",
                    "[plan](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)",
                    "not implemented", "—",
                )
            )
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assert_gate_finding(
                root,
                "Gate R2 entry unmet: R1-OPENAPI active successor R1-OPENAPI-V2 is DRAFT, requires IMPLEMENTED",
            )

    def test_visual_contract_rejects_missing_expected_png_replaced_by_rogue_png(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            (root / "docs/design/sales-mvp-workcards/p0/P0-15-transfer-resubmit.png").unlink()
            self.write_bytes(root, "docs/design/sales-mvp-workcards/p0/rogue.png", b"png")

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-P0-15 Artifact must contain safe Git-tracked in-repository regular-file links",
            )

    def test_visual_contract_rejects_unexpected_visual_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            original = next(line for line in text.splitlines() if "VIS-SALES-BASE-01" in line)
            ledger.write_text(text + original.replace("VIS-SALES-BASE-01", "VIS-SALES-BASE-99") + "\n", encoding="utf-8")

            self.assert_finding(root, "Delivery ledger has unexpected visual row: VIS-SALES-BASE-99")

    def test_visual_contract_requires_resolving_png_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); `merge-commit=",
                    "[visual index duplicate](../design/sales-mvp-workcards/README.md); `merge-commit=",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 Evidence must link its exact PNG",
            )

    def test_visual_contract_rejects_any_unresolvable_png_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); `merge-commit=",
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); [rogue PNG](../design/sales-mvp-workcards/frozen/missing.png); `merge-commit=",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 links a missing or outside PNG evidence asset",
            )

    def test_ledger_rejects_a_data_row_used_as_the_delimiter(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            duplicate = next(line for line in lines if "DB-52P2-CONTRACT" in line)
            delimiter_index = next(index for index, line in enumerate(lines) if line.startswith("| ---"))
            lines[delimiter_index] = duplicate
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assert_finding(root, "Delivery ledger table delimiter row is invalid")

    def test_merged_evidence_rejects_nonexistent_or_negated_commit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            merge_commit = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); "
                f"`merge-commit={merge_commit}; this is not evidence`",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact",
            )

    def test_merged_evidence_requires_an_unambiguous_merge_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            merge_commit = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); "
                f"`merge commit {merge_commit}`",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact",
            )

    def assert_merged_evidence_mutation_is_rejected(
        self, replacement: str
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            merge_commit = self.fixture_merge_commit(root)
            mutated_marker = replacement.format(
                marker=f"`merge-commit={merge_commit}`", sha=merge_commit
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[confirmed closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); "
                f"{mutated_marker}",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact",
            )

    def test_merged_evidence_rejects_straight_apostrophe_contradiction(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} this isn't evidence"
        )

    def test_merged_evidence_rejects_right_curly_apostrophe_contradiction(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} this isn’t evidence"
        )

    def test_merged_evidence_rejects_left_curly_apostrophe_contradiction(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} this isn‘t evidence"
        )

    def test_merged_evidence_rejects_invalid_evidence_suffix(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} this is invalid evidence"
        )

    def test_merged_evidence_rejects_fake_evidence_suffix(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} this is fake evidence"
        )

    def test_merged_evidence_rejects_without_evidence_suffix(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} without evidence"
        )

    def test_merged_evidence_rejects_arbitrary_suffix_prose(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "{marker} trailing prose"
        )

    def test_merged_evidence_rejects_arbitrary_prefix_prose(self) -> None:
        self.assert_merged_evidence_mutation_is_rejected(
            "leading prose; {marker}"
        )

    def test_merged_evidence_accepts_exact_whole_cell_grammar(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            self.assertEqual(verify_repository(root), [])

    def test_merged_evidence_rejects_reviewed_nested_label_prose(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[arbitrary prefix [proof]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_reviewed_space_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/superpowers/specs/space evidence.md",
                "# Literal space evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](../superpowers/specs/space evidence.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_reviewed_raw_parenthesis_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/superpowers/specs/bad(name.md",
                "# Literal raw-parenthesis evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](../superpowers/specs/bad(name.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_reviewed_duplicate_marker_in_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof `merge-commit={sha}`]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_backslash_escaped_marker_in_label(self) -> None:
        hidden_markers = (r"merge\-commit=deadbee", r"merge-commit\=deadbee")
        for hidden_marker in hidden_markers:
            with self.subTest(hidden_marker=hidden_marker), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                sha = self.fixture_merge_commit(root)
                self.replace_ledger_cell(
                    root,
                    "BASE-CLOSURE-DESIGN",
                    "Evidence",
                    f"[proof {hidden_marker}]"
                    "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                    f"; `merge-commit={sha}`",
                )

                self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_accepts_even_backslash_marker_lookalike(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                r"[proof merge\\-commit=deadbee]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assertEqual(verify_repository(root), [])

    def test_merged_evidence_rejects_unescaped_right_bracket_in_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof] unexpected]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_accepts_escaped_right_bracket_in_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof \\] label]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assertEqual(verify_repository(root), [])

    def test_merged_evidence_rejects_empty_link_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_whitespace_only_link_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[   ](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_empty_link_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof](); `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_percent_encoded_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/superpowers/specs/space%20evidence.md",
                "# Literal percent-encoded evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](../superpowers/specs/space%20evidence.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_character_reference_scheme_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/progress/https&colon;/example.test/evidence.md",
                "# Literal encoded-scheme evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](https&colon;//example.test/evidence.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_character_reference_absolute_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/progress/&sol;etc&sol;hosts",
                "# Literal encoded-absolute evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof](&sol;etc&sol;hosts); `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_character_reference_label_syntax(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof &#96;merge-commit&equals;encoded&#96;]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_angle_bracket_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/progress/<evidence.md>",
                "# Literal angle-bracket evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof](<evidence.md>); `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_nested_autolink_in_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[<https://example.test>]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_control_character_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                "docs/superpowers/specs/control\tevidence.md",
                "# Literal control-character evidence fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](../superpowers/specs/control\tevidence.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_unicode_format_control_destination(self) -> None:
        for control in ("\u200b", "\u202e"):
            with self.subTest(control=repr(control)), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                sha = self.fixture_merge_commit(root)
                self.write(
                    root,
                    f"docs/superpowers/specs/control{control}evidence.md",
                    "# Literal Unicode format-control evidence fixture\n",
                )
                self.replace_ledger_cell(
                    root,
                    "BASE-CLOSURE-DESIGN",
                    "Evidence",
                    f"[proof](../superpowers/specs/control{control}evidence.md)"
                    f"; `merge-commit={sha}`",
                )

                self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_unicode_format_control_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof\u202e]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_scheme_and_absolute_destinations(self) -> None:
        invalid_destinations = (
            "https://example.test/evidence.md",
            "file:///etc/hosts",
            "/etc/hosts",
        )
        for destination in invalid_destinations:
            with self.subTest(destination=destination), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                sha = self.fixture_merge_commit(root)
                self.replace_ledger_cell(
                    root,
                    "BASE-CLOSURE-DESIGN",
                    "Evidence",
                    f"[proof]({destination}); `merge-commit={sha}`",
                )

                self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_backticks_in_link_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof `code`]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_emphasis_hidden_marker_in_link_label(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof merge-*commit=deadbee*]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_backtick_in_link_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/superpowers/specs/evidence`copy.md",
                "# Backtick destination fixture\n",
            )
            subprocess.run(
                ["git", "add", "docs/superpowers/specs/evidence`copy.md"],
                cwd=root,
                check=True,
            )
            subprocess.run(
                ["git", "commit", "-qm", "add backtick destination fixture"],
                cwd=root,
                check=True,
            )
            sha = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=root,
                capture_output=True,
                text=True,
                check=True,
            ).stdout.strip()
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof](../superpowers/specs/evidence`copy.md); `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_overlong_and_invalid_ledger_targets_return_findings_not_tracebacks(self) -> None:
        invalid_targets = (
            "../superpowers/specs/" + "a" * 5000,
            "../superpowers/specs/invalid\x00target.md",
        )
        expected = (
            "Delivery ledger row BASE-CLOSURE-DESIGN Evidence must contain "
            "existing in-repository relative Markdown links"
        )
        for target in invalid_targets:
            with self.subTest(target=repr(target[:80])), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                sha = self.fixture_merge_commit(root)
                self.replace_ledger_cell(
                    root,
                    "BASE-CLOSURE-DESIGN",
                    "Evidence",
                    f"[proof]({target}); `merge-commit={sha}`",
                )

                try:
                    findings = verify_repository(root)
                except (OSError, ValueError) as error:
                    self.fail(f"verifier raised for an invalid target: {error!r}")
                self.assertEqual(findings, [expected])

                cli_result = self.run_cli(root)
                self.assertEqual(cli_result.returncode, 1)
                self.assertEqual(cli_result.stdout.strip().splitlines(), [expected])
                self.assertEqual(cli_result.stderr, "")

    def test_implemented_source_cannot_be_git_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_ledger_cell(
                root,
                "R1-BACKEND",
                "Artifact",
                "[backend source](../../.git/config)",
            )
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\n"
                "Version: r1\n"
                "Artifact: [backend](../../../.git/config)\n"
                "Test: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND Artifact must contain safe Git-tracked in-repository regular-file links",
            )

    def test_runtime_verified_production_row_keeps_implementation_evidence_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\n"
                "Version: r1\n"
                "Command: pytest backend/tests/test_main.py\n"
                "Exit code: 0\n",
            )
            self.replace_ledger_cell(root, "R1-BACKEND", "State", "RUNTIME_VERIFIED")

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND RUNTIME_VERIFIED evidence must also link distinct in-repository production source and test",
            )

    def test_runtime_verified_e2e_row_uses_its_runtime_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            self.assertEqual(verify_repository(root), [])

    def test_artifact_rejects_absolute_outside_and_symlink_paths(self) -> None:
        expected = (
            "Delivery ledger row R1-BACKEND Artifact must contain safe "
            "Git-tracked in-repository regular-file links"
        )

        with self.subTest(kind="absolute"), tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.replace_ledger_cell(root, "R1-BACKEND", "Artifact", "[host](/etc/hosts)")
            self.assert_finding(root, expected)

        with self.subTest(kind="outside"), tempfile.TemporaryDirectory() as temp_dir:
            container = Path(temp_dir)
            root = container / "repo"
            root.mkdir()
            self.create_valid_repository(root)
            self.write(container, "outside.py", "# outside repository\n")
            self.replace_ledger_cell(
                root,
                "R1-BACKEND",
                "Artifact",
                "[outside](../../../outside.py)",
            )
            self.assert_finding(root, expected)

        with self.subTest(kind="symlink"), tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            alias = root / "backend/alias.py"
            alias.symlink_to("src/main.py")
            subprocess.run(["git", "add", "backend/alias.py"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "add tracked source alias"], cwd=root, check=True)
            self.replace_ledger_cell(
                root,
                "R1-BACKEND",
                "Artifact",
                "[backend alias](../../backend/alias.py)",
            )
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\n"
                "Version: r1\n"
                "Artifact: [backend alias](../../../backend/alias.py)\n"
                "Test: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\n",
            )
            self.assert_finding(root, expected)

    def test_structured_evidence_record_must_be_git_tracked(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/untracked-r1-backend.md",
                "ID: R1-BACKEND\n"
                "Version: r1\n"
                "Artifact: [backend](../../../backend/src/main.py)\n"
                "Test: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\n",
            )
            self.replace_ledger_cell(
                root,
                "R1-BACKEND",
                "Evidence",
                "[untracked record](../evidence/ledger/untracked-r1-backend.md)",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND Evidence must contain existing in-repository relative Markdown links",
            )

    def test_structured_record_does_not_filter_an_invalid_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\n"
                "Version: r1\n"
                "Artifact: [backend](../../../backend/src/main.py); [unterminated](../../../backend/src/main.py\n"
                "Test: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-BACKEND IMPLEMENTED evidence must link distinct in-repository production source and test",
            )

    def test_merge_marker_hex_text_must_prefix_resolved_commit_oid(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            commit = self.fixture_merge_commit(root)
            subprocess.run(
                ["git", "update-ref", "refs/heads/deadbee", commit],
                cwd=root,
                check=True,
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                "; `merge-commit=deadbee`",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact",
            )

    def test_merge_snapshot_rejects_symlink_tree_entry_even_if_worktree_is_regular(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            alias = root / "docs/superpowers/specs/closure-alias.md"
            alias.symlink_to("2026-08-28-baseline-closure-and-r1-gate-design.md")
            subprocess.run(
                ["git", "add", "docs/superpowers/specs/closure-alias.md"],
                cwd=root,
                check=True,
            )
            subprocess.run(
                ["git", "commit", "-qm", "snapshot symlink artifact"],
                cwd=root,
                check=True,
            )
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=root,
                capture_output=True,
                text=True,
                check=True,
            ).stdout.strip()
            alias.unlink()
            alias.write_text("# Regular worktree alias\n", encoding="utf-8")
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Artifact",
                "[closure alias](../superpowers/specs/closure-alias.md)",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[closure alias](../superpowers/specs/closure-alias.md); `merge-commit={commit}`",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN MERGED evidence must cite an ancestor commit containing its Artifact",
            )

    def test_merged_evidence_rejects_duplicate_marker_in_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.write(
                root,
                f"docs/superpowers/specs/merge-commit={sha}.md",
                "# Literal duplicate-marker destination fixture\n",
            )
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                f"[proof](../superpowers/specs/merge-commit={sha}.md)"
                f"; `merge-commit={sha}`",
            )

            self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_rejects_malformed_field_separators(self) -> None:
        separators = (";", ";  ", ";\t", ", ")
        for separator in separators:
            with self.subTest(separator=repr(separator)), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                self.create_valid_repository(root)
                sha = self.fixture_merge_commit(root)
                self.replace_ledger_cell(
                    root,
                    "BASE-CLOSURE-DESIGN",
                    "Evidence",
                    "[proof]"
                    "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                    f"{separator}`merge-commit={sha}`",
                )

                self.assert_merged_evidence_cell_is_rejected(root)

    def test_merged_evidence_accepts_exact_one_link_cell(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                f"; `merge-commit={sha}`",
            )

            self.assertEqual(verify_repository(root), [])

    def test_merged_evidence_accepts_exact_multiple_link_cell(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            sha = self.fixture_merge_commit(root)
            self.replace_ledger_cell(
                root,
                "BASE-CLOSURE-DESIGN",
                "Evidence",
                "[proof]"
                "(../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)"
                "; [plan](../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md)"
                f"; `merge-commit={sha}`",
            )

            self.assertEqual(verify_repository(root), [])

    def test_merged_evidence_must_cover_each_declared_artifact_link(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(root, "docs/superpowers/specs/uncommitted.md", "# Uncommitted artifact\n")
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[Closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md)",
                    "[Closure spec](../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md); [uncommitted](../superpowers/specs/uncommitted.md)",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row BASE-CLOSURE-DESIGN Artifact must contain safe Git-tracked in-repository regular-file links",
            )

    def test_implemented_evidence_requires_a_structured_row_bound_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8").replace(
                    "[structured record](../evidence/ledger/r1-openapi.md)",
                    "[source](../../contracts/openapi/ontology-law-api.yaml)",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-OPENAPI IMPLEMENTED evidence must link a structured row-bound evidence record",
            )

    def test_runtime_evidence_requires_a_structured_row_bound_record(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8").replace(
                    "[runtime record](../evidence/ledger/db-runtime.md)",
                    "[source](../../database/schema-contract-52-plus-2/contract/schema_contract.py)",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence must link a structured row-bound evidence record",
            )

    def test_runtime_record_rejects_token_only_command(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/db-runtime.md",
                "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\nCommand: x\nExit code: 0\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_runtime_record_rejects_arbitrary_one_token_command(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/db-runtime.md",
                "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\nCommand: y\nExit code: 0\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_runtime_record_requires_the_planned_db_runtime_verifier_target(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/db-runtime.md",
                "ID: DB-52P2-PG18-RUNTIME\nVersion: pg18-52-plus-2-v1\nCommand: python3 runtime/not-the-verifier.py verify --runs 2\nExit code: 0\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row DB-52P2-PG18-RUNTIME RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_runtime_record_requires_its_planned_playwright_target(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-e2e-golden.md",
                "ID: R1-E2E-GOLDEN\nVersion: r1\nCommand: npx playwright test e2e/tests/r1-failure-paths.spec.ts\nExit code: 0\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-E2E-GOLDEN RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record",
            )

    def test_openapi_record_rejects_an_unrelated_backend_test(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-openapi.md",
                "ID: R1-OPENAPI\nVersion: r1\nArtifact: [OpenAPI](../../../contracts/openapi/ontology-law-api.yaml)\nTest: [backend test](../../../backend/tests/test_main.py)\n",
            )

            self.assert_finding(
                root,
                "Delivery ledger row R1-OPENAPI IMPLEMENTED evidence must link distinct in-repository production source and test",
            )

    def test_supersession_rejects_unrelated_successor_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            original = next(line for line in lines if line.startswith("| R1-OPENAPI |"))
            lines[lines.index(original)] = original.replace("| — |", "| R1-BACKEND |")
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger row R1-OPENAPI successor must be versioned as R1-OPENAPI-<version>",
            )

    def test_supersession_rejects_visual_to_nonvisual_successor(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            original = next(line for line in text.splitlines() if "VIS-SALES-BASE-01" in line)
            ledger.write_text(text.replace(original, original.replace("| — |", "| BASE-CLOSURE-DESIGN |")), encoding="utf-8")

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 successor must be versioned as VIS-SALES-BASE-01-<version>",
            )

    def test_supersession_accepts_a_versioned_same_contract_successor(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-openapi-v2.md",
                "ID: R1-OPENAPI-V2\nVersion: r2\nArtifact: [OpenAPI](../../../contracts/openapi/ontology-law-api.yaml)\nTest: [OpenAPI contract test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java)\n",
            )
            subprocess.run(
                ["git", "add", "docs/evidence/ledger/r1-openapi-v2.md"],
                cwd=root,
                check=True,
            )
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            original = next(line for line in lines if line.startswith("| R1-OPENAPI |"))
            lines[lines.index(original)] = original.replace("| — |", "| R1-OPENAPI-V2 |")
            lines.append(
                markdown_row(
                    "R1-OPENAPI-V2", "R1", "OpenAPI", "API",
                    "[OpenAPI source](../../contracts/openapi/ontology-law-api.yaml)",
                    "Engineering", "r2", "R2 entry", "IMPLEMENTED",
                    "[structured record](../evidence/ledger/r1-openapi-v2.md)", "none", "—",
                )
            )
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assertEqual(verify_repository(root), [])

    def test_r2_gate_accepts_runtime_verified_for_an_implemented_requirement(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/evidence/ledger/r1-backend.md",
                "ID: R1-BACKEND\nVersion: r1\nArtifact: [backend](../../../backend/src/main.py)\nTest: [R1 API test](../../../backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java)\nCommand: pytest backend/tests/test_main.py\nExit code: 0\n",
            )
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            lines = ledger.read_text(encoding="utf-8").splitlines()
            original = next(line for line in lines if line.startswith("| R1-BACKEND |"))
            lines[lines.index(original)] = original.replace("| IMPLEMENTED |", "| RUNTIME_VERIFIED |")
            ledger.write_text("\n".join(lines) + "\n", encoding="utf-8")

            self.assertEqual(verify_repository(root), [])

    def test_cross_layout_openapi_record_binds_the_contract_to_its_backend_test(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            self.assertEqual(verify_repository(root), [])

    def test_visual_png_targets_must_be_the_exact_singleton(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            text = ledger.read_text(encoding="utf-8")
            ledger.write_text(
                text.replace(
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); `merge-commit=",
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); [another PNG](../design/sales-mvp-workcards/frozen/02-opportunity-progress.png); `merge-commit=",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 Artifact/Evidence PNG targets must be exactly its expected PNG",
            )

    def test_visual_png_fragment_target_cannot_evade_the_exact_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8").replace(
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); `merge-commit=",
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); [another PNG](../design/sales-mvp-workcards/frozen/02-opportunity-progress.png#preview); `merge-commit=",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 Evidence must contain existing in-repository relative Markdown links",
            )

    def test_visual_png_query_target_cannot_evade_the_exact_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8").replace(
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); `merge-commit=",
                    "[PNG](../design/sales-mvp-workcards/frozen/01-contact-lead-review.png); [another PNG](../design/sales-mvp-workcards/frozen/02-opportunity-progress.png?preview=1); `merge-commit=",
                    1,
                ),
                encoding="utf-8",
            )

            self.assert_finding(
                root,
                "Delivery ledger row VIS-SALES-BASE-01 Evidence must contain existing in-repository relative Markdown links",
            )

    def test_notes_table_after_ledger_is_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8")
                + "\n## Notes\n\n| Name | Value |\n| --- | --- |\n| review | retained |\n",
                encoding="utf-8",
            )

            self.assertEqual(verify_repository(root), [])

    def test_default_cli_fails_for_structural_only_findings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
                "# 收口设计\n\n状态：待复核\n",
            )
            expected = (
                "Closure spec must be approved and frozen: "
                "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md"
            )

            self.assertEqual(verify_repository(root), [expected])
            cli_result = self.run_cli(root)
            self.assertEqual(cli_result.returncode, 1)
            self.assertEqual(cli_result.stdout.strip().splitlines(), [expected])
            self.assertEqual(cli_result.stderr, "")

    def test_default_cli_fails_structural_findings_but_marks_r2_blockers_nonfatal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            self.write(
                root,
                "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md",
                "# 收口设计\n\n状态：待复核\n",
            )
            ledger = root / "docs/progress/MVP-DELIVERY-LEDGER.md"
            ledger.write_text(
                "\n".join(
                    line
                    for line in ledger.read_text(encoding="utf-8").splitlines()
                    if not line.startswith("| R1-OPENAPI |")
                )
                + "\n",
                encoding="utf-8",
            )
            structural = (
                "Closure spec must be approved and frozen: "
                "docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md"
            )
            blocker = (
                "Gate R2 entry unmet: missing required delivery row R1-OPENAPI; "
                "计划不是生产代码"
            )

            self.assertEqual(verify_repository(root), [structural, blocker])
            cli_result = self.run_cli(root)
            self.assertEqual(cli_result.returncode, 1)
            self.assertEqual(
                cli_result.stdout.strip().splitlines(),
                [structural, f"R2 readiness blocker (non-fatal): {blocker}"],
            )
            self.assertEqual(cli_result.stderr, "")

    def test_healthy_cli_prints_pass_in_default_and_strict_modes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)

            for strict_r2 in (False, True):
                with self.subTest(strict_r2=strict_r2):
                    cli_result = self.run_cli(root, strict_r2=strict_r2)
                    self.assertEqual(cli_result.returncode, 0)
                    self.assertEqual(cli_result.stdout, "baseline consistency: PASS\n")
                    self.assertEqual(cli_result.stderr, "")

    def test_structural_finding_with_gate_prefix_remains_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            synthetic_structural = (
                "Gate R2 entry unmet: synthetic structural parser failure"
            )

            def inject_structural(_root: Path, findings: list[str]) -> None:
                findings.append(synthetic_structural)

            stdout = io.StringIO()
            with mock.patch.object(
                verify_baseline_module,
                "verify_delivery_ledger",
                side_effect=inject_structural,
            ), redirect_stdout(stdout):
                exit_code = verify_baseline_module.main([str(root)])

            self.assertEqual(exit_code, 1)
            self.assertEqual(stdout.getvalue(), f"{synthetic_structural}\n")

    def test_visual_asset_counts_are_frozen(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.create_valid_repository(root)
            (root / "docs/design/sales-mvp-workcards/p0/P0-15-transfer-resubmit.png").unlink()

            expected = [
                "Delivery ledger row VIS-SALES-P0-15 Artifact must contain safe Git-tracked in-repository regular-file links",
                "Visual asset count mismatch for docs/design/sales-mvp-workcards: expected 27 PNG files, found 26",
            ]
            self.assertEqual(verify_repository(root), expected)
            cli_result = self.run_cli(root)
            self.assertEqual(cli_result.returncode, 1)
            self.assertEqual(cli_result.stdout.strip().splitlines(), expected)
            self.assertEqual(cli_result.stderr, "")


if __name__ == "__main__":
    unittest.main()
