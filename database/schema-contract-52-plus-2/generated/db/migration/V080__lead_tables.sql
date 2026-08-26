-- 销售接入域：保存不可覆盖Lead、追加分派链与追加联系结果，不承载机会、报价或冲突决定。

CREATE TABLE lead.lead (
    tenant_id uuid NOT NULL,
    lead_id uuid NOT NULL,
    source_channel_code varchar(64) NOT NULL,
    source_account_code varchar(128) NOT NULL,
    source_record_key_digest bytea NOT NULL,
    captured_at timestamptz(6) NOT NULL,
    captured_name_ciphertext bytea,
    captured_phone_ciphertext bytea,
    captured_phone_hmac bytea,
    captured_email_ciphertext bytea,
    captured_email_hmac bytea,
    city_code varchar(64),
    service_category_code varchar(64) NOT NULL,
    jurisdiction_code varchar(64) NOT NULL,
    urgency_code varchar(64) NOT NULL,
    legal_need_summary_ciphertext bytea NOT NULL,
    captured_content_digest bytea NOT NULL,
    parsed_party_id uuid,
    party_resolution_code varchar(64) NOT NULL,
    disposition_code varchar(64) NOT NULL,
    current_assignment_id uuid,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_lead PRIMARY KEY (tenant_id, lead_id),
    CONSTRAINT ck_lead__party_resolution_code CHECK (party_resolution_code IN ('UNRESOLVED', 'RESOLVED', 'AMBIGUOUS')),
    CONSTRAINT ck_lead__party_resolution_pair CHECK (((party_resolution_code = 'RESOLVED' AND parsed_party_id IS NOT NULL) OR (party_resolution_code <> 'RESOLVED' AND parsed_party_id IS NULL))),
    CONSTRAINT ck_lead__phone_pair CHECK ((captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL) OR (captured_phone_ciphertext IS NOT NULL AND captured_phone_hmac IS NOT NULL)),
    CONSTRAINT ck_lead__email_pair CHECK ((captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL) OR (captured_email_ciphertext IS NOT NULL AND captured_email_hmac IS NOT NULL)),
    CONSTRAINT ck_lead__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_lead__source_record_key_digest_length CHECK (octet_length(source_record_key_digest) = 32),
    CONSTRAINT ck_lead__captured_phone_hmac_length CHECK (octet_length(captured_phone_hmac) = 32),
    CONSTRAINT ck_lead__captured_email_hmac_length CHECK (octet_length(captured_email_hmac) = 32),
    CONSTRAINT ck_lead__captured_content_digest_length CHECK (octet_length(captured_content_digest) = 32)
);

