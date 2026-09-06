# R1本地实施进度（2026-09-06）

> 当前结论：收口计划Task 1–5已完成本地实现、验证与独立评审。Task 6预检发现的两处边界已有用户确认的最小方案，现已形成[主管重开与证据只读修订规格](../superpowers/specs/2026-09-06-r1-contact-reopen-evidence-read-design.md)，等待书面审阅后进入详细计划；活动合同尚未激活、业务代码尚未修改，Task 6未完成。生产HTTP安全装配、前端接线和R1整体验收仍未完成，不能称“基础功能全部实现”。Task 7–10未启动。

本轮用户已确认：联系总序号永久递增，第3次及以后未接通只转主管复核，主管每次明确重开一张新Task、不补充自动额度；Evidence只校验既有、同Tenant、有效且准确绑定当前Lead版本的引用。规格补充了Submission/Binding自身DENY、工作卡披露与缓存、绑定撤回的既有业务围栏要求。仅新增书面规格和更新进度，没有执行迁移、GRANT、业务实现、推送或部署；基线版本仍为MVP-2026-09-06.2/52-plus-2-v1.2。

## Task 6预检记录：现已形成上述修订规格

本轮基于`9892ec7`在现有隔离工作区继续原Task 6，未进入后续Worker/HTTP/SPA范围。起始回归`task-6-start-baseline.log`于2026-09-06T21:25:44+08:00实际exit0：16项unit/architecture＋9项IT，0 failures/errors/skips，49.955秒；这些是继承组件基线，不是新增首联业务完成证据。

核对发现：

- [Task矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)允许第三次未接通后创建主管复核，且`REOPEN_CONTACT`须新建联系Task。
- [V080物理合同](../../database/schema-contract-52-plus-2/generated/db/migration/V080__lead_tables.sql)明确`contact_no`在Lead内从1递增且唯一，不能将第四次重新编号为1或3。
- [现有事件校验](../../backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/R1EventPolicy.java)要求所有联系结果序号不超过3，连第四次`CONNECTED_VALID`也不能提交；Task矩阵中未接通复核因果绑定又仅接受`contact_no=3`。

因此“第三次未接通→主管重新联系→新任务提交结果”目前缺少一致的已批准规则。只实现新建Task会留下无法完成的责任卡；重置序号违反物理合同；擅自新增重试轮次或放宽次数会改变设计。需要先明确全局联系序号与自动重试上限的关系、主管重开后的结果及后继规则，再同步相关合同和验证器。当前没有采用任何新规则，未修改生产代码、迁移、权限或API，Task 6尚未完成。

另一个需明确的既有接口边界：[HTTP矩阵](../contracts/r1/R1-HTTP-ERROR-PRECONDITION-MATRIX.md)要求可选`evidenceSubmissionId`必须指向同Tenant可见的准确EvidenceSubmission，但当前Java模块白名单、依赖DAG及jOOQ Owner白名单未包含Evidence Owner，也没有既有只读端口。V830已经授予对应角色SELECT，因此这不是新的数据库权限问题。不能让Lead直接查询其他Owner的表，也不能用外键存在性代替可见性授权。建议仅补既有证据引用的最小只读Owner/授权边界，不实施上传、证据管理UI或新接口；该建议尚未获批或实现。

以下Task 5及更早章节保留历史证据；其中“本轮不进入Task 6”的表述仅指当时轮次，当前状态以上文为准。

前置合同修订（ADR-0010）：四列QUERY能力修订已完成本地实现、验证与独立规格/质量评审，提交`f0eb0ab`及评审修复`1543f49`。活动基线为`MVP-2026-09-06.2`/`52-plus-2-v1.2`。下文历史章节中的“待确认/未激活/V850全槽禁读”不代表当前状态。原Task 5读取阻塞已解除，其消费者现已完成；旧v1.1托管RUNTIME_VERIFIED仅属于旧合同，SPA/E2E/容量/发布状态未提升。

