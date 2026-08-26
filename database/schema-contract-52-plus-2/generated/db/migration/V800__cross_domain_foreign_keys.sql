-- 所有物理关系均来自冻结白名单；多态准确引用不在此伪造外键。

ALTER TABLE identity.principal
    ADD CONSTRAINT fk_principal__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_principal__tenant ON identity.principal IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.organization_unit
    ADD CONSTRAINT fk_organization_unit__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_organization_unit__tenant ON identity.organization_unit IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.organization_unit
    ADD CONSTRAINT fk_organization_unit__parent_organization_unit
    FOREIGN KEY (tenant_id, parent_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_organization_unit__parent_organization_unit ON identity.organization_unit IS '组织层级：上级组织单元必须存在于同一租户；禁止级联删除。';

ALTER TABLE identity.appointment
    ADD CONSTRAINT fk_appointment__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_appointment__tenant ON identity.appointment IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.appointment
    ADD CONSTRAINT fk_appointment__principal
    FOREIGN KEY (tenant_id, principal_id)
    REFERENCES identity.principal (tenant_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_appointment__principal ON identity.appointment IS '任职主体必须是同租户已存在的身份主体。';

ALTER TABLE identity.appointment
    ADD CONSTRAINT fk_appointment__organization_unit
    FOREIGN KEY (tenant_id, organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_appointment__organization_unit ON identity.appointment IS '任职组织单元必须是同租户已存在的组织节点。';

ALTER TABLE identity.authority_grant
    ADD CONSTRAINT fk_authority_grant__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_authority_grant__tenant ON identity.authority_grant IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.authority_grant
    ADD CONSTRAINT fk_authority_grant__grantee_appointment
    FOREIGN KEY (tenant_id, grantee_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_authority_grant__grantee_appointment ON identity.authority_grant IS '受权任职必须存在于同一租户。';

ALTER TABLE identity.authority_grant
    ADD CONSTRAINT fk_authority_grant__granted_by_appointment
    FOREIGN KEY (tenant_id, granted_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_authority_grant__granted_by_appointment ON identity.authority_grant IS '授予人任职必须存在于同一租户。';

ALTER TABLE identity.authority_grant
    ADD CONSTRAINT fk_authority_grant__scope_org
    FOREIGN KEY (tenant_id, scope_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_authority_grant__scope_org ON identity.authority_grant IS '授权组织范围根必须存在于同一租户，树关系在命令提交前按当前结构复验。';

ALTER TABLE identity.delegation_grant
    ADD CONSTRAINT fk_delegation_grant__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_delegation_grant__tenant ON identity.delegation_grant IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.delegation_grant
    ADD CONSTRAINT fk_delegation_grant__source_grantee
    FOREIGN KEY (tenant_id, source_authority_grant_id, delegator_appointment_id)
    REFERENCES identity.authority_grant (tenant_id, authority_grant_id, grantee_appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_delegation_grant__source_grantee ON identity.delegation_grant IS '同一路径来源：转授权来源Grant必须准确授予本行委托人Appointment。';

ALTER TABLE identity.delegation_grant
    ADD CONSTRAINT fk_delegation_grant__delegator_appointment
    FOREIGN KEY (tenant_id, delegator_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_delegation_grant__delegator_appointment ON identity.delegation_grant IS '委托人任职必须存在于同一租户。';

ALTER TABLE identity.delegation_grant
    ADD CONSTRAINT fk_delegation_grant__delegate_appointment
    FOREIGN KEY (tenant_id, delegate_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_delegation_grant__delegate_appointment ON identity.delegation_grant IS '受托人任职必须存在于同一租户。';

ALTER TABLE identity.delegation_grant
    ADD CONSTRAINT fk_delegation_grant__scope_org
    FOREIGN KEY (tenant_id, scope_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_delegation_grant__scope_org ON identity.delegation_grant IS '委托组织范围根必须存在于同一租户，范围收窄由命令提交前复验。';

ALTER TABLE identity.object_access_grant
    ADD CONSTRAINT fk_object_access_grant__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_object_access_grant__tenant ON identity.object_access_grant IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE identity.object_access_grant
    ADD CONSTRAINT fk_object_access_grant__grantee_principal
    FOREIGN KEY (tenant_id, grantee_principal_id)
    REFERENCES identity.principal (tenant_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_object_access_grant__grantee_principal ON identity.object_access_grant IS '对象规则必须绑定同租户准确Principal。';

ALTER TABLE identity.object_access_grant
    ADD CONSTRAINT fk_object_access_grant__granted_by_appointment
    FOREIGN KEY (tenant_id, granted_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_object_access_grant__granted_by_appointment ON identity.object_access_grant IS '授予人任职必须存在于同一租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__tenant ON audit.audit_entry IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__actor_principal
    FOREIGN KEY (tenant_id, actor_principal_id)
    REFERENCES identity.principal (tenant_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__actor_principal ON audit.audit_entry IS '实际发起身份必须存在于同一租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__actor_appointment
    FOREIGN KEY (tenant_id, actor_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__actor_appointment ON audit.audit_entry IS '实际采用的任职若存在，必须属于同一租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__on_behalf_principal
    FOREIGN KEY (tenant_id, on_behalf_of_principal_id)
    REFERENCES identity.principal (tenant_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__on_behalf_principal ON audit.audit_entry IS '被代表Principal若存在，必须属于同一租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__on_behalf_of_appointment
    FOREIGN KEY (tenant_id, on_behalf_of_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__on_behalf_of_appointment ON audit.audit_entry IS '代办任职若存在，必须属于同一租户。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__actor_appointment_principal
    FOREIGN KEY (tenant_id, actor_appointment_id, actor_principal_id)
    REFERENCES identity.appointment (tenant_id, appointment_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__actor_appointment_principal ON audit.audit_entry IS 'Actor一致性：实际Appointment若存在必须属于同一实际Principal。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__on_behalf_appointment_principal
    FOREIGN KEY (tenant_id, on_behalf_of_appointment_id, on_behalf_of_principal_id)
    REFERENCES identity.appointment (tenant_id, appointment_id, principal_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__on_behalf_appointment_principal ON audit.audit_entry IS '代办一致性：被代表Appointment必须属于同一被代表Principal。';

ALTER TABLE audit.audit_entry
    ADD CONSTRAINT fk_audit_entry__authorization_scope_org
    FOREIGN KEY (tenant_id, authorization_scope_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_audit_entry__authorization_scope_org ON audit.audit_entry IS '授权组织Scope若存在，必须属于同一租户。';

ALTER TABLE responsibility.task_occurrence
    ADD CONSTRAINT fk_task_occurrence__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_task_occurrence__tenant ON responsibility.task_occurrence IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE responsibility.task_occurrence
    ADD CONSTRAINT fk_task_occurrence__owner_appointment
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_task_occurrence__owner_appointment ON responsibility.task_occurrence IS '任务Owner必须是同租户已存在的任职。';

ALTER TABLE responsibility.decision_record
    ADD CONSTRAINT fk_decision_record__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_decision_record__tenant ON responsibility.decision_record IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE responsibility.decision_record
    ADD CONSTRAINT fk_decision_record__task_occurrence
    FOREIGN KEY (tenant_id, task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_decision_record__task_occurrence ON responsibility.decision_record IS '决策必须属于同租户已存在的待办。';

ALTER TABLE responsibility.decision_record
    ADD CONSTRAINT fk_decision_record__predecessor
    FOREIGN KEY (tenant_id, predecessor_decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_decision_record__predecessor ON responsibility.decision_record IS '版本链：后续决定必须引用同租户直接前序。';

ALTER TABLE responsibility.decision_record
    ADD CONSTRAINT fk_decision_record__decided_by_appointment
    FOREIGN KEY (tenant_id, decided_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_decision_record__decided_by_appointment ON responsibility.decision_record IS '决策人任职必须存在于同一租户。';

ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_wait_receipt__tenant ON responsibility.wait_receipt IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__task_occurrence
    FOREIGN KEY (tenant_id, task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_wait_receipt__task_occurrence ON responsibility.wait_receipt IS '等待回执必须属于同租户已存在的待办。';

ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__recorded_by_appointment
    FOREIGN KEY (tenant_id, recorded_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_wait_receipt__recorded_by_appointment ON responsibility.wait_receipt IS '记录人任职必须存在于同一租户。';

ALTER TABLE responsibility.action_draft
    ADD CONSTRAINT fk_action_draft__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_action_draft__tenant ON responsibility.action_draft IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE responsibility.action_draft
    ADD CONSTRAINT fk_action_draft__task_occurrence
    FOREIGN KEY (tenant_id, task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_action_draft__task_occurrence ON responsibility.action_draft IS '行动草案必须属于同租户已存在的待办。';

ALTER TABLE responsibility.action_draft
    ADD CONSTRAINT fk_action_draft__created_by_appointment
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_action_draft__created_by_appointment ON responsibility.action_draft IS '草案创建人任职必须存在于同一租户。';

ALTER TABLE responsibility.action_draft
    ADD CONSTRAINT fk_action_draft__confirmed_by_appointment
    FOREIGN KEY (tenant_id, confirmed_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_action_draft__confirmed_by_appointment ON responsibility.action_draft IS '草案确认人任职若存在，必须属于同一租户。';

ALTER TABLE execution.command_execution_slot
    ADD CONSTRAINT fk_command_execution_slot__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_command_execution_slot__tenant ON execution.command_execution_slot IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE execution.command_receipt
    ADD CONSTRAINT fk_command_receipt__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_command_receipt__tenant ON execution.command_receipt IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE execution.command_receipt
    ADD CONSTRAINT fk_command_receipt__command_execution_slot
    FOREIGN KEY (tenant_id, command_execution_slot_id)
    REFERENCES execution.command_execution_slot (tenant_id, command_execution_slot_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_command_receipt__command_execution_slot ON execution.command_receipt IS '命令归属：终态回执必须关联同租户已存在的永久命令占位。';

ALTER TABLE execution.domain_event
    ADD CONSTRAINT fk_domain_event__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_domain_event__tenant ON execution.domain_event IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE execution.domain_event_outbox
    ADD CONSTRAINT fk_domain_event_outbox__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_domain_event_outbox__tenant ON execution.domain_event_outbox IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE execution.domain_event_outbox
    ADD CONSTRAINT fk_domain_event_outbox__domain_event
    FOREIGN KEY (tenant_id, domain_event_id)
    REFERENCES execution.domain_event (tenant_id, domain_event_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_domain_event_outbox__domain_event ON execution.domain_event_outbox IS '强关系例外：Outbox投递必须关联同租户已存在的领域事件。';

ALTER TABLE external_action.external_action
    ADD CONSTRAINT fk_external_action__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_external_action__tenant ON external_action.external_action IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE external_action.external_action_outbox
    ADD CONSTRAINT fk_external_action_outbox__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_external_action_outbox__tenant ON external_action.external_action_outbox IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE external_action.external_action_outbox
    ADD CONSTRAINT fk_external_action_outbox__external_action
    FOREIGN KEY (tenant_id, external_action_id)
    REFERENCES external_action.external_action (tenant_id, external_action_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_external_action_outbox__external_action ON external_action.external_action_outbox IS '强关系例外：Outbox工作项必须关联同租户已存在的外部动作。';

ALTER TABLE external_action.provider_inbox
    ADD CONSTRAINT fk_provider_inbox__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_provider_inbox__tenant ON external_action.provider_inbox IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE external_action.provider_inbox
    ADD CONSTRAINT fk_provider_inbox__external_action
    FOREIGN KEY (tenant_id, external_action_id)
    REFERENCES external_action.external_action (tenant_id, external_action_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_provider_inbox__external_action ON external_action.provider_inbox IS '准确关联：仅能物理关联同租户ExternalAction，Subject一致性由固定内部命令复验。';

ALTER TABLE evidence.upload_session
    ADD CONSTRAINT fk_upload_session__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_upload_session__tenant ON evidence.upload_session IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE evidence.upload_session
    ADD CONSTRAINT fk_upload_session__creator
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_upload_session__creator ON evidence.upload_session IS '会话创建主体必须是同租户准确Appointment。';

ALTER TABLE evidence.received_source_object
    ADD CONSTRAINT fk_received_source_object__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_received_source_object__tenant ON evidence.received_source_object IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE evidence.received_source_object
    ADD CONSTRAINT fk_received_source_object__upload_session
    FOREIGN KEY (tenant_id, upload_session_id)
    REFERENCES evidence.upload_session (tenant_id, upload_session_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_received_source_object__upload_session ON evidence.received_source_object IS '证据链第一段：来源对象必须强关联同租户上传会话。';

ALTER TABLE evidence.evidence_submission
    ADD CONSTRAINT fk_evidence_submission__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_submission__tenant ON evidence.evidence_submission IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE evidence.evidence_submission
    ADD CONSTRAINT fk_evidence_submission__received_source_object
    FOREIGN KEY (tenant_id, received_source_object_id)
    REFERENCES evidence.received_source_object (tenant_id, received_source_object_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_submission__received_source_object ON evidence.evidence_submission IS '证据链第二段：证据提交必须强关联同租户接收来源对象。';

ALTER TABLE evidence.evidence_submission
    ADD CONSTRAINT fk_evidence_submission__submitter
    FOREIGN KEY (tenant_id, submitted_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_submission__submitter ON evidence.evidence_submission IS '提交主体必须是同租户准确Appointment。';

ALTER TABLE evidence.evidence_binding
    ADD CONSTRAINT fk_evidence_binding__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_binding__tenant ON evidence.evidence_binding IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE evidence.evidence_binding
    ADD CONSTRAINT fk_evidence_binding__evidence_submission
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_binding__evidence_submission ON evidence.evidence_binding IS '证据链第三段：证据绑定必须强关联同租户不可变提交。';

ALTER TABLE evidence.evidence_binding
    ADD CONSTRAINT fk_evidence_binding__binder
    FOREIGN KEY (tenant_id, bound_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_binding__binder ON evidence.evidence_binding IS '绑定主体必须是同租户准确Appointment。';

ALTER TABLE evidence.evidence_binding
    ADD CONSTRAINT fk_evidence_binding__revoker
    FOREIGN KEY (tenant_id, revoked_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_evidence_binding__revoker ON evidence.evidence_binding IS '撤回主体若存在必须是同租户准确Appointment。';

ALTER TABLE party.party
    ADD CONSTRAINT fk_party__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_party__tenant ON party.party IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE party.party
    ADD CONSTRAINT fk_party__merged_into_party
    FOREIGN KEY (tenant_id, merged_into_party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_party__merged_into_party ON party.party IS '一跳合并：合并行直接关联同租户目标主体；CommandRuntime必须复验目标仍为ACTIVE。';

ALTER TABLE lead.lead
    ADD CONSTRAINT fk_lead__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead__tenant ON lead.lead IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE lead.lead
    ADD CONSTRAINT fk_lead__parsed_party
    FOREIGN KEY (tenant_id, parsed_party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead__parsed_party ON lead.lead IS 'Party解析关系：解析结果必须指向同租户Party。';

ALTER TABLE lead.lead
    ADD CONSTRAINT fk_lead__current_assignment
    FOREIGN KEY (tenant_id, current_assignment_id)
    REFERENCES lead.lead_assignment (tenant_id, lead_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead__current_assignment ON lead.lead IS '当前分派关系：当前指针必须指向同租户LeadAssignment；所属Lead一致性由命令提交前复验。';

ALTER TABLE lead.lead_assignment
    ADD CONSTRAINT fk_lead_assignment__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_assignment__tenant ON lead.lead_assignment IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE lead.lead_assignment
    ADD CONSTRAINT fk_lead_assignment__lead
    FOREIGN KEY (tenant_id, lead_id)
    REFERENCES lead.lead (tenant_id, lead_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_assignment__lead ON lead.lead_assignment IS 'Lead关系：分派必须属于同租户已存在Lead。';

ALTER TABLE lead.lead_assignment
    ADD CONSTRAINT fk_lead_assignment__previous_assignment
    FOREIGN KEY (tenant_id, previous_assignment_id)
    REFERENCES lead.lead_assignment (tenant_id, lead_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_assignment__previous_assignment ON lead.lead_assignment IS '前序关系：非链首分派必须引用同租户前序Assignment；同属一个Lead由提交前复验。';

ALTER TABLE lead.lead_assignment
    ADD CONSTRAINT fk_lead_assignment__owner_appointment
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_assignment__owner_appointment ON lead.lead_assignment IS 'Owner关系：承接人必须是同租户Appointment。';

ALTER TABLE lead.lead_contact_result
    ADD CONSTRAINT fk_lead_contact_result__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_contact_result__tenant ON lead.lead_contact_result IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE lead.lead_contact_result
    ADD CONSTRAINT fk_lead_contact_result__lead
    FOREIGN KEY (tenant_id, lead_id)
    REFERENCES lead.lead (tenant_id, lead_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_contact_result__lead ON lead.lead_contact_result IS 'Lead关系：联系结果必须属于同租户Lead。';

ALTER TABLE lead.lead_contact_result
    ADD CONSTRAINT fk_lead_contact_result__lead_assignment
    FOREIGN KEY (tenant_id, lead_assignment_id)
    REFERENCES lead.lead_assignment (tenant_id, lead_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_contact_result__lead_assignment ON lead.lead_contact_result IS '分派关系：联系结果必须绑定同租户准确LeadAssignment，Lead一致性由提交前复验。';

ALTER TABLE lead.lead_contact_result
    ADD CONSTRAINT fk_lead_contact_result__contact_task
    FOREIGN KEY (tenant_id, contact_task_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_contact_result__contact_task ON lead.lead_contact_result IS '任务关系：结果必须关联同租户TaskOccurrence；CONTACT_LEAD类型由运行时复验。';

ALTER TABLE lead.lead_contact_result
    ADD CONSTRAINT fk_lead_contact_result__evidence_submission
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_lead_contact_result__evidence_submission ON lead.lead_contact_result IS '证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__tenant ON opportunity.opportunity IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__source_lead
    FOREIGN KEY (tenant_id, source_lead_id)
    REFERENCES lead.lead (tenant_id, lead_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__source_lead ON opportunity.opportunity IS '来源关系：Opportunity必须引用同租户准确Lead。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__source_assignment
    FOREIGN KEY (tenant_id, source_assignment_id)
    REFERENCES lead.lead_assignment (tenant_id, lead_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__source_assignment ON opportunity.opportunity IS '来源关系：Opportunity必须沿同租户LeadAssignment形成。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__owner_appointment
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__owner_appointment ON opportunity.opportunity IS 'Owner关系：Opportunity Owner必须为同租户Appointment。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__source_contact_result
    FOREIGN KEY (tenant_id, source_contact_result_id)
    REFERENCES lead.lead_contact_result (tenant_id, lead_contact_result_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__source_contact_result ON opportunity.opportunity IS '资格来源：Opportunity必须引用同租户准确LeadContactResult。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__assignment_path
    FOREIGN KEY (tenant_id, source_assignment_id, source_lead_id, owner_appointment_id)
    REFERENCES lead.lead_assignment (tenant_id, lead_assignment_id, lead_id, owner_appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__assignment_path ON opportunity.opportunity IS '销售路径：来源Assignment必须同时属于来源Lead并冻结同一Owner。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__contact_path
    FOREIGN KEY (tenant_id, source_contact_result_id, source_lead_id, source_assignment_id)
    REFERENCES lead.lead_contact_result (tenant_id, lead_contact_result_id, lead_id, lead_assignment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__contact_path ON opportunity.opportunity IS '资格路径：来源ContactResult必须同时属于来源Lead和Assignment；CONNECTED_VALID由提交前守卫复验。';

ALTER TABLE opportunity.opportunity
    ADD CONSTRAINT fk_opportunity__current_quote_revision
    FOREIGN KEY (tenant_id, current_quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity__current_quote_revision ON opportunity.opportunity IS '当前报价关系：指针必须指向同租户QuoteRevision；属于本Opportunity由提交前复验。';

ALTER TABLE opportunity.opportunity_participation
    ADD CONSTRAINT fk_opportunity_participation__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity_participation__tenant ON opportunity.opportunity_participation IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.opportunity_participation
    ADD CONSTRAINT fk_opportunity_participation__opportunity
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity_participation__opportunity ON opportunity.opportunity_participation IS 'Opportunity关系：参与方角色必须属于同租户Opportunity。';

ALTER TABLE opportunity.opportunity_participation
    ADD CONSTRAINT fk_opportunity_participation__party
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity_participation__party ON opportunity.opportunity_participation IS 'Party关系：参与方必须指向同租户Party。';

ALTER TABLE opportunity.opportunity_progress
    ADD CONSTRAINT fk_opportunity_progress__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity_progress__tenant ON opportunity.opportunity_progress IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.opportunity_progress
    ADD CONSTRAINT fk_opportunity_progress__opportunity
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_opportunity_progress__opportunity ON opportunity.opportunity_progress IS 'Opportunity关系：进展必须属于同租户Opportunity。';

ALTER TABLE opportunity.quote_revision
    ADD CONSTRAINT fk_quote_revision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_revision__tenant ON opportunity.quote_revision IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_revision
    ADD CONSTRAINT fk_quote_revision__opportunity
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_revision__opportunity ON opportunity.quote_revision IS 'Opportunity关系：报价版本必须属于同租户Opportunity。';

ALTER TABLE opportunity.quote_revision
    ADD CONSTRAINT fk_quote_revision__predecessor
    FOREIGN KEY (tenant_id, predecessor_quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_revision__predecessor ON opportunity.quote_revision IS '报价版本链：后续版本必须引用同租户直接前序。';

ALTER TABLE opportunity.quote_revision
    ADD CONSTRAINT fk_quote_revision__action_draft
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_revision__action_draft ON opportunity.quote_revision IS '候选输入：报价版本包必须引用同租户准确确认草案。';

ALTER TABLE opportunity.quote_revision
    ADD CONSTRAINT fk_quote_revision__creator
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_revision__creator ON opportunity.quote_revision IS '创建主体：报价版本必须记录同租户准确Appointment。';

ALTER TABLE opportunity.quote_service_scope
    ADD CONSTRAINT fk_quote_service_scope__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_service_scope__tenant ON opportunity.quote_service_scope IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_service_scope
    ADD CONSTRAINT fk_quote_service_scope__quote_revision
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_service_scope__quote_revision ON opportunity.quote_service_scope IS '版本包关系：服务范围必须属于同租户QuoteRevision。';

ALTER TABLE opportunity.quote_line
    ADD CONSTRAINT fk_quote_line__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_line__tenant ON opportunity.quote_line IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_line
    ADD CONSTRAINT fk_quote_line__quote_revision
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_line__quote_revision ON opportunity.quote_line IS '版本包关系：计价行必须属于同租户QuoteRevision。';

ALTER TABLE opportunity.quote_line
    ADD CONSTRAINT fk_quote_line__service_scope
    FOREIGN KEY (tenant_id, quote_service_scope_id)
    REFERENCES opportunity.quote_service_scope (tenant_id, quote_service_scope_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_line__service_scope ON opportunity.quote_line IS '服务范围关系：非空时计价行必须关联同租户QuoteServiceScope。';

ALTER TABLE opportunity.quote_payment_term
    ADD CONSTRAINT fk_quote_payment_term__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_payment_term__tenant ON opportunity.quote_payment_term IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_payment_term
    ADD CONSTRAINT fk_quote_payment_term__quote_revision
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_payment_term__quote_revision ON opportunity.quote_payment_term IS '版本包关系：付款条件必须属于同租户QuoteRevision。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__tenant ON opportunity.quote_issue IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__quote_revision
    FOREIGN KEY (tenant_id, quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__quote_revision ON opportunity.quote_issue IS '报价版本关系：Issue必须发出同租户不可变QuoteRevision。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__recipient_participation
    FOREIGN KEY (tenant_id, recipient_participation_id)
    REFERENCES opportunity.opportunity_participation (tenant_id, opportunity_participation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__recipient_participation ON opportunity.quote_issue IS '收件人关系：Issue必须指向同租户冻结Participation。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__external_action
    FOREIGN KEY (tenant_id, external_action_id)
    REFERENCES external_action.external_action (tenant_id, external_action_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__external_action ON opportunity.quote_issue IS '外部动作关系：非空时送达必须关联同租户ExternalAction。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__provider_inbox
    FOREIGN KEY (tenant_id, provider_inbox_id)
    REFERENCES external_action.provider_inbox (tenant_id, provider_inbox_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__provider_inbox ON opportunity.quote_issue IS 'Provider证明：非空时必须引用同租户验签消息。';

ALTER TABLE opportunity.quote_issue
    ADD CONSTRAINT fk_quote_issue__replaces
    FOREIGN KEY (tenant_id, replaces_quote_issue_id)
    REFERENCES opportunity.quote_issue (tenant_id, quote_issue_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_issue__replaces ON opportunity.quote_issue IS '替代关系：新Issue必须指向同租户准确旧Issue；同一收件人及版本顺序由提交前重验。';

ALTER TABLE opportunity.quote_response
    ADD CONSTRAINT fk_quote_response__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response__tenant ON opportunity.quote_response IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE opportunity.quote_response
    ADD CONSTRAINT fk_quote_response__issue
    FOREIGN KEY (tenant_id, quote_issue_id)
    REFERENCES opportunity.quote_issue (tenant_id, quote_issue_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response__issue ON opportunity.quote_response IS 'Issue关系：响应必须指向同租户准确不可变QuoteIssue。';

ALTER TABLE opportunity.quote_response
    ADD CONSTRAINT fk_quote_response__provider_inbox
    FOREIGN KEY (tenant_id, provider_inbox_id)
    REFERENCES external_action.provider_inbox (tenant_id, provider_inbox_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response__provider_inbox ON opportunity.quote_response IS 'Provider来源：非空时必须引用同租户验签消息。';

ALTER TABLE opportunity.quote_response
    ADD CONSTRAINT fk_quote_response__evidence_submission
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response__evidence_submission ON opportunity.quote_response IS '证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。';

ALTER TABLE opportunity.quote_response
    ADD CONSTRAINT fk_quote_response__recorder
    FOREIGN KEY (tenant_id, recorded_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_quote_response__recorder ON opportunity.quote_response IS '人工确认：非空时必须引用同租户准确Appointment。';

ALTER TABLE conflict.conflict_review
    ADD CONSTRAINT fk_conflict_review__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_review__tenant ON conflict.conflict_review IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE conflict.conflict_review_party
    ADD CONSTRAINT fk_conflict_review_party__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_review_party__tenant ON conflict.conflict_review_party IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE conflict.conflict_review_party
    ADD CONSTRAINT fk_conflict_review_party__conflict_review
    FOREIGN KEY (tenant_id, conflict_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_review_party__conflict_review ON conflict.conflict_review_party IS 'Review关系：范围参与方必须属于同租户ConflictReview。';

ALTER TABLE conflict.conflict_review_party
    ADD CONSTRAINT fk_conflict_review_party__party
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_review_party__party ON conflict.conflict_review_party IS 'Party关系：审查范围参与方必须指向同租户Party。';

ALTER TABLE conflict.conflict_finding
    ADD CONSTRAINT fk_conflict_finding__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_finding__tenant ON conflict.conflict_finding IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE conflict.conflict_finding
    ADD CONSTRAINT fk_conflict_finding__conflict_review
    FOREIGN KEY (tenant_id, conflict_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_finding__conflict_review ON conflict.conflict_finding IS 'Review关系：Finding必须属于同租户ConflictReview。';

ALTER TABLE conflict.conflict_finding
    ADD CONSTRAINT fk_conflict_finding__review_party
    FOREIGN KEY (tenant_id, conflict_review_party_id)
    REFERENCES conflict.conflict_review_party (tenant_id, conflict_review_party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_finding__review_party ON conflict.conflict_finding IS '范围Party关系：Finding必须命中同租户ConflictReviewParty。';

ALTER TABLE conflict.conflict_finding
    ADD CONSTRAINT fk_conflict_finding__evidence_submission
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_conflict_finding__evidence_submission ON conflict.conflict_finding IS '证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract__tenant ON contract.contract IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__opportunity
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract__opportunity ON contract.contract IS '来源完整性：合同必须属于同租户准确商机。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__accepted_quote_response
    FOREIGN KEY (tenant_id, accepted_quote_response_id)
    REFERENCES opportunity.quote_response (tenant_id, quote_response_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract__accepted_quote_response ON contract.contract IS '来源完整性：合同必须引用同租户准确接受回应。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__current_revision
    FOREIGN KEY (tenant_id, current_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_contract__current_revision ON contract.contract IS '当前版本槽：必须指向本合同的准确版本，归属关系由延迟守卫复验。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__approved_revision
    FOREIGN KEY (tenant_id, approved_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_contract__approved_revision ON contract.contract IS '批准槽：必须指向同租户准确合同版本。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__execution
    FOREIGN KEY (tenant_id, contract_execution_id)
    REFERENCES contract.contract_execution (tenant_id, contract_execution_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_contract__execution ON contract.contract IS '执行槽：必须指向同租户唯一合同执行事实。';

ALTER TABLE contract.contract
    ADD CONSTRAINT fk_contract__termination
    FOREIGN KEY (tenant_id, contract_termination_id)
    REFERENCES contract.contract_termination (tenant_id, contract_termination_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_contract__termination ON contract.contract IS '终止槽：必须指向同租户准确终止事实。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__tenant ON contract.contract_revision IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__contract
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__contract ON contract.contract_revision IS '归属完整性：合同版本必须属于同租户合同。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__predecessor
    FOREIGN KEY (tenant_id, predecessor_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__predecessor ON contract.contract_revision IS '版本链：后续版本准确引用同租户直接前序。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__action_draft
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__action_draft ON contract.contract_revision IS '输入来源：版本包必须引用准确确认草案。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__quote_revision
    FOREIGN KEY (tenant_id, source_quote_revision_id)
    REFERENCES opportunity.quote_revision (tenant_id, quote_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__quote_revision ON contract.contract_revision IS '报价来源：版本包必须引用准确报价版本。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__quote_response
    FOREIGN KEY (tenant_id, source_quote_response_id)
    REFERENCES opportunity.quote_response (tenant_id, quote_response_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__quote_response ON contract.contract_revision IS '接受来源：版本包必须引用准确报价回应。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__body_evidence
    FOREIGN KEY (tenant_id, body_evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__body_evidence ON contract.contract_revision IS '正文来源：版本包必须引用准确EvidenceSubmission。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__pre_contract_review
    FOREIGN KEY (tenant_id, pre_contract_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__pre_contract_review ON contract.contract_revision IS '审查来源：版本包必须引用独立PRE_CONTRACT审查。';

ALTER TABLE contract.contract_revision
    ADD CONSTRAINT fk_contract_revision__creator
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_revision__creator ON contract.contract_revision IS '创建主体：版本包必须记录同租户准确任职。';

ALTER TABLE contract.contract_participation
    ADD CONSTRAINT fk_contract_participation__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_participation__tenant ON contract.contract_participation IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_participation
    ADD CONSTRAINT fk_contract_participation__contract_revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_participation__contract_revision ON contract.contract_participation IS '版本归属：参与项必须属于准确合同版本。';

ALTER TABLE contract.contract_participation
    ADD CONSTRAINT fk_contract_participation__party
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_participation__party ON contract.contract_participation IS '主体完整性：参与项必须引用同租户Party。';

ALTER TABLE contract.contract_participation
    ADD CONSTRAINT fk_contract_participation__source_opportunity_participation
    FOREIGN KEY (tenant_id, source_opportunity_participation_id)
    REFERENCES opportunity.opportunity_participation (tenant_id, opportunity_participation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_participation__source_opportunity_participation ON contract.contract_participation IS '销售来源：可选引用准确商机参与项。';

ALTER TABLE contract.contract_fee_term
    ADD CONSTRAINT fk_contract_fee_term__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_fee_term__tenant ON contract.contract_fee_term IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_fee_term
    ADD CONSTRAINT fk_contract_fee_term__contract_revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_fee_term__contract_revision ON contract.contract_fee_term IS '版本归属：费用条款必须属于准确合同版本。';

ALTER TABLE contract.contract_fee_term
    ADD CONSTRAINT fk_contract_fee_term__source_quote_line
    FOREIGN KEY (tenant_id, source_quote_line_id)
    REFERENCES opportunity.quote_line (tenant_id, quote_line_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_fee_term__source_quote_line ON contract.contract_fee_term IS '报价来源：可选引用准确报价行。';

ALTER TABLE contract.payment_gate
    ADD CONSTRAINT fk_payment_gate__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_gate__tenant ON contract.payment_gate IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.payment_gate
    ADD CONSTRAINT fk_payment_gate__contract_revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_gate__contract_revision ON contract.payment_gate IS '版本归属：门禁必须属于准确合同版本。';

ALTER TABLE contract.payment_gate
    ADD CONSTRAINT fk_payment_gate__risk_decision
    FOREIGN KEY (tenant_id, risk_decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_gate__risk_decision ON contract.payment_gate IS '风险依据：风险门禁满足时必须引用准确决定。';

ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__tenant ON contract.signature_plan IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__contract_revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__contract_revision ON contract.signature_plan IS '版本归属：签署槽必须属于准确合同版本。';

ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__participation
    FOREIGN KEY (tenant_id, contract_participation_id)
    REFERENCES contract.contract_participation (tenant_id, contract_participation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__participation ON contract.signature_plan IS '参与方归属：签署槽必须引用准确合同参与项。';

ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__signer_party
    FOREIGN KEY (tenant_id, signer_party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__signer_party ON contract.signature_plan IS '签署主体：签署槽必须引用同租户Party。';

ALTER TABLE contract.signature_plan
    ADD CONSTRAINT fk_signature_plan__participation_path
    FOREIGN KEY (tenant_id, contract_participation_id, contract_revision_id, signer_party_id)
    REFERENCES contract.contract_participation (tenant_id, contract_participation_id, contract_revision_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_signature_plan__participation_path ON contract.signature_plan IS '签署计划路径：参与项必须属于同一合同版本且其Party就是计划签署主体。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__tenant ON contract.contract_signature IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__contract_revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__contract_revision ON contract.contract_signature IS '版本归属：签署必须对应准确合同版本。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__plan
    FOREIGN KEY (tenant_id, signature_plan_id)
    REFERENCES contract.signature_plan (tenant_id, signature_plan_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__plan ON contract.contract_signature IS '计划归属：签署必须对应准确签署槽。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__evidence
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__evidence ON contract.contract_signature IS '证据来源：签署必须引用准确EvidenceSubmission。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__external_action
    FOREIGN KEY (tenant_id, external_action_id)
    REFERENCES external_action.external_action (tenant_id, external_action_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__external_action ON contract.contract_signature IS '外部动作：可选引用准确外部签署尝试。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__provider_inbox
    FOREIGN KEY (tenant_id, provider_inbox_id)
    REFERENCES external_action.provider_inbox (tenant_id, provider_inbox_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__provider_inbox ON contract.contract_signature IS 'Provider证明：可选引用准确可信入站消息。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__signer_party
    FOREIGN KEY (tenant_id, signer_party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__signer_party ON contract.contract_signature IS '签署主体：实际签署人必须是同租户Party。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__revoker
    FOREIGN KEY (tenant_id, revoked_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__revoker ON contract.contract_signature IS '撤回主体：撤回必须记录准确任职。';

ALTER TABLE contract.contract_signature
    ADD CONSTRAINT fk_contract_signature__plan_path
    FOREIGN KEY (tenant_id, signature_plan_id, contract_revision_id, signer_party_id)
    REFERENCES contract.signature_plan (tenant_id, signature_plan_id, contract_revision_id, signer_party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_signature__plan_path ON contract.contract_signature IS '签署路径：签署事实的Plan、版本和实际Party必须完全一致。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__tenant ON contract.contract_execution IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__contract
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__contract ON contract.contract_execution IS '合同归属：执行事实必须属于准确合同。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__revision ON contract.contract_execution IS '版本归属：执行事实必须引用准确合同版本。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__revision_contract
    FOREIGN KEY (tenant_id, contract_revision_id, contract_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__revision_contract ON contract.contract_execution IS '执行归属：被执行Revision必须属于同一Contract。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__archive_evidence
    FOREIGN KEY (tenant_id, archive_evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__archive_evidence ON contract.contract_execution IS '归档证据：执行事实必须引用准确EvidenceSubmission。';

ALTER TABLE contract.contract_execution
    ADD CONSTRAINT fk_contract_execution__executor
    FOREIGN KEY (tenant_id, executed_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_execution__executor ON contract.contract_execution IS '执行主体：执行事实必须记录准确任职。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__tenant ON contract.payment_confirmation IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__contract
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__contract ON contract.payment_confirmation IS '合同归属：付款确认必须属于准确合同。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__revision ON contract.payment_confirmation IS '版本归属：付款确认必须冻结准确合同版本。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__revision_contract
    FOREIGN KEY (tenant_id, contract_revision_id, contract_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__revision_contract ON contract.payment_confirmation IS '付款归属：Confirmation引用的Revision必须属于同一Contract。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__external_action
    FOREIGN KEY (tenant_id, external_action_id)
    REFERENCES external_action.external_action (tenant_id, external_action_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__external_action ON contract.payment_confirmation IS '外部动作：可选引用准确外部资金动作。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__provider_inbox
    FOREIGN KEY (tenant_id, provider_inbox_id)
    REFERENCES external_action.provider_inbox (tenant_id, provider_inbox_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__provider_inbox ON contract.payment_confirmation IS 'Provider来源：可选引用准确可信消息。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__evidence
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__evidence ON contract.payment_confirmation IS '证据来源：可选引用准确EvidenceSubmission。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__reverses
    FOREIGN KEY (tenant_id, reverses_payment_confirmation_id)
    REFERENCES contract.payment_confirmation (tenant_id, payment_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__reverses ON contract.payment_confirmation IS '反向事实：撤销或退款必须引用同租户原付款确认。';

ALTER TABLE contract.payment_confirmation
    ADD CONSTRAINT fk_payment_confirmation__recorder
    FOREIGN KEY (tenant_id, recorded_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_payment_confirmation__recorder ON contract.payment_confirmation IS '记录主体：付款确认必须记录准确任职。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__tenant ON contract.contract_termination IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__contract
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__contract ON contract.contract_termination IS '合同归属：终止事实必须属于准确合同。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__revision
    FOREIGN KEY (tenant_id, contract_revision_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__revision ON contract.contract_termination IS '版本归属：终止事实必须冻结准确合同版本。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__execution
    FOREIGN KEY (tenant_id, contract_execution_id)
    REFERENCES contract.contract_execution (tenant_id, contract_execution_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__execution ON contract.contract_termination IS '执行来源：执行后终止必须引用准确执行事实。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__revision_contract
    FOREIGN KEY (tenant_id, contract_revision_id, contract_id)
    REFERENCES contract.contract_revision (tenant_id, contract_revision_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__revision_contract ON contract.contract_termination IS '终止版本归属：取消或终止采用的Revision必须属于同一Contract。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__execution_path
    FOREIGN KEY (tenant_id, contract_execution_id, contract_id, contract_revision_id)
    REFERENCES contract.contract_execution (tenant_id, contract_execution_id, contract_id, contract_revision_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__execution_path ON contract.contract_termination IS '执行后终止路径：Execution、Contract和Revision必须完全一致；CANCELLED时空值按MATCH SIMPLE跳过。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__decision
    FOREIGN KEY (tenant_id, decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__decision ON contract.contract_termination IS '决定依据：终止事实必须引用准确授权决定。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__evidence
    FOREIGN KEY (tenant_id, evidence_submission_id)
    REFERENCES evidence.evidence_submission (tenant_id, evidence_submission_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__evidence ON contract.contract_termination IS '证据来源：可选引用准确终止材料。';

ALTER TABLE contract.contract_termination
    ADD CONSTRAINT fk_contract_termination__terminator
    FOREIGN KEY (tenant_id, terminated_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_contract_termination__terminator ON contract.contract_termination IS '执行主体：终止事实必须记录准确任职。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__tenant ON transfer.transfer_request IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__opportunity
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__opportunity ON transfer.transfer_request IS '销售来源：转案请求必须引用准确商机。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__contract
    FOREIGN KEY (tenant_id, contract_id)
    REFERENCES contract.contract (tenant_id, contract_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__contract ON transfer.transfer_request IS '合同来源：转案请求必须引用准确合同。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__contract_execution
    FOREIGN KEY (tenant_id, contract_execution_id)
    REFERENCES contract.contract_execution (tenant_id, contract_execution_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__contract_execution ON transfer.transfer_request IS '执行来源：转案请求必须引用准确合同执行事实。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__from_org
    FOREIGN KEY (tenant_id, from_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__from_org ON transfer.transfer_request IS '转出组织：必须是同租户当前组织树中的准确组织单元。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__to_org
    FOREIGN KEY (tenant_id, to_organization_unit_id)
    REFERENCES identity.organization_unit (tenant_id, organization_unit_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__to_org ON transfer.transfer_request IS '接收组织：必须是同租户当前组织树中的准确组织单元。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__accepted_snapshot
    FOREIGN KEY (tenant_id, accepted_snapshot_id)
    REFERENCES transfer.transfer_snapshot (tenant_id, transfer_snapshot_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_transfer_request__accepted_snapshot ON transfer.transfer_request IS '接收快照：必须引用本请求当前叶Snapshot，归属由延迟守卫复验。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__accept_decision
    FOREIGN KEY (tenant_id, accept_decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__accept_decision ON transfer.transfer_request IS '接收决定：必须引用准确ACCEPT DecisionRecord。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__creator
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__creator ON transfer.transfer_request IS '创建主体：转案请求必须记录准确任职。';

ALTER TABLE transfer.transfer_request
    ADD CONSTRAINT fk_transfer_request__contract_path
    FOREIGN KEY (tenant_id, contract_id, opportunity_id, contract_execution_id)
    REFERENCES contract.contract (tenant_id, contract_id, opportunity_id, contract_execution_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_request__contract_path ON transfer.transfer_request IS '转案合同主链：Opportunity、Contract及Execution必须来自同一已执行合同锚点。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__tenant ON transfer.transfer_snapshot IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__transfer_request
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__transfer_request ON transfer.transfer_snapshot IS '请求归属：快照必须属于准确转案请求。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__predecessor
    FOREIGN KEY (tenant_id, predecessor_snapshot_id)
    REFERENCES transfer.transfer_snapshot (tenant_id, transfer_snapshot_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__predecessor ON transfer.transfer_snapshot IS '补正链：补正快照必须引用同租户直接前序。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__submission_task
    FOREIGN KEY (tenant_id, submission_task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__submission_task ON transfer.transfer_snapshot IS '责任完成：快照必须完成准确提交Task。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__action_draft
    FOREIGN KEY (tenant_id, confirmed_action_draft_id)
    REFERENCES responsibility.action_draft (tenant_id, action_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__action_draft ON transfer.transfer_snapshot IS '输入来源：快照必须引用准确确认草案。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__pre_transfer_review
    FOREIGN KEY (tenant_id, pre_transfer_review_id)
    REFERENCES conflict.conflict_review (tenant_id, conflict_review_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__pre_transfer_review ON transfer.transfer_snapshot IS '审查来源：快照必须引用独立PRE_TRANSFER审查。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__previous_return_decision
    FOREIGN KEY (tenant_id, previous_return_decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__previous_return_decision ON transfer.transfer_snapshot IS '补正依据：后续快照必须引用前序RETURN决定。';

ALTER TABLE transfer.transfer_snapshot
    ADD CONSTRAINT fk_transfer_snapshot__submitter
    FOREIGN KEY (tenant_id, submitted_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_snapshot__submitter ON transfer.transfer_snapshot IS '提交主体：快照必须记录准确任职。';

ALTER TABLE transfer.transfer_return_item
    ADD CONSTRAINT fk_transfer_return_item__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_return_item__tenant ON transfer.transfer_return_item IS '租户边界：该记录必须属于一个已存在的租户。';

ALTER TABLE transfer.transfer_return_item
    ADD CONSTRAINT fk_transfer_return_item__transfer_request
    FOREIGN KEY (tenant_id, transfer_request_id)
    REFERENCES transfer.transfer_request (tenant_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_return_item__transfer_request ON transfer.transfer_return_item IS '请求归属：退回项必须属于准确转案请求。';

ALTER TABLE transfer.transfer_return_item
    ADD CONSTRAINT fk_transfer_return_item__reviewed_snapshot
    FOREIGN KEY (tenant_id, reviewed_snapshot_id)
    REFERENCES transfer.transfer_snapshot (tenant_id, transfer_snapshot_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_return_item__reviewed_snapshot ON transfer.transfer_return_item IS '审查对象：退回项必须引用准确已审快照。';

ALTER TABLE transfer.transfer_return_item
    ADD CONSTRAINT fk_transfer_return_item__snapshot_request
    FOREIGN KEY (tenant_id, reviewed_snapshot_id, transfer_request_id)
    REFERENCES transfer.transfer_snapshot (tenant_id, transfer_snapshot_id, transfer_request_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_return_item__snapshot_request ON transfer.transfer_return_item IS '退回路径：reviewedSnapshot必须属于本ReturnItem冻结的同一TransferRequest。';

ALTER TABLE transfer.transfer_return_item
    ADD CONSTRAINT fk_transfer_return_item__return_decision
    FOREIGN KEY (tenant_id, return_decision_record_id)
    REFERENCES responsibility.decision_record (tenant_id, decision_record_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_transfer_return_item__return_decision ON transfer.transfer_return_item IS '决定归属：退回项必须引用准确RETURN决定。';
