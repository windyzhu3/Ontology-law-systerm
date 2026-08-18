# Ontology Law System

待办驱动律所管理系统的产品架构、本体与销售 MVP 设计仓库。

当前阶段优先完成设计，不进入实施计划。系统以销售至转案闭环作为 MVP，并为后续案管登记、案件分配及综法/非诉等案件办理能力保留稳定扩展契约。

## 阅读顺序

1. [总体架构与本体完整设计](docs/specs/2026-08-18-law-firm-overall-architecture-ontology-design.md)  
   当前总体设计入口，统一范围、模块边界、本体、责任、时效、安全与扩展契约。
2. [最小 Matter 身份与后 MVP 扩展契约 v1.0](docs/specs/2026-08-18-minimal-matter-identity-post-mvp-extension-contract-v1.0.md)  
   已冻结方案 B：最小 Matter 身份 + 不可变 TransferSnapshot 采纳引用。
3. [目标产品基线 v2.0](docs/specs/2026-08-17-law-firm-ontology-todo-chatbot-design.md)  
   六个销售至转案业务聚合、最小责任内核与一卡多态交互基线。
4. [销售 MVP 工作卡与对话状态设计 v1.0](docs/specs/2026-08-17-law-firm-sales-mvp-workcard-dialogue-design.md)  
   销售、主管、财务、行政、风险和案管等角色的具体工作卡与对话状态。

## 当前冻结边界

- 六个业务聚合只限定销售至转案上下文，不限制整个律所系统未来聚合数量。
- 内部统一 Chat 工作台，客户使用轻量安全入口。
- 领域边界模块化单体 + 最小共享内核。
- 逻辑多租户，初期单律所部署。
- MVP 止于转案接收并原子创建正式最小 Matter。
- Matter 创建后不在 MVP 内生成登记、分案或案件办理任务。
- AI 只负责提取、草拟、解释和分类建议，不拥有审批、核验、付款、签章、接收或分案权力。

## 文档状态

设计基线更新日期：2026-08-18。
