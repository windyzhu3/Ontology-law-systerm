# Ontology Law System｜律所待办驱动智能管理系统

> 以准确业务事实驱动责任：**一张卡、一个 Owner、一个主命令、一个明确结果**。当一张卡无法维持这一约束时，拆分责任或后置能力，不增加通用流程结构。

本仓库保存 Ontology Law System 的产品、领域、架构及 PostgreSQL 契约。当前最新基线已经从原则设计推进到可机械验证的 **52 张应用事实表＋2 张 `platform_meta` 技术表**字段合同和 Flyway DDL；尚未开始生产应用代码建设。

> 2026-08-27 设计一致性说明：销售MVP从线索接入到案管接收的当前闭环终点是 `TransferAccepted + MatterRef`；`Matter` 仍属于Post-MVP。工作卡视觉基准及P0覆盖证据位于[销售MVP工作卡高保真](docs/design/sales-mvp-workcards/README.md)。身份、授权与审计管理的三批冻结视觉基线位于[身份与组织管理MVP高保真](docs/design/identity-admin-mvp/README.md)。如历史设计段落存在冲突，以各规格末尾的“2026-08-27 P0一致性补充”和52＋2字段合同为准。

## 1. 当前正式冻结基线

最新权威交付物：

- [52＋2 Schema 合同说明](database/schema-contract-52-plus-2/README.md)
- [完整字段合同](database/schema-contract-52-plus-2/generated/field-contract.md)
- [机器可读合同清单](database/schema-contract-52-plus-2/generated/schema-contract-manifest.json)
- [19 个 Flyway 迁移](database/schema-contract-52-plus-2/generated/db/migration/)
- [运行时提交前重验合同](database/schema-contract-52-plus-2/docs/runtime-validation-contract.md)
- [验证记录](database/schema-contract-52-plus-2/VERIFICATION.md)
- [身份与组织管理MVP高保真基线](docs/design/identity-admin-mvp/README.md)

如历史设计稿与上述合同发生冲突，以 **2026-08-26 的52＋2冻结合同**为准。历史设计稿继续保留，用于说明设计演进，不再作为DDL生成来源。

## 2. 产品与交互原则

Ontology Law System 不是传统律所菜单系统、通用Todo或BPM平台。它采用以下最小闭环：

```text
准确领域事实变化
→ 静态规则确定唯一责任
→ 为一个Owner创建一张TaskOccurrence
→ SPA展示当前工作卡
→ 用户确认固定主命令
→ CommandRuntime实时鉴权、重验版本并提交唯一完成事实
→ 原Task完成；如仍需行动则新建下一张Task
```

- **领域事实是业务真源**：Task、Audit、Event和Evidence都不能越权替代领域结论。
- **Task是责任卡**：只冻结一个准确Subject及版本、一个Owner Appointment、一个业务目的、一个固定主命令和一个完成事实。
- **AI是受控薄包**：只能理解、提取、解释和形成候选输入；不能审批、核验、签署、确认到账或直接完成Task。
- **历史只追加、不复用**：转派、退回、补正、重试和恢复后行动均创建新Task或新业务版本，旧事实不重开、不覆盖。

## 3. 销售至原子转案闭环

MVP完整覆盖：

```text
Lead接入 → Assignment分配 → ContactResult联系结果
→ Opportunity与版本化Quote → PRE_CONTRACT冲突审查
→ ContractRevision版本包 → 审批、签署、执行与首款事实
→ DealActivated → TransferSnapshot提交/补正
→ PRE_TRANSFER冲突审查 → 案管ACCEPT或RETURN
→ ACCEPT同事务写入MatterRef并产生MatterCreated
```

`MatterRef`只是转案接收时一次写入的稳定引用；MVP不建设Matter表、案件分配、办理流程或结案能力。`FINALIZED`、Evidence Binding、外部`SUCCEEDED`、Quote接受、ContractExecution或PaymentConfirmation均只表达各自Owner的事实，不得跨域推导成合同有效、付款门槛满足、Task完成或Matter创建。

## 4. 52＋2表总账

| Schema | 表数 | Fact Owner与边界 |
|---|---:|---|
| `identity` | 7 | Tenant、Principal、组织邻接树、多任职与单路径授权 |
| `audit` | 1 | 类型化、不可变`AuditEntry` |
| `responsibility` | 4 | `TaskOccurrence`、`DecisionRecord`、`WaitReceipt`、`ActionDraft` |
| `execution` | 4 | 无状态命令占位、不可变Receipt、准确Fact Event与Owner Outbox |
| `external_action` | 3 | 一次外部效果意图、排程及可信Provider消息 |
| `evidence` | 4 | 一文件一次接收及严格一对一不可变晋级 |
| `party` | 1 | 当前态主体锚点 |
| `lead` | 3 | 一次接入、追加分配与联系结果 |
| `opportunity` | 9 | 一项法律需求、版本化报价包、逐收件人Issue与Response |
| `conflict` | 3 | 准确审查范围、参与方和逐Finding结论 |
| `contract` | 10 | 合同版本包、审批引用、签署、执行、到账、激活与终止 |
| `transfer` | 3 | 完整Snapshot、逐项退回要求与原子接收槽 |
| **应用事实合计** | **52** | 不得通过通用表扩张边界 |

