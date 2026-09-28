"""类型化准确引用的静态允许列表。

类型代码统一采用未加引号的 ``schema.table``，Resolver必须按本文件中的准确槽位
选择目标Fact Owner，禁止由请求动态指定表名、Schema或解析器。
"""

APPLICATION_FACT_TYPES = (
    "identity.tenant",
    "identity.principal",
    "identity.organization_unit",
    "identity.appointment",
    "identity.authority_grant",
    "identity.delegation_grant",
    "identity.object_access_grant",
    "audit.audit_entry",
    "responsibility.task_occurrence",
    "responsibility.decision_record",
    "responsibility.wait_receipt",
    "responsibility.action_draft",
    "execution.command_execution_slot",
    "execution.command_receipt",
    "execution.domain_event",
    "execution.domain_event_outbox",
    "external_action.external_action",
    "external_action.external_action_outbox",
    "external_action.provider_inbox",
    "evidence.upload_session",
    "evidence.received_source_object",
    "evidence.evidence_submission",
    "evidence.evidence_binding",
    "party.party",
    "lead.lead",
    "lead.lead_assignment",
    "lead.lead_contact_result",
    "opportunity.opportunity",
    "opportunity.opportunity_participation",
    "opportunity.opportunity_progress",
    "opportunity.quote_revision",
    "opportunity.quote_service_scope",
    "opportunity.quote_line",
    "opportunity.quote_payment_term",
    "opportunity.quote_issue",
    "opportunity.quote_response",
    "conflict.conflict_review",
    "conflict.conflict_review_party",
    "conflict.conflict_finding",
    "contract.contract",
    "contract.contract_revision",
    "contract.contract_participation",
    "contract.contract_fee_term",
    "contract.payment_gate",
    "contract.signature_plan",
    "contract.contract_signature",
    "contract.contract_execution",
    "contract.payment_confirmation",
    "contract.contract_termination",
    "transfer.transfer_request",
    "transfer.transfer_snapshot",
    "transfer.transfer_return_item",
)

BUSINESS_SUBJECT_TYPES = (
    "party.party",
    "lead.lead",
    "lead.lead_assignment",
    "opportunity.opportunity",
    "opportunity.opportunity_participation",
    "opportunity.quote_revision",
    "opportunity.quote_issue",
    "conflict.conflict_review",
    "conflict.conflict_finding",
    "contract.contract",
    "contract.contract_revision",
    "contract.payment_gate",
    "contract.signature_plan",
    "contract.contract_signature",
    "contract.contract_execution",
    "contract.payment_confirmation",
    "contract.contract_termination",
    "transfer.transfer_request",
    "transfer.transfer_snapshot",
    "transfer.transfer_return_item",
    "evidence.evidence_submission",
    "evidence.evidence_binding",
    "responsibility.task_occurrence",
    "responsibility.decision_record",
    "external_action.external_action",
)

RESULT_FACT_TYPES = tuple(
    fact_type
    for fact_type in APPLICATION_FACT_TYPES
    if fact_type not in {
        "audit.audit_entry",
        "execution.command_execution_slot",
        "execution.command_receipt",
        "execution.domain_event",
        "execution.domain_event_outbox",
        "external_action.external_action_outbox",
    }
)

