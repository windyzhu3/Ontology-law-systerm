# R1 回执恢复授权边界：最小合同修订设计

日期：2026-09-08。方案方向已获用户批准；本文件将批准的五项原则细化为供审阅的准确规则。状态：DESIGN REVIEW。本文件不是活动合同，不修改当前基线或声明 Task8 已实现。

原实现基点：`d12ffbf875710cac22793311600c5e6665744cfc`。原计划：[R1 business closure plan](../plans/2026-09-05-r1-business-closure-plan.md) 的 Task8。现有四个未提交认证文件及首个 HTTP RED/GREEN 证据保留；本设计不把它们当作完整认证验收。

## 1. 问题与选择

Slot 只保存 scope/payload 摘要；REJECTED Receipt 不允许 resultFact，且被拒绝的新建 Lead 已回滚、没有成功 Event。`GET /api/v1/commands/{commandId}/receipt` 不能从 commandId 反推出原 Actor、来源自然键及准确授权对象。仅有有效登录或同组织 LEAD_CAPTURE 权限不构成原回执读取权限。

现有 `audit.audit_entry_classified_v` 的 QUERY SELECT 也不是读取授权：运行时合同和 V830 注释要求实时四轴授权与审计查询自审计。不能为解决上述信息依赖，直接把该视图变成无门禁授权索引；也不能解析旧 `authorizationEvidence` 文本充当稳定恢复协议。

| 方案 | 影响 | 决定 |
|---|---|---|
| 原命令 Audit 增加具名恢复元数据，严格限定内部元数据读取，再当前授权与披露审计 | 改静态审计/回执语义，不改表、GRANT 或 HTTP shape | 已批准方向，本文件细化 |
| Slot/Receipt 增加持久授权字段或新表 | 改物理合同、迁移和生成物 | 不采用 |
| 只允许原请求同 key 重试，取消按 commandId 查询 | 影响冻结 GET 与客户端恢复流程 | 不采用；仅保留为旧记录的安全退路 |

## 2. 不变的范围

一个 SPA、一份 OpenAPI、一个 Jar；`ols.runtime-role` / `OLS_RUNTIME_ROLE` 的 api/worker 互斥规则不变。保持 16 operation、11 public Bearer / 5 internal mTLS、全部已有请求/响应 DTO 及错误码集合。保持 13 Schema、52 应用表加 2 技术表、`52-plus-2-v1.2`、V001–V860、manifest、字段合同、数据库 GRANT、jOOQ 生成范围及 14 个 EventType。

不增加业务 Command、authority code、权限槽、通用审计查询入口、管理页、凭据签发、Provider、AI 或 R2+。不变更命令成功/拒绝的 Receipt、Audit、Event、Outbox 基数，不复用读取审计作为业务事实。原 POST/PUT 同 key 重放及冲突的零增量保持。

本次仅为 `getCommandReceipt` 冻结专用读取规则。公共 Bearer 不因这项修订获得内部恢复命令权限；两个内部 recovery 的响应丢失继续通过原 mTLS 请求和原 key 恢复，不新增 mTLS Receipt GET，也不让公共 GET 读取内部 recovery 回执。

## 3. 命令原子写入的恢复元数据

九种公共写命令（capture、save Draft、七个人工 Task 主命令）的新终态 Audit 使用 `R1_COMMAND_AUDIT_V2` / `summary_schema_version=2`。仍是一条同事务命令 Audit；SUCCEEDED、NO_CHANGE、post-slot REJECTED 都必须写入。pre-slot 拒绝仍零写。两个内部 recovery 的命令 Audit 不因本修订升级。

摘要恰含原有 `result`、`authorizationEvidence` 和新增 `receiptRecovery`。历史证据文本仅供其原有用途，不得作为恢复数据解析器的输入。`receiptRecovery` 是封闭结构：

| 字段 | 准确规则 |
|---|---|
| profile | `R1_COMMAND_RECEIPT_RECOVERY_V1` |
| scope | 原 `CommandScope.canonical()` 表达的 JSON 对象，不是 JSON 文本字符串；四种已冻结 scope profile 中这里只允许 capture、draft、primary-task 三种 |
| binding | 下表按 commandType 确定的唯一结构，拒绝额外字段 |

