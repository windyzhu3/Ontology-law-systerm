# Linux v22 发布与海华初始化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增可在 Linux 执行和恢复的 v20→v22 发布、海华空库初始化入口，以真实迁移、身份认证和业务验证交付可复查结果。

**Architecture:** 将发布制品校验、私密原操作日志、基础设施、迁移恢复、身份初始化分成小模块，由唯一 CLI 编排。来源绑定与责任路由接入既有应用事务和权限端口，公开配置用逻辑名称、私密配置保存实际 UUID。这些部分共同决定发布后的可用性，采用一份集成计划，逐项独立测试。

**Tech Stack:** Python 标准库、Docker Linux 容器、PostgreSQL18、Flyway13.4.0、Keycloak26.7.3、Java25.0.4.1+1、Maven Wrapper、Node24.20.0/npm11.9.0、React、Vitest、Playwright1.63.0；镜像摘要和依赖沿用仓库锁文件。

**Spec:** [已确认设计](../specs/2026-10-07-linux-v22-initialization-design.md)。用户已确认17人/20个人类任职；执行方式保留此前选择的“当前会话逐项实施”。用户已确认计划，现由当前会话实施；完成步骤按实际证据更新。

## Global Constraints

- 新增 `deploy/linux`，不包装 Windows 工具为 Linux 发布器；不连接腾讯云，不收集服务器配置。
- 不改动现有本地海华库、账号、授权、材料、进程、运行目录；新验证资源必须独立登记。
- 一 SPA、一 OpenAPI、一 JAR 的 `api|worker` 互斥角色；身份库和业务库分离，保持 TLS 与真实 OIDC。
- Flyway 是唯一 DDL 执行入口；全量43份 SQL，v20前缀41份，仅追加 V1070/V1080；旧迁移字节保持不变，不新增迁移。
- 同 schema 字节发布仍拒绝 schema 变化；迁移后不得单独回滚旧 JAR，不用 `repair`、`clean`、向下迁移或重放业务命令补救。
- 未知结果核对原操作，保存原键、正文摘要、前置条件与结果引用；不自动新键重做，不清库初始化。
- 人类管理调用使用本人真实会话；两位管理员互相授权，不用 SQL 或 SERVICE 冒充 HUMAN。
- 初始化为7组织、17人、20个人类任职，技术 SERVICE 另外核对；岗位名称不赋权，不跨任职合并权限。
- 主任全所 HUMAN 业务权限显式列出；AUDIT_READ 仅两位主任，不做导出。LEAD_ASSIGN 初始不指定人员；五名普通案管员无本阶段业务职责，陈路监督不确认付款。
- 本环境最低密码8位，保留 notUsername/notEmail；17账号初始临时密码要求 UPDATE_PASSWORD，不启用 directAccessGrants。
- 三类模板仅为候选；不预写审核、签署、到账、接收、分类结论，不迁入历史数据，不初始化业务交易。
- 默认代表丁启明，可逐份替换；电话待补，收款显示“海华律师事务所验收收款账户”，不虚构开户行/账号。
- 公开配置、候选模板和去秘密证据入库；密码、令牌、密钥、备份和敏感运行记录在仓库外。

## Review Focus

1. 路径含中文/空格、符号链接逃出运行目录或目录属于另一实例：参数数组执行，归属及真实路径验证失败则拒绝（L01/L02）。
2. V1070已成功而进程退出、迁移命令超时但数据库已提交：原日志核对实际 history/gate 后续跑，不生成新升级（L03）。
3. 身份/管理请求已成功但客户端未收到结果、配置随后修改：原键核对，不重置密码、不认领同名对象，不复用不同配置日志（L06/L07）。
4. 退出/切换账号后仍有旧导入草稿和未决请求：清除当前表单能力，保留原恢复标记，不给新账号自动重发（L04/L05/L09）。
5. 主任全权使候选增多、指定经办人撤权或与提交人同人：有效旧责任保留，新责任正确定向，失效阻断，不能自动选主任（L08/L09）。

---

## 文件边界与执行约定

以下路径相对仓库根目录；Java 包根 `J = backend/src/main/java/io/github/windyzhu3/ontologylaw`，测试包根 `JT = backend/src/test/java/io/github/windyzhu3/ontologylaw`。每项的 `J/...`、`JT/...` 均按此展开，不是新增目录名。

