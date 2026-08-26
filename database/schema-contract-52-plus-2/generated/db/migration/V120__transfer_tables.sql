-- 转案事实域：保存转案请求锚点、不可变提交快照和逐项退回要求。

CREATE TABLE transfer.transfer_request (
    tenant_id uuid NOT NULL,
    transfer_request_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_execution_id uuid NOT NULL,
    deal_activated_at timestamptz(6) NOT NULL,
    deal_activation_digest bytea NOT NULL,
    from_organization_unit_id uuid NOT NULL,
    to_organization_unit_id uuid NOT NULL,
    transfer_purpose_code varchar(64) NOT NULL,
    proposed_matter_type_code varchar(64) NOT NULL,
    proposed_capability_pack_code varchar(64) NOT NULL,
    proposed_capability_pack_version integer NOT NULL,
    accepted_snapshot_id uuid,
    accept_decision_record_id uuid,
    matter_id uuid,
    matter_no varchar(80),
    matter_type_code varchar(64),
    matter_capability_pack_code varchar(64),
    matter_capability_pack_version integer,
    matter_created_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_transfer_request PRIMARY KEY (tenant_id, transfer_request_id),
    CONSTRAINT uk_transfer_request__deal_activation UNIQUE (tenant_id, contract_id, deal_activation_digest),
    CONSTRAINT ck_transfer_request__different_orgs CHECK (from_organization_unit_id <> to_organization_unit_id),
    CONSTRAINT ck_transfer_request__capability_version CHECK (proposed_capability_pack_version > 0),
    CONSTRAINT ck_transfer_request__accept_complete CHECK ((accepted_snapshot_id IS NULL AND accept_decision_record_id IS NULL AND matter_id IS NULL AND matter_no IS NULL AND matter_type_code IS NULL AND matter_capability_pack_code IS NULL AND matter_capability_pack_version IS NULL AND matter_created_at IS NULL) OR (accepted_snapshot_id IS NOT NULL AND accept_decision_record_id IS NOT NULL AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND matter_type_code IS NOT NULL AND matter_capability_pack_code IS NOT NULL AND matter_capability_pack_version > 0 AND matter_created_at IS NOT NULL)),
    CONSTRAINT ck_transfer_request__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_transfer_request__deal_activation_digest_length CHECK (octet_length(deal_activation_digest) = 32)
);