| commandType | binding 恰含字段 |
|---|---|
| CAPTURE_LEAD | `kind="CAPTURE"`；sourceAccountCode/sourceRecordKeyDigest 来自 scope，准确原组织 Subject 来自原 Audit 固定列，不重复持久化 |
| SAVE_ACTION_DRAFT | `kind="DRAFT"`, `lead`（准确 Lead revision selector）, `taskRevision`（原请求授权时 revision）, `draft`（原绑定无 draftId/draftRevision 时为 null，否则为准确 action_draft revision selector） |
| 七个人工 Task 命令 | `kind="TASK"`, `evidence`；除 RECORD_CONTACT_RESULT 外 evidence 必须为 null，后者没有 Evidence 引用时亦为 null，否则恰为 `submission`（hash selector）和 `binding`（revision selector） |

`lead` selector 恰为 `{type:"lead.lead",id,revision}`；非空 `draft` 恰为 `{type:"responsibility.action_draft",id,revision}`；Evidence 的 submission 恰为 `{type:"evidence.evidence_submission",id,hash}`，binding 恰为 `{type:"evidence.evidence_binding",id,revision}`，不放 null 占位字段。UUID 小写连字符、revision 为 JSON 安全整数、hash 为 43 字符无 padding base64url。所有对象拒绝重复键、未知字段及错误标量类型，含 scope 的整个恢复元数据规范 UTF-8 长度不得超过 8192 字节。元数据必须由已解析的服务端 Context 构造；调用方不能提供它。

scope 仍按既有 RFC8785/JCS UTF-8 SHA-256 算法与 Slot 的 `command_scope_digest` 比较，不能修改既有 scope 成员、排序或摘要算法。Audit 与 Slot/Receipt 的 tenant、commandId、commandType、envelope/kind、终态 result 必须一致且唯一。原 Actor Principal/Appointment、代办二元组、原授权 Subject 和组织 Scope 使用已有 Audit 固定列；不再复制一套身份或历史 Grant 到 JSON。

capture 的 `sourceRecordKeyDigest` 是已有来源记录自然键 HMAC，确为拒绝后重建原来源范围并检查后来出现的同自然键 Lead 所必需；不是原始 sourceRecordKey，也不是联系方式 HMAC、外部登录 subject HMAC 或凭据。本设计只允许这个准确的来源自然键 HMAC 出现在内部恢复 scope 中，按受限元数据处理，绝不进入响应、日志、Trace、读取审计摘要或前端。禁止业务 payload、Draft values、法律需求、理由正文、联系方式及其密文/HMAC、Token、Secret、外部 subject 原文或完整请求。

新增元数据不是授权事实或业务事实，不替代 Fact Owner。它只提供重建候选所需的 selector。Runtime 应在业务 savepoint 前取得已验证 Context 的不可变元数据；业务回滚后的 REJECTED Audit 仍可保存它，但不能保存被回滚的新 Fact 作为实际存在事实。元数据生成/校验/写 Audit/提交任一技术失败均整体回滚，不降级写缺失元数据的 V2。

## 4. 审计元数据的唯一内部读取例外

为打破“先知道 Subject 才能授权、授权后才可取得 Subject”的依赖，具名允许 `R1_RECEIPT_RECOVERY_METADATA_LOOKUP_V1`：只由 API 内部回执服务调用 Audit Owner 窄端口，在 QUERY 能力下查询既有 classified view。该用途在取得完整业务授权前可以读取下列最小元数据，且这一步不递归追加审计查询 Audit。此例外须由新 ADR 对运行时合同及 V830 注释作具名语义 supersession；不重写历史迁移或 render/manifest 字节。

前置条件：Bearer 验证成功且唯一绑定准确 Tenant/Principal/Appointment/kind；用新鲜数据库时间证明 Principal、Appointment、组织链有效；代办二元组仅来自可信 ActorContext，不能由 query/header/body 选择。先取得 Tenant 业务共享锁，再取得 identity 共享锁，连接为 READ COMMITTED。元数据查找不是对 Grant 或业务可见性的提前放行。

