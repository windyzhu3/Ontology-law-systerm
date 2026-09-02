# ADR-0002：Lead Ingress Completion 一次写入槽

Status: Accepted

日期：2026-09-02

## Context

R1 的 `COMPLETE_LEAD_INGRESS` 只处理一种受限情况：渠道捕获的原始电话与邮箱均缺失，准确 Task Owner 通过后续交互补齐至少一种联系方式。冻结的 v1 合同没有可承载该完成事实的类型化字段；覆盖 `captured_*` 会破坏渠道原始事实，将联系方式写入 Party、ActionDraft、Audit、Event、通用 JSON 或摘要也会混淆 Fact Owner、扩大披露面或丢失可验证语义。

本 ADR 只决定 P0-02 的持久化槽和数据库演进方式，不实现 HTTP、Task handler 或页面，也不把静态 schema 视为真实 PostgreSQL 18 运行证据。

## Decision

保持 52 张应用事实表加 2 张技术表不变，在 `lead.lead` 通过唯一前向迁移 `V850__lead_ingress_completion_slot.sql` 追加以下九个可空列：

| Column | SQL type | Meaning |
|---|---|---|
| `ingress_completion_phone_ciphertext` | `bytea` | 补全电话密文 |
| `ingress_completion_phone_hmac` | `bytea`，32 bytes | 电话精确匹配 HMAC |
| `ingress_completion_email_ciphertext` | `bytea` | 补全邮箱密文 |
| `ingress_completion_email_hmac` | `bytea`，32 bytes | 邮箱精确匹配 HMAC |
| `ingress_completion_source_code` | `varchar(64)` | 静态注册的来源代码 |
| `ingress_completion_source_summary_ciphertext` | `bytea` | 最小必要的加密来源说明 |
| `ingress_completed_by_appointment_id` | `uuid` | 同租户准确执行 Appointment |
| `ingress_completed_at` | `timestamptz(6)` | 完成时间 |
| `ingress_completion_digest` | `bytea`，32 bytes | 规范化完整槽摘要 |

约束固定如下：

- phone 密文与 HMAC 同时存在或同时为空；email 同理。
- 整槽只能全空，或在四个原始 `captured_phone_*` / `captured_email_*` 字段均为空时，至少写入一组联系方式，并同时写齐来源代码、来源说明密文、完成 Appointment、完成时间和完成摘要。
- `ingress_completed_by_appointment_id` 使用 `(tenant_id, ingress_completed_by_appointment_id)` 到 `identity.appointment(tenant_id, appointment_id)` 的复合外键；授权、有效期、准确 Task Owner 与 subject revision 仍由 LeadRuntime/CommandRuntime 在提交前重验。
- 九列全部加入 `lead.lead` 的受控更新白名单和 write-once 集合。创建 Lead 时九列必须全空；之后只允许在一次 CAS 更新中由全空变为完整槽。专用 OLD/NEW 槽级 trigger 在首次完成后封存整槽，因此 email-only 后补写 phone、phone-only 后补写 email、覆盖或清空都被拒绝。
- 原始 `captured_*` 字段继续不可更新。补全错误的后续纠正不得覆盖本槽；如确需纠错，必须由新的版本 ADR 引入准确的追加 Fact。

## Evolution boundary

V001–V840 是已发布的 v1 字节基线，文件名、顺序和 SHA-256 均不得变化。合同源分别暴露冻结的 `BASE_SCHEMAS` 和应用 append-only Evolution 后的当前 `SCHEMAS`：旧迁移只从 `BASE_SCHEMAS` 渲染，V850 只渲染上述增量。任何 Evolution 改变旧 19 个迁移字节都会使测试失败。

V850 还必须：

- 重建 `trg_lead__mutation_guard` 和 Lead 初始空槽 trigger；最终 mutation guard 数仍为 53。
- 只向 `${app_command_role}` 增加九列的列级 `UPDATE`。V830 的 Query 表级 `SELECT` 必须在 V850 撤销，并仅按列重授 v1 原有 Lead 字段，避免新增密文/HMAC/来源字段被隐式纳入读取能力；worker、query 与 audit capability 均不得扩张。
- 以旧合同版本为比较条件，将 `platform_meta.deployment_state.schema_contract_version` 推进到 `52-plus-2-v1.1`。
- 在迁移内验证 54 张物理表、207 个复合外键和 53 个 mutation guard。

## Command boundary

数据库只能证明槽形、同租户 FK、一次写和列权限。`CompleteLeadIngress` 还必须在同一短事务内锁定准确 Lead/Task 根并重验：原始 phone/email 为空、槽为空、Task 是准确 Owner 的 OPEN `COMPLETE_LEAD_INGRESS`、Task/Lead revision 与请求前置条件一致。成功分支由服务器生成密文、HMAC、摘要和完成时间，CAS 增加 Lead revision，并把更新后的 `lead.lead` revision 绑定为 Task 完成 Fact；Task、后继责任、Audit、Event/Outbox 与 Receipt 原子提交。

## Consequences

- 字段合同版本变为 `52-plus-2-v1.1`，迁移数变为 20，最高版本为 850，外键数变为 207；表数、schema 数和 mutation guard 数不变。
- v1 PostgreSQL 18 证据继续保留且只证明 v1。只有 v1.1 在两套全新 PostgreSQL 18 空库完成 migrate、strict validate、info、结构/权限/guard 检查、run A no-op 和 fail-closed 探针并形成持久证据后，数据库门禁才完成。
- 在 v1.1 运行证据入账前，不得开始 R1 生产脚手架或把 R1 台账行提前标记为已实现。
