# R2 T02 正常商机周期调度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Do not commit or deploy automatically.

**Goal:** 把已有INITIAL首次任务发现和DUE等待恢复组合进实际Worker，使R1有效联系形成商机后及历史商机的待办能够可靠接续。

**Architecture:** 复用R2OpportunityTaskScheduler、正式内部HTTP和数据库持久检查点；正常商机两种扫描独立于T01异常观察开关。Worker无业务SQL写入，仍经当前SERVICE授权和数据库运行门禁。默认关闭，启用属于受控发布配置。

**Tech Stack:** Java25 / Spring Boot / PostgreSQL / 既有mTLS内部HTTP。

## 范围与设计

用户已授权继续下一项整体实现。T02的正常商机周期总装边界见F README；本计划细化既定范围，不新增业务页面、API、权限、事实或Schema。E/F视觉不变，R1 PAUSED / R2 NOT_GRANTED。当前隔离worktree与已有未提交修改保留。

采用一项显式正常商机周期开关，精确启动INITIAL与DUE、强制持久检查点；不采用默认全枚举启动（会误开异常观察），不新建调度引擎。正常周期与异常观察可分别启停，健康逐项核对，关闭时释放会话锁。

## Tasks

- [x] T02-01 配置与生命周期：R1WorkerDeployment增加默认false的opportunity-task-scheduling-enabled；明确只允许具有检查点的已注册v3/v4 Schema。WorkerRuntimeHealth暴露正常周期enabled/initial/due，任一启用循环未健康则整体未健康；保护原R1及T01行为。
- [x] T02-02 单元验收：配置默认、显式开关、旧Schema拒绝、各健康分量独立判定；已有checkpoint和scheduler失败恢复测试保持。
- [x] T02-03 真实组合验收：实际打包API、TLS和数据库运行Worker。R1有效联系形成商机→自动唯一初始卡；真实进展形成等待→周期自动恢复同一等待卡；保留原Owner/期限，无伪进展。停止/重启持久检查点不重复卡；撤权不再健康；正常模式不隐式开启异常观察。
- [x] T02-04 回归与证据：相关R1生产/Worker、T01生产观察、R2发现/内部HTTP/正式激活恢复、checkpoint/scheduler/health检查；发展门禁与diff检查；更新合同和进度报告。前端未修改，不重复无关截图验收。

## 当前状态

IMPLEMENTED / DEVELOPMENT-ACCEPTANCE-PASSED。204项后端单元、94项相关集成测试通过；精确发展门禁及diff检查通过。证据见[验收报告](../../evidence/r2/t02-opportunity-worker-report.md)。未部署、未授予真实权限、未启用生产开关。