能力实库门禁：`IngressQueryCapabilityIT` 5例＋`CapabilityRoleExecutorIT` 8例，2026-09-06T16:57:01+08:00，Maven verify exit 0（57.279秒），另14例unit/architecture。覆盖真实新库、V850九列禁读、四列读取与五列/整行/写拒绝、无GRANT OPTION、真实Lead/补全数据升级与非QUERY ACL/登录成员保持、SQLSTATE 55000故障回滚及恢复重试、Flyway validate与no-op；现有runtime SQL在真实数据库拒绝遗漏V860、旧版本、额外列授权和GRANT OPTION。完整后端71 unit＋254 IT、schema 58例与generate --check、runtime 121例、baseline 243例均exit 0。评审发现并修复旧运行证据可能满足新版门禁的问题，受影响227例复验通过（192.211秒，非新增227例）。主控对修复提交实跑baseline CLI exit 0：baseline consistency PASS、R2 BLOCKED 7，明确包含v1.2运行证据未满足项；V001–V850/OpenAPI/事件/历史证据字节保持不变。独立复审确认全部问题已处理，无新增Critical/Important。日志保留既有工具警告，不声称warning-free。详细原始日志与报告保存在本地计划工作区。

当前位于本地分支`codex/r1-lead-contact-vertical-slice`。本记录区分合同修复、已有组件和真实业务交付；不代表已合并、已部署或R1已验收。

当前已经进入**R1 MVP业务的后端实施**，不是仍只在搭建通用基础设施，也不是前后端已打通。基础授权/事务、草稿和审计读取组件及Task1–5已完成本地实现、验证和独立评审；完整基础使用闭环仍缺HTTP安全装配、前端及E2E，完整R1业务还缺首联/复核/恢复与Worker。Task编号不代表工作量百分比。

| 层次 | 当前结论 |
|---|---|
| 后端业务 | 线索接入、重复确认、信息补齐、分配、路由处置、来源停用请求确认，以及草稿保存/编辑/读取与主命令确认已实现并实库验证；尚非全部R1业务。 |
| 工作卡读取 | 七种卡、草稿恢复、安全摘要/零态、逐来源授权、200/304审计提交后返回已完成后端验证；首联/复核卡读取夹具不等于对应业务命令已实现。 |
| HTTP接口 | 尚未完成生产安全装配和业务接线；已有OpenAPI合同不等于接口已可用。 |
| 前端 | 尚未与上述后端业务打通；本步没有页面交付。 |
| 整体验收 | 浏览器E2E及完整容量未完成；容量环境按用户要求后补。 |

