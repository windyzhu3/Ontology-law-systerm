# 当前MVP基线

Baseline ID: MVP-2026-09-05.1

状态：`FROZEN`

确认日期：2026-09-05；前版MVP-2026-08-28.1的已合并证据保留为历史。

R1实施合同确认日期：2026-09-02

当前数据库合同版本：`52-plus-2-v1.1`（静态合同；真实PostgreSQL 18运行证据完成前不得开始R1生产代码）
当前52＋2合同摘要：`0c04d48ddae6891b53fdacabdba34d1124e757b070a4c9018597e4e0a4674301`
字段合同摘要：`f4c17c4c0a8697820b30adb61b8cdb209666a4672393d4f8fc9d73a5f169addf`

本文件是当前销售MVP唯一人工阅读入口和语义总纲。未在本基线明确保留的历史语义不得自动复活；任何未决事项只能通过新的ADR和新的基线版本处理。[ADR-0001](../adr/ADR-0001-pr2-runtime-gate-order.md)冻结PR #2合并与后续PostgreSQL运行时门禁的执行顺序。

## authority-order

[ADR-0006](../adr/ADR-0006-command-runtime-authorization-boundary.md)记录本版经用户确认的CommandRuntime授权裁定时点，以及Scope binding、静态信封和提交确认丢失的精确解释；52＋2物理合同、迁移、工程版本与产品范围不变。

按以下顺序解释仓库；前项与后项冲突时以前项为准：

1. 本文件 `docs/baseline/CURRENT-MVP-BASELINE.md`。
2. `database/schema-contract-52-plus-2/contract/`：当前MVP数据结构唯一人工维护源。
3. 由合同机械生成的manifest、字段合同和Flyway DDL。
4. `database/schema-contract-52-plus-2/docs/runtime-validation-contract.md`：DDL无法证明的运行时规则。
5. [ADR-0004](../adr/ADR-0004-r1-scaffold-and-http-contract.md)、[ADR-0005](../adr/ADR-0005-r1-foundation-readiness.md)、[R1 Task完成矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)、[R1 HTTP矩阵](../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)和[R1 Workbench合同](../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)：决定R1工程、HTTP、责任完成与呈现语义；涉及持久化形态、唯一键或Receipt基数时必须服从第2至4项。
6. [冻结R1计划](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)：只决定上述合同的实施顺序，不能覆盖合同或数据库权威。
7. `docs/progress/MVP-DELIVERY-LEDGER.md`：仅记录交付状态，不改变产品或数据库语义。
8. `docs/design/`：视觉验收证据，不产生领域规则。
9. `docs/specs/`：设计演进历史，不作为新实现或DDL生成依据。

以下七份历史规格及其各自的`2026-08-27 P0一致性补充`均由本基线显式替代：

1. [目标产品基线](../specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md)
2. [销售MVP工作卡与对话状态设计](../specs/2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md)
3. [总体架构与本体完整设计](../specs/2026-08-18-law-firm-overall-architecture-ontology-design.md)
4. [最小Matter身份与后MVP扩展契约](../specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md)
5. [基础框架设计规格](../specs/2026-08-18-ontology-law-system-foundation-architecture-v1.0.md)
6. [项目结构、模块边界与构建契约](../specs/2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md)
7. [PostgreSQL物理模型总纲](../specs/2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md)

## product-invariants

- 普通用户只使用同一个响应式工作台入口；首屏只完整展开一张WorkCard。
- 一张卡只有一个Owner Appointment、一个业务目的、一个固定主命令和一个明确完成Fact类型。
- Task不能由勾选、页面状态或AI输出直接完成；退回、转派、补正、重试和恢复后的新行动创建新Task，旧Task不重开、不复用。
- 领域Fact是业务真源；Task、Audit、Event、Evidence、Receipt和查询投影都不能越权替代领域结论。
- AI只理解、提取、草拟和解释候选输入，不拥有审批、冲突、签署、到账、转案接收或Task完成权。
- MVP不建设通用Todo、BPMN、动态规则DSL、运行时本体平台、EAV、通用Saga、通用Job或通用Agent Runtime。