技术表只有：

1. `platform_meta.flyway_schema_history`：由Flyway创建并独占管理；
2. `platform_meta.deployment_state`：唯一自建技术表，默认`BLOCKED`，经发布门禁CAS激活。

## 5. 技术架构基线

| 维度 | 正式基线 |
|---|---|
| 前端/API | 一个SPA＋一份OpenAPI |
| 后端 | 一个Spring Boot模块化单体制品；`api`与`worker`两种互斥启动角色 |
| 数据库 | 专用PostgreSQL 15＋；单库、13个受管Schema、未加引号`snake_case`物理名称 |
| 持久化 | jOOQ唯一业务持久化范式；Flyway唯一DDL真源 |
| 租户 | 除`identity.tenant`外，所有租户表使用`tenant_id`参与的复合主键和复合外键 |
| 命令 | 四类静态命令信封；CommandRuntime开启唯一READ COMMITTED短事务 |
| 权限 | 四轴实时鉴权；限制优先、同一Appointment路径、提交前复验 |
| 异步 | PostgreSQL Owner定向Outbox；at-least-once；MVP不引入Kafka、Saga或通用Job |
| 查询 | 零新增表的同步Query Facade；敏感读取先审计后披露 |
| AI | 零新增表的受控AI薄包；候选输入归`ActionDraft` |

所有稳定物理外键必须携带`tenant_id`且禁止级联删除。多态SubjectRef、FactRef、AuditRef和Event来源使用类型化准确引用，由静态允许列表、同租户Resolver及提交前重验保证。应用事实默认不可变，只允许冻结合同中列明的当前态锚点、单向槽位、Task等待态和Outbox租约字段受控CAS更新。

## 6. 仓库结构

```text
.
├── README.md
├── docs/
│   ├── design/                        # 已确认高保真视觉证据索引
│   └── specs/                         # 产品、架构及物理模型历史规格
├── database/
│   └── schema-contract-52-plus-2/
│       ├── contract/                  # 唯一人工维护的静态字段与约束合同
│       ├── generated/
│       │   ├── field-contract.md      # 完整字段合同
│       │   ├── schema-contract-manifest.json
│       │   └── db/migration/          # 19个Flyway迁移
│       ├── docs/                      # 设计与运行时验证合同
│       ├── tests/                     # 合同、语义和生成SQL测试
│       ├── generate.py                # 确定性生成入口
│       └── VERIFICATION.md
└── .github/workflows/
    └── schema-contract-52-plus-2.yml  # 生成一致性与静态SQL门禁
```

## 7. 生成与验证

```bash
cd database/schema-contract-52-plus-2
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements-dev.txt

python3 generate.py --check
python3 -m unittest discover -s tests -v
python3 scripts/verify_generated_sql.py
```

当前冻结版本验证口径：43项合同测试、19个迁移、23个PL/pgSQL函数、206个复合外键、22类类型化准确引用和25项跨行守卫合同。生成物摘要记录在[验证报告](database/schema-contract-52-plus-2/VERIFICATION.md)中。

当前构建环境未提供真实PostgreSQL/Flyway运行时，因此上线前必须在专用PostgreSQL 15＋数据库以固定Flyway版本执行`validate`和`migrate`。只有`V840__schema_contract_validation.sql`通过，并且应用制品摘要与manifest完全匹配，发布作业才可将`deployment_state`从`BLOCKED`切换为`ACTIVE`。

## 8. 历史规格阅读顺序

以下文档保留为设计演进背景；其中表名、数量或能力边界如与52＋2合同不同，均视为已被最新合同替代：

1. [目标产品基线 v2.0](docs/specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md)
2. [总体架构与本体完整设计 v1.0](docs/specs/2026-08-18-law-firm-overall-architecture-ontology-design.md)
3. [销售MVP工作卡与对话状态设计 v1.0](docs/specs/2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md)
4. [最小Matter身份与后MVP扩展契约 v1.0](docs/specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md)
5. [基础框架设计规格 v1.0](docs/specs/2026-08-18-ontology-law-system-foundation-architecture-v1.0.md)
6. [项目结构、模块边界与构建契约 v1.0](docs/specs/2026-08-18-ontology-law-system-project-module-build-contract-v1.0.md)
7. [PostgreSQL物理模型总纲 v1.0](docs/specs/2026-08-19-ontology-law-system-postgresql-physical-model-guideline-v1.0.md)

最后更新：2026-08-27。
