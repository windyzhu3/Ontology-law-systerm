# Ontology Law System 项目结构、模块边界与构建契约 v1.0

> 状态：正式冻结版；九个设计章节已经用户逐节确认
> 日期：2026-08-18
> 兼容修订：2026-08-19，随已冻结的PostgreSQL物理模型总纲加入DatabaseContractBundle生成链并升级ReleaseManifest Schema v2
> 范围：销售至转案MVP的项目组织、Java包边界、模块依赖、运行角色、持久化、前端工作区、代码注册表、测试与构建门禁
> 明确不包含：实施计划、逐表DDL、逐接口字段Schema、工期估算、后MVP案件办理实现及备份与灾难恢复框架

## 1. 文档定位与适用顺序

本规格是《Ontology Law System 基础框架设计规格 v1.0》的下一层技术结构契约，回答以下问题：

1. 一个Spring Boot工程如何保持可验证的领域模块边界。
2. 六个销售至转案聚合、Matter Core和平台能力如何映射为代码模块。
3. API、Worker、四个安全通道和三个SPA如何在同一仓库中隔离。
4. Maven、npm、Flyway、jOOQ、OpenAPI、Spring Modulith和ArchUnit如何形成单一可执行门禁。
5. 后MVP案件办理模块如何保留接入能力但不进入当前实现范围。

本规格不重新定义领域事实、Task完成事实、时效、Decision、Evidence、ExternalAction或Matter本体。解释优先级固定为：

1. 《律所待办驱动智能管理系统：目标产品基线 v2.0》。
2. 《待办驱动律所管理系统：总体架构与本体完整设计》。
3. 《销售MVP工作卡与对话状态设计 v1.0》。
4. 《最小Matter身份与后MVP扩展契约 v1.0》。
5. 《Ontology Law System 基础框架设计规格 v1.0》。
6. 本规格。

若发生冲突：

- 领域事实、责任、完成事实、时效、Matter和MVP范围以上游领域规格为准。
- 项目目录、Java包、模块依赖、代码生成、测试与构建门禁以本规格为准。
- 本规格将基础框架v1.0中的 public-api/application/domain/infrastructure 逻辑分层细化为 api + internal 物理封装；这只是更严格的代码组织，不改变“其他模块只能访问公开API”的原边界。
- 旧代码仓库和高保真原型都不是兼容性基线。原型只作为交互参考资产。

## 2. 冻结决策摘要

本规格冻结以下方案：

~~~text
Maven Wrapper
+ 单一后端Maven工程
+ 一个Spring Boot Jar
+ npm workspaces管理三个SPA和一个纯UI包
+ 包级模块化单体
+ api + internal强边界
+ Spring Modulith管理模块DAG
+ ArchUnit管理横切禁令
+ 事实起点模块拥有具名原子用例
+ CommandRuntime开启唯一外层事务
+ jOOQ单一持久化范式
+ Flyway是Schema唯一事实源
+ 四份独立OpenAPI契约
+ 服务端权威前端状态
+ 单测试树和真实PostgreSQL协议测试
+ 单一ChangeGate
+ 不可变RegistryManifest
~~~

当前不引入：

- Maven领域子模块。
- 微服务。
- Kafka。
- 通用工作流、BPMN、动态规则DSL或通用Coordinator。
- JPA、Hibernate和Spring Data。
- 第二数据库和分布式事务。
- 统一前端业务核心包。
- 空的后MVP模块、表或Task。
- 通过运行时类名、脚本或配置选择业务Handler。

## 3. 仓库与构建单元

### 3.1 目标仓库结构

~~~text
/
├── .mvn/
├── mvnw
├── mvnw.cmd
├── package.json
├── package-lock.json
├── .npmrc
├── <Node与npm版本锁定文件>
├── backend/
│   ├── pom.xml
│   ├── src/main/java/
│   ├── src/main/resources/
│   ├── src/generated/java/
│   ├── src/test/java/
│   └── src/test/resources/
├── apps/
│   ├── internal-workbench/
│   ├── admin-console/
│   └── customer-entry/
├── packages/
│   └── ui/
├── contracts/
│   ├── openapi/
│   │   ├── internal.yaml
│   │   ├── admin.yaml
│   │   ├── customer.yaml
│   │   └── provider.yaml
│   └── database-security/
│       ├── manifest.yaml
│       └── manifest.schema.json
├── ci/
│   ├── change-gate
│   ├── database-security-bootstrap
│   └── database-security-reconcile
└── docs/
~~~

现有高保真原型在实施迁移前继续作为参考资产保存，但必须满足：

- 不加入根npm workspace。
- 不被三个生产SPA导入。
- 不参与生产构建产物。
- 不作为服务端状态机、API Schema或领域规则的实现来源。

### 3.2 构建边界

- backend 是唯一后端Maven工程，只生成一个Spring Boot Jar。
- API和Worker是同一Jar的两种运行角色，不是Maven模块。
- 根npm workspace只管理三个SPA和 packages/ui。
- 根package.json必须private=true，workspaces只能列出三个SPA和packages/ui；固定使用npm ci。
- Maven不驱动Node，npm不驱动Java。
- ci/change-gate是唯一组合入口，分别调用Maven Wrapper和npm workspace命令。
- JDK、Maven Wrapper、Node、npm、插件和依赖版本由受控工具链清单固定。
- 禁止SNAPSHOT、动态版本和未锁定前端依赖。

## 4. Java顶层模块

### 4.1 顶层包

Java根包的最终反向域名由实施ADR锁定；根包之下固定为：

~~~text
<base>
├── bootstrap
├── controlcli
├── apihost
├── workerhost
├── sharedkernel
├── party
├── lead
├── opportunity
├── conflict
├── contract
├── transfer
├── mattercore
├── executionruntime
├── identityaccess
├── responsibility
├── temporal
├── audit
├── evidence
├── externalaction
├── configuration
├── aigateway
├── workbench
├── admin
├── jurisdictionpolicy
├── legalcontentgovernance
└── observabilitycontract
~~~

Java包名使用无连字符形式；Spring Modulith模块ID继续使用稳定领域名，例如 matter-core、execution-runtime、identity-access。

### 4.2 当前业务模块

销售至转案六聚合映射为：

| 本体聚合 | Java模块 |
|---|---|
| Party | party |
| Lead | lead |
| Opportunity | opportunity |
| ConflictReview | conflict |
| Contract | contract |
| TransferRequest | transfer |

mattercore是销售闭环终点的最小Matter身份模块，不属于六聚合。

以下对象不拆分成新模块：

- QuoteRevision属于opportunity。
- FeeTerms、SignaturePlan、PaymentGate和PaymentConfirmation属于contract。
- MaterialManifest属于transfer。
- DealActivated是contract事实，不建立Deal模块。

### 4.3 当前平台模块

当前创建的平台模块为：

- executionruntime
- identityaccess
- responsibility
- temporal
- audit
- evidence
- externalaction
- configuration
- aigateway
- workbench
- admin
- jurisdictionpolicy
- legalcontentgovernance
- observabilitycontract

### 4.4 后MVP保留边界

下列能力不创建空包、空模块、表或Task：

- Matter Registration & Intake。
- Matter Classification。
- Matter Allocation & Team。
- Handling Kernel。
- 综法、非诉、诉讼和执行能力包。
- Legal Deadline。
- Work Product与结案。
- 完整财务、开票和Ledger Export。

MVP代码只实现MatterOpeningPort、最小Matter Identity与MatterCreated Event。MatterScopedAuthorizationPort和MatterCapabilityPackageRegistry当前只保留文档级名称、语义和依赖方向，不创建代码实现、表、Task或Matter Core内部模型；后MVP启用时分别由授权绑定模块和能力包绑定模块拥有。Matter Core不得导入、保存或枚举具体能力包。

## 5. 模块内部封装

### 5.1 统一结构

每个实际模块按需采用：

~~~text
<module>/
├── package-info.java
├── api/
│   ├── command/
│   ├── query/
│   ├── event/
│   └── integration/
│       └── wiring/
└── internal/
    ├── application/
    │   ├── usecase/
    │   ├── handler/
    │   ├── port/
    │   └── mapper/
    ├── domain/
    │   ├── model/
    │   ├── service/
    │   └── repository/
    ├── persistence/
    │   ├── repository/
    │   ├── query/
    │   ├── mapper/
    │   └── jooq/generated/
    ├── adapter/
    │   ├── inbound/
    │   └── outbound/
    └── config/
        ├── registry/
        └── wiring/
~~~

不存在实际内容的目录不得预建。

api.integration.wiring是唯一技术装配面，只允许bootstrap访问。它公开CoreModuleWiring以及实际需要的ApiAdapterWiring或WorkerAdapterWiring，包装本模块Internal Bean但不暴露实现类型。internal.config.wiring继续保存模块私有装配细节。

每个业务和平台模块根包除package-info.java外不得放置可引用类型；所有类型必须进入api或internal。这样不能借用Spring Modulith对模块根包的默认可见性绕过Named Interface。

