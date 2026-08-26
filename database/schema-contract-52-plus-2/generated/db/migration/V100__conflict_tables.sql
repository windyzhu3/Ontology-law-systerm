-- 冲突审查域：冻结PRE_CONTRACT或PRE_TRANSFER完整范围、规则与语料，保存不可变参与方和Finding；决定统一归Responsibility。

CREATE TABLE conflict.conflict_review (
    tenant_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    review_type_code varchar(64) NOT NULL,
    legal_need_digest bytea NOT NULL,
    review_contract_code varchar(64) NOT NULL,
    review_contract_version integer NOT NULL,
    scope_hash bytea NOT NULL,
    rule_set_code varchar(64) NOT NULL,
    rule_set_revision bigint NOT NULL,
    rule_set_hash bytea NOT NULL,
    corpus_code varchar(64) NOT NULL,
    corpus_revision bigint NOT NULL,
    corpus_hash bytea NOT NULL,
    initial_conclusion_code varchar(64) NOT NULL,
    finding_count integer NOT NULL,
    reviewed_at timestamptz(6) NOT NULL,
    resolution_code varchar(64),
    resolution_digest bytea,
    resolved_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    trigger_fact_type varchar(64) NOT NULL,
    trigger_fact_id uuid NOT NULL,
    trigger_fact_revision bigint,
    trigger_fact_hash bytea,
    CONSTRAINT pk_conflict_review PRIMARY KEY (tenant_id, conflict_review_id),
    CONSTRAINT ck_conflict_review__review_type_code CHECK (review_type_code IN ('PRE_CONTRACT', 'PRE_TRANSFER')),
    CONSTRAINT ck_conflict_review__contract_version CHECK (review_contract_version > 0),
    CONSTRAINT ck_conflict_review__rule_set_revision_nonnegative CHECK (rule_set_revision >= 0),
    CONSTRAINT ck_conflict_review__corpus_revision_nonnegative CHECK (corpus_revision >= 0),
    CONSTRAINT ck_conflict_review__initial_conclusion_code CHECK (initial_conclusion_code IN ('CLEAR', 'NEED_INFO', 'FINDINGS')),
    CONSTRAINT ck_conflict_review__finding_count CHECK (finding_count >= 0 AND ((initial_conclusion_code = 'CLEAR' AND finding_count = 0) OR (initial_conclusion_code = 'NEED_INFO' AND finding_count = 0) OR (initial_conclusion_code = 'FINDINGS' AND finding_count > 0))),
    CONSTRAINT ck_conflict_review__resolution_code CHECK (resolution_code IN ('BLOCKED', 'WAIVED')),
    CONSTRAINT ck_conflict_review__resolution_pair CHECK ((resolution_code IS NULL AND resolution_digest IS NULL AND resolved_at IS NULL) OR (initial_conclusion_code = 'FINDINGS' AND resolution_code IS NOT NULL AND resolution_digest IS NOT NULL AND resolved_at IS NOT NULL)),
    CONSTRAINT ck_conflict_review__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_conflict_review__trigger_fact_exact CHECK ((trigger_fact_type IS NOT NULL AND trigger_fact_id IS NOT NULL AND ((trigger_fact_revision IS NOT NULL AND trigger_fact_revision >= 0 AND trigger_fact_hash IS NULL) OR (trigger_fact_revision IS NULL AND trigger_fact_hash IS NOT NULL)))),
    CONSTRAINT ck_conflict_review__legal_need_digest_length CHECK (octet_length(legal_need_digest) = 32),
    CONSTRAINT ck_conflict_review__scope_hash_length CHECK (octet_length(scope_hash) = 32),
    CONSTRAINT ck_conflict_review__rule_set_hash_length CHECK (octet_length(rule_set_hash) = 32),
    CONSTRAINT ck_conflict_review__corpus_hash_length CHECK (octet_length(corpus_hash) = 32),
    CONSTRAINT ck_conflict_review__resolution_digest_length CHECK (octet_length(resolution_digest) = 32),
    CONSTRAINT ck_conflict_review__trigger_fact_hash_length CHECK (octet_length(trigger_fact_hash) = 32)
);

