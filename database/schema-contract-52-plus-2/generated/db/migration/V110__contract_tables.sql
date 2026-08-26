-- 合同事实域：保存版本化合同包、准确签署、执行、付款、激活和终止事实。

CREATE TABLE contract.contract (
    tenant_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    accepted_quote_response_id uuid NOT NULL,
    current_revision_id uuid,
    approved_revision_id uuid,
    contract_execution_id uuid,
    deal_activated_at timestamptz(6),
    contract_termination_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    activation_source_type varchar(64),
    activation_source_id uuid,
    activation_source_revision bigint,
    activation_source_hash bytea,
    CONSTRAINT pk_contract PRIMARY KEY (tenant_id, contract_id),
    CONSTRAINT uk_contract__accepted_quote_response UNIQUE (tenant_id, accepted_quote_response_id),
    CONSTRAINT uk_contract__id_opportunity_execution UNIQUE (tenant_id, contract_id, opportunity_id, contract_execution_id),
    CONSTRAINT ck_contract__activation_complete CHECK ((deal_activated_at IS NULL AND activation_source_type IS NULL) OR (deal_activated_at IS NOT NULL AND activation_source_type IS NOT NULL)),
    CONSTRAINT ck_contract__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_contract__activation_source_exact CHECK (((activation_source_type IS NOT NULL AND activation_source_id IS NOT NULL AND ((activation_source_revision IS NOT NULL AND activation_source_revision >= 0 AND activation_source_hash IS NULL) OR (activation_source_revision IS NULL AND activation_source_hash IS NOT NULL))) OR (activation_source_type IS NULL AND activation_source_id IS NULL AND activation_source_revision IS NULL AND activation_source_hash IS NULL))),
    CONSTRAINT ck_contract__activation_source_hash_length CHECK (octet_length(activation_source_hash) = 32)
);

COMMENT ON TABLE contract.contract IS 'Fact Owner：ContractRuntime；合同锚点：一行对应一份由准确商机和接受报价形成的合同，只保存当前版本与当前批准指针，以及执行、激活、终止的单向槽位，不代表合同已经生效。';
COMMENT ON CONSTRAINT pk_contract ON contract.contract IS '主键：在租户内唯一标识一条contract记录。';
COMMENT ON INDEX contract.pk_contract IS '主键：在租户内唯一标识一条contract记录。';
COMMENT ON COLUMN contract.contract.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract.contract_id IS '合同锚点标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract.opportunity_id IS '来源商机标识：合同所承接的唯一法律需求。';
COMMENT ON COLUMN contract.contract.accepted_quote_response_id IS '接受报价回应标识：合同成立准备工作的准确销售来源。';
COMMENT ON COLUMN contract.contract.current_revision_id IS '当前合同版本标识：可随新版本前移，但不改变旧版本。';
COMMENT ON COLUMN contract.contract.approved_revision_id IS '当前已批准合同版本标识：只能等于当前版本；形成新版本时可原子清空或前移，旧批准历史保留在准确DecisionRecord中，执行后冻结。';
COMMENT ON COLUMN contract.contract.contract_execution_id IS '合同执行事实标识：全部执行门禁通过后一次写入。';
COMMENT ON COLUMN contract.contract.deal_activated_at IS '交易激活时间：首款门禁或风险决定满足后一次写入。';
COMMENT ON COLUMN contract.contract.contract_termination_id IS '合同取消或终止事实标识：形成终止事实后一次写入。';
COMMENT ON COLUMN contract.contract.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.contract.created_at IS '创建时间：合同锚点首次建立的可信服务端时间。';
COMMENT ON COLUMN contract.contract.changed_at IS '变更时间：最近一次受控槽位更新的可信服务端时间。';
COMMENT ON COLUMN contract.contract.activation_source_type IS '合同激活依据事实的静态注册类型。';
COMMENT ON COLUMN contract.contract.activation_source_id IS '合同激活依据事实在所属租户内的准确标识。';
COMMENT ON COLUMN contract.contract.activation_source_revision IS '合同激活依据事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN contract.contract.activation_source_hash IS '合同激活依据事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT uk_contract__accepted_quote_response ON contract.contract IS '来源唯一：一条接受报价回应只能形成一份合同。';
COMMENT ON INDEX contract.uk_contract__accepted_quote_response IS '来源唯一：一条接受报价回应只能形成一份合同。';
COMMENT ON CONSTRAINT uk_contract__id_opportunity_execution ON contract.contract IS '准确转案来源候选键：供TransferRequest证明Opportunity、Contract及Execution属于同一合同链。';
COMMENT ON INDEX contract.uk_contract__id_opportunity_execution IS '准确转案来源候选键：供TransferRequest证明Opportunity、Contract及Execution属于同一合同链。';
COMMENT ON CONSTRAINT ck_contract__activation_complete ON contract.contract IS '激活完整性：激活时间与准确激活依据必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_contract__revision_nonnegative ON contract.contract IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_contract__activation_source_exact ON contract.contract IS '准确引用：合同激活依据事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_contract__activation_source_hash_length ON contract.contract IS '摘要格式：activation_source_hash必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_revision (
    tenant_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    revision_no integer NOT NULL,
    predecessor_revision_id uuid,
    confirmed_action_draft_id uuid NOT NULL,
    source_quote_revision_id uuid NOT NULL,
    source_quote_response_id uuid NOT NULL,
    body_evidence_submission_id uuid NOT NULL,
    body_sha256 bytea NOT NULL,
    pre_contract_review_id uuid NOT NULL,
    pre_contract_scope_hash bytea NOT NULL,
    pre_contract_resolution_digest bytea NOT NULL,
    package_contract_code varchar(64) NOT NULL,
    package_contract_version integer NOT NULL,
    content_digest bytea NOT NULL,
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_revision PRIMARY KEY (tenant_id, contract_revision_id),
    CONSTRAINT uk_contract_revision__contract_revision_no UNIQUE (tenant_id, contract_id, revision_no),
    CONSTRAINT uk_contract_revision__predecessor UNIQUE (tenant_id, predecessor_revision_id),
    CONSTRAINT uk_contract_revision__confirmed_draft UNIQUE (tenant_id, confirmed_action_draft_id),
    CONSTRAINT uk_contract_revision__id_contract UNIQUE (tenant_id, contract_revision_id, contract_id),
    CONSTRAINT ck_contract_revision__revision_no CHECK (revision_no > 0),
    CONSTRAINT ck_contract_revision__predecessor_shape CHECK ((revision_no = 1 AND predecessor_revision_id IS NULL) OR (revision_no > 1 AND predecessor_revision_id IS NOT NULL)),
    CONSTRAINT ck_contract_revision__package_version CHECK (package_contract_version > 0),
    CONSTRAINT ck_contract_revision__body_sha256_length CHECK (octet_length(body_sha256) = 32),
    CONSTRAINT ck_contract_revision__pre_contract_scope_hash_length CHECK (octet_length(pre_contract_scope_hash) = 32),
    CONSTRAINT ck_contract_revision__pre_contract_resolution_digest_length CHECK (octet_length(pre_contract_resolution_digest) = 32),
    CONSTRAINT ck_contract_revision__content_digest_length CHECK (octet_length(content_digest) = 32)
);

