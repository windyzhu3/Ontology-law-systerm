# Ontology Law System 基础框架设计规格 v1.0

> 状态：正式冻结版；已通过领域、安全与质量门禁复核，并经用户确认  
> 日期：2026-08-18  
> 范围：销售至转案MVP的技术基础框架，以及后MVP模块接入所需的稳定扩展契约  
> 明确不包含：数据库逐表DDL、API逐字段Schema、迭代排期、工作量估算和实施计划

## 1. 文档定位

本规格回答一个问题：已经冻结的领域本体、责任契约和极简Chat交互，应该由怎样的技术基础框架可靠实现。

本规格不重新定义销售流程，也不把领域本体改写成工作流引擎。业务语义继续由以下规格拥有：

1. 《律所待办驱动智能管理系统：目标产品基线 v2.0》。
2. 《待办驱动律所管理系统：总体架构与本体完整设计》。
3. 《销售MVP工作卡与对话状态设计 v1.0》。
4. 《最小Matter身份与后MVP扩展契约 v1.0》。

适用优先级：

- 领域事实、Task完成事实、时效、Decision、Matter边界发生冲突时，以上领域规格优先。
- 技术选型、模块组织、运行角色、持久化、发布、安全和质量门禁发生冲突时，本规格优先。
- 既有原型只用于验证交互，不构成本规格的技术约束。
- 旧代码仓库不作为兼容性基线；实现以目标产品基线为准。

本规格补充并替换总体架构旧文档中“技术品牌尚未锁定”和“技术基础设计尚未进入”的表述，不改变其业务本体结论。

## 2. 设计目标

基础框架必须同时满足：

1. 用户只学习一个极简工作卡，复杂性保留在确定性内核。
2. 每项正式写入都能回答：谁、基于什么权限、对哪个准确版本、执行哪个命令、形成什么事实。
3. 每个业务模块拥有自己的事实和表，不能被Chat、AI、Worker或管理员绕过。
4. 长生命周期Task、Event、Decision、TemporalPolicy和Evidence在版本升级后仍可解释。
5. 外部供应商不可靠、重复回调、响应丢失或状态未知时，不重复制造外部效果。
6. 初期以单律所、单体、单PostgreSQL保持简单，同时保留后续Matter办理模块的稳定接入边界。
7. 不为尚未进入MVP的案件办理、完整财务、零停机、多区域或大规模SaaS提前建设平台。

## 3. 已冻结的总体方案

```text
逻辑多租户，初期单律所部署
+ 领域边界模块化单体
+ 最小共享内核
+ 单Spring Boot工程、包级业务模块
+ 单PostgreSQL数据库、模块独立Schema
+ 同一后端产物，API与Worker两种启动角色
+ 三个独立SPA，共享纯UI包
+ 内部身份与权限内建，认证凭据交给OIDC
+ 内部统一Chat工作台，客户轻量安全入口
+ 代码静态注册业务语义，后台只配置授权和受控参数
```

MVP不引入：

- 微服务拆分。
- Kafka。
- Redis分布式锁或Redis工作队列。
- 外部搜索引擎。
- 通用工作流/BPMN引擎。
- 动态规则DSL。
- 通用Agent Runtime。
- Event Sourcing。
- 多数据库分布式事务。

## 4. 目标技术基线

| 层次 | 冻结选择 |
|---|---|
| 后端语言 | Java 25 |
| 应用框架 | Spring Boot 4.1 |
| 模块边界 | Spring Modulith 2.1与包可见性约束 |
| 关系数据库 | PostgreSQL 18 |
| 数据访问 | jOOQ；领域代码不依赖通用Active Record |
| 数据库迁移 | Flyway，单一全局有序迁移历史 |
| 内部与管理前端 | React 19、TypeScript、Vite |
| 客户入口 | 独立轻量SPA，同一前端技术栈 |
| API契约 | 用例型REST API、OpenAPI生成客户端、通道隔离 |
| 认证 | 外部OIDC；系统内保存用户、组织、任职与授权 |
| 文件 | S3兼容不可变对象存储，通过Evidence Port隔离品牌 |
| 遥测 | OpenTelemetry关联链；指标、日志与法律审计分离 |
| 密钥 | 外置SecretStore与KeyManagementPort |

确切补丁版本、容器基础镜像、OIDC产品、对象存储产品、SecretStore产品和部署平台由实施ADR锁定，但不得改变本规格的端口和安全边界。

## 5. 运行拓扑

```text
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ 内部Chat工作台SPA │  │ 受限管理后台SPA   │  │ 客户安全入口SPA   │
└────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
         │ internal API         │ admin API            │ customer API
         └──────────────┬───────┴───────────────┬──────┘
                        ▼                       ▼
              ┌────────────────────────────────────┐
              │ 同一后端构建：APP_ROLE=api         │
              │ REST、安全链、用例服务、操作读模型  │
              └────────────────┬───────────────────┘
                               │ 短本地事务
                               ▼
              ┌────────────────────────────────────┐
              │ PostgreSQL：模块独立Schema          │
              │ 状态表/Event/Audit/Task/双Outbox    │
              └────────────────┬───────────────────┘
                               │ 持久化工作队列
                               ▼
              ┌────────────────────────────────────┐
              │ 同一后端构建：APP_ROLE=worker      │
              │ Temporal/Outbox/Inbox/Recovery      │
              └──────────┬───────────┬─────────────┘
                         │           │
                         ▼           ▼
                  外部Provider   对象存储/AI Gateway
```

运行约束：

- API与Worker使用同一代码和构建产物，但使用不同进程、运行凭据、数据库角色和网络入口。
- API角色不得领取后台工作。
- Worker角色不开放业务写REST，只开放受限健康检查和遥测端口。
- `ServiceActorCommandEnvelope`没有HTTP入口。
- API与Worker必须运行相同`releaseId`，否则保持`NOT_READY`。
- 初期可以各运行一个实例；扩容优先增加同版本API或Worker实例，不改变领域模型。

## 6. 前端与通道边界

同一仓库保存三个独立SPA：

| SPA | 用户 | 允许能力 |
|---|---|---|
| Internal Workbench | 内部业务人员 | 当前工作卡、等待区、极少量受控入口 |
| Admin Console | 具名管理人员 | 用户、组织、授权、配置与受控运营动作 |
| Customer Entry | 持有效CustomerAccessGrant的客户 | 准确Subject上的上传、回复或签署 |

共享包只能包含：

- 设计Token。
- 无业务语义的可访问UI组件。
- 基础格式化和国际化工具。

共享包不得包含：

- 业务状态机。
- 权限判断。
- API路由选择。
- 工作卡完成逻辑。
- 客户与内部通道共用的认证状态。

四条安全链固定分离：

```text
/api/internal/**  → InternalTaskCommand与内部Query
/api/admin/**     → InternalAdminCommand与管理Query
/api/customer/**  → CustomerGrantCommand与客户受控Query
/api/provider/**  → Provider Inbox传输入口
```

通道不能通过请求Header或Body中的`channel`字段切换。内部工作台和管理后台使用不同OIDC Client/Audience；管理员持有的工作台Token不能调用管理API。

浏览器身份边界固定为：

- Internal Workbench与Admin Console使用OIDC Authorization Code＋PKCE；Customer Entry使用独立的受控客户会话交换协议。
- 长期Access Token、Refresh Token、Customer Grant秘密和一次性确认秘密不得进入`localStorage`或`sessionStorage`。
- 若使用Cookie承载会话，必须设置`HttpOnly、Secure、SameSite`，并在有副作用请求上同时执行CSRF Token与Origin校验；不能只依赖SameSite。
- 三个SPA使用互不复用的Cookie名称、Path、Audience和服务端会话状态；任一通道登出或撤权按自身安全代次失效。
- Admin Step-up证明只绑定Admin Client、管理会话、准确高风险动作和短时有效期，不能被Internal Workbench会话复用。
- 前端可隐藏无权能力，但所有Query、Command、下载和外发仍由服务端重验；浏览器状态不是授权源。

## 7. 模块化单体结构

### 7.1 模块类别

业务模块：

- `party`
- `lead`
- `opportunity`
- `conflict`
- `contract`
- `transfer`
- `matter-core`

平台模块：

- `execution-runtime`
- `identity-access`
- `responsibility`
- `temporal`
- `audit`
- `evidence`
- `external-action`
- `configuration`
- `ai-gateway`
- `workbench`
- `admin`
- `jurisdiction-policy`
- `legal-content-governance`
- `observability-contract`

后MVP模块只保留接入契约，不在MVP建表或建Task：

- Matter Registration & Intake。
- Matter Classification。
- Matter Allocation & Team。
- Handling Kernel。
- 综法、非诉、诉讼、执行能力包。
- Legal Deadline。
- Work Product。
- 完整财务与Ledger Export。

### 7.2 模块内部形态

每个模块最少区分：

```text
public-api       稳定本地端口、公开类型、受限领域Event
application      具名用例服务、事务边界、命令处理器
domain           聚合、不变量、值对象、领域服务
infrastructure   jOOQ、Provider Adapter、投影实现
```

具体目录名可以调整，但以下边界不能调整：

- 其他模块只能依赖`public-api`。
- Repository和jOOQ生成类型属于模块内部。
- 模块不能读取或修改其他模块私有表。
- 跨模块查询通过稳定读取端口。
- 跨模块写入通过具名用例、本地端口或明确允许的受限Event。
- Spring Modulith验证和架构测试必须阻止非法包依赖。

### 7.3 最小共享内核

共享内核只允许稳定技术值类型：

- `TenantId`
- `ActorRef`
- `SubjectRef`
- `CommandId`
- `CorrelationId`
- `CausationId`
- `OccurredAt / RecordedAt`
- `Version / Digest`
- 类型化错误基类

共享内核不得包含：

- Lead、Contract、Matter等业务对象。
- 通用业务状态。
- 工作流Node/Edge。
- 通用规则表达式。
- 跨模块Repository。
- `Map<String,Object>`式业务扩展字段。

### 7.4 法域、内容、财务与Matter扩展边界

- `jurisdiction-policy`提供中国大陆默认、代码版本化的法域策略包、业务日历和签署/材料策略引用；它不拥有Matter分类，也不允许后台编辑任意法律规则。
- `legal-content-governance`拥有模板、条款、批准基线和内容摘要；Office/WPS等外部编辑器只处理受控副本，重新导入必然形成新Revision，不能直接写“已批准”或“已签署”。
- 销售MVP的法律业务财务继续由Contract拥有FeeTerms、PaymentGate和PaymentConfirmation；会计总账只能经RESERVED的`LedgerExportPort`消费已确认事实，不能反向改写成交门槛。
- `matter-core`只拥有Matter Identity、MatterRef、不可变Origin和MatterCreated；MatterRef只能由Matter Core签发。
- `transfer`拥有不可变TransferSnapshot和write-once MatterLink，并持续按准确版本和Digest提供读取。
- Matter Core只保存`sourceTransferRequestId + snapshotVersion + snapshotDigest`采纳引用，不复制客户、合同、材料或冲突快照，也不保存status、type、jurisdiction、owner、team、handlingStatus、extensions JSON、metadata或EAV。
- 登记、分类、团队分配和综法/非诉办理通过稳定端口在后MVP接入，任何后续模块都不能修改Matter Core私有事实。

