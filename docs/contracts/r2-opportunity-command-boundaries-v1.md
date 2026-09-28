# R2 商机命令边界 V1

状态：内部正式命令运行时已实现并完成数据库集成测试；公开回执读取及商机草稿 / 确认 HTTP 操作已接通，工作卡与消费者尚未接通。不能据此判定销售页面的商机跟进已可用。设计依据为已批准高保真 C；本增量不增加业务范围、页面或迁移。

## 身份与恢复

RECORD_OPPORTUNITY_PROGRESS 仅接受 HUMAN / INTERNAL_TASK，主责任为 PROGRESS_OPPORTUNITY，权限槽 OPPORTUNITY_OWNER，权限 SALES_OPPORTUNITY_OWNER，草稿 schema RecordOpportunityProgressV1。

正式命令范围使用 R2_OPPORTUNITY_COMMAND_SCOPE_V1，精确绑定 tenantId、commandType、taskId 和 opportunity.opportunity 的 id / revision。拒绝用 lead.lead 代替商机；旧 R1_COMMAND_SCOPE_V1 的字段和范围算法不变。

草稿继续使用 SAVE_ACTION_DRAFT 和既有 R1_DRAFT_SCOPE_V1，只有 actionCode 明确为 RECORD_OPPORTUNITY_PROGRESS 才允许恢复元数据中的历史字段 lead 承载 Opportunity selector。其他动作仍要求 Lead selector。恢复元数据外壳沿用原版本，内部商机 scope 必须为上述命名版本；不接受额外字段、证据绑定或跨域身份。元数据读取通过不等于当前授权通过，公开恢复已接入商机 Owner 身份读取及现时权限复核。

## 事件边界

| 事件 | 来源 | 队列 |
| --- | --- | --- |
| OpportunityActionDraftSavedV1 | responsibility.action_draft / revision:post-write | R2_PROJECTION |
| OpportunityProgressRecordedV1 | opportunity.opportunity_progress / hash:protected-body | R2_PROJECTION |

R1 的 19 个成功分支和原事件描述符保持原样，商机新增两个独立分支。原 OpportunityOpened 仍进入 R1_PROJECTION。本注册不代表 R2 消费者已经实现或可启动。

进展事件校验要求原待办 OPEN → DONE、精确完成事实、同一商机与负责人、任务修订递增且原 SLA 不变；正式进展必须绑定同一商机和原任务；草稿必须从同一 DRAFT 转成 CONFIRMED 且修订递增，动作、schema 与版本一致。草稿保存事件要求任务仍 OPEN 且草稿写入成立。声明成功但缺少持久事实时拒绝。

OpportunityCommandReader 由 Opportunity Owner 读取商机、锁定根及读取受保护正式进展；查询按租户隔离，解密后重新核对 SHA-256 摘要。错误密钥或正文摘要不一致不能作为事件证据；返回对象日志不包含正文。该读取口不提供面向用户的自动披露。

## 接线剩余条件

R2OpportunityCommandRuntime 现已组合真实 CommandRuntime、R1 原处理器和商机处理器，并组合商机授权及受保护事件事实读取。草稿保存按 actionCode 精确分流，R1 动作仍交给原 ActionDraftCommands。内部运行时支持保存草稿 → 精确确认 → 正式进展 / 原任务完成 / 独立后继责任原子提交 → 审计与回执 → 同键重放和失败回滚。配置商机保护口的生产 R1CommandService 已切换到这个组合，兼容构造仍支持原 R1 装配。

商机草稿 values 仅接受 progressTypeCode、progressSummary、occurredAt、nextCheckAt；正文按 Owner 规则标准化，时间精确到微秒。正式确认额外要求 draftId、expectedDraftRevision、draftDigest 及准确 Task ETag。确认内容与已保存草稿不一致、过时任务/草稿、无当前权限、非本人操作、商机已关闭或计划跟进时间已过均不能推进。实际发生时间早于商机形成时间时，同事务撤销此前草稿确认。

重复的命令键及相同正文返回原回执；同键不同正文冲突，不追加进展。撤权后不披露历史成功结果。响应投影在事务提交前失败时，草稿确认、进展、后继任务、审计、事件及回执一起回滚，同键仍可重试。R2 事件仅写独立队列，空事件载荷不复制进展正文。

公开回执投影及商机恢复查询已按 R2_OPPORTUNITY_RECEIPT_V1 接入现有 GET 路由；无需进展正文解密密钥。商机提交接口及草稿响应已按 R2_OPPORTUNITY_SUBMIT_V1 复用这些处理器；下一步接工作卡和我的待办，不绕过 CommandRuntime。

随后按批准设计接 HTTP 契约、草稿返回、单卡和我的待办；生产可靠激活与到期唤醒完成后才发放可办理商机卡。报价、合同及转案规则继续遵守原批准范围。R1 验收暂停；本增量不授予 R2 发布验收。
