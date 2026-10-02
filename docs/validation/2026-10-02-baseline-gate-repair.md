# 已批准功能基线门禁修复验证

日期：2026-10-02。接续第二管理员与 ADM-07 查询验证；不修改当时记录，不重复创建账户或授权。

## 改动与边界

- 精确登记已批准岗位操作、身份命令和 schema v21 / v22；未知版本与权限路径继续拒绝。
- 个人等待与海华岗位通过完整 successor 校验后投影回原 R1 合同，冻结摘要不变。
- V1080 只补齐 55 个函数、94 个触发器和一列中文注释，以及具名 deployment schema 版本。V001～V1070 全部字节不变。
- 旧业务夹具明确配置其合成岗位、采用当前版本 HTTP 栅栏；V950 历史回填测试使用当时存在的来源图，不修改生产授权规则。
- CI 使用既有 R2 开发准入。R1 历史运行校验使用独立临时 schema；完整 R2 migration 仍由 Maven 集成测试验证。此结果不授予 R1 实际业务验收或 R2 发布准入。

## 已核实结果

- schema unittest：118 项通过；generate --check 通过。
- SQL / PLpgSQL 解析：43 项 migration、119 个函数通过。
- 目标 HTTP / 历史回填集成：32 项通过，包括代办审计入口拒绝、线索来源披露及旧报价 provenance。
- 新 successor / Task9 / CI 隔离夹具：17 项通过。
- 最新已完成单元回归：499 项，零失败、零错误，4 项跳过。
- Linux 历史 runtime harness 单元：121 项通过。
- 真实 PostgreSQL V1070→V1080：1 项通过，函数体、触发器、约束、ACL 和所有应用事实一致；缺失注释已补齐。
- 运行环境事实只读复核通过，继续保留 v21、现有两管理员及八个接案事项。
- 独立整分支复核完成，未发现 Critical / Important 问题。

## 后续验证

GitHub commit a430b28 的 PostgreSQL 18 历史运行门禁已通过：两次干净迁移及全部反例完成。见 https://github.com/windyzhu3/Ontology-law-systerm/actions/runs/37014004874 。

完整回归发现旧合同分页断言未同步 R25-CONTRACT-RESPONSIBILITY-RECOVERY-CONTRACT 已批准的 PREPARE 失权扫描；仅修正测试为当前页空结果可以续查、下一页必须结束，生产代码不变。真实 PostgreSQL 单项回归通过。

独立 bootstrap 原始事实校验夹具原先直接写入未配置的 SERVICE / DELEGATE 岗位，8 项目标测试复现 2 项拒绝。夹具改用租户已配置的 CONTACT_OPERATOR；完整原始事实校验 15 项通过，不新增岗位或授权，生产未知岗位拒绝规则保持有效。

R1 兼容夹具继续保留原十四条投影路线，同时精确验证当前队列等于十四条加四个具名后续事件；离线 CLI 使用当前 v22 栅栏。目标 15 项通过。原两种到期恢复保持独立夹具，新来源复查使用其已有真实 HTTP 链路，合计 2 项通过，完整枚举仍精确拒绝未知类型。

完整 baseline verifier 在 GitHub 通过 205 项反例，另有 schema 118 项通过（commit a430b28，run 37014004574）。本地 Linux 同一套 205 项及 successor 17 项亦通过。当前业务集成时长接近原 R1 一小时预算，preflight 预算增加至 120 分钟，全部检查、断言与验收边界保持不变。

## 尚需完成

完整集成重新回归和 GitHub CI 正在验证。Windows 原 R1 runtime 因 Docker Compose wait 返回 no containers 整体失败；首次历史 schema 断言通过不能替代完整结果。main 合并必须等待全部必要门禁通过。