COMMENT ON TABLE contract.contract_revision IS 'Fact Owner：ContractRuntime；合同版本：一行连同其参与方、费用、付款门禁和签署计划构成一个不可变版本包，旧批准、签名和正文不得复用。';
COMMENT ON CONSTRAINT pk_contract_revision ON contract.contract_revision IS '主键：在租户内唯一标识一条contract_revision记录。';
COMMENT ON INDEX contract.pk_contract_revision IS '主键：在租户内唯一标识一条contract_revision记录。';
COMMENT ON COLUMN contract.contract_revision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_revision.contract_revision_id IS '合同版本标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_revision.contract_id IS '合同标识：该版本所属的合同锚点。';
COMMENT ON COLUMN contract.contract_revision.revision_no IS '版本序号：从一开始在同一合同内连续递增。';
COMMENT ON COLUMN contract.contract_revision.predecessor_revision_id IS '前序合同版本标识：首版本为空，其余版本准确引用直接前序。';
COMMENT ON COLUMN contract.contract_revision.confirmed_action_draft_id IS '确认草案标识：形成该版本包的准确候选输入。';
COMMENT ON COLUMN contract.contract_revision.source_quote_revision_id IS '来源报价版本标识：商业条件的准确来源。';
COMMENT ON COLUMN contract.contract_revision.source_quote_response_id IS '来源报价回应标识：客户接受的准确发出与回应链。';
COMMENT ON COLUMN contract.contract_revision.body_evidence_submission_id IS '合同正文证据提交标识：准确指向不可变正文对象。';
COMMENT ON COLUMN contract.contract_revision.body_sha256 IS '合同正文SHA-256：正文准确对象字节的32字节服务端摘要。';
COMMENT ON COLUMN contract.contract_revision.pre_contract_review_id IS '签约前冲突审查标识：该版本包冻结的独立PRE_CONTRACT审查。';
COMMENT ON COLUMN contract.contract_revision.pre_contract_scope_hash IS '签约前审查范围摘要：准确参与方、规则和语料范围的32字节摘要。';
COMMENT ON COLUMN contract.contract_revision.pre_contract_resolution_digest IS '签约前审查结论摘要：可用于本版本放行的准确结论摘要。';
COMMENT ON COLUMN contract.contract_revision.package_contract_code IS '版本包合同代码：静态注册的合同结构类型。';
COMMENT ON COLUMN contract.contract_revision.package_contract_version IS '版本包合同版本：解释全部子项结构的正整数版本。';
COMMENT ON COLUMN contract.contract_revision.content_digest IS '版本包内容摘要：覆盖正文对象版本、全部子项和签约前审查快照。';
COMMENT ON COLUMN contract.contract_revision.created_by_appointment_id IS '创建任职标识：确认并提交该版本包的准确任职。';
COMMENT ON COLUMN contract.contract_revision.created_at IS '创建时间：版本包在同一短事务封存的可信时间。';
COMMENT ON CONSTRAINT uk_contract_revision__contract_revision_no ON contract.contract_revision IS '版本唯一：同一合同内版本序号不得重复。';
COMMENT ON INDEX contract.uk_contract_revision__contract_revision_no IS '版本唯一：同一合同内版本序号不得重复。';
COMMENT ON CONSTRAINT uk_contract_revision__predecessor ON contract.contract_revision IS '单后继链：一个合同版本最多只有一个直接后继。';
COMMENT ON INDEX contract.uk_contract_revision__predecessor IS '单后继链：一个合同版本最多只有一个直接后继。';
COMMENT ON CONSTRAINT uk_contract_revision__confirmed_draft ON contract.contract_revision IS '草案唯一：一份确认草案只能形成一个合同版本包。';
COMMENT ON INDEX contract.uk_contract_revision__confirmed_draft IS '草案唯一：一份确认草案只能形成一个合同版本包。';
COMMENT ON CONSTRAINT uk_contract_revision__id_contract ON contract.contract_revision IS '准确版本归属候选键：供执行、付款和终止事实证明版本属于同一Contract。';
COMMENT ON INDEX contract.uk_contract_revision__id_contract IS '准确版本归属候选键：供执行、付款和终止事实证明版本属于同一Contract。';
COMMENT ON CONSTRAINT ck_contract_revision__revision_no ON contract.contract_revision IS '版本序号必须为正数。';
COMMENT ON CONSTRAINT ck_contract_revision__predecessor_shape ON contract.contract_revision IS '版本链形态：首版本无前序，后续版本必须有直接前序。';
COMMENT ON CONSTRAINT ck_contract_revision__package_version ON contract.contract_revision IS '版本包合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_contract_revision__body_sha256_length ON contract.contract_revision IS '摘要格式：body_sha256必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_revision__pre_contract_scope_hash_length ON contract.contract_revision IS '摘要格式：pre_contract_scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_revision__pre_contract_resolution_digest_length ON contract.contract_revision IS '摘要格式：pre_contract_resolution_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_revision__content_digest_length ON contract.contract_revision IS '摘要格式：content_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_participation (
    tenant_id uuid NOT NULL,
    contract_participation_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    participation_no integer NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    party_snapshot_digest bytea NOT NULL,
    context_role_code varchar(64) NOT NULL,
    source_opportunity_participation_id uuid,
    signature_required boolean NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_participation PRIMARY KEY (tenant_id, contract_participation_id),
    CONSTRAINT uk_contract_participation__revision_no UNIQUE (tenant_id, contract_revision_id, participation_no),
    CONSTRAINT uk_contract_participation__revision_party_role UNIQUE (tenant_id, contract_revision_id, party_id, context_role_code),
    CONSTRAINT uk_contract_participation__id_revision_party UNIQUE (tenant_id, contract_participation_id, contract_revision_id, party_id),
    CONSTRAINT ck_contract_participation__no_positive CHECK (participation_no > 0),
    CONSTRAINT ck_contract_participation__party_revision CHECK (party_revision >= 0),
    CONSTRAINT ck_contract_participation__party_snapshot_digest_length CHECK (octet_length(party_snapshot_digest) = 32)
);