## 8. 数据库与Schema所有权

采用一个PostgreSQL集群、一个业务数据库、模块独立Schema。运行表所有权固定如下：

| Owner模块 | Schema | 权威表族 |
|---|---|---|
| execution-runtime | `execution_runtime` | CommandExecutionSlot、CommandReceipt、DomainEventEnvelope、DomainEventOutbox、普通InternalWorkItem、RegistryRelease、ReleaseState、BackfillRun |
| identity-access | `identity_access` | Tenant目录、Bootstrap状态、内部用户、IdentityBinding、组织、任职、Grant、Restriction、Delegation、CustomerAccessGrant |
| responsibility | `responsibility` | TaskOccurrence、DecisionRecord、WaitReceipt、RecoveryEpisode及定义版本引用 |
| temporal | `temporal` | TemporalSnapshot、Milestone执行状态和时钟工作记录 |
| audit | `audit` | 追加式法律与管理审计账本 |
| evidence | `evidence` | UploadSession、EvidenceItem/Submission/Binding、DerivedArtifact、RetentionAssignment、Hold和DestructionRequest |
| external-action | `external_action` | ExternalAction、ExternalDispatchOutbox、ExternalProbe、ProviderInboxRecord、ProviderInboxConflictAttempt、IngressBusinessIdentitySlot与SecurityIngressAttempt |
| configuration | `configuration` | ConfigurationAssignment、SecretHandle绑定、Kill Switch激活记录 |
| ai-gateway | `ai_gateway` | AIInvocation、AICandidate、AI Interaction Ledger和能力激活记录 |
| jurisdiction-policy | `jurisdiction_policy` | 已激活策略包和版本引用；不保存动态法律规则 |
| legal-content-governance | `legal_content` | 模板、条款、内容Revision、批准基线和Content Digest |
| party/lead/opportunity/conflict | 同名Schema | 各自聚合当前状态和模块私有投影 |
| contract/transfer/matter-core | `contract`/`transfer`/`matter_core` | 各自聚合当前状态；MatterRef仅在`matter_core`，MatterLink仅在`transfer` |
| workbench | `workbench` | CurrentCard、ActionDraft及强一致操作投影；不拥有业务事实 |

Event Payload的业务语义归生产它的事实Owner模块；`execution-runtime`只拥有不可变Event Envelope和投递状态，不解释或改写业务事实。Audit不是DomainEvent，DomainEvent也不是Audit。ExternalDispatch不进入通用DomainEvent Outbox。

规则：

1. 所有Tenant业务数据、Task、Event、Audit、Inbox、Outbox、Evidence和授权记录显式保存`tenant_id`。封闭的部署级例外只有Tenant目录、一次性Bootstrap状态、真正SYSTEM级配置、`RegistryRelease`、`ReleaseState`、Flyway Schema History、SYSTEM级`BackfillRun`以及无法可信映射Tenant的最小`SecurityIngressAttempt`。例外必须使用独立类型、独立Repository/Port和受限权限，不能靠`tenant_id IS NULL`把普通记录伪装成全局记录；扩充例外必须升级本契约。
2. 每张表只有一个模块Owner。
3. 其他模块不得直接读写Owner表，即使数据库账号技术上可见。
4. jOOQ按Schema生成代码并放入Owner模块内部包。
5. 查询显式列字段，禁止`SELECT *`作为稳定接口。
6. 所有Tenant对象的Repository和Query Port必须以`tenantId`为首要参数，并在SQL源头加入Tenant Predicate；禁止先按对象ID全库读取再过滤，也禁止通过对象ID反推Tenant。
7. Tenant内唯一约束包含`tenant_id`。能表达时，同模块引用和已登记的跨模块原子关系使用`(tenant_id, object_id)`复合唯一键/外键；不能使用外键时，在同一事务执行等价不变量校验。
8. Task、Grant、EvidenceBinding、Inbox、Outbox、对象存储Key、缓存、临时文件和导出都必须绑定同一Tenant；任何跨Tenant Subject绑定在写入前拒绝。
9. 跨Schema外键不是默认选择；只有稳定平台身份或已登记的原子本地事务关系可以使用，并进入模块依赖清单。
10. 多态Subject引用使用类型化`SubjectRef`和版本绑定，不构造万能EAV表。
11. Evidence原始字节不进入PostgreSQL；数据库只保存不可变对象版本引用和Hash。
12. 事实Owner模块的当前状态表是该事实的权威操作读源；WorkBench CurrentCard、搜索和发现投影都不是事实Owner，不能反向驱动领域状态。DomainEvent不是Event Sourcing恢复源。
13. 异步发现投影可以重建，不能反向成为业务事实源。

数据库运行账号：

- API账号：仅拥有API所需DML和查询权限。
- Worker账号：仅拥有队列、Outbox、Inbox及后台用例需要的DML权限。
- Migration账号：独立受限DDL身份，只在受控发布期间使用。
- 只读运营账号：通过受控View访问，不允许直接查看高敏字段。

## 9. 具名用例与短事务

每个正式写操作由具名用例服务拥有，例如：

```text
RecordContactAttemptUseCase
DecideLeadValidityUseCase
IssueQuoteUseCase
RecordQuoteResponseUseCase
AcceptTransferUseCase
```

统一执行顺序：

```text
解析类型化命令信封
→ 解析Principal与Actor
→ 以唯一CommandId声明或读取幂等执行槽
→ 校验Tenant、通道、PayloadDigest及已有执行结果
→ 校验当前授权、职责分离和限制
→ 校验Task/Intake及准确subjectBindings
→ 调用具名用例并锁定必要的当前版本
→ 修改权威聚合
→ 追加Event/Decision
→ 完成或取消当前Task
→ 按具名用例创建下一Task、WaitReceipt或同时创建两者
→ 写Audit、Outbox与CommandReceipt
→ 单个PostgreSQL短事务提交
```

幂等执行槽与最终CommandReceipt必须分开：执行槽可以表达本次命令是否已被声明，CommandReceipt一经事务提交即不可变。不得先写一个可被后续覆盖的“临时Receipt”。

WaitReceipt只能映射已经存在的下游Task、ExternalAction或权威等待事实，不能替代下游责任。

`CommandRuntime`只负责上述机械性工作，不负责：

- 解释流程图。
- 决定下一节点。
- 动态执行条件表达式。
- 跨聚合通用编排。
- 自动补全业务事实。

下一责任由具体用例或代码注册的确定性路由显式创建。

### 9.1 显式跨模块原子用例

跨模块原子写入只能由代码正向登记的具名用例执行。销售MVP冻结以下五个原子边界，不能改造成提交后再靠异步Event补齐的主链：

| 原子边界 | 同事务最小结果 |
|---|---|
| 有效联系建商机 | 准确`ContactResultRecorded(VALID)`＋幂等`OpportunityOpened`＋首个商机责任；`CONNECTED`只属于此前独立的ContactAttempt事实 |
| 报价接受进入签约前门槛 | `QuoteResponseRecorded(ACCEPTED)`＋权威`QuoteAccepted`＋准确版本的`PRE_CONTRACT`冲突审查实例/责任 |
| 销售提交转案 | `TransferSubmitted(snapshotVersion/digest)`＋同一快照的`PRE_TRANSFER`审查实例＋销售只读WaitReceipt |
| 委托成立启动转案 | 一次性`DealActivated`＋`TransferRequestInitialized`＋准确Owner的`SUBMIT_TRANSFER` Task |
| 案管接收并建立Matter身份 | 下述`AcceptTransferUseCase`完整事务 |

每个边界仍由事实Owner模块验证并写自己的事实；“同事务”不等于共享聚合或允许其他模块直接写私表。除正向登记的边界外，模块之间默认通过稳定读取Port或提交后受限DomainEvent协作。

`AcceptTransferUseCase`固定为：

```text
校验当前案管Task、唯一CompletionContract、Actor及TRANSFER_REVIEW Authority
→ 校验TransferRequest、snapshotVersion/digest与materialManifestVersion/hash
→ 校验DealActivated与ContractExecuted绑定同一当前ContractRevision/approvedContentDigest
→ 校验不存在有效EngagementTerminated
→ 校验MaterialManifest AcceptReady
→ 校验当前conflictReviewId/scopeHash的PRE_TRANSFER已解决
→ responsibility写DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
→ transfer写TransferAccepted
→ 通过本地MatterOpeningPort调用matter-core
→ matter-core幂等创建最小Matter、签发MatterRef并写MatterCreated
→ transfer写write-once MatterLink
→ 案管Task DONE
→ 更新销售WaitReceipt结果
→ 写Audit、CommandReceipt、DomainEvent Envelope与Outbox
→ 同一个PostgreSQL事务提交
```

任一写入失败，整个Decision及接收事务回滚。`MatterCreated`消费者只供提交后的后MVP登记模块使用，不得用于异步创建Matter、补写MatterLink或完成转案接收。

## 10. 三类领域Actor与四类命令信封

### 10.1 Principal与Actor分离

```text
CallerPrincipal                    BusinessActor
OidcPrincipal              ──────→ InternalUserActor
CustomerSessionPrincipal   ──────→ CustomerGrantActor
WorkerWorkloadPrincipal    ──────→ ServiceActor
ProviderTransportPrincipal ──────→ 无，止步Provider Inbox
AI Provider                ──────→ 无，只返回Candidate
```

OIDC只证明`issuer + subject`对应的身份，不直接提供Tenant、业务Role、Capability、Authority或DataScope。

### 10.2 共同因果头与精确绑定

四类信封进入Runtime后都被规范化为服务端构造的共同上下文：

```text
NormalizedCommandContext {
  tenantId,
  commandId,
  correlationId,
  causationId,
  payloadDigest,
  subjectBindings[],
  appliedPolicyRefs[]
}
```

`tenantId`不接受请求Body或自由Header值，而是从认证绑定、CustomerGrant、权威WorkItem、ProviderAccount或Service Trigger派生。`appliedPolicyRefs[]`保存本次实际参与校验或计算的Temporal、Jurisdiction、Material、Disclosure及其他政策代码/版本；它与规范化Context摘要一并进入CommandReceipt和Audit。请求可以携带用于匹配的公开CommandId和因果引用，但最终上下文只能由服务端构造。

`SubjectBinding`是封闭判别联合：

```text
AggregateVersionBinding(subjectRef, aggregateVersion)
RevisionContentDigestBinding(subjectRef, revisionId, contentDigest)
SnapshotDigestBinding(subjectRef, snapshotVersion, snapshotDigest)
```

每个CompletionContract、DecisionKind和高风险Command必须声明所需Binding全集。禁止将Version、Revision和Digest实现成可以任选其一的Nullable字段；客户端提交值必须与Task、Grant、Intake或Trigger中的权威绑定逐项匹配。

### 10.3 InternalTaskCommandEnvelope

用于内部业务动作，`workBinding`只能是：

```text
ExistingTaskBinding(taskId, taskVersion)
或
RegisteredIntakeBinding(intakeDefinitionCode, intakeRequestId)
```

`RegisteredIntakeBinding`只允许代码注册的无前置Task入口，例如手工录入线索或提交线索导入批次。禁止`AD_HOC`或`NO_TASK_REQUIRED`开关。

