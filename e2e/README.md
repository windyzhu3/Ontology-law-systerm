# Task 9.6e 受控本地身份链

CI uses `npm run test:e2e:offline` to select only `offline-harness` and
`offline-business`. This preflight entry does not run or authorize any
`approved-local*` project and is not R1 runtime acceptance.

## Task 9.6o 具名草稿成功回执恢复

草稿响应值与发送候选值使用生产 `sameValues` 比较：对象属性顺序不影响相等性，字段值、缺失或多余字段仍拒绝。其他 journal、身份、回执和字节完整性比较不变。

9.6n 的普通 restart 仍拒绝 PENDING。仅控制者已批准核对原成功回执时，在原业务批准、原 run/CONTINUE 和原 `TASK9_BUSINESS_RESTART_SHA256` 之外，向本次子进程成对传入 `TASK9_BUSINESS_RECOVER_COMMAND_ID`（原 contact-draft UUID）与 `TASK9_BUSINESS_RECOVER_JOURNAL_SHA256`（核对前 journal 原始字节 SHA256）。任一个变量存在就必须两者合法；缺少 restart/CONTINUE 或只给部分参数不能回退。当前批准的原始 journal SHA 为 `1c35efc0cf060718439baed5ae1acc34bccda1ac8745faef2cad146c296612b5`，命令 UUID 由控制者从原受保护记录核对，不重新生成。

该许可只接受准确12条命令中唯一第12条 `contact-draft` PENDING、前11条 CONFIRMED、原两阶段及原检查点/凭据/保全证明；任何 `.pending` 或 `.completion.pending` 均拒绝。当前进程与制品仍完整核验。打开时固定原前11条及 PENDING 原命令字段，后续不得从可变 journal 刷新这些绑定。恢复沿用原 contact 账号及 `reconcilePending`，只 GET 原命令回执和获权当前卡，核对 Actor、task/subject/revision、taskETag、draftRef/revision 与规范化意图摘要后，调用原 `journal.complete(originalId, 200, receipt, selectors)`；200只表示回执查询，不补造原 PUT 状态。

404、失败或不匹配的回执、Actor/草稿漂移均保留原 PENDING 并阻断新写入；不重发、不换 key、不使用旧进程身份绕过。成功后本次 context 可继续原 `contact-submit`，保留原11条及两阶段。带旧恢复参数再次加载已变化 journal 必须失败；控制者成功后移除这两个 RECOVER 参数，普通无 PENDING restart 才能再次打开。恢复不是产品接口，也不替代独立评审、真实恢复前环境核对与操作后数据闭包。U01–U03延期、R2排除及完整Task9未完成的边界不变。

## Task 9.6n 原运行的同构建重启衔接

只支持已批准的业务 run `9848f4ee-5612-49df-9e10-a8c40c09bd3d`。控制者在独立评审通过后独占创建同一受保护 runtime 内的 `task9-business-restart.json`，并保留 `task9-business-pre-restart.json` 原 journal 字节副本及 `task96n-idp-addition-proof.json` 精确新增用户保全证明；实现者只使用隔离临时合成文件。衔接凭据固定 profile `TASK9_SAME_BUILD_RESTART_V1`、当前 BUSINESS_PIN 制品、原检查点 SHA、原／当前环境和 API 身份、9条已确认命令、2个已提交阶段和第三 case。它是本次验收记录的完整性绑定，不是产品授权或通用迁移接口。

控制者必须继续设置原业务批准标记及相同的 `TASK9_BUSINESS_RUN_ID`／`TASK9_BUSINESS_CONTINUE_RUN_ID`，另外显式传入凭据原始字节 SHA256 `TASK9_BUSINESS_RESTART_SHA256`，只选择 `T9-W01-assign-contact-review`。没有该变量时仍执行原严格同环境检查；变量存在但不合法时拒绝，不回退。凭据、检查点、保全证明、历史报告或原九命令／两阶段前缀变化，任何 PENDING、`.pending` 或 `.completion.pending`，以及再次进程／制品漂移均拒绝继续。不得更换 run、刷新检查点摘要或重放已成功步骤。

内存与磁盘 journal 的原 identity 保持不变；环境消费者始终计算实际当前三进程环境摘要。历史两个报告仍使用原环境及原 API；第三报告绑定当前环境及当前 API，沿用第三 case 的7条报告命令（原 capture-manual 加剩余6步），完整 journal 必须为准确顺序的15条命令。原 capture-manual 不声称在重启后重执行，因果边界由原始检查点和9条保全命令明确记录。所有后续写入沿用原互斥、fsync、字节比较与原子发布围栏，未生成新的恢复／派发通道。

