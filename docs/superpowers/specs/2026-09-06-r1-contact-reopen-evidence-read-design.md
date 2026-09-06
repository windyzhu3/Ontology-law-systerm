# R1 主管重开联系与证据引用只读边界修订规格

日期：2026-09-06。状态：DESIGN_CONFIRMED / WRITTEN_SPEC_PENDING_REVIEW。

用户已确认对话中的两项最小方案：全局联系序号不重置、第三次及以后未接通转主管复核；证据仅通过最小Evidence Owner只读边界校验既有引用。本文件将已确认方案精确化，待用户审阅书面规格后进入详细计划。它尚未激活活动合同，不是业务实现或运行验收证据。

基准提交：`9892ec73aea3472d41dd89787aa650ae5069b810`。实施位置为既有隔离分支`codex/r1-lead-contact-vertical-slice`。本修订仅解除[收口计划](../plans/2026-09-05-r1-business-closure-plan.md)原Task 6的两项前置冲突；Task 1–5不重做，Task 7–10不自动启动。

## 1. 问题、权威与取舍

现行[Task矩阵](../../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)允许第三次未接通后主管选择`REOPEN_CONTACT`，但仅定义`contact_no=3`的耗尽复核因果资格。[事件校验](../../../backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/R1EventPolicy.java)拒绝所有序号大于3的结果。[V080](../../../database/schema-contract-52-plus-2/generated/db/migration/V080__lead_tables.sql)又要求Lead内序号从1追加、不可变且唯一。因此新建第四张联系任务不足以形成可完成闭环。

[HTTP矩阵](../../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)允许可选`evidenceSubmissionId`并要求同Tenant可见的准确Submission，但当前Java模块/DAG/jOOQ Owner白名单缺少Evidence读口。[V830](../../../database/schema-contract-52-plus-2/generated/db/migration/V830__application_privileges.sql)已有对应SELECT；不需要新的数据库授权。

| 选择 | 结论 |
|---|---|
| 全局序号持续递增，第三次起关闭自动重试，每次额外联系由主管明确新建 | 已确认；不引入重试轮次或新字段。 |
| 每次主管重开重新获得三次重试 | 不采用；需要独立轮次及因果规则，扩大自动行为。 |
| 第三次后禁止主管重开，或只创建不可完成任务 | 不采用；取消既有能力或留下半闭环。 |
| 通过Evidence Owner校验既有提交和绑定 | 已确认；保留Fact Owner与授权边界。 |
| Lead跨Owner查表、仅凭外键存在或开放证据上传/浏览平台 | 不采用；前两者绕过边界，后者超出R1。 |

批准并激活后，本规格只具名替代上述次数解释和缺失的只读模块边界，其他已批准基线继续有效。历史规格、迁移及已发布证据保留原文，不原地伪造历史一致性。

## 2. 联系总序号与自动重试

### 2.1 唯一计数口径

`contact_no`是同Tenant、同Lead的全局联系结果序号。从1开始，在现有Lead排他业务锁内按历史最大序号加1分配；无历史结果时为1。它不是重试轮次内计数，也不是Assignment内计数。主管重开、Owner任期或Assignment变化均不得重置、覆盖或复用它。

不新增`attemptNo`持久字段或轮次字段。活动分支合同中的`attemptNo`必须同步改为明确的`contactNo`谓词，避免两个计数器并存。计算使用安全整数边界；无法递增时在任何事实写入前以既有技术错误整体回滚，不截断或回绕。

“最多三次”仅限制无需新增主管决定即可推进的初始联系序列：第1次和第2次未接通可各创建一次自动重试，第3次及以后不能自动创建联系重试。主管可以再次明确决定一次额外联系；本修订不另设累计人工联系次数上限，也不允许后台自动模拟主管决定。

### 2.2 完整结果表

| 本次结果 | 全局序号 | 原Task完成事实 | 唯一后继或边界 |
|---|---:|---|---|
| CONNECTED_VALID | 任意合法正序号 | 本次不可变ContactResult | 原子开立准确Opportunity；无R2 Task。 |
| NOT_CONNECTED | 1 | 本次不可变ContactResult | 下一当地工作日10:00恢复的新CONTACT Task。 |
| NOT_CONNECTED | 2 | 本次不可变ContactResult | 下一当地工作日15:00恢复的新CONTACT Task。 |
| NOT_CONNECTED | ≥3 | 本次不可变ContactResult | 新REVIEW_LEAD_VALIDITY，reason=CONTACT_RETRY_EXHAUSTED；无自动CONTACT。 |
| SUSPECT_INVALID | 任意合法正序号 | 本次不可变ContactResult | 新REVIEW_LEAD_VALIDITY，reason=SUSPECT_INVALID。 |

