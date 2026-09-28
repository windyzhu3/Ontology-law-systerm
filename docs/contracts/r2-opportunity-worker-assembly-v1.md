# R2 正常商机周期总装 V1（T02）

状态：IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED；运行默认关闭。承接已批准T02，不新增页面或业务流程。

## 组合边界

`ols.worker.opportunity-task-scheduling-enabled=false` 为默认。显式true时只注册INITIAL与DUE，使用数据库持久检查点和现有mTLS具名HTTP。`ols.worker.owner-exception-observation-enabled` 是T01独立开关；开启正常周期不隐式开启异常观察，反之亦然。

仅支持含既定检查点的准确Schema版本 `52-plus-2-r2-v3` / `52-plus-2-r2-v4`；实际运行仍需数据库ACTIVE和准确release/manifest/capability验证。配置通过不等于数据库准入通过。

INITIAL使用当前SERVICE的OPPORTUNITY_TASK_ACTIVATE，DUE使用OPPORTUNITY_TASK_RECOVER。生产SERVICE与这些权限经原受控配置路径管理，不新增授权界面或自动grant。两种扫描均使用当前有效责任、准确来源、对象DENY和正式命令复验。

## 业务连续性

扫描已存在的历史商机及后续新商机，不依赖R1投影消费位置。有效联系形成商机后，合格销售取得唯一首次待办；已有任何初始任务状态不能重复激活。提交真实进展形成等待，只有约定到期后恢复同一合法等待任务，保留期限和进展引用。负责人异常仍由T01发现及处置，不自动转交或授予权限。

## 可靠性与健康

每种扫描使用独立检查点会话锁；请求发出前持久化原键原输入，响应未知时优先恢复，不重新生成任务。复用既有codec/有界分页/重试，不新增队列或业务SQL权限。

运行健康报告正常周期是否启用及INITIAL/DUE各自状态。启用后任一循环未启动、失去授权、存储不可用或降级，整体不报告健康；未启用时不拖累原R1/T01健康。停止时关闭两种扫描，保留检查点供新Worker实例恢复。

## 验收与启用

T02验收必须通过真实打包API、TLS和隔离数据库的首次卡→进展→等待到期恢复→重启不重复闭环，并检查撤权以及T01独立开关。只覆盖组合变化及相关回归；E/F界面未变，不以新原型或假成功代替后端接线。

开发验收完成后，生产启用仍属于既有发布门禁。R1 PAUSED、R2 NOT_GRANTED不变。记录见[实施计划](../superpowers/plans/2026-09-15-r2-t02-opportunity-worker.md)。