COMMENT ON TABLE contract.contract_participation IS 'Fact Owner：ContractRuntime；合同参与方：一行冻结一个合同版本中的一项主体角色和准确Party修订，不表示该主体已经签署。';
COMMENT ON CONSTRAINT pk_contract_participation ON contract.contract_participation IS '主键：在租户内唯一标识一条contract_participation记录。';
COMMENT ON INDEX contract.pk_contract_participation IS '主键：在租户内唯一标识一条contract_participation记录。';
COMMENT ON COLUMN contract.contract_participation.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_participation.contract_participation_id IS '合同参与方标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_participation.contract_revision_id IS '合同版本标识：该参与方所属的不可变版本包。';
COMMENT ON COLUMN contract.contract_participation.participation_no IS '参与项序号：在合同版本内稳定排序。';
COMMENT ON COLUMN contract.contract_participation.party_id IS '主体标识：参与合同的当前态Party锚点。';
COMMENT ON COLUMN contract.contract_participation.party_revision IS 'Party CAS修订号：形成版本包时用于提交前重验，不声称可从当前态Party回读历史版本。';
COMMENT ON COLUMN contract.contract_participation.party_snapshot_digest IS '合同主体快照摘要：冻结本版本所需的最小规范名称、主标识选择及角色上下文。';
COMMENT ON COLUMN contract.contract_participation.context_role_code IS '上下文角色：静态注册的委托人、对方、签署人等角色。';
COMMENT ON COLUMN contract.contract_participation.source_opportunity_participation_id IS '来源商机参与项标识：可追溯到销售阶段的准确参与事实。';
COMMENT ON COLUMN contract.contract_participation.signature_required IS '签署要求：该参与方是否必须拥有至少一个签署计划槽。';
COMMENT ON COLUMN contract.contract_participation.created_at IS '创建时间：随合同版本包封存的可信时间。';
COMMENT ON CONSTRAINT uk_contract_participation__revision_no ON contract.contract_participation IS '参与项唯一：同一合同版本内序号不得重复。';
COMMENT ON INDEX contract.uk_contract_participation__revision_no IS '参与项唯一：同一合同版本内序号不得重复。';
COMMENT ON CONSTRAINT uk_contract_participation__revision_party_role ON contract.contract_participation IS '角色唯一：同一版本内同一主体的同一上下文角色不得重复。';
COMMENT ON INDEX contract.uk_contract_participation__revision_party_role IS '角色唯一：同一版本内同一主体的同一上下文角色不得重复。';
COMMENT ON CONSTRAINT uk_contract_participation__id_revision_party ON contract.contract_participation IS '准确签署参与候选键：供SignaturePlan证明参与项、版本和签署Party一致。';
COMMENT ON INDEX contract.uk_contract_participation__id_revision_party IS '准确签署参与候选键：供SignaturePlan证明参与项、版本和签署Party一致。';
COMMENT ON CONSTRAINT ck_contract_participation__no_positive ON contract.contract_participation IS '参与项序号必须为正数。';
COMMENT ON CONSTRAINT ck_contract_participation__party_revision ON contract.contract_participation IS '冻结的Party修订号不得为负数。';
COMMENT ON CONSTRAINT ck_contract_participation__party_snapshot_digest_length ON contract.contract_participation IS '摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_fee_term (
    tenant_id uuid NOT NULL,
    contract_fee_term_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    term_no integer NOT NULL,
    fee_type_code varchar(64) NOT NULL,
    amount_minor bigint NOT NULL,
    currency_code varchar(3) NOT NULL,
    calculation_contract_code varchar(64) NOT NULL,
    calculation_contract_version integer NOT NULL,
    source_quote_line_id uuid,
    term_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_fee_term PRIMARY KEY (tenant_id, contract_fee_term_id),
    CONSTRAINT uk_contract_fee_term__revision_no UNIQUE (tenant_id, contract_revision_id, term_no),
    CONSTRAINT ck_contract_fee_term__no_positive CHECK (term_no > 0),
    CONSTRAINT ck_contract_fee_term__amount_nonnegative CHECK (amount_minor >= 0),
    CONSTRAINT ck_contract_fee_term__currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_fee_term__contract_version CHECK (calculation_contract_version > 0),
    CONSTRAINT ck_contract_fee_term__term_digest_length CHECK (octet_length(term_digest) = 32)
);

COMMENT ON TABLE contract.contract_fee_term IS 'Fact Owner：ContractRuntime；合同费用条款：一行保存合同版本中的一项不可变费用约定，不代表付款已经发生。';
COMMENT ON CONSTRAINT pk_contract_fee_term ON contract.contract_fee_term IS '主键：在租户内唯一标识一条contract_fee_term记录。';
COMMENT ON INDEX contract.pk_contract_fee_term IS '主键：在租户内唯一标识一条contract_fee_term记录。';
COMMENT ON COLUMN contract.contract_fee_term.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_fee_term.contract_fee_term_id IS '合同费用条款标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_fee_term.contract_revision_id IS '合同版本标识：费用条款所属版本包。';
COMMENT ON COLUMN contract.contract_fee_term.term_no IS '条款序号：在合同版本内稳定排序。';
COMMENT ON COLUMN contract.contract_fee_term.fee_type_code IS '费用类型：静态注册的固定费、计时费、风险代理等类型。';
COMMENT ON COLUMN contract.contract_fee_term.amount_minor IS '约定金额：以currency_code最小货币单位表示的非负金额。';
COMMENT ON COLUMN contract.contract_fee_term.currency_code IS '币种代码：三位大写ISO 4217代码。';
COMMENT ON COLUMN contract.contract_fee_term.calculation_contract_code IS '计费合同代码：解释费用计算方式的静态代码。';
COMMENT ON COLUMN contract.contract_fee_term.calculation_contract_version IS '计费合同版本：解释计算参数的正整数版本。';
COMMENT ON COLUMN contract.contract_fee_term.source_quote_line_id IS '来源报价行标识：费用来源于报价时准确引用。';
COMMENT ON COLUMN contract.contract_fee_term.term_digest IS '条款摘要：规范化费用条款的32字节摘要。';
COMMENT ON COLUMN contract.contract_fee_term.created_at IS '创建时间：随合同版本包封存的可信时间。';
COMMENT ON CONSTRAINT uk_contract_fee_term__revision_no ON contract.contract_fee_term IS '条款唯一：同一合同版本内条款序号不得重复。';
COMMENT ON INDEX contract.uk_contract_fee_term__revision_no IS '条款唯一：同一合同版本内条款序号不得重复。';
COMMENT ON CONSTRAINT ck_contract_fee_term__no_positive ON contract.contract_fee_term IS '条款序号必须为正数。';
COMMENT ON CONSTRAINT ck_contract_fee_term__amount_nonnegative ON contract.contract_fee_term IS '费用金额不得为负数。';
COMMENT ON CONSTRAINT ck_contract_fee_term__currency ON contract.contract_fee_term IS '币种必须为三位大写代码。';
COMMENT ON CONSTRAINT ck_contract_fee_term__contract_version ON contract.contract_fee_term IS '计费合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_contract_fee_term__term_digest_length ON contract.contract_fee_term IS '摘要格式：term_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.payment_gate (
    tenant_id uuid NOT NULL,
    payment_gate_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    gate_kind varchar(64) NOT NULL,
    required_amount_minor bigint,
    currency_code varchar(3),
    gate_state varchar(64) NOT NULL,
    satisfied_at timestamptz(6),
    satisfaction_digest bytea,
    payment_confirmation_ids uuid[],
    confirmation_set_digest bytea,
    risk_decision_record_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_payment_gate PRIMARY KEY (tenant_id, payment_gate_id),
    CONSTRAINT uk_payment_gate__contract_revision UNIQUE (tenant_id, contract_revision_id),
    CONSTRAINT ck_payment_gate__gate_kind CHECK (gate_kind IN ('FIRST_PAYMENT', 'RISK_DECISION')),
    CONSTRAINT ck_payment_gate__gate_state CHECK (gate_state IN ('PENDING', 'SATISFIED')),
    CONSTRAINT ck_payment_gate__kind_payload CHECK ((gate_kind = 'FIRST_PAYMENT' AND required_amount_minor IS NOT NULL AND required_amount_minor > 0 AND currency_code ~ '^[A-Z]{3}$') OR (gate_kind = 'RISK_DECISION' AND required_amount_minor IS NULL AND currency_code IS NULL)),
    CONSTRAINT ck_payment_gate__satisfaction CHECK ((gate_state = 'PENDING' AND satisfied_at IS NULL AND satisfaction_digest IS NULL AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NULL) OR (gate_state = 'SATISFIED' AND satisfied_at IS NOT NULL AND satisfaction_digest IS NOT NULL AND ((gate_kind = 'FIRST_PAYMENT' AND payment_confirmation_ids IS NOT NULL AND cardinality(payment_confirmation_ids) > 0 AND confirmation_set_digest IS NOT NULL AND risk_decision_record_id IS NULL) OR (gate_kind = 'RISK_DECISION' AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NOT NULL)))),
    CONSTRAINT ck_payment_gate__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_payment_gate__satisfaction_digest_length CHECK (octet_length(satisfaction_digest) = 32),
    CONSTRAINT ck_payment_gate__confirmation_set_digest_length CHECK (octet_length(confirmation_set_digest) = 32)
);

