# Task9 最小补充设计：合法代办接入与原身份恢复

日期：2026-09-08。

状态：**APPROVED**。用户于 2026-09-08 审阅本文后确认具体接口、披露与恢复规则，授权按本文执行合同前置及独立评审，再恢复 Task9.2。批准时生效合同为 ADR-0014 / MVP-2026-09-08.2；后继实际激活与验证另行记账，本文批准不代表 Task9.2 已完成。

依据：[Task9 批准设计](2026-09-08-task9-real-user-access-design.md) §3–4、[Identity 合同](../../contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md)、[命令合同](../../contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md)、[原回执规则](../../adr/ADR-0013-r1-command-receipt-recovery.md)。

## 1. 问题、决策与不变项

旧生产 Registration 能提供实际办理人及 on-behalf 元组；ADR-0014 删除逐人 HUMAN 注册后，只有本人任职选择不能构造原来合法的代办 Actor。底层授权测试仍通过，不等于真实登录入口保留该能力。

采用：本人先选择自己的有效任职，再显式选择已有合法一跳代办关系的被代办任职；后端从数据库解析被代办 Principal，每个操作继续由原业务策略裁定准确权限。这里只恢复身份入口，不创建委托。

不采用：阶段性 DIRECT-only（会使合法代办及旧代办回执入口不可达）；保留逐人 HUMAN 回退（违反已批准的动态映射）；让客户端选 Grant ID（把身份选择和单次业务授权证据混为一谈，也不能稳定代表原 Actor）。

不变：37 个业务 operation / 32 public Bearer / 5 internal mTLS；原九业务请求 body、七类 Task、十四事件、Owner、完成事实、锁序、数据库 13 Schema / 52+2 表、物理 52-plus-2-v1.2。无 ADM-05～07 CRUD、委托创建/续期/撤销接口、批量目录、SERVICE 管理、新权限码或新表。身份管理仍仅 HUMAN / 本人任职 / DIRECT。

## 2. 最小 wire 补充

保留 `X-Appointment-Id`：只选择已验证 Principal 的本人有效任职。新增可选 `X-On-Behalf-Appointment-Id`：只选择被代办任职，值是 UUID，不接收被代办 Principal、Grant ID、Tenant、权限码或路径。

- 缺少新 header 就是本人办理，绝不自动代办，即使只有一项代办候选。
- 新 header 出现时，必须同时显式携带 `X-Appointment-Id`，避免任职数量变化引起身份推断。
- 空值、重复 header、逗号列表、非 UUID 或缺少配套本人 header：400 `VALIDATION_FAILED`，在占槽前拒绝。只报告安全字段名，不回显值。
- 合法格式但不存在、跨 Tenant、非本人受托关系、关系/任职已失效：统一 403 `NOT_AUTHORIZED`，不区分目标是否存在；不得退回本人身份继续执行。
- 确定无效凭据仍 401；IdP/数据库不可用仍 503。已有安全错误结构、retryPolicy 和未知提交规则不变，不新增“可以换 key”语义。
- 在 32 个 public Bearer operation 上明确声明这个认证选择器；仅原 11 个公共业务 operation 与 `getSessionContext` 接受有效代办上下文。其余 20 个身份管理 operation 收到该 header 一律拒绝，不静默忽略。
- SERVICE Bearer 收到代办 selector 一律拒绝；internal mTLS 不接入此 selector，也不得由它改写既有静态 Actor。CORS 只为受信 Origin 增加准确 header allowlist，不允许通配。

`SessionContextV1` 原七个字段保留，新增两个 required 字段：

| 字段 | 闭合形状 |
|---|---|
| `delegatedAppointmentChoices` | 0..50 个 `{id: UUID, label: SafeText200}`，只属于当前已选择的本人任职；id 是被代办 Appointment，按 UUID 升序，去重 |
| `selectedOnBehalfAppointmentId` | UUID 或 null；非 null 必须恰好匹配候选一项 |

无本人任职或尚待选择本人任职时，新增数组为空、选择为 null，原 state/entry/key 规则不变。本人任职已选时，返回该任职当前候选；没有代办 header 时 `selectedOnBehalfAppointmentId=null`。合法显式代办选择得到 `READY`、对应 Actor 的 scope key，`canEnterIdentityAdmin=false`。`canEnterWorkbench` 重新按该 Actor 的既有业务入口资格计算，不因存在候选就固定为 true。

候选只披露用于识别任职的最小安全标签，沿用已批准任职 label 规则；不返回 Grant 明细、权限矩阵、外部账号、Tenant/Principal ID 或委托期限。UUID 仅作为 wire 选择值，不显示在可见文案中。同标签不得导致客户端以文本替代 ID 选择。

