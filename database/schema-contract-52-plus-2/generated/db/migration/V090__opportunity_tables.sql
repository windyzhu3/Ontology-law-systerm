-- 机会与报价域：保存单项法律需求、冻结参与角色、追加进展及不可变报价版本包、逐收件人Issue和Response。

CREATE TABLE opportunity.opportunity (
    tenant_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    source_lead_id uuid NOT NULL,
    source_assignment_id uuid NOT NULL,
    source_contact_result_id uuid NOT NULL,
    owner_appointment_id uuid NOT NULL,
    legal_need_ciphertext bytea NOT NULL,
    legal_need_digest bytea NOT NULL,
    current_quote_revision_id uuid,
    close_outcome_code varchar(64),
    closed_at timestamptz(6),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_opportunity PRIMARY KEY (tenant_id, opportunity_id),
    CONSTRAINT ck_opportunity__closed_pair CHECK ((close_outcome_code IS NULL AND closed_at IS NULL) OR (close_outcome_code IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_opportunity__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_opportunity__source_contact_result UNIQUE (tenant_id, source_contact_result_id),
    CONSTRAINT ck_opportunity__legal_need_digest_length CHECK (octet_length(legal_need_digest) = 32)
);

COMMENT ON TABLE opportunity.opportunity IS 'Fact Owner：OpportunityRuntime；Opportunity锚点：一行代表从一个Lead及其唯一Assignment路径形成的一项准确法律需求和Owner；只保存当前报价指针及一次终结槽，不保存通用Stage或Status。';
COMMENT ON CONSTRAINT pk_opportunity ON opportunity.opportunity IS '主键：在租户内唯一标识一条opportunity记录。';
COMMENT ON INDEX opportunity.pk_opportunity IS '主键：在租户内唯一标识一条opportunity记录。';
COMMENT ON COLUMN opportunity.opportunity.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.opportunity.opportunity_id IS 'Opportunity锚点标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.opportunity.source_lead_id IS '来源Lead标识：法律需求由该不可覆盖接入事实转化。';
COMMENT ON COLUMN opportunity.opportunity.source_assignment_id IS '来源LeadAssignment标识：证明机会沿哪次分派形成，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity.source_contact_result_id IS '来源联系结果标识：必须是同一Lead和Assignment上的准确CONNECTED_VALID事实。';
COMMENT ON COLUMN opportunity.opportunity.owner_appointment_id IS 'Opportunity Owner任命标识：必须与来源Assignment冻结的Owner一致，该一致性由命令提交前复验。';
COMMENT ON COLUMN opportunity.opportunity.legal_need_ciphertext IS '法律需求密文：一项法律需求的受保护原始描述，写入后不可覆盖。';
COMMENT ON COLUMN opportunity.opportunity.legal_need_digest IS '法律需求摘要：规范化法律需求的SHA-256原始32字节摘要，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity.current_quote_revision_id IS '当前QuoteRevision指针：为空表示尚无报价版本；仅为当前导航，历史版本不可覆盖。';
COMMENT ON COLUMN opportunity.opportunity.close_outcome_code IS '终结结果：明确结束该销售机会时一次写入的静态业务结论；未终结为空。';
COMMENT ON COLUMN opportunity.opportunity.closed_at IS '终结时间：形成明确终结事实时一次写入；未终结为空。';
COMMENT ON COLUMN opportunity.opportunity.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.opportunity.created_at IS '创建时间：该法律需求首次形成Opportunity的时间，永久冻结。';
COMMENT ON CONSTRAINT ck_opportunity__closed_pair ON opportunity.opportunity IS '机会终结配对：终结结果和时间必须同时为空或一次写入。';
COMMENT ON CONSTRAINT ck_opportunity__revision_nonnegative ON opportunity.opportunity IS '修订号范围：Opportunity受控更新的CAS修订号不得为负数。';
COMMENT ON CONSTRAINT uq_opportunity__source_contact_result ON opportunity.opportunity IS '资格来源唯一：一条CONNECTED_VALID联系结果至多形成一个Opportunity。';
COMMENT ON INDEX opportunity.uq_opportunity__source_contact_result IS '资格来源唯一：一条CONNECTED_VALID联系结果至多形成一个Opportunity。';
COMMENT ON CONSTRAINT ck_opportunity__legal_need_digest_length ON opportunity.opportunity IS '摘要格式：legal_need_digest必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.opportunity_participation (
    tenant_id uuid NOT NULL,
    opportunity_participation_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    participation_set_revision integer NOT NULL,
    participation_no integer NOT NULL,
    participation_set_size integer NOT NULL,
    participation_set_digest bytea NOT NULL,
    party_id uuid NOT NULL,
    party_revision bigint NOT NULL,
    party_snapshot_digest bytea NOT NULL,
    context_role_code varchar(64) NOT NULL,
    role_context_ciphertext bytea,
    role_context_digest bytea,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_opportunity_participation PRIMARY KEY (tenant_id, opportunity_participation_id),
    CONSTRAINT ck_opportunity_participation__context_pair CHECK (((role_context_ciphertext IS NULL AND role_context_digest IS NULL) OR (role_context_ciphertext IS NOT NULL AND role_context_digest IS NOT NULL))),
    CONSTRAINT ck_opportunity_participation__set_revision CHECK (participation_set_revision > 0),
    CONSTRAINT ck_opportunity_participation__participation_no CHECK (participation_no > 0 AND participation_no <= participation_set_size),
    CONSTRAINT ck_opportunity_participation__set_size CHECK (participation_set_size > 0),
    CONSTRAINT ck_opportunity_participation__party_revision CHECK (party_revision >= 0),
    CONSTRAINT uq_opportunity_participation__set_no UNIQUE (tenant_id, opportunity_id, participation_set_revision, participation_no),
    CONSTRAINT uq_opportunity_participation__set_party_role UNIQUE (tenant_id, opportunity_id, participation_set_revision, party_id, context_role_code),
    CONSTRAINT ck_opportunity_participation__participation_set_digest_length CHECK (octet_length(participation_set_digest) = 32),
    CONSTRAINT ck_opportunity_participation__party_snapshot_digest_length CHECK (octet_length(party_snapshot_digest) = 32),
    CONSTRAINT ck_opportunity_participation__role_context_digest_length CHECK (octet_length(role_context_digest) = 32)
);

COMMENT ON TABLE opportunity.opportunity_participation IS 'Fact Owner：OpportunityRuntime；Opportunity参与方事实：一行代表某次完整参与集合revision中的一个Party上下文角色，由OpportunityRuntime只追加；同一集合revision共享大小和摘要，不代表Party全局身份或合同角色。';
COMMENT ON CONSTRAINT pk_opportunity_participation ON opportunity.opportunity_participation IS '主键：在租户内唯一标识一条opportunity_participation记录。';
COMMENT ON INDEX opportunity.pk_opportunity_participation IS '主键：在租户内唯一标识一条opportunity_participation记录。';
COMMENT ON COLUMN opportunity.opportunity_participation.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.opportunity_participation.opportunity_participation_id IS 'Opportunity参与方事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.opportunity_participation.opportunity_id IS '所属Opportunity标识：参与方上下文必须属于同租户法律需求。';
COMMENT ON COLUMN opportunity.opportunity_participation.participation_set_revision IS '完整参与集合版本：同一Opportunity每次重新冻结全部参与方时递增。';
COMMENT ON COLUMN opportunity.opportunity_participation.participation_no IS '集合内序号：从一开始连续编号，按完整集合稳定排序。';
COMMENT ON COLUMN opportunity.opportunity_participation.participation_set_size IS '完整集合大小：同一Opportunity和集合版本的全部行必须保存相同正整数。';
COMMENT ON COLUMN opportunity.opportunity_participation.participation_set_digest IS '完整集合摘要：覆盖本版本全部参与方、角色、Party快照和上下文的规范摘要。';
COMMENT ON COLUMN opportunity.opportunity_participation.party_id IS '参与Party标识：物理关联同租户Party；Party可演进但本行角色上下文永久冻结。';
COMMENT ON COLUMN opportunity.opportunity_participation.party_revision IS 'Party CAS修订号：形成本集合时用于提交前重验，不声称可从当前态Party回读历史版本。';
COMMENT ON COLUMN opportunity.opportunity_participation.party_snapshot_digest IS '主体业务快照摘要：冻结本法律需求所需的最小规范名称、主标识选择和角色上下文，不复制完整Party。';
COMMENT ON COLUMN opportunity.opportunity_participation.context_role_code IS '上下文角色代码：委托人、付款方、对方等静态业务角色，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity_participation.role_context_ciphertext IS '角色上下文密文：冻结与该法律需求有关的受保护补充上下文，写入后不可覆盖。';
COMMENT ON COLUMN opportunity.opportunity_participation.role_context_digest IS '角色上下文摘要：无补充上下文时为空；否则保存SHA-256原始32字节摘要。';
COMMENT ON COLUMN opportunity.opportunity_participation.created_at IS '创建时间：参与方角色首次纳入Opportunity的时间，永久冻结。';
COMMENT ON CONSTRAINT ck_opportunity_participation__context_pair ON opportunity.opportunity_participation IS '角色上下文配对：受保护上下文密文与其摘要必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_opportunity_participation__set_revision ON opportunity.opportunity_participation IS '完整参与集合版本必须为正数。';
COMMENT ON CONSTRAINT ck_opportunity_participation__participation_no ON opportunity.opportunity_participation IS '集合序号必须为正且不得超过冻结集合大小。';
COMMENT ON CONSTRAINT ck_opportunity_participation__set_size ON opportunity.opportunity_participation IS '完整参与集合大小必须为正数。';
COMMENT ON CONSTRAINT ck_opportunity_participation__party_revision ON opportunity.opportunity_participation IS '冻结的Party修订号不得为负数。';
COMMENT ON CONSTRAINT uq_opportunity_participation__set_no ON opportunity.opportunity_participation IS '集合序号唯一：完整参与集合内序号不得重复。';
COMMENT ON INDEX opportunity.uq_opportunity_participation__set_no IS '集合序号唯一：完整参与集合内序号不得重复。';
COMMENT ON CONSTRAINT uq_opportunity_participation__set_party_role ON opportunity.opportunity_participation IS '集合角色唯一：同一完整集合内同一Party的同一角色不得重复。';
COMMENT ON INDEX opportunity.uq_opportunity_participation__set_party_role IS '集合角色唯一：同一完整集合内同一Party的同一角色不得重复。';
COMMENT ON CONSTRAINT ck_opportunity_participation__participation_set_digest_length ON opportunity.opportunity_participation IS '摘要格式：participation_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_opportunity_participation__party_snapshot_digest_length ON opportunity.opportunity_participation IS '摘要格式：party_snapshot_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_opportunity_participation__role_context_digest_length ON opportunity.opportunity_participation IS '摘要格式：role_context_digest必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.opportunity_progress (
    tenant_id uuid NOT NULL,
    opportunity_progress_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    progress_no bigint NOT NULL,
    progress_type_code varchar(64) NOT NULL,
    progress_contract_code varchar(64) NOT NULL,
    progress_contract_version integer NOT NULL,
    progress_digest bytea NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    source_fact_type varchar(64),
    source_fact_id uuid,
    source_fact_revision bigint,
    source_fact_hash bytea,
    CONSTRAINT pk_opportunity_progress PRIMARY KEY (tenant_id, opportunity_progress_id),
    CONSTRAINT ck_opportunity_progress__progress_no_positive CHECK (progress_no > 0),
    CONSTRAINT ck_opportunity_progress__contract_version CHECK (progress_contract_version > 0),
    CONSTRAINT uq_opportunity_progress__opportunity_no UNIQUE (tenant_id, opportunity_id, progress_no),
    CONSTRAINT ck_opportunity_progress__source_fact_exact CHECK (((source_fact_type IS NOT NULL AND source_fact_id IS NOT NULL AND ((source_fact_revision IS NOT NULL AND source_fact_revision >= 0 AND source_fact_hash IS NULL) OR (source_fact_revision IS NULL AND source_fact_hash IS NOT NULL))) OR (source_fact_type IS NULL AND source_fact_id IS NULL AND source_fact_revision IS NULL AND source_fact_hash IS NULL))),
    CONSTRAINT ck_opportunity_progress__progress_digest_length CHECK (octet_length(progress_digest) = 32),
    CONSTRAINT ck_opportunity_progress__source_fact_hash_length CHECK (octet_length(source_fact_hash) = 32)
);

