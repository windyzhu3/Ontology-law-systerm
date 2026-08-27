# Ontology Law System PostgreSQL物理模型总纲 v1.0

> 状态：正式冻结版；八个设计章节已经用户逐节确认  
> 日期：2026-08-19  
> 范围：销售至转案MVP的PostgreSQL物理建模、事务、隔离、安全、迁移、生成和容量演进总契约  
> 明确不包含：逐表DDL、实施计划、工期估算、后MVP案件办理表、备份与灾难恢复方案、物理破坏性Contract实施

## 1. 文档定位、原则与物理边界

### 1.1 文档定位

本规格是《Ontology Law System 基础框架设计规格 v1.0》和《Ontology Law System 项目结构、模块边界与构建契约 v1.0》的下一层数据库总纲。它回答以下问题：

1. 领域本体如何映射为可验证的关系模型，而不退化成万能表或动态工作流数据库。
2. 单PostgreSQL、模块独立Schema和逻辑多租户如何形成可证明的隔离边界。
3. Command、Task、Decision、Event、Receipt、Evidence、Outbox和投影如何选择不同表形态。
4. READ COMMITTED下如何通过显式锁序、CAS、唯一约束和幂等Receipt保证正确性。
5. API、Worker、Migrator和受限维护身份如何获得最小数据库权限。
6. Flyway、jOOQ、Schema快照、能力安全快照和发布门禁如何保持单一Schema真源。
7. MVP如何先在中型单律所容量包络内验证，再由证据触发分区、外置搜索或Kafka。

本规格是“物理模型的总约束”，不是全库逐表设计。后续每个模块的Schema细册必须遵守本规格，但不能从本规格推导出一个通用Repository、万能事实表或运行时本体平台。

### 1.2 适用优先级

发生冲突时，解释顺序固定为：

1. 《律所待办驱动智能管理系统：目标产品基线 v2.0》。
2. 《待办驱动律所管理系统：总体架构与本体完整设计》。
3. 《销售MVP工作卡与对话状态设计 v1.0》。
4. 《最小Matter身份与后MVP扩展契约 v1.0》。
5. 《Ontology Law System 基础框架设计规格 v1.0》。
6. 《Ontology Law System 项目结构、模块边界与构建契约 v1.0》。
7. 本规格。

冲突裁决：

- 领域概念、事实、关系、Owner、Task完成事实、时效和MVP范围以上游领域规格为准。
- Java模块、Facet、Host、构建与ChangeGate组织以项目结构规格为准。
- PostgreSQL键、表形态、引用、锁、权限、加密、Schema演进和物理门禁以本规格为准。
- 既有代码和高保真原型都不是数据库兼容性基线。

### 1.3 已冻结方案

```text
单PostgreSQL数据库、模块独立Schema
+ 强制Tenant Predicate与复合约束，不启用RLS
+ 应用生成UUIDv7、PostgreSQL原生uuid
+ 同Schema强外键、跨Schema封闭白名单
+ 类型化Owner事实表，不使用RDF/EAV/三元组万能表
+ READ COMMITTED、静态LockPlan、CAS与唯一约束
+ API/Worker分离数据库身份与最小权限
+ 追加式Audit与不可变历史数据库保护
+ 默认静态加密、高敏字段应用层AEAD
+ Flyway唯一Schema真源
+ jOOQ、Physical Schema与Capability Security机械快照
+ Expand–Migrate–Contract和可续跑Backfill
+ Query–Index Catalog和触发式容量升级
```

### 1.4 六种表形态

六种表形态是建模模式，不是六张通用基表，也不得通过PostgreSQL继承实现：

| 表形态 | 用途 | 允许变化 |
|---|---|---|
| Current State | 聚合当前状态、Task、授权当前状态 | 受控UPDATE、CAS、状态机 |
| Append-only Fact | Event、Audit、Decision、已发布Fact | 仅INSERT |
| Immutable Receipt | CommandReceipt、批次Receipt、销毁Receipt | 仅INSERT |
| Work Queue | InternalWork、可重投Outbox、BackfillRun | 封闭状态机、租约与fencing |
| Operational Projection | CurrentCard、类型化Lookup、强一致读投影 | Owner事务内更新或可重建 |
| Control Plane | Release、Registry激活、运行门禁 | 当前状态＋追加变更记录 |

以下对象不得按名称误归类：

- `WaitReceipt`是可变Current State，不是Immutable Receipt。
- `ActionDraft`是短生命周期交互状态，不是Operational Projection。
- `ExternalAction/ExternalDispatchOutbox/ExternalProbe`和Provider Inbox有特殊安全状态机，不能套用普通Work Queue重试语义。
- Control Plane必须将部署级和Tenant级对象分表，禁止用`tenant_id=NULL`混装。

## 2. Tenant隔离、复合键与查询边界

### 2.1 隔离方案

正式采用：**强制Tenant Predicate＋复合约束**。

MVP不启用PostgreSQL RLS。隔离由以下四层共同保证：

1. 服务端可信上下文派生`tenantId`。
2. 所有Tenant SQL在源头包含Tenant Predicate。
3. 主键、唯一约束和关系约束携带`tenant_id`。
4. 真实PostgreSQL负向测试证明所有已登记应用查询路径不能串租，跨Tenant写入和关联被数据库约束拒绝。

RLS可在未来作为纵深防御重新评估，但不得替代QueryAccessGuard、Owner Port、复合约束或四轴授权。

复合PK/UK/FK能够在数据库层阻止跨Tenant写入和错误关联，但在未启用RLS时，持有表`SELECT`权限的进程仍可能执行一条遗漏Tenant Predicate的任意SQL。因此，读取隔离由QueryAccessGuard、Owner内Schema限定参数化SQL、代码边界和协议负向测试共同保证；本规格不声称PostgreSQL能够拦截持有API凭据者编写的任意错误SELECT。数据库凭据泄露或运行进程被完全攻陷属于独立威胁场景。

### 2.2 Tenant来源

`tenantId`只能由服务端根据信任通道解析：

| 通道 | Tenant来源 |
|---|---|
| Internal | OIDC映射后的内部Actor绑定与有效任职路径 |
| Admin | 管理Actor的受限管理范围 |
| Customer | 准确`CustomerAccessGrant` |
| ServiceActor | 代码注册的Activation与冻结工作范围 |
| Provider | Provider Inbox完成验签和账户绑定后的服务器解析结果 |

禁止从请求Body、普通Header、查询参数、AI输出或Provider自报字段直接信任Tenant。

内部或管理Actor存在多个有效Tenant/任职时，服务端先从OIDC精确绑定得到候选集合，再与Task/Registered Intake、服务端Tenant Session或Admin Scope求唯一交集。用户只能从服务端返回的授权Tenant列表建立受保护会话，不能在业务请求中提交裸`tenantId`自由切换。

登录后、Tenant Session建立前只有一个代码登记的跨Tenant身份查询例外：`ListMyAuthorizedTenantsQuery`。它必须：

- 仅接受服务端解析的准确OIDC IdentityBinding，不接受客户端Principal或Tenant条件；
- 只由Identity Access模块实现并查询其Owner表，不接触任何业务Subject、Task、Audit或Evidence；
- 事务开始必须先取得并重验Deployment Security Fence（Rank 5）的`FOR SHARE`；只有确认业务门禁有效后才允许第一次无锁发现候选Tenant并按`tenant_id`排序，随后以`FOR SHARE`锁定全部候选Tenant Authorization Fence（Rank 10），再锁User、IdentityBinding与Membership（Rank 20），最后完整重读候选集合、Membership和Generation；
- 只返回最小Tenant Ref和安全显示名，不返回总量侧信道、角色、权限、客户或事项数据；
- 在任一候选撤权并发下等待后按新状态返回，不能从Query Cache或过期投影返回已撤销Tenant。

重验发现候选集合扩大时可以有界回滚并重试整次查询；不得在持有Rank 20锁时反向补取新的Rank 10锁。新增Membership暂时未出现在本次结果只会少返回授权，不构成越权；撤销Membership则必须被同一Tenant Fence顺序化后立即消失。

该例外不产生可复用`AuthorizedQueryContext`，只用于选择并建立服务端Tenant Session；Session建立后，全部查询恢复单一Tenant Predicate、Tenant Fence与普通QueryAccessGuard。新增任何Pre-Tenant Query必须升级本规格，不能沿用该例外。

### 2.3 复合键规则

所有Tenant级表，包括Current State、Fact、Receipt、Queue、Projection、Inbox、Outbox、Evidence和授权历史，均必须具有Tenant复合身份。当前实体默认使用：

```sql
PRIMARY KEY (tenant_id, object_id)
```

规则：

- `tenant_id`和`object_id`均`NOT NULL`。
- Tenant内自然唯一性使用`UNIQUE (tenant_id, ...)`。
- 同租户外键使用`(tenant_id, ref_id)`引用`(tenant_id, id)`。
- 禁止只按`object_id`读取后再判断Tenant。
- 禁止使用Nil UUID、空字符串或特殊Tenant值表达全局对象。
- 部署级对象保存于独立部署级表，不包含`tenant_id`。

### 2.4 QueryAccessGuard

Internal、Admin和Customer的所有Owner Query必须经过显式`QueryAccessGuard`：

1. 先通过`DeploymentFenceRuntimePort`取得Rank 5围栏并冻结`deploymentSecurityGeneration、verificationGeneration、verifiedUntil`，再取得Tenant授权围栏。
2. 重验User、Membership、Grant、Restriction和Access Generation。
3. 校验Purpose、DataScope、Capability和字段敏感等级。
4. 校验Responsibility、Capability、Authority、DataScope四轴中每一轴的`REQUIRED`或`NOT_APPLICABLE`，不得隐式跳过。
5. 应用Restriction和显式Deny优先规则，禁止拼接来自不同任职路径的残缺授权片段。
6. 生成类型化`AuthorizedQueryContext`。
7. Owner模块将封闭Scope策略映射为Schema限定、参数化SQL，并在SQL源头选择允许披露的列。
8. 结果物化前完成当前版本与披露范围校验。

每个`QueryOperationDefinition`静态冻结Owner、Permission、Purpose、DisclosureProfile，以及四轴中每一轴的适用性。`AuthorizedQueryContext`只包含Tenant、Actor、Purpose、允许的Scope Ref、字段集合、授权依据、Tenant/Actor Generation，以及本事务观察到的`deploymentSecurityGeneration、verificationGeneration、verifiedUntil`；不包含SQL文本、列名或任意表达式。Identity模块不得向Owner模块注入动态SQL。

Controller和Host不得取得`DSLContext`、Repository或通用查询入口。动态SQL、自由字段选择和跨Owner万能搜索均被禁止。

搜索只返回候选Ref，候选选择、展示和后续Command仍需回到Owner Query/Command重新鉴权。

### 2.5 Tenant隔离硬失败

以下均为P0：

- Tenant表缺`tenant_id`。
- Tenant唯一约束没有以`tenant_id`参与隔离。
- 跨Tenant FK或无Tenant Predicate的Owner SQL。
- 客户入口提供通用对象搜索。
- 通过对象ID反推出Tenant。
- 将Tenant隔离仅寄托于应用缓存、前端隐藏或数据库连接池上下文。

## 3. 公共数据库类型、身份、版本、时间与Digest

### 3.1 系统自有ID

系统自有实体、Occurrence、Revision、Event、Receipt和内部工作ID统一采用：

- 应用生成UUIDv7；
- PostgreSQL原生`uuid`；
- Java类型`UUID`；
- OpenAPI使用规范UUID字符串。

UUIDv7只用于全局唯一、索引局部性和运维相关性，不得作为：

- 权威业务时间；
- 因果顺序；
- 聚合版本；
- 队列排序；
- Event全序。

聚合顺序由`aggregate_version`确定，队列顺序由`available_at/priority/stable_id`确定，事实记录时间由数据库时间确定。

### 3.2 外部身份

客户端Command ID、OIDC Subject、Provider ID、银行流水号、对象存储Version ID及其他外部标识保持类型化的Opaque Text，不强制转换为UUIDv7。

外部标识必须同时绑定：

```text
namespace/provider/account + externalId
```

禁止把不同Provider命名空间中的相同字符串视为同一对象。

Code、Opaque External ID、Idempotency Key、Namespace、Provider Account Ref、Digest/Canonicalization Profile等身份或唯一键Text列必须使用确定性`C`/binary Collation；禁止使用Nondeterministic Collation参与身份、UK、FK、幂等或Digest选择。姓名、机构名和别名的搜索规范化列可以按代码登记的Normalization/Collation Profile另行选择，但不得改变原始身份键语义。

### 3.3 版本分型

不同版本语义必须使用不同字段和Java类型：

| 版本类型 | PostgreSQL | 语义 |
|---|---|---|
| row_version | bigint，起始1 | 当前状态CAS |
| aggregate_version | bigint，起始1 | 聚合事实顺序 |
| access/revocation generation | bigint，起始1 | 授权并发围栏 |
| occurrence/snapshot/revision version | bigint，起始1 | 运行期精确版本 |
| definition/schema/policy/profile version | integer，起始1 | 代码注册定义版本 |
| Provider协议版本 | 受控text | 外部产品自己的版本标识 |

规则：

- 运行期`bigint`通过API/JSON使用规范十进制字符串，避免JavaScript安全整数问题。
- 所有运行期Version和Generation列使用`CHECK (value > 0)`；代码Definition Version同样必须为正整数。
- 代码定义版本以`(definition_code, definition_version)`为键；不得用`MAX(version)`替代ActivationState。
- Registry版本不能与Flyway版本、聚合版本或Schema Epoch混用。
- 选择性加密的加密修订不能绑定会因无关业务更新而变化的`row_version`。

### 3.4 时间类型

所有Instant统一使用：

- PostgreSQL `timestamptz(6)`；
- Java `Instant`；
- API RFC 3339 UTC表示。

`recorded_at`由数据库`transaction_timestamp()`产生，表示事务记录时刻，即事务起点，不是精确Commit时刻。同一事务中多条记录具有相同`recorded_at`是正常现象，不能依赖该字段形成事务内顺序。

法域时区、工作日历和客户承诺时区必须由Temporal Snapshot显式保存，不能依赖数据库Session Timezone。

微秒归一规则固定为：

- OLS Internal/Admin/Customer API输入超过6位小数秒时返回类型化校验错误，不做静默舍入；
- 应用生成的`Instant`在持久化、CAS比较或参与Digest前显式截断到微秒；
- Provider原始字节与签名验证不改变；只有对应Provider Contract Definition登记`TRUNCATE_TO_MICROSECOND`时，才允许把高精度外部时间向零截断为领域Instant，并同时保留原始Receipt/Digest与归一化规则Ref，否则隔离输入；
- Subject Snapshot、Event、Command和Audit中的时间Digest一律使用UTC、微秒精度的规范表示。

API、Worker、Release Control、Security Probe和Migration数据库身份均由DatabaseSecurityManifest固定`TimeZone=UTC`；仍禁止从Session Timezone推导法域或业务时间。

时间语义必须分列，不能互相代用：`occurred_at`表示外部或业务发生时间，`effective_at/valid_from`表示规则或关系生效时间，`received_at`表示系统收到外部输入时间，`recorded_at`表示事务记录时间。只有权威来源存在时才写对应时间，禁止用`recorded_at`猜测外部发生时间。

### 3.5 Digest

任何具有完整性或精确版本语义的Digest必须绑定：

```text
algorithmCode
canonicalizationProfileCode
canonicalizationVersion
digestBytes
```

规则：

- 禁止只保存裸Hash值。
- 禁止对`jsonb::text`结果重新计算业务Digest。
- Evidence原始文件使用`RAW_BYTES`规范化配置。
- Command Payload、Registry、Schema快照、Provider验签分别使用自己的Canonicalization Profile。
- Provider签名必须针对收到的原始字节验证，不能先JSON重排。