默认离线发现包含 `task9-business-restart.spec.ts`；环境和 setup 的可注入依赖仅用于离线控制外部读取，真实默认仍调用原 toolchain、runtime 与完整保护／快照。离线验证不表示真实续验、三卡或完整 Task9 已完成。

## Task 9.6p 首联等待准备入口

独立项目 `approved-local-contact-wait` 只匹配 `task9-contact-wait.spec.ts` 的 `T9-W09-contact-wait-preparation`。默认 `offline-business` 包含新增等待 harness；未明确选择该真实项目时不发现真实等待测试。项目选择使用原 Playwright CLI 已解析 options；grep、其他选项值与 `--` 后的文本不构成真实入口授权，真实项目拒绝 reporter 覆盖。

控制者完成独立评审后，使用原严格 CA 和当前 BUSINESS_PIN，设置 `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY`、`TASK9_BUSINESS_ACCEPTANCE=APPROVED_CONTACT_WAIT_CHAIN`、全新 UUID `TASK9_BUSINESS_RUN_ID`，执行 `node node_modules/@playwright/test/cli.js test --config e2e/business.config.ts --project=approved-local-contact-wait`。显式 CONTINUE 只能等于该新 run；等待模式拒绝任何存在的 RESTART_SHA256、RECOVER_COMMAND_ID、RECOVER_JOURNAL_SHA256（包括空值）。不能通过原 `BusinessSetup.create` 进入，也不能在六卡模式调用 `createContactWait`。

新 `task9-contact-wait-operation.json` 固定五步：manual capture、assign draft/submit、contact draft/submit；唯一阶段报告前缀为 `task9-contact-wait-<run>-<case>-<uuid>.json`。记录身份指向已闭合六卡 run `9848f4ee-5612-49df-9e10-a8c40c09bd3d`，原 raw SHA `841a4d275bf97bdb832bbb138f70efbc310dbbe10f532d6c4dbb435380d52333`。保护时只读核对其15条/3阶段、原三份报告哈希和旧 identity 前驱，并只复用第8条记录的准确 contact Grant ID。原六卡记录不追加或重放。

真实准备使用原会话、四账号/任职、原12个 Grant、MANUAL 来源与 `task96p-<newrun>` 合成标识。capture 是 API 准备；两次候选保存和明确提交仍走原 UI 与 armed 网络门。`NOT_CONNECTED` + `EMAIL` 提交后必须同时确认真实回执、无 current/next 卡、waitingCount1、禁用 composer、准确等待标题、等待数和服务端今日摘要的页面文本，才发布唯一完成报告。未知 capture/submit 保留 PENDING 并禁止新 key；沿用安全的同 run 候选回执核对，不新增提交恢复或 restart 资格。

离线用真实临时 journal、共享会话/派发/持久化消费者与外部浏览器 transport 验证。固定真实前驱 SHA 无法在不读取保护资料的前提下合成，完整 loader 的固定前驱正例留给控制者真实入口；离线不替代该门。控制者另行核对五个 Slot/Receipt/Audit、2 DONE + 1 WAITING、WaitReceipt、下一工作日10:00恢复与10:30 SLA，并保存准确等待 selector。这里只交付等待准备，不代表 Worker 到期恢复、W09重新打开或整个 W 组通过。

## Task 9.6l 续验缓存合同

业务续验在复用已登录页面前仍由真实“刷新当前责任”UI请求建立网络证据。工作台响应按自身合同校验：`200`必须返回完整工作台 envelope、强`"wb.…"` ETag、`Cache-Control: private, no-cache`及`Vary: Authorization`；合法`304`还必须精确串联同一当前 Actor 的先前`200` envelope、请求`If-None-Match`与响应 ETag。缓存和 Vary 指令按不区分大小写的 token 语义解析，不依赖序列化顺序；缺失、冲突或错误策略均清空已观察凭据、失效缓存证据并关闭写门。

首次进入工作台的真实 UI `200`通过页面 response 边界排队记录；复用前等待该观察完成，避免首个缓存刷新已返回`304`时丢失先前证据。身份管理刷新保持独立的严格`200`/`no-store`合同，不接受工作台缓存策略或`304`。这是剩余真实续验的测试消费者前置，不重置原 journal、不授权重放，也不表示三卡、U01–U03或完整 Task9 已完成。