前两次重试继续使用`CONTACT_RETRY_V1`、Source Policy IANA时区、`CN_WEEKDAY_V1`、既有DST处理及受控可用渠道切换规则。SLA原始截止为恢复后30分钟；新Task先OPEN/revision0，再同事务WAITING/revision1并追加WaitReceipt。到期恢复仅WAITING→OPEN，不改Owner、subject、SLA或WaitReceipt。

### 2.3 主管重开

主管三个决定保持`CONFIRM_INVALID|CLOSE_UNREACHED|REOPEN_CONTACT`。前两者以DecisionRecord完成复核，不创建后继；后者以DecisionRecord完成复核，创建一张OPEN/revision0 CONTACT Task，绑定当前准确Lead版本及唯一有效OPEN Assignment Owner。Owner/Assignment/授权失效时按既有拒绝合同处理，不猜测替代负责人。

新联系任务按普通首次可行动Task计算原有30分钟业务SLA，不伪造WaitReceipt。旧CONTACT及旧REVIEW保持DONE，不能重开或复用。

所有新结果继续按2.2表处理。例：第1次疑似无效→主管重开→第2次未接通，仍允许按第2次规则自动安排第3次；第三次未接通→主管重开→第4次未接通，只能再次进入主管复核。主管重开不补充已消耗的自动额度。

### 2.4 因果选择与通知

REVIEW的准确触发结果资格改为`SUSPECT_INVALID`或`NOT_CONNECTED且contact_no>=3`。其他条件保持：同Lead、来源CONTACT Task的完成事实准确指回结果、结果时间不晚于复核Task创建时间，按最大`(resulted_at, UUID无符号网络字节序)`选择；必须是导致该复核Task创建的同事务结果，不能选择更早的第三次结果冒充第四次结果。

CurrentCard、主命令重验及事件校验使用同一资格定义。REVIEW scope、Draft字段与不可变ContactResult行摘要规则不变。

`CONTACT_NOT_CONNECTED_RETRY`谓词为`contactNo<3`，`CONTACT_NOT_CONNECTED_EXHAUSTED`谓词为`contactNo>=3`。不新增BranchID、EventType或QueueOwner。耗尽继续发`LeadContactRetryExhaustedV1`；有效接通仍恰2 Event/2 Outbox、1 Receipt/1 Audit，其余分支仍按既有准确集合。Receipt和原Task完成事实仍是ContactResult，Opportunity不能替代它。

## 3. Evidence引用的最小读口

### 3.1 准确引用资格

`evidenceSubmissionId`仍可省略，省略时不读取Evidence。不接受调用方传Tenant、Grant、绑定ID、版本/hash、对象地址或文件内容。

出现时，只认可当前Tenant下已有的不可变`evidence.evidence_submission`及其唯一`evidence.evidence_binding`，且绑定未撤回、`target_type=lead.lead`、target ID及revision准确等于当前Task绑定且锁内重验的Lead selector，target hash为空。不接受其他Lead、其他版本、Task目标、其他业务目标、已撤回或无绑定的提交；不沿旧版本或同ID猜测可见性，不自动修复/迁移绑定。

只校验已经存在的引用，不将其解释为业务审核通过，不新增用途、上传、扫描、晋级或绑定动作。已有Evidence物理晋级链与不可变约束保持；R1不重新下载或扫描对象，不伪造提交事实。没有合格既有证据时可以省略该可选字段，不能用静态示例ID绕过验证。

### 3.2 Owner与字段边界

增加纯Java的Evidence Owner只读公开端口及该Owner的`internal.persistence`实现。SQL/jOOQ及生成类型只属于Evidence，不进入Lead、API或Query。新增模块依赖限于`lead→evidence`、`api→evidence`（组合读取/披露）和`evidence→identity`（共享准确selector类型）；Evidence不依赖Lead、responsibility、execution、api、query或worker，不形成环。其他既有边不改变。

读口只访问两张已有表：

- `evidence_submission`：准确ID及其不可变行字段，用于构造提交的准确selector；不沿`received_source_object_id`访问来源对象。
- `evidence_binding`：准确ID/revision、submission ID、target selector和撤回状态，用于验证有效关系。

