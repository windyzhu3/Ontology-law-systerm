# R2 商机到期恢复正式命令 V1

本实现属于既定后台责任接续范围，不新增用户页面或输入字段。

## 命令边界

REOPEN_DUE_OPPORTUNITY_TASKS 仅接受 SERVICE_ACTOR，使用 OPPORTUNITY_TASK_RECOVER / R2_OPPORTUNITY_SYSTEM 的当前直接服务授权。原 R1 两种恢复命令与 R1_REOPEN_SCOPE_V1 保持，新命令使用独立 R2_OPPORTUNITY_RECOVERY_SCOPE_V1，绑定准确任务、商机、等待和前序进展引用。

封闭正文仅有 opportunityId、expectedOpportunityRevision、taskId、expectedTaskRevision、waitReceiptId、waitReceiptHash、progressId、progressHash、dueCutoff。日期须精确到微秒以内；附加业务分类、任意字段或浏览器任务/草稿头不能进入该命令。授权策略独立核对正文与已解析的任务 / 商机修订绑定，避免用不同正文套用已有授权上下文。

候选适配器保留 R2OpportunityDiscoveryService 返回的稳定命令键并构造准确正文，不执行写入。发现与命令执行保持显式分离；测试已从实际候选调用正式运行时恢复并重放。

## 事务及恢复

沿用 CommandRuntime 的查询授权、业务栅栏、商机/任务根锁、命令键锁、当前授权复核、任务恢复、事件、审计、回执及提交确认。

新命令键先检查准确 WAITING 状态、当前未关闭商机、最新等待 ID / 摘要 / 修订、R2_OPPORTUNITY_FOLLOWUP_V1、数据库时间已到期，以及前序 DONE 任务的准确完成进展。失败不占用命令键。执行阶段再由上一批受保护恢复能力核验加密进展及约定时间；只恢复原任务，不创建额外责任或重设 SLA。

服务权限和当前人类负责人的 SALES_OPPORTUNITY_OWNER 权限均在执行及重放时复核，商机、任务、来源线索、等待及进展对象的 DENY 生效。相同键、相同正文返回原回执，不重复写入；同键不同正文返回冲突；服务或负责人撤权后不能取得旧结果。提交前响应投影失败会整体回滚，可使用原键重试。

成功仅登记 OpportunityTaskReopenedV1，source 为恢复后 Task revision，队列为 R2_PROJECTION。R1EventPolicy 的 R1 19 条成功分支及原事件队列保持。新增分支校验实际 WAITING → OPEN、仅一次修订、原主体/负责人/SLA/草稿不变，以及同一最新等待记录。

## 尚未开放的生产入口

此命令已经注册在组合业务运行时，尚未添加内部 HTTP / mTLS 传输契约或 Worker 派发，也没有新增面向人类的 GET 回执恢复类型。后台服务通过相同命令键重放取得正式回执；这与工作台的人类回执恢复入口不同。

首次激活的正式命令及回执已在第二十一批实现；接下来将两种命令接入内部传输和 Worker 重试，验证生产自动接续。R1 PAUSED / R2 NOT_GRANTED 保持，不能把当前正式命令能力记为整个销售 MVP 已完成。


第二十二批接线状态：三条具名内部 mTLS 接口及封闭 Worker 客户端已接入，详见 [R2 内部传输 V1](r2-opportunity-internal-transport-v1.md)。尚未注册周期派发 / 消费循环；历史分页检查点及负责人异常处理仍待交付。早期批次中的接口待实现描述保留为当时状态，不代表当前传输缺失。
