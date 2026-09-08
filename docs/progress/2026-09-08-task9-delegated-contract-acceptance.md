# Task9.2a 合法代办合同前置验收

日期：2026-09-08。状态：**静态合同验收及独立复审通过**。这不是代办运行时、真实登录或 Task9 整体验收。

批准依据：[最小补充设计](../superpowers/specs/2026-09-08-task9-delegated-context-amendment-design.md)。激活依据：[ADR-0015](../adr/ADR-0015-task9-delegated-context.md)。完整评审范围 `bf2b836..73e3250`，实现提交 `33e866f`，评审修复 `73e3250`；根控制代理的下游计划/验收/账本提交也纳入评审。最终代码为 `73e3250f81258974d49fb172eac96a91d44ecab1`。

## 已完成的内容

- 激活 MVP-2026-09-08.3、Identity V1.1、HTTP V1.5、Workbench V1.3、OpenAPI1.4.0；Command V1.3 和物理52-plus-2-v1.2保持。
- 明确双 header 显式代办选择、context 两个新增必填字段、候选上限50、SELF披露引用上限101、当前一跳资格与逐操作授权分离、完整 Actor 回执及重登选择期间未决线索规则。
- 全部32公共接口声明格式错误的400 VALIDATION_FAILED；20管理接口仍拒绝代办。生成类型和静态检查同步，无运行时认证实现变更。
- 原37/32/5接口绑定、九业务请求及47传递依赖、193个非context Schema（含Problem/retryPolicy）、权限注册、事件、Task完成矩阵、数据库及jOOQ字节保持。

## 实际验证

| 检查 | 最终结果 |
|---|---|
| 锁定 Linux 全量基线 | 300 tests，727.772秒，exit0；最终日志 Ran300/OK |
| 拓扑单元测试 | 33 tests，exit0；修复未改变拓扑源 |
| 修复定向 Python | 23 tests，exit0；含32接口的64项独立错误绑定删除变异 |
| Java OpenApiContractTest | 19 tests，0失败/错误/跳过，exit0 |
| Node24.20.0/npm11.9.0 前端 | 75 tests/7files；生成、生成漂移检查、typecheck、build均exit0 |
| 控制代理最终实际工作树 CLI | session12191，baseline/topology均通过，exit0；7项既有非致命R2阻塞保留 |
| 独立评审 | 初审1项Important，修复后同席复审Approved，无剩余重要问题 |

初始RED为10项测试中的7项断言失败；评审修复RED准确命中回执查询缺少400绑定。修复覆盖所有公共接口，测试要求实际结构检查返回指定发现，不能仅靠整个OpenAPI摘要变化让变异测试通过。

第一次全量测试因评审修复而中止，session99484退出1，仅228项完成记录，不计作通过；最终300项是修复版本的重新完整执行。初次前端运行的嵌套npm版本及一次shim路径错误均保留在报告，最终证据已用准确锁定版本重跑。Java生成器既有OpenAPI3.1/mTLS/注解/JAXB与暂停草稿编译提示有记录，未宣称无警告或生成器提供运行时鉴权。

## 范围与后续

Task9.1历史验收不被覆盖；T9-D01静态项通过，T9-D02～08仍随Task9.2/9.3/9.4/9.6实施和验证。Task9.2已有9个未验收草稿文件，经控制代理摘要比对保持未改动，没有混入本次合同提交。下一步恢复同一运行时实施子代理，重新检查其最后一次真实登录失败，再完成原Task9.2与新增代办后端验收。

没有推送、部署、真实用户/授权写入，没有增加表、权限、委托管理或R2功能；Task9整体、人工UAT、Task10/容量及R1发布均未完成。

本地详细证据在 `.superpowers/sdd/2026-09-08-task9-real-user-access-plan/`：`task-9.2a-report.md`、`task-9.2a-review-01.md`、`task-9.2a-review-02.md`、`task92a-fix1-baseline-full.log`、`task92a-root-cli-receipt.md` 及报告指向的定向/生成/不变性日志。CLI文件明确为根代理工具输出回执，不冒充原始重定向日志。
