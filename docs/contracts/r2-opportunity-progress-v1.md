# R2 商机进展与后继责任 V1

Scope: 已批准 R2 销售 MVP；高保真 C 于 2026-09-14 获用户确认。
Implementation: Owner 业务服务及数据库增量；尚未注册生产 HTTP 命令、卡片、消费者或到期 Worker。

## 本次实现

`R2OpportunityProgressServices` 组合 Opportunity 与 Responsibility 的公开端口及既有规范 JSON 编码。Opportunity 不直接访问 Responsibility Repository，也不依赖 Execution 或 Lead 模块。调用方仍负责租户业务 fence、入口认证、命令幂等回执、草稿确认、审计及最终提交；此组合不是可直接调用的用户接口。

`OpportunityProgressService.record` 必须在 READ COMMITTED 非自动提交事务中执行。锁定当前准确 Opportunity 后检查未关闭、本人 HUMAN 任职、主体 Owner、当前有效组织与 `OPPORTUNITY_OWNER / SALES_OPPORTUNITY_OWNER` 权限。身份锁保留至事务结束，写入后再次检查权限。锁定准确 OPEN 商机待办，重读注册命令、责任、Owner 和版本后才写进展。

有效类型封闭为 `PHONE_CONNECTED`、`CLIENT_VISIT`、`MEETING`、`SITE_VISIT`、`WECHAT_CONNECTED`。草稿、内部备注、未接通、未具备实际报价交付事实的 `QUOTE_SENT` 均不能作为本合同的有效进展。摘要为规范 Unicode、换行、首尾空白处理后的 1–2000 字符；控制字符和非法代理项拒绝。真实发生时间不能晚于记录时间或早于商机形成；下次跟进必须在记录时间之后，时间使用微秒精度。

正式进展是 `opportunity.opportunity_progress` 不可变事实。规范正文包含命名 profile、租户、商机、进展身份、完成的任务身份、记录任职、记录时间及有效进展值；既有 `progress_digest` 在本具名合同下覆盖该完整规范正文。AES-GCM 的 AAD 由命名 profile 和固定长度租户/商机/进展 UUID 组成，禁止把一个正文移到其他租户、商机或进展。正文不放入审计摘要、事件或普通 `toString`。旧进展的摘要语义和无正文记录保持原样。

一次成功服务调用在同一事务内追加进展、以准确进展 selector 完成当前任务、创建新任务并进入 WAITING。`predecessor_task_occurrence_id` 保存不可变因果关系，租户内唯一约束保证每个前序最多一个后继，复合外键保持同租户。初始任务仅匹配此前序为空的全部状态，跟进多轮后仍返回最初身份。后继保留 Owner 和准确商机，SLA 从计划跟进时间按既有业务日历计算；不修改旧任务 SLA。

等待原因 `OPPORTUNITY_FOLLOWUP`、等待合同 `R2_OPPORTUNITY_FOLLOWUP_V1`、版本 1。等待回执保存真实恢复时间，不把已经完成的进展填成“等待未来发生的事实”。服务重试使用旧任务版本会拒绝追加；端到端同命令重放返回原回执仍需后续命令 Runtime 接线。专用后继工厂重入返回同一个任务与等待时间，不能用新日期覆写原计划。

## 数据增量与兼容

V880 命名版本 `52-plus-2-r2-v2` 仅追加进展正文密文和任务前序关系、相应约束与列读取授权。V001–V870 原始字节不变。历史 R2 V1 清单先被精确重建，再按已有规则投影回 R1 V1.2；只有显式 R2 开发校验接受新版本。运行时版本与 release/manifest 摘要仍必须精确匹配，旧配置不会自动接受新结构。

## 下一接线要求

1. 注册准确草稿 Schema、主命令、错误/回执事实、事件合同、当前卡与授权查询；正式读取需验证进展摘要和加密上下文后，经披露授权及审计返回，不直接公开数据库正文。
2. R1 `OpportunityOpened` 保持既有队列含义；新增可靠承接与有界历史检查点。Owner 失效、主体关闭等必须进入持久、可查看的异常与恢复路径。
3. 到期唤醒必须由准确服务权限、等待回执和当前责任共同判定；应用不能依靠用户刷新或测试中的直接 `reopen`。本次多轮跟进测试在明确标注的可信调度边界模拟唤醒，不是生产调度完成证据。
4. 完成单卡与台账读写接线后，才可启用生产商机责任。报价、直接授权、合同签署与转案仍按已确认计划逐片实现，不用本次领域测试标记整个销售流程完成。

R1 验收继续 PAUSED，R2 发布验收未授予。