## application-topology

- 一个响应式SPA；一份OpenAPI；一个Spring Boot模块化单体制品。
- 同一后端制品只有`api`和`worker`两种互斥启动角色。
- 使用一个PostgreSQL数据库和13个受管Schema；jOOQ是唯一业务持久化范式，Flyway是唯一DDL真源。
- SPA内部可有普通用户工作台、身份/授权/审计/运营管理模式和客户轻量安全入口三种受保护体验模式，但它们共享同一部署制品和OpenAPI；Provider Inbox不面向浏览器。
- 历史“三SPA＋四OpenAPI”已被替代，不得按旧拓扑搭建工程。
- R1代码框架精确采用ADR-0004的单Maven工程、单Jar、单npm workspace和`api|worker`互斥角色；文档冻结不等于脚手架或生产代码已经实现。

## r1-http-and-workbench-contract

- Tenant只由认证后的服务端ActorContext提供；公共调用方不能提交或覆盖Tenant。写操作使用调用方生成的UUID `Idempotency-Key`，这是“业务UUID由服务端生成”的唯一例外并原样映射到现有`command_id`；Receipt与领域ID仍由服务端生成UUIDv7。同key同scope同payload只重放原Receipt；异scope或异payload返回冲突和原Receipt引用且新增delta为零。
- capture没有不存在资源的`If-Match`。Draft创建、Draft更新、Task命令与subject重验分别使用HTTP矩阵冻结的前置条件和ETag种类，不得混用。
- R1责任完成、后继Owner、Receipt result与E2E delta以Task完成矩阵为准；零分配候选完成P0-04正常分支，不是HTTP错误。
- Workbench envelope固定为一句`todaySummary`、零或一张完整`currentCard`、最多两条`nextSummaries`、一个`waitingCount`和一个固定`chatComposer`。普通`/workbench`无全局导航/侧栏；身份管理使用同一SPA的独立受保护route mode，生产CRUD不计入R1。

## task-waiting-contract

R1 wire `Revision`固定为JSON安全整数`0..9007199254740991`；数据库`bigint`不变。超界输入和ETag revision必须在API边界拒绝且不得舍入；旧导入超界值不得发出。任何需要`revision+1`且当前已达上限的写入必须在持久化前以`INTERNAL_ERROR`原子失败，不留下slot或Receipt。内部恢复命令按责任类型具名分离：CONTACT_LEAD只接受`CONTACT_RETRY_V1`，RESOLVE_LEAD_ROUTING_GAP只接受`R1_ROUTING_REVIEW_WAIT_V1`；浏览器不得调用内部operation，恢复scope必须包含`commandType`。

Command授权的自然有效期以最终持锁完整复验使用的数据库`clock_timestamp()`为裁定时点，不保证随后的物理COMMIT瞬间仍在有效期内；锁不能停止时间。Command从该复验前至事务结束持有具名Tenant共享advisory事务锁，所有未来Identity writer在任何身份变更之前取得同Tenant排他锁，之后不得再获取Lead/Task等业务锁。复验前已提交的撤销、新DENY及组织变更必须被观察；复验之后的这些写者等待本事务结束。授权失败回滚领域写入，按既有pre-slot/post-slot合同裁定。提交前技术故障全回滚；COMMIT确认丢失不能证明未提交，必须同key重试读取原Receipt，不制造FAILED/UNKNOWN Receipt。

`TaskOccurrence.state`只有`OPEN`、`WAITING`、`DONE`和`CANCELLED`四态；`DONE`和`CANCELLED`为永久终态。

所有TaskOccurrence先以OPEN、revision=0创建；禁止直接插入初始WAITING。