COMMENT ON TABLE opportunity.opportunity_progress IS 'Fact Owner：OpportunityRuntime；Opportunity进展事实：一行代表一项法律需求的一次已发生进展，Fact Owner为OpportunityRuntime并按序追加；机会Owner只是责任Actor，不可覆盖且不代表可变的当前机会阶段。';
COMMENT ON CONSTRAINT pk_opportunity_progress ON opportunity.opportunity_progress IS '主键：在租户内唯一标识一条opportunity_progress记录。';
COMMENT ON INDEX opportunity.pk_opportunity_progress IS '主键：在租户内唯一标识一条opportunity_progress记录。';
COMMENT ON COLUMN opportunity.opportunity_progress.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.opportunity_progress.opportunity_progress_id IS 'Opportunity进展事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.opportunity_progress.opportunity_id IS '所属Opportunity标识：进展必须属于同租户法律需求。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_no IS '进展序号：从一开始在Opportunity内追加，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_type_code IS '进展类型代码：描述会谈、材料收到、方案确认等已发生事实，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_contract_code IS '进展事实合同代码：静态注册并准确解释该类型进展的来源与语义。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_contract_version IS '进展事实合同版本：静态注册合同的正整数版本。';
COMMENT ON COLUMN opportunity.opportunity_progress.progress_digest IS '进展事实摘要：覆盖类型、合同版本和准确来源Fact，不复制来源正文。';
COMMENT ON COLUMN opportunity.opportunity_progress.occurred_at IS '发生时间：进展实际发生的带时区微秒精度时间，写入后不可变。';
COMMENT ON COLUMN opportunity.opportunity_progress.created_at IS '创建时间：进展事实首次持久化的时间，永久冻结。';
COMMENT ON COLUMN opportunity.opportunity_progress.source_fact_type IS '触发本次OpportunityProgress的多态准确来源事实的静态注册类型。';
COMMENT ON COLUMN opportunity.opportunity_progress.source_fact_id IS '触发本次OpportunityProgress的多态准确来源事实在所属租户内的准确标识。';
COMMENT ON COLUMN opportunity.opportunity_progress.source_fact_revision IS '触发本次OpportunityProgress的多态准确来源事实的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN opportunity.opportunity_progress.source_fact_hash IS '触发本次OpportunityProgress的多态准确来源事实的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_opportunity_progress__progress_no_positive ON opportunity.opportunity_progress IS '进展序号范围：Opportunity内进展序号必须为正整数。';
COMMENT ON CONSTRAINT ck_opportunity_progress__contract_version ON opportunity.opportunity_progress IS '进展事实合同版本必须为正整数。';
COMMENT ON CONSTRAINT uq_opportunity_progress__opportunity_no ON opportunity.opportunity_progress IS '追加幂等：同一Opportunity的进展序号不得重复。';
COMMENT ON INDEX opportunity.uq_opportunity_progress__opportunity_no IS '追加幂等：同一Opportunity的进展序号不得重复。';
COMMENT ON CONSTRAINT ck_opportunity_progress__source_fact_exact ON opportunity.opportunity_progress IS '准确引用：触发本次OpportunityProgress的多态准确来源事实必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_opportunity_progress__progress_digest_length ON opportunity.opportunity_progress IS '摘要格式：progress_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_opportunity_progress__source_fact_hash_length ON opportunity.opportunity_progress IS '摘要格式：source_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.quote_revision (
    tenant_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    opportunity_id uuid NOT NULL,
    quote_revision_no bigint NOT NULL,
    predecessor_quote_revision_id uuid,
    confirmed_action_draft_id uuid NOT NULL,
    participation_set_revision integer NOT NULL,
    participation_set_digest bytea NOT NULL,
    package_contract_code varchar(64) NOT NULL,
    package_contract_version integer NOT NULL,
    currency_code varchar(3) NOT NULL,
    total_minor bigint NOT NULL,
    content_digest bytea NOT NULL,
    valid_until timestamptz(6),
    created_by_appointment_id uuid NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_revision PRIMARY KEY (tenant_id, quote_revision_id),
    CONSTRAINT ck_quote_revision__quote_revision_no_positive CHECK (quote_revision_no > 0),
    CONSTRAINT ck_quote_revision__currency_code CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_quote_revision__total_minor_nonnegative CHECK (total_minor >= 0),
    CONSTRAINT ck_quote_revision__package_version CHECK (package_contract_version > 0),
    CONSTRAINT ck_quote_revision__participation_set_revision CHECK (participation_set_revision > 0),
    CONSTRAINT ck_quote_revision__predecessor_shape CHECK ((quote_revision_no = 1 AND predecessor_quote_revision_id IS NULL) OR (quote_revision_no > 1 AND predecessor_quote_revision_id IS NOT NULL)),
    CONSTRAINT ck_quote_revision__valid_until CHECK (valid_until IS NULL OR valid_until > created_at),
    CONSTRAINT uq_quote_revision__opportunity_no UNIQUE (tenant_id, opportunity_id, quote_revision_no),
    CONSTRAINT uq_quote_revision__predecessor UNIQUE (tenant_id, predecessor_quote_revision_id),
    CONSTRAINT uq_quote_revision__confirmed_draft UNIQUE (tenant_id, confirmed_action_draft_id),
    CONSTRAINT ck_quote_revision__participation_set_digest_length CHECK (octet_length(participation_set_digest) = 32),
    CONSTRAINT ck_quote_revision__content_digest_length CHECK (octet_length(content_digest) = 32)
);

