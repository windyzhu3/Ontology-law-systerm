# Ontology Law System 基线收口与 R1 实施门禁设计

日期：2026-08-28

状态：待用户复核

适用范围：销售 MVP、责任内核、前后端拓扑、Matter 终点、52＋2数据库合同及 R1 实施门禁

## 1. 目的

本设计将已经冻结但散落在历史规格、52＋2合同和高保真补充中的决定收口为一套可实施、可验证的当前基线。收口完成前不合并 PR #2，不开始 R1 生产代码，也不继续扩展页面、表或后续业务范围。

本次只解决六件事：

1. 统一 WAITING、WaitReceipt 与 WaitingProjection 语义。
2. 统一为一个 SPA、一份 OpenAPI 和 API/Worker 两种后端启动角色。
3. 统一销售 MVP 终点为 `TransferAccepted + MatterRef`。
4. 建立草案、冻结、合并、实现、实库验证五态台账。
5. 在真实 PostgreSQL 15＋验证 52＋2迁移与部署门禁。
6. 以 R1 线索接入至首联结果作为第一个生产垂直切片，阻止 R2/R3 提前实现。

## 2. 权威性与适用顺序

收口后按以下顺序解释仓库：

1. `docs/baseline/CURRENT-MVP-BASELINE.md`：当前唯一人工阅读入口和语义总纲。
2. `database/schema-contract-52-plus-2/contract/`：当前 MVP 数据结构唯一人工维护源。
3. 由合同机械生成的 manifest、字段合同和 Flyway DDL。
4. `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`：DDL无法证明的运行时规则。
5. `docs/progress/MVP-DELIVERY-LEDGER.md`：交付状态，不改变产品或数据库语义。
6. `docs/design/`：视觉验收证据，不产生领域规则。
7. `docs/specs/`：设计演进历史；与当前基线冲突的段落必须显式标为已被替代，不再依靠文末补丁隐式覆盖。

任何高保真、OpenAPI、应用代码、测试或部署脚本与前四项冲突时，必须停止实施并通过新的 ADR 或基线版本处理，不能在局部代码中自行选择解释。

## 3. 冻结产品不变量

- 普通用户只使用同一个响应式工作台入口。
- 首屏只完整展开一张 WorkCard。
- 一张卡只有一个 Owner Appointment、一个业务目的、一个固定主命令和一个明确完成 Fact 类型。
- Task 不能由勾选、页面状态或 AI 输出直接完成。
- 退回、转派、补正、重试和恢复后的新行动创建新 Task；旧 Task 不重开、不复用。
- 领域 Fact 是业务真源；Task、Audit、Event、Evidence、Receipt 和查询投影都不能越权替代领域结论。
- AI只理解、提取、草拟和解释候选输入，不拥有审批、冲突、签署、到账、转案接收或 Task 完成权。
- MVP 不建设通用 Todo、BPMN、动态规则 DSL、运行时本体平台、EAV、通用 Saga、通用 Job 或通用 Agent Runtime。

## 4. WAITING、WaitReceipt 与 WaitingProjection

### 4.1 Task 四态

`TaskOccurrence.state`只允许：

- `OPEN`
- `WAITING`
- `DONE`
- `CANCELLED`

`DONE`和`CANCELLED`为永久终态。

### 4.2 创建与进入等待

所有新 Task 必须先以`OPEN、revision=0`创建。禁止直接插入初始`WAITING`。

如果新责任属于同一个准确 Owner、未来确定时间才可行动，创建事务必须依次完成：

1. 插入`OPEN、revision=0`的 TaskOccurrence；
2. 以 CAS 推进为`WAITING、revision=1`；
3. 追加绑定`task_revision=1`的不可变 WaitReceipt；
4. 同事务提交。

现有`OPEN`任务只有在同一责任仍由原 Owner 承担且暂时不能安全行动，或进入`SYSTEM_RECOVERY`时，才允许`OPEN → WAITING`。每次迁移都必须增加 revision 并追加新的 WaitReceipt。

### 4.3 WaitReceipt

WaitReceipt是一次真实`OPEN → WAITING`迁移的不可变追加事实，不是可变状态容器。它没有业务主命令，不提供重试、取消、改期、转派或完成按钮，也不能被更新为“成功”或“失败”。