COMMENT ON TABLE transfer.transfer_request IS 'Fact Owner：TransferRuntime；转案请求锚点：一行对应一次由准确DealActivated来源发起的组织间转案，只保存一次性接收和MatterRef槽，不保存通用状态或当前快照指针。';
COMMENT ON CONSTRAINT pk_transfer_request ON transfer.transfer_request IS '主键：在租户内唯一标识一条transfer_request记录。';
COMMENT ON INDEX transfer.pk_transfer_request IS '主键：在租户内唯一标识一条transfer_request记录。';
COMMENT ON COLUMN transfer.transfer_request.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.transfer_request.transfer_request_id IS '转案请求锚点标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.transfer_request.opportunity_id IS '来源商机标识：转案所承接的唯一法律需求。';
COMMENT ON COLUMN transfer.transfer_request.contract_id IS '来源合同标识：已经形成DealActivated的准确合同。';
COMMENT ON COLUMN transfer.transfer_request.contract_execution_id IS '合同执行事实标识：转案必须以准确执行事实为前提。';
COMMENT ON COLUMN transfer.transfer_request.deal_activated_at IS '交易激活时间：来源合同DealActivated槽的准确时间。';
COMMENT ON COLUMN transfer.transfer_request.deal_activation_digest IS '交易激活摘要：冻结激活依据、合同和版本的32字节摘要。';
COMMENT ON COLUMN transfer.transfer_request.from_organization_unit_id IS '转出组织标识：发起转案的准确组织单元。';
COMMENT ON COLUMN transfer.transfer_request.to_organization_unit_id IS '接收组织标识：负责案管审查和Matter接收的准确组织单元。';
COMMENT ON COLUMN transfer.transfer_request.transfer_purpose_code IS '转案目的：静态注册的业务目的代码。';
COMMENT ON COLUMN transfer.transfer_request.proposed_matter_type_code IS '拟建Matter类型：首次请求时冻结的静态类型。';
COMMENT ON COLUMN transfer.transfer_request.proposed_capability_pack_code IS '拟建能力包代码：接收方应具备的静态能力包。';
COMMENT ON COLUMN transfer.transfer_request.proposed_capability_pack_version IS '拟建能力包版本：解释Matter能力的正整数版本。';
COMMENT ON COLUMN transfer.transfer_request.accepted_snapshot_id IS '接收快照标识：ACCEPT事务中一次写入的当前叶Snapshot。';
COMMENT ON COLUMN transfer.transfer_request.accept_decision_record_id IS '接收决定标识：ACCEPT事务中一次写入的准确DecisionRecord。';
COMMENT ON COLUMN transfer.transfer_request.matter_id IS 'Matter稳定标识：接收成功时一次生成，MVP不建立Matter表。';
COMMENT ON COLUMN transfer.transfer_request.matter_no IS 'Matter编号：接收成功时一次生成的租户内稳定编号。';
COMMENT ON COLUMN transfer.transfer_request.matter_type_code IS 'Matter类型：接收成功时冻结在MatterRef中的静态类型。';
COMMENT ON COLUMN transfer.transfer_request.matter_capability_pack_code IS 'Matter能力包代码：接收成功时冻结的准确能力包。';
COMMENT ON COLUMN transfer.transfer_request.matter_capability_pack_version IS 'Matter能力包版本：接收成功时冻结的正整数版本。';
COMMENT ON COLUMN transfer.transfer_request.matter_created_at IS 'Matter创建时间：ACCEPT与MatterCreated同事务提交的可信时间。';
COMMENT ON COLUMN transfer.transfer_request.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN transfer.transfer_request.created_by_appointment_id IS '创建任职标识：发起转案请求的准确任职。';
COMMENT ON COLUMN transfer.transfer_request.created_at IS '创建时间：转案请求锚点建立的可信时间。';
COMMENT ON COLUMN transfer.transfer_request.changed_at IS '变更时间：一次性接收槽最近写入的可信时间。';
COMMENT ON CONSTRAINT uk_transfer_request__deal_activation ON transfer.transfer_request IS '来源唯一：同一合同的准确DealActivated事实只能形成一个转案请求。';
COMMENT ON INDEX transfer.uk_transfer_request__deal_activation IS '来源唯一：同一合同的准确DealActivated事实只能形成一个转案请求。';
COMMENT ON CONSTRAINT ck_transfer_request__different_orgs ON transfer.transfer_request IS '组织边界：转出组织和接收组织不得相同。';
COMMENT ON CONSTRAINT ck_transfer_request__capability_version ON transfer.transfer_request IS '拟建能力包版本必须为正数。';
COMMENT ON CONSTRAINT ck_transfer_request__accept_complete ON transfer.transfer_request IS '原子接收：acceptedSnapshot、acceptDecision和完整MatterRef必须全部为空或一次性全部写入。';
COMMENT ON CONSTRAINT ck_transfer_request__revision_nonnegative ON transfer.transfer_request IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_transfer_request__deal_activation_digest_length ON transfer.transfer_request IS '摘要格式：deal_activation_digest必须保存32字节的规范二进制值。';