缓存证据只接受生产`parseEnvelope`能够实际缓存的完整响应，并绑定 UI 请求发出时的 Actor 与递增 generation；晚到的旧 generation 不得覆盖较新证据，Actor 已变化的响应失败关闭。初始或后续 UI 观察仍待完成时，工作台就绪、测试写入 arm 与最终路由派发均不得越过。测试侧的 CURRENT 核对改用同一严格浏览器上下文的只读`context.request.get`，固定同源 GET、现有 UI 观察凭据、`maxRedirects: 0`且无正文；它不产生 page response，因此不会冒充 SPA 缓存更新，其余读写 transport 保持不变。

## Task 9.6k 独立六卡业务入口

`e2e/business.config.ts` 是独立入口，并以稳定名称定义 `offline-business` 和 `approved-local-business`，以便控制者与 worker 重载同一项目身份；默认发现和运行仍只有 `offline-business`，真实项目保持惰性且只有命令行明确选择 `--project approved-local-business` 时才匹配测试。实现者仅运行离线项目；离线通过不代表真实浏览器、数据库闭包、U01–U03、完整七卡、容量或R1总验收通过。

项目选择和 reporter 覆盖判定复用锁定的 Playwright 1.63.0 所导出的 `playwright/lib/program` 的已解析 test options，不自行扫描 argv；`--project=approved-local-business` 等价有效，其他选项的必需值和 `--` 后的参数不构成项目选择或 reporter 覆盖。worker 无主进程解析状态时仍保留稳定项目身份；无浏览器合成子进程回归消费实际配置验证此边界。升级 Playwright 时必须重新验证该导出及控制者到 worker 行为。

跨阶段复用已登录页面时，先通过原管理页“刷新”或工作台“刷新当前责任”让 SPA 校验／续期自身会话；只在准确同源 GET 成功且已观察到原任职、无代办的请求凭据后，才继续测试侧读取。刷新失败清空旧凭据并停止写入，不回退旧 Bearer、不手动刷新令牌、不改变会话寿命。

真实入口只接受当前 release `6411135a52094b6ba16a80df80b025a1`、build `421ca57aed3d2f364fea2af64b12b5c9d6226c7d`、gate12，以及已完成身份 run `74a496f6-494e-417d-9abd-69a85c94f165` 的原 journal SHA256 `44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1`。它逐份核对原七阶段报告并只读取得原 resource IDs；所有业务操作前后重新核对原记录和当前三进程／制品，绝不改写身份 journal。

业务 journal 固定为受保护 runtime 下的 `task9-business-operation.json`。只允许两次合成 capture、一个 `SALES_CONTACT_OWNER` 直接授权、六次候选保存和六次明确提交，共15个首次派发；PENDING、持久化不确定、阶段报告隔离、跨run或跨制品均禁止新key。续跑必须显式设置同一 `TASK9_BUSINESS_CONTINUE_RUN_ID`，只查询原回执并从当前阶段的准确已确认位置继续，不重放完成步骤。

```powershell
$env:PATH='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/.bin;' + $env:PATH
node node_modules/@playwright/test/cli.js test --config e2e/business.config.ts --project offline-business
node node_modules/@playwright/test/cli.js test --config playwright.config.ts --project offline-harness
node node_modules/typescript/bin/tsc --noEmit --target ES2022 --module commonjs --moduleResolution node --esModuleInterop --skipLibCheck --strict e2e/business.config.ts e2e/fixtures/business-environment.ts e2e/fixtures/business-restart.ts e2e/fixtures/business-journal.ts e2e/fixtures/business-session.ts e2e/fixtures/r1-business-setup.ts e2e/tests/task9-business-harness.spec.ts e2e/tests/task9-business-restart.spec.ts e2e/tests/task9-six-workcards.spec.ts e2e/reporters/business-reporter.ts
node node_modules/@playwright/test/cli.js test --config e2e/business.config.ts --list --reporter list
```

控制者真实运行还须仅向该测试进程传入既有受保护 CA，并设置 `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY`、`TASK9_BUSINESS_ACCEPTANCE=APPROVED_SIX_CARD_CHAIN` 和新 UUID `TASK9_BUSINESS_RUN_ID`，再显式选择 `approved-local-business`。不得覆盖安全 reporter、关闭TLS、开启调试／trace／video／screenshot／storageState，或使用真实人员资料。本入口的阶段正文只写 `ACTIONS_VERIFIED`；只有控制者另行核对真实退出和 Fact／Receipt／Task／Draft／Event／Outbox／Audit 数据库闭包后才能判定该六卡子集。

