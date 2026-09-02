from __future__ import annotations

import argparse
import html
import os
import re
import shlex
import stat
import string
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from enum import Enum
from pathlib import Path, PureWindowsPath
from urllib.parse import unquote, urlsplit


ALLOWED_STATES = {"DRAFT", "FROZEN", "MERGED", "IMPLEMENTED", "RUNTIME_VERIFIED"}
LEDGER_COLUMNS = [
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
TARGET_GATE_STATES = {
    "PR2 merge": {"DRAFT", "FROZEN", "MERGED"},
    "R1 implementation": {"DRAFT", "FROZEN", "MERGED"},
    "R2 entry": ALLOWED_STATES,
}
VISUAL_BUNDLE_VERSION = "visual-bundle-2026-08-27"
VISUAL_OWNER = "Product Design"
VISUAL_CONFIRMATION_DATE = "2026-08-27"
CANONICAL_BASELINE_ID = "MVP-2026-08-28.1"
HISTORICAL_BANNER = "历史规格（HISTORICAL_SUPERSEDED）"
HISTORICAL_WARNING = (
    "> [!WARNING]\n"
    "> 历史规格（HISTORICAL_SUPERSEDED）。本文仅保留设计演进证据；"
    "与[当前MVP基线](../baseline/CURRENT-MVP-BASELINE.md)冲突时，"
    "当前基线及52＋2合同优先。本文不得作为新实现或DDL生成依据。"
)
HISTORICAL_REPLACEMENT_HEADING = "## 历史修订记录（已被当前基线替代）"
MATTER_ENDPOINT_FROZEN_SECTION = """## matter-endpoint

销售MVP终点固定为：

```text
DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
+ TransferAccepted
+ TransferRequest的一次写入MatterRef
+ 案管Task DONE
+ 销售结果回执
```

同一本地事务必须写入完整MatterRef槽：稳定`matter_id`、`matter_no`、类型、能力包版本和可信创建时间，并发布`MatterCreated`事实通知供Post-MVP消费者使用。

MVP不建Matter业务表、页面或办理责任，不建设登记资料、分类、分案、承办团队、节点、期限、办理、成果或结案Task。Post-MVP不得生成第二Matter身份或反向改写销售历史；Matter模块只能消费已接受转案及其稳定MatterRef。MatterRef表示正式稳定身份已被分配，不表示完整Matter聚合或案件办理能力已经启用。
"""


class FindingCategory(Enum):
    STRUCTURAL = "structural"
    R2_READINESS = "r2_readiness"


@dataclass(frozen=True)
class CategorizedFinding:
    message: str
    category: FindingCategory


@dataclass(frozen=True)
class VerificationResult:
    findings: tuple[CategorizedFinding, ...]

    def messages(self) -> list[str]:
        return [finding.message for finding in self.findings]

    def in_category(self, category: FindingCategory) -> list[CategorizedFinding]:
        return [finding for finding in self.findings if finding.category is category]


class InvalidUtf8GovernedFile(Exception):
    def __init__(self, relative_path: Path):
        self.relative_path = relative_path
        super().__init__(relative_path.as_posix())


CONFLICT_SECTIONS = {
    Path("docs/specs/2026-08-18-law-firm-overall-architecture-ontology-design.md"): {
        "### 3.2 MVP终点": "matter-endpoint",
        "### 7.2 Task状态机": "task-waiting-contract",
        "## 9. WAITING、WaitReceipt与Chat状态": "task-waiting-contract",
        "## 17. 《最小Matter身份与后MVP扩展契约 v1.0》（冻结）": "matter-endpoint",
        "## 18. 后MVP Matter扩展契约": "matter-endpoint",
    },
    Path("docs/specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md"): {
        "### 3.1 MVP终点": "matter-endpoint",
        "## 4. 最小Matter本体": "matter-endpoint",
        "## 5. MatterOpeningPort": "matter-endpoint",
        "## 6. 接收事务与因果顺序": "matter-endpoint",
        "## 7. MatterLink": "matter-endpoint",
        "## 13. 验收级不变量": "matter-endpoint",
        "## 14. 版本治理": "matter-endpoint",
    },
    Path("docs/specs/2026-08-18-ontology-law-system-foundation-architecture-v1.0.md"): {
        "## 3. 已冻结的总体方案": "application-topology",
        "## 4. 目标技术基线": "application-topology",
        "## 5. 运行拓扑": "application-topology",
        "## 6. 前端与通道边界": "application-topology",
        "### 12.1 Task不变量": "task-waiting-contract",
        "### 12.4 WAITING与WaitReceipt": "task-waiting-contract",
        "### 28.2 ChangeGate": "application-topology",
        "## 31. 下一层详细设计边界": "application-topology",
    },
    Path("docs/specs/2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md"): {
        "## 2. 冻结决策摘要": "application-topology",
        "### 7.3 五个跨模块原子边界": "matter-endpoint",
        "## 12. OpenAPI与前端工作区": "application-topology",
        "### 16.1 固定检查组": "application-topology",
        "## 18. 完成判据": "application-topology",
    },
    Path("docs/specs/2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md"): {
        "### 1.2 适用优先级": "application-topology",
        "### 1.4 六种表形态": "task-waiting-contract",
        "### 5.2 SemanticKind": "task-waiting-contract",
        "### 6.7 Task与唯一完成事实": "task-waiting-contract",
        "## 11. 冻结声明": "application-topology",
    },
}
EXPECTED_VISUAL_ASSETS = {
    "docs/design/sales-mvp-workcards": 27,
    "docs/design/identity-admin-mvp": 7,
}

CANONICAL_BASELINE = Path("docs/baseline/CURRENT-MVP-BASELINE.md")
CLOSURE_SPEC = Path("docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md")
DELIVERY_LEDGER = Path("docs/progress/MVP-DELIVERY-LEDGER.md")
R1_PLAN = Path("docs/superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md")
R1_SCAFFOLD_ADR = Path("docs/adr/ADR-0004-r1-scaffold-and-http-contract.md")
R1_TASK_CONTRACT = Path("docs/contracts/r1/R1-TASK-COMPLETION-MATRIX.md")
R1_HTTP_CONTRACT = Path("docs/contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md")
R1_WORKBENCH_CONTRACT = Path("docs/contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md")
README = Path("README.md")
VISUAL_INDEXES = {
    Path("docs/design/sales-mvp-workcards/README.md"): Path(
        "docs/design/sales-mvp-workcards"
    ),
    Path("docs/design/identity-admin-mvp/README.md"): Path(
        "docs/design/identity-admin-mvp"
    ),
}
VISUAL_INDEX_METADATA = "\n".join(
    [
        "> 状态：FROZEN",
        f"> Bundle版本：{VISUAL_BUNDLE_VERSION}",
        f"> Owner：{VISUAL_OWNER}",
        f"> 确认日期：{VISUAL_CONFIRMATION_DATE}",
    ]
)

R1_TASK_CONTRACTS = {
    "RESOLVE_LEAD_DUPLICATE": ("RESOLVE_DUPLICATE_LEAD", "responsibility.decision_record"),
    "COMPLETE_LEAD_INGRESS": ("COMPLETE_LEAD_INGRESS", "lead.lead"),
    "ASSIGN_LEAD": ("ASSIGN_LEAD", "lead.lead_assignment"),
    "RESOLVE_LEAD_ROUTING_GAP": ("RECORD_ROUTING_DISPOSITION", "responsibility.decision_record"),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": (
        "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
        "responsibility.decision_record",
    ),
    "CONTACT_LEAD": ("RECORD_CONTACT_RESULT", "lead.lead_contact_result"),
    "REVIEW_LEAD_VALIDITY": ("REVIEW_LEAD_VALIDITY", "responsibility.decision_record"),
}
R1_REQUIRED_TASK_TYPES = set(R1_TASK_CONTRACTS)
R1_REQUIRED_BRANCHES = {
    "P0_01_LINK_EXISTING": ("RESOLVE_LEAD_DUPLICATE", "LINK_EXISTING_PARTY"),
    "P0_01_KEEP_SEPARATE": ("RESOLVE_LEAD_DUPLICATE", "KEEP_SEPARATE"),
    "P0_02_COMPLETE": ("COMPLETE_LEAD_INGRESS", "INGRESS_COMPLETED"),
    "P0_03_ASSIGN": ("ASSIGN_LEAD", "ASSIGNED"),
    "P0_04_SCHEDULE_ROUTING_REVIEW": (
        "RESOLVE_LEAD_ROUTING_GAP",
        "SCHEDULE_ROUTING_REVIEW",
    ),
    "P0_04_RETRY_ASSIGNMENT_NOW": (
        "RESOLVE_LEAD_ROUTING_GAP",
        "RETRY_ASSIGNMENT_NOW",
    ),
    "P0_04_REQUEST_SOURCE_INTAKE_STOP": (
        "RESOLVE_LEAD_ROUTING_GAP",
        "REQUEST_SOURCE_INTAKE_STOP",
    ),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": (
        "ACK_SOURCE_INTAKE_STOP_REQUEST",
        "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED",
    ),
    "CONTACT_CONNECTED_VALID": ("CONTACT_LEAD", "CONNECTED_VALID"),
    "CONTACT_NOT_CONNECTED_RETRY": ("CONTACT_LEAD", "NOT_CONNECTED"),
    "CONTACT_NOT_CONNECTED_EXHAUSTED": ("CONTACT_LEAD", "NOT_CONNECTED"),
    "CONTACT_SUSPECT_INVALID": ("CONTACT_LEAD", "SUSPECT_INVALID"),
    "REVIEW_CONFIRM_INVALID": ("REVIEW_LEAD_VALIDITY", "CONFIRM_INVALID"),
    "REVIEW_CLOSE_UNREACHED": ("REVIEW_LEAD_VALIDITY", "CLOSE_UNREACHED"),
    "REVIEW_REOPEN_CONTACT": ("REVIEW_LEAD_VALIDITY", "REOPEN_CONTACT"),
}
R1_RECEIPT_RESULTS = {"SUCCEEDED", "NO_CHANGE", "REJECTED"}
R1_SUCCESSOR_CONTRACTS = {
    "P0_01_LINK_EXISTING": (
        "COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP",
        "R1_LEAD_NEXT_RESPONSIBILITY_V1",
        "POLICY_SELECTED",
    ),
    "P0_01_KEEP_SEPARATE": (
        "COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP",
        "R1_LEAD_NEXT_RESPONSIBILITY_V1",
        "POLICY_SELECTED",
    ),
    "P0_02_COMPLETE": (
        "ASSIGN_LEAD,CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP",
        "R1_LEAD_NEXT_RESPONSIBILITY_V1",
        "POLICY_SELECTED",
    ),
    "P0_03_ASSIGN": ("CONTACT_LEAD", "DIRECT", "ASSIGNMENT_OWNER"),
    "P0_04_SCHEDULE_ROUTING_REVIEW": (
        "RESOLVE_LEAD_ROUTING_GAP",
        "NEXT_BUSINESS_WINDOW",
        "SAME_ROUTING_SUPERVISOR",
    ),
    "P0_04_RETRY_ASSIGNMENT_NOW": (
        "CONTACT_LEAD,RESOLVE_LEAD_ROUTING_GAP",
        "R1_ASSIGNMENT_RETRY_V1",
        "POLICY_SELECTED",
    ),
    "P0_04_REQUEST_SOURCE_INTAKE_STOP": (
        "ACK_SOURCE_INTAKE_STOP_REQUEST",
        "DIRECT",
        "SOURCE_INTAKE_OWNER",
    ),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": ("NONE", "NONE", "NONE"),
    "CONTACT_CONNECTED_VALID": ("NONE", "OPPORTUNITY_BOUNDARY_V1", "NONE"),
    "CONTACT_NOT_CONNECTED_RETRY": (
        "CONTACT_LEAD",
        "CONTACT_RETRY_V1",
        "SAME_ASSIGNMENT_OWNER",
    ),
    "CONTACT_NOT_CONNECTED_EXHAUSTED": (
        "REVIEW_LEAD_VALIDITY",
        "CONTACT_RETRY_V1",
        "ROUTING_SUPERVISOR",
    ),
    "CONTACT_SUSPECT_INVALID": ("REVIEW_LEAD_VALIDITY", "DIRECT", "ROUTING_SUPERVISOR"),
    "REVIEW_CONFIRM_INVALID": ("NONE", "NONE", "NONE"),
    "REVIEW_CLOSE_UNREACHED": ("NONE", "NONE", "NONE"),
    "REVIEW_REOPEN_CONTACT": ("CONTACT_LEAD", "DIRECT", "CURRENT_ASSIGNMENT_OWNER"),
}
R1_BRANCH_DETAIL_CONTRACTS = {
    "P0_01_LINK_EXISTING": (
        "SUCCEEDED", "`LEAD_DUPLICATE_RESOLUTION@hash`",
        "`LeadDuplicateResolutionRecordedV1`", "R1_PROJECTION",
    ),
    "P0_01_KEEP_SEPARATE": (
        "SUCCEEDED", "`LEAD_DUPLICATE_RESOLUTION@hash`",
        "`LeadDuplicateResolutionRecordedV1`", "R1_PROJECTION",
    ),
    "P0_02_COMPLETE": (
        "SUCCEEDED", "`lead@newRevision`", "`LeadIngressCompletedV1`", "R1_PROJECTION",
    ),
    "P0_03_ASSIGN": (
        "SUCCEEDED", "`assignment@revision0`", "`LeadAssignedV1`", "R1_PROJECTION",
    ),
    "P0_04_SCHEDULE_ROUTING_REVIEW": (
        "SUCCEEDED", "`LEAD_ROUTING_DISPOSITION@hash`",
        "`LeadRoutingDispositionRecordedV1`", "R1_PROJECTION",
    ),
    "P0_04_RETRY_ASSIGNMENT_NOW": (
        "SUCCEEDED", "`LEAD_ROUTING_DISPOSITION@hash`",
        "`LeadRoutingDispositionRecordedV1`", "R1_PROJECTION",
    ),
    "P0_04_REQUEST_SOURCE_INTAKE_STOP": (
        "SUCCEEDED", "`LEAD_ROUTING_DISPOSITION@hash`",
        "`SourceIntakeStopRequestedV1`", "R1_PROJECTION",
    ),
    "ACK_SOURCE_INTAKE_STOP_REQUEST": (
        "SUCCEEDED", "`SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED@hash`",
        "`SourceIntakeStopRequestAcknowledgedV1`", "R1_PROJECTION",
    ),
    "CONTACT_CONNECTED_VALID": (
        "SUCCEEDED", "`contactResult@hash`", "`LeadContactResultRecordedV1`", "R1_PROJECTION",
    ),
    "CONTACT_NOT_CONNECTED_RETRY": (
        "SUCCEEDED", "`contactResult@hash; attemptNo<3`",
        "`LeadContactResultRecordedV1`", "R1_PROJECTION",
    ),
    "CONTACT_NOT_CONNECTED_EXHAUSTED": (
        "SUCCEEDED", "`contactResult@hash; attemptNo=3`",
        "`LeadContactRetryExhaustedV1`", "R1_PROJECTION",
    ),
    "CONTACT_SUSPECT_INVALID": (
        "SUCCEEDED", "`contactResult@hash`", "`LeadContactResultRecordedV1`", "R1_PROJECTION",
    ),
    "REVIEW_CONFIRM_INVALID": (
        "SUCCEEDED", "`LEAD_VALIDITY_REVIEW@hash`", "`LeadValidityReviewedV1`", "R1_PROJECTION",
    ),
    "REVIEW_CLOSE_UNREACHED": (
        "SUCCEEDED", "`LEAD_VALIDITY_REVIEW@hash`", "`LeadValidityReviewedV1`", "R1_PROJECTION",
    ),
    "REVIEW_REOPEN_CONTACT": (
        "SUCCEEDED", "`LEAD_VALIDITY_REVIEW@hash`", "`LeadValidityReviewedV1`", "R1_PROJECTION",
    ),
}
R1_E2E_CONTRACTS = {
    "E2E_P0_01_LINK": (
        "P0_01_LINK_EXISTING", "`decision_record:+1`", "`current:DONE,r+1`",
        "`R1 selector:exactly1`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; replay:all-0; technical-failure:all-0`",
    ),
    "E2E_P0_01_SEPARATE": (
        "P0_01_KEEP_SEPARATE", "`decision_record:+1`", "`current:DONE,r+1`",
        "`R1 selector:exactly1`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; replay:all-0; technical-failure:all-0`",
    ),
    "E2E_P0_02": (
        "P0_02_COMPLETE", "`lead rows:+0; ingress slot:0-to-1; lead revision:+1`",
        "`current:DONE,r+1`", "`R1 selector:exactly1`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`stale:domain-0; other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_P0_03": (
        "P0_03_ASSIGN", "`assignment:+1; lead revision:+1`", "`current:DONE,r+1`",
        "`CONTACT_LEAD:+1,OPEN,r0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; duplicate-open-assignment:rejected; technical-failure:all-0`",
    ),
    "E2E_P0_04_SCHEDULE": (
        "P0_04_SCHEDULE_ROUTING_REVIEW", "`decision_record:+1; wait_receipt:+1`",
        "`current:DONE,r+1`", "`routing task:+1,WAITING,r1`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_P0_04_RETRY_CANDIDATE": (
        "P0_04_RETRY_ASSIGNMENT_NOW",
        "`decision_record:+1; assignment:+1; lead revision:+1`", "`current:DONE,r+1`",
        "`CONTACT_LEAD:+1,OPEN,r0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_P0_04_RETRY_EMPTY": (
        "P0_04_RETRY_ASSIGNMENT_NOW", "`decision_record:+1; assignment:+0`",
        "`current:DONE,r+1`", "`routing task:+1,OPEN,r0`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`no-recursion:true; other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_P0_04_STOP_REQUEST": (
        "P0_04_REQUEST_SOURCE_INTAKE_STOP", "`decision_record:+1; source state:+0`",
        "`current:DONE,r+1`", "`ACK_SOURCE_INTAKE_STOP_REQUEST:+1,OPEN,r0`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_STOP_ACK": (
        "ACK_SOURCE_INTAKE_STOP_REQUEST", "`decision_record:+1; source state:+0`",
        "`current:DONE,r+1`", "`NONE`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_CONTACT_CONNECTED": (
        "CONTACT_CONNECTED_VALID", "`contact_result:+1; opportunity:+1`",
        "`current:DONE,r+1`", "`R2 task:+0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_CONTACT_RETRY": (
        "CONTACT_NOT_CONNECTED_RETRY", "`contact_result:+1; wait_receipt:+1`",
        "`current:DONE,r+1`", "`CONTACT_LEAD:+1,WAITING,r1`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; due-before-resume:0; technical-failure:all-0`",
    ),
    "E2E_CONTACT_EXHAUSTED": (
        "CONTACT_NOT_CONNECTED_EXHAUSTED", "`contact_result:+1`", "`current:DONE,r+1`",
        "`REVIEW_LEAD_VALIDITY:+1,OPEN,r0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`retry-task:+0; other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_CONTACT_SUSPECT": (
        "CONTACT_SUSPECT_INVALID", "`contact_result:+1`", "`current:DONE,r+1`",
        "`REVIEW_LEAD_VALIDITY:+1,OPEN,r0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_REVIEW_INVALID": (
        "REVIEW_CONFIRM_INVALID", "`decision_record:+1`", "`current:DONE,r+1`", "`NONE`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_REVIEW_UNREACHED": (
        "REVIEW_CLOSE_UNREACHED", "`decision_record:+1`", "`current:DONE,r+1`", "`NONE`",
        "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`other-tenant:0; technical-failure:all-0`",
    ),
    "E2E_REVIEW_REOPEN": (
        "REVIEW_REOPEN_CONTACT", "`decision_record:+1`", "`current:DONE,r+1`",
        "`CONTACT_LEAD:+1,OPEN,r0`", "`receipt:+1,event:+1,outbox:+1,audit:+1`",
        "`old-task-reopen:0; other-tenant:0; technical-failure:all-0`",
    ),
}
R1_ERROR_CONTRACTS = {
    "VALIDATION_FAILED": ("400", "SAME_KEY_AFTER_FIX", "REQUIRED", "NONE"),
    "IDEMPOTENCY_KEY_REQUIRED": ("400", "SAME_KEY_AFTER_FIX", "NONE", "NONE"),
    "IDEMPOTENCY_KEY_INVALID": ("400", "SAME_KEY_AFTER_FIX", "NONE", "NONE"),
    "UNAUTHENTICATED": ("401", "SAME_KEY_AFTER_REAUTH", "NONE", "NONE"),
    "NOT_AUTHORIZED": ("403", "NO", "NONE", "NONE"),
    "APPOINTMENT_INACTIVE": ("403", "NO", "NONE", "NONE"),
    "NOT_FOUND": ("404", "NO", "NONE", "NONE"),
    "COMMAND_PAYLOAD_CONFLICT": ("409", "NO", "NONE", "NONE"),
    "TASK_NOT_OPEN": ("409", "NO", "NONE", "TASK"),
    "TASK_ALREADY_COMPLETED": ("409", "NO", "NONE", "TASK"),
    "DRAFT_DIGEST_MISMATCH": ("409", "NEW_KEY_AFTER_REFRESH", "NONE", "DRAFT"),
    "INGRESS_COMPLETION_ALREADY_RECORDED": ("409", "NO", "NONE", "SUBJECT"),
    "STALE_TASK": ("412", "NEW_KEY_AFTER_REFRESH", "NONE", "TASK"),
    "STALE_DRAFT": ("412", "NEW_KEY_AFTER_REFRESH", "NONE", "DRAFT"),
    "STALE_SUBJECT": ("412", "NEW_KEY_AFTER_REFRESH", "NONE", "SUBJECT"),
    "SUPERVISOR_UNRESOLVED": ("422", "NEW_KEY_AFTER_ADMIN_FIX", "NONE", "NONE"),
    "SOURCE_INTAKE_OWNER_UNRESOLVED": (
        "422", "NEW_KEY_AFTER_ADMIN_FIX", "NONE", "NONE"
    ),
    "DRAFT_PRECONDITION_REQUIRED": ("428", "SAME_KEY_AFTER_FIX", "NONE", "DRAFT"),
    "TASK_PRECONDITION_REQUIRED": ("428", "SAME_KEY_AFTER_FIX", "NONE", "TASK"),
    "RATE_LIMITED": ("429", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
    "INTERNAL_ERROR": ("500", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
    "SERVICE_UNAVAILABLE": ("503", "SAME_KEY_AFTER_BACKOFF", "NONE", "NONE"),
}
R1_OPERATION_CONTRACTS = {
    "captureLead": (
        "POST", "/api/v1/leads", "REQUIRED", "NONE", "SOURCE_NATURAL_KEY", "201"
    ),
    "getCurrentWorkCard": (
        "GET",
        "/api/v1/workcards/current",
        "NONE",
        "OPTIONAL_WORKBENCH_ETAG",
        "ACTOR_SCOPE",
        "200/304",
    ),
    "saveActionDraft": (
        "PUT",
        "/api/v1/tasks/{taskId}/draft",
        "REQUIRED",
        "IF_NONE_MATCH_STAR_OR_DRAFT_ETAG",
        "TASK_AND_DRAFT",
        "200/201",
    ),
    "resolveDuplicateLead": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_AND_LEAD_REVISION",
        "200",
    ),
    "completeLeadIngress": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/complete-lead-ingress",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_AND_LEAD_REVISION",
        "200",
    ),
    "assignLead": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/assign-lead",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_LEAD_AND_ASSIGNMENT",
        "200",
    ),
    "recordRoutingDisposition": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/record-routing-disposition",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_AND_LEAD_REVISION",
        "200",
    ),
    "acknowledgeSourceIntakeStopRequest": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_AND_CAUSAL_DECISION",
        "200",
    ),
    "recordContactResult": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/record-contact-result",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_LEAD_AND_ASSIGNMENT",
        "200",
    ),
    "reviewLeadValidity": (
        "POST",
        "/api/v1/tasks/{taskId}/commands/review-lead-validity",
        "REQUIRED",
        "TASK_ETAG",
        "TASK_AND_CAUSAL_RESULT",
        "200",
    ),
    "getCommandReceipt": (
        "GET",
        "/api/v1/commands/{commandId}/receipt",
        "NONE",
        "NONE",
        "COMMAND_ID_AND_ACTOR_SCOPE",
        "200",
    ),
    "reopenDueContactTasks": (
        "POST",
        "/internal/v1/tasks/commands/reopen-due-contact-tasks",
        "REQUIRED",
        "NONE",
        "DUE_CUTOFF_AND_OWNER_QUEUE",
        "200",
    ),
}
R1_IDEMPOTENCY_BINDING = {
    "Header": "Idempotency-Key",
    "ValueType": "UUID",
    "SlotColumn": "execution.command_execution_slot.command_id",
    "CommandId": "EXACT_CALLER_KEY",
    "ReceiptId": "SERVER_UUIDV7",
    "SlotScope": "TENANT_ENVELOPE_SUBJECT_SCOPE",
    "PayloadConflict": "ORIGINAL_RECEIPT_NO_NEW_WRITES",
}
R1_OPERATION_ERRORS = {
    "captureLead": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE",
        "COMMAND_PAYLOAD_CONFLICT", "SUPERVISOR_UNRESOLVED", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "getCurrentWorkCard": {
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "NOT_FOUND", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "saveActionDraft": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT",
        "DRAFT_PRECONDITION_REQUIRED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "resolveDuplicateLead": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "SUPERVISOR_UNRESOLVED", "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "completeLeadIngress": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "INGRESS_COMPLETION_ALREADY_RECORDED", "STALE_TASK",
        "STALE_DRAFT", "STALE_SUBJECT", "SUPERVISOR_UNRESOLVED",
        "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "assignLead": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "recordRoutingDisposition": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "SOURCE_INTAKE_OWNER_UNRESOLVED", "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "acknowledgeSourceIntakeStopRequest": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "recordContactResult": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "SUPERVISOR_UNRESOLVED", "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "reviewLeadValidity": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "NOT_FOUND",
        "COMMAND_PAYLOAD_CONFLICT", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH", "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
        "TASK_PRECONDITION_REQUIRED", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "getCommandReceipt": {
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "NOT_FOUND", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
    "reopenDueContactTasks": {
        "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID",
        "UNAUTHENTICATED", "NOT_AUTHORIZED", "COMMAND_PAYLOAD_CONFLICT", "RATE_LIMITED",
        "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
    },
}
R1_WORKBENCH_FIELDS = {
    "todaySummary": "1",
    "currentCard": "0..1",
    "nextSummaries": "0..2",
    "waitingCount": "1",
    "chatComposer": "1",
}
R1_ROUTE_MODES = {
    "WORKBENCH": ("/workbench", "NONE", "NONE"),
    "IDENTITY_ADMIN": ("/admin/identity/*", "IDENTITY_ONLY", "LEFT"),
}
R1_SCAFFOLD_DECISIONS = {
    "backendProject": (
        "SINGLE_MAVEN_BACKEND",
        "单 Maven 工程 `backend/pom.xml`；不得创建第二个后端项目或聚合多模块",
    ),
    "jar": (
        "SINGLE_DEPLOYABLE_JAR",
        "唯一可部署 Jar 的 groupId 为 `io.github.windyzhu3`，artifactId 为 `ontology-law-system`",
    ),
    "rootPackage": (
        "IO_GITHUB_WINDYZHU3_ONTOLOGYLAW",
        "唯一 Java 根包为 `io.github.windyzhu3.ontologylaw`；R1 只含 bootstrap、api、worker、audit、execution、identity、lead、opportunity、query、responsibility",
    ),
    "runtimeRole": (
        "OLS_API_OR_WORKER",
        "唯一键 `ols.runtime-role` 只接受单值 `api` 或 `worker`；缺失、未知、重复来源冲突或双 Context 均启动失败",
    ),
    "npmWorkspace": (
        "ROOT_SINGLE_WORKBENCH",
        "根 `package.json` 只声明一个 workspace `apps/workbench`；提交 npm lockfile；禁止第二个可部署前端 package",
    ),
    "workbench": (
        "SINGLE_SPA_ROUTE_MODES",
        "唯一 SPA 位于 `apps/workbench`；`/workbench` 与 `/admin/identity/*` 是同一制品的不同受保护 route mode",
    ),
    "openapi": (
        "SINGLE_OPENAPI_DUAL_CODEGEN",
        "唯一源为 `contracts/openapi/ontology-law-api.yaml`；后端生成到 `backend/target/generated-sources/openapi` 且不提交；前端提交 `apps/workbench/src/generated/api/schema.d.ts`；两端 `--check` 重生成必须零差异",
    ),
    "database": (
        "PG18_13_SCHEMAS",
        "一个 PostgreSQL 18 数据库和 13 个受管 Schema；应用运行角色不是 migration owner；API 和 Worker 不持有 migration owner 凭据",
    ),
    "migrations": (
        "SINGLE_JAR_FLYWAY_SOURCE",
        "唯一迁移源为 `database/schema-contract-52-plus-2/generated/db/migration`；构建只读映射到同一 Jar 的 `db/migration`；Jar 内字节清单必须与源 SHA-256 清单相同；禁止第二套手写迁移",
    ),
    "jooq": (
        "V1_1_RECORDS_POJOS_ONLY",
        "仅从已通过门禁的真实 `52-plus-2-v1.1` PostgreSQL 空库生成；提交 records、POJOs 与 `MANIFEST.sha256`；不生成 DAO、Active Record 或第二持久化模型",
    ),
    "checks": (
        "BASELINE_SCHEMA_RUNTIME_SCAFFOLD",
        "既有稳定名为 `verify-baseline`、`verify`、`runtime-postgresql-18`；后续脚手架新增唯一 always-run 聚合名 `scaffold-gate`；本 ADR 不伪称该 workflow 已存在",
    ),
    "toolchain": (
        "EXACT_PINS_2026_09_02",
        "Temurin `25.0.4.1+1`；Maven Wrapper `3.3.4`、Maven `3.9.16`、distribution SHA-256 `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`；CPython `3.12.14`；Node `24.20.0`、npm `11.9.0`；PostgreSQL `18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`；Flyway `13.4.0@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93`",
    ),
}

MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.*)$", re.MULTILINE)
FENCE_OPEN_PATTERN = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
MERGE_MARKER_PREFIX = "`merge-commit="
LOWER_HEX_DIGITS = frozenset("0123456789abcdef")
HEX_DIGITS = LOWER_HEX_DIGITS | frozenset("ABCDEF")
MAX_LINK_TARGET_LENGTH = 4096
MARKDOWN_INLINE_LABEL_DELIMITERS = frozenset("*_~`<>")


def expected_visual_rows() -> dict[str, str]:
    sales_base_names = [
        "contact-lead-review", "opportunity-progress", "prepare-quote",
        "contract-approval", "follow-first-payment", "fix-transfer-upload",
        "conflict-input", "prepare-contract", "submit-signature-evidence",
        "quote-response", "confirm-payment", "transfer-accept",
    ]
    sales_p0_names = [
        "duplicate-lead", "missing-contact", "manual-owner", "zero-candidate",
        "invalid-lead-review", "opportunity-disposition", "quote-approval-request",
        "quote-authorization", "quote-send", "quote-send-correction",
        "quote-unknown-disposition", "conflict-finding-decision", "first-transfer",
        "transfer-return", "transfer-resubmit",
    ]
    identity_names = [
        "identity-principals", "organization-units", "appointments", "authority-grants",
        "delegation-grants", "object-access-grants", "audit-records",
    ]
    rows = {
        f"VIS-SALES-BASE-{index:02d}":
        f"../design/sales-mvp-workcards/frozen/{index:02d}-{name}.png"
        for index, name in enumerate(sales_base_names, 1)
    }
    rows.update({
        f"VIS-SALES-P0-{index:02d}":
        f"../design/sales-mvp-workcards/p0/P0-{index:02d}-{name}.png"
        for index, name in enumerate(sales_p0_names, 1)
    })
    rows.update({
        f"VIS-IDENTITY-ADM-{index:02d}":
        f"../design/identity-admin-mvp/frozen/ADM-{index:02d}-{name}.png"
        for index, name in enumerate(identity_names, 1)
    })
    return rows


EXPECTED_VISUAL_ROWS = expected_visual_rows()
REQUIRED_NONVISUAL_ROWS = {
    "DB-52P2-CONTRACT": ("MVP", "MERGED", "52-plus-2-v1", "../../database/schema-contract-52-plus-2/contract/schema_contract.py"),
    "DB-52P2-MIGRATIONS": ("MVP", "MERGED", "52-plus-2-v1", "../../database/schema-contract-52-plus-2/generated/db/migration/V840__schema_contract_validation.sql"),
    "BASE-CLOSURE-DESIGN": ("PR2", "FROZEN", CANONICAL_BASELINE_ID, "../superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md"),
    "BASE-PR2-CLOSURE-PLAN": ("PR2", "FROZEN", "2026-08-28", "../superpowers/plans/2026-08-28-pr2-baseline-and-ledger-closure-plan.md"),
    "BASE-CURRENT-MVP": ("MVP", "FROZEN", CANONICAL_BASELINE_ID, "../baseline/CURRENT-MVP-BASELINE.md"),
    "R1-IMPLEMENTATION-PLAN": ("R1", "FROZEN", "2026-08-28", "../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md"),
    "R1-IMPLEMENTATION-CONTRACT": ("R1", "FROZEN", "r1-contract-v1", "../adr/ADR-0004-r1-scaffold-and-http-contract.md"),
    "DB-52P2-PG18-RUNTIME-PLAN": ("MVP", "DRAFT", "2026-08-28", "../superpowers/plans/2026-08-28-postgresql-runtime-verification-plan.md"),
}
R1_PRODUCTION_ROWS = {
    "R1-OPENAPI": "IMPLEMENTED",
    "R1-BACKEND": "IMPLEMENTED",
    "R1-SPA": "IMPLEMENTED",
    "R1-E2E-GOLDEN": "RUNTIME_VERIFIED",
    "R1-E2E-FAILURES": "RUNTIME_VERIFIED",
}
STATE_RANK = {
    "DRAFT": 0,
    "FROZEN": 1,
    "MERGED": 2,
    "IMPLEMENTED": 3,
    "RUNTIME_VERIFIED": 4,
}
R2_STATE_REQUIREMENTS = {
    "DB-52P2-PG18-RUNTIME": "RUNTIME_VERIFIED",
    "BASE-CLOSURE-DESIGN": "MERGED",
    "BASE-PR2-CLOSURE-PLAN": "MERGED",
    "BASE-CURRENT-MVP": "MERGED",
}
STRUCTURED_EVIDENCE_ROOT = Path("docs/evidence/ledger")
STRUCTURED_RUNTIME_EVIDENCE_ROOT = Path("docs/evidence/schema-runtime")
IMPLEMENTED_EVIDENCE_CONTRACTS = {
    "R1-OPENAPI": (
        Path("contracts/openapi/ontology-law-api.yaml"),
        Path("backend/src/test/java/io/github/windyzhu3/ontologylaw/api/OpenApiContractTest.java"),
    ),
}
IMPLEMENTED_TEST_CONTRACTS = {
    "R1-BACKEND": Path("backend/src/test/java/io/github/windyzhu3/ontologylaw/api/R1ApiIT.java"),
    "R1-SPA": Path("apps/workbench/src/features/workcard/CurrentCard.test.tsx"),
}
IMPLEMENTED_DELIVERY_ROWS = {
    row_id: None
    for row_id in set(IMPLEMENTED_EVIDENCE_CONTRACTS) | set(IMPLEMENTED_TEST_CONTRACTS)
}
RUNTIME_COMMAND_CONTRACTS = {
    "DB-52P2-PG18-RUNTIME": (
        "python3 runtime/verify_runtime.py verify --ci-only --runs 2 --evidence-dir ../../.artifacts/schema-runtime",
        Path("database/schema-contract-52-plus-2/runtime/verify_runtime.py"),
    ),
    "R1-E2E-GOLDEN": (
        "npx playwright test e2e/tests/r1-golden-path.spec.ts",
        Path("e2e/tests/r1-golden-path.spec.ts"),
    ),
    "R1-E2E-FAILURES": (
        "npx playwright test e2e/tests/r1-failure-paths.spec.ts",
        Path("e2e/tests/r1-failure-paths.spec.ts"),
    ),
}


def read_text(root: Path, relative_path: Path) -> str | None:
    path = root / relative_path
    if not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        raise InvalidUtf8GovernedFile(relative_path) from None


def read_repository_file(root: Path, path: Path) -> str:
    root_path = root.resolve()
    try:
        relative_path = path.relative_to(root_path)
    except ValueError:
        relative_path = path
    text = read_text(root_path, relative_path)
    if text is None:
        raise FileNotFoundError(path)
    return text


def masked_markdown_line(line: str) -> str:
    return "".join(character if character in "\r\n" else " " for character in line)


def active_markdown_text(text: str) -> str:
    """Mask fenced code while preserving all character offsets and line endings."""
    active_lines: list[str] = []
    fence_character: str | None = None
    fence_length = 0

    for line in text.splitlines(keepends=True):
        content = line.rstrip("\r\n")
        if fence_character is not None:
            closing = re.fullmatch(
                rf" {{0,3}}{re.escape(fence_character)}{{{fence_length},}}[ \t]*",
                content,
            )
            active_lines.append(masked_markdown_line(line))
            if closing is not None:
                fence_character = None
                fence_length = 0
            continue

        opening = FENCE_OPEN_PATTERN.fullmatch(content)
        if opening is not None:
            fence_run = opening.group(1)
            info_string = opening.group(2)
            if fence_run[0] != "`" or "`" not in info_string:
                fence_character = fence_run[0]
                fence_length = len(fence_run)
                active_lines.append(masked_markdown_line(line))
                continue
        active_lines.append(line)

    return "".join(active_lines)


def validate_general_markdown_target(
    root: Path, base: Path, target: str
) -> tuple[bool, Path | None]:
    if not target or len(target) > MAX_LINK_TARGET_LENGTH:
        return False, None
    try:
        decoded_target = unquote(html.unescape(target), errors="strict")
        parsed = urlsplit(decoded_target)
    except (UnicodeError, ValueError):
        return False, None
    if (
        len(decoded_target) > MAX_LINK_TARGET_LENGTH
        or has_control_character(decoded_target)
    ):
        return False, None
    if decoded_target.startswith("#"):
        return True, None

    scheme = parsed.scheme.casefold()
    if scheme in {"http", "https"}:
        return bool(parsed.netloc), None
    if scheme == "mailto":
        return bool(parsed.path), None
    if scheme or parsed.netloc or parsed.query:
        return False, None

    target_path = parsed.path
    if (
        not target_path
        or "\\" in target_path
        or Path(target_path).is_absolute()
        or PureWindowsPath(target_path).drive
        or PureWindowsPath(target_path).is_absolute()
    ):
        return False, None

    try:
        root_path = root.resolve(strict=True)
        candidate = Path(os.path.normpath(root_path / base / target_path))
        relative = candidate.relative_to(root_path)
    except (OSError, RuntimeError, ValueError):
        return False, None
    if not relative.parts or ".git" in relative.parts:
        return False, relative

    current = root_path
    try:
        for part in relative.parts:
            current = current / part
            if stat.S_ISLNK(current.lstat().st_mode):
                return False, relative
        is_regular = stat.S_ISREG(current.stat(follow_symlinks=False).st_mode)
    except (OSError, ValueError):
        return False, relative
    return is_regular, relative


def iter_controlled_markdown_paths(root: Path) -> list[Path]:
    controlled_paths: list[Path] = []
    for fixed_path in (
        README,
        CANONICAL_BASELINE,
        CLOSURE_SPEC,
        DELIVERY_LEDGER,
        R1_PLAN,
        R1_SCAFFOLD_ADR,
        R1_TASK_CONTRACT,
        R1_HTTP_CONTRACT,
        R1_WORKBENCH_CONTRACT,
        *VISUAL_INDEXES,
    ):
        if (root / fixed_path).is_file():
            controlled_paths.append(fixed_path)
    specs_root = root / "docs/specs"
    if specs_root.is_dir():
        controlled_paths.extend(sorted(path.relative_to(root) for path in specs_root.rglob("*.md")))
    return controlled_paths


def verify_relative_markdown_links(
    root: Path, findings: list[str], missing_controlled_paths: set[Path]
) -> None:
    for relative_path in iter_controlled_markdown_paths(root):
        if relative_path == DELIVERY_LEDGER:
            # Ledger evidence is parsed below so its stricter per-row rules apply once.
            continue
        text = read_text(root, relative_path)
        if text is None:
            continue
        for raw_target in MARKDOWN_LINK_PATTERN.findall(
            active_markdown_text(text)
        ):
            target = raw_target.strip()
            if (
                relative_path in VISUAL_INDEXES
                and Path(target).suffix.lower() == ".png"
            ):
                # The frozen visual-index verifier applies the stricter exact-set
                # contract to PNG links without duplicating its findings here.
                continue
            is_valid, resolved_relative = validate_general_markdown_target(
                root, relative_path.parent, target
            )
            if resolved_relative in missing_controlled_paths:
                continue
            if not is_valid:
                findings.append(
                    f"Broken relative Markdown link in {relative_path}: {target}"
                )


def verify_canonical_baseline(root: Path, findings: list[str]) -> str | None:
    baseline_text = read_text(root, CANONICAL_BASELINE)
    if baseline_text is None:
        findings.append(f"Missing canonical baseline: {CANONICAL_BASELINE.as_posix()}")
        return None
    if f"Baseline ID: {CANONICAL_BASELINE_ID}" not in baseline_text:
        findings.append(
            f"Current baseline must declare Baseline ID: {CANONICAL_BASELINE_ID}"
        )
    return baseline_text


def verify_closure_spec(findings: list[str], closure_spec_text: str | None) -> None:
    if closure_spec_text is None or "状态：已确认（`FROZEN`）" not in closure_spec_text:
        findings.append(
            "Closure spec must be approved and frozen: "
            f"{CLOSURE_SPEC.as_posix()}"
        )


def verify_readme(findings: list[str], readme_text: str | None) -> None:
    link_target = CANONICAL_BASELINE.as_posix()
    link_count = 0
    all_links: list[str] = []
    if readme_text is not None:
        readme_text = active_markdown_text(readme_text)
        all_links = [
            raw_target.split("#", 1)[0].strip()
            for raw_target in MARKDOWN_LINK_PATTERN.findall(readme_text)
        ]
        link_count = sum(
            1
            for target in all_links
            if target == link_target
        )
    if link_count != 1:
        findings.append(f"README must link {link_target} exactly once")
    elif all_links and all_links[0] != link_target:
        findings.append(
            f"README first Markdown link must point to {link_target}"
        )


def find_preamble_after_h1(text: str) -> str:
    text = active_markdown_text(text)
    headings = list(HEADING_PATTERN.finditer(text))
    h1_match = next((match for match in headings if len(match.group(1)) == 1), None)
    if h1_match is None:
        return ""

    for later_match in headings:
        if later_match.start() <= h1_match.start():
            continue
        return text[h1_match.end() : later_match.start()]
    return text[h1_match.end() :]


def normalize_heading(heading_text: str) -> str:
    return " ".join(heading_text.strip().lower().split())


def extract_markdown_section(text: str, exact_heading: str) -> str | None:
    active_text = active_markdown_text(text)
    headings = list(HEADING_PATTERN.finditer(active_text))
    normalized_exact = normalize_heading(exact_heading)

    for index, match in enumerate(headings):
        level = len(match.group(1))
        heading_text = match.group(2).strip()
        normalized_heading = normalize_heading(heading_text)
        if normalized_heading != normalized_exact:
            continue

        section_start = match.start()
        section_end = len(text)
        for later_match in headings[index + 1 :]:
            later_level = len(later_match.group(1))
            if later_level <= level:
                section_end = later_match.start()
                break
        return text[section_start:section_end]

    return None


def verify_historical_specs(root: Path, findings: list[str]) -> None:
    specs_root = root / "docs/specs"
    if not specs_root.is_dir():
        return
    for path in sorted(specs_root.rglob("*.md")):
        relative_path = path.relative_to(root)
        text = read_text(root, relative_path)
        if text is None:
            continue
        active_text = active_markdown_text(text)
        preamble = find_preamble_after_h1(text)
        if HISTORICAL_BANNER not in preamble:
            findings.append(
                f"Historical spec missing superseded banner: {relative_path.as_posix()}"
            )
            continue
        if HISTORICAL_WARNING not in preamble:
            findings.append(
                "Historical spec must preserve the exact superseded warning block: "
                f"{relative_path.as_posix()}"
            )
            continue
        for heading, replacement_section in CONFLICT_SECTIONS.get(
            relative_path, {}
        ).items():
            expected = (
                f"{heading}\n"
                "superseded-by: docs/baseline/CURRENT-MVP-BASELINE.md\n"
                f"replacement-section: {replacement_section}"
            )
            if expected not in active_text:
                findings.append(
                    "Historical spec conflict heading lacks exact supersession metadata: "
                    f"{relative_path.as_posix()} :: {heading}"
                )
        if HISTORICAL_REPLACEMENT_HEADING not in active_text:
            findings.append(
                "Historical spec must use the replacement appendix heading: "
                f"{relative_path.as_posix()}"
            )


def expected_visual_index_assets(index_root: Path) -> set[str]:
    ledger_relative_root = Path("docs/progress")
    expected: set[str] = set()
    for target in EXPECTED_VISUAL_ROWS.values():
        repository_path = Path(os.path.normpath(ledger_relative_root / target))
        try:
            expected.add(repository_path.relative_to(index_root).as_posix())
        except ValueError:
            continue
    return expected


def verify_visual_indexes(root: Path, findings: list[str]) -> None:
    for index_path, index_root in VISUAL_INDEXES.items():
        text = read_text(root, index_path)
        if text is None:
            findings.append(f"Missing visual index: {index_path.as_posix()}")
            continue
        preamble = find_preamble_after_h1(text)
        if VISUAL_INDEX_METADATA not in preamble:
            findings.append(
                "Visual index must declare exact frozen bundle metadata: "
                f"{index_path.as_posix()}"
            )
            continue
        png_targets = [
            target
            for target in markdown_links(active_markdown_text(text))
            if Path(target).suffix.lower() == ".png"
        ]
        expected_assets = expected_visual_index_assets(index_root)
        if len(png_targets) != len(expected_assets) or set(png_targets) != expected_assets:
            findings.append(
                "Visual index must list exactly its frozen PNG assets: "
                f"{index_path.as_posix()}"
            )


def verify_waiting_contract(findings: list[str], baseline_text: str | None) -> None:
    if baseline_text is None:
        return
    waiting_section = extract_markdown_section(baseline_text, "task-waiting-contract")
    if (
        waiting_section is None
        or "SYSTEM_RECOVERY" not in active_markdown_text(waiting_section)
    ):
        findings.append(
            "Current baseline must freeze WAITING entry with SYSTEM_RECOVERY: "
            f"{CANONICAL_BASELINE.as_posix()}"
        )


def verify_matter_endpoint(findings: list[str], baseline_text: str | None) -> None:
    if baseline_text is None:
        return
    matter_section = extract_markdown_section(baseline_text, "matter-endpoint")
    normalized_section = normalize_frozen_markdown_section(matter_section or "")
    frozen_section = normalize_frozen_markdown_section(
        MATTER_ENDPOINT_FROZEN_SECTION
    )
    if normalized_section != frozen_section:
        findings.append(
            "Current baseline must freeze Matter endpoint with same-transaction "
            "MatterCreated, no second Matter identity, and no reverse rewrite of "
            "sales history: "
            f"{CANONICAL_BASELINE.as_posix()}"
        )


def normalize_frozen_markdown_section(section: str) -> str:
    normalized_line_endings = section.replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(
        " ".join(line.split())
        for line in normalized_line_endings.split("\n")
        if line.strip()
    )


def markdown_links(text: str) -> list[str]:
    return [target.strip() for target in MARKDOWN_LINK_PATTERN.findall(text)]


def character_is_escaped(text: str, index: int) -> bool:
    backslashes = 0
    index -= 1
    while index >= 0 and text[index] == "\\":
        backslashes += 1
        index -= 1
    return backslashes % 2 == 1


def has_percent_encoding(text: str) -> bool:
    return any(
        text[index] == "%"
        and index + 2 < len(text)
        and text[index + 1] in HEX_DIGITS
        and text[index + 2] in HEX_DIGITS
        for index in range(len(text))
    )


def has_control_character(text: str) -> bool:
    return any(
        unicodedata.category(character) in {"Cc", "Cf"}
        for character in text
    )


def unescape_markdown_punctuation(text: str) -> str:
    unescaped: list[str] = []
    index = 0
    while index < len(text):
        if (
            text[index] == "\\"
            and index + 1 < len(text)
            and text[index + 1] in string.punctuation
        ):
            unescaped.append(text[index + 1])
            index += 2
            continue
        unescaped.append(text[index])
        index += 1
    return "".join(unescaped)


def parse_exact_markdown_link(field: str) -> str | None:
    if not field.startswith("["):
        return None

    closing_label = None
    for index in range(1, len(field)):
        character = field[index]
        if character == "[" and not character_is_escaped(field, index):
            return None
        if character == "]" and not character_is_escaped(field, index):
            closing_label = index
            break
    if closing_label is None or closing_label == 1:
        return None
    if field[closing_label + 1:closing_label + 2] != "(" or not field.endswith(")"):
        return None

    label = field[1:closing_label]
    rendered_label = unescape_markdown_punctuation(label)
    if (
        not label.strip()
        or has_control_character(label)
        or html.unescape(label) != label
        or any(character in label for character in MARKDOWN_INLINE_LABEL_DELIMITERS)
        or "merge-commit=" in rendered_label.casefold()
    ):
        return None

    destination = field[closing_label + 2:-1]
    try:
        decoded_destination = unquote(html.unescape(destination), errors="strict")
    except (UnicodeError, ValueError):
        return None
    if (
        not destination
        or len(destination) > MAX_LINK_TARGET_LENGTH
        or len(decoded_destination) > MAX_LINK_TARGET_LENGTH
        or any(
            character.isspace()
            for character in destination
        )
        or has_control_character(destination)
        or decoded_destination != destination
        or any(character in destination for character in "()`\\<>")
        or "merge-commit=" in destination.casefold()
        or has_percent_encoding(destination)
    ):
        return None
    try:
        parsed = urlsplit(destination)
    except (UnicodeError, ValueError):
        return None
    if (
        parsed.scheme
        or parsed.netloc
        or parsed.path != destination
        or not parsed.path
        or Path(parsed.path).is_absolute()
        or PureWindowsPath(parsed.path).drive
        or PureWindowsPath(parsed.path).is_absolute()
    ):
        return None
    return destination


def parse_exact_markdown_link_list(field: str) -> list[str] | None:
    fields = field.split("; ")
    if not fields:
        return None
    links: list[str] = []
    for link_field in fields:
        destination = parse_exact_markdown_link(link_field)
        if destination is None:
            return None
        links.append(destination)
    return links


def parse_merged_evidence(evidence: str) -> tuple[list[str], str] | None:
    fields = evidence.split("; ")
    if len(fields) < 2 or evidence.casefold().count("merge-commit=") != 1:
        return None

    marker = fields[-1]
    if not marker.startswith(MERGE_MARKER_PREFIX) or not marker.endswith("`"):
        return None
    commit = marker[len(MERGE_MARKER_PREFIX):-1]
    if (
        not 7 <= len(commit) <= 40
        or any(character not in LOWER_HEX_DIGITS for character in commit)
    ):
        return None

    links: list[str] = []
    for field in fields[:-1]:
        destination = parse_exact_markdown_link(field)
        if destination is None:
            return None
        links.append(destination)
    return links, commit


def resolve_in_repository_ledger_link(root: Path, target: str) -> Path | None:
    return resolve_in_repository_link(root, DELIVERY_LEDGER.parent, target)


def local_link_path(target: str) -> str | None:
    if len(target) > MAX_LINK_TARGET_LENGTH or has_control_character(target):
        return None
    try:
        decoded_target = unquote(html.unescape(target), errors="strict")
        parsed = urlsplit(target)
    except (UnicodeError, ValueError):
        return None
    if len(decoded_target) > MAX_LINK_TARGET_LENGTH or decoded_target != target:
        return None
    if parsed.scheme or parsed.netloc:
        return None
    if parsed.query or parsed.fragment or parsed.path != target:
        return None
    return parsed.path


def resolve_safe_repository_file(
    root: Path, base: Path, target: str
) -> tuple[Path, Path] | None:
    target_path = local_link_path(target)
    if (
        not target_path
        or Path(target_path).is_absolute()
        or PureWindowsPath(target_path).drive
        or PureWindowsPath(target_path).is_absolute()
        or "://" in target_path
        or target_path.startswith("mailto:")
        or "\\" in target_path
    ):
        return None
    try:
        root_path = root.resolve(strict=True)
        candidate = Path(os.path.normpath(root_path / base / target_path))
        relative = candidate.relative_to(root_path)
    except (OSError, RuntimeError, ValueError):
        return None
    if not relative.parts or ".git" in relative.parts:
        return None

    current = root_path
    try:
        for part in relative.parts:
            current = current / part
            if stat.S_ISLNK(current.lstat().st_mode):
                return None
        if not stat.S_ISREG(current.stat(follow_symlinks=False).st_mode):
            return None
    except (OSError, ValueError):
        return None

    tracked = git_command(
        root_path, "ls-files", "--error-unmatch", "--", relative.as_posix()
    )
    if tracked.returncode != 0:
        return None
    return current, relative


def resolve_in_repository_link(root: Path, base: Path, target: str) -> Path | None:
    resolved = resolve_safe_repository_file(root, base, target)
    return resolved[0] if resolved is not None else None


def existing_relative_ledger_links(root: Path, links: list[str]) -> list[Path] | None:
    if not links:
        return None
    resolved_paths: list[Path] = []
    for target in links:
        resolved = resolve_in_repository_ledger_link(root, target)
        if resolved is None:
            return None
        resolved_paths.append(resolved)
    return resolved_paths


def is_markdown_delimiter_row(line: str, width: int) -> bool:
    cells = [cell.strip() for cell in line.strip("|").split("|")]
    return len(cells) == width and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells)


def parse_controlled_markdown_table(
    text: str,
    heading: str,
    headers: tuple[str, ...],
    label: str,
    findings: list[str],
) -> list[dict[str, str]] | None:
    section = extract_markdown_section(text, heading)
    if section is None:
        findings.append(f"{label} missing section: {heading}")
        return None
    lines = active_markdown_text(section).splitlines()
    intended = [
        index
        for index, line in enumerate(lines)
        if line.strip().startswith("|")
        and line.strip().endswith("|")
        and tuple(cell.strip() for cell in line.strip().strip("|").split("|"))
        == headers
    ]
    if len(intended) != 1:
        findings.append(f"{label} must contain exactly one {heading} table")
        return None
    header_index = intended[0]
    if header_index + 1 >= len(lines) or not is_markdown_delimiter_row(
        lines[header_index + 1].strip(), len(headers)
    ):
        findings.append(f"{label} table delimiter is invalid: {heading}")
        return None
    rows: list[dict[str, str]] = []
    row_index = header_index + 2
    while (
        row_index < len(lines)
        and lines[row_index].strip().startswith("|")
        and lines[row_index].strip().endswith("|")
    ):
        cells = [cell.strip() for cell in lines[row_index].strip().strip("|").split("|")]
        if len(cells) != len(headers) or any(not cell for cell in cells):
            findings.append(f"{label} row at line {row_index + 1} is incomplete: {heading}")
            return None
        rows.append(dict(zip(headers, cells, strict=True)))
        row_index += 1
    if not rows:
        findings.append(f"{label} table has no rows: {heading}")
        return None
    return rows


def unique_rows_by(
    rows: list[dict[str, str]],
    key: str,
    label: str,
    findings: list[str],
) -> dict[str, dict[str, str]] | None:
    indexed: dict[str, dict[str, str]] = {}
    for row in rows:
        value = row[key]
        if value in indexed:
            findings.append(f"{label} duplicate {key}: {value}")
            return None
        indexed[value] = row
    return indexed


def verify_r1_contracts(root: Path, findings: list[str]) -> None:
    governed = {
        R1_PLAN: read_text(root, R1_PLAN),
        R1_SCAFFOLD_ADR: read_text(root, R1_SCAFFOLD_ADR),
        R1_TASK_CONTRACT: read_text(root, R1_TASK_CONTRACT),
        R1_HTTP_CONTRACT: read_text(root, R1_HTTP_CONTRACT),
        R1_WORKBENCH_CONTRACT: read_text(root, R1_WORKBENCH_CONTRACT),
    }
    for path, text in governed.items():
        if text is None:
            findings.append(f"Missing frozen R1 contract: {path.as_posix()}")
            return
    plan_text = governed[R1_PLAN]
    adr_text = governed[R1_SCAFFOLD_ADR]
    task_text = governed[R1_TASK_CONTRACT]
    http_text = governed[R1_HTTP_CONTRACT]
    workbench_text = governed[R1_WORKBENCH_CONTRACT]
    assert plan_text is not None
    assert adr_text is not None
    assert task_text is not None
    assert http_text is not None
    assert workbench_text is not None
    if field_value(plan_text, "Status") != "FROZEN":
        findings.append("R1 implementation plan must declare Status: FROZEN")
        return
    metadata = (
        (task_text, "R1-TASK-COMPLETION-V1", "R1 task contract"),
        (http_text, "R1-HTTP-V1", "R1 HTTP contract"),
        (workbench_text, "R1-WORKBENCH-V1", "R1 workbench contract"),
    )
    for text, expected_id, label in metadata:
        if field_value(text, "Contract ID") != expected_id or field_value(text, "Status") != "FROZEN":
            findings.append(f"{label} must declare Contract ID {expected_id} and Status FROZEN")
            return
    if field_value(adr_text, "Contract ID") != "R1-SCAFFOLD-V1" or field_value(
        adr_text, "Status"
    ) != "Accepted":
        findings.append("ADR-0004 must declare Contract ID R1-SCAFFOLD-V1 and Status Accepted")
        return

    task_headers = (
        "TaskType",
        "BusinessPurpose",
        "SubjectSelector",
        "OwnerAuthoritySlot",
        "PrimaryCommand",
        "PayloadSchema",
        "CompletionFactType",
        "CompletionBinding",
        "NaturalIdempotencyKey",
        "LockRoot",
        "SLA",
    )
    task_rows = parse_controlled_markdown_table(
        task_text, "Task registry", task_headers, "R1 task registry", findings
    )
    if task_rows is None:
        return
    tasks = unique_rows_by(task_rows, "TaskType", "R1 task registry", findings)
    if tasks is None:
        return
    for task_type in sorted(R1_REQUIRED_TASK_TYPES - tasks.keys()):
        findings.append(f"R1 task registry missing TaskType: {task_type}")
        return
    for task_type in sorted(tasks.keys() - R1_REQUIRED_TASK_TYPES):
        findings.append(f"R1 task registry uses unknown TaskType: {task_type}")
        return
    commands: set[str] = set()
    for task_type, row in tasks.items():
        command = row["PrimaryCommand"]
        if command in commands:
            findings.append(f"R1 task registry duplicate PrimaryCommand: {command}")
            return
        commands.add(command)
        if row["CompletionFactType"] in {"—", "-", "NONE"}:
            findings.append(
                f"R1 task registry TaskType {task_type} has no bound CompletionFactType"
            )
            return
        if (command, row["CompletionFactType"]) != R1_TASK_CONTRACTS[task_type]:
            findings.append(f"R1 task registry TaskType {task_type} differs from its frozen command/Fact binding")
            return

    branch_headers = (
        "BranchID",
        "TaskType",
        "OutcomeCode",
        "ReceiptResult",
        "CompletionFactType",
        "CompletionBinding",
        "EventType",
        "QueueOwner",
        "AllowedSuccessorTaskTypes",
        "SuccessorPolicy",
        "SuccessorOwnerSlot",
    )
    branch_rows = parse_controlled_markdown_table(
        task_text,
        "Completion branches",
        branch_headers,
        "R1 completion branches",
        findings,
    )
    if branch_rows is None:
        return
    branches = unique_rows_by(branch_rows, "BranchID", "R1 completion branch", findings)
    if branches is None:
        return
    for branch_id in sorted(R1_REQUIRED_BRANCHES.keys() - branches.keys()):
        findings.append(f"R1 completion branches missing BranchID: {branch_id}")
        return
    for branch_id in sorted(branches.keys() - R1_REQUIRED_BRANCHES.keys()):
        findings.append(f"R1 completion branches use unknown BranchID: {branch_id}")
        return
    for branch_id, row in branches.items():
        required_task, required_outcome = R1_REQUIRED_BRANCHES[branch_id]
        if (row["TaskType"], row["OutcomeCode"]) != (required_task, required_outcome):
            findings.append(f"R1 completion branch {branch_id} has inconsistent task or outcome")
            return
        if row["ReceiptResult"] not in R1_RECEIPT_RESULTS:
            findings.append(
                f"R1 completion branch {branch_id} uses unknown ReceiptResult: {row['ReceiptResult']}"
            )
            return
        if row["CompletionFactType"] != tasks[required_task]["CompletionFactType"]:
            findings.append(f"R1 completion branch {branch_id} is not bound to its Task completion Fact")
            return
        branch_detail = (
            row["ReceiptResult"],
            row["CompletionBinding"],
            row["EventType"],
            row["QueueOwner"],
        )
        if branch_detail != R1_BRANCH_DETAIL_CONTRACTS[branch_id]:
            findings.append(
                f"R1 completion branch {branch_id} differs from its frozen branch contract"
            )
            return
        successor_contract = (
            row["AllowedSuccessorTaskTypes"],
            row["SuccessorPolicy"],
            row["SuccessorOwnerSlot"],
        )
        if successor_contract != R1_SUCCESSOR_CONTRACTS[branch_id]:
            findings.append(
                f"R1 completion branch {branch_id} differs from its frozen successor contract"
            )
            return

    e2e_headers = (
        "ScenarioID",
        "BranchID",
        "FactDelta",
        "TaskDelta",
        "SuccessorDelta",
        "ReceiptEventOutboxAudit",
        "IsolationRollback",
    )
    e2e_rows = parse_controlled_markdown_table(
        task_text, "E2E deltas", e2e_headers, "R1 E2E deltas", findings
    )
    if e2e_rows is None:
        return
    scenarios = unique_rows_by(e2e_rows, "ScenarioID", "R1 E2E scenario", findings)
    if scenarios is None:
        return
    if set(scenarios) != set(R1_E2E_CONTRACTS):
        findings.append("R1 E2E deltas must contain the exact frozen ScenarioID set")
        return
    for scenario_id, row in scenarios.items():
        actual = (
            row["BranchID"],
            row["FactDelta"],
            row["TaskDelta"],
            row["SuccessorDelta"],
            row["ReceiptEventOutboxAudit"],
            row["IsolationRollback"],
        )
        if actual != R1_E2E_CONTRACTS[scenario_id]:
            findings.append(
                f"R1 E2E scenario differs from frozen delta contract: {scenario_id}"
            )
            return
    e2e_branches = [row["BranchID"] for row in e2e_rows]
    if set(e2e_branches) != set(branches):
        findings.append("R1 E2E deltas must cover every completion BranchID")
        return

    operation_headers = (
        "OperationId",
        "Method",
        "Path",
        "TenantSource",
        "IdempotencyKey",
        "Preconditions",
        "SubjectBinding",
        "SuccessStatus",
        "ErrorCodes",
    )
    operation_rows = parse_controlled_markdown_table(
        http_text, "Operations", operation_headers, "R1 HTTP operations", findings
    )
    if operation_rows is None:
        return
    operations = unique_rows_by(operation_rows, "OperationId", "R1 HTTP operation", findings)
    if operations is None:
        return
    if set(operations) != set(R1_OPERATION_CONTRACTS):
        findings.append("R1 HTTP operations must contain the exact frozen OperationId set")
        return
    for operation_id, row in operations.items():
        method, path, idempotency, preconditions, subject_binding, success_status = (
            R1_OPERATION_CONTRACTS[operation_id]
        )
        if (
            row["Method"],
            row["Path"],
            row["IdempotencyKey"],
            row["Preconditions"],
            row["SubjectBinding"],
            row["SuccessStatus"],
        ) != (method, path, idempotency, preconditions, subject_binding, success_status):
            findings.append(f"R1 HTTP operation {operation_id} differs from its frozen contract")
            return
        if row["TenantSource"] != "ACTOR_CONTEXT":
            findings.append(f"R1 HTTP operation {operation_id} must derive tenant from ACTOR_CONTEXT")
            return

    idempotency_headers = ("Property", "FrozenValue")
    idempotency_rows = parse_controlled_markdown_table(
        http_text,
        "Idempotency binding",
        idempotency_headers,
        "R1 HTTP idempotency binding",
        findings,
    )
    if idempotency_rows is None:
        return
    idempotency = unique_rows_by(
        idempotency_rows, "Property", "R1 HTTP idempotency binding", findings
    )
    if idempotency is None:
        return
    if {key: row["FrozenValue"] for key, row in idempotency.items()} != R1_IDEMPOTENCY_BINDING:
        findings.append(
            "R1 HTTP idempotency binding differs from the frozen command slot contract"
        )
        return

    error_headers = (
        "ErrorCode",
        "HttpStatus",
        "RetryPolicy",
        "FieldErrors",
        "CurrentETag",
        "SafeText",
    )
    error_rows = parse_controlled_markdown_table(
        http_text, "Error registry", error_headers, "R1 HTTP error registry", findings
    )
    if error_rows is None:
        return
    errors = unique_rows_by(error_rows, "ErrorCode", "R1 HTTP error registry", findings)
    if errors is None:
        return
    if set(errors) != set(R1_ERROR_CONTRACTS):
        findings.append("R1 HTTP error registry must contain the exact frozen ErrorCode set")
        return
    for error_code, expected in R1_ERROR_CONTRACTS.items():
        row = errors[error_code]
        if (
            row["HttpStatus"],
            row["RetryPolicy"],
            row["FieldErrors"],
            row["CurrentETag"],
        ) != expected:
            findings.append(f"R1 HTTP error registry row differs from frozen contract: {error_code}")
            return
        if row["SafeText"] in {"—", "-", "NONE", "TBD"}:
            findings.append(f"R1 HTTP error registry lacks safe text: {error_code}")
            return
    for operation_id, row in operations.items():
        operation_errors = {code.strip() for code in row["ErrorCodes"].split(",")}
        for error_code in operation_errors:
            if error_code not in errors:
                findings.append(
                    f"R1 HTTP operation {operation_id} references unknown ErrorCode: {error_code}"
                )
                return
        if operation_errors != R1_OPERATION_ERRORS[operation_id]:
            findings.append(
                f"R1 HTTP operation {operation_id} differs from its frozen ErrorCode set"
            )
            return
    envelope_headers = ("Field", "Cardinality", "Contract")
    envelope_rows = parse_controlled_markdown_table(
        workbench_text,
        "Envelope fields",
        envelope_headers,
        "R1 workbench envelope",
        findings,
    )
    if envelope_rows is None:
        return
    envelope = unique_rows_by(envelope_rows, "Field", "R1 workbench envelope", findings)
    if envelope is None or {key: row["Cardinality"] for key, row in envelope.items()} != R1_WORKBENCH_FIELDS:
        if envelope is not None:
            findings.append("R1 workbench envelope differs from the frozen field/cardinality set")
        return
    route_headers = ("RouteMode", "PathPattern", "Navigation", "Sidebar")
    route_rows = parse_controlled_markdown_table(
        workbench_text, "Route modes", route_headers, "R1 workbench routes", findings
    )
    if route_rows is None:
        return
    routes = unique_rows_by(route_rows, "RouteMode", "R1 workbench route", findings)
    if routes is None:
        return
    route_values = {
        key: (row["PathPattern"], row["Navigation"], row["Sidebar"])
        for key, row in routes.items()
    }
    if route_values != R1_ROUTE_MODES:
        findings.append("R1 workbench route modes differ from the frozen contract")
        return

    decision_headers = ("Decision", "FrozenCode", "FrozenValue")
    decision_rows = parse_controlled_markdown_table(
        adr_text, "Controlled decisions", decision_headers, "ADR-0004 decisions", findings
    )
    if decision_rows is None:
        return
    decisions = unique_rows_by(decision_rows, "Decision", "ADR-0004 decision", findings)
    if decisions is None or set(decisions) != set(R1_SCAFFOLD_DECISIONS):
        if decisions is not None:
            findings.append("ADR-0004 must contain the exact controlled decision set")
        return
    for decision, row in decisions.items():
        frozen_code, frozen_value = R1_SCAFFOLD_DECISIONS[decision]
        if row["FrozenCode"] != frozen_code:
            findings.append(f"ADR-0004 decision differs from frozen code: {decision}")
            return
        if row["FrozenValue"] != frozen_value:
            findings.append(f"ADR-0004 decision differs from frozen value: {decision}")
            return
        if re.search(r"(?i)\b(?:TBD|TODO|latest|dynamic)\b", row["FrozenValue"]):
            findings.append(f"ADR-0004 decision is not frozen: {decision}")
            return


def parse_delivery_ledger(ledger_text: str, findings: list[str]) -> list[dict[str, str]] | None:
    lines = active_markdown_text(ledger_text).splitlines()
    table_starts = [
        index for index, line in enumerate(lines)
        if line.strip().startswith("|") and line.strip().endswith("|")
    ]
    if not table_starts:
        findings.append("Delivery ledger must contain a header and at least one row")
        return None

    intended_starts = [
        index for index in table_starts
        if [cell.strip() for cell in lines[index].strip().strip("|").split("|")] == LEDGER_COLUMNS
    ]
    header_index = intended_starts[0] if intended_starts else table_starts[0]
    headers = [cell.strip() for cell in lines[header_index].strip().strip("|").split("|")]
    missing_columns = [column for column in LEDGER_COLUMNS if column not in headers]
    if missing_columns:
        findings.append("Delivery ledger missing required columns: " + ", ".join(missing_columns))
        return None
    if len(headers) != len(LEDGER_COLUMNS) or len(set(headers)) != len(headers):
        findings.append("Delivery ledger header must contain each required column exactly once")
        return None
    if len(intended_starts) > 1:
        findings.append("Delivery ledger must contain exactly one intended table")
        return None
    if header_index + 1 >= len(lines) or not is_markdown_delimiter_row(
        lines[header_index + 1].strip(), len(headers)
    ):
        findings.append("Delivery ledger table delimiter row is invalid")
        return None

    rows: list[dict[str, str]] = []
    row_index = header_index + 2
    while row_index < len(lines) and lines[row_index].strip().startswith("|") and lines[row_index].strip().endswith("|"):
        line_number = row_index + 1
        row = lines[row_index].strip()
        cells = [cell.strip() for cell in row.strip("|").split("|")]
        if len(cells) != len(headers):
            findings.append(f"Delivery ledger row at line {line_number} must contain {len(headers)} columns")
            return None
        parsed = dict(zip(headers, cells, strict=True))
        if any(not parsed[column] for column in LEDGER_COLUMNS):
            findings.append(f"Delivery ledger row at line {line_number} must populate every required column")
            return None
        rows.append(parsed)
        row_index += 1
    if not rows:
        findings.append("Delivery ledger must contain a header and at least one row")
        return None
    later_heading_seen = False
    for line in lines[row_index:]:
        if HEADING_PATTERN.match(line):
            later_heading_seen = True
            continue
        if line.strip().startswith("|") and line.strip().endswith("|") and not later_heading_seen:
            findings.append("Delivery ledger table data rows must be contiguous")
            return None
    return rows


def superseded_by(row: dict[str, str]) -> str | None:
    value = row["Superseded by"].strip()
    return None if value in {"—", "-"} else value


def is_test_path(path: Path) -> bool:
    lower = path.as_posix().lower()
    return "/tests/" in lower or "/test_" in lower or "/src/test/" in lower or ".test." in lower


def is_production_source_path(root: Path, path: Path) -> bool:
    relative = path.relative_to(root.resolve()).as_posix().lower()
    return (
        path.is_file()
        and not is_test_path(path)
        and not relative.startswith("docs/")
        and "/plan" not in relative
        and not relative.endswith(".md")
    )


def component_root(root: Path, path: Path) -> str:
    return path.relative_to(root.resolve()).parts[0]


def field_value(text: str, name: str) -> str | None:
    matches = re.findall(
        rf"(?mi)^[ \t]*{re.escape(name)}[ \t]*:[ \t]*(\S[^\r\n]*)$",
        active_markdown_text(text),
    )
    return matches[0].strip() if len(matches) == 1 else None


def controlled_evidence_records(root: Path, links: list[str]) -> list[Path]:
    records: list[Path] = []
    for link in links:
        path = resolve_in_repository_ledger_link(root, link)
        if path is None or not path.is_file():
            continue
        relative = path.relative_to(root.resolve())
        if (
            relative.is_relative_to(STRUCTURED_EVIDENCE_ROOT)
            or relative.is_relative_to(STRUCTURED_RUNTIME_EVIDENCE_ROOT)
        ):
            records.append(path)
    return records


def record_links(root: Path, record: Path, field: str) -> list[Path]:
    value = field_value(read_repository_file(root, record), field)
    if value is None:
        return []
    base = record.relative_to(root.resolve()).parent
    links = parse_exact_markdown_link_list(value)
    if links is None:
        return []
    paths: list[Path] = []
    for link in links:
        path = resolve_in_repository_link(root, base, link)
        if path is None:
            return []
        paths.append(path)
    return paths


def git_command(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=False)


def has_snapshot_merge_evidence(
    root: Path,
    artifact_entries: list[tuple[Path, Path]],
    commit: str,
) -> bool:
    if not artifact_entries:
        return False
    resolved_commit = git_command(root, "rev-parse", "--verify", f"{commit}^{{commit}}")
    if resolved_commit.returncode != 0:
        return False
    commit_oid = resolved_commit.stdout.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", commit_oid) or not commit_oid.startswith(commit):
        return False
    if git_command(root, "merge-base", "--is-ancestor", commit_oid, "HEAD").returncode != 0:
        return False
    for _, relative_path in artifact_entries:
        artifact = relative_path.as_posix()
        tree = git_command(root, "ls-tree", "-z", commit_oid, "--", artifact)
        if tree.returncode != 0:
            return False
        entries = [entry for entry in tree.stdout.split("\0") if entry]
        if len(entries) != 1 or "\t" not in entries[0]:
            return False
        metadata, entry_path = entries[0].split("\t", 1)
        metadata_parts = metadata.split()
        if (
            entry_path != artifact
            or len(metadata_parts) != 3
            or metadata_parts[0] not in {"100644", "100755"}
            or metadata_parts[1] != "blob"
        ):
            return False
    return True


def contract_id(row_id: str, contracts: dict[str, object]) -> str | None:
    return next(
        (candidate for candidate in contracts if row_id == candidate or row_id.startswith(f"{candidate}-")),
        None,
    )


def has_concrete_runtime_command(root: Path, row: dict[str, str], command: str | None) -> bool:
    if command is None:
        return False
    try:
        tokens = shlex.split(command)
    except ValueError:
        return False
    if len(tokens) < 2:
        return False
    expected_id = contract_id(row["ID"], RUNTIME_COMMAND_CONTRACTS)
    if expected_id is None:
        return True
    expected_command, target = RUNTIME_COMMAND_CONTRACTS[expected_id]
    return command == expected_command and (root / target).is_file()


def structured_record_for_row(root: Path, row: dict[str, str]) -> Path | None:
    evidence_target = parse_exact_markdown_link(row["Evidence"])
    if evidence_target is None:
        return None
    for record in controlled_evidence_records(root, [evidence_target]):
        text = read_repository_file(root, record)
        if field_value(text, "ID") == row["ID"] and field_value(text, "Version") == row["Version"]:
            return record
    return None


def active_successor(row_id: str, rows_by_id: dict[str, dict[str, str]]) -> str:
    current = row_id
    while (successor := superseded_by(rows_by_id[current])) is not None:
        current = successor
    return current


def verify_delivery_ledger(root: Path, findings: list[str]) -> list[str] | None:
    ledger_text = read_text(root, DELIVERY_LEDGER)
    if ledger_text is None:
        findings.append(f"Missing delivery ledger: {DELIVERY_LEDGER.as_posix()}")
        return
    rows = parse_delivery_ledger(ledger_text, findings)
    if rows is None:
        return

    rows_by_id: dict[str, dict[str, str]] = {}
    for row in rows:
        row_id = row["ID"]
        if row_id in rows_by_id:
            findings.append(f"Delivery ledger duplicate ID: {row_id}")
            return
        rows_by_id[row_id] = row

    merged_evidence_by_id: dict[str, tuple[list[str], str]] = {}
    invalid_visual_merged_evidence: list[str] = []

    for row_id, row in rows_by_id.items():
        state = row["State"]
        if state not in ALLOWED_STATES:
            findings.append(f"Delivery ledger row {row_id} uses unknown state: {state}")
            return
        if row_id in EXPECTED_VISUAL_ROWS and state not in {"FROZEN", "MERGED"}:
            findings.append(
                f"Delivery ledger row {row_id} cannot claim {state}; FROZEN visual evidence is not production code"
            )
            return
        target_gate = row["Target gate"]
        if target_gate not in TARGET_GATE_STATES:
            findings.append(f"Delivery ledger row {row_id} uses unknown target gate: {target_gate}")
            return
        if state not in TARGET_GATE_STATES[target_gate]:
            findings.append(
                f"Delivery ledger row {row_id} state {state} exceeds target gate {target_gate}"
            )
            return

        artifact_links = parse_exact_markdown_link_list(row["Artifact"])
        artifact_entries = (
            [
                entry
                for link in artifact_links
                if (entry := resolve_safe_repository_file(
                    root, DELIVERY_LEDGER.parent, link
                )) is not None
            ]
            if artifact_links is not None
            else []
        )
        if (
            artifact_links is None
            or not artifact_links
            or len(artifact_entries) != len(artifact_links)
        ):
            findings.append(
                f"Delivery ledger row {row_id} Artifact must contain safe Git-tracked in-repository regular-file links"
            )
            return
        merged_evidence = (
            parse_merged_evidence(row["Evidence"])
            if state == "MERGED"
            else None
        )
        if merged_evidence is not None:
            merged_evidence_by_id[row_id] = merged_evidence
        evidence_links = (
            merged_evidence[0]
            if merged_evidence is not None
            else markdown_links(row["Evidence"])
        )
        non_png_links = [
            link for link in evidence_links
            if (link_path := local_link_path(link)) is None or Path(link_path).suffix.lower() != ".png"
        ]
        links_requiring_resolution = (
            non_png_links if row_id in EXPECTED_VISUAL_ROWS else evidence_links
        )
        resolved_evidence = existing_relative_ledger_links(
            root, links_requiring_resolution
        )
        if resolved_evidence is None:
            findings.append(
                f"Delivery ledger row {row_id} Evidence must contain existing in-repository relative Markdown links"
            )
            return
        if state == "MERGED":
            if merged_evidence is None and row_id in EXPECTED_VISUAL_ROWS:
                invalid_visual_merged_evidence.append(row_id)
            elif merged_evidence is None or not has_snapshot_merge_evidence(
                root, artifact_entries, merged_evidence[1]
            ):
                findings.append(
                    f"Delivery ledger row {row_id} MERGED evidence must cite an ancestor commit containing its Artifact"
                )
                return
        requires_implementation_evidence = (
            state in {"IMPLEMENTED", "RUNTIME_VERIFIED"}
            and contract_id(row_id, IMPLEMENTED_DELIVERY_ROWS) is not None
        )
        if requires_implementation_evidence:
            record = structured_record_for_row(root, row)
            if record is None:
                findings.append(
                    f"Delivery ledger row {row_id} {state} evidence must link a structured row-bound evidence record"
                )
                return
            artifact_paths = [
                path for path, _ in artifact_entries
                if is_production_source_path(root, path)
            ]
            record_artifacts = record_links(root, record, "Artifact")
            record_tests = record_links(root, record, "Test")
            expected_id = contract_id(row_id, IMPLEMENTED_EVIDENCE_CONTRACTS)
            if expected_id is not None:
                expected_artifact, expected_test = IMPLEMENTED_EVIDENCE_CONTRACTS[expected_id]
                evidence_is_valid = (
                    artifact_paths == [root / expected_artifact]
                    and record_artifacts == [root / expected_artifact]
                    and record_tests == [root / expected_test]
                )
            else:
                expected_test_id = contract_id(row_id, IMPLEMENTED_TEST_CONTRACTS)
                expected_tests = (
                    [root / IMPLEMENTED_TEST_CONTRACTS[expected_test_id]]
                    if expected_test_id is not None
                    else None
                )
                evidence_is_valid = (
                    len(artifact_paths) == 1
                    and artifact_paths[0] in record_artifacts
                    and (
                        record_tests == expected_tests
                        if expected_tests is not None
                        else any(is_test_path(path) and path != artifact_paths[0] for path in record_tests)
                    )
                )
            if not evidence_is_valid:
                if state == "RUNTIME_VERIFIED":
                    findings.append(
                        f"Delivery ledger row {row_id} RUNTIME_VERIFIED evidence must also link distinct in-repository production source and test"
                    )
                else:
                    findings.append(
                        f"Delivery ledger row {row_id} IMPLEMENTED evidence must link distinct in-repository production source and test"
                    )
                return
        if state == "RUNTIME_VERIFIED":
            record = structured_record_for_row(root, row)
            if record is None:
                findings.append(
                    f"Delivery ledger row {row_id} RUNTIME_VERIFIED evidence must link a structured row-bound evidence record"
                )
                return
            record_text = read_repository_file(root, record)
            command = field_value(record_text, "Command")
            exit_code = field_value(record_text, "Exit code")
            if not has_concrete_runtime_command(root, row, command) or exit_code != "0":
                findings.append(
                    f"Delivery ledger row {row_id} RUNTIME_VERIFIED evidence lacks concrete version, command, or successful exit code record"
                )
                return

    for row_id, row in rows_by_id.items():
        successor = superseded_by(row)
        if successor is None:
            continue
        if successor not in rows_by_id:
            findings.append(
                f"Delivery ledger row {row_id} Superseded by references missing ID: {successor}"
            )
            return
        if successor == row_id:
            findings.append(f"Delivery ledger row {row_id} cannot supersede itself")
            return
    for row_id in rows_by_id:
        path: list[str] = []
        current = row_id
        while current not in path and superseded_by(rows_by_id[current]) is not None:
            path.append(current)
            current = superseded_by(rows_by_id[current]) or current
        if current in path:
            cycle = path[path.index(current):] + [current]
            findings.append("Delivery ledger Superseded by cycle: " + " -> ".join(cycle))
            return
    for row_id, row in rows_by_id.items():
        successor = superseded_by(row)
        if successor is None:
            continue
        successor_row = rows_by_id[successor]
        if not successor.startswith(f"{row_id}-"):
            findings.append(
                f"Delivery ledger row {row_id} successor must be versioned as {row_id}-<version>"
            )
            return
        if successor_row["Version"] == row["Version"]:
            findings.append(f"Delivery ledger row {row_id} successor must use a new Version")
            return
        for field in ("Release", "Capability", "Layer", "Owner", "Target gate"):
            if successor_row[field] != row[field]:
                findings.append(f"Delivery ledger row {row_id} successor must preserve {field}")
                return

    for row_id, (
        release,
        state,
        version,
        artifact_target,
    ) in REQUIRED_NONVISUAL_ROWS.items():
        row = rows_by_id.get(row_id)
        if row is None:
            findings.append(f"Delivery ledger missing required row: {row_id}")
            return
        if row["Release"] != release:
            findings.append(f"Delivery ledger row {row_id} must record Release {release}")
            return
        if row["Version"] != version:
            findings.append(
                f"Delivery ledger row {row_id} must record Version {version}"
            )
            return
        if STATE_RANK[row["State"]] < STATE_RANK[state]:
            findings.append(f"Delivery ledger row {row_id} must be at least initial state {state}")
            return
        if artifact_target not in markdown_links(row["Artifact"]):
            findings.append(f"Delivery ledger row {row_id} must link its required artifact")
            return

    visual_row_ids = {row_id for row_id in rows_by_id if row_id.startswith("VIS-")}
    unexpected_visual_ids = sorted(visual_row_ids - set(EXPECTED_VISUAL_ROWS))
    if unexpected_visual_ids:
        findings.append(f"Delivery ledger has unexpected visual row: {unexpected_visual_ids[0]}")
        return
    for row_id, asset_target in EXPECTED_VISUAL_ROWS.items():
        row = rows_by_id.get(row_id)
        if row is None:
            findings.append(f"Delivery ledger missing required visual row: {row_id}")
            return
        asset_path = resolve_in_repository_ledger_link(root, asset_target)
        if asset_path is None or not asset_path.is_file():
            findings.append(f"Delivery ledger expected visual PNG is missing: {row_id}")
            return
        evidence_links = (
            merged_evidence_by_id[row_id][0]
            if row_id in merged_evidence_by_id
            else markdown_links(row["Evidence"])
        )
        artifact_links = [
            link for link in parse_exact_markdown_link_list(row["Artifact"]) or []
        ]
        png_links = [
            link for link in artifact_links + evidence_links
            if (link_path := local_link_path(link)) is not None and Path(link_path).suffix.lower() == ".png"
        ]
        if any(
            (path := resolve_in_repository_ledger_link(root, link)) is None or not path.is_file()
            for link in png_links
        ):
            findings.append(
                f"Delivery ledger row {row_id} links a missing or outside PNG evidence asset"
            )
            return
        normalized_png_targets = {local_link_path(link) for link in png_links}
        if normalized_png_targets != {asset_target}:
            findings.append(
                f"Delivery ledger row {row_id} Artifact/Evidence PNG targets must be exactly its expected PNG"
            )
            return
        if asset_target not in {local_link_path(link) for link in artifact_links}:
            findings.append(f"Delivery ledger row {row_id} must link {asset_target}")
            return
        if asset_target not in {local_link_path(link) for link in evidence_links}:
            findings.append(f"Delivery ledger row {row_id} Evidence must link its exact PNG")
            return
        if row["Owner"] != VISUAL_OWNER or row["Version"] != VISUAL_BUNDLE_VERSION:
            findings.append(f"Delivery ledger row {row_id} must use the frozen visual bundle metadata")
            return
        if VISUAL_CONFIRMATION_DATE not in row["Evidence"]:
            findings.append(f"Delivery ledger row {row_id} must record confirmation date {VISUAL_CONFIRMATION_DATE}")
            return

    if invalid_visual_merged_evidence:
        row_id = invalid_visual_merged_evidence[0]
        findings.append(
            f"Delivery ledger row {row_id} MERGED evidence must cite an ancestor commit containing its Artifact"
        )
        return

    for row_id in R1_PRODUCTION_ROWS:
        row = rows_by_id.get(row_id)
        if row is not None and row["State"] == "DRAFT":
            findings.append(f"Delivery ledger row {row_id} cannot be DRAFT: 计划不是生产代码")
            return

    # A plan may be recorded as DRAFT, but it is never a substitute for one of
    # these five separately locatable production deliverables.
    gate_findings: list[str] = []
    for row_id, required_state in R1_PRODUCTION_ROWS.items():
        row = rows_by_id.get(row_id)
        if row is None:
            gate_findings.append(
                f"Gate R2 entry unmet: missing required delivery row {row_id}; 计划不是生产代码"
            )
            continue
        active_id = active_successor(row_id, rows_by_id)
        active_row = rows_by_id[active_id]
        if STATE_RANK[active_row["State"]] < STATE_RANK[required_state]:
            subject = row_id if active_id == row_id else f"{row_id} active successor {active_id}"
            gate_findings.append(
                f"Gate R2 entry unmet: {subject} is {active_row['State']}, requires {required_state}"
            )
    for row_id, required_state in R2_STATE_REQUIREMENTS.items():
        if row_id not in rows_by_id:
            if row_id == "DB-52P2-PG18-RUNTIME":
                gate_findings.append(
                    "Gate R2 entry unmet: missing required delivery row "
                    "DB-52P2-PG18-RUNTIME; a runtime plan is not runtime evidence"
                )
            else:
                gate_findings.append(
                    f"Gate R2 entry unmet: missing required delivery row {row_id}"
                )
            continue
        active_id = active_successor(row_id, rows_by_id)
        active_row = rows_by_id[active_id]
        if STATE_RANK[active_row["State"]] < STATE_RANK[required_state]:
            subject = row_id if active_id == row_id else f"{row_id} active successor {active_id}"
            gate_findings.append(
                f"Gate R2 entry unmet: {subject} is {active_row['State']}, requires {required_state}"
            )
    unmerged_visual_rows = [
        row_id for row_id in EXPECTED_VISUAL_ROWS
        if rows_by_id[active_successor(row_id, rows_by_id)]["State"] != "MERGED"
    ]
    if unmerged_visual_rows:
        active_row = rows_by_id[active_successor(unmerged_visual_rows[0], rows_by_id)]
        gate_findings.append(
            "Gate R2 entry unmet: PR2 visual bundle is "
            f"{active_row['State']}, requires MERGED"
        )
    return gate_findings


def verify_visual_asset_counts(root: Path, findings: list[str]) -> None:
    for relative_dir, expected_count in EXPECTED_VISUAL_ASSETS.items():
        asset_count = sum(1 for _ in (root / relative_dir).rglob("*.png"))
        if asset_count != expected_count:
            findings.append(
                f"Visual asset count mismatch for {relative_dir}: "
                f"expected {expected_count} PNG files, found {asset_count}"
            )


def _verify_repository_result_unchecked(root: Path) -> VerificationResult:
    structural_findings: list[str] = []
    missing_controlled_paths: set[Path] = set()

    baseline_text = verify_canonical_baseline(root, structural_findings)
    if baseline_text is None:
        missing_controlled_paths.add(CANONICAL_BASELINE)
    closure_spec_text = read_text(root, CLOSURE_SPEC)
    readme_text = read_text(root, README)

    verify_closure_spec(structural_findings, closure_spec_text)
    verify_readme(structural_findings, readme_text)
    verify_historical_specs(root, structural_findings)
    verify_waiting_contract(structural_findings, baseline_text)
    verify_matter_endpoint(structural_findings, baseline_text)
    verify_r1_contracts(root, structural_findings)
    readiness_blockers = (
        verify_delivery_ledger(root, structural_findings) or []
    )
    ledger_boundary = len(structural_findings)
    verify_visual_indexes(root, structural_findings)
    verify_visual_asset_counts(root, structural_findings)
    verify_relative_markdown_links(
        root, structural_findings, missing_controlled_paths
    )

    ordered_findings = (
        [
            CategorizedFinding(message, FindingCategory.STRUCTURAL)
            for message in structural_findings[:ledger_boundary]
        ]
        + [
            CategorizedFinding(message, FindingCategory.R2_READINESS)
            for message in readiness_blockers
        ]
        + [
            CategorizedFinding(message, FindingCategory.STRUCTURAL)
            for message in structural_findings[ledger_boundary:]
        ]
    )
    return VerificationResult(tuple(ordered_findings))


def _verify_repository_result(root: Path) -> VerificationResult:
    try:
        return _verify_repository_result_unchecked(root)
    except InvalidUtf8GovernedFile as error:
        message = (
            "Invalid UTF-8 in governed file: "
            f"{error.relative_path.as_posix()}"
        )
        return VerificationResult(
            (CategorizedFinding(message, FindingCategory.STRUCTURAL),)
        )


def verify_repository(root: Path) -> list[str]:
    return _verify_repository_result(root).messages()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify the canonical MVP baseline")
    parser.add_argument(
        "--strict-r2",
        action="store_true",
        help="treat R2 readiness blockers as fatal",
    )
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    root = args.root.resolve()
    result = _verify_repository_result(root)
    readiness_blockers = result.in_category(FindingCategory.R2_READINESS)
    structural_findings = result.in_category(FindingCategory.STRUCTURAL)

    for finding in result.findings:
        if finding.category is FindingCategory.R2_READINESS:
            status = "strict" if args.strict_r2 else "non-fatal"
            print(f"R2 readiness blocker ({status}): {finding.message}")
        else:
            print(finding.message)

    if structural_findings:
        return 1
    if readiness_blockers:
        count = len(readiness_blockers)
        noun = "blocker" if count == 1 else "blockers"
        if args.strict_r2:
            print(
                "baseline consistency: FAIL; R2 readiness: BLOCKED "
                f"({count} {noun})"
            )
            return 1
        print(
            "baseline consistency: PASS; R2 readiness: BLOCKED "
            f"({count} non-fatal {noun})"
        )
        return 0
    print("baseline consistency: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