COMMENT ON TABLE opportunity.quote_revision IS 'Fact Owner：OpportunityRuntime；QuoteRevision事实：一行代表某Opportunity的一版不可变报价包头，与Scope、Line及PaymentTerm在同一事务完整写入，由机会域负责；不可覆盖，授权归Responsibility的DecisionRecord，不代表已向任何收件人发出。';
COMMENT ON CONSTRAINT pk_quote_revision ON opportunity.quote_revision IS '主键：在租户内唯一标识一条quote_revision记录。';
COMMENT ON INDEX opportunity.pk_quote_revision IS '主键：在租户内唯一标识一条quote_revision记录。';
COMMENT ON COLUMN opportunity.quote_revision.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_revision.quote_revision_id IS 'QuoteRevision事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_revision.opportunity_id IS '所属Opportunity标识：报价版本必须属于同租户一项法律需求。';
COMMENT ON COLUMN opportunity.quote_revision.quote_revision_no IS '报价版本号：从一开始在Opportunity内递增，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_revision.predecessor_quote_revision_id IS '前序报价版本标识：首版本为空，后续版本准确引用直接前序。';
COMMENT ON COLUMN opportunity.quote_revision.confirmed_action_draft_id IS '确认草案标识：形成该不可变报价版本包的准确候选输入。';
COMMENT ON COLUMN opportunity.quote_revision.participation_set_revision IS '参与集合版本：本QuoteRevision采用的完整OpportunityParticipation集合版本。';
COMMENT ON COLUMN opportunity.quote_revision.participation_set_digest IS '参与集合摘要：必须与该完整集合全部行共享的准确摘要一致。';
COMMENT ON COLUMN opportunity.quote_revision.package_contract_code IS '报价包合同代码：静态注册的Scope、Line和PaymentTerm结构。';
COMMENT ON COLUMN opportunity.quote_revision.package_contract_version IS '报价包合同版本：解释全部版本子项的正整数版本。';
COMMENT ON COLUMN opportunity.quote_revision.currency_code IS '报价币种：ISO 4217三位大写代码，整个版本包内金额必须一致。';
COMMENT ON COLUMN opportunity.quote_revision.total_minor IS '报价总金额：以最小货币单位记录，不得为负，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_revision.content_digest IS '版本包内容摘要：覆盖QuoteRevision及同事务Scope、Line、PaymentTerm的规范SHA-256。';
COMMENT ON COLUMN opportunity.quote_revision.valid_until IS '自然失效时间：报价版本自身的可信截止时间；旧Issue是否替代仍由准确Issue事实决定。';
COMMENT ON COLUMN opportunity.quote_revision.created_by_appointment_id IS '创建任职标识：确认并形成该报价版本包的准确Appointment。';
COMMENT ON COLUMN opportunity.quote_revision.created_at IS '创建时间：不可变报价版本包在同一事务中首次持久化的时间。';
COMMENT ON CONSTRAINT ck_quote_revision__quote_revision_no_positive ON opportunity.quote_revision IS '报价版本号范围：Opportunity内报价版本号必须为正整数。';
COMMENT ON CONSTRAINT ck_quote_revision__currency_code ON opportunity.quote_revision IS '币种格式：报价币种必须为三位大写字母。';
COMMENT ON CONSTRAINT ck_quote_revision__total_minor_nonnegative ON opportunity.quote_revision IS '金额范围：报价总金额最小货币单位不得为负。';
COMMENT ON CONSTRAINT ck_quote_revision__package_version ON opportunity.quote_revision IS '报价包合同版本必须为正数。';
COMMENT ON CONSTRAINT ck_quote_revision__participation_set_revision ON opportunity.quote_revision IS '报价采用的完整参与集合版本必须为正数。';
COMMENT ON CONSTRAINT ck_quote_revision__predecessor_shape ON opportunity.quote_revision IS '报价版本链：首版本无前序，后续版本必须引用直接前序。';
COMMENT ON CONSTRAINT ck_quote_revision__valid_until ON opportunity.quote_revision IS '自然失效时间若存在必须晚于版本创建时间。';
COMMENT ON CONSTRAINT uq_quote_revision__opportunity_no ON opportunity.quote_revision IS '报价版本唯一性：同一Opportunity的版本号不得重复。';
COMMENT ON INDEX opportunity.uq_quote_revision__opportunity_no IS '报价版本唯一性：同一Opportunity的版本号不得重复。';
COMMENT ON CONSTRAINT uq_quote_revision__predecessor ON opportunity.quote_revision IS '单后继链：一个报价版本最多只有一个直接后继。';
COMMENT ON INDEX opportunity.uq_quote_revision__predecessor IS '单后继链：一个报价版本最多只有一个直接后继。';
COMMENT ON CONSTRAINT uq_quote_revision__confirmed_draft ON opportunity.quote_revision IS '草案唯一：一份确认草案只能形成一个报价版本包。';
COMMENT ON INDEX opportunity.uq_quote_revision__confirmed_draft IS '草案唯一：一份确认草案只能形成一个报价版本包。';
COMMENT ON CONSTRAINT ck_quote_revision__participation_set_digest_length ON opportunity.quote_revision IS '摘要格式：participation_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_quote_revision__content_digest_length ON opportunity.quote_revision IS '摘要格式：content_digest必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.quote_service_scope (
    tenant_id uuid NOT NULL,
    quote_service_scope_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    scope_no bigint NOT NULL,
    service_code varchar(64) NOT NULL,
    scope_summary text NOT NULL,
    included boolean NOT NULL,
    scope_hash bytea NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_service_scope PRIMARY KEY (tenant_id, quote_service_scope_id),
    CONSTRAINT ck_quote_service_scope__scope_no_positive CHECK (scope_no > 0),
    CONSTRAINT uq_quote_service_scope__revision_no UNIQUE (tenant_id, quote_revision_id, scope_no),
    CONSTRAINT ck_quote_service_scope__scope_hash_length CHECK (octet_length(scope_hash) = 32)
);

