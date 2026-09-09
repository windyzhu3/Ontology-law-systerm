# Task9 扩展设计：真实用户接入、受控身份管理与工作台状态

日期：2026-09-08。

状态：**APPROVED — 用户于 2026-09-08 在审阅本文与配套计划后明确确认详细设计。** 从 Task9.1 开始具名合同后继实施；设计批准本身不代表活动合同已完成修订或功能已实现，不部署身份服务、不建立实际用户、不授予实际权限。

## 1. 目标与完成口径

Task9 从“可注入身份的工作台前端”扩展为：管理员通过受控流程建立用户、组织、任职和直接授权，真实用户通过生产登录入口进入系统，获得自己有权处理的责任卡，完成候选保存、显式提交及必要恢复，并得到完整、准确的状态提示。

用户请求虽然写“两个内容”，实际列出三项，全部纳入：

1. 登录、会话、真实用户进入系统的验收。
2. 受控建立用户、组织、任职和授权，打通用户到责任卡的资格链。
3. 今日摘要、当前卡、后续摘要、等待数量、提交结果、刷新与恢复等工作台状态。

原 `5136e01` / `424f030` 的 75 项测试、七类 HTTP 联通及首联浏览器验证保留为 **Task9.0 历史阶段证据**，不能满足扩大后的 Task9 完成定义。扩大后的 Task9 重新打开，所有新增验收项通过前均为未完成。

## 2. 设计依据与已确认选型

当前活动权威仍为 `MVP-2026-09-08.1`、HTTP V1.3、Workbench V1.1、OpenAPI 1.2.0、16 operations（11 public / 5 internal）、物理 `52-plus-2-v1.2`。本设计不是这些合同的即时替代。

已检查的具体缺口：

- `api/security/ActorContextResolver.java` 将 HUMAN 也绑定到部署时的逐人 Registration；新增数据库用户不能自动变成可登录用户。
- `api/security/R1SecurityConfiguration.java` 是无状态 Bearer API，没有浏览器登录与会话编排。
- `apps/workbench/src/main.tsx` 未接入生产身份；现有 App 无身份时安全关闭。
- Identity 既有四张相关表已经存在；Principal 只存 provider code、外部 subject 的 Tenant HMAC 和显示名，不存密码、Token、邮箱或电话。
- 当前基线、Workbench 合同及原计划明确排除身份管理生产 CRUD，需要具名后继修订。
- HTTP Evidence 最终复验允许 `REJECTED/NOT_FOUND`，OpenAPI TerminalRejectionCode 遗漏该值，纳入本次合同前置一起对齐。

比较过三种路线：

| 路线 | 取舍 | 结论 |
|---|---|---|
| 自托管 Keycloak/OIDC，业务系统负责 Identity 与授权 | 复用成熟凭据及会话生命周期，需增加明确的外部身份基础设施依赖 | 用户已确认 |
| 接入已有统一身份服务 | 同样可保留业务授权边界，但用户目前没有该服务 | 不采用 |
| 系统自建密码、凭据、找回与会话库 | 增加密码安全、运维与持久化合同负担，明显扩大本轮风险 | 不采用 |

