# R1 Workbench 呈现合同

Contract ID: R1-WORKBENCH-V1

Status: FROZEN

确认日期：2026-09-02

R1 只交付一份响应式 Workbench。身份管理 route mode 与 Workbench 位于同一 SPA，但身份管理生产能力不属于 R1 纵切实现范围。

## Envelope fields

| Field | Cardinality | Contract |
|---|---|---|
| todaySummary | 1 | 一句安全、可本地化的今日摘要；没有工作时仍返回零态句子 |
| currentCard | 0..1 | 唯一可展开完整业务内容与动作区的当前责任卡 |
| nextSummaries | 0..2 | 只含下一责任摘要、优先级和安全时间提示，不含完整 payload 或动作 |
| waitingCount | 1 | 当前 Actor 可见 WAITING Task 的非负计数 |
| chatComposer | 1 | 固定底部候选输入区；只生成或修改 ActionDraft，不自行完成 Task |

`currentCard` 缺失时必须显示明确零态，不能用 `nextSummaries` 提升成第二张完整卡。任何普通销售 Workbench 响应最多暴露一张完整卡和两条摘要。

## Route modes

| RouteMode | PathPattern | Navigation | Sidebar |
|---|---|---|---|
| WORKBENCH | /workbench | NONE | NONE |
| IDENTITY_ADMIN | /admin/identity/* | IDENTITY_ONLY | LEFT |

Workbench 普通路径不显示全局菜单或左侧栏；操作流围绕当前卡和固定 composer。身份管理只有在受保护的 `IDENTITY_ADMIN` route mode 中显示身份专用导航和左侧栏，不能把管理入口混入普通销售页面。

## Presentation and interaction invariants

- 首屏读取 `getCurrentWorkCard` envelope；Workbench ETag 只缓存整份投影，不替代 Task、Draft 或 subject 的并发条件。
- DTO必须携带taskType与revision供合同分派和并发控制；卡片只向普通用户显示本地化业务目的、安全主体摘要、Owner、SLA、安全版本状态和允许动作，不直接渲染原始Task/Command/Event/Decision/hash代码、Repository或不可见Tenant信息。
- composer 的候选动作先保存为 Draft；用户显式确认后才调用对应 command。网络重试保持原幂等键；stale 或需要刷新后改正的拒绝按 HTTP 合同生成新键。
- 写入成功后以 Receipt 为准刷新 envelope；同 key replay 不重复乐观插入卡、消息或计数。
- 错误显示只消费 HTTP 合同的安全 Problem Details。403/404 不推断对象是否存在；412/428 明确提示刷新或补齐前置条件。
- 键盘顺序固定为摘要、当前卡、动作、composer；焦点在刷新后回到相同逻辑控件。状态不能只靠颜色表达，动态摘要和错误使用合适的 live region，并尊重 reduced motion。
- 手机、平板和桌面共享相同信息层级；响应式变化不得引入第二套路由、第二份合同或另一 SPA。

## R1 boundary

R1 只实现 `/workbench` 及其 P0-01 至 P0-04、联系和有效性复核卡。`/admin/identity/*` 的 route mode、导航隔离和权限边界在脚手架中保留，但身份管理生产页面、CRUD 和独立验收属于后续交付，不能计入 R1 完成证据。
