-- 查询、自然幂等和单当前事实索引。

CREATE INDEX ix_tenant__state ON identity.tenant (state);
COMMENT ON INDEX identity.ix_tenant__state IS '按生命周期状态执行租户运维筛选。';

CREATE INDEX ix_principal__state ON identity.principal (tenant_id, state);
COMMENT ON INDEX identity.ix_principal__state IS '按租户和生命周期状态查找身份主体。';

CREATE INDEX ix_organization_unit__parent ON identity.organization_unit (tenant_id, parent_organization_unit_id) WHERE parent_organization_unit_id IS NOT NULL;
COMMENT ON INDEX identity.ix_organization_unit__parent IS '按同租户上级节点遍历直属组织单元。';

CREATE INDEX ix_appointment__principal ON identity.appointment (tenant_id, principal_id, state);
COMMENT ON INDEX identity.ix_appointment__principal IS '按身份主体查找当前及历史任职。';

CREATE INDEX ix_appointment__unit ON identity.appointment (tenant_id, organization_unit_id, state);
COMMENT ON INDEX identity.ix_appointment__unit IS '按组织单元查找任职。';

CREATE INDEX ix_authority_grant__grantee ON identity.authority_grant (tenant_id, grantee_appointment_id, authority_code, state);
COMMENT ON INDEX identity.ix_authority_grant__grantee IS '授权复验时按受权任职、权限和状态定位候选授予。';

CREATE INDEX ix_authority_grant__scope ON identity.authority_grant (tenant_id, scope_organization_unit_id, authority_code, state);
COMMENT ON INDEX identity.ix_authority_grant__scope IS '授权复验：按当前组织树范围根、权限和状态定位授予。';

CREATE INDEX ix_delegation_grant__delegate ON identity.delegation_grant (tenant_id, delegate_appointment_id, state);
COMMENT ON INDEX identity.ix_delegation_grant__delegate IS '授权复验时按受托任职和状态查找委托。';

CREATE INDEX ix_delegation_grant__source ON identity.delegation_grant (tenant_id, source_authority_grant_id);
COMMENT ON INDEX identity.ix_delegation_grant__source IS '按来源直接授权查找全部委托。';

CREATE INDEX ix_object_access_grant__grantee ON identity.object_access_grant (tenant_id, grantee_principal_id, access_code, effect_code, state);
COMMENT ON INDEX identity.ix_object_access_grant__grantee IS '授权复验时按Principal、能力、先限后允效果和状态定位对象规则。';

CREATE INDEX ix_object_access_grant__object ON identity.object_access_grant (tenant_id, object_subject_type, object_subject_id, state);
COMMENT ON INDEX identity.ix_object_access_grant__object IS '按准确业务Subject查找对象访问授予。';

CREATE INDEX ix_audit_entry__subject_time ON audit.audit_entry (tenant_id, subject_type, subject_id, trusted_at);
COMMENT ON INDEX audit.ix_audit_entry__subject_time IS '按准确Subject和可信时间检索审计轨迹。';

CREATE INDEX ix_audit_entry__actor_time ON audit.audit_entry (tenant_id, actor_principal_id, trusted_at);
COMMENT ON INDEX audit.ix_audit_entry__actor_time IS '按实际发起身份和可信时间检索审计轨迹。';

CREATE INDEX ix_audit_entry__correlation ON audit.audit_entry (tenant_id, correlation_id, trusted_at);
COMMENT ON INDEX audit.ix_audit_entry__correlation IS '按Correlation标识重建一次动作链的审计顺序。';

CREATE INDEX ix_audit_entry__scope_time ON audit.audit_entry (tenant_id, audit_scope_code, trusted_at);
COMMENT ON INDEX audit.ix_audit_entry__scope_time IS '分类查询：按准确审计Scope和可信时间检索。';

CREATE UNIQUE INDEX ux_audit_entry__correction_target ON audit.audit_entry (tenant_id, correction_target_type, correction_target_id) WHERE entry_type = 'CORRECTION';
COMMENT ON INDEX audit.ux_audit_entry__correction_target IS '更正单链唯一：一条AuditEntry最多只有一个直接CORRECTION后继；继续修正必须引用上一条CORRECTION。';