Keycloak 的 Authorization Code 流程、浏览器内存 Token 与刷新机制参考[官方 JavaScript adapter 文档](https://www.keycloak.org/securing-apps/javascript-adapter)。会话超时、撤销与退出边界参考[官方管理文档](https://www.keycloak.org/docs/latest/server_admin/index.html)。下面的具体超时、授权限制和验收阈值是本项目提出的设计值，不是宣称产品默认值。

## 3. 不变边界与允许改变的边界

- 业务系统仍为一个响应式 SPA、一份业务 OpenAPI、一个模块化单体 Jar；`APP_ROLE=api|worker` 互斥。
- Keycloak 是新增外部身份基础设施，不是第二个业务 SPA、第三种业务 Jar 角色或 Provider Inbox。其数据库由 Keycloak 单独拥有，不放进业务 13 Schema，不使用应用数据库权限；这一外部数据库/服务依赖必须在拓扑后继中明示，不能拿“单数据库”旧口径掩盖它。
- 业务数据库保持 13 Schema、52 应用表＋2 技术表。复用 Tenant、Principal、OrganizationUnit、Appointment、AuthorityGrant 和既有 Audit/Slot/Receipt；不加用户密码表、会话表、动态角色权限表或通用关系表，不修改旧迁移字节。
- 只实现 ADM-01～04 的下述受控子集。ADM-05 代理授权、ADM-06 对象规则编辑、ADM-07 审计浏览/导出仍不实施；既有 DENY、合法代办及审计安全必须回归，不能绕过它们。
- 不新增业务 Task 类型或 Task 完成方式；新用户不会因为登录、授权或管理员点击而自动生成业务责任。责任仍由 R1 接入、分配及后继业务事实产生。
- 附件上传与通知中心/推送留在 R2，语音继续后置。本次状态提示不是通知中心，不增加铃铛、订阅、消息 Inbox 或 AI。
- 工作台仍仅一张完整当前卡、至多两条后续摘要、一个等待计数和固定候选输入区。身份管理单独受保护 route mode，普通工作台不增加业务导航或管理侧栏。

## 4. 登录与会话

### 4.1 浏览器和 API 的责任划分

采用 SPA public client 的 OIDC Authorization Code + PKCE S256；禁止 Implicit、密码直传 grant、前端 client secret、粘贴 Token 登录和测试身份回退。

`/login` 是本系统的品牌入口和明确“登录”操作，凭据只在 Keycloak 托管登录页输入；`/auth/callback` 处理一次性回跳。允许的 issuer、client、audience、redirect URI、登出回跳及 Web Origin 均由部署配置固定，不接受 URL 参数指定任意 issuer 或跳转地址。OIDC 回跳校验 state/nonce/PKCE；一次性协议暂存不可包含密码或 access/refresh token，并及时清理。

access/refresh token 只在浏览器内存中保存；不写 localStorage、sessionStorage、IndexedDB、URL、日志或错误报告。页面完整刷新通过 Keycloak 的现有会话重新走可信回跳；不能只依赖可能被浏览器禁止的第三方 iframe 静默登录。失败给出明确重新登录操作，不无限重定向。

业务 API 保持 Bearer。HUMAN token 先做签名、算法 allowlist、issuer/audience/exp/nbf 校验，再由服务端向固定 Keycloak introspection endpoint 复核当前活动性；不缓存成功活动性、不在故障时降级为仅凭未过期 JWT 放行。单次远程检查超时 2 秒，失败按安全 503 区分于确定的无效身份 401。相关 confidential client 凭据只由后端秘密配置提供。必须实测 IdP 退出/撤销后活动性检查拒绝旧 token，不能假设 JWT 本身会立即失效。

SERVICE/mTLS 的既有准确注册、来源账户绑定、Worker readiness 和隔离机制保持；HUMAN 的动态映射不能被用作 SERVICE 入口。

### 4.2 真实身份映射与任职选择

可信配置将每个 issuer/provider 唯一绑定到一个 Tenant；不同 Tenant 使用不同 realm/issuer，不从 token 的任意 tenant/role/organization claims 产生业务权威。登录入口的 realm 来自受信部署 Origin 配置，不增加自由 Tenant 输入框。

验证后的 `sub` 在服务端按 Tenant 密钥 HMAC，与现有 Principal 唯一匹配；映射不存在或歧义均拒绝进入业务，不按显示名、邮箱或同名账号合并，不在首次登录时自动建 Principal 或授予 Grant。HUMAN 新增映射以后无需逐人改部署 Actor registration 或重启。

增加只读 `/api/v1/session/context`：它只使用已认证的本人的 Principal 身份，返回本人安全显示名、可用任职选项、当前选定任职标签、工作台/管理入口资格及不透明 `actorScopeKey`。它不是业务权限快照，不能被命令信任。该具名 self 查询可在尚无 Appointment 时运行；审计使用现有可空 actor_appointment_id，不虚构任职。未映射账号不得创建临时审计 Principal，其登录拒绝由 IdP/安全日志记录。

actorScopeKey 由服务端以独立用途的持久密钥对规范化 Actor 元组做 HMAC，同一 Tenant/Principal/Appointment/合法代办路径跨续期、重登和应用重启稳定；任何 Actor 分量改变则不同。它不是会话 ID 或权限证明，不编码可逆身份信息。密钥版本改变导致旧标记不能匹配时安全提示无法恢复，不尝试跨身份猜测。

单个有效任职可自动选择；多个任职必须显示“请选择本次任职”，不取列表第一项。客户端只能回传本人服务端提供的 `X-Appointment-Id` 选择器；每次业务请求均重新验证其属于当前 Principal、Tenant，且 Principal/Appointment/组织当前有效。它是选择器，不是授权凭据，UI 不直接显示 UUID。没有任职、没有工作台入口资格与“有权限但暂无责任”分开呈现。

更换用户、Tenant、任职或退出时递增 identity epoch，清除当前卡、ETag、候选、请求 generation 和旧授权相关视图。仅同一身份的 access token 续期不得更换 epoch、丢弃未保存输入或丢失原请求；API 每次从凭据提供器取得当前有效 token，不把旧 Bearer 固化进恢复请求。

### 4.3 会话期限、退出与失效

拟采用 access token 5 分钟、SSO/client session idle 30 分钟、absolute max 8 小时，禁用 remember-me 和 offline token，开启 refresh token rotation。前端提前 60 秒提示交互会话即将结束；后台等待轮询不计为用户活动，也不允许无界续期。IdP idle 的实现窗口须在配置验收中记录，不能把配置值宣称为精确到秒的安全截止；API 当前活动性复核与应用主动退出单独验证。

主动退出先清理敏感内存并广播本 Origin 的无敏感内容登出信号，再执行 OIDC RP-initiated logout。即使 IdP 不可用，本地仍退出，显示“已退出本页面，统一会话退出尚未确认”，不能假报已撤销服务器会话。其他标签页收到信号立即清屏；不支持该通道时在重新聚焦/下次请求前复核，不宣称瞬时跨端退出。

用户挂起、任职挂起、Grant 撤销在业务数据库提交后，后续 API 及 200/304 披露按既有最终授权重验拒绝；不得因旧 token 或 ETag 保留权限。当前命令最终持锁复验之后的并发撤销仍遵循 ADR-0006 的裁定点，不承诺撤销能倒退已经合法提交的业务事实。

### 4.4 登录跳转与未决提交恢复

同页原请求继续完整保存在内存；未知提交结果仍只查原回执或同原 key/body 重放，绝不自动生成新命令。

为完整刷新/重新登录补一个 **每标签页最多一条、24 小时过期的非凭据恢复标记**：仅 `commandId`、静态 `commandType`、服务端不透明 `actorScopeKey`、客户端记录时间。写请求派发前存入 sessionStorage；不存 Task/Lead payload、候选文本、ETag、Token、密码、Tenant/Principal 原始 ID 或原请求正文。标记写入失败时不派发写请求，明确说明浏览器恢复存储不可用。标记仅是待核对线索，不能授予访问权，伪造或篡改标记不能越权。

存在未决标记时禁止同标签页另发新写请求覆盖它，包括切到身份管理页面；只允许核对/恢复原操作。收到准确终态回执，或合同明确证明没有占槽/提交的响应后，才按该错误的既有键策略清除或替换标记。401、超时、断网和无法判定的技术故障不能充当“未提交”证明；用户显式放弃本地线索须单独确认风险，不能自动替代此判断。

重新登录或刷新后，仅相同 actorScopeKey 可显式查询原 `commandId` 的现有 Receipt endpoint；每次仍由后端按 ADR-0013 当前授权及原 Actor 复验。终态回执成功读取并处理后清标记，刷新 envelope。无完整原请求时不显示“原请求重试”，不从新卡重构旧正文；无合法元数据/404/遗留回执不可恢复时明确显示“结果尚未确认，不能自动重发”，不伪造失败。显式放弃标记只删除本地线索，文案必须说明不等于撤销原操作。切换其他身份时不查询或显示原身份标记，清除跨身份线索。

此处仅增加安全恢复线索，不新增恢复管理后台、审计回填、人工完成 Task 或第二个回执接口；须作为 Workbench 合同后继的具名变化确认，不能悄悄改变原内存恢复边界。

## 5. 受控用户、组织、任职、授权

### 5.1 双阶段建立账号，避免跨系统伪事务

最小可交付流程明确为两段，而不是声称本系统一个按钮能原子创建两个系统的数据：

1. 获授权的身份运维人员在 Keycloak 建立真实 HUMAN 账号、设置初次登录改密流程并通过安全渠道交付初始凭据；密码重置、凭据锁定与会话管理由 Keycloak 提供。禁止公开自助注册，禁止在代码、聊天、截图或验收报告中保存真实密码。本系统不新增邮件/短信发送功能。
2. 管理员在同 SPA 的 ADM-01，从服务端按本 Tenant realm 只读查询的受控 IdP 账号候选中选择准确账号，创建本地 Principal；然后通过 ADM-02/03/04 建组织、任职、直接授权。按用户于 2026-09-09 确认的最小澄清，在线候选只按完整用户名精确查询，返回 0 或 1 个当前有效 HUMAN 账号，nextCursor 恒为 null；不提供姓名、邮箱、模糊搜索或通配／特殊 lookup。既有 query/DTO、limit 1～50 和错误结构保留，所供 cursor 因不签发后续页而拒绝。本地四类列表及 options 分页不变。候选仅获权管理员可读，不在浏览器持有 IdP 管理凭据，不允许手填任意 subject 冒充绑定；不增加目录缓存、表、Provider 扩展或权限。

Keycloak 账号已存在但本地绑定失败时保持“未接入业务系统”，不自动授予权限或删除外部账号。重试根据唯一 provider-subject HMAC 判断相同绑定；已有其他映射返回安全冲突。本轮不建设跨库事务、通用 Saga、同步用户平台或自动批量开户。

### 5.2 四类页面与受控动作

| 页面 | 本轮允许 | 明确不允许 |
|---|---|---|
| ADM-01 用户与身份 | 查询、绑定已存在 HUMAN 账号、改显示名、挂起/恢复、满足前置后永久禁用 | 本系统保存凭据、修改 provider/sub/kind、SERVICE 创建、硬删除、DISABLED 恢复 |
| ADM-02 组织 | 查询、创建根下子组织、改名称、满足前置后关闭 | 改稳定 code、已建节点重新挂父级、跨 Tenant 迁移、CLOSED 恢复、HR |
| ADM-03 任职 | 查询、为准确用户/组织创建静态岗位任期、挂起/恢复、满足前置后结束 | 修改已建 principal/org/role/任期、ENDED 恢复、岗位自动等于权限 |
| ADM-04 直接授权 | 查询、对准确任职授予一项静态 R1 HUMAN 权限及范围/有效期、撤销 | 修改、续期、恢复旧 Grant、动态权限矩阵、自授权、SERVICE 或身份管理权限的在线转授 |

Principal ACTIVE/SUSPENDED/DISABLED、Organization ACTIVE/CLOSED、Appointment ACTIVE/SUSPENDED/ENDED、Grant ACTIVE/REVOKED 均按既有表状态机；稳定字段和一次写入字段不得改写。授权调整用撤销旧 Grant＋新建准确 Grant，不把它包装成更新原授权。

新增四个静态管理权限候选：`IDENTITY_PRINCIPAL_MANAGE`、`IDENTITY_ORGANIZATION_MANAGE`、`IDENTITY_APPOINTMENT_MANAGE`、`IDENTITY_AUTHORITY_MANAGE`，对应同域的读取和具名写入；仅 HUMAN、DIRECT、自身任职路径，仍受当前组织范围、有效期与 DENY 约束。Keycloak realm/client role 不产生这些权限。

在线管理员只能在自身当前管理 scope 内操作；目标组织必须为 scope 根或后代，不能选择祖先扩大范围。管理授权可授予的目标 code 为批准的 R1 HUMAN 业务静态集合，不包含四个管理权限、SYSTEM/Worker 权限或任意字符串。原业务 command 不接受请求自选 authority；管理 Grant DTO 中的目标 authorityCode 是受控业务内容，与调用者需要满足的管理权限严格区分。

Principal 是 Tenant 级身份，不自带所属组织，挂起它会影响所有任职。因此本轮 ADM-01 及 IdP 账号候选查询仅允许具有 Tenant 根组织 scope 的直接 `IDENTITY_PRINCIPAL_MANAGE`；不根据任意一条任职就让局部组织管理员读取/挂起整个用户，也不把尚无任职的新 Principal 隐藏到无法继续配置。ADM-03 的新任职用户候选同样要求根 scope；已有任职的局部查询只返回当前获权范围内的安全资料。更细的跨组织人员管理不在本轮实现。

组织关闭须没有有效子组织/有效任职；Appointment 结束须没有其仍承担的 OPEN/WAITING 责任；Principal 永久禁用须其任职已结束。安全挂起与撤权立即允许，不被未完成业务阻挡；不擅自转派、复制或关闭旧 Task。通过 Runtime 调用 Responsibility Owner 的窄依赖检查，不允许 Identity SQL 直接读取业务表。当前管理员不得通过页面禁用/结束自己；不能在线修改最后可用的引导管理员资格。

### 5.3 首位管理员与生产引导

提供关闭 HTTP 的一次性受控引导命令，由部署操作者在受限环境执行，不增加公开 bootstrap endpoint，不把“第一个登录的人”变成管理员。

在受信 Tenant/provider 配置和已经真实存在的 Keycloak 管理员账号基础上，初始化唯一 Tenant、根组织、HUMAN Principal、管理员 Appointment 及四项直接管理 Grant；指定确定的 bootstrap commandId，记录脱敏清单摘要、准确操作者声明和 Audit/Receipt。仅全新目标或同一完整引导的无变化核验可通过；部分存在、相冲突或已初始化后请求追加权限均失败关闭。此 SYSTEM 引导是具名、仅离线的启动信任根例外，不进入在线普通管理权限路径，不推广为任意 SQL 初始化工具。

bootstrap 不写或返回 Keycloak 密码、Token、私钥，不放在普通 API readiness 的自动修复中。首次完成后关闭该入口；重放只能核验原结果，不能生成新管理员或提升权限。失去所有管理员时走受控运维事件流程，不新增网页后门。

引导管理员只取得四项管理 Grant，不因此获得业务卡。验收另建真实试用者，配置有效组织、任职和所需 R1 业务 Grant，再通过实际接入/分配让 Task 归属该任职；不得为“能看见卡”额外授予全域权限或在登录时生成 Task。

### 5.4 原子写入、锁与审计

所有在线 Identity mutation 必须走静态具名 Handler、CAS、UUID Idempotency-Key、Slot/Receipt/Audit 原子提交；未知结果原 key 恢复，不能直接 Controller→jOOQ 写表。采用具名身份命令执行路径复用底层持久化机制，不建设通用流程引擎。

Identity 写路径的锁序拟定为：`R1_BUSINESS_TENANT_LOCK exclusive → command UUID fence → identity Tenant exclusive → 按确定顺序的 Identity 行`；之后不得取得 Lead/Task 业务锁。业务依赖检查只通过 Owner 窄读口，在已排他的 Tenant 业务围栏下执行。现有 R1 业务命令锁序不变；两类路径须用实库双连接证明无反向死锁、撤销线性化及不同 Tenant 独立推进。

新建 Fact 的 UUID 由服务端生成，修改/生命周期动作带准确 Identity ETag；缺失前置、stale、权限不足、最后管理员保护、组织依赖、开放责任依赖分别静态登记安全错误与可重试策略。新命令成功/拒绝、CAS 失败、重放、Audit/Receipt 写失败均有明确 delta 测试。

Identity 列表及本人 context 使用新的具名披露 profile 与先 Audit commit 后返回，不能借旧 WorkCard 的 Lead/Task 授权锚点查询任意身份。未映射身份的拒绝在 IdP/安全日志，不伪造审计主体。既有 R1 14 个业务事件及投影路由不扩张；身份改变由当前事实重验和 ETag 失效生效，不伪造 R1 DomainEvent 或增加 Worker QueueOwner。

## 6. 工作台状态模型

session、envelope 读取、候选保存、业务提交是独立状态轴，不用一个 loading boolean 冒充所有状态。显示优先级：身份失效/越权 ＞ 未决提交恢复 ＞ 已确认结果但刷新失败 ＞ 普通读取错误 ＞ 普通摘要。

| 状态/区域 | 必须呈现与行为 | 禁止 |
|---|---|---|
| 未登录 | 品牌入口＋登录操作，不请求业务卡 | 假账号、令牌粘贴入口 |
| 登录中/回跳验证 | 有界加载、明确失败后重新登录 | 空白页、无限重定向 |
| 未映射/无任职 | 安全的“账号尚未接入”或“尚无可用任职”，联系管理员 | 误报“今天工作已完成” |
| 多任职 | 本人的任职选择，无默认猜测；切换清旧 scope | 用别人的 Appointment 获权 |
| 无工作台资格 | 安全“尚未配置工作台权限”；管理模式可独立有权 | 用无任务零态掩盖配置缺失 |
| 初次读取 | 摘要区加载占位；计数未知，不显示假 0 | 从旧用户缓存拼屏 |
| 今日摘要 | 展示当前服务端安全 todaySummary，一句说明责任；日期语义按部署业务时区 | 把“当前一张卡”说成“今日总任务 1” |
| 当前卡 | 0 或 1 张；Owner、SLA、版本、候选状态明确；仅一业务主动作 | 第二张完整卡、管理员伪造 Task |
| 后续摘要 | 0～2 条；空时“暂无后续责任”；只摘要无处理按钮 | 由摘要直接提交或暴露完整 payload |
| 等待数量 | 服务端可见 WAITING 数；0 表示没有等待，不表示没有其他工作 | 本地乐观加减、Worker 成功直接视为客户端已更新 |
| 正常零态/仅等待 | 分别“当前暂无可处理责任”和“当前无可处理责任，另有等待事项” | 把隐藏/无权责任数量泄露出来 |
| 未保存/保存中/已保存 | 明确区分；保存中禁止重复保存；已保存仍需明确主确认 | Draft 成功就说责任已完成 |
| 提交中 | 禁用重复主提交；保留原 key；声明正在处理 | 连点重复命令 |
| 提交已确认、刷新成功 | 以 Receipt 为准显示已记录，再展示最新 envelope | 用 GET 成功推断 POST 成功 |
| 提交已确认、刷新失败 | “结果已记录，当前责任刷新失败”，仅重试读取 | 鼓励再次提交相同业务 |
| 结果未知/查回执中 | “结果尚未确认”；同页可原请求重试；跨重登仅按标记查原回执 | 将断网、超时、Receipt GET 失败当业务失败 |
| 正常刷新/304 | 保留同 scope envelope、dirty 输入和逻辑焦点；304 也须先后端披露审计 | 清空尚未保存的字段 |
| 陈旧上下文/412/digest | 阻止旧候选提交，明确重新读取并核对；键策略按最新错误 | stale 后继续复用错误修正键 |
| 网络/503/429 | 显示暂不可用及有界重试；旧内容若保留则标记非最新、禁止写入 | 伪造空 envelope 或假 0；无限轮询 |
| 401/403/404/撤权 | 清除敏感卡与候选，提供安全登录/刷新说明，不推断资源存在 | 继续渲染缓存以泄露撤权内容 |
| 会话续期/到期 | 同 scope 续期保留编辑和未决请求；真正到期停写并安全重登 | 每次 token rotation 都重新挂载工作台 |

等待自动刷新保留 30 秒间隔、每 mounted identity epoch 最多 6 次；回执自动查询最多 3 次，均可见性门控，无新 key。额度用尽显示“自动刷新已暂停，可手动刷新”，不能假装仍实时。令牌续期不得重置额度；普通刷新不触发 Worker。实际 WAITING→OPEN 只能来自当前业务恢复机制。

## 7. 视觉和交互约束

工作台沿用现有暖白/石墨/翡翠绿/薄荷 tokens、中文字体栈与 Phosphor 图标；ADM-01～04 使用已冻结的 `docs/design/identity-admin-mvp/frozen/` 对应画面和仅管理模式侧栏，不新造看板或普通用户菜单。登录和任职选择是新增画面，没有已冻结高保真：先提供同风格设计确认，再实施 UI；Keycloak 登录主题只做同品牌基础样式，不重写凭据表单安全机制。

普通工作台顶栏可新增本人身份、切换任职和退出的紧凑会话操作，不混入管理业务导航；管理人员通过单独已授权 `/admin/identity/*` 地址进入。每个管理页面仅一个视觉主操作，危险生命周期动作二次确认且解释不可逆；禁用不只靠颜色。全部动态提示使用合适的 live region，错误关联字段，弹层键盘焦点可进入/返回，按钮触控区至少 44px。

真实浏览器验收包含 360/768/1440 宽度，固定 composer 不遮挡字段/提示/主操作，键盘焦点在刷新、会话续期、任职切换和弹层关闭后正确。ui-ux-pro-max 只用于可访问性、提交反馈与恢复流程建议；其通用配色/营销布局建议不覆盖现有冻结风格。

## 8. 接口与合同后继边界

接口提案列于配套计划，共新增 **21 个业务 API operation**：本人 context 1、IdP 候选查询 1、管理选项查询 1、四类列表 4、四类创建 4、Principal 改名/挂起/恢复/禁用 4、组织改名/关闭 2、任职挂起/恢复/结束 3、Grant 撤销 1。旧 16 个保留，提案合计 **37 = 32 public Bearer + 5 internal mTLS**；Keycloak 自身协议端点和离线 bootstrap 不计入业务 OpenAPI。该数字是待确认提案，不是当前冻结 inventory。

管理 mutation 共 14 个具名命令，均 INTERNAL_ADMIN/HUMAN/DIRECT；bootstrap 独立离线信任根例外。getCommandReceipt 的新身份终态分支使用受控 Identity Fact 引用，原九公共业务命令 body/主完成 Fact 不改；新增命令的回执读取必须扩展原 Actor 元数据、当前管理 scope 和披露审计规则，不能依旧按 Lead/Task 绑定硬套。NOT_FOUND terminal enum 同步修正，不再依赖前端临时 union。

实施前的 Task9.1 必须一次对齐：新的具名 ADR/基线、HTTP/Workbench/Command 合同、Identity 管理/self-context/引导披露规则、OpenAPI/生成类型/门禁 inventory、模块边界和 Runtime 锁序说明。需要新数据库能力时仅允许具名最小前向授权迁移并独立验证；不得手改既有 DDL 或扩大 API/Worker 登录角色。若必须新增应用持久表，则停止并另行申请物理合同修订，不能在本设计内自行批准。

## 9. 验收与 Task10 的边界

Task9 的完成门改为：四类受控管理、真实 OIDC 登录/会话、多任职/撤权、七类卡页面完整联通、状态/恢复矩阵、跨 Tenant 安全、真实人工账号进入系统均通过。详见 [Task9 扩展验收矩阵](../../acceptance/2026-09-08-task9-real-user-access-acceptance.md)。

自动化使用真实 Keycloak、真实 PostgreSQL、实际 API/SPA 和可控 Worker，只使用合成业务数据及隔离测试账号；不得用注入 JWT、SQL 插入目标用户、mock fetch、测试登录开关或写配置硬塞 Actor 替代这条链。除一次性受控 bootstrap 外，验收目标用户/组织/任职/Grant 必须经本设计允许的管理路径建立。

另有指定真实使用者的人工 UAT：用户自己输入凭据，验证登录、查看本人卡、保存/提交、退出与重新进入；只留脱敏时间、构建、账号别名、场景和结果，不采集密码。没有该证据不能把扩展 Task9 标完成。

Task10 仍负责更广的 R1 全 BranchID、Worker 故障/披露与恢复矩阵、严格 CI、完整证据链和参考容量门禁。Task9 测试在 Task10 复用，不复制两套登录或用户创建夹具；增加 Keycloak/introspection 开销后容量评估必须显式包含该依赖，但不得擅改原容量业务向量或降低参考资源。

## 10. 审阅门

本设计及详细接口 inventory、会话恢复标记、管理权限和 bootstrap 边界已获用户确认。当前按配套计划 Task9.1 先收口合同并独立评审，不跳过安全边界先画表单或开接口；Task9.2～9.6 的实现和运行时验收不因本次批准自动完成。新增登录/任职画面仍须按 §7 单独确认视觉。
