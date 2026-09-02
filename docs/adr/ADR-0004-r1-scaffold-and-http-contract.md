# ADR-0004：R1 单制品工程与 HTTP 合同

Status: Accepted

Contract ID: R1-SCAFFOLD-V1

日期：2026-09-02

## Context

当前基线已经冻结“一份 OpenAPI、一个响应式 SPA、一个 Spring Boot 模块化单体 Jar，以及 `api` / `worker` 两种互斥启动角色”。历史规格中的三 SPA、四 OpenAPI、`APP_ROLE`、Control CLI、多 Maven 模块、按模块复制迁移和提前建立 Matter/AI/Admin 空壳均为历史证据，不能继续决定代码结构。

本 ADR 只冻结 R1 脚手架和 HTTP 实现必须消费的工程值。它不创建应用代码，不证明 V850、`52-plus-2-v1.1`、R1 OpenAPI、后端、SPA 或 E2E 已实现。

HTTP 的操作、错误与前置条件由 [R1 HTTP 矩阵](../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)约束；任务完成链由 [R1 Task 矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)约束；首屏由 [R1 Workbench 合同](../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)约束。

## Controlled decisions

| Decision | FrozenCode | FrozenValue |
|---|---|---|
| backendProject | SINGLE_MAVEN_BACKEND | 单 Maven 工程 `backend/pom.xml`；不得创建第二个后端项目或聚合多模块 |
| jar | SINGLE_DEPLOYABLE_JAR | 唯一可部署 Jar 的 groupId 为 `io.github.windyzhu3`，artifactId 为 `ontology-law-system` |
| rootPackage | IO_GITHUB_WINDYZHU3_ONTOLOGYLAW | 唯一 Java 根包为 `io.github.windyzhu3.ontologylaw`；R1 只含 bootstrap、api、worker、audit、execution、identity、lead、opportunity、query、responsibility |
| runtimeRole | OLS_API_OR_WORKER | 唯一键 `ols.runtime-role` 只接受单值 `api` 或 `worker`；缺失、未知、重复来源冲突或双 Context 均启动失败 |
| npmWorkspace | ROOT_SINGLE_WORKBENCH | 根 `package.json` 只声明一个 workspace `apps/workbench`；提交 npm lockfile；禁止第二个可部署前端 package |
| workbench | SINGLE_SPA_ROUTE_MODES | 唯一 SPA 位于 `apps/workbench`；`/workbench` 与 `/admin/identity/*` 是同一制品的不同受保护 route mode |
| openapi | SINGLE_OPENAPI_DUAL_CODEGEN | 唯一源为 `contracts/openapi/ontology-law-api.yaml`；后端生成到 `backend/target/generated-sources/openapi` 且不提交；前端提交 `apps/workbench/src/generated/api/schema.d.ts`；两端 `--check` 重生成必须零差异 |
| database | PG18_13_SCHEMAS | 一个 PostgreSQL 18 数据库和 13 个受管 Schema；应用运行角色不是 migration owner；API 和 Worker 不持有 migration owner 凭据 |
| migrations | SINGLE_JAR_FLYWAY_SOURCE | 唯一迁移源为 `database/schema-contract-52-plus-2/generated/db/migration`；构建只读映射到同一 Jar 的 `db/migration`；Jar 内字节清单必须与源 SHA-256 清单相同；禁止第二套手写迁移 |
| jooq | V1_1_RECORDS_POJOS_ONLY | 仅从已通过门禁的真实 `52-plus-2-v1.1` PostgreSQL 空库生成；提交 records、POJOs 与 `MANIFEST.sha256`；不生成 DAO、Active Record 或第二持久化模型 |
| checks | BASELINE_SCHEMA_RUNTIME_SCAFFOLD | 既有稳定名为 `verify-baseline`、`verify`、`runtime-postgresql-18`；后续脚手架新增唯一 always-run 聚合名 `scaffold-gate`；本 ADR 不伪称该 workflow 已存在 |
| toolchain | EXACT_PINS_2026_09_02 | Temurin `25.0.4.1+1`；Maven Wrapper `3.3.4`、Maven `3.9.16`、distribution SHA-256 `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`；CPython `3.12.14`；Node `24.20.0`、npm `11.9.0`；PostgreSQL `18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`；Flyway `13.4.0@sha256:c093a247b19ff09a6a72774569171ee355fff2ae44ceba4a4aa4b23235d99c93` |