`RegisteredIntakeDefinition`必须正向声明唯一允许的起始Command、Capability、DataScope、Purpose、允许创建的初始Subject/Event及必须同时创建的首个Task。Intake不能修改既有Subject、作出Decision、完成已有Task或直接制造ExternalAction。

### 10.4 InternalAdminCommandEnvelope

只允许管理后台使用，覆盖：

- 用户、组织和任职。
- 授权、限制、代理和对象授权。
- 受控配置、RetentionPolicy和ServiceActor启停。
- CustomerAccessGrant管理。
- EmergencyAccessGrant和受控Runbook动作。

允许用例由`AdminUseCaseDefinitionRegistry`正向登记，未注册默认拒绝。管理命令不能修改任何业务聚合、代替业务Owner完成Task或绕过Decision；管理后台也不得提供通用SQL、跨Schema浏览器、任意对象编辑器或Repository入口。管理员身份不自动授予业务数据读取权，Admin Query仍校验Purpose、DataScope和DisclosureProfile。

### 10.5 CustomerGrantCommandEnvelope

必须绑定：

- `grantId/grantVersion`
- 客户入口会话
- 枚举的`allowedCommandCode`
- 准确`subjectBindings[]`
- 一次性确认令牌
- `payloadDigest`

Tenant、Subject、允许Evidence类型和披露字段由服务端Grant取得，不能由客户请求扩大。

每次Customer Query和Command都必须在线校验`grantVersion、revocationGeneration、expiresAt、allowedCommand、allowedEvidenceType、subjectBindings、authenticationStrength`。会话有效期不得超过Grant有效期。Grant秘密只保存不可逆Hash，不进入URL查询串、日志或普通Audit字段。一次性确认Token绑定Grant版本、会话、Subject版本、Command和PayloadDigest；重放返回原幂等结果。

### 10.6 ServiceActorCommandEnvelope

只能由Worker内部构造，必须绑定：

- 代码注册的`serviceActorCode/version`
- `triggerKind/ref/version`
- Work claim或Fencing Token
- 准确`subjectBindings[]`
- 实际`policyVersion`

`serviceActorCode`由Handler和工作类型确定，不能从队列Payload任意传入。ServiceActor不能模拟InternalUser、审批人工Decision或取得客户Grant。

`subjectBindings[]`只有在`REGISTERED_INGRESS`对应Definition明确声明`CREATE_NEW_SUBJECT`时才可为空；此时准确ProviderInbox Ref/version成为Trigger Binding，具名用例创建新Subject后必须把结果写入Receipt。其他ServiceActor命令必须携带Definition要求的完整Subject Binding，不能用“尚未匹配”绕过版本校验。

```text
ServiceActorDefinition：code/version、允许Command/Trigger/Subject、最大Risk
ServiceActorActivation：tenantId、code/version、enabled、activationGeneration
```

CommandRuntime必须同时验证`APP_ROLE=worker`、有效WorkerWorkloadPrincipal、Definition和Tenant Activation。构造器位于Worker专属内部包，不对HTTP或API角色可见。Activation只能缩小代码定义；停用时推进Generation，已领取命令在提交事务中重验。

### 10.7 互斥不变量

1. 一个命令只能使用一种信封和一种Actor。
2. 四类信封字段不能混用，反序列化阶段即拒绝非法组合。
3. Tenant只能从认证绑定、Grant、WorkItem或ProviderAccount取得，不能信任请求正文。
4. API不能从HTTP构造ServiceActor。
5. Worker不能构造InternalUserActor。
6. Provider和AI永远不是领域Actor。

## 11. 身份引导、用户与权限

### 11.1 一次性Bootstrap

首次安装进入`UNBOOTSTRAPPED`，业务写入口关闭。

```text
短时BootstrapGrant
→ 指定OIDC issuer + subject登录
→ 原子创建首个Tenant、根组织、首位安全管理员
→ 写不可变BootstrapReceipt与Audit
→ BootstrapGrant销毁，入口永久关闭
```

首管理员不是超级管理员：

- 只能建立身份、组织和授权治理能力。
- 不自动拥有销售、合同、冲突、财务或Matter业务权限。
- 不能为自己授予高风险权限。
- 第一项管理责任是邀请第二位具名安全复核人。
- 第二位复核人建立前，需要双人复核的动作保持不可用，不提供隐含绕过。

禁止首次OIDC登录自动开户，禁止通过手工SQL创建永久管理员。

Installation Bootstrap是唯一安装期根信任例外，不是领域Actor，也不是可重复使用的第五类CommandEnvelope：

- 只在`UNBOOTSTRAPPED`且Identity Schema为空时启用。
- BootstrapGrant来自SecretStore，只保存Hash、准确Issuer、Subject、Audience、过期时间和一次性Nonce。
- 校验未初始化、消费Nonce、创建Tenant与首管理员、写BootstrapReceipt/Audit必须在一个事务中完成。
- 一旦存在BootstrapReceipt，入口永久关闭，不能通过配置重新开启。
- 入口只绑定受限管理网络，失败默认关闭。

内部用户生命周期固定为：

```text
INVITED → OIDC_BOUND → ACTIVE → SUSPENDED | TERMINATED
```

身份只以准确`issuer + subject`绑定；邮箱、姓名和OIDC Group不能作为稳定身份键。Suspend、Terminate或身份解绑必须推进`accessGeneration`，使旧会话后续命令在事务内失效。OIDC不可用时系统Fail Closed，MVP不提供本地后门账号。

API只接受允许Issuer/JWKS签发的Access Token，固定算法白名单并校验签名、`iss、aud、azp/client_id、exp、nbf`；禁止将ID Token作为Access Token。Internal和Admin通道分别校验准确Audience/Client。高风险管理操作还必须校验满足策略的`acr/auth_time`。每次内部Query和Command在线校验User、IdentityBinding、Membership和`accessGeneration`。

### 11.2 四轴权限模型

正式授权判断由四个轴组成：

```text
Responsibility  当前是否由该人员承担责任
Capability      是否具备该类操作能力
Authority       是否有权作出该类型决定或承诺
DataScope       是否可接触当前对象和字段
```

一个命令必须沿完整有效的授权路径通过全部轴，不能把来自不同任职的Capability和Authority任意拼接。Restriction与显式Deny优先。

### 11.3 组织、任职与代理

- 组织支持层级结构。
- 用户可以同时拥有多个有明确有效期的任职关系。
- 组织Role不等于Task Owner。
- Owner创建时必须解析并冻结为一个具体有效的内部用户。
- 代理必须限时、限范围；代理不改变真实执行Actor。
- Task转派必须使用显式命令、留下前后Owner和理由，不能依靠组织变更静默换Owner。
- 一个客户入口会话只能绑定一个CustomerAccessGrant；Grant撤销、过期或`revocationGeneration`变化必须立即使会话和未使用确认令牌失效。

### 11.4 权限语义与授权数据

- Permission、AuthoritySlot、ScopeType和Restriction语义由代码注册并版本化。
- 管理后台只能在代码允许范围内配置Grant和Restriction。
- 具体对象授权允许，但必须绑定准确对象、范围、有效期和Purpose。
- 高风险授权变更采用Maker/Checker，不同Actor复核。
- 历史Decision固化当时的`authorityBasisRef/version`；执行旧Task时仍重新校验当前授权未撤销。

### 11.5 紧急访问

系统不设置永久`SUPER_ADMIN`或共享Break Glass账号。紧急访问使用临时`EmergencyAccessGrant`：

- 限定单一Tenant。
- 限定准确Subject或对象范围。
- 限定代码注册的Runbook动作。
- OIDC二次强认证。
- 不同Actor复核。
- MVP最长60分钟并自动失效。
- 默认只读；写动作逐项列举。

紧急访问不能关闭审计、突破Tenant、代做业务Decision、绕过PreservationHold、Task完成契约或ExternalAction的UNKNOWN保护。

`requestedBy`、`approvedBy`和受益Actor必须满足职责分离，批准人不能是受益人。每次Query/Command事务内重新校验Grant版本、撤销代次、有效期和准确Runbook Action。紧急Grant不能修改或扩大自身、签发另一个紧急Grant、管理Permission/Secret/Audit，也不能执行任意SQL或Shell。撤销先于命令提交时，该命令必须失败。

## 12. 最小责任内核

Responsibility模块只管理人类责任，不拥有业务流程。

核心对象：

- `TaskOccurrence`
- `TaskDefinition`
- `CompletionContract`
- `DecisionKindDefinition`
- `DecisionRecord`
- `ResponsibilitySlot`
- `WaitReceipt`
- `RecoveryEpisode`

### 12.1 Task不变量

- 一个Task只有一个具体Owner。
- 一个Task只有一个固定`commandVariant`。
- 一个Task只有一个准确完成事实类型。
- Task绑定创建时的`subjectBindings[]`和定义版本。
- Task创建时只能为OPEN或WAITING。
- `WAITING --nextCheckAt或恢复前提满足--> OPEN`。
- `WAITING --取消或替代事实--> CANCELLED`，禁止`WAITING → DONE`。
- `OPEN --准确完成事实--> DONE`。
- `OPEN --取消或替代事实--> CANCELLED`。
- 只有SYSTEM_RECOVERY可以使`OPEN → WAITING`；重验通过后回OPEN，Subject版本已变化时取消旧Task并创建新Task。
- `DONE/CANCELLED`为终态，永不重新OPEN。
- 退回、补正、重试、重新分配和新版本必须创建新TaskId。
- 旧Task通过`predecessorTaskId/supersedesTaskId`保留因果关系。

### 12.2 静态注册

TaskDefinition代码注册：

```text
taskType/version
commandVariant
completionContractCode/version
requiredCapability
requiredAuthoritySlot
subjectBindingContract
ownerResolverCode/version
temporalPolicyCode/version
presenterContractVersion
```

`CompletionContract`再固定：

```text
completionEventType
completionEventSchemaVersion
decisionKind/version?       # 仅Decision Task需要
subjectBindingContract
taskId/causationId匹配规则
```

`TaskOccurrence`在创建时冻结准确`completionContractCode/version`。Event Schema版本与完成契约版本是两个概念；只产生同类型Event但不满足准确Subject、Task和Causation匹配时，不能完成该Task。

注册表只登记责任语义，不登记流程边、后继节点或动态条件。

### 12.3 类型化不可变Decision

人工Decision使用代码注册的`DecisionKindDefinition`，固定：

```text
decisionKind/version
subjectBindingContract
allowedOutcomes[]
requiredAuthoritySlot
reason/evidence contract
separationOfDutiesPolicy
```

`DecisionRecorded`至少固化：

- 真实Actor。
- `authorityBasisRef/version`。
- 准确Subject版本或内容Digest。
- DecisionKind、Outcome、Reason和Evidence。
- 业务发生时间、服务端记录时间及必要的生效时间。

Decision提交后不可修改或覆盖。Decision Task的唯一完成事实是准确的`DecisionRecorded`；由该决定派生的TransferAccepted、返工Task或其他领域事实不能反过来充当Task完成事实。执行旧Decision Task时仍必须在线校验Actor、当前授权、Restriction和职责分离。

### 12.4 WAITING与WaitReceipt

`WAITING Task`只用于：