本目录交付测试，不部署服务。当前已验收 local-login runner 承担本地 compose 环境职责；CI 适配仍属 Task10。U01–U03 均为 `NOT_EXECUTED`。离线测试不代表真实浏览器、身份管理或人工验收通过。

## 当前制品绑定与执行边界

控制者已提供 9.6g 入口修复的独立评审、实际构建/部署/协议检查及三进程核对证据。本目录只据该证据更新精确 `PIN`：业务 buildSha `6c6c6d90105b6d647fd213afed0c30dd9ff3a594`（包含已评审产品提交 `4db2509`），package `2db735dbaddc435fb585483f5393f40e`，revision11；完整 Jar/manifest 摘要保存在 `local-environment.ts`。这是实际业务构建来源，不是后续测试提交。此次机械绑定不改变已评审入口或精确401驱动逻辑，仍需独立评审，再由控制者用新 UUID 先执行 `T9-L01-entry` 与 `T9-L03-unmapped`；其余阶段不据此宣称完成。旧失败 run 已由控制者按零命令/零阶段留档，不在本次续跑。本实现者没有运行真实身份链，U01–U03 保持 `NOT_EXECUTED`。

替换包只接受精确 kind `controlled-local-release`：解析后的 release-manifest 必须等于 record.provenance，provenance 的规范化摘要必须等于当前 active_manifest_hash，provenance.jarSha256 必须同时等于 active_release_digest 与实际 Jar 字节摘要。未知、legacy、旧 source-release kind 均拒绝。保持 current-release/descriptor/package 字节检查和严格当前三进程命令/PID/创建时间校验，不变更已有 AUTO/MANUAL 来源配置。旧 Location 缺陷 build `04bd695f7a8f656a5ed8fb96c5168e44a91bab8d` 仍在派发前硬性拒绝；冻结 OpenAPI ReceiptLocation 要求保持，禁止接受旧资源 Location 或自动重启 API。

## 离线命令

使用已固定 Node 24.20.0 / npm 11.9.0；`@playwright/test=1.63.0` 与 `@types/node=24.13.3`（类型检查具名补充）由根 lock 完整锁定。无需浏览器启动，也不读取私有 runtime。

```powershell
$env:PATH='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/.bin;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;' + $env:PATH
node C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/npm/bin/npm-cli.js run test:e2e:task9 -- --project offline-harness
node node_modules/typescript/bin/tsc --noEmit --target ES2022 --module commonjs --moduleResolution node --esModuleInterop --skipLibCheck --strict playwright.config.ts e2e/readonly-session.config.ts e2e/fixtures/local-environment.ts e2e/fixtures/operation-journal.ts e2e/fixtures/identity-setup.ts e2e/fixtures/readonly-session.ts e2e/reporters/safe-reporter.ts e2e/reporters/readonly-reporter.ts e2e/tests/task9-harness.spec.ts e2e/tests/task9-identity-entry.spec.ts e2e/readonly/session-refresh.spec.ts
node node_modules/@playwright/test/cli.js test --config playwright.config.ts --list --reporter list
```

## 控制者实际执行边界

实际项目是 `approved-local`，默认入口同时列出两个项目；未设置明确批准标记时真实项目失败。实现者不得运行真实项目。运行依赖现有可信本机 CA、Chromium revision1243/version153.0.8010.12、同一 API/SPA/Worker 制品及已完成的四账户操作。不得启用 DEBUG、PWDEBUG、trace、HAR、video、storageState、其他 reporter 或自动截图。

控制者必须先通过原 `RuntimeBoundary.protect()`，再在启动 Node 测试进程时向该进程的环境传入 `NODE_EXTRA_CA_CERTS=C:/Users/Jacob/.cache/codex-worktrees/ontology-law-r1-business/.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime/certs/ca.pem`，指向既有受保护 CA 的绝对路径。该环境设置仅限本次测试进程，不持久化到用户/系统环境，不修改全局信任、CA 或密钥。Chromium 信任 Windows CA 不等于 Node 的 Playwright `APIRequestContext` 自动信任同一 CA；控制者已用相同 Node/Playwright 的严格 TLS GET 隔离出未加载该 CA 时证书链失败、加载后 HTTP200。始终保持 `ignoreHTTPSErrors=false`，禁止 `NODE_TLS_REJECT_UNAUTHORIZED=0`、替换 CA 或其他跳过证书校验的办法。这项诊断不代表完整 harness 已通过：新 run 首两场景首次失败证据仍保留，控制者仅按下述同环境/同 run 的显式 CONTINUE 规则重新验证首两阶段，不据此宣称其余阶段完成；本补充不改变 PIN、journal 或恢复资格。

