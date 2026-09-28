# R2 商机首次激活正式命令 V1

状态：正式命令及内部候选联通已实现并验证；生产后台派发尚未接通。本合同属于既定商机责任接续，不新增页面、用户输入字段或业务范围。

## 业务边界

R1 有效联系已经生成的商机，通过受限 INITIAL 候选发现进入正式后台命令。新商机和历史商机采用同一入口，不补造联系、分配或完成事实，不重置原 R1 Outbox 消费位置。候选保留稳定命令键；读取候选和工作卡均不隐式创建任务。

调用方必须是当前选定的 SERVICE 任职，具有准确 OPPORTUNITY_TASK_ACTIVATE 权限；原 R1 投影权限及到期恢复权限不能替代。接收初始责任的销售负责人必须仍为有效人类任职，并具有 SALES_OPPORTUNITY_OWNER 权限。服务与负责人均受对象级 DENY 约束。

## 命令边界

ACTIVATE_INITIAL_OPPORTUNITY_TASK 仅接受 SERVICE_ACTOR。独立 R2_OPPORTUNITY_ACTIVATION_SCOPE_V1 绑定租户及准确商机；封闭正文只有 opportunityId 与 expectedOpportunityRevision。业务时区由原来源策略读取，调用方不能指定负责人、SLA、业务分类、草稿或任务头。

首次创建返回 SUCCEEDED，登记 OpportunityInitialTaskActivatedV1 到 R2_PROJECTION；准确已存在的初始事项返回 NO_CHANGE，记录回执与审计，不产生新的创建事件。服务通过同键重放取得回执，不增加面向人类的回执恢复入口。

## 准确承接与幂等

新命令执行前核对商机当前准确修订、未关闭状态、原 Lead / Assignment / CONNECTED_VALID ContactResult，以及已完成原联系责任的准确完成引用。来源、Owner 或权限不满足时不创建孤立待办，也不默认为另一销售或主管。

责任创建复用专用初始入口，按租户和商机串行检查所有状态的初始责任。已有 OPEN、WAITING、DONE 或 CANCELLED 初始事项均不能重复创建或重开，也不能重设原 SLA。真正首次创建使用既定业务日历和当前承接时间，不把历史商机创建时间当作新待办 SLA 起点。

正式运行时承担授权复核、业务栅栏、根锁、命令键、责任写入、R2 事件、审计、回执和提交。相同键与正文重放原回执；同键不同正文冲突。重放仍检查当前权限，并同时检查当前任务版本及持久化回执 resultFact 所引用的准确历史版本；服务或当前人类 Owner 对任一版本的 DENY 均阻断披露。该规则覆盖首次创建和 NO_CHANGE 回执，不能只固定检查 Task revision 0。业务状态已推进不能误触发再次创建。失败回滚可安全重试；原 R1 命令、事件分支和队列保持。

## 生产交付边界

本批只交付正式命令能力及与候选发现的组合。内部 HTTP / mTLS、Worker 派发重试、历史扫描检查点和负责人异常处理仍需接通。不能将本批记为自动激活已上线或完整销售 MVP 已完成。


第二十二批接线状态：三条具名内部 mTLS 接口及封闭 Worker 客户端已接入，详见 [R2 内部传输 V1](r2-opportunity-internal-transport-v1.md)。尚未注册周期派发 / 消费循环；历史分页检查点及负责人异常处理仍待交付。早期批次中的接口待实现描述保留为当时状态，不代表当前传输缺失。