COMMENT ON TABLE opportunity.quote_service_scope IS 'Fact Owner：OpportunityRuntime；QuoteServiceScope事实：一行代表某不可变QuoteRevision包中的一项服务范围，由机会域在版本同一事务写入；写入后不可覆盖，不代表另一个报价版本的范围。';
COMMENT ON CONSTRAINT pk_quote_service_scope ON opportunity.quote_service_scope IS '主键：在租户内唯一标识一条quote_service_scope记录。';
COMMENT ON INDEX opportunity.pk_quote_service_scope IS '主键：在租户内唯一标识一条quote_service_scope记录。';
COMMENT ON COLUMN opportunity.quote_service_scope.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_service_scope.quote_service_scope_id IS 'QuoteServiceScope事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_service_scope.quote_revision_id IS '所属QuoteRevision标识：服务范围必须属于同租户不可变报价版本。';
COMMENT ON COLUMN opportunity.quote_service_scope.scope_no IS '服务范围序号：从一开始在QuoteRevision内排序，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_service_scope.service_code IS '服务代码：静态业务代码，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_service_scope.scope_summary IS '服务范围摘要：仅保存履约边界的必要非敏感摘要，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_service_scope.included IS '是否包含：真表示纳入报价服务，假表示明确排除，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_service_scope.scope_hash IS '范围摘要：本项服务范围规范表示的SHA-256原始32字节摘要。';
COMMENT ON COLUMN opportunity.quote_service_scope.created_at IS '创建时间：随QuoteRevision版本包在同一事务持久化的时间。';
COMMENT ON CONSTRAINT ck_quote_service_scope__scope_no_positive ON opportunity.quote_service_scope IS '范围序号：QuoteRevision内服务范围序号必须为正整数。';
COMMENT ON CONSTRAINT uq_quote_service_scope__revision_no ON opportunity.quote_service_scope IS '版本范围唯一性：同一QuoteRevision的服务范围序号不得重复。';
COMMENT ON INDEX opportunity.uq_quote_service_scope__revision_no IS '版本范围唯一性：同一QuoteRevision的服务范围序号不得重复。';
COMMENT ON CONSTRAINT ck_quote_service_scope__scope_hash_length ON opportunity.quote_service_scope IS '摘要格式：scope_hash必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.quote_line (
    tenant_id uuid NOT NULL,
    quote_line_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    quote_service_scope_id uuid,
    line_no bigint NOT NULL,
    line_type_code varchar(64) NOT NULL,
    line_summary text NOT NULL,
    amount_minor bigint NOT NULL,
    currency_code varchar(3) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_line PRIMARY KEY (tenant_id, quote_line_id),
    CONSTRAINT ck_quote_line__line_no_positive CHECK (line_no > 0),
    CONSTRAINT ck_quote_line__currency_code CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_quote_line__revision_no UNIQUE (tenant_id, quote_revision_id, line_no)
);