| 范围 | 当前证据与状态 |
|---|---|
| P0-01合同前置修复 | [ADR-0009](../adr/ADR-0009-p0-duplicate-automatic-assignment.md)与[Task V1.1矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)将有界自动Assignment指针与解析变更合为一次Lead CAS，保持revision +1、Decision完成事实与既有digest/event含义；基线升级为MVP-2026-09-06.1。Python验证结果见下文。 |
| 当前收口计划Task 1 | 本地合同对齐已完成，最终修订提交`58abbc432a609248c17d85157db799ff8e513737`；SERVICE capture、15个operation、审计披露与投影确认合同已落地。此前报告中曾有测试误报，已在原报告更正，不能引用被撤销的PASS。 |
| 当前收口计划Task 2 | 本地基础授权/运行时围栏已完成并提交`3a2b63a556297e81842421f52d50950695209d64`，真实PostgreSQL回归证据保留在原Task 2报告。它不代表生产Lead业务命令已实现。 |
| 当前收口计划Task 3 | 本地实现及独立评审已完成，代码提交`df9b985a2b371e93016890fd1fc8ffc7e50415f7`。六个生产capture/P0 Handler及必要Owner读写、真实Draft原子确认、保护/规范化、Task后继/等待已实现。四个生产P0 IT类共33个测试，覆盖各分支、准确delta、幂等、权限变更、并发与技术失败回滚。固定运行时最终回归`task-3-resume-22-final-regression.log`：67 unit/architecture +218 IT，0 failures/errors/skips，进程exit0；218包含继承的既有运行时回归，不能当作218个新增业务测试。独立评审：Spec compliant、Task quality Approved，无Critical/Important；不代表已合并、部署或全R1验收。 |
| 当前收口计划Task 4 | 本地后端实现、验证和独立评审已完成，代码提交`03573e93e03e0a5d71d7f2b6c5c93ea7cfe07fbd`。生产SAVE_ACTION_DRAFT通过现有CommandRuntime持久化、CAS编辑、NO_CHANGE和幂等重放；保留Task3原子确认，七种冻结候选校验与Task/Draft/Lead/Owner准确来源读口已实现。专项5个unit及30个新增Draft/读口IT通过，完整回归71 unit/architecture +249 IT通过，进程均exit0。独立评审Spec compliant、Task quality Approved，无Critical/Important；未连接HTTP或前端，不代表全部R1业务验收。 |
| 当前收口计划Task 5 | 本地后端实现、验证和独立评审已完成，代码`555d2f5`及修复`cef0431`；完整初始回归72 unit/architecture＋282 IT通过，修复后定向13 architecture＋33 IT通过。详细版本与独立复验见下文；未接生产HTTP或前端。 |
| 当前收口计划Tasks 6–10 | 尚未完成。首联/复核/等待恢复、Worker、HTTP装配、SPA与整体E2E/容量验收仍待实现或验证；本轮不进入。 |

以上Task编号来自[2026-09-05收口计划](../superpowers/plans/2026-09-05-r1-business-closure-plan.md)，与早期R1计划编号不可混用。Task 1/2/3原始报告和日志保留在本地忽略目录`.superpowers/sdd/2026-09-05-r1-business-closure-plan/`的`task-1-report.md`、`task-2-report.md`、`task-3-report.md`。Task3报告保留原NEEDS_CONTEXT检查点、真实RED/GREEN与失败迭代、最终接口和逐分支证据；Task4在后续授权后单独实施，报告为同目录`task-4-report.md`；Task5随后在能力修订获批后单独完成，见下文。以下历史Python结果仍是前一轮合同修复证据，不冒充Task5重新执行。历史托管与合并证据见[2026-09-05分层验收报告](2026-09-05-r1-contract-closure-acceptance.md)。

## Task 5本轮完成：后端工作卡读取与披露审计

本步以已评审能力修订后的`43b1926`为起点，完整实施范围为`43b1926..cef0431`。纯Query只组合Owner数据；Owner使用明确的QUERY可读列，保留原始联系方式语义并按正确AAD读取有效补全联系方式。七种卡及DRAFT/CONFIRMED状态按冻结OpenAPI校验；重复候选、Assignment、因果Decision/ContactResult和Owner标签分别绑定准确来源，不从草稿推断Fact。

生产服务在同一READ COMMITTED连接上取得租户业务共享锁，逐来源授权，再在identity共享锁下用新鲜时间重建整份卡片/来源/授权；随后切换AUDIT并追加去重后的准确披露记录，只有提交确认后才返回正文或304。真实HTTP测试桥、独立数据库观察连接及受控等待验证提交前零成功字节/ETag，审计第N条失败全回滚，提交成功但确认丢失安全503且重试新增审计。该HTTP桥仅用于测试，生产认证/接口装配仍属于Task 8。

验证证据分版本记录，不混报：