新增 Python 包 `deploy/linux/ols_linux/`：`config.py` 校验公开输入；`journal.py` 原操作和独占锁；`bundle.py` 制品描述；`runtime.py` Linux 资源启停与归属；`database.py` SQL/Flyway能力；`checkpoint.py` 联动检查点；`release.py` 发布状态机；`identity.py` IdP及引导；`admin.py` HUMAN命令客户端；`initialize.py` 初始化编排；`business_config.py` 已有审批策略及可信配置；`verify.py` 只读核对。包目录添加空 `__init__.py`，不设第二套包管理器。

新增 `deploy/linux/linux.py` 为 CLI，`deploy/linux/tests/` 为标准库测试，`deploy/linux/verification/` 为独立 Linux 实测编排。公开文件位于 `config/`、`templates/`，运行秘密不在这些目录。

验证命令从仓库根运行。文中的 `python3`、`node`、`npm`、`./mvnw` 指锁定 Linux 运行时；当前 Windows 主机用相同版本的现有路径及 `mvnw.cmd`，Linux特性在独立容器验证。单项测试以新建的具名 test fixture 承接示例断言，不把下面的 fixture 名当成现成 API。

先编写失败测试，确认失败原因是新能力缺失，再实现并通过测试；文档/候选文件不额外编造镜像式测试。提交使用本项列出的准确路径；提交前 `git diff --check`，不加入无关用户文件。L01→L02→L03；L04→L05；L06→L07；L01/L02/L04/L07→L08；全部完成后L09→L10。

### Task 1: L01 公开配置、候选模板、制品与原操作日志

**Files:** 新建 `deploy/linux/ols_linux/{config,journal,bundle}.py`、`deploy/linux/config/{haihua.json,haihua.schema.json}`、`deploy/linux/templates/manifest.json`、`deploy/linux/tests/{test_config,test_journal,test_bundle}.py`；复制已验证的三组 DOCX/PDF/fields.json 为 `templates/{consulting,civil-litigation,enforcement}.{docx,pdf,fields.json}`，复制原官方来源清单为 `templates/sources.json`。不复制资料 ZIP、凭据或私密制作脚本。

**Interfaces:**
- `config.load(path: Path) -> dict`：返回通过固定 schema/语义校验的公开配置，拒绝未知键和秘密字段；`config.digest(value: dict) -> str` 为规范 JSON SHA256。
- `journal.begin(runtime: Path, kind: str, config_digest: str) -> dict`：独占生成原 operationId、阶段和实例归属；`journal.record(runtime: Path, operation_id: str, event: dict) -> None` 原子持久化；`journal.read(runtime: Path, operation_id: str) -> dict` 只读。
- `bundle.describe(repo: Path, jar: Path, spa: Path, commit: str) -> dict`、`bundle.verify(descriptor: dict, directory: Path) -> None`：记录 commit、JAR/SPA/配置/manifest/43迁移及锁文件摘要、schema版本和构建结果，拒绝缺项。

- [x] 写上述测试，包括 `assert len(config['people']) == 17`、`assert sum(len(p['appointments']) for p in config['people']) == 20`、`assert len(config['organizations']) == 7`；主管授权无 `LEAD_ASSIGN`，陈路无 `PAYMENT_CONFIRM`，普通案管授权为空；重复账号/非法scope/未知authority拒绝。日志并发二次 begin 拒绝；中文空格路径通过，外部 symlink/其他实例/秘密字段拒绝，不能泄露秘密正文。release/start使用同一实例锁，不能与迁移抢占启动writer。
- [x] 运行 `python3 -m unittest discover -s deploy/linux/tests -p 'test_config.py' -v`，以及 journal/bundle 同名测试；首次应缺新模块而失败。
- [x] 实现上述接口；config包含已确认姓名账号、角色、明确当前 HUMAN authority和scope、默认值及独立分配待指派条件。role/scope可复用数据，配置展开后输出逐任职权项；不得通配或自动接纳未来authority。journal采用0600文件/0700目录、fsync与原子替换、持有进程锁；路径和配置摘要不匹配拒绝。模板manifest记录候选状态、来源、版本和准确摘要，字段仍为八项。
- [x] 运行三组测试并核对候选字节与既有已验证输出一致；生成 bundle 不启动应用。`git diff --check` 通过。
- [x] 提交本项，消息 `feat: define Linux release and Haihua initialization inputs`。

