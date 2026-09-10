# Task 9.6e 受控本地身份链

## Task 9.6k 独立六卡业务入口

`e2e/business.config.ts` 是独立入口，默认只定义并运行 `offline-business`；只有命令行明确选择 `--project approved-local-business` 时才注册真实项目。实现者仅运行离线项目；离线通过不代表真实浏览器、数据库闭包、U01–U03、完整七卡、容量或R1总验收通过。

真实入口只接受当前 release `6411135a52094b6ba16a80df80b025a1`、build `421ca57aed3d2f364fea2af64b12b5c9d6226c7d`、gate12，以及已完成身份 run `74a496f6-494e-417d-9abd-69a85c94f165` 的原 journal SHA256 `44c95f59853fa552d2d7ba933dcb80a4877464fe26dab7c34202d3e1fb0ad9a1`。它逐份核对原七阶段报告并只读取得原 resource IDs；所有业务操作前后重新核对原记录和当前三进程／制品，绝不改写身份 journal。

业务 journal 固定为受保护 runtime 下的 `task9-business-operation.json`。只允许两次合成 capture、一个 `SALES_CONTACT_OWNER` 直接授权、六次候选保存和六次明确提交，共15个首次派发；PENDING、持久化不确定、阶段报告隔离、跨run或跨制品均禁止新key。续跑必须显式设置同一 `TASK9_BUSINESS_CONTINUE_RUN_ID`，只查询原回执并从当前阶段的准确已确认位置继续，不重放完成步骤。

```powershell
$env:PATH='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/.bin;' + $env:PATH
node node_modules/@playwright/test/cli.js test --config e2e/business.config.ts --project offline-business
node node_modules/@playwright/test/cli.js test --config playwright.config.ts --project offline-harness
node node_modules/typescript/bin/tsc --noEmit --target ES2022 --module commonjs --moduleResolution node --esModuleInterop --skipLibCheck --strict e2e/business.config.ts e2e/fixtures/business-environment.ts e2e/fixtures/business-journal.ts e2e/fixtures/business-session.ts e2e/fixtures/r1-business-setup.ts e2e/tests/task9-business-harness.spec.ts e2e/tests/task9-six-workcards.spec.ts e2e/reporters/business-reporter.ts
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