- Owner已确定、未来`availableAt`已确定的动作责任。
- SYSTEM_RECOVERY对原Task的安全暂停。

`WaitReceipt`用于：

- 内部移交。
- 外部Provider处理。
- 等待非当前Owner结果。

WaitReceipt只读，不属于WorkCard状态机，不能关闭Task。需要用户行动时创建新的TaskOccurrence。

`receiptStatus`只能由绑定的准确领域Event、Decision、ExternalAction权威结果或下游Task结果更新；Chat、AI、前端和普通管理员不能修改。Receipt没有业务按钮，需要原用户再次行动时必须创建新Task。

### 12.5 RecoveryEpisode

内部不变量或基础设施异常可能导致错误业务写入时：

```text
创建RecoveryEpisode
→ 原Task以SYSTEM_RECOVERY原因安全暂停
→ 唯一创建独立RESOLVE_SYSTEM_RECOVERY运营Task
→ 运营修复或判定
→ 原Task恢复同一Draft，或取消并创建准确的新Task
```

普通AI失败、合法重复回调、可自动恢复的瞬时错误不创建RecoveryEpisode。

SYSTEM_RECOVERY只能按冻结策略暂停Owner动作SLA，不能移动法律期限、合同期限或原始`scheduledAt/actionDueAt`；原时间和暂停依据必须保留在Audit中。

## 13. TemporalPolicy

时效语义由代码版本化，不由管理后台动态编辑。

Task创建时冻结`TemporalSnapshot`：

```text
policyRef/version
timezone
calendarRef/version
anchorEventId/anchorAt
availableAt
nextCheckAt?
actionDueAt?
reminderMilestones[]
escalationMilestones[]
cancellationFacts[]
calculationDigest
```

规则：

- 新政策只影响新建Task，不静默重算旧Task。
- `scheduledAt`表示原定时间，Worker迟启动不能修改原定时间。
- Temporal Worker只发里程碑事实或创建代码登记的责任，不能伪造人工Decision。
- 同一里程碑使用稳定幂等键，只能生效一次。
- 没有正式SLA时`actionDueAt=null`，系统和WaitReceipt都不能虚构截止时间。
- 历史提醒积压不得形成通知洪泛；是否发送当前状态提醒由策略确定。
- 30日无进展等跨域处置由Temporal产生阈值事实，再由事实Owner聚合验证和执行，不由时间内核直接改归属。

## 14. CommandReceipt与幂等

CommandReceipt不可变，并至少保存：

```text
tenantId
commandId
envelopeType
actorRef
task/intake/trigger binding
subjectBindings[]
payloadDigest
authorizationDecisionRef
appliedPolicyRefs[]
resultType
resultRef
createdAt
```

CommandExecutionSlot唯一键固定为：

```text
(tenantId, envelopeType, commandScope, commandId)
```

`payloadDigest`只保存并比较，不进入唯一键。同一作用域和CommandId携带不同PayloadDigest时永久冲突。Receipt查询必须重新取得相同Tenant与CommandScope；跨作用域统一返回不可区分的NOT_FOUND。

`commandScope`只能由服务端从已经校验的权威绑定确定，不能从请求Body、自由Header、Query参数或客户端自由字符串取得：

```text
InternalTask   = actorId + ExistingTaskBinding/RegisteredIntakeBinding
InternalAdmin  = actorId + adminUseCaseCode/version + targetBinding
CustomerGrant  = grantId + grantVersion
ServiceActor   = serviceActorCode/version + triggerKind/ref/version
```

Scope组成值使用规范化类型和长度边界后生成Digest；实现不得用显示名称、可变Role或客户端提供的“scope”替代权威标识。

幂等层次必须分开：

| 层次 | 作用 |
|---|---|
| ResponsibilitySlotKey | 防止重复创建同一责任 |
| scoped commandId，另行比较payloadDigest | 防止双击和命令重放 |
| aggregate version / digest | 防止并发覆盖当前事实 |
| temporal milestone key | 防止重复触发时效 |
| tenantId + providerAccountRef + providerEventId，另行比较payloadHash | 防止回调重放或篡改 |
| ProviderIngress Definition冻结的business identity key，另行比较businessPayloadHash | 防止Provider更换EventId后重复创建同一线索或交易输入 |
| tenantId + providerAccountRef + effectKey + attemptNo | 防止外部效果重复派发 |

相同CommandId、相同Payload返回原Receipt；相同CommandId、不同Payload永久冲突。客户端不确定请求是否送达时先查询Receipt，不能盲目重试。

## 15. 当前状态、DomainEvent与双Outbox

采用：

```text
当前状态表
+ 追加式DomainEvent
+ DomainEvent Outbox
+ ExternalDispatch Outbox
```

### 15.1 DomainEvent

- Event按`eventType + schemaVersion`版本化。
- 保存原始不可变Payload和完整Envelope：`eventId/eventType/schemaVersion、aggregateType/id/version、tenantId、commandId、occurredAt/recordedAt、actorRef、correlationId/causationId、subjectBindings[]、evidenceRefs[]、appliedPolicyRefs[]、producerRelease`。
- 只发布跨模块有稳定消费者价值的Event，聚合内部细节不全部外泄。
- Event不是数据库重建的唯一来源，不宣称Event Sourcing。

### 15.2 DomainEvent Outbox

- 与业务状态和Event同事务写入。
- 可以按EventId至少一次投递。
- 消费者必须幂等。
- 失败可以安全重放，不得改变原Event。

### 15.3 ExternalDispatch Outbox

- 只承载外部效果意图。
- 一次领取、租约和Attempt必须可追踪。
- `DISPATCHING`租约超时不能退回普通PENDING重试，而应进入UNKNOWN核验。
- 不得因Worker重启、发布或数据库轮询重复发送。

两个Outbox不能合并，因为其故障语义不同。

## 16. PostgreSQL持久化工作队列

MVP所有后台工作保存在PostgreSQL：

- Temporal里程碑。
- DomainEvent投递。
- ExternalAction派发和探测。
- Provider Inbox处理。
- Evidence扫描与派生处理。
- 异步搜索投影。
- Recovery工作。
- 批量导入和受控Backfill。

队列不引入通用消息编排语义，但必须区分两种租约结果：

```text
普通InternalWorkItem：
  租约过期 → 可以重新领取；旧Generation提交被拒绝

ExternalDispatch：
  PENDING只能领取一个Attempt
  DISPATCHING租约过期 → UNKNOWN
  永不因租约过期回到PENDING

ExternalProbe：
  只读查询工作，可以按幂等策略安全重试
```

Worker统一使用可见时间、有限批量和Fencing Token，但通用队列框架不得覆盖ExternalDispatch的特殊一次派发语义。

Kafka仅在PostgreSQL队列经过索引、批量、Worker池和分区优化后仍持续无法满足C1 SLO时评估。

## 17. ExternalAction与Provider Inbox

### 17.1 两阶段外部动作

```text
内部命令事务：ExternalActionRequested + ExternalDispatchOutbox
→ Worker调用Provider
→ Provider回调或受信主动查询
→ ProviderInboxRecord
→ ServiceActorCommand
→ ExternalAction单调推进与领域重新验证
```

人工Task在`ExternalActionRequested`可靠持久化时完成；这只表示人的发起责任结束，不表示消息送达、电子签完成或付款确认。

ExternalAction状态机固定为：

```text
PENDING → DISPATCHING → DISPATCHED | FAILED | UNKNOWN
DISPATCHED → SUCCEEDED | FAILED | UNKNOWN
UNKNOWN → SUCCEEDED | FAILED
```

`SUCCEEDED/FAILED`是单个ExternalAction Attempt终态。租约过期、调用响应丢失或调用结果不明只能进入UNKNOWN。只有权威证明原效果未发生后，才能创建`retryOf`的新Action；原Action不重新打开。

### 17.2 Provider Inbox

Provider止步同一个Inbox传输入口，但Inbox是封闭判别联合，不是通用Webhook平台：

```text
ProviderInboxKind =
  ACTION_CALLBACK
  | REGISTERED_INGRESS
```

两类请求都必须先完成：

- 签名或mTLS校验。
- timestamp、nonce和重放窗口校验。
- 从已验证证书、签名Key或受信路由派生ProviderAccount，再映射Tenant；禁止信任Payload中的Provider Account。
- 以`(tenantId, providerAccountRef, providerEventId)`唯一持久化，另行保存和比较`canonicalPayloadHash`。
- 保存实际验签Key/Certificate、Payload Schema和Canonicalization的代码/版本。

`ACTION_CALLBACK`还必须绑定已有ExternalAction、准确Subject和合法状态迁移；无法关联时进入隔离，不得猜测最新Action或Subject。

`REGISTERED_INGRESS`用于合法但没有既有ExternalAction、接收时也可能没有Subject的外部输入。它必须绑定代码注册的`ProviderIngressDefinition/version`、ProviderAccount、Tenant、Payload Schema/Canonicalization版本、外部事件唯一键、Payload Hash以及不可变的`businessIdentityKeyDeriver/version`。MVP正向登记范围仅包括实际启用渠道的`LEAD_CAPTURE`，以及确需自动接收时的`TRUSTED_BANK_TRANSACTION`；未登记Ingress类型默认隔离。

业务身份键最小契约固定为：

```text
LEAD_CAPTURE = tenantId + providerAccountRef + stableSourceLeadId
  # Provider没有稳定sourceLeadId时，Definition必须显式声明providerEventId即业务身份
TRUSTED_BANK_TRANSACTION = tenantId + trustedSourceAccountRef + externalTransactionId
```

具名用例在创建新Subject前必须在同一事务声明唯一`IngressBusinessIdentitySlot`。同业务键、同业务Payload Hash返回原Receipt/Subject；同业务键、不同Hash只能按Definition进入新Revision、人工核验或隔离，不能创建第二个独立Lead或交易输入。传输EventId幂等与业务身份幂等是两层不同约束。

Ingress接收事务只持久化Inbox，不创建Lead、Payment、Task或领域事实。Worker只能由Definition选择固定的ServiceActorCommand Handler，不能由Payload、Provider或后台配置选择命令。去重、主体创建/匹配、`LeadCaptured`或受信流水输入由后续具名用例重新校验后形成；Definition及其历史Decoder必须进入RegistryManifest。

Provider只作为`ProviderTransportPrincipal`止步Inbox。伪造、跨Tenant、同EventId异Hash或无法关联的回调进入隔离审计，不调用领域命令。

签名必须针对原始请求字节验证，Canonical Hash不能替代签名验证。`canonicalPayloadHash`绑定明确的Canonicalization版本；只允许排除签名、nonce、timestamp等已注册传输字段，业务字段不得被排除。若TLS在受信代理终止，身份Header必须由代理重建、在公网入口清除且后端链路受保护。Provider入口永远只写Inbox或隔离记录，不同步调用领域用例。

未通过身份验证或尚不能从受信路由映射Tenant的请求，不得创建Tenant级Inbox；只允许向部署级`SecurityIngressAttempt`追加最小Digest、来源类别、失败代码和时间，不能保存未经批准的原始敏感Payload。已存在的`ProviderInboxRecord`状态只能单调推进；同EventId异Hash的后续请求不得修改、回退或覆盖原记录，而是追加独立`ProviderInboxConflictAttempt`和安全Audit。