### Task 2: L02 独立 Linux 基础设施和空库迁移能力

**Files:** 新建 `deploy/linux/ols_linux/{runtime,database}.py`、`deploy/linux/runtime/{Dockerfile.app,server.mjs,toolchain.lock.json}`、`deploy/linux/tests/{test_runtime,test_database}.py`、`deploy/linux/verification/{harness.py,empty_database.py}`。复用现有数据库角色 bootstrap 和锁文件，不重写历史 SQL；新toolchain文件仅补现有锁未覆盖的Linux JDK/Node同版本制品、平台与SHA256，已有数据库/身份镜像不复制另一版本来源。

**Interfaces:** 消费L01日志和制品；`runtime.prepare(runtime: Path, settings: dict) -> dict` 登记网络/卷/容器/端口/TLS资源；`runtime.stop_writers(runtime: Path, operation_id: str) -> None` 关闭公网入口并停写核验；`runtime.start_internal(runtime: Path, descriptor: dict) -> dict` 内部启动；`runtime.open_ingress(runtime: Path, operation_id: str) -> None` 仅经验证阶段开放。
- `database.observe(runtime: Path) -> dict` 返回准确gate/history/catalog；`database.flyway(runtime: Path, operation_id: str, action: str, target: str | None = None) -> dict` 只接受validate/migrate，使用锁定镜像、专用migrator与私密配置文件。`harness.create(run_id: str) -> Path` 返回新独立实例根。

- [x] 测试：`assert spy.commands_contain_no('pwsh', 'icacls', 'certutil')`；停止失败不得进入迁移；缺/错TLS、占用端口/外部卷、非登记writer拒绝；`repair/clean`拒绝。empty_database验证 `assert sql_versions == expected_43_versions`、所有history成功及schema为v22；API/worker角色无DDL能力。
- [x] 运行runtime/database测试，缺实现失败；执行 `python3 deploy/linux/verification/empty_database.py --run-id <本轮唯一ID>`，首次缺入口失败。测试只能创建本轮资源。
- [x] 实现 runtime/database接口和容器入口：应用镜像从匹配的既有JDK/Linux制品构建，核对版本；秘密以文件挂载，不放argv/公网。API/Worker各用独立容器、同一JAR，Node入口复用既有SPA服务语义。默认仅内部网络/loopback，CA校验和mTLS保持。资源删除仅限日志登记且label匹配的本轮测试资源。
- [x] 通过单元测试与真实空库43迁移/schema/数据库角色检查；记录Linux版本、镜像摘要及结果。此项只证明数据库基础能力，不宣称初始化完成。
- [x] 提交本项，消息 `feat: add isolated Linux runtime and schema preparation`。

### Task 3: L03 v20→v22状态机、检查点、原操作恢复

**Files:** 新建 `deploy/linux/ols_linux/{checkpoint,release}.py`、`deploy/linux/tests/{test_checkpoint,test_release}.py`、`deploy/linux/verification/{migration.py,fixtures/v20_facts.sql}`。fixtures只写独立v20测试库，不用于上线初始化。

**Interfaces:** 消费L01/L02；`checkpoint.capture(runtime: Path, operation_id: str) -> dict` 返回业务库/身份库/材料/密钥配置引用/旧制品摘要；`checkpoint.verify_restore(runtime: Path, operation_id: str) -> dict` 在独立目标实际恢复；`checkpoint.restore(runtime: Path, operation_id: str) -> dict` 显式执行已验证检查点联动恢复。
- `release.upgrade(runtime: Path, bundle_dir: Path) -> dict` 新升级仅接受真实v20；`release.resume(runtime: Path, operation_id: str) -> dict` 只推进原操作剩余阶段；`release.publish_bytes(runtime: Path, bundle_dir: Path) -> dict` 只接受同schema；`release.status(runtime: Path) -> dict` 只读。