COMMENT ON TABLE opportunity.quote_line IS 'Fact Owner：OpportunityRuntime；QuoteLine事实：一行代表某不可变QuoteRevision包中的一条计价行，由机会域在版本同一事务写入；写入后不可覆盖，不代表收款或付款确认。';
COMMENT ON CONSTRAINT pk_quote_line ON opportunity.quote_line IS '主键：在租户内唯一标识一条quote_line记录。';
COMMENT ON INDEX opportunity.pk_quote_line IS '主键：在租户内唯一标识一条quote_line记录。';
COMMENT ON COLUMN opportunity.quote_line.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_line.quote_line_id IS 'QuoteLine事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_line.quote_revision_id IS '所属QuoteRevision标识：计价行必须属于同租户不可变报价版本。';
COMMENT ON COLUMN opportunity.quote_line.quote_service_scope_id IS '关联QuoteServiceScope标识：为空表示包级计价；非空必须属于同一QuoteRevision，后者由提交前复验。';
COMMENT ON COLUMN opportunity.quote_line.line_no IS '计价行序号：从一开始在QuoteRevision内排序，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_line.line_type_code IS '计价行类型代码：固定费、阶段费、折扣等静态业务代码，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_line.line_summary IS '计价行摘要：仅保存必要的非敏感计价说明，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_line.amount_minor IS '计价行金额：以最小货币单位记录，可用负值表达明确折扣。';
COMMENT ON COLUMN opportunity.quote_line.currency_code IS '计价行币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。';
COMMENT ON COLUMN opportunity.quote_line.created_at IS '创建时间：随QuoteRevision版本包在同一事务持久化的时间。';
COMMENT ON CONSTRAINT ck_quote_line__line_no_positive ON opportunity.quote_line IS '计价行序号：QuoteRevision内计价行序号必须为正整数。';
COMMENT ON CONSTRAINT ck_quote_line__currency_code ON opportunity.quote_line IS '币种格式：计价行币种必须为三位大写字母。';
COMMENT ON CONSTRAINT uq_quote_line__revision_no ON opportunity.quote_line IS '版本计价行唯一性：同一QuoteRevision的计价行序号不得重复。';
COMMENT ON INDEX opportunity.uq_quote_line__revision_no IS '版本计价行唯一性：同一QuoteRevision的计价行序号不得重复。';

