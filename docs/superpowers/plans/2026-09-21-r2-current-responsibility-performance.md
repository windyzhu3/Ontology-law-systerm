# R2 当前责任读取性能优化方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持冻结 UI、业务状态、精确授权和同步审计的前提下，降低当前责任加载及待办切换延迟。

**Architecture:** 先优化 PostgreSQL 读取路径和请求内重复工作，再依据性能证据决定是否引入 Redis。PostgreSQL 始终是责任、授权、业务状态和审计的事实来源。本轮安全优化已部署，P07 验证已执行；建议性能门槛部分达到。以下保留原始验收清单，未覆盖项不补勾；实际裁定以执行状态表及验收记录为准。

**Tech Stack:** Java / JDBC / jOOQ / PostgreSQL；现有 React 工作台；Redis 为条件性选项。

**Spec:** 用户 2026-09-21 性能优化请求；docs/superpowers/plans/2026-09-20-r2-t07-quotes.md；docs/design/r2-sales-mvp/review/2026-09-20-k2/index.html；docs/evidence/r2/2026-09-20-current-responsibility-loading.md。

## 2026-09-21 执行状态

详细数据见 [实施与 P07 验收记录](../../evidence/r2/2026-09-21-current-responsibility-performance.md)。

| Task | 状态 | 裁定 |
|---|---|---|
| P01 | 测量完成，部分原始采集项未覆盖 | 900 次内核矩阵、300 次真实 HTTP、浏览器复测；旧版无 P95，CPU/冷启动/连接排队独立基线未覆盖。 |
| P02 | 已实施验证 | 锁内原始事实复用，逐次新时间评价，跨请求无授权缓存。 |
| P03 | 安全范围内实施 | 稳定商机数据及报价上下文去重；候选资格读取保留，原“全量详情消除”指标不宣称通过。 |
| P04 | 已实施验证并部署 | 连接池、锁后单次完整构建、归还清理及失败初始化清理。 |
| P05 | 基础优化完成 | 维持安全 304 路径，未启用早期版本向量捷径。 |
| P06 | 条件评估完成，不实施 | 无证据支持 Redis 用于剩余热点。 |
| P07 | 验证执行完成，性能部分达标 | 19 条混合待办 HTTP P95 1.57 秒；普通切换 P95 0.842 秒；报价切换 P95 1.864 秒，1 秒建议门槛未通过。 |

- [x] 部署前保存运行包与前端备份；本地服务复测。
- [x] 完成授权、并发、披露、连接池及报价业务回归。
- [x] 前端 745 项通过，后端最终集成 59 项通过；单元 282 项中 3 项既有跳过。
- [x] 桌面及移动端冻结样式复核；未调整 CSS 或扩展业务范围。
- [ ] 完整性能门槛全部通过：报价切换目标、精确混合任务并发浏览器和冷启动容量基线仍保留。

下方为原始计划条目；执行中有条件不适用或按安全约束收敛的项目不追认完成。

## Global Constraints

- 保持冻结 K2 及共用组件规范，不新增页面、导航或业务审批环节。
- 保留 R1 至已实现 R2 的线索、客户、材料、报价待办联通及我的待办主动切换。
- 不扩展合同、签署、案管功能；不改变推荐排序、可处理资格或授权规则。
- 保留 exact revision/hash DENY、代办身份隔离、数据库可信时间与权限撤销语义。
- 敏感 200/304 响应仍须在所需审计提交确认后释放；不得异步化审计以换取速度。
- V950/V960 已发布迁移不修改；必要数据库变更新增迁移。
- 保留现有未提交修改，不通过重置或覆盖工作区实施；分项部署及回退。

## 当前证据与测量限制

先前同账号验收组织 18 条待办请求约 6.8–10 秒，无待办组织约 2.75 秒；最近 19 条待办单次采样为 12.19 秒。这些不是正式 P95 基线。JFR 观察到频繁数据库 socket 读取及权限计算，但存在其他请求，不能将 socket 次数等同于 SQL 次数，也不能据此给出各阶段占比。

代码确认：SensitiveReadRuntime.read 执行两次完整 ReadWork；CurrentWorkCardSources.read 对全部可见事项构建 candidate；权限 Check 每次重建并重复查询公共身份信息；JdbcRuntimeConnections 每次 DriverManager 建连；JooqRuntimeDatabase.open 每次执行 capability/deployment 检查；304 判定发生在完整构建后。

## Review Focus

