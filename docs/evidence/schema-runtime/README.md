# PostgreSQL Schema 运行证据

`52-plus-2-v1` 已按 [ADR-0003](../../adr/ADR-0003-hosted-runtime-evidence-promotion.md) 完成 PostgreSQL 18 托管运行时验证。规范持久证据是以下固定双文件：

- [机器可读摘要](2026-09-01-postgresql-18-v1-summary.json)
- [审阅报告](2026-09-01-postgresql-18-v1-report.md)

来源是 PR #3 的 workflow run `33405965491`、artifact `9763252627`。下载 ZIP SHA-256 为 `dc4a633aadf4faee4931dd782d4edd105add5078227d9f2a24f2fb4b2401e7fc`，且只含 `ci-runtime-summary.json` 与 `ci-job-summary.md`。仓库晋级器验证规范双文件、`PASSED` 语义、base/head/test-merge、V001–V840 tree、合同摘要和字段合同摘要后，才原子发布上述持久双文件；不是人工拼造成功记录。

托管结果使用 PostgreSQL 18.6、Flyway 13.4.0，完成两次隔离空库运行、run A no-op、19 个迁移、54 张受管表、13 个 Schema、206 个外键、53 个 mutation guard 和五个失败关闭探针。safe artifact 没有公开原始 32 位 catalog fingerprint；它公开的两次 verifier 输出 SHA-256 相同，持久证据按原貌标注该边界。

本地执行器没有 Docker/Compose，本地结果仍是 `BLOCKED/docker_compose_unavailable`、退出码 `5`，从未声称本地 PASS。ADR-0003 仅对 v1 接受经过精确来源绑定的托管两次运行作为正式证明，不覆盖 ADR-0002、V850、v1.1 或 R1 实现。

维护命令：

```bash
python3 database/schema-contract-52-plus-2/runtime/verify_runtime.py validate-promoted-evidence
```

PR 与 `main` 的 `runtime-postgresql-18` job 会先重验该持久证据，再发起新的两次托管空库运行；任一闭合字段、来源、合同或渲染漂移都会失败关闭。