- [x] 失败测试断言：`assert old_table_digests_after == old_table_digests_before`、`assert history_after[:len(history_before)] == history_before`、`assert appended_versions == ['1070', '1080']`。旧表摘要集仅排除单独核对的deployment_state/Flyway history；不排除身份、授权、业务、任务、审计表，新appointment_role种子另验。错版、旧41文件篡改、bundle不符、并发、缺备份/恢复证明、writer未停都拒绝。V1070后中断原resume只执行V1080；两项已提交但命令超时，resume不再迁移。旧JAR独立rollback拒绝；显式恢复后gate/history/两库/材料/配置/制品匹配原检查点。
- [x] 运行 checkpoint/release 单元测试，首次缺实现失败；真实场景命令为 `python3 deploy/linux/verification/migration.py --run-id <唯一ID>`。
- [x] 实现状态顺序：读取冻结原gate→CAS MAINTENANCE→关闭入口/停全部writer→联动备份并真实恢复核验→记录迁移可能执行→validate/migrate/validate→核对v22/history/catalog/旧逐表事实→安装bundle→用实际revision做gateCAS→内部启动/原引导核对/健康→开放入口。联动检查点停写包含Keycloak身份写入进程，内部验收时再启动，不把两份运行中无关联备份称为一致检查点。迁移前单独校验旧41个history及对应字节，迁移后校验完整43个history；不能拿仅含旧41迁移的locations对已迁v22库执行Flyway validate。每个effect之前持久化意图。V1070新增目录种子单独核对，不把新role表计入“旧表必须字节相同”。
- [x] 实现unknown reconcile：实际history、gate、当前字节和原日志一致才推进；不猜revision、不覆盖他人release，冲突留在MAINTENANCE/BLOCKED。v21仅允许属于该日志的续跑；不创建新升级。checkpoint恢复包括两库、材料、密钥配置和旧制品，先隔离验证再切换。
- [x] 通过单元与真实v20事实保留/中断/激活失败/联动恢复场景；同schema入口再次确认schema变化拒绝。记录每种失败停留阶段，不只记录退出码。
- [x] 提交本项，消息 `feat: implement journaled v20 to v22 Linux migration`。

### Task 4: L04 HUMAN来源绑定和最终写入校验

**Files:** 新建 `J/lead/R1HumanSourceBinding.java`、`JT/lead/HumanSourceBindingIT.java`；修改 `J/lead/{LeadIntakeSources,LeadCommands}.java`、`J/api/{R1ApiDeployment,R1ApiServices}.java`、`JT/api/LeadIntakeConfigurationTest.java`、`JT/api/LeadIntakeSourcesHttpIT.java`。

**Interfaces:** `R1HumanSourceBinding.Entry(UUID tenantId, UUID principalId, String sourceAccountCode)`；`R1HumanSourceBinding(List<Entry> entries, R1SourcePolicyRegistry sources)`；`boolean enabled(UUID tenantId)`；`boolean permits(Actor actor, String sourceAccountCode)`。未配置租户保留原行为；已配置租户的HUMAN无绑定拒绝，SERVICE保持原协议。R1ApiDeployment.Settings新增 `humanIntakeBindings: List<Entry>`，旧构造路径用空列表兼容。

- [ ] 新IT断言本人目录只含本人source、跨账号伪造得到 `NOT_AUTHORIZED` 且无线索/任务新增；换任职仍同principal来源；撤权/跨租户/停任失败；SERVICE测试仍通过。同原键同正文重放 `assert replay.factRef().equals(original.factRef())`、`assert leadCountAfter == leadCountBefore`，不改已有receipt/source。重复绑定或未注册source启动失败。
- [ ] `./mvnw -B -f backend/pom.xml -Pit '-Dit.test=HumanSourceBindingIT,LeadIntakeSourcesHttpIT,LeadIntakeSourcesIT,LeadAssignmentIT' verify`：首次缺类/缺绑定校验失败。
- [ ] 实现可信设置解析、来源目录过滤和 `LeadCommands.Handler.validateBeforeWork` 的新捕获绑定检查，仍由既有Runtime完成授权锁、原回执判断、幂等和事实事务；不在HTTP层改写客户端原正文。原receipt查询/重放按现有当前授权规则处理，不用新来源覆盖旧结果。新增LeadCommands构造重载，旧构造保持未配置语义。
- [ ] 通过上述IT及 `./mvnw -B -f backend/pom.xml '-Dtest=LeadIntakeConfigurationTest,ArchitectureTest' test`。
- [ ] 提交本项，消息 `feat: bind human lead intake to authenticated principals`。

