# LINUX_HUMAN_INTAKE_BINDING_V1

已确认 Linux v22 初始化设计的具名增量，接续 `R2_LEAD_INTAKE_SOURCES_V1`；不改 R1 冻结合同、捕获正文、来源事实或数据库 schema。

部署可信配置 `human-intake-bindings` 绑定 tenant UUID、principal UUID 和已注册 sourceAccountCode。一个租户内每个 principal 只绑定一个来源，每个来源只有一个 principal；重复或未注册来源拒绝启动。任职仍决定授权和组织范围，绑定本身不赋权，不合并多个任职权限。未配置的租户保留旧模式；配置后无绑定的人类用户不得捕获。SERVICE 保留原注册来源与认证要求。

`GET /api/v1/leads/intake-sources` 的封闭 `LeadIntakeSourcesV1` 新增可选 `sourceSelection`，仅允许 `BOUND_TO_PRINCIPAL` 或 `SELECTABLE`。服务器对已配置租户明确返回前者，目录按本人来源及当前捕获授权过滤，数量只能为 0 或 1；未配置租户保留 `{sources:[...]}` 原响应。客户端缺字段按 `SELECTABLE` 解析，拒绝未知字段、未知模式以及多来源的绑定响应。原来源条目的六字段、0..50 旧目录边界、Bearer、本人任职、no-store 和错误合同不变。

OpenAPI envelope 标记 `x-contract-extension: LINUX_HUMAN_INTAKE_BINDING_V1`。`scripts/baseline/linux_human_intake_contract.py` 精确验证完整增量后投影为旧 schema，随后由原 intake/frozen 门禁继续校验；标记与字段必须同时出现，不能借此放宽旧来源字段、目录边界或权限合同。历史未启用该增量的合同仍可核对。

绑定模式显示只读“来源：<本人来源>”，手工、CSV 和 XLSX 使用同一元数据，不从表格或用户输入推断账号。捕获 Handler 在原回执判断之后、最终写入之前核对实际 actor 的绑定；不改写请求或旧回执。来源撤权、任职停用仍由现有事务授权阻断。

退出和切换账号/任职沿用会话 epoch 与 actorScopeKey：放弃旧表单、文件预览及旧响应，不自动重发；保留原恢复标记，由原账号和合法任职按现有规则查询原回执。来源变化不追写旧 Lead，也不把未决正文归给新账号。