### 5.2 四类API Facet

| Facet | 责任 | 禁止 |
|---|---|---|
| command | 类型化不可变Command模型 | Handler、聚合、Repository、Actor或Tenant伪造字段 |
| query | 面向用例的Query Facade、Lookup Port及只读结果 | jOOQ Record、可变领域对象、跨Schema SQL |
| event | 有稳定消费者价值的版本化已提交事实 | 发布全部内部变化、替代原子主链 |
| integration | 具名跨模块本地能力 | 通用execute、动态步骤、远程调用 |

每个实际Facet分别声明为Spring Modulith Named Interface。依赖必须精确到 module-id::facet，不能因为读取需要而获得整个模块API。

四类Facet是语义分类，不限制进一步缩窄Named Interface。例如execution-runtime可以在integration下分别声明handler-spi、五个按能力Owner拆分的Runtime SPI和四类gateway；调用方只能取得其被明确允许的最窄接口。

业务模块之间默认不能导入对方的command。Command模型由对应Host经Command Gateway提交；Command Handler位于internal.application。

### 5.3 内部层规则

- Application中的每个正式写操作只有一个具名UseCase。
- 每个Command只有一个注册的CommandHandler。
- Handler只适配CommandRuntime；主要业务规则在UseCase与Domain。
- Mapper只做结构转换，不得隐藏业务判断。
- Domain保持纯Java，不依赖Spring、Jackson、jOOQ、HTTP、文件系统或Provider SDK。
- Repository接口属于模块内部，不进入API。
- 聚合只保存准确类型化引用，不持有其他模块聚合对象。
- Persistence完成jOOQ Record与领域/读取模型的转换。
- Adapter只能实现明确Port，SDK类型不得向内泄漏。

### 5.4 可见性与命名

- 只有API类型和必要装配点使用public。
- Internal类优先使用包可见性。
- 禁止 CommonService、BaseManager、GenericProcessor、DataHelper、WorkflowUtil、EntityService等无明确责任命名。
- 禁止以 Map<String,Object>承载Command、Event、Port参数、领域对象或未来扩展字段。
- 禁止通过反射、配置类名或动态Bean查找选择业务Handler。

## 6. 封闭白名单式Shared Kernel

sharedkernel只允许所有模块必须共同理解、语义稳定的技术值类型。初始白名单为：

- TenantId。
- ActorRef。
- SubjectRef。
- CommandId。
- EventId。
- CorrelationId。
- CausationId。
- OccurredAt与RecordedAt，或等价的明确时间语义。
- 不可变Version、VersionRef和Digest。
- RegistryCode与RegistryVersionRef。
- 极少量类型化技术错误基类和不透明分页游标。

sharedkernel不得包含：

- Lead、Contract、Matter等业务对象。
- Money、折扣、付款门槛等有明确领域Owner的值对象。
- 通用业务状态。
- 工作流Node、Edge或规则表达式。
- Repository、BaseEntity、BaseService。
- DTO、JSON对象或业务扩展Map。
- Spring、Jackson、jOOQ或Provider SDK依赖。

每个业务ID和业务枚举仍由事实Owner模块定义。新增Shared Kernel类型必须通过架构ChangeGate、更新封闭类型白名单并进入Release构建摘要；sharedkernel不能依赖任何其他模块。

Shared Kernel是一个特殊的只读技术模块，只暴露shared-kernel::types Named Interface，不包含Wiring、Command、Repository或运行Bean。其封闭类型集合由架构白名单和Release构建摘要管理；除非未来正式定义SharedKernelTypeDefinition，否则不把每个类型作为Registry Definition。

## 7. 业务模块依赖与原子用例

### 7.1 业务依赖DAG

Java import方向固定为：

~~~text
lead        → party、opportunity
opportunity → party、conflict
conflict    → party
contract    → opportunity、conflict
transfer    → contract、conflict、mattercore
party       → 无其他业务模块
mattercore  → 无其他业务模块
~~~

Named Interface白名单至少为：

| 调用方 | 允许访问 |
|---|---|
| party | execution-runtime::deployment-fence-runtime |
| lead | execution-runtime::deployment-fence-runtime、party::query、opportunity::integration |
| opportunity | execution-runtime::deployment-fence-runtime、party::query、conflict::integration |
| conflict | execution-runtime::deployment-fence-runtime、party::query |
| contract | execution-runtime::deployment-fence-runtime、opportunity::query、conflict::query |
| transfer | execution-runtime::deployment-fence-runtime、contract::query、contract::transfer-initialization-spi、conflict::query、conflict::integration、matter-core::integration |
| mattercore | execution-runtime::deployment-fence-runtime |

### 7.2 端口归属

- 查询能力由数据Owner在api.query定义。
- 跨模块写能力默认由被调用的事实Owner在api.integration定义。
- 调用方只依赖能力Owner的Named Interface。
- 参数使用能力Owner定义的输入类型和Shared Kernel稳定引用，避免能力Owner反向依赖调用方。

contract成交后必须初始化Transfer，而transfer接收时又必须读取Contract权威门槛。为避免 contract ↔ transfer 编译环，登记唯一依赖倒置：

~~~text
contract.api.integration.transferinitialization.InitializeTransferForActivatedDealPort
                         ↑
          transfer.internal.adapter固定实现
~~~

contract::transfer-initialization-spi是专用于此原子边界的窄Named Interface。运行时由Contract用例调用Transfer实现；Java依赖仍只有 transfer → contract.api。该Port只能有一个固定实现，不得动态选择Handler。

### 7.3 五个跨模块原子边界

| 具名用例与Owner | 同一事务的最低结果 |
|---|---|
| lead.RecordContactResultUseCase | ContactResultRecorded(VALID)＋OpportunityOpened＋首个商机责任 |
| opportunity.RecordQuoteResponseUseCase | QuoteResponseRecorded(ACCEPTED)＋QuoteAccepted＋准确PRE_CONTRACT审查实例/责任 |
| transfer.SubmitTransferUseCase | TransferSubmitted(snapshotVersion/digest)＋同快照PRE_TRANSFER审查实例＋销售WaitReceipt |
| contract.ActivateDealUseCase | DealActivated＋TransferRequestInitialized＋准确Owner的SUBMIT_TRANSFER Task |
| transfer.AcceptTransferUseCase | DecisionRecorded(TRANSFER_REVIEW, ACCEPT，匹配当前task、CompletionContract版本及全部SubjectBinding)＋TransferAccepted＋Matter Core签发MatterRef并写MatterCreated＋write-once MatterLink＋销售WaitReceipt结果更新；只有该DecisionRecorded完成案管Task |

用例Owner按当前命令、唯一完成事实和成立判断的事实Owner确定，不能按写表数量或最终创建对象确定。

每个参与模块仍自行校验并写自己的事实。同事务不等于共享聚合，也不允许调用方写目标模块私表。

AcceptTransferUseCase表中全部结果必须在同一事务中成立，任一步失败全部回滚；TransferAccepted、MatterCreated、MatterRef或MatterLink都不能替代DecisionRecorded作为案管Task的唯一完成事实。

### 7.4 禁止God Module

- 禁止新增workflow、orchestration、sales-flow、process-engine或通用coordinator模块。
- 只有上述五个用例可以在一个事务内调用其他业务模块写Port。
- 新增第六个原子边界必须升级本规格和RegistryManifest。
- 其他跨模块协作默认使用稳定Query Port或提交后受限Event。
- Event消费者只能写自己的事实，不能回写生产者私表。
- 禁止execute(List<Step>)、动态路由、Node/Edge、规则DSL和通用状态机。

## 8. 平台模块依赖与能力归属

### 8.1 基础层

- sharedkernel不依赖任何模块。
- configuration只提供类型化配置、Assignment和Secret Handle，不解释业务规则。
- observabilitycontract只注册日志、指标和Trace语义，不参与业务判定。
- jurisdictionpolicy只提供代码版本化策略包、时区、日历及签署/材料政策引用。
- audit只追加法律与管理审计，不成为DomainEvent或业务事实源。

### 8.2 运行与治理层

Execution Runtime不能直接编译依赖Identity、Responsibility或Audit，否则这些事实Owner实现自己的Command Handler时会形成模块环。固定采用消费者侧依赖倒置：

~~~text
execution-runtime::handler-spi
  ← 所有具名Command Owner实现

execution-runtime::actor-resolution-runtime-spi
  ← identity-access唯一实现ActorResolutionRuntimePort
execution-runtime::authorization-runtime-spi
  ← identity-access唯一实现AuthorizationRuntimePort
execution-runtime::responsibility-runtime-spi
  ← responsibility唯一实现ResponsibilityUnitOfWorkPort
execution-runtime::audit-runtime-spi
  ← audit唯一实现AuditAppendRuntimePort
execution-runtime::operational-projection-runtime-spi
  ← workbench唯一实现OperationalProjectionUnitOfWorkPort