COMMENT ON TABLE conflict.conflict_review IS 'Fact Owner：ConflictReviewRuntime；ConflictReview事实：一行与Party和Finding集合在同一事务封存一次PRE_CONTRACT或PRE_TRANSFER审查及初始结论；仅Finding集合可按Decision单向收敛为BLOCKED或WAIVED。';
COMMENT ON CONSTRAINT pk_conflict_review ON conflict.conflict_review IS '主键：在租户内唯一标识一条conflict_review记录。';
COMMENT ON INDEX conflict.pk_conflict_review IS '主键：在租户内唯一标识一条conflict_review记录。';
COMMENT ON COLUMN conflict.conflict_review.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN conflict.conflict_review.conflict_review_id IS 'ConflictReview事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN conflict.conflict_review.review_type_code IS '审查类型：仅可取PRE_CONTRACT或PRE_TRANSFER，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_review.legal_need_digest IS '法律需求摘要：冻结本次审查对应的准确法律需求语义，不复制需求正文。';
COMMENT ON COLUMN conflict.conflict_review.review_contract_code IS '审查合同代码：静态注册并解释范围、规则输入和结论结构。';
COMMENT ON COLUMN conflict.conflict_review.review_contract_version IS '审查合同版本：静态注册审查合同的正整数版本。';
COMMENT ON COLUMN conflict.conflict_review.scope_hash IS '完整审查范围摘要：覆盖所有ConflictReviewParty及其冻结角色的规范SHA-256原始32字节摘要。';
COMMENT ON COLUMN conflict.conflict_review.rule_set_code IS '冲突规则集代码：静态注册的规则集身份，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_review.rule_set_revision IS '冲突规则集修订号：冻结本次实际执行的准确规则版本，必须为非负。';
COMMENT ON COLUMN conflict.conflict_review.rule_set_hash IS '冲突规则语义摘要：实际执行规则语料的SHA-256原始32字节摘要。';
COMMENT ON COLUMN conflict.conflict_review.corpus_code IS '比对语料代码：静态注册的审查语料身份，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_review.corpus_revision IS '比对语料修订号：冻结本次使用的准确语料版本，必须为非负。';
COMMENT ON COLUMN conflict.conflict_review.corpus_hash IS '比对语料摘要：本次实际审查语料的SHA-256原始32字节摘要。';
COMMENT ON COLUMN conflict.conflict_review.initial_conclusion_code IS '初始结论：CLEAR、NEED_INFO或FINDINGS，在审查封存事务中不可变写入。';
COMMENT ON COLUMN conflict.conflict_review.finding_count IS 'Finding数量：与同事务写入的ConflictFinding集合准确一致。';
COMMENT ON COLUMN conflict.conflict_review.reviewed_at IS '审查执行时间：使用已冻结范围、规则和语料完成计算的时间，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_review.resolution_code IS 'Finding裁决收敛：BLOCKED或WAIVED；CLEAR和NEED_INFO不使用本槽。';
COMMENT ON COLUMN conflict.conflict_review.resolution_digest IS '裁决集合摘要：覆盖本Review、scopeHash、全部Finding和各authoritySlot Decision；未收敛为空。';
COMMENT ON COLUMN conflict.conflict_review.resolved_at IS '裁决收敛时间：BLOCKED或全部必要槽WAIVE后一次写入。';
COMMENT ON COLUMN conflict.conflict_review.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN conflict.conflict_review.created_at IS '创建时间：审查快照首次持久化的时间，永久冻结。';
COMMENT ON COLUMN conflict.conflict_review.trigger_fact_type IS '触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的静态注册类型。';
COMMENT ON COLUMN conflict.conflict_review.trigger_fact_id IS '触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实在所属租户内的准确标识。';
COMMENT ON COLUMN conflict.conflict_review.trigger_fact_revision IS '触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN conflict.conflict_review.trigger_fact_hash IS '触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_conflict_review__review_type_code ON conflict.conflict_review IS '审查类型域：冲突门禁仅允许合同前或转移前。';
COMMENT ON CONSTRAINT ck_conflict_review__contract_version ON conflict.conflict_review IS '审查合同版本必须为正整数。';
COMMENT ON CONSTRAINT ck_conflict_review__rule_set_revision_nonnegative ON conflict.conflict_review IS '规则修订范围：冻结的冲突规则集修订号不得为负数。';
COMMENT ON CONSTRAINT ck_conflict_review__corpus_revision_nonnegative ON conflict.conflict_review IS '语料修订范围：冻结的比对语料修订号不得为负数。';
COMMENT ON CONSTRAINT ck_conflict_review__initial_conclusion_code ON conflict.conflict_review IS '初始结论仅允许完整零Finding、明确业务信息缺失或存在Finding。';
COMMENT ON CONSTRAINT ck_conflict_review__finding_count ON conflict.conflict_review IS 'Finding集合：CLEAR必须完整且零Finding，NEED_INFO只表示业务信息缺失，FINDINGS必须至少一项。';
COMMENT ON CONSTRAINT ck_conflict_review__resolution_code ON conflict.conflict_review IS '裁决收敛只允许任一阻断或全部必要授权槽豁免。';
COMMENT ON CONSTRAINT ck_conflict_review__resolution_pair ON conflict.conflict_review IS '裁决一次写入：只有FINDINGS可把结果、Decision集合摘要和时间一次性全部写入。';
COMMENT ON CONSTRAINT ck_conflict_review__revision_nonnegative ON conflict.conflict_review IS '修订号范围：ConflictReview受控更新的CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_conflict_review__trigger_fact_exact ON conflict.conflict_review IS '准确引用：触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_conflict_review__legal_need_digest_length ON conflict.conflict_review IS '摘要格式：legal_need_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review__scope_hash_length ON conflict.conflict_review IS '摘要格式：scope_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review__rule_set_hash_length ON conflict.conflict_review IS '摘要格式：rule_set_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review__corpus_hash_length ON conflict.conflict_review IS '摘要格式：corpus_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review__resolution_digest_length ON conflict.conflict_review IS '摘要格式：resolution_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review__trigger_fact_hash_length ON conflict.conflict_review IS '摘要格式：trigger_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE conflict.conflict_review_party (
    tenant_id uuid NOT NULL,
    conflict_review_party_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    party_id uuid NOT NULL,
    scope_role_code varchar(64) NOT NULL,
    party_snapshot_hash bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    source_item_type varchar(64) NOT NULL,
    source_item_id uuid NOT NULL,
    source_item_revision bigint,
    source_item_hash bytea,
    CONSTRAINT pk_conflict_review_party PRIMARY KEY (tenant_id, conflict_review_party_id),
    CONSTRAINT uq_conflict_review_party__party_role UNIQUE (tenant_id, conflict_review_id, party_id, scope_role_code),
    CONSTRAINT ck_conflict_review_party__source_item_exact CHECK ((source_item_type IS NOT NULL AND source_item_id IS NOT NULL AND ((source_item_revision IS NOT NULL AND source_item_revision >= 0 AND source_item_hash IS NULL) OR (source_item_revision IS NULL AND source_item_hash IS NOT NULL)))),
    CONSTRAINT ck_conflict_review_party__party_snapshot_hash_length CHECK (octet_length(party_snapshot_hash) = 32),
    CONSTRAINT ck_conflict_review_party__source_item_hash_length CHECK (octet_length(source_item_hash) = 32)
);

