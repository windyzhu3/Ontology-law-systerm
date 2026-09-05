# R1 授权与事件合同收口验收记录

验证日期：2026-09-05。版本：MVP-2026-09-05.2。分支：`codex/r1-contract-closure`。

本记录证明合同收口及可复用运行时机制的本地验证；合并、完整GitHub checkout CI和真实业务验收分别记账，不将测试Handler视为已交付业务入口。

## 分层状态

| 层次 | 本轮观察到的交付 | 尚未完成的门禁 |
|---|---|---|
| 合同 | [ADR-0007](../adr/ADR-0007-r1-command-policy-event-closure.md)、[静态策略/事件/分支合同](../contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md)、共享空payload Schema及机器验证器 | 合同冻结不代表生产业务可用；分支最终审查、完整checkout CI和合并由控制者单独记录 |
| 可复用后端 | 四类专属授权；14个准确事件描述、19个成功分支集合；真实Owner读接口；CommandRuntime在同连接、同事务验证持久来源、Task前后状态和准确selector | 原计划Task 5–8的业务Owner Handler、HTTP adapter、真实Bearer/mTLS映射、受控身份配置及Worker仍未交付 |
| 前端 | 既有单SPA壳、生成API类型和客户端；OpenAPI检查、类型检查、7个客户端测试及构建通过 | CurrentCard、Draft、恢复交互和可用工作台仍属Task 9；本轮没有新增业务页面 |
| 实库集成 | PostgreSQL上的真实Identity/Grant/Owner facts；capture、draft、两种恢复成功事件；ContactResult/Opportunity双来源原子事务和失败回滚 | 测试业务写入器仅在test源码；没有浏览器业务E2E，不覆盖完整后继责任选择/联系人加密/法律需求确认等原业务Handler验收 |
| R1整体 | A/B/C0/C/D基础设施与本轮合同/机制证据可定位 | `R1-BACKEND`、`R1-SPA`、`R1-E2E-GOLDEN`、`R1-E2E-FAILURES`仍未满足；不创建或推进对应完成行 |
| ADM-01～07 | 历史视觉设计和身份数据原语 | 管理CRUD、可操作页面和完整配置运行验收未交付；按批准范围不作为本轮R1合同收口前置门 |
| R2/R3 | `OpportunityOpened`生产及延迟消费者交接义务已明确 | 无R2 QueueOwner、Outbox、Task或消费代码；R1整体未验收前不启动R2/R3实现 |

## 事务和来源证据

[R1ContractClosureIT](../../backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/R1ContractClosureIT.java)通过真实Owner数据库读取器及生产`R1EventPolicy`执行。成功CONNECTED_VALID同事务写ContactResult、Opportunity@0、确认Draft、原Task DONE、一个Receipt/Audit和两组Event/Outbox。独立固定fixture证明：

- Receipt与`LeadContactResultRecordedV1`指向`01900000-0000-7000-8000-000000000109@bDHXwGMMysZpts2GoiO2JcmY_ocM3LMmMdr-lFfPHDk`。
- `OpportunityOpened`指向不同来源`01900000-0000-7000-8000-000000000110@revision 0`；数据库Owner事实核对同Lead、Assignment、ContactResult及Owner路径。
- Slot、Receipt、Audit、Event、Outbox的增量为`[1,1,1,2,2]`；同key重放仍为该计数；R2 Task增量为0。
- 缺失/额外/重复事件、错误Opportunity/ContactResult/hash/Owner、缺失Opportunity、错误Opportunity revision、未完成Task或未确认Draft均以技术错误完整回滚，原Task/Draft状态与所有命令记录恢复为事务前状态。
- 已在前一事务确认的Draft不能冒充本次确认；本次必须由同一Draft的DRAFT状态递增一个revision转为CONFIRMED。
- 已在前一事务提交的ContactResult＋Opportunity不能冒充本次创建。运行时在既有根锁内、Handler写入前，通过Lead Owner查询准确Tenant＋Task是否已有ContactResult；CONNECTED_VALID要求此前不存在。实库回归在当前命令省略两次insert、仅完成Task和确认Draft时要求SQLState `22000`、命令差额`[0,0,0,0,0]`、既有两种事实逐列不变以及Task/Draft全部当前更改回滚。
- 真正的第二条Event插入失败会回滚第一条Event和两种事实；Audit已追加后故障仍全部回滚；commit确认丢失后重试同key返回原Receipt，保持2/2计数。
- NOT_CONNECTED联系次数1/2仅一条普通联系事件，次数3仅一条耗尽事件；SUSPECT_INVALID仅一条普通联系事件。调配分支按持久Decision code和content_digest选事件，不采信Handler单独声明的结果标签。