CREATE INDEX ix_task_occurrence__owner_state_due ON responsibility.task_occurrence (tenant_id, owner_appointment_id, state, original_sla_due_at);
COMMENT ON INDEX responsibility.ix_task_occurrence__owner_state_due IS '按Owner、状态和原始SLA截止时间生成责任待办视图。';

CREATE INDEX ix_task_occurrence__subject ON responsibility.task_occurrence (tenant_id, subject_type, subject_id, state);
COMMENT ON INDEX responsibility.ix_task_occurrence__subject IS '按冻结Subject查找相关责任实例。';

CREATE INDEX ix_wait_receipt__task_time ON responsibility.wait_receipt (tenant_id, task_occurrence_id, entered_waiting_at);
COMMENT ON INDEX responsibility.ix_wait_receipt__task_time IS '按待办和时间读取不可变等待历史。';

CREATE INDEX ix_action_draft__state ON responsibility.action_draft (tenant_id, state, last_edited_at);
COMMENT ON INDEX responsibility.ix_action_draft__state IS '按租户、草案状态和最近编辑时间查找待处理草案。';

CREATE INDEX ix_command_execution_slot__occupied_at ON execution.command_execution_slot (tenant_id, occupied_at);
COMMENT ON INDEX execution.ix_command_execution_slot__occupied_at IS '运维查询：按租户和占位时间定位永久命令占位，不承担队列语义。';

CREATE INDEX ix_domain_event__source_fact ON execution.domain_event (tenant_id, source_fact_type, source_fact_id);
COMMENT ON INDEX execution.ix_domain_event__source_fact IS '事实追溯：按租户和来源事实定位通知；准确版本选择器仍由行内约束限定。';

CREATE INDEX ix_domain_event_outbox__claim ON execution.domain_event_outbox (tenant_id, queue_owner, status, available_at) WHERE status = 'PENDING';
COMMENT ON INDEX execution.ix_domain_event_outbox__claim IS '队列领取：按租户、Owner、状态和可用时间扫描可领取投递。';

CREATE INDEX ix_domain_event_outbox__lease_expiry ON execution.domain_event_outbox (tenant_id, lease_until) WHERE status = 'CLAIMED';
COMMENT ON INDEX execution.ix_domain_event_outbox__lease_expiry IS '租约回收：定位已过期的CLAIMED投递并执行带围栏CAS。';

CREATE UNIQUE INDEX ix_external_action__provider_action ON external_action.external_action (tenant_id, provider_account_id, provider_action_id) WHERE provider_action_id IS NOT NULL;
COMMENT ON INDEX external_action.ix_external_action__provider_action IS '远端对账：按Provider账户和远端动作标识定位本地唯一尝试。';

CREATE INDEX ix_external_action__unknown ON external_action.external_action (tenant_id, provider_code, status, dispatched_at) WHERE status = 'UNKNOWN';
COMMENT ON INDEX external_action.ix_external_action__unknown IS 'UNKNOWN收敛：定位需要通过Provider探测确认结果的动作。';

CREATE INDEX ix_external_action_outbox__claim ON external_action.external_action_outbox (tenant_id, operation, status, available_at) WHERE status = 'PENDING';
COMMENT ON INDEX external_action.ix_external_action_outbox__claim IS '队列领取：按租户、工作类型、状态和可用时间扫描可领取工作项。';

CREATE INDEX ix_external_action_outbox__lease_expiry ON external_action.external_action_outbox (tenant_id, lease_until) WHERE status = 'CLAIMED';
COMMENT ON INDEX external_action.ix_external_action_outbox__lease_expiry IS '租约回收：定位已过期的CLAIMED工作项并执行带围栏CAS。';

CREATE INDEX ix_provider_inbox__received_at ON external_action.provider_inbox (tenant_id, provider_code, received_at);
COMMENT ON INDEX external_action.ix_provider_inbox__received_at IS '入站审计：按租户、Provider和接收时间追溯已验签事件指纹。';

CREATE INDEX ix_upload_session__open_expiry ON evidence.upload_session (tenant_id, status, expires_at) WHERE status = 'OPEN';
COMMENT ON INDEX evidence.ix_upload_session__open_expiry IS '会话回收：按租户和到期时间定位仍OPEN的会话。';

