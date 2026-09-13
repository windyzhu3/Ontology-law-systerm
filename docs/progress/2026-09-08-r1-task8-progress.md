# R1 Task8 验收与整体进度

日期：2026-09-08。**Task8 本地验收完成：实施、完整回归、独立规格/质量复审、控制者最终定向复验均通过。未推送、合并或部署。**

## 当前到底完成了什么

Task8 完成的是**生产后端认证、HTTP 接线与 API/Worker 装配**，不是前端交付，也没有新增 MVP 业务范围。

| 交付层 | 状态 |
|---|---|
| Task1–7：合同、事务与业务服务、责任卡、首联/复核/等待恢复、Worker 核心 | 已按历史记录完成本地后端交付，本轮完整回归覆盖既有代码 |
| Task8：生产认证、16 个 HTTP 接口、同 Jar 的 API/Worker 装配 | 本地验收完成 |
| Task9：单 SPA 工作台接线 | 尚未完成；不在本轮实施范围 |
| Task10：真实前后端 E2E、严格 CI、容量门禁 | 尚未完成；用户稍后提供参考容量环境 |
| R1 基础功能整体 | **仍未全部完成，不能宣称前后端已完整打通或可以上线** |

下一项实施应为批准计划中的 Task9。本轮没有启动 Task9，也没有用本地缩小环境代替容量验收。

## Task8 交付

- 全部 16 个生成接口接入真实业务服务：11 个公共 Bearer、5 个内部 mTLS。
- 可信 JWT/证书与数据库身份绑定、严格有效期、SERVICE 精确来源约束、实时授权。
- 九项公共新命令 V2 恢复元数据、不可变回执 GET、原 key 重放零增量；内部恢复维持 V1 和原 mTLS 重放。
- 七种责任卡与空态、Draft 当前投影和独立 ETag、严格输入与安全 Problem、审计提交 ACK 前不披露响应。
- API/Worker Bean 与数据库能力隔离、部署只读门禁、动态健康、三个独立 Worker 循环、同 Jar 启动及正常关闭。

## 提交与最终证据

| 项目 | 可核对结果 |
|---|---|
| 原始运行时起点 | `824ac39e44d16547a5f0c4e92720ae1090fe3a68` |
| Task8 实施提交 | `c0801e614170deea4829b76275864cdadb6d7edd` |
| 独立评审修复提交 | `ea12ba374e240bb220f8b9f8c1444fad6061c34a` |
| 最终验证代码树 | `5640945483b43c8bbcaa3ddf4b64ab291e81e166` |
| 最终完整命令 | `./mvnw.cmd -f backend/pom.xml clean verify -Pit` |
| 固定 JDK | `25.0.4.1+1` |
| 第三轮完整结果 | 实际退出码 0，14:48，于 13:37:41 完成 |
| 唯一用例 | **681 项：104 单元/架构/合同 + 577 集成；80 个 suite，零失败、错误、跳过** |
| 独立评审 | 原评审发现 1 项 Important，修复后同一评审者确认规格合规、质量 Approved，无剩余 Critical/Important |
| 冻结面与基线 | 冻结面检查通过；锁定基线一致性 PASS，原有 7 项 R2 readiness 后续门禁未变 |

控制者已独立核对第三轮所有 XML 的唯一性和失败/错误/跳过数、实际退出码、提交与代码树一致性；测试期间无代码变化。

控制者另在最终提交独立运行 `R1ProductionAssemblyIT` 与 `ArchitectureTest`：实际退出码 0，01:09，13:40:50 结束，18 个唯一用例（13 架构 + 5 生产装配）、2 个 suite，零失败/错误/跳过。只归档该次两份新 XML；这是独立复验，不加进完整 681 项统计。

同 Jar 测试实际验证：两项 WAITING Task 恢复 OPEN、准确三条 Outbox 全部 DELIVERED、三个循环 READY、稳定零重复增量。真实生产 Worker Context.close 在 10 秒内返回，角色线程在 5 秒内退出，之后零增量；没有把强制进程清理当作正常关闭证据。

最终 Jar SHA256：`835a91f26d152ed42fb157dd2478ee4273d3d4f256293d731031193b5a6b0bd5`。

## 独立评审发现与修复

API 门禁在生命周期启动时正常、启动末段 PRIMARY 变为 BLOCKED 时，框架最终就绪事件曾把 REFUSING 覆盖成 ACCEPTING。实际请求仍由数据库门禁拒绝，不是授权绕过，但就绪报告错误。

真实生产装配测试先得到预期 REFUSING、实际 ACCEPTING 的失败；只增加 API 局部就绪保护、生产 Bean 和回归测试，共 3 个后端文件。定向 48 项通过，随后同一评审者复审批准，并重新执行上述完整 681 项。测试覆盖实际 TLS503/零增量、门禁恢复、再次失效、再恢复及正常关闭。没有添加测试开关或扩大权限。

首版完整 680 项、首版控制者重点 70 项与修复定向 48 项均是独立历史运行，**不与最终 681 项相加**。第一轮失败证据也保留，未覆盖为成功。

## 范围与未验收项

活动合同保持 `MVP-2026-09-08.1`、HTTP V1.3、Command V1.2、OpenAPI 1.2.0。
保持单 SPA、单 OpenAPI、单 Jar，16 operation、13 Schema、52+2 表、V001–V860、14 个 EventType；没有变更 schema/GRANT/字段/manifest/生成范围、前端或 CI。

没有新增 Command、authority、独立 Draft 授权对象、通用审计查询、Provider、AI、ADM 或 R2+。没有推送、合并、部署或容量验收。实际受控发布仍须提供与制品匹配的期望摘要并完成激活；合成测试配置不是发布链验收。

保留锁定生成器的 OpenAPI 3.1/mutualTLS 诊断、JAXB 编译告警、测试夹具提示与预期故障测试日志；未通过升级依赖或改合同掩盖这些非阻断诊断。实际五项内部 mTLS 接口已经生产实现和真实 TLS 测试验证。

## 本地证据索引

证据保留于本地忽略目录 `.superpowers/sdd/2026-09-05-r1-business-closure-plan/`，原始日志/XML/Jar 未提交远端：

- `task-8-report.md`：16 接口与 16 类回执验收映射、实施及失败/修复历史。
- `task-8-final-clean-verify-03.log`、`task-8-final-clean-verify-03-evidence/`：最终完整原始日志、80 份独立 XML、结果 JSON、Jar、真实角色进程证据。
- `task-8-runtime-review-01.md`、`task-8-runtime-review-02.md`：独立初评与修复复审。
- `task-8-root-fixed-baseline.log`：固定环境基线复验。
- `task-8-root-fixed-acceptance.log`：控制者在最终提交上的独立定向复验。
