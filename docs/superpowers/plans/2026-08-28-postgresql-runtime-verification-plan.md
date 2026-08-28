# PostgreSQL Runtime Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在两次相互隔离的空 PostgreSQL 18 数据库中，以固定 Flyway 13.4.0 执行并验证19个迁移，保存可复验的52＋2运行时证据，并把该门禁接入CI。

**Architecture:** Docker Compose提供一次性PostgreSQL、Flyway和`psql`验证器。仓库中的生成迁移以只读卷挂载；启动SQL只创建迁移/能力角色，不创建业务对象。验证SQL直接查询系统目录并执行最小权限负向测试。编排脚本连续运行A/B两套空库，规范化时间等非确定字段后比较Schema指纹。

**Tech Stack:** PostgreSQL 18、Flyway 13.4.0 Community、Docker Compose v2、Bash、`psql`、Python 3.12标准库、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-08-28-baseline-closure-and-r1-gate-design.md`

## Global Constraints

- 只在PR #2合并后、从最新`main`创建`test/schema-contract-postgresql-18`分支执行。
- 先运行PR #2基线门禁；未通过时停止。
- PostgreSQL使用18系列，满足当前冻结技术基线并高于设计要求的15＋；不得用H2、SQLite、SQL解析器或Mock替代本门禁。
- Flyway固定为`13.4.0`；镜像摘要写入`database/schema-contract-52-plus-2/runtime/toolchain.lock.json`后，Compose只能引用摘要，不能引用`latest`。
- 迁移目录只读挂载；不得编辑19个既有生成迁移。发现物理错误时保存失败证据，回到Python合同源并新增前向迁移版本。
- 每次运行使用独立Compose project、独立匿名volume和随机宿主端口；结束后清理容器和volume，不复用数据库。
- 凭据仅为一次性测试值，不进入日志证据；生产凭据/IaC不在本计划范围。

---

## Task 1: 锁定工具链并为运行编排建立失败测试

**Files:**

- Create: `database/schema-contract-52-plus-2/runtime/toolchain.lock.json`
- Create: `database/schema-contract-52-plus-2/runtime/tests/test_runtime_harness.py`
- Create: `database/schema-contract-52-plus-2/runtime/verify_runtime.py`
- Modify: `.gitignore`

- [ ] 使用Docker Registry返回的不可变digest锁定`postgres:18`和`redgate/flyway:13.4.0`；锁文件必须包含`image`、`tag`、`digest`、`resolvedAt`，且digest匹配`sha256:[0-9a-f]{64}`。实现时记录实际18.x服务器补丁版本到证据，不在计划中猜测补丁号。

- [ ] 先编写单元测试，覆盖：锁文件拒绝`latest`和缺失digest；运行ID只允许`[a-z0-9-]+`；证据目录不能逃出`.artifacts/schema-runtime/`；A/B指纹规范化后必须一致；命令失败时保留证据并返回原退出码。

测试方法名固定为`test_images_are_digest_pinned`、`test_run_id_rejects_path_traversal`、`test_evidence_path_is_workspace_scoped`、`test_normalized_fingerprints_match`和`test_failed_stage_is_reported_without_claiming_success`。

- [ ] 运行测试并确认失败，因为`verify_runtime.py`尚未提供所需接口。

```bash
cd database/schema-contract-52-plus-2
python3 -m unittest runtime.tests.test_runtime_harness -v
```

- [ ] 实现纯标准库编排核心：`load_toolchain_lock`、`validate_run_id`、`evidence_dir`、`run_checked`、`normalize_snapshot`和CLI。CLI固定子命令为`verify --runs 2 --evidence-dir <path>`，`--runs`小于2时拒绝。

- [ ] `.gitignore`只忽略`/.artifacts/`、`__pycache__/`、`*.pyc`及本地虚拟环境；不得忽略最终脱敏证据目录`docs/evidence/schema-runtime/`。

- [ ] 运行测试至通过。

```bash
python3 -m unittest runtime.tests.test_runtime_harness -v
```

- [ ] Commit:

```bash
git add .gitignore database/schema-contract-52-plus-2/runtime
git commit -m "test: scaffold PostgreSQL runtime verifier"
```

## Task 2: 建立一次性空库和Flyway迁移链

**Files:**

- Create: `database/schema-contract-52-plus-2/runtime/compose.yaml`
- Create: `database/schema-contract-52-plus-2/runtime/bootstrap/001-create-capability-roles.sql`
- Modify: `database/schema-contract-52-plus-2/runtime/tests/test_runtime_harness.py`
- Modify: `database/schema-contract-52-plus-2/runtime/verify_runtime.py`

- [ ] 先增加失败测试，要求Compose恰有`postgres`、`flyway`、`verifier`三个服务；迁移目录挂载为`:ro`；PostgreSQL有`pg_isready`健康检查；Flyway等待数据库健康且verifier等待Flyway成功；所有镜像由锁文件渲染为digest。

- [ ] 运行单测确认失败。

```bash
python3 -m unittest runtime.tests.test_runtime_harness -v
```

- [ ] 编写bootstrap SQL，创建一次性`law_schema_migrator LOGIN NOINHERIT`并让它拥有测试数据库；另创建四个互异、无父角色的`NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT`能力角色：`law_app_command`、`law_app_worker`、`law_app_query`、`law_audit_append`。bootstrap不得创建13个Schema、业务表或Flyway历史表。

- [ ] Compose的Flyway配置固定包含：

```text
-connectRetries=30
-validateMigrationNaming=true
-cleanDisabled=true
-baselineOnMigrate=false
-defaultSchema=platform_meta
-schemas=identity,audit,responsibility,execution,external_action,evidence,party,lead,opportunity,conflict,contract,transfer,platform_meta
-locations=filesystem:/flyway/sql
-placeholders.app_command_role=law_app_command
-placeholders.app_worker_role=law_app_worker
-placeholders.app_query_role=law_app_query
-placeholders.audit_append_role=law_audit_append
```

- [ ] 空库命令顺序固定为`migrate`（其`validateOnMigrate=true`）→严格`validate`→`info`。禁止先执行strict validate，因为19个pending versioned migrations会被正确判为失败；不得用`baselineOnMigrate`或永久`ignoreMigrationPatterns`绕过。同步修正README和VERIFICATION中的旧命令顺序。

- [ ] 编排器用临时Compose override把锁定镜像注入服务，生成随机project名；始终在`finally`执行`docker compose down --volumes --remove-orphans`。

- [ ] 运行静态测试至通过，再执行一次集成运行并预期在verifier尚未实现时失败；确认19个迁移本身已经成功且失败证据被保存。

```bash
python3 -m unittest runtime.tests.test_runtime_harness -v
python3 runtime/verify_runtime.py verify --runs 2 --evidence-dir ../../.artifacts/schema-runtime
```

- [ ] Commit:

```bash
git add database/schema-contract-52-plus-2/runtime
git commit -m "test: run Flyway against disposable PostgreSQL"
```

## Task 3: 实现系统目录、权限和失败关闭断言

**Files:**

- Create: `database/schema-contract-52-plus-2/runtime/sql/assert_schema_contract.sql`
- Create: `database/schema-contract-52-plus-2/runtime/sql/assert_capabilities.sql`
- Create: `database/schema-contract-52-plus-2/runtime/sql/schema_fingerprint.sql`
- Create: `database/schema-contract-52-plus-2/runtime/sql/failures/extra_managed_table.sql`
- Create: `database/schema-contract-52-plus-2/runtime/sql/failures/forbidden_delete_grant.sql`
- Create: `database/schema-contract-52-plus-2/runtime/sql/failures/missing_mutation_guard.sql`
- Modify: `database/schema-contract-52-plus-2/runtime/compose.yaml`
- Modify: `database/schema-contract-52-plus-2/runtime/tests/test_runtime_harness.py`

- [ ] 先增加静态测试，要求断言SQL覆盖以下精确事实：13个受管Schema、52张应用表、2张`platform_meta`表、空`public`、19条成功迁移、206个复合外键、53张自建表的mutation guard、所有应用外键`NO ACTION`且validated/MATCH SIMPLE、租户外键首列`tenant_id`、四能力角色互异/无父角色/`NOLOGIN`、`deployment_state`为`PRIMARY/BLOCKED/52-plus-2-v1/revision=0`且两个摘要均为32字节全零。

- [ ] 运行测试确认失败。

```bash
python3 -m unittest runtime.tests.test_runtime_harness -v
```

- [ ] 在`assert_schema_contract.sql`使用PL/pgSQL `DO`块和`RAISE EXCEPTION`失败关闭；每条错误包含断言名、expected和actual。除V840已经验证的合同外，再显式查询`platform_meta.flyway_schema_history`：19行、全部`success=true`、最大version为`840`。

- [ ] 在`assert_capabilities.sql`用事务和`SET LOCAL ROLE`验证最小权限：

  - `law_app_query`可读`lead.lead`但不能INSERT/UPDATE/DELETE，也不能直接读`audit.audit_entry`；
  - `law_audit_append`只能INSERT `audit.audit_entry`，不能SELECT或UPDATE；
  - `law_app_worker`只能SELECT及更新两个Outbox允许列，不能写领域表；
  - `law_app_command`可按白名单INSERT/UPDATE，但不能DELETE、TRUNCATE、修改冻结列或写`platform_meta`；
  - 四角色都不能创建Schema/Table、不能取得迁移Owner身份。

- [ ] 负向测试必须捕获预期SQLSTATE并回滚，不得因“命令失败但psql继续”而假通过；`psql`统一使用`-X -v ON_ERROR_STOP=1`。

- [ ] 增加三项事务行为探针并校验准确SQLSTATE：跨租户组织父引用`23503`；deployment no-op更新`55000`；revision未精确加一`40001`。

- [ ] 在`schema_fingerprint.sql`按稳定顺序输出：服务器版本、Schema、表/列/约束/索引/触发器/函数定义摘要、Flyway脚本/校验和；排除OID、执行耗时、安装时间和数据库随机标识。

- [ ] 让verifier服务按顺序执行三个SQL文件并输出单个JSON摘要；任何断言失败使容器非零退出。对run A再次执行`migrate`，要求history仍为19且指纹不变。

- [ ] 实现五个失败关闭场景；任何场景意外成功、失败阶段错误或消息不匹配都令总验证失败：

  1. `app_query_role=law_missing_role`：V830失败并含`configured application database role does not exist`；
  2. 迁移至V830后创建`lead.runtime_drift_probe`，V840失败并含`expected 52 application tables`；
  3. V830后授予`law_app_command`对`lead.lead`的DELETE，V840失败并含`forbidden DELETE or TRUNCATE`；
  4. V830后删除`lead.lead_contact_result`的mutation guard，V840失败并含`mutation guard coverage mismatch`；
  5. 仅在临时迁移副本给V010追加注释，对已迁移run A strict validate，必须checksum mismatch；绝不修改仓库生成SQL。

- [ ] 运行测试和两次真实空库验证。

```bash
python3 -m unittest runtime.tests.test_runtime_harness -v
python3 runtime/verify_runtime.py verify --runs 2 --evidence-dir ../../.artifacts/schema-runtime
```

- [ ] Commit:

```bash
git add database/schema-contract-52-plus-2/runtime
git commit -m "test: assert 52 plus 2 runtime contract"
```

## Task 4: 生成并复核可提交的脱敏证据

**Files:**

- Create: `docs/evidence/schema-runtime/README.md`
- Create after successful run: `docs/evidence/schema-runtime/2026-08-28-postgresql-18-summary.json`
- Create after successful run: `docs/evidence/schema-runtime/2026-08-28-postgresql-18-report.md`
- Modify: `database/schema-contract-52-plus-2/README.md`
- Modify: `database/schema-contract-52-plus-2/VERIFICATION.md`
- Modify: `database/schema-contract-52-plus-2/flyway.conf.example`
- Modify: `database/schema-contract-52-plus-2/runtime/verify_runtime.py`

- [ ] 先增加测试：证据必须含Git commit、UTC时间、镜像tag与实际RepoDigest、PostgreSQL完整版本、Flyway版本、两次run ID、每阶段命令名/退出码、19迁移、54受管表、13Schema、206外键、53个mutation guard、A/B指纹、run A no-op migrate、五个失败关闭场景、合同与字段摘要；不得包含密码、容器环境变量、连接串或临时目录。

- [ ] 运行测试确认失败，然后实现证据规范化和Secret扫描。扫描模式至少覆盖`password=|POSTGRES_PASSWORD|jdbc:postgresql://[^\s]+:[^\s]+@|token|secret`，匹配即拒绝发布证据。

