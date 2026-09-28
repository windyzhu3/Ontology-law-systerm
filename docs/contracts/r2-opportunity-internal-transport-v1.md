# R2 商机后台内部传输 V1

本合同承接已实现的候选发现、正式首次激活和到期恢复命令，不新增用户页面或业务字段，也不自动授予服务权限。唯一传输源为 contracts/openapi/ontology-law-api.yaml；原 R1 路由、请求及错误集合保持。

## 三条具名接口

| 方法与路径 | 用途 |
| --- | --- |
| GET /internal/v1/opportunity-tasks/candidates | 按 INITIAL 或 DUE 发现当前有权候选，limit 为 1–100，默认 50，cursor 最多 2048 字符 |
| POST /internal/v1/opportunity-tasks/commands/activate-initial | 激活准确商机的初始事项 |
| POST /internal/v1/opportunity-tasks/commands/reopen-due | 恢复准确到期跟进事项 |

所有接口只接受容器验证的 mTLS 证书绑定 SERVICE 身份。普通 Bearer、转发证书头和调用方提供的租户 / 任职选择不能替代认证；租户选择头、任职头、任务条件头和多值 / 未注册查询参数拒绝。发现为只读，命令需要单个有效 Idempotency-Key。

候选为平铺的闭合 INITIAL / DUE 类型，包含 kind、稳定 idempotencyKey 和准确商机修订；DUE 另含准确任务、等待、进展和到期时间。终止页省略 nextCursor，不返回 null。候选不含客户正文。

激活正文只含 opportunityId、expectedOpportunityRevision；恢复正文复用正式恢复合同的九个准确字段。重复字段、额外字段、显式 null、非整数修订和不满足时间精度的输入被拒绝。接口使用已有正式事务、权限、审计和回执，不绕过领域服务写数据库。

## 回执和错误

成功返回 200 TaskOccurrenceCommandReceipt 和 Cache-Control: no-store；无 Location / ETag，不指向人类工作台的回执 GET。后台通过相同键和正文重放。错误使用独立 R2OpportunityTaskProblemV1，保留准确状态和重试语义；陈旧任务 / 商机 / 进展、来源不完整和撤权都作为具名错误返回，不伪装成成功。此内部错误合同不附加人类 receiptRef 或 currentETag。

## Worker 客户端

InternalApiClient 新增封闭 OpportunityKind、两种候选记录、opportunityCandidates 与 maintainOpportunity。复用原 HTTPS、单一证书别名、租户绑定、部署门禁、超时和 64 KiB 响应上限。查询固定每页 50 条，游标做 URL 编码；执行保留候选命令键，只发送对应的具名字段。

客户端拒绝混合候选、重复键 / 字段、非法摘要、修订或时间、尾随 JSON 和错误 UTF-8。成功回执须具有准确字段、相同 commandId、TASK_OCCURRENCE 不透明引用、安全修订和有效完成时间，不能只凭 HTTP 200 判定成功。错误响应保留状态供后续派发策略处理。

## 交付边界

本批实现服务端内部接口及客户端，尚未注册 R2 周期调度或消费循环。生产自动运行仍需 Worker 派发重试、历史分页检查点、负责人异常持久化与处理路径。不得把传输联通记为自动激活已上线或完整销售 MVP 完成。R1 PAUSED / R2 NOT_GRANTED 保持。
