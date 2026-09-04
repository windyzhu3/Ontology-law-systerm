# PostgreSQL Schema 运行证据

## 当前 v1.1 闭合

`52-plus-2-v1.1` 已在 PR #5 的 PostgreSQL 18 托管执行器中完成独立运行时闭合。权威记录是：

- [v1.1 机器可读摘要](2026-08-28-postgresql-18-v1.1-summary.json)
- [v1.1 审阅报告](2026-08-28-postgresql-18-v1.1-report.md)

该闭合绑定 workflow run `33590363980`、artifact `9831569892`，使用 PostgreSQL 18.6 和 Flyway 13.4.0，完成两次隔离空库运行、run A no-op 和五个失败关闭探针。它覆盖 V001–V850、20 个迁移、54 张受管表、13 个 Schema、207 个物理外键和53个 mutation guard。safe artifact 不公开原始 catalog fingerprint，只公开完整 verifier 输出的 SHA-256。

本地执行器仍没有 Docker/Compose，本地结果为 `BLOCKED/docker_compose_unavailable`、退出码 `5`，从未声称本地 PASS。v1.1 证据只证明数据库合同的运行时门禁，不证明 R1 业务生产实现。

## 历史 v1 ADR-0003 证据

`52-plus-2-v1` 的历史持久证据与 v1.1 独立，仍由 [ADR-0003](../../adr/ADR-0003-hosted-runtime-evidence-promotion.md) 治理：

- [v1 机器可读摘要](2026-09-01-postgresql-18-v1-summary.json)
- [v1 审阅报告](2026-09-01-postgresql-18-v1-report.md)

它来自 PR #3 workflow run `33405965491`、artifact `9763252627`，覆盖 V001–V840、19 个迁移、206 个物理外键和53个 mutation guard。以下命令只重验这一 ADR-0003 v1 持久证据对，不验证 v1.1 report：

```bash
python3 database/schema-contract-52-plus-2/runtime/verify_runtime.py validate-promoted-evidence
```