同Owner定时等待在同一事务内执行OPEN创建、CAS到WAITING、追加绑定revision=1的WaitReceipt。

WaitReceipt只记录一次真实OPEN→WAITING迁移，永久不可变；WaitingProjection只读计算且不新增表。

现有OPEN只有在原Owner仍承担同一责任且暂时不能安全行动，或进入SYSTEM_RECOVERY时，才可CAS到WAITING；每次迁移都增加revision并追加新的WaitReceipt。

等待其他Owner、客户、Provider或下游系统时，原人工Task完成为DONE，由准确下游责任或事实表达等待。

允许的完整转换集合为OPEN→WAITING|DONE|CANCELLED以及WAITING→OPEN|DONE|CANCELLED；WAITING→DONE只能由准确完成Fact原子触发。

仍需由Owner执行交互式行动时，Task必须先从`WAITING`恢复为`OPEN`。WaitingProjection由Query Facade从WaitReceipt及准确Task、下游未完成Task、ExternalAction、领域Review或其他明确允许的等待Fact只读计算；Query Facade不得写Task、补造WaitReceipt、改变SLA或从缺失事实推测等待状态。

## matter-endpoint

销售MVP终点固定为：

```text
DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
+ TransferAccepted
+ TransferRequest的一次写入MatterRef
+ 案管Task DONE
+ 销售结果回执
```

同一本地事务必须写入完整MatterRef槽：稳定`matter_id`、`matter_no`、类型、能力包版本和可信创建时间，并发布`MatterCreated`事实通知供Post-MVP消费者使用。

MVP不建Matter业务表、页面或办理责任，不建设登记资料、分类、分案、承办团队、节点、期限、办理、成果或结案Task。Post-MVP不得生成第二Matter身份或反向改写销售历史；Matter模块只能消费已接受转案及其稳定MatterRef。MatterRef表示正式稳定身份已被分配，不表示完整Matter聚合或案件办理能力已经启用。

## schema-52-plus-2-boundary

52张应用事实表＋2张`platform_meta`技术表是当前销售MVP Schema Contract的冻结结果，不是整个律所系统永久表数上限。当前MVP版本新增第55张受管表属于合同漂移并应失败关闭。

未来Matter、财务或其他上下文确需新增表时，必须通过新ADR、合同版本和前向迁移：明确Fact Owner、租户边界、不可变性、授权和生命周期，升级合同、manifest、验证门禁和发布摘要。不得为了维持“52＋2”而将不同事实塞入JSON、EAV、通用关系表或无Owner扩展表。

## p0-acceptance-mapping

P0-01至P0-15是销售主链的冻结验收映射；历史七份规格文末P0补充不再拥有活动权威：

| ID | 画面 | 唯一主命令 |
|---|---|---|
| P0-01 | 疑似重复线索 | 确认线索归属 |
| P0-02 | 联系方式缺失 | 保存并继续分配 |
| P0-03 | 人工指定Owner | 分配给所选销售 |
| P0-04 | 零候选调配 | 记录本次调配处置 |
| P0-05 | 疑似无效复核 | 记录复核决定 |
| P0-06 | 商机停滞或报价拒绝处置 | 记录处置决定 |
| P0-07 | 提交报价审批 | 提交这份报价审批 |
| P0-08 | 报价授权决定 | 记录报价授权决定 |
| P0-09 | 报价发出 | 发送这份报价给客户 |
| P0-10 | 报价发送修正 | 按修正信息重新发送 |
| P0-11 | 不可直接重发处置 | 记录发送处置 |
| P0-12 | 冲突Finding决定 | 记录冲突决定 |
| P0-13 | 首次转案 | 提交案管审核 |
| P0-14 | 案管RETURN | 退回销售补正 |
| P0-15 | 补正重提 | 重新提交案管审核 |

视觉证据位于`docs/design/sales-mvp-workcards/`；P0-13至P0-15必须结合Snapshot/Review实例约束验收，视觉文案不得解释为复用旧实例。