- 初始实现`555d2f5`：`task5-full-backend.log`于2026-09-06T18:49:52+08:00实际exit0，72 unit/architecture＋282 IT，0 failures/errors/skips，05:34。其中新增1个架构测试及28个IT；其余为继承回归。主控保存并复算原始XML得到相同总数。
- 独立评审发现读取阶段直接SQLException被统一映射503，而同类包装异常按类型映射500/503。修复`cef0431`区分读取与提交确认阶段，统一直接/包装SQL分类，同时保留审计失败和提交不确定性的503语义。
- 修复后`task5-fix1-green.log`于19:08:19实际exit0，13 architecture＋33 IT，0 failures/errors/skips；这是覆盖修复的定向回归，不声称在修复版本重跑了完整72/282套件。主控提交后另实跑13 architecture＋12个准确错误分类/初始化/提交确认测试调用，19:11:52 exit0，44.021秒。
- 完整Task 5独立评审及修复的限范围复审均已完成：唯一Important问题已处理，无新Critical/Important或未关闭阻断项。生成器/JAXB/Flyway等既有警告及部分长行可读性保留为整分支非阻断复核项，不声称warning-free。
- 主控完整提交范围的`database/`、`contracts/`、`apps/`及`docs/contracts/`差异为空，OpenAPI摘要未变，`git diff --check`通过。真实基线CLI一致性PASS，R2仍BLOCKED 7，包含活动v1.2托管运行证据未满足项；不推进发布或容量状态。

本轮边界处理：无法识别的可选缓存标签按不匹配处理，仍完整授权/审计后200，不增加合同外400；若将来要求拒绝，须显式修订HTTP合同。首个规范重复候选不可见时排除该完整卡，选择下一张可操作任务或安全零态，不用第二候选冒充；若将来要展示“有权限但不可操作”的任务，需明确交互约定。前置修订中的固定Python/容器Git路径调整仅修正测试运行方式，代价是维护对应夹具命令，不改变产品能力或权限。V850禁止同一Lead原始联系方式与补全槽共存，测试按真实可达状态修正，未放宽CHECK。

整体下一步是原Task 6：首联结果、等待到期恢复、主管复核及其原子完成事实；之后才是Worker、生产HTTP、SPA和联合验收。没有新增业务范围、推送、合并或部署。原始实现/评审/修复报告与日志保留在本地忽略目录`.superpowers/sdd/2026-09-05-r1-business-closure-plan/`。

## 历史：Task 5预检时的读取能力合同阻塞

用户随后回复“继续”，同意先做最小只读能力修订。已形成[四列精确权限与迁移规格草案](../superpowers/specs/2026-09-06-r1-ingress-query-capability-design.md)，等待确认具体字段和后继版本：仅补全phone/email密文及HMAC四列SELECT，其余五列仍禁读；旧V001–V850保持字节不变，通过独立后继迁移实施。当前只新增书面设计，尚未激活合同、执行GRANT或开始Task5生产实现。

本轮基于`610c16b`继续，发现上一步记录的依赖确实不能仅靠补Java接口解决：[Task矩阵](../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)第114–116行要求重复候选同时比较原始及后补phone/email HMAC，[展示合同](../contracts/r1/R1-WORKBENCH-PRESENTATION-CONTRACT.md)第87行要求服务器提供准确候选selector；但[批准规格](../superpowers/specs/2026-09-05-r1-business-closure-alignment-design.md)第5.2节限定QUERY读取，而[V850](../../database/schema-contract-52-plus-2/generated/db/migration/V850__lead_ingress_completion_slot.sql)第135–160及243–250行明确禁止QUERY读取任何补全槽字段。

只比较原始联系方式会漏掉或错排合法候选；从Draft还原会把草稿当作Fact真源；临时切换COMMAND、新增提权函数或SELECT授权都会改变冻结的权限/设计边界。因此暂停Task5生产实施，等待用户确认是否先进行最小必要的只读能力设计及合同受控修订。至少两个补全HMAC匹配字段确实必需，其他字段须按实际展示需求逐项论证；建议保留旧迁移字节、不新增表、不开放写权限、不扩Worker权限，并保留同事务授权及披露前审计。这只是待确认建议，尚未实施修订。