COMMENT ON TABLE lead.lead IS 'Fact Owner：LeadRuntime；Lead接入事实：一行代表渠道一次不可覆盖的原始接入，由销售接入域负责；仅允许更新Party解析、当前处置、当前Assignment和CAS修订号，不代表已形成法律需求或客户关系。';
COMMENT ON CONSTRAINT pk_lead ON lead.lead IS '主键：在租户内唯一标识一条lead记录。';
COMMENT ON INDEX lead.pk_lead IS '主键：在租户内唯一标识一条lead记录。';
COMMENT ON COLUMN lead.lead.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN lead.lead.lead_id IS 'Lead接入事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN lead.lead.source_channel_code IS '来源渠道代码：由接入适配器写入并永久冻结，不含凭据。';
COMMENT ON COLUMN lead.lead.source_account_code IS '渠道账号代码：标识静态配置的接入账号，不保存账号凭据。';
COMMENT ON COLUMN lead.lead.source_record_key_digest IS '来源记录键摘要：渠道账号内稳定记录键的32字节HMAC或规范摘要，用于来源幂等。';
COMMENT ON COLUMN lead.lead.captured_at IS '渠道捕获时间：使用带时区微秒精度时间，由可信接入适配器写入并永久冻结。';
COMMENT ON COLUMN lead.lead.captured_name_ciphertext IS '捕获姓名密文：渠道提供的姓名受保护值；缺失时为空，写入后不可覆盖。';
COMMENT ON COLUMN lead.lead.captured_phone_ciphertext IS '捕获电话密文：渠道提供的电话受保护值；缺失时为空。';
COMMENT ON COLUMN lead.lead.captured_phone_hmac IS '捕获电话HMAC：用于受控精确匹配；电话缺失时为空。';
COMMENT ON COLUMN lead.lead.captured_email_ciphertext IS '捕获邮箱密文：渠道提供的邮箱受保护值；缺失时为空。';
COMMENT ON COLUMN lead.lead.captured_email_hmac IS '捕获邮箱HMAC：用于受控精确匹配；邮箱缺失时为空。';
COMMENT ON COLUMN lead.lead.city_code IS '捕获城市代码：规范化地域代码；渠道未提供时为空。';
COMMENT ON COLUMN lead.lead.service_category_code IS '服务类别代码：静态注册的拟咨询法律服务类别。';
COMMENT ON COLUMN lead.lead.jurisdiction_code IS '法域代码：静态注册的主要适用法域；尚不明确时使用明确UNKNOWN代码。';
COMMENT ON COLUMN lead.lead.urgency_code IS '紧急度代码：静态注册的销售接入紧急程度。';
COMMENT ON COLUMN lead.lead.legal_need_summary_ciphertext IS '法律需求摘要密文：最小必要的受保护需求摘要，不保存完整咨询正文。';
COMMENT ON COLUMN lead.lead.captured_content_digest IS '接入内容摘要：覆盖上述规范化结构化捕获字段，用于业务疑似重复提示而非来源幂等。';
COMMENT ON COLUMN lead.lead.parsed_party_id IS 'Party解析结果：为空表示尚未或无法唯一解析；可随解析结论受控更新，关系由复合外键证明。';
COMMENT ON COLUMN lead.lead.party_resolution_code IS 'Party解析状态：仅可取UNRESOLVED、RESOLVED或AMBIGUOUS，可受控更新。';
COMMENT ON COLUMN lead.lead.disposition_code IS '当前处置代码：销售接入域的当前处置结论，可受控更新但不得改写原捕获事实。';
COMMENT ON COLUMN lead.lead.current_assignment_id IS '当前Assignment标识：为空表示尚未分派；只作为当前指针受控更新，历史由LeadAssignment链保留。';
COMMENT ON COLUMN lead.lead.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN lead.lead.created_at IS '创建时间：该Lead首次持久化的带时区微秒精度时间，永久冻结。';
COMMENT ON CONSTRAINT ck_lead__party_resolution_code ON lead.lead IS 'Party解析状态域：限制为未解析、已唯一解析或存在歧义三种机器状态。';
COMMENT ON CONSTRAINT ck_lead__party_resolution_pair ON lead.lead IS 'Party解析配对：只有RESOLVED状态必须且仅能携带一个Party标识。';
COMMENT ON CONSTRAINT ck_lead__phone_pair ON lead.lead IS '电话保护字段：电话密文和HMAC必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_lead__email_pair ON lead.lead IS '邮箱保护字段：邮箱密文和HMAC必须同时存在或同时为空。';
COMMENT ON CONSTRAINT ck_lead__revision_nonnegative ON lead.lead IS '修订号范围：Lead受控更新的CAS修订号不得为负数。';
COMMENT ON CONSTRAINT ck_lead__source_record_key_digest_length ON lead.lead IS '摘要格式：source_record_key_digest必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_lead__captured_phone_hmac_length ON lead.lead IS '摘要格式：captured_phone_hmac必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_lead__captured_email_hmac_length ON lead.lead IS '摘要格式：captured_email_hmac必须保存32字节的规范二进制值。';
COMMENT ON CONSTRAINT ck_lead__captured_content_digest_length ON lead.lead IS '摘要格式：captured_content_digest必须保存32字节的规范二进制值。';