### Task 5: L05 自动来源的HTTP合同与导入界面

**Files:** 修改 `contracts/openapi/ontology-law-api.yaml`、`apps/workbench/src/generated/api/schema.d.ts`、`J/api/R1ApiServices.java`、`apps/workbench/src/features/lead-intake/{leadIntakeApi,LeadIntakeApplication}.ts*`及各自已有测试；新增 `docs/baseline/LINUX-HUMAN-INTAKE-BINDING-V1.md`，更新 `docs/contracts/r2-lead-intake-sources-v1.md`。Java API模型使用既有Maven生成路径，不手写生成类。

**Interfaces:** `LeadIntakeSourcesV1` 增量可选 `sourceSelection: 'BOUND_TO_PRINCIPAL' | 'SELECTABLE'`。启用绑定租户显式给出BOUND_TO_PRINCIPAL，未配置租户保留旧响应；客户端缺字段时按原SELECTABLE处理。前端 `SourceSelection = 'BOUND_TO_PRINCIPAL' | 'SELECTABLE'`，`sources(...) -> Promise<{sources: IntakeSource[]; sourceSelection: SourceSelection}>`；绑定模式只允许0/1条，非法数量拒绝展示/提交。

- [ ] 增加断言：绑定模式 `expect(screen.queryByRole('combobox', {name:'来源'})).toBeNull()`、当前来源只读显示；手工/CSV/XLSX请求均用该source；旧响应仍能选择。模拟退出/切换时旧response忽略，旧draft不提交，新账号没有自动重发，原恢复标记仍可查询；两条绑定来源响应拒绝。
- [ ] `npm test -- --run src/features/lead-intake/leadIntakeApi.test.ts src/features/lead-intake/LeadIntakeApplication.test.tsx`：首次新协议/界面断言失败。
- [ ] 实现协议、严格解析器与只读来源文案“来源：<本人来源>”；保留共用页面/菜单/按钮及现有恢复流程。会话变化沿用已有清理机制，不能把任职变化视作可自动重写原命令。冻结合同只通过具名增量说明调整，记录兼容范围，不广泛松开baseline断言。
- [ ] `npm run openapi:generate` 后通过上述测试、`npm run openapi:check`、`npm run typecheck` 和 HTTP IT；不删除旧模式反例。
- [ ] 提交本项，消息 `feat: show bound intake sources in the shared workbench`。

### Task 6: L06 真实IdP账号、首次改密与原引导

**Files:** 新建 `deploy/linux/ols_linux/identity.py`、`deploy/linux/tests/test_identity.py`、`deploy/linux/verification/identity_login.mjs`。读取 `deploy/identity/realm-template.json`，仅在新环境私密派生realm中设置min8，不降低已有环境默认。

**Interfaces:** `identity.prepare(runtime: Path, config: dict, initial_password_file: Path) -> dict` 返回日志登记的17个准确IdP subjects；`identity.bootstrap(runtime: Path, config: dict) -> dict` 调用现有IdentityBootstrapCommand的candidate/dry-run/execute/verify；`identity.verify(runtime: Path) -> dict` 只读。输出写私密文件，公开结果仅含逻辑账号与通过状态。

- [ ] 单元/真实协议断言所有17账号 `temporary == true` 且requiredActions包含 `UPDATE_PASSWORD`；policy含length8/notUsername/notEmail、directAccessGrants=false、PKCE S256。已有非本日志realm拒绝；已创建账号响应丢失必须核对准确subject，不重置密码/新建用户。同日志改配置拒绝。
- [ ] `python3 -m unittest discover -s deploy/linux/tests -p test_identity.py -v` 首次缺模块失败；真实流程 `node deploy/linux/verification/identity_login.mjs --runtime <本轮实例>` 首次缺新入口失败。
- [ ] 实现临时秘密文件传递、账号创建前原日志、已创建对象的精确引用核对和原引导manifest。初始丁启明ROOT/IDENTITY_ADMIN结构不改；candidate过期/execute未知先调用原verify，不自动新candidate。脚本提示两位管理员通过真实页面各自改密和提供本人受保护会话，其他15人不代改。
- [ ] 在独立Linux IdP验证：未改密不得进入业务，8位合规新密码通过；保留7位/用户名/邮箱反例；两位管理员改密成功，其他15仍待改密。无password grant、token日志泄露或原环境realm变化。
- [ ] 提交本项，消息 `feat: prepare Haihua identity with required password updates`。

