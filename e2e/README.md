# Task 9.6e 受控本地身份链

本目录交付测试，不部署服务。当前已验收 local-login runner 承担本地 compose 环境职责；CI 适配仍属 Task10。U01–U03 均为 `NOT_EXECUTED`。离线测试不代表真实浏览器、身份管理或人工验收通过。

## 当前制品绑定与执行边界

控制者已提供 9.6g 入口修复的独立评审、实际构建/部署/协议检查及三进程核对证据。本目录只据该证据更新精确 `PIN`：业务 buildSha `6c6c6d90105b6d647fd213afed0c30dd9ff3a594`（包含已评审产品提交 `4db2509`），package `2db735dbaddc435fb585483f5393f40e`，revision11；完整 Jar/manifest 摘要保存在 `local-environment.ts`。这是实际业务构建来源，不是后续测试提交。此次机械绑定不改变已评审入口或精确401驱动逻辑，仍需独立评审，再由控制者用新 UUID 先执行 `T9-L01-entry` 与 `T9-L03-unmapped`；其余阶段不据此宣称完成。旧失败 run 已由控制者按零命令/零阶段留档，不在本次续跑。本实现者没有运行真实身份链，U01–U03 保持 `NOT_EXECUTED`。

替换包只接受精确 kind `controlled-local-release`：解析后的 release-manifest 必须等于 record.provenance，provenance 的规范化摘要必须等于当前 active_manifest_hash，provenance.jarSha256 必须同时等于 active_release_digest 与实际 Jar 字节摘要。未知、legacy、旧 source-release kind 均拒绝。保持 current-release/descriptor/package 字节检查和严格当前三进程命令/PID/创建时间校验，不变更已有 AUTO/MANUAL 来源配置。旧 Location 缺陷 build `04bd695f7a8f656a5ed8fb96c5168e44a91bab8d` 仍在派发前硬性拒绝；冻结 OpenAPI ReceiptLocation 要求保持，禁止接受旧资源 Location 或自动重启 API。

## 离线命令

使用已固定 Node 24.20.0 / npm 11.9.0；`@playwright/test=1.63.0` 与 `@types/node=24.13.3`（类型检查具名补充）由根 lock 完整锁定。无需浏览器启动，也不读取私有 runtime。

```powershell
$env:PATH='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/.bin;C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/node-v24.20.0-win-x64;' + $env:PATH
node C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/npm-11.9.0/node_modules/npm/bin/npm-cli.js run test:e2e:task9 -- --project offline-harness
node node_modules/typescript/bin/tsc --noEmit --target ES2022 --module commonjs --moduleResolution node --esModuleInterop --skipLibCheck --strict playwright.config.ts e2e/fixtures/local-environment.ts e2e/fixtures/operation-journal.ts e2e/fixtures/identity-setup.ts e2e/reporters/safe-reporter.ts e2e/tests/task9-harness.spec.ts e2e/tests/task9-identity-entry.spec.ts
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