~~~

Execution Runtime本身只依赖shared-kernel::types和observability-contract::integration；它通过注入上述五个窄Runtime SPI完成当前身份、授权、责任、审计与强一致操作投影，不导入这些模块的实现或API。

Execution Runtime在api.integration下进一步暴露最窄Named Interface：

- handler-spi：仅供具名Command Owner实现Handler。
- actor-resolution-runtime-spi与authorization-runtime-spi：仅供Identity实现。
- responsibility-runtime-spi：仅供Responsibility实现。
- audit-runtime-spi：仅供Audit实现。
- operational-projection-runtime-spi：仅供WorkBench实现。
- deployment-fence-runtime：由Execution Runtime拥有并实现，只向所有会开启API/Worker数据库事务的模块暴露`DeploymentFenceRuntimePort`；调用必须以MANDATORY语义加入当前事务，在任何Tenant/业务SQL前取得Rank 5门禁并返回冻结的Security/Verification Generation。调用方不得直接读取`platform_meta`，不得提供Noop、缓存或本地替代实现。
- internal-task-gateway：仅Internal Channel调用。
- internal-admin-gateway：仅Admin Channel调用。
- customer-grant-gateway：仅Customer Channel调用。
- service-actor-gateway：仅Worker Host调用。
- receipt-query位于api.query，仅供准确CommandId结果查询。
- release-control：仅一次性controlcli的ReleaseActivator与PrincipalBindingActivator调用，暴露类型化ReleaseControlPort和PrincipalBindingControlPort。
- security-verification：仅一次性controlcli的SecurityProbe调用，暴露类型化SecurityVerificationPort。

供Worker Host领取技术工作而暴露的接口全部是各Owner在api.integration下进一步缩窄的Named Interface，固定为：

| Owner Named Interface | 唯一用途 |
|---|---|
| execution-runtime::worker | 领取InternalWorkItem、DomainEvent Outbox及执行运行兼容性检查 |
| temporal::worker | 领取到期Milestone并形成已登记Trigger |
| external-action::worker | 领取External Dispatch、Probe和已验真的Provider Inbox处理项 |
| evidence::worker | 领取上传验证、扫描和受控对账项 |
| ai-gateway::worker | 领取AIInvocation技术工作并持久化Candidate或降级结果 |
| workbench::projection-worker | 领取可重建的异步发现投影工作 |

这些接口只能由workerhost消费，不能包含任意业务Command选择、ServiceActor伪造字段或对其他模块私表的写入口。形成正式业务事实时，Worker仍必须通过service-actor-gateway提交准确的已登记命令；纯技术生命周期只能由其Owner的受限技术事务推进。

除shared-kernel::types和各模块自己的wiring外，平台依赖白名单为：

| 模块 | 允许依赖的Named Interface |
|---|---|
| executionruntime | observability-contract::integration |
| configuration | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime |
| observabilitycontract | 无 |
| jurisdictionpolicy | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime |
| audit | execution-runtime::audit-runtime-spi、execution-runtime::deployment-fence-runtime |
| identityaccess | execution-runtime::handler-spi、execution-runtime::actor-resolution-runtime-spi、execution-runtime::authorization-runtime-spi、execution-runtime::deployment-fence-runtime、configuration::query |
| temporal | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime、jurisdiction-policy::query |
| responsibility | execution-runtime::handler-spi、execution-runtime::responsibility-runtime-spi、execution-runtime::deployment-fence-runtime、identity-access::query、temporal::query、temporal::integration |
| evidence | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime、identity-access::query、audit::typed-append、configuration::query |
| externalaction | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime、evidence::query、configuration::query、observability-contract::integration |
| aigateway | execution-runtime::deployment-fence-runtime、identity-access::query、evidence::query、audit::typed-append、configuration::query、observability-contract::integration |
| legalcontentgovernance | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime、evidence::query、jurisdiction-policy::query |
| workbench | execution-runtime::handler-spi、execution-runtime::operational-projection-runtime-spi、execution-runtime::deployment-fence-runtime、party::query、lead::query、opportunity::query、conflict::query、contract::query、transfer::query、matter-core::query、responsibility::query、identity-access::query、evidence::query、ai-gateway::query、ai-gateway::integration |
| admin | execution-runtime::handler-spi、execution-runtime::deployment-fence-runtime、identity-access::admin-query、configuration::admin-query、audit::query、evidence::retention-admin |

audit::typed-append只能接收封闭的类型化审计输入，禁止通用JSON、Map或任意文本事件。ai-gateway::integration只允许创建、读取或处理Candidate技术生命周期，不能暴露任何业务Command或Write Port。

任何实际allowedDependencies都必须写到准确module-id::named-interface；禁止只写模块名或通配符。

### 8.3 业务模块使用平台能力

业务模块可按已登记用途依赖：

- execution-runtime::handler-spi，实现类型化Command Handler；业务Command模型仍由对应事实Owner的api.command拥有。
- execution-runtime::deployment-fence-runtime，仅由登记的Command、Query、Worker或技术Ingress事务入口以MANDATORY语义在首条Tenant/业务SQL前取得Rank 5门禁；不得用于读取发布控制数据或绕过Owner Port。
- responsibility::integration，创建Task、Decision和WaitReceipt。
- evidence::query/integration，引用准确Evidence版本。
- external-action::integration，创建外部动作意图。
- jurisdiction-policy::query，取得冻结策略。
- legal-content-governance::query，取得模板和批准内容基线。
- observability-contract::integration，使用代码注册的低基数遥测语义。

业务模块不得直接依赖identityaccess内部实现、audit Repository、temporal内部时钟、aigateway、workbench、admin或任何外部SDK。

AI只能经WorkBench形成Candidate；业务Application不能调用AI后自动写正式事实。

### 8.4 技术适配器所有权

| 技术 | 唯一允许位置 |
|---|---|
| OIDC/JWT原始解析 | 仅apihost.internalchannel.security.oidc与apihost.adminchannel.security.oidc |
| Customer身份输入 | customerchannel中的Customer Session与Grant验证类型 |
| Provider身份输入 | providerchannel中的ProviderTransportPrincipal、签名或mTLS验证类型 |
| S3与恶意文件扫描SDK | evidence.internal.adapter |
| LLM SDK | aigateway.internal.adapter |
| 签章、消息、银行等Provider SDK | externalaction.internal.adapter |
| Secret/KMS SDK | configuration或登记的加密Adapter |
| jOOQ生成类型 | Owner模块internal.persistence |

Raw Jwt和OIDC SDK类型不得进入identityaccess、业务模块、Customer或Provider Channel；OIDC Role/Group Claim不构成系统内权限。Evidence不得依赖业务模块。External Action Adapter不解释付款、签署、冲突或转案的业务成功条件。Provider技术成功不能直接形成业务有效性事实。

## 9. 运行角色与安全通道

### 9.1 角色化装配

同一Jar只允许：

~~~text
APP_ROLE=api
APP_ROLE=worker
~~~

APP_ROLE没有默认值。缺失、未知或同时装配两种角色时必须启动失败。

唯一例外是一次性控制命令启动：`CONTROL_COMMAND=release-activate | principal-binding-activate | security-probe`时必须没有`APP_ROLE`，且进程执行完即退出；`APP_ROLE`与`CONTROL_COMMAND`同时存在、均缺失或值不在封闭枚举中都启动失败。控制命令不是常驻运行角色。

~~~text
<base>
├── bootstrap/
│   ├── AppRole
│   ├── RoleImportSelector
│   ├── ControlCommandImportSelector
│   └── RoleStartupGuard
├── controlcli/
│   ├── wiring/
│   │   └── ControlCliWiring
│   ├── releaseactivation/
│   ├── principalbinding/
│   └── securityprobe/
├── apihost/
│   ├── wiring/
│   │   └── ApiHostWiring
│   ├── internalchannel/
│   ├── adminchannel/
│   ├── customerchannel/
│   └── providerchannel/
└── workerhost/
    ├── wiring/
    │   └── WorkerHostWiring
    ├── claim/
    ├── temporal/
    ├── domainoutbox/
    ├── externaldispatch/
    ├── providerinbox/
    ├── evidence/
    ├── ai/
    └── projection/
~~~

bootstrap、controlcli、apihost和workerhost是装配与传输Host，不属于领域Application Module Catalog；Spring Modulith的CLOSED模块发现只覆盖sharedkernel、业务模块和平台模块。四类Host由ArchUnit与ApplicationContext测试管理，不能被业务或平台模块反向依赖。

根应用禁止扫描整个base package后再排除Bean。RoleImportSelector必须按唯一APP_ROLE正向导入：

- 每个实际提供运行Bean的模块所暴露的module::wiring Named Interface；sharedkernel等无运行Bean模块不得伪造空Wiring。
- API角色需要的模块ApiAdapterWiring与唯一`apihost.wiring.ApiHostWiring`；后者只正向导入四个Channel的封闭配置。
- Worker角色需要的模块WorkerAdapterWiring与唯一`workerhost.wiring.WorkerHostWiring`；后者只正向导入登记的后台领取器。