系统内部新定义的完整性Digest默认使用`SHA-256 + bytea(32)`，仍必须保存Profile Code/Version；外部Provider算法由对应Provider Contract Definition登记，不能被该默认值覆盖。

### 3.6 NULL与JSONB

`NULL`只表达真正可选或尚不存在，不得同时代表UNKNOWN、NOT_APPLICABLE、NOT_VERIFIED、REDACTED和失败。

禁止：

- 空字符串表达缺失；
- Nil UUID；
- 零版本；
- 零Digest；
- Epoch时间表达未知时间。

JSONB只允许保存模块Owner拥有、具有版本化Schema的受控Payload或技术快照。核心业务身份、状态、授权、关系端点、SubjectBinding、索引字段和加密包不得藏入自由JSONB。

## 4. Schema所有权、表形态与跨Schema引用

### 4.1 Schema边界

MVP只创建当前活跃模块的Schema：

```text
platform_meta
execution_runtime
identity_access
responsibility
temporal
audit
evidence
external_action
configuration
ai_gateway
jurisdiction_policy
legal_content
party
lead
opportunity
conflict
contract
transfer
matter_core
workbench
```

`platform_meta`是部署级Schema，不是业务领域模块Schema；其唯一代码、迁移和jOOQ Owner是`executionruntime.internal.platformmeta`技术包。它只保存Flyway History和数据库安全控制面，不拥有Release/Registry业务语义或业务Subject。`admin`和`observabilitycontract`当前不拥有独立Schema：Admin通过受限Facet编排Identity、Configuration、Audit、Evidence及政策Owner；Observability Contract只注册低基数遥测语义，不保存业务判定数据。

后MVP案件办理、案管分配、综法、非诉和能力包不创建空Schema、空表或占位Task，只保留文档和Owner Port接入契约。

### 4.2 物理Owner

数据库对象由一个NOLOGIN物理Owner持有：

```text
ols_object_owner
```

受限Migrator在发布期间使用临时身份并受控`SET ROLE`。API和Worker不拥有任何数据库对象。

“模块Owner”是逻辑和代码Owner，由Schema、jOOQ生成包、Spring Modulith、ArchUnit、SQL门禁和DatabaseSecurityManifest共同保证；MVP不为每个Schema切换运行数据库账号。

### 4.3 同Schema外键

同一Owner Schema内默认使用强外键保护机械关系和Tenant一致性。以下情况可以不建FK，但必须在Schema细册逐项说明：

- 引用外部系统身份；
- 引用不可用数据库FK表达的版本化Ref；
- 历史事实必须在目标当前实体删除或迁移后继续解释；
- Owner要求类型化Snapshot而非当前对象引用。

禁止为了“减少耦合”普遍删除同Schema FK。

### 4.4 跨Schema封闭白名单

跨Schema FK默认禁止，只允许登记在`CrossSchemaReferenceManifest`中的稳定结构关系。

MVP白名单：

| 引用 | 物理约束 |
|---|---|
| Tenant级对象→Tenant根 | `(tenant_id)→identity_access.tenant(tenant_id)` |
| Task→具体内部Owner | 复合Tenant FK |
| Task→冻结TemporalSnapshot | 复合Tenant FK |
| `transfer.matter_link`→`matter_core.matter` | `(tenant_id, matter_id)`稳定复合FK；Link write-once |

默认不建立跨Schema FK：

- CommandRuntime→Responsibility；
- Task→完成Event；
- SubjectBinding→业务聚合；
- AuthorityBasis、EvidenceRef、Audit Ref、Event Ref；
- Workbench、Search和其他投影；
- Matter Core的`sourceTransferRequestId`历史采纳引用。

这些关系使用类型化Ref、精确Version/Digest、同事务Owner Port校验和ChangeGate不变量测试。

其中Tenant根FK是唯一的基础设施结构例外，在Manifest中登记为`TENANT_ROOT_STRUCTURAL`。它只证明Tenant存在和复合键一致，不授予Java Named Interface依赖、查询能力或跨Schema读取。`transfer.matter_link(tenant_id, matter_id)`可以引用`matter_core.matter(tenant_id, matter_id)`；MatterLink仍由Transfer拥有，禁止反向建立Matter Core到Transfer的FK。

所有跨Schema FK必须：

```text
NOT DEFERRABLE
ON UPDATE RESTRICT
ON DELETE RESTRICT
```

并满足：

- 租户级FK包含`tenant_id`；
- 除`TENANT_ROOT_STRUCTURAL`外，FK方向与Spring Modulith直接Named Interface依赖方向一致；
- 不形成物理依赖环；
- 禁止CASCADE、SET NULL；
- 禁止跨Schema业务Trigger、View或函数绕过Owner Port。

### 4.5 表形态机械规则

以下是每类表的最小列族和物理责任，不是通用基表、继承层次或统一字段名：

| 表形态 | 最小列族 | 可变与删除 | 必需访问/索引族 | 唯一Owner |
|---|---|---|---|---|
| Current State | Tenant/Identity、`row_version`、当前事实列；只有存在真实生命周期时才含封闭`state` | CAS更新登记列；业务删除须显式领域命令 | Tenant＋Identity唯一定位、Owner登记的当前查询、CAS版本 | 该聚合或当前对象模块 |
| Append-only Fact | Tenant/Fact Identity、准确SubjectBinding或类型化Scope、Definition Ref、Provenance、`recorded_at` | 仅INSERT；Correction/Revocation另起事实 | Tenant＋Fact Identity、Subject/Scope＋记录序列、代码登记的事实查询 | 事实语义Owner |
| Immutable Receipt | Tenant/Receipt Identity、幂等/工作身份、准确结果码、Payload/Result Digest Ref、`recorded_at` | 仅INSERT | Tenant＋Receipt Identity、幂等槽/结果回查唯一键 | 产生该Receipt的运行模块 |
| Work Queue | Tenant/Stable Work Identity、封闭状态、`available_at`、Lease、`claim_generation`、稳定Service Command ID | 只按状态机更新；终态不重开 | READY领取与Lease回收分离的部分索引、工作唯一槽 | 工作定义Owner；领取机制由execution runtime提供 |
| Operational Projection | Tenant/Projection Key、Source Ref/Version、Consistency Kind、Projection Version/Rebuild Marker、披露安全列 | STRONG由Owner事务内更新；DISCOVERY可重建 | 具名Query对应索引、来源版本核验 | Query/Projection Owner |
| Control Plane | 当前State的Active Ref/Digest、`row_version`、Generation；另有不可变Transition/Activation/Probe Receipt | 当前行CAS；历史仅INSERT | 唯一当前行、Generation/CAS、按时间与类型查历史 | 对应控制面模块 |

补充规则：

- Current State必须有`row_version`；只有对象确实具有生命周期状态列时，才要求封闭状态和迁移矩阵。
- Append-only Fact和Immutable Receipt不提供UPDATE/DELETE/TRUNCATE权限。
- Work Queue必须有稳定工作身份、状态、可用时间、租约、claim generation和幂等Service Command。
- `ExternalAction/ExternalDispatchOutbox/ExternalProbe`和Provider Inbox遵守第6.11、6.12节的特殊状态机，不得套用普通Work Queue模板。
- Operational Projection必须标明STRONG或DISCOVERY，并可从Owner权威状态重建或核对。
- Control Plane必须使用“当前状态＋追加变更记录”，不能只保留可覆盖的一行配置。
- `ActionDraft`是短生命周期Current State，不能成为业务事实源。
- 最小Matter的SemanticKind是ENTITY，但物理变化为创建后不可变；`aggregate_version`固定为1，不增加生命周期状态。
- 禁止用PostgreSQL继承、通用基表或万能`entity`表实现这些表形态。

### 4.6 最小Matter物理契约

`matter_core.matter`的SemanticKind是ENTITY，但创建后不可修改，不是带状态的Current State：

```text
tenant_id
matter_id
matter_ref
origin_kind = ACCEPTED_TRANSFER
source_transfer_request_id
source_snapshot_version
source_snapshot_digest
acceptance_decision_id
transfer_accepted_event_id
accepted_at
created_at
aggregate_version = 1
```

机械约束：

- `matter_ref`在Tenant内唯一、不可修改、不可复用，只能由Matter Core签发。
- `UNIQUE (tenant_id, source_transfer_request_id)`是Matter创建业务幂等约束。
- `origin`各字段创建后不可覆盖。
- 同一来源携带不同Snapshot Version/Digest或Acceptance Decision的普通陈旧命令返回`IDEMPOTENCY_CONFLICT`或`SUBJECT_VERSION_CONFLICT`；只有数据库已经出现相互矛盾的已提交事实时才创建RecoveryEpisode。
- `transfer.matter_link` write-once；一个TransferRequest最多一个MatterLink，一个Matter只有一个被接受转案Origin。
- 最小Matter禁止`status、matter_type、jurisdiction、owner、team、handling_status、metadata/extensions JSON`及未来能力预留列。
- 后MVP登记、分类、分配和办理模块只能引用Matter Identity，不能回写MatterRef或Origin。

## 5. 本体到关系模型的映射契约

### 5.1 TBox与ABox

Ontology Law System的本体语义分为：

- TBox：概念、定义、角色类型、关系类型、完成契约、策略和派生规则，保存在代码静态注册表与不可变RegistryManifest中。
- ABox：具体Party、Lead、Opportunity、Contract、Task、Decision、Fact、Relation和Evidence实例，保存在Owner模块的类型化关系表中。

MVP不引入：

- RDF Store；
- OWL推理器；
- Subject–Predicate–Object三元组表；
- EAV；
- 通用Relation表；
- 数据库动态本体编辑器。

### 5.2 SemanticKind

每个物理对象必须登记一个封闭语义类型：

```text
ENTITY
OCCURRENCE
REVISION
DOMAIN_FACT
DOMAIN_EVENT
DECISION
AUDIT
RECEIPT
RELATION
CANDIDATE
PROJECTION
WORK_ITEM
CONTROL
```

`semanticKind`与`tableShape`是两条正交轴。对象名称不能反向决定表形态：例如WaitReceipt登记为`RECEIPT + CURRENT_STATE`，CommandReceipt登记为`RECEIPT + IMMUTABLE_RECEIPT`。

代码构建时生成`OntologyPhysicalMappingCatalog`：

```text
OntologyPhysicalMapping {
  mappingKind = ONTOLOGY | TECHNICAL
  conceptCode?
  definitionVersion?
  definitionRef?
  technicalContractRef?
  ownerModule
  schemaName
  relationName
  semanticKind
  tableShape
  mutability
  tenantMode
  identityStrategy
  versionSemantics
  temporalSemantics
  provenanceProfile
  retentionProfile
  classificationProfile
}
```

`ONTOLOGY`映射必须引用RegistryManifest中的领域Definition Code/Version/Digest；`TECHNICAL`映射用于Lease、Claim、ReleaseState、ACL、迁移控制等非领域对象，必须引用版本化Technical Contract，禁止伪造领域概念。两类Ref通过CHECK保证互斥完整。

该Catalog不进入领域Definition Digest或Registry依赖闭包。它属于`DatabaseContractBundle`，由ChangeGate与真实数据库结构核对；物理表名变化不能反向篡改已经发布的领域Definition语义。

### 5.3 角色轴分离

以下“角色”不得落入同一张万能Role表：

1. Actor Type：Internal Actor、Customer Actor、Service Actor。
2. Membership/Position：用户在组织中的任职。
3. Capability与Data Scope：允许做什么、在哪些范围。
4. Authority Slot：能够作出哪类正式Decision。
5. Task Owner：某一个Task冻结的具体内部用户。
6. Contextual Party Role：某一商机、冲突、合同或转案中的客户、相对方、联系人等角色。
7. ProviderTransportPrincipal：外部供应商传输账户和验签主体，只能止步Inbox。

Party上禁止增加`is_customer`、`is_counterparty`等永久布尔标记。客户是Party在特定上下文和精确版本中的角色。

Contextual Role由上下文Owner保存不可变Snapshot：

```text
contextId
snapshotVersion/digest
partyRef
roleCode/version
validFrom/validTo
provenance
```

### 5.4 类型化Relation

每类正式关系由唯一Owner建立专用表或事实，不使用全局Relation表。Relation Definition至少冻结：

```text
relationCode/version
ownerModule
sourceType
targetType
direction
cardinality
identityRule
endpointVersionRule
validTimeRule
provenanceRule
mutabilityRule
```

典型关系包括MatterLink、EvidenceBinding、PartyIdentityResolution和Transfer采纳关系。

### 5.5 SubjectBinding判别联合

任何命令、Task、Decision、Evidence或派生事实对Subject的绑定必须属于三类之一：

```text
AGGREGATE_VERSION {
  subjectRef, aggregateVersion
}

REVISION_CONTENT_DIGEST {
  subjectRef, revisionId, contentDigest
}

SNAPSHOT_DIGEST {
  subjectRef, snapshotVersion, snapshotDigest
}
```

物理模型必须使用类型化列或分表，并通过CHECK保证判别联合互斥完整。禁止：

- 混用多组可空列而无判别约束；
- 只保存`subjectId`；
- 执行时自动绑定“最新版本”；
- 把SubjectBinding藏进自由JSON。

执行命令携带的`expectedVersion`用于比较，必须与冻结Binding中的`aggregateVersion`匹配，但不改变Binding字段语义。只有代码注册的`RegisteredIntakeBinding`、`TriggerBinding`或声明`CREATE_NEW_SUBJECT`的Provider Ingress可以在Subject尚未创建时使用定义允许的空`subjectBindings`集合；Command Scope及对应Intake/Trigger/ProviderIngress Binding本身仍必须准确存在并冻结版本，不得以Nil UUID或伪造Subject代替。

### 5.6 事实、事件、Audit和Receipt分离

| 对象 | 语义 |
|---|---|
| Domain Fact | 领域内已经成立的权威陈述 |
| DomainEvent | 某事实在事务中产生的追加通知信封 |
| AuditEntry | 谁基于什么授权执行了什么动作 |
| CommandReceipt | 某个幂等命令的不可变执行结果 |
| Projection | 为查询和交互形成的派生表示 |

禁止将Event当成Event Sourcing事实库，也禁止用Audit、Receipt或Projection反向替代领域事实。

Completion Event必须由权威领域事实或Decision在同一事务产生。它用于CompletionContract精确匹配，但不因此取得业务事实所有权，也不得由提交后的Event消费者完成Task。

### 5.7 认知状态与闭世界边界

不同认知状态必须由各事实Owner通过类型化对象或其私有封闭枚举表达，例如：

```text
CANDIDATE
ASSERTED
VERIFIED
REJECTED
DISPUTED
NOT_CAPTURED
NOT_APPLICABLE
INDETERMINATE
```

上述值不是全局`KnowledgeStatus`枚举，不得形成所有表通用的认知状态列。

只有Owner声明并验证了`CompletenessGuard`，才允许应用闭世界推断。没有找到记录不能自动推导为事实不存在、冲突CLEAR、付款未发生或材料完整。

### 5.8 权威派生事实

任何能够驱动Task、Gate或Deal激活的派生事实必须由该事实Owner保存类型化`DerivationProof/Provenance Snapshot`：

```text
derivationDefinitionCode/version/digest
inputRefs[]及准确Version/Digest
policyRefs[]
completenessGuardRef
result = SATISFIED | NOT_SATISFIED | INDETERMINATE
derivedAt
```

输入Ref必须使用类型化子表或封闭列组保存，不得落成自由JSON数组、万能Relation或动态推理表。`CompletenessGuard`和`DerivationProof`是Owner契约，不形成通用推理引擎。

