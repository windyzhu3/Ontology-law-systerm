# ADR-0007：R1 命令授权与事件合同收口

日期：2026-09-05。状态：ACCEPTED。语义基线：MVP-2026-09-05.2。

## Decision

采用 [R1 Command Policy and Event Contract](../contracts/r1/R1-COMMAND-POLICY-EVENT-CONTRACT.md)作为 CAPTURE_LEAD、SAVE_ACTION_DRAFT、REOPEN_DUE_CONTACT_TASKS、REOPEN_DUE_ROUTING_REVIEW_TASKS 专属授权，以及全部 R1 成功分支事件集合的唯一静态权威。共享 version-1 通知 payload Schema 固定为 [`r1-domain-notification-v1.schema.json`](../../contracts/events/r1-domain-notification-v1.schema.json)，唯一合法实例是空对象。Markdown 表由 `validate_r1_command_contract(root)`机械验证并接入 baseline verifier；不创建第二份可独立漂移的 JSON 事件注册表。

四个命令的服务端静态策略、七种 Draft TaskType→主权限映射、两类 recovery 的专属 SYSTEM Grant、真实 Owner 组织 scope、对象 DENY 和最终持锁复验按合同冻结。调用方不能选择 policy、authority、组织、Grant、事件或 QueueOwner。该决定保留 ADR-0006 的同连接 READ COMMITTED、Tenant identity shared lock、savepoint、重放/冲突、提交确认丢失和审计规则。

所有 R1 通知显式冻结 event type、schema version、source type/selector、QueueOwner 和成功分支精确集合。CONTACT_CONNECTED_VALID 必须且只产生 LeadContactResultRecordedV1 与 OpportunityOpened，故同事务为 Event +2、R1_PROJECTION Outbox +2；唯一 Receipt 仍引用 ContactResult。一般基数为 EventCount=分支通知集合基数，OutboxCount=各事件静态 QueueOwner 数量之和，不能把单事件分支经验写成通用规则。

R1_PROJECTION 对可变事实只重读当前状态并防止延迟、重复、乱序事件倒退投影。OpportunityOpened 永久保留以供 R2 以后覆盖历史与并发新增事件；R2 启用前另行冻结具名消费者和 Tenant + Opportunity + 首个推进责任类型的幂等边界。本轮不增加 R2 QueueOwner、Outbox、Task、管理 CRUD 或通用回放平台。

## Supersession and compatibility

本 ADR 只明确替代 ADR-0006 中“上述四类命令的生产 authority policy、其 SUCCEEDED 事件映射以及 OpportunityOpened 描述仍未冻结”的四项缺口。ADR-0006 其余授权时点、锁顺序、Receipt、失败差额和提交语义保持 ACCEPTED，不重写为历史。Task 完成矩阵的 CONNECTED_VALID 事件集合及 E2E 计数按本 ADR 修正；其他原有 receipt、失败和单事件分支规则不变。

## Delivery boundary

该决定推进语义合同至 MVP-2026-09-05.2，但不把 R1-BACKEND、R1-SPA、R1-E2E-GOLDEN 或 R1-E2E-FAILURES 标为已实现/已验证。生产 Handler、真实授权执行、原子多事件持久化及业务联调仍须分别提供代码和运行证据。52-plus-2-v1.1、13 Schema、54 张受管表、V001–V850 字节、13 个 HTTP operation、单 Jar/单 SPA 拓扑、依赖版本和模块 DAG 均不变。
