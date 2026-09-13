# Task9.2 身份接入后端验收记录

日期：2026-09-09。状态：**Task9.2 本地阶段验收通过；独立复审4项重要问题全部关闭，无遗留阻断问题。** 本记录只覆盖 Task9.2，不是扩大后的 Task9、Task10 或 R1 发布验收。

## 范围与版本

- 工作分支：`codex/r1-lead-contact-vertical-slice`；完整运行时评审起点：`5ae6852ebcf292786e6236d4c34b09860f597343`。
- 运行时实现：`43fd69c8620d8edeeca3566a85ebf372bac030ff`；最终修复：`581c61e95b5951121e842d67ddbd2c50192626ea`。同一独立评审者完成差异复核，四项均为ADDRESSED、无新增Critical/Important。
- 依照已批准 Task9 扩展设计和代办补充设计；当前语义版本 `MVP-2026-09-08.3`、Identity V1.1。Task9.1/9.2a 静态后继已单独验收，不重复认领。
- 一份 SPA、一份业务 OpenAPI、一个互斥 `api|worker` Jar；13 Schema、52应用表＋2技术表及旧迁移保持。Keycloak 使用独立身份数据库。
- 本阶段没有前端页面、ADM-01～04 管理命令实现、ADM-05～07、附件、通知、语音或新业务流程。

## 已实现能力及验收边界

| 能力 | 本阶段实现 | 不等同于 |
|---|---|---|
| 真实身份验证 | 真实 Code＋PKCE、固定 issuer/audience/provider→Tenant、签名/时效及每次在线活动性复核；当前 subject-HMAC 动态 HUMAN 映射 | SPA 登录、续期、退出及实际人员登录验收 |
| 本人／合法代办 context | 无任职与多任职选择、显式代办、当前资格／来源授权复验、50/50有界候选、稳定完整Actor scope key | 新建委托关系、代办管理授权、前端任职选择画面 |
| SELF 披露 | 真实登录人主体、具名max101引用、先审计提交后披露、无缓存／无304、既有围栏锁序 | 以SELF资格替代具体业务授权 |
| 原身份业务与回执 | 真实动态用户读卡、保存草稿、完成、原完整代办Actor回执及当前DENY复验 | 七类卡逐屏真实登录E2E、管理建档链和人工UAT |
| 离线信任根 | 只读真实账号目录、独立用途候选密封、准确固定创建集合、零写dry-run、明确确认执行、完整原清单核验 | 在线默认管理员、部分状态修补、实际生产开户授权 |
| 生产安全装配 | 原生HTTPS、固定客户端、独立身份库verify-full TLS、缺失/错误配置失败关闭、SERVICE/mTLS与Worker隔离 | 生产部署、Origin/CSP浏览器实测、参考容量或备份验收 |

## 测试证据

锁定 JDK25、仓库 Maven wrapper、Keycloak26.7.3及PostgreSQL18镜像；具体digest见 `deploy/identity/identity-toolchain.lock.json` 和实现报告。所有账号与业务资料均为隔离合成测试资源，未操作实际人员或生产授权，未改变系统证书信任。

| 验证层 | 源码及结果 |
|---|---|
| 完整后端回归 | `43fd69c`：113项单元测试＋639项集成测试，均零失败／错误／跳过，实际退出0，20分07秒；`task92-stable-java-03.log` |
| 实际仓库合同／拓扑CLI | `43fd69c`：实际退出0；保留7项非致命R2 readiness阻断，不晋级 |
| 评审修复定向RED | 异常introspection 8项失败、动态数据库断连2项失败、引导逐字段损坏8项失败、预览1项失败，全部为真实断言失败，未以环境错误代替 |
| 修复后的定向验证 | `581c61e`：15项unit＋20项IT，零失败／错误／跳过，实际退出0；动态断连2、真实HTTPS CLI1、引导17 |
| 修复后的覆盖验证与实际CLI | `581c61e`：52项unit＋98项IT，零失败／错误／跳过，实际退出0，7分04秒；实际合同／拓扑CLI退出0，仍保留原7项R2阻断 |
| 独立评审 | 首评0Critical、4Important；修复复审4/4 ADDRESSED，无新增Critical/Important；非阻断建议见下文 |