控制者设置 `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY` 与新 UUID `TASK9_RUN_ID`，再执行 `npm run test:e2e:task9 -- --project approved-local`。配置固定 workers1/retries0，7 个普通测试按明确阶段声明，不使用 serial 自动 skip。前置阶段失败时后续阶段失败且不写入；不得报告未执行阶段通过。

真实项目调用原 `RuntimeBoundary.protect()` 后读取当前制品、进程注册、原两份凭据、四账户操作清单和 `worker/grants.json` 的原 bootstrap 关联。API、SPA、Worker 的注册和实际进程命令必须逐一等于当前 package 派生的命令，保留 PID/创建时间检查；历史受控包不满足本测试资格。三进程身份摘要进入 environmentDigest，初始化及后续快照均须一致。只有已投影的原 tenant/ROOT/founder/appointment ID 进入测试内存；无数据库 Owner 凭据、SQL 或静态 HUMAN 注册。Keycloak 令牌 subject 仅在内存与原 providerUserId 比较；候选仍按完整用户名唯一精确查询，保持 selector 不透明。SELF 不公开 principalId，绑定主体由原 CREATE 回执事实及任职 principal 引用确认；原 subject/HMAC 的数据库闭包由控制者具名只读核对。

写入从真实 SPA 发出；浏览器路由在派发前将原 Idempotency-Key、原 actorScopeKey、HTTP固定路径及准确请求字节 SHA256 保存到受保护 `task9-identity-operation.json`。只允许四主体、一组织、四任职、七业务 DIRECT Grant；首联/代办无业务 Grant。Journal 的 resultFact 保留原 opaque factRef，并用公开 `R1_PUBLIC_FACT_REF_V1` 算法在已获权候选 ID 中精确验证关联，登记 resourcePath；不按名字收养业务事实。

## 中断和恢复

任何未知响应保留 PENDING 原 key，阻断全部新写入。`.pending` 临时文件说明落盘结果未知，不自动删除或覆盖。即使回执404，也不能推断未提交。收到201后必须符合冻结回执Location，再读取原回执并比对精确事实才确认。`httpStatus=201` 是实际写入响应，`httpStatus=200` 仅表示显式恢复的回执查询200，不补造原写入响应。

仅同一 `TASK9_RUN_ID`、同一 environmentDigest/原API进程/制品允许明确续跑。控制者另设 `TASK9_CONTINUE_RUN_ID` 为原 run UUID；如有 PENDING，还须 `TASK9_RECOVER_COMMAND_ID` 为原 command UUID。恢复只 GET 原回执及获权事实，不重发无正文写入。用 `--grep` 选择未完成阶段及其后继，已完成阶段不伪装重执行；例如主体建立阶段中断后选择 `T9-I02|T9-L04|T9-I05|T9-I06|T9-I13`。若环境变化、清单损坏、回执拒绝或原事实不匹配，停下交控制者核对。

阶段完成使用持久 `.completion.pending` 隔离文件：先创建并刷盘待提交清单，再独占写入和刷盘 `ACTIONS_VERIFIED` 证据（exitCode=null，不单独声明通过），核对字节、保护和原清单后，以最后一次原子 rename 发布阶段资格。发布之后不再执行可能失败的证据/保护操作。只有正式 journal 的 `stages` 中对应 `PASSED_SUBSCENARIO`/exitCode0 和一致的 reportSha256 同时存在，才表示该子场景完成。任何此前完成错误保留隔离文件；本进程及明确续跑的新进程均不能继续写入。隔离文件和不确定证据不得自动删除/覆盖，需要控制者明确核对处理；普通 `TASK9_CONTINUE_RUN_ID` 不解除这种阻断。旧字符串阶段格式不会静默升级。

