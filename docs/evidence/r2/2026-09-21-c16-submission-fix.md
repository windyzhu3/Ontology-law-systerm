# C16 联系结果提交停留：原因、修复与横向检查

## 已确认原因

真实 task9-local-contact / 页面验收组请求保存 C16 联系草稿返回 HTTP 412 STALE_TASK。并非“联系已提交成功但未跳转”：草稿阶段就被拒绝，联系事实没有写入。API 返回的 taskETag 未变化，反复刷新也不能解决。

原本地合成数据脚本 `visual-cases/duplicate-history-fixture.sql` 为构造重复候选历史，将 C16 的 parsed_party_id / party_resolution_code 更新并把 lead revision 从 1 增至 2，却保留绑定 lead revision 1 的 CONTACT_LEAD 任务。业务命令的准确版本检查正确拒绝了该任务。共用查询却仍将 primaryCommand.enabled 返回 true，页面显示“刷新后核对”，造成反复填写、提交和刷新。

## 修复

- 不放宽草稿、命令或数据库的准确版本校验，不修改任何冻结页面布局。
- R1 共用查询在任务依据与当前线索版本不一致时禁用办理和草稿；已有安全投影可隐藏不再成立的事项。
- 前端 7 类 R1 工作卡独立检查 versionStatus，禁用编辑和提交，并明确提示联系管理员核对责任依据，避免承诺刷新可自动修复。
- 仅修复本地 C16 合成数据。旧任务保留为 CANCELLED，取消原因为 LOCAL_SYNTHETIC_C16_BASIS_REPAIR；新任务绑定准确版本 2，原负责人和原 SLA 完整保留。未改写旧任务不可变字段、未删除历史、未停用触发器。
- 修复 SQL 已先执行 ROLLBACK 演练；正式执行前完整备份数据库及案例 journal，位置 `.superpowers/r2-review-runtime/before-c16-repair-20260921-223103`。原有错误夹具脚本封存并禁止再次执行。

## 实际业务验证

修复后读取 CURRENT；真实草稿保存 SUCCEEDED，记录联系结果 SUCCEEDED。新联系任务已从 currentCard 和 myTasks 移除。验收采用明确的本地合成联系说明，不代表真实客户联系。

另发现已有 INITIAL worker 处于 503 退避，导致商机后继待办延迟。通过既有 SERVICE 候选与 activate-initial 接口，以原确定幂等键补消费 C16 一条来源，回执 SUCCEEDED；未重置 checkpoint，未直接写入成功事实。之后 myTasks 中 C16 的事项为“推进客户委托”。该运行延迟与 C16 旧版本错位分别记录，不能声称全局 worker 健康已修复。

## 横向检查边界

全库 OPEN / WAITING 的 R1 线索任务与当前 lead revision 比较：修复前只有 C16 一条错位，修复后 0 条。不是所有页面都存在本次数据问题，但 7 类 R1 工作卡共享了“版本不一致仍可提交”的显示缺陷，已统一防护。

前端全量 70 文件 / 802 项通过，包含新增 7 类卡片回归和既有提交后继、回执恢复、主动切换及 R2 商机/客户/材料/报价/合同组件测试；类型检查与验收配置构建通过。后端 35 项架构/接口测试、12 项真实数据库 IT 通过（CurrentWorkCardIT 7、ContactChainIT 4、ActionDraftConfirmationIT 1），BUILD SUCCESS。新增的七类型后端检查在一个循环用例内，不额外累加为 7 个测试。

修复包于 2026-09-21 22:39 部署；部署备份 `.superpowers/r2-review-runtime/before-t08-20260921-223910`。本次只涉及共用投影、卡片交互防护及本地合成数据修复，没有新增数据库迁移或扩大功能范围。

部署后真实认证读取再次确认 C16 待办为“推进客户委托”，全库 R1 活跃任务版本错位为 0；当前 baseline consistency / R2 development admission PASS。证据分别为 `.local/c16-deployed-verification.log`、`.local/c16-baseline.log`。

浏览器控制连接两次失败，因此本轮使用真实认证 HTTP、数据库只读核对和组件集成回归；不宣称完成浏览器点击实走。日志：`.local/c16-*.log`；精确修复脚本 `output/repair-c16-synthetic.sql`。R1 PAUSED / R2 NOT_GRANTED 保持。