COMMENT ON TABLE conflict.conflict_review_party IS 'Fact Owner：ConflictReviewRuntime；ConflictReviewParty事实：一行代表某Review完整scope内一个Party及其冻结审查角色，由冲突审查域随Review写入且不可变；不代表Party当前全局资料或最终冲突结论。';
COMMENT ON CONSTRAINT pk_conflict_review_party ON conflict.conflict_review_party IS '主键：在租户内唯一标识一条conflict_review_party记录。';
COMMENT ON INDEX conflict.pk_conflict_review_party IS '主键：在租户内唯一标识一条conflict_review_party记录。';
COMMENT ON COLUMN conflict.conflict_review_party.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN conflict.conflict_review_party.conflict_review_party_id IS 'ConflictReviewParty事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN conflict.conflict_review_party.conflict_review_id IS '所属ConflictReview标识：范围参与方必须属于同租户审查。';
COMMENT ON COLUMN conflict.conflict_review_party.party_id IS '范围Party标识：物理关联同租户Party，身份资料有效性在审查提交前复验。';
COMMENT ON COLUMN conflict.conflict_review_party.scope_role_code IS '审查范围角色：委托方、对方、关联方等静态角色，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_review_party.party_snapshot_hash IS 'Party审查快照摘要：冻结本次用于匹配的必要规范字段，保存SHA-256原始32字节摘要。';
COMMENT ON COLUMN conflict.conflict_review_party.created_at IS '创建时间：该Party角色纳入完整审查范围的时间，永久冻结。';
COMMENT ON COLUMN conflict.conflict_review_party.source_item_type IS '本次Review实际纳入该Party和上下文角色的准确来源Fact的静态注册类型。';
COMMENT ON COLUMN conflict.conflict_review_party.source_item_id IS '本次Review实际纳入该Party和上下文角色的准确来源Fact在所属租户内的准确标识。';
COMMENT ON COLUMN conflict.conflict_review_party.source_item_revision IS '本次Review实际纳入该Party和上下文角色的准确来源Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN conflict.conflict_review_party.source_item_hash IS '本次Review实际纳入该Party和上下文角色的准确来源Fact的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT uq_conflict_review_party__party_role ON conflict.conflict_review_party IS '范围角色唯一性：同一Party在同一Review的同一审查角色只出现一次。';
COMMENT ON INDEX conflict.uq_conflict_review_party__party_role IS '范围角色唯一性：同一Party在同一Review的同一审查角色只出现一次。';
COMMENT ON CONSTRAINT ck_conflict_review_party__source_item_exact ON conflict.conflict_review_party IS '准确引用：本次Review实际纳入该Party和上下文角色的准确来源Fact必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_conflict_review_party__party_snapshot_hash_length ON conflict.conflict_review_party IS '摘要格式：party_snapshot_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_review_party__source_item_hash_length ON conflict.conflict_review_party IS '摘要格式：source_item_hash必须保存32字节的规范二进制值。';

