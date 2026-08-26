from contract.helpers import (
    bigint_col,
    bool_col,
    check,
    code_col,
    col,
    digest_col,
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
from contract.model import Schema, Table


SCHEMA_CONTRACT = "合同事实域：保存版本化合同包、准确签署、执行、付款、激活和终止事实。"
SCHEMA_TRANSFER = "转案事实域：保存转案请求锚点、不可变提交快照和逐项退回要求。"
SCHEMA_PLATFORM = "平台元数据域：仅保存部署门禁；Flyway历史表由Flyway独占管理。"


activation_source = typed_ref("activation_source", "合同激活依据事实", optional=True)

contract = tenant_table(
    "contract",
    "contract",
    "contract_id",
    "合同锚点：一行对应一份由准确商机和接受报价形成的合同，只保存当前版本与当前批准指针，以及执行、激活、终止的单向槽位，不代表合同已经生效。",
    (
        uuid_col("opportunity_id", "来源商机标识：合同所承接的唯一法律需求。"),
        uuid_col("accepted_quote_response_id", "接受报价回应标识：合同成立准备工作的准确销售来源。"),
        uuid_col("current_revision_id", "当前合同版本标识：可随新版本前移，但不改变旧版本。", nullable=True),
        uuid_col("approved_revision_id", "当前已批准合同版本标识：只能等于当前版本；形成新版本时可原子清空或前移，旧批准历史保留在准确DecisionRecord中，执行后冻结。", nullable=True),
        uuid_col("contract_execution_id", "合同执行事实标识：全部执行门禁通过后一次写入。", nullable=True),
        time_col("deal_activated_at", "交易激活时间：首款门禁或风险决定满足后一次写入。", nullable=True),
        uuid_col("contract_termination_id", "合同取消或终止事实标识：形成终止事实后一次写入。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：合同锚点首次建立的可信服务端时间。"),
        time_col("changed_at", "变更时间：最近一次受控槽位更新的可信服务端时间。"),
    ),
    constraints=(
        unique("uk_contract__accepted_quote_response", ("tenant_id", "accepted_quote_response_id"), "来源唯一：一条接受报价回应只能形成一份合同。"),
        unique("uk_contract__id_opportunity_execution", ("tenant_id", "contract_id", "opportunity_id", "contract_execution_id"), "准确转案来源候选键：供TransferRequest证明Opportunity、Contract及Execution属于同一合同链。"),
        check(
            "ck_contract__activation_complete",
            "(deal_activated_at IS NULL AND activation_source_type IS NULL) OR (deal_activated_at IS NOT NULL AND activation_source_type IS NOT NULL)",
            "激活完整性：激活时间与准确激活依据必须同时存在或同时为空。",
        ),
        check("ck_contract__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ix_contract__opportunity", ("tenant_id", "opportunity_id"), "来源查询：按商机定位其合同。"),
    ),
    foreign_keys=(
        entity_fk("contract", "opportunity_id", "opportunity", "opportunity", "opportunity_id", "来源完整性：合同必须属于同租户准确商机。"),
        entity_fk("contract", "accepted_quote_response_id", "opportunity", "quote_response", "quote_response_id", "来源完整性：合同必须引用同租户准确接受回应。", suffix="accepted_quote_response"),
        entity_fk("contract", "current_revision_id", "contract", "contract_revision", "contract_revision_id", "当前版本槽：必须指向本合同的准确版本，归属关系由延迟守卫复验。", suffix="current_revision", deferrable=True, initially_deferred=True),
        entity_fk("contract", "approved_revision_id", "contract", "contract_revision", "contract_revision_id", "批准槽：必须指向同租户准确合同版本。", suffix="approved_revision", deferrable=True, initially_deferred=True),
        entity_fk("contract", "contract_execution_id", "contract", "contract_execution", "contract_execution_id", "执行槽：必须指向同租户唯一合同执行事实。", suffix="execution", deferrable=True, initially_deferred=True),
        entity_fk("contract", "contract_termination_id", "contract", "contract_termination", "contract_termination_id", "终止槽：必须指向同租户准确终止事实。", suffix="termination", deferrable=True, initially_deferred=True),
    ),
    typed_references=(activation_source,),
    update_policy="CONTROLLED",
    mutable_columns=(
        "current_revision_id", "approved_revision_id", "contract_execution_id",
        "deal_activated_at", "activation_source_type", "activation_source_id",
        "activation_source_revision", "activation_source_hash",
        "contract_termination_id", "revision", "changed_at",
    ),
    write_once_columns=(
        "contract_execution_id", "deal_activated_at",
        "activation_source_type", "activation_source_id", "activation_source_revision",
        "activation_source_hash", "contract_termination_id",
    ),
)


contract_revision = tenant_table(
    "contract",
    "contract_revision",
    "contract_revision_id",
    "合同版本：一行连同其参与方、费用、付款门禁和签署计划构成一个不可变版本包，旧批准、签名和正文不得复用。",
    (
        uuid_col("contract_id", "合同标识：该版本所属的合同锚点。"),
        int_col("revision_no", "版本序号：从一开始在同一合同内连续递增。"),
        uuid_col("predecessor_revision_id", "前序合同版本标识：首版本为空，其余版本准确引用直接前序。", nullable=True),
        uuid_col("confirmed_action_draft_id", "确认草案标识：形成该版本包的准确候选输入。"),
        uuid_col("source_quote_revision_id", "来源报价版本标识：商业条件的准确来源。"),
        uuid_col("source_quote_response_id", "来源报价回应标识：客户接受的准确发出与回应链。"),
        uuid_col("body_evidence_submission_id", "合同正文证据提交标识：准确指向不可变正文对象。"),
        digest_col("body_sha256", "合同正文SHA-256：正文准确对象字节的32字节服务端摘要。"),
        uuid_col("pre_contract_review_id", "签约前冲突审查标识：该版本包冻结的独立PRE_CONTRACT审查。"),
        digest_col("pre_contract_scope_hash", "签约前审查范围摘要：准确参与方、规则和语料范围的32字节摘要。"),
        digest_col("pre_contract_resolution_digest", "签约前审查结论摘要：可用于本版本放行的准确结论摘要。"),
        code_col("package_contract_code", "版本包合同代码：静态注册的合同结构类型。"),
        int_col("package_contract_version", "版本包合同版本：解释全部子项结构的正整数版本。"),
        digest_col("content_digest", "版本包内容摘要：覆盖正文对象版本、全部子项和签约前审查快照。"),
        uuid_col("created_by_appointment_id", "创建任职标识：确认并提交该版本包的准确任职。"),
        time_col("created_at", "创建时间：版本包在同一短事务封存的可信时间。"),
    ),
    constraints=(
        unique("uk_contract_revision__contract_revision_no", ("tenant_id", "contract_id", "revision_no"), "版本唯一：同一合同内版本序号不得重复。"),
        unique("uk_contract_revision__predecessor", ("tenant_id", "predecessor_revision_id"), "单后继链：一个合同版本最多只有一个直接后继。"),
        unique("uk_contract_revision__confirmed_draft", ("tenant_id", "confirmed_action_draft_id"), "草案唯一：一份确认草案只能形成一个合同版本包。"),
        unique("uk_contract_revision__id_contract", ("tenant_id", "contract_revision_id", "contract_id"), "准确版本归属候选键：供执行、付款和终止事实证明版本属于同一Contract。"),
        check("ck_contract_revision__revision_no", "revision_no > 0", "版本序号必须为正数。"),
        check("ck_contract_revision__predecessor_shape", "(revision_no = 1 AND predecessor_revision_id IS NULL) OR (revision_no > 1 AND predecessor_revision_id IS NOT NULL)", "版本链形态：首版本无前序，后续版本必须有直接前序。"),
        check("ck_contract_revision__package_version", "package_contract_version > 0", "版本包合同版本必须为正数。"),
    ),
    foreign_keys=(
        entity_fk("contract_revision", "contract_id", "contract", "contract", "contract_id", "归属完整性：合同版本必须属于同租户合同。"),
        entity_fk("contract_revision", "predecessor_revision_id", "contract", "contract_revision", "contract_revision_id", "版本链：后续版本准确引用同租户直接前序。", suffix="predecessor"),
        entity_fk("contract_revision", "confirmed_action_draft_id", "responsibility", "action_draft", "action_draft_id", "输入来源：版本包必须引用准确确认草案。", suffix="action_draft"),
        entity_fk("contract_revision", "source_quote_revision_id", "opportunity", "quote_revision", "quote_revision_id", "报价来源：版本包必须引用准确报价版本。", suffix="quote_revision"),
        entity_fk("contract_revision", "source_quote_response_id", "opportunity", "quote_response", "quote_response_id", "接受来源：版本包必须引用准确报价回应。", suffix="quote_response"),
        entity_fk("contract_revision", "body_evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "正文来源：版本包必须引用准确EvidenceSubmission。", suffix="body_evidence"),
        entity_fk("contract_revision", "pre_contract_review_id", "conflict", "conflict_review", "conflict_review_id", "审查来源：版本包必须引用独立PRE_CONTRACT审查。", suffix="pre_contract_review"),
        entity_fk("contract_revision", "created_by_appointment_id", "identity", "appointment", "appointment_id", "创建主体：版本包必须记录同租户准确任职。", suffix="creator"),
    ),
)


contract_participation = tenant_table(
    "contract", "contract_participation", "contract_participation_id",
    "合同参与方：一行冻结一个合同版本中的一项主体角色和准确Party修订，不表示该主体已经签署。",
    (
        uuid_col("contract_revision_id", "合同版本标识：该参与方所属的不可变版本包。"),
        int_col("participation_no", "参与项序号：在合同版本内稳定排序。"),
        uuid_col("party_id", "主体标识：参与合同的当前态Party锚点。"),
        bigint_col("party_revision", "Party CAS修订号：形成版本包时用于提交前重验，不声称可从当前态Party回读历史版本。"),
        digest_col("party_snapshot_digest", "合同主体快照摘要：冻结本版本所需的最小规范名称、主标识选择及角色上下文。"),
        code_col("context_role_code", "上下文角色：静态注册的委托人、对方、签署人等角色。"),
        uuid_col("source_opportunity_participation_id", "来源商机参与项标识：可追溯到销售阶段的准确参与事实。", nullable=True),
        bool_col("signature_required", "签署要求：该参与方是否必须拥有至少一个签署计划槽。"),
        time_col("created_at", "创建时间：随合同版本包封存的可信时间。"),
    ),
    constraints=(
        unique("uk_contract_participation__revision_no", ("tenant_id", "contract_revision_id", "participation_no"), "参与项唯一：同一合同版本内序号不得重复。"),
        unique("uk_contract_participation__revision_party_role", ("tenant_id", "contract_revision_id", "party_id", "context_role_code"), "角色唯一：同一版本内同一主体的同一上下文角色不得重复。"),
        unique("uk_contract_participation__id_revision_party", ("tenant_id", "contract_participation_id", "contract_revision_id", "party_id"), "准确签署参与候选键：供SignaturePlan证明参与项、版本和签署Party一致。"),
        check("ck_contract_participation__no_positive", "participation_no > 0", "参与项序号必须为正数。"),
        check("ck_contract_participation__party_revision", "party_revision >= 0", "冻结的Party修订号不得为负数。"),
    ),
    foreign_keys=(
        entity_fk("contract_participation", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：参与项必须属于准确合同版本。"),
        entity_fk("contract_participation", "party_id", "party", "party", "party_id", "主体完整性：参与项必须引用同租户Party。"),
        entity_fk("contract_participation", "source_opportunity_participation_id", "opportunity", "opportunity_participation", "opportunity_participation_id", "销售来源：可选引用准确商机参与项。", suffix="source_opportunity_participation"),
    ),
)


contract_fee_term = tenant_table(
    "contract", "contract_fee_term", "contract_fee_term_id",
    "合同费用条款：一行保存合同版本中的一项不可变费用约定，不代表付款已经发生。",
    (
        uuid_col("contract_revision_id", "合同版本标识：费用条款所属版本包。"),
        int_col("term_no", "条款序号：在合同版本内稳定排序。"),
        code_col("fee_type_code", "费用类型：静态注册的固定费、计时费、风险代理等类型。"),
        bigint_col("amount_minor", "约定金额：以currency_code最小货币单位表示的非负金额。"),
        code_col("currency_code", "币种代码：三位大写ISO 4217代码。", length=3),
        code_col("calculation_contract_code", "计费合同代码：解释费用计算方式的静态代码。"),
        int_col("calculation_contract_version", "计费合同版本：解释计算参数的正整数版本。"),
        uuid_col("source_quote_line_id", "来源报价行标识：费用来源于报价时准确引用。", nullable=True),
        digest_col("term_digest", "条款摘要：规范化费用条款的32字节摘要。"),
        time_col("created_at", "创建时间：随合同版本包封存的可信时间。"),
    ),
    constraints=(
        unique("uk_contract_fee_term__revision_no", ("tenant_id", "contract_revision_id", "term_no"), "条款唯一：同一合同版本内条款序号不得重复。"),
        check("ck_contract_fee_term__no_positive", "term_no > 0", "条款序号必须为正数。"),
        check("ck_contract_fee_term__amount_nonnegative", "amount_minor >= 0", "费用金额不得为负数。"),
        check("ck_contract_fee_term__currency", "currency_code ~ '^[A-Z]{3}$'", "币种必须为三位大写代码。"),
        check("ck_contract_fee_term__contract_version", "calculation_contract_version > 0", "计费合同版本必须为正数。"),
    ),
    foreign_keys=(
        entity_fk("contract_fee_term", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：费用条款必须属于准确合同版本。"),
        entity_fk("contract_fee_term", "source_quote_line_id", "opportunity", "quote_line", "quote_line_id", "报价来源：可选引用准确报价行。", suffix="source_quote_line"),
    ),
)


payment_gate = tenant_table(
    "contract", "payment_gate", "payment_gate_id",
    "付款门禁：一行冻结合同版本的首款或风险激活条件，并只允许从未满足单向写入准确满足事实。",
    (
        uuid_col("contract_revision_id", "合同版本标识：付款门禁所属的不可变版本包。"),
        code_col("gate_kind", "门禁类型：FIRST_PAYMENT表示首款，RISK_DECISION表示专门风险决定。"),
        bigint_col("required_amount_minor", "首款要求金额：最小货币单位；风险决定门禁为空。", nullable=True),
        code_col("currency_code", "首款币种：三位大写代码；风险决定门禁为空。", length=3, nullable=True),
        code_col("gate_state", "门禁状态：PENDING或SATISFIED，只允许单向满足。"),
        time_col("satisfied_at", "满足时间：准确Confirmation集合或风险决定通过后一次写入。", nullable=True),
        digest_col("satisfaction_digest", "满足摘要：覆盖门禁条件及其准确满足依据。", nullable=True),
        col("payment_confirmation_ids", "uuid[]", "到账确认集合：FIRST_PAYMENT满足时按UUID字节升序去重冻结的准确PaymentConfirmation标识；其他状态或门禁类型为空。", nullable=True),
        digest_col("confirmation_set_digest", "到账确认集合摘要：FIRST_PAYMENT满足时准确冻结。", nullable=True),
        uuid_col("risk_decision_record_id", "风险激活决定标识：RISK_DECISION满足时准确引用。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：随合同版本包封存的可信时间。"),
        time_col("changed_at", "变更时间：门禁最近一次受控更新的可信时间。"),
    ),
    constraints=(
        unique("uk_payment_gate__contract_revision", ("tenant_id", "contract_revision_id"), "版本唯一：一个合同版本最多具有一个付款门禁。"),
        enum_check("payment_gate", "gate_kind", ("FIRST_PAYMENT", "RISK_DECISION"), "门禁类型仅允许首款或专门风险决定。"),
        enum_check("payment_gate", "gate_state", ("PENDING", "SATISFIED"), "门禁状态仅允许未满足或已满足。"),
        check("ck_payment_gate__kind_payload", "(gate_kind = 'FIRST_PAYMENT' AND required_amount_minor IS NOT NULL AND required_amount_minor > 0 AND currency_code ~ '^[A-Z]{3}$') OR (gate_kind = 'RISK_DECISION' AND required_amount_minor IS NULL AND currency_code IS NULL)", "门禁载荷：首款门禁必须有正金额和币种，风险决定门禁不得伪造零元付款。"),
        check("ck_payment_gate__satisfaction", "(gate_state = 'PENDING' AND satisfied_at IS NULL AND satisfaction_digest IS NULL AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NULL) OR (gate_state = 'SATISFIED' AND satisfied_at IS NOT NULL AND satisfaction_digest IS NOT NULL AND ((gate_kind = 'FIRST_PAYMENT' AND payment_confirmation_ids IS NOT NULL AND cardinality(payment_confirmation_ids) > 0 AND confirmation_set_digest IS NOT NULL AND risk_decision_record_id IS NULL) OR (gate_kind = 'RISK_DECISION' AND payment_confirmation_ids IS NULL AND confirmation_set_digest IS NULL AND risk_decision_record_id IS NOT NULL)))", "满足完整性：首款门禁冻结非空准确Confirmation集合和摘要，风险门禁只引用专门DecisionRecord。"),
        check("ck_payment_gate__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
    ),
    foreign_keys=(
        entity_fk("payment_gate", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：门禁必须属于准确合同版本。"),
        entity_fk("payment_gate", "risk_decision_record_id", "responsibility", "decision_record", "decision_record_id", "风险依据：风险门禁满足时必须引用准确决定。", suffix="risk_decision"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("gate_state", "satisfied_at", "satisfaction_digest", "payment_confirmation_ids", "confirmation_set_digest", "risk_decision_record_id", "revision", "changed_at"),
    write_once_columns=("satisfied_at", "satisfaction_digest", "payment_confirmation_ids", "confirmation_set_digest", "risk_decision_record_id"),
    state_column="gate_state",
    initial_state="PENDING",
    state_transitions=(("PENDING", "SATISFIED"),),
)


signature_plan = tenant_table(
    "contract", "signature_plan", "signature_plan_id",
    "签署计划槽：一行冻结合同版本中的一个必需或可选签署槽，不代表已经签署。",
    (
        uuid_col("contract_revision_id", "合同版本标识：签署槽所属的准确版本包。"),
        int_col("slot_no", "签署槽序号：在合同版本内稳定排序。"),
        code_col("authority_slot_code", "签署授权槽代码：静态注册的签署能力要求。"),
        uuid_col("contract_participation_id", "合同参与项标识：计划签署人对应的准确参与事实。"),
        uuid_col("signer_party_id", "签署主体标识：必须与合同参与项中的Party一致。"),
        code_col("signature_method_code", "签署方式：静态注册的电子、线下等方式。"),
        bool_col("seal_required", "印章要求：该槽是否必须核验准确印章事实。"),
        bool_col("required", "必需标志：执行前该槽是否必须具有有效签署事实。"),
        digest_col("plan_digest", "计划摘要：该签署槽规范内容的32字节摘要。"),
        time_col("created_at", "创建时间：随合同版本包封存的可信时间。"),
    ),
    constraints=(
        unique("uk_signature_plan__revision_slot_no", ("tenant_id", "contract_revision_id", "slot_no"), "签署槽唯一：同一合同版本内槽序号不得重复。"),
        unique("uk_signature_plan__revision_authority_slot", ("tenant_id", "contract_revision_id", "authority_slot_code"), "授权槽唯一：同一合同版本内静态授权槽不得重复。"),
        unique("uk_signature_plan__id_revision_signer", ("tenant_id", "signature_plan_id", "contract_revision_id", "signer_party_id"), "准确签署计划候选键：供ContractSignature证明Plan、版本和签署Party一致。"),
        check("ck_signature_plan__slot_no_positive", "slot_no > 0", "签署槽序号必须为正数。"),
    ),
    foreign_keys=(
        entity_fk("signature_plan", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：签署槽必须属于准确合同版本。"),
        entity_fk("signature_plan", "contract_participation_id", "contract", "contract_participation", "contract_participation_id", "参与方归属：签署槽必须引用准确合同参与项。", suffix="participation"),
        entity_fk("signature_plan", "signer_party_id", "party", "party", "party_id", "签署主体：签署槽必须引用同租户Party。", suffix="signer_party"),
        fk("fk_signature_plan__participation_path", ("tenant_id", "contract_participation_id", "contract_revision_id", "signer_party_id"), "contract", "contract_participation", ("tenant_id", "contract_participation_id", "contract_revision_id", "party_id"), "签署计划路径：参与项必须属于同一合同版本且其Party就是计划签署主体。"),
    ),
)


contract_signature = tenant_table(
    "contract", "contract_signature", "contract_signature_id",
    "合同签署事实：一行表示一个计划槽上经证据、身份授权、签署内容和可信外部结果核验通过的签署；外部发送或回调本身不构成本事实。",
    (
        uuid_col("contract_revision_id", "合同版本标识：签署内容对应的准确版本。"),
        uuid_col("signature_plan_id", "签署计划标识：本签署满足的准确计划槽。"),
        int_col("signature_no", "签署序号：同一计划槽重新签署时递增。"),
        uuid_col("evidence_submission_id", "签署证据提交标识：经核验的准确EvidenceSubmission。"),
        uuid_col("external_action_id", "外部签署动作标识：使用外部签署服务时准确引用。", nullable=True),
        uuid_col("provider_inbox_id", "Provider消息标识：可信外部结果来自回调时准确引用。", nullable=True),
        uuid_col("signer_party_id", "实际签署Party标识：必须符合计划槽和版本参与方。"),
        digest_col("signer_identity_digest", "签署身份摘要：冻结核验通过的身份材料与方法。"),
        digest_col("signer_authority_digest", "签署授权摘要：冻结签署时有效的授权路径。"),
        digest_col("signed_content_digest", "签署内容摘要：必须与合同版本内容摘要准确匹配。"),
        code_col("verification_method_code", "核验方法：静态注册的签署真实性验证方法。"),
        time_col("signed_at", "签署时间：可信签署结果中的业务发生时间。"),
        time_col("verified_at", "核验时间：服务端完成全部签署门禁的时间。"),
        time_col("revoked_at", "撤回时间：仅合同执行前可由授权命令一次写入。", nullable=True),
        uuid_col("revoked_by_appointment_id", "撤回任职标识：执行撤回命令的准确任职。", nullable=True),
        digest_col("revocation_authorization_digest", "撤回授权摘要：冻结执行前撤回命令提交时的单路径四轴授权快照。", nullable=True),
        code_col("revocation_reason_code", "撤回原因：允许列表化原因代码。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：签署事实经核验后追加的可信时间。"),
        time_col("changed_at", "变更时间：签署撤回槽最近一次受控写入时间。"),
    ),
    constraints=(
        unique("uk_contract_signature__plan_no", ("tenant_id", "signature_plan_id", "signature_no"), "签署唯一：同一计划槽内签署序号不得重复。"),
        check("ck_contract_signature__signature_no", "signature_no > 0", "签署序号必须为正数。"),
        check("ck_contract_signature__provider_pair", "(external_action_id IS NULL AND provider_inbox_id IS NULL) OR external_action_id IS NOT NULL", "外部证明：Provider消息存在时必须同时能定位准确外部动作。"),
        check("ck_contract_signature__revocation_complete", "(revoked_at IS NULL AND revoked_by_appointment_id IS NULL AND revocation_authorization_digest IS NULL AND revocation_reason_code IS NULL) OR (revoked_at IS NOT NULL AND revoked_by_appointment_id IS NOT NULL AND revocation_authorization_digest IS NOT NULL AND revocation_reason_code IS NOT NULL)", "撤回完整性：撤回时间、主体、授权摘要和原因必须一次性完整写入。"),
        check("ck_contract_signature__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ux_contract_signature__active_plan", ("tenant_id", "signature_plan_id"), "有效签署唯一：一个计划槽同时最多有一条未撤回签署。", unique_=True, where="revoked_at IS NULL"),
    ),
    foreign_keys=(
        entity_fk("contract_signature", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：签署必须对应准确合同版本。"),
        entity_fk("contract_signature", "signature_plan_id", "contract", "signature_plan", "signature_plan_id", "计划归属：签署必须对应准确签署槽。", suffix="plan"),
        entity_fk("contract_signature", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据来源：签署必须引用准确EvidenceSubmission。", suffix="evidence"),
        entity_fk("contract_signature", "external_action_id", "external_action", "external_action", "external_action_id", "外部动作：可选引用准确外部签署尝试。", suffix="external_action"),
        entity_fk("contract_signature", "provider_inbox_id", "external_action", "provider_inbox", "provider_inbox_id", "Provider证明：可选引用准确可信入站消息。", suffix="provider_inbox"),
        entity_fk("contract_signature", "signer_party_id", "party", "party", "party_id", "签署主体：实际签署人必须是同租户Party。", suffix="signer_party"),
        entity_fk("contract_signature", "revoked_by_appointment_id", "identity", "appointment", "appointment_id", "撤回主体：撤回必须记录准确任职。", suffix="revoker"),
        fk("fk_contract_signature__plan_path", ("tenant_id", "signature_plan_id", "contract_revision_id", "signer_party_id"), "contract", "signature_plan", ("tenant_id", "signature_plan_id", "contract_revision_id", "signer_party_id"), "签署路径：签署事实的Plan、版本和实际Party必须完全一致。"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code", "revision", "changed_at"),
    write_once_columns=("revoked_at", "revoked_by_appointment_id", "revocation_authorization_digest", "revocation_reason_code"),
)


contract_execution = tenant_table(
    "contract", "contract_execution", "contract_execution_id",
    "合同执行事实：一行表示准确合同版本的审批、审查、签署、印章和归档条件全部经提交前复验通过，不代表首款到账。",
    (
        uuid_col("contract_id", "合同标识：被执行的合同锚点。"),
        uuid_col("contract_revision_id", "合同版本标识：实际执行的唯一准确版本。"),
        digest_col("approval_set_digest", "审批集合摘要：覆盖全部静态授权槽的准确DecisionRecord。"),
        digest_col("signature_set_digest", "签署集合摘要：覆盖全部必要且未撤回的签署事实。"),
        digest_col("review_scope_hash", "审查范围摘要：执行时复验的PRE_CONTRACT准确范围。"),
        digest_col("review_resolution_digest", "审查结论摘要：执行时仍可用于放行的准确结论。"),
        uuid_col("archive_evidence_submission_id", "归档证据提交标识：执行版本的准确归档文件。"),
        digest_col("execution_digest", "执行摘要：覆盖合同版本及全部执行门禁结果。"),
        uuid_col("executed_by_appointment_id", "执行任职标识：实施执行命令的准确任职。"),
        time_col("executed_at", "执行时间：唯一合同执行事实提交的可信时间。"),
    ),
    constraints=(
        unique("uk_contract_execution__contract", ("tenant_id", "contract_id"), "合同唯一：一份合同最多形成一个执行事实。"),
        unique("uk_contract_execution__revision", ("tenant_id", "contract_revision_id"), "版本唯一：一个合同版本最多形成一个执行事实。"),
        unique("uk_contract_execution__id_contract_revision", ("tenant_id", "contract_execution_id", "contract_id", "contract_revision_id"), "准确执行候选键：供终止及转案证明Execution、Contract和Revision为同一事实链。"),
    ),
    foreign_keys=(
        entity_fk("contract_execution", "contract_id", "contract", "contract", "contract_id", "合同归属：执行事实必须属于准确合同。"),
        entity_fk("contract_execution", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：执行事实必须引用准确合同版本。", suffix="revision"),
        fk("fk_contract_execution__revision_contract", ("tenant_id", "contract_revision_id", "contract_id"), "contract", "contract_revision", ("tenant_id", "contract_revision_id", "contract_id"), "执行归属：被执行Revision必须属于同一Contract。"),
        entity_fk("contract_execution", "archive_evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "归档证据：执行事实必须引用准确EvidenceSubmission。", suffix="archive_evidence"),
        entity_fk("contract_execution", "executed_by_appointment_id", "identity", "appointment", "appointment_id", "执行主体：执行事实必须记录准确任职。", suffix="executor"),
    ),
)


payment_confirmation = tenant_table(
    "contract", "payment_confirmation", "payment_confirmation_id",
    "付款确认事实：一行保存可信来源已确认且准确归属合同的一笔到账、撤销或退款，不代表付款门禁已经满足。",
    (
        uuid_col("contract_id", "合同标识：付款被准确归属的合同。"),
        uuid_col("contract_revision_id", "合同版本标识：确认付款时适用的准确合同版本。"),
        int_col("confirmation_no", "确认序号：在合同内按追加顺序递增。"),
        code_col("confirmation_type", "确认类型：RECEIPT、REVERSAL或REFUND。"),
        bigint_col("amount_minor", "确认金额：以currency_code最小货币单位表示的非负绝对金额。"),
        code_col("currency_code", "币种代码：三位大写ISO 4217代码。", length=3),
        code_col("provider_account_code", "资金来源账号代码：静态配置的支付或银行账号。"),
        digest_col("provider_transaction_key_hmac", "Provider交易键HMAC：用于同账号内安全去重的32字节值，不保存原始交易凭据。"),
        uuid_col("external_action_id", "外部动作标识：本系统发起资金动作时可准确引用。", nullable=True),
        uuid_col("provider_inbox_id", "Provider消息标识：由可信入站通知形成确认时准确引用。", nullable=True),
        uuid_col("evidence_submission_id", "付款证据提交标识：用于确认归属的准确EvidenceSubmission。", nullable=True),
        uuid_col("reverses_payment_confirmation_id", "被撤销或退款的原付款确认标识；普通到账为空。", nullable=True),
        digest_col("attribution_digest", "归属摘要：冻结付款与合同、版本及来源的准确匹配依据。"),
        time_col("effective_at", "资金事实发生时间：可信来源确认的到账、撤销或退款时间。"),
        time_col("confirmed_at", "确认时间：Fact Owner完成真实性和合同归属核验的时间。"),
        uuid_col("recorded_by_appointment_id", "记录任职标识：确认并写入付款事实的准确任职。"),
    ),
    constraints=(
        unique("uk_payment_confirmation__contract_no", ("tenant_id", "contract_id", "confirmation_no"), "确认唯一：同一合同内确认序号不得重复。"),
        unique("uk_payment_confirmation__provider_key", ("tenant_id", "provider_account_code", "provider_transaction_key_hmac", "confirmation_type"), "来源幂等：同Provider账号、交易键和事实类型不得重复确认。"),
        enum_check("payment_confirmation", "confirmation_type", ("RECEIPT", "REVERSAL", "REFUND"), "确认类型只允许到账、撤销或退款。"),
        check("ck_payment_confirmation__no_positive", "confirmation_no > 0", "确认序号必须为正数。"),
        check("ck_payment_confirmation__amount_positive", "amount_minor > 0", "确认金额使用正绝对值，方向由确认类型表达；零金额不得伪造资金事实。"),
        check("ck_payment_confirmation__currency", "currency_code ~ '^[A-Z]{3}$'", "币种必须为三位大写代码。"),
        check("ck_payment_confirmation__reversal_source", "(confirmation_type = 'RECEIPT' AND reverses_payment_confirmation_id IS NULL) OR (confirmation_type IN ('REVERSAL', 'REFUND') AND reverses_payment_confirmation_id IS NOT NULL)", "来源完整性：撤销和退款必须准确引用原确认，到账不得引用。"),
        check("ck_payment_confirmation__trusted_source", "provider_inbox_id IS NOT NULL OR evidence_submission_id IS NOT NULL", "可信来源：付款确认必须至少引用验签ProviderInbox或经核验EvidenceSubmission；ExternalAction成功本身不足以证明到账。"),
        unique("uk_payment_confirmation__id_contract_revision", ("tenant_id", "payment_confirmation_id", "contract_id", "contract_revision_id"), "准确付款候选键：供付款集合Resolver证明Confirmation属于准确合同版本。"),
    ),
    foreign_keys=(
        entity_fk("payment_confirmation", "contract_id", "contract", "contract", "contract_id", "合同归属：付款确认必须属于准确合同。"),
        entity_fk("payment_confirmation", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：付款确认必须冻结准确合同版本。", suffix="revision"),
        fk("fk_payment_confirmation__revision_contract", ("tenant_id", "contract_revision_id", "contract_id"), "contract", "contract_revision", ("tenant_id", "contract_revision_id", "contract_id"), "付款归属：Confirmation引用的Revision必须属于同一Contract。"),
        entity_fk("payment_confirmation", "external_action_id", "external_action", "external_action", "external_action_id", "外部动作：可选引用准确外部资金动作。", suffix="external_action"),
        entity_fk("payment_confirmation", "provider_inbox_id", "external_action", "provider_inbox", "provider_inbox_id", "Provider来源：可选引用准确可信消息。", suffix="provider_inbox"),
        entity_fk("payment_confirmation", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据来源：可选引用准确EvidenceSubmission。", suffix="evidence"),
        entity_fk("payment_confirmation", "reverses_payment_confirmation_id", "contract", "payment_confirmation", "payment_confirmation_id", "反向事实：撤销或退款必须引用同租户原付款确认。", suffix="reverses"),
        entity_fk("payment_confirmation", "recorded_by_appointment_id", "identity", "appointment", "appointment_id", "记录主体：付款确认必须记录准确任职。", suffix="recorder"),
    ),
)


contract_termination = tenant_table(
    "contract", "contract_termination", "contract_termination_id",
    "合同终止事实：一行单向保存合同取消或执行后终止，并可一次补入退款计算；实际退款仍追加PaymentConfirmation。",
    (
        uuid_col("contract_id", "合同标识：被取消或终止的准确合同。"),
        uuid_col("contract_revision_id", "合同版本标识：取消或终止时适用的准确版本。"),
        uuid_col("contract_execution_id", "合同执行事实标识：执行后终止时必须存在，执行前取消时为空。", nullable=True),
        code_col("termination_kind", "终止类型：CANCELLED表示执行前取消，TERMINATED表示执行后终止。"),
        uuid_col("decision_record_id", "终止决定标识：授权取消或终止的准确DecisionRecord。"),
        uuid_col("evidence_submission_id", "终止证据提交标识：存在正式材料时准确引用。", nullable=True),
        code_col("reason_code", "终止原因：允许列表化的业务原因代码。"),
        text_col("reason_summary", "原因摘要：最小必要且允许列表化的说明，不保存完整案情。"),
        time_col("terminated_at", "终止时间：取消或终止事实生效的可信业务时间。"),
        uuid_col("terminated_by_appointment_id", "终止任职标识：执行授权命令的准确任职。"),
        bigint_col("refund_calculation_minor", "退款计算金额：最小货币单位，尚未计算时为空，不代表已经退款。", nullable=True),
        code_col("refund_currency_code", "退款计算币种：三位大写代码，尚未计算时为空。", length=3, nullable=True),
        digest_col("refund_calculation_digest", "退款计算摘要：覆盖计算输入、规则与结果，尚未计算时为空。", nullable=True),
        time_col("refund_calculated_at", "退款计算时间：Fact Owner完成计算后一次写入。", nullable=True),
        revision_col(),
        time_col("created_at", "创建时间：取消或终止事实首次写入的可信时间。"),
        time_col("changed_at", "变更时间：退款计算槽最近一次受控写入的可信时间。"),
    ),
    constraints=(
        unique("uk_contract_termination__contract", ("tenant_id", "contract_id"), "合同唯一：一份合同最多形成一个取消或终止事实。"),
        enum_check("contract_termination", "termination_kind", ("CANCELLED", "TERMINATED"), "终止类型仅允许执行前取消或执行后终止。"),
        check("ck_contract_termination__execution_shape", "(termination_kind = 'CANCELLED' AND contract_execution_id IS NULL) OR (termination_kind = 'TERMINATED' AND contract_execution_id IS NOT NULL)", "执行关系：取消发生在执行前，终止必须引用执行事实。"),
        check("ck_contract_termination__refund_complete", "(refund_calculation_minor IS NULL AND refund_currency_code IS NULL AND refund_calculation_digest IS NULL AND refund_calculated_at IS NULL) OR (refund_calculation_minor IS NOT NULL AND refund_calculation_minor >= 0 AND refund_currency_code ~ '^[A-Z]{3}$' AND refund_calculation_digest IS NOT NULL AND refund_calculated_at IS NOT NULL)", "退款计算完整性：金额、币种、摘要和时间必须一次性全部写入或全部为空。"),
        check("ck_contract_termination__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
    ),
    foreign_keys=(
        entity_fk("contract_termination", "contract_id", "contract", "contract", "contract_id", "合同归属：终止事实必须属于准确合同。"),
        entity_fk("contract_termination", "contract_revision_id", "contract", "contract_revision", "contract_revision_id", "版本归属：终止事实必须冻结准确合同版本。", suffix="revision"),
        entity_fk("contract_termination", "contract_execution_id", "contract", "contract_execution", "contract_execution_id", "执行来源：执行后终止必须引用准确执行事实。", suffix="execution"),
        fk("fk_contract_termination__revision_contract", ("tenant_id", "contract_revision_id", "contract_id"), "contract", "contract_revision", ("tenant_id", "contract_revision_id", "contract_id"), "终止版本归属：取消或终止采用的Revision必须属于同一Contract。"),
        fk("fk_contract_termination__execution_path", ("tenant_id", "contract_execution_id", "contract_id", "contract_revision_id"), "contract", "contract_execution", ("tenant_id", "contract_execution_id", "contract_id", "contract_revision_id"), "执行后终止路径：Execution、Contract和Revision必须完全一致；CANCELLED时空值按MATCH SIMPLE跳过。"),
        entity_fk("contract_termination", "decision_record_id", "responsibility", "decision_record", "decision_record_id", "决定依据：终止事实必须引用准确授权决定。", suffix="decision"),
        entity_fk("contract_termination", "evidence_submission_id", "evidence", "evidence_submission", "evidence_submission_id", "证据来源：可选引用准确终止材料。", suffix="evidence"),
        entity_fk("contract_termination", "terminated_by_appointment_id", "identity", "appointment", "appointment_id", "执行主体：终止事实必须记录准确任职。", suffix="terminator"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("refund_calculation_minor", "refund_currency_code", "refund_calculation_digest", "refund_calculated_at", "revision", "changed_at"),
    write_once_columns=("refund_calculation_minor", "refund_currency_code", "refund_calculation_digest", "refund_calculated_at"),
)


CONTRACT_TABLES = (
    contract, contract_revision, contract_participation, contract_fee_term,
    payment_gate, signature_plan, contract_signature, contract_execution,
    payment_confirmation, contract_termination,
)


transfer_request = tenant_table(
    "transfer", "transfer_request", "transfer_request_id",
    "转案请求锚点：一行对应一次由准确DealActivated来源发起的组织间转案，只保存一次性接收和MatterRef槽，不保存通用状态或当前快照指针。",
    (
        uuid_col("opportunity_id", "来源商机标识：转案所承接的唯一法律需求。"),
        uuid_col("contract_id", "来源合同标识：已经形成DealActivated的准确合同。"),
        uuid_col("contract_execution_id", "合同执行事实标识：转案必须以准确执行事实为前提。"),
        time_col("deal_activated_at", "交易激活时间：来源合同DealActivated槽的准确时间。"),
        digest_col("deal_activation_digest", "交易激活摘要：冻结激活依据、合同和版本的32字节摘要。"),
        uuid_col("from_organization_unit_id", "转出组织标识：发起转案的准确组织单元。"),
        uuid_col("to_organization_unit_id", "接收组织标识：负责案管审查和Matter接收的准确组织单元。"),
        code_col("transfer_purpose_code", "转案目的：静态注册的业务目的代码。"),
        code_col("proposed_matter_type_code", "拟建Matter类型：首次请求时冻结的静态类型。"),
        code_col("proposed_capability_pack_code", "拟建能力包代码：接收方应具备的静态能力包。"),
        int_col("proposed_capability_pack_version", "拟建能力包版本：解释Matter能力的正整数版本。"),
        uuid_col("accepted_snapshot_id", "接收快照标识：ACCEPT事务中一次写入的当前叶Snapshot。", nullable=True),
        uuid_col("accept_decision_record_id", "接收决定标识：ACCEPT事务中一次写入的准确DecisionRecord。", nullable=True),
        uuid_col("matter_id", "Matter稳定标识：接收成功时一次生成，MVP不建立Matter表。", nullable=True),
        code_col("matter_no", "Matter编号：接收成功时一次生成的租户内稳定编号。", length=80, nullable=True),
        code_col("matter_type_code", "Matter类型：接收成功时冻结在MatterRef中的静态类型。", nullable=True),
        code_col("matter_capability_pack_code", "Matter能力包代码：接收成功时冻结的准确能力包。", nullable=True),
        int_col("matter_capability_pack_version", "Matter能力包版本：接收成功时冻结的正整数版本。", nullable=True),
        time_col("matter_created_at", "Matter创建时间：ACCEPT与MatterCreated同事务提交的可信时间。", nullable=True),
        revision_col(),
        uuid_col("created_by_appointment_id", "创建任职标识：发起转案请求的准确任职。"),
        time_col("created_at", "创建时间：转案请求锚点建立的可信时间。"),
        time_col("changed_at", "变更时间：一次性接收槽最近写入的可信时间。"),
    ),
    constraints=(
        unique("uk_transfer_request__deal_activation", ("tenant_id", "contract_id", "deal_activation_digest"), "来源唯一：同一合同的准确DealActivated事实只能形成一个转案请求。"),
        check("ck_transfer_request__different_orgs", "from_organization_unit_id <> to_organization_unit_id", "组织边界：转出组织和接收组织不得相同。"),
        check("ck_transfer_request__capability_version", "proposed_capability_pack_version > 0", "拟建能力包版本必须为正数。"),
        check("ck_transfer_request__accept_complete", "(accepted_snapshot_id IS NULL AND accept_decision_record_id IS NULL AND matter_id IS NULL AND matter_no IS NULL AND matter_type_code IS NULL AND matter_capability_pack_code IS NULL AND matter_capability_pack_version IS NULL AND matter_created_at IS NULL) OR (accepted_snapshot_id IS NOT NULL AND accept_decision_record_id IS NOT NULL AND matter_id IS NOT NULL AND matter_no IS NOT NULL AND matter_type_code IS NOT NULL AND matter_capability_pack_code IS NOT NULL AND matter_capability_pack_version > 0 AND matter_created_at IS NOT NULL)", "原子接收：acceptedSnapshot、acceptDecision和完整MatterRef必须全部为空或一次性全部写入。"),
        check("ck_transfer_request__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
    ),
    indexes=(
        index("ux_transfer_request__matter_id", ("tenant_id", "matter_id"), "Matter标识唯一：已接收请求生成的matterId在租户内唯一。", unique_=True, where="matter_id IS NOT NULL"),
        index("ux_transfer_request__matter_no", ("tenant_id", "matter_no"), "Matter编号唯一：已接收请求生成的matterNo在租户内唯一。", unique_=True, where="matter_no IS NOT NULL"),
    ),
    foreign_keys=(
        entity_fk("transfer_request", "opportunity_id", "opportunity", "opportunity", "opportunity_id", "销售来源：转案请求必须引用准确商机。"),
        entity_fk("transfer_request", "contract_id", "contract", "contract", "contract_id", "合同来源：转案请求必须引用准确合同。"),
        entity_fk("transfer_request", "contract_execution_id", "contract", "contract_execution", "contract_execution_id", "执行来源：转案请求必须引用准确合同执行事实。", suffix="contract_execution"),
        entity_fk("transfer_request", "from_organization_unit_id", "identity", "organization_unit", "organization_unit_id", "转出组织：必须是同租户当前组织树中的准确组织单元。", suffix="from_org"),
        entity_fk("transfer_request", "to_organization_unit_id", "identity", "organization_unit", "organization_unit_id", "接收组织：必须是同租户当前组织树中的准确组织单元。", suffix="to_org"),
        entity_fk("transfer_request", "accepted_snapshot_id", "transfer", "transfer_snapshot", "transfer_snapshot_id", "接收快照：必须引用本请求当前叶Snapshot，归属由延迟守卫复验。", suffix="accepted_snapshot", deferrable=True, initially_deferred=True),
        entity_fk("transfer_request", "accept_decision_record_id", "responsibility", "decision_record", "decision_record_id", "接收决定：必须引用准确ACCEPT DecisionRecord。", suffix="accept_decision"),
        entity_fk("transfer_request", "created_by_appointment_id", "identity", "appointment", "appointment_id", "创建主体：转案请求必须记录准确任职。", suffix="creator"),
        fk("fk_transfer_request__contract_path", ("tenant_id", "contract_id", "opportunity_id", "contract_execution_id"), "contract", "contract", ("tenant_id", "contract_id", "opportunity_id", "contract_execution_id"), "转案合同主链：Opportunity、Contract及Execution必须来自同一已执行合同锚点。"),
    ),
    update_policy="CONTROLLED",
    mutable_columns=(
        "accepted_snapshot_id", "accept_decision_record_id", "matter_id", "matter_no",
        "matter_type_code", "matter_capability_pack_code", "matter_capability_pack_version",
        "matter_created_at", "revision", "changed_at",
    ),
    write_once_columns=(
        "accepted_snapshot_id", "accept_decision_record_id", "matter_id", "matter_no",
        "matter_type_code", "matter_capability_pack_code", "matter_capability_pack_version",
        "matter_created_at",
    ),
)


transfer_snapshot = tenant_table(
    "transfer", "transfer_snapshot", "transfer_snapshot_id",
    "转案快照：一行表示首次提交或补正后的完整不可变版本，准确绑定一张提交Task、确认草案、材料合同、EvidenceRef集合和独立PRE_TRANSFER审查。",
    (
        uuid_col("transfer_request_id", "转案请求标识：快照所属的一次转案请求。"),
        int_col("snapshot_no", "快照序号：从一开始沿单后继链递增。"),
        uuid_col("predecessor_snapshot_id", "前序快照标识：首次提交为空，补正提交准确引用直接前序。", nullable=True),
        uuid_col("submission_task_occurrence_id", "提交Task标识：该Snapshot完成的唯一SUBMIT或RESUBMIT责任卡。"),
        uuid_col("confirmed_action_draft_id", "确认草案标识：本次提交使用的准确候选输入。"),
        digest_col("action_draft_digest", "草案摘要：确认时冻结的ActionDraft准确内容摘要。"),
        digest_col("contract_context_digest", "合同上下文摘要：冻结合同、版本、执行和DealActivated来源。"),
        digest_col("legal_need_context_digest", "法律需求上下文摘要：冻结来源商机的准确法律需求。"),
        code_col("material_contract_code", "材料合同代码：静态注册的完整材料结构。"),
        int_col("material_contract_version", "材料合同版本：解释本快照材料范围的正整数版本。"),
        col("evidence_submission_ids", "uuid[]", "EvidenceRef集合：按UUID字节升序、去重保存的准确EvidenceSubmission标识数组，由同租户Resolver逐项复验。"),
        digest_col("evidence_set_digest", "EvidenceRef集合摘要：覆盖排序后全部EvidenceSubmission标识及用途。"),
        uuid_col("pre_transfer_review_id", "转案前冲突审查标识：为本快照独立创建的PRE_TRANSFER审查。"),
        digest_col("pre_transfer_scope_hash", "转案前审查范围摘要：必须与所引用Review的准确scopeHash一致。"),
        uuid_col("previous_return_decision_record_id", "前序RETURN决定标识：补正快照必须引用，首次提交为空。", nullable=True),
        digest_col("previous_return_items_digest", "前序退回项集合摘要：补正快照覆盖全部ReturnItem时必填。", nullable=True),
        digest_col("snapshot_digest", "快照摘要：覆盖全部上下文、材料、EvidenceRef、审查和前序退回信息。"),
        uuid_col("submitted_by_appointment_id", "提交任职标识：执行提交主命令的准确任职。"),
        time_col("submitted_at", "提交时间：快照与Task完成事实同事务封存的可信时间。"),
    ),
    constraints=(
        unique("uk_transfer_snapshot__request_no", ("tenant_id", "transfer_request_id", "snapshot_no"), "快照唯一：同一转案请求内快照序号不得重复。"),
        unique("uk_transfer_snapshot__predecessor", ("tenant_id", "predecessor_snapshot_id"), "单后继链：一个快照最多只有一个补正后继。"),
        unique("uk_transfer_snapshot__submission_task", ("tenant_id", "submission_task_occurrence_id"), "单完成事实：一张提交Task只能由一个TransferSnapshot完成。"),
        unique("uk_transfer_snapshot__confirmed_draft", ("tenant_id", "confirmed_action_draft_id"), "草案唯一：一份确认草案只能形成一个转案快照。"),
        unique("uk_transfer_snapshot__id_request", ("tenant_id", "transfer_snapshot_id", "transfer_request_id"), "准确快照候选键：供退回项证明reviewedSnapshot与TransferRequest属于同一条转案链。"),
        check("ck_transfer_snapshot__snapshot_no", "snapshot_no > 0", "快照序号必须为正数。"),
        check("ck_transfer_snapshot__material_version", "material_contract_version > 0", "材料合同版本必须为正数。"),
        check("ck_transfer_snapshot__evidence_nonempty", "cardinality(evidence_submission_ids) > 0", "材料完整性：每个转案快照至少包含一个准确EvidenceRef。"),
        check("ck_transfer_snapshot__chain_shape", "(snapshot_no = 1 AND predecessor_snapshot_id IS NULL AND previous_return_decision_record_id IS NULL AND previous_return_items_digest IS NULL) OR (snapshot_no > 1 AND predecessor_snapshot_id IS NOT NULL AND previous_return_decision_record_id IS NOT NULL AND previous_return_items_digest IS NOT NULL)", "补正链：首次提交无前序和RETURN依据，补正必须同时引用前序快照、RETURN决定和完整退回项集合摘要。"),
    ),
    foreign_keys=(
        entity_fk("transfer_snapshot", "transfer_request_id", "transfer", "transfer_request", "transfer_request_id", "请求归属：快照必须属于准确转案请求。"),
        entity_fk("transfer_snapshot", "predecessor_snapshot_id", "transfer", "transfer_snapshot", "transfer_snapshot_id", "补正链：补正快照必须引用同租户直接前序。", suffix="predecessor"),
        entity_fk("transfer_snapshot", "submission_task_occurrence_id", "responsibility", "task_occurrence", "task_occurrence_id", "责任完成：快照必须完成准确提交Task。", suffix="submission_task"),
        entity_fk("transfer_snapshot", "confirmed_action_draft_id", "responsibility", "action_draft", "action_draft_id", "输入来源：快照必须引用准确确认草案。", suffix="action_draft"),
        entity_fk("transfer_snapshot", "pre_transfer_review_id", "conflict", "conflict_review", "conflict_review_id", "审查来源：快照必须引用独立PRE_TRANSFER审查。", suffix="pre_transfer_review"),
        entity_fk("transfer_snapshot", "previous_return_decision_record_id", "responsibility", "decision_record", "decision_record_id", "补正依据：后续快照必须引用前序RETURN决定。", suffix="previous_return_decision"),
        entity_fk("transfer_snapshot", "submitted_by_appointment_id", "identity", "appointment", "appointment_id", "提交主体：快照必须记录准确任职。", suffix="submitter"),
    ),
)


return_target = typed_ref("required_target", "退回项要求补正的准确目标")

transfer_return_item = tenant_table(
    "transfer", "transfer_return_item", "transfer_return_item_id",
    "转案退回项：一行保存针对准确已审快照和RETURN决定的一项不可变补正要求，不设置OPEN或RESOLVED状态。",
    (
        uuid_col("transfer_request_id", "转案请求标识：退回项所属请求，用于同链一致性复验。"),
        uuid_col("reviewed_snapshot_id", "已审快照标识：RETURN决定实际审查的准确Snapshot。"),
        uuid_col("return_decision_record_id", "RETURN决定标识：与本退回项同事务创建的准确DecisionRecord。"),
        int_col("item_no", "退回项序号：在一次RETURN决定内稳定排序。"),
        code_col("requirement_code", "要求代码：允许列表化的缺失或不符合项类型。"),
        int_col("requirement_contract_version", "要求合同版本：解释目标和补正指令的正整数版本。"),
        code_col("reason_code", "原因代码：允许列表化的退回原因。"),
        text_col("correction_instruction", "补正指令：最小必要的结构化文字说明，不保存文档正文。"),
        code_col("required_evidence_purpose_code", "所需证据用途：要求新增Evidence时使用的静态用途代码。", nullable=True),
        digest_col("item_digest", "退回项摘要：覆盖要求、目标、版本、原因和补正指令。"),
        time_col("created_at", "创建时间：与RETURN决定同事务写入的可信时间。"),
    ),
    constraints=(
        unique("uk_transfer_return_item__decision_no", ("tenant_id", "return_decision_record_id", "item_no"), "退回项唯一：一次RETURN决定内项目序号不得重复。"),
        check("ck_transfer_return_item__item_no", "item_no > 0", "退回项序号必须为正数。"),
        check("ck_transfer_return_item__contract_version", "requirement_contract_version > 0", "要求合同版本必须为正数。"),
    ),
    indexes=(index("ix_transfer_return_item__snapshot", ("tenant_id", "reviewed_snapshot_id", "item_no"), "审查查询：按已审快照读取全部不可变退回项。"),),
    foreign_keys=(
        entity_fk("transfer_return_item", "transfer_request_id", "transfer", "transfer_request", "transfer_request_id", "请求归属：退回项必须属于准确转案请求。"),
        entity_fk("transfer_return_item", "reviewed_snapshot_id", "transfer", "transfer_snapshot", "transfer_snapshot_id", "审查对象：退回项必须引用准确已审快照。", suffix="reviewed_snapshot"),
        fk("fk_transfer_return_item__snapshot_request", ("tenant_id", "reviewed_snapshot_id", "transfer_request_id"), "transfer", "transfer_snapshot", ("tenant_id", "transfer_snapshot_id", "transfer_request_id"), "退回路径：reviewedSnapshot必须属于本ReturnItem冻结的同一TransferRequest。"),
        entity_fk("transfer_return_item", "return_decision_record_id", "responsibility", "decision_record", "decision_record_id", "决定归属：退回项必须引用准确RETURN决定。", suffix="return_decision"),
    ),
    typed_references=(return_target,),
)


TRANSFER_TABLES = (transfer_request, transfer_snapshot, transfer_return_item)


deployment_state_columns = (
    code_col("deployment_state_key", "单行主键：固定为PRIMARY，用于定位唯一部署门禁。", length=16),
    code_col("operating_mode", "运行模式：ACTIVE、MAINTENANCE或BLOCKED。", length=16),
    digest_col("active_release_digest", "当前发布摘要：运行中应用发布物的32字节规范摘要。"),
    digest_col("active_manifest_hash", "当前部署清单摘要：类型、路由、Schema和策略清单的32字节摘要。"),
    code_col("schema_contract_version", "Schema合同版本：与本次52＋2字段合同对应的静态版本。", length=40),
    revision_col(),
    time_col("changed_at", "变更时间：部署门禁最近一次受控切换的可信时间。"),
)

deployment_state = Table(
    schema="platform_meta",
    name="deployment_state",
    id_column="deployment_state_key",
    columns=deployment_state_columns,
    primary_key=("deployment_state_key",),
    primary_key_comment="主键：固定PRIMARY确保部署门禁始终只有一行。",
    comment="部署门禁：唯一一行记录当前运行模式、发布摘要和Schema合同版本，不保存业务事实。",
    constraints=(
        check("ck_deployment_state__singleton", "deployment_state_key = 'PRIMARY'", "单行约束：门禁主键只能为PRIMARY。"),
        enum_check("deployment_state", "operating_mode", ("ACTIVE", "MAINTENANCE", "BLOCKED"), "运行模式只允许正常、维护或阻断。"),
        check("ck_deployment_state__revision_nonnegative", "revision >= 0", "CAS修订号不得为负数。"),
        *tuple(
            check(
                f"ck_deployment_state__{column.name}_length",
                f"octet_length({column.name}) = 32",
                f"摘要格式：{column.name}必须为32字节规范摘要。",
            )
            for column in deployment_state_columns
            if column.byte_length == 32
        ),
    ),
    update_policy="CONTROLLED",
    mutable_columns=("operating_mode", "active_release_digest", "active_manifest_hash", "schema_contract_version", "revision", "changed_at"),
)


SCHEMAS = (
    Schema("contract", SCHEMA_CONTRACT, CONTRACT_TABLES),
    Schema("transfer", SCHEMA_TRANSFER, TRANSFER_TABLES),
    Schema("platform_meta", SCHEMA_PLATFORM, (deployment_state,)),
)