COMMENT ON TABLE contract.payment_gate IS 'Fact Owner：ContractRuntime；付款门禁：一行冻结合同版本的首款或风险激活条件，并只允许从未满足单向写入准确满足事实。';
COMMENT ON CONSTRAINT pk_payment_gate ON contract.payment_gate IS '主键：在租户内唯一标识一条payment_gate记录。';
COMMENT ON INDEX contract.pk_payment_gate IS '主键：在租户内唯一标识一条payment_gate记录。';
COMMENT ON COLUMN contract.payment_gate.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.payment_gate.payment_gate_id IS '付款门禁标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.payment_gate.contract_revision_id IS '合同版本标识：付款门禁所属的不可变版本包。';
COMMENT ON COLUMN contract.payment_gate.gate_kind IS '门禁类型：FIRST_PAYMENT表示首款，RISK_DECISION表示专门风险决定。';
COMMENT ON COLUMN contract.payment_gate.required_amount_minor IS '首款要求金额：最小货币单位；风险决定门禁为空。';
COMMENT ON COLUMN contract.payment_gate.currency_code IS '首款币种：三位大写代码；风险决定门禁为空。';
COMMENT ON COLUMN contract.payment_gate.gate_state IS '门禁状态：PENDING或SATISFIED，只允许单向满足。';
COMMENT ON COLUMN contract.payment_gate.satisfied_at IS '满足时间：准确Confirmation集合或风险决定通过后一次写入。';
COMMENT ON COLUMN contract.payment_gate.satisfaction_digest IS '满足摘要：覆盖门禁条件及其准确满足依据。';
COMMENT ON COLUMN contract.payment_gate.payment_confirmation_ids IS '到账确认集合：FIRST_PAYMENT满足时按UUID字节升序去重冻结的准确PaymentConfirmation标识；其他状态或门禁类型为空。';
COMMENT ON COLUMN contract.payment_gate.confirmation_set_digest IS '到账确认集合摘要：FIRST_PAYMENT满足时准确冻结。';
COMMENT ON COLUMN contract.payment_gate.risk_decision_record_id IS '风险激活决定标识：RISK_DECISION满足时准确引用。';
COMMENT ON COLUMN contract.payment_gate.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.payment_gate.created_at IS '创建时间：随合同版本包封存的可信时间。';
COMMENT ON COLUMN contract.payment_gate.changed_at IS '变更时间：门禁最近一次受控更新的可信时间。';
COMMENT ON CONSTRAINT uk_payment_gate__contract_revision ON contract.payment_gate IS '版本唯一：一个合同版本最多具有一个付款门禁。';
COMMENT ON INDEX contract.uk_payment_gate__contract_revision IS '版本唯一：一个合同版本最多具有一个付款门禁。';
COMMENT ON CONSTRAINT ck_payment_gate__gate_kind ON contract.payment_gate IS '门禁类型仅允许首款或专门风险决定。';
COMMENT ON CONSTRAINT ck_payment_gate__gate_state ON contract.payment_gate IS '门禁状态仅允许未满足或已满足。';
COMMENT ON CONSTRAINT ck_payment_gate__kind_payload ON contract.payment_gate IS '门禁载荷：首款门禁必须有正金额和币种，风险决定门禁不得伪造零元付款。';
COMMENT ON CONSTRAINT ck_payment_gate__satisfaction ON contract.payment_gate IS '满足完整性：首款门禁冻结非空准确Confirmation集合和摘要，风险门禁只引用专门DecisionRecord。';
COMMENT ON CONSTRAINT ck_payment_gate__revision_nonnegative ON contract.payment_gate IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_payment_gate__satisfaction_digest_length ON contract.payment_gate IS '摘要格式：satisfaction_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_payment_gate__confirmation_set_digest_length ON contract.payment_gate IS '摘要格式：confirmation_set_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.signature_plan (
    tenant_id uuid NOT NULL,
    signature_plan_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    slot_no integer NOT NULL,
    authority_slot_code varchar(64) NOT NULL,
    contract_participation_id uuid NOT NULL,
    signer_party_id uuid NOT NULL,
    signature_method_code varchar(64) NOT NULL,
    seal_required boolean NOT NULL,
    required boolean NOT NULL,
    plan_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_signature_plan PRIMARY KEY (tenant_id, signature_plan_id),
    CONSTRAINT uk_signature_plan__revision_slot_no UNIQUE (tenant_id, contract_revision_id, slot_no),
    CONSTRAINT uk_signature_plan__revision_authority_slot UNIQUE (tenant_id, contract_revision_id, authority_slot_code),
    CONSTRAINT uk_signature_plan__id_revision_signer UNIQUE (tenant_id, signature_plan_id, contract_revision_id, signer_party_id),
    CONSTRAINT ck_signature_plan__slot_no_positive CHECK (slot_no > 0),
    CONSTRAINT ck_signature_plan__plan_digest_length CHECK (octet_length(plan_digest) = 32)
);