- [ ] 成功运行两次空库验证；要求A/B规范化Schema指纹完全相同。把脱敏JSON与Markdown报告复制到固定证据路径，原始容器日志仅作为CI artifact保存，不提交仓库。

- [ ] 将README、`VERIFICATION.md`和`flyway.conf.example`中的空库执行顺序统一为`migrate`后strict`validate`；成功后把“未在真实PostgreSQL执行”替换为准确运行结论和证据链接，保留静态验证记录，不回写历史日期。

- [ ] 复核证据不声称API、SPA、授权业务逻辑或R1已运行验证。

- [ ] Commit:

```bash
git add docs/evidence/schema-runtime database/schema-contract-52-plus-2/README.md database/schema-contract-52-plus-2/VERIFICATION.md database/schema-contract-52-plus-2/flyway.conf.example database/schema-contract-52-plus-2/runtime
git commit -m "docs: record PostgreSQL 18 migration evidence"
```

## Task 5: 接入CI并推进台账

**Files:**

- Modify: `.github/workflows/schema-contract-52-plus-2.yml`
- Modify: `docs/progress/MVP-DELIVERY-LEDGER.md`

- [ ] 在现有工作流保留静态`verify` job，新增依赖它的`runtime-postgresql-18` job；设置只读`contents`权限、20分钟超时和并发取消。不得从Fork PR读取仓库Secret，本验证只使用运行时随机的一次性本地凭据。