[R1CommandPolicyIT](../../backend/src/test/java/io/github/windyzhu3/ontologylaw/execution/R1CommandPolicyIT.java)证明新capture事件引用同事务最终Lead revision，Draft事件引用准确保存后的唯一Draft而不确认或完成Task。陈旧Draft binding不能把既有revision冒充新保存。两种recovery使用不同组织中的SERVICE与真实Owner，服务Grant必须覆盖Owner组织；不覆盖、Owner Principal失效或Owner组织关闭均拒绝且命令增量全零。恢复前后保持Owner、subject、原SLA和WaitReceipt；伪造状态恢复、错误post-CAS来源或SLA改写均回滚。已有运行时的NO_CHANGE savepoint、拒绝、并发重复key、最终身份锁复验及提交确认丢失回归保留。

## 语义承接与原始约束

本轮新增语义由批准设计及ADR-0007显式承接：四类专属授权、五个新增事件描述、空payload、精确成功集合和CONNECTED_VALID的2/2计数是有意的基线修订。其余原始约束没有随实现放宽。

ContactResult/WaitReceipt不可变行哈希按[原规范](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)逐持久字段编码；本轮澄清仅`tenant_id`改为`tenantId`，其余JSON键保留SQL字段名。UUID为小写连字符，时间为UTC六位小数，二进制为无padding base64url，NULL显式写出。固定ContactResult向量及带二进制/null/时间的WaitReceipt向量`50qW-BnsU4UkYsAIfbQ-qqAuyT7BjZbBwhgbYegJL6E`独立验证该编码；不使用DTO、行文本或伪造常量代替持久事实证明。

未改变13 Schema、52＋2物理合同、manifest、字段合同或V001–V850；一SPA、一OpenAPI、单Jar及api/worker互斥角色保留。新增SQL仅在各Owner的internal.persistence，execution-facing记录由下游lead组合，冻结模块DAG及架构门禁未修改。HTTP operation、ETag、scope字段和摘要向量、错误公开形态、依赖版本未修改。Event payload始终为`{}`，schemaVersion=1，每条仅`R1_PROJECTION` Outbox；不增加通用事件平台或R2消费者。

## 历史验证命令和观察结果

下表保留Task 3修复前`3110f97dffb1f20d3715a98b615ebd8c278519b1`的实施验证记录。其中55 unit＋93 integration是该修复前代码边界的结果，不能代表随后新增回归和最终修复后的完整后端执行次数；最终修复的实测提交边界另列。

| 验证 | 命令 | 结果 |
|---|---|---|
| 修复前后端完整门禁（`3110f97`） | `./mvnw.cmd -f backend/pom.xml verify -Pit` | exit 0；55 unit＋93 integration executions；failure/error/skipped全部为0；含12项ArchitectureTest、16项OpenApiContractTest及真实jOOQ生成验证 |
| Linux完整baseline测试 | `python -m unittest discover -s scripts/baseline/tests -v` | exit 0；199 test executions，195.291秒，无跳过；discovery含被导入的合同测试重复执行，不代表199个独立案例 |
| Schema生成与测试 | `python generate.py --check`；`python -m unittest discover -s tests -v` | exit 0；生成无漂移，57 tests通过 |
| PostgreSQL静态解析 | `python scripts/verify_generated_sql.py` | exit 0；20 migrations、24 PL/pgSQL functions解析通过 |
| 生成客户端 | `npm run openapi:check` | exit 0 |
| SPA客户端验证 | `npm run typecheck --workspace apps/workbench`；`npm run test --workspace apps/workbench`；`npm run build --workspace apps/workbench` | 三项exit 0；7 tests通过；构建14 modules；不代表浏览器业务验收 |
| 零漂移核对 | 从Task 3 BASE `b0de36a62b4160cf23da7fdfb4f15bd2408e8dcb`比较database、OpenAPI、前端源码、依赖、ArchitectureTest及scope向量 | 这些路径无差异；`git diff --check`通过 |

## 最终修复后的实测边界