每个实际子场景的受保护独立 JSON 包含真实二进制 buildSha、environmentDigest、API启动身份摘要、时间、caseIdentity、证据状态、退出码、reportPath，以及仅含固定path/status的HTTP证据；完成状态以该 JSON 和正式 journal 的哈希绑定记录共同确认。原始响应、凭据、token、selector、subject/HMAC、storageState和请求正文不落盘。自定义 reporter 只输出闭合ID/状态/阶段码；敏感错误在测试退出前替换，关闭自动DOM快照，且无主动截图。本单元不包含十四管理生命周期、七卡、代办/等待恢复、撤销/禁用或人工签认。

## 非阻塞边界与独立只读持续会话入口

Python 边界使用固定可执行文件的异步子进程，仍逐次运行完整保护/快照，不缓存或跳过检查；60秒上限、stdout/stderr各2MiB上限、隐藏窗口和错误脱敏不变。所有调用等待完成后才使用结果。Journal 同一实例的 mutator 在第一个 await 前排他占用，竞争请求直接拒绝而非排队；跨实例仍以独占 pending 文件和原字节比较拒绝旧状态覆盖。写入路由在 headers/环境检查前消耗唯一 armed 许可，派发前再次核对失败状态。任何未知结果保留原 key/pending，不自动重试。

以下是控制者专用的独立验收入口，不在原7 CASES中、不打开 OperationJournal、不改原清单、不重放任何命令。原 run 已有7阶段/16 CONFIRMED；只读取其精确 SHA256 `44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1`，前后均须相同。继续使用上文固定 Node、原 protect 前置及**仅测试进程**的既有 CA `NODE_EXTRA_CA_CERTS`；保持严格 TLS，禁止全局环境/信任修改或 `NODE_TLS_REJECT_UNAUTHORIZED=0`。不设置 CONTINUE/RECOVER，不需要新 run。

```powershell
# 仅控制者：先完成上文 protect 与进程级 CA 设置，再启动此独立进程。
# 向测试进程传入 TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY
# 和 TASK9_READONLY_SESSION=APPROVED_EXISTING_RUN_ONLY。
node node_modules/@playwright/test/cli.js test --config e2e/readonly-session.config.ts
```

入口真实 founder 登录并确认管理员，250秒无 keepalive 后站内读取组织与主体；只有真实 token endpoint POST 的内存 `grant_type=refresh_token` 才启动完整 assertUnchanged 快照及3次 protect。请求不 Mock，刷新 HTTP 必须发生在该异步边界未结束时，并且随后组织/主体 GET200、no-store及安全管理员 UI 同时成立。所有业务 mutations 和非白名单请求均拦截；任何被拦截请求均使结果不能通过。普通列表GET不等同于生产 OIDC/SELF 的10秒 bound。零业务写入不声称零读取审计。

独占新建的闭合报告为 `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/output/task96e-readonly-session-<uuid>.json`；只含固定 step/status、refresh/组织/主体 HTTP、session/login/admin booleans、阻断计数及 journal/environment 摘要，不含 token、凭据、raw DOM/error/body。明确异常为 FAILED；无其他故障且未观察 refresh 为 NOT_TRIGGERED，绝非 PASS。仅全部门同时满足才可标记 PASSED_READ_ONLY_SUBSCENARIO，不能代替原7阶段或U01–U03。当前已报告的生产 refresh nonce 兼容问题单独处理，此入口不放宽生产校验、不把 refresh200 单独当成功，也不据离线异步测试推断旧 I05/I06 timeout 根因或持续会话已验收。
# Task 9.6q: existing waiting card read-only acceptance

`e2e/readonly-waiting.config.ts` is an isolated entry for the existing authenticated waiting predecessor. Its default discovery selects zero real cases. The default business config discovers only the new offline consumer tests, alongside its existing offline cases.

After independent review and the controller's unchanged database/IdP/material preflight, the controller alone may run the pinned Node CLI with `test --config e2e/readonly-waiting.config.ts --project=approved-local-readonly-waiting`. The two required flags are `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY` and `TASK9_READONLY_WAITING=APPROVED_EXISTING_WAIT_ONLY`; all business run/continue/restart/recovery flags and identity run/continue/recovery flags must be absent. External reporters, repeat runs, retries, debug logging, recording, storage state and disabled TLS are refused. No credentials or tokens belong in the command line.