COMMENT ON TABLE contract.signature_plan IS 'Fact Owner：ContractRuntime；签署计划槽：一行冻结合同版本中的一个必需或可选签署槽，不代表已经签署。';
COMMENT ON CONSTRAINT pk_signature_plan ON contract.signature_plan IS '主键：在租户内唯一标识一条signature_plan记录。';
COMMENT ON INDEX contract.pk_signature_plan IS '主键：在租户内唯一标识一条signature_plan记录。';
COMMENT ON COLUMN contract.signature_plan.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.signature_plan.signature_plan_id IS '签署计划槽标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.signature_plan.contract_revision_id IS '合同版本标识：签署槽所属的准确版本包。';
COMMENT ON COLUMN contract.signature_plan.slot_no IS '签署槽序号：在合同版本内稳定排序。';
COMMENT ON COLUMN contract.signature_plan.authority_slot_code IS '签署授权槽代码：静态注册的签署能力要求。';
COMMENT ON COLUMN contract.signature_plan.contract_participation_id IS '合同参与项标识：计划签署人对应的准确参与事实。';
COMMENT ON COLUMN contract.signature_plan.signer_party_id IS '签署主体标识：必须与合同参与项中的Party一致。';
COMMENT ON COLUMN contract.signature_plan.signature_method_code IS '签署方式：静态注册的电子、线下等方式。';
COMMENT ON COLUMN contract.signature_plan.seal_required IS '印章要求：该槽是否必须核验准确印章事实。';
COMMENT ON COLUMN contract.signature_plan.required IS '必需标志：执行前该槽是否必须具有有效签署事实。';
COMMENT ON COLUMN contract.signature_plan.plan_digest IS '计划摘要：该签署槽规范内容的32字节摘要。';
COMMENT ON COLUMN contract.signature_plan.created_at IS '创建时间：随合同版本包封存的可信时间。';
COMMENT ON CONSTRAINT uk_signature_plan__revision_slot_no ON contract.signature_plan IS '签署槽唯一：同一合同版本内槽序号不得重复。';
COMMENT ON INDEX contract.uk_signature_plan__revision_slot_no IS '签署槽唯一：同一合同版本内槽序号不得重复。';
COMMENT ON CONSTRAINT uk_signature_plan__revision_authority_slot ON contract.signature_plan IS '授权槽唯一：同一合同版本内静态授权槽不得重复。';
COMMENT ON INDEX contract.uk_signature_plan__revision_authority_slot IS '授权槽唯一：同一合同版本内静态授权槽不得重复。';
COMMENT ON CONSTRAINT uk_signature_plan__id_revision_signer ON contract.signature_plan IS '准确签署计划候选键：供ContractSignature证明Plan、版本和签署Party一致。';
COMMENT ON INDEX contract.uk_signature_plan__id_revision_signer IS '准确签署计划候选键：供ContractSignature证明Plan、版本和签署Party一致。';
COMMENT ON CONSTRAINT ck_signature_plan__slot_no_positive ON contract.signature_plan IS '签署槽序号必须为正数。';
COMMENT ON CONSTRAINT ck_signature_plan__plan_digest_length ON contract.signature_plan IS '摘要格式：plan_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_signature (
    tenant_id uuid NOT NULL,
    contract_signature_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    signature_plan_id uuid NOT NULL,
    signature_no integer NOT NULL,
    evidence_submission_id uuid NOT NULL,
    external_action_id uuid,
    provider_inbox_id uuid,
    signer_party_id uuid NOT NULL,
    signer_identity_digest bytea NOT NULL,
    signer_authority_digest bytea NOT NULL,
    signed_content_digest bytea NOT NULL,
    verification_method_code varchar(64) NOT NULL,
    signed_at timestamptz(6) NOT NULL,
    verified_at timestamptz(6) NOT NULL,
    revoked_at timestamptz(6),
    revoked_by_appointment_id uuid,
    revocation_authorization_digest bytea,
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_signature PRIMARY KEY (tenant_id, contract_signature_id),
    CONSTRAINT uk_contract_signature__plan_no UNIQUE (tenant_id, signature_plan_id, signature_no),
    CONSTRAINT ck_contract_signature__signature_no CHECK (signature_no > 0),
    CONSTRAINT ck_contract_signature__provider_pair CHECK ((external_action_id IS NULL AND provider_inbox_id IS NULL) OR external_action_id IS NOT NULL),
    CONSTRAINT ck_contract_signature__revocation_complete CHECK ((revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL)),
    CONSTRAINT ck_contract_signature__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_contract_signature__signer_identity_digest_length CHECK (octet_length(signer_identity_digest) = 32),
    CONSTRAINT ck_contract_signature__signer_authority_digest_length CHECK (octet_length(signer_authority_digest) = 32),
    CONSTRAINT ck_contract_signature__signed_content_digest_length CHECK (octet_length(signed_content_digest) = 32),
    CONSTRAINT ck_contract_signature__revocation_authorization_digest_length CHECK (octet_length(revocation_authorization_digest) = 32)
);

