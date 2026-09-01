# ADR-0003：托管运行时证据晋级

- 状态：Accepted
- 日期：2026-09-01
- 范围：`52-plus-2-v1` / `DB-52P2-PG18-RUNTIME`

## 背景

原运行时计划要求本地与 CI 都成功；后续批准的结构化 artifact 设计允许本地执行器在没有 Docker/Compose 时如实返回 `BLOCKED/docker_compose_unavailable`，再由具备 Docker 能力的托管执行器完成验证。PR #3 的本地尝试退出码为 `5`，没有本地 PASS；托管 workflow run `33405965491` 的闭合 artifact `9763252627` 则在 PostgreSQL 18.6 与 Flyway 13.4.0 上通过。

## 决策

对 `52-plus-2-v1`，两套相互隔离空库的托管运行正式替代原计划的“本地成功＋CI 成功”，但只有同时满足以下条件时才能把台账推进到 `RUNTIME_VERIFIED`：

1. 下载 ZIP 的 SHA-256 与经审阅值完全相同，ZIP 只包含规范 JSON/Markdown 双文件；
2. artifact 为 `PASSED/runtime_verified`，含两次完整运行、run A no-op 与五个完整失败关闭探针；
3. base、head、test-merge 绑定准确，base→head 的 V001–V840 Git tree 完全相同；
4. artifact 中合同摘要与 head 上的 manifest、字段合同逐字节摘要一致；
5. 通过仓库内 fail-closed 晋级器生成固定持久证据，不复制原始日志、不人工拼造成功 JSON；
6. 后续 PR/main workflow 每次重新验证持久证据的闭合 schema、来源绑定和规范渲染。

本地结果必须继续记录为 `BLOCKED`，不得改写为 PASS。闭合 safe artifact 未公开原始 32 位 catalog fingerprint，只公开完整 verifier 输出的 SHA-256；两次空库与 run A no-op 的该摘要相同。持久证据必须明确此披露边界，不得把输出摘要冒充原始 catalog fingerprint。

## 结果与边界

- `DB-52P2-PG18-RUNTIME` 可仅对 `52-plus-2-v1` 标为 `RUNTIME_VERIFIED`。
- 本决策不证明 ADR-0002、`52-plus-2-v1.1`、V850 或任何 R1 生产实现已经完成。
- v1.1 必须发布独立证据，不能继承本次 v1 结论。
- 若来源绑定、artifact hash、规范双文件或合同摘要任一不匹配，晋级与后续验证都失败关闭。

规范证据见 [PostgreSQL 18 v1 报告](../evidence/schema-runtime/2026-09-01-postgresql-18-v1-report.md) 与 [机器可读摘要](../evidence/schema-runtime/2026-09-01-postgresql-18-v1-summary.json)。