最终修复源提交为`2fb596a6aac91072264f91f3d42dd3391ea5ce72`（`fix(r1): reject reused connected facts and numeric schema booleans`），tree为`bc8ea0a3c32b8cacf6f4fca4852149b4a22c01a9`。该提交已包含全部代码、测试、scratch索引范围修正和上述历史记录校正；两项完整复验开始至结束均无tracked文件更改。后续文档提交仅追加本节实测证据，不将其文档SHA冒充已执行测试的源提交。

使用固定`JAVA_HOME=C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1`及其`bin`执行`./mvnw.cmd -f backend/pom.xml verify -Pit`，exit 0。日志的两个汇总分别属于Surefire和Failsafe：

```text
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  01:31 min
Finished at: 2026-09-05T18:31:35+08:00
```

Surefire包括OpenApiContractTest 16、ArchitectureTest 12、RuntimeRoleTest 17、CanonicalJsonTest 7、R1EventPolicyTest 1和PostgresImageLockTest 2。Failsafe包括CommandRuntimeIT 22、CapabilityRoleExecutorIT 8、R1CommandPolicyIT 47、R1ContractClosureIT 9、AuthorizationServiceIT 8和JooqGenerationIT 1；`failsafe-summary.xml`另确认completed=95、errors/failures/skipped/flakes=0。这些是测试执行次数，含继承的运行时回归，不代表95个新增案例。

Python验证器已先观察到真实复制fixture中的`0`和`0.0`导致两个预期失败，再补上`additionalProperties is False`类型敏感检查；原duplicate-member拒绝保留。数值和重复成员focused GREEN为2 tests／0.089秒。既有ContactResult＋Opportunity回归先观察到“Expected java.sql.SQLException to be thrown, but nothing was thrown.”（1 IT失败，0 errors/skips），修复后与routing测试一起通过（7 unit＋2 IT，26.443秒）。以上RED均为语义失败，未将fixture设置错误记为RED。

在相同源提交/tree执行准确的CI baseline单元测试目标，未使用discovery替代：

```text
docker run --rm --mount type=bind,source=C:/Users/Jacob/.cache/codex-worktrees/ontology-law-c0,target=/repo,readonly -w /repo -e PYTHONDONTWRITEBYTECODE=1 python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970 python -m unittest scripts.baseline.tests.test_verify_baseline -v
Ran 188 tests in 195.072s
OK
```

该命令exit 0，无failure/error/skip，包含新增数值Schema回归及Linux符号链接检查。它是baseline测试套件通过的证据；稀疏本地checkout仍缺历史merge对象和34张PNG，不能据此宣称本地完整`verify_baseline.py`检查或完整GitHub checkout CI通过。最终schema/frontend检查、全分支复审、发布tree比较、hosted CI和merge/head证据仍由控制者后续执行；本次未推进任何业务交付状态。

工具链为冻结的JDK 25.0.4.1+1、Maven 3.9.16、Node 24.20.0、npm 11.9.0。Linux测试使用`python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`和只读`/repo`挂载；Windows账户缺少symlink权限，没有跳过该测试。SQL解析器在一次性容器安装现有`requirements-dev.txt`的pglast 7.10/PyYAML 6.0.3后执行；首次裸镜像缺pglast的失败不记为通过。前端首次嵌套npm因手工PATH指向错误shim目录失败，改为固定npm CLI直接运行workspace脚本后通过，仓库未为此修改。

成功日志不声明pristine：保留既有OpenAPI生成器mutualTLS error-level消息、OpenAPI 3.1/oneOf/discriminator及模型命名警告、JooqAuditAppender的JAXB annotation编译警告和Flyway already-exists警告；没有升级依赖或加入抑制。它们仍须最终审查分类，生成/测试成功不等于真实mTLS接入已完成。

稀疏本地checkout缺历史merge对象及34张PNG，完整`verify_baseline.py`在此环境的这些检查不能记为PASS。完整GitHub checkout中的Baseline consistency、R1 foundation、Scaffold gate及Schema静态/实库CI、最终审查和merge/head证据是后续合并门禁，由控制者记录。本记录不提前声明已合并。

下一工作仍为[原R1计划](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)Task 5：Lead接入及P0-01～P0-04，随后Task 6–10。R2启用时须覆盖已经被R1投影消费的历史OpportunityOpened、并发新事件、重复、乱序、中断重试，以及Tenant＋Opportunity＋首个推进责任类型的至多一次业务约束；该交接验收本轮未执行。