### Task 7: L07 通过真实HUMAN管理命令建立任职与授权

**Files:** 新建 `deploy/linux/ols_linux/{admin,initialize,verify}.py`、`deploy/linux/tests/{test_admin,test_initialize}.py`、`deploy/linux/verification/initialization.py`。

**Interfaces:** `admin.execute(runtime: Path, operation_id: str, command: dict, session_file: Path) -> dict` 保存并使用原commandId、body、precondition并核对本人Actor/当前任职；`admin.reconcile(runtime: Path, operation_id: str, command_id: str, session_file: Path) -> dict` 查询原结果，未知不新键。
- `initialize.run(runtime: Path, config: dict, sessions: dict[str, Path]) -> dict` 消费L06的准确subject和原bootstrap；`initialize.resume(runtime: Path, operation_id: str, sessions: dict[str, Path]) -> dict` 原操作续跑；`verify.initialization(runtime: Path, config: dict) -> dict` 只读比较期望与实际。sessions键只允许dingqiming/huangxuexue，值是私密文件路径。

- [ ] 测试实际7组织/17principal/20 HUMAN appointment及明确grant集合相等，无业务交易；丁3任职、黄2任职、其余各1。丁建立黄管理任职及四项身份权限，黄建立丁主任任职/授权；自我授权与最后管理员保护拒绝。普通用户AUDIT_READ拒绝，主任允许；重复运行不增对象，命令已提交响应丢失原键核对，无SQL HUMAN grant或SERVICE冒充。
- [ ] 运行admin/initialize单元测试和 `python3 deploy/linux/verification/initialization.py --runtime <本轮实例> --sessions <私密会话索引>`：首次缺实现失败。
- [ ] 实现具名顺序，使用现有IdentityAdminController及真实候选/前置条件/receipt；公开配置解析成准确subject/principal/appointment，不按名称认领外部对象。首次初始化在任何DDL/IdP写入前创建唯一init日志并检查空目标，整个L02/L06/L07流程沿用此operationId；恢复只接纳其已登记事实，不在bootstrap已建数据后重新要求空库。先建DIRECTOR/FINANCE_SUPERVISOR/CASE_SUPERVISOR目录，角色目录操作本身不赋权。
- [ ] 打通bootstrap后管理API启动：受控准备先建技术SERVICE目录、任职及系统权限，配置准确bootstrap租户/原管理员trust和验证过的v22制品gate，再内部启动API。初始业务来源目录关闭、尚未解析的绑定/路由不启用，公网业务入口保持关闭；API真实管理命令完成后由L08安装完整可信配置并重启核对。技术SERVICE不算人类任职/业务授权，不依赖尚未存在的人类任职UUID启动管理API。
- [ ] 核对完整grant范围：普通销售本人主链，两主管本部门审批，孙/焦分别两部核款，陈路监督无核款，杨案管职责，五案管无办理职责，主任显式全所HUMAN业务。总所身份资格与案管隶属按20任职设计分开；独立线索分配待指派不偷偷授权。
- [ ] 通过真实初始化及重复/未知响应反例；verify只读，不取得新candidate、不重置密码、不补授权。报告分别显示初始化结构PASS、待改密人数、LEAD_ASSIGN待指派、模板待审核。
- [ ] 提交本项，消息 `feat: initialize Haihua appointments through human administration`。

### Task 8: L08 有界责任路由和已有审批策略配置

**Files:** 新建 `J/api/BusinessResponsibilityRouting.java`、`JT/api/BusinessResponsibilityRoutingIT.java`、`deploy/linux/ols_linux/business_config.py`、`deploy/linux/tests/test_business_config.py`；修改 `J/api/{R1ApiDeployment,R1ApiServices,ContractWorkflowPorts,PaymentWorkflowPorts,TransferWorkflowPorts}.java`、`J/contract/ContractWorkflowService.java`、`J/contract/internal/persistence/{JdbcContractWorkflowService,JdbcManualSignatureWorkflow}.java`；新增 `docs/baseline/LINUX-BUSINESS-RESPONSIBILITY-V1.md`。