TYPED_REFERENCE_ALLOWED_TARGETS = {
    "identity.object_access_grant.object_subject": BUSINESS_SUBJECT_TYPES,
    "audit.audit_entry.subject": APPLICATION_FACT_TYPES,
    "audit.audit_entry.correction_target": ("audit.audit_entry",),
    "audit.audit_entry.authorization_fact": (
        "identity.appointment",
        "identity.authority_grant",
        "identity.delegation_grant",
        "identity.object_access_grant",
        "responsibility.decision_record",
    ),
    "responsibility.task_occurrence.subject": BUSINESS_SUBJECT_TYPES,
    "responsibility.task_occurrence.completion_fact": RESULT_FACT_TYPES,
    "responsibility.decision_record.decision_subject": BUSINESS_SUBJECT_TYPES,
    "responsibility.wait_receipt.awaited_fact": RESULT_FACT_TYPES,
    "execution.command_receipt.result_fact": RESULT_FACT_TYPES,
    "execution.domain_event.source_fact": RESULT_FACT_TYPES,
    "external_action.external_action.subject": BUSINESS_SUBJECT_TYPES,
    "external_action.external_action.resolution_source": (
        "external_action.provider_inbox",
        "responsibility.decision_record",
    ),
    "evidence.upload_session.target": BUSINESS_SUBJECT_TYPES,
    "evidence.evidence_binding.target": BUSINESS_SUBJECT_TYPES,
    "opportunity.opportunity_progress.source_fact": (
        "lead.lead_contact_result",
        "opportunity.quote_issue",
        "opportunity.quote_response",
        "responsibility.decision_record",
        "external_action.external_action",
        "external_action.provider_inbox",
        "evidence.evidence_submission",
    ),
    "opportunity.quote_issue.delivery_fact": (
        "external_action.external_action",
        "external_action.provider_inbox",
    ),
    "conflict.conflict_review.trigger_fact": (
        "opportunity.quote_revision",
        "opportunity.quote_response",
        "contract.contract_revision",
        "transfer.transfer_request",
        "responsibility.action_draft",
    ),
    "conflict.conflict_review_party.source_item": (
        "party.party",
        "opportunity.opportunity_participation",
        "contract.contract_participation",
        "transfer.transfer_snapshot",
    ),
    "conflict.conflict_finding.matched_fact": (
        "party.party",
        "opportunity.opportunity_participation",
        "contract.contract_participation",
        "conflict.conflict_review_party",
        "transfer.transfer_request",
    ),
    "conflict.conflict_finding.source_fact": (
        "party.party",
        "opportunity.opportunity_participation",
        "contract.contract_participation",
        "transfer.transfer_snapshot",
        "evidence.evidence_submission",
    ),
    "contract.contract.activation_source": (
        "contract.payment_gate",
        "responsibility.decision_record",
    ),
    "transfer.transfer_return_item.required_target": (
        "party.party",
        "evidence.evidence_submission",
        "evidence.evidence_binding",
        "contract.contract_revision",
        "conflict.conflict_review",
        "transfer.transfer_request",
        "transfer.transfer_snapshot",
    ),
}

# V900 named schema successor slots.
APPLICATION_FACT_TYPES += ("opportunity.owner_exception", "opportunity.owner_exception_disposition", "opportunity.responsibility_handoff")
TYPED_REFERENCE_ALLOWED_TARGETS.update({
 "responsibility.task_occurrence.responsibility_basis": ("opportunity.opportunity", "opportunity.responsibility_handoff"),
 "responsibility.task_occurrence.cancellation_fact": ("opportunity.responsibility_handoff",),
})

# Formal observation/disposition commands publish these facts through existing exact slots.
for _slot in ("audit.audit_entry.subject", "execution.command_receipt.result_fact", "execution.domain_event.source_fact"):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += ("opportunity.owner_exception", "opportunity.owner_exception_disposition", "opportunity.responsibility_handoff")

# V910 explicit terminal fact; existing cancellation/receipt/event slots gain only this named target.
APPLICATION_FACT_TYPES += ("opportunity.closure",)
for _slot in ("identity.object_access_grant.object_subject", "responsibility.task_occurrence.cancellation_fact", "audit.audit_entry.subject", "execution.command_receipt.result_fact", "execution.domain_event.source_fact"):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += ("opportunity.closure",)

# V920 exact customer requirements and Party history; never a task cancellation basis.
_T05_FACTS = ("party.profile_version", "opportunity.customer_requirement_draft", "opportunity.customer_requirement_confirmation", "opportunity.customer_requirement_participant", "opportunity.customer_requirement_draft_party")
APPLICATION_FACT_TYPES += _T05_FACTS
for _slot in ("identity.object_access_grant.object_subject", "audit.audit_entry.subject", "execution.command_receipt.result_fact", "execution.domain_event.source_fact"):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += _T05_FACTS

