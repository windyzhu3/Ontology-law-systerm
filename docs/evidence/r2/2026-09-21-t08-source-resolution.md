# T08 双入口准备来源数据库复验

日期：2026-09-21。范围：T08-03b 的准确来源读取增量；合同锚点双入口、准备版本写入及后置冲突绑定尚未完成。保持 L/E 冻结页面，本批无 UI、HTTP 端点、待办命令或验收环境部署变更。

## 实现

- `ContractPreparationSources` 以互斥的 AcceptedResponse / DirectDecision 选择数据库事实，不接受客户端构造的批准凭据。
- 同一 READ COMMITTED 命令事务锁商机、复验当前客户确认，按稳定顺序共享锁定参与主体并检查修订和 ACTIVE 状态。批准申请必须仍是当前申请，且商业摘要和责任依据一致。
- 报价入口接真实 T07 接受链及 T06 材料；验证准确交付、接受、记录时序，拒绝非接受、被替代、商业内容不一致。过去合法接受不因读取时自然到期被删除。
- 交付和接受证明的 EvidenceBinding 均按稳定顺序锁定并检查未撤回。正文经解密后，与报价头和受保护依据两份摘要分别比对，再提取商业条款计算合同摘要。
- 最终有效时间来自锁等待及读取完成后的数据库时钟；不使用请求开始时间或缓存时钟。

## 回归与独立复核

初始 RED：缺少来源读取服务，见 `.local/t08-source-red.log`。首批 28 项单元/架构检查及 6 项 PostgreSQL 用例通过。

独立只读复核发现两项 Important：客户确认引用的主体可能已经变版，以及原接受证明可能已撤回。均增加真实数据库失败用例后修复：

- `changed_party_requires_fresh_customer_confirmation`：RED 时未抛出 Unavailable，见 `.local/t08-party-red.log`；增加参与主体事务内共享锁及版本/状态检查。
- `revoked_acceptance_proof_cannot_start_contract_preparation`：RED 时仍返回来源，见 `.local/t08-evidence-red.log`；增加两类证明的共享锁和撤回检查。最终测试使用不同的交付与接受材料，并单独覆盖交付证明撤回。

中间回归 `.local/t08-source-reviewed.log` 的两个错误来自独立证据夹具在报价形成后沿用旧商机修订上传，既有守卫正确拒绝。已调整为报价形成前接收两份独立材料，未放松业务守卫。

最终验证结果以 `.local/t08-source-verified.log` 为准；测试包括准确授权、后继申请/退回、跨租户、客户及主体变化、事务隔离、锁等待过期、T07 来源/证据/商业摘要、非接受、报价自然到期、正文完整性及两类证明撤回。

最终结果：**BUILD SUCCESS；28 项单元/架构检查、12 项 PostgreSQL 集成用例，失败/错误/跳过均为 0**。结束时间 2026-09-21 15:32:11 +08:00。单元选择 `ContractPreparationSourceTest,ContractVersionInputTest,ContractCanonicalJsonTest,ArchitectureTest`；数据库选择本批 `R2ContractDirectSourceIT` 的 6 个新增方法及 `R2ContractQuoteSourceIT` 的 6 个新增方法，显式选择以免将继承夹具的用例混入计数。独立复核的两项 Important 均已由失败用例转绿验证，无待处理 Minor。

## 接续边界

该服务不是身份授权或敏感读 API。后续命令必须完成全部准确依赖授权、同步审计，在同一个受控事务内调用并写入版本，在最终写入边界重验时间与来源。源码未注册运行入口，不能作为缓存的准入凭据。

本批沿用 V970 测试数据库；未追加迁移、未改生成字段合同或正式数据。现有合同锚点仍要求报价接受，版本仍要求既有 PRE_CONTRACT 字段。因此不将此来源服务等同于合同准备业务已可用，T08-03b、T08-04～06 继续保持未完成。R1 PAUSED / R2 NOT_GRANTED。
