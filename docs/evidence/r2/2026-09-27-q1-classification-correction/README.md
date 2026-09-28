# Q1 分类及承接更正验收

用户于 2026-09-27 确认 Q1。实现遵守 Q/P/P1 共用样式和原销售 MVP 范围。

## 已实现及验证

- 合同台账仅向有权案管显示完成后的“更正分类及承接”；进入共用工作台，回填当前分类和仍有权承接的任职，更正说明必须重新填写。取消不写业务事实。
- 新增受审计的只读分类上下文；读取不创建任务。更正复用 CLASSIFY_MATTER，保持唯一案件身份并追加历史，原回执恢复及重复提交不会新增案件。
- 实际 App 检查发现并修复共性问题：台账卸载后工作卡丢失母版样式、从管理入口进入时我的待办未激活读取、无关推荐任务错误显示为已选中、没有草稿功能的卡片显示保存草稿动作。共用 useWorkcardMaster 挂载现有 CSS，未改冻结 CSS 内容。

| 检查 | 结果与证据 |
|---|---|
| 全新导入至分类后再更正的真实 HTTP 链路 | PASS，570.422 秒；`output/q1-authoritative-http.log` |
| 只读权限、原值、撤权、读取不创建任务 | PASS，31.777 秒，同批 Maven |
| 管理入口权限及撤权隐藏 | PASS，41.209 秒，同批 Maven |
| 边界及架构检查 | 同批 Maven exit 0 |
| 前端定向回归 | 15 文件122 PASS，`output/q1-final-targeted2.log`；最终 App/MyTasks 26 PASS，`output/q1-app-final.log` |
| OpenAPI 精确增量及既有基线 | 9 PASS，`output/q1-baselines2.log` |
| 实际 App + 合成 transport | 1440/390/360，回填、提交、取消、待办切换、离开确认及母版样式 PASS；`output/q1-task-selection-green.log` |
| 构建 | 后端 package、SPA TypeScript/build exit 0；现有 bundle 大小提示保留 |
| 本地部署 | Q1 产物已更新至 https://localhost:19444，部署前数据库/产物/材料/数据日志已备份；`output/q1-activate.log` |
| 真实案管登录与台账 | PASS，1440/390/360，`output/q1-live-intake.log` |

## 证据边界

全前端首次运行961 PASS，另1项因运行目录错误无法读取 XLSX fixture，正确目录补跑该文件15 PASS。初次新 schema 片段生成存在 YAML anchor 冲突，修复序列化后基线及最终生成/打包通过。保留 RED 与失败记录，不计为通过。

合成 transport 浏览器不是后台写入验收。真实本地 C49 新案例正在补齐签署到转案、分类和更正的浏览器写入，原 C01–C48 保留。四条来源/付款完整 HTTP 矩阵见相邻 sales-mvp-matrix 证据；完整浏览器矩阵、管理及 AI 收口仍不应据此宣称完成。R1 PAUSED / R2 NOT_GRANTED。

## 真实环境续验发现

C49 通过真实页面完成签署安排、3份材料上传、双方签署提交和核验及完整归档。两次后续读取503使浏览器脚本未满足连续成功条件，分别保留在 `output/q1-sign-browser.log`、`output/q1-sign-browser-continue.log`；接口重读确认已成功事实，重新登录从下一阶段继续，没有重提原安排或归档。后台接续扫描存在批量耗时问题：真实mTLS同环境 limit1=700ms，limit50=9326ms，而Worker请求上限10秒；正在修复，尚不计浏览器整链通过。

全开发检查补齐了精确F10/F11数据库增量投影，旧R1哈希和准入条件保持；schema生成检查零变化、6项schema/transport测试通过，`q1-development-baseline4.log` 开发基线通过并保留7项非致命R1准入阻断。

Worker缩页回归：真实HTTPS7项、调度与回执18项、架构13项通过，后端打包exit0。合同接续批量由50改为5，保留10秒上限、准确游标和原命令幂等；证据 `q1-worker-page-green.log`、`q1-worker-package.log`。

补跑旧R1业务合同单测34项中21通过、13失败：失败均发生于旧测试按单行YAML文本定位变异点，而当前OpenAPI为多行格式，尚未执行对应变异断言；不把这批记为通过。当前语义开发基线与新精确增量检查通过，R1仍暂停。日志 `q1-r1-baseline-final.log`。

## C49 真实浏览器结果（18:54）

- 销售本人从“我的待办”确认执行条件、提交转案；案管另行登录，完成独立审查、接收、首次分类。各阶段成功回执和1440/390/360截图见 `live/`。
- 案管从合同台账进入Q1更正，将综法改为执行，实际POST成功回执已保存。重新登录再打开，显示执行且更正说明重新为空；取消不新增业务写入。首次分类页面和最终授权接口读取的案件ID、案号完全一致，分类历史正好2条。
- 最终财务任职重新读取C49，CHECK_CONTRACT_RECEIPT仍可办理；非先款合同转案/分类未取消独立收款责任。见 `q1-finance-after-correction.log`。
- 新运行包摘要与部署jar一致；后台按5条翻页，实测3727ms，checkpoint恢复推进。此为本地单次观测，不是负载测试承诺。
- 本轮脚本3处断言修正：案管推荐任务已是当前卡时不应再次点击禁用按钮；更正返回台账后需重新选记录，不能假定详情始终选中；销售历史定位需限定已展开历史区域，不能匹配筛选器隐藏option。这些脚本失败没有重提已成功的业务命令，也未要求产品改动。

本轮后端与真实页面验证已证明Q1同案更正闭环。C49前置线索/首联/授权/合同生成审批由真实API准备，签署及其后由真实浏览器办理；不能表述为“全链从CSV导入均由浏览器完成”。完整四路径浏览器矩阵、退回/结果未知跨登录场景及R2管理/AI收口仍待继续，R2 release acceptance仍为NOT_GRANTED。

18:55 Sales final browser PASS1440/390/360: completed transfer/classification history visible, classification correction action absent for sales; no business write. C49 browser/API evidence complete for this approved Q1 slice.