输入变化后不得覆盖旧派生事实，应使其失效并形成新版本或新事实。AI Candidate不是DerivationProof，也不能产生权威派生事实。

### 5.9 身份解析与Same-As边界

Party身份解析在后续Party Schema细册中至少区分以下语义候选：

```text
POSSIBLE_MATCH
SAME_PARTY_CONFIRMED
NOT_SAME
```

确认相同Party不删除原Lead、来源或历史Ref，不自动转移权限、合同、Evidence或Matter，也不执行OWL式全局传递合并。

### 5.10 有效时间与记录时间

只在概念确实需要时保存：

- `valid_from/valid_to`：现实有效时间；
- `recorded_at`：系统记录时间。

有效区间统一为半开区间`[from, to)`。角色、授权或关系变化创建新Snapshot或新事实，不覆盖历史含义。MVP不建设通用双时间引擎。

## 6. 事务、并发、锁顺序、幂等与队列领取

### 6.1 总体方案

正式采用：

```text
READ COMMITTED
+ CommandRuntime唯一外层事务
+ 代码静态LockPlan
+ expectedVersion/CAS
+ 唯一责任槽和幂等槽
+ Immutable CommandReceipt
+ SKIP LOCKED仅用于工作队列
```

不使用REPEATABLE READ作为默认隔离级别，不使用SERIALIZABLE替代领域约束，不使用应用Advisory Lock。

### 6.2 五类事务

| 类型 | 事务边界 |
|---|---|
| Command Transaction | CommandRuntime开启，具名用例加入 |
| Query Transaction | 短查询事务，持有授权围栏后物化结果 |
| Work Claim Transaction | SKIP LOCKED领取并提交租约 |
| External Dispatch Transition | 单次外发状态迁移，外部调用不在事务内 |
| Technical Ingress | Provider/Evidence等传输验真与不可变接收 |

禁止在领域事务中执行Provider、对象存储、LLM、邮件、短信或电子签章调用。

### 6.3 Tenant授权围栏

`platform_meta.security_fence`是部署级稳定围栏行，Genesis初始状态必须是`FENCED_BOOTSTRAP`而不是`OPEN`。所有API/Worker的Command、Query、Work Claim、Work Result、External Dispatch Transition和技术Ingress事务，必须在任何Tenant或业务锁之前通过`DeploymentFenceRuntimePort`加入当前事务，并对`security_fence`及当前`SecurityVerificationState`取得同Rank的`FOR SHARE`。同次门禁必须重验`state=OPEN`、当前`security_generation`、Verification绑定该同一Generation、Capability与Principal两类Digest均为`MATCH`，且以数据库时钟判断`now < verified_until`；任一失败立即回滚并返回类型化不可用结果，不能继续接触Tenant、Subject、Inbox、Queue、缓存或创建`DISPATCHING`。Port返回冻结的`deploymentSecurityGeneration、verificationGeneration、verifiedUntil`供本次Context和Audit使用。Readiness、负载均衡或进程内最后绿色状态都不能替代该数据库门禁。Release/Principal Binding/Security Probe/Migration控制命令只可按其专用协议使用该围栏，不得伪装普通业务事务绕过。

`security_generation`是部署级单调CAS世代，但不是每个阶段都递增。进入一个新的Fence Episode或开始一个新的Capability/Principal安全图Candidate时只递增一次并冻结为`G`；该Episode内的Apply、Abort、Binding、Phase与Receipt只推进`row_version/phase`而保持`G`。Security Probe对准确`G`生成单调递增的`verification_generation`及`verified_until`；最终Unfence只CAS状态和Row Version并保持`G`，因此OPEN状态始终有绑定同一Security Generation的有效证明。旧缓存属于`G-1`并已失效，FENCED期间不得产生`G`缓存。禁止复用历史Generation或把Probe刷新误当成安全图变更。

Security Verification状态转换固定为：进入新Episode或Episode内每次受控安全图变更时写/保持`NOT_VERIFIED(G)`；只有准确Episode最终验证可以执行`NOT_VERIFIED(G) → MATCH(G)`；OPEN状态下的正常周期刷新只能`MATCH(G) → MATCH(G)`；OPEN状态下发现未授权漂移或验证失败才写锁存的`INVALID(G)`。`INVALID(G)`绝不能回到MATCH或复活G的缓存，Release Control必须创建`SECURITY_PROBE_RECOVERY` Episode并推进到新`G+1/NOT_VERIFIED`，再由该Episode最终验证形成`MATCH(G+1)`。

Probe运行模式封闭为两类：`PERIODIC_ACTIVE`只在Fence=`OPEN`时验证当前Active Contract；遇FENCED只签发`SKIPPED_FENCED` Receipt且不修改Verification。`EPISODE_VERIFY`必须携带准确`fenceEpisodeId、securityGeneration、candidateDigest`并仅在匹配的FENCED Episode内运行；失败保持`NOT_VERIFIED/FAILED_FENCED`供同一Episode修复后重试，成功才写MATCH。两种模式都遵守Rank 5锁序与CAS。Probe没有修改Security Fence或Unfence的权限；探针无法持续证明安全时，最迟在既有`verified_until`到期后由每个业务事务自身Fail Closed。

每次围栏必须有唯一外层`FenceEpisode(fence_episode_id, owner_purpose, candidate_release_id?, phase, security_generation, expected_row_version)`。`owner_purpose`至少封闭为`RELEASE | PRINCIPAL_BINDING | SECURITY_PROBE_RECOVERY | MIGRATION_RECOVERY | REPAIR`。发布Episode内的Migrator启停、Flyway、Role Graph Expand、Object ACL Reconcile、Principal Binding和Probe都只是同一Episode的受控子阶段，只能CAS推进其`phase/row_version`，无权独立解除外层Fence；只有Episode Owner在全部子阶段、临时Principal撤销、Release激活和绑定同一`security_generation`的新鲜Probe都闭合后，才能以准确`fence_episode_id + owner_purpose + expectedGeneration`执行最终Unfence。Standalone Principal Binding才创建并关闭自己的Episode。旧Episode、错误Purpose、缺少子阶段Receipt或Generation不匹配的Unfence必须失败。

创建`platform_meta`控制表的Genesis迁移必须在同一事务原子插入唯一`SecurityFence(state=FENCED_BOOTSTRAP, security_generation=1, row_version=1)`、与其配对的`FenceEpisode(owner_purpose=RELEASE, phase=FENCED_BOOTSTRAP, security_generation=1)`、`SecurityVerificationState(status=NOT_VERIFIED, boundSecurityGeneration=1)`和`PrincipalBindingState(status=UNINITIALIZED)`；这些是显式判别值，不得用NULL或缺行表达。初始Release Control只能携带该Genesis Episode ID和Expected Row Version接管，完成首次Binding、Probe、临时Genesis高权身份撤销和Release激活后才可解除Fence。

本版本选择严格Fence而不设置`FENCED_TRANSPORT_ONLY`旁路：Provider/Evidence等技术Ingress在FENCED时不得写Tenant级Inbox或返回成功确认。每个启用的ProviderContract必须冻结足以覆盖最大维护窗口的可靠回调重试或权威主动查询能力；两者都不具备时，进入Fence前必须停止新外发并把未决动作收敛到明确终态或受控人工模式。Fence前已经提交`DISPATCHING`且事务外Provider调用仍在进行的Attempt可能已产生外部现实；结果写入被Fence阻止时保持原持久化状态，解除Fence后按既有租约过期规则进入`UNKNOWN`并主动核对，绝不能因Fence自动重发。

`identity_access.tenant_authorization_state`为每个Tenant保存稳定围栏行。

- 普通受保护Command和Query：事务开始取得`FOR SHARE`。
- 任何改变有效授权路径或Actor可用性的命令：从事务开始取得`FOR UPDATE`并递增Tenant Authorization Generation。至少包括User启停/终止、IdentityBinding绑定或解绑、Membership、Permission/Capability、DataScope、Grant、Restriction、Delegation、Authority、EmergencyGrant、CustomerAccessGrant和ServiceActor Activation的创建、撤销、到期或最终生效。
- 锁模式由静态UseCase Definition声明，禁止先SHARE后升级。

由此保证：

- 撤权先提交，后续Command/Query等待后读到新Generation并失败。
- Command/Query先取得SHARE，则该次操作可完成，撤权随后生效。

Rank 20重验固定为：Internal/Admin锁定或稳定读取准确User与IdentityBinding；Customer锁定准确CustomerAccessGrant；ServiceActor锁定准确Activation。缺失、停用、到期、Generation不符或Tenant不唯一时立即拒绝。任何Actor类型都不能只依赖Tenant Fence而跳过自身可用性与绑定重验。

### 6.4 全局锁顺序

| Rank | 资源 |
|---:|---|
| 5 | Deployment Security Fence；普通API/Worker事务取FOR SHARE，受控安全/迁移转换取FOR UPDATE |
| 10 | Tenant Authorization Fence |
| 20 | Actor、CustomerGrant、ServiceActivation、Session |
| 30 | CommandExecutionSlot |
| 40 | 全部既有Task、Inbox、Work、Milestone Binding，包括将被完成、取消、替代或恢复的Task |
| 45 | IngressBusinessIdentitySlot；仅REGISTERED_INGRESS使用，必须在锁定Inbox后、任何业务聚合根前声明或锁定 |
| 50 | 具体Owner定义的Decision Requirement、SoD Occupancy或Approval Requirement当前行；不再包含Task |
| 55 | ExternalAction、RecoveryEpisode |
| 60 | Party |
| 70 | Lead |
| 80 | Opportunity |
| 90 | Conflict |
| 100 | Contract |
| 110 | Transfer |
| 120 | Matter Core |
| 130 | 新Responsibility Slot、WaitReceipt、Temporal Occurrence |
| 140 | 强一致Workbench Projection |
| 150 | Fact、Event、Audit、Outbox、Receipt追加写入 |

规则：

- UseCase Definition声明准确LockPlan。
- 同Rank按`resourceKindCode + tenant_id + 规范化稳定锁键字节序`排序；外部Opaque ID不强制转换为UUID。
- 一次取得本事务全部已知Task锁，禁止边处理边发现后再反向加锁。
- 加锁顺序与事实写入顺序是两个不同契约。

### 6.5 事实写入顺序

锁全部取得并完成重验后，事务内写入顺序为：

1. DecisionRecord（如有）及业务领域事实/当前状态。
2. 同事务DomainEvent。
3. 旧Task完成、取消或替代。
4. 后继Responsibility、WaitReceipt或Temporal Occurrence。
5. 强一致操作读投影。
6. Audit和所需Outbox。
7. Immutable CommandReceipt。

Task完成不得由事务提交后的Event消费者补做。

### 6.6 Command幂等

`CommandExecutionSlot`唯一键：

```text
(tenant_id, envelope_type, command_scope_digest, command_id)
```

`command_scope_digest`必须由服务端从已验证的InternalTask、InternalAdmin、CustomerGrant或ServiceActor权威Scope生成，并同时保存Scope Type、Canonicalization Profile和Digest Ref；客户端不得提交自由Scope或无法解释的裸Hash。

规则：

- 首次使用`INSERT ... ON CONFLICT DO NOTHING RETURNING`占槽。
- 禁止`ON CONFLICT DO UPDATE`。
- 冲突后读取原Slot和Receipt。
- 相同Command ID、相同Payload Digest返回原结果。
- 相同Command ID、不同Payload Digest永久冲突。
- Slot在创建时预绑定`receipt_id`；必须使用`DEFERRABLE INITIALLY DEFERRED` FK或等价数据库约束，保证已提交Slot必有Receipt。
- Task创建幂等、Command执行幂等和External Provider幂等必须使用三个不同的键。

`CommandReceipt`结果封闭为：

```text
SUCCEEDED
NO_CHANGE
REJECTED
```

数据库提交结果未知不属于Receipt状态。客户端必须使用相同Command ID和Payload查询/重入，禁止生成新Command ID。

### 6.7 Task与唯一完成事实

- 每个Task绑定一个准确`TaskDefinition`、一个`commandVariant`和一个`CompletionContract`。
- Task只能由匹配当前Task、SubjectBinding、Completion Contract和事实版本的唯一Event完成。
- Decision Task的唯一完成事实是`DecisionRecorded`。
- 同一完成Event不能完成多个Task。
- DONE和CANCELLED是终态，不得重开。
- 退回、补正、重试、转派和重新分配均创建新`task_id`并保存`predecessor_task_id`。
- Task Owner在创建时解析为一个具体有效的内部用户并冻结，角色、队列或组织不能成为Owner Ref。

Task状态机固定为：

```text
创建 → OPEN | WAITING
WAITING --nextCheckAt或恢复前提满足--> OPEN
WAITING --取消或替代事实--> CANCELLED
OPEN --准确Completion Event--> DONE
OPEN --取消或替代事实--> CANCELLED
OPEN --SYSTEM_RECOVERY--> WAITING
```

禁止`WAITING→DONE`；除`SYSTEM_RECOVERY`外禁止`OPEN→WAITING`。系统恢复不得移动法律期限、合同期限或冻结的`actionDueAt`。

`WaitReceipt`必须准确绑定已存在的下游Task、ExternalAction或权威等待事实。它不拥有责任、不能完成Task、没有业务写按钮；其状态只能由绑定的准确Event、Decision、ExternalAction权威结果或下游Task结果在正式事务中更新。需要用户再次行动时必须创建新Task。

Recovery链固定为：

```text
创建RecoveryEpisode
→ 原Task以SYSTEM_RECOVERY安全暂停
→ ResponsibilitySlot唯一创建RESOLVE_SYSTEM_RECOVERY运营Task
→ 修复成功后恢复原Task及同一Draft
   或取消原Task并创建准确的新Task
```

RecoveryEpisode创建、原Task暂停和恢复Task创建必须在同一事务完成。运营处置成功时，以准确完成事实完成恢复Task，随后同事务恢复/替代原Task并关闭Episode；失败或继续阻断时保持Episode非终态并形成准确后续责任。

Decision Command必须在同一CommandRuntime事务中：

```text
锁定并重验Actor、Authority Slot、Restriction与SoD
→ 插入不可变DecisionRecord
→ 写事实Owner派生业务事实
→ 追加准确DecisionRecorded及其他DomainEvent Envelope
→ 以该Event完成当前Decision Task
→ 写后继责任、Audit、Outbox和CommandReceipt
```

DecisionRecord固化`taskId、DecisionKind/version、SubjectBindings、Outcome、Actor、authorityBasisRef/version、Reason/Evidence`。任一步失败全部回滚。`DecisionRecord`具有`UNIQUE(tenant_id, task_id)`；Task Completion Binding具有`UNIQUE(tenant_id, completion_event_id)`；多槽审批通过类型化SoD Occupancy Slot及唯一约束防止同一Actor占用互斥槽。派生的TransferAccepted、QuoteAuthorized或返工Task均不能替代DecisionRecorded完成原Decision Task。

### 6.8 五个跨模块原子用例

以下用例必须在单个本地事务中完成，不由异步Event拼接；最低结果完整继承《项目结构、模块边界与构建契约 v1.0》第7.3节：

1. `RecordContactResultUseCase`：`ContactResultRecorded(VALID)`、幂等`OpportunityOpened`和首个商机责任。`CONNECTED`仍属于此前独立的`ContactAttemptRecorded`。
2. `RecordQuoteResponseUseCase`：`QuoteResponseRecorded(ACCEPTED)`、`QuoteAccepted`和绑定当前报价版本的PRE_CONTRACT审查实例及责任。
3. `SubmitTransferUseCase`：`TransferSubmitted(snapshotVersion/digest)`、同一Snapshot的PRE_TRANSFER审查实例及责任、销售WaitReceipt。
4. `ActivateDealUseCase`：一次性`DealActivated`、`TransferRequestInitialized`和准确Owner的`SUBMIT_TRANSFER` Task。
5. `AcceptTransferUseCase`：匹配当前Task、CompletionContract和全部SubjectBinding的`DecisionRecorded(TRANSFER_REVIEW, ACCEPT)`、`TransferAccepted`、`MatterCreated/MatterRef`、write-once MatterLink、案管Task DONE及销售WaitReceipt结果更新；只有该DecisionRecorded完成案管Task。