CREATE TABLE transfer.transfer_snapshot (
    tenant_id uuid NOT NULL,
    transfer_snapshot_id uuid NOT NULL,
    transfer_request_id uuid NOT NULL,
    snapshot_no integer NOT NULL,
    predecessor_snapshot_id uuid,
    submission_task_occurrence_id uuid NOT NULL,
    confirmed_action_draft_id uuid NOT NULL,
    action_draft_digest bytea NOT NULL,
    contract_context_digest bytea NOT NULL,
    legal_need_context_digest bytea NOT NULL,
    material_contract_code varchar(64) NOT NULL,
    material_contract_version integer NOT NULL,
    evidence_submission_ids uuid[] NOT NULL,
    evidence_set_digest bytea NOT NULL,
    pre_transfer_review_id uuid NOT NULL,
    pre_transfer_scope_hash bytea NOT NULL,
    previous_return_decision_record_id uuid,
    previous_return_items_digest bytea,
    snapshot_digest bytea NOT NULL,
    submitted_by_appointment_id uuid NOT NULL,
    submitted_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_transfer_snapshot PRIMARY KEY (tenant_id, transfer_snapshot_id),
    CONSTRAINT uk_transfer_snapshot__request_no UNIQUE (tenant_id, transfer_request_id, snapshot_no),
    CONSTRAINT uk_transfer_snapshot__predecessor UNIQUE (tenant_id, predecessor_snapshot_id),
    CONSTRAINT uk_transfer_snapshot__submission_task UNIQUE (tenant_id, submission_task_occurrence_id),
    CONSTRAINT uk_transfer_snapshot__confirmed_draft UNIQUE (tenant_id, confirmed_action_draft_id),
    CONSTRAINT uk_transfer_snapshot__id_request UNIQUE (tenant_id, transfer_snapshot_id, transfer_request_id),
    CONSTRAINT ck_transfer_snapshot__snapshot_no CHECK (snapshot_no > 0),
    CONSTRAINT ck_transfer_snapshot__material_version CHECK (material_contract_version > 0),
    CONSTRAINT ck_transfer_snapshot__evidence_nonempty CHECK (cardinality(evidence_submission_ids) > 0),
    CONSTRAINT ck_transfer_snapshot__chain_shape CHECK ((snapshot_no = 1 AND predecessor_snapshot_id IS NULL AND previous_return_decision_record_id IS NULL AND previous_return_items_digest IS NULL) OR (snapshot_no > 1 AND predecessor_snapshot_id IS NOT NULL AND previous_return_decision_record_id IS NOT NULL AND previous_return_items_digest IS NOT NULL)),
    CONSTRAINT ck_transfer_snapshot__action_draft_digest_length CHECK (octet_length(action_draft_digest) = 32),
    CONSTRAINT ck_transfer_snapshot__contract_context_digest_length CHECK (octet_length(contract_context_digest) = 32),
    CONSTRAINT ck_transfer_snapshot__legal_need_context_digest_length CHECK (octet_length(legal_need_context_digest) = 32),
    CONSTRAINT ck_transfer_snapshot__evidence_set_digest_length CHECK (octet_length(evidence_set_digest) = 32),
    CONSTRAINT ck_transfer_snapshot__pre_transfer_scope_hash_length CHECK (octet_length(pre_transfer_scope_hash) = 32),
    CONSTRAINT ck_transfer_snapshot__previous_return_items_digest_length CHECK (octet_length(previous_return_items_digest) = 32),
    CONSTRAINT ck_transfer_snapshot__snapshot_digest_length CHECK (octet_length(snapshot_digest) = 32)
);