# V930 material receipts expose immutable supplementary facts, never mutable upload selectors.
_T06_FACTS = ('evidence.material_upload_basis', 'evidence.material_upload_check', 'opportunity.material_version')
APPLICATION_FACT_TYPES += _T06_FACTS
for _slot in ('identity.object_access_grant.object_subject', 'audit.audit_entry.subject', 'execution.command_receipt.result_fact', 'execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += _T06_FACTS

# V940 exact protected quote sources.
_T07_FACTS = ("opportunity.quote_draft", "opportunity.quote_package_basis")
APPLICATION_FACT_TYPES += _T07_FACTS
for _slot in ("identity.object_access_grant.object_subject", "audit.audit_entry.subject", "execution.command_receipt.result_fact", "execution.domain_event.source_fact"):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += _T07_FACTS

# V950 named runtime facts; manual delivery is distinct from provider delivery.
_T07_RUNTIME_FACTS = tuple('opportunity.' + name for name in (
    'quote_approval_policy', 'quote_approval_policy_signer', 'quote_approval_request',
    'quote_approval_member', 'quote_approval_decision', 'quote_manual_delivery',
    'quote_response_basis', 'contract_preparation_source', 'quote_workflow'))
APPLICATION_FACT_TYPES += _T07_RUNTIME_FACTS
for _slot in ('identity.object_access_grant.object_subject', 'audit.audit_entry.subject',
              'execution.command_receipt.result_fact', 'execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot] += _T07_RUNTIME_FACTS
TYPED_REFERENCE_ALLOWED_TARGETS['opportunity.quote_issue.delivery_fact'] += ('opportunity.quote_manual_delivery',)
TYPED_REFERENCE_ALLOWED_TARGETS['responsibility.task_occurrence.completion_fact'] += (
    'opportunity.quote_approval_request', 'opportunity.quote_approval_decision')

# V970 structural direct preparation facts; command slots are not activated yet.
APPLICATION_FACT_TYPES += ("contract.preparation_request", "contract.preparation_decision")

# V980 named contract preparation facts. Runtime activation still requires capability/version gate.
_T08_FACTS=tuple('contract.'+name for name in ('approval_policy','approval_policy_member','preparation_workflow','revision_approval_request','preparation_draft','template_version','clause_version','revision_clause','revision_review_request','revision_review_decision','revision_review_binding','revision_approval_requirement','revision_approval_decision','signature_readiness'))
APPLICATION_FACT_TYPES+=_T08_FACTS
for _slot in ('identity.object_access_grant.object_subject','audit.audit_entry.subject','execution.command_receipt.result_fact','execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot]+=(*_T08_FACTS,'contract.preparation_request','contract.preparation_decision')
TYPED_REFERENCE_ALLOWED_TARGETS['responsibility.task_occurrence.completion_fact']+=('contract.preparation_request','contract.preparation_decision','contract.revision_review_request','contract.revision_review_decision','contract.revision_approval_request','contract.revision_approval_decision','contract.signature_readiness')

# V990 manual signing facts retain exact audit/receipt/task references.
_T09_FACTS=tuple('contract.'+name for name in ('signature_arrangement','signature_draft','signature_submission','signature_verification','signature_archive','signature_revision_return','signature_workflow','signature_handoff'))
APPLICATION_FACT_TYPES+=_T09_FACTS
for _slot in ('identity.object_access_grant.object_subject','audit.audit_entry.subject','execution.command_receipt.result_fact','execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot]+=_T09_FACTS
TYPED_REFERENCE_ALLOWED_TARGETS['responsibility.task_occurrence.completion_fact']+=tuple(f for f in _T09_FACTS if f not in ('contract.signature_draft','contract.signature_workflow'))

# Approved-template signing identity metadata is immutable, never a task completion.
APPLICATION_FACT_TYPES+=('contract.template_signing_party',)
for _slot in ('identity.object_access_grant.object_subject','audit.audit_entry.subject','execution.command_receipt.result_fact','execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot]+=('contract.template_signing_party',)

# V1050 exact pending transfer facts; they do not imply intake or case creation.
_TRANSFER_PENDING_FACTS=('transfer.workflow','transfer.submission','transfer.review','transfer.review_return_item','transfer.intake','transfer.classification')
APPLICATION_FACT_TYPES+=_TRANSFER_PENDING_FACTS
for _slot in ('identity.object_access_grant.object_subject','audit.audit_entry.subject','execution.command_receipt.result_fact','execution.domain_event.source_fact'):
    TYPED_REFERENCE_ALLOWED_TARGETS[_slot]+=_TRANSFER_PENDING_FACTS
TYPED_REFERENCE_ALLOWED_TARGETS['responsibility.task_occurrence.completion_fact']+=('transfer.submission','transfer.review','transfer.intake','transfer.classification')

TYPED_REFERENCE_ALLOWED_TARGETS['conflict.conflict_review.trigger_fact']+=('transfer.submission',)
TYPED_REFERENCE_ALLOWED_TARGETS['conflict.conflict_review_party.source_item']+=('opportunity.customer_requirement_participant',)