CREATE TABLE conflict.conflict_finding (
    tenant_id uuid NOT NULL,
    conflict_finding_id uuid NOT NULL,
    conflict_review_id uuid NOT NULL,
    finding_no bigint NOT NULL,
    conflict_review_party_id uuid NOT NULL,
    rule_code varchar(64) NOT NULL,
    rule_revision bigint NOT NULL,
    risk_classification_code varchar(64) NOT NULL,
    finding_summary text NOT NULL,
    evidence_submission_id uuid,
    finding_digest bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    matched_fact_type varchar(64) NOT NULL,
    matched_fact_id uuid NOT NULL,
    matched_fact_revision bigint,
    matched_fact_hash bytea,
    source_fact_type varchar(64),
    source_fact_id uuid,
    source_fact_revision bigint,
    source_fact_hash bytea,
    CONSTRAINT pk_conflict_finding PRIMARY KEY (tenant_id, conflict_finding_id),
    CONSTRAINT ck_conflict_finding__finding_no_positive CHECK (finding_no > 0),
    CONSTRAINT ck_conflict_finding__rule_revision CHECK (rule_revision >= 0),
    CONSTRAINT uq_conflict_finding__review_no UNIQUE (tenant_id, conflict_review_id, finding_no),
    CONSTRAINT ck_conflict_finding__matched_fact_exact CHECK ((matched_fact_type IS NOT NULL AND matched_fact_id IS NOT NULL AND ((matched_fact_revision IS NOT NULL AND matched_fact_revision >= 0 AND matched_fact_hash IS NULL) OR (matched_fact_revision IS NULL AND matched_fact_hash IS NOT NULL)))),
    CONSTRAINT ck_conflict_finding__source_fact_exact CHECK (((source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL))) OR (source_fact_type IS NULL AND source_fact_id IS NULL AND source_fact_revision IS NULL AND source_fact_hash IS NULL))),
    CONSTRAINT ck_conflict_finding__finding_digest_length CHECK (octet_length(finding_digest) = 32),
    CONSTRAINT ck_conflict_finding__matched_fact_hash_length CHECK (octet_length(matched_fact_hash) = 32),
    CONSTRAINT ck_conflict_finding__source_fact_hash_length CHECK (octet_length(source_fact_hash) = 32)
);