**Interfaces:** `BusinessResponsibilityRouting.Entry(UUID tenantId, UUID sourceOrganizationId, String stageCode, UUID appointmentId)`；`BusinessResponsibilityRouting(List<Entry> entries)`；`Optional<UUID> target(UUID tenantId, UUID sourceOrganizationId, String stageCode)`；`boolean enabled(UUID tenantId)`。Settings新增 `responsibilityRoutes: List<Entry>`，旧构造默认未配置。
- ContractWorkflowService.Ports新增默认 `Optional<UUID> routingTarget(UUID tenantId, UUID sourceOrganizationId, String stageCode)` 和 `boolean routingEnabled(UUID tenantId)`，默认空/false；具体ports注入上述配置，旧构造保留。
- `business_config.install(runtime: Path, operation_id: str, config: dict) -> dict` 从已核对UUID生成私密可信设置及现有quote/contract policy/members，持久化原策略版本和准确摘要；重复按原记录核对，不写任何审批完成事实。

- [ ] 真实IT断言主任均有全所业务权时，新合同审查/签署核验归档owner=杨、两部核款owner=孙/焦，报价/合同审批成员=万/耿；`assert incumbentAfterConfigChange == incumbentBefore`。目标停任/撤权/跨租户/缺项、与提交人同principal导致既有阻断，不降级首候选/主任。旧无配置环境仍使用唯一合法候选；既有审批冻结成员不被改写。
- [ ] `./mvnw -B -f backend/pom.xml -Pit '-Dit.test=BusinessResponsibilityRoutingIT,R2ContractAuthorityRecoveryIT,R2ContractPaymentHttpIT,R2TransferWorkflowIT,R2TransferRecoveryIT' verify`；首次缺路由实现失败。Python business_config测试首次缺模块失败。
- [ ] 实现固定阶段白名单：合同AWAIT_REVIEW→CONTRACT_REVIEW、签署AWAIT_VERIFICATION/ARCHIVE→CONTRACT_SIGNATURE_VERIFY、付款CHECK_RECEIPT→PAYMENT_CONFIRM、转案REVIEW_TRANSFER/INTAKE/CLASSIFY→TRANSFER_REVIEW/TRANSFER_ACCEPT/MATTER_CLASSIFY。字段使用现有阶段/authority准确代码，在具名增量合同记录，不允许配置新阶段；归档复用当前核验权限，不新增归档权限代码。审批继续由既有策略表控制，不改为通用路由完成审批。
- [ ] 在“新责任选择”处接线：有效既有责任和冻结审批成员先保留；其他情况只选配置目标并完整验证权限、组织和独立性。付款SUPPLEMENT_RECEIPT、合同准备/签署采集、转案PREPARE/SUPPLEMENT及销售交接执行核验保持既有销售责任，不被杨的案管路由替换。Transfer按req.from查路由但按req.to验证隶属；默认主任案管任职提供合法办理资格，仍不越过TaskOwner。
- [ ] 通过上述IT、ArchitectureTest和Python测试；核对source绑定、路由、审批成员及收款默认值均引用本实例准确UUID，无额外DDL/历史任务改派。
- [ ] 提交本项，消息 `feat: configure bounded business responsibility routing`。

### Task 9: L09 真实 Linux 端到端、四组合与故障验收

**Files:** 新建 `deploy/linux/verification/{acceptance.py,business_chain.mjs}`，扩充已有本计划harness/identity_login/migration/initialization，新增 `docs/evidence/linux-v22-initialization/verification-matrix.md` 和去秘密 `report.md`。

**Interfaces:** `acceptance.py --run-id <唯一ID> --scenario empty|upgrade|business|all`；只建立日志登记资源，分为初始化实例、v20升级实例、合成业务实例。消费前项工具，必须使用待发布真实JAR/SPA与真实IdP，不能以mock/direct数据库事实代替业务办理。

