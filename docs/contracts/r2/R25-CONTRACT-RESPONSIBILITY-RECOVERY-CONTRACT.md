# R25 合同准备责任恢复合同

范围来自已确认 R25-02：补齐合同准备与补正阶段的失权发现和原责任恢复，不新增业务表单、权限或任务类型。

已在真实 PostgreSQL 复现：合同处于 PREPARE，撤销 CONTRACT_PREPARE、保留 SALES_OPPORTUNITY_OWNER 后，原后台候选为空。旧扫描及恢复约束只覆盖独立授权、审查、审批失权。

新增 V1060（schema v20）仅扩展 contract.preparation_workflow 已有 recovery_resume_stage 的准确取值和来源校验。V001–V1050 字节保持原样；不新增表、不改写历史工作流、不调整权限。

| 原阶段 | 原/恢复任务 |
| --- | --- |
| DIRECT_RETURNED | REQUEST_CONTRACT_PREPARATION |
| PREPARE、RETURNED、REVIEW_BLOCKED | PREPARE_CONTRACT |
| SUBMIT_REVIEW | SUBMIT_CONTRACT_REVIEW |
| SUBMIT_APPROVAL | SUBMIT_CONTRACT_APPROVAL |
| REVIEW_SUPPLEMENT | SUPPLEMENT_CONTRACT_REVIEW |

发现准确当前任务失去 CONTRACT_PREPARE 后，仍通过既有 CONTRACT_TASK_RECOVER 服务权限和 AUTHORITY_RETURN 命令处理。旧任务按既有原因 CONTRACT_AUTHORITY_MISSING 取消；新工作流为无可办 taskId 的 OWNER_EXCEPTION，并记录原阶段、原任务和直接前序。原人员仍未获得资格时不重复追加。恢复资格后只恢复原阶段及对应任务，沿用原 SLA，重新读取当前授权与来源；不替人批准、审查、签署或付款。

独立授权/审查/审批的原退回语义保留：DIRECT_REVIEW → DIRECT_RETURNED；AWAIT_REVIEW / AWAIT_APPROVAL → RETURNED。上述销售准备阶段则保留各自原阶段，不统一改成“待修订”。

候选发现不写业务；幂等重放不新增工作流/任务；准确拒绝、审计失败、旧前序、跨事项和不匹配恢复类型必须拒绝。M02 尚需把这些无 taskId 的真实异常接入已确认 S 页面。此合同及迁移源码不表示本地运行库已升级或 R25-02 已验收。