SQL 必须同时限定可信 Tenant、准确 commandId、原 actor Principal/Appointment，以及 NULL-safe 完全一致的 on-behalf 二元组；只接受原 EVENT 命令 Audit，排除 CORRECTION 和读取 Audit。先用 limit 2 发现不存在/重复原命令 Audit，不能按任意 ID 浏览或分页。只返回固定匹配列、summary schema/version、原 Subject/Scope、command type/result、`receiptRecovery` 子对象和核验摘要所需值；不得将整个 change_summary、authorizationEvidence、其他审计字段或行对象交出 Audit Owner。摘要完整性校验在 Audit Owner 内完成，不能为重算而披露其他字段。

该例外仅为原 Actor 的原命令恢复候选，不能用于 Workbench、报告、导出、后台浏览、其他人的回执、内部 recovery 或 Worker；不允许直接读 audit 基表、增加数据库权限、读取证书或认证 HMAC。找不到匹配项不能再退回无 Actor 条件的查询。

## 5. 当前授权、稳定性和响应顺序

在同一持锁事务中核验唯一 Slot/Receipt 与恢复元数据，再由对应 Owner 窄端口按现行九命令注册表重建并复核业务范围：

- capture：准确 sourceAccountCode 当前仍在受信策略中；SERVICE 的准确来源绑定仍允许该账户；由该账户当前政策解析组织，不能用历史 Grant scope 或服务任职组织代替。当前解析的组织 Subject 必须与原 Audit Subject 的 type/id/revision 完全一致，且 Scope organization ID 一致；更换组织或原组织 revision 变化失败关闭，不把原回执迁移到新来源政策。组织祖先链、Grant 与有效期仍当前重算。用原自然键摘要找当前 Lead；若现在已存在，必须检查其准确 Tenant/当前 selector 与 LEAD_CAPTURE DENY，即使原命令曾 REJECTED 且当时没有 Lead。
- Draft/Task：用原 taskId 读取真实 Task、原持久 Lead subject 与当前 Lead、Owner Appointment/Principal/组织；匹配命令类型与实际 Owner 或同一合法一跳代办身份。Draft 的非空原 selector 必须仍绑定该 Task 的准确 Draft ID，原 revision 不得大于当前 revision；原 selector 非空而当前 Draft 不存在必须拒绝，不伪造另一 Draft 替代。无论原 selector 是否为 null，当前 Draft 如存在，其 task/action/schema/version 绑定必须准确；唯一 Task→Draft 身份和单调 revision 由原物理守卫保持。当前相关业务 Subject 的 DENY 不能遗漏。Contact 已记录 Evidence 引用时，还须复核准确 Submission/Binding、未撤回状态及 Task、Lead、Submission、Binding 四个准确 Subject 的 DENY，不因只返回不透明 factRef 而跳过。
- 当前 Grant/Delegation/组织链/有效期/撤销和 DENY 必须完整重算，一条完整路径独立成立，不能使用历史 Audit 的 ALLOW、历史 Grant ID 或泛化组织权限替代。
- 这是终态回执读取，不重新执行命令。Task 已 DONE、Draft 已 CONFIRMED、等待已恢复、旧输入修订已被原命令推进都不能被当作新命令 eligibility 来拒绝；但当前授权、来源绑定与真实 Fact 一致性仍必须成立。历史不可变 selector 与当前授权 selector 分别校验，不把旧 revision 假装成当前行。

特别区分被拒绝的尝试输入与已解析的授权依据：scope 中的 attempted assignee/candidate/party/causal selector 通过原 scopeDigest 验证完整性，不能要求这些尝试值现在变成有效候选或能够成功执行业务。因候选 stale、assignee inactive、草稿确认条件或其他 NEW-command eligibility 被拒绝的终态，在准确 Task/Lead/Owner 当前授权仍成立时必须可以读取。不能为了查 REJECTED 再跑 Handler.validateBeforeWork。只有由授权协议实际采用的 Subject（含已通过引用解析的 Evidence pair）需要上述当前权限和绑定复核；不能凭尝试输入读取额外候选正文。