COMMENT ON TABLE transfer.transfer_snapshot IS 'Fact Owner：TransferRuntime；转案快照：一行表示首次提交或补正后的完整不可变版本，准确绑定一张提交Task、确认草案、材料合同、EvidenceRef集合和独立PRE_TRANSFER审查。';
COMMENT ON CONSTRAINT pk_transfer_snapshot ON transfer.transfer_snapshot IS '主键：在租户内唯一标识一条transfer_snapshot记录。';
COMMENT ON INDEX transfer.pk_transfer_snapshot IS '主键：在租户内唯一标识一条transfer_snapshot记录。';
COMMENT ON COLUMN transfer.transfer_snapshot.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.transfer_snapshot.transfer_snapshot_id IS '转案快照标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.transfer_snapshot.transfer_request_id IS '转案请求标识：快照所属的一次转案请求。';
COMMENT ON COLUMN transfer.transfer_snapshot.snapshot_no IS '快照序号：从一开始沿单后继链递增。';
COMMENT ON COLUMN transfer.transfer_snapshot.predecessor_snapshot_id IS '前序快照标识：首次提交为空，补正提交准确引用直接前序。';
COMMENT ON COLUMN transfer.transfer_snapshot.submission_task_occurrence_id IS '提交Task标识：该Snapshot完成的唯一SUBMIT或RESUBMIT责任卡。';
COMMENT ON COLUMN transfer.transfer_snapshot.confirmed_action_draft_id IS '确认草案标识：本次提交使用的准确候选输入。';
COMMENT ON COLUMN transfer.transfer_snapshot.action_draft_digest IS '草案摘要：确认时冻结的ActionDraft准确内容摘要。';
COMMENT ON COLUMN transfer.transfer_snapshot.contract_context_digest IS '合同上下文摘要：冻结合同、版本、执行和DealActivated来源。';
COMMENT ON COLUMN transfer.transfer_snapshot.legal_need_context_digest IS '法律需求上下文摘要：冻结来源商机的准确法律需求。';
COMMENT ON COLUMN transfer.transfer_snapshot.material_contract_code IS '材料合同代码：静态注册的完整材料结构。';
COMMENT ON COLUMN transfer.transfer_snapshot.material_contract_version IS '材料合同版本：解释本快照材料范围的正整数版本。';
COMMENT ON COLUMN transfer.transfer_snapshot.evidence_submission_ids IS 'EvidenceRef集合：按UUID字节升序、去重保存的准确EvidenceSubmission标识数组，由同租户Resolver逐项复验。';
COMMENT ON COLUMN transfer.transfer_snapshot.evidence_set_digest IS 'EvidenceRef集合摘要：覆盖排序后全部EvidenceSubmission标识及用途。';
COMMENT ON COLUMN transfer.transfer_snapshot.pre_transfer_review_id IS '转案前冲突审查标识：为本快照独立创建的PRE_TRANSFER审查。';
COMMENT ON COLUMN transfer.transfer_snapshot.pre_transfer_scope_hash IS '转案前审查范围摘要：必须与所引用Review的准确scopeHash一致。';
COMMENT ON COLUMN transfer.transfer_snapshot.previous_return_decision_record_id IS '前序RETURN决定标识：补正快照必须引用，首次提交为空。';
COMMENT ON COLUMN transfer.transfer_snapshot.previous_return_items_digest IS '前序退回项集合摘要：补正快照覆盖全部ReturnItem时必填。';
COMMENT ON COLUMN transfer.transfer_snapshot.snapshot_digest IS '快照摘要：覆盖全部上下文、材料、EvidenceRef、审查和前序退回信息。';
COMMENT ON COLUMN transfer.transfer_snapshot.submitted_by_appointment_id IS '提交任职标识：执行提交主命令的准确任职。';
COMMENT ON COLUMN transfer.transfer_snapshot.submitted_at IS '提交时间：快照与Task完成事实同事务封存的可信时间。';
COMMENT ON CONSTRAINT uk_transfer_snapshot__request_no ON transfer.transfer_snapshot IS '快照唯一：同一转案请求内快照序号不得重复。';
COMMENT ON INDEX transfer.uk_transfer_snapshot__request_no IS '快照唯一：同一转案请求内快照序号不得重复。';
COMMENT ON CONSTRAINT uk_transfer_snapshot__predecessor ON transfer.transfer_snapshot IS '单后继链：一个快照最多只有一个补正后继。';
COMMENT ON INDEX transfer.uk_transfer_snapshot__predecessor IS '单后继链：一个快照最多只有一个补正后继。';
COMMENT ON CONSTRAINT uk_transfer_snapshot__submission_task ON transfer.transfer_snapshot IS '单完成事实：一张提交Task只能由一个TransferSnapshot完成。';
COMMENT ON INDEX transfer.uk_transfer_snapshot__submission_task IS '单完成事实：一张提交Task只能由一个TransferSnapshot完成。';
COMMENT ON CONSTRAINT uk_transfer_snapshot__confirmed_draft ON transfer.transfer_snapshot IS '草案唯一：一份确认草案只能形成一个转案快照。';
COMMENT ON INDEX transfer.uk_transfer_snapshot__confirmed_draft IS '草案唯一：一份确认草案只能形成一个转案快照。';
COMMENT ON CONSTRAINT uk_transfer_snapshot__id_request ON transfer.transfer_snapshot IS '准确快照候选键：供退回项证明reviewedSnapshot与TransferRequest属于同一条转案链。';
COMMENT ON INDEX transfer.uk_transfer_snapshot__id_request IS '准确快照候选键：供退回项证明reviewedSnapshot与TransferRequest属于同一条转案链。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__snapshot_no ON transfer.transfer_snapshot IS '快照序号必须为正数。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__material_version ON transfer.transfer_snapshot IS '材料合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__evidence_nonempty ON transfer.transfer_snapshot IS '材料完整性：每个转案快照至少包含一个准确EvidenceRef。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__chain_shape ON transfer.transfer_snapshot IS '补正链：首次提交无前序和RETURN依据，补正必须同时引用前序快照、RETURN决定和完整退回项集合摘要。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__action_draft_digest_length ON transfer.transfer_snapshot IS '摘要格式：action_draft_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__contract_context_digest_length ON transfer.transfer_snapshot IS '摘要格式：contract_context_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__legal_need_context_digest_length ON transfer.transfer_snapshot IS '摘要格式：legal_need_context_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__evidence_set_digest_length ON transfer.transfer_snapshot IS '摘要格式：evidence_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__pre_transfer_scope_hash_length ON transfer.transfer_snapshot IS '摘要格式：pre_transfer_scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__previous_return_items_digest_length ON transfer.transfer_snapshot IS '摘要格式：previous_return_items_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_snapshot__snapshot_digest_length ON transfer.transfer_snapshot IS '摘要格式：snapshot_digest必须保存32字节的规范二进制值。';

