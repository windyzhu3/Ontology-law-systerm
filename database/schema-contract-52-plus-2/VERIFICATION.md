# 52＋2 Schema 合同验证记录

验证日期：2026-09-01

本记录保存静态校验与 `52-plus-2-v1` PostgreSQL 运行时证据，不是领域、拓扑或生命周期规范。

## 静态校验证据（非规范）

- `python3 generate.py --check`：通过；静态合同与生成物逐字节一致。
- `python3 -m unittest discover -s tests -v`：50/50 通过。
- `python3 scripts/verify_generated_sql.py`（`pglast 7.10`）：19 个 PostgreSQL 迁移和 23 个 PL/pgSQL 函数全部解析通过。
- 表总账：52 张应用事实表＋1 张自建 `platform_meta.deployment_state`；Flyway 创建 `platform_meta.flyway_schema_history` 后准确为 54 张。
- 物理关系：206 个复合外键，全部租户外键以 `tenant_id` 开头并使用 `NO ACTION`。
- 类型化关系：22 个准确引用槽；静态允许列表与 revision/hash 选择器已进入 manifest。
- 跨行关系：25 个守卫合同已进入 manifest；更新、权限、注释和第 55 张表由生成测试及 `V840` 失败式验证。
- 禁止项扫描：未出现级联删除、`SET NULL`、`SET DEFAULT`、业务原生 enum 或手工创建 Flyway 历史表。

## PostgreSQL 18 v1 运行时证据

PR #3 workflow run `33405965491` 的闭合 artifact `9763252627` 在 PostgreSQL 18.6、Flyway 13.4.0 上通过。它绑定 test-merge `ae0ec5d32fdc2e5db7276a9ba7ebbbeb2814a6c1`、base `72a83b810339095a6ebefd11b30cf7fc8f522eec` 与 head `d0cd39de079f69cbd3973ab59f9f4ff75732203c`，完成两套隔离空库、run A no-op 和五个失败关闭探针。

- [持久机器摘要](../../docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-summary.json)
- [持久审阅报告](../../docs/evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md)
- [ADR-0003 托管证据晋级决策](../../docs/adr/ADR-0003-hosted-runtime-evidence-promotion.md)
- ZIP SHA-256：`dc4a633aadf4faee4931dd782d4edd105add5078227d9f2a24f2fb4b2401e7fc`
- `contractSha256`：`a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a`
- `fieldContractSha256`：`be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed`

safe artifact 不公开原始 catalog fingerprint，只公开完整 verifier 输出的 SHA-256；run A、run B 与 run A no-op 的该摘要相同。不得把输出摘要描述成原始 fingerprint。

## 本地限制与门禁结论

当前本地执行器没有 Docker/Compose，本地尝试保持 `BLOCKED/docker_compose_unavailable`、退出码 `5`，没有本地 PASS。ADR-0003 规定：在精确 artifact/source/合同校验与持久发布后，本次托管两套空库证明正式满足 v1 运行时门禁，因此 `DB-52P2-PG18-RUNTIME=RUNTIME_VERIFIED`。

该结论只覆盖 V001–V840 与 `52-plus-2-v1`；不覆盖 ADR-0002、V850、v1.1 或 R1 生产实现。受控发布仍须在目标环境执行 `migrate` → strict `validate` → `info`，并在应用制品与 manifest 摘要匹配后才可 CAS 激活 `deployment_state`。