完整回归只证明对应源码，不能自动覆盖其后修复。修复后已重跑受影响认证、SELF、引导、生产装配、架构／角色及静态SERVICE/mTLS回归，并完成同席差异复审；这些数字存在重叠，不相加冒充不同用例。最终375个`backend/src`及`deploy/identity`跟踪文件的规范SHA256为`8dd74983308b70a3917b2dd4b05ceef4d37f10cc0c88b09b8fc480e38e891c3d`，控制代理在验证前后独立重算一致。

最终覆盖命令：

```text
./mvnw.cmd -f backend/pom.xml -B -Pit '-Dtest=ArchitectureTest,RuntimeRoleTest,RuntimeWebRoleTest,HumanCredentialTransportTest,ActorScopeProtectionTest,BootstrapCandidateProtectionTest,IdentityBootstrapManifestTest' '-Dit.test=HumanLoginMappingIT,DelegatedSessionContextHttpIT,R1DynamicHumanReceiptHttpIT,SessionContextHttpIT,IdentityBootstrapIT,R1ProductionAssemblyIT,R1AuthInfrastructureHttpIT,ActorContextResolverIT,ClientCertificateIT,R1InternalHttpIT,R1ReceiptScopeHttpIT' verify
```

历史失败记录保留：首次全量因新测试调用私有helper而编译失败；第二轮113项以前的111项unit通过、639项IT中1项PKCE错误回跳预期失败，整体实际退出1；纠正为已批准回跳地址、`invalid_request`且无code后，最终全量通过。SafeText200遗漏另经RED/GREEN最小规范化。诊断收集曾明确使用failure-ignore的旧RED轮不算通过。

OpenAPI生成器的`mutualTLS`诊断、已有编译／迁移提示及Worker负向测试WARN均保留并分类记录；不能声称输出完全无警告，也不为消除诊断修改安全合同或升级依赖。

## 独立评审与闭环

首次独立评审要求修复：

1. 动态HUMAN映射／任职选择的包装SQL异常必须安全归类503，保留既有认证异常。
2. 异常introspection结构为503；合法`active=false`为401，不将上游异常当作确定失效。
3. 原引导Slot/Receipt时间及Audit固定来源关系完整一致后，才允许过期候选／IdP离线的原结果核验。
4. dry-run展示已验证的准确目标、固定任职和四项授权／范围，保持零写入且不泄露候选、subject/HMAC或秘密。

上述四项已修复且逐项复审通过。修复中曾误读现有审计分类视图未开放的session/IP/执行节点列，导致合法原结果被拒绝；该失败轮保留，最终已移除越界读取，未新增视图、GRANT或原始Audit读取。最终保留准确时间、固定API来源及可读取关系一致性，并验证跨执行节点合法恢复。

非阻断事项保留给后续全分支评审：局部密集格式可读性；既有诊断记录；Task9.6用同一原回执串联重登、重建服务与更换双方合法授权证据，补完整E2E序列。另损坏记录测试从scenario开始计时等待6秒，未直接断言候选已过期；负载下该前提不够严格，应改为准确到期时刻等待／断言。损坏拒绝与已有合法过期原结果恢复分别有证据，但不夸大该负例的到期计时保证。以上不构成本轮复审阻断，不得写成已修复或已通过浏览器证据。

## 整体进度与下一阶段

Task9.0的七卡前端和原接口请求层是历史已验收部分；Task9.2补后端身份基础，尚不构成生产用户前后端全链路完成。随后依赖为Task9.3受控Identity管理后端，Task9.4浏览器登录／会话／恢复，Task9.5管理页面与完整状态，Task9.6真实全链路和人工验收。新增登录／任职画面仍先由用户确认视觉。附件与通知留R2，语音后置；参考容量继续等待用户提供环境。

本轮无推送、合并或生产发布；本地提交不能表述为远端已同步。

## 本地详细证据索引

同一计划目录 `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`：`task-9.2-report.md`、`task-9.2-review-01.md`、`task-9.2-review-02.md`、`task92-stable-java-03.log`、`task92-fix1-targeted-green-02.log`、`task92-fix1-affected-green-01.log`、各RED日志、`task92-root-cli-receipt.md`及`progress.md`。该目录为本地过程证据，本记录保留可提交的准确结论与限制。