CREATE TABLE transfer.transfer_return_item (
    tenant_id uuid NOT NULL,
    transfer_return_item_id uuid NOT NULL,
    transfer_request_id uuid NOT NULL,
    reviewed_snapshot_id uuid NOT NULL,
    return_decision_record_id uuid NOT NULL,
    item_no integer NOT NULL,
    requirement_code varchar(64) NOT NULL,
    requirement_contract_version integer NOT NULL,
    reason_code varchar(64) NOT NULL,
    correction_instruction text NOT NULL,
    required_evidence_purpose_code varchar(64),
    item_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    required_target_type varchar(64) NOT NULL,
    required_target_id uuid NOT NULL,
    required_target_revision bigint,
    required_target_hash bytea,
    CONSTRAINT pk_transfer_return_item PRIMARY KEY (tenant_id, transfer_return_item_id),
    CONSTRAINT uk_transfer_return_item__decision_no UNIQUE (tenant_id, return_decision_record_id, item_no),
    CONSTRAINT ck_transfer_return_item__item_no CHECK (item_no > 0),
    CONSTRAINT ck_transfer_return_item__contract_version CHECK (requirement_contract_version > 0),
    CONSTRAINT ck_transfer_return_item__required_target_exact CHECK ((required_target_type IS NOT NULL AND required_target_id IS NOT NULL AND ((required_target_revision IS NOT NULL AND required_target_revision >= 0 AND required_target_hash IS NULL) OR (required_target_revision IS NULL AND required_target_hash IS NOT NULL)))),
    CONSTRAINT ck_transfer_return_item__item_digest_length CHECK (octet_length(item_digest) = 32),
    CONSTRAINT ck_transfer_return_item__required_target_hash_length CHECK (octet_length(required_target_hash) = 32)
);

