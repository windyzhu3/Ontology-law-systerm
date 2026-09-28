# T06 材料接收实施与验收记录（2026-09-19）

状态：DEVELOPMENT_VERIFIED / LOCAL_REVIEW_READY。设计依据 E/G/I2/J，用户已授权继续实施。仅开发和本机验收，不授予 R2 发布验收。

## 实现与范围

- 从 G 商机详情的“业务材料”次级摘要进入 E/J 双栏卡，低频信息折叠、单一主操作；沿用冻结母版样式。
- PDF/JPG/PNG 单份文件，最多 20 MiB。真实字节解析、摘要、ClamAV 扫描和私有不可变保存；未通过或扫描不可用不得接收。原文件统一使用授权下载查看，未宣称实现内嵌预览。
- 上传与人工接收分离；会话冻结商机、当前责任、可选客户确认和准确前版。提交、绑定、材料版本、审计与回执原子形成，重复命令不重复接收。
- 未确认客户时先归属商机，不伪造 Party；已确认时引用准确 T05 版本。补交新版保留原文件和历史引用，当前版由版本链派生。
- 每次读取和下载重新核对当前身份、准确证据链与权限，记录审计；商机结束只读，移交、失权和旧依据不得继续写入。
- 上传 CHECKING/UNKNOWN 只查询原上传，未过期前服务端同时阻断新会话及预开会话绕行。正式接收未知只查询原命令回执；扫描不可用属于已知未保存结果，可以稍后重新选择。
- 接收不完成普通跟进，不改变 SLA，不提前唤醒 WAITING；返回办理前重新核对原任务 ID、revision 和办理资格。
- 不新增文件中心、Office/OCR、批量处理、硬删除、重绑、报价、签约、收款、转案或 AI。材料缺项仅为确定性准备提示，不自动阻断后续业务。

## 验证证据

| 验证 | 结果 / 原始日志 |
| --- | --- |
| 前端全量 | 56 文件 / 691 项通过；`output/t06-frontend-final.log` |
| 类型及生产构建 | 通过；`output/t06-build-final.log`；Vite 既有大包提示非阻塞 |
| 后端全量单元/架构/合同 | 258 项：255 通过、3 跳过；`output/t06-regression-final.log` |
| R1 / T01 / T03 / T04 / T05 集成回归 | 20 项通过；同上日志 BUILD SUCCESS |
| T06 数据库与 HTTP | 12 项（数据库 11 + HTTP 1）通过，另 JSON/执行合同 4 项通过；`output/t06-integrated-verified.log` BUILD SUCCESS |
| 真实扫描/存储探针 | clean、EICAR 拒绝、PNG 保存后准确读取通过；`output/t06-unit.log`，38 通过 / 1 跳过 |
| Schema / 精确历史投影 | schema 75 项（`output/t06-schema-verified.log`）、schema 投影 3 项通过；wire 4 项通过（`output/t06-wire-final.log`） |
| 发展基线 | PASS；`output/t06-baseline-final.log`，原 7 项发布阻塞未变化 |
| UI | 真实本机 PDF 首版、新版、历史下载、PNG 等待接收均通过；桌面及 360/390 宽度检查无横溢；浏览器 error 日志为空 |

全量单元中的 3 个跳过为默认未启用的真实扫描探针及 Windows 符号链接权限测试；真实扫描已另行显式运行。数据库和 HTTP 集成采用隔离 PostgreSQL 测试环境，不把模拟 scanner 的业务 IT 当作真实杀毒证据。历史失败日志保留，不据此宣称整条失败命令通过。

## 本机真实业务链

独立 `law_r2_review` 已更新 v7/V930，并在更新前备份数据库、jar、配置和前端。既有业务验收账号增加 T06 两项具名权限；凭据仍在本机私有账号文件。系统入口 https://localhost:19444 。

1. 真实页面录入合成线索 **T06材料连贯性合成客户0919** → R1 有效首联 → 商机跟进。商机 `01a0b940-d3af-78a2-b0a9-618bea94ddd2`。
2. 客户未确认时接收 `t06-synthetic-v1.pdf`（627 字节），再补交 `t06-synthetic-v2.pdf`（627 字节）。技术检查后分别人工确认，首版与新版准确连成同一条目。
3. 首版 `4e21bbfa-7be3-4b3d-955b-81c67b29a6a9`，新版 `6d73e3fe-6c6b-44ea-bcf6-02428f993ede`。历史清单显示第 1/2 版及接收人，首版下载成功。
4. 两份服务端 SHA-256 分别为 `8bc76b8e780ba56e20323b8ea8b2ce42470c34df568d528649681156ade78494`、`d45c76c2ebdbe3288cba95297a3181c3edb6592d16e0f450b9a4ce47b49f3651`，与原始合成文件相同。真实扫描引擎 `ClamAV 1.5.4/28128/Sat Sep 19 06:24:24 2026`。
5. “继续原跟进事项”返回相同商机的 R1 进展卡。任务 `01a0b940-e10d-7dac-b885-77a664f23fcc` 接收前后均 revision 0 / OPEN，SLA `2026-09-21T05:00:00Z`。
6. 已确认资料的 T05 合成商机接收 PNG 往来材料，准确关联确认 `f49844ff-86bb-4530-a090-b627aca5ff4b`。原任务 `01a0b8f0-a234-7203-8cfb-7c8f5a373b8e` 仍 revision 1 / WAITING、SLA `2026-09-28T05:00:00Z`，原约定联系时间未改。未重置用户后来安排的跟进记录。

[真实桌面核对](t06-ui/live-review.png) · [接收成功](t06-ui/live-accepted.png) · [返回原事项](t06-ui/live-return-task.png) · [历史版本](t06-ui/live-history.png) · [等待事项接收](t06-ui/live-waiting-accepted.png) · [小屏选择](t06-ui/mobile-select.png) · [小屏核对](t06-ui/mobile-review.png) · [小屏历史](t06-ui/mobile-history.png)。以上均为真实产品与本机合成数据，不是静态设计稿。

## 复审与限制

独立复审指出的原上传状态重试、刷新后的回执入口、恢复新版准确前版、冻结空客户确认已修复并复审通过；服务端未知状态绕行与静默历史截断也已修复并复审通过。准确任务 ID/revision、WAITING 和恢复流程有前端回归覆盖。

R1 PAUSED / R2 NOT_GRANTED 保持；产品默认 Worker 策略不变，仅既有本机 review Worker 启用。T06 开发验收不等同用户 UAT 或发布许可。未提交或推送。运行依赖、失败门禁与恢复说明见 [T06 运行说明](t06-runtime.md)。