## Dependency and plugin pins

- Spring Boot BOM / plugin `4.1.1`；Spring Modulith BOM `2.1.1`。
- jOOQ runtime/codegen `3.21.7`；Flyway core/PostgreSQL/plugin `13.4.0`；Testcontainers BOM `2.0.0`。
- OpenAPI Generator Maven plugin `7.25.0`；`openapi-typescript` `7.13.0`；`openapi-fetch` `0.17.0`。
- React `19.2.8`、Vite `8.2.2`、TypeScript `7.0.2`、Vitest `4.1.11`、Playwright `1.62.1`。
- Maven Compiler `3.14.1`、Surefire `3.5.4`、Failsafe `3.5.4`、Enforcer `3.6.2`。所有 Maven 与 npm 依赖必须是精确版本；禁止 SNAPSHOT、版本范围和动态解析。
- Maven distribution 的官方 SHA-512 同时记录为 `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`，脚手架生成时必须同时复核 SHA-256 和官方 SHA-512。

## GitHub Actions pins

后续新增或修改 workflow 时必须直接使用以下完整提交，不使用可移动 major tag：

| Action | Commit SHA |
|---|---|
| `actions/checkout` | `11d5960a326750d5838078e36cf38b85af677262` |
| `actions/setup-python` | `a26af69be951a213d495a4c3e4e4022e16d87065` |
| `actions/upload-artifact` | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/setup-java` | `b6effb05e454b25005698d916606bdc6ffcbf961` |
| `actions/setup-node` | `49933ea5288caeca8642d1e84afbd3f7d6820020` |

这些 SHA 是 2026-09-02 对官方仓库相应 major ref 的解析结果；变更必须通过新 ADR 或本 ADR 的显式修订，而不是悄然移动。

## Package dependency DAG

允许的生产依赖方向固定如下，未列出的跨包依赖均由 ArchUnit 和 Spring Modulith 测试拒绝：

| Consumer | May depend on public API of |
|---|---|
| `identity` | 无 R1 业务包 |
| `audit` | `identity` |
| `opportunity` | `identity`、`audit` |
| `execution` | `identity`、`audit` |
| `responsibility` | `identity`、`audit`、`execution` |
| `lead` | `identity`、`audit`、`execution`、`responsibility`、`opportunity` |
| `query` | `identity`、`responsibility`、`lead`、`opportunity` |
| `api` | 上述包的具名应用端口和 `query`；不得引用其他包的 `internal` |
| `worker` | mTLS internal client、准确 Owner queue port；不得引用 Controller、浏览器 DTO 或 Command Repository |
| `bootstrap` | 只负责选择并组装一个 runtime role Context |

领域包保持纯 Java，不依赖 Spring、Jackson、HTTP 或 jOOQ。jOOQ 类型只能存在于各 Fact Owner 的 `internal.persistence`，API 不得暴露 Repository、jOOQ Record 或内部 Command 模型。任何包不得访问另一个包的 `internal`。

## Runtime role and Bean isolation

- 配置源优先级固定为命令行、环境映射 `OLS_RUNTIME_ROLE`、配置文件；若多个来源解析出不同值，启动失败。
- `api` Context 可加载 Controller、认证后的 ActorContext、同步 Query、CommandRuntime、准确 Fact Owner Repository 与 AuditAppender；它不加载定时器或 Owner queue poller。
- `worker` Context 不加载浏览器 Controller、同步 Query、用户 Command handler 或 API 数据库能力。R1 的到期恢复通过 mTLS internal endpoint 请求 API 执行；准确 Owner queue poller只能使用对应 worker 能力角色。
- 两个 Context 的 Bean 白名单由启动测试分别快照；双集合交集只允许 bootstrap、配置、可观测和纯 DTO 基础 Bean。

## Flyway and jOOQ

- API 与 Worker 均关闭自动迁移。受控发布作业使用固定 migration owner 执行 `migrate`、strict `validate`、`info`，随后才启动应用角色。
- Maven 资源阶段只读复制已生成迁移；构建校验源目录、Jar 资源和 manifest 三方字节摘要。不得从 Jar 反向生成合同或修改 V001 至 V840。
- jOOQ codegen 输入必须是应用 V001 至 V850 后的全新 PostgreSQL 18 空库；只生成R1实际访问的表，并把record/POJO放到对应Fact Owner的`internal.persistence.jooq`，禁止`shared`生成根。生成 locale 固定 `C.UTF-8`、timezone 固定 `UTC`、编码固定 UTF-8。
- `generate-jooq.sh --check` 在临时目录重建并逐字节比较 records、POJOs 与 manifest。DAO、relations convenience mutation、Active Record 和运行时 schema 探测全部关闭。

## Test layers

| Layer | Runner and exact inclusion |
|---|---|
| Java unit/architecture/context/OpenAPI | Surefire；`**/*Test.java`，排除 `**/*IT.java` |
| PostgreSQL integration | Failsafe；`**/*IT.java`，只在 `-Pit`，真实 digest-pinned PostgreSQL 18 |
| Module boundary | ArchUnit + Spring Modulith；检查 DAG、`internal` 和 api/worker Bean 隔离 |
| Database contract | 既有 Python schema、SQL parser、runtime unit 与 PostgreSQL 18 两空库门禁 |
| Workbench | typecheck、Vitest、production build；只消费生成 API 类型 |
| Browser | Playwright；R1 golden/failure/tenant/replay 分支，留到对应业务任务 |

`scaffold-gate` 必须使用 job 级条件汇总所有子作业，并在仅改文档或无相关代码时仍产生不可跳过的成功/失败结论。workflow 级 path filter 不得使 required check 消失。

## HTTP invariants

- 公共 API 不定义也不接受 `X-Tenant-Id` 作为合同输入；Tenant 只由认证后的服务端 ActorContext 派生并贯穿授权与 SQL 绑定。
- 公共 API 不定义额外Command header。调用方提供UUID `Idempotency-Key`作为唯一UUID生成例外；服务端原样保存到现有`execution.command_execution_slot.command_id`并作为`commandId`返回，`receiptId`和领域ID由服务端生成UUIDv7。
- CommandRuntime在既有业务锁之后，以Tenant＋Command UUID取得事务级advisory lock，保证同Tenant内Command ID不跨Scope复用。同完整slot key与同一规范化payload重放原Receipt且数据库delta为零；异Scope或异payload返回`COMMAND_PAYLOAD_CONFLICT`和原Receipt引用，所有新增delta为零。一个slot至多一张不可变Receipt；已经提交的Receipt不能被第二张REJECTED Receipt追加或改写，修复stale、draft或主管配置后使用新key。
- `POST /api/v1/leads` 没有不存在资源的 `If-Match`；Task、Draft 与 subject 分别绑定自己的 ETag/revision，不得混用。

## Superseded engineering clauses

本 ADR 精确替代以下历史工程含义：三 SPA、四 OpenAPI、多后端 Maven 模块、`APP_ROLE`/apihost/workerhost/controlcli、通用 Control Command、按模块维护第二套 Flyway 目录、jOOQ DAO/Active Record、预建 Matter/Provider/AI/Admin 空包。历史文件仍保留设计演进证据，但不能驱动 Task 1 脚手架。

## Exit gate

只有本 ADR、三份 R1 合同、当前基线、R1 plan 和 baseline verifier 一致且合并后，Task 0 才能实施 ADR-0002/V850/v1.1。生产脚手架仍必须等待 v1.1 运行时证据，不能以文档冻结替代。
