# F06 报价结束办理切片验收

2026-09-25；工作树 `codex/r2-sales-mvp`。本记录只接受 F06 的报价部分，合同终止与主管核对仍在实施。

## 结果与边界

- 新增具名 `END_QUOTE_NEGOTIATION`，事务内保存结束原因、准确报价/责任版本及取消明细，沿用商机合法终点。取消不计为有效进展或任务完成。
- 覆盖准备、提交审批、待审批、退回、交付、待回复、继续跟进、澄清、拒绝处置、已接受但尚未由合同领域接管。已进入合同不能由报价入口结束。
- 多审批人全部准确取消；等待记录、批准、交付、回复和下载历史保留。旧页面、审批竞争、失权、重复提交及原回执均受保护。
- 复用 O 高保真、共享卡片与按钮，无新 CSS。小屏隐藏左侧说明的既有规则会漏掉结束提示，已将只读说明放入主要内容区，并以“查看报价历史”替代旧活动标题。

## 本轮证据

执行日志位于 `.superpowers/sdd/2026-09-23-r2-sales-chain-closure-repair/`。

| 验证 | 结果 |
| --- | --- |
| 后端组合回归 `f06-quote-regression.log` | 74 个 IT，73 通过；1 个撤权断言错误 |
| 修正断言后的 `f06-quote-final-targeted.log` | 1 通过；撤权在 resolve 拒绝，原回执返回 403；74 个不同案例全部得到通过结果 |
| 实际 HTTP / 两种先后顺序锁竞争 | 1 / 2 通过；观察到真实 PostgreSQL 锁等待后才放行 |
| ArchitectureTest | 13 通过 |
| 前端组合 `f06-ui-regression.log` | 5 文件、86 用例通过 |
| 小屏终态回归 | 新增失败复现后，`f06-terminal-ui-green.log` 全部 30 个 QuoteCard 用例通过 |
| schema/transport 基线测试 | 12 + 3 通过；历史投影不变 |
| 生成检查、类型检查、SPA 构建、开发基线 | 通过；既有 7 项发布阻断保留 |

浏览器使用实际 React 组件与合成传输验收表单及只读结果；并不代替真实 HTTP/数据库验证。1440/390/360 均无横向溢出，表单只有一个主按钮；未填写原因或未勾选核对不能提交。截图：[1440](quote-termination-1440.png)、[390](quote-termination-390.png)、[360](quote-termination-360.png)、[结束结果](quote-ended-360.png)。

## 本地启用

已备份并部署 v16/V1020，备份目录 `.superpowers/r2-review-runtime/before-closure-20260925-114400`。入口仍为 `https://localhost:19444`，账号仍为 `task9-local-contact` 的原验收任职。

只读核对 C06、C26、C30、C32、C39、C45：C26 有报价结束入口；合同已接管案例没有该入口。没有修改原案例业务数据。记录见 `f06-quote-runtime.json`。

R1 PAUSED / R2 NOT_GRANTED 不变；未提交、推送或执行 F08 存量修复。合同、来源请求后继和签后执行/转案尚不能按本切片宣称完成。