COMMENT ON TABLE contract.contract_signature IS 'Fact Owner：ContractRuntime；合同签署事实：一行表示一个计划槽上经证据、身份授权、签署内容和可信外部结果核验通过的签署；外部发送或回调本身不构成本事实。';
COMMENT ON CONSTRAINT pk_contract_signature ON contract.contract_signature IS '主键：在租户内唯一标识一条contract_signature记录。';
COMMENT ON INDEX contract.pk_contract_signature IS '主键：在租户内唯一标识一条contract_signature记录。';
COMMENT ON COLUMN contract.contract_signature.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_signature.contract_signature_id IS '合同签署事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_signature.contract_revision_id IS '合同版本标识：签署内容对应的准确版本。';
COMMENT ON COLUMN contract.contract_signature.signature_plan_id IS '签署计划标识：本签署满足的准确计划槽。';
COMMENT ON COLUMN contract.contract_signature.signature_no IS '签署序号：同一计划槽重新签署时递增。';
COMMENT ON COLUMN contract.contract_signature.evidence_submission_id IS '签署证据提交标识：经核验的准确EvidenceSubmission。';
COMMENT ON COLUMN contract.contract_signature.external_action_id IS '外部签署动作标识：使用外部签署服务时准确引用。';
COMMENT ON COLUMN contract.contract_signature.provider_inbox_id IS 'Provider消息标识：可信外部结果来自回调时准确引用。';
COMMENT ON COLUMN contract.contract_signature.signer_party_id IS '实际签署Party标识：必须符合计划槽和版本参与方。';
COMMENT ON COLUMN contract.contract_signature.signer_identity_digest IS '签署身份摘要：冻结核验通过的身份材料与方法。';
COMMENT ON COLUMN contract.contract_signature.signer_authority_digest IS '签署授权摘要：冻结签署时有效的授权路径。';
COMMENT ON COLUMN contract.contract_signature.signed_content_digest IS '签署内容摘要：必须与合同版本内容摘要准确匹配。';
COMMENT ON COLUMN contract.contract_signature.verification_method_code IS '核验方法：静态注册的签署真实性验证方法。';
COMMENT ON COLUMN contract.contract_signature.signed_at IS '签署时间：可信签署结果中的业务发生时间。';
COMMENT ON COLUMN contract.contract_signature.verified_at IS '核验时间：服务端完成全部签署门禁的时间。';
COMMENT ON COLUMN contract.contract_signature.revoked_at IS '撤回时间：仅合同执行前可由授权命令一次写入。';
COMMENT ON COLUMN contract.contract_signature.revoked_by_appointment_id IS '撤回任职标识：执行撤回命令的准确任职。';
COMMENT ON COLUMN contract.contract_signature.revocation_authorization_digest IS '撤回授权摘要：冻结执行前撤回命令提交时的单路径四轴授权快照。';
COMMENT ON COLUMN contract.contract_signature.revocation_reason_code IS '撤回原因：允许列表化原因代码。';
COMMENT ON COLUMN contract.contract_signature.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.contract_signature.created_at IS '创建时间：签署事实经核验后追加的可信时间。';
COMMENT ON COLUMN contract.contract_signature.changed_at IS '变更时间：签署撤回槽最近一次受控写入时间。';
COMMENT ON CONSTRAINT uk_contract_signature__plan_no ON contract.contract_signature IS '签署唯一：同一计划槽内签署序号不得重复。';
COMMENT ON INDEX contract.uk_contract_signature__plan_no IS '签署唯一：同一计划槽内签署序号不得重复。';
COMMENT ON CONSTRAINT ck_contract_signature__signature_no ON contract.contract_signature IS '签署序号必须为正数。';
COMMENT ON CONSTRAINT ck_contract_signature__provider_pair ON contract.contract_signature IS '外部证明：Provider消息存在时必须同时能定位准确外部动作。';
COMMENT ON CONSTRAINT ck_contract_signature__revocation_complete ON contract.contract_signature IS '撤回完整性：撤回时间、主体、授权摘要和原因必须一次性完整写入。';
COMMENT ON CONSTRAINT ck_contract_signature__revision_nonnegative ON contract.contract_signature IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_contract_signature__signer_identity_digest_length ON contract.contract_signature IS '摘要格式：signer_identity_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_signature__signer_authority_digest_length ON contract.contract_signature IS '摘要格式：signer_authority_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_signature__signed_content_digest_length ON contract.contract_signature IS '摘要格式：signed_content_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_signature__revocation_authorization_digest_length ON contract.contract_signature IS '摘要格式：revocation_authorization_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_execution (
    tenant_id uuid NOT NULL,
    contract_execution_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    approval_set_digest bytea NOT NULL,
    signature_set_digest bytea NOT NULL,
    review_scope_hash bytea NOT NULL,
    review_resolution_digest bytea NOT NULL,
    archive_evidence_submission_id uuid NOT NULL,
    execution_digest bytea NOT NULL,
    executed_by_appointment_id uuid NOT NULL,
    executed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_execution PRIMARY KEY (tenant_id, contract_execution_id),
    CONSTRAINT uk_contract_execution__contract UNIQUE (tenant_id, contract_id),
    CONSTRAINT uk_contract_execution__revision UNIQUE (tenant_id, contract_revision_id),
    CONSTRAINT uk_contract_execution__id_contract_revision UNIQUE (tenant_id, contract_execution_id, contract_id, contract_revision_id),
    CONSTRAINT ck_contract_execution__approval_set_digest_length CHECK (octet_length(approval_set_digest) = 32),
    CONSTRAINT ck_contract_execution__signature_set_digest_length CHECK (octet_length(signature_set_digest) = 32),
    CONSTRAINT ck_contract_execution__review_scope_hash_length CHECK (octet_length(review_scope_hash) = 32),
    CONSTRAINT ck_contract_execution__review_resolution_digest_length CHECK (octet_length(review_resolution_digest) = 32),
    CONSTRAINT ck_contract_execution__execution_digest_length CHECK (octet_length(execution_digest) = 32)
);

COMMENT ON TABLE contract.contract_execution IS 'Fact Owner：ContractRuntime；合同执行事实：一行表示准确合同版本的审批、审查、签署、印章和归档条件全部经提交前复验通过，不代表首款到账。';
COMMENT ON CONSTRAINT pk_contract_execution ON contract.contract_execution IS '主键：在租户内唯一标识一条contract_execution记录。';
COMMENT ON INDEX contract.pk_contract_execution IS '主键：在租户内唯一标识一条contract_execution记录。';
COMMENT ON COLUMN contract.contract_execution.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_execution.contract_execution_id IS '合同执行事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_execution.contract_id IS '合同标识：被执行的合同锚点。';
COMMENT ON COLUMN contract.contract_execution.contract_revision_id IS '合同版本标识：实际执行的唯一准确版本。';
COMMENT ON COLUMN contract.contract_execution.approval_set_digest IS '审批集合摘要：覆盖全部静态授权槽的准确DecisionRecord。';
COMMENT ON COLUMN contract.contract_execution.signature_set_digest IS '签署集合摘要：覆盖全部必要且未撤回的签署事实。';
COMMENT ON COLUMN contract.contract_execution.review_scope_hash IS '审查范围摘要：执行时复验的PRE_CONTRACT准确范围。';
COMMENT ON COLUMN contract.contract_execution.review_resolution_digest IS '审查结论摘要：执行时仍可用于放行的准确结论。';
COMMENT ON COLUMN contract.contract_execution.archive_evidence_submission_id IS '归档证据提交标识：执行版本的准确归档文件。';
COMMENT ON COLUMN contract.contract_execution.execution_digest IS '执行摘要：覆盖合同版本及全部执行门禁结果。';
COMMENT ON COLUMN contract.contract_execution.executed_by_appointment_id IS '执行任职标识：实施执行命令的准确任职。';
COMMENT ON COLUMN contract.contract_execution.executed_at IS '执行时间：唯一合同执行事实提交的可信时间。';
COMMENT ON CONSTRAINT uk_contract_execution__contract ON contract.contract_execution IS '合同唯一：一份合同最多形成一个执行事实。';
COMMENT ON INDEX contract.uk_contract_execution__contract IS '合同唯一：一份合同最多形成一个执行事实。';
COMMENT ON CONSTRAINT uk_contract_execution__revision ON contract.contract_execution IS '版本唯一：一个合同版本最多形成一个执行事实。';
COMMENT ON INDEX contract.uk_contract_execution__revision IS '版本唯一：一个合同版本最多形成一个执行事实。';
COMMENT ON CONSTRAINT uk_contract_execution__id_contract_revision ON contract.contract_execution IS '准确执行候选键：供终止及转案证明Execution、Contract和Revision为同一事实链。';
COMMENT ON INDEX contract.uk_contract_execution__id_contract_revision IS '准确执行候选键：供终止及转案证明Execution、Contract和Revision为同一事实链。';
COMMENT ON CONSTRAINT ck_contract_execution__approval_set_digest_length ON contract.contract_execution IS '摘要格式：approval_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_execution__signature_set_digest_length ON contract.contract_execution IS '摘要格式：signature_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_execution__review_scope_hash_length ON contract.contract_execution IS '摘要格式：review_scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_execution__review_resolution_digest_length ON contract.contract_execution IS '摘要格式：review_resolution_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_contract_execution__execution_digest_length ON contract.contract_execution IS '摘要格式：execution_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.payment_confirmation (
    tenant_id uuid NOT NULL,
    payment_confirmation_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    confirmation_no integer NOT NULL,
    confirmation_type varchar(64) NOT NULL,
    amount_minor bigint NOT NULL,
    currency_code varchar(3) NOT NULL,
    provider_account_code varchar(64) NOT NULL,
    provider_transaction_key_hmac bytea NOT NULL,
    external_action_id uuid,
    provider_inbox_id uuid,
    evidence_submission_id uuid,
    reverses_payment_confirmation_id uuid,
    attribution_digest bytea NOT NULL,
    effective_at timestamptz(6) NOT NULL,
    confirmed_at timestamptz(6) NOT NULL,
    recorded_by_appointment_id uuid NOT NULL,
    CONSTRAINT pk_payment_confirmation PRIMARY KEY (tenant_id, payment_confirmation_id),
    CONSTRAINT uk_payment_confirmation__contract_no UNIQUE (tenant_id, contract_id, confirmation_no),
    CONSTRAINT uk_payment_confirmation__provider_key UNIQUE (tenant_id, provider_account_code, provider_transaction_key_hmac, confirmation_type),
    CONSTRAINT ck_payment_confirmation__confirmation_type CHECK (confirmation_type IN ('RECEIPT', 'REVERSAL', 'REFUND')),
    CONSTRAINT ck_payment_confirmation__no_positive CHECK (confirmation_no > 0),
    CONSTRAINT ck_payment_confirmation__amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_confirmation__currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_confirmation__reversal_source CHECK ((confirmation_type = 'RECEIPT' AND reverses_payment_confirmation_id IS NULL) OR (confirmation_type IN ('REVERSAL', 'REFUND') AND reverses_payment_confirmation_id IS NOT NULL)),
    CONSTRAINT ck_payment_confirmation__trusted_source CHECK (provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL),
    CONSTRAINT uk_payment_confirmation__id_contract_revision UNIQUE (tenant_id, payment_confirmation_id, contract_id, contract_revision_id),
    CONSTRAINT ck_payment_confirmation__provider_transaction_key_hmac_length CHECK (octet_length(provider_transaction_key_hmac) = 32),
    CONSTRAINT ck_payment_confirmation__attribution_digest_length CHECK (octet_length(attribution_digest) = 32)
);

