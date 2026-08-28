# ADR-0001：PR #2 与 PostgreSQL 运行时门禁顺序

日期：2026-08-28

状态：已确认（`FROZEN`）

## 背景

PR #2 的职责是收口当前MVP基线、历史权威、五态台账、静态数据库合同验证和只读CI。原门禁同时要求PR #2合并前取得真实PostgreSQL证据，又要求PostgreSQL运行时计划只能在PR #2合并后从最新`main`执行，形成不可满足的循环。

## 决策

1. 当前基线版本推进为`MVP-2026-08-28.1`。
2. PR #2在当前基线、历史降级、五态台账、静态数据库验证和只读CI通过后即可合并；真实PostgreSQL运行时证据不是PR #2合并前置条件。
3. PR #2合并后，按[PostgreSQL运行时验证计划](../superpowers/plans/2026-08-28-postgresql-runtime-verification-plan.md)创建或推进独立交付行`DB-52P2-PG18-RUNTIME`至`RUNTIME_VERIFIED`。
4. `DB-52P2-CONTRACT`和`DB-52P2-MIGRATIONS`保持`MERGED`；静态合同或生成SQL不得借用运行时证据升级自身状态。
5. 独立运行时行达到`RUNTIME_VERIFIED`前，不得开始[R1实施计划](../superpowers/plans/2026-08-28-r1-lead-contact-vertical-slice-plan.md)。R2随后还必须同时取得五个独立R1交付门：`R1-OPENAPI`、`R1-BACKEND`、`R1-SPA`、`R1-E2E-GOLDEN`和`R1-E2E-FAILURES`。
6. R1的Ingress Completion槽决策保留编号`ADR-0002`，其未来路径为`docs/adr/ADR-0002-lead-ingress-completion-slot.md`。

## 结果

- 当前[交付台账](../progress/MVP-DELIVERY-LEDGER.md)只保留运行时计划的`DRAFT`行，不预造`DB-52P2-PG18-RUNTIME`交付行。
- PR #2的默认基线CLI可在如实报告后续准备度阻塞项时成功；严格R2模式仍以非零退出拒绝提前进入R2。
- 任何改变此顺序的决定都必须使用新的ADR和新的基线版本，不能通过调整台账措辞或复用数据库静态行绕过。
