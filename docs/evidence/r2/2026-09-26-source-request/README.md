# F07 来源请求后的线索去向

范围：已确认的 O 高保真来源处理场景。来源负责人确认收到请求后，必须接续唯一主管责任；主管选择合格销售、约时复查或明确结束本线索。来源全局状态不变。

## 业务和协议

- 具名责任 RESOLVE_SOURCE_REQUEST，操作 RECORD_SOURCE_REQUEST_CONTINUATION。不可变 SOURCE_REQUEST_CONTINUATION 决定绑定原 ACK 事实、所选负责人或准确复查时间。
- 分配进入所选销售首联；复查形成一个等待后继，保留原 SLA；结束保存依据且不伪造首联或分配。
- REOPEN_DUE_SOURCE_REQUEST_TASKS 由现有 Worker 执行，复用 ROUTING_REVIEW_TASK_RECOVER 授权；精确核对等待回执、任务版本、线索、负责人权限。撤权不自动换人或扩大授权。
- 独立的 R2 页面、草稿和字段协议，R1 ActionCode、TaskType、FormField 未扩展；既有 ACK 操作仅补充“主管安排未解决”的失败声明。历史协议投影有逐项摘要验证。

## 已完成验证

- SourceRequestContinuationIT：10 项，确认后接续、原回执重放、无主管、结束、指定销售、失效候选、准确复查时间/原 SLA、到期恢复、非法输入、撤权与存储失败回滚。
- R2SourceRequestHttpIT：2 项，真实公开 HTTP 读取/保存/结束/回执恢复；真实 mTLS Worker 发现/撤权过滤/恢复/重放。
- DueR1TaskDiscoveryIT：8 项旧扫描回归；LeadRoutingDispositionIT 7 项、WaitLifecycleIT 22 项已通过。
- 前端五个文件 71 项通过，含条件字段、未来时间、无候选仍可结束、具名 URL、结果未知原请求恢复；TypeScript 检查通过。
- 传输历史投影 6 项通过；开发基线通过，既有 7 项发布阻断保留。

## 页面证据

实际 CurrentCard 和 ActionDraftForm 组件，沿用共用 CSS，没有新增样式或导航。合成输入和提交回调只用于 UI 验证，**不作为真实数据库写入证据**。

| 场景 | 桌面 | 窄屏 | 最窄 |
| --- | --- | --- | --- |
| 继续分配 | assign-1440.png | assign-390.png | assign-360.png |
| 约时复查 | review-1440.png | review-390.png | review-360.png |
| 明确结束 | end-1440.png | end-390.png | end-360.png |

9 个场景均无横向溢出，各保留一个确认主按钮；3 个分支均实际选择、填写并提交通过表单检查。截图已人工查看桌面与窄屏。

日志：output/f07-source-worker-http4.log、f07-source-ui-green3.log、f07-source-typecheck5.log、f07-browser-visual.log、f07-browser-interaction.log、f07-final-transport.log、f07-final-baseline.log。

最终扩展回归通过：98 项集成测试、21 项架构与配置检查（output/f07-final-backend2.log）。本地激活完成，备份 before-closure-20260926-192326；原验收账号六个案例只读检查通过，原案例未推进。未据此授予 R2 发布验收。存量断点在 F08 单独修复；此处不宣称旧 ACK 已自动补齐。