CREATE TABLE lead.lead_assignment (
    tenant_id uuid NOT NULL,
    lead_assignment_id uuid NOT NULL,
    lead_id uuid NOT NULL,
    assignment_no bigint NOT NULL,
    previous_assignment_id uuid,
    owner_appointment_id uuid NOT NULL,
    assignment_reason_code varchar(64) NOT NULL,
    assigned_at timestamptz(6) NOT NULL,
    assignment_status_code varchar(64) NOT NULL,
    closed_at timestamptz(6),
    close_reason_code varchar(64),
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_lead_assignment PRIMARY KEY (tenant_id, lead_assignment_id),
    CONSTRAINT ck_lead_assignment__assignment_no_positive CHECK (assignment_no > 0),
    CONSTRAINT ck_lead_assignment__assignment_status_code CHECK (assignment_status_code IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_lead_assignment__close_pair CHECK (((assignment_status_code = 'OPEN' AND closed_at IS NULL AND close_reason_code IS NULL) OR (assignment_status_code = 'CLOSED' AND closed_at IS NOT NULL AND close_reason_code IS NOT NULL))),
    CONSTRAINT ck_lead_assignment__not_self_previous CHECK (previous_assignment_id IS NULL OR previous_assignment_id <> lead_assignment_id),
    CONSTRAINT ck_lead_assignment__chain_shape CHECK ((assignment_no = 1 AND previous_assignment_id IS NULL) OR (assignment_no > 1 AND previous_assignment_id IS NOT NULL)),
    CONSTRAINT ck_lead_assignment__revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT uq_lead_assignment__lead_no UNIQUE (tenant_id, lead_id, assignment_no),
    CONSTRAINT uq_lead_assignment__id_lead_owner UNIQUE (tenant_id, lead_assignment_id, lead_id, owner_appointment_id)
);

COMMENT ON TABLE lead.lead_assignment IS 'Fact Owner：LeadRuntime；Lead分派事实：一行代表Lead分派链中一次追加分派，由销售接入域负责；分派核心永久冻结，仅允许一次性关闭和CAS修订，不代表可覆盖的当前负责人历史。';
COMMENT ON CONSTRAINT pk_lead_assignment ON lead.lead_assignment IS '主键：在租户内唯一标识一条lead_assignment记录。';
COMMENT ON INDEX lead.pk_lead_assignment IS '主键：在租户内唯一标识一条lead_assignment记录。';
COMMENT ON COLUMN lead.lead_assignment.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN lead.lead_assignment.lead_assignment_id IS 'Lead分派事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN lead.lead_assignment.lead_id IS '所属Lead标识：指向同租户不可覆盖的渠道接入记录，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.assignment_no IS '分派序号：从一开始按Lead单调分配，用于检查追加顺序，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.previous_assignment_id IS '前序Assignment标识：为空仅表示链首；非空时指向同一Lead的直接前序，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.owner_appointment_id IS '承接Owner任命标识：指向同租户有效Appointment；资格有效性在提交前复验，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.assignment_reason_code IS '分派原因代码：说明本次追加分派的业务原因，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.assigned_at IS '分派时间：本次Assignment生效的带时区微秒精度时间，写入后不可变。';
COMMENT ON COLUMN lead.lead_assignment.assignment_status_code IS '分派状态：仅可由OPEN单向变为CLOSED。';
COMMENT ON COLUMN lead.lead_assignment.closed_at IS '关闭时间：为空表示尚未关闭；仅允许一次从空写入，之后不可更改。';
COMMENT ON COLUMN lead.lead_assignment.close_reason_code IS '关闭原因代码：仅在关闭时一次写入，之后不可更改。';
COMMENT ON COLUMN lead.lead_assignment.revision IS 'CAS修订号：每次受控更新必须精确递增一，初始为零。';
COMMENT ON COLUMN lead.lead_assignment.created_at IS '创建时间：本次分派事实首次持久化的时间，永久冻结。';
COMMENT ON CONSTRAINT ck_lead_assignment__assignment_no_positive ON lead.lead_assignment IS '分派序号范围：Lead内分派序号必须为正整数。';
COMMENT ON CONSTRAINT ck_lead_assignment__assignment_status_code ON lead.lead_assignment IS '分派状态域：仅允许开放或已关闭。';
COMMENT ON CONSTRAINT ck_lead_assignment__close_pair ON lead.lead_assignment IS '关闭配对：开放分派不得有关闭信息，已关闭分派必须同时记录关闭时间和原因。';
COMMENT ON CONSTRAINT ck_lead_assignment__not_self_previous ON lead.lead_assignment IS '前序链防自环：Assignment不得把自身声明为前序。';
COMMENT ON CONSTRAINT ck_lead_assignment__chain_shape ON lead.lead_assignment IS '分派链形态：链首序号必须为一且无前序，后续分派必须具有准确前序。';
COMMENT ON CONSTRAINT ck_lead_assignment__revision_nonnegative ON lead.lead_assignment IS '修订号范围：LeadAssignment受控更新的CAS修订号不得为负数。';
COMMENT ON CONSTRAINT uq_lead_assignment__lead_no ON lead.lead_assignment IS '追加顺序唯一性：同一Lead的分派序号不得重复。';
COMMENT ON INDEX lead.uq_lead_assignment__lead_no IS '追加顺序唯一性：同一Lead的分派序号不得重复。';
COMMENT ON CONSTRAINT uq_lead_assignment__id_lead_owner ON lead.lead_assignment IS '准确销售路径候选键：供Opportunity证明来源Lead和Owner来自同一Assignment。';
COMMENT ON INDEX lead.uq_lead_assignment__id_lead_owner IS '准确销售路径候选键：供Opportunity证明来源Lead和Owner来自同一Assignment。';

CREATE TABLE lead.lead_contact_result (
    tenant_id uuid NOT NULL,
    lead_contact_result_id uuid NOT NULL,
    lead_id uuid NOT NULL,
    lead_assignment_id uuid NOT NULL,
    contact_no bigint NOT NULL,
    contact_task_id uuid NOT NULL,
    contact_channel_code varchar(64) NOT NULL,
    result_code varchar(64) NOT NULL,
    result_summary text,
    evidence_submission_id uuid,
    resulted_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    CONSTRAINT pk_lead_contact_result PRIMARY KEY (tenant_id, lead_contact_result_id),
    CONSTRAINT ck_lead_contact_result__contact_no_positive CHECK (contact_no > 0),
    CONSTRAINT ck_lead_contact_result__result_code CHECK (result_code IN ('CONNECTED_VALID', 'NOT_CONNECTED', 'SUSPECT_INVALID')),
    CONSTRAINT uq_lead_contact_result__lead_no UNIQUE (tenant_id, lead_id, contact_no),
    CONSTRAINT uq_lead_contact_result__task UNIQUE (tenant_id, contact_task_id),
    CONSTRAINT uq_lead_contact_result__id_path UNIQUE (tenant_id, lead_contact_result_id, lead_id, lead_assignment_id)
);

COMMENT ON TABLE lead.lead_contact_result IS 'Fact Owner：LeadRuntime；Lead联系结果事实：一行代表一个CONTACT_LEAD Task对某Lead的第几次联系结果，Fact Owner为LeadRuntime并只追加；任务执行人只是Actor，结果不可覆盖且不代表分派关闭或机会成立。';
COMMENT ON CONSTRAINT pk_lead_contact_result ON lead.lead_contact_result IS '主键：在租户内唯一标识一条lead_contact_result记录。';
COMMENT ON INDEX lead.pk_lead_contact_result IS '主键：在租户内唯一标识一条lead_contact_result记录。';
COMMENT ON COLUMN lead.lead_contact_result.tenant_id IS '租户标识：复合主键和所有租户内关联的第一列。';
COMMENT ON COLUMN lead.lead_contact_result.lead_contact_result_id IS 'Lead联系结果事实标识：由应用生成的UUIDv7。';
COMMENT ON COLUMN lead.lead_contact_result.lead_id IS '所属Lead标识：联系结果必须归属同租户Lead，写入后不可变。';
COMMENT ON COLUMN lead.lead_contact_result.lead_assignment_id IS '所属分派标识：联系结果必须绑定执行该CONTACT_LEAD Task时的准确Owner任期。';
COMMENT ON COLUMN lead.lead_contact_result.contact_no IS '联系序号：从一开始在Lead内追加，写入后不可变。';
COMMENT ON COLUMN lead.lead_contact_result.contact_task_id IS 'CONTACT_LEAD TaskOccurrence标识：每个任务至多产生一个联系结果；任务类型由CommandRuntime复验。';
COMMENT ON COLUMN lead.lead_contact_result.contact_channel_code IS '联系渠道代码：电话、邮件等静态注册代码，写入后不可变且不含凭据。';
COMMENT ON COLUMN lead.lead_contact_result.result_code IS '联系结果：仅可取CONNECTED_VALID、NOT_CONNECTED或SUSPECT_INVALID，写入后不可变。';
COMMENT ON COLUMN lead.lead_contact_result.result_summary IS '结果摘要：仅保存必要的非敏感业务摘要，不得保存沟通正文、Secret或Token；写入后不可变。';
COMMENT ON COLUMN lead.lead_contact_result.evidence_submission_id IS 'EvidenceRef：为空表示该结果无独立证据提交；非空必须物理关联同租户EvidenceSubmission。';
COMMENT ON COLUMN lead.lead_contact_result.resulted_at IS '结果发生时间：带时区微秒精度时间，写入后不可变。';
COMMENT ON COLUMN lead.lead_contact_result.created_at IS '创建时间：联系结果首次持久化的时间，永久冻结。';
COMMENT ON CONSTRAINT ck_lead_contact_result__contact_no_positive ON lead.lead_contact_result IS '联系序号范围：Lead内联系序号必须为正整数。';
COMMENT ON CONSTRAINT ck_lead_contact_result__result_code ON lead.lead_contact_result IS '联系结果域：严格限制为有效接通、未接通或疑似无效三种冻结结论。';
COMMENT ON CONSTRAINT uq_lead_contact_result__lead_no ON lead.lead_contact_result IS '追加幂等：同一Lead的联系序号不得重复。';
COMMENT ON INDEX lead.uq_lead_contact_result__lead_no IS '追加幂等：同一Lead的联系序号不得重复。';
COMMENT ON CONSTRAINT uq_lead_contact_result__task ON lead.lead_contact_result IS '任务唯一结果：每个CONTACT_LEAD TaskOccurrence至多写入一个联系结果。';
COMMENT ON INDEX lead.uq_lead_contact_result__task IS '任务唯一结果：每个CONTACT_LEAD TaskOccurrence至多写入一个联系结果。';
COMMENT ON CONSTRAINT uq_lead_contact_result__id_path ON lead.lead_contact_result IS '准确资格来源候选键：供Opportunity证明ContactResult、Lead及Assignment来自同一路径。';
COMMENT ON INDEX lead.uq_lead_contact_result__id_path IS '准确资格来源候选键：供Opportunity证明ContactResult、Lead及Assignment来自同一路径。';
