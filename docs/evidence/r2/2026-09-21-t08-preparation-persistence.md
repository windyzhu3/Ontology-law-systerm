# T08 持久化增量：直接准备申请与决定

状态：V970_SOURCE_FACTS_SELF_VERIFIED。T08 整体仍在实施；不是合同双入口、审批或完整业务闭环验收。

## 实际交付

追加 V970 / `52-plus-2-r2-v11`，新增 `contract.preparation_request` 与 `contract.preparation_decision`。申请绑定同租户商机当前版本、责任依据、负责人、当前客户确认、商业摘要及密文；决定绑定唯一准确申请。首申请和后继均有唯一性约束，两表不可修改/删除。

批准起点由数据库时间产生，退回不产生授权区间。商机关闭/版本变化、责任移交、客户确认被替代或申请已有后继时，旧申请不能继续形成决定。真实 Actor 权限、审批策略与正文真实性仍由后续命令运行时验证，数据库结构不能替代授权。

API schema expectations 及现有 worker checkpoint/exception 配置认可追加版本，仍强制准确 release/manifest 摘要。没有启用新的 worker 功能或命令/API事实槽。现有合同锚点和合同版本守卫未放宽；没有虚构 QuoteResponse、冲突结论、合同或任务。

## 验证

- 字段合同：87 项通过；生成结果确定性校验通过。
- PostgreSQL/PLpgSQL 解析：32 个迁移、55 个函数通过。
- 单元：23 项通过，含合同输入、摘要和 schema/worker 门禁。
- PostgreSQL 集成：6 项通过，覆盖不可变/唯一决定、不造假报价合同、申请不分叉、旧申请拒绝、客户版本变化、错误/跨租户来源拒绝、事务回滚、并发首申请及 REPEATABLE READ 旧快照。
- 独立审查未发现支持的 READ COMMITTED 运行路径中的阻断问题；针对更高隔离级别可能出现的重复首申请，额外补了物理唯一索引，并以真实旧快照测试验证拒绝 SQLSTATE 23505。
- jOOQ 从迁移后的真实数据库重新生成并校验通过。首次检查发现既有 TaskOccurrence/WaitReceipt 表约束元数据滞后于报价迁移，已由生成器同步；不是手工修改业务或数据库策略。文件集合和 POJO 保持一致，更新两份表元数据及生成清单。

RED 证据包括缺少 V970 演进、缺少首申请物理唯一索引、运行门禁不识别 v11；修复后相关检查通过。首次 IT 的测试方法异常类型编译错误已修正；首次混用 jOOQ 专用 profile 导致普通测试未被编译，改用独立命令执行，未跳过最终验证。

本地日志：`.local/t08-schema-tests-final.log`、`t08-persistence-final.log`、`t08-jooq-final.log`。测试使用独立 Testcontainers PostgreSQL，不改用户验收案例。

## 旧迁移与运行环境

- V950 SHA256：`d466d2e4f1a2c0119a4989708cc9eb61d8e21a5f31acfb6c8cddac8fffbd5b99`。
- V960 SHA256：`1f1385ef7564bf03ad7ccea132118b33a013770a80041e3447ee23fb64cb7681`。
- 本轮没有升级本地验收库、替换运行包或修改冻结页面。V970 未在该环境发布，后续仍需按备份及兼容部署流程落地。

## 剩余事项

T08-03 的合同锚点双入口、准备版本与冲突决定分离、Contract Owner 持久化服务尚未完成。后续依次实现命令/准确授权、可靠后继与回执、签约前审查审批、已确认 L 页面接入及全链验收。不得把本批已建申请/决定表视为销售已能在页面完成合同业务。

R1 PAUSED / R2 NOT_GRANTED 保持。