等待其他 Owner、客户、审批、Provider或下游系统结果时，已完成人工动作的原 Task 必须进入`DONE`；后续责任由准确下游 Task、ExternalAction、Review或领域 Fact表达，不能把原 Task长期挂为WAITING。

### 4.4 WaitingProjection

WaitingProjection由 Query Facade只读计算，不新增表。投影来源仅限静态注册的：

- WaitReceipt及其准确Task；
- 下游未完成Task；
- ExternalAction；
- 领域Review或其他明确允许的等待Fact。

Query Facade不得写Task、补造WaitReceipt、改变SLA或从缺失事实推测等待状态。

## 5. 应用与接口拓扑

当前冻结拓扑为：

- 一个响应式 SPA；
- 一份 OpenAPI；
- 一个 Spring Boot 模块化单体制品；
- 同一后端制品的`api`和`worker`两种互斥启动角色；
- 一个 PostgreSQL 数据库和13个受管Schema；
- jOOQ作为唯一业务持久化范式，Flyway作为唯一DDL真源。

SPA内部允许三种受保护的体验模式，但不拆成独立应用：

1. 普通用户工作台；
2. 身份、授权、审计和运营管理模式；
3. 客户轻量安全入口。

不同模式使用独立路由、会话能力和字段披露合同，但共享同一部署制品和OpenAPI。Provider Inbox不面向浏览器，可作为同一OpenAPI中的隔离服务端通道描述。

历史“三个自治SPA＋四份OpenAPI”构建契约被本基线替代，实施时不得按旧拓扑搭建工程。

## 6. Matter与销售MVP终点

销售MVP终点固定为：

```text
DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
+ TransferAccepted
+ TransferRequest的一次写入MatterRef
+ 案管Task DONE
+ 销售结果回执
```

同一本地事务必须写入完整MatterRef槽，包括稳定`matter_id`、`matter_no`、类型、能力包版本和可信创建时间，并发布`MatterCreated`事实通知供Post-MVP消费者使用。

MVP不创建Matter业务表、Matter页面、登记资料、分类、分案、承办团队、节点、期限、办理、成果或结案Task。Post-MVP Matter模块只能消费已接受转案及其稳定MatterRef，不能生成第二Matter身份或反向改写销售链历史。

MatterRef表示正式稳定身份已被分配，不表示完整Matter聚合或案件办理能力已经启用。

## 7. 52＋2边界

52张应用事实表＋2张`platform_meta`技术表是当前销售MVP Schema Contract的冻结结果，不是整个律所系统永久表数上限。

在当前MVP版本内，新增第55张受管表属于合同漂移并应失败关闭。未来Matter、财务或其他上下文确需新增表时，必须：

1. 形成新的ADR和MVP后版本；
2. 明确Fact Owner、租户边界、不可变性、授权和生命周期；
3. 使用新的向前Flyway迁移；
4. 升级合同、manifest、验证门禁和发布摘要。

禁止为了维持“52＋2”数字而把不同事实塞入JSON、EAV、通用关系表或无Owner的扩展表。

## 8. 五态交付台账

每项交付物必须且只能处于以下一个状态：

| 状态 | 定义 | 进入条件 |
|---|---|---|
| `DRAFT` | 尚可调整的设计或代码 | 已有可定位资产，但未获正式确认 |
| `FROZEN` | 语义或视觉已确认 | 有确认日期、版本和权威Owner |
| `MERGED` | 已进入main | 对应提交已合并且无未解决基线冲突 |
| `IMPLEMENTED` | 生产代码已实现 | 编译、单元测试和契约测试通过 |
| `RUNTIME_VERIFIED` | 已在目标运行时验证 | 真实PostgreSQL、API、浏览器或Provider协议验证通过并保存证据 |

“高保真已冻结”“DDL已生成”或“PR可合并”都不能记为`IMPLEMENTED`。同一能力的设计、数据库、后端、前端和运行时验证应分别记账，避免用文档进度替代系统进度。

## 9. PostgreSQL真实验证门禁

开始R1生产代码前，必须在一次性、专用、空的PostgreSQL 15＋数据库执行：

1. 固定Flyway版本的`validate`与`migrate`；
2. 19个迁移全部成功；
3. `V840__schema_contract_validation.sql`通过；
4. 52张应用事实表、`deployment_state`和Flyway历史表总数准确；
5. 四个应用能力角色、租户复合外键、禁止级联、更新守卫和部署BLOCKED门禁验证通过；
6. 重复从空库执行的结果一致；
7. 保存PostgreSQL、Flyway版本、命令、退出码和摘要证据。