COMMENT ON TABLE transfer.transfer_return_item IS 'Fact Owner：TransferRuntime；转案退回项：一行保存针对准确已审快照和RETURN决定的一项不可变补正要求，不设置OPEN或RESOLVED状态。';
COMMENT ON CONSTRAINT pk_transfer_return_item ON transfer.transfer_return_item IS '主键：在租户内唯一标识一条transfer_return_item记录。';
COMMENT ON INDEX transfer.pk_transfer_return_item IS '主键：在租户内唯一标识一条transfer_return_item记录。';
COMMENT ON COLUMN transfer.transfer_return_item.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN transfer.transfer_return_item.transfer_return_item_id IS '转案退回项标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN transfer.transfer_return_item.transfer_request_id IS '转案请求标识：退回项所属请求，用于同链一致性复验。';
COMMENT ON COLUMN transfer.transfer_return_item.reviewed_snapshot_id IS '已审快照标识：RETURN决定实际审查的准确Snapshot。';
COMMENT ON COLUMN transfer.transfer_return_item.return_decision_record_id IS 'RETURN决定标识：与本退回项同事务创建的准确DecisionRecord。';
COMMENT ON COLUMN transfer.transfer_return_item.item_no IS '退回项序号：在一次RETURN决定内稳定排序。';
COMMENT ON COLUMN transfer.transfer_return_item.requirement_code IS '要求代码：允许列表化的缺失或不符合项类型。';
COMMENT ON COLUMN transfer.transfer_return_item.requirement_contract_version IS '要求合同版本：解释目标和补正指令的正整数版本。';
COMMENT ON COLUMN transfer.transfer_return_item.reason_code IS '原因代码：允许列表化的退回原因。';
COMMENT ON COLUMN transfer.transfer_return_item.correction_instruction IS '补正指令：最小必要的结构化文字说明，不保存文档正文。';
COMMENT ON COLUMN transfer.transfer_return_item.required_evidence_purpose_code IS '所需证据用途：要求新增Evidence时使用的静态用途代码。';
COMMENT ON COLUMN transfer.transfer_return_item.item_digest IS '退回项摘要：覆盖要求、目标、版本、原因和补正指令。';
COMMENT ON COLUMN transfer.transfer_return_item.created_at IS '创建时间：与RETURN决定同事务写入的可信时间。';
COMMENT ON COLUMN transfer.transfer_return_item.required_target_type IS '退回项要求补正的准确目标的静态注册类型。';
COMMENT ON COLUMN transfer.transfer_return_item.required_target_id IS '退回项要求补正的准确目标在所属租户内的准确标识。';
COMMENT ON COLUMN transfer.transfer_return_item.required_target_revision IS '退回项要求补正的准确目标的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN transfer.transfer_return_item.required_target_hash IS '退回项要求补正的准确目标的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT uk_transfer_return_item__decision_no ON transfer.transfer_return_item IS '退回项唯一：一次RETURN决定内项目序号不得重复。';
COMMENT ON INDEX transfer.uk_transfer_return_item__decision_no IS '退回项唯一：一次RETURN决定内项目序号不得重复。';
COMMENT ON CONSTRAINT ck_transfer_return_item__item_no ON transfer.transfer_return_item IS '退回项序号必须为正数。';
COMMENT ON CONSTRAINT ck_transfer_return_item__contract_version ON transfer.transfer_return_item IS '要求合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_transfer_return_item__required_target_exact ON transfer.transfer_return_item IS '准确引用：退回项要求补正的准确目标必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_transfer_return_item__item_digest_length ON transfer.transfer_return_item IS '摘要格式：item_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_transfer_return_item__required_target_hash_length ON transfer.transfer_return_item IS '摘要格式：required_target_hash必须保存32字节的规范二进制值。';