CREATE INDEX ix_evidence_binding__active_target ON evidence.evidence_binding (tenant_id, target_type, target_id, purpose_code) WHERE revoked_at IS NULL;
COMMENT ON INDEX evidence.ix_evidence_binding__active_target IS '有效证据查询：按租户、准确目标标识和用途定位尚未撤回的绑定。';

CREATE UNIQUE INDEX ux_party__active_primary_identifier ON party.party (tenant_id, primary_identifier_type, primary_identifier_hmac) WHERE status = 'ACTIVE' AND primary_identifier_hmac IS NOT NULL;
COMMENT ON INDEX party.ux_party__active_primary_identifier IS '受保护主标识匹配：活动主体的类型与HMAC组合在租户内唯一。';

CREATE INDEX ix_party__canonical_name ON party.party (tenant_id, canonical_name);
COMMENT ON INDEX party.ix_party__canonical_name IS '主体检索：按租户和当前规范名定位活动或已合并主体锚点。';

CREATE INDEX ix_party__merge_target ON party.party (tenant_id, merged_into_party_id) WHERE merged_into_party_id IS NOT NULL;
COMMENT ON INDEX party.ix_party__merge_target IS '合并追溯：定位直接并入某个最终活动主体的一跳来源。';

CREATE UNIQUE INDEX ux_lead__source_idempotency ON lead.lead (tenant_id, source_account_code, source_record_key_digest);
COMMENT ON INDEX lead.ux_lead__source_idempotency IS '来源幂等索引：阻止同一渠道投递重复落库，不用于判定业务疑似重复。';

CREATE INDEX ix_lead__current_disposition ON lead.lead (tenant_id, disposition_code, captured_at);
COMMENT ON INDEX lead.ix_lead__current_disposition IS '处置查询索引：支持租户内按当前处置和捕获时间检索Lead。';

CREATE UNIQUE INDEX ux_lead_assignment__previous ON lead.lead_assignment (tenant_id, previous_assignment_id) WHERE previous_assignment_id IS NOT NULL;
COMMENT ON INDEX lead.ux_lead_assignment__previous IS '前序链唯一索引：一个前序Assignment最多只有一个直接后继，避免链分叉。';

CREATE UNIQUE INDEX ux_lead_assignment__chain_head ON lead.lead_assignment (tenant_id, lead_id) WHERE previous_assignment_id IS NULL;
COMMENT ON INDEX lead.ux_lead_assignment__chain_head IS '链首唯一索引：每个Lead最多存在一个无前序Assignment的链首。';

CREATE UNIQUE INDEX ux_lead_assignment__open ON lead.lead_assignment (tenant_id, lead_id) WHERE assignment_status_code = 'OPEN';
COMMENT ON INDEX lead.ux_lead_assignment__open IS '当前分派唯一索引：每个Lead最多保留一个OPEN Assignment。';

CREATE INDEX ix_lead_contact_result__lead_time ON lead.lead_contact_result (tenant_id, lead_id, resulted_at);
COMMENT ON INDEX lead.ix_lead_contact_result__lead_time IS '联系历史索引：支持按Lead和发生时间读取追加结果。';

CREATE UNIQUE INDEX ux_opportunity__source_assignment ON opportunity.opportunity (tenant_id, source_assignment_id);
COMMENT ON INDEX opportunity.ux_opportunity__source_assignment IS '来源唯一索引：一次LeadAssignment最多形成一项法律需求Opportunity。';

CREATE INDEX ix_opportunity__owner_open ON opportunity.opportunity (tenant_id, owner_appointment_id, created_at) WHERE closed_at IS NULL;
COMMENT ON INDEX opportunity.ix_opportunity__owner_open IS 'Owner工作台：按Owner和创建时间读取尚未终结的机会。';

CREATE INDEX ix_opportunity_progress__timeline ON opportunity.opportunity_progress (tenant_id, opportunity_id, occurred_at);
COMMENT ON INDEX opportunity.ix_opportunity_progress__timeline IS '进展时间线索引：支持按Opportunity和发生时间读取追加事实。';

CREATE INDEX ix_quote_revision__opportunity_created ON opportunity.quote_revision (tenant_id, opportunity_id, created_at);
COMMENT ON INDEX opportunity.ix_quote_revision__opportunity_created IS '报价版本索引：支持按Opportunity读取不可变版本历史。';