## delivery-state-definitions

每项交付物必须且只能处于以下一个状态：

| 状态 | 定义 | 进入条件 |
|---|---|---|
| `DRAFT` | 尚可调整的设计或代码 | 已有可定位资产，但未获正式确认 |
| `FROZEN` | 语义或视觉已确认 | 有确认日期、版本和权威Owner |
| `MERGED` | 已进入main | 对应提交已合并且无未解决基线冲突 |
| `IMPLEMENTED` | 生产代码已实现 | 编译、单元测试和契约测试通过 |
| `RUNTIME_VERIFIED` | 已在目标运行时验证 | 真实PostgreSQL、API、浏览器或Provider协议验证通过并保存证据 |

“高保真已冻结”“DDL已生成”或“PR可合并”都不能记为`IMPLEMENTED`。同一能力的设计、数据库、后端、前端和运行时验证应分别记账。

## r1-r2-r3-gates

R1只覆盖Lead接入至首联结果：去重/缺失处理、唯一Owner分配或受控异常、创建`CONTACT_LEAD` TaskOccurrence、SPA显示唯一CurrentCard、保存/确认ActionDraft、写准确ContactResult、同事务写DomainEvent/AuditEntry/CommandReceipt并完成原Task；按结果创建重试或主管复核责任，有效接通形成R2可消费的`OpportunityOpened`边界。R1不实现Opportunity实质推进、报价、冲突审查、合同、签署、付款、转案、AI写入、客户入口、Provider真实发送、新表或通用平台组件。

PR #2只收口本基线、历史权威、五态台账、静态数据库验证和只读CI，可以在真实PostgreSQL运行时验证前合并。合并后必须先按运行时计划创建或推进独立`DB-52P2-PG18-RUNTIME`交付行至`RUNTIME_VERIFIED`；`DB-52P2-CONTRACT`和`DB-52P2-MIGRATIONS`保持`MERGED`。该独立运行时行存在并通过前，R1计划不得开始。

R1生产代码不得开始，直到`docs/adr/ADR-0002-lead-ingress-completion-slot.md`、合同版本`52-plus-2-v1.1`、V850前向迁移及v1.1真实PostgreSQL证据全部完成。已确认的P0-02方案保持52＋2表数：在`lead.lead`增加类型化、一次写入的Ingress Completion槽；原始phone/email均缺失且整槽为空时才可写入至少一组联系方式，V850以前的迁移不可改写。该槽记录phone/email密文与HMAC配对、静态来源代码、加密来源说明、完成Appointment、完成时间和32字节完成摘要；不得改写原始渠道捕获值或将联系方式写入通用JSON、审计摘要或事件载荷。

P0-04的`REQUEST_SOURCE_INTAKE_STOP`只表示请求，不证明来源已停用；R1形成绑定准确Lead和Task的`DecisionRecord(LEAD_ROUTING_DISPOSITION)`，并给准确Source Intake Owner创建`ACK_SOURCE_INTAKE_STOP_REQUEST`。只有该Owner执行具名确认命令并写`DecisionRecord(SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED)`后才完成后继责任，仍不改变SourceAccount状态。

R2只有在收口设计、当前基线和PR #2视觉资产已进入`main`，R1计划与四份实施合同保持`FROZEN`且位于`main`，独立`DB-52P2-PG18-RUNTIME`行为`RUNTIME_VERIFIED`，`R1-OPENAPI`、`R1-BACKEND`、`R1-SPA`均为`IMPLEMENTED`，`R1-E2E-GOLDEN`和`R1-E2E-FAILURES`均为`RUNTIME_VERIFIED`，且未通过新增通用平台能力绕过冻结边界时才能开始。`FROZEN`文档只表示实现输入已确定，不表示生产能力已经实现。R3只能在R2以同样标准完成后开始；后续页面设计可处于`DRAFT`，不得标为已实现或驱动R1扩大范围。