因果Decision/ContactResult的服务端选择属于Task5原范围，可通过Owner读口完成；ACK已有`causalStop`，并非另一个需要扩大权限的阻塞。此处读取冲突与已修复的P0自动分配合同冲突、尚待处理的容量向量问题分别记录，不混为同一问题。

本轮未改生产代码，固定JDK及迁移后PostgreSQL基线`task-5-preflight-baseline.log`实际通过14个unit/architecture与9个IT，0 failures/errors/skips，于2026-09-06T16:21:19+08:00退出0。测试覆盖既有能力角色和准确QUERY刷新读口，不冒充新的CurrentCard实现或冲突修复。整体仍为Task1–4完成，HTTP/前端/E2E未打通。

## Task 4后端完成复核与范围

本步增加独立的`lead.ActionDraftCommands`生产保存Handler，复用既有Runtime/授权/事件事实读取；没有把Lead依赖加入Responsibility。正文仍恰为actionCode/schemaVersion/values，可信Draft路径及条件头单独承载且不进入payload摘要。七类候选使用同一封闭业务Schema，必填与条件必填字段没有变为可选。保存不完成Task，只有主命令事务可同时确认准确Draft并产生完成事实。

新Owner读口保留准确Task/Lead/Draft、独立Appointment/Principal/OrganizationUnit、Party标签、Assignment及因果Decision/ContactResult来源selector。所有SQL留在所属Owner内；后续Task5仍须完成同连接围栏、逐来源授权与审计提交后披露。CurrentLeadReader仅承诺原始捕获字段，V850补全槽不授予QUERY读取；涉及该槽的有效联系方式或重复候选重建是Task5预检依赖，本步没有以空值替代事实或扩大物理权限。

最终全后端受影响回归`task-4-14-final-regression.log`实际通过71个unit/architecture及249个IT，0 failures/errors/skips；进程于2026-09-06T14:06:36+08:00完成、exit0。249个IT由30个新增ActionDraftIT和219个继承IT组成；219包含历史Task3选定218以外的1个JooqGenerationIT，不能把全部回归数当作新增业务测试。最终原始XML/文本报告保留于同目录`task-4-final-reports/`，已核对实际测试身份。原始有效RED为`task-4-02-red.log`（真实SAVE入口尚未注册）、`task-4-05-schema-red.log`（Contact/Review候选尚未支持）、`task-4-08-readers-red.log`（读口实现缺失）。所有编译或fixture失败也保留，未算作业务RED或通过证据；既有生成器/JAXB/Flyway诊断噪声不等于无警告成功。

独立评审确认规格符合、质量通过，无Critical/Important。提交后独立复验`task-4-root-committed-verification.log`于14:11:12+08:00实际exit0，17个unit/architecture及3个准确生产IT通过，覆盖更新/NO_CHANGE/旧键重放、新保存草稿被真实主命令确认、写入后的最终DENY回滚。`3ec837e..03573e9`的contracts/database/apps字节差异为空，OpenAPI与事件Schema摘要未变，`git diff --check`通过。

固定Python真实基线CLI在`task-4-root-baseline-direct-shell.log`中实际exit0/PASS，仍有6项预期后续门禁阻塞。初次验证误用旧版PowerShell读UTF-8脚本，中文Git路径被误解码而失败；直接在当前PowerShell7运行同一脚本即通过，未修改产品或放宽校验，失败日志仍保留。

两项非阻断评审意见留给整分支复核：新增编排/持久化方法部分长行可读性，以及继承的生成器诊断噪声。Task5还必须服务端选择因果Decision/ContactResult ID并验证绑定；本步准确的按ID读口不等于责任卡整合完成。涉及入口补全槽的QUERY能力问题同样在Task5实施前核对，不能静默遗漏来源或扩大权限。

本步仅交付经评审的后端能力。没有新公共接口、Task5披露服务、Contact/Review执行、Worker、HTTP安全装配、前端连接、物理合同/迁移/权限修改、容量修正、推送、合并或部署。R1-BACKEND、R1-SPA、R1-E2E及容量门禁不因此推进。下一步为Task5责任卡查询与敏感披露审计，本轮未自动启动；其后依次为Task6首联/复核/恢复、Task7 Worker、Task8 HTTP装配、Task9 SPA、Task10 E2E及容量验收。

