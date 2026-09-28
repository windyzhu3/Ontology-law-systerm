-- V900: append-only exception versions and immutable human decisions; no grants to identities.
CREATE TABLE opportunity.owner_exception (
    tenant_id uuid NOT NULL,
    owner_exception_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    is_current boolean DEFAULT true NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    responsibility_slot varchar(64) DEFAULT 'OPPORTUNITY_OWNER' NOT NULL,
    frozen_owner_appointment_id uuid NOT NULL,
    current_owner_appointment_id uuid NOT NULL,
    basis_type varchar(64) NOT NULL,
    basis_id uuid NOT NULL,
    basis_revision bigint NOT NULL,
    task_occurrence_id uuid,
    task_revision bigint,
    wait_receipt_id uuid,
    wait_hash bytea,
    reason_codes varchar(64)[] NOT NULL,
    state varchar(64) NOT NULL,
    first_observed_at timestamptz(6) NOT NULL,
    last_observed_at timestamptz(6) NOT NULL,
    last_disposition_id uuid,
    review_due_at timestamptz(6),
    resolution_kind varchar(64),
    resolution_type varchar(64),
    resolution_id uuid,
    resolution_revision bigint,
    resolution_hash bytea,
    CONSTRAINT pk_owner_exception PRIMARY KEY (tenant_id, owner_exception_id, revision),
    CONSTRAINT ck_owner_exception__slot CHECK (responsibility_slot = 'OPPORTUNITY_OWNER'),
    CONSTRAINT ck_owner_exception__state CHECK (state IN ('ACTIVE','COORDINATING','RESOLVED','NO_LONGER_APPLICABLE')),
    CONSTRAINT ck_owner_exception__basis CHECK (basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff')),
    CONSTRAINT ck_owner_exception__times CHECK (last_observed_at >= first_observed_at),
    CONSTRAINT ck_owner_exception__task CHECK ((task_occurrence_id IS NULL) = (task_revision IS NULL)),
    CONSTRAINT ck_owner_exception__wait CHECK ((wait_receipt_id IS NULL) = (wait_hash IS NULL)),
    CONSTRAINT ck_owner_exception__coordination CHECK (state <> 'COORDINATING' OR (last_disposition_id IS NOT NULL AND review_due_at IS NOT NULL)),
    CONSTRAINT ck_owner_exception__resolution CHECK ((state IN ('ACTIVE','COORDINATING') AND resolution_kind IS NULL AND resolution_type IS NULL AND resolution_id IS NULL AND resolution_revision IS NULL AND resolution_hash IS NULL) OR (resolution_id IS NOT NULL AND resolution_kind IS NOT NULL AND resolution_type IS NOT NULL AND ((state='RESOLVED' AND resolution_kind='TRANSFER' AND resolution_type='opportunity.responsibility_handoff' AND resolution_revision IS NOT NULL AND resolution_revision=0 AND resolution_hash IS NULL) OR (state='RESOLVED' AND resolution_kind='OWNER_VALIDATED' AND resolution_type='audit.audit_entry' AND resolution_revision IS NULL AND resolution_hash IS NOT NULL) OR (state='NO_LONGER_APPLICABLE' AND resolution_kind='OPPORTUNITY_CLOSED' AND resolution_type='opportunity.opportunity' AND resolution_revision IS NOT NULL AND resolution_hash IS NULL)))),
    CONSTRAINT ck_owner_exception__wait_hash_length CHECK (octet_length(wait_hash) = 32),
    CONSTRAINT ck_owner_exception__resolution_hash_length CHECK (octet_length(resolution_hash) = 32),
    CONSTRAINT ck_owner_exception__revision_bound CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_owner_exception__opportunity_revision_bound CHECK (opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_owner_exception__basis_revision_bound CHECK (basis_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_owner_exception__task_revision_bound CHECK (task_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_owner_exception__resolution_revision_bound CHECK (resolution_revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE opportunity.owner_exception IS 'Fact Owner：OpportunityRuntime；负责人异常版本：每次观察保留准确历史版本。';
COMMENT ON CONSTRAINT pk_owner_exception ON opportunity.owner_exception IS '主键：在租户内唯一标识一条owner_exception记录。';
COMMENT ON INDEX opportunity.pk_owner_exception IS '主键：在租户内唯一标识一条owner_exception记录。';
COMMENT ON COLUMN opportunity.owner_exception.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.owner_exception.owner_exception_id IS '负责人异常版本标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.owner_exception.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.owner_exception.is_current IS '当前版本定位标记，仅允许退役。';
COMMENT ON COLUMN opportunity.owner_exception.opportunity_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.opportunity_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.owner_exception.responsibility_slot IS '冻结责任槽。';
COMMENT ON COLUMN opportunity.owner_exception.frozen_owner_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.current_owner_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.basis_type IS '当前有效责任依据类型。';
COMMENT ON COLUMN opportunity.owner_exception.basis_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.basis_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.owner_exception.task_occurrence_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.task_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.owner_exception.wait_receipt_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.wait_hash IS '准确等待摘要。';
COMMENT ON COLUMN opportunity.owner_exception.reason_codes IS '规范有序的异常原因集合。';
COMMENT ON COLUMN opportunity.owner_exception.state IS '活动或终态。';
COMMENT ON COLUMN opportunity.owner_exception.first_observed_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.owner_exception.last_observed_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.owner_exception.last_disposition_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.review_due_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.owner_exception.resolution_kind IS '解决类别。';
COMMENT ON COLUMN opportunity.owner_exception.resolution_type IS '准确解决事实类型。';
COMMENT ON COLUMN opportunity.owner_exception.resolution_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception.resolution_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.owner_exception.resolution_hash IS '准确不可变验证审计摘要；与解决版本互斥。';
COMMENT ON CONSTRAINT ck_owner_exception__slot ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__state ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__basis ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__times ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__task ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__wait ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__coordination ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__resolution ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__wait_hash_length ON opportunity.owner_exception IS '摘要格式：wait_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_owner_exception__resolution_hash_length ON opportunity.owner_exception IS '摘要格式：resolution_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_owner_exception__revision_bound ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__opportunity_revision_bound ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__basis_revision_bound ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__task_revision_bound ON opportunity.owner_exception IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception__resolution_revision_bound ON opportunity.owner_exception IS 'T01具名事实一致性。';
CREATE TABLE opportunity.owner_exception_disposition (
    tenant_id uuid NOT NULL,
    owner_exception_disposition_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    owner_exception_id uuid NOT NULL,
    owner_exception_revision bigint NOT NULL,
    kind varchar(64) NOT NULL,
    actor_appointment_id uuid NOT NULL,
    reason text NOT NULL,
    decided_at timestamptz(6) NOT NULL,
    review_due_at timestamptz(6),
    receiver_appointment_id uuid,
    responsibility_handoff_id uuid,
    CONSTRAINT pk_owner_exception_disposition PRIMARY KEY (tenant_id, owner_exception_disposition_id),
    CONSTRAINT ck_owner_exception_disposition__revision_zero CHECK (revision=0),
    CONSTRAINT ck_owner_exception_disposition__reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_owner_exception_disposition__kind CHECK ((kind='COORDINATION' AND review_due_at IS NOT NULL AND review_due_at > decided_at AND receiver_appointment_id IS NULL AND responsibility_handoff_id IS NULL) OR (kind='TRANSFER' AND review_due_at IS NULL AND receiver_appointment_id IS NOT NULL AND responsibility_handoff_id IS NOT NULL)),
    CONSTRAINT ck_owner_exception_disposition__revision_bound CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_owner_exception_disposition__owner_exception_revision_bound CHECK (owner_exception_revision BETWEEN 0 AND 9007199254740991)
);

COMMENT ON TABLE opportunity.owner_exception_disposition IS 'Fact Owner：OpportunityRuntime；负责人异常处置：不可变人工决定。';
COMMENT ON CONSTRAINT pk_owner_exception_disposition ON opportunity.owner_exception_disposition IS '主键：在租户内唯一标识一条owner_exception_disposition记录。';
COMMENT ON INDEX opportunity.pk_owner_exception_disposition IS '主键：在租户内唯一标识一条owner_exception_disposition记录。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.owner_exception_disposition_id IS '负责人异常处置标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.owner_exception_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.owner_exception_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.kind IS 'TRANSFER或COORDINATION。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.actor_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.reason IS '规范人工原因。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.decided_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.review_due_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.receiver_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.owner_exception_disposition.responsibility_handoff_id IS 'T01同租户准确身份。';
COMMENT ON CONSTRAINT ck_owner_exception_disposition__revision_zero ON opportunity.owner_exception_disposition IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception_disposition__reason ON opportunity.owner_exception_disposition IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception_disposition__kind ON opportunity.owner_exception_disposition IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception_disposition__revision_bound ON opportunity.owner_exception_disposition IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_owner_exception_disposition__owner_exception_revision_bound ON opportunity.owner_exception_disposition IS 'T01具名事实一致性。';
CREATE TABLE opportunity.responsibility_handoff (
    tenant_id uuid NOT NULL,
    responsibility_handoff_id uuid NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    opportunity_id uuid NOT NULL,
    opportunity_revision bigint NOT NULL,
    prior_basis_type varchar(64) NOT NULL,
    prior_basis_id uuid NOT NULL,
    prior_basis_revision bigint NOT NULL,
    from_appointment_id uuid NOT NULL,
    to_appointment_id uuid NOT NULL,
    actor_appointment_id uuid NOT NULL,
    owner_exception_disposition_id uuid NOT NULL,
    old_task_occurrence_id uuid,
    old_task_revision bigint,
    new_task_occurrence_id uuid NOT NULL,
    new_task_revision bigint NOT NULL,
    original_due_at timestamptz(6) NOT NULL,
    original_wait_receipt_id uuid,
    original_wait_hash bytea,
    handed_off_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_responsibility_handoff PRIMARY KEY (tenant_id, responsibility_handoff_id),
    CONSTRAINT ck_responsibility_handoff__revision_zero CHECK (revision=0),
    CONSTRAINT uq_responsibility_handoff__prior UNIQUE (tenant_id, opportunity_id, prior_basis_type, prior_basis_id, prior_basis_revision),
    CONSTRAINT uq_responsibility_handoff__decision UNIQUE (tenant_id, owner_exception_disposition_id),
    CONSTRAINT ck_responsibility_handoff__basis CHECK (prior_basis_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND prior_basis_id <> responsibility_handoff_id),
    CONSTRAINT ck_responsibility_handoff__receiver CHECK (from_appointment_id <> to_appointment_id),
    CONSTRAINT ck_responsibility_handoff__tasks CHECK ((old_task_occurrence_id IS NULL) = (old_task_revision IS NULL) AND (old_task_occurrence_id IS NULL OR old_task_occurrence_id <> new_task_occurrence_id)),
    CONSTRAINT ck_responsibility_handoff__wait CHECK ((original_wait_receipt_id IS NULL) = (original_wait_hash IS NULL)),
    CONSTRAINT ck_responsibility_handoff__original_wait_hash_length CHECK (octet_length(original_wait_hash) = 32),
    CONSTRAINT ck_responsibility_handoff__revision_bound CHECK (revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_responsibility_handoff__opportunity_revision_bound CHECK (opportunity_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_responsibility_handoff__prior_basis_revision_bound CHECK (prior_basis_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_responsibility_handoff__old_task_revision_bound CHECK (old_task_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_responsibility_handoff__new_task_revision_bound CHECK (new_task_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT uq_responsibility_handoff__new_task UNIQUE (tenant_id, new_task_occurrence_id)
);

COMMENT ON TABLE opportunity.responsibility_handoff IS 'Fact Owner：OpportunityRuntime；商机责任交接：不可变唯一责任链。';
COMMENT ON CONSTRAINT pk_responsibility_handoff ON opportunity.responsibility_handoff IS '主键：在租户内唯一标识一条responsibility_handoff记录。';
COMMENT ON INDEX opportunity.pk_responsibility_handoff IS '主键：在租户内唯一标识一条responsibility_handoff记录。';
COMMENT ON COLUMN opportunity.responsibility_handoff.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.responsibility_handoff.responsibility_handoff_id IS '商机责任交接标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.responsibility_handoff.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.responsibility_handoff.opportunity_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.opportunity_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.responsibility_handoff.prior_basis_type IS '准确前任责任类型。';
COMMENT ON COLUMN opportunity.responsibility_handoff.prior_basis_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.prior_basis_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.responsibility_handoff.from_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.to_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.actor_appointment_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.owner_exception_disposition_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.old_task_occurrence_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.old_task_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.responsibility_handoff.new_task_occurrence_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.new_task_revision IS 'T01准确版本，JSON安全整数。';
COMMENT ON COLUMN opportunity.responsibility_handoff.original_due_at IS 'T01数据库业务时刻。';
COMMENT ON COLUMN opportunity.responsibility_handoff.original_wait_receipt_id IS 'T01同租户准确身份。';
COMMENT ON COLUMN opportunity.responsibility_handoff.original_wait_hash IS '准确原等待摘要。';
COMMENT ON COLUMN opportunity.responsibility_handoff.handed_off_at IS 'T01数据库业务时刻。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__revision_zero ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT uq_responsibility_handoff__prior ON opportunity.responsibility_handoff IS '准确前任至多一个后继。';
COMMENT ON INDEX opportunity.uq_responsibility_handoff__prior IS '准确前任至多一个后继。';
COMMENT ON CONSTRAINT uq_responsibility_handoff__decision ON opportunity.responsibility_handoff IS '决定至多一个交接。';
COMMENT ON INDEX opportunity.uq_responsibility_handoff__decision IS '决定至多一个交接。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__basis ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__receiver ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__tasks ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__wait ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__original_wait_hash_length ON opportunity.responsibility_handoff IS '摘要格式：original_wait_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__revision_bound ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__opportunity_revision_bound ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__prior_basis_revision_bound ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__old_task_revision_bound ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT ck_responsibility_handoff__new_task_revision_bound ON opportunity.responsibility_handoff IS 'T01具名事实一致性。';
COMMENT ON CONSTRAINT uq_responsibility_handoff__new_task ON opportunity.responsibility_handoff IS '新任务只能由一个交接创建。';
COMMENT ON INDEX opportunity.uq_responsibility_handoff__new_task IS '新任务只能由一个交接创建。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_owner_exception__tenant ON opportunity.owner_exception IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__opportunity_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__frozen_owner_appointment_id
    FOREIGN KEY (tenant_id, frozen_owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__frozen_owner_appointment_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__current_owner_appointment_id
    FOREIGN KEY (tenant_id, current_owner_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__current_owner_appointment_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__task_occurrence_id
    FOREIGN KEY (tenant_id, task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__task_occurrence_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__wait_receipt_id
    FOREIGN KEY (tenant_id, wait_receipt_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__wait_receipt_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception
    ADD CONSTRAINT fk_owner_exception__last_disposition_id
    FOREIGN KEY (tenant_id, last_disposition_id)
    REFERENCES opportunity.owner_exception_disposition (tenant_id, owner_exception_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception__last_disposition_id ON opportunity.owner_exception IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception_disposition
    ADD CONSTRAINT fk_owner_exception_disposition__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_owner_exception_disposition__tenant ON opportunity.owner_exception_disposition IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.owner_exception_disposition
    ADD CONSTRAINT fk_owner_exception_disposition__exact_exception
    FOREIGN KEY (tenant_id, owner_exception_id, owner_exception_revision)
    REFERENCES opportunity.owner_exception (tenant_id, owner_exception_id, revision)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception_disposition__exact_exception ON opportunity.owner_exception_disposition IS '准确异常历史版本。';
ALTER TABLE opportunity.owner_exception_disposition
    ADD CONSTRAINT fk_owner_exception_disposition__actor_appointment_id
    FOREIGN KEY (tenant_id, actor_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception_disposition__actor_appointment_id ON opportunity.owner_exception_disposition IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception_disposition
    ADD CONSTRAINT fk_owner_exception_disposition__receiver_appointment_id
    FOREIGN KEY (tenant_id, receiver_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception_disposition__receiver_appointment_id ON opportunity.owner_exception_disposition IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.owner_exception_disposition
    ADD CONSTRAINT fk_owner_exception_disposition__responsibility_handoff_id
    FOREIGN KEY (tenant_id, responsibility_handoff_id)
    REFERENCES opportunity.responsibility_handoff (tenant_id, responsibility_handoff_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_owner_exception_disposition__responsibility_handoff_id ON opportunity.owner_exception_disposition IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__tenant
    FOREIGN KEY (tenant_id)
    REFERENCES identity.tenant (tenant_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION;
COMMENT ON CONSTRAINT fk_responsibility_handoff__tenant ON opportunity.responsibility_handoff IS '租户边界：该记录必须属于一个已存在的租户。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__opportunity_id
    FOREIGN KEY (tenant_id, opportunity_id)
    REFERENCES opportunity.opportunity (tenant_id, opportunity_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__opportunity_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__from_appointment_id
    FOREIGN KEY (tenant_id, from_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__from_appointment_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__to_appointment_id
    FOREIGN KEY (tenant_id, to_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__to_appointment_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__actor_appointment_id
    FOREIGN KEY (tenant_id, actor_appointment_id)
    REFERENCES identity.appointment (tenant_id, appointment_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__actor_appointment_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__owner_exception_disposition_id
    FOREIGN KEY (tenant_id, owner_exception_disposition_id)
    REFERENCES opportunity.owner_exception_disposition (tenant_id, owner_exception_disposition_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__owner_exception_disposition_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__old_task_occurrence_id
    FOREIGN KEY (tenant_id, old_task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__old_task_occurrence_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__new_task_occurrence_id
    FOREIGN KEY (tenant_id, new_task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__new_task_occurrence_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE opportunity.responsibility_handoff
    ADD CONSTRAINT fk_responsibility_handoff__original_wait_receipt_id
    FOREIGN KEY (tenant_id, original_wait_receipt_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_responsibility_handoff__original_wait_receipt_id ON opportunity.responsibility_handoff IS '同租户身份存在性，准确版本由Owner复验。';
-- 查询、自然幂等和单当前事实索引。

CREATE UNIQUE INDEX uq_owner_exception__current ON opportunity.owner_exception (tenant_id, owner_exception_id) WHERE is_current;
COMMENT ON INDEX opportunity.uq_owner_exception__current IS '单当前版本。';

CREATE UNIQUE INDEX uq_owner_exception__active_slot ON opportunity.owner_exception (tenant_id, opportunity_id, responsibility_slot) WHERE is_current AND state IN ('ACTIVE','COORDINATING');
COMMENT ON INDEX opportunity.uq_owner_exception__active_slot IS '同商机责任槽至多一个活动异常周期。';

CREATE UNIQUE INDEX uq_responsibility_handoff__initial ON opportunity.responsibility_handoff (tenant_id, opportunity_id) WHERE prior_basis_type='opportunity.opportunity';
COMMENT ON INDEX opportunity.uq_responsibility_handoff__initial IS '商机只能有一个首次交接。';

REVOKE ALL ON opportunity.owner_exception FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.owner_exception TO ${app_command_role};
GRANT SELECT (tenant_id, owner_exception_id, revision, is_current, opportunity_id, opportunity_revision, responsibility_slot, frozen_owner_appointment_id, current_owner_appointment_id, basis_type, basis_id, basis_revision, task_occurrence_id, task_revision, wait_receipt_id, wait_hash, reason_codes, state, first_observed_at, last_observed_at, last_disposition_id, review_due_at, resolution_kind, resolution_type, resolution_id, resolution_revision, resolution_hash) ON opportunity.owner_exception TO ${app_query_role};
REVOKE ALL ON opportunity.owner_exception_disposition FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.owner_exception_disposition TO ${app_command_role};
GRANT SELECT (tenant_id, owner_exception_disposition_id, revision, owner_exception_id, owner_exception_revision, kind, actor_appointment_id, reason, decided_at, review_due_at, receiver_appointment_id, responsibility_handoff_id) ON opportunity.owner_exception_disposition TO ${app_query_role};
CREATE TRIGGER trg_owner_exception_disposition__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.owner_exception_disposition FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
REVOKE ALL ON opportunity.responsibility_handoff FROM PUBLIC, ${app_command_role}, ${app_query_role}, ${app_worker_role}, ${audit_append_role};
GRANT SELECT, INSERT ON opportunity.responsibility_handoff TO ${app_command_role};
GRANT SELECT (tenant_id, responsibility_handoff_id, revision, opportunity_id, opportunity_revision, prior_basis_type, prior_basis_id, prior_basis_revision, from_appointment_id, to_appointment_id, actor_appointment_id, owner_exception_disposition_id, old_task_occurrence_id, old_task_revision, new_task_occurrence_id, new_task_revision, original_due_at, original_wait_receipt_id, original_wait_hash, handed_off_at) ON opportunity.responsibility_handoff TO ${app_query_role};
CREATE TRIGGER trg_responsibility_handoff__mutation_guard BEFORE UPDATE OR DELETE ON opportunity.responsibility_handoff FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
GRANT UPDATE (is_current) ON opportunity.owner_exception TO ${app_command_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN responsibility_basis_type varchar(64);
COMMENT ON COLUMN responsibility.task_occurrence.responsibility_basis_type IS '当前有效责任依据的静态注册类型。';
GRANT SELECT (responsibility_basis_type) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN responsibility_basis_id uuid;
COMMENT ON COLUMN responsibility.task_occurrence.responsibility_basis_id IS '当前有效责任依据在所属租户内的准确标识。';
GRANT SELECT (responsibility_basis_id) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN responsibility_basis_revision bigint;
COMMENT ON COLUMN responsibility.task_occurrence.responsibility_basis_revision IS '当前有效责任依据的准确修订号；按哈希冻结时为空。';
GRANT SELECT (responsibility_basis_revision) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN responsibility_basis_hash bytea;
COMMENT ON COLUMN responsibility.task_occurrence.responsibility_basis_hash IS '当前有效责任依据的准确规范摘要；按修订冻结时为空。';
GRANT SELECT (responsibility_basis_hash) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN cancellation_fact_type varchar(64);
COMMENT ON COLUMN responsibility.task_occurrence.cancellation_fact_type IS '交接取消依据的静态注册类型。';
GRANT SELECT (cancellation_fact_type) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN cancellation_fact_id uuid;
COMMENT ON COLUMN responsibility.task_occurrence.cancellation_fact_id IS '交接取消依据在所属租户内的准确标识。';
GRANT SELECT (cancellation_fact_id) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN cancellation_fact_revision bigint;
COMMENT ON COLUMN responsibility.task_occurrence.cancellation_fact_revision IS '交接取消依据的准确修订号；按哈希冻结时为空。';
GRANT SELECT (cancellation_fact_revision) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN cancellation_fact_hash bytea;
COMMENT ON COLUMN responsibility.task_occurrence.cancellation_fact_hash IS '交接取消依据的准确规范摘要；按修订冻结时为空。';
GRANT SELECT (cancellation_fact_hash) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD COLUMN handoff_predecessor_task_occurrence_id uuid;
COMMENT ON COLUMN responsibility.task_occurrence.handoff_predecessor_task_occurrence_id IS 'T01同租户准确身份。';
GRANT SELECT (handoff_predecessor_task_occurrence_id) ON responsibility.task_occurrence TO ${app_query_role};
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__responsibility_basis_exact CHECK (((responsibility_basis_type IS NOT NULL AND responsibility_basis_id IS NOT NULL AND ((responsibility_basis_revision IS NOT NULL AND responsibility_basis_revision >= 0 AND responsibility_basis_hash IS NULL) OR (responsibility_basis_revision IS NULL AND responsibility_basis_hash IS NOT NULL))) OR (responsibility_basis_type IS NULL AND responsibility_basis_id IS NULL AND responsibility_basis_revision IS NULL AND responsibility_basis_hash IS NULL)));
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__cancellation_fact_exact CHECK (((cancellation_fact_type IS NOT NULL AND cancellation_fact_id IS NOT NULL AND ((cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision >= 0 AND cancellation_fact_hash IS NULL) OR (cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NOT NULL))) OR (cancellation_fact_type IS NULL AND cancellation_fact_id IS NULL AND cancellation_fact_revision IS NULL AND cancellation_fact_hash IS NULL)));
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__responsibility_basis_hash_length CHECK (octet_length(responsibility_basis_hash) = 32);
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__cancellation_fact_hash_length CHECK (octet_length(cancellation_fact_hash) = 32);
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_predecessor CHECK (handoff_predecessor_task_occurrence_id IS NULL OR (business_purpose_code='PROGRESS_OPPORTUNITY' AND handoff_predecessor_task_occurrence_id<>task_occurrence_id AND predecessor_task_occurrence_id IS NULL AND responsibility_basis_type IS NOT NULL AND responsibility_basis_type='opportunity.responsibility_handoff'));
ALTER TABLE responsibility.task_occurrence ADD CONSTRAINT ck_task_occurrence__handoff_cancellation CHECK (cancellation_fact_type IS NULL OR (state='CANCELLED' AND cancellation_reason_code='R2_OPPORTUNITY_HANDOFF_V1' AND cancellation_fact_type='opportunity.responsibility_handoff' AND cancellation_fact_revision IS NOT NULL AND cancellation_fact_revision=0));
ALTER TABLE responsibility.task_occurrence
    ADD CONSTRAINT fk_task_occurrence__handoff_predecessor_task_occurrence_id
    FOREIGN KEY (tenant_id, handoff_predecessor_task_occurrence_id)
    REFERENCES responsibility.task_occurrence (tenant_id, task_occurrence_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_task_occurrence__handoff_predecessor_task_occurrence_id ON responsibility.task_occurrence IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE responsibility.wait_receipt ADD COLUMN handoff_fact_id uuid;
COMMENT ON COLUMN responsibility.wait_receipt.handoff_fact_id IS 'T01同租户准确身份。';
GRANT SELECT (handoff_fact_id) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN handoff_fact_revision bigint;
COMMENT ON COLUMN responsibility.wait_receipt.handoff_fact_revision IS 'T01准确版本，JSON安全整数。';
GRANT SELECT (handoff_fact_revision) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN inherited_wait_receipt_id uuid;
COMMENT ON COLUMN responsibility.wait_receipt.inherited_wait_receipt_id IS 'T01同租户准确身份。';
GRANT SELECT (inherited_wait_receipt_id) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN inherited_wait_hash bytea;
COMMENT ON COLUMN responsibility.wait_receipt.inherited_wait_hash IS '准确原等待摘要。';
GRANT SELECT (inherited_wait_hash) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN origin_progress_id uuid;
COMMENT ON COLUMN responsibility.wait_receipt.origin_progress_id IS 'T01同租户准确身份。';
GRANT SELECT (origin_progress_id) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN origin_progress_hash bytea;
COMMENT ON COLUMN responsibility.wait_receipt.origin_progress_hash IS '准确原进展摘要。';
GRANT SELECT (origin_progress_hash) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD COLUMN original_sla_due_at timestamptz(6);
COMMENT ON COLUMN responsibility.wait_receipt.original_sla_due_at IS 'T01数据库业务时刻。';
GRANT SELECT (original_sla_due_at) ON responsibility.wait_receipt TO ${app_query_role};
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__inherited_wait_hash_length CHECK (octet_length(inherited_wait_hash) = 32);
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__origin_progress_hash_length CHECK (octet_length(origin_progress_hash) = 32);
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__handoff_shape CHECK ((wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND wait_contract_version=1 AND handoff_fact_id IS NOT NULL AND handoff_fact_revision IS NOT NULL AND handoff_fact_revision=0 AND inherited_wait_receipt_id IS NOT NULL AND inherited_wait_hash IS NOT NULL AND origin_progress_id IS NOT NULL AND origin_progress_hash IS NOT NULL AND original_sla_due_at IS NOT NULL) OR (wait_contract_code<>'R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND handoff_fact_id IS NULL AND handoff_fact_revision IS NULL AND inherited_wait_receipt_id IS NULL AND inherited_wait_hash IS NULL AND origin_progress_id IS NULL AND origin_progress_hash IS NULL AND original_sla_due_at IS NULL));
ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__handoff_fact_id
    FOREIGN KEY (tenant_id, handoff_fact_id)
    REFERENCES opportunity.responsibility_handoff (tenant_id, responsibility_handoff_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_wait_receipt__handoff_fact_id ON responsibility.wait_receipt IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__inherited_wait_receipt_id
    FOREIGN KEY (tenant_id, inherited_wait_receipt_id)
    REFERENCES responsibility.wait_receipt (tenant_id, wait_receipt_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_wait_receipt__inherited_wait_receipt_id ON responsibility.wait_receipt IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE responsibility.wait_receipt
    ADD CONSTRAINT fk_wait_receipt__origin_progress_id
    FOREIGN KEY (tenant_id, origin_progress_id)
    REFERENCES opportunity.opportunity_progress (tenant_id, opportunity_progress_id)
    ON UPDATE NO ACTION
    ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_wait_receipt__origin_progress_id ON responsibility.wait_receipt IS '同租户身份存在性，准确版本由Owner复验。';
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__resume_after_entry;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__resume_after_entry CHECK (resume_due_at IS NULL OR resume_due_at > entered_waiting_at OR wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1');
ALTER TABLE responsibility.wait_receipt DROP CONSTRAINT ck_wait_receipt__positive_task_revision;
ALTER TABLE responsibility.wait_receipt ADD CONSTRAINT ck_wait_receipt__positive_task_revision CHECK (task_revision > 0 OR (wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND task_revision=0));
DROP TRIGGER trg_task_occurrence__mutation_guard ON responsibility.task_occurrence;
CREATE TRIGGER trg_task_occurrence__mutation_guard BEFORE UPDATE OR DELETE ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_guard_controlled_update('state,completed_at,cancelled_at,cancellation_reason_code,completion_fact_type,completion_fact_id,completion_fact_revision,completion_fact_hash,revision,cancellation_fact_type,cancellation_fact_id,cancellation_fact_revision,cancellation_fact_hash','completed_at,cancelled_at,cancellation_reason_code,completion_fact_type,completion_fact_id,completion_fact_revision,completion_fact_hash,cancellation_fact_type,cancellation_fact_id,cancellation_fact_revision,cancellation_fact_hash','state','OPEN>WAITING,WAITING>OPEN,OPEN>DONE,WAITING>DONE,OPEN>CANCELLED,WAITING>CANCELLED','CONTROLLED');
GRANT UPDATE (cancellation_fact_type,cancellation_fact_id,cancellation_fact_revision,cancellation_fact_hash) ON responsibility.task_occurrence TO ${app_command_role};
DROP TRIGGER trg_task_occurrence__initial_state ON responsibility.task_occurrence;
CREATE FUNCTION responsibility.fn_guard_r2_handoff_task_initial() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NEW.revision<>0 OR (NEW.state='OPEN' OR (NEW.state='WAITING' AND NEW.business_purpose_code='PROGRESS_OPPORTUNITY' AND NEW.responsibility_basis_type='opportunity.responsibility_handoff' AND NEW.responsibility_basis_revision=0 AND NEW.handoff_predecessor_task_occurrence_id IS NOT NULL)) IS NOT TRUE THEN RAISE EXCEPTION 'invalid task initial state' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_task_occurrence__initial_state BEFORE INSERT ON responsibility.task_occurrence FOR EACH ROW EXECUTE FUNCTION responsibility.fn_guard_r2_handoff_task_initial();
REVOKE ALL ON FUNCTION responsibility.fn_guard_r2_handoff_task_initial() FROM PUBLIC;

CREATE FUNCTION opportunity.fn_guard_responsibility_handoff_prior() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NEW.prior_basis_type='opportunity.responsibility_handoff' AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND responsibility_handoff_id=NEW.prior_basis_id AND opportunity_id=NEW.opportunity_id AND revision=NEW.prior_basis_revision) THEN RAISE EXCEPTION 'handoff predecessor must already exist' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_responsibility_handoff__prior BEFORE INSERT ON opportunity.responsibility_handoff FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_responsibility_handoff_prior();
REVOKE ALL ON FUNCTION opportunity.fn_guard_responsibility_handoff_prior() FROM PUBLIC;
CREATE FUNCTION opportunity.fn_check_responsibility_handoff() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE d opportunity.owner_exception_disposition%ROWTYPE; predecessor opportunity.responsibility_handoff%ROWTYPE; original_owner uuid;
BEGIN
 SELECT owner_appointment_id INTO original_owner FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NEW.prior_basis_type='opportunity.opportunity' THEN
  IF NEW.prior_basis_id<>NEW.opportunity_id OR NEW.prior_basis_revision<>NEW.opportunity_revision OR NEW.from_appointment_id IS DISTINCT FROM original_owner THEN RAISE EXCEPTION 'invalid initial handoff basis' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO predecessor FROM opportunity.responsibility_handoff WHERE tenant_id=NEW.tenant_id AND responsibility_handoff_id=NEW.prior_basis_id;
  IF NOT FOUND OR predecessor.opportunity_id<>NEW.opportunity_id OR predecessor.revision<>NEW.prior_basis_revision OR predecessor.to_appointment_id<>NEW.from_appointment_id OR predecessor.handed_off_at>NEW.handed_off_at THEN RAISE EXCEPTION 'invalid prior handoff basis' USING ERRCODE='23514'; END IF;
 END IF;
 SELECT * INTO d FROM opportunity.owner_exception_disposition WHERE tenant_id=NEW.tenant_id AND owner_exception_disposition_id=NEW.owner_exception_disposition_id;
 IF NOT FOUND OR d.kind<>'TRANSFER' OR d.responsibility_handoff_id<>NEW.responsibility_handoff_id OR d.receiver_appointment_id<>NEW.to_appointment_id OR d.actor_appointment_id<>NEW.actor_appointment_id THEN RAISE EXCEPTION 'handoff decision mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM opportunity.owner_exception e WHERE e.tenant_id=NEW.tenant_id AND e.owner_exception_id=d.owner_exception_id AND e.revision=d.owner_exception_revision AND e.opportunity_id=NEW.opportunity_id AND e.state IN ('ACTIVE','COORDINATING') AND e.basis_type=NEW.prior_basis_type AND e.basis_id=NEW.prior_basis_id AND e.basis_revision=NEW.prior_basis_revision) THEN RAISE EXCEPTION 'handoff exception basis mismatch' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.new_task_occurrence_id AND t.revision=NEW.new_task_revision AND t.owner_appointment_id=NEW.to_appointment_id AND t.subject_type='opportunity.opportunity' AND t.subject_id=NEW.opportunity_id AND t.responsibility_basis_type='opportunity.responsibility_handoff' AND t.responsibility_basis_id=NEW.responsibility_handoff_id AND t.responsibility_basis_revision=0 AND t.original_sla_due_at=NEW.original_due_at AND t.handoff_predecessor_task_occurrence_id IS NOT DISTINCT FROM NEW.old_task_occurrence_id AND t.state=CASE WHEN NEW.original_wait_receipt_id IS NULL THEN 'OPEN' ELSE 'WAITING' END) THEN RAISE EXCEPTION 'handoff successor task mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.old_task_occurrence_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.old_task_occurrence_id AND t.revision=NEW.old_task_revision+1 AND t.state='CANCELLED' AND t.owner_appointment_id=NEW.from_appointment_id AND t.original_sla_due_at=NEW.original_due_at AND t.cancellation_fact_type='opportunity.responsibility_handoff' AND t.cancellation_fact_id=NEW.responsibility_handoff_id AND t.cancellation_fact_revision=0 AND t.completion_fact_type IS NULL) THEN RAISE EXCEPTION 'handoff cancellation mismatch' USING ERRCODE='23514'; END IF;
 IF NEW.original_wait_receipt_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.wait_receipt w JOIN responsibility.wait_receipt original ON original.tenant_id=w.tenant_id AND original.wait_receipt_id=NEW.original_wait_receipt_id WHERE w.tenant_id=NEW.tenant_id AND w.task_occurrence_id=NEW.new_task_occurrence_id AND w.wait_contract_code='R2_OPPORTUNITY_HANDOFF_WAIT_V1' AND w.handoff_fact_id=NEW.responsibility_handoff_id AND w.handoff_fact_revision=0 AND w.inherited_wait_receipt_id=NEW.original_wait_receipt_id AND w.inherited_wait_hash=NEW.original_wait_hash AND w.resume_due_at IS NOT DISTINCT FROM original.resume_due_at AND w.original_sla_due_at=NEW.original_due_at AND original.task_occurrence_id=NEW.old_task_occurrence_id) THEN RAISE EXCEPTION 'handoff wait inheritance mismatch' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
CREATE CONSTRAINT TRIGGER trg_responsibility_handoff__facts AFTER INSERT ON opportunity.responsibility_handoff DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_responsibility_handoff();
REVOKE ALL ON FUNCTION opportunity.fn_check_responsibility_handoff() FROM PUBLIC;


CREATE FUNCTION opportunity.fn_guard_owner_exception_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE prior opportunity.owner_exception%ROWTYPE; canonical varchar(64)[];
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'exception history deletion forbidden' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NOT OLD.is_current OR NEW.is_current OR (to_jsonb(NEW)-'is_current') IS DISTINCT FROM (to_jsonb(OLD)-'is_current') THEN
   RAISE EXCEPTION 'only current version retirement permitted' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 PERFORM 1 FROM opportunity.opportunity WHERE tenant_id=NEW.tenant_id AND opportunity_id=NEW.opportunity_id FOR UPDATE;
 IF NOT NEW.is_current THEN RAISE EXCEPTION 'new version must be current' USING ERRCODE='23514'; END IF;
 SELECT array_agg(x ORDER BY n) INTO canonical FROM unnest(ARRAY['OWNER_INACTIVE','OWNER_AUTHORITY_MISSING','OWNER_DENIED','SUPERVISOR_UNRESOLVED','SOURCE_INCONSISTENT']::varchar(64)[]) WITH ORDINALITY a(x,n) WHERE x=ANY(NEW.reason_codes);
 IF cardinality(NEW.reason_codes) NOT BETWEEN 1 AND 5 OR NEW.reason_codes IS DISTINCT FROM canonical THEN RAISE EXCEPTION 'noncanonical reason set' USING ERRCODE='23514'; END IF;
 SELECT * INTO prior FROM opportunity.owner_exception WHERE tenant_id=NEW.tenant_id AND owner_exception_id=NEW.owner_exception_id ORDER BY revision DESC LIMIT 1;
 IF FOUND THEN
  IF prior.is_current OR prior.state IN ('RESOLVED','NO_LONGER_APPLICABLE') OR prior.revision >= 9007199254740991 OR NEW.revision <> prior.revision+1 OR
   ROW(NEW.opportunity_id,NEW.opportunity_revision,NEW.responsibility_slot,NEW.frozen_owner_appointment_id,NEW.first_observed_at) IS DISTINCT FROM ROW(prior.opportunity_id,prior.opportunity_revision,prior.responsibility_slot,prior.frozen_owner_appointment_id,prior.first_observed_at) OR NEW.last_observed_at < prior.last_observed_at THEN
   RAISE EXCEPTION 'invalid exception version successor' USING ERRCODE='23514';
  END IF;
  IF prior.state='COORDINATING' AND NEW.state='ACTIVE' AND NEW.last_observed_at < prior.review_due_at THEN RAISE EXCEPTION 'coordination review not due' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.revision<>0 OR NEW.state<>'ACTIVE' THEN RAISE EXCEPTION 'exception initial version must be ACTIVE zero' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
CREATE TRIGGER trg_owner_exception__mutation_guard BEFORE INSERT OR UPDATE OR DELETE ON opportunity.owner_exception FOR EACH ROW EXECUTE FUNCTION opportunity.fn_guard_owner_exception_version();
CREATE FUNCTION opportunity.fn_check_owner_exception_current() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM opportunity.owner_exception WHERE tenant_id=NEW.tenant_id AND owner_exception_id=NEW.owner_exception_id AND is_current) THEN RAISE EXCEPTION 'exception must retain one current version' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$fn$;
CREATE CONSTRAINT TRIGGER trg_owner_exception__current_required AFTER INSERT OR UPDATE ON opportunity.owner_exception DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_owner_exception_current();
REVOKE ALL ON FUNCTION opportunity.fn_guard_owner_exception_version() FROM PUBLIC;
REVOKE ALL ON FUNCTION opportunity.fn_check_owner_exception_current() FROM PUBLIC;
ALTER TABLE platform_meta.r2_opportunity_checkpoint DROP CONSTRAINT ck_r2_opportunity_checkpoint__kind;
ALTER TABLE platform_meta.r2_opportunity_checkpoint ADD CONSTRAINT ck_r2_opportunity_checkpoint__kind CHECK (scan_kind IN ('INITIAL','DUE','OWNER_EXCEPTION'));
DO $v900$
DECLARE actual_count bigint;
BEGIN
 SELECT count(*) INTO actual_count FROM pg_catalog.pg_tables WHERE schemaname IN ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta');
 IF actual_count<>58 THEN RAISE EXCEPTION 'V900 expected 58 tables, found %',actual_count; END IF;
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v4',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v3';
 IF NOT FOUND THEN RAISE EXCEPTION 'V900 requires schema contract 52-plus-2-r2-v3' USING ERRCODE='55000'; END IF;
END;
$v900$;

COMMENT ON FUNCTION responsibility.fn_guard_r2_handoff_task_initial() IS 'T01具名事实守卫。';
COMMENT ON FUNCTION opportunity.fn_guard_responsibility_handoff_prior() IS 'T01具名事实守卫。';
COMMENT ON FUNCTION opportunity.fn_check_responsibility_handoff() IS 'T01具名事实守卫。';
COMMENT ON FUNCTION opportunity.fn_guard_owner_exception_version() IS 'T01具名事实守卫。';
COMMENT ON FUNCTION opportunity.fn_check_owner_exception_current() IS 'T01具名事实守卫。';
COMMENT ON TRIGGER trg_owner_exception_disposition__mutation_guard ON opportunity.owner_exception_disposition IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_responsibility_handoff__mutation_guard ON opportunity.responsibility_handoff IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_task_occurrence__mutation_guard ON responsibility.task_occurrence IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_task_occurrence__initial_state ON responsibility.task_occurrence IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_responsibility_handoff__prior ON opportunity.responsibility_handoff IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_responsibility_handoff__facts ON opportunity.responsibility_handoff IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_owner_exception__mutation_guard ON opportunity.owner_exception IS 'T01具名事实一致性守卫。';
COMMENT ON TRIGGER trg_owner_exception__current_required ON opportunity.owner_exception IS 'T01具名事实一致性守卫。';