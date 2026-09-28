# Q 转案接收实现记录（进行中）

Q 高保真已获用户确认。保持 P/P1/Q 共用样式，不提前要求销售选择案件业务分类。

已验证：
- 转案来源精确绑定已执行合同、批准版本、人工执行核验、签署归档和激活摘要。`R2TransferSourceIT` 1 项通过（26.34 秒）。读取本身不生成案件或转案请求。
- 提交输入要求准确材料版本、人工一致性确认以及完整退回项回应；3 项单元测试通过。
- 准备、独立冲突审查、案管接收、接收后分类的 Q 表单组件：10 项交互测试通过；结果不明锁定重复写入，退回不携带虚构接收确认，无案件身份不显示分类表单。
- 1440、390、360 像素共 12 项组件视口检查及退回模式通过，无横向溢出或脚本错误；已人工查看桌面及手机截图。

证据：`output/f11-transfer-source-green.log`、`output/f11-transfer-forms-final.log`、`output/f11-transfer-typecheck.log`、`output/playwright/f11-transfer/qa.json`。

转案请求原子创建已在真实 PostgreSQL 验证：4 项通过（56.04 秒），覆盖无执行事实拒绝、服务/人工边界、权限拒绝、事务回滚和并发唯一创建；接受槽与案件身份均保持为空。证据 `output/f11-transfer-request-green2.log`。该服务尚未接入实际恢复任务。尚待实现/接通：不可变提交及补正、独立审查、案管接收生成唯一案件、分类持久化，以及待办/权限/回执/接口/页面整链。上述表单组件尚未接入运行页面，不能作为 F11 或 R2 全部完成的证明。运行环境未更新。


本轮新增：销售提交会在同一事务中保存不可变材料/客户/合同依据、完成销售待办，并生成独立审查待办；缺少合格审查人时进入责任异常，恢复后保留原期限。同一个人切换任职不能自审，服务层及数据库双重阻断。真实数据库 4 项测试通过（58.62 秒），覆盖正常接续、回滚、缺权恢复和自审拒绝；输入 3、任务映射 1、运行版本 1、Worker 8、架构 13 项通过，Schema 2 项通过。证据 output/f11-submission-final.log。V1050/v19 尚未部署，运行页面尚未接入；没有生成审查结论或案件。独立审查决策、补正、接收和分类仍在后续实施范围内。


F11 predecessor-closure hardening: a real DB negative test reproduced that inserting a successor while its predecessor task stayed OPEN was allowed (output/f11-successor-open-red.log). V1050 now requires exact DONE/submission completion for sales submission, and CANCELLED predecessor for unchanged-submission responsibility reassignment. Owner recovery rejects terminal completed tasks instead of reopening them. Full five-case TransferWorkflowIT rerun exits0 (output/f11-successor-closure-green.log); schema tests2 and deterministic generation pass, all8 Q approved asset hashes unchanged. No live deployment; review decision/intake/classification integration still pending.


## 2026-09-27 接通与验收更新

上述“未接入”的记录为早期检查点，当前实现已推进到以下状态：

- Q 五个办理动作已接入共用工作台、准确任务上下文、受保护材料读取、人工权限、事务审计和原回执恢复。共用 CSS 未调整。
- 实际 HTTP 导入的同一销售记录已通过合同、签署、执行条件、两笔独立收款、转案提交、独立审查、案管接收及分类。`output/f11-full-http-green.log` 中整链1项通过，585.1秒；该轮转案准备仍由测试准备步骤建立，不能证明后台自动衔接。
- 本次补入实际后台候选发现及 SERVICE 恢复命令，使用 `TRANSFER_HANDOFF` / `TRANSFER_RECOVERY`。已验证只读发现不建单、首次准备创建单一待办且不生成案件、同请求重放原回执、撤权与恢复保留原期限。`output/f11-auto-guard-green.log` 两项通过（含恢复命令与接收后快照保护），62.24秒。SERVICE 原回执以重放同一命令读取，沿用现有设计，不走人工回执 GET。
- 新增接收/分类结果投影到原合同历史及台账，修复完成后仍显示“已具备转案条件”的问题。`output/f11-history-red.log` 复现缺失，`output/f11-history-green.log` 真实数据库1项通过，45.11秒。
- 使用实际恢复命令替换测试准备步骤的整链回归正在 `output/f11-auto-final.log` 运行，尚不可记为通过。