如真实迁移失败，只允许修改人工维护的合同源并生成新的尚未发布迁移；禁止直接手改生成SQL或绕过V840。

## 10. R1垂直切片

R1只实现以下闭环：

```text
Lead接入
→ 去重/缺失处理
→ 唯一Owner分配或受控异常
→ 创建CONTACT_LEAD TaskOccurrence
→ SPA显示唯一CurrentCard
→ 保存ActionDraft并确认固定主命令
→ 写ContactResult准确事实
→ 同事务写DomainEvent、AuditEntry、CommandReceipt
→ 原Task DONE
→ 按结果创建重试、主管复核或商机推进责任
```

R1必须使用现有52＋2合同，不新增表。第一版不调用LLM；先实现确定性问答和固定选项，AI只在R1确定性验收后作为候选提取增强。

### 10.1 R1最小用户能力

- 人工或受信服务Actor接入一条Lead；
- 处理疑似重复、联系方式缺失、自动分配、人工分配和零候选；
- Owner读取唯一CurrentCard；
- 保存、恢复和确认ActionDraft；
- 记录首次联系结果；
- 根据结果结束、重试、提交疑似无效复核或创建下一责任；
- 查看只读等待摘要和命令结果；
- 对写入、拒绝和敏感读取形成审计。

### 10.2 R1不包含

- Opportunity实质推进；
- Quote、冲突审查、Contract、签署、付款和转案实现；
- AI写入或自主Agent；
- 管理页完整CRUD；
- 客户入口；
- Provider真实发送；
- 新表、Kafka、Redis、搜索引擎或通用流程组件。

## 11. R1测试和验收

R1采用测试驱动开发。每个行为必须先有失败测试，再写最小实现。

至少覆盖：

- 同一接入幂等键不创建重复Lead；
- 同一Lead只有一个当前有效分配；
- Task先以OPEN创建，不能直接初始WAITING；
- 同Owner定时责任以OPEN创建后同事务进入WAITING并追加WaitReceipt；
- 一张Task只能由准确ContactResult完成一次；
- 同Command ID同Payload返回原Receipt，不同Payload被拒绝；
- 对象版本、Owner任职或授权变化导致提交失败；
- 失败事务不留下部分Fact、Event、Audit或Receipt；
- CurrentCard只完整返回一张可执行卡；
- AI关闭时全部R1流程仍可完成；
- 浏览器刷新和请求结果不确定时可按Command ID恢复；
- R1端到端测试使用真实PostgreSQL，不用H2替代协议语义。

## 12. 阶段门禁

只有同时满足以下条件才能开始R2：

1. 本收口设计和实施计划已合并；
2. PR #2的视觉资产与权威基线无冲突并已合并；
3. 52＋2合同已真实PostgreSQL验证；
4. R1后端、SPA和OpenAPI均达到`IMPLEMENTED`；
5. R1黄金路径及关键失败路径达到`RUNTIME_VERIFIED`；
6. 没有通过新增通用平台能力绕过冻结边界。

R3只能在R2以同样标准完成后开始。后续页面设计可以作为DRAFT存在，但不得被标记为已实现，也不得驱动R1扩大范围。

## 13. 仓库变更策略

第一阶段继续使用`fix/p0-workcard-design-consistency`收口PR #2，只修改文档、视觉索引和必要的合同测试，不引入生产应用代码。完成并合并后，再从最新`main`创建独立R1实现分支。

已生成Flyway迁移不在PR #2中重写。若真实PostgreSQL验证证明物理合同需要修正，先记录失败证据，再通过独立前向迁移和合同版本处理。

## 14. 完成判据

本轮“收口”只有在以下结果全部具备时完成：

- 当前权威基线不存在WAITING、SPA/OpenAPI或Matter终点的双重解释；
- 历史冲突段落被明确标为已替代；
- 五态台账准确反映设计与实现差距；
- PR #2通过文档和合同一致性检查；
- 真实PostgreSQL迁移证据可复验；
- R1实现计划只覆盖线索至首联垂直切片；
- R2/R3门禁可由CI或验收清单机械判断。