每个原子用例由事实起点模块拥有，目标模块通过显式本地Port校验和写入，禁止通用Coordinator和跨模块Repository。

### 6.9 Query事务

Query使用短事务，但不能声明PostgreSQL`READ ONLY`，因为需要对授权围栏执行`FOR SHARE`。

固定步骤：

1. 锁定并重验Deployment Security Fence。
2. 锁定Tenant授权围栏。
3. 重验Actor、Grant、Generation、Purpose和Restriction。
4. 执行Owner Query SQL。
5. 在同一事务中物化结果。
6. 若`QueryOperationDefinition/DisclosureProfile`要求审计，则在同一事务内先追加`QueryAccessAudit`；Audit失败不得返回结果。
7. 立即提交，提交成功后才向调用方披露结果。

分页每页开启新事务并重新鉴权。禁止用长事务承载文件流；Evidence下载和Range请求必须逐次经过应用网关重鉴权，并在签发任何下载能力或开始读取对象前成功提交授权Audit。流传输完成、失败和字节数可以另行追加技术Audit，但不能替代事前授权Audit。

Query Cache键必须至少包含：

```text
deploymentSecurityGeneration
tenantId
actorId
actorAccessFence =
  Internal(userId, accessGeneration)
  | Customer(grantId, grantVersion, revocationGeneration)
  | AdminStandard(userId, accessGeneration)
  | AdminEmergency(userId, accessGeneration,
                   emergencyGrantId, grantVersion, revocationGeneration)
tenantAuthorizationGeneration
purposeCode
queryDefinitionVersion
queryParametersDigest
```

Query Cache不是Rank 5的旁路。每次请求必须先在本次短事务内成功锁定并重验Deployment Security Fence、Security Verification与`deploymentSecurityGeneration`，之后才允许查找该Generation下的缓存；处于Fence、Probe过期或Generation不符时不得披露任何缓存结果。FENCE、安全图变更和UNFENCE都使旧Generation条目不可命中；实现可以惰性清理，但不能把旧值重新标记为当前。

### 6.10 普通工作队列

普通队列采用PostgreSQL持久化和Worker轮询：

```text
READY → CLAIMED → SUCCEEDED | FAILED
CLAIMED --租约过期或可重试失败--> READY(new claim generation)
```

不可重试错误或受控尝试耗尽进入FAILED；不得把所有失败自动退回READY。

领取方式：

1. 短事务先取得并重验Deployment Security Fence SHARE。
2. 使用`FOR UPDATE SKIP LOCKED`选择有限批量。
3. 原子更新Lease、Owner Worker和`claim_generation`。
4. 提交领取事务。
5. 事务外执行工作。
6. 使用稳定Service Command ID进入新的结果事务，并再次取得Deployment Security Fence SHARE。

队列为at-least-once，安全重领依赖稳定工作身份、Handler幂等和Claim Generation fencing。

### 6.11 ExternalAction、ExternalDispatchOutbox与ExternalProbe

三个对象必须分开：

- `ExternalAction`：外部效果意图及Attempt的权威Current State。
- `ExternalDispatchOutbox`：对一个Attempt的一次性派发工作与租约。
- `ExternalProbe`：UNKNOWN后可安全重试的只读权威查询工作。

ExternalAction不是普通可重试队列：

```text
PENDING → DISPATCHING → DISPATCHED | FAILED | UNKNOWN
DISPATCHED → SUCCEEDED | FAILED | UNKNOWN
UNKNOWN → SUCCEEDED | FAILED
```

- `DISPATCHING`租约过期、连接丢失或响应不确定必须进入UNKNOWN。
- UNKNOWN不得自动回到PENDING。
- 只有权威Provider回调、受信主动查询或受控运营取得的权威证据证明未发生外部效果后，才能创建新的`retry_of` ExternalAction。
- 外部效果唯一键为`(tenant_id, provider_account_ref, effect_key, attempt_no)`；Provider Idempotency Key由该元组确定性生成。`externalActionId`只是内部关联身份，不能替代`effect_key`。
- UNKNOWN只能由权威回调、受信主动查询或有权运营处置推进到终态。
- 用户命令完成只代表`ExternalActionRequested`已经持久化，不代表消息送达、签章完成或付款发生。

### 6.12 Provider Inbox

Provider Inbox物理拆分为：

1. 不可变接收与验真记录。
2. 可变处理状态和领取信息。

判别联合固定为：

```text
ProviderInboxKind = ACTION_CALLBACK | REGISTERED_INGRESS
ProviderInboxState = RECEIVED | QUARANTINED | PROCESSED
```

传输唯一键为`(tenant_id, provider_account_ref, provider_event_id)`，另行保存并比较第3.5节定义的`canonicalPayloadDigestRef`，其中Canonicalization Profile由Provider Contract Definition准确冻结。上游接口若使用“payload hash”术语，在数据库契约中也只能映射为该类型化Digest Ref，不能保存裸Hash。

同Provider事件键：

- 相同`canonicalPayloadDigestRef`：幂等重复。
- 不同Digest Ref：隔离并触发安全告警，不能产生领域副作用。

同EventId异Digest Ref不得修改原Inbox，必须追加`ProviderInboxConflictAttempt`。ProviderTransportPrincipal只负责验签和不可变接收；Worker只能依据代码注册的Definition构造固定ServiceActorCommand，Provider Payload和后台配置均不能选择Handler或Command名称。

`ACTION_CALLBACK`只能由已匹配的ProviderAccount与准确ExternalAction唯一派生Tenant；`REGISTERED_INGRESS`只能由代码登记的ProviderAccount与Ingress Binding唯一派生Tenant。零匹配或多匹配等同无法验真：只能追加部署级最小`SecurityIngressAttempt`并隔离，不得创建Tenant Inbox，也不得读取Provider Payload中的Tenant字段作为回退，更不得在普通业务表保存未经批准的原始Payload。

`ACTION_CALLBACK`处理必须在一个正式结果事务中按第6.4节完整LockPlan锁定Deployment Security Fence、Tenant Fence、Service Activation、CommandExecutionSlot、Inbox、全部受影响的既有Task、ExternalAction、准确Subject及需要更新的WaitReceipt。DML严格引用第6.5节并补充Inbox终结位置：业务状态/事实→DomainEvent Envelope→Task及后继责任/WaitReceipt→强一致投影→Audit和所需Outbox→Inbox PROCESSED→Immutable CommandReceipt最后写入。任一步失败时Inbox不得进入PROCESSED，Receipt也不得存在。

`REGISTERED_INGRESS`在创建Lead或交易输入前还必须声明`IngressBusinessIdentitySlot`。传输Event ID幂等与业务身份幂等是两个独立约束。

`REGISTERED_INGRESS`结果事务固定为：

```text
锁定Deployment Security Fence、Tenant Fence、Service Activation、CommandExecutionSlot和Inbox
→ 声明或读取IngressBusinessIdentitySlot
→ 执行Definition固定的具名用例
→ 写Subject及领域事实/当前状态
→ 追加DomainEvent Envelope
→ 创建首个责任及强一致投影
→ 追加Audit和所需Outbox
→ 最后将Inbox标记PROCESSED
→ 最后写Immutable CommandReceipt
→ 单事务提交
```

任一步失败时Inbox不得进入PROCESSED。同业务身份键、同`businessPayloadDigestRef`返回原Receipt/Subject；同业务身份键、不同Digest只能按Definition进入新Revision、人工核验或隔离，不得创建第二个独立Subject。`businessPayloadDigestRef`同样必须包含Algorithm与业务Canonicalization Profile Code/Version，不能退化为裸Hash列。

Provider只能止步Inbox，不能直接构造ServiceActor Command、Task、Decision或领域事实。

### 6.13 Temporal

Temporal Milestone使用稳定唯一键和稳定Service Command ID。重复扫描只能命中同一Milestone和同一命令结果。

时效内核只能产生到期事实或责任，不得跨域直接修改Opportunity归属、伪造Decision或完成业务Task。

Task与TemporalSnapshot必须同事务创建；Snapshot不可变并准确冻结：

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

Worker迟到不改变`scheduledAt`、`actionDueAt`或法律期限。SYSTEM_RECOVERY只暂停安全执行，不给业务责任重新计时。

### 6.14 SQLSTATE与提交不确定

| 类型 | 处理 |
|---|---|
| `40P01` deadlock | 整个Command最多受控重试3次 |
| `40001` serialization failure | 整个Command最多受控重试3次 |
| 唯一约束冲突 | 读取对应Slot/Fact，按领域冲突处理 |
| FK/CHECK/NOT NULL | 不重试，返回类型化Problem Detail |
| `08xxx`连接/提交不确定 | 查询CommandReceipt，使用相同Command重入 |
| 锁/语句超时 | 终止本次事务，按UseCase策略处理 |

禁止把所有数据库错误统一包装成“系统繁忙后重试”。

所有自动重试必须先完整回滚原事务，再使用相同Command ID、相同Payload和有界指数退避加抖动重入。授权失败、Subject版本冲突、FK/CHECK/NOT NULL、业务拒绝和职责分离失败不得自动重试。Problem Detail不得泄露SQL、约束名、表名或数据库原文。

## 7. 数据库身份、最小权限、不可变保护、加密与审计

### 7.1 四层保护

数据库安全同时依赖：

1. 本体Owner与表形态。
2. PostgreSQL Role和GRANT。
3. Java Owner Port、CommandRuntime和QueryAccessGuard。
4. ChangeGate真实PostgreSQL协议测试。

任一层都不能声称替代其他层。

### 7.2 数据库身份

固定能力角色：

```text
ols_object_owner       NOLOGIN
ols_api_cap            NOLOGIN
ols_worker_cap         NOLOGIN
ols_release_control_cap NOLOGIN
ols_security_probe_cap  NOLOGIN
ols_migration_cap       NOLOGIN
ols_ops_read_cap       NOLOGIN
ols_repair_cap         NOLOGIN
ols_destruction_cap    NOLOGIN
```

运行身份：

- API Login只继承`ols_api_cap`。
- Worker Login只继承`ols_worker_cap`。
- ReleaseActivator与`PrincipalBindingActivator`是同一受控发布工具的两个一次性子命令，只使用限时Release Control Login并只继承`ols_release_control_cap`；它们不是常驻APP_ROLE。
- Security Probe使用一次性、限时Login，只继承`ols_security_probe_cap`。
- Migrator使用临时、可审计、限时Login，只继承`ols_migration_cap`；Repair和Destruction在相应能力未解冻前没有可登录业务身份。

规则：

- API与Worker使用不同凭据、Secret Scope和网络策略。
- 所有OLS Login禁止直接获得Database/Schema/Object/Column Grant，只能通过`DatabaseSecurityManifest`登记的准确Capability Role获得权限；Principal Binding只做实际Login到符号能力槽的映射。
- API、Worker、ReleaseActivator和Security Probe不得成为Owner成员，不得拥有`CREATEDB/CREATEROLE/SUPERUSER/BYPASSRLS`。
- API、Worker、ReleaseActivator和Security Probe的Role Membership必须显式`SET FALSE`，不能切换到任何可提升权限的角色；除Migrator批准窗口外全部禁止DDL。
- `ols_release_control_cap`只允许读取发布/门禁证明，以CAS修改`ReleaseState/Security Fence/PrincipalBindingState`，并追加`ReleaseActivationRecord/PrincipalBindingTransition`；不得读取或修改业务Subject。
- `ols_security_probe_cap`只允许读取验证数据库安全图所需的系统目录，并写`SecurityVerificationState/SecurityProbeReceipt`；不得取得业务表、密文、Audit明细或通用查询权限。
- `ols_migration_cap`只在Maintenance Fence和批准窗口内允许以`ADMIN FALSE、INHERIT FALSE、SET TRUE`的登记Membership切换到`ols_object_owner`执行Flyway；窗口外实际Migrator Login不得存在有效绑定。任何其他Capability或Login都不能沿该链成为Owner。
- 从数据库撤销`PUBLIC CONNECT/TEMP`，从`public` Schema撤销`CREATE/USAGE`，对实际Owner执行精确`ALTER DEFAULT PRIVILEGES ... REVOKE`，默认撤销新函数EXECUTE及不需要的类型、序列权限。
- 不在`public` Schema创建对象。
- 固定`search_path=pg_catalog`；所有业务SQL必须Schema限定。

`ols_ops_read_cap`只能读取基础设施健康、队列积压和脱敏聚合View，不得读取Subject级业务记录、Audit明细、法律材料、业务基表、最高敏密文、Blind Index、对象存储Key或通用跨Schema View。任何具体用户、客户、合同或事项排障都必须走Admin Query＋QueryAccessGuard。`ols_repair_cap`和`ols_destruction_cap`在MVP中均为NOLOGIN、零业务表权限的保留角色；RecoveryEpisode由Worker经ServiceActor处理，业务事实修复只能通过正式Command或版本化Backfill。物理破坏性Contract尚未解冻，因此Destruction身份不得获得常驻DELETE或对象存储删除权限。

### 7.3 DatabaseSecurityManifest

代码和发布工件使用一个`DatabaseSecurityManifest`登记环境无关的NOLOGIN Role Attribute、能力角色之间的Membership及SET Option、Schema/Object/Column Grant、`search_path`、PUBLIC规则、Default Privilege Policy、PrincipalSlotContract和SecurityProbePolicy。实际LOGIN到符号身份槽的绑定由部署级`PrincipalBindingManifest`拥有。`DatabasePrivilegeEntry`只是对象权限子项，不是第二份手写清单：

```text
DatabasePrivilegeEntry {
  capabilityRole
  schema
  objectKind
  objectName
  privilege
  updateColumns[]
  lockOnlyColumns[]
  tableShape
  lifecycle
}
```

同一Manifest还必须登记环境无关的`PrincipalSlotContract`，实际Principal名称仍不进入该工件：

```text
PrincipalSlotContract {
  slotCode
  allowedCapabilityRoles[]
  requiredRoleAttributes[]
  forbiddenRoleAttributes[]
  membershipOptions[]
  databaseSetOptions[]
  directObjectGrantAllowed = false
  lifecycle
}
```

至少登记`API、WORKER、RELEASE_ACTIVATOR、SECURITY_PROBE、MIGRATOR、OPS、INFRA_ADMIN`槽。`INFRA_ADMIN`用于显式表达经批准的平台高权身份，不成为业务查询通道；`REPAIR/DESTRUCTION`在能力解冻前不得有ACTIVE Login槽。`PrincipalBindingManifest`只能把实际Login映射到这些固定槽及激活窗口，不能新增权限、Role Attribute或直Grant。

`lifecycle`封闭为`ACTIVE | LEGACY_COMPAT`。Expand期间N构建仍需要的权限必须保留为`LEGACY_COMPAT`，只能在后续ContractGate撤销。

`DatabaseSecurityManifest`的唯一可编辑源是版本控制下的`contracts/database-security/manifest.yaml`及固定Schema；它是静态部署契约，不由管理后台或数据库动态编辑。受控生成器产生规范Payload/Digest和各语言只读类型，禁止再维护第二份手写角色或GRANT清单。