提交selector为`evidence.evidence_submission@hash`：沿用`R1_JSON_JCS_SHA256_V1`，覆盖不可变Submission全部持久字段，SQL snake_case属性名，唯一例外`tenant_id→tenantId`，UUID/六位UTC时间按现有规范编码。绑定selector为`evidence.evidence_binding@revision`。这些是内部事实selector，不新增HTTP字段；不把对象字节SHA256冒充Submission hash。

不读取`upload_session`、`received_source_object`、文件名、正文、object_key、对象版本地址或能力凭据。端口只返回上述资格判断所需selector与关系，不返回对象位置。不新增Evidence Repository写方法、列表/搜索接口或下载URL。

### 3.3 授权与事务边界

复用当前首联的`ASSIGNMENT_OWNER / SALES_CONTACT_OWNER`及真实Task Owner组织scope，只接受已有HUMAN直接或合法一跳委托路径。先通过Task/Lead/Owner准确授权，再查询最小Evidence元数据；同Tenant存在或当前任职曾提交该证据都不能替代授权。

Submission和Binding均在现有业务Subject白名单内，须分别对准确Submission hash及Binding revision评估所选`SALES_CONTACT_OWNER`权限的对象DENY，同时保持Task/Lead DENY。只复用该命令的完整授权路径，不引入OBJECT-only ALLOW替代、不拼接多个不完整Grant、不增加EVIDENCE_READ权限代码。

初始读取与提交前最终复验使用现有同连接Runtime；后者在既有业务锁及identity shared锁下，以新鲜数据库时间复验完整授权和同一Submission/Binding/Lead关系。业务拒绝不得留下ContactResult、Opportunity、CONFIRMED Draft或Task迁移。

Binding是可撤回的事实，identity锁本身不能保护其状态。任何未来会影响R1引用的Evidence绑定写者必须在修改前加入现有R1 Tenant业务排他围栏，并遵守业务锁先于identity锁的顺序；本修订不实现这种写者或新增锁种类。读取路径采用既有共享围栏，命令采用既有排他围栏；测试中的撤回写者同样遵守此协议。未遵守协议的直接数据库管理写入不在应用并发保证内。

证据不存在、跨Tenant、不具资格或不可见均使用已有安全`NOT_FOUND`，不返回是哪张表/绑定/权限出错；按发现阶段维持既有pre-slot全零或post-slot仅REJECTED Slot/Receipt/Audit的差异。技术故障整事务回滚并沿用现有错误分类，不作为业务拒绝吞掉。

### 3.4 草稿、工作卡与缓存

保存Draft只保存规范候选值，不证明证据已经通过主命令资格校验，不产生Evidence事实。主命令不能信任Draft中的证据ID而省略Owner重验。

CurrentCard恢复含该ID的CONTACT Draft时，必须经过同一Evidence资格和逐来源授权后才披露ID。不可见或绑定失效时，不披露该完整卡中的候选值；沿用现有不合格卡选择下一张/安全零态行为，不静默删除候选字段以改变Draft digest。

准确Submission/Binding selector及其授权依赖加入现有Workcard ETag与披露Audit来源集合；不把敏感元数据加入展示DTO或日志。200和304都按既有敏感读取协议重新校验并在审计提交后返回，旧缓存不能绕过撤权/撤回。该改动是Task6既有证据字段的安全闭合，不是新增证据浏览功能。

## 4. 明确保留的范围约束

- 一个SPA、一份OpenAPI、一个模块化单体Jar，API/Worker互斥；11 public＋4 internal operation及既有DTO字段形状不变。
- 13 Schema、52应用表＋2技术表、活动物理能力`52-plus-2-v1.2`保持；V001–V860、manifest、field contract、DB权限及历史证据字节不变，无新迁移。
- Evidence既有数据库SELECT足够；模块读口增加不意味着可扩大数据库角色能力。jOOQ只生成本修订需要的两张Evidence表到所属Owner包，不生成DAO或Active Record。
- 锁顺序、身份授权路径、完成事实、Draft确认、Command幂等与原子Receipt/Audit/Event/Outbox不变。
- 不新增自动重试轮次、Evidence上传/下载/管理界面、Provider发送、AI、ADM-01～07、R2+、通用回放或投影表。
- 不因合同修订提升R1-BACKEND、SPA、E2E、容量或发布门禁。容量环境仍后补；历史容量向量冲突不在此次修订范围内。