CREATE TABLE opportunity.quote_payment_term (
    tenant_id uuid NOT NULL,
    quote_payment_term_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    term_no bigint NOT NULL,
    due_basis_code varchar(64) NOT NULL,
    due_offset_days integer NOT NULL,
    amount_minor bigint NOT NULL,
    currency_code varchar(3) NOT NULL,
    term_summary text,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_payment_term PRIMARY KEY (tenant_id, quote_payment_term_id),
    CONSTRAINT ck_quote_payment_term__term_no_positive CHECK (term_no > 0),
    CONSTRAINT ck_quote_payment_term__due_offset_nonnegative CHECK (due_offset_days >= 0),
    CONSTRAINT ck_quote_payment_term__amount_nonnegative CHECK (amount_minor >= 0),
    CONSTRAINT ck_quote_payment_term__currency_code CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_quote_payment_term__revision_no UNIQUE (tenant_id, quote_revision_id, term_no)
);

COMMENT ON TABLE opportunity.quote_payment_term IS 'Fact Owner：OpportunityRuntime；QuotePaymentTerm事实：一行代表某不可变QuoteRevision包中的一项付款条件，由机会域在版本同一事务写入；写入后不可覆盖，不代表已收款或支付门禁。';
COMMENT ON CONSTRAINT pk_quote_payment_term ON opportunity.quote_payment_term IS '主键：在租户内唯一标识一条quote_payment_term记录。';
COMMENT ON INDEX opportunity.pk_quote_payment_term IS '主键：在租户内唯一标识一条quote_payment_term记录。';
COMMENT ON COLUMN opportunity.quote_payment_term.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_payment_term.quote_payment_term_id IS 'QuotePaymentTerm事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_payment_term.quote_revision_id IS '所属QuoteRevision标识：付款条件必须属于同租户不可变报价版本。';
COMMENT ON COLUMN opportunity.quote_payment_term.term_no IS '付款条件序号：从一开始在QuoteRevision内排序，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_payment_term.due_basis_code IS '到期基准代码：签署、开票、里程碑等静态业务代码，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_payment_term.due_offset_days IS '到期偏移天数：相对到期基准的自然日偏移，可为零但不得为负。';
COMMENT ON COLUMN opportunity.quote_payment_term.amount_minor IS '应付金额：以最小货币单位记录，不得为负，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_payment_term.currency_code IS '付款条件币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。';
COMMENT ON COLUMN opportunity.quote_payment_term.term_summary IS '付款条件摘要：仅保存必要的非敏感条件说明，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_payment_term.created_at IS '创建时间：随QuoteRevision版本包在同一事务持久化的时间。';
COMMENT ON CONSTRAINT ck_quote_payment_term__term_no_positive ON opportunity.quote_payment_term IS '付款条件序号：QuoteRevision内付款条件序号必须为正整数。';
COMMENT ON CONSTRAINT ck_quote_payment_term__due_offset_nonnegative ON opportunity.quote_payment_term IS '到期偏移范围：到期偏移天数不得为负。';
COMMENT ON CONSTRAINT ck_quote_payment_term__amount_nonnegative ON opportunity.quote_payment_term IS '金额范围：应付金额最小货币单位不得为负。';
COMMENT ON CONSTRAINT ck_quote_payment_term__currency_code ON opportunity.quote_payment_term IS '币种格式：付款条件币种必须为三位大写字母。';
COMMENT ON CONSTRAINT uq_quote_payment_term__revision_no ON opportunity.quote_payment_term IS '版本付款条件唯一性：同一QuoteRevision的付款条件序号不得重复。';
COMMENT ON INDEX opportunity.uq_quote_payment_term__revision_no IS '版本付款条件唯一性：同一QuoteRevision的付款条件序号不得重复。';

