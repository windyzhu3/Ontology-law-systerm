# ADR-0010：Lead补全联系方式四列QUERY读取能力

日期：2026-09-06。状态：ACCEPTED；用户回复“确认”批准[有限规格](../superpowers/specs/2026-09-06-r1-ingress-query-capability-design.md)。

基线后继为`MVP-2026-09-06.2`，数据库能力合同后继为`52-plus-2-v1.2`。追加`V860__lead_ingress_query_read_capability.sql`，仅向既有`${app_query_role}`授予`lead.lead`以下列级SELECT，均无GRANT OPTION：

- `ingress_completion_phone_hmac`
- `ingress_completion_email_hmac`
- `ingress_completion_phone_ciphertext`
- `ingress_completion_email_ciphertext`

继续禁止读取`ingress_completion_source_code`、`ingress_completion_source_summary_ciphertext`、`ingress_completed_by_appointment_id`、`ingress_completed_at`、`ingress_completion_digest`及`SELECT *`。QUERY无INSERT/UPDATE/DELETE/TRUNCATE或新增函数执行能力。COMMAND、WORKER、AUDIT、PUBLIC和登录角色的权限及成员关系不变；API保持NOINHERIT，只经既有能力执行器进入QUERY；Worker不能进入QUERY。

这是数据库只读能力的明确扩大。密文与HMAC仍是敏感数据，不能直接进入HTTP、日志或纯Query结果。业务披露仍要求Lead Owner显式列读取、准确Tenant/字段AAD、逐来源授权及审计提交后响应；这些消费者属于原闭环Task 5。

## 具名supersession

本ADR只替代原闭环规格、计划及活动数据库合同中“QUERY拒绝全部补全槽列”“固定52-plus-2-v1.1”及“不得修改物理合同”与本四列例外直接矛盾的部分。其他字段、权限、业务模型、模块DAG、Task/命令代码、公共DTO、15个OpenAPI operation和事件Schema均维持原合同。

V001–V850字节及历史证据不可修改。V860不改任何表、列、约束、索引、外键、业务触发器或业务行；13 Schema、52应用表加2技术表保持不变。部署状态仅从v1.1推进至v1.2且revision加一；版本不符或最终权限不符使整个迁移事务失败，授权和状态一起回滚，无权限修复机制。

当前完整迁移数21，最大版本860，新建库deployment revision为2。manifest摘要为`a4beeb91ed93be455736eafa3abb829f6a94fed3a263be5996832e458b7c4b39`，字段合同摘要仍为`f4c17c4c0a8697820b30adb61b8cdb209666a4672393d4f8fc9d73a5f169addf`。当前CI制品使用`postgresql-runtime-ci-artifact-v1.2`；v1/v1.1按原profile解码，不能重绑新摘要。

## 验收边界

能力修订门禁覆盖新库、V850真实数据升级、四读五拒绝及写拒绝、无GRANT OPTION、旧ACL/成员关系与数据保持、故障回滚、重启checksum/no-op和现有runtime/manifest失败关闭验证。生产HTTP readiness组装仍属于原Task 8。

本地验证及独立评审记录在[本地进度](../progress/2026-09-06-r1-local-progress.md)。旧v1.1托管RUNTIME_VERIFIED不是v1.2证据；本修订不自动升级R1-BACKEND、SPA、E2E、容量或发布状态。原Task 5须在此门禁评审通过后继续；Task 6–10、推送、合并、部署和外部环境变更不属于本修订。