Flyway只拥有Schema Object DDL真源；普通Flyway文件禁止手写`CREATE/ALTER/DROP ROLE、GRANT、REVOKE、ALTER DEFAULT PRIVILEGES`。数据库权限由DatabaseSecurityManifest唯一拥有：Role Attribute与环境无关Membership由INFRA工具生成受控Role Graph Plan，对象创建后的Ownership/ACL与Default Privilege由`DatabaseSecurityReconcile`根据Manifest、真实Physical Schema和previousReleaseBundle机械生成`ObjectAclApplyPlan`。由此既不把权限复制到Flyway，也不迫使`ols_object_owner`获得角色管理能力。

规则：

- Current State、Queue和Control Plane只授予准确可变列的UPDATE。
- PostgreSQL的锁定SELECT需要调用者对目标表至少拥有一列UPDATE权限。所有仅需`FOR SHARE/KEY SHARE`而无权修改语义状态的围栏表，必须提供非语义`lock_capability smallint NOT NULL DEFAULT 0 CHECK (lock_capability = 0)`列；Manifest以`LOCK_ONLY_UPDATE`只授予该列UPDATE和必要SELECT，不得把`state、generation、row_version、valid_until`等语义列授予调用者。该列不进入任何本体、SubjectBinding、Payload、Projection或业务版本Digest，但必须进入PhysicalSchemaDigest，且其`LOCK_ONLY_UPDATE`权限进入DatabaseSecurityManifestDigest与Capability ACL Snapshot；禁止用Trigger赋予额外语义。
- Fact、Revision、Receipt和Audit只授予INSERT/SELECT，不授予UPDATE、DELETE、TRUNCATE。
- 禁止`ALL TABLES IN SCHEMA`、通配默认授权和多余Sequence/Function权限。
- 缺权和多权均使ChangeGate失败。

真实PostgreSQL协议测试必须证明API/Worker能够按契约取得围栏`FOR SHARE`，但不能修改任一语义列，也不能把`lock_capability`改成非零；同值更新只具锁能力，不得推进任何Version、Generation或Audit。禁止用表级UPDATE、Owner身份或`SECURITY DEFINER`函数替代该最小列权限模式。

### 7.4 Append-only数据库保护

不可变保护不能只靠Java约定或Trigger：

- 对Append-only表显式撤销UPDATE、DELETE、TRUNCATE。
- API和Worker没有Owner权限。
- 原事实错误通过新Correction/Revocation事实纠正，不覆盖旧行。
- Audit纠正使用`AuditCorrection`引用原条目。
- 正常业务删除不得触及Fact、Event、Decision、Receipt或Audit。

### 7.5 Audit

所有正式Command由`AuditAppendRuntimePort`在唯一外层事务中追加类型化Audit。Audit写入失败，整个Command回滚。

Audit至少保存：

```text
tenant_id
actorRef与actorType
commandId/type
subjectBindings
authorityBasisRefs及准确版本
decisionBasisRef?
reasonCode
resultCode
payloadDigest
appliedPolicyRefs
参与校验的Access/Authorization Generation
参与校验的Deployment Security/Verification Generation与Verified Until
causation/correlation refs
occurredAt?
recordedAt
```

Audit保存Ref、Reason Code和Digest，不复制合同正文、Evidence内容、案情、证件号、银行信息或完整AI Prompt。

高敏Query、Evidence下载和External Disclosure是否需要Audit由各`QueryOperationDefinition/DisclosureProfile`静态声明，不能假设“只有Command需要审计”。

MVP不引入全局Audit Hash Chain；追加权限、备份留存、抽查和不变量验证比单链Hash更重要。

### 7.6 两层加密

第一层：平台默认静态加密，覆盖PostgreSQL数据文件、WAL、临时盘、备份介质（待DR设计）、对象存储和传输TLS。

第二层：代码注册为最高敏感级的结构化字段使用应用层AES-256-GCM。

每个加密字段在Owner表内显式保存：

```text
ciphertext
nonce              12 bytes
authentication_tag 16 bytes
key_mode = ENVELOPE_DEK
wrapping_key_handle
wrapping_key_version
wrapped_data_key
crypto_profile_code/version
aad_profile_code/version
encryption_instance_id
encryption_context_revision
ontology_concept_code/version
data_element_code/version
classification_code/version
subject_binding_digest_ref
```

可空加密字段必须通过CHECK保证整个ENVELOPE_DEK密文包全空或全非空，并固定`CHECK (key_mode = 'ENVELOPE_DEK')`。MVP表不预留`direct_data_key_*`列；未来启用DIRECT必须先升级规格并通过Expand新增物理表示。各Crypto Profile另行声明其他必填集合。禁止：

- 明文影子列长期共存；
- 把通用密文包藏进JSONB；
- 自制加密算法；
- KMS不可用时降级保存明文。

MVP唯一ACTIVE Crypto Profile是`ENVELOPE_DEK`：每次加密使用独立随机DEK，由版本化KMS Wrapping Key包装；这就是基础框架所称的“应用层AEAD信封加密”。`DIRECT`仅是RESERVED判别值，不得配置为ACTIVE，也不得产生新密文。

每次加密和重加密都必须生成全新的96-bit CSPRNG Nonce，并保证在所使用的准确DEK下唯一；禁止任何Nonce复用。Envelope-DEK Profile每次使用独立随机DEK时，Nonce唯一性边界是该准确DEK；`wrapping_key_version`只表示包装DEK的KMS/KEK版本，绝不伪装成Data Key Version。采用Envelope DEK时只持久化`wrapped_data_key`，永不保存明文DEK。KMS管理Key，SecretStore只管理访问KMS和Provider所需的凭据；数据库只保存不透明Handle、Wrapped Key和准确版本。

未来若启用DIRECT，必须先以新规格冻结KMS原子用量/Nonce分配，或独立`KeyUsageState`的原子Reservation、幂等键、重试和崩溃恢复；不得依赖进程本地计数、概率估算或遥测统计，并必须通过ChangeGate并发唯一性测试。

### 7.7 AAD

AAD绑定稳定语义：

```text
tenantId
ownerModule
ontologyConceptCode/version
dataElementCode/version
subjectBindingDigestRef
objectId
fieldCode
classificationCode/version
encryptionInstanceId
encryptionContextRevision
aadProfileCode/version
cryptoProfileCode/version
```

AAD不得绑定会随无关更新变化的`row_version`、`aggregate_version`、更新时间、表名或列名。

AAD中的每一项必须是加密行内持久化值，或能够通过不可变Definition Ref唯一重构。`subjectBindingDigestRef`必须对第5.5节准确判别联合的Canonical Bytes计算，并包含Digest Algorithm及Canonicalization Profile Code/Version；不能仅对`objectId`哈希，也不能绑定“最新Subject”。

与基础框架§22.2的机械映射固定为：`elementCode → dataElementCode/version`，`subjectRef → subjectBindingDigestRef覆盖的准确SubjectBinding`，`recordVersion → encryption_context_revision`；`objectId/fieldCode`是附加的密文位置绑定，不能替代前三项。解密前必须在线重验Actor/ServiceActor、Purpose、DataScope、DisclosureProfile和当前撤销Generation；KMS能够解密不等于业务有权读取。

### 7.8 Blind Index

只有代码注册的精确等值查询可以使用Blind Index：

- 独立的per-tenant、per-purpose、per-field HMAC-SHA-256 Key。
- 字段专用版本化Normalization Profile。
- 保存完整32字节HMAC。
- 索引包含`tenant_id + search_key_version + blind_index`。
- 轮换期查询所有仍有效Key Version，新写只使用Active Version。
- 命中后必须解密复核。

禁止前缀、范围、模糊Blind Index，禁止明文SHA-256和确定性加密。

Blind Index必须分别保存`search_key_version`与`normalization_profile_code/version`，不得复用Data Encryption Key Version字段。

### 7.9 Key生命周期

```text
ACTIVE_ENCRYPT_DECRYPT
→ DECRYPT_ONLY
→ PENDING_RETIREMENT
→ DESTROYED
```

- Current State通过独立`encryption_context_revision`执行Crypto CAS；重加密只更新加密列，不改变业务Aggregate Version、不产生业务Event，也不能覆盖并发业务字段。业务更新改变明文或任一AAD组成项时，必须重加密并同步推进该Revision。
- Immutable Fact/Receipt不因普通轮换UPDATE；MVP保持旧Key可解密。未来如需Rewrap，必须使用独立可变Crypto Envelope表或追加式CryptoRepresentation，不能直接更新不可变事实行。
- Key销毁等同数据销毁，受PreservationHold和双人复核约束。
- 在备份与灾难恢复框架解冻前，禁止自动进入DESTROYED，也禁止宣称所有副本已被清除。

### 7.10 外发门禁

普通DomainEvent、Outbox、Queue、Search、日志、Trace和AI遥测不得携带：

- 最高敏感明文；
- ciphertext、nonce、tag；
- Blind Index；
- keyHandle；
- Evidence正文或OCR全文。

真正需要外发原文时，只能通过ExternalAction专用加密Payload或受控Ref，在派发时执行：

1. Disclosure Profile校验。
2. 当前授权和Purpose重验。
3. Tenant、Subject和Provider Policy重验。
4. 追加Audit。
5. JIT解密并最小化发送。

### 7.11 Trigger、函数与维护

- 禁止数据库业务Trigger自动生成Fact、Event、Audit或跨Schema写入。
- 受控技术函数必须代码登记、`SECURITY INVOKER`、无动态SQL、无跨Schema业务DML。
- Break-glass只允许通过受控应用命令，不能获得SQL、Owner或DDL能力。
- 物理Repair和Destruction必须进入维护Fence，绑定准确对象清单、两名不同Actor、临时身份、前后Digest、Audit和不可变Receipt。
- PreservationHold在申请、批准和执行前均需重验；Hold优先于Retention和销毁。

## 8. Flyway迁移、Schema演进、jOOQ生成、索引与容量升级

### 8.1 总体方案

正式采用：

```text
唯一全局Flyway历史
+ Expand–Migrate–Contract
+ 代码注册可续跑Backfill
+ Physical Schema/jOOQ/Capability Security机械快照
+ Query–Index Catalog
+ C1容量验证与证据触发升级
```

不采用把长数据回填全部塞入Flyway，也不建设运行时Schema平台、自动索引系统或预先分片。

### 8.2 Flyway唯一迁移链

全库唯一历史：

```text
platform_meta.flyway_schema_history
```

Flyway之前必须完成唯一的`DatabaseSecurityBootstrap`：

1. 临时`INFRA_PROVISIONER`在目标Cluster创建准确Database、`ols_object_owner`、全部NOLOGIN Capability Role、环境无关Role Membership/SET Option、PUBLIC撤销与Default Privilege基线。
2. 按独立签名且双控制人批准的`GenesisControlPrincipalManifest`创建或绑定只在Genesis Episode批准窗口可用的Migrator、Release Control、Security Probe和一个临时Genesis Infra Principal；前三者分别只能继承`ols_migration_cap、ols_release_control_cap、ols_security_probe_cap`，最后一项必须绑定既有`INFRA_ADMIN`槽并冻结`activationPurpose=GENESIS_BOOTSTRAP`及一次性窗口，是首次Principal Binding创建/调整实际LOGIN所需的唯一临时角色管理根。`Genesis Infra Principal`是流程称谓，不是新增Slot或固定数据库用户名。Secret与网络路径只交给对应一次性控制程序，不能进入API/Worker。
3. 对实际安全图与`DatabaseSecurityManifest`的Bootstrap Scope做规范化比对。
4. 签发不可变`DatabaseSecurityBootstrapReceipt(manifestDigestRef, genesisControlPrincipalManifestDigestRef, securityChangeApprovalDigestRef, bootstrapToolDigestRef, postgresImageDigestRef, targetDatabaseRef, actualBootstrapSnapshotDigestRef, provisioningPrincipalRef, performedAt)`。
5. Bootstrap完成后立即撤销原始`INFRA_PROVISIONER`；若其同时承担Genesis Infra Principal，则只能缩限为已登记`INFRA_ADMIN`槽的Genesis窗口并保持在外层Fence内。Flyway创建`platform_meta`后，初始Release Control以`expectedPrincipalBindingVersion=GENESIS`启动首次Binding，该临时Principal只执行Candidate所需实际LOGIN/Role绑定；完成后必须先禁止新连接、关闭工具连接池、撤销Membership/网络/Secret并由独立Infra控制终止其全部PostgreSQL Backend，证明`pg_stat_activity`中该Principal活动会话为零，再签发包含`sessionClosureDigestRef`的`GenesisSecurityClosureReceipt`。随后才可提取最终PrincipalBindingSnapshot、CAS激活Genesis Binding并运行首个可用于Unfence的MATCH Probe。云平台内建管理员无法删除时必须作为普通`INFRA_ADMIN` Slot保留在最终Manifest/Snapshot并受周期探针监控；ReleaseActivationRecord只能引用临时身份与会话清理后的最终Digest和Verification。未形成Closure Receipt不得激活或Unfence。

该引导由外部部署控制面的内容寻址工具执行，不是应用内Installation Bootstrap、第三个APP_ROLE、Flyway脚本或常驻服务。它只能建立Flyway起跑所需的角色/数据库安全基线，不能创建业务表、Tenant、用户、Task或领域事实。后续新增环境无关Capability Role、Role Attribute或能力角色间Membership时，只能由同一INFRA工具的受控`SECURITY_EXPAND` Role Graph Plan执行并签发Receipt；`ols_object_owner`与普通Migrator不得获得`CREATEROLE`或角色管理能力。MVP不执行计划性Role Drop或收权；未来必须经ContractGate和独立`SECURITY_CONTRACT`协议后才能开放。

新增Capability Role、扩大能力角色Membership，或扩大API、Worker、Release、Probe、Migration权限，统一属于高风险Security Change，必须绑定两名不同有权Actor、准确Authority Basis和批准清单。Genesis在应用内审批主体尚未建立前，只允许使用封存的双控制根签名完成同等SoD；后续不得复用Genesis例外。

Flyway完成对象DDL后、提取Capability Security Snapshot前必须执行`DatabaseSecurityReconcile`。它只管理Database/Schema/Object/Column/Sequence/Function Ownership、Grant和Default Privilege，不得创建、修改、删除Role或改变Role Membership：

1. 输入当前DatabaseSecurityManifest、真实PhysicalSchemaDigest、previousReleaseBundle或GENESIS_BASELINE和固定工具Digest。
2. 机械生成不可变`ObjectAclApplyPlan`，列出每个Database/Schema/Object/Column/Sequence/Function的期望Before、After、Ownership/Grant/Default Privilege操作、Lifecycle和Plan Digest；任何Role Attribute、Role Membership或Role DDL操作都必须使Plan生成失败，禁止人工编辑Plan。
3. ChangeGate空库可按GENESIS执行EXACT；生产普通Expand只允许增加当前Manifest所需对象权限或保持LEGACY_COMPAT权限。计划性REVOKE只能在ContractGate放行后执行；未登记多权属于安全漂移，必须保持Fence并走安全Runbook，不伪装普通Contract。
4. 在Maintenance/Deployment Security Fence内，由临时Migrator经`ols_migration_cap → ols_object_owner`执行幂等Plan；失败保持Fence并从已提交Operation Receipt重鉴后继续，不能`repair`成成功。
5. 签发不可变`DatabaseSecurityApplyReceipt(manifestDigestRef, physicalSchemaDigestRef, previousBundleDigestRef, applyPlanDigestRef, beforeSnapshotDigestRef, afterSnapshotDigestRef, securityChangeApprovalRefOrNotApplicable, toolDigestRef, executorPrincipalRef, result, performedAt)`。
6. 只有Receipt成功且随后提取的CanonicalCapabilityAclSnapshot等于Manifest Effective Security Set，ReleaseGate才可继续。

