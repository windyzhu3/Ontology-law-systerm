# Task 9.6e 受控本地身份链

本目录交付测试，不部署服务。当前已验收 local-login runner 承担本地 compose 环境职责；CI 适配仍属 Task10。U01–U03 均为 `NOT_EXECUTED`。离线测试不代表真实浏览器、身份管理或人工验收通过。

## 当前真实运行阻断

独立源码核对发现：`IdentityCommandRuntime.result` 返回业务资源 Location，而冻结 OpenAPI `ReceiptLocation` 与生产 SPA 要求 `/api/v1/commands/{commandId}/receipt`。9.6f 将独立修复和重建。已知缺陷二进制在派发前被硬性阻止，避免先提交再由SPA报未知。控制者完成评审并明确更新本目录 `PIN` 的已核验二进制来源、release、manifest、revision 后才可实际运行。禁止为通过测试接受旧资源 Location、自动重启 API 或把测试提交冒充应用 buildSha。

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

控制者设置 `TASK9_LOCAL_ACCEPTANCE=APPROVED_SYNTHETIC_ONLY` 与新 UUID `TASK9_RUN_ID`，再执行 `npm run test:e2e:task9 -- --project approved-local`。配置固定 workers1/retries0，7 个普通测试按明确阶段声明，不使用 serial 自动 skip。前置阶段失败时后续阶段失败且不写入；不得报告未执行阶段通过。

真实项目调用原 `RuntimeBoundary.protect()` 后读取当前制品、进程注册、原两份凭据、四账户操作清单和 `worker/grants.json` 的原 bootstrap 关联。只有已投影的原 tenant/ROOT/founder/appointment ID 进入测试内存；无数据库 Owner 凭据、SQL 或静态 HUMAN 注册。Keycloak 令牌 subject 仅在内存与原 providerUserId 比较；候选仍按完整用户名唯一精确查询，保持 selector 不透明。SELF 不公开 principalId，绑定主体由原 CREATE 回执事实及任职 principal 引用确认；原 subject/HMAC 的数据库闭包由控制者具名只读核对。

写入从真实 SPA 发出；浏览器路由在派发前将原 Idempotency-Key、原 actorScopeKey、HTTP固定路径及准确请求字节 SHA256 保存到受保护 `task9-identity-operation.json`。只允许四主体、一组织、四任职、七业务 DIRECT Grant；首联/代办无业务 Grant。Journal 的 resultFact 保留原 opaque factRef，并用公开 `R1_PUBLIC_FACT_REF_V1` 算法在已获权候选 ID 中精确验证关联，登记 resourcePath；不按名字收养业务事实。

## 中断和恢复

任何未知响应保留 PENDING 原 key，阻断全部新写入。`.pending` 临时文件说明落盘结果未知，不自动删除或覆盖。即使回执404，也不能推断未提交。收到201后必须符合冻结回执Location，再读取原回执并比对精确事实才确认。`httpStatus=201` 是实际写入响应，`httpStatus=200` 仅表示显式恢复的回执查询200，不补造原写入响应。

仅同一 `TASK9_RUN_ID`、同一 environmentDigest/原API进程/制品允许明确续跑。控制者另设 `TASK9_CONTINUE_RUN_ID` 为原 run UUID；如有 PENDING，还须 `TASK9_RECOVER_COMMAND_ID` 为原 command UUID。恢复只 GET 原回执及获权事实，不重发无正文写入。用 `--grep` 选择未完成阶段及其后继，已完成阶段不伪装重执行；例如主体建立阶段中断后选择 `T9-I02|T9-L04|T9-I05|T9-I06|T9-I13`。若环境变化、清单损坏、回执拒绝或原事实不匹配，停下交控制者核对。

每个实际子场景输出受保护独立 JSON：真实二进制 buildSha、environmentDigest、API启动身份摘要、时间、caseIdentity、状态、退出码、reportPath，以及仅含固定path/status的HTTP证据。原始响应、凭据、token、selector、subject/HMAC、storageState和请求正文不落盘。自定义 reporter 只输出闭合ID/状态/阶段码；敏感错误在测试退出前替换，关闭自动DOM快照，且无主动截图。本单元不包含十四管理生命周期、七卡、代办/等待恢复、撤销/禁用或人工签认。