ProviderInbox状态固定为：

```text
RECEIVED | QUARANTINED | PROCESSED
```

同EventId同Hash返回原结果。对`ACTION_CALLBACK`，锁定Inbox和ExternalAction、推进状态、产生经事实Owner验证的领域事实、更新WaitReceipt/Task、写Audit并标记PROCESSED必须在同一个本地事务中完成；任一步失败不得标记PROCESSED。对`REGISTERED_INGRESS`，锁定Inbox、执行其固定具名用例、写事实/Audit/Receipt并标记PROCESSED同样必须在一个本地事务中完成。Provider技术成功仍不能直接等于付款到账、ContractExecuted、材料VERIFIED或MatterCreated。

### 17.3 UNKNOWN

状态不确定时：

- 禁止普通自动重发。
- 按`nextProbeAt`进行受信查询。
- 权威回调、受信查询或有权运营处置才能解除。
- 到`resolutionDueAt`仍未知时，唯一创建`RESOLVE_EXTERNAL_ACTION`运营Task。
- 只有证明原效果未发生后，才允许创建`retryOf`的新ExternalAction并递增Attempt。

## 18. Evidence双层模型

### 18.1 所有权边界

Evidence Core拥有：

- 文件身份。
- 不可变Submission版本。
- 原始对象来源链。
- Hash、大小、技术完整性和安全扫描。
- 存储引用、ACL、Retention与Hold。

业务域拥有：

- 签署是否有效。
- 材料是否VERIFIED。
- 付款是否到账并被正确分配。
- 冲突Finding是否有充分依据。

`UploadSession.FINALIZED`只表示受控入库完成并产生了技术检查通过的准确EvidenceSubmission，不表示业务有效。MVP不另设含义重叠的Submission“可用”状态。

### 18.2 核心对象

```text
EvidenceItem
EvidenceSubmission
ReceivedSourceObject
EvidenceBinding
DerivedArtifact
RetentionAssignment
PreservationHold
DestructionRequest
```

补正形成新的EvidenceSubmission并通过`supersedes`关联，旧Submission不得覆盖。

### 18.3 UploadSession隔离入库

状态：

```text
OPEN → RECEIVED → VALIDATING → FINALIZED
OPEN → EXPIRED | CANCELLED
RECEIVED/VALIDATING → REJECTED
重复基础设施失败 → RECOVERY_REQUIRED
```

流程：

1. PostgreSQL先创建UploadSession并冻结Tenant、Subject、来源和限制。
2. 服务端随机生成绑定Tenant和UploadSession的Opaque Object Key；客户端不能指定Key。签发能力冻结准确Key、最大长度、Multipart清单、Checksum和SSE要求。
3. 对象写入私有隔离Key，一次写入并冻结准确`objectVersionId`。Bucket Policy拒绝覆盖、公开ACL、列目录及未经批准的删除；API和普通Worker没有Delete权限。
4. Worker对准确版本重新计算SHA-256、Magic Type、大小、压缩风险和恶意文件扫描。
5. FINALIZED事务重新校验UploadSession签发时冻结的Actor/CustomerGrant版本、撤销代次、Tenant、Subject和上传限制；授权已撤销或绑定已失效时只能进入REJECTED/隔离清理，不能产生可业务引用的EvidenceSubmission。
6. 校验通过后，在单个PostgreSQL事务创建不可变EvidenceSubmission并将UploadSession逻辑晋级为FINALIZED。
7. 字节不在扫描后搬迁，避免双对象复制窗口。

相同Hash不能自动合并两个法律证据。客户端Hash、MIME和ETag不是可信完整性依据。

### 18.4 下载与派生物

- MVP所有Evidence下载都由应用网关实时执行Tenant、Actor、Grant撤销代次、Purpose、DataScope和字段敏感级别检查并代理/门控对象读取；Customer Entry以及CONFIDENTIAL/RESTRICTED文件绝不向浏览器暴露可绕过在线撤权的对象存储GET预签名URL。未来若内部通道为PUBLIC/INTERNAL文件引入预签名GET，必须另行冻结最大残余TTL，并不得宣称该能力可即时撤销。
- UploadSession的预签名PUT仅允许准确Opaque Key上的单次写入、短TTL和冻结上限；它不是EvidenceRef。即使上传URL尚在TTL内，授权撤销后的字节也不得通过FINALIZED门槛。
- OCR、缩略图和受控摘要若启用，均为DerivedArtifact，不是Evidence或VERIFIED事实；销售MVP不生成或保存Embedding，未来启用时也必须按DerivedArtifact治理。
- DerivedArtifact继承源Evidence最严ACL、敏感等级、Retention和Hold。
- 只有由FINALIZED UploadSession事务创建且技术扫描通过的准确EvidenceSubmission才能预览、下载、进入AI或业务引用；仍处于OPEN/RECEIVED/VALIDATING的ReceivedSourceObject以及REJECTED对象均不得使用。
- SVG、HTML、Office宏及其他主动内容不得从主应用Origin直接内联；预览使用隔离Origin/Sandbox或安全派生格式。文件名与Content-Disposition由服务端净化。
- 物理销毁只能由独立受限执行身份处理已双人批准清单中的准确对象版本。

## 19. Retention、Hold与销毁

采用版本化`RetentionPolicy`、范围化`PreservationHold`和双人复核销毁。

规则：

- RetentionPolicy代码注册范围类型，后台只能在允许范围内配置Assignment。
- Hold只阻止销毁，不自动授予读取权限。
- Hold可以绑定Tenant、Subject、EvidenceItem、Submission或法律事项范围。
- 销毁请求保存策略版本、计算依据、对象清单、申请人与复核人。
- 执行前再次校验当前Retention、Hold和对象版本。
- 高风险销毁必须由两个不同Actor复核。
- MVP禁止无人值守自动物理销毁。
- 销毁必须覆盖原始对象、DerivedArtifact、搜索索引、AI缓存和临时副本。
- 审计保存销毁墓碑、Hash、依据和结果，但不保留已销毁正文。

对象存储不可变不等同于法律原件，也不能用无限期WORM替代Retention和个人信息删除治理。

## 20. 读取模型与搜索

### 20.1 两类读模型

```text
强一致操作读模型    与命令事务同步更新
异步发现型投影      允许短暂延迟，可重建
```

必须强一致：

- CurrentCard。
- Task当前状态和可执行性。
- CommandReceipt。
- 当前授权与限制。
- 当前Subject版本。
- ExternalAction安全状态。

可以异步：

- 名称/别名查找。
- 发现型列表。
- 统计和运营趋势。
- 非权威搜索候选。

### 20.2 Query Facade

每个模块提供面向用例的Query Facade和稳定Lookup Port。WorkBench可以编排多个读取端口，但不能直接跨Schema SQL查询。

### 20.3 PostgreSQL类型化搜索

MVP使用：

- B-tree精确匹配。
- B-tree前缀匹配。
- `pg_trgm`处理名称和别名容错。
- 只对白名单非高敏文本使用受控全文索引。

禁止万能`search_document JSON`。每个模块拥有类型化`*_lookup_projection`，至少保存`tenantId、subjectRef、subjectVersion、projectionVersion`。

搜索约束：

- 服务端从Actor和Purpose派生Tenant、对象类型、字段等级和DataScope。
- SQL源头先做Tenant与授权过滤，不能全库检索后在内存过滤。
- 返回的只是候选Ref；查看、选择和执行命令时回到Owner模块重鉴权。
- Chat最多返回5个已授权候选，不显示未授权总数或原文高亮。
- 证件号、电话、邮箱和银行账号不进入Trigram或全文索引；必要时仅用租户密钥版本化HMAC盲索引做精确匹配。
- Evidence/OCR不进入MVP全局搜索。
- ConflictSearchPort只负责候选召回，不能产生CLEAR或Finding。

外置搜索引擎仅在PostgreSQL发现查询持续违反C1 SLO并明显影响OLTP后评估。

## 21. 类型化AI Gateway

### 21.1 AI权力边界

AI只允许：

- 提取候选。
- 草拟内容。
- 解释当前责任。
- 给出受控分类候选。

AI不能：

- 调用领域写命令。
- 完成Task。
- 作出Decision。
- 发送消息或签章。
- 修改权限、时效或优先级。
- 成为ServiceActor。

### 21.2 AICapabilityDefinition

每个能力代码注册并进入RegistryManifest：

```text
capabilityCode/version
purposeCode
allowedChannels/TaskTypes/SubjectTypes
allowedInputTypes/sensitivityCeiling
ContextBuilder版本
Provider/Model策略
Prompt/OutputSchema/ToolProfile版本
humanReview=REQUIRED
timeout/token/cost上限
fallback
EvalSuite/threshold
```

后台只能启停、限额和选择已批准Provider，不能创建任意Prompt、Purpose或工具。

### 21.3 只读工具

MVP工具仅允许：

- `READ_ONLY_QUERY`
- `PURE_FUNCTION`

Tenant、Actor、Purpose和Subject由服务端注入。禁止任意SQL、HTTP、文件系统、网络抓取和业务Command Port。

上传文件、OCR和客户文本一律作为不可信数据，不能改变系统指令或工具白名单。

### 21.4 Invocation与Candidate

`AIInvocation`是技术辅助生命周期，不是Task、Decision、WaitReceipt或ExternalAction。模型调用不在业务数据库事务内。

`AICandidate`不可变并绑定：

- 准确Task/Draft/Subject版本。
- EvidenceRef/version/hash。
- Purpose、Provider、Model、Prompt、Schema和工具版本。
- 来源页、段或Span。
- 置信度、缺失项和警告。

用户可以采纳、修改或拒绝；正式事实仍由原类型化命令、当前鉴权和准确版本校验产生。上下文变化后Candidate显式失效。

### 21.5 Provider准入与降级

- ProviderProfile冻结允许数据等级、处理地域、模型白名单、训练禁用、留存和删除能力。
- 不满足准入要求的Provider不能接收客户Evidence、合同、证件或冲突数据。
- 超时、熔断、预算不足或非法输出立即走确定性手工路径。
- AI不可用不暂停Task、时效或排序，不创建WaitReceipt。
- 普通AI故障不创建RecoveryEpisode。
- MVP不做客户Evidence通用RAG、跨Matter向量检索、长期记忆、客户数据微调或自动回训。

### 21.6 EvalGate

模型、Prompt、ContextBuilder、Tool、OutputSchema或提取策略任一变化都必须运行能力级EvalSuite。跨Tenant泄露、越权工具或领域写操作容忍值为零；未通过不能激活。

## 22. 数据保护与外发门禁

### 22.1 四级分类

```text
PUBLIC
INTERNAL
CONFIDENTIAL
RESTRICTED
```

字段、Evidence类型、Event字段、投影字段和外发数据在代码中注册`DataElementDefinition`：

```text
elementCode/version
ownerModule
classification
encryptionMode
indexingMode
allowedPurposeCodes
allowedDisclosureProfiles
maskingPolicy
retentionCategory
```

后台不能降低分类等级。ChangeGate必须与上一已激活安全Registry比较：降低Classification、扩大Indexing、Purpose、Disclosure或AI Sensitivity Ceiling属于显式安全破坏性变更，不能作为普通版本替换。任何进入DTO、Event、Search、AI、Notification、Provider或Export的新字段未注册时，构建或披露必须失败。