## Task 3完成复核与范围

提交后的独立复验使用固定JDK/Maven与迁移后的真实PostgreSQL：`task-3-root-committed-verification.log`实际通过20个unit/architecture及2个授权IT；`task-3-root-duplicate-verification.log`实际通过2个unit及1个包含六种重复确认组合的IT，进程均exit0。初次专项命令的重复分支方法名未匹配，未将其计入通过数，随后用准确方法名完成后一个独立日志。

`task-3-root-final-baseline.log`记录本轮固定Python真实基线CLI的exit0/PASS，仍有6个预期非致命后续门禁阻塞。`3a2b63a..df9b985`的`contracts/`、`database/`和`apps/`字节差异为空；OpenAPI SHA256仍为`a1ab94f96c2e9016c70ca3591cda60695f98526eccb6c2ec4cf09faa53b76c1c`。未新增表、迁移、公共接口、权限、前端或后续业务功能；来源停用仅创建/确认责任，不修改实际来源配置。

Task3安全收口补齐共享命令策略的准确Task/Lead/Owner复验，并在Handler最终校验前保存持锁授权结果，避免回滚丢失准确版本的DENY审计证据。代价是增加最终Owner/授权读取；完整容量影响尚未验收。当时后续工作由Tasks4–10承担；其中Draft保存/编辑现已由上述Task4完成，其余仍待Tasks5–10。

Task3独立评审留下两项非阻断改进：新编排/持久化方法的长行可读性，以及已有OpenAPI生成器、JAXB和Flyway日志噪声；留给后续整分支复核，不为本次扩大重构范围。测试通过不表示构建无警告。Task3结束时完成Task1–3；当前状态以上方Task4完成记录为准，不能据此推算整体工作量完成百分比。

## 历史合同修复验证

前一轮合同验证使用固定镜像`python@sha256:581429e3df12d76e6af4be5ab7d0e7fc2013eb57dc23d2de691411c8efdbb970`及PyYAML 6.0.3，所有进程均读取真实最终退出状态：

- `p0-contract-red-behavior.log`：7 tests，exit 1、1个预期断言失败、0 errors，证明旧解析器拒绝新结构。
- `p0-contract-green-verified.log`：10 tests，3.047秒，exit 0；有界自动分配、旧禁止字段、越界指针/revision、完成/事件/后继、候选不可变及新基线版本校验通过。
- `p0-contract-full-regression.log`：`python -m unittest discover -s scripts/baseline/tests -v`实际运行240 tests，171.889秒，exit 0；覆盖完整baseline/command/closure及本次新增合同测试。
- `p0-contract-baseline-cli-tracked.log`：通过既有`verify-baseline-locked.ps1`运行真实基线CLI，exit 0、baseline consistency PASS；R2仍有6个非致命阻塞（5个既有业务门禁，加新基线尚未合并）。
- `git diff --check`与暂存差异检查通过。物理合同、V001–V850、OpenAPI/事件Schema与前端文件对起始提交无差异。

上述日志保留于前述本地忽略目录。初始测试构造错误、两次fixture迭代失败和新证据文件尚未进入Git索引时的CLI失败日志也保留；不将这些失败命名中的“green”视为通过。最终完整报告为同目录`p0-contract-amendment-report.md`。

用户已推迟完整容量测试。此外，容量向量要求duplicate完成后产生ingress的路径，与duplicate必须已有匹配联系方式、ingress必须原始联系方式和槽全空的规则冲突，仍未解决。本次不改变该向量计数，不伪造容量资格。R1-BACKEND、R1-SPA、R1-E2E-GOLDEN、R1-E2E-FAILURES以及R2/R3门禁不因本次合同修复而推进。
