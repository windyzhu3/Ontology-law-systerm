# 待办驱动律所系统：52＋2 Schema 合同

本仓库把已经冻结的律所销售、审查、合同与转案主链，落实为一份静态 Python 字段合同，并机械生成 PostgreSQL/Flyway DDL、字段说明和机器可读清单。交付边界固定为 **52 张应用事实表＋2 张 `platform_meta` 技术表**；任何第 55 张受管表都属于合同漂移。

## 冻结边界

| Schema | 应用事实表数 | Fact Owner / 主要职责 |
|---|---:|---|
| `identity` | 7 | IdentityRuntime：租户、Principal、组织、Appointment 与授权锚点 |
| `audit` | 1 | AuditAppender：不可变 `AuditEntry` |
| `responsibility` | 4 | ResponsibilityRuntime：Task、Decision、WaitReceipt、ActionDraft |
| `execution` | 4 | CommandRuntime / OutboxDispatcher：命令原子结果与事实通知 |
| `external_action` | 3 | ExternalActionRuntime / ExternalActionDispatcher / ProviderIngress：一次外部效果、排程及可证明结果 |
| `evidence` | 4 | EvidenceIngress / EvidenceRuntime：一文件一次接收与不可变晋级 |
| `party` | 1 | PartyRuntime：当前态主体锚点 |
| `lead` | 3 | LeadRuntime：销售接入、分配和联系事实 |
| `opportunity` | 9 | OpportunityRuntime：法律需求、版本化报价与发出/回应事实 |
| `conflict` | 3 | ConflictReviewRuntime：准确审查范围、参与方和 Finding |
| `contract` | 10 | ContractRuntime：不可变合同版本包、签署、到账与激活事实 |
| `transfer` | 3 | TransferRuntime：完整 Snapshot、退回项和原子接收槽 |
| 合计 | **52** | 应用事实表总账，不得增表 |

两个技术表是：

- `platform_meta.flyway_schema_history`：由 Flyway 在迁移前创建并独占维护；本项目只补中文注释，不创建它，也不向应用授予写权限。
- `platform_meta.deployment_state`：本项目唯一自建技术表；由部署控制面以 CAS 维护，应用角色只读。

业务标识由应用生成 UUIDv7。除 `identity.tenant` 外，租户表均采用以 `tenant_id` 开头的复合主键；物理外键必须携带 `tenant_id`，并统一使用 `ON UPDATE NO ACTION ON DELETE NO ACTION`。多态 Subject/Fact/Audit/Event 引用不伪造物理外键，而由静态允许列表、同租户 Resolver 和提交前准确版本复验共同保证。

安装目标必须是专用数据库：除上述 13 个受管 Schema 与空的 `public` 外，不得存在其他用户 Schema，`public` 不得已有用户表。`V830` 和 `V840` 都会失败式检查该前提；因此四个应用能力角色不可能借未受管 Schema 的 `CREATE` 权限绕过 52＋2 总账。扩展或其他系统对象应部署到独立数据库，或先由新 ADR 明确调整边界。

## 目录

- `contract/`：唯一人工维护的静态字段、约束、索引、Owner 和引用合同。
- `generate.py`：确定性生成入口；`--check` 只比较，不改写交付物。
- `generated/db/migration/`：按依赖顺序生成的 Flyway 迁移，禁止手工修改。
- `generated/field-contract.md`：完整字段合同、跨域外键、类型化引用和更新白名单。
- `generated/schema-contract-manifest.json`：CI/部署使用的机器可读合同及 `contractSha256`。
- `docs/runtime-validation-contract.md`：DDL 无法证明、必须由 Owner/CommandRuntime 实时复验的边界。
- `tests/`：表总账、语义、注释、迁移顺序和确定性验证。

## 本地生成与验证

要求 Python 3.11+。SQL 最低安装目标固定为 PostgreSQL 15；上线前应在实际采用的 PostgreSQL 小版本执行 Flyway 集成验证。Flyway CLI 必须由构建镜像摘要或工具链锁定到一个准确版本，并把该版本写入发布清单；开发、CI 和生产不得漂移。

```bash
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements-dev.txt

python3 generate.py
python3 generate.py --check
python3 -m unittest discover -s tests -v
python3 scripts/verify_generated_sql.py
```

`python3 generate.py` 只应在修改 `contract/` 后执行。评审应同时检查合同源和全部生成差异；已执行的迁移不得被重写，后续兼容变更必须新增向前迁移，并先通过新的架构决策解除当前冻结边界。

## Flyway 迁移顺序

迁移位置固定为 `generated/db/migration`，顺序固定如下：

1. `V001__bootstrap_schemas.sql`
2. `V002__deployment_state.sql`
3. `V010__identity_tables.sql`
4. `V020__audit_tables.sql`
5. `V030__responsibility_tables.sql`
6. `V040__execution_tables.sql`
7. `V050__external_action_tables.sql`
8. `V060__evidence_tables.sql`
9. `V070__party_tables.sql`
10. `V080__lead_tables.sql`
11. `V090__opportunity_tables.sql`
12. `V100__conflict_tables.sql`
13. `V110__contract_tables.sql`
14. `V120__transfer_tables.sql`
15. `V800__cross_domain_foreign_keys.sql`
16. `V810__update_guards.sql`
17. `V820__indexes.sql`
18. `V830__application_privileges.sql`
19. `V840__schema_contract_validation.sql`