### 22.2 加密

- 所有传输使用TLS。
- PostgreSQL和对象存储使用基础设施静态加密。
- RESTRICTED结构化字段采用应用层AEAD信封加密，AAD至少绑定`tenantId、elementCode、subjectRef、recordVersion`；密文复制到其他Tenant、字段或记录时必须解密失败。
- 数据库只保存`keyRef/keyVersion`和密文，不保存主密钥。
- 密钥通过KeyManagementPort取得。
- 轮换采用读旧版本、写当前版本；重加密是受控技术Backfill，不改业务语义。
- 解密前重新校验Actor、Purpose、DataScope和DisclosureProfile；KMS允许解密不等于业务有权读取。KMS不可用时Fail Closed，不允许明文降级。
- Evidence对象存储强制使用批准的SSE-KMS/Key策略，上传能力冻结必需加密Header。

### 22.3 DisclosureProfile

分别注册：

- Internal Workbench披露。
- Admin Console披露。
- Customer Entry披露。
- Search候选披露。
- Notification披露。
- AI Provider披露。
- External Provider披露。
- Export披露。

任何外发必须同时满足Tenant、Actor/ServiceActor、Purpose、DataScope、字段等级和目标Provider Profile。未注册Purpose或通道默认拒绝。

日志、指标、Trace、通知标题、搜索摘要和AI普通遥测不得复制RESTRICTED原文。

MVP不建设动态DLP规则引擎。

## 23. 配置、Secret与Kill Switch

### 23.1 ConfigurationDefinitionRegistry

配置Key由代码注册：

```text
configCode/version
valueType
allowedScopes
validation
riskLevel
dynamicMode
defaultPolicy
secret=false
```

允许Scope：

```text
SYSTEM
TENANT
CHANNEL
PROVIDER_ACCOUNT
```

后台只能为已注册Key创建版本化`ConfigurationAssignment`，不能创建任意JSON规则。

配置不得定义：

- 工作流。
- SQL或脚本。
- Task完成事实。
- Permission或Authority语义。
- Decision结果。
- 跨聚合后继关系。

只有标记为`DYNAMIC`的配置允许热加载；其他配置受控重启。读取时保存实际生效版本，不能只记录当前值。

### 23.2 SecretStore

- Secret值只存在外部SecretStore。
- PostgreSQL、配置、Receipt、Event、日志和Trace只保存类型化、不可枚举的`SecretHandle/version`。
- 应用通过Workload Identity取得最小范围Secret。
- API与Worker使用不同Secret授权。
- Secret轮换不能要求修改业务配置语义。
- SecretStore适配器校验Handle的Scope、Tenant/ProviderAccount和Purpose；Admin API只能绑定Handle，永不读取或返回Secret值。
- Secret值不得进入异常信息、健康检查、测试快照或前端状态。缓存必须有上限并绑定轮换Generation；取密失败只关闭受影响能力，不回退默认Secret或旧明文。
- Provider验签轮换只接受策略登记的当前/前一Key版本，并冻结明确退役时间。

### 23.3 Kill Switch

AI、Provider、外部派发、客户入口等Kill Switch是独立高风险配置：

- 代码注册。
- 默认Fail Closed。
- 具备专用Capability的一个Actor可立即停止能力或缩小适用范围，并形成完整Audit；紧急止损不得被复核等待阻塞。
- 重新启用、扩大范围或解除Fail-Closed属于高风险变更，必须由两个不同Actor复核并满足职责分离。
- EmergencyAccessGrant不能静默启用或扩大Kill Switch控制的能力；只能执行已登记的停止/缩小Runbook。
- 配置服务、SecretStore或状态真实性不可用时保持关闭，不能回退为默认开启或沿用未验证缓存。
- Kill Switch不能绕过领域校验；停止能力也不能删除既有事实、Receipt或Audit。

## 24. 审计与可观测性

### 24.1 审计账本

所有正式写入追加统一审计账本，按业务、权限、证据、外部动作、AI和运营形成分类查询视图。Audit至少关联：

- Actor与Principal。
- Command、Receipt、Task和Subject版本。
- AuthorizationDecision与AuthorityBasis。
- Correlation/Causation。
- Event、ExternalAction或Evidence结果。
- 实际策略和注册表版本。

审计不可由普通管理员修改或关闭。数据库权限同时强制：

- API和Worker身份对Audit Schema只有INSERT及通过受控Query/View的读取权限，没有UPDATE、DELETE或TRUNCATE权限。
- 纠错只能追加`AuditCorrection`并关联原记录，不能覆盖原Audit。
- 分区维护、依法销毁或归档只允许专用受控身份在批准清单内执行，并为操作本身追加独立Audit。
- 正式领域或管理写入未能在同一事务追加规定Audit时，整个事务失败；不得“先成功、后补审计”。

### 24.2 OpenTelemetry关联链

```text
HTTP/Worker入口
→ Command
→ UseCase/DB Transaction
→ DomainEvent/Outbox
→ ExternalAction/ProviderInbox
→ 后续Command/Task
```

Trace用于运行诊断，不是法律审计或业务事实源。

指标标签禁止使用TenantId、UserId、TaskId、EvidenceId等高基数标识；日志禁止保存OIDC Token、Cookie、一次性Token、预签URL、原始Chat、Provider回调、Evidence正文、OCR、完整AI Prompt/Output和SQL绑定值。

### 24.3 分层SLO

核心目标采用滚动统计：

除另有说明，窗口为滚动30天、按服务端计时；计划维护单独统计，业务校验失败和授权拒绝等预期4xx不计为系统5xx，外部Provider等待不计入本地命令延迟。99.9%是内部工程目标，不是尚未签署的对外SLA。

| 链路 | 目标 |
|---|---|
| 核心API可用性 | 99.9%，计划维护单独统计 |
| CurrentCard | P95 ≤ 1.5秒，P99 ≤ 3秒 |
| 本地确定性命令 | P95 ≤ 2秒，P99 ≤ 5秒；不含上传、AI和Provider等待 |
| PostgreSQL类型化搜索 | P95 ≤ 2秒 |
| DomainEvent Outbox | 99% ≤ 60秒，99.9% ≤ 5分钟 |
| ExternalDispatch首次领取 | 99% ≤ 2分钟 |
| Temporal触发 | 99.9%在scheduledAt后2分钟内 |
| Provider Inbox合法回调处理 | 99% ≤ 60秒，99.9% ≤ 5分钟 |
| Evidence验证 | 按文件大小档位和冻结扫描器版本，P95 ≤ 2分钟，P99 ≤ 10分钟 |
| AI | 不计入核心可用性；20秒是返回Candidate或确定性降级的调用时间预算，不代表模型质量 |

以下是零容忍正确性不变量，不使用百分比掩盖：

- 跨Tenant或越权读写。
- 撤权后成功执行受保护命令。
- 系统为同一`tenantId + providerAccountRef + effectKey + attemptNo`发起第二次派发；实际结果不明时必须保持UNKNOWN。
- 非由FINALIZED UploadSession事务创建的Submission或未通过技术扫描的ReceivedSourceObject被业务引用或下载。
- 正式写入缺失Audit。
- 同Provider EventId不同Hash产生副作用。

### 24.4 Alert、Task与Runbook

- Alert属于外部监控系统，即使业务数据库不可用也应工作。
- 只有需要某个具名人员完成明确动作时才创建运营Task。
- 自动恢复不创建Task。
- 告警不能一条对应一张Task；使用故障Fingerprint和Scope去重。
- Runbook动作代码注册、限权、审计，并受EmergencyAccessGrant约束。

## 25. RegistryManifest

以下语义全部代码注册并版本化：

- TaskDefinition和CompletionContract。
- RegisteredIntakeDefinition、AdminUseCaseDefinition和四类Command Schema。
- DecisionKind。
- TemporalPolicy。
- JurisdictionPolicy、RetentionPolicy和DisclosureProfile依赖定义。
- Permission、AuthoritySlot、ScopeType、Restriction。
- ServiceActorDefinition。
- Event Schema与Decoder。
- ExternalAction Definition、ProviderContractDefinition、ProviderIngressDefinition、请求/响应/回调/Ingress Schema、Canonicalization、状态映射、幂等Key方案和Probe Contract。
- AICapability、Tool、Prompt和OutputSchema。
- DataElementDefinition与DisclosureProfile。
- ConfigurationDefinition。
- Observation/Metric Definition。
- Presenter Contract。

已发布的：

```text
(code, version, canonicalDigest)
```

永久不可变。生命周期：

```text
REGISTERED
→ ACTIVE_FOR_NEW
→ LEGACY_EXECUTABLE
→ HISTORICAL_READ_ONLY
```

MVP不物理删除发布版本。旧Task继续使用原TaskDefinition、CompletionContract和TemporalSnapshot；语义变化必须取消旧Task并显式创建新Task，不能静默迁移。

仍被OPEN/WAITING Task、Draft、PENDING/DISPATCHING/DISPATCHED/UNKNOWN ExternalAction、未处理Inbox、Backfill或有效配置引用的版本只能处于`LEGACY_EXECUTABLE`。进入`HISTORICAL_READ_ONLY`后仍必须保留历史Event Decoder和Presenter。

构建生成不可编辑`RegistryManifest`。第二次及后续发布的比较基线必须来自上一已激活发布的不可变`previousReleaseBundle`，其中包含ReleaseManifest、RegistryManifest、OpenAPI、Event Fixture摘要和Flyway摘要；首次发布使用§26.1的`GENESIS_BASELINE`。任何发布都不能只与当前工作树比较。CI检查：

- 同版本Digest未漂移。
- 历史版本未删除。
- Task到Command、Completion、Temporal、Permission、Authority和Presenter依赖闭合。
- 历史Event Fixture仍可反序列化。
- AI工具仍为只读且敏感度未扩大。
- Telemetry定义没有高基数或敏感标签。

启动时与数据库最后激活的RegistryRelease比对。缺少旧Decoder、旧Executor或出现Digest漂移时，API写入口与Worker不得READY。

每个ExternalAction冻结实际Provider Contract版本；存在未终态Action或未处理Inbox时不得移除对应Adapter/Decoder。Worker不能处理任一可领取记录的版本时保持NOT_READY，不能跳过后继续运行。

## 26. 单版本发布与数据库演进

### 26.1 ReleaseManifest

每个构建包含：

```text
releaseId
applicationBuildDigest
databaseExpandEpoch
databaseContractFloor
OpenAPIDigest
FlywayMigrationDigest
RegistryManifestDigest
```

首次发布没有上一版N，使用项目内封存且不可变的`GENESIS_BASELINE`作为比较起点：只允许对空库初始化，执行完整ChangeGate、ReleaseGate和首次CapacityGate，并建立第一个`previousReleaseBundle`。`N → N+1`迁移、N构建兼容冒烟和旧Registry引用检查从第二次发布开始强制；Genesis不是跳过安全、Tenant、审计或黄金路径测试的豁免。

### 26.2 Expand–Migrate–Contract

各模块拥有自己Schema的迁移文件，但模块化单体作为一个发布单元使用一套全局有序Flyway历史。应用运行账号没有DDL权限；启动只做Validate和兼容性门禁，迁移仅由受控Migration身份执行。