The actual consumer loads the current shared BUSINESS_PIN and authenticates both old predecessors plus the fixed waiting journal/checkpoint. It logs in through the existing contact UI, observes an initial 200 and six automatic reads at 25–45 second start intervals, retains the mounted page until the most recently observed bearer naturally expires plus two seconds, and clicks the real Refresh button once. Renewal is lazy: the click triggers the SDK's ordinary refresh. Passing requires a real same-Actor 304 chain, a successful refresh POST, changed bearer, unchanged waiting envelope/UI, continued paused state and 60 seconds without additional CURRENT GETs. No epoch is inspected. Long waits are sliced at 30 seconds; fixture time reduces the available 510-second flow deadline under the 540-second test timeout. Insufficient due-time margin gives NOT_EXECUTED; an unavailable renewal/304 opportunity gives NOT_TRIGGERED, never a pass.

Reports use exclusive/fsynced `output/task96q-readonly-waiting-<uuid>.json` files. Their closed metadata/counts/booleans omit rows, payloads, tokens and token digests. The final environment/file guard runs after browser closure. A failure before a verified environment is available emits only the closed reporter and the controller's actual process exit evidence; it cannot fabricate a verified report. The controller binds successful SELF observations to independent database audit deltas. This empty waiting case does not prove nonempty-card disclosure audit gates or complete W06/W08/Task9. Human UAT remains `USER_CONFIRMED_CLOSED` independently of this run.

Offline injection is limited to Playwright's external browser transport, clock (including the fixture deadline), report UUID entropy and temporary files. The real route, cache, envelope, identity, UI, budget, lifecycle and report validators run in every consumer test. Three selector regressions additionally use pinned headless Chromium with synthetic HTML only, offline networking, a deny-all content policy and aborted routes to exercise the actual two-span DOM and strict Playwright locator. The remaining app transport and timing stay external doubles; no runtime, real URL, login, database or IdP is used by offline tests, and no screenshots or traces are recorded.

## Task 9.6r: existing-session logout acceptance

`e2e/readonly-logout.config.ts` is a separate controller-only entry. Default discovery selects no real test. The controller must explicitly select `--project=approved-local-readonly-logout` and provide only `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY` plus `TASK9_READONLY_LOGOUT=APPROVED_EXISTING_SESSION_LOGOUT_ONLY`; readonly waiting/session, identity and business write/continue/restart/recovery flags are mutually exclusive. Reporter overrides, retry/repeat/parallel execution, recording, storage state, debug modes and disabled TLS fail closed. Credentials and tokens never belong on the command line.

The single `T9-L09-existing-session-logout` flow uses two pages in one context. Both enter as the original contact and independently prove SELF, the original appointment and the exact WAITING workbench. It first holds the source page's exact logout GET without forwarding it, waits boundedly for local and peer clearing with their distinct uncertainty messages, and then injects exactly one network failure. It next uses ordinary SSO re-entry on both pages. From each verified workbench it performs an ordinary navigation to `/login` and SSO re-entry to establish genuine application history before the confirmed logout. The normal hint-free logout GET verifies the Keycloak `#kc-logout-confirm > form.form-actions` method/action boundary and clicks its real visible `#kc-logout` confirmation input. Only normal SELF/CURRENT reads, an exact fixed-issuer authentication path set and static resources, the logout GET and the fixed `/logout/logout-confirm` POST are allowed.

Each entry is bound to request-time SELF/CURRENT evidence, so a response from an older entry cannot satisfy re-entry or replace its foreground bearer; a 304 may reuse only the separately validated same-Actor cache. After confirmed logout, each page's still-active pre-logout bearer is retained only in memory and probed against SELF and CURRENT with strict TLS and redirects disabled. All four responses must be 401 with expiry checked both before and after each request. Browser back/focus must visit the recorded main-frame application entry and then settle safely without reviving the old identity, waiting card or input UI; absent navigable application history is `NOT_TRIGGERED`, not a pass. Cleanup stops new observers, closes the browser, drains queued observations, clears bearer/SELF/cache/history evidence, and only then runs the final environment, journal and checkpoint guard.

Evidence is exclusively and fsynchronously created as `output/task96r-readonly-logout-<uuid>.json`. It contains only closed status/scenario values, pinned build/environment/API identities, original evidence hashes, safe HTTP statuses/counts, booleans, time and a closed failure step. It contains no credential, token, header, response body, DOM, subject, HMAC, CSRF/session code or raw exception. This automation does not replay q or any business command, does not prove other sessions or accounts, and cannot close full Task 9; Root owns the one controlled real execution and independent preservation/audit closure.