COMMENT ON TABLE contract.payment_confirmation IS 'Fact Owner：ContractRuntime；付款确认事实：一行保存可信来源已确认且准确归属合同的一笔到账、撤销或退款，不代表付款门禁已经满足。';
COMMENT ON CONSTRAINT pk_payment_confirmation ON contract.payment_confirmation IS '主键：在租户内唯一标识一条payment_confirmation记录。';
COMMENT ON INDEX contract.pk_payment_confirmation IS '主键：在租户内唯一标识一条payment_confirmation记录。';
COMMENT ON COLUMN contract.payment_confirmation.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.payment_confirmation.payment_confirmation_id IS '付款确认事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.payment_confirmation.contract_id IS '合同标识：付款被准确归属的合同。';
COMMENT ON COLUMN contract.payment_confirmation.contract_revision_id IS '合同版本标识：确认付款时适用的准确合同版本。';
COMMENT ON COLUMN contract.payment_confirmation.confirmation_no IS '确认序号：在合同内按追加顺序递增。';
COMMENT ON COLUMN contract.payment_confirmation.confirmation_type IS '确认类型：RECEIPT、REVERSAL或REFUND。';
COMMENT ON COLUMN contract.payment_confirmation.amount_minor IS '确认金额：以currency_code最小货币单位表示的非负绝对金额。';
COMMENT ON COLUMN contract.payment_confirmation.currency_code IS '币种代码：三位大写ISO 4217代码。';
COMMENT ON COLUMN contract.payment_confirmation.provider_account_code IS '资金来源账号代码：静态配置的支付或银行账号。';
COMMENT ON COLUMN contract.payment_confirmation.provider_transaction_key_hmac IS 'Provider交易键HMAC：用于同账号内安全去重的32字节值，不保存原始交易凭据。';
COMMENT ON COLUMN contract.payment_confirmation.external_action_id IS '外部动作标识：本系统发起资金动作时可准确引用。';
COMMENT ON COLUMN contract.payment_confirmation.provider_inbox_id IS 'Provider消息标识：由可信入站通知形成确认时准确引用。';
COMMENT ON COLUMN contract.payment_confirmation.evidence_submission_id IS '付款证据提交标识：用于确认归属的准确EvidenceSubmission。';
COMMENT ON COLUMN contract.payment_confirmation.reverses_payment_confirmation_id IS '被撤销或退款的原付款确认标识；普通到账为空。';
COMMENT ON COLUMN contract.payment_confirmation.attribution_digest IS '归属摘要：冻结付款与合同、版本及来源的准确匹配依据。';
COMMENT ON COLUMN contract.payment_confirmation.effective_at IS '资金事实发生时间：可信来源确认的到账、撤销或退款时间。';
COMMENT ON COLUMN contract.payment_confirmation.confirmed_at IS '确认时间：Fact Owner完成真实性和合同归属核验的时间。';
COMMENT ON COLUMN contract.payment_confirmation.recorded_by_appointment_id IS '记录任职标识：确认并写入付款事实的准确任职。';
COMMENT ON CONSTRAINT uk_payment_confirmation__contract_no ON contract.payment_confirmation IS '确认唯一：同一合同内确认序号不得重复。';
COMMENT ON INDEX contract.uk_payment_confirmation__contract_no IS '确认唯一：同一合同内确认序号不得重复。';
COMMENT ON CONSTRAINT uk_payment_confirmation__provider_key ON contract.payment_confirmation IS '来源幂等：同Provider账号、交易键和事实类型不得重复确认。';
COMMENT ON INDEX contract.uk_payment_confirmation__provider_key IS '来源幂等：同Provider账号、交易键和事实类型不得重复确认。';
COMMENT ON CONSTRAINT ck_payment_confirmation__confirmation_type ON contract.payment_confirmation IS '确认类型只允许到账、撤销或退款。';
COMMENT ON CONSTRAINT ck_payment_confirmation__no_positive ON contract.payment_confirmation IS '确认序号必须为正数。';
COMMENT ON CONSTRAINT ck_payment_confirmation__amount_positive ON contract.payment_confirmation IS '确认金额使用正绝对值，方向由确认类型表达；零金额不得伪造资金事实。';
COMMENT ON CONSTRAINT ck_payment_confirmation__currency ON contract.payment_confirmation IS '币种必须为三位大写代码。';
COMMENT ON CONSTRAINT ck_payment_confirmation__reversal_source ON contract.payment_confirmation IS '来源完整性：撤销和退款必须准确引用原确认，到账不得引用。';
COMMENT ON CONSTRAINT ck_payment_confirmation__trusted_source ON contract.payment_confirmation IS '可信来源：付款确认必须至少引用验签ProviderInbox或经核验EvidenceSubmission；ExternalAction成功本身不足以证明到账。';
COMMENT ON CONSTRAINT uk_payment_confirmation__id_contract_revision ON contract.payment_confirmation IS '准确付款候选键：供付款集合Resolver证明Confirmation属于准确合同版本。';
COMMENT ON INDEX contract.uk_payment_confirmation__id_contract_revision IS '准确付款候选键：供付款集合Resolver证明Confirmation属于准确合同版本。';
COMMENT ON CONSTRAINT ck_payment_confirmation__provider_transaction_key_hmac_length ON contract.payment_confirmation IS '摘要格式：provider_transaction_key_hmac必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_payment_confirmation__attribution_digest_length ON contract.payment_confirmation IS '摘要格式：attribution_digest必须保存32字节的规范二进制值。';

