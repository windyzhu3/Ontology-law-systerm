"""销售接入、机会报价与冲突审查领域的冻结表合同。"""

from __future__ import annotations

from ..helpers import (
    bigint_col,
    bool_col,
    check,
    code_col,
    col,
    digest_col,
    encrypted_col,
    entity_fk,
    enum_check,
    fk,
    index,
    int_col,
    revision_col,
    tenant_table,
    text_col,
    time_col,
    typed_ref,
    unique,
    uuid_col,
)
from ..model import Schema


LEAD = tenant_table(
    "lead",
    "lead",
    "lead_id",
    "Lead接入事实：一行代表渠道一次不可覆盖的原始接入，由销售接入域负责；仅允许更新Party解析、当前处置、当前Assignment和CAS修订号，不代表已形成法律需求或客户关系。",
    (
        code_col("source_channel_code", "来源渠道代码：由接入适配器写入并永久冻结，不含凭据。"),
        code_col("source_account_code", "渠道账号代码：标识静态配置的接入账号，不保存账号凭据。", length=128),
        digest_col("source_record_key_digest", "来源记录键摘要：渠道账号内稳定记录键的32字节HMAC或规范摘要，用于来源幂等。"),
        time_col("captured_at", "渠道捕获时间：使用带时区微秒精度时间，由可信接入适配器写入并永久冻结。"),
        encrypted_col("captured_name_ciphertext", "捕获姓名密文：渠道提供的姓名受保护值；缺失时为空，写入后不可覆盖。", nullable=True),
        encrypted_col("captured_phone_ciphertext", "捕获电话密文：渠道提供的电话受保护值；缺失时为空。", nullable=True),
        digest_col("captured_phone_hmac", "捕获电话HMAC：用于受控精确匹配；电话缺失时为空。", nullable=True),
        encrypted_col("captured_email_ciphertext", "捕获邮箱密文：渠道提供的邮箱受保护值；缺失时为空。", nullable=True),
        digest_col("captured_email_hmac", "捕获邮箱HMAC：用于受控精确匹配；邮箱缺失时为空。", nullable=True),
        code_col("city_code", "捕获城市代码：规范化地域代码；渠道未提供时为空。", nullable=True),
        code_col("service_category_code", "服务类别代码：静态注册的拟咨询法律服务类别。"),
        code_col("jurisdiction_code", "法域代码：静态注册的主要适用法域；尚不明确时使用明确UNKNOWN代码。"),
        code_col("urgency_code", "紧急度代码：静态注册的销售接入紧急程度。"),
        encrypted_col("legal_need_summary_ciphertext", "法律需求摘要密文：最小必要的受保护需求摘要，不保存完整咨询正文。"),
        digest_col("captured_content_digest", "接入内容摘要：覆盖上述规范化结构化捕获字段，用于业务疑似重复提示而非来源幂等。"),
        uuid_col("parsed_party_id", "Party解析结果：为空表示尚未或无法唯一解析；可随解析结论受控更新，关系由复合外键证明。", nullable=True),
        code_col("party_resolution_code", "Party解析状态：仅可取UNRESOLVED、RESOLVED或AMBIGUOUS，可受控更新。"),
        code_col("disposition_code", "当前处置代码：销售接入域的当前处置结论，可受控更新但不得改写原捕获事实。"),
        uuid_col("current_assignment_id", "当前Assignment标识：为空表示尚未分派；只作为当前指针受控更新，历史由LeadAssignment链保留。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：该Lead首次持久化的带时区微秒精度时间，永久冻结。"),
    ),
    constraints=(
        enum_check(
            "lead",
            "party_resolution_code",
            ("UNRESOLVED", "RESOLVED", "AMBIGUOUS"),
            "Party解析状态域：限制为未解析、已唯一解析或存在歧义三种机器状态。",
        ),
        check(
            "ck_lead__party_resolution_pair",
            "((party_resolution_code = 'RESOLVED' AND parsed_party_id IS NOT NULL) OR (party_resolution_code <> 'RESOLVED' AND parsed_party_id IS NULL))",
            "Party解析配对：只有RESOLVED状态必须且仅能携带一个Party标识。",
        ),
        check("ck_lead__phone_pair", "(captured_phone_ciphertext IS NULL AND captured_phone_hmac IS NULL) OR (captured_phone_ciphertext IS NOT NULL AND captured_phone_hmac IS NOT NULL)", "电话保护字段：电话密文和HMAC必须同时存在或同时为空。"),
        check("ck_lead__email_pair", "(captured_email_ciphertext IS NULL AND captured_email_hmac IS NULL) OR (captured_email_ciphertext IS NOT NULL AND captured_email_hmac IS NOT NULL)", "邮箱保护字段：邮箱密文和HMAC必须同时存在或同时为空。"),
        check(
            "ck_lead__revision_nonnegative",
            "revision >= 0",
            "修订号范围：Lead受控更新的CAS修订号不得为负数。",
        ),
    ),
    indexes=(
        index(
            "ux_lead__source_idempotency",
            ("tenant_id", "source_account_code", "source_record_key_digest"),
            "来源幂等索引：阻止同一渠道投递重复落库，不用于判定业务疑似重复。",
            unique_=True,
        ),
        index(
            "ix_lead__current_disposition",
            ("tenant_id", "disposition_code", "captured_at"),
            "处置查询索引：支持租户内按当前处置和捕获时间检索Lead。",
        ),
    ),
    foreign_keys=(
        entity_fk("lead", "parsed_party_id", "party", "party", "party_id", "Party解析关系：解析结果必须指向同租户Party。", suffix="parsed_party"),
        entity_fk("lead", "current_assignment_id", "lead", "lead_assignment", "lead_assignment_id", "当前分派关系：当前指针必须指向同租户LeadAssignment；所属Lead一致性由命令提交前复验。", suffix="current_assignment"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=(
        "parsed_party_id",
        "party_resolution_code",
        "disposition_code",
        "current_assignment_id",
        "revision",
    ),
)


LEAD_ASSIGNMENT = tenant_table(
    "lead",
    "lead_assignment",
    "lead_assignment_id",
    "Lead分派事实：一行代表Lead分派链中一次追加分派，由销售接入域负责；分派核心永久冻结，仅允许一次性关闭和CAS修订，不代表可覆盖的当前负责人历史。",
    (
        uuid_col("lead_id", "所属Lead标识：指向同租户不可覆盖的渠道接入记录，写入后不可变。"),
        bigint_col("assignment_no", "分派序号：从一开始按Lead单调分配，用于检查追加顺序，写入后不可变。"),
        uuid_col("previous_assignment_id", "前序Assignment标识：为空仅表示链首；非空时指向同一Lead的直接前序，写入后不可变。", nullable=True),
        uuid_col("owner_appointment_id", "承接Owner任命标识：指向同租户有效Appointment；资格有效性在提交前复验，写入后不可变。"),
        code_col("assignment_reason_code", "分派原因代码：说明本次追加分派的业务原因，写入后不可变。"),
        time_col("assigned_at", "分派时间：本次Assignment生效的带时区微秒精度时间，写入后不可变。"),
        code_col("assignment_status_code", "分派状态：仅可由OPEN单向变为CLOSED。"),
        time_col("closed_at", "关闭时间：为空表示尚未关闭；仅允许一次从空写入，之后不可更改。", nullable=True),
        code_col("close_reason_code", "关闭原因代码：仅在关闭时一次写入，之后不可更改。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：本次分派事实首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_lead_assignment__assignment_no_positive", "assignment_no > 0", "分派序号范围：Lead内分派序号必须为正整数。"),
        enum_check("lead_assignment", "assignment_status_code", ("OPEN", "CLOSED"), "分派状态域：仅允许开放或已关闭。"),
        check(
            "ck_lead_assignment__close_pair",
            "((assignment_status_code = 'OPEN' AND closed_at IS NULL AND close_reason_code IS NULL) OR (assignment_status_code = 'CLOSED' AND closed_at IS NOT NULL AND close_reason_code IS NOT NULL))",
            "关闭配对：开放分派不得有关闭信息，已关闭分派必须同时记录关闭时间和原因。",
        ),
        check("ck_lead_assignment__not_self_previous", "previous_assignment_id IS NULL OR previous_assignment_id <> lead_assignment_id", "前序链防自环：Assignment不得把自身声明为前序。"),
        check("ck_lead_assignment__chain_shape", "(assignment_no = 1 AND previous_assignment_id IS NULL) OR (assignment_no > 1 AND previous_assignment_id IS NOT NULL)", "分派链形态：链首序号必须为一且无前序，后续分派必须具有准确前序。"),
        check("ck_lead_assignment__revision_nonnegative", "revision >= 0", "修订号范围：LeadAssignment受控更新的CAS修订号不得为负数。"),
        unique("uq_lead_assignment__lead_no", ("tenant_id", "lead_id", "assignment_no"), "追加顺序唯一性：同一Lead的分派序号不得重复。"),
        unique("uq_lead_assignment__id_lead_owner", ("tenant_id", "lead_assignment_id", "lead_id", "owner_appointment_id"), "准确销售路径候选键：供Opportunity证明来源Lead和Owner来自同一Assignment。"),
    ),
    indexes=(
        index("ux_lead_assignment__previous", ("tenant_id", "previous_assignment_id"), "前序链唯一索引：一个前序Assignment最多只有一个直接后继，避免链分叉。", unique_=True, where="previous_assignment_id IS NOT NULL"),
        index("ux_lead_assignment__chain_head", ("tenant_id", "lead_id"), "链首唯一索引：每个Lead最多存在一个无前序Assignment的链首。", unique_=True, where="previous_assignment_id IS NULL"),
        index("ux_lead_assignment__open", ("tenant_id", "lead_id"), "当前分派唯一索引：每个Lead最多保留一个OPEN Assignment。", unique_=True, where="assignment_status_code = 'OPEN'"),
    ),
    foreign_keys=(
        entity_fk("lead_assignment", "lead_id", "lead", "lead", "lead_id", "Lead关系：分派必须属于同租户已存在Lead。"),
        entity_fk("lead_assignment", "previous_assignment_id", "lead", "lead_assignment", "lead_assignment_id", "前序关系：非链首分派必须引用同租户前序Assignment；同属一个Lead由提交前复验。", suffix="previous_assignment"),
        entity_fk("lead_assignment", "owner_appointment_id", "identity", "appointment", "appointment_id", "Owner关系：承接人必须是同租户Appointment。", suffix="owner_appointment"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("assignment_status_code", "closed_at", "close_reason_code", "revision"),
    write_once_columns=("closed_at", "close_reason_code"),
    state_column="assignment_status_code",
    initial_state="OPEN",
    state_transitions=(("OPEN", "CLOSED"),),
)


LEAD_CONTACT_RESULT = tenant_table(
    "lead",
    "lead_contact_result",
    "lead_contact_result_id",
    "Lead联系结果事实：一行代表一个CONTACT_LEAD Task对某Lead的第几次联系结果，Fact Owner为LeadRuntime并只追加；任务执行人只是Actor，结果不可覆盖且不代表分派关闭或机会成立。",
    (
        uuid_col("lead_id", "所属Lead标识：联系结果必须归属同租户Lead，写入后不可变。"),
        uuid_col("lead_assignment_id", "所属分派标识：联系结果必须绑定执行该CONTACT_LEAD Task时的准确Owner任期。"),
        bigint_col("contact_no", "联系序号：从一开始在Lead内追加，写入后不可变。"),
        uuid_col("contact_task_id", "CONTACT_LEAD TaskOccurrence标识：每个任务至多产生一个联系结果；任务类型由CommandRuntime复验。"),
        code_col("contact_channel_code", "联系渠道代码：电话、邮件等静态注册代码，写入后不可变且不含凭据。"),
        code_col("result_code", "联系结果：仅可取CONNECTED_VALID、NOT_CONNECTED或SUSPECT_INVALID，写入后不可变。"),
        text_col("result_summary", "结果摘要：仅保存必要的非敏感业务摘要，不得保存沟通正文、Secret或Token；写入后不可变。", nullable=True),
        uuid_col("evidence_submission_id", "EvidenceRef：为空表示该结果无独立证据提交；非空必须物理关联同租户EvidenceSubmission。", nullable=True),
        time_col("resulted_at", "结果发生时间：带时区微秒精度时间，写入后不可变。"),
        time_col("created_at", "创建时间：联系结果首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_lead_contact_result__contact_no_positive", "contact_no > 0", "联系序号范围：Lead内联系序号必须为正整数。"),
        enum_check(
            "lead_contact_result",
            "result_code",
            ("CONNECTED_VALID", "NOT_CONNECTED", "SUSPECT_INVALID"),
            "联系结果域：严格限制为有效接通、未接通或疑似无效三种冻结结论。",
        ),
        unique("uq_lead_contact_result__lead_no", ("tenant_id", "lead_id", "contact_no"), "追加幂等：同一Lead的联系序号不得重复。"),
        unique("uq_lead_contact_result__task", ("tenant_id", "contact_task_id"), "任务唯一结果：每个CONTACT_LEAD TaskOccurrence至多写入一个联系结果。"),
        unique("uq_lead_contact_result__id_path", ("tenant_id", "lead_contact_result_id", "lead_id", "lead_assignment_id"), "准确资格来源候选键：供Opportunity证明ContactResult、Lead及Assignment来自同一路径。"),
    ),
    indexes=(
        index("ix_lead_contact_result__lead_time", ("tenant_id", "lead_id", "resulted_at"), "联系历史索引：支持按Lead和发生时间读取追加结果。"),
    ),
    foreign_keys=(
        entity_fk("lead_contact_result", "lead_id", "lead", "lead", "lead_id", "Lead关系：联系结果必须属于同租户Lead。"),
        entity_fk("lead_contact_result", "lead_assignment_id", "lead", "lead_assignment", "lead_assignment_id", "分派关系：联系结果必须绑定同租户准确LeadAssignment，Lead一致性由提交前复验。", suffix="lead_assignment"),
        entity_fk("lead_contact_result", "contact_task_id", "responsibility", "task_occurrence", "task_occurrence_id", "任务关系：结果必须关联同租户TaskOccurrence；CONTACT_LEAD类型由运行时复验。", suffix="contact_task"),
        entity_fk("lead_contact_result", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。", suffix="evidence_submission"),
    ),
)


LEAD_SCHEMA = Schema(
    "lead",
    "销售接入域：保存不可覆盖Lead、追加分派链与追加联系结果，不承载机会、报价或冲突决定。",
    (LEAD, LEAD_ASSIGNMENT, LEAD_CONTACT_RESULT),
)


# Opportunity 把一项法律需求与来源 Assignment 的 Owner 冻结在一起。
OPPORTUNITY = tenant_table(
    "opportunity",
    "opportunity",
    "opportunity_id",
    "Opportunity锚点：一行代表从一个Lead及其唯一Assignment路径形成的一项准确法律需求和Owner；只保存当前报价指针及一次终结槽，不保存通用Stage或Status。",
    (
        uuid_col("source_lead_id", "来源Lead标识：法律需求由该不可覆盖接入事实转化。"),
        uuid_col("source_assignment_id", "来源LeadAssignment标识：证明机会沿哪次分派形成，写入后不可变。"),
        uuid_col("source_contact_result_id", "来源联系结果标识：必须是同一Lead和Assignment上的准确CONNECTED_VALID事实。"),
        uuid_col("owner_appointment_id", "Opportunity Owner任命标识：必须与来源Assignment冻结的Owner一致，该一致性由命令提交前复验。"),
        encrypted_col("legal_need_ciphertext", "法律需求密文：一项法律需求的受保护原始描述，写入后不可覆盖。"),
        digest_col("legal_need_digest", "法律需求摘要：规范化法律需求的SHA-256原始32字节摘要，写入后不可变。"),
        uuid_col("current_quote_revision_id", "当前QuoteRevision指针：为空表示尚无报价版本；仅为当前导航，历史版本不可覆盖。", nullable=True),
        code_col("close_outcome_code", "终结结果：明确结束该销售机会时一次写入的静态业务结论；未终结为空。", nullable=True),
        time_col("closed_at", "终结时间：形成明确终结事实时一次写入；未终结为空。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：该法律需求首次形成Opportunity的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_opportunity__closed_pair", "(close_outcome_code IS NULL AND closed_at IS NULL) OR (close_outcome_code IS NOT NULL AND closed_at IS NOT NULL)", "机会终结配对：终结结果和时间必须同时为空或一次写入。"),
        check("ck_opportunity__revision_nonnegative", "revision >= 0", "修订号范围：Opportunity受控更新的CAS修订号不得为负数。"),
        unique("uq_opportunity__source_contact_result", ("tenant_id", "source_contact_result_id"), "资格来源唯一：一条CONNECTED_VALID联系结果至多形成一个Opportunity。"),
    ),
    indexes=(
        index("ux_opportunity__source_assignment", ("tenant_id", "source_assignment_id"), "来源唯一索引：一次LeadAssignment最多形成一项法律需求Opportunity。", unique_=True),
        index("ix_opportunity__owner_open", ("tenant_id", "owner_appointment_id", "created_at"), "Owner工作台：按Owner和创建时间读取尚未终结的机会。", where="closed_at IS NULL"),
    ),
    foreign_keys=(
        entity_fk("opportunity", "source_lead_id", "lead", "lead", "lead_id", "来源关系：Opportunity必须引用同租户准确Lead。", suffix="source_lead"),
        entity_fk("opportunity", "source_assignment_id", "lead", "lead_assignment", "lead_assignment_id", "来源关系：Opportunity必须沿同租户LeadAssignment形成。", suffix="source_assignment"),
        entity_fk("opportunity", "owner_appointment_id", "identity", "appointment", "appointment_id", "Owner关系：Opportunity Owner必须为同租户Appointment。", suffix="owner_appointment"),
        entity_fk("opportunity", "source_contact_result_id", "lead", "lead_contact_result", "lead_contact_result_id", "资格来源：Opportunity必须引用同租户准确LeadContactResult。", suffix="source_contact_result"),
        fk("fk_opportunity__assignment_path", ("tenant_id", "source_assignment_id", "source_lead_id", "owner_appointment_id"), "lead", "lead_assignment", ("tenant_id", "lead_assignment_id", "lead_id", "owner_appointment_id"), "销售路径：来源Assignment必须同时属于来源Lead并冻结同一Owner。"),
        fk("fk_opportunity__contact_path", ("tenant_id", "source_contact_result_id", "source_lead_id", "source_assignment_id"), "lead", "lead_contact_result", ("tenant_id", "lead_contact_result_id", "lead_id", "lead_assignment_id"), "资格路径：来源ContactResult必须同时属于来源Lead和Assignment；CONNECTED_VALID由提交前守卫复验。"),
        entity_fk("opportunity", "current_quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "当前报价关系：指针必须指向同租户QuoteRevision；属于本Opportunity由提交前复验。", suffix="current_quote_revision"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("current_quote_revision_id", "close_outcome_code", "closed_at", "revision"),
    write_once_columns=("close_outcome_code", "closed_at"),
)


OPPORTUNITY_PARTICIPATION = tenant_table(
    "opportunity",
    "opportunity_participation",
    "opportunity_participation_id",
    "Opportunity参与方事实：一行代表某次完整参与集合revision中的一个Party上下文角色，由OpportunityRuntime只追加；同一集合revision共享大小和摘要，不代表Party全局身份或合同角色。",
    (
        uuid_col("opportunity_id", "所属Opportunity标识：参与方上下文必须属于同租户法律需求。"),
        int_col("participation_set_revision", "完整参与集合版本：同一Opportunity每次重新冻结全部参与方时递增。"),
        int_col("participation_no", "集合内序号：从一开始连续编号，按完整集合稳定排序。"),
        int_col("participation_set_size", "完整集合大小：同一Opportunity和集合版本的全部行必须保存相同正整数。"),
        digest_col("participation_set_digest", "完整集合摘要：覆盖本版本全部参与方、角色、Party快照和上下文的规范摘要。"),
        uuid_col("party_id", "参与Party标识：物理关联同租户Party；Party可演进但本行角色上下文永久冻结。"),
        bigint_col("party_revision", "Party CAS修订号：形成本集合时用于提交前重验，不声称可从当前态Party回读历史版本。"),
        digest_col("party_snapshot_digest", "主体业务快照摘要：冻结本法律需求所需的最小规范名称、主标识选择和角色上下文，不复制完整Party。"),
        code_col("context_role_code", "上下文角色代码：委托人、付款方、对方等静态业务角色，写入后不可变。"),
        encrypted_col("role_context_ciphertext", "角色上下文密文：冻结与该法律需求有关的受保护补充上下文，写入后不可覆盖。", nullable=True),
        digest_col("role_context_digest", "角色上下文摘要：无补充上下文时为空；否则保存SHA-256原始32字节摘要。", nullable=True),
        time_col("created_at", "创建时间：参与方角色首次纳入Opportunity的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_opportunity_participation__context_pair", "((role_context_ciphertext IS NULL AND role_context_digest IS NULL) OR (role_context_ciphertext IS NOT NULL AND role_context_digest IS NOT NULL))", "角色上下文配对：受保护上下文密文与其摘要必须同时存在或同时为空。"),
        check("ck_opportunity_participation__set_revision", "participation_set_revision > 0", "完整参与集合版本必须为正数。"),
        check("ck_opportunity_participation__participation_no", "participation_no > 0 AND participation_no <= participation_set_size", "集合序号必须为正且不得超过冻结集合大小。"),
        check("ck_opportunity_participation__set_size", "participation_set_size > 0", "完整参与集合大小必须为正数。"),
        check("ck_opportunity_participation__party_revision", "party_revision >= 0", "冻结的Party修订号不得为负数。"),
        unique("uq_opportunity_participation__set_no", ("tenant_id", "opportunity_id", "participation_set_revision", "participation_no"), "集合序号唯一：完整参与集合内序号不得重复。"),
        unique("uq_opportunity_participation__set_party_role", ("tenant_id", "opportunity_id", "participation_set_revision", "party_id", "context_role_code"), "集合角色唯一：同一完整集合内同一Party的同一角色不得重复。"),
    ),
    foreign_keys=(
        entity_fk("opportunity_participation", "opportunity_id", "opportunity", "opportunity", "opportunity_id", "Opportunity关系：参与方角色必须属于同租户Opportunity。"),
        entity_fk("opportunity_participation", "party_id", "party", "party", "party_id", "Party关系：参与方必须指向同租户Party。"),
    ),
)


_opportunity_progress_source = typed_ref(
    "source_fact",
    "触发本次OpportunityProgress的多态准确来源事实",
    optional=True,
)

OPPORTUNITY_PROGRESS = tenant_table(
    "opportunity",
    "opportunity_progress",
    "opportunity_progress_id",
    "Opportunity进展事实：一行代表一项法律需求的一次已发生进展，Fact Owner为OpportunityRuntime并按序追加；机会Owner只是责任Actor，不可覆盖且不代表可变的当前机会阶段。",
    (
        uuid_col("opportunity_id", "所属Opportunity标识：进展必须属于同租户法律需求。"),
        bigint_col("progress_no", "进展序号：从一开始在Opportunity内追加，写入后不可变。"),
        code_col("progress_type_code", "进展类型代码：描述会谈、材料收到、方案确认等已发生事实，写入后不可变。"),
        code_col("progress_contract_code", "进展事实合同代码：静态注册并准确解释该类型进展的来源与语义。"),
        int_col("progress_contract_version", "进展事实合同版本：静态注册合同的正整数版本。"),
        digest_col("progress_digest", "进展事实摘要：覆盖类型、合同版本和准确来源Fact，不复制来源正文。"),
        time_col("occurred_at", "发生时间：进展实际发生的带时区微秒精度时间，写入后不可变。"),
        time_col("created_at", "创建时间：进展事实首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_opportunity_progress__progress_no_positive", "progress_no > 0", "进展序号范围：Opportunity内进展序号必须为正整数。"),
        check("ck_opportunity_progress__contract_version", "progress_contract_version > 0", "进展事实合同版本必须为正整数。"),
        unique("uq_opportunity_progress__opportunity_no", ("tenant_id", "opportunity_id", "progress_no"), "追加幂等：同一Opportunity的进展序号不得重复。"),
    ),
    indexes=(
        index("ix_opportunity_progress__timeline", ("tenant_id", "opportunity_id", "occurred_at"), "进展时间线索引：支持按Opportunity和发生时间读取追加事实。"),
    ),
    foreign_keys=(
        entity_fk("opportunity_progress", "opportunity_id", "opportunity", "opportunity", "opportunity_id", "Opportunity关系：进展必须属于同租户Opportunity。"),
    ),
    typed_references=(_opportunity_progress_source,),
)


QUOTE_REVISION = tenant_table(
    "opportunity",
    "quote_revision",
    "quote_revision_id",
    "QuoteRevision事实：一行代表某Opportunity的一版不可变报价包头，与Scope、Line及PaymentTerm在同一事务完整写入，由机会域负责；不可覆盖，授权归Responsibility的DecisionRecord，不代表已向任何收件人发出。",
    (
        uuid_col("opportunity_id", "所属Opportunity标识：报价版本必须属于同租户一项法律需求。"),
        bigint_col("quote_revision_no", "报价版本号：从一开始在Opportunity内递增，写入后不可变。"),
        uuid_col("predecessor_quote_revision_id", "前序报价版本标识：首版本为空，后续版本准确引用直接前序。", nullable=True),
        uuid_col("confirmed_action_draft_id", "确认草案标识：形成该不可变报价版本包的准确候选输入。"),
        int_col("participation_set_revision", "参与集合版本：本QuoteRevision采用的完整OpportunityParticipation集合版本。"),
        digest_col("participation_set_digest", "参与集合摘要：必须与该完整集合全部行共享的准确摘要一致。"),
        code_col("package_contract_code", "报价包合同代码：静态注册的Scope、Line和PaymentTerm结构。"),
        int_col("package_contract_version", "报价包合同版本：解释全部版本子项的正整数版本。"),
        code_col("currency_code", "报价币种：ISO 4217三位大写代码，整个版本包内金额必须一致。", length=3),
        bigint_col("total_minor", "报价总金额：以最小货币单位记录，不得为负，写入后不可变。"),
        digest_col("content_digest", "版本包内容摘要：覆盖QuoteRevision及同事务Scope、Line、PaymentTerm的规范SHA-256。"),
        time_col("valid_until", "自然失效时间：报价版本自身的可信截止时间；旧Issue是否替代仍由准确Issue事实决定。", nullable=True),
        uuid_col("created_by_appointment_id", "创建任职标识：确认并形成该报价版本包的准确Appointment。"),
        time_col("created_at", "创建时间：不可变报价版本包在同一事务中首次持久化的时间。"),
    ),
    constraints=(
        check("ck_quote_revision__quote_revision_no_positive", "quote_revision_no > 0", "报价版本号范围：Opportunity内报价版本号必须为正整数。"),
        check("ck_quote_revision__currency_code", "currency_code ~ '^[A-Z]{3}$'", "币种格式：报价币种必须为三位大写字母。"),
        check("ck_quote_revision__total_minor_nonnegative", "total_minor >= 0", "金额范围：报价总金额最小货币单位不得为负。"),
        check("ck_quote_revision__package_version", "package_contract_version > 0", "报价包合同版本必须为正数。"),
        check("ck_quote_revision__participation_set_revision", "participation_set_revision > 0", "报价采用的完整参与集合版本必须为正数。"),
        check("ck_quote_revision__predecessor_shape", "(quote_revision_no = 1 AND predecessor_quote_revision_id IS NULL) OR (quote_revision_no > 1 AND predecessor_quote_revision_id IS NOT NULL)", "报价版本链：首版本无前序，后续版本必须引用直接前序。"),
        check("ck_quote_revision__valid_until", "valid_until IS NULL OR valid_until > created_at", "自然失效时间若存在必须晚于版本创建时间。"),
        unique("uq_quote_revision__opportunity_no", ("tenant_id", "opportunity_id", "quote_revision_no"), "报价版本唯一性：同一Opportunity的版本号不得重复。"),
        unique("uq_quote_revision__predecessor", ("tenant_id", "predecessor_quote_revision_id"), "单后继链：一个报价版本最多只有一个直接后继。"),
        unique("uq_quote_revision__confirmed_draft", ("tenant_id", "confirmed_action_draft_id"), "草案唯一：一份确认草案只能形成一个报价版本包。"),
    ),
    indexes=(
        index("ix_quote_revision__opportunity_created", ("tenant_id", "opportunity_id", "created_at"), "报价版本索引：支持按Opportunity读取不可变版本历史。"),
    ),
    foreign_keys=(
        entity_fk("quote_revision", "opportunity_id", "opportunity", "opportunity", "opportunity_id", "Opportunity关系：报价版本必须属于同租户Opportunity。"),
        entity_fk("quote_revision", "predecessor_quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "报价版本链：后续版本必须引用同租户直接前序。", suffix="predecessor"),
        entity_fk("quote_revision", "confirmed_action_draft_id", "responsibility", "action_draft", "action_draft_id", "候选输入：报价版本包必须引用同租户准确确认草案。", suffix="action_draft"),
        entity_fk("quote_revision", "created_by_appointment_id", "identity", "appointment", "appointment_id", "创建主体：报价版本必须记录同租户准确Appointment。", suffix="creator"),
    ),
)


QUOTE_SERVICE_SCOPE = tenant_table(
    "opportunity",
    "quote_service_scope",
    "quote_service_scope_id",
    "QuoteServiceScope事实：一行代表某不可变QuoteRevision包中的一项服务范围，由机会域在版本同一事务写入；写入后不可覆盖，不代表另一个报价版本的范围。",
    (
        uuid_col("quote_revision_id", "所属QuoteRevision标识：服务范围必须属于同租户不可变报价版本。"),
        bigint_col("scope_no", "服务范围序号：从一开始在QuoteRevision内排序，写入后不可变。"),
        code_col("service_code", "服务代码：静态业务代码，写入后不可变。"),
        text_col("scope_summary", "服务范围摘要：仅保存履约边界的必要非敏感摘要，写入后不可变。"),
        bool_col("included", "是否包含：真表示纳入报价服务，假表示明确排除，写入后不可变。"),
        digest_col("scope_hash", "范围摘要：本项服务范围规范表示的SHA-256原始32字节摘要。"),
        time_col("created_at", "创建时间：随QuoteRevision版本包在同一事务持久化的时间。"),
    ),
    constraints=(
        check("ck_quote_service_scope__scope_no_positive", "scope_no > 0", "范围序号：QuoteRevision内服务范围序号必须为正整数。"),
        unique("uq_quote_service_scope__revision_no", ("tenant_id", "quote_revision_id", "scope_no"), "版本范围唯一性：同一QuoteRevision的服务范围序号不得重复。"),
    ),
    foreign_keys=(
        entity_fk("quote_service_scope", "quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "版本包关系：服务范围必须属于同租户QuoteRevision。"),
    ),
)


QUOTE_LINE = tenant_table(
    "opportunity",
    "quote_line",
    "quote_line_id",
    "QuoteLine事实：一行代表某不可变QuoteRevision包中的一条计价行，由机会域在版本同一事务写入；写入后不可覆盖，不代表收款或付款确认。",
    (
        uuid_col("quote_revision_id", "所属QuoteRevision标识：计价行必须属于同租户不可变报价版本。"),
        uuid_col("quote_service_scope_id", "关联QuoteServiceScope标识：为空表示包级计价；非空必须属于同一QuoteRevision，后者由提交前复验。", nullable=True),
        bigint_col("line_no", "计价行序号：从一开始在QuoteRevision内排序，写入后不可变。"),
        code_col("line_type_code", "计价行类型代码：固定费、阶段费、折扣等静态业务代码，写入后不可变。"),
        text_col("line_summary", "计价行摘要：仅保存必要的非敏感计价说明，写入后不可变。"),
        bigint_col("amount_minor", "计价行金额：以最小货币单位记录，可用负值表达明确折扣。"),
        code_col("currency_code", "计价行币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。", length=3),
        time_col("created_at", "创建时间：随QuoteRevision版本包在同一事务持久化的时间。"),
    ),
    constraints=(
        check("ck_quote_line__line_no_positive", "line_no > 0", "计价行序号：QuoteRevision内计价行序号必须为正整数。"),
        check("ck_quote_line__currency_code", "currency_code ~ '^[A-Z]{3}$'", "币种格式：计价行币种必须为三位大写字母。"),
        unique("uq_quote_line__revision_no", ("tenant_id", "quote_revision_id", "line_no"), "版本计价行唯一性：同一QuoteRevision的计价行序号不得重复。"),
    ),
    foreign_keys=(
        entity_fk("quote_line", "quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "版本包关系：计价行必须属于同租户QuoteRevision。"),
        entity_fk("quote_line", "quote_service_scope_id", "opportunity", "quote_service_scope", "quote_service_scope_id", "服务范围关系：非空时计价行必须关联同租户QuoteServiceScope。", suffix="service_scope"),
    ),
)


QUOTE_PAYMENT_TERM = tenant_table(
    "opportunity",
    "quote_payment_term",
    "quote_payment_term_id",
    "QuotePaymentTerm事实：一行代表某不可变QuoteRevision包中的一项付款条件，由机会域在版本同一事务写入；写入后不可覆盖，不代表已收款或支付门禁。",
    (
        uuid_col("quote_revision_id", "所属QuoteRevision标识：付款条件必须属于同租户不可变报价版本。"),
        bigint_col("term_no", "付款条件序号：从一开始在QuoteRevision内排序，写入后不可变。"),
        code_col("due_basis_code", "到期基准代码：签署、开票、里程碑等静态业务代码，写入后不可变。"),
        int_col("due_offset_days", "到期偏移天数：相对到期基准的自然日偏移，可为零但不得为负。"),
        bigint_col("amount_minor", "应付金额：以最小货币单位记录，不得为负，写入后不可变。"),
        code_col("currency_code", "付款条件币种：ISO 4217三位大写代码，必须与QuoteRevision一致并由提交前复验。", length=3),
        text_col("term_summary", "付款条件摘要：仅保存必要的非敏感条件说明，写入后不可变。", nullable=True),
        time_col("created_at", "创建时间：随QuoteRevision版本包在同一事务持久化的时间。"),
    ),
    constraints=(
        check("ck_quote_payment_term__term_no_positive", "term_no > 0", "付款条件序号：QuoteRevision内付款条件序号必须为正整数。"),
        check("ck_quote_payment_term__due_offset_nonnegative", "due_offset_days >= 0", "到期偏移范围：到期偏移天数不得为负。"),
        check("ck_quote_payment_term__amount_nonnegative", "amount_minor >= 0", "金额范围：应付金额最小货币单位不得为负。"),
        check("ck_quote_payment_term__currency_code", "currency_code ~ '^[A-Z]{3}$'", "币种格式：付款条件币种必须为三位大写字母。"),
        unique("uq_quote_payment_term__revision_no", ("tenant_id", "quote_revision_id", "term_no"), "版本付款条件唯一性：同一QuoteRevision的付款条件序号不得重复。"),
    ),
    foreign_keys=(
        entity_fk("quote_payment_term", "quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "版本包关系：付款条件必须属于同租户QuoteRevision。"),
    ),
)


_quote_delivery_fact = typed_ref("delivery_fact", "逐收件人报价已权威发送的准确证明Fact")

QUOTE_ISSUE = tenant_table(
    "opportunity",
    "quote_issue",
    "quote_issue_id",
    "QuoteIssue事实：一行代表某不可变QuoteRevision向一个冻结收件人发出的一次报价，由机会域负责；新Issue可准确引用其替代的旧Issue，旧Issue不被自动改写，只允许授权单向撤回。",
    (
        uuid_col("quote_revision_id", "发出的QuoteRevision标识：写入后不可变，保证内容来自完整不可变版本包。"),
        uuid_col("recipient_participation_id", "收件人Participation标识：冻结收件人在Opportunity中的上下文角色，写入后不可变。"),
        digest_col("recipient_context_digest", "收件人上下文摘要：冻结准确Participation版本、Party和送达地址选择。"),
        digest_col("authorization_set_digest", "授权集合摘要：覆盖绑定该QuoteRevision及contentDigest的全部必要DecisionRecord。"),
        code_col("delivery_channel_code", "送达渠道代码：邮件、门户等静态代码，写入后不可变且不含凭据。"),
        uuid_col("external_action_id", "外部送达ExternalAction标识：为空表示无需外部动作；非空时物理关联同租户外部动作。", nullable=True),
        uuid_col("provider_inbox_id", "Provider消息标识：权威送达证明来自验签消息时准确引用。", nullable=True),
        time_col("issued_at", "发出时间：逐收件人报价实际发出的带时区微秒精度时间，写入后不可变。"),
        uuid_col("replaces_quote_issue_id", "被替代的旧QuoteIssue标识：由新Issue在创建时准确引用；为空表示不替代其他Issue。", nullable=True),
        code_col("issue_status_code", "发出状态：ACTIVE或REVOKED；创建新Issue不自动改变旧Issue。"),
        time_col("revoked_at", "撤回时间：仅在REVOKED时一次写入。", nullable=True),
        code_col("revocation_reason_code", "撤回原因代码：仅在REVOKED时一次写入，不得包含自由文本案情。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：逐收件人QuoteIssue首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        enum_check("quote_issue", "issue_status_code", ("ACTIVE", "REVOKED"), "发出状态域：仅允许有效或已撤回；替代通过新Issue不可变引用旧Issue表达。"),
        check(
            "ck_quote_issue__terminal_payload",
            "((issue_status_code = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason_code IS NULL) OR (issue_status_code = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason_code IS NOT NULL))",
            "单向终态载荷：ACTIVE无撤回字段，REVOKED必须一次写入撤回时间和原因。",
        ),
        check("ck_quote_issue__not_self_replacement", "replaces_quote_issue_id IS NULL OR replaces_quote_issue_id <> quote_issue_id", "替代关系防自环：新QuoteIssue不得声明替代自身。"),
        check("ck_quote_issue__revision_nonnegative", "revision >= 0", "修订号范围：QuoteIssue受控更新的CAS修订号不得为负数。"),
        unique("uq_quote_issue__replaces", ("tenant_id", "replaces_quote_issue_id"), "单后继链：一个旧QuoteIssue最多被一个新Issue直接替代；收件人及版本顺序由提交前重验。"),
    ),
    indexes=(
        index("ux_quote_issue__active_recipient", ("tenant_id", "quote_revision_id", "recipient_participation_id"), "逐收件人有效Issue唯一索引：同一报价版本对同一收件人最多一个ACTIVE Issue。", unique_=True, where="issue_status_code = 'ACTIVE'"),
        index("ix_quote_issue__recipient_time", ("tenant_id", "recipient_participation_id", "issued_at"), "收件人发出历史索引：支持按冻结收件人和时间读取Issue链。"),
    ),
    foreign_keys=(
        entity_fk("quote_issue", "quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "报价版本关系：Issue必须发出同租户不可变QuoteRevision。"),
        entity_fk("quote_issue", "recipient_participation_id", "opportunity", "opportunity_participation", "opportunity_participation_id", "收件人关系：Issue必须指向同租户冻结Participation。", suffix="recipient_participation"),
        entity_fk("quote_issue", "external_action_id", "external_action", "external_action", "external_action_id", "外部动作关系：非空时送达必须关联同租户ExternalAction。", suffix="external_action"),
        entity_fk("quote_issue", "provider_inbox_id", "external_action", "provider_inbox", "provider_inbox_id", "Provider证明：非空时必须引用同租户验签消息。", suffix="provider_inbox"),
        entity_fk("quote_issue", "replaces_quote_issue_id", "opportunity", "quote_issue", "quote_issue_id", "替代关系：新Issue必须指向同租户准确旧Issue；同一收件人及版本顺序由提交前重验。", suffix="replaces"),
    ),
    typed_references=(_quote_delivery_fact,),
    update_policy="CONTROLLED",
    mutable_columns=("issue_status_code", "revoked_at", "revocation_reason_code", "revision"),
    write_once_columns=("revoked_at", "revocation_reason_code"),
    state_column="issue_status_code",
    initial_state="ACTIVE",
    state_transitions=(("ACTIVE", "REVOKED"),),
)


QUOTE_RESPONSE = tenant_table(
    "opportunity",
    "quote_response",
    "quote_response_id",
    "QuoteResponse事实：一行代表收件人对准确QuoteIssue版本的一次已收到响应，由机会域按序追加；写入后不可覆盖，不代表合同已成立或报价Issue可被改写。",
    (
        uuid_col("quote_issue_id", "报价发出标识：响应只能物理引用同租户准确不可变QuoteIssue。"),
        bigint_col("response_no", "响应序号：从一开始在准确Issue标识内追加，写入后不可变。"),
        code_col("response_code", "响应代码：ACCEPTED、NOT_ACCEPTED、REJECTED或AMBIGUOUS，写入后不可变。"),
        encrypted_col("response_content_ciphertext", "响应内容密文：保存受保护原始响应，写入后不可覆盖。", nullable=True),
        digest_col("response_content_digest", "响应内容摘要：无原始内容时为空；否则保存SHA-256原始32字节摘要。", nullable=True),
        uuid_col("provider_inbox_id", "Provider消息标识：响应由可信外部回调形成时准确引用。", nullable=True),
        uuid_col("evidence_submission_id", "EvidenceRef：响应由受控文件证明时准确引用。", nullable=True),
        uuid_col("recorded_by_appointment_id", "记录任职标识：由内部人员确认响应时记录；纯Provider事实可为空。", nullable=True),
        time_col("received_at", "收到时间：响应实际接收的带时区微秒精度时间，写入后不可变。"),
        time_col("created_at", "创建时间：响应事实首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_quote_response__response_no_positive", "response_no > 0", "响应序号范围：准确Issue标识内响应序号必须为正整数。"),
        enum_check("quote_response", "response_code", ("ACCEPTED", "NOT_ACCEPTED", "REJECTED", "AMBIGUOUS"), "响应结论只允许接受、暂不接受、明确拒绝或不明确回应。"),
        check("ck_quote_response__content_pair", "((response_content_ciphertext IS NULL AND response_content_digest IS NULL) OR (response_content_ciphertext IS NOT NULL AND response_content_digest IS NOT NULL))", "响应内容配对：受保护内容密文与其摘要必须同时存在或同时为空。"),
        check("ck_quote_response__proof_present", "provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL OR recorded_by_appointment_id IS NOT NULL", "响应证明：每条响应必须至少具有可信Provider消息、EvidenceRef或内部确认任职之一。"),
        unique("uq_quote_response__issue_no", ("tenant_id", "quote_issue_id", "response_no"), "追加幂等：同一QuoteIssue标识下的响应序号不得重复。"),
    ),
    indexes=(
        index("ix_quote_response__issue_time", ("tenant_id", "quote_issue_id", "received_at"), "响应历史索引：支持按QuoteIssue标识和接收时间读取追加响应。"),
    ),
    foreign_keys=(
        entity_fk("quote_response", "quote_issue_id", "opportunity", "quote_issue", "quote_issue_id", "Issue关系：响应必须指向同租户准确不可变QuoteIssue。", suffix="issue"),
        entity_fk("quote_response", "provider_inbox_id", "external_action", "provider_inbox", "provider_inbox_id", "Provider来源：非空时必须引用同租户验签消息。", suffix="provider_inbox"),
        entity_fk("quote_response", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。", suffix="evidence_submission"),
        entity_fk("quote_response", "recorded_by_appointment_id", "identity", "appointment", "appointment_id", "人工确认：非空时必须引用同租户准确Appointment。", suffix="recorder"),
    ),
)


OPPORTUNITY_SCHEMA = Schema(
    "opportunity",
    "机会与报价域：保存单项法律需求、冻结参与角色、追加进展及不可变报价版本包、逐收件人Issue和Response。",
    (
        OPPORTUNITY,
        OPPORTUNITY_PARTICIPATION,
        OPPORTUNITY_PROGRESS,
        QUOTE_REVISION,
        QUOTE_SERVICE_SCOPE,
        QUOTE_LINE,
        QUOTE_PAYMENT_TERM,
        QUOTE_ISSUE,
        QUOTE_RESPONSE,
    ),
)


# ConflictReview 的触发对象、匹配对象与来源事实均以准确 typed reference 冻结。
_conflict_trigger = typed_ref("trigger_fact", "触发PRE_CONTRACT或PRE_TRANSFER冲突审查的多态准确事实")

CONFLICT_REVIEW = tenant_table(
    "conflict",
    "conflict_review",
    "conflict_review_id",
    "ConflictReview事实：一行与Party和Finding集合在同一事务封存一次PRE_CONTRACT或PRE_TRANSFER审查及初始结论；仅Finding集合可按Decision单向收敛为BLOCKED或WAIVED。",
    (
        code_col("review_type_code", "审查类型：仅可取PRE_CONTRACT或PRE_TRANSFER，写入后不可变。"),
        digest_col("legal_need_digest", "法律需求摘要：冻结本次审查对应的准确法律需求语义，不复制需求正文。"),
        code_col("review_contract_code", "审查合同代码：静态注册并解释范围、规则输入和结论结构。"),
        int_col("review_contract_version", "审查合同版本：静态注册审查合同的正整数版本。"),
        digest_col("scope_hash", "完整审查范围摘要：覆盖所有ConflictReviewParty及其冻结角色的规范SHA-256原始32字节摘要。"),
        code_col("rule_set_code", "冲突规则集代码：静态注册的规则集身份，写入后不可变。"),
        bigint_col("rule_set_revision", "冲突规则集修订号：冻结本次实际执行的准确规则版本，必须为非负。"),
        digest_col("rule_set_hash", "冲突规则语义摘要：实际执行规则语料的SHA-256原始32字节摘要。"),
        code_col("corpus_code", "比对语料代码：静态注册的审查语料身份，写入后不可变。"),
        bigint_col("corpus_revision", "比对语料修订号：冻结本次使用的准确语料版本，必须为非负。"),
        digest_col("corpus_hash", "比对语料摘要：本次实际审查语料的SHA-256原始32字节摘要。"),
        code_col("initial_conclusion_code", "初始结论：CLEAR、NEED_INFO或FINDINGS，在审查封存事务中不可变写入。"),
        int_col("finding_count", "Finding数量：与同事务写入的ConflictFinding集合准确一致。"),
        time_col("reviewed_at", "审查执行时间：使用已冻结范围、规则和语料完成计算的时间，写入后不可变。"),
        code_col("resolution_code", "Finding裁决收敛：BLOCKED或WAIVED；CLEAR和NEED_INFO不使用本槽。", nullable=True),
        digest_col("resolution_digest", "裁决集合摘要：覆盖本Review、scopeHash、全部Finding和各authoritySlot Decision；未收敛为空。", nullable=True),
        time_col("resolved_at", "裁决收敛时间：BLOCKED或全部必要槽WAIVE后一次写入。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：审查快照首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        enum_check("conflict_review", "review_type_code", ("PRE_CONTRACT", "PRE_TRANSFER"), "审查类型域：冲突门禁仅允许合同前或转移前。"),
        check("ck_conflict_review__contract_version", "review_contract_version > 0", "审查合同版本必须为正整数。"),
        check("ck_conflict_review__rule_set_revision_nonnegative", "rule_set_revision >= 0", "规则修订范围：冻结的冲突规则集修订号不得为负数。"),
        check("ck_conflict_review__corpus_revision_nonnegative", "corpus_revision >= 0", "语料修订范围：冻结的比对语料修订号不得为负数。"),
        enum_check("conflict_review", "initial_conclusion_code", ("CLEAR", "NEED_INFO", "FINDINGS"), "初始结论仅允许完整零Finding、明确业务信息缺失或存在Finding。"),
        check("ck_conflict_review__finding_count", "finding_count >= 0 AND ((initial_conclusion_code = 'CLEAR' AND finding_count = 0) OR (initial_conclusion_code = 'NEED_INFO' AND finding_count = 0) OR (initial_conclusion_code = 'FINDINGS' AND finding_count > 0))", "Finding集合：CLEAR必须完整且零Finding，NEED_INFO只表示业务信息缺失，FINDINGS必须至少一项。"),
        enum_check("conflict_review", "resolution_code", ("BLOCKED", "WAIVED"), "裁决收敛只允许任一阻断或全部必要授权槽豁免。"),
        check(
            "ck_conflict_review__resolution_pair",
            "(resolution_code IS NULL AND resolution_digest IS NULL AND resolved_at IS NULL) OR (initial_conclusion_code = 'FINDINGS' AND resolution_code IS NOT NULL AND resolution_digest IS NOT NULL AND resolved_at IS NOT NULL)",
            "裁决一次写入：只有FINDINGS可把结果、Decision集合摘要和时间一次性全部写入。",
        ),
        check("ck_conflict_review__revision_nonnegative", "revision >= 0", "修订号范围：ConflictReview受控更新的CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_conflict_review__trigger_scope", ("tenant_id", "review_type_code", "trigger_fact_type", "trigger_fact_id", "trigger_fact_revision", "trigger_fact_hash", "scope_hash", "rule_set_hash", "corpus_hash"), "审查来源索引：按准确触发版本或摘要、范围、规则和语料读取历史Review；不设置自然唯一，以允许Decision或有效性变化后创建新Review。"),
        index("ix_conflict_review__unresolved", ("tenant_id", "reviewed_at"), "待收敛审查索引：只定位存在Finding且尚未写入裁决结果的Review。", where="initial_conclusion_code = 'FINDINGS' AND resolution_code IS NULL"),
    ),
    typed_references=(_conflict_trigger,),
    update_policy="CONTROLLED",
    mutable_columns=("resolution_code", "resolution_digest", "resolved_at", "revision"),
    write_once_columns=("resolution_code", "resolution_digest", "resolved_at"),
)


_conflict_party_source = typed_ref("source_item", "本次Review实际纳入该Party和上下文角色的准确来源Fact")

CONFLICT_REVIEW_PARTY = tenant_table(
    "conflict",
    "conflict_review_party",
    "conflict_review_party_id",
    "ConflictReviewParty事实：一行代表某Review完整scope内一个Party及其冻结审查角色，由冲突审查域随Review写入且不可变；不代表Party当前全局资料或最终冲突结论。",
    (
        uuid_col("conflict_review_id", "所属ConflictReview标识：范围参与方必须属于同租户审查。"),
        uuid_col("party_id", "范围Party标识：物理关联同租户Party，身份资料有效性在审查提交前复验。"),
        code_col("scope_role_code", "审查范围角色：委托方、对方、关联方等静态角色，写入后不可变。"),
        digest_col("party_snapshot_hash", "Party审查快照摘要：冻结本次用于匹配的必要规范字段，保存SHA-256原始32字节摘要。"),
        time_col("created_at", "创建时间：该Party角色纳入完整审查范围的时间，永久冻结。"),
    ),
    constraints=(
        unique("uq_conflict_review_party__party_role", ("tenant_id", "conflict_review_id", "party_id", "scope_role_code"), "范围角色唯一性：同一Party在同一Review的同一审查角色只出现一次。"),
    ),
    indexes=(
        index("ix_conflict_review_party__review", ("tenant_id", "conflict_review_id", "scope_role_code"), "完整范围读取索引：支持按Review和审查角色枚举全部Party。"),
    ),
    foreign_keys=(
        entity_fk("conflict_review_party", "conflict_review_id", "conflict", "conflict_review", "conflict_review_id", "Review关系：范围参与方必须属于同租户ConflictReview。"),
        entity_fk("conflict_review_party", "party_id", "party", "party", "party_id", "Party关系：审查范围参与方必须指向同租户Party。"),
    ),
    typed_references=(_conflict_party_source,),
)


_conflict_matched_fact = typed_ref("matched_fact", "产生本条ConflictFinding的多态准确匹配事实")
_conflict_source_fact = typed_ref("source_fact", "支持本条ConflictFinding的多态准确来源事实", optional=True)

CONFLICT_FINDING = tenant_table(
    "conflict",
    "conflict_finding",
    "conflict_finding_id",
    "ConflictFinding事实：一行代表某Review基于冻结规则与语料产生的一条不可变命中，由冲突审查域只追加；每个Finding及authoritySlot的决定归Responsibility DecisionRecord，不在本域建决定表。",
    (
        uuid_col("conflict_review_id", "所属ConflictReview标识：Finding必须归属同租户已冻结审查。"),
        bigint_col("finding_no", "命中序号：从一开始在ConflictReview内追加，写入后不可变。"),
        uuid_col("conflict_review_party_id", "命中的范围Party标识：指向同租户ConflictReviewParty；必须属于本Review并由提交前复验。"),
        code_col("rule_code", "命中规则代码：本条Finding实际采用的确定性规则。"),
        bigint_col("rule_revision", "命中规则修订号：冻结该规则的准确版本，不得为负数。"),
        code_col("risk_classification_code", "风险分类：由冻结规则确定，静态authoritySlot集合由代码注册表按本分类解析。"),
        text_col("finding_summary", "命中摘要：仅保存必要的非敏感说明，不得保存语料正文、Secret或Token。"),
        uuid_col("evidence_submission_id", "EvidenceRef：为空表示匹配事实本身足以追溯；非空必须物理关联同租户EvidenceSubmission。", nullable=True),
        digest_col("finding_digest", "Finding摘要：覆盖Review、范围Party、规则、风险分类、匹配对象和EvidenceRef。"),
        time_col("created_at", "创建时间：Finding首次持久化的时间，永久冻结。"),
    ),
    constraints=(
        check("ck_conflict_finding__finding_no_positive", "finding_no > 0", "命中序号范围：ConflictReview内Finding序号必须为正整数。"),
        check("ck_conflict_finding__rule_revision", "rule_revision >= 0", "命中规则修订号不得为负数。"),
        unique("uq_conflict_finding__review_no", ("tenant_id", "conflict_review_id", "finding_no"), "追加幂等：同一ConflictReview的Finding序号不得重复。"),
    ),
    indexes=(
        index("ix_conflict_finding__risk", ("tenant_id", "conflict_review_id", "risk_classification_code"), "任务生成：按Review和风险分类解析静态authoritySlot并创建逐Finding责任卡。"),
        index("ix_conflict_finding__review_party", ("tenant_id", "conflict_review_id", "conflict_review_party_id"), "命中查询索引：支持按Review和范围Party读取全部不可变Finding。"),
    ),
    foreign_keys=(
        entity_fk("conflict_finding", "conflict_review_id", "conflict", "conflict_review", "conflict_review_id", "Review关系：Finding必须属于同租户ConflictReview。"),
        entity_fk("conflict_finding", "conflict_review_party_id", "conflict", "conflict_review_party", "conflict_review_party_id", "范围Party关系：Finding必须命中同租户ConflictReviewParty。", suffix="review_party"),
        entity_fk("conflict_finding", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据关系：EvidenceRef必须物理指向同租户EvidenceSubmission。", suffix="evidence_submission"),
    ),
    typed_references=(_conflict_matched_fact, _conflict_source_fact),
)


CONFLICT_SCHEMA = Schema(
    "conflict",
    "冲突审查域：冻结PRE_CONTRACT或PRE_TRANSFER完整范围、规则与语料，保存不可变参与方和Finding；决定统一归Responsibility。",
    (CONFLICT_REVIEW, CONFLICT_REVIEW_PARTY, CONFLICT_FINDING),
)


SCHEMAS = (LEAD_SCHEMA, OPPORTUNITY_SCHEMA, CONFLICT_SCHEMA)


__all__ = ["SCHEMAS"]