`Expand`：

- 新增表、可空列、安全默认值和兼容索引。
- 新旧结构并存。
- 不直接删除、重命名、改类型或给大历史表直接加阻塞性约束。

`Migrate`：

- 读取和写入按受控阶段切换。
- 大回填使用具名、可续跑、分批提交的BackfillRun，不塞入长Flyway事务。
- 纯数据形态回填不得生成Task、Decision或业务Event。
- 若必须改变业务事实，则走正式用例而不是迁移脚本。

`Contract`：

- 只能在后续独立版本执行。
- 只要求没有非终态Task、待处理工作及运行路径依赖将被删除的数据库结构；历史Task、Event、Decision和Receipt仍必须按原注册版本读取和解释。
- 第一次物理删除表、列或历史运行结构前必须另行冻结ContractGate；销售MVP初始发布不执行物理Contract清理。

### 26.3 MVP受控切换

```text
进入MAINTENANCE_FENCED
→ 停止新业务命令
→ Worker停止领取新工作
→ 排空短事务
→ 所有DISPATCHING Attempt明确完成或进入UNKNOWN；未领取PENDING保持PENDING
→ Migration身份取得数据库迁移锁
→ 执行Expand和必要校验
→ 启动新API/Worker但保持NOT_READY
→ 校验ReleaseManifest、Schema和RegistryManifest
→ 一次性ReleaseActivator以expectedPreviousReleaseId做数据库CAS并激活
→ API和Worker分别观察激活记录并通过Readiness
→ 发布控制器确认两种角色均READY后退出维护
```

ReleaseActivator使用同一构建产物的一次性受控命令，不增加第三种常驻运行角色。

维护期间停止新的ExternalDispatch，但必须处理在途Provider现实：Provider入口可以保持“验签并持久化Inbox、禁止领域处理”的传输模式；若部署方式无法保持入口，则对应Provider必须支持可靠回调重试或权威主动查询。两者都不具备的Provider，在维护前不得保留未决自动外发，只能进入受控人工模式。

启动硬门禁至少验证：Flyway无Checksum漂移或失败迁移、Schema Epoch兼容、ReleaseManifest获准、Registry Digest一致、历史Decoder/Executor齐全、所有可领取工作均有兼容Handler。

MVP不支持N/N-1后端长期混跑。缓存的旧SPA提交不兼容命令时返回类型化`CLIENT_UPGRADE_REQUIRED`并保留本地Draft，不能自动转换为语义不同的新命令。

### 26.4 回退

- 从第二次发布开始，只执行Expand且尚未产生新版本专属事实时，只有`N数据库 → N+1 Expand → N构建关键读写冒烟`通过后，才可以保留扩展结构并回退应用；Genesis没有旧构建可回退，只能修复后重新发布或重新初始化尚未承载正式事实的环境。
- 已产生新Event、Task、Decision或外部效果后，禁止回退到无法解释它们的旧处理器，只能向前修复。
- 业务事实不得通过反向SQL或删除Event回滚。
- 发布导致外部效果不确定时进入UNKNOWN，不能因应用回退而重发。

## 27. C1单律所容量包络

C1是设计与验证目标，不是许可证或业务硬上限。达到包络后不得静默丢线索、Task、Event或Audit。

### 27.1 容量目标

| 项目 | C1目标 |
|---|---:|
| 内部注册用户 | 300～500 |
| 同时在线会话 | 50～100 |
| 普通业务写命令 | 持续10次/秒，短时50次/秒 |
| 当前卡及操作查询 | 持续100次/秒，短时300次/秒 |
| 日常新增线索 | 2,000～5,000/日 |
| 活动集中导入 | 20,000～50,000/日 |
| 单次异步导入 | 最大100,000行 |
| 每日新建Task occurrence | 50,000以内 |
| OPEN＋WAITING Task | 500,000以内 |
| Provider回调 | 持续20次/秒，短时100次/秒 |
| PostgreSQL追加类记录 | 约1亿行设计规模 |
| PostgreSQL在线数据 | 约500GB，不含Evidence对象 |
| Evidence对象字节 | 不计入PostgreSQL C1容量，由对象存储配额、吞吐和Retention单独监控；Evidence元数据仍计入数据库行数和容量 |

### 27.2 保护性输入限制

| 项目 | 初始限制 |
|---|---:|
| JSON命令体 | 1MiB |
| 单条Chat文本 | 20,000字符 |
| 单个Evidence文件 | 200MiB |
| 单个UploadSession | 2GiB且最多100个对象 |
| 单个线索导入文件 | 100MiB或100,000行，先到者为准 |
| 列表页 | 默认30、最大100 |
| 同步HTTP处理 | 最长10秒，超出转异步Receipt |

限制通过版本化ConfigurationDefinition管理。超限必须在入口返回类型化错误，不能截断输入。

### 27.3 容量升级触发器

满足任一情况并持续出现时进入C2评审：

- 连续10个业务日达到C1容量70%以上。
- CurrentCard或命令SLO连续两个统计周期不达标。
- Worker最老READY工作持续超过5分钟。
- PostgreSQL CPU、连接池或存储吞吐持续超过70%。
- 任一热点追加表、相关索引或数据库整体接近Schema分册和基准测试确定的阈值；全库约1亿追加记录或500GB只作为整体评审信号，不能等到整体阈值才分区。
- 搜索消耗PostgreSQL超过25%的CPU/I/O且仍不达SLO。
- 10万行以上导入成为日常操作。
- 单模块占数据库写入或Worker处理量50%以上。

升级顺序：

```text
SQL、索引、批量和连接池
→ 增加同版本API/Worker实例
→ 追加表分区与归档
→ 独立Worker池但保持同一产物
→ 只读副本或外部搜索
→ Kafka
→ 仅在独立发布/隔离成为持续问题时拆服务
```

### 27.4 C1-V1可重复验证Profile

`C1 Design Envelope`用于架构容量和升级触发；是否通过性能门禁由精确的`C1-V1 Verification Profile`判断：

| 项目 | C1-V1固定值 |
|---|---|
| API角色 | 1实例，4 vCPU / 8 GiB |
| Worker角色 | 1实例，4 vCPU / 8 GiB |
| PostgreSQL 18 | 8 vCPU / 32 GiB，SSD持续至少3000 IOPS |
| 测试Tenant | 1个主Tenant＋1个约1%数据量的隔离哨兵Tenant |
| 确定性Fixture种子 | `OLS-C1-V1-20260818`；相同Profile必须生成相同主键关系、时间分布和Payload尺寸Digest |
| 六聚合Fixture | 500,000 Party、500,000 Lead、120,000 Opportunity、60,000 ConflictReview、50,000 Contract、20,000 TransferRequest，另有100,000最小Matter |
| Identity/授权Fixture | 500 InternalUser、80 OrgUnit、1,000 Membership/Position、4,000 CapabilityGrant、1,500 AuthorityAssignment、25,000 DataScopeGrant、250,000具体对象授权、10,000 Restriction、5,000 Delegation（含2,000有效）、100,000 CustomerAccessGrant |
| 运行记录Fixture | 3,000,000 Task＝400,000 OPEN（其中80,000逾期）＋100,000 WAITING＋2,350,000 DONE＋150,000 CANCELLED；另有5,000,000 Receipt、10,000,000 Event、20,000,000 Audit、1,000,000 EvidenceSubmission、2,000,000搜索投影 |
| 数据时间与Owner分布 | 5年；20%引用历史Registry版本；包含过期代理、Restriction和对象授权；活动Task按确定性80/20 Owner偏斜，且至少20%落在离岗、代理、撤权或转派边界Fixture中 |
| 预热 | 10分钟 |
| 稳定负载 | 30分钟：100次/秒操作读取、10次/秒业务命令、5%幂等重试、10个并发上传 |
| 短时突发 | 60秒：300次/秒读取、50次/秒业务命令 |
| 计时 | 服务端p95/p99；预期4xx不计系统5xx；OIDC、AI和Provider使用类型化Stub |

Profile必须包含受版本控制的`C1FixtureManifest`，记录`fixtureGeneratorDigest`和`expectedFixtureDigest`；种子相同但Generator或结果Digest变化时，原容量认证立即失效，不能把不同数据集冒充同一C1-V1。

核心C1只验证Evidence元数据、UploadSession状态和对象存储类型化Stub，不用外部存储/扫描器波动污染数据库容量认证。实际字节另以`EvidenceAdapterProfile-V1`认证：同区域私网对象存储、至少1Gbps有效链路、一个4 vCPU/8 GiB扫描Worker、10个并发UploadSession、30分钟稳定负载。固定语料共10,000件：4,000 PDF、2,500 JPEG/PNG、2,000 Office Open XML、1,000 UTF-8文本/结构化文本、500 ZIP/7z；其中50件为压缩炸弹/异常嵌套、100件为恶意或主动内容，均包含在前述类型数量内。大小分布固定为9,000件0～5MiB、900件5～50MiB、100件50～200MiB。

`EvidenceCorpusManifest`记录每个样本的Content Digest、MIME、大小档、压缩特征和预期UploadSession终态（FINALIZED或REJECTED），并生成`EvidenceCorpusDigest`。认证结果同时冻结对象存储实现/区域、网络测量、Scanner Engine与Signature Set Digest、Worker镜像Digest及P95/P99；Corpus、任一Adapter资源或版本变化都形成新Profile并重测§24.3 Evidence SLO。

Profile任何资源、数据分布或测量口径变化都形成新版本，不覆盖C1-V1。

## 28. 分层可执行契约与质量门禁

发布生命周期只有两级门禁：合并前`ChangeGate`与激活前`ReleaseGate`。`CapacityGate`是按触发条件生成、可在兼容范围内复用的专项容量认证证据，由ReleaseGate校验其有效性；它不是每次发布都经过的第三个常驻审批层。

### 28.1 四层测试

1. **领域不变量测试**：纯内存验证Task、Decision、Temporal、权限、版本和状态终态。
2. **模块契约测试**：只通过公开本地端口或具名用例服务，不穿透其他模块Repository。
3. **PostgreSQL运行协议测试**：使用真实PostgreSQL验证幂等、并发、队列、双Outbox、Inbox、UNKNOWN、Recovery和Tenant隔离。
4. **最小系统验收**：只维护一条销售黄金路径，异常优先在较小测试覆盖。

销售黄金路径：

```text
线索导入
→ 分配
→ 联系与跟进
→ 报价及冲突检查
→ ContractExecuted
→ PaymentGate或纯风险门槛满足
→ DealActivated
→ TransferSubmitted
→ PRE_TRANSFER解决
→ [同一事务：DecisionRecorded(TRANSFER_REVIEW, ACCEPT)
   + TransferAccepted + MatterCreated + MatterLink
   + 案管Task DONE + 销售结果回执]
```

### 28.2 ChangeGate

每次合并必须验证：