## 5. 活动合同同步清单与生效条件

书面规格获批后，单独实施并验证具名合同修订：新增ADR-0011明确替代范围，将语义基线推进至`MVP-2026-09-06.3`、Task完成合同推进至`R1-TASK-COMPLETION-V1.2`；其他合同标识若保持不变，必须明确引用本次语义基线及ADR。物理能力版本不推进。这些版本在本文件中是待激活目标，不能当作已生效。

| 受影响来源/消费者 | 必须同步的内容 |
|---|---|
| 当前基线、具名ADR、Task完成矩阵 | 全局序号、<3/≥3分支、主管重开、准确复核因果链及最小Evidence边界。 |
| 命令授权/事件、HTTP、Workbench合同 | 既有事件集合保持；Evidence引用授权/安全错误/披露来源；HTTP字段形状与operation数保持。 |
| 原收口设计/计划的活动补充索引 | 指向具名替代，不把历史Task6勾成已完成；原Task6后续消费本修订。 |
| baseline verifier及负向变异测试 | 拒绝旧=3因果规则、事件max3、轮次重置、第四次自动重试、缺Evidence逐来源授权或DAG越界。 |
| 后续Task6 Runtime/Owner/CurrentCard测试 | 事件校验合法>3结果，耗尽≥3，准确最新因果结果；Evidence Owner及授权/披露/缓存测试。 |
| 架构与jOOQ生成配置/漂移测试 | 只允许本规格新增的Owner和依赖边、两张表生成，其他Owner隔离及无环检查保持。 |
| 进度台账 | 分开记录合同已对齐、后端已实现、HTTP/SPA/E2E未完成；不提升业务完成状态。 |

合同与验证器先形成一致、可机械验证的提交，再恢复原Task6实现及独立评审。不得仅删除事件上限而保留旧CurrentCard资格，也不得只增加Evidence包白名单而绕过引用授权。此处列出同步面，不替代随后逐步实施计划。

## 6. 有限验收矩阵

1. 实际Handler执行三次未接通→主管重开→第四次CONNECTED_VALID，准确完成事实、唯一Opportunity、2 Event/Outbox及1 Receipt/Audit；无R2 Task。
2. 同一前链的第四次NOT_CONNECTED创建准确第四次结果触发的REVIEW，无WAITING自动重试；再次经主管明确重开产生第五次结果仍按同规则收口。
3. 第一次SUSPECT_INVALID→主管重开→第二次NOT_CONNECTED，保持下一工作日15:00及原SLA/渠道规则；不得回到第1次或补发新一轮额度。
4. 序号并发、重放、唯一性、安全递增溢出及不可变结果；主管三决定及旧Task永久终态；旧第三次selector不能完成第四次触发的复核。
5. 可选证据缺省零Evidence读取；合法既有Submission＋准确有效Lead绑定可引用；跨Tenant、他Lead、旧revision、其他目标、无绑定、撤回或不可见均安全失败。
6. Task/Lead/Submission/Binding各自DENY，直接/委托范围与到期、最终撤权；拒绝外键存在即放行、OBJECT-only替代、提交人身份替代授权。
7. 遵守业务围栏的绑定撤回竞争、CurrentCard/Draft引用回显与200/304审计；撤回、DENY或失效绑定不能沿旧ETag泄露ID。
8. Event2/Outbox2/Receipt/Audit故障、技术错误与拒绝的准确delta；跨Tenant哨兵零变更。Owner/角色实库、架构/jOOQ漂移及活动合同回归均必须有实际执行证据。

计数按实际测试方法/参数调用报告，不把情景循环或已有底座回归冒充新增业务测试。现有25项起始回归仅证明旧底座可用，不证明上述验收完成。

## 7. 书面规格自审

- 全局序号和自动上限分离；早期疑似无效重开与耗尽后重开均有确定后继，无新增隐式轮次。
- 事件、复核创建、CurrentCard选择和提交重验同步，不留下第四张不可完成卡或错误第三次因果引用。
- 证据是既有准确Lead版本的引用，不是文件读取或业务审核；Submission/Binding自身DENY不被Lead权限覆盖。
- 只读模块关系无环、SQL归Owner、两张表生成范围明确；角色权限和物理合同不扩大。
- Binding撤回由业务围栏保护而非误用identity锁；披露与提交共用准确引用资格，缓存不绕过审计。
- 版本、实施与验收状态分开；本文件不激活合同、不完成Task6、不授权推送/部署。