Manifest、Schema、previous bundle与工具版本共同唯一决定Plan；`generatorToolchainDigest`覆盖Plan Generator。ChangeGate Evidence保存Plan/Receipt，生产ReleaseGate重新计算并比对；previousReleaseBundle封存上一Manifest与Snapshot，不能用当前分支伪造Before状态。

候选Release在激活前失败时，不得把已经应用的Candidate权限留给旧Release，也不得用普通ContractGate冒充回退。唯一允许的`SECURITY_ABORT`协议为：

1. `execution_runtime.ReleaseState.activeReleaseId`仍等于previous Release `N`，Candidate从未激活，且没有Candidate专属Event、Task、Decision、ExternalAction、Inbox领域结果、Backfill或外部效果；不满足任一条件只能向前修复。
2. `RELEASE` Fence Episode保持API/Worker `NOT_READY`和外层Episode已冻结的`security_generation`，只CAS推进`phase/row_version`并确认所有在途事务已经排空。
3. 只以可信previousReleaseBundle、准确Candidate Role Graph Expand/Object ACL Apply Receipt、当前PhysicalSchemaDigest和固定工具Digest机械生成不可编辑的`RoleGraphAbortPlan`与`ObjectAclAbortPlan`。它们只能逆转对应Receipt引入且从未成为Active Contract的增量；不得触碰业务事实、Principal Secret、既有Active权限或保留的Expand Schema。
4. INFRA工具只执行Role Graph Abort，Migrator/Owner只执行Object ACL Abort；二者均绑定原高风险批准清单并分别签发Operation Receipt，最终签发不可变`DatabaseSecurityAbortReceipt(candidateReleaseId, previousReleaseId, candidateSecurityReceiptDigests, abortPlanDigests, beforeSnapshotDigestRef, restoredSnapshotDigestRef, physicalSchemaDigestRef, toolDigestRef, executorPrincipalRefs, result, performedAt)`。Role仍被对象或Principal使用、存在未登记漂移或任一子计划失败时必须保持Fence并向前修复。
5. 重新提取PhysicalSchemaSnapshot并证明Schema Digest未变，再证明Capability Security Snapshot与previousReleaseBundle精确相等；Security Probe必须对外层Episode已冻结的同一`security_generation`形成新鲜MATCH，只有Release Episode Owner才能在不改变该Generation的前提下解除Fence，随后已通过Expanded-Schema冒烟的N构建才可恢复READY。

`SECURITY_ABORT`不是业务Schema Contract，也不是可逆Flyway/Down Migration；它只清理未激活Candidate的权限增量并形成可审计终局。Candidate一旦激活，即使尚未观察到业务流量，也不再允许使用本协议回退ReleaseState或ACL，只能向前修复。

迁移路径和命名：

```text
backend/src/main/resources/db/migration/<module-id>/
V<global-version>__<module-id>__<phase>__<description>.sql
```

规则：

- `global-version`全库唯一、严格递增、零填充，只表示机械DDL顺序。
- 所有模块目录由一次Flyway调用完整加载；APP_ROLE不改变迁移集合。
- 一个脚本原则上只修改一个Owner Schema。
- 已成功执行的文件、名称、路径、SQL和`.sql.conf`永久不可修改或移动。
- API和Worker只执行Validate与兼容检查，没有DDL权限。
- 禁止`R__`、`outOfOrder`、`baselineOnMigrate`、`clean`和选择性模块迁移。

固定配置：

```text
outOfOrder=false
baselineOnMigrate=false
cleanDisabled=true
validateOnMigrate=true
validateMigrationNaming=true
mixed=false
group=false
```

### 8.3 MigrationManifest

构建时机械生成：

```text
MigrationDescriptor {
  globalVersion
  ownerModule
  phase
  transactionMode
  riskClass
  affectedRelations[]
  dependencyVersions[]
  expectedLockClass
  lockTimeoutProfile
  statementTimeoutProfile
  rewriteExpected
  recoveryRunbookCode?
  changeSetId
}
```

`riskClass`封闭为：

```text
METADATA_ONLY
VALIDATION_SCAN
ONLINE_INDEX
TABLE_REWRITE
CONTRACT_DESTRUCTIVE
```

`TABLE_REWRITE`不得作为普通Expand进入MVP；`CONTRACT_DESTRUCTIVE`必须经过未来独立ContractGate。

版本、路径、Owner和SQL Digest可机械提取；`riskClass、expectedLockClass、rewriteExpected、recoveryRunbookCode`由版本化伴随Descriptor显式声明，再由SQL分析器和真实PostgreSQL验证。禁止仅靠解析SQL猜测风险，也禁止人工声明与实测不符仍放行。

### 8.4 Expand–Migrate–Contract

Expand允许新表、可空列、新旧结构共存、兼容索引和旧写路径已经满足的约束；禁止删除、直接重命名、直接改类型、长时间阻塞约束、挥发性默认值和旧代码无法满足的新写约束。

Migrate由Owner模块注册`BackfillDefinition`，以`BackfillRun`短批次执行：

```text
BackfillDefinition {
  backfillCode/version/digest
  ownerModule
  scopeKind
  sourceShapeVersion
  targetShapeVersion
  cursorKind
  preconditionCode
  postconditionCode
}
```

`BackfillDefinition`由被迁移数据的Owner模块拥有；`execution_runtime`只保存和调度`BackfillRun`、Lease、`BackfillBatchReceipt`与`BackfillCompletionReceipt`。Definition Owner实现Executor，并只能通过自身Owner Port修改自身Schema；Execution Runtime不直接读写被迁移数据，也不获得其业务语义所有权。

BackfillDefinition进入RegistryManifest。非终态BackfillRun存在时，旧Executor不得从构建中删除；Worker无法解释或执行任一可领取Run时必须`NOT_READY`。Representation Backfill不得推进业务`aggregate_version`或产生DomainEvent，只能使用独立存储CAS、Shadow字段或技术Revision。

数据库不得保存动态SQL、脚本或Handler类名。Backfill规则：

- 稳定Keyset游标与启动时High Watermark；
- 每批短事务和准确Tenant Scope；
- 条件写、源Version/Digest重验；
- 与正常业务写锁定同一Owner行；
- Worker崩溃后从最后已提交批次恢复；
- Representation迁移只产生技术Audit和Batch Receipt；
- 业务语义变化必须走正式Command。

Contract包括删除、改名、原地改类型、收紧旧写约束、移除旧兼容路径和提高Contract Floor。Expand与Contract至少跨一个已激活发布。

以下对象仍引用旧结构、旧Executor或旧解释能力时，ContractGate必须拒绝：

- OPEN/WAITING Task和ActionDraft；
- PENDING/DISPATCHING/DISPATCHED/UNKNOWN ExternalAction；
- 未处理Provider Inbox、Outbox、InternalWork或BackfillRun；
- 有效Configuration或Registry Activation；
- 仍需旧结构解释的Event、Decision、Receipt、TemporalSnapshot和Evidence Binding。

销售MVP继续禁止物理破坏性Contract。

### 8.5 在线DDL与约束

每个迁移会话必须设置有限`lock_timeout`和`statement_timeout`。拿不到锁必须快速失败，不得无限排队。

大表索引使用`CREATE INDEX CONCURRENTLY`时：

- 独立版本脚本；
- `executeInTransaction=false`；
- 一个脚本只有一个非事务主体；
- 配套准确恢复Runbook；
- 完成后校验`indisvalid/indisready`；
- 禁止`IF NOT EXISTS`掩盖定义漂移。

约束建立：

```text
CHECK/FK:
ADD ... NOT VALID
→ Backfill/修复
→ 独立VALIDATE CONSTRAINT

NOT NULL:
可空列→双写→Backfill
→ CHECK NOT VALID
→ VALIDATE
→ SET NOT NULL

UNIQUE:
重复预检/修复
→ CREATE UNIQUE INDEX CONCURRENTLY
→ 验证有效
→ ADD CONSTRAINT USING INDEX
```

`NOT VALID`仍约束后续写入；若旧构建可能产生新违规数据，该操作属于Contract而非Expand。

迁移失败恢复固定为：

- 事务型迁移失败：当前脚本整体回滚，ReleaseState、Schema Epoch和Registry激活均不推进。
- 非事务迁移失败：保持Maintenance Fence，重鉴Flyway History和系统目录，按准确Runbook清理无效索引，并生成不可变`MigrationReconciliationReceipt`。
- `flyway repair`只能移除已经核实失败的History记录；禁止修改成功迁移Checksum、接受缺失文件或把部分执行伪装成成功。
- Expand成功但激活失败：保留兼容扩展结构，不执行Down Migration；若Candidate已经应用Role Graph或Object ACL增量，必须先按第8.2节`SECURITY_ABORT`恢复上一准确Capability Security契约。只有Candidate从未激活、N构建Expanded-Schema冒烟通过、Abort Receipt与新鲜Probe均成功时才允许恢复N。
- 已经产生N+1专属Event、Task、Decision、ExternalAction、Inbox、Backfill或外部效果后，只能向前修复。
- 禁止通过反向SQL、删除Event、覆盖Receipt或撤销业务事实实现发布回滚。

### 8.6 Schema兼容状态

数据库兼容字段是既有`execution_runtime.ReleaseState`的物理组成，不创建第二张可独立推进`activeReleaseId`的状态表。每次发布激活CAS同时追加唯一类型的不可变`ReleaseActivationRecord`，不得另建含义重叠的ReleaseStateTransition账本。`platform_meta`只保存Flyway机械History、Database Security Bootstrap/Principal Binding/Security Verification部署控制面及其追加技术Receipt，不保存或推进ReleaseState、Registry Activation、Tenant业务状态或领域事实。

ReleaseState至少保存：

```text
ReleaseState {
  activeReleaseId
  databaseExpandEpoch
  databaseContractFloor
  flywayMigrationDigest
  physicalSchemaDigest
  databaseSecurityManifestDigest
  canonicalCapabilityAclSnapshotDigest
  databaseContractBundleDigest
  rowVersion
}
```

Flyway完成不等于发布激活。ReleaseActivator必须使用`expectedPreviousReleaseId` CAS推进状态，重验不可变ChangeGateReceipt、ReleaseGateReceipt、baselineBundleDigest和ReleaseManifest Digest，并固化实际Schema、Capability Security、Principal Binding和Bundle Digest。

不可变`ReleaseActivationRecord`还必须固化`releaseManifestDigest、expectedPreviousReleaseId、ChangeGateReceiptRef/Digest、ReleaseGateReceiptRef/Digest、baselineBundleDigest、canonicalCapabilityAclSnapshotDigest、databaseSecurityApplyReceiptRef/Digest、principalBindingManifestDigest、principalBindingSnapshotDigest`；Genesis或本Release执行过`SECURITY_EXPAND`时，还必须固化`databaseSecurityBootstrapOrExpandReceiptRef/Digest`，Genesis另须固化`genesisSecurityClosureReceiptRef/Digest`，其他发布相应字段为NOT_APPLICABLE而不是伪造空Digest。API和Worker必须运行相同Release和Database Contract，并分别重验自身`current_user`、有效能力和Security Fence；外部部署控制面使用受限一次性探测身份，在激活时及运行期间周期性同时重验数据库级Capability Security Digest和Principal Binding Digest，不增加第三个常驻APP_ROLE。任一不符即Fence两种角色并保持`NOT_READY`。不能只相信激活时写入的Capability Security Digest，因为激活后的Role Attribute、Membership、SET Option或越权Grant变化也必须被发现。旧构建只允许在经过验证的Expanded Schema上受控短期回退；一旦出现旧构建无法解释的新事实或外部效果，只允许向前修复。

### 8.7 唯一机械生成链

```text
固定PostgreSQL镜像Digest
→ 空库
→ 校验DatabaseSecurityManifest并执行DatabaseSecurityBootstrap，签发BootstrapReceipt；撤销原始Provisioner或将其缩限为INFRA_ADMIN槽中activationPurpose=GENESIS_BOOTSTRAP的临时Principal
→ 执行全部Flyway迁移
→ 提取并校验PhysicalSchemaSnapshot与Digest
→ 由DatabaseSecurityReconcile机械生成并应用ObjectAclApplyPlan，签发SecurityApplyReceipt
→ 复验PhysicalSchemaDigest未变并提取CanonicalCapabilityAclSnapshot
→ 在Genesis Fence内由临时Genesis Infra Principal完成测试LOGIN/Binding后先撤销该身份并签发GenesisSecurityClosureReceipt，再提取最终PrincipalBindingSnapshot、激活Binding并运行Security Probe
→ 按Owner Schema生成jOOQ
→ 编译Java并生成最终RegistryManifest
→ 生成并校验OntologyPhysicalMappingCatalog与QueryIndexCatalog，核对最终Registry、真实Schema及生产Query闭包
→ 生成DatabaseContractBundle
→ 与仓库机械快照零差异比较
```

Flyway SQL仍是唯一DDL真源。jOOQ、Schema Snapshot和Capability Security Snapshot都是证明工件，不是第二真源。

`PhysicalSchemaSnapshot`覆盖对象Owner、Schema、表、列、类型/typmod、默认值、生成列、PK/UK/CHECK/FK及动作、约束`convalidated`、索引键/INCLUDE/Predicate/Operator Class、`indisvalid/indisready/indislive`、列/索引Collation、Table/Index Reloptions、Partition Bound、Enum、Domain、Sequence、View、Trigger、函数、扩展、RLS和Policy；排除OID、统计、物理页和创建时间等环境噪声。

`DatabasePlatformProfile`另外冻结PostgreSQL镜像Digest、主版本、数据库Encoding、Locale、Collation Provider及实际Collation Version，避免平台差异被误当成相同Schema。

`generatorToolchainDigest`必须覆盖Database Security Bootstrap/Reconcile工具及Manifest Canonicalizer版本；ChangeGateReceipt的Evidence Bundle必须包含准确BootstrapReceipt。Bootstrap Receipt是引导证明，不是Schema、Registry或业务事实源。

必须证明：

1. 空库执行当前Flyway后的Digest等于预期。
2. 上一发布数据库执行N→N+1后的Digest等于同一预期。
3. 激活前目标数据库实际Digest等于Release预期。

### 8.8 jOOQ机械快照

生成路径：

```text
backend/src/generated/java/<base>/<owner>/internal/persistence/jooq/generated/
```

规则：

- 固定PostgreSQL镜像、Flyway/jOOQ/Java版本、Generator配置、类型映射、Locale、Timezone和Encoding。
- 每个Owner Schema独立生成。
- 禁止从开发、共享测试、预生产或生产库生成。
- 生成代码只能进入Owner的`internal.persistence`。
- 禁止进入API、Domain、OpenAPI DTO或跨模块Port。
- 禁止人工修改和非确定时间、主机名、绝对路径。
- 普通开发编译使用仓库快照，不依赖Docker。
- ChangeGate重生成并要求目录和Digest零差异。
- 跨Schema FK不得导致生成源码引用另一个Owner的jOOQ包；出现跨Owner生成类型引用时ChangeGate失败。

### 8.9 DatabaseContractBundle

```text
DatabaseContractBundle {
  bundleSchemaVersion
  databasePlatformProfileDigest
  flywayMigrationDigest
  physicalSchemaDigest
  jooqSnapshotDigest
  databaseSecurityManifestDigest
  canonicalCapabilityAclSnapshotDigest
  ontologyPhysicalMappingCatalogDigest
  queryIndexCatalogDigest
  generatorToolchainDigest
}
```

以上每个Digest均为第3.5节定义的类型化Digest Ref，不能保存裸字符串。`databaseSecurityManifestDigest`表示代码期望的环境无关安全契约。名称保持为`CanonicalCapabilityAclSnapshot`；其`actualSecuritySet`必须使用与`DatabaseSecurityManifest`中数据库可观测Security Entry相同的规范化Schema，但不包含Manifest-only Lifecycle、PrincipalSlotContract或实际LOGIN绑定。实际集合至少覆盖：

