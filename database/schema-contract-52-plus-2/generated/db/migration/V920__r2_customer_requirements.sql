CREATE TABLE party.profile_version (
    tenant_id uuid NOT NULL,
    profile_version_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    party_type varchar(32) NOT NULL,
    canonical_name text NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_profile_version PRIMARY KEY (tenant_id, profile_version_id),
    CONSTRAINT ck_profile_version__revision CHECK (revision=0),
    CONSTRAINT uq_profile_version__party_revision UNIQUE (tenant_id, party_id, party_revision),
    CONSTRAINT ck_profile_version__party_revision CHECK (party_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_profile_version__type CHECK (party_type IN ('PERSON','ORGANIZATION')),
    CONSTRAINT ck_profile_version__name CHECK (length(btrim(canonical_name)) BETWEEN 1 AND 300)
);

COMMENT ON TABLE party.profile_version IS 'Fact Owner：PartyRuntime；主体资料不可变版本；复用Party身份，不建立第二主体库。';
COMMENT ON CONSTRAINT pk_profile_version ON party.profile_version IS '主键：在租户内唯一标识一条profile_version记录。';
COMMENT ON INDEX party.pk_profile_version IS '主键：在租户内唯一标识一条profile_version记录。';
COMMENT ON COLUMN party.profile_version.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN party.profile_version.profile_version_id IS '主体资料不可变版本；复用Party身份，不建立第二主体库。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN party.profile_version.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN party.profile_version.party_id IS '准确Party主体。';
COMMENT ON COLUMN party.profile_version.party_revision IS '保存时准确主体版本。';
COMMENT ON COLUMN party.profile_version.party_type IS '主体种类。';
COMMENT ON COLUMN party.profile_version.canonical_name IS '当时规范名称。';
COMMENT ON COLUMN party.profile_version.created_by_appointment_id IS '实际维护者。';
COMMENT ON COLUMN party.profile_version.created_at IS '资料版本形成时间。';
COMMENT ON CONSTRAINT ck_profile_version__revision ON party.profile_version IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_profile_version__party_revision ON party.profile_version IS '主体准确版本唯一。';
COMMENT ON INDEX party.uq_profile_version__party_revision IS '主体准确版本唯一。';
COMMENT ON CONSTRAINT ck_profile_version__party_revision ON party.profile_version IS '准确主体版本。';
COMMENT ON CONSTRAINT ck_profile_version__type ON party.profile_version IS '主体类型域。';
COMMENT ON CONSTRAINT ck_profile_version__name ON party.profile_version IS '必要名称范围。';
CREATE TABLE opportunity.customer_requirement_draft (
    tenant_id uuid NOT NULL,
    customer_requirement_draft_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    owner_appointment_id uuid NOT NULL,
    previous_draft_id uuid,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_customer_requirement_draft PRIMARY KEY (tenant_id, customer_requirement_draft_id),
    CONSTRAINT ck_customer_requirement_draft__revision CHECK (revision=0),
    CONSTRAINT ck_customer_requirement_draft__basis CHECK (responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_customer_requirement_draft__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_customer_requirement_draft__previous UNIQUE (tenant_id, previous_draft_id),
    CONSTRAINT ck_customer_requirement_draft__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE opportunity.customer_requirement_draft IS 'Fact Owner：OpportunityRuntime；责任人独立不可变草稿；不依赖普通任务且不会成为确认事实。';
COMMENT ON CONSTRAINT pk_customer_requirement_draft ON opportunity.customer_requirement_draft IS '主键：在租户内唯一标识一条customer_requirement_draft记录。';
COMMENT ON INDEX opportunity.pk_customer_requirement_draft IS '主键：在租户内唯一标识一条customer_requirement_draft记录。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.customer_requirement_draft_id IS '责任人独立不可变草稿；不依赖普通任务且不会成为确认事实。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.created_in_transaction IS '由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.opportunity_revision IS '读取的商机版本。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.responsibility_type IS '责任依据类型。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.responsibility_id IS '责任依据身份。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.responsibility_revision IS '责任依据版本。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.owner_appointment_id IS '保存/确认的当前负责人。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.previous_draft_id IS '准确前一保存；首次为空。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN opportunity.customer_requirement_draft.created_at IS '保存时间。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft__revision ON opportunity.customer_requirement_draft IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft__basis ON opportunity.customer_requirement_draft IS '准确责任和版本。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft__body ON opportunity.customer_requirement_draft IS '有界加密正文。';
COMMENT ON CONSTRAINT uq_customer_requirement_draft__previous ON opportunity.customer_requirement_draft IS '同一保存版本只能有一个后继。';
COMMENT ON INDEX opportunity.uq_customer_requirement_draft__previous IS '同一保存版本只能有一个后继。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft__body_digest_length ON opportunity.customer_requirement_draft IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE opportunity.customer_requirement_confirmation (
    tenant_id uuid NOT NULL,
    customer_requirement_confirmation_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    created_in_transaction xid8 DEFAULT pg_current_xact_id() NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_type varchar(64) NOT NULL,
    responsibility_id uuid NOT NULL,
    responsibility_revision bigint NOT NULL,
    owner_appointment_id uuid NOT NULL,
    draft_id uuid NOT NULL,
    previous_confirmation_id uuid,
    body_ciphertext bytea NOT NULL,
    body_digest bytea NOT NULL,
    confirmed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_customer_requirement_confirmation PRIMARY KEY (tenant_id, customer_requirement_confirmation_id),
    CONSTRAINT ck_customer_requirement_confirmation__revision CHECK (revision=0),
    CONSTRAINT ck_customer_requirement_confirmation__basis CHECK (responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_customer_requirement_confirmation__body CHECK (octet_length(body_ciphertext) BETWEEN 29 AND 131072),
    CONSTRAINT uq_customer_requirement_confirmation__draft UNIQUE (tenant_id, draft_id),
    CONSTRAINT uq_customer_requirement_confirmation__previous UNIQUE (tenant_id, previous_confirmation_id),
    CONSTRAINT ck_customer_requirement_confirmation__body_digest_length CHECK (octet_length(body_digest) = 32)
);

COMMENT ON TABLE opportunity.customer_requirement_confirmation IS 'Fact Owner：OpportunityRuntime；客户参与方及服务需求的不可变完整确认版本；不完成普通任务。';
COMMENT ON CONSTRAINT pk_customer_requirement_confirmation ON opportunity.customer_requirement_confirmation IS '主键：在租户内唯一标识一条customer_requirement_confirmation记录。';
COMMENT ON INDEX opportunity.pk_customer_requirement_confirmation IS '主键：在租户内唯一标识一条customer_requirement_confirmation记录。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.customer_requirement_confirmation_id IS '客户参与方及服务需求的不可变完整确认版本；不完成普通任务。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.created_in_transaction IS '由插入守卫强制写入顶层事务身份；子事务保存点不能改变集合冻结边界。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.opportunity_id IS '准确商机。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.opportunity_revision IS '读取的商机版本。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.responsibility_type IS '责任依据类型。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.responsibility_id IS '责任依据身份。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.responsibility_revision IS '责任依据版本。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.owner_appointment_id IS '保存/确认的当前负责人。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.draft_id IS '准确不可变草稿。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.previous_confirmation_id IS '前一完整确认；首次为空。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.body_ciphertext IS '联系方式、服务需求和显示快照密文；AAD绑定租户、商机及事实身份。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.body_digest IS '受保护规范正文完整性摘要。';
COMMENT ON COLUMN opportunity.customer_requirement_confirmation.confirmed_at IS '确认时间。';
COMMENT ON CONSTRAINT ck_customer_requirement_confirmation__revision ON opportunity.customer_requirement_confirmation IS '不可变保存版本。';
COMMENT ON CONSTRAINT ck_customer_requirement_confirmation__basis ON opportunity.customer_requirement_confirmation IS '准确责任和版本。';
COMMENT ON CONSTRAINT ck_customer_requirement_confirmation__body ON opportunity.customer_requirement_confirmation IS '有界加密正文。';
COMMENT ON CONSTRAINT uq_customer_requirement_confirmation__draft ON opportunity.customer_requirement_confirmation IS '一份草稿至多确认一次。';
COMMENT ON INDEX opportunity.uq_customer_requirement_confirmation__draft IS '一份草稿至多确认一次。';
COMMENT ON CONSTRAINT uq_customer_requirement_confirmation__previous ON opportunity.customer_requirement_confirmation IS '完整确认版本不允许分叉。';
COMMENT ON INDEX opportunity.uq_customer_requirement_confirmation__previous IS '完整确认版本不允许分叉。';
COMMENT ON CONSTRAINT ck_customer_requirement_confirmation__body_digest_length ON opportunity.customer_requirement_confirmation IS '摘要格式：body_digest必须保存32字节的规范二进制值。';
CREATE TABLE opportunity.customer_requirement_participant (
    tenant_id uuid NOT NULL,
    customer_requirement_participant_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    confirmation_id uuid NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    role varchar(32) NOT NULL,
    profile_version_id uuid NOT NULL,
    CONSTRAINT pk_customer_requirement_participant PRIMARY KEY (tenant_id, customer_requirement_participant_id),
    CONSTRAINT ck_customer_requirement_participant__revision CHECK (revision=0),
    CONSTRAINT uq_customer_requirement_participant__role UNIQUE (tenant_id, confirmation_id, party_id, role),
    CONSTRAINT ck_customer_requirement_participant__role CHECK (role IN ('CLIENT','OPPONENT','OTHER')),
    CONSTRAINT ck_customer_requirement_participant__party_revision CHECK (party_revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE opportunity.customer_requirement_participant IS 'Fact Owner：OpportunityRuntime；完整确认中的准确参与方；未知对方只存正文状态，不创建占位主体。';
COMMENT ON CONSTRAINT pk_customer_requirement_participant ON opportunity.customer_requirement_participant IS '主键：在租户内唯一标识一条customer_requirement_participant记录。';
COMMENT ON INDEX opportunity.pk_customer_requirement_participant IS '主键：在租户内唯一标识一条customer_requirement_participant记录。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.customer_requirement_participant_id IS '完整确认中的准确参与方；未知对方只存正文状态，不创建占位主体。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.confirmation_id IS '完整确认版本。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.party_id IS '准确主体。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.party_revision IS '当时主体版本。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.role IS '参与角色。';
COMMENT ON COLUMN opportunity.customer_requirement_participant.profile_version_id IS '准确不可变主体显示快照。';
COMMENT ON CONSTRAINT ck_customer_requirement_participant__revision ON opportunity.customer_requirement_participant IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_customer_requirement_participant__role ON opportunity.customer_requirement_participant IS '集合中主体角色唯一。';
COMMENT ON INDEX opportunity.uq_customer_requirement_participant__role IS '集合中主体角色唯一。';
COMMENT ON CONSTRAINT ck_customer_requirement_participant__role ON opportunity.customer_requirement_participant IS '具名参与方角色。';
COMMENT ON CONSTRAINT ck_customer_requirement_participant__party_revision ON opportunity.customer_requirement_participant IS '准确主体版本。';
CREATE TABLE opportunity.customer_requirement_draft_party (
    tenant_id uuid NOT NULL,
    customer_requirement_draft_party_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    draft_id uuid NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    CONSTRAINT pk_customer_requirement_draft_party PRIMARY KEY (tenant_id, customer_requirement_draft_party_id),
    CONSTRAINT ck_customer_requirement_draft_party__revision CHECK (revision=0),
    CONSTRAINT uq_customer_requirement_draft_party__party UNIQUE (tenant_id, draft_id, party_id),
    CONSTRAINT ck_customer_requirement_draft_party__party_revision CHECK (party_revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE opportunity.customer_requirement_draft_party IS 'Fact Owner：OpportunityRuntime；草稿准确主体来源集合；回执逐事实授权无需解密正文。';
COMMENT ON CONSTRAINT pk_customer_requirement_draft_party ON opportunity.customer_requirement_draft_party IS '主键：在租户内唯一标识一条customer_requirement_draft_party记录。';
COMMENT ON INDEX opportunity.pk_customer_requirement_draft_party IS '主键：在租户内唯一标识一条customer_requirement_draft_party记录。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.customer_requirement_draft_party_id IS '草稿准确主体来源集合；回执逐事实授权无需解密正文。标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.draft_id IS '准确不可变草稿。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.party_id IS '人工选定的准确主体。';
COMMENT ON COLUMN opportunity.customer_requirement_draft_party.party_revision IS '保存时准确主体版本。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft_party__revision ON opportunity.customer_requirement_draft_party IS '不可变保存版本。';
COMMENT ON CONSTRAINT uq_customer_requirement_draft_party__party ON opportunity.customer_requirement_draft_party IS '一份草稿每个主体来源唯一。';
COMMENT ON INDEX opportunity.uq_customer_requirement_draft_party__party IS '一份草稿每个主体来源唯一。';
COMMENT ON CONSTRAINT ck_customer_requirement_draft_party__party_revision ON opportunity.customer_requirement_draft_party IS '准确主体版本。';
ALTER TABLE party.profile_version
    ADD CONSTRAINT fk_profile_version__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_profile_version__tenant ON party.profile_version IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE party.profile_version
    ADD CONSTRAINT fk_profile_version__party_id
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_profile_version__party_id ON party.profile_version IS '同租户准确事实引用。';
ALTER TABLE party.profile_version
    ADD CONSTRAINT fk_profile_version__created_by_appointment_id
    FOREIGN KEY (tenant_id, created_by_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_profile_version__created_by_appointment_id ON party.profile_version IS '同租户准确事实引用。';
CREATE TRIGGER trg_profile_version__mutation_guard BEFORE UPDATE OR DELETE ON party.profile_version
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_profile_version__mutation_guard ON party.profile_version IS '不可变版本禁止改写或删除。';
REVOKE ALL ON party.profile_version FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON party.profile_version TO ${app_command_role};
GRANT SELECT ON party.profile_version TO ${app_query_role};
ALTER TABLE opportunity.customer_requirement_draft
    ADD CONSTRAINT fk_customer_requirement_draft__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft__tenant ON opportunity.customer_requirement_draft IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.customer_requirement_draft
    ADD CONSTRAINT fk_customer_requirement_draft__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft__opportunity_id ON opportunity.customer_requirement_draft IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_draft
    ADD CONSTRAINT fk_customer_requirement_draft__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft__owner_appointment_id ON opportunity.customer_requirement_draft IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_draft
    ADD CONSTRAINT fk_customer_requirement_draft__previous_draft_id
    FOREIGN KEY (tenant_id, previous_draft_id)
    REFERENCES opportunity.customer_requirement_draft (tenant_id, customer_requirement_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft__previous_draft_id ON opportunity.customer_requirement_draft IS '同租户准确事实引用。';
CREATE TRIGGER trg_customer_requirement_draft__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.customer_requirement_draft
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_customer_requirement_draft__mutation_guard ON opportunity.customer_requirement_draft IS '不可变版本禁止改写或删除。';
REVOKE ALL ON opportunity.customer_requirement_draft FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.customer_requirement_draft TO ${app_command_role};
GRANT SELECT ON opportunity.customer_requirement_draft TO ${app_query_role};
ALTER TABLE opportunity.customer_requirement_confirmation
    ADD CONSTRAINT fk_customer_requirement_confirmation__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_confirmation__tenant ON opportunity.customer_requirement_confirmation IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.customer_requirement_confirmation
    ADD CONSTRAINT fk_customer_requirement_confirmation__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_confirmation__opportunity_id ON opportunity.customer_requirement_confirmation IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_confirmation
    ADD CONSTRAINT fk_customer_requirement_confirmation__owner_appointment_id
    FOREIGN KEY (tenant_id, owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_confirmation__owner_appointment_id ON opportunity.customer_requirement_confirmation IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_confirmation
    ADD CONSTRAINT fk_customer_requirement_confirmation__draft_id
    FOREIGN KEY (tenant_id, draft_id)
    REFERENCES opportunity.customer_requirement_draft (tenant_id, customer_requirement_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_confirmation__draft_id ON opportunity.customer_requirement_confirmation IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_confirmation
    ADD CONSTRAINT fk_customer_requirement_confirmation__previous_confirmation_id
    FOREIGN KEY (tenant_id, previous_confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_confirmation__previous_confirmation_id ON opportunity.customer_requirement_confirmation IS '同租户准确事实引用。';
CREATE TRIGGER trg_customer_requirement_confirmation__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.customer_requirement_confirmation
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_customer_requirement_confirmation__mutation_guard ON opportunity.customer_requirement_confirmation IS '不可变版本禁止改写或删除。';
REVOKE ALL ON opportunity.customer_requirement_confirmation FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.customer_requirement_confirmation TO ${app_command_role};
GRANT SELECT ON opportunity.customer_requirement_confirmation TO ${app_query_role};
ALTER TABLE opportunity.customer_requirement_participant
    ADD CONSTRAINT fk_customer_requirement_participant__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_participant__tenant ON opportunity.customer_requirement_participant IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.customer_requirement_participant
    ADD CONSTRAINT fk_customer_requirement_participant__confirmation_id
    FOREIGN KEY (tenant_id, confirmation_id)
    REFERENCES opportunity.customer_requirement_confirmation (tenant_id, customer_requirement_confirmation_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_participant__confirmation_id ON opportunity.customer_requirement_participant IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_participant
    ADD CONSTRAINT fk_customer_requirement_participant__party_id
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_participant__party_id ON opportunity.customer_requirement_participant IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_participant
    ADD CONSTRAINT fk_customer_requirement_participant__profile_version_id
    FOREIGN KEY (tenant_id, profile_version_id)
    REFERENCES party.profile_version (tenant_id, profile_version_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_participant__profile_version_id ON opportunity.customer_requirement_participant IS '同租户准确事实引用。';
CREATE TRIGGER trg_customer_requirement_participant__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.customer_requirement_participant
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_customer_requirement_participant__mutation_guard ON opportunity.customer_requirement_participant IS '不可变版本禁止改写或删除。';
REVOKE ALL ON opportunity.customer_requirement_participant FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.customer_requirement_participant TO ${app_command_role};
GRANT SELECT ON opportunity.customer_requirement_participant TO ${app_query_role};
ALTER TABLE opportunity.customer_requirement_draft_party
    ADD CONSTRAINT fk_customer_requirement_draft_party__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft_party__tenant ON opportunity.customer_requirement_draft_party IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.customer_requirement_draft_party
    ADD CONSTRAINT fk_customer_requirement_draft_party__draft_id
    FOREIGN KEY (tenant_id, draft_id)
    REFERENCES opportunity.customer_requirement_draft (tenant_id, customer_requirement_draft_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft_party__draft_id ON opportunity.customer_requirement_draft_party IS '同租户准确事实引用。';
ALTER TABLE opportunity.customer_requirement_draft_party
    ADD CONSTRAINT fk_customer_requirement_draft_party__party_id
    FOREIGN KEY (tenant_id, party_id)
    REFERENCES party.party (tenant_id, party_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_customer_requirement_draft_party__party_id ON opportunity.customer_requirement_draft_party IS '同租户准确事实引用。';
CREATE TRIGGER trg_customer_requirement_draft_party__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.customer_requirement_draft_party
FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_customer_requirement_draft_party__mutation_guard ON opportunity.customer_requirement_draft_party IS '不可变版本禁止改写或删除。';
REVOKE ALL ON opportunity.customer_requirement_draft_party FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.customer_requirement_draft_party TO ${app_command_role};
GRANT SELECT ON opportunity.customer_requirement_draft_party TO ${app_query_role};

CREATE FUNCTION opportunity.fn_check_requirement_draft_party() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft d WHERE d.tenant_id=NEW.tenant_id AND d.customer_requirement_draft_id=NEW.draft_id AND d.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'draft party set already frozen' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.status='ACTIVE' FOR SHARE) THEN RAISE EXCEPTION 'draft party changed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_draft_party() IS '草稿来源在形成事务完整冻结，逐事实授权不解密正文。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_draft_party() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_draft_party__source BEFORE INSERT ON opportunity.customer_requirement_draft_party FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_draft_party();
COMMENT ON TRIGGER trg_customer_requirement_draft_party__source ON opportunity.customer_requirement_draft_party IS '准确主体版本与草稿同事务冻结。';

CREATE FUNCTION opportunity.fn_check_requirement_basis() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE; d opportunity.customer_requirement_draft%ROWTYPE;
BEGIN
 NEW.created_in_transaction := pg_current_xact_id();
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM NEW.opportunity_revision THEN RAISE EXCEPTION 'requirement opportunity differs' USING ERRCODE='23514'; END IF;
 IF NEW.responsibility_type='opportunity.opportunity' THEN
  IF NEW.responsibility_id<>NEW.opportunity_id OR NEW.responsibility_revision<>NEW.opportunity_revision OR NEW.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'requirement responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=NEW.tenant_id AND h.responsibility_handoff_id=NEW.responsibility_id AND h.revision=NEW.responsibility_revision AND h.opportunity_id=NEW.opportunity_id AND h.to_appointment_id=NEW.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'requirement handoff differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='customer_requirement_draft' THEN
  IF NEW.previous_draft_id IS NOT NULL THEN
   SELECT * INTO d FROM opportunity.customer_requirement_draft WHERE tenant_id=NEW.tenant_id AND customer_requirement_draft_id=NEW.previous_draft_id;
   IF d.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR d.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id OR d.responsibility_type IS DISTINCT FROM NEW.responsibility_type OR d.responsibility_id IS DISTINCT FROM NEW.responsibility_id OR d.responsibility_revision IS DISTINCT FROM NEW.responsibility_revision THEN RAISE EXCEPTION 'draft predecessor differs' USING ERRCODE='23514'; END IF;
  ELSIF EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft x WHERE x.tenant_id=NEW.tenant_id AND x.opportunity_id=NEW.opportunity_id AND x.owner_appointment_id=NEW.owner_appointment_id AND x.responsibility_type=NEW.responsibility_type AND x.responsibility_id=NEW.responsibility_id AND x.responsibility_revision=NEW.responsibility_revision) THEN RAISE EXCEPTION 'draft initial version exists' USING ERRCODE='23514'; END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM opportunity.customer_requirement_draft x WHERE x.tenant_id=NEW.tenant_id AND x.previous_draft_id=NEW.draft_id) THEN RAISE EXCEPTION 'confirmation draft superseded' USING ERRCODE='23514'; END IF;
  IF NEW.previous_confirmation_id IS NULL AND EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'confirmation initial version exists' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_basis() IS '同商机锁核验当前责任、未关闭状态和不分叉草稿。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_basis() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_draft__basis BEFORE INSERT ON opportunity.customer_requirement_draft FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_basis();
COMMENT ON TRIGGER trg_customer_requirement_draft__basis ON opportunity.customer_requirement_draft IS '草稿必须属于当前有效责任。';
CREATE TRIGGER trg_customer_requirement_confirmation__basis BEFORE INSERT ON opportunity.customer_requirement_confirmation FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_basis();
COMMENT ON TRIGGER trg_customer_requirement_confirmation__basis ON opportunity.customer_requirement_confirmation IS '确认必须属于当前有效责任。';

CREATE FUNCTION party.fn_check_profile_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.party_type=NEW.party_type AND p.canonical_name=NEW.canonical_name AND p.status='ACTIVE') THEN RAISE EXCEPTION 'profile source differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION party.fn_check_profile_version() IS '形成时核验主体准确版本；历史不随当前资料改写。';
REVOKE ALL ON FUNCTION party.fn_check_profile_version() FROM PUBLIC;
CREATE TRIGGER trg_profile_version__source BEFORE INSERT ON party.profile_version FOR EACH ROW EXECUTE FUNCTION party.fn_check_profile_version();
COMMENT ON TRIGGER trg_profile_version__source ON party.profile_version IS '主体版本快照准确性。';

CREATE FUNCTION opportunity.fn_check_requirement_participant() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM party.profile_version p WHERE p.tenant_id=NEW.tenant_id AND p.profile_version_id=NEW.profile_version_id AND p.party_id=NEW.party_id AND p.party_revision=NEW.party_revision) THEN RAISE EXCEPTION 'participant profile differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM party.party p WHERE p.tenant_id=NEW.tenant_id AND p.party_id=NEW.party_id AND p.revision=NEW.party_revision AND p.status='ACTIVE' FOR SHARE) THEN RAISE EXCEPTION 'participant party changed' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.confirmation_id AND c.created_in_transaction=pg_current_xact_id()) THEN RAISE EXCEPTION 'participant set already frozen' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_participant() IS '参与方必须引用准确同租户主体快照。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_participant() FROM PUBLIC;
CREATE TRIGGER trg_customer_requirement_participant__source BEFORE INSERT ON opportunity.customer_requirement_participant FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_participant();
COMMENT ON TRIGGER trg_customer_requirement_participant__source ON opportunity.customer_requirement_participant IS '准确主体版本核验。';

CREATE FUNCTION opportunity.fn_check_requirement_confirmation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.customer_requirement_draft%ROWTYPE;
BEGIN
 SELECT * INTO d FROM opportunity.customer_requirement_draft WHERE tenant_id=NEW.tenant_id AND customer_requirement_draft_id=NEW.draft_id;
 IF d.opportunity_id IS DISTINCT FROM NEW.opportunity_id OR d.opportunity_revision IS DISTINCT FROM NEW.opportunity_revision OR d.responsibility_type IS DISTINCT FROM NEW.responsibility_type OR d.responsibility_id IS DISTINCT FROM NEW.responsibility_id OR d.responsibility_revision IS DISTINCT FROM NEW.responsibility_revision OR d.owner_appointment_id IS DISTINCT FROM NEW.owner_appointment_id THEN RAISE EXCEPTION 'confirmation draft differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_confirmation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.previous_confirmation_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'confirmation predecessor differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_participant p WHERE p.tenant_id=NEW.tenant_id AND p.confirmation_id=NEW.customer_requirement_confirmation_id AND p.role='CLIENT') THEN RAISE EXCEPTION 'confirmation requires client' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_requirement_confirmation() IS '同事务确认完整集合、准确草稿及前一版本。';
REVOKE ALL ON FUNCTION opportunity.fn_check_requirement_confirmation() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_customer_requirement_confirmation__complete AFTER INSERT ON opportunity.customer_requirement_confirmation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_requirement_confirmation();
COMMENT ON TRIGGER trg_customer_requirement_confirmation__complete ON opportunity.customer_requirement_confirmation IS '至少一个委托方与准确草稿同事务。';
DO $v920$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v6',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v5';
 IF NOT FOUND THEN RAISE EXCEPTION 'V920 requires 52-plus-2-r2-v5' USING ERRCODE='55000'; END IF;
END;
$v920$;