CREATE TABLE contract.contract_termination (
    tenant_id uuid NOT NULL,
    contract_termination_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    contract_revision_id uuid NOT NULL,
    contract_execution_id uuid,
    termination_kind varchar(64) NOT NULL,
    decision_record_id uuid NOT NULL,
    evidence_submission_id uuid,
    reason_code varchar(64) NOT NULL,
    reason_summary text NOT NULL,
    terminated_at timestamptz(6) NOT NULL,
    terminated_by_appointment_id uuid NOT NULL,
    refund_calculation_minor bigint,
    refund_currency_code varchar(3),
    refund_calculation_digest bytea,
    refund_calculated_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_contract_termination PRIMARY KEY (tenant_id, contract_termination_id),
    CONSTRAINT uk_contract_termination__contract UNIQUE (tenant_id, contract_id),
    CONSTRAINT ck_contract_termination__termination_kind CHECK (termination_kind IN ('CANCELLED', 'TERMINATED')),
    CONSTRAINT ck_contract_termination__execution_shape CHECK ((termination_kind = 'CANCELLED' AND contract_execution_id IS NULL) OR (termination_kind = 'TERMINATED' AND contract_execution_id IS NOT NULL)),
    CONSTRAINT ck_contract_termination__refund_complete CHECK ((refund_calculation_minor IS NULL AND refund_currency_code IS NULL AND refund_calculation_digest IS NULL AND refund_calculated_at IS NULL) OR (refund_calculation_minor IS NOT NULL AND refund_calculation_minor >= 0 AND refund_currency_code ~ '^[A-Z]{3}$' AND refund_calculation_digest IS NOT NULL AND refund_calculated_at IS NOT NULL)),
    CONSTRAINT ck_contract_termination__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_contract_termination__refund_calculation_digest_length CHECK (octet_length(refund_calculation_digest) = 32)
);

COMMENT ON TABLE contract.contract_termination IS 'Fact Owner：ContractRuntime；合同终止事实：一行单向保存合同取消或执行后终止，并可一次补入退款计算；实际退款仍追加PaymentConfirmation。';
COMMENT ON CONSTRAINT pk_contract_termination ON contract.contract_termination IS '主键：在租户内唯一标识一条contract_termination记录。';
COMMENT ON INDEX contract.pk_contract_termination IS '主键：在租户内唯一标识一条contract_termination记录。';
COMMENT ON COLUMN contract.contract_termination.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN contract.contract_termination.contract_termination_id IS '合同终止事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN contract.contract_termination.contract_id IS '合同标识：被取消或终止的准确合同。';
COMMENT ON COLUMN contract.contract_termination.contract_revision_id IS '合同版本标识：取消或终止时适用的准确版本。';
COMMENT ON COLUMN contract.contract_termination.contract_execution_id IS '合同执行事实标识：执行后终止时必须存在，执行前取消时为空。';
COMMENT ON COLUMN contract.contract_termination.termination_kind IS '终止类型：CANCELLED表示执行前取消，TERMINATED表示执行后终止。';
COMMENT ON COLUMN contract.contract_termination.decision_record_id IS '终止决定标识：授权取消或终止的准确DecisionRecord。';
COMMENT ON COLUMN contract.contract_termination.evidence_submission_id IS '终止证据提交标识：存在正式材料时准确引用。';
COMMENT ON COLUMN contract.contract_termination.reason_code IS '终止原因：允许列表化的业务原因代码。';
COMMENT ON COLUMN contract.contract_termination.reason_summary IS '原因摘要：最小必要且允许列表化的说明，不保存完整案情。';
COMMENT ON COLUMN contract.contract_termination.terminated_at IS '终止时间：取消或终止事实生效的可信业务时间。';
COMMENT ON COLUMN contract.contract_termination.terminated_by_appointment_id IS '终止任职标识：执行授权命令的准确任职。';
COMMENT ON COLUMN contract.contract_termination.refund_calculation_minor IS '退款计算金额：最小货币单位，尚未计算时为空，不代表已经退款。';
COMMENT ON COLUMN contract.contract_termination.refund_currency_code IS '退款计算币种：三位大写代码，尚未计算时为空。';
COMMENT ON COLUMN contract.contract_termination.refund_calculation_digest IS '退款计算摘要：覆盖计算输入、规则与结果，尚未计算时为空。';
COMMENT ON COLUMN contract.contract_termination.refund_calculated_at IS '退款计算时间：Fact Owner完成计算后一次写入。';
COMMENT ON COLUMN contract.contract_termination.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN contract.contract_termination.created_at IS '创建时间：取消或终止事实首次写入的可信时间。';
COMMENT ON COLUMN contract.contract_termination.changed_at IS '变更时间：退款计算槽最近一次受控写入的可信时间。';
COMMENT ON CONSTRAINT uk_contract_termination__contract ON contract.contract_termination IS '合同唯一：一份合同最多形成一个取消或终止事实。';
COMMENT ON INDEX contract.uk_contract_termination__contract IS '合同唯一：一份合同最多形成一个取消或终止事实。';
COMMENT ON CONSTRAINT ck_contract_termination__termination_kind ON contract.contract_termination IS '终止类型仅允许执行前取消或执行后终止。';
COMMENT ON CONSTRAINT ck_contract_termination__execution_shape ON contract.contract_termination IS '执行关系：取消发生在执行前，终止必须引用执行事实。';
COMMENT ON CONSTRAINT ck_contract_termination__refund_complete ON contract.contract_termination IS '退款计算完整性：金额、币种、摘要和时间必须一次性全部写入或全部为空。';
COMMENT ON CONSTRAINT ck_contract_termination__revision_nonnegative ON contract.contract_termination IS 'CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_contract_termination__refund_calculation_digest_length ON contract.contract_termination IS '摘要格式：refund_calculation_digest必须保存32字节的规范二进制值。';