- [ ] `runtime-postgresql-18`复用与本地完全相同的Compose入口执行两次空库验证，并以`actions/upload-artifact@v4`和`if: always()`上传`.artifacts/schema-runtime`，artifact保留90天；失败时仍上传脱敏日志并写Step Summary，但台账不推进。

- [ ] 完整运行：

```bash
python3 scripts/baseline/verify_baseline.py
cd database/schema-contract-52-plus-2
python3 generate.py --check
python3 -m unittest discover -s tests -v
python3 scripts/verify_generated_sql.py
python3 -m unittest runtime.tests.test_runtime_harness -v
python3 runtime/verify_runtime.py verify --runs 2 --evidence-dir ../../.artifacts/schema-runtime
```

- [ ] 仅在本地和CI两者成功、证据已入库后，创建或推进`DB-52P2-PG18-RUNTIME`台账行为`RUNTIME_VERIFIED`；`DB-52P2-CONTRACT`和`DB-52P2-MIGRATIONS`都保持原有`MERGED`状态，三类交付状态不得合并。

- [ ] 运行`git diff --check`、检查无生成漂移和Secret，再提交：

```bash
git diff --check
rg -n -i "password=|POSTGRES_PASSWORD|token|secret" docs/evidence/schema-runtime
git add .github/workflows/schema-contract-52-plus-2.yml docs/progress/MVP-DELIVERY-LEDGER.md
git commit -m "ci: require real PostgreSQL schema verification"
```

预期：Secret扫描无输出；所有验证为零退出。

## Exit Gate

- [ ] 两次独立空库运行都由Flyway 13.4.0成功执行19个迁移和V840。
- [ ] PostgreSQL完整18.x版本、Flyway版本、退出码和A/B相同指纹已脱敏保存。
- [ ] 52应用表、2技术表、13Schema、206复合外键、四个NOLOGIN能力角色及BLOCKED部署门禁均由真实系统目录证明。
- [ ] CI能从空环境重现，不依赖开发者机器已有数据库。
- [ ] 只有满足全部条件后才执行`2026-08-28-r1-lead-contact-vertical-slice-plan.md`。