最终决策在全部共享锁下使用新鲜 `clock_timestamp()` 重读当前身份/权限及 Owner 依赖。身份或业务写者遵守现有配对锁。评价后自然到期至提交/传输间的既有时间边界保持，不承诺客户端收到字节时权限仍有效。禁止提交后再查询另一个版本；不缓存授权或回执读取结果，不新增持久 proof。

只有当前授权通过后，才在同一事务切换 AUDIT、追加本次回执披露审计；全部审计提交确认成功后才允许交给 HTTP 序列化。任何失败不得先返回 Receipt、resultFact、receiptRef 或成功 header。HTTP 200 body 逐字段等于原不可变回执投影，不创建新的“查询回执”或改写原 outcome。公开 factRef 的确定性 Actor 绑定沿用原 HTTP 合同。

## 6. 回执读取审计与错误

每次成功 GET 追加一条 `READ_COMMAND_RECEIPT` 审计，`entry_type=EVENT`、command_id/command_type 为 NULL、result_code=SUCCEEDED、service_role_code=API、summary schema 为 `R1_COMMAND_RECEIPT_DISCLOSURE_AUDIT_V1` / 1。它是审计 action，不是新业务 Command、authority 或权限槽。

disclosedSource 是准确 `execution.command_receipt@hash`，authorizationAnchor 是本次当前授权的主 Subject，允许二者不同。Receipt hash 使用 JCS 对原不可变行全部列构成的对象做 SHA-256：字段名为物理列名，UUID 小写、bytea 为无 padding base64url、NULL 为 JSON null、completed_at 为 UTC 六位小数 Z；包含 tenant_id、command_receipt_id、command_execution_slot_id、outcome、rejection_code、completed_at、result_fact_type/id/revision/hash，不加入查询时刻。这不是新增数据库 hash 列。

读取审计摘要恰含 `profile="R1_COMMAND_RECEIPT_DISCLOSURE_V1"`、`version=1`、`responseMode="BODY"`、`commandId`、`receiptId`、`disclosedSource`、`authorizationAnchor`。不复制 recovery scope、来源自然键 HMAC、原审计文本、业务 values 或 HTTP body。冻结实际 Actor/代办、当前授权快照和服务端 correlation。主 Subject 及所有额外 Subject 的完整当前判定按确定性顺序合并为内存授权证据，并由既有 `authorization_snapshot_digest` 绑定；不声称完整证据正文保存在该列，也不添加摘要字段或第二条 Receipt。固定 authorization_fact 列记录主完整授权路径，不能拼接授权碎片。

| 情形 | 既有 HTTP 结果 | 新增持久写 |
|---|---|---|
| 成功读取 SUCCEEDED/NO_CHANGE/REJECTED，当前授权完整成立 | 200，原 CommandReceipt | 本次读取 Audit +1；其他全部 0 |
| Bearer 无效 | 401 UNAUTHENTICATED，标准 Bearer challenge | 0 |
| 当前身份无效或当前授权失败 | 403 NOT_AUTHORIZED；不可见准确业务对象沿用统一404 | 0；不披露回执 |
| 不存在、跨 Tenant、不是完全相同原 Actor/代办、内部 recovery 回执 | 404 NOT_FOUND | 0；不能披露存在性细节 |
| 同 Actor 记录缺元数据/旧 V1、版本不支持、重复或完整性不一致 | 503 SERVICE_UNAVAILABLE | 0；不得猜测或解析旧文本 |
| Audit append、锁超时、提交确认丢失 | 503 SERVICE_UNAVAILABLE | 未提交部分回滚；提交确认丢失可能已提交读取 Audit，不宣称确定零写 |
| 程序错误 | 500 INTERNAL_ERROR | 未提交部分回滚，无敏感结果 |

本 GET 的非披露拒绝在该具名读取 profile 下零增量，不能据此豁免通用高风险拒绝审计。所有响应 `Cache-Control: no-store`，无成功 ETag/304。错误 shape/code/retryPolicy 沿用 HTTP 合同，detail 不区分旧记录、错 Actor 或内部故障原因。GET 无业务 key，不扩展请求参数来提交 scope/Actor/原 payload。

