# F06 合同结束与主管处置验证记录

状态：2026-09-26 完成本批 F06 验证与本地部署；不代表 R2 发布验收通过。

## 实现范围

未有签署等独立事实时，销售可说明原因结束本次办理。有签署、待核验签字件、收款、执行或转案事实时，提交独立授权主管核对。主管选择停止或继续；停止办理不等同于解除合同。

准确取消本销售链上的责任，保留签署、收款、执行、转案事实及独立责任。继续办理生成同目的、原负责人、原 SLA 的后继，不重开已取消任务。没有合格主管时保留可见的待协调状态；授权恢复后由已有 Worker 接续。撤销 CONTRACT_READ 后不能继续提交或读取原回执。

## 已观察到的验证

日志位于 `.superpowers/sdd/2026-09-23-r2-sales-chain-closure-repair/`。

| 验证 | 已观察结果 |
| --- | --- |
| `f06-contract-final-targeted.log` | 35 IT 通过：24 业务处置、6 实际运行时、4 真实锁竞争、1 HTTP；Architecture 通过 |
| `f06-contract-read-revocation-red.log` | 3 个撤权问题先复现，后在上述运行中通过 |
| `f06-contract-ui-final-green.log` | 156 个前端测试通过，包含新回执、拒绝回执和恢复语义 |
| `f06-independent-payment-green.log` | 4 个真实数据库并发测试及 Architecture 通过；停止后收款仍允许 |
| schema/transport | 7 项通过；生成检查及开发基线通过 |

`f06-transfer-red3.log` 先复现转案读取遗漏和无锁等待，`f06-independent-facts-green.log` 的 6 项独立事实 IT 及 13 项 Architecture 随后通过。转案更新测试在观察到锁等待后进入本领域外键校验并回滚，不宣称转案接收成功。其测试数据是隔离数据库内的合成已有下游事实，不表示 R2 执行协议已开放，也不代替 F10/F11 的端到端验收。

## 界面验证边界

沿用用户确认的 O 高保真与共享卡片，无新增 CSS。实际 React 组件配合合成传输分别检查 1440、390、360 宽度；无横向溢出，一项主操作。截图包括 `end-1440.png`、`end-390.png`、`stopped-390.png`、`review-1440.png`、`review-390.png`、`review-360.png`、`unassigned-360.png`、`request-360.png`、`review-stopped-360.png`。

上述浏览器预览验证结束、申请、待协调、主管停止和只读结果；不宣称浏览器完成“继续办理”。继续办理由后端真实事务测试覆盖。

R1 PAUSED / R2 NOT_GRANTED 保持；未提交或推送。

## 最终回归与本地启用

`f06-contract-post-transfer-regression.log` 再次通过 35 IT；`f06-contract-transfer-baseline.log` 开发基线通过，原有 7 项发布阻断保持。SPA 构建、生成检查、7 项 schema/transport 检查通过。只读修复复核未发现新增 Important/Critical 阻断问题。

本地启用 v17/V1030，完整备份目录 `.superpowers/r2-review-runtime/before-closure-20260926-182018`。入口 `https://localhost:19444`，原账号 `task9-local-contact`。

`f06-contract-runtime.json` 只读核对 C06/C26/C30/C32/C39/C45，未更改这些案例。C30/C32/C39 提供未签结束入口；已归档 C45 仅提供请求主管核对，不提供未签直接结束入口；没有同时出现两类互斥动作。F07～F12 及 R2 其他管理项继续按原范围推进。
