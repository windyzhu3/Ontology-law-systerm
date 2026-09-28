# R2 首个商机推进责任承接 V1

本合同定义已确认 R2-1 的首次责任承接基础。商机进展命令、工作卡及到期恢复正式命令已在后续批次接入，首次激活正式命令已在第二十一批实现；生产 Worker 自动接续仍未启用。实现与测试通过不表示 R2-1 业务闭环完成。

| 项目 | 静态注册 |
|---|---|
| 业务目的 | `PROGRESS_OPPORTUNITY` |
| 准确 Subject | `opportunity.opportunity`，按 revision 冻结 |
| 主命令 | `RECORD_OPPORTUNITY_PROGRESS`，已接入商机进展命令合同 |
| 责任槽 / 权限 | `OPPORTUNITY_OWNER` / `SALES_OPPORTUNITY_OWNER` |
| 草稿 Schema | `RecordOpportunityProgressV1`，已接入输入与受保护事实合同 |
| 完成事实 | `opportunity.opportunity_progress` |
| SLA | `R2_BUSINESS_4H_V1`，14400 个业务秒，复用现行业务日历 |

## 输入与写入边界

可信应用层在一个 READ COMMITTED 事务中调用 Opportunity Owner 承接服务，传入同租户准确 Opportunity selector、业务时区和承接时间。服务锁定 Opportunity 行，重读来源和 Owner。来源必须仍对应同一 Lead、Assignment、`CONNECTED_VALID` ContactResult，以及已完成且 completion selector 准确等于 ContactResult 的原 `CONTACT_LEAD` 责任。来源读取通过具名 Owner 端口组合，不跨域访问内部 Repository。

Owner 必须是当前有效 HUMAN 任职，具有该商机上的 `SALES_OPPORTUNITY_OWNER` 权限；组织、任职和对象级拒绝仍适用。失效、无授权或来源不完整时返回具名阻塞原因，不创建孤立卡片，不默认为主管或另一销售。生产启用前必须把阻塞接入主管/运营可见的持久化异常与恢复路径。

Responsibility Owner 的专用初始卡入口按 Tenant + Opportunity ID + `PROGRESS_OPPORTUNITY` 取得事务级锁，并检查前序关系为空的全部初始任务状态（含 DONE/CANCELLED）。首次创建冻结当前准确 Subject、原 Owner 与 SLA；重试返回同一任务身份，不改 Owner、原 SLA 或历史状态。普通 `create` 对此业务目的明确拒绝。后续跟进责任使用 [R2 商机进展与后继责任 V1](r2-opportunity-progress-v1.md) 的专用入口，绑定不可变前序关系，不能绕过该初始卡入口反复创建同一初始责任。

生产调用方负责服务 Actor 的入口鉴权、业务事务、命令回执、审计和最终提交，并在业务根锁之前沿用已有租户业务 fence。服务在读取当前 Owner 前持有身份共享锁，并在责任创建后再次验证权限，避免等待期间任职或时限失效。本 Owner 端口不提交事务、不改变数据库 capability、不发网络请求，也不能由浏览器直接调用。

本版不新增数据库唯一索引。至多一次保证覆盖全部受支持的初始创建入口；迁移和导入不得绕过专用入口直接写此任务类型。若读到已有重复或冻结合同不一致，专用入口报错并要求受控修复，不任选一张或追加第三张卡。

## 历史与生产激活

历史和新商机调用同一个承接服务。历史商机不补造联系事实，不重置已消费的 R1 Outbox，不改写 queue owner。已关闭商机不新建普通跟进卡；已存在的初始责任保持原身份。机会 revision 已变化时不得把旧 selector 当成当前版本继续创建。

当前已交付进展受保护事实、草稿与人工确认、后继事项、公开单卡与前端、受限候选发现及正式到期恢复命令。首次激活正式命令已接入，详见 [正式激活命令 V1](r2-opportunity-activation-command-v1.md)。生产启用仍须接入内部传输与 Worker 派发重试、历史扫描检查点及失效 Owner 的持久化异常处理，再验证端到端自动接续；不得以内部能力代替生产交付。

开发与发布证据继续分开，R1 暂停验收不在本合同中改记通过。


第二十二批接线状态：三条具名内部 mTLS 接口及封闭 Worker 客户端已接入，详见 [R2 内部传输 V1](r2-opportunity-internal-transport-v1.md)。尚未注册周期派发 / 消费循环；历史分页检查点及负责人异常处理仍待交付。早期批次中的接口待实现描述保留为当时状态，不代表当前传输缺失。
