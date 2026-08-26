-- 责任域：保存待办责任实例及其不可变决策、等待回执和唯一行动草案；不建设通用工作流或作业系统。

CREATE TABLE responsibility.task_occurrence (
    tenant_id uuid NOT NULL,
    task_occurrence_id uuid NOT NULL,
    owner_appointment_id uuid NOT NULL,
    business_purpose_code varchar(64) NOT NULL,
    primary_command_code varchar(64) NOT NULL,
    expected_completion_fact_type varchar(64) NOT NULL,
    original_sla_code varchar(64) NOT NULL,
    original_sla_seconds bigint NOT NULL,
    original_sla_due_at timestamptz(6) NOT NULL,
    state varchar(64) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    completed_at timestamptz(6),
    cancelled_at timestamptz(6),
    cancellation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    subject_type varchar(64) NOT NULL,
    subject_id uuid NOT NULL,
    subject_revision bigint,
    subject_hash bytea,
    completion_fact_type varchar(64),
    completion_fact_id uuid,
    completion_fact_revision bigint,
    completion_fact_hash bytea,
    CONSTRAINT pk_task_occurrence PRIMARY KEY (tenant_id, task_occurrence_id),
    CONSTRAINT ck_task_occurrence__state CHECK (state IN ('OPEN', 'WAITING', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_task_occurrence__original_sla_seconds_nonnegative CHECK (original_sla_seconds >= 0),
    CONSTRAINT ck_task_occurrence__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_task_occurrence__completion_type CHECK (completion_fact_type IS NULL OR completion_fact_type = expected_completion_fact_type),
    CONSTRAINT ck_task_occurrence__terminal_fields CHECK ((state = 'DONE' AND completed_at IS NOT NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NOT NULL) OR (state = 'CANCELLED' AND completed_at IS NULL AND cancelled_at IS NOT NULL AND cancellation_reason_code IS NOT NULL AND completion_fact_type IS NULL) OR (state IN ('OPEN', 'WAITING') AND completed_at IS NULL AND cancelled_at IS NULL AND cancellation_reason_code IS NULL AND completion_fact_type IS NULL)),
    CONSTRAINT ck_task_occurrence__subject_exact CHECK ((subject_type IS NOT NULL AND subject_id IS NOT NULL AND ((subject_revision IS NOT NULL AND subject_revision >= 0 AND subject_hash IS NULL) OR (subject_revision IS NULL AND subject_hash IS NOT NULL)))),
    CONSTRAINT ck_task_occurrence__completion_fact_exact CHECK (((completion_fact_type IS NOT NULL AND completion_fact_id IS NOT NULL AND ((completion_fact_revision IS NOT NULL AND completion_fact_revision >= 0 AND completion_fact_hash IS NULL) OR (completion_fact_revision IS NULL AND completion_fact_hash IS NOT NULL))) OR (completion_fact_type IS NULL AND completion_fact_id IS NULL AND completion_fact_revision IS NULL AND completion_fact_hash IS NULL))),
    CONSTRAINT ck_task_occurrence__subject_hash_length CHECK (octet_length(subject_hash) = 32),
    CONSTRAINT ck_task_occurrence__completion_fact_hash_length CHECK (octet_length(completion_fact_hash) = 32)
);

COMMENT ON TABLE responsibility.task_occurrence IS 'Fact Owner：ResponsibilityRuntime；待办发生：一行代表针对一个冻结Subject、由一个Owner任职承担且只有一个主命令的责任实例，由责任域拥有；只可CAS推进等待或终态，不是通用工作流或作业。';
COMMENT ON CONSTRAINT pk_task_occurrence ON responsibility.task_occurrence IS '主键：在租户内唯一标识一条task_occurrence记录。';
COMMENT ON INDEX responsibility.pk_task_occurrence IS '主键：在租户内唯一标识一条task_occurrence记录。';
COMMENT ON COLUMN responsibility.task_occurrence.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN responsibility.task_occurrence.task_occurrence_id IS '待办发生标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN responsibility.task_occurrence.owner_appointment_id IS 'Owner任职标识：任务创建时冻结并以同租户复合外键关联任职，之后不可改派。';
COMMENT ON COLUMN responsibility.task_occurrence.business_purpose_code IS '业务目的代码：来自静态代码注册表，任务创建后不可修改。';
COMMENT ON COLUMN responsibility.task_occurrence.primary_command_code IS '固定主命令代码：完成该任务所允许提交的唯一主命令，创建后不可修改。';
COMMENT ON COLUMN responsibility.task_occurrence.expected_completion_fact_type IS '预期完成Fact类型：静态注册类型，创建后不可修改；DONE时必须与准确完成Fact类型一致。';
COMMENT ON COLUMN responsibility.task_occurrence.original_sla_code IS '原始SLA代码：任务发生时采用的静态规则代码，创建后不可修改。';
COMMENT ON COLUMN responsibility.task_occurrence.original_sla_seconds IS '原始SLA时长：任务发生时冻结的非负秒数，创建后不可修改。';
COMMENT ON COLUMN responsibility.task_occurrence.original_sla_due_at IS '原始SLA截止时间：任务发生时计算并冻结，后续等待或策略变化均不改写。';
COMMENT ON COLUMN responsibility.task_occurrence.state IS '任务状态：OPEN、WAITING、DONE或CANCELLED；只允许OPEN与WAITING互转并从二者进入终态。';
COMMENT ON COLUMN responsibility.task_occurrence.created_at IS '创建时间：任务发生并冻结责任信息的时间，创建后不可修改。';
COMMENT ON COLUMN responsibility.task_occurrence.completed_at IS '完成时间：状态首次变为DONE时一次写入；其他状态为空。';
COMMENT ON COLUMN responsibility.task_occurrence.cancelled_at IS '取消时间：状态首次变为CANCELLED时一次写入；其他状态为空。';
COMMENT ON COLUMN responsibility.task_occurrence.cancellation_reason_code IS '取消原因代码：取消时一次写入；其他状态为空。';
COMMENT ON COLUMN responsibility.task_occurrence.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN responsibility.task_occurrence.subject_type IS '待办发生时冻结的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN responsibility.task_occurrence.subject_id IS '待办发生时冻结的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN responsibility.task_occurrence.subject_revision IS '待办发生时冻结的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN responsibility.task_occurrence.subject_hash IS '待办发生时冻结的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN responsibility.task_occurrence.completion_fact_type IS '完成待办所产生的准确业务Fact的静态注册类型。';
COMMENT ON COLUMN responsibility.task_occurrence.completion_fact_id IS '完成待办所产生的准确业务Fact在所属租户内的准确标识。';
COMMENT ON COLUMN responsibility.task_occurrence.completion_fact_revision IS '完成待办所产生的准确业务Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN responsibility.task_occurrence.completion_fact_hash IS '完成待办所产生的准确业务Fact的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_task_occurrence__state ON responsibility.task_occurrence IS '任务状态只能取冻结的四个生命周期值。';
COMMENT ON CONSTRAINT ck_task_occurrence__original_sla_seconds_nonnegative ON responsibility.task_occurrence IS '原始SLA秒数不得为负数。';
COMMENT ON CONSTRAINT ck_task_occurrence__revision_nonnegative ON responsibility.task_occurrence IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_task_occurrence__completion_type ON responsibility.task_occurrence IS '完成类型一致性：实际准确完成Fact的类型必须等于任务创建时冻结的预期类型。';
COMMENT ON CONSTRAINT ck_task_occurrence__terminal_fields ON responsibility.task_occurrence IS '终态一致性：DONE必须准确记录完成Fact和完成时间，CANCELLED必须记录取消时间与原因，非终态不得预填终态事实。';
COMMENT ON CONSTRAINT ck_task_occurrence__subject_exact ON responsibility.task_occurrence IS '准确引用：待办发生时冻结的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_task_occurrence__completion_fact_exact ON responsibility.task_occurrence IS '准确引用：完成待办所产生的准确业务Fact必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_task_occurrence__subject_hash_length ON responsibility.task_occurrence IS '摘要格式：subject_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_task_occurrence__completion_fact_hash_length ON responsibility.task_occurrence IS '摘要格式：completion_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE responsibility.decision_record (
    tenant_id uuid NOT NULL,
    decision_record_id uuid NOT NULL,
    task_occurrence_id uuid NOT NULL,
    decision_version integer NOT NULL,
    predecessor_decision_record_id uuid,
    decided_by_appointment_id uuid NOT NULL,
    authority_slot_code varchar(64) NOT NULL,
    decision_contract_code varchar(64) NOT NULL,
    decision_contract_version integer NOT NULL,
    decision_code varchar(64) NOT NULL,
    content_digest bytea NOT NULL,
    rationale_summary text NOT NULL,
    decided_at timestamptz(6) NOT NULL,
    decision_subject_type varchar(64) NOT NULL,
    decision_subject_id uuid NOT NULL,
    decision_subject_revision bigint,
    decision_subject_hash bytea,
    CONSTRAINT pk_decision_record PRIMARY KEY (tenant_id, decision_record_id),
    CONSTRAINT uq_decision_record__task_version UNIQUE (tenant_id, task_occurrence_id, decision_version),
    CONSTRAINT uq_decision_record__predecessor UNIQUE (tenant_id, predecessor_decision_record_id),
    CONSTRAINT ck_decision_record__positive_version CHECK (decision_version > 0),
    CONSTRAINT ck_decision_record__contract_version CHECK (decision_contract_version > 0),
    CONSTRAINT ck_decision_record__predecessor_shape CHECK ((decision_version = 1 AND predecessor_decision_record_id IS NULL) OR (decision_version > 1 AND predecessor_decision_record_id IS NOT NULL)),
    CONSTRAINT ck_decision_record__decision_subject_exact CHECK ((decision_subject_type IS NOT NULL AND decision_subject_id IS NOT NULL AND ((decision_subject_revision IS NOT NULL AND decision_subject_revision >= 0 AND decision_subject_hash IS NULL) OR (decision_subject_revision IS NULL AND decision_subject_hash IS NOT NULL)))),
    CONSTRAINT ck_decision_record__content_digest_length CHECK (octet_length(content_digest) = 32),
    CONSTRAINT ck_decision_record__decision_subject_hash_length CHECK (octet_length(decision_subject_hash) = 32)
);

COMMENT ON TABLE responsibility.decision_record IS 'Fact Owner：ResponsibilityRuntime；决策记录：一行代表某个待办的一个不可变、显式版本决策事实，由责任域拥有；只能追加新版本，不覆盖旧决策，也不保存完整案情或文档正文。';
COMMENT ON CONSTRAINT pk_decision_record ON responsibility.decision_record IS '主键：在租户内唯一标识一条decision_record记录。';
COMMENT ON INDEX responsibility.pk_decision_record IS '主键：在租户内唯一标识一条decision_record记录。';
COMMENT ON COLUMN responsibility.decision_record.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN responsibility.decision_record.decision_record_id IS '决策记录标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN responsibility.decision_record.task_occurrence_id IS '所属待办标识：以同租户复合外键关联待办发生。';
COMMENT ON COLUMN responsibility.decision_record.decision_version IS '决策版本：同一待办内从一开始的正整数；唯一但连续性由命令运行时串行保证。';
COMMENT ON COLUMN responsibility.decision_record.predecessor_decision_record_id IS '前序决定标识：首版本为空，后续版本准确引用同Task直接前序。';
COMMENT ON COLUMN responsibility.decision_record.decided_by_appointment_id IS '决策人任职标识：以同租户复合外键关联任职。';
COMMENT ON COLUMN responsibility.decision_record.authority_slot_code IS '授权槽：本决定所满足或拒绝的唯一静态authoritySlot。';
COMMENT ON COLUMN responsibility.decision_record.decision_contract_code IS '决定合同代码：静态注册的结论Schema及允许结果集合。';
COMMENT ON COLUMN responsibility.decision_record.decision_contract_version IS '决定合同版本：解释本版本决定内容的正整数。';
COMMENT ON COLUMN responsibility.decision_record.decision_code IS '决策代码：来自该业务目的的静态允许列表。';
COMMENT ON COLUMN responsibility.decision_record.content_digest IS '决定内容摘要：覆盖准确Subject、authoritySlot、结论和规范依据。';
COMMENT ON COLUMN responsibility.decision_record.rationale_summary IS '脱敏理由摘要：只记录可审查的简短依据，不得包含凭据、文档正文或非必要案情。';
COMMENT ON COLUMN responsibility.decision_record.decided_at IS '决策时间：该版本决策完成并持久化的时间。';
COMMENT ON COLUMN responsibility.decision_record.decision_subject_type IS '本版本Decision实际裁定的准确业务Subject的静态注册类型。';
COMMENT ON COLUMN responsibility.decision_record.decision_subject_id IS '本版本Decision实际裁定的准确业务Subject在所属租户内的准确标识。';
COMMENT ON COLUMN responsibility.decision_record.decision_subject_revision IS '本版本Decision实际裁定的准确业务Subject的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN responsibility.decision_record.decision_subject_hash IS '本版本Decision实际裁定的准确业务Subject的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT uq_decision_record__task_version ON responsibility.decision_record IS '每个待办的决策版本号唯一，旧版本不可覆盖。';
COMMENT ON INDEX responsibility.uq_decision_record__task_version IS '每个待办的决策版本号唯一，旧版本不可覆盖。';
COMMENT ON CONSTRAINT uq_decision_record__predecessor ON responsibility.decision_record IS '单后继链：一个DecisionRecord最多只有一个直接后继版本。';
COMMENT ON INDEX responsibility.uq_decision_record__predecessor IS '单后继链：一个DecisionRecord最多只有一个直接后继版本。';
COMMENT ON CONSTRAINT ck_decision_record__positive_version ON responsibility.decision_record IS '决策版本必须为正整数。';
COMMENT ON CONSTRAINT ck_decision_record__contract_version ON responsibility.decision_record IS '决定合同版本必须为正整数。';
COMMENT ON CONSTRAINT ck_decision_record__predecessor_shape ON responsibility.decision_record IS '决定版本链：首版本无前序，后续版本必须准确引用直接前序。';
COMMENT ON CONSTRAINT ck_decision_record__decision_subject_exact ON responsibility.decision_record IS '准确引用：本版本Decision实际裁定的准确业务Subject必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_decision_record__content_digest_length ON responsibility.decision_record IS '摘要格式：content_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_decision_record__decision_subject_hash_length ON responsibility.decision_record IS '摘要格式：decision_subject_hash必须保存32字节的规范二进制值。';

CREATE TABLE responsibility.wait_receipt (
    tenant_id uuid NOT NULL,
    wait_receipt_id uuid NOT NULL,
    task_occurrence_id uuid NOT NULL,
    task_revision bigint NOT NULL,
    wait_sequence integer NOT NULL,
    wait_reason_code varchar(64) NOT NULL,
    wait_contract_code varchar(64) NOT NULL,
    wait_contract_version integer NOT NULL,
    entered_waiting_at timestamptz(6) NOT NULL,
    resume_due_at timestamptz(6),
    recorded_by_appointment_id uuid NOT NULL,
    awaited_fact_type varchar(64),
    awaited_fact_id uuid,
    awaited_fact_revision bigint,
    awaited_fact_hash bytea,
    CONSTRAINT pk_wait_receipt PRIMARY KEY (tenant_id, wait_receipt_id),
    CONSTRAINT uq_wait_receipt__task_revision UNIQUE (tenant_id, task_occurrence_id, task_revision),
    CONSTRAINT uq_wait_receipt__task_sequence UNIQUE (tenant_id, task_occurrence_id, wait_sequence),
    CONSTRAINT ck_wait_receipt__positive_task_revision CHECK (task_revision > 0),
    CONSTRAINT ck_wait_receipt__positive_sequence CHECK (wait_sequence > 0),
    CONSTRAINT ck_wait_receipt__contract_version CHECK (wait_contract_version > 0),
    CONSTRAINT ck_wait_receipt__resume_after_entry CHECK (resume_due_at IS NULL OR resume_due_at > entered_waiting_at),
    CONSTRAINT ck_wait_receipt__awaited_fact_exact CHECK (((awaited_fact_type IS NOT NULL AND awaited_fact_id IS NOT NULL AND ((awaited_fact_revision IS NOT NULL AND awaited_fact_revision >= 0 AND awaited_fact_hash IS NULL) OR (awaited_fact_revision IS NULL AND awaited_fact_hash IS NOT NULL))) OR (awaited_fact_type IS NULL AND awaited_fact_id IS NULL AND awaited_fact_revision IS NULL AND awaited_fact_hash IS NULL))),
    CONSTRAINT ck_wait_receipt__awaited_fact_hash_length CHECK (octet_length(awaited_fact_hash) = 32)
);

COMMENT ON TABLE responsibility.wait_receipt IS 'Fact Owner：ResponsibilityRuntime；等待回执：一行代表某待办一次进入WAITING的不可变追加事实，由责任域拥有；每次进入等待均新增回执，不覆盖历史，也不代表通用工作流步骤。';
COMMENT ON CONSTRAINT pk_wait_receipt ON responsibility.wait_receipt IS '主键：在租户内唯一标识一条wait_receipt记录。';
COMMENT ON INDEX responsibility.pk_wait_receipt IS '主键：在租户内唯一标识一条wait_receipt记录。';
COMMENT ON COLUMN responsibility.wait_receipt.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN responsibility.wait_receipt.wait_receipt_id IS '等待回执标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN responsibility.wait_receipt.task_occurrence_id IS '所属待办标识：以同租户复合外键关联待办发生。';
COMMENT ON COLUMN responsibility.wait_receipt.task_revision IS '入等待后的待办准确修订号：用于把回执绑定到那次状态迁移。';
COMMENT ON COLUMN responsibility.wait_receipt.wait_sequence IS '等待序号：同一待办内从一开始的正整数，用于稳定排序。';
COMMENT ON COLUMN responsibility.wait_receipt.wait_reason_code IS '等待原因代码：来自该主命令的静态允许列表。';
COMMENT ON COLUMN responsibility.wait_receipt.wait_contract_code IS '等待合同代码：静态注册的等待原因和恢复Fact约束。';
COMMENT ON COLUMN responsibility.wait_receipt.wait_contract_version IS '等待合同版本：解释本次无状态WaitReceipt的正整数版本。';
COMMENT ON COLUMN responsibility.wait_receipt.entered_waiting_at IS '进入等待时间：该次OPEN到WAITING迁移发生的时间。';
COMMENT ON COLUMN responsibility.wait_receipt.resume_due_at IS '预期恢复时间：未知时为空，不改写原始SLA。';
COMMENT ON COLUMN responsibility.wait_receipt.recorded_by_appointment_id IS '记录人任职标识：以同租户复合外键关联执行该次迁移的任职。';
COMMENT ON COLUMN responsibility.wait_receipt.awaited_fact_type IS '本次进入等待所等待的准确外部或领域Fact的静态注册类型。';
COMMENT ON COLUMN responsibility.wait_receipt.awaited_fact_id IS '本次进入等待所等待的准确外部或领域Fact在所属租户内的准确标识。';
COMMENT ON COLUMN responsibility.wait_receipt.awaited_fact_revision IS '本次进入等待所等待的准确外部或领域Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN responsibility.wait_receipt.awaited_fact_hash IS '本次进入等待所等待的准确外部或领域Fact的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT uq_wait_receipt__task_revision ON responsibility.wait_receipt IS '一个待办修订号至多对应一次进入等待回执。';
COMMENT ON INDEX responsibility.uq_wait_receipt__task_revision IS '一个待办修订号至多对应一次进入等待回执。';
COMMENT ON CONSTRAINT uq_wait_receipt__task_sequence ON responsibility.wait_receipt IS '同一待办内等待序号唯一。';
COMMENT ON INDEX responsibility.uq_wait_receipt__task_sequence IS '同一待办内等待序号唯一。';
COMMENT ON CONSTRAINT ck_wait_receipt__positive_task_revision ON responsibility.wait_receipt IS '等待回执绑定的待办修订号必须为正数。';
COMMENT ON CONSTRAINT ck_wait_receipt__positive_sequence ON responsibility.wait_receipt IS '等待序号必须为正整数。';
COMMENT ON CONSTRAINT ck_wait_receipt__contract_version ON responsibility.wait_receipt IS '等待合同版本必须为正整数。';
COMMENT ON CONSTRAINT ck_wait_receipt__resume_after_entry ON responsibility.wait_receipt IS '预期恢复时间若存在必须晚于进入等待时间。';
COMMENT ON CONSTRAINT ck_wait_receipt__awaited_fact_exact ON responsibility.wait_receipt IS '准确引用：本次进入等待所等待的准确外部或领域Fact必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_wait_receipt__awaited_fact_hash_length ON responsibility.wait_receipt IS '摘要格式：awaited_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE responsibility.action_draft (
    tenant_id uuid NOT NULL,
    action_draft_id uuid NOT NULL,
    task_occurrence_id uuid NOT NULL,
    action_code varchar(64) NOT NULL,
    payload_schema_code varchar(64) NOT NULL,
    payload_schema_version integer NOT NULL,
    candidate_payload jsonb NOT NULL,
    candidate_payload_digest bytea NOT NULL,
    state varchar(64) NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    last_edited_at timestamptz(6) NOT NULL,
    confirmed_by_appointment_id uuid,
    confirmed_at timestamptz(6),
    confirmed_payload_digest bytea,
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT pk_action_draft PRIMARY KEY (tenant_id, action_draft_id),
    CONSTRAINT uq_action_draft__task UNIQUE (tenant_id, task_occurrence_id),
    CONSTRAINT ck_action_draft__state CHECK (state IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT ck_action_draft__positive_schema_version CHECK (payload_schema_version > 0),
    CONSTRAINT ck_action_draft__confirmation_fields CHECK ((state = 'CONFIRMED' AND confirmed_by_appointment_id IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_payload_digest IS NOT NULL AND confirmed_payload_digest = candidate_payload_digest) OR (state = 'DRAFT' AND confirmed_by_appointment_id IS NULL AND confirmed_at IS NULL AND confirmed_payload_digest IS NULL)),
    CONSTRAINT ck_action_draft__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_action_draft__candidate_payload_digest_length CHECK (octet_length(candidate_payload_digest) = 32),
    CONSTRAINT ck_action_draft__confirmed_payload_digest_length CHECK (octet_length(confirmed_payload_digest) = 32)
);

COMMENT ON TABLE responsibility.action_draft IS 'Fact Owner：ResponsibilityRuntime；行动草案：一行代表某待办唯一一份按静态Schema校验的候选主命令载荷，由责任域拥有；确认前可CAS编辑且只能确认一次，不是业务最终事实或通用文档。';
COMMENT ON CONSTRAINT pk_action_draft ON responsibility.action_draft IS '主键：在租户内唯一标识一条action_draft记录。';
COMMENT ON INDEX responsibility.pk_action_draft IS '主键：在租户内唯一标识一条action_draft记录。';
COMMENT ON COLUMN responsibility.action_draft.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN responsibility.action_draft.action_draft_id IS '行动草案标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN responsibility.action_draft.task_occurrence_id IS '所属待办标识：以同租户复合外键关联待办；唯一约束保证每个待办最多一份草案。';
COMMENT ON COLUMN responsibility.action_draft.action_code IS '候选行动代码：必须等于待办冻结主命令所允许的静态代码，创建后不可修改。';
COMMENT ON COLUMN responsibility.action_draft.payload_schema_code IS '候选载荷Schema代码：来自静态应用注册表，创建后不可修改。';
COMMENT ON COLUMN responsibility.action_draft.payload_schema_version IS '候选载荷Schema版本：正整数，创建后不可修改。';
COMMENT ON COLUMN responsibility.action_draft.candidate_payload IS '候选载荷：按指定静态Schema校验的JSONB，确认前可CAS编辑；不得用作其他业务真相。';
COMMENT ON COLUMN responsibility.action_draft.candidate_payload_digest IS '候选载荷摘要：规范化JSON的32字节SHA-256，随确认前编辑一并CAS更新。';
COMMENT ON COLUMN responsibility.action_draft.state IS '草案状态：DRAFT或CONFIRMED；只允许从DRAFT一次进入CONFIRMED。';
COMMENT ON COLUMN responsibility.action_draft.created_by_appointment_id IS '创建人任职标识：以同租户复合外键关联任职，创建后不可修改。';
COMMENT ON COLUMN responsibility.action_draft.created_at IS '创建时间：草案首次持久化的时间，创建后不可修改。';
COMMENT ON COLUMN responsibility.action_draft.last_edited_at IS '最近编辑时间：每次候选载荷CAS修改时更新；未编辑时等于创建时间。';
COMMENT ON COLUMN responsibility.action_draft.confirmed_by_appointment_id IS '确认人任职标识：确认时一次写入；未确认为空。';
COMMENT ON COLUMN responsibility.action_draft.confirmed_at IS '确认时间：从DRAFT进入CONFIRMED时一次写入；未确认为空。';
COMMENT ON COLUMN responsibility.action_draft.confirmed_payload_digest IS '确认载荷摘要：确认时一次复制候选载荷摘要，只绑定输入，不代表主命令执行成功。';
COMMENT ON COLUMN responsibility.action_draft.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON CONSTRAINT uq_action_draft__task ON responsibility.action_draft IS '每个待办最多存在一份行动草案。';
COMMENT ON INDEX responsibility.uq_action_draft__task IS '每个待办最多存在一份行动草案。';
COMMENT ON CONSTRAINT ck_action_draft__state ON responsibility.action_draft IS '行动草案状态只能为DRAFT或CONFIRMED。';
COMMENT ON CONSTRAINT ck_action_draft__positive_schema_version ON responsibility.action_draft IS '候选载荷Schema版本必须为正整数。';
COMMENT ON CONSTRAINT ck_action_draft__confirmation_fields ON responsibility.action_draft IS '确认一致性：CONFIRMED一次冻结当前候选载荷摘要，DRAFT不得预填；确认本身不产生业务执行Fact。';
COMMENT ON CONSTRAINT ck_action_draft__revision_nonnegative ON responsibility.action_draft IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_action_draft__candidate_payload_digest_length ON responsibility.action_draft IS '摘要格式：candidate_payload_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_action_draft__confirmed_payload_digest_length ON responsibility.action_draft IS '摘要格式：confirmed_payload_digest必须保存32字节的规范二进制值。';