- 权限撤销或精确对象 DENY 与读并发：不得因缓存或单次构建多披露数据（P02/P04）。
- 完成、退回、交接后马上切换待办：不得出现旧责任或错误推荐（P03/P05/P07）。
- 共用连接先执行审计再服务另一身份：不得遗留数据库角色、事务或租户状态（P04）。
- 等待到期但没有人工写操作：仍能识别时间引起的资格变化（P03/P05）。
- 审计提交失败、缓存中断及旧请求晚返回：不能释放未确认数据或覆盖新事项（P04/P06/P07）。

## P01 建立可比较的性能基线

Files: backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/SensitiveReadRuntime.java；backend/src/main/java/io/github/windyzhu3/ontologylaw/api/CurrentWorkCardSources.java；backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/JooqRuntimeDatabase.java；新增 docs/evidence/r2/2026-09-21-current-responsibility-performance.md。

- [ ] 增加按 correlation 汇总的计时：连接获取及检查、锁等待、两次读取、权限 SQL、摘要、详情、审计、提交、总请求；记录 SQL 次数及累计时间，不记录令牌、SQL 参数或客户字段。
- [ ] 区分 200/304、首次/切换/刷新、冷启动/热运行；嵌套计时不得相加后声称为总耗时。
- [ ] 在同一环境使用 0/20/100 条合成待办、1/5/10 并发，先预热，每组至少 100 次，记录 P50/P95、失败率、CPU、连接等待和 SQL 数量。性能压测使用独立合成数据，保留用户验收案例。
- [ ] 确认计时不改变响应及异常语义，以结果确定 P02–P04 内部优先顺序。

Deliverable: 可重跑基线与分段报告，不先承诺某项优化比例。

## P02 公共授权信息复用与批量读取

Files: backend/src/main/java/io/github/windyzhu3/ontologylaw/identity/internal/persistence/JooqAuthorizationService.java；backend/src/main/java/io/github/windyzhu3/ontologylaw/api/CurrentWorkCardSources.java；测试 backend/src/test/java/io/github/windyzhu3/ontologylaw/api/CurrentWorkCardAuthorizationIT.java、CurrentWorkCardConcurrencyIT.java。

- [ ] 先添加同租户多事项、跨任职/代办、revision DENY、撤权并发回归与重复查询计数断言。
- [ ] 对租户、主体、任职、组织树等公共事实批量读取；限定在同一次锁后授权评价阶段复用。不能只因处于同一个 READ_COMMITTED 事务就假定数据不会变化。
- [ ] 仅对被现有锁保护且时间有效性明确的事实复用；前置读取和锁后读取不共享缓存。精确对象 DENY、版本/hash、到期时间和 action 仍逐项正确评价。
- [ ] 将同一来源重复 bind 的可复用部分去重，保留完整披露来源及依赖集合；对未受保护的可变事实继续读取。
- [ ] 跑授权与并发回归，对照 P01 的权限查询次数和总耗时。

Deliverable: 同一身份公共查询不再随重复 bind 无限制增长，授权输出与原路径等价。

## P03 待办摘要与当前卡片详情分离

Files: backend/src/main/java/io/github/windyzhu3/ontologylaw/api/CurrentWorkCardSources.java；backend/src/main/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardQuery.java；测试 backend/src/test/java/io/github/windyzhu3/ontologylaw/api/CurrentWorkCardCausalIT.java、CurrentWorkCardDisclosureIT.java。

- [ ] 固定旧路径输出样例，覆盖推荐、主动选择、选择失效、OPEN/WAITING、代办、线索和报价阶段。
- [ ] 先批量取得已授权摘要和必要资格信息；保留 candidate 原先隐含的资格判断，不能把“有任务”直接视为“可操作”。
- [ ] 按原排序确定推荐及所选事项；只构建最终显示卡片的完整草稿、材料、候选人员等详情。所选失效按原规则回退。
- [ ] 摘要字段仍保留其真实来源的授权和披露记录；未展示详情不再构建，但不得删除影响资格的依赖。
- [ ] 比较响应语义、披露和推荐结果；测量 20/100 条待办下详情读取次数，确认不会为全部事项加载详情。

Deliverable: 接口形状及 UI 不变，摘要与详情成本分离。

## P04 连接复用与安全的单次完整构建

Files: backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/internal/persistence/JdbcRuntimeConnections.java、JooqRuntimeDatabase.java；backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/SensitiveReadRuntime.java；backend/pom.xml；测试 CurrentWorkCardConcurrencyIT.java、CurrentWorkCardDisclosureIT.java。