CREATE TABLE opportunity.quote_issue (
    tenant_id uuid NOT NULL,
    quote_issue_id uuid NOT NULL,
    quote_revision_id uuid NOT NULL,
    recipient_participation_id uuid NOT NULL,
    recipient_context_digest bytea NOT NULL,
    authorization_set_digest bytea NOT NULL,
    delivery_channel_code varchar(64) NOT NULL,
    external_action_id uuid,
    provider_inbox_id uuid,
    issued_at timestamptz(6) NOT NULL,
    replaces_quote_issue_id uuid,
    issue_status_code varchar(64) NOT NULL,
    revoked_at timestamptz(6),
    revocation_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    delivery_fact_type varchar(64) NOT NULL,
    delivery_fact_id uuid NOT NULL,
    delivery_fact_revision bigint,
    delivery_fact_hash bytea,
    CONSTRAINT pk_quote_issue PRIMARY KEY (tenant_id, quote_issue_id),
    CONSTRAINT ck_quote_issue__issue_status_code CHECK (issue_status_code IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_quote_issue__terminal_payload CHECK (((issue_status_code = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL) OR (issue_status_code = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL))),
    CONSTRAINT ck_quote_issue__not_self_replacement CHECK (replaces_quote_issue_id IS NULL OR replaces_quote_issue_id <> quote_issue_id),
    CONSTRAINT ck_quote_issue__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_quote_issue__replaces UNIQUE (tenant_id, replaces_quote_issue_id),
    CONSTRAINT ck_quote_issue__delivery_fact_exact CHECK ((delivery_fact_type IS NOT NULL AND delivery_fact_id IS NOT NULL AND ((delivery_fact_revision IS NOT NULL AND delivery_fact_revision >= 0 AND delivery_fact_hash IS NULL) OR (delivery_fact_revision IS NULL AND delivery_fact_hash IS NOT NULL)))),
    CONSTRAINT ck_quote_issue__recipient_context_digest_length CHECK (octet_length(recipient_context_digest) = 32),
    CONSTRAINT ck_quote_issue__authorization_set_digest_length CHECK (octet_length(authorization_set_digest) = 32),
    CONSTRAINT ck_quote_issue__delivery_fact_hash_length CHECK (octet_length(delivery_fact_hash) = 32)
);

COMMENT ON TABLE opportunity.quote_issue IS 'Fact Owner：OpportunityRuntime；QuoteIssue事实：一行代表某不可变QuoteRevision向一个冻结收件人发出的一次报价，由机会域负责；新Issue可准确引用其替代的旧Issue，旧Issue不被自动改写，只允许授权单向撤回。';
COMMENT ON CONSTRAINT pk_quote_issue ON opportunity.quote_issue IS '主键：在租户内唯一标识一条quote_issue记录。';
COMMENT ON INDEX opportunity.pk_quote_issue IS '主键：在租户内唯一标识一条quote_issue记录。';
COMMENT ON COLUMN opportunity.quote_issue.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_issue.quote_issue_id IS 'QuoteIssue事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_issue.quote_revision_id IS '发出的QuoteRevision标识：写入后不可变，保证内容来自完整不可变版本包。';
COMMENT ON COLUMN opportunity.quote_issue.recipient_participation_id IS '收件人Participation标识：冻结收件人在Opportunity中的上下文角色，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_issue.recipient_context_digest IS '收件人上下文摘要：冻结准确Participation版本、Party和送达地址选择。';
COMMENT ON COLUMN opportunity.quote_issue.authorization_set_digest IS '授权集合摘要：覆盖绑定该QuoteRevision及contentDigest的全部必要DecisionRecord。';
COMMENT ON COLUMN opportunity.quote_issue.delivery_channel_code IS '送达渠道代码：邮件、门户等静态代码，写入后不可变且不含凭据。';
COMMENT ON COLUMN opportunity.quote_issue.external_action_id IS '外部送达ExternalAction标识：为空表示无需外部动作；非空时物理关联同租户外部动作。';
COMMENT ON COLUMN opportunity.quote_issue.provider_inbox_id IS 'Provider消息标识：权威送达证明来自验签消息时准确引用。';
COMMENT ON COLUMN opportunity.quote_issue.issued_at IS '发出时间：逐收件人报价实际发出的带时区微秒精度时间，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_issue.replaces_quote_issue_id IS '被替代的旧QuoteIssue标识：由新Issue在创建时准确引用；为空表示不替代其他Issue。';
COMMENT ON COLUMN opportunity.quote_issue.issue_status_code IS '发出状态：ACTIVE或REVOKED；创建新Issue不自动改变旧Issue。';
COMMENT ON COLUMN opportunity.quote_issue.revoked_at IS '撤回时间：仅在REVOKED时一次写入。';
COMMENT ON COLUMN opportunity.quote_issue.revocation_reason_code IS '撤回原因代码：仅在REVOKED时一次写入，不得包含自由文本案情。';
COMMENT ON COLUMN opportunity.quote_issue.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN opportunity.quote_issue.created_at IS '创建时间：逐收件人QuoteIssue首次持久化的时间，永久冻结。';
COMMENT ON COLUMN opportunity.quote_issue.delivery_fact_type IS '逐收件人报价已权威发送的准确证明Fact的静态注册类型。';
COMMENT ON COLUMN opportunity.quote_issue.delivery_fact_id IS '逐收件人报价已权威发送的准确证明Fact在所属租户内的准确标识。';
COMMENT ON COLUMN opportunity.quote_issue.delivery_fact_revision IS '逐收件人报价已权威发送的准确证明Fact的准确修订号；按哈希冻结时为空。';
COMMENT ON COLUMN opportunity.quote_issue.delivery_fact_hash IS '逐收件人报价已权威发送的准确证明Fact的准确规范摘要；按修订冻结时为空。';
COMMENT ON CONSTRAINT ck_quote_issue__issue_status_code ON opportunity.quote_issue IS '发出状态域：仅允许有效或已撤回；替代通过新Issue不可变引用旧Issue表达。';
COMMENT ON CONSTRAINT ck_quote_issue__terminal_payload ON opportunity.quote_issue IS '单向终态载荷：ACTIVE无撤回字段，REVOKED必须一次写入撤回时间和原因。';
COMMENT ON CONSTRAINT ck_quote_issue__not_self_replacement ON opportunity.quote_issue IS '替代关系防自环：新QuoteIssue不得声明替代自身。';
COMMENT ON CONSTRAINT ck_quote_issue__revision_nonnegative ON opportunity.quote_issue IS '修订号范围：QuoteIssue受控更新的CAS修订号不得为负数。';
COMMENT ON CONSTRAINT uq_quote_issue__replaces ON opportunity.quote_issue IS '单后继链：一个旧QuoteIssue最多被一个新Issue直接替代；收件人及版本顺序由提交前重验。';
COMMENT ON INDEX opportunity.uq_quote_issue__replaces IS '单后继链：一个旧QuoteIssue最多被一个新Issue直接替代；收件人及版本顺序由提交前重验。';
COMMENT ON CONSTRAINT ck_quote_issue__delivery_fact_exact ON opportunity.quote_issue IS '准确引用：逐收件人报价已权威发送的准确证明Fact必须完整给出类型、标识以及修订号或摘要二者之一。';
COMMENT ON CONSTRAINT ck_quote_issue__recipient_context_digest_length ON opportunity.quote_issue IS '摘要格式：recipient_context_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_quote_issue__authorization_set_digest_length ON opportunity.quote_issue IS '摘要格式：authorization_set_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_quote_issue__delivery_fact_hash_length ON opportunity.quote_issue IS '摘要格式：delivery_fact_hash必须保存32字节的规范二进制值。';

CREATE TABLE opportunity.quote_response (
    tenant_id uuid NOT NULL,
    quote_response_id uuid NOT NULL,
    quote_issue_id uuid NOT NULL,
    response_no bigint NOT NULL,
    response_code varchar(64) NOT NULL,
    response_content_ciphertext bytea,
    response_content_digest bytea,
    provider_inbox_id uuid,
    evidence_submission_id uuid,
    recorded_by_appointment_id uuid,
    received_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_quote_response PRIMARY KEY (tenant_id, quote_response_id),
    CONSTRAINT ck_quote_response__response_no_positive CHECK (response_no > 0),
    CONSTRAINT ck_quote_response__response_code CHECK (response_code IN ('ACCEPTED', 'NOT_ACCEPTED', 'REJECTED', 'AMBIGUOUS')),
    CONSTRAINT ck_quote_response__content_pair CHECK (((response_content_ciphertext IS NULL AND response_content_digest IS NULL) OR (response_content_ciphertext IS NOT NULL AND response_content_digest IS NOT NULL))),
    CONSTRAINT ck_quote_response__proof_present CHECK (provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL OR recorded_by_appointment_id IS NOT NULL),
    CONSTRAINT uq_quote_response__issue_no UNIQUE (tenant_id, quote_issue_id, response_no),
    CONSTRAINT ck_quote_response__response_content_digest_length CHECK (octet_length(response_content_digest) = 32)
);

COMMENT ON TABLE opportunity.quote_response IS 'Fact Owner：OpportunityRuntime；QuoteResponse事实：一行代表收件人对准确QuoteIssue版本的一次已收到响应，由机会域按序追加；写入后不可覆盖，不代表合同已成立或报价Issue可被改写。';
COMMENT ON CONSTRAINT pk_quote_response ON opportunity.quote_response IS '主键：在租户内唯一标识一条quote_response记录。';
COMMENT ON INDEX opportunity.pk_quote_response IS '主键：在租户内唯一标识一条quote_response记录。';
COMMENT ON COLUMN opportunity.quote_response.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN opportunity.quote_response.quote_response_id IS 'QuoteResponse事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN opportunity.quote_response.quote_issue_id IS '报价发出标识：响应只能物理引用同租户准确不可变QuoteIssue。';
COMMENT ON COLUMN opportunity.quote_response.response_no IS '响应序号：从一开始在准确Issue标识内追加，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_response.response_code IS '响应代码：ACCEPTED、NOT_ACCEPTED、REJECTED或AMBIGUOUS，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_response.response_content_ciphertext IS '响应内容密文：保存受保护原始响应，写入后不可覆盖。';
COMMENT ON COLUMN opportunity.quote_response.response_content_digest IS '响应内容摘要：无原始内容时为空；否则保存SHA-256原始32字节摘要。';
COMMENT ON COLUMN opportunity.quote_response.provider_inbox_id IS 'Provider消息标识：响应由可信外部回调形成时准确引用。';
COMMENT ON COLUMN opportunity.quote_response.evidence_submission_id IS 'EvidenceRef：响应由受控文件证明时准确引用。';
COMMENT ON COLUMN opportunity.quote_response.recorded_by_appointment_id IS '记录任职标识：由内部人员确认响应时记录；纯Provider事实可为空。';
COMMENT ON COLUMN opportunity.quote_response.received_at IS '收到时间：响应实际接收的带时区微秒精度时间，写入后不可变。';
COMMENT ON COLUMN opportunity.quote_response.created_at IS '创建时间：响应事实首次持久化的时间，永久冻结。';
COMMENT ON CONSTRAINT ck_quote_response__response_no_positive ON opportunity.quote_response IS '响应序号范围：准确Issue标识内响应序号必须为正整数。';
COMMENT ON CONSTRAINT ck_quote_response__response_code ON opportunity.quote_response IS '响应结论只允许接受、暂不接受、明确拒绝或不明确回应。';
COMMENT ON CONSTRAINT ck_quote_response__content_pair ON opportunity.quote_response IS '响应内容配对：受保护内容密文与其摘要必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_quote_response__proof_present ON opportunity.quote_response IS '响应证明：每条响应必须至少具有可信Provider消息、EvidenceRef或内部确认任职之一。';
COMMENT ON CONSTRAINT uq_quote_response__issue_no ON opportunity.quote_response IS '追加幂等：同一QuoteIssue标识下的响应序号不得重复。';
COMMENT ON INDEX opportunity.uq_quote_response__issue_no IS '追加幂等：同一QuoteIssue标识下的响应序号不得重复。';
COMMENT ON CONSTRAINT ck_quote_response__response_content_digest_length ON opportunity.quote_response IS '摘要格式：response_content_digest必须保存32字节的规范二进制值。';