本人任职仍最多 50；当前所选本人任职的去重有效代办候选超过 50 时，安全 503 配置错误，不截取前 50、不自动选择。只查询当前受托任职关联事实，不提供 Tenant 全目录；数据库查询采用有界输出/超过上限检测，不能先向应用取回全 Tenant 再过滤。

## 3. 候选关系与真正授权分开

候选资格由 Identity Owner 在当前受信 Tenant 内检查：

1. 本人及被代办 Principal 均为 ACTIVE HUMAN；两个 Appointment 当前 ACTIVE、任期有效、分别归属准确 Principal，完整组织链有效；Tenant ACTIVE。
2. 至少一个当前 ACTIVE、期限有效的既有 DelegationGrant，delegate 精确等于本人选中 Appointment，delegator 精确等于被代办 Appointment。
3. sourceAuthorityGrant 是被代办 Appointment 自己持有的有效直接授权，其 code 位于既有允许 DELEGATED 的 R1 HUMAN 业务静态集合；授权范围覆盖委托范围，相关组织有效。只允许一跳，不以另一委托作为来源，不把管理权限变成候选资格。

相同双方任职可以有多条不同权限/范围的有效委托；候选按被代办任职去重。选择的是 Actor 元组而非某条 Grant。业务操作仍由原 `R1AuthorityReader` / `AuthorizationService` 选择能够覆盖本次准确权限和目标的证据，复验 source Grant、委托、组织、有效期及双方适用的对象 DENY；一项候选存在不能授权任何具体 Task/Lead。

初始身份解析不能替代最终持锁授权。保持原 Runtime 授权裁定点和并发撤销语义；Task Owner 比较仍使用原规则：本人路径对应本人 Appointment，代办路径对应 on-behalf Appointment。不改 Owner、不合并两种身份的卡/数量、不按看到的 Task 反推 Actor。

## 4. self 披露与审计边界

`getSessionContext` 仍是已认证本人 Principal 的具名 SELF 查询；被代办人不是本次登录主体。即使返回代办候选/选择，也不伪造业务 Actor 去取得 SELF 资格，不将审计记为被代办人登录。

共享 business → 共享 identity 围栏持有到 Audit commit；在锁内以新鲜数据库时间重新检查候选和实际选择，成功后才返回 no-store BODY，禁止 304。无法提交审计不返回候选、选择或 scope key；未知提交不声称 Audit 零增量。

延续 `R1_IDENTITY_SELF_DISCLOSURE_V1` 的固定 SELF_IDENTITY / DIRECT / authorizationFact=null 审计例外，actorPrincipal 为登录本人，actorAppointment 为已选本人任职或 null，on-behalf 审计 Actor 列保持 null。新候选仅扩充具名 SELF 的披露范围，不扩充管理查询权限。

SELF `disclosedSources` 记录本人 Principal、返回的最多 50 个本人 Appointment 和最多 50 个被代办 Appointment 的准确 type/id/revision，去重，上限由 51 调整为 101；摘要其余闭合字段不变。Grant/source Grant 仅用于服务端资格判断，不作为前端披露内容，不输出其 ID/证据。业务命令/卡/回执的 Audit 则继续使用实际完整代办 Actor 及准确授权证据，不能沿用 SELF 的 null on-behalf 例外。

## 5. scope key、重登与原回执

`actorScopeKey` 仍是原 `ask1` 格式及独立持久用途密钥，规范输入仍为 Tenant、实际 Principal、本人 Appointment、被代办 Principal 或 null、被代办 Appointment 或 null。Principal 均由后端事实解析。不得加入 Token、Grant ID、当前有效期或选项排序；不因续期、重登、进程重启、同双方另一项合法授权被选作证据而改变身份 key。当前授权摘要/ETag 仍反映权限变化，scope key 不是授权快照。

同一实际用户使用同一本人任职和同一被代办任职，重新取得相同 key 后，才能显式查原回执。后端仍复验原 Actor 的所有列和 NULL-safe on-behalf 对、原元数据完整性及本次当前权限；不要求历史 Grant 仍是唯一证据，也不绕过当前授权。本人身份不能读取代办身份回执，另一受托人不能凭相同被代办任职读取他人回执。

若原代办已撤销/过期或来源授权失效，不为恢复重新开放该 Actor。安全拒绝不证明原写入没有提交；保留“结果尚未确认，不能自动重发”的边界，不生成新 key、不补写历史审计、不改造旧 Slot/Receipt。

