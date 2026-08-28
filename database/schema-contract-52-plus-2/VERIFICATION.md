# 52＋2 Schema 合同验证记录

验证日期：2026-08-26

本记录仅保存静态校验证据，不是领域、拓扑、生命周期或运行时规范；真实PostgreSQL尚未运行。

## 静态校验证据（非规范）

- `python3 generate.py --check`：通过；静态合同与生成物逐字节一致。
- `python3 -m unittest discover -s tests -v`：43/43 通过。
- `python3 scripts/verify_generated_sql.py`（`pglast 7.10`）：19 个 PostgreSQL 迁移和 23 个 PL/pgSQL 函数全部解析通过。
- 表总账：52 张应用事实表＋1 张自建 `platform_meta.deployment_state`；Flyway 创建 `platform_meta.flyway_schema_history` 后准确为 54 张。
- 物理关系：206 个复合外键，全部租户外键以 `tenant_id` 开头并使用 `NO ACTION`。
- 类型化关系：22 个准确引用槽；静态允许列表与 revision/hash 选择器已进入 manifest。
- 跨行关系：25 个守卫合同已进入 manifest；更新、权限、注释和第 55 张表由生成测试及 `V840` 失败式验证。
- 禁止项扫描：未出现级联删除、`SET NULL`、`SET DEFAULT`、业务原生 enum 或手工创建 Flyway 历史表。

## 静态摘要（非规范）

- `contractSha256`：`a9c53d0126b7997e0aac511d3a4baf1da02a5f10d829ca5113458be51813034a`
- `fieldContractSha256`：`be79d991fa9e13e3f0af1c682333b6a063201387b78f7c9ec32a03bad51096ed`

## 环境限制与上线门禁

当前构建环境没有 `postgres`、`psql`、Flyway CLI 或 Docker，因此未声称已在真实 PostgreSQL 实例执行迁移。发布前必须在专用 PostgreSQL 15＋数据库中，以固定 Flyway 版本依次执行：

```bash
flyway -configFiles=flyway.conf validate
flyway -configFiles=flyway.conf migrate
```

只有 `V840__schema_contract_validation.sql` 成功、应用制品与 manifest 摘要匹配，受控发布作业才可将 `deployment_state` 从 `BLOCKED` CAS 切换为 `ACTIVE`。
