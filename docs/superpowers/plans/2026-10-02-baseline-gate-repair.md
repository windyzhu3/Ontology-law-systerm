# 已批准功能基线门禁修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 修复当前已批准功能的遗漏登记和实际注释缺失，通过合并所需校验后将已授权改动合并 main。

**Architecture:** 保留 R1 原始精确集合和摘要，逐项验证已批准 successor 后投影回历史合同。历史迁移字节不改；实际缺失的中文注释仅以新增注释迁移补齐。运行环境账户与业务事实不因门禁修复重新初始化。

**Tech Stack:** Java 25、Maven/JUnit、Python unittest、PostgreSQL/Flyway、GitHub CLI。

**Spec:** 后台可配置岗位设计、个人等待已批准设计、ADM-07 查询计划、第二管理员验证记录及既有 schema 演进合同。

## Global Constraints

- 不增加业务功能、岗位自动权限或审计导出；不删除或放宽失败断言。
- 五个岗位接口、一个岗位回执事实、四种岗位命令、v21 schema 必须具名登记；未知接口、命令、事实、版本仍拒绝。
- 个人等待两项查询及 DTO 必须通过精确完整 successor 校验；R1 37 项操作及冻结摘要保持不变。
- V001～V1070 字节与全部历史摘要保持不变；注释修补只能新增 migration，不修改函数体、约束、表结构或权限。
- 合并前完成必要静态、单元、集成、生成一致性和 CI 验证；不绕过失败门禁。

## Review Focus

- 未登记接口或事实混入闭合集合必须失败。
- SERVICE／代办不能利用岗位命令进入管理授权路径。
- successor 缺项、重复、任意字段漂移必须失败。
- 注释迁移不得修改旧 SQL 或业务语义；旧版本准确历史投影保持有效。
- 既有环境两管理员、审计权限及八个已接案事项不得重建或改变。

## Task 1：Java 登记

- [x] 读取已批准岗位设计并复现 29 项测试中的四项失败。
- [x] 更新 `OpenApiContractTest` 的具名操作、准确路径数量和岗位事实分支。
- [x] 更新 `CommandEnvelopeTest` 为准确十八项身份命令，保留 HUMAN／SERVICE 断言并补代办反例；登记 v21 与未知版本／错误摘要反例。
- [ ] 运行目标 29 项测试，再运行完整后端回归。

## Task 2：传输历史投影

- [x] 证实已有投影留下个人等待两个接口，导致冻结 R1 37 项断言实际为 39 项。
- [x] 新增精确个人等待 successor 及负向测试，接入现有投影链，保留各层原 PIN。
- [x] 恢复稀疏检出排除的现有 Git 设计资产，只恢复原始字节。
- [x] 运行基线 verifier、全部对应反例测试及生成一致性检查。

## Task 3：schema 历史校验与注释修补

- [x] 在正确目录复现既有 schema 116 项中的十项失败，区分历史断言与实际注释缺失。
- [x] 历史测试使用其具名版本，而当前 inventory 使用已批准精确新增集合；保留历史 migration 和 contract SHA 校验。
- [x] 新增注释专用 migration，完整清单补齐缺失函数中文注释及协商类型说明；新增 source／manifest successor 精确校验与无语义改变测试。
- [x] 修复注释修补暴露的遗漏版本登记，运行静态和真实 PostgreSQL 迁移／权限／事实一致性验证。

## Task 4：验证与集成

- [ ] 完整相关测试和生成检查通过，独立整分支复核一次，修复重大问题。
- [ ] 保存公开验证记录、提交推送、确认 CI，不使用绕过门禁参数。
- [ ] 按已授权范围处理叠加 PR 并合并 main；核对本地与远程 main。

### 2026-10-02 验证进展

目标集成 32 项、schema 118 项、successor 17 项已通过。SQL 解析通过 43 项 migration / 119 个 PL/pgSQL 函数；生成文件检查通过。完整单元最近结果为 499 项、零失败、4 项跳过；修正旧夹具后完整集成重新执行中。独立整分支复核已完成，未发现 Critical / Important 问题。Windows 原 R1 runtime 两次迁移中的首次 schema 断言通过，但 Docker Compose wait 返回 no containers，整体验证失败，必须由 GitHub Linux 门禁继续验证，不能据此合并。