- NOLOGIN能力角色和Owner角色的Role Attribute，包括`LOGIN/SUPERUSER/CREATEDB/CREATEROLE/INHERIT/BYPASSRLS/REPLICATION`；
- 能力角色之间全部直接和传递Membership，以及每条Membership的`ADMIN/INHERIT/SET`选项；
- 角色级、数据库级SET Option与规范化`search_path`；
- Database、Schema、Object、Column、Sequence、Function的全部Grant；
- Database、Schema和Object Ownership；
- Default Privilege和PUBLIC规则；

`ACTIVE | LEGACY_COMPAT`是Manifest中的发布契约元数据，PostgreSQL系统目录不能直接观测，因而不得伪造进实际Snapshot。ChangeGate比较规则固定为：

```text
CanonicalCapabilityAclSnapshot.actualSecuritySet
  == DatabaseSecurityManifest中ACTIVE与LEGACY_COMPAT条目的effectiveSecuritySet并集
```

缺项、多项、未登记传递Membership或属性漂移均失败；生命周期从ACTIVE到LEGACY_COMPAT再到撤销的合法性，另由previousReleaseBundle、Compatibility Matrix和ContractGate校验。该Snapshot只排除环境相关的实际LOGIN Principal名称及其凭据绑定，不得排除能力角色本身的安全属性。

实际LOGIN Principal名称和凭据轮换属于部署环境，不进入跨环境DatabaseContractBundle。部署控制面维护版本化`PrincipalBindingManifest`，只描述允许的符号身份槽、其激活窗口到实际Principal的映射；实际LOGIN必须满足对应`PrincipalSlotContract`冻结的连接属性及准确Database上的`pg_db_role_setting`，其中`search_path`必须规范为`pg_catalog`。激活时另行提取`PrincipalBindingSnapshot`，它必须枚举：

- 所有直接或传递拥有任一OLS Capability、Owner或特权成员关系的LOGIN Principal；
- 对OLS Database、Schema、Table、Column、Sequence或Function拥有直接Grant的LOGIN；
- 任一`SUPERUSER/BYPASSRLS/CREATEROLE/CREATEDB/REPLICATION` LOGIN；
- OLS Database、Schema或Object Owner；
- `pg_read_all_data`、`pg_write_all_data`或其他能够隐式读取、修改OLS对象的内建/平台角色成员；
- API、Worker、ReleaseActivator、Migrator、Ops、Repair、Destruction以及云平台基础设施管理员等临时或常驻身份。

每个被枚举Principal都必须验证Role Attribute、直接/传递Membership及其`ADMIN/INHERIT/SET`选项、准确Database上的SET Option、规范化`search_path`和直接Grant。云平台管理员必须映射为显式`INFRA_ADMIN`符号槽，不能作为快照外的隐含旁路。把`PrincipalBindingManifest`的实际名称映射代入`DatabaseSecurityManifest.PrincipalSlotContract`后，期望集合必须与Snapshot精确相等；任何未登记Principal、传递成员关系、直Grant或高权身份均使激活失败。

`PrincipalBindingActivator`必须使用`expectedPrincipalBindingVersion`并追加`PrincipalBindingTransition`。Transition至少固化签名Manifest Digest、Expected Previous Version、实际Snapshot Digest、Command/Change Receipt、Authority Basis、审批Actor及时间。新增或扩大`INFRA_ADMIN`、API/Worker、Migration窗口或任何高权Slot属于高风险变更，必须由两个不同有权Actor复核；同一Slot、权限图完全不变的Secret/凭据轮换，才可由版本化政策允许自动化。实际Membership变更和Manifest激活不能因来自同一操作者而绕过签名、职责分离或Audit。

两类Snapshot Digest写入`ReleaseActivationRecord`。受限一次性安全探针在激活时以及外部部署控制面的周期任务中同时复验`CanonicalCapabilityAclSnapshot`和`PrincipalBindingSnapshot`两类数据库级Digest，API/Worker Readiness只复验自身绑定与Security Fence。探针不是常驻应用Host、第三个`APP_ROLE`或业务查询通道。

版本化`SecurityProbePolicy`冻结最大验证间隔；它是`DatabaseSecurityManifest`规范Payload的部署级Technical Contract子项，由现有`databaseSecurityManifestDigest`覆盖并随DatabaseContractBundle校验，不新增游离Digest，也不是Tenant动态配置或业务Registry Definition。`platform_meta`拥有唯一当前`PrincipalBindingState`、`SecurityVerificationState(boundSecurityGeneration, verificationStatus, expectedCapabilityDigest, actualCapabilityDigest, expectedPrincipalDigest, actualPrincipalDigest, verifiedAt, validUntil, verificationGeneration, rowVersion)`及追加式`PrincipalBindingTransition/SecurityProbeReceipt`；`verificationStatus`封闭为`NOT_VERIFIED | MATCH | INVALID`，每个Probe Receipt必须固化`observedSecurityGeneration、verificationGeneration、expected/actual Digest、result、verifiedAt、validUntil`。`execution_runtime.ReleaseState`仍是唯一发布激活权威点，两者不得合并或互相代替。API与Worker Readiness及每次Rank 5事务门禁都要求Verification绑定当前Security Generation、两类Digest均`MATCH`且`now < validUntil`；探测失败、权限被撤、状态过期或无法持久化新结果都必须Fail Closed，不能沿用最后一次绿色结果。Unfence CAS必须同时证明当前MATCH的`boundSecurityGeneration == security_fence.security_generation`并冻结所采纳的`verificationGeneration`；旧G的MATCH不得用于新Episode。

Principal Binding的实际Role DDL与`PrincipalBindingState` CAS不尝试跨系统原子事务，而使用强制安全围栏Saga：

```text
IDLE
→ FENCED_PREPARED
→ INFRA_APPLIED
→ SNAPSHOT_VERIFIED
→ MANIFEST_ACTIVATED
→ PROBE_MATCHED
→ UNFENCED

任一步失败 → FAILED_FENCED
```

1. Standalone Principal Binding由PrincipalBindingActivator以Release Control权限对Deployment Security Fence（Rank 5）取得`FOR UPDATE`，创建`owner_purpose=PRINCIPAL_BINDING`的Episode并一次推进新的Security Generation；发布中的Binding则必须携带并加入既有`owner_purpose=RELEASE` Episode，不得创建或覆盖外层Episode。由于所有API/Worker事务先持有该行SHARE，首次FENCE锁必须等待全部在途事务结束并阻止新事务越过。随后在同一短事务写入`FENCED_PREPARED`、签名Candidate Manifest/Expected Version并把当前Episode Verification置为`NOT_VERIFIED(G)`后提交。被阻塞或后续请求取得SHARE后必须看到FENCED并在接触Tenant/Queue前回滚；API/Worker Readiness同时为NOT_READY。
2. 外部INFRA控制面按Candidate执行准确CREATE/ALTER ROLE、GRANT/REVOKE和SET Option；尚未激活的新Principal不得取得可用Secret或网络路径。
3. 提取完整PrincipalBindingSnapshot并与Candidate映射后的PrincipalSlotContract比较；不相等时保持Fence。
4. 比较成功后才以CAS推进`PrincipalBindingState`、追加Transition和Snapshot Receipt。
5. 立即运行一次受限Security Probe；它只写绑定当前Episode Security Generation的新鲜MATCH证明，无权解除Fence。Standalone Episode由PrincipalBindingActivator在证明闭合后CAS解除；Release Episode只推进子阶段，最终仍由Release Episode Owner在全部发布条件闭合后解除。
6. 失败时只能在Fence内恢复前一准确权限图或继续完成Candidate，并追加`PrincipalBindingRecoveryReceipt`；禁止手工改绿色状态或沿用旧Probe结果。

任何被撤销、替换或过期的Login在最终Snapshot/Probe/Unfence前都必须执行相同的`PrincipalSessionClosure`：先阻止新连接，再关闭连接池与凭据入口，由独立控制身份终止全部旧Backend，复验活动会话为零并把Principal、Backend集合Digest、终止结果和观察时刻写入不可变Receipt。仅执行`NOLOGIN/REVOKE`不能视为已关闭既有会话。Migrator和临时Infra不得有任何例外；最终Unfence事务自身使用的已登记Release Control Principal必须只用`SET LOCAL ROLE`，并在提交后自动复位，不能持有可复用的临时Owner会话。

代码登记的旧＋新短重叠窗口也必须作为一个显式Candidate Manifest走上述Saga；若最终只保留新Principal，则到期撤销旧Principal是第二个受围栏保护的Transition。Security Probe和Migrator Principal的创建、Membership变更及撤销同样适用；仅Secret值变化且Principal名称、Role Attribute、Membership、SET Option、直Grant和网络授权范围都不变时，不属于Binding图变更。

本节将外置发布工件显式升级为`ReleaseManifest Schema v2`，新增`databaseContractBundleDigest`并由用户冻结的第8节批准。该字段必须同步进入基础框架和项目结构规格；它不与RegistryManifest或Application Build Digest形成摘要自引用。

`previousReleaseBundle`必须包含DatabaseContractBundle以及上一版准确Jar/Image引用，并来自数据库当前`activeReleaseId`对应的内容寻址、不可变发布工件。N兼容冒烟必须运行该封存工件，禁止从当前Git分支、可变URL、开发数据库或当前源码临时重建“上一版本”。首次发布只使用封存的`GENESIS_BASELINE`。

### 8.10 PostgreSQL类型化搜索基线

MVP搜索继续采用PostgreSQL类型化投影，不引入万能`search_document`、外置搜索引擎或客户Evidence全局全文索引。

匹配顺序固定为：

1. B-tree标准化精确匹配。
2. B-tree前缀匹配。
3. `pg_trgm`仅用于姓名、机构名称、别名等代码白名单非高敏字段。
4. `tsvector + GIN`仅用于登记的非高敏受控文本。

每个Owner维护自己的`*_lookup_projection`，至少保存：

```text
tenant_id
subject_ref
subject_version
projection_version
<Owner按登记Schema展开的显式命名搜索列>
```

尖括号行是设计占位说明，不得实现为名为`search_safe_fields`的JSONB、数组或通用字段袋。

证件号、电话、邮箱、银行账户只能在准确Permission、Purpose和Disclosure Profile下使用第7.8节的精确Blind Index。Evidence正文、OCR、AI摘要和Embedding不进入MVP全局搜索。

候选搜索仅限代码登记的Internal/Admin Query，最多返回5个已授权候选Ref，不返回未授权总数或高敏命中片段。Customer只能读取当前CustomerGrant绑定的准确Subject，不提供候选搜索或数量。候选查看、选择和后续Command必须回Owner Query/Command进行当前版本和四轴授权重验。

### 8.11 Query–Index Catalog

建立代码注册、机械生成快照的`QueryIndexCatalog`：

```text
QueryIndexDefinition {
  queryId
  queryOperationDefinitionRef及code/version/digest
  ownerModule
  queryFacet
  consistency
  sqlShapeDigest
  securityPredicateProfileRef
  parameterizationProfile
  requiredPredicates
  orderAndPagination
  maxPageSize
  expectedCardinalityAndSkew
  serviceSlo
  databaseBudget
  servedByIndexes[]
  planAssertions
  capacityProfileRef
}
```

规则：

- 每个非约束索引必须反向关联具名Query、Queue Claim或维护用例。
- PK/UK索引标记为`CONSTRAINT_OWNED`。
- Tenant查询原则上以`tenant_id`为首个等值键。
- 跨Tenant Worker Claim是显式登记的ServiceActor例外。
- 列表统一Keyset Pagination，禁止深`OFFSET`。
- Catalog表达设计意图，PostgreSQL Planner保留最终选择权。
- 禁止运行时自动建删索引。
- ChangeGate检查孤儿索引、重复前缀、未登记表达式索引和无证据的宽INCLUDE。

Partial Index只允许稳定状态Predicate，例如OPEN、READY、PENDING、RECEIVED、SCHEDULED；禁止`due_at < now()`等动态时间Predicate和Tenant专属索引。

READY领取与租约回收使用不同索引。`ExternalDispatchOutbox`为PENDING/单次领取建立索引；`ExternalAction`为DISPATCHING、DISPATCHED和UNKNOWN状态核验建立独立索引；`ExternalProbe`按`next_probe_at`建立只读核验领取索引，三者不得共用普通重试索引。

宽`INCLUDE`只用于低更新、窄列操作读投影，禁止用于Task、Queue、Outbox、JSONB、密文、Evidence和ExternalAction Payload。

BRIN只作为大规模、按`recorded_at`自然追加历史表的实验候选，不能替代Subject/Aggregate精确B-tree、授权查询索引或Queue Claim索引。禁止为每个FK、JSONB字段或“未来可能查询”预建索引。

容量验证必须运行真实jOOQ/JDBC绑定路径，不能用手写常量SQL替代生产Query。固定状态常量、Tenant/Scope Predicate及用户参数的绑定形态必须进入`sqlShapeDigest`。

### 8.12 热表与Autovacuum

- 本节直接复用冻结的C1包络：300～500内部用户、50～100同时在线会话、持续/突发业务写10/50次每秒、操作读100/300次每秒、每日2,000～5,000线索、单次异步导入100,000行、每日新Task不超过50,000、OPEN＋WAITING不超过500,000、Provider回调20/100次每秒、约1亿追加记录和约500GB PostgreSQL在线数据。
- C1-V1验证资源固定为1个4 vCPU/8 GiB API实例、1个4 vCPU/8 GiB Worker实例、PostgreSQL 18的8 vCPU/32 GiB及至少3000 IOPS SSD；使用主Tenant和隔离哨兵Tenant、固定Fixture Digest、10分钟预热、30分钟稳态和60秒突发。
- 所有业务表禁止关闭Autovacuum。
- 热表使用版本化的表级Tuning Profile。
- 禁止把`VACUUM FULL`作为常规Runbook。
- 限制长事务和`idle in transaction`。
- 大批量导入改变分布后执行受控`ANALYZE`。
- C1必须包含`AuthorizationFence-Stress-V1`：同一主Tenant至少300次/秒受保护Query/Command同时持有`security_fence + SecurityVerificationState`两张部署控制行及Tenant Authorization Fence的SHARE；在Tenant层持续1次/秒、60秒突发10次/秒执行启停/撤权/Restriction类UPDATE，按SecurityProbePolicy频率执行`PERIODIC_ACTIVE`刷新，并至少执行一次Probe失败→`INVALID`→`SECURITY_PROBE_RECOVERY` Episode→`EPISODE_VERIFY`→UNFENCE演练。验证全部在途事务排空、FENCED/INVALID后无业务DML或缓存披露越过、恢复后无旧Generation请求成功，且p99满足对应服务SLO。

C1-V1初始校准值：

| 表型 | Vacuum scale/threshold | Analyze scale/threshold | Fillfactor |
|---|---|---|---:|
| Queue/Outbox/Inbox | 0.01 / 500 | 0.02 / 500 | 80 |
| Task及高更新Current State | 0.02 / 2000 | 0.02 / 2000 | 85 |
| 追加事实表 | 重点校准insert-vacuum | 0.02起测 | 100 |

这些值属于可验证物理Profile，不是领域不变量。

