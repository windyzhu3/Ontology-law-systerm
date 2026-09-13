# R1 剩余真实验收执行矩阵

更新：2026-09-13。本文是执行清单，不是通过报告。以当前基线MVP-2026-09-08.3、Task完成合同V1.2及Task9验收矩阵为准。

## 执行规则

- `历史子项已证实`保留原构建和原运行记录，不自动变为当前最终构建PASS；先核对制品适用性，仅缺少或不适用的证据才补跑。
- `源码覆盖存在`只表示有可定位测试，不表示本轮执行或真实浏览器全链通过。
- 旧六卡及等待刷新不整体重放。新黄金链需要创建新的隔离合成Lead，不接管旧journal、不换旧命令键、不修改旧数据。
- API、SPA、Worker和制品/配置摘要须绑定同一新环境manifest。实际登录、页面交互、HTTP结果和数据库完成Fact一致后才关闭真实行。
- T9-W09为`DEFERRED_BY_USER`。不得通过改日期、时钟或把ACK当恢复来关闭；不阻塞以下非到期工作。U01～U03为`USER_CONFIRMED_CLOSED`，只待补归档信息，不要求重做。

## 业务分支（合同15行）

2026-09-13源码适用性核对：`git diff 421ca57..320f917`在生产`backend/src/main`、`apps/workbench/src`、`contracts`、生成数据库合同、后端pom、前端构建配置及依赖锁范围均为空；根package.json唯一差异是新增具名offline E2E命令。此前六卡验收的生产源码未被本轮改动，继续保留历史构建/运行标签，不整体重放。此结论不是新隔离环境已通过，仍须补齐新黄金链及原缺失安全/分支证据。

源码路径均在`backend/src/test/java/io/github/windyzhu3/ontologylaw/lead/`；下表只作复用定位，不将单元/集成测试替代浏览器。

| BranchID | 可复用定位 | 真实验收现状 / 后续动作 |
|---|---|---|
| P0_01_LINK_EXISTING | LeadIngressIT：both_duplicate_outcomes… | 缺历史已解析候选前置和页面证据；在新隔离环境受控准备候选后验 |
| P0_01_KEEP_SEPARATE | LeadIngressIT：同上 | 同上，独立Lead，候选/Party不得被改写 |
| P0_02_COMPLETE | LeadIngressCompletionIT | 历史六卡已证实一次补齐；核对原证据适用性，二次覆盖拒绝单独补缺 |
| P0_03_ASSIGN | LeadAssignmentIT | 历史六卡已证实手工分配；不整体重放，越权Owner/撤销候选另验 |
| P0_04_SCHEDULE_ROUTING_REVIEW | LeadRoutingDispositionIT：empty_candidates_schedule… | 缺真实页面分支；核对新责任WAITING/WaitReceipt，实际到期恢复不并入本轮 |
| P0_04_RETRY_ASSIGNMENT_NOW | LeadRoutingDispositionIT：retry_empty…、retry_after_candidate… | 缺真实分支：候选仍空与变为可用分别检查，无递归重试 |
| P0_04_REQUEST_SOURCE_INTAKE_STOP | LeadRoutingDispositionIT：source_stop_request_and_ack… | 历史六卡已证实请求及后继；不是来源已停用 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | 同上 | 历史六卡已证实准确因果ACK；核对证据，不重做来源停用功能 |
| CONTACT_CONNECTED_VALID | ContactResultIT：connected_result… | 优先新黄金链：自动分配→页面保存/刷新→有效接通→唯一Opportunity及合同EventOutbox记录→原Receipt恢复；不含R2通知入口 |
| CONTACT_NOT_CONNECTED_RETRY | ContactResultIT：first_unconnected… | 历史等待准备已证实；保留旧WAITING证据，W09延期 |
| CONTACT_NOT_CONNECTED_EXHAUSTED | ContactChainIT、ContactSafetyIT | 后端源码覆盖存在，真实全局次数耗尽缺口待补；不得用改旧到期时间加速 |
| CONTACT_SUSPECT_INVALID | ContactResultIT：suspect_result… | 历史六卡已证实产生主管Task，核对原证据适用性 |
| REVIEW_CONFIRM_INVALID | LeadValidityReviewIT | 历史六卡已证实CONFIRM_INVALID，核对原证据适用性 |
| REVIEW_CLOSE_UNREACHED | LeadValidityReviewIT | 缺真实页面分支；按准确因果ContactResult准备，不插终态Task |
| REVIEW_REOPEN_CONTACT | LeadValidityReviewIT、ContactChainIT | 缺真实页面分支；新建OPEN Task、旧Task不重开、contactNo不重置 |