浏览器恢复标记仍只有 `{commandId,commandType,actorScopeKey,recordedAt}`，一标签页一条、24 小时；不得为方便恢复新增 on-behalf ID/Token/正文持久化。完整重登后先走当前本人/代办选择，再比较 key，不能由标记猜测或自动尝试多个 Actor 查回执。

补充原恢复规则的时机：重登后的初始上下文建立不是用户已确认切换身份。尚在选择本人/代办时保留有效的未决标记、禁止新的写入；不查询或展示不匹配 key 的回执详情。用户显式改用其他身份时执行原跨身份清理，并在未决标记被丢弃前明确确认“只删除本地线索，不撤销原操作”。避免默认本人 context 暂不匹配就丢掉原代办恢复线索。TTL 到期按原规则处理，不以标记到期推断提交失败。

显式本人↔代办或被代办任职切换改变 identity epoch，清除旧卡、候选、ETag、迟到响应资格；同一 Actor Token 续期不改变 epoch、不重置轮询额度。新增选择界面沿用 Task9.4/9.5 的视觉确认门，不在此次合同修订中直接实施画面。

## 6. 激活范围和实施顺序

书面审阅后先做独立的 Task9.2 合同前置子步骤，再恢复原 Task9.2 implementer：

1. 新增具名 ADR 后继，激活 semantic `MVP-2026-09-08.3`、Identity V1.1、HTTP V1.5、Workbench V1.3、OpenAPI 1.4.0。Command V1.3 的权限注册及业务 body 不变，只增加指向新身份选择规则的准确引用；不改物理版本。
2. 同步 CURRENT-MVP-BASELINE、四份合同、OpenAPI 两字段/header、生成类型及必要 delegate 签名、实际静态门禁和变异测试。37/32/5 不变，不能只在文档放宽而运行门禁仍拒绝，或只改生成代码。
3. 静态合同测试与生成/编译回归完成，独立评审无未解决的重要问题后，再实现动态认证、SELF 披露、原 Actor 代办回执回归。新引导/管理/SPA 的原任务顺序不变。
4. Task9.4/9.5 接入选择和恢复状态；Task9.6 验证实际登录至业务的闭环。已有委托只作为隔离测试预置事实；不得为测试新增生产 ADM-05 入口。目标用户、组织、任职、直接业务 Grant 仍按原真实管理链建立；预置委托是测试旧关系兼容的明确例外，不作为委托创建产品功能的证据。

## 7. 新增验收门（全部尚未执行）

| ID | 场景 | 必須结果 / 阶段 |
|---|---|---|
| T9-D01 | header/两字段/闭合 DTO/state 约束变异；旧业务 body、权限、事件和 operation 对比 | 拒绝任意 Principal/Grant/路径输入；37/32/5、业务 body/策略/事件/物理字节不变；合同前置 |
| T9-D02 | 无代办选择、合法单条关系、多条同双方关系、50/51 候选；外部/已失效/伪造选择 | 无隐式代办、去重、有界、无越权披露/静默回退；Task9.2 |
| T9-D03 | 实际 OIDC 动态映射本人，不逐人注册；选合法代办并读取/保存/完成责任 | Owner 未变、实际办理人与被代办人审计准确；业务动作逐次按各自 Grant/scope/DENY 判断；Task9.2 API 与 Task9.6 真浏览器 |
| T9-D04 | 受托人/被代办人/任职/组织/委托/来源 Grant 的挂起、撤销、过期；实际对象 DENY；双连接撤销竞争 | 初始与最终检查拒绝相应失效，Audit-before-disclosure，原裁定点语义保持；Task9.2 |
| T9-D05 | 代办 token 请求全部 20 个管理 operation；SERVICE/mTLS 携带 selector | 无本人降级/管理转授/SERVICE 改绑，零业务写；Task9.2/9.3 |
| T9-D06 | 代办 context 审计成功/失败/未知提交、响应上限、未授权探测 | 先审计后披露，SELF 主体仍本人，max101准确引用，拒绝不泄露目标是否存在；Task9.2 |
| T9-D07 | 原完整代办 Actor 写入回执，重登/重启/换同双方有效授权证据；改任一 Actor 分量 | 同元组 key 稳定且原回执可达；不同元组拒绝；原授权失效不绕过；Task9.2 |
| T9-D08 | 持有未决代办标记重登，初始本人 context、再次选择原代办；显式换身份、迟到响应、Token 轮转 | 未选完不丢线索/不发新写、不遍历身份查回执；同 scope 恢复，切换清旧视图并确认放弃风险；Task9.4/9.6 |

以上通过仍不替代 Task9 其他全部验收、人工 UAT 或 Task10 参考容量门禁。现有 Task9.1 静态验收保留为历史，不能当作本修订已验收。