最后一个迁移会在安装事务内拒绝错误表数、缺失中文注释、非白名单物理外键、越权列更新、应用角色Owner/DDL/Delete/Truncate能力和缺失 mutation guard。它验证的是物理结构，不替代运行时授权或业务有效性验证。

### 角色占位符

IaC 必须在迁移前预创建以下数据库角色。占位符会作为未加引号的 SQL 标识符使用，因此值只能匹配 `[a-z][a-z0-9_]{0,62}`，不能来自请求或租户配置，且不能是表/Schema Owner。

Flyway迁移角色必须是当前数据库及全部受管Schema/对象的固定Owner，或具有执行V830权限清零所需的等价管理权限；它不得被任何应用角色继承。V830会撤销应用角色及`PUBLIC`的数据库建Schema/临时表能力和受管Schema对象能力，再按下表精确授权；V840对有效继承权限做失败式复验。

| Flyway 占位符 | 建议角色名 | 唯一用途 |
|---|---|---|
| `app_command_role` | `law_app_command` | CommandRuntime 对领域事实执行允许列表化 `SELECT`/`INSERT` 和受控列 `UPDATE` |
| `app_worker_role` | `law_app_worker` | Dispatcher 对两个 Outbox 执行租约、围栏和投递状态 CAS |
| `app_query_role` | `law_app_query` | 仅由同步 Query Facade 使用的只读能力 |
| `audit_append_role` | `law_audit_append` | 固定 AuditAppender 对 `audit.audit_entry` 的 `INSERT` 能力 |

这四个数据库能力角色必须是 `NOLOGIN`，不增加后端启动形态。部署仍只有 `api` 与 `worker` 两种启动角色：IaC 必须把它们建立为 `LOGIN NOINHERIT`，并且只向两个登录角色直接授予目标数据库 `CONNECT`，不得直授 `CREATE`、`TEMPORARY`、Schema USAGE或任何对象权限；否则在连接前无法执行`SET ROLE`。SPA只调用同一份OpenAPI；API登录角色只能在受控事务中以`SET LOCAL ROLE`选择一个准确Command、Query或Audit能力，不能把能力并集长期继承；同事务业务写与Audit追加可按语句切换准确角色。Worker登录角色只可选择Worker能力，内部命令通过固定API/CommandRuntime入口衔接。浏览器、用户和Provider都不能取得数据库角色。

复制示例配置后，以环境变量或 Secret Manager 提供连接密码：

```bash
cp flyway.conf.example flyway.conf
export FLYWAY_PASSWORD='由部署环境注入'
flyway -configFiles=flyway.conf validate
flyway -configFiles=flyway.conf migrate
```

不要把真实口令写入配置、迁移、日志或仓库。`flyway clean` 必须保持禁用；不得对已迁移数据库使用 `baselineOnMigrate` 绕过合同。

## 部署门禁

`V002` 将 `platform_meta.deployment_state` 初始化为 `BLOCKED`，摘要为 32 字节零值。完成迁移、应用制品与静态注册表校验后，受控发布作业以同一个受 IaC 保护的迁移 Owner 凭据充当 DeploymentRuntime，才能在一个短事务中：

1. 锁定唯一 `PRIMARY` 行并校验期望 `revision`；
2. 写入准确应用发布摘要和部署清单摘要；
3. 确认 `schema_contract_version = '52-plus-2-v1'`；
4. 将 `revision` 精确加一并写入可信 `changed_at`；
5. 最后把运行模式切换为 `ACTIVE`。

应用启动时必须比对发布摘要、清单摘要和 Schema 合同版本；不匹配时拒绝提供业务写入。该迁移 Owner 不是第五个应用能力角色，不进入 API/Worker 进程，也没有 Flyway 占位符；`V840` 只验证四个应用能力角色，迁移 Owner 的凭据隔离与发布作业边界由 IaC 验证。应用角色只有 `SELECT`，不能自行激活部署。

## 运行时边界

数据库只证明字段形态、同租户稳定关系、唯一性、允许状态转换、CAS 形状和部分延迟跨行关系。以下结论必须由准确 Fact Owner 在同一短事务的提交前重验：当前四轴授权、同一 Appointment 路径、当前组织树 Scope、类型化引用的准确 revision/hash、Evidence 对象版本与安全门禁、Provider 验签和 `UNKNOWN` 收敛、冲突规则/语料水位、合同批准/签名/首款条件以及转案当前叶 Snapshot。

`FINALIZED`、`PASSED`、Submission、Binding、外部 `SUCCEEDED`、Quote `ACCEPTED`、ContractExecution 或 PaymentConfirmation 都只表示各自 Owner 的准确事实，不得跨域推导为合同有效、付款到账、Task 完成或 Matter 创建。完整规范见 [运行时重验合同](docs/runtime-validation-contract.md)。

## 查询与数据保护

MVP 不新增查询表、物化视图、全局 Evidence 搜索、文档库或通用流程结构。同步 Query Facade 直接从 52 张事实表构造响应；审计读取只能经过零新增表的 `audit.audit_entry_classified_v` 分类视图。每次查询和披露都必须携带 `tenant_id`、执行实时四轴重鉴权、限制字段/分页，并对敏感读取和审计导出先提交 `AuditEntry` 再返回结果。受保护值仅在授权应用路径中解密；日志、错误码、审计摘要和 Outbox 不得复制密码、Token、Secret、文档正文或非必要案情。