## 横切失败与恢复

联系耗尽的非到期执行安排：可对一条新合成Lead依次提交`SUSPECT_INVALID(contactNo=1)→主管REOPEN_CONTACT→SUSPECT_INVALID(contactNo=2)→主管REOPEN_CONTACT→NOT_CONNECTED(contactNo=3)`，验证进入主管复核而非自动等待；再次主管重开后验证第4次仍不补充重试预算。此序列使用合同允许的真实主命令，不改时钟、不伪造ContactResult、不重开旧Task；属于待运行方案，不是已经通过。它验证全局次数和耗尽分支，不替代自动定时恢复W09。

| 执行组 | 必须补齐的结果 | 复用入口 / 边界 |
|---|---|---|
| 幂等/响应丢失 | 来源自然幂等；同Command同payload原Receipt；异payload/异scope无新增业务；双击和提交响应丢失后只恢复原结果 | LeadAssignmentIT、CommandReceipt*IT、api/R1CommandHttpIT及R1Receipt*IT；仍须浏览器/API/DB联合证据 |
| CAS/撤权/原子性 | stale revision、Appointment提交前撤销、Audit写失败完整回滚、跨租户拒绝 | 复用既有真实PG并发/回滚测试；新E2E只补缺少的前后端链，不关闭guard或改生产权限来测试 |
| 登录与会话 T9-L01～12 | 重点补退出L09首轮未报告、token轮转/故障、迟到响应及原Actor恢复；已通过登录/未映射子项先核对 | Task9.6r原失败保留；测试工具修复不是产品通过，新尝试用独立记录 |
| 代办 T9-D02～08 | 真实动态登录选择代办、写入/回执恢复、撤销拒绝，管理20operation不允许代办 | 目标身份/直接Grant经管理API；仅既有DelegationGrant测试关系允许隔离预置，不新增ADM-05 |
| 身份管理 T9-I01～13 | 生命周期、范围、自锁拒绝、撤权、CAS及未知结果恢复；动态用户无需改注册/重启 | 已有建档链和bootstrap核验先核对；目标HUMAN身份事实不由SQL插入 |
| 工作台 T9-W01～12 | 七卡缺重复确认；dirty/旧响应/失败刷新/后台/回执额度/权限变化等未闭合子项 | 原六卡、等待准备与q只读刷新子项不重跑；W09单独延期；沿用冻结视觉 |

## 当前实施顺序与退出条件

1. Task10.2隔离基础设施及准备产物：真实Compose/TLS/schema检查通过，只可标基础设施就绪。
2. 受控bootstrap→受控SERVICE前置及同Jar API/Worker＋同SPA装配→管理API建档/任职/授权；真实登录和当前身份合格后才可标应用环境就绪。不能在API启动前声称管理API写入完成。
3. 先黄金链，再按上表缺口批量执行关键失败/分支和Task9横切安全项。每场景保留命令键、退出码、脱敏完成Fact与摘要；失败不生成总PASS。
4. 完成最终适用构建的独立评审，生成当前日期总报告。分别列PASS、FAIL、NOT_EXECUTED、DEFERRED_BY_USER与外部参考容量待提供；不自动进入R2。

历史证据入口：[Task9当前记录](../../progress/2026-09-09-task9-local-chain-acceptance.md)、[Task9必需矩阵](../../acceptance/2026-09-08-task9-real-user-access-acceptance.md)、[Task完成合同](../../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)。