仍未完成：转案办理入口在管理台账中的完整衔接、分类纠正入口、运行环境 v19 部署、F12 两类来源及付款条件的浏览器整体验收。当前运行环境仍为 v17，R1 PAUSED / R2 NOT_GRANTED，不将测试库通过写为用户运行环境已更新。


台账入口续验：`output/f11-entry-green.log` 真实数据库1项通过（36.69秒），校验有权办理的准确转案任务和撤权后不再可办。`output/f11-ledger-regression.log` 前端18文件123项通过；`output/f11-ledger-visual.log` 6项实际台账组件视口及入口检查通过（合成上下文），截图在 `output/playwright/f11-ledger/`。`output/f11-final-baselines.log` 8项合同基线检查通过，TypeScript检查退出0。

本地部署接入条件：在既有租户密钥配置项中增加 `transfer-destination-organization-id`，值必须是该租户已有且有效的案管组织 UUID；路由只从部署配置读取，销售提交不能改写。组织必须与销售责任组织不同。恢复服务仍要求 `CONTRACT_TASK_RECOVER`；销售、独立审查、案管接收、分类及承接使用对应具名权限并要求合同读取权。配置缺失或失效时，不创建请求或消费幂等请求槽；配置修复后可继续同一候选。此处仅记录接入条件，尚未改写用户运行环境的配置和权限。


### 2026-09-27 16:46 本地接入与整链续验

- 实际 SERVICE 自动发现/创建转案责任的完整导入链路：授权直签通过（638.1 秒），报价路径通过（786.7 秒）。末轮 `output/f11-ledger-final-quote-chain.log` 退出 0，包含最新台账入口及权限撤回验证。
- v19 已部署到原本地验收地址，V1040/V1050 两项迁移应用成功，41 项迁移校验通过。原应用、配置、材料、测试记录及数据库已备份在私有运行目录 `before-f11-20260927-164011`，未清空既有 Cxx 数据。
- 经现有审计身份接口补入销售执行/收款提交/转案、独立财务确认、独立案管审查/接收/分类/承接权限，配置可信转案组织和合成收款账户。原账号密码未改动。
- `output/f12-live-smoke3.log`：原 contact 账号实际 Keycloak 登录 → 工作台 → 商机台账 → 合同台账 → 真实合同详情通过；1440/390/360 无横向溢出、无脚本异常。截图 `output/playwright/f12-live/`。首轮仅检查页面外壳，末轮已等待真实记录及详情载入，不把外壳成功作为数据验收。
- 先款两路径仍在 `output/f12-prepay-matrix4.log` 运行，未计为通过。收款后必须经实际后台调度推进执行核验，不能直接假定财务确认等于已执行。
- 分类纠正的领域命令和唯一案件保护已验证；已批准 Q 的完成态仅提供历史查看，尚无分类纠正管理入口，保留为后续管理收口缺项。F12 浏览器完整业务矩阵、后续 R2 管理/AI 工作仍未完成；R1 PAUSED / R2 NOT_GRANTED。


本地续验：案管首次登录发现缺少共用台账 `OPPORTUNITY_LEDGER_READ` 读取授权，已在合成来源组织范围补齐（财务同样补齐），`output/f12-live-intake2.log` 通过。后台已为既有 C45 自动接出执行条件与逐笔收款责任，contact/delegate 分别从“我的待办”打开真实执行/财务卡，三个视口通过；未代用户确认 C45 的执行或到账。截图见 `live-v19/`。跨角色选择验证：销售请求财务任务时，现有接口返回本人推荐事项，当前卡与我的待办均不含该财务任务；第一轮仅断言 HTTP 403 的测试预期不符合该回退约定，第二轮按实际隔离结果验证通过。

Q1 分类纠正补稿已完成并请求确认：`docs/design/r2-sales-mvp/review/2026-09-27-q1/`。仅增加原计划内完成后的更正入口与说明，四个共享 CSS 与 Q 字节一致；三视口、更正后原值回填和取消返回验证通过。尚未收到确认，未实现新增产品入口。
