# Task9.1 合同阶段本地验收

日期：2026-09-08。结论：Task9.1 静态合同交付通过；扩大后的 Task9 与 R1 整体仍未完成。没有部署 Keycloak、创建真实账号/授权、实现管理 Handler 或新增登录页面。

## 版本与范围

完整审阅起点 `0cf0646`；实现与修复 `6e9a0e2`、`74697c4`、`59bcdb0`；最终实现报告 `3bbc559`。所有功能源码在 `59bcdb0` 冻结后运行最终全量，后续仅记录报告和进度。本轮提交在本地 `codex/r1-lead-contact-vertical-slice`，未推送或合并。

[ADR-0014](../adr/ADR-0014-task9-real-user-access.md) 与 [Identity 合同](../contracts/r1/R1-IDENTITY-ACCESS-CONTRACT.md) 明确 `MVP-2026-09-08.2`、HTTP V1.4、Command V1.3、Workbench V1.2、Identity V1.0、OpenAPI 1.3.0。总计 37 个 operation（32 public Bearer、5 internal mTLS），包含新增 21 项读取/管理接口合同，不代表新增接口已可用。

旧 9 个业务请求及其 47 个传递结构、原 16 项 method/path/operationId/security 精确相同；14 个业务事件、旧数据库生成迁移和 jOOQ 结构没有差异。物理基线仍为 52-plus-2-v1.2，没有新增应用表、GRANT 或角色成员关系。附件/通知仍为 R2，语音仍后置。

## 实际验证

| 检查 | 最终结果与边界 |
|---|---|
| 锁定 Linux Python 全量基线 | 296 项通过，710.067 秒，实际 exit 0；在冻结源码上运行，不以中途失败轮冒充通过 |
| 新 Task9 合同与拓扑 | 40 项通过，exit 0；包含 ROOT 权限、离线候选、畸形结构、真实测试命令的拒绝变异 |
| Java 生成与合同 | 19 项通过，exit 0；明确校验空任职上下文、严格结构及数组唯一性 |
| 原有真实 PostgreSQL/HTTP 回归 | 11 项通过，exit 0；验证机械请求头参数兼容，不是新身份 Handler 验收 |
| 前端 | 75 项通过；生成漂移检查、类型检查、构建 exit 0；控制代理在最终源码上再次验证 75 项和生成检查 |
| 实际仓库 CLI | 控制代理与实施代理分别验证 baseline consistency 和 topology，通过、exit 0；R2 的 7 项非致命未满足门槛保持 |
| 独立评审 | 首轮 3 项 Important、无 Critical；同一独立评审复审全部关闭，无新问题，静态规范/质量通过 |

原始日志和两轮评审保留在本地 `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`，其中 `baseline-stable-final.log`、`cli-stable-final.log`、`review-topology-green.log`、`java-contract-review-final.log`、`http-regression-final.log`、`preservation-stable-final.log` 为对应证据。该工作目录是本地执行证据，不宣称已推送至远端。

早期合同集成与测试夹具对齐发生过失败，最终稳定轮全部通过。Java 生成日志仍有 OAS 3.1/composition/null、默认注解、JAXB 和 Flyway existing-schema 警告，不宣称警告全为既存或输出无警告。新增 uniqueItems 生成 Jackson2 依赖的问题已通过等价 allOf 唯一性约束解决，生成类型保持准确 List，未添加 Jackson2 依赖，合法/重复数组均有实际测试。保留警告不是新增登录运行时验收。

## 评审修正与后续门槛

- 首次引导候选使用已批准离线部署操作者信任根的独立用途凭证，无既有 Actor、公开端点或会话表；精确唯一账号核验、用途隔离、失效与原命令恢复规则明确。
- 建立任职统一要求根范围；既有任职生命周期管理的受控范围不扩大。畸形合同结构返回校验错误而不是异常退出。
- SYSTEM 仅表示离线授权起点，不新增 Principal 类型；初始 HUMAN/Appointment 满足既有审计和授权外键。R1ApiDelegate 仅机械补入生成接口参数，真实任职选择校验仍待实现。

[扩展验收矩阵](../acceptance/2026-09-08-task9-real-user-access-acceptance.md) 的 C01 合同项及 C02～04 静态部分已有证据；其中运行时权限/错误配置/回执披露、真实登录、账号管理、七卡实屏和人工 UAT 仍未完成，不能将整组 C01～04 或扩大 Task9 提前全部标记通过。

下一步仅 Task9.2：锁定 Keycloak 环境、可信 HUMAN 映射、本人 context、任职选择后端校验及一次性引导。之后依次 9.3 管理后端、9.4 会话接线、9.5 一致页面与状态、9.6 真实用户验收。登录/任职选择新页面仍需单独视觉确认；Task10 联合与容量门槛不晋级。