- [ ] 先写失败场景清单与machine assertions：`assert initialization_business_transactions == 0`、`assert human_appointments == 20`、`assert old_fact_digests_preserved`、`assert all(owners_match_configuration)`；业务四组合明确为销售一部/二部各“引用报价进入合同”和“直接合同”链路，共4条从线索至案管接收分类的正常链。
- [ ] 运行 `python3 deploy/linux/verification/acceptance.py --run-id <唯一ID> --scenario all`，确认缺覆盖阶段失败，不把SKIP当PASS。
- [ ] 实现真实页面PKCE登录/首次改密/退出切换、身份管理和审计读取反例、自动来源手工与批量导入、草稿切换与原键恢复；合成库用登记的独立分配授权及审核模板办理材料扫描、报价/合同审批、签署核验归档、付款、转案。正式初始化库不写这些测试授权、材料或业务事实。
- [ ] 注入并验收两项迁移间中断、命令超时已提交、gateCAS冲突、缺备份/停止失败/并发、管理未知响应、来源伪造、经办撤权和同人独立性冲突；验证等待/原键恢复及联动实际restore。失败停留在安全阶段，不自动“补成成功”。
- [ ] 完成all场景，保存runId、commit、镜像/制品/配置/模板摘要、真实计数、案例与原命令引用、失败裁定及restore证据。仅公开去秘密结果；真实令牌和材料正文保持私密。
- [ ] 提交本项，消息 `test: verify Linux migration and Haihua initialization end to end`。

### Task 10: L10 唯一 CLI、操作文档、构建回归和交付

**Files:** 新建 `deploy/linux/linux.py`、`deploy/linux/README.md`、`deploy/linux/tests/test_cli.py`；按必要范围更新根 `README.md` 的 Linux 入口，更新本计划状态与去秘密验证报告。所有前项模块经唯一 CLI 调用。

**Interfaces:** `python3 deploy/linux/linux.py --runtime <绝对私密目录> <subcommand>`。具名子命令：`prepare`、`describe-bundle`、`initialize`、`initialize-resume --operation-id`、`verify-initialization`、`upgrade --bundle`、`upgrade-resume --operation-id`、`publish-bytes --bundle`、`release-status`、`restore-checkpoint --operation-id`、`start`、`stop`、`health`。initialize还接受 `--config`、`--initial-password-file`、`--sessions-file`；无秘密值argv参数。退出码0仅表示相应阶段确证完成，unknown/blocked返回非0并显示原operationId和核对命令。

- [ ] CLI测试断言秘密参数拒绝、未知子命令/误将v21当新upgrade拒绝、只读命令无mutation、start/restore归属不符拒绝、CLI调用准确前项接口；首次缺入口失败。
- [ ] 运行 `python3 -m unittest discover -s deploy/linux/tests -p test_cli.py -v`；实现解析/分派，不复制前项状态机。README给空库与v20两套命令、Linux锁定前置、两管理员改密/会话阶段、停写恢复、候选模板审核和LEAD_ASSIGN待指派条件；不宣传全自动无会话或正式模板已审核。
- [ ] 验证 `python3 -m unittest discover -s deploy/linux/tests -v`、相关已有 local-login/haihua工具回归；`npm run openapi:check`、`npm run typecheck`、`npm run build`；后端上述定向IT及ArchitectureTest，`./mvnw -B -f backend/pom.xml -DskipTests package`。schema `generate.py --check`、`scripts/verify_generated_sql.py` 在其目录执行，核对43 SQL摘要仍等于实施前清单；jOOQ本任务无DDL变化，验证现有生成产物一致，不改数据库合同源。
- [ ] 使用最终构建的bundle复核L09健康/登录及发布引用，确保证据指向最终字节；仅制品变化且不影响场景时不重复无关完整业务测试，新失败按影响补测。检查公开Git差异无密码/令牌/私钥/备份，现有本地运行资源未变化。
- [ ] 完成全分支代码审查，修复实际问题并重跑受影响验证；更新各项checkbox、证据及限制。提交开发分支，不以设计/模拟测试替代完成声明；合并main/实际云部署依后续明确操作指令执行。
- [ ] 提交本项，消息 `docs: deliver Linux release and initialization workflow`。

## 自审与交付判定

计划自审已覆盖设计第1–11节：发布与恢复L01–L03/L10，首次身份与授权L06–L07，来源L04–L05，经办/策略L08，候选目录L01，真实验证L02/L03/L06/L07/L09。五项Review Focus均有对应反例；相邻任务接口名称和私密数据边界一致。

实施结束分别报告数据库迁移、身份初始化、业务准备、合成业务验收四种结果。未审核模板、分配权限待指派和其他人员首次改密属于已知准备条件，不能隐藏在“全部可正式使用”结论中；本计划确认后按当前会话顺序实施。