CREATE UNIQUE INDEX ux_quote_issue__active_recipient ON opportunity.quote_issue (tenant_id, quote_revision_id, recipient_participation_id) WHERE issue_status_code = 'ACTIVE';
COMMENT ON INDEX opportunity.ux_quote_issue__active_recipient IS '逐收件人有效Issue唯一索引：同一报价版本对同一收件人最多一个ACTIVE Issue。';

CREATE INDEX ix_quote_issue__recipient_time ON opportunity.quote_issue (tenant_id, recipient_participation_id, issued_at);
COMMENT ON INDEX opportunity.ix_quote_issue__recipient_time IS '收件人发出历史索引：支持按冻结收件人和时间读取Issue链。';

CREATE INDEX ix_quote_response__issue_time ON opportunity.quote_response (tenant_id, quote_issue_id, received_at);
COMMENT ON INDEX opportunity.ix_quote_response__issue_time IS '响应历史索引：支持按QuoteIssue标识和接收时间读取追加响应。';

CREATE INDEX ix_conflict_review__trigger_scope ON conflict.conflict_review (tenant_id, review_type_code, trigger_fact_type, trigger_fact_id, trigger_fact_revision, trigger_fact_hash, scope_hash, rule_set_hash, corpus_hash);
COMMENT ON INDEX conflict.ix_conflict_review__trigger_scope IS '审查来源索引：按准确触发版本或摘要、范围、规则和语料读取历史Review；不设置自然唯一，以允许Decision或有效性变化后创建新Review。';

CREATE INDEX ix_conflict_review__unresolved ON conflict.conflict_review (tenant_id, reviewed_at) WHERE initial_conclusion_code = 'FINDINGS' AND resolution_code IS NULL;
COMMENT ON INDEX conflict.ix_conflict_review__unresolved IS '待收敛审查索引：只定位存在Finding且尚未写入裁决结果的Review。';

CREATE INDEX ix_conflict_review_party__review ON conflict.conflict_review_party (tenant_id, conflict_review_id, scope_role_code);
COMMENT ON INDEX conflict.ix_conflict_review_party__review IS '完整范围读取索引：支持按Review和审查角色枚举全部Party。';

CREATE INDEX ix_conflict_finding__risk ON conflict.conflict_finding (tenant_id, conflict_review_id, risk_classification_code);
COMMENT ON INDEX conflict.ix_conflict_finding__risk IS '任务生成：按Review和风险分类解析静态authoritySlot并创建逐Finding责任卡。';

CREATE INDEX ix_conflict_finding__review_party ON conflict.conflict_finding (tenant_id, conflict_review_id, conflict_review_party_id);
COMMENT ON INDEX conflict.ix_conflict_finding__review_party IS '命中查询索引：支持按Review和范围Party读取全部不可变Finding。';

CREATE INDEX ix_contract__opportunity ON contract.contract (tenant_id, opportunity_id);
COMMENT ON INDEX contract.ix_contract__opportunity IS '来源查询：按商机定位其合同。';

CREATE UNIQUE INDEX ux_contract_signature__active_plan ON contract.contract_signature (tenant_id, signature_plan_id) WHERE revoked_at IS NULL;
COMMENT ON INDEX contract.ux_contract_signature__active_plan IS '有效签署唯一：一个计划槽同时最多有一条未撤回签署。';

CREATE UNIQUE INDEX ux_transfer_request__matter_id ON transfer.transfer_request (tenant_id, matter_id) WHERE matter_id IS NOT NULL;
COMMENT ON INDEX transfer.ux_transfer_request__matter_id IS 'Matter标识唯一：已接收请求生成的matterId在租户内唯一。';

CREATE UNIQUE INDEX ux_transfer_request__matter_no ON transfer.transfer_request (tenant_id, matter_no) WHERE matter_no IS NOT NULL;
COMMENT ON INDEX transfer.ux_transfer_request__matter_no IS 'Matter编号唯一：已接收请求生成的matterNo在租户内唯一。';

CREATE INDEX ix_transfer_return_item__snapshot ON transfer.transfer_return_item (tenant_id, reviewed_snapshot_id, item_no);
COMMENT ON INDEX transfer.ix_transfer_return_item__snapshot IS '审查查询：按已审快照读取全部不可变退回项。';
