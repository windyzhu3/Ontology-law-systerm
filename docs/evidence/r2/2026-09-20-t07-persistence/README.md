# T07 持久化增量验收（非 T07 整体验收）

2026-09-20。不改产品页面，不部署本机审阅系统，保持 K2 冻结设计。

## 实际实现

- V940 / 52-plus-2-r2-v8：quote_draft 加密不可变草稿、quote_package_basis 既有报价版本的一对一受保护依据；不改 V001–V930，不新建第二报价身份。
- QuoteDraftService：保存、读取、当前草稿；检查事务、同租户、当前责任/客户确认/直接前版。保存不完成或唤醒普通跟进任务。
- 完整报价包同事务约束，以及范围/计价/付款/参与方集合的形成事务冻结守卫。
- 新增报价专用加密 AAD；历史正文不可修改；当前草稿按版本链末端选择。

## 证据

`./mvnw.cmd -q -f backend/pom.xml -Pit -Dit.test=R2QuoteDraftIT -Dtest=QuotePackageTest,QuoteReplyTest,QuoteProtectionTest,R1WorkerDeploymentTest,OpportunityProgressInputTest,CustomerRequirementDocumentTest -Dcodegen.skip=true verify`

退出码 0，26项单元测试 + 19项 PostgreSQL 18 集成测试通过。集成测试包含继承的 T05 回归和5项新增报价测试，不能把19项全称作新增测试。实际验证加密/跨租户读取、WAITING不变、过期草稿、事务回滚、不可修改、客户确认换版、时钟回拨、形成后禁止追加范围/计价/付款/参与方。

数据库77项生成器测试、确定性生成检查通过；29项迁移和45个 PL/pgSQL 函数解析通过。新增2项精确后继基线测试通过；历史投影证明原 V930 manifest hash不变；基线一致性和R2开发准入PASS。R1仍暂停，发布验收未授予。

## 审查与修复

独立审查发现子项可后续追加、时钟排序错误选择草稿head。已先复现失败，再以顶层事务冻结集合和无后继head修复；回归通过。补查参与方同类漏洞，亦以失败测试复现并修复。形成顺序必须为报价头→受保护依据→子项，提交前复验完整包。

## 仍未完成

准确报价形成服务/命令、草稿HTTP权限与回执、审批与交付/回复待办、报价文件、K2接API、全链路验收。QuoteDraftService为内部Owner端口，授权与命令回执仍由后续API/CommandRuntime接入；不可直接暴露为无权限接口。V940仅在临时测试数据库验证，未应用到用户审阅环境。