`ControlCommandImportSelector`必须按唯一CONTROL_COMMAND正向导入最小`controlcli.wiring.ControlCliWiring`和准确Command Adapter；它不得导入ApiHostWiring、WorkerHostWiring、业务Controller、Worker Claim或四类业务Command Gateway。

除Wiring所属模块自身外，Bootstrap是唯一允许访问module::wiring的包。`ApiHostWiring`与`WorkerHostWiring`是Host唯一对Bootstrap公开的装配类型，不是业务API，也不得访问对方Host。公开Wiring只包装本模块Internal Bean，不暴露领域实现类型。需要角色差异的模块分别提供ApiAdapterWiring和WorkerAdapterWiring。Bootstrap只装配模块、选择准确Host并执行启动校验，不能包含业务条件。

### 9.2 四条API通道

| URL边界 | 唯一写入口 |
|---|---|
| /api/internal/** | InternalTaskCommand Gateway |
| /api/admin/** | InternalAdminCommand Gateway |
| /api/customer/** | CustomerGrantCommand Gateway |
| /api/provider/** | ProviderInboxIngressPort |

Controller只能处理传输校验、安全上下文、DTO映射和Gateway/Query调用。Controller不能取得CommandHandler、领域聚合、Repository或ServiceActor Gateway。

每个模块的api.command按唯一允许信封进一步声明internal-task-command-model、internal-admin-command-model、customer-grant-command-model或service-actor-command-model窄Named Interface；一个Command Definition只能属于其中一个。

Host依赖白名单固定为：

- internalchannel：
  - execution-runtime::internal-task-gateway、execution-runtime::receipt-query。
  - workbench::query、responsibility::query。
  - party::query、lead::query、opportunity::query、conflict::query、contract::query、transfer::query、matter-core::query、evidence::query、ai-gateway::query。
  - lead::internal-task-command-model、opportunity::internal-task-command-model、conflict::internal-task-command-model、contract::internal-task-command-model、transfer::internal-task-command-model、workbench::internal-task-command-model、responsibility::internal-task-command-model、evidence::internal-task-command-model、external-action::internal-task-command-model。
- adminchannel：
  - execution-runtime::internal-admin-gateway、execution-runtime::receipt-query。
  - admin::query、identity-access::admin-query、configuration::admin-query、audit::query、evidence::retention-admin、jurisdiction-policy::query、legal-content-governance::query。
  - identity-access::internal-admin-command-model、configuration::internal-admin-command-model、evidence::internal-admin-command-model、jurisdiction-policy::internal-admin-command-model、legal-content-governance::internal-admin-command-model。
- customerchannel：
  - execution-runtime::customer-grant-gateway、execution-runtime::receipt-query。
  - opportunity::customer-query、contract::customer-query、transfer::customer-query、evidence::customer-query。
  - opportunity::customer-grant-command-model、contract::customer-grant-command-model、transfer::customer-grant-command-model、evidence::customer-grant-command-model。
- providerchannel：仅external-action::provider-inbox-ingress。
- workerhost：
  - execution-runtime::service-actor-gateway、execution-runtime::receipt-query、execution-runtime::worker。
  - temporal::worker、external-action::worker、evidence::worker、ai-gateway::worker、workbench::projection-worker。
  - lead::service-actor-command-model、opportunity::service-actor-command-model、conflict::service-actor-command-model、contract::service-actor-command-model、transfer::service-actor-command-model、responsibility::service-actor-command-model、temporal::service-actor-command-model、evidence::service-actor-command-model、external-action::service-actor-command-model、ai-gateway::service-actor-command-model。
- controlcli：仅`execution-runtime::release-control`或`execution-runtime::security-verification`中与准确CONTROL_COMMAND对应的一个接口，不得访问业务Query/Command Facet。
- bootstrap：仅各运行模块::wiring，以及按唯一APP_ROLE二选一访问`apihost.wiring.ApiHostWiring`或`workerhost.wiring.WorkerHostWiring`；CONTROL_COMMAND路径只访问`controlcli.wiring.ControlCliWiring`。

未来新增任何Host依赖、Command Owner或Query Named Interface都必须升级本白名单和架构契约，不能靠“登记的其他接口”自动放行。任何Host都不得访问execution-runtime::handler-spi、任一execution-runtime::*-runtime-spi、业务internal、Repository或jOOQ类型。

四个Channel包之间双向零依赖。Installation Bootstrap作为adminchannel中的封闭安装期子入口并进入admin OpenAPI契约；它仍遵守UNBOOTSTRAPPED、一次性Grant和永久关闭规则，不转换成InternalAdminCommand。

Provider入口只做传输验真并追加Inbox或隔离记录，不创建Task、Decision、领域事实或业务Event。

### 9.3 Worker边界

- Worker只能经ServiceActor Gateway执行代码注册的后台命令。
- ServiceActorCommandEnvelope构造器只能存在于workerhost。
- Worker不开放业务REST，只开放受限健康和遥测端口。
- API角色不得领取Temporal、Outbox、Inbox或后台工作。

API和Worker必须使用不同数据库账号、Secret Scope和网络入口，并运行相同releaseId。身份或release不匹配时保持NOT_READY。

系统测试必须证明API Context没有Worker Bean、Worker Context没有业务Controller、Provider Channel无法取得ServiceActor Gateway。业务模块只能实现execution-runtime::handler-spi，不能调用任何Gateway。

## 10. CommandRuntime与Unit of Work

### 10.1 四类互斥信封

正式领域及管理写入只能使用：

- InternalTaskCommandEnvelope。
- InternalAdminCommandEnvelope。
- CustomerGrantCommandEnvelope。
- ServiceActorCommandEnvelope。

一个命令只能有一种Actor和一种信封。Provider止步Inbox，AI止步Candidate，两者都不是领域Actor。

### 10.2 执行链

事务外只允许完成请求大小、传输Schema、算法白名单、OIDC/签名的密码学验证和Canonical Payload Digest计算；这些步骤不得作出可变授权、Task或Subject成立判断。

~~~text
开启唯一PostgreSQL外层事务
→ 通过execution-runtime::deployment-fence-runtime取得Rank 5并冻结Deployment Security/Verification Generation；失败时在任何Tenant/业务SQL前回滚
→ 从当前权威绑定重新派生Tenant、Principal与Actor
→ 锁定或比较User / Grant / ServiceActor Activation及撤销Generation
→ 声明或锁定CommandExecutionSlot并比较PayloadDigest
→ 校验当前授权、职责分离、通道、Task / Intake / Trigger
→ 校验准确Subject Binding及当前版本
→ 构造最终NormalizedCommandContext
→ 调用具名UseCase和本地Integration Port
→ 写权威事实、Task、Decision与Event
→ 写Audit、Outbox和不可变CommandReceipt
→ 一次提交
~~~

CommandRuntime只负责信封、授权、幂等、Unit of Work、Audit、Receipt和Outbox的机械一致性。它不解释流程图、决定下一节点、运行动态条件或补全业务事实。

### 10.3 事务约束

- CommandRuntime使用唯一外层TransactionTemplate或等价边界。
- 当前授权、撤销Generation、幂等执行槽、Task和Subject版本校验必须位于该事务内。
- 撤权与命令并发必须通过锁或Generation比较形成确定提交顺序；撤权先提交时命令必须失败。
- 具名写UseCase必须以MANDATORY语义加入当前事务。
- 五个原子边界的Integration Port必须加入同一事务。
- 禁止REQUIRES_NEW、NESTED、NOT_SUPPORTED和异步Event补写原子主链。
- 事务中不得调用LLM、对象存储或外部Provider。
- 外部动作只写ExternalActionRequested及Outbox，提交后由Worker派发。
- CommandReceipt只能在最终事务中一次生成，不能先写临时Receipt再覆盖。
- 新Command的ExecutionSlot在失败回滚后不得遗留可覆盖占位；已提交相同CommandId和相同Payload只返回原不可变Receipt，相同CommandId不同Payload永久冲突。
- 任一规定事实、Task、Audit、Outbox或Receipt写入失败，整个事务回滚。

Provider Inbox接收、UploadSession隔离入库和Installation Bootstrap使用已经登记的受限技术事务，不伪装成第五类领域命令。

## 11. PostgreSQL、Schema、Flyway与jOOQ

### 11.1 Schema Owner

采用一个PostgreSQL集群、一个业务数据库、模块独立Schema：

| Owner模块 | Schema |
|---|---|
| executionruntime | execution_runtime；另唯一拥有部署级platform_meta Schema及`internal.platformmeta`技术包 |
| identityaccess | identity_access |
| responsibility | responsibility |
| temporal | temporal |
| audit | audit |
| evidence | evidence |
| externalaction | external_action |
| configuration | configuration |
| aigateway | ai_gateway |
| jurisdictionpolicy | jurisdiction_policy |
| legalcontentgovernance | legal_content |
| party、lead、opportunity、conflict | 同名Schema |
| contract、transfer、mattercore | contract、transfer、matter_core |
| workbench | workbench |

每张表只有一个Owner。其他模块不得直接读写，即使运行账号技术上可见。

### 11.2 数据访问不变量

- 正式持久化只使用jOOQ，不引入JPA、Hibernate、Spring Data或通用BaseRepository。
- Domain与Application不能直接依赖DSLContext。
- Query Facade可在Owner模块内直接使用jOOQ构建只读投影，不必恢复完整聚合。
- jOOQ Record、Table、Result和DAO不得离开Owner的internal.persistence。
- 所有Tenant Repository与Query Port以tenantId为首要参数，并在SQL源头加入Tenant Predicate。
- 禁止先按对象ID全库读取后再过滤，也禁止通过对象ID反推Tenant。
- Tenant内唯一键包含tenant_id。
- 聚合更新绑定tenant_id、aggregate_id和expectedVersion；影响行数不为1即并发冲突。
- 查询显式列字段，禁止SELECT *成为稳定接口。
- 跨Schema外键只允许稳定平台身份或已登记原子关系，并进入依赖清单。
- Workbench、搜索和异步发现投影都不是事实Owner。
- Evidence原始字节不进入PostgreSQL。

### 11.3 Flyway

迁移目录按Owner组织：

~~~text
backend/src/main/resources/db/migration/<module-id>/
V<global-version>__<module>__<phase>__<description>.sql
~~~

所有模块共享一个全局单调版本序列和一套Flyway History。SQL必须显式指定Schema。

`platform_meta`不新增Spring Modulith业务模块。其迁移目录使用稳定`platform-meta` module-id，但`MigrationDescriptor.ownerModule=executionruntime`；jOOQ生成到`executionruntime.internal.platformmeta.persistence.jooq.generated`。Execution Runtime以`release-control`和`security-verification` Named Interface向一次性控制CLI/探针暴露类型化`ReleaseControlPort、PrincipalBindingControlPort、SecurityVerificationPort`；另以`deployment-fence-runtime`向所有登记事务Owner暴露唯一`DeploymentFenceRuntimePort`，只执行MANDATORY Rank 5门禁并返回冻结Generation。API/Worker Controller及其他模块不得直接访问该Schema、Repository或jOOQ类型。

所有模块迁移目录必须由一次Flyway调用同时加载，locations按稳定Module Catalog排序；全库唯一History固定为部署级platform_meta.flyway_schema_history。禁止模块独立启动Flyway、维护自己的History或只迁移“当前模块”。ChangeGate与发布迁移的固定调用入口为：

~~~text
./mvnw -f backend/pom.xml ...
~~~

运行身份：

- API账号只拥有API需要的DML和查询权限。
- Worker账号只拥有后台工作需要的DML权限。
- Migration账号在受控发布期间拥有DDL权限。
- 运营只读账号只能访问受控View。

API与Worker均关闭自动Migrate，启动只执行Validate和兼容性门禁，不执行DDL。ChangeGate和受控发布迁移只使用Migration账号。

MVP禁止可变R__迁移、长事务业务回填和直接物理删除。大回填使用可续跑BackfillRun；纯数据回填不生成Task、Decision或业务Event。物理Contract必须在后续独立ContractGate执行。

### 11.4 jOOQ生成快照

~~~text
backend/src/generated/java/<base>/<owner>/internal/persistence/jooq/generated/
~~~

- 生成源码作为机械快照提交Git，禁止人工修改。
- backend/pom.xml必须把backend/src/generated/java显式登记为compile source root。
- 普通编译和IDE不依赖临时数据库。
- ChangeGate使用固定PostgreSQL镜像，从空库执行当前Flyway，再按Owner Schema生成。
- 重生成结果必须与仓库快照完全一致。
- Flyway仍是唯一Schema真源，生成快照不是第二DDL来源。
- 禁止连接开发、共享测试或生产数据库生成代码。
- 每个Owner Schema使用独立inputSchema、目标目录和Java包；生成任务只能清理该Owner的准确目录。
- 禁止在生成源码中写入时间、绝对路径、主机名或其他非确定信息。
- PostgreSQL、jOOQ Generator、Locale、Encoding、换行、命名策略和类型映射版本必须固定。
- ChangeGate重生成后必须对src/generated/java执行零差异检查。

## 12. OpenAPI与前端工作区

### 12.1 四份契约

~~~text
contracts/openapi/
├── internal.yaml
├── admin.yaml
├── customer.yaml
└── provider.yaml
~~~

四份契约独立维护，不能从一个Mega Spec按路径切分。

- 每个写操作对应一个具名UseCase。
- 每个读操作对应一个明确Query Facade。
- operationId必须与代码注册定义一一对应并保持稳定，写入口只允许以下封闭判别联合：
  - Internal、Admin、Customer的正式领域或管理写操作绑定CommandDefinition＋具名UseCaseDefinition。
  - Evidence UploadSession的创建、续签、Seal或取消绑定EvidenceTechnicalOperationDefinition；它只能推进隔离入库技术生命周期，不能产生业务有效性事实或完成业务Task。
  - 一次性Installation Bootstrap绑定BootstrapOperationDefinition；它只能在UNBOOTSTRAPPED状态及准确一次性Grant下执行，成功后永久关闭。
  - Provider入口绑定准确ProviderIngressDefinition/version，只追加Inbox或隔离记录。
  - 读操作绑定QueryOperationDefinition，冻结Owner、Permission、Purpose和Disclosure依赖。
- ChangeGate拒绝缺失绑定、重复绑定、跨通道绑定或operationId语义漂移。
- 除上述两类技术操作和Provider入口外，不得为普通业务写入新增绕过四类Command Envelope的“技术操作”定义。
- 禁止POST /commands、POST /execute、PATCH /entities/{type}/{id}等通用入口。
- OpenAPI DTO不得复用Domain、Command、Query内部模型或jOOQ类型。
- Tenant、Actor、Authority、Channel和ServiceActorCode由服务端派生，不进入可伪造请求字段。
- Provider契约只描述Inbox传输，不生成浏览器客户端。
- 四份YAML是唯一业务HTTP契约源，必须同时生成服务端接口和传输DTO；Controller只能实现一个准确通道的生成接口，不得独立声明额外业务RequestMapping。
- ChangeGate必须枚举实际业务RequestMapping，任何未被对应OpenAPI Operation覆盖的端点均失败；受限Actuator管理端点使用独立Management端口，不计入业务契约。

生成方向：

| 契约 | 生成目标 |
|---|---|
| internal.yaml | apps/internal-workbench/src/generated/api私有TS客户端＋backend/target/generated-sources/openapi/internal服务端接口/DTO |
| admin.yaml | apps/admin-console/src/generated/api私有TS客户端＋backend/target/generated-sources/openapi/admin服务端接口/DTO |
| customer.yaml | apps/customer-entry/src/generated/api私有TS客户端＋backend/target/generated-sources/openapi/customer服务端接口/DTO |
| provider.yaml | backend/target/generated-sources/openapi/provider服务端接口/DTO |

OpenAPI生成物不提交Git。三个src/generated/api目录是gitignored的生成源码目录，不得放手写文件。ChangeGate固定Generator、Canonicalizer和兼容比较器版本，执行lint、operationId检查、生成、编译和破坏性变更比较；四份规范先分别规范化，再按internal、admin、customer、provider固定顺序计算规范字段`openApiDigest`。

### 12.2 三个自治SPA

~~~text
apps/<app>/src/
├── app/
├── generated/
├── features/
└── composites/

packages/ui/
├── tokens/
├── primitives/
├── accessibility/
└── formatting/
~~~

三个SPA不能相互导入。每个SPA独立拥有路由、会话、OpenAPI客户端、错误映射、Feature、组合组件和Query Cache。

ESLint/import-boundary规则必须验证三个App互不导入，packages/ui不反向依赖任何App，生成客户端只能被所属App导入。

packages/ui只允许设计Token、无业务语义Primitive、可访问性、格式化和国际化。禁止包含认证、权限、API Client、网络请求、Task/Decision/WaitReceipt模型、WorkCard状态机、Customer Grant或路由。

### 12.3 服务端权威状态

服务端唯一拥有：

- CurrentCard。
- ActionDraft及draftVersion。
- Task、Decision和WaitReceipt。
- confirmationToken。
- CommandReceipt。
- 当前权限和字段披露。

浏览器只保存尚未完成一次Draft保存的输入缓冲、焦点、展开状态和提交中的视觉状态。

- CurrentCard使用封闭判别联合，前端不推导下一业务节点。
- ActionDraft绑定taskId、taskVersion、draftVersion和准确Subject Binding，并版本化保存。
- 敏感Draft、客户材料、Token和Grant秘密不进入localStorage、sessionStorage或普通IndexedDB。
- 正式提交不乐观修改业务状态，以CommandReceipt和重新读取CurrentCard为准。
- 请求结果不确定时先按原CommandId查询Receipt，禁止盲目重发。
- 登出、撤权、会话代次变化、部署安全围栏/`deploymentSecurityGeneration`变化或客户端版本不兼容时清空对应Query Cache；服务端Draft继续保留。任何缓存响应只有在本次服务端请求已重验当前Deployment Security Fence、Security Verification和Generation后才可披露，SPA不得在FENCED或Generation未知时用旧缓存兜底。

## 13. RegistryManifest与版本兼容

### 13.1 Definition Owner

| 定义 | Owner |
|---|---|
| Command与具名UseCase | 对应业务模块 |
| QueryOperationDefinition | 对应Query Facade的唯一Owner |
| ProviderIngressDefinition | externalaction；其处理结果仍由业务事实Owner验证 |
| EvidenceTechnicalOperationDefinition | evidence；仅限UploadSession隔离入库技术生命周期 |
| BootstrapOperationDefinition | identityaccess；仅限一次性Installation Bootstrap |
| 每个具体TaskDefinition与CompletionContract | 其完成事实语义的唯一事实Owner |
| 每个具体DecisionKindDefinition | 其决定语义的唯一事实Owner |
| TemporalPolicy | temporal |
| Permission、Scope与Authority Slot | identityaccess |
| ExternalActionDefinition | externalaction |
| AICapabilityDefinition | aigateway |
| BackfillDefinition | 被迁移数据的唯一Owner；executionruntime只拥有Run、Lease与Receipt元模型 |
| DerivationDefinition与CompletenessGuard Definition | 派生事实的唯一Owner |
| DataClassification | 数据Owner |
| Observation与SLO定义 | observabilitycontract |

模块注册类位于internal.config.registry，只能声明静态类型化定义。禁止从数据库读取动态流程、SQL、SpEL、脚本、Handler类名或任意Command名。

每个Definition只有一个definitionOwnerModule。Responsibility拥有Task/Decision元模型、校验器、Occurrence、DecisionRecord和版本引用，但不与业务事实Owner共同拥有具体Definition；Command Schema、Completion Event、Authority Slot和TemporalPolicy通过dependencyRefs关联。

### 13.2 不可变Manifest

每次构建先生成不含自引用字段的规范化Payload：

~~~text
RegistryManifestPayload {
  manifestSchemaVersion,
  definitions[],
  dependencyClosure
}

manifestDigest = SHA-256(CANONICAL-V1(RegistryManifestPayload))

RegistryManifest {
  payload,
  manifestDigest
}
~~~

每个静态Definition Descriptor至少包含definitionCode、definitionVersion、canonicalDigest、ownerModule、dependencyRefs以及该构建支持的Executor、Decoder或Presenter能力；它不包含按Tenant或Scope变化的运行期Lifecycle。

releaseId和applicationBuildDigest不属于RegistryManifest，而属于构建完成后生成的外置不可变ReleaseManifest：

~~~text
ReleaseManifest {
  releaseManifestSchemaVersion = 2,
  releaseId,
  applicationBuildDigest,
  registryManifestDigest,
  openApiDigest,
  flywayMigrationDigest,
  databaseContractBundleDigest,
  databaseExpandEpoch,
  databaseContractFloor
}
~~~

RegistryManifest嵌入Jar；applicationBuildDigest是最终Jar的SHA-256。ReleaseManifest作为Jar旁的独立发布工件生成并封存，因此不参与自身或Jar摘要计算，避免摘要自引用。数据库激活记录保存ReleaseManifest Digest。

`databaseContractBundleDigest`绑定Flyway、Physical Schema、jOOQ机械快照、期望`DatabaseSecurityManifest`、实际`CanonicalCapabilityAclSnapshot`、Ontology Physical Mapping和Query–Index Catalog的不可变数据库契约工件。该字段属于ReleaseManifest Schema v2；全库仍只有`execution_runtime.ReleaseState`一个发布激活权威点，不新增第二份兼容状态源。

实际生命周期保存在execution_runtime.RegistryActivationState，以tenant/activationScope、definitionCode和version为键，并绑定激活它的ReleaseManifest Digest：

~~~text
REGISTERED
→ ACTIVE_FOR_NEW
→ LEGACY_EXECUTABLE
→ HISTORICAL_READ_ONLY
~~~

- 在同一tenant/activation scope内，每个definitionCode最多只有一个version用于创建新实例；不同definitionCode可以同时处于ACTIVE_FOR_NEW。
- RegistryManifest只证明当前构建支持哪些不可变版本及其解释能力，不试图把不同Tenant的激活状态写进Jar。
- 已发布code + version的Canonical Digest永不改变。
- 语义变化必须创建新版本。
- 历史Decoder、Definition和Presenter不能直接删除。
- 旧Task、Decision、Event、TemporalSnapshot和AICandidate继续绑定准确旧版本。
- 在途责任不能静默迁移；需要改变时取消旧Task并显式创建新Task。

### 13.3 构建与启动门禁

ChangeGate与不可变previousReleaseBundle比较；该Bundle必须包含上一已激活ReleaseManifest、RegistryManifest、DatabaseContractBundle及准确Jar/Image引用，N构建兼容冒烟必须运行封存工件而不是从当前源码重建：

- 同键Digest未变。
- 历史版本未删除。
- Task、Command、Completion、Decision、Temporal、Permission和Authority依赖闭合。
- Event历史Fixture仍可反序列化。
- AI能力只引用允许的只读工具。
- Observation含义、单位或维度变化使用新Code。

启动时比较嵌入的RegistryManifest、当前Jar Digest、外置ReleaseManifest、数据库已激活RegistryRelease和API/Worker releaseId。缺少旧执行器、Decoder、工件摘要不匹配或出现Definition Digest漂移时，API正式写和Worker领取都保持关闭。

发布使用单版本受控切换：

~~~text
Expand
→ 部署支持旧、新定义的新构建
→ 确认API与Worker使用相同releaseId
→ 受控激活新RegistryRelease
→ 新责任使用新版本
→ Migrate
→ 满足独立条件后Contract
~~~

激活后旧构建自我Fence。配置后台只能在代码允许范围内启停、分配或缩小能力，不能修改Definition语义、完成事实、Handler或授权含义。

ReleaseActivator接受的门禁证明必须使用不可变信封：

~~~text
GateReceipt {
  gateKind,
  gateContractVersion,
  releaseManifestDigest,
  baselineBundleDigest,
  result = PASSED,
  evidenceBundleDigest,
  producerIdentity,
  issuedAt,
  attestationDigest
}
~~~

GateReceipt只能由受信CI或发布控制面签发，本地文件、布尔参数或手工数据库标记不能替代。CapacityGate认证还必须绑定准确容量包络版本、兼容范围、资源、Profile、Fixture、Corpus及依赖Digest，以及会使认证失效的精确变更条件；单纯时间流逝不得使容量认证失效。

`ReleaseActivator`、`PrincipalBindingActivator`与`SecurityProbe`是同一Jar的三个一次性、显式控制CLI子命令，不是第三个APP_ROLE，也不启动常驻API或Worker Context。正常服务启动才进入APP_ROLE二选一门禁；三个子命令只装配`executionruntime::integration`的最小控制面及各自允许的Outbound Adapter，使用相互隔离的限时凭据并在成功或失败后退出。ReleaseActivator必须以expectedPreviousReleaseId执行数据库CAS，并重新校验：

- 当前ReleaseManifest和Jar Digest。
- ChangeGateReceipt。
- ReleaseGateReceipt。
- 命中容量触发条件时的CapacityGate认证。
- 当前数据库Expand Epoch与Contract Floor。

任一引用、Digest或CAS不匹配都不得激活。ReleaseGate继续继承基础框架v1.0的N→N+1迁移、Schema/Manifest一致性、API/Worker启动、销售黄金路径、历史版本解释和N构建兼容冒烟要求。

PrincipalBindingActivator只允许以`expectedPrincipalBindingVersion`激活签名的PrincipalBindingManifest、校验当前PrincipalBindingSnapshot并追加Transition；它不能执行CREATE ROLE、GRANT或修改业务数据。高权槽扩展必须满足PostgreSQL物理模型总纲的双人复核，普通凭据轮换也不能扩大权限图。

SecurityProbe只允许读取Database Security系统目录并通过`SecurityVerificationPort`写入当前Verification State及追加Probe Receipt；它不能调用业务Command Gateway、Query Facade或读取Subject。三个控制CLI的ApplicationContext边界必须由Context测试和ArchUnit规则单独验证。

## 14. 测试组织

### 14.1 单测试树

~~~text
backend/src/test/java/<base>/
├── <module>/domain/
├── <module>/application/
├── <module>/module/
├── architecture/
├── protocol/
└── system/
~~~

- Surefire执行*Test。
- Failsafe执行*IT。
- Capacity与Release测试通过JUnit Tag进入独立Gate。
- 不建立额外Maven测试模块或自定义Source Set。
- ChangeGate默认Surefire和Failsafe都显式排除release与capacity Tag。
- ReleaseGate只包含release并排除capacity；CapacityGate只通过独立Profile运行准确容量Tag。

### 14.2 测试层次

| 层次 | 验证目标 |
|---|---|
| domain | 聚合、不变量、值对象和纯函数 |
| application | 具名UseCase、Port交互、完成事实和错误分类 |
| module | Spring Modulith模块API及依赖 |
| architecture | Modulith和ArchUnit契约 |
| protocol | 幂等、并发、Tenant、Task、Outbox、Inbox、Temporal |
| system | API/Worker角色、四安全链和完整事务 |

数据库协议测试只使用固定PostgreSQL Testcontainers，不使用H2证明PostgreSQL行为。Flyway从空库迁移后运行jOOQ测试。

允许对其他模块公开Port和真实外部系统Port使用类型化Test Fake；不得Mock被测Domain、被测Repository或PostgreSQL协议。并发、唯一约束、行锁、SKIP LOCKED、JSONB、Tenant Predicate、Outbox和Inbox必须使用真实PostgreSQL。

API与Worker分别启动ApplicationContext，验证对方Bean不存在。

## 15. Spring Modulith与ArchUnit

### 15.1 职责分工

Spring Modulith是模块DAG唯一权威：

- 每个业务与平台Application Module为CLOSED；bootstrap、controlcli、apihost和workerhost按第9章排除在Module Catalog之外。
- API Facet为Named Interface。
- allowedDependencies逐模块、逐Facet显式列出。
- 禁止通配依赖。
- ApplicationModules.verify检查内部包不可见、依赖白名单和无环。

ArchUnit不复制模块依赖图，只管理横切禁令。

Spring Modulith只用于模块模型、结构验证和模块测试。禁止启用其持久化Event Publication Registry、@Externalized或以@ApplicationModuleListener作为正式投递机制；所有正式DomainEvent Envelope及投递状态只由execution-runtime的DomainEvent Outbox拥有。

### 15.2 编号化规则集

最低规则及固定执行机制如下；同一单元格列出的机制必须全部执行，不是任选其一：

| 编号 | 规则 | 固定执行机制 |
|---|---|---|
| MOD-001 | 模块外不得访问internal | Spring Modulith |
| MOD-002 | 跨模块只能依赖Named Interface | Spring Modulith |
| LAYER-001 | Domain不得依赖Spring、Jackson、jOOQ、HTTP或SDK | ArchUnit |
| LAYER-002 | Application不得依赖Persistence或Adapter实现 | ArchUnit |
| LAYER-003 | API不得暴露聚合、Repository、jOOQ或传输DTO | ArchUnit（Custom ArchCondition） |
| LAYER-004 | 禁止跨模块Adapter到Adapter依赖 | ArchUnit |
| LAYER-005 | 禁止顶层common、util、helper | ArchUnit |
| WEB-001 | 业务RestController只能位于四个Channel | ArchUnit |
| WEB-002 | Controller不得依赖业务internal或Handler | ArchUnit |
| CHANNEL-001 | Provider Channel只能访问Provider Inbox Ingress | ArchUnit |
| CHANNEL-002 | Admin Channel不得访问InternalTask Gateway | ArchUnit |
| CHANNEL-003 | Customer Channel不得访问Internal/Admin Gateway | ArchUnit |
| CHANNEL-004 | 四个Channel包之间双向零依赖 | ArchUnit |
| ROLE-001 | apihost与workerhost互相不得依赖 | ArchUnit |
| ROLE-002 | ServiceActor Gateway只能由workerhost调用；executionruntime.internal可实现，业务模块只能实现handler-spi | ArchUnit（Custom ArchCondition） |
| HOST-001 | 业务与平台模块不得依赖bootstrap、controlcli、apihost或workerhost | ArchUnit |
| HOST-DEP-001 | 精确执行第9.2节的Host到Named Interface白名单；Host不得访问handler-spi或任一Runtime SPI | ArchUnit（Custom ArchCondition） |
| RUNTIME-SPI-001 | 五个Runtime SPI只能由executionruntime及各自登记的Provider Owner引用，只能由对应Provider Owner实现；每个生产Context各恰有一个实现，Runtime不得提供Default、Noop或降级实现 | ArchUnit（Custom ArchCondition）＋ApplicationContext Test |
| FENCE-001 | 每个登记的API/Worker事务入口必须在首条Tenant/业务SQL前调用execution-runtime::deployment-fence-runtime；该Port只能由Execution Runtime实现且必须加入当前事务 | ArchUnit＋Transaction Protocol Test＋SQL Trace Assertion |
| WIRING-001 | 除Wiring所属模块自身外，只有bootstrap可访问module::wiring；bootstrap访问Host时只允许两个准确HostWiring类型 | ArchUnit（Custom ArchCondition） |
| WIRING-002 | 根应用禁止全base扫描，角色必须通过正向Import装配 | Source Scan＋Configuration Registry Scan＋Context Test |
| IDENTITY-001 | Raw Jwt与OIDC SDK只在Internal/Admin OIDC包 | ArchUnit |
| IDENTITY-002 | identityaccess和业务模块不得读取OIDC Role/Group Claim | ArchUnit＋Source Scan |
| ADAPTER-001 | 对象存储和扫描SDK只能在Evidence Adapter | ArchUnit |
| ADAPTER-002 | LLM SDK只能在AI Gateway Adapter | ArchUnit |
| ADAPTER-003 | 外部业务Provider SDK只能在External Action Adapter | ArchUnit |
| ADAPTER-004 | Secret/KMS SDK只能在批准Adapter | ArchUnit |
| PERSIST-001 | jOOQ生成类型只能由Owner Persistence访问 | ArchUnit |
| AI-001 | AI不得依赖Command、Write Port或External Dispatch | ArchUnit |
| EVIDENCE-001 | Evidence不得依赖业务模块 | ArchUnit |
| CONFIG-001 | Domain/Application禁止读取Environment和环境变量 | ArchUnit＋Source Scan |
| CONFIG-002 | ConditionalOnProperty只允许在登记Wiring包 | ArchUnit |
| CONFIG-003 | 禁止security.disabled等生产旁路 | Source Scan＋Configuration Registry Scan |
| TEST-001 | 生产源码禁止Fake、Mock、Noop安全实现 | ArchUnit＋Source Scan |
| TEST-002 | 生产Jar不得包含fixture、testsupport和TestConfiguration | Production Jar Inspection |

API/Worker Bean隔离由ApplicationContext Test执行，数据库身份与Tenant Predicate由PostgreSQL Protocol Test执行；它们不伪装成ArchUnit规则。

### 15.3 例外机制

- 禁止全局ignore和模糊包豁免。
- 例外只能登记准确规则编号、源类、目标类或准确依赖、原因、Owner和截止版本；禁止包级例外。
- 确需覆盖一组类时必须冻结完整类集合Digest，新增或删除类都会使ChangeGate失败。
- ChangeGate验证例外没有扩大。
- 新增例外属于架构变更。

ArchUnit不冒充SQL、OpenAPI和运行上下文验证。Tenant Predicate由真实PostgreSQL负向测试验证，OpenAPI兼容由契约门禁验证，API/Worker Bean隔离由Context测试验证。

## 16. ChangeGate

仓库唯一入口为：

~~~text
ci/change-gate
~~~

### 16.1 固定检查组

执行可以在依赖满足后并行，但逻辑DAG固定为：

~~~text
工具链、Lock与可信基线校验
→ 四份OpenAPI规范化、兼容检查及Java/TS生成
→ 校验规范化DatabaseSecurityManifest并计算Digest
→ 临时INFRA Provisioning Principal执行Database Security Bootstrap，签发BootstrapReceipt；撤销原始Provisioner或将其缩限为INFRA_ADMIN槽中activationPurpose=GENESIS_BOOTSTRAP的临时Principal
→ Flyway空库migrate/validate
→ 提取并校验PhysicalSchemaSnapshot与Digest
→ 由DatabaseSecurityReconcile按Manifest＋真实Schema＋可信Before Bundle机械生成并应用ObjectAclApplyPlan，签发SecurityApplyReceipt
→ 复验PhysicalSchemaDigest未变并提取CanonicalCapabilityAclSnapshot
→ 在Genesis Fence内由临时Genesis Infra Principal完成测试LOGIN/Binding后先撤销该身份并签发GenesisSecurityClosureReceipt，再提取最终PrincipalBindingSnapshot、激活Binding并运行Security Probe
→ jOOQ按Owner重生成并校验机械快照零差异
→ Java编译并生成最终RegistryManifest
→ 生成OntologyPhysicalMappingCatalog与QueryIndexCatalog，核对最终Registry、真实Schema及生产Query闭包
→ 生成DatabaseContractBundle并与可信基线/仓库机械快照零漂移比较
→ Spring Modulith、ArchUnit及Registry历史兼容校验
→ 自动化测试和API/Worker Context测试
→ 单一Spring Boot Jar打包
→ 计算Jar Digest并生成外置ReleaseManifest
→ 三个SPA分别typecheck/test/build
→ 安全、敏感数据与供应链检查
→ 受信CI签发ChangeGateReceipt
~~~

`backend/target/generated-sources/openapi/**`必须在Java编译前登记为compile source root；三个SPA的`src/generated/api`必须在其typecheck前生成。干净工作区不得依赖上一次构建遗留的OpenAPI生成物。

固定检查覆盖如下：

1. 工具链、锁定与基线
   - JDK、Maven Wrapper、Node、npm、插件及依赖版本固定。
   - Lockfile完整，无SNAPSHOT和动态版本。
   - previousReleaseBundle或GENESIS_BASELINE来源及Digest可信。
   - DatabaseSecurityManifest Schema、Canonical Digest和Bootstrap工具Digest固定；BootstrapReceipt绑定准确Manifest、PostgreSQL镜像、空库、双控制批准、Genesis Control Principal Manifest和Provisioning Identity，GenesisSecurityClosureReceipt证明临时高权身份已撤销或转入已登记INFRA_ADMIN Slot。

2. 契约、Schema与机械生成物
   - 四份OpenAPI lint、operation绑定、Digest、兼容性和生成物编译。
   - Flyway空库migrate与validate。
   - Flyway前已按DatabaseSecurityManifest创建准确NOLOGIN Owner/Capability角色、环境无关Membership/SET/Default/PUBLIC基线；原始Provisioner在Bootstrap后撤销或缩限为`INFRA_ADMIN + activationPurpose=GENESIS_BOOTSTRAP`的临时Principal，且该限时身份在最终Principal Snapshot/Probe前先撤销并形成Closure Receipt。
   - 普通Flyway文件不含手写Role/Grant/Default Privilege DDL；Role Graph操作只由受控INFRA工具执行，对象/列权限由DatabaseSecurityReconcile从唯一Manifest机械生成ObjectAclApplyPlan，计划不可编辑且Receipt绑定Manifest、真实Schema、previousReleaseBundle/GENESIS和工具Digest。
   - CanonicalCapabilityAclSnapshot只能在SecurityApplyReceipt成功后提取，并与Manifest Effective Security Set精确一致；计划性REVOKE在ContractGate前失败关闭。
   - 未激活Candidate中止时，`SECURITY_ABORT`只能从previousReleaseBundle＋准确Candidate Expand/Apply Receipt机械生成，恢复上一Capability Snapshot并签发Abort Receipt；Candidate已激活、存在专属事实/外部效果或恢复快照不精确时拒绝回退并保持Fence。
   - jOOQ重生成零漂移。
   - PhysicalSchemaSnapshot覆盖对象Owner、约束/索引有效性、Reloptions、Collation及分区边界。
   - CanonicalCapabilityAclSnapshot的实际安全集合必须与DatabaseSecurityManifest中`ACTIVE | LEGACY_COMPAT`条目的有效安全集合并集精确一致；Snapshot覆盖能力/Owner角色属性、角色间Membership及其ADMIN/INHERIT/SET选项、SET Option、Ownership、全部Grant、Default Privilege、PUBLIC和`search_path`，只排除部署环境的实际LOGIN绑定。Lifecycle是Manifest元数据，不伪造为`pg_catalog`可观测字段，其转换由previousReleaseBundle与ContractGate另行校验。
   - OntologyPhysicalMappingCatalog与QueryIndexCatalog引用闭合且匹配真实Schema和生产Query定义。
   - DatabaseContractBundle生成、Digest固定，并与previousReleaseBundle或GENESIS_BASELINE比较。
   - RegistryManifest闭包、历史版本兼容和Event历史Fixture。

3. 编译与结构
   - Java编译、Spring Modulith验证和ArchUnit全部通过。
   - 三个SPA禁止互相导入，packages/ui反向零依赖。

4. 自动化测试
   - Domain、Application和Module测试。
   - 真实PostgreSQL协议测试。
   - Tenant、幂等、并发和四通道负向测试。
   - API与Worker两种Context边界测试。

5. 构建与前端
   - 只产生一个Spring Boot Jar并通过生产Jar内容检查。
   - Jar Digest形成后才生成外置ReleaseManifest。
   - 三个SPA分别typecheck、test和build。

6. 安全、供应链与证明
   - Secret和敏感输出扫描、DataClassification覆盖、SBOM和依赖漏洞策略。
   - 生产Jar无Test、Fake或旁路实现。
   - 全部检查成功后，受信CI才可签发绑定本次ReleaseManifest与证据包Digest的ChangeGateReceipt。

本地执行相同DAG并返回逐项结果，但不得持有生产发布签名身份，也不得签发可被ReleaseActivator接受的ChangeGateReceipt。

### 16.2 不进入每次ChangeGate

- 完整销售黄金路径进入ReleaseGate。
- C1容量认证进入触发式CapacityGate。
- 真实OIDC、LLM和Provider联调进入专项发布验证。
- 视觉截图与浏览器回归进入前端专项验收。
- 不设置掩盖关键不变量的统一代码覆盖率数字。

previousReleaseBundle由CI注入并验证Digest，不能作为开发分支可编辑目录。首次发布使用不可变GENESIS_BASELINE。

## 17. 跨文档追踪

| 上游冻结结论 | 本规格落实位置 |
|---|---|
| 六聚合只属于销售至转案上下文 | 4.2、4.4 |
| 最小Matter身份与后MVP接入 | 4.2、4.4、7.3 |
| 模块化单体＋最小共享内核 | 3、4、5、6 |
| 具名用例＋极薄CommandRuntime | 7.3、10 |
| 五个跨聚合原子边界 | 7.3、10.3 |
| 四类互斥命令信封 | 9、10.1 |
| Task WAITING与WaitReceipt分离 | responsibility API边界；不由Host或前端改写 |
| ExternalAction＋双Outbox＋Inbox | 8、9、10、11 |
| Evidence双层模型 | evidence模块及Adapter所有权；不改变Evidence本体 |
| AI无正式写权 | 8.3、8.4、15.2 |
| 单PostgreSQL＋模块Schema | 11 |
| 强一致操作读＋异步发现投影 | 11.2、12.3 |
| 三个独立SPA＋通道隔离 | 9、12 |
| RegistryManifest与受控切换 | 13 |
| ChangeGate/ReleaseGate分层 | 16 |

## 18. 完成判据

本规格的实现只有同时满足以下条件才可认为基础骨架成立：

1. 后端只有一个Maven工程和一个Spring Boot Jar。
2. API与Worker分别启动且Bean、凭据、数据库权限和网络入口隔离。
3. Spring Modulith验证所有模块为CLOSED、依赖精确且无环。
4. 其他模块无法编译访问任意internal包。
5. 六聚合、Matter Core和平台模块的事实、表与jOOQ代码各有唯一Owner。
6. 五个跨模块原子用例在同一PostgreSQL事务中完成，失败整体回滚。
7. 不存在通用流程编排模块、动态规则或跨SchemaRepository。
8. 四类命令信封、Provider Inbox和AI Candidate权力边界不能互相转换。
9. Flyway可从空库建立Schema，jOOQ重生成零漂移。
10. 四份OpenAPI互相隔离，三个SPA不能互相导入。
11. CurrentCard、ActionDraft、权限和Receipt以服务端为权威。
12. 真实PostgreSQL协议测试覆盖Tenant、幂等、并发、Task、Outbox、Inbox和Temporal关键不变量。
13. RegistryManifest能解释所有在途和历史定义版本。
14. ci/change-gate在本地和CI使用同一入口并失败关闭。

## 19. 明确延后

以下内容需要后续独立详细设计或实施ADR，不能在实现中自行假设：

- 精确Java根包命名。
- Maven依赖与插件的补丁版本。
- Spring Boot、Modulith、jOOQ、Flyway和前端工具的兼容矩阵。
- OIDC、对象存储、SecretStore、KMS和部署平台产品。
- 逐表DDL、索引、分区和SQL。
- 四份OpenAPI的逐字段Schema。
- Responsibility、Temporal、CurrentCard和ActionDraft详细代码模型。
- Provider Adapter逐产品协议。
- 实施计划、迭代切片和工期。
- Matter登记、分类、分配、办理和能力包实现。
- 已明确搁置的备份与灾难恢复框架。

在这些内容被后续规格冻结前，不得以“预留字段”、extensions JSON、EAV、空模块、通用Handler或动态配置绕过本规格。

## 2026-08-27 P0一致性补充

构建契约必须为报价观察、每Snapshot独立Review、不可变WaitReceipt和只读WaitingProjection保留明确模块边界。Task类型注册表必须机械包含P0-01至P0-15的固定变体、主命令和完成Fact；禁止用通用审批Handler或同一Task重开实现退回、修正和补正重提。
