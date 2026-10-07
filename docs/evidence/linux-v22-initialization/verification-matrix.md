# Linux v22 初始化验证矩阵

执行日期：2026-10-07。本文件只记已观察到的结果；L09 全场景通过；L10 的全分支独立审查仍待完成。

| 验证范围 | 当前结果 | 证据与边界 |
| --- | --- | --- |
| Linux 工具单元回归 | PASS，133 项，零跳过 | `task10-owner-acl-native-green.log`；先前 Docker 中断输出保留 |
| 财务跨部门入口 | PASS，真实 PostgreSQL 1 项，零跳过 | `task10-cross-department-green-4.log`；同人其他任职、撤权和所在部门未获权均拒绝 |
| 既有登录与代办会话 | PASS，28 项，零跳过 | `task10-cross-department-green-3.log` 中两个 HTTP 类；该轮总体因新夹具错误失败，未改称整体通过 |
| 架构约束 | PASS，13 项，零跳过 | `task10-final-architecture.log` |
| 既有 Windows 发布工具 | PASS，90 项 | `task10-local-regression.log` |
| 海华验收工具 | PASS，Python 3 项、Node 9 项 | `task10-haihua-regression.log`、`task10-haihua-node-regression.log` |
| schema 生成及解析 | PASS | `task10-schema-check.log`；`task10-schema-sql-2.log`：43 SQL、119 函数，41 个历史迁移前缀保留 |
| CLI 真实 HUMAN 回调与换取会话 | PASS，两位管理员 | `task10-cli-capture-continue-2.log`；原 PKCE/state/nonce/issuer/subject；另外 15 人仍待改密，业务入口关闭 |
| 空库原生初始化 | PASS，最终制品复核完成 | 7 组织、17 人、20 HUMAN 任职、280 HUMAN 授权、0 业务事实；两主任页面登录及实际 CLI stop/start/verify 通过；不混入 SERVICE 任职 |
| v20 → v22 两次已提交响应丢失与联动恢复 | PASS | `task9-native-restore-control-continue-3.log`；真实双库、角色、ACL、资产、密钥和原 v20 完整运行；恢复启动前全事实相等 |
| 四组合真实页面业务链 | PASS，4 条均完成分类 | `task10-native-business-proof-final.log`；原成功回执 35/26/31/27，实际经办、接收及分类关联一致 |
| 最终原生制品发布与登录 | PASS | 原生提交 `0ba4d8f091cb`；两条实际 CLI 发布及健康；9 业务身份、两主任页面登录；恢复 v20 两主任 PKCE |
| 同一浏览器草稿及账号切换 | PASS | `task10-native-draft-switch.log`；原草稿清除、新 HUMAN 来源正确、零线索 POST |
| 三实例全场景验收 | PASS | `linux-v22-final-a7ef88313cc7`，原 all runner，3 个独立实例、4 条正常链，零缺覆盖 |
| 原海华验收环境恢复 | PASS | 保全校验后迁 D；原文件摘要与数据卷保留；`task10-original-final-login-3.log`：11 账号真实登录；Java 原进程保留 |

完整原始输出、令牌、合同正文和数据库备份保存在本计划私密证据目录，不进入 Git。后端先前 245 项集成运行有 3 项失败，针对三项修复后的独立运行 3/3 通过；原失败运行保留。Windows 的 Python 对应回归有 4 项平台跳过，不能替代上述零跳过 Linux 运行。