COMMENT ON TABLE conflict.conflict_finding IS 'Fact Owner：ConflictReviewRuntime；ConflictFinding事实：一行代表某Review基于冻结规则与语料产生的一条不可变命中，由冲突审查域只追加；每个Finding及authoritySlot的决定归Responsibility DecisionRecord，不在本域建决定表。';
COMMENT ON CONSTRAINT pk_conflict_finding ON conflict.conflict_finding IS '主键：在租户内唯一标识一条conflict_finding记录。';
COMMENT ON INDEX conflict.pk_conflict_finding IS '主键：在租户内唯一标识一条conflict_finding记录。';
COMMENT ON COLUMN conflict.conflict_finding.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN conflict.conflict_finding.conflict_finding_id IS 'ConflictFinding事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN conflict.conflict_finding.conflict_review_id IS '所属ConflictReview标识：Finding必须归属同租户已冻结审查。';
COMMENT ON COLUMN conflict.conflict_finding.finding_no IS '命中序号：从一开始在ConflictReview内追加，写入后不可变。';
COMMENT ON COLUMN conflict.conflict_finding.conflict_review_party_id IS '命中的范围Party标识：指向同租户ConflictReviewParty；必须属于本Review并由提交前复验。';
COMMENT ON COLUMN conflict.conflict_finding.rule_code IS '命中规则代码：本条Finding实际采用的确定性规则。';
COMMENT ON COLUMN conflict.conflict_finding.rule_revision IS '命中规则修订号：冻结该规则的准确版本，不得为负数。';
COMMENT ON COLUMN conflict.conflict_finding.risk_classification_code IS '风险分类：由冻结规则确定，静态authoritySlot集合由代码注册表按本分类解析。';
COMMENT ON COLUMN conflict.conflict_finding.finding_summary IS '命中摘要：仅保存必要的非敏感说明，不得保存语料正文、Secret或Token。';
COMMENT ON COLUMN conflict.conflict_finding.evidence_submission_id IS 'EvidenceRef：为空表示匹配事实本身足以追溯；非空必须物理关联同租户EvidenceSubmission。';
COMMENT ON COLUMN conflict.conflict_finding.finding_digest IS 'Finding摘要：覆盖Review、范围Party、规则、风险分类、匹配对象和EvidenceRef。';
COMMENT ON COLUMN conflict.conflict_finding.created_at IS '创建时间：Finding首次持久化的时间，永久冻结。';
COMMENT ON COLUMN conflict.conflict_finding.matched_fact_type IS '产生本条ConflictFinding的多态准确匹配事实的静态注册类型。';
COMMENT ON COLUMN conflict.conflict_finding.matched_fact_id IS '产生本条ConflictFinding的多态准确匹配事实在所属租户内的准确标识。';
COMMENT ON COLUMN conflict.conflict_finding.matched_fact_revision IS '产生本条ConflictFinding的多态准确匹配事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN conflict.conflict_finding.matched_fact_hash IS '产生本条ConflictFinding的多态准确匹配事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON COLUMN conflict.conflict_finding.source_fact_type IS '支持本条ConflictFinding的多态准确来源事实的静态注册类型。';
COMMENT ON COLUMN conflict.conflict_finding.source_fact_id IS '支持本条ConflictFinding的多态准确来源事实在所属租户内的准确标识。';
COMMENT ON COLUMN conflict.conflict_finding.source_fact_revision IS '支持本条ConflictFinding的多态准确来源事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN conflict.conflict_finding.source_fact_hash IS '支持本条ConflictFinding的多态准确来源事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_conflict_finding__finding_no_positive ON conflict.conflict_finding IS '命中序号范围：ConflictReview内Finding序号必须为正整数。';
COMMENT ON CONSTRAINT ck_conflict_finding__rule_revision ON conflict.conflict_finding IS '命中规则修订号不得为负数。';
COMMENT ON CONSTRAINT uq_conflict_finding__review_no ON conflict.conflict_finding IS '追加幂等：同一ConflictReview的Finding序号不得重复。';
COMMENT ON INDEX conflict.uq_conflict_finding__review_no IS '追加幂等：同一ConflictReview的Finding序号不得重复。';
COMMENT ON CONSTRAINT ck_conflict_finding__matched_fact_exact ON conflict.conflict_finding IS '准确引用：产生本条ConflictFinding的多态准确匹配事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_conflict_finding__source_fact_exact ON conflict.conflict_finding IS '准确引用：支持本条ConflictFinding的多态准确来源事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_conflict_finding__finding_digest_length ON conflict.conflict_finding IS '摘要格式：finding_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_finding__matched_fact_hash_length ON conflict.conflict_finding IS '摘要格式：matched_fact_hash必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_conflict_finding__source_fact_hash_length ON conflict.conflict_finding IS '摘要格式：source_fact_hash必须保存32字节的规范二进制值。';
