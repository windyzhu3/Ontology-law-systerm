-- 由静态52＋2字段合同生成；禁止手工编辑。

CREATE SCHEMA IF NOT EXISTS identity;
COMMENT ON SCHEMA identity IS '身份域：保存租户、身份、组织、任职及授权锚点；不保存凭据，也不替代命令时动态授权复验。';

CREATE SCHEMA IF NOT EXISTS audit;
COMMENT ON SCHEMA audit IS '审计域：只追加不可变审计事实，以准确类型化引用冻结对象、授权依据及更正目标。';

CREATE SCHEMA IF NOT EXISTS responsibility;
COMMENT ON SCHEMA responsibility IS '责任域：保存待办责任实例及其不可变决策、等待回执和唯一行动草案；不建设通用工作流或作业系统。';

CREATE SCHEMA IF NOT EXISTS execution;
COMMENT ON SCHEMA execution IS '执行域：保存永久命令占位、不可变终态回执、准确事实事件及带租约围栏的投递队列。';

CREATE SCHEMA IF NOT EXISTS external_action;
COMMENT ON SCHEMA external_action IS '外部动作域：保存一次性外部效果尝试、派发或探测队列，以及验签后不可变的Provider入站事件指纹。';

CREATE SCHEMA IF NOT EXISTS evidence;
COMMENT ON SCHEMA evidence IS '证据域：保存单文件上传会话、固定对象版本、不可变提交与固定目标用途绑定组成的严格一对一物理链。';

CREATE SCHEMA IF NOT EXISTS party;
COMMENT ON SCHEMA party IS '主体域：保存跨业务流程共享的当前态主体锚点、受保护主标识与一跳合并关系。';

CREATE SCHEMA IF NOT EXISTS lead;
COMMENT ON SCHEMA lead IS '销售接入域：保存不可覆盖Lead、追加分派链与追加联系结果，不承载机会、报价或冲突决定。';

CREATE SCHEMA IF NOT EXISTS opportunity;
COMMENT ON SCHEMA opportunity IS '机会与报价域：保存单项法律需求、冻结参与角色、追加进展及不可变报价版本包、逐收件人Issue和Response。';

CREATE SCHEMA IF NOT EXISTS conflict;
COMMENT ON SCHEMA conflict IS '冲突审查域：冻结PRE_CONTRACT或PRE_TRANSFER完整范围、规则与语料，保存不可变参与方和Finding；决定统一归Responsibility。';

CREATE SCHEMA IF NOT EXISTS contract;
COMMENT ON SCHEMA contract IS '合同事实域：保存版本化合同包、准确签署、执行、付款、激活和终止事实。';

CREATE SCHEMA IF NOT EXISTS transfer;
COMMENT ON SCHEMA transfer IS '转案事实域：保存转案请求锚点、不可变提交快照和逐项退回要求。';

CREATE SCHEMA IF NOT EXISTS platform_meta;
COMMENT ON SCHEMA platform_meta IS '平台元数据域：仅保存部署门禁；Flyway历史表由Flyway独占管理。';