C1-V1必须保存真实jOOQ SQL、p95/p99、`EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS)`、扫描行/返回行、Buffer、WAL、临时文件、`n_dead_tup/n_live_tup`、`n_mod_since_analyze`、Autovacuum延迟、XID年龄、长事务阻塞、最老READY年龄以及表/索引增长率。Authorization Fence专项还必须分别记录`security_fence`、`SecurityVerificationState`和Tenant Fence三类行的`datminmxid/relminmxid`、`pg_stat_slru`中的MultiXactOffset/MultiXactMember、锁等待分布、Probe写者等待、在途排空时间、WAL和MultiXact年龄趋势。若出现持续SLRU/锁热点或SLO失败，先实验分层/分片Authorization Fence或更细粒度Generation设计并重新证明撤权及全局Fence顺序；本版本不提前引入分片，也不得为性能删除当前安全围栏。SQL、索引、Partition、Queue Claim算法、授权围栏算法、PostgreSQL主版本或Reloptions变化时，相关CapacityGate证据立即失效并需重测。

### 8.13 分区、归档与升级触发

MVP默认不分区Current State、Task、Work Queue、授权表、Tenant表、ExternalAction活动状态和ExternalDispatchOutbox；禁止一Tenant一分区和按日过早分区。

进入C2评审的完整上游触发器保持不变：连续10个业务日达到C1的70%以上；CurrentCard或Command SLO连续两个周期不达标；最老READY持续超过5分钟；PG CPU、连接池或存储吞吐持续超过70%；热点表/索引接近实测阈值；搜索超过25%的PG CPU/I/O且仍不达标；10万行以上导入成为日常；或单模块占数据库写入/Worker处理量50%以上。

分区只作为追加历史表候选：DomainEvent、Audit、Immutable Receipt、Provider不可变接收记录和大规模Evidence元数据历史。

进入分区实验的评审触发器，满足任一即可，但不自动分区：

- 单表约1000万行；
- 表加索引约25GB；
- 单索引约10GB；
- Retention处理产生不可接受的WAL或维护窗口；
- 完成SQL、索引和统计优化后仍违反C1 SLO。

推荐按数据库`recorded_at`做月或季度Range Partition。启用前必须解决跨分区全局唯一、稳定ID定位、查询裁剪、父表统计和Hold影响。必要时由Owner增加窄的未分区`IdentityGuard/Locator`。

分区数量优先控制在可维护的月/季度范围，禁止日分区和无证据子分区。父分区在数据分布明显变化后必须显式`ANALYZE`。分区中任一记录受PreservationHold时，MVP默认保留整个分区，不实现“抽出Hold行后删除其余行”。

Detach Partition不等于归档完成或数据销毁。任何Drop必须经过Retention、Hold、双人销毁和Audit；当前备份/灾备Deferred期间不启用自动物理删除。

本节阈值只补充《基础框架设计规格 v1.0》第27.3节，不替代其任何C1/C2触发器。完整升级顺序固定为：

```text
SQL、索引、批量和连接池优化
→ 同版本API/Worker横向扩容
→ 追加表分区与归档
→ 独立Worker池
→ 只读副本或外置搜索
→ Kafka
→ 最后才评估拆服务
```

外置搜索只在PostgreSQL类型化搜索持续违反SLO、占用约25%以上PG CPU/读I/O且完成PG优化后评估。Kafka只在最老READY持续超过5分钟、Backlog增长、完成索引/批量/Worker/Autovacuum优化，并完成分区可行性实验后评估；证据支持时实施分区，证据不支持时保存不采用分区的容量结论。还必须证明瓶颈确属PG Queue而不是Provider、AI或业务Handler。

即使引入Kafka，CommandReceipt、DomainEvent持久化、ExternalAction、ExternalDispatchOutbox、UNKNOWN和Provider Inbox仍以PostgreSQL为权威。Kafka不得承担ExternalDispatchOutbox单次派发或ExternalAction UNKNOWN恢复语义。

### 8.14 Gate

ChangeGate至少验证：

- DatabaseSecurityManifest规范化、DatabaseSecurityBootstrap从空Cluster可重复执行、BootstrapReceipt完整；Genesis控制表原子初始化，临时Provisioning/Genesis Infra Principal按最终态顺序撤销且GenesisSecurityClosureReceipt完整；
- 空库完整Flyway迁移与History唯一性；
- Flyway无手写权限DDL；Role Graph Plan与ObjectAclApplyPlan职责分离，PhysicalSchemaSnapshot先于ObjectAclApplyPlan提取，后者由Manifest＋该真实Schema Digest＋可信Before Bundle唯一生成；应用后Schema Digest保持不变、Receipt成功且Capability Snapshot精确匹配；
- N数据库→N+1迁移；
- 历史迁移Checksum无漂移；
- jOOQ重生成零差异；
- Physical Schema和Capability Security与Manifest一致；
- Expand无删除、改名、直接改类型、旧写不兼容约束和提前撤权；
- Backfill中断续跑及并发业务写安全；
- 不可变历史行和旧Digest不变；
- Query–Index Catalog没有缺失、孤儿或重复索引；
- 非事务索引失败可确定恢复。

ReleaseGate至少验证：

- Genesis或新增Capability Role时存在与本Release Manifest/工具/PostgreSQL镜像匹配的DatabaseSecurityBootstrap/Expand Receipt，且未保留未登记Provisioning Principal；
- Genesis Release引用临时高权身份清理后的最终Principal Snapshot、Verification和GenesisSecurityClosureReceipt；新增Capability Role、Membership或能力扩张有两个不同Actor的批准依据；
- 生产Role Graph Expand/Object ACL Apply Plan与ChangeGate证明的Manifest/Schema/previousReleaseBundle闭包一致，各Receipt成功且无未批准计划性REVOKE；若候选发布中止，则只有未激活Candidate可以执行的`SECURITY_ABORT`闭包、Abort Receipt、恢复后的上一版Capability Snapshot和新鲜Probe全部通过后，旧Release才可READY；
- 在上一正式数据库副本完成升级；
- 上一准确构建能在Expanded Schema完成关键读写冒烟；
- 新API和Worker分别用自身数据库角色启动；
- 目标库Schema、Capability Security、Flyway、Expand Epoch和Contract Floor与ReleaseManifest一致；
- 按第8.9节证明`CanonicalCapabilityAclSnapshot.actualSecuritySet`与`DatabaseSecurityManifest`中`ACTIVE | LEGACY_COMPAT`的`effectiveSecuritySet`精确相等，并分别校验两个工件各自的Digest；将`PrincipalBindingManifest`映射代入`PrincipalSlotContract`后与`PrincipalBindingSnapshot`精确相等；
- `SecurityVerificationState`由受限探针新鲜验证、两类Digest均MATCH且尚未超过`validUntil`；
- `SecurityProbePolicy`最大间隔与实际`validUntil`计算均匹配本Release的`databaseSecurityManifestDigest`；
- 每个启用ProviderContract均证明在最大Fence窗口内具有可靠回调重试或权威主动查询能力；两者均无时已停止新外发并把未决动作收敛到终态或受控人工模式；
- 出现新版本专属事实后，旧构建回退被拒绝。

真实PostgreSQL协议测试还必须覆盖：

- 非法`WAITING→DONE`被拒绝；
- 一个Completion Event试图完成两个Task时唯一约束拒绝；
- Decision链任一步故障整体回滚；
- Inbox领域处理失败时保持未处理，重放只产生一组结果；
- DISPATCHING租约过期只能进入UNKNOWN；
- 同`(tenant_id, provider_account_ref, effect_key, attempt_no)`二次派发被拒绝；
- Temporal重复扫描只产生一个Milestone和一个Command结果；
- 非事务Flyway失败不能被自动repair；
- 存在非终态Task、ExternalAction、Inbox或Backfill时ContractGate拒绝；
- API/Worker实际Capability权限多权或缺权时保持NOT_READY；
- 未登记LOGIN、传递Membership或直接Grant，Capability/Owner Role Attribute漂移，以及高权/内建旁路身份未登记时激活失败；
- 安全探针失败、权限被撤或`SecurityVerificationState`过期时API/Worker自动保持NOT_READY。
- 即使绕过负载均衡直连NOT_READY实例，Rank 5门禁在任何Tenant/业务SQL前仍拒绝；Genesis的`FENCED_BOOTSTRAP/NOT_VERIFIED/UNINITIALIZED`不能被普通业务入口越过。
- `PERIODIC_ACTIVE`在FENCED时只产生`SKIPPED_FENCED`，`EPISODE_VERIFY`只能验证准确Episode/G/Candidate；`INVALID(G)`不能被普通Probe改回MATCH，旧G的MATCH不能用于新Episode Unfence。
- API/Worker凭`LOCK_ONLY_UPDATE`可以取得围栏`FOR SHARE`，但不能修改语义列、Generation或把`lock_capability`改为非零。
- 预热Query Cache后进入FENCE、改变Capability/Principal安全图并UNFENCE时，旧Deployment Security/Verification Generation缓存从未披露且新Generation不能命中旧条目。
- `ListMyAuthorizedTenantsQuery`在FENCED状态下必须先于任何Identity、Membership或Tenant候选SQL失败。
- FENCED后不能新建`DISPATCHING`或启动新Provider调用；Fence前已进入事务外调用的Attempt解除Fence后只能按权威结果或`UNKNOWN`协议收敛，不能盲重试。
- 未激活Candidate的`SECURITY_ABORT`只能逆转准确Expand/Apply Receipt引入的权限增量；Candidate已激活、存在专属事实/外部效果或恢复快照不等于previousReleaseBundle时必须拒绝。
- 非Release Episode的子步骤、旧Episode或错误Purpose试图解除外层Release Fence时CAS失败。

CapacityGate继续保持触发式，不成为每次发布都运行的第三个常驻Gate。

## 9. 跨章节机械不变量

### 9.1 上游规格追踪

| 上游冻结概念/不变量 | 本规格落实 | 后续物理Owner | 范围 |
|---|---|---|---|
| 六聚合仅是销售至转案上下文 | 1.3、4.1、10 | party/lead/opportunity/conflict/contract/transfer | MVP |
| 最小Matter身份与不可变Origin | 4.4、4.6、6.8 | matter_core、transfer | MVP |
| 后MVP不创建空表或Task | 4.1、4.6、10 | future capability owners | Deferred |
| 四类互斥命令信封与幂等Receipt | 2.2、6.2、6.6 | execution_runtime | MVP |
| Task单Owner、单命令、单完成事实 | 4.4、5.5、6.7 | responsibility＋事实Owner | MVP |
| WAITING与WaitReceipt严格分离 | 1.4、6.7 | responsibility | MVP |
| Temporal Snapshot与到期幂等 | 4.4、6.13 | temporal | MVP |
| 五个跨模块原子用例 | 6.4、6.5、6.8 | 各事实起点模块 | MVP |
| ExternalAction、双Outbox、Provider Inbox | 1.4、6.11、6.12 | external_action、execution_runtime | MVP |
| Evidence双层、Retention与Hold | 3.5、5.5、7.10、8.13 | evidence | MVP骨架 |
| Responsibility/Capability/Authority/DataScope四轴授权 | 2.4、5.3、6.3、7 | identity_access＋Owner Query | MVP |
| AI止步不可变Candidate | 5.7、5.8、9.2 | ai_gateway | MVP |
| 强一致操作读与异步发现投影 | 1.4、4.5、8.10、8.11 | workbench＋各Query Owner | MVP |
| PostgreSQL类型化搜索与权威重鉴权 | 2.4、7.8、8.10、8.11 | 各Lookup Owner | MVP |
| C1容量包络与触发式升级 | 8.11—8.14 | platform/release controls | MVP验证 |
| ChangeGate/ReleaseGate两级门禁 | 7.3、8.6—8.14 | release controls | MVP |
| 备份与灾难恢复 | 7.9、8.4、8.13、10 | 未冻结 | Deferred |

### 9.2 不变量落实矩阵

以下规则必须同时出现在模块Schema细册、Registry/Manifest和测试中：

| 不变量 | 数据库机制 | Owner代码机制 |
|---|---|---|
| Tenant不能串租 | 复合PK/UK/FK、Tenant Predicate | QueryAccessGuard、Command Context |
| 一个Task一个Owner | Owner FK、NOT NULL | Owner Resolver冻结具体用户 |
| 一个Task一个完成事实 | `UNIQUE(tenant_id, completion_event_id)`及Task完成绑定 | Completion Contract精确匹配 |
| DONE不重开 | 状态CHECK/CAS | 新Task＋predecessor |
| Command不重复执行 | CommandExecutionSlot唯一键 | 相同ID/Payload重入 |
| External效果不重复 | effectKey、attempt、UNKNOWN状态 | Provider查询和恢复命令 |
| Event/Audit/Receipt不可覆盖 | INSERT-only GRANT | Correction追加事实 |
| 只引用准确版本 | SubjectBinding判别联合 | Owner Port重验 |
| Decision授权不可伪造 | Actor/Slot唯一约束 | 当前Authority与SoD重验 |
| AI不能产生正式事实 | 无AI写权限 | Candidate人工采纳后正式Command |
| Evidence可用不等于业务有效 | Evidence精确Ref | 业务域独立Verification Fact |
| 时间到期不等于业务决定 | Milestone幂等槽 | Temporal只生成到期事实/责任 |
| 投影不能成为事实源 | 可重建标识、无反向FK | Query Facade回源重验 |
| 旧历史仍可解释 | Append-only、版本化Digest | 历史Decoder/Presenter保留 |
| 最小Matter身份不可变 | Tenant唯一MatterRef、Origin/Link唯一约束 | Matter Core签发、AcceptTransfer原子用例 |

## 10. 明确Deferred与下一层边界

本规格冻结后，以下内容仍然不属于当前设计：

1. 各模块逐表DDL、列、索引和SQL。
2. 全量ER图。
3. CommandRuntime、Responsibility、Identity、Evidence等模块的Repository实现。
4. 备份、PITR、恢复纪元和灾后外部现实对账。
5. 物理破坏性Contract和自动数据销毁。
6. 默认分区、外置搜索、Kafka、读副本和微服务拆分。
7. 案管登记分配、综法、非诉和案件能力包的物理表。
8. 实施排期、任务拆分和工作量估算。

下一层设计应优先展开：

1. 最小共享数据库类型与`platform_meta`细册。
2. `identity_access`最小Tenant Root、Actor Binding、Authorization Fence及QueryAccessGuard物理模型。
3. `execution_runtime`运行契约和物理模型。
4. `temporal`领域契约和物理模型。
5. `responsibility`领域契约和物理模型。
6. Audit、Configuration、Evidence和External Action基础表。
7. 销售六聚合与最小Matter的Owner Schema细册。

这些细册必须逐个冻结，不得一次性生成全库DDL，也不得提前进入实施计划。

## 11. 冻结声明

本规格固定以下边界：

- PostgreSQL保存类型化领域事实，不保存运行时本体图或通用流程定义。
- 本体语义由代码Registry拥有，事实实例由唯一Owner Schema拥有。
- 数据库负责可机械强制的Tenant、身份、唯一性、版本、状态和不可变约束。
- 领域Owner负责权限、语义完备性、派生、版本适用性和命令合法性。
- 单PostgreSQL和模块化单体是MVP有意选择，不是未来永久的部署上限。
- 任何升级都必须先证明当前结构在C1范围内无法满足目标，而不是因预期复杂性提前引入基础设施。

除非形成新版本规格和明确迁移契约，后续模块细册、DDL、代码和实施计划不得改变以上规则。

## 2026-08-27 P0一致性补充

本节取代早期“可变WaitReceipt”与“Task可直接初始WAITING”的表述：WaitReceipt只追加不可变事实；TaskOccurrence只以OPEN创建；WaitingProjection是查询投影且不新增表。PaymentGate需准确保存due_at、业务时区及付款条款来源。TransferSnapshot与PRE_TRANSFER Review为一对一实例关系，补正重提创建新Snapshot和新Review。冲突BLOCK需以条件唯一性和事务测试保证同审查其他OPEN槽被取消。已执行Flyway迁移不得改写，物理变更必须使用新的前向迁移。