- 模块依赖方向合法。
- 领域不变量通过。
- RegistryManifest无重复、无Digest漂移且依赖闭合。
- 历史Event Fixture可反序列化。
- 仍存在的旧Task版本有Executor和CompletionContract。
- OpenAPI无未显式版本化的破坏性变化。
- 受影响模块真实PostgreSQL集成测试通过。
- 当前版本可以从空数据库初始化。
- Secret和高敏原文未进入Event、Receipt、日志或Trace。
- 与上一已激活`previousReleaseBundle`比较Registry、OpenAPI、Event Fixture和Flyway摘要，而不是只比较当前分支文件；首次发布改与不可变`GENESIS_BASELINE`比较。
- DataElementDefinition覆盖所有DTO/Event/Search/AI/Notification/Provider/Export字段；任何安全等级降低或披露扩大均触发显式安全评审。
- 源代码Secret扫描、SBOM生成和已知严重漏洞检查通过；限时例外必须具名审批。
- 三个SPA各自的OpenAPI生成客户端均可编译；Internal、Admin、Customer、Provider四条API安全链的隔离及跨Tenant负向测试通过。

### 28.3 ReleaseGate

激活发布前必须验证：

- 空库到当前版本安装成功。
- 从第二次发布开始，上一正式版本数据库`N → N+1`迁移成功；Genesis只执行空库初始化。
- Flyway、Schema Epoch、ReleaseManifest和RegistryManifest一致。
- API与Worker两种角色分别启动且边界正确。
- 小规模性能冒烟通过；完整C1容量认证由CapacityGate管理。
- 销售黄金路径通过。
- 历史Task、Event和Registry版本仍可解释。
- 从第二次发布开始，`N数据库 → N+1 Expand → N构建关键读写冒烟`通过，证明激活前的应用回退边界。

固定失败场景：

1. 重复命令只产生一个逻辑Receipt和一次业务效果。
2. 无权命令不产生聚合修改、Task、Event或Outbox。
3. Provider状态不确定进入UNKNOWN且不自动重发。
4. 旧Task和旧Event继续使用原版本语义。

Registry版本切换为`ACTIVE_FOR_NEW`必须同时满足ChangeGate、ReleaseGate；若本次变更命中§28.4条件，还必须绑定与当前Build/Profile Digest一致且未被变更条件失效的CapacityGate认证结果。容量认证不靠任意日历期限失效，而由热点实现、资源、Profile、Fixture/Corpus或依赖Digest变化精确失效。ReleaseActivator在数据库CAS时再次校验这些门禁引用，不能只相信部署流水线传入的布尔值。

### 28.4 CapacityGate

完整C1-V1不要求每次普通发布重复执行。以下情况必须运行：

- 首次生产发布。
- PostgreSQL主版本，热点路径SQL、查询计划、索引、分区或队列领取算法变化。
- 序列化、授权过滤、CurrentCard或搜索实现变化。
- C1 Verification Profile或参考资源变化。
- Evidence对象存储、扫描器、网络或Worker资源变化时运行对应EvidenceAdapterProfile CapacityGate，不要求无关数据库Profile重测。
- 最近一次认证结果对应的构建依赖已不再适用。

其他发布可以复用最近一次仍兼容的容量认证，并执行ReleaseGate的小规模性能冒烟。CapacityGate使用§27.4固定Profile，结果必须记录构建Digest、数据库Fixture Digest、资源、预热/持续时间和p95/p99。

### 28.5 AI测试

常规测试使用固定类型化AI Adapter，不调用真实模型。只验证：

```text
固定Candidate
→ 人工确认
→ 类型化Command
→ 权威重鉴权
→ 业务事实
```

模型效果由独立能力级EvalGate验证。

### 28.6 固定安全负向场景

1. Tenant A与B使用相同CommandId互不碰撞，也不能探测对方Receipt。
2. 同一作用域、同一CommandId、不同Payload只能得到永久冲突。
3. 不同ProviderAccount使用相同EventId可独立处理；同账户同EventId异Hash只能隔离。
4. CustomerGrant撤销后，旧会话、旧确认Token和未提交命令全部失败。
5. Internal Token不能调用Admin API；Admin Token不能代替业务Task。
6. API角色尝试构造ServiceActorCommand必须失败。
7. Tenant A的Task、Grant或Evidence不能绑定Tenant B的Subject。
8. RESTRICTED密文复制到其他Tenant、字段或记录后解密失败。
9. BootstrapGrant重放、过期或数据库已初始化时不能创建第二个根管理员。
10. EmergencyAccessGrant过期、撤销、自批或执行未登记Runbook时全部失败。
11. 未扫描文件、覆盖上传和主动内容主域内联预览全部被拒绝。
12. SecretStore或KMS不可用时，系统不会使用默认Secret或明文继续运行。
13. 客户端伪造`commandScope、tenantId、policyRef`或另一类信封字段时，在创建执行槽前被拒绝。
14. 浏览器跨Origin、缺失/错误CSRF Token、Internal Cookie调用Admin以及Admin Step-up复用到Internal时全部失败。
15. 同一Provider EventId异Hash不会改写原Inbox，只追加ConflictAttempt且不产生领域副作用；未验真请求不能创建Tenant Inbox。
16. REGISTERED_INGRESS的Payload不能选择ServiceActor或Command；未登记Ingress和未知Decoder只能隔离。
17. CustomerGrant/Actor在上传后、UploadSession进入FINALIZED前撤销时，该UploadSession只能REJECTED，原始字节不能形成可引用EvidenceSubmission。
18. API/Worker执行Audit UPDATE/DELETE/TRUNCATE被数据库拒绝；规定Audit追加失败时业务事务整体回滚。
19. 同一Ingress业务身份使用不同Provider EventId重放时不能创建第二个Subject；同业务键异Hash只能走Definition登记的Revision、人工核验或隔离路径。

### 28.7 MVP不建设的测试平台

- 不设置武断的统一代码覆盖率百分比。
- 不建设通用测试管理平台。
- 不建设大规模浏览器和视觉回归平台。
- 不做生产流量回放和混沌工程。
- 不做N/N-1混跑测试。
- 不要求真实OIDC、LLM和所有Provider进入普通CI。

## 29. 明确DEFERRED与RESERVED

### 29.1 DEFERRED

- 备份与灾难恢复框架，包括PITR、RPO、RTO、RecoveryEpoch和跨地域恢复。
- N/N-1滚动发布、蓝绿数据库和零停机承诺。
- Kafka、外部搜索引擎和独立事件平台。
- 多区域、多活和大规模SaaS运营控制台。
- 客户Evidence通用RAG、跨Matter向量检索与长期AI记忆。
- 自动化Evidence物理销毁。
- Matter登记、分类、分配、办理、期限、成果和能力包实现。
- 完整开票、退款、冲正、佣金与会计总账。
- 动态工作流、规则DSL和本体编辑器。
- 旧代码仓库兼容、旧系统历史数据迁移和一次性业务切换。
- 通用报表、批量导出和数据仓库接口实现。
- 正式HA部署拓扑及对外可用性SLA。
- 第一次物理删除表、列或历史运行结构所需的ContractGate。

### 29.2 RESERVED

- `MatterCreated`稳定消费者边界；只供提交后的后MVP登记模块使用，不能用于创建Matter、补写MatterLink或完成转案接收。
- `MatterScopedAuthorizationPort`。
- `MatterCapabilityPackageRegistry`。
- `LedgerExportPort`。
- `SearchIndexPort`。
- 外部消息基础设施适配端口。
- 灾备安全围栏扩展位，但MVP不实现。

RESERVED只表示端口和依赖方向稳定，不表示提前建表、建服务、建Task或建页面。

## 30. 基础框架验收不变量

1. 任何模块不能直接修改其他模块私有表。
2. Chat、AI、Provider、管理员和Worker都不能绕过具名用例服务。
3. 除一次性Installation Bootstrap协议外，正式领域及管理写入只能来自四类互斥命令信封之一。允许不经命令信封接收的技术Ingress仅正向枚举为：`ProviderInboxRecord`、`ProviderInboxConflictAttempt`、`SecurityIngressAttempt`、认证安全尝试、与已签发UploadSession准确对应的原始对象字节接收，以及OpenTelemetry接收。它们只能验真并追加技术/隔离事实，不能直接修改业务聚合、Task、Decision、WaitReceipt或ExternalAction，不能创建业务DomainEvent/ExternalDispatch Outbox；后续业务处理必须构造已登记的ServiceActorCommand。新增Ingress类型必须升级本契约。
4. Provider和AI永远不是领域Actor。
5. OIDC Claim不能直接成为业务权限。
6. 一个Task只有一个具体Owner、一个命令和一个完成事实。
7. WAITING Task必须先转OPEN才能由完成事实进入DONE；权威取消或替代只能使其CANCELLED。
8. 旧Task、Event、Decision、TemporalSnapshot和Evidence版本在升级后仍可解释。
9. 相同命令重放只返回一个逻辑Receipt和一次业务效果。
10. Tenant A的Task、Grant、Evidence、Inbox、Outbox或Receipt不能引用、泄露或探测Tenant B对象。
11. ExternalAction UNKNOWN不得普通重发，DISPATCHING租约过期不得回到PENDING。
12. WaitReceipt与WAITING Task是不同对象和状态机。
13. DomainEvent Outbox与ExternalDispatch Outbox不能合并。
14. 只有由FINALIZED UploadSession事务创建且扫描通过的准确EvidenceSubmission才能进入业务引用、下载或AI。
15. `UploadSession.FINALIZED`及Evidence技术检查通过不等于业务VERIFIED。
16. Search、异步投影和AI Candidate都不是权威事实源。
17. RESTRICTED原文不得进入普通日志、Trace、搜索摘要或通知标题。
18. 未注册配置、Purpose、工具、数据披露或ServiceActor默认拒绝。
19. 管理员不能直接完成业务Task或修改业务聚合。
20. 紧急访问不能突破Tenant、审计和领域不变量。
21. API与Worker版本或RegistryManifest不一致时不得READY。
22. 数据库破坏性变更不得与Expand同版完成。
23. 已产生新版本专属事实或外部效果后只能向前修复。
24. ChangeGate、ReleaseGate及ReleaseGate要求的有效容量认证证据未通过时，不得激活新Registry版本。
25. C1容量超出不能触发静默丢弃、截断或无审计删除。
26. 灾备未设计完成前，不得宣称具备RPO、RTO或跨区域恢复保障。
27. TransferAccepted、Matter创建、MatterLink、案管Task完成和销售结果回执必须遵守冻结的同事务契约。
28. 任何Registration、Classification、Allocation、Handling或能力包模块都不得修改Matter Core或销售六聚合的私有事实。

## 31. 下一层详细设计边界

本规格通过后，可以按独立分册继续设计，但仍不应一次性形成超大实施计划：

1. 项目与包结构、构建模块及ArchUnit规则。
2. PostgreSQL Schema、表、索引、唯一约束和队列领取SQL。
3. 四类命令信封、CommandReceipt与Problem Detail的API Schema。
4. Identity、四轴权限和管理后台详细模型。
5. Responsibility、Temporal和WorkCard Query Facade详细模型。
6. ExternalAction、Provider Adapter和恢复协议。
7. Evidence UploadSession、对象存储和Retention详细模型。
8. 三个SPA的路由、状态边界与OpenAPI客户端分包。
9. RegistryManifest、ReleaseManifest及ChangeGate/ReleaseGate契约。
10. 销售黄金路径的测试Fixture与验收清单。

每一分册都必须引用本规格和领域本体，不得重新发明通用状态、工作流或授权模型。