- [ ] 先建立锁顺序、权限变化、审计失败、提交不确定、连接角色残留的回归。
- [ ] 接入受限连接池，API 与 worker 分开配置；按数据库连接预算和 P01 数据设置容量，连接获取超时有界。启动配置和依赖版本在实施时核验。
- [ ] 连接借出/归还检查事务、角色、只读及会话状态；异常或重置失败销毁连接。敏感校验不因使用连接池而跳过，deployment 状态仍保持当前强度。
- [ ] capability 检查先保持原语义，只复用物理连接；如这部分仍是瓶颈，另行证明管理员权限变化检测机制后再优化，不用长 TTL 掩盖变化。
- [ ] 核对 business fence、identity lock 与写路径锁顺序；将第一次完整构建改为最小必要预检，锁后完整构建一次。没有通过并发证明前保留双读，不能机械删除第一遍。
- [ ] 审计及提交确认继续同步执行；记录连接和双读分别带来的实际收益。

Deliverable: 连接复用安全可回退；完整构建减少须通过并发授权验收。

## P05 减少无变化刷新成本

Files: SensitiveReadRuntime.java；CurrentWorkCardSources.java；apps/workbench/src/features/workcard/useCurrentCard.ts；apps/workbench/src/features/workcard/useCurrentCard.test.tsx。

- [ ] 优先复用 P02/P03 的低成本路径，验证现有 304 已获得的收益；不强制引入新版本表。
- [ ] 如仍不达标，定义包含身份、责任、业务、草稿、材料、策略及时间到期边界的完整变化依赖；不能仅使用 task.updated_at 判定不变。
- [ ] 只有依赖完整、权限重新确认、所需审计提交成功，才允许早期 304；依赖不完整则回到正常读取。
- [ ] 保留写后主动刷新、手动刷新、旧请求隔离和等待事项到期刷新；不为减少请求而让用户看到旧责任。

Deliverable: 无变化刷新更轻；不足以证明安全的快速 304 不上线。

## P06 Redis 条件性任务

启动条件：P01–P04 后目标仍未达到，且复测显示剩余瓶颈主要来自跨请求重复读取稳定数据；必须用小范围实验确认命中收益超过网络及维护成本。若已达标，本任务标记不需要，不部署 Redis。

- [ ] 首批范围仅限可明确标识版本的不可变配置/模板元数据等实际热点；单实例少量静态数据优先有界进程缓存。当前责任、授权允许/拒绝结果、完整工作卡、审批及报价接受状态不进入跨请求缓存。
- [ ] PostgreSQL 决定当前有效版本，缓存键包含租户、资源类型、ID、不可变 revision/hash 和结构版本；Redis 命中不能替代当前授权、时间判断或审计。
- [ ] 使用 cache-aside，提交成功后才填充。版本变更读取新键；TTL 和清理仅用于空间治理，不能承担强一致性保证。
- [ ] 缓存短超时后回源；增加有限并发回源保护，Redis 故障不会阻断业务。缓存不保存凭证、客户材料正文或权限快照。
- [ ] 验证跨租户隔离、旧版本命中、提交失败、缓存故障、冷缓存、并发回填；对照无缓存实验，记录命中率和 P95。

Redis 官方参考：https://redis.io/docs/latest/develop/use-cases/cache-aside/ 。普通 cache-aside 存在失效窗口，本项目不能仅以 TTL 或提交后 DEL 保证责任及授权正确。

## P07 业务与性能整体验收

- [ ] 执行 P01 同条件矩阵，分别记录 API 与浏览器“点击到可操作”的耗时，禁止把首次登录/OIDC 重定向混入当前责任 API 数值。
- [ ] 建议目标：验收环境、20 条待办、5 并发，热运行当前责任 API P95 ≤2 秒；浏览器主动切换至可操作 P95 ≤1 秒。冷启动、100 条待办及 10 并发单独报告，不冒充该目标已覆盖。
- [ ] 对指定优化负载要求无超时和无功能错误；100 条待办不发生全量详情构建。新基线不足则如实记录，不通过放宽权限或减少审计达标。
- [ ] 覆盖 R1 线索导入→补全→联系→R2 客户/材料→报价草稿/审批/退回/发送/回复/等待/交接→已实现合同准备来源边界；不宣称未实现的合同签署转案已验收。
- [ ] 覆盖提交后下一责任、待办切换、所选失效回退、权限撤销、组织切换、等待到期、304、审计失败、晚返回；检查冻结页面视觉无变化。
- [ ] 发布前保存运行包及配置；逐项上线复测，出现权限/责任/审计回归立即回退对应项。Redis 若启用必须可关闭并回源。

## 执行顺序及范围

P01 → P02 → P03 → P04 → 复测；只有仍有瓶颈才执行 P05 的深层改造和 P06；最后 P07。实施过程保持本计划的业务和设计范围，具体优化方案若改变安全模型须先补足设计证明。本轮已实施并更新本地验收运行包；未安装 Redis。P07 结果及剩余性能差距见执行状态，不将回归通过等同于所有建议性能目标通过。