## 7. 历史兼容和恢复

旧 Audit/Receipt/Slot 不改写、不回填、不删除。不得从自由文本 authorizationEvidence、Event 缺失、某个候选 Lead、相似时间/组织或当前拥有同类型权限猜原范围。即使旧 SUCCEEDED 可以部分推导，也不提供与 REJECTED 不同的宽松旁路。旧记录无法完成此具名 GET 校验时按上表安全失败。

持有完整原始请求的调用方仍可按原 endpoint/Idempotency-Key 重试，Runtime 继续当前授权下返回原终态/冲突；不追加读取 Audit，不补写恢复元数据、不重新执行业务、不改写原回执。GET 失败不等于原命令失败，不得自动换 key 再执行。没有原请求且缺少完整元数据时，R1 没有自动恢复捷径，也不新增管理修复入口。

## 8. 合同激活及后续实施门禁

拟定具名 ADR-0013，语义基线 `MVP-2026-09-08.1`，HTTP 合同 `R1-HTTP-V1.3`、命令授权及事件合同 `R1-COMMAND-POLICY-EVENT-V1.2`。OpenAPI `1.2.0`、operation/DTO/errors/security 与事件 schema 版本不变；只同步 GET 描述与已有 header 的语义说明。此前 ADR 的激活版本和历史证据保留，只有准确 successor 生效，不放宽成任意版本均接受。

书面审阅通过后执行独立合同单元：ADR、新基线、HTTP/命令/运行时合同的具名例外、Workbench 原请求恢复说明、原计划/spec 指针、delivery ledger 仅新增 FROZEN successor、静态 verifier 与 mutation tests 一致提交。不得重写已冻结迁移注释来“消除”历史措辞；当前语义由有权威的 named supersession 决定。

验证至少拒绝：缺失/未知/重复元数据、scopeDigest 不一致、跨 Actor/代办读取、泛化来源 Grant、先返回后 Audit、历史 ALLOW 代替当前授权、旧文本解析/回填、POST replay 新增 Audit、错误改成新业务 outcome、公开内部 recovery、额外 DTO/error/operation、物理或权限扩张。静态验证只证明合同一致性，不能冒充运行验收。

合同单元验收后，由原 Task8 实施单元落实新命令元数据写入和窄 Owner 读取/当前复核/读取审计/HTTP 映射；真实 PostgreSQL/HTTP 必测拒绝 capture 后没有 Lead、同自然键后来有 Lead 且 DENY、SERVICE 账户撤销、另一个账户同组织不能替代、来源重映射或原组织 revision 变化、同 Principal 换 Appointment、代办变化、终态 Task/Draft、因 stale candidate/inactive assignee 被拒绝但仍可查询原拒绝回执、Evidence 撤回、权限变化及提交故障，并证明所有 delta。新写入与本次 GET 的行为必须共同验收后才可称解决缺口。Task9/10、容量环境和整体 R1 状态仍未晋级。

## 9. 本轮验证记录

已逐项对照当前 Slot/Receipt 字段、CommandScope、CommandAuthorizationBinding、R1CommandPolicy、AuditAppender、classified view 和运行时审计限制形成设计；不存在现成可恢复原始范围的 Slot 字段或已批准的 Audit 元数据例外。本文不执行数据库、不运行生产迁移、不修改四个 Task8 工作文件。详细规则需经书面审阅；本轮不发布活动合同或宣称运行测试通过。

独立设计评审提出的尝试输入与授权区分、来源组织原锚点、授权摘要绑定三项意见，以及 Draft 原选择器和 Evidence 类型边界均已修订；同一评审者定点复核通过，无剩余阻断意见。此结论仅针对设计文本，不是实现验收。

本轮原活动基线的锁定验证脚本实际退出 0：baseline consistency PASS，R2 仍 BLOCKED（7 项非致命下游门禁）；仅证明加入本设计不改变原活动基线。原计划相对链接检查通过。无新增运行时测试或容量验收结果。
