# R2 T04 商机明确结束合同 V1

本合同落实已确认的 H 高保真和 T04 计划。验收状态以 `docs/evidence/r2/t04-opportunity-close-report.md` 为准。范围仅限明确结束销售洽谈，保留来源、历史跟进和责任事实；不包括成交、合同终止、建案、删除或重新开启。

## 操作与界面

台账详情保留正常办理主入口，在“其他业务处理”中进入“结束本次商机”。原因仅为客户明确拒绝、需求取消、其他；说明必填，1–1000 字符。用户先核对准确责任及当前普通待办的结束影响，再正式确认。使用冻结 E 共用样式及 H 页内状态，不新增导航模块。

存在未保存办理内容时，先回原卡片处理。结果未知时保留本次核对内容于当前身份的内存，只查询原命令回执，不自动重复提交；恢复存储不保存说明正文。撤权或身份变化清除业务详情。

## 准确读取与正式命令

`GET /api/v1/opportunities/{opportunityId}/closure` 返回准确商机引用及 READY、READ_ONLY、BLOCKED 或 CLOSED。仅 READY 返回 expectedResponsibility、expectedTask、expectedWait；无当前任务或等待时显式为 null。CLOSED 的原因、说明和时间须具有准确终结事实的读取权限，不能用缺省说明误报“没有说明”。后续报价、合同或转案事实的存在仅在有权读取这些准确事实时披露。

`POST /api/v1/opportunities/{opportunityId}/commands/close` 使用 Idempotency-Key，正文提供 expectedOpportunityRevision、expectedResponsibility、expectedTask、expectedWait、reasonCode、summary。调用者必须是直接 HUMAN，具备当前有效 OPPORTUNITY_CLOSE 权限。台账读取或普通办理权限不会自动赋予结束权。

正式命令重新核验准确商机、有效责任、来源链、当前任务及等待、草稿、关联客户和负责人身份；对象拒权优先。锁定业务事实后校验版本，提交前再次校验授权与落库结果。存在后续报价、合同或转案事实时拒绝，使用 OPPORTUNITY_HAS_DOWNSTREAM_FACTS；已关闭使用 OPPORTUNITY_CLOSED。

## 领域事实、事务与回执

V910 为具名精确后继迁移，版本为 52-plus-2-r2-v5。新增不可变 opportunity.closure，revision 固定为 0，记录准确关闭前商机、责任、任务/等待及操作者。说明使用独立 R2_CLOSURE_AES_GCM_V1 保护，不能复用进展密文的关联数据。

终结事实、商机版本递增及关闭时间、当前 OPEN/WAITING 普通任务取消、事件和原命令回执同事务提交。取消依据为准确 opportunity.closure，原因 R2_OPPORTUNITY_CLOSE_V1；任务为 CANCELLED，不伪造 DONE 或进展事实。未建卡商机也可结束。来源 Owner 和 Assignment 保持原历史含义。

成功回执为 SUCCEEDED，公开事实 OPPORTUNITY_CLOSURE，以既有 factRef 表达不可变 revision 0。相同命令重试读取原结果，不产生第二条终结事实。回执恢复仍须当前授权，并核验原操作者及准确终结事实。

## 连贯性与边界

结束与跟进、初始激活、到期恢复、责任交接串行化；准确依据已变化的请求失败，不静默覆盖新结果。结束后 INITIAL/DUE 不再创建或恢复普通跟进。旧负责人异常通过既有观察机制收敛，不以旧异常继续转交。

读取使用具名 R2_OPPORTUNITY_CLOSURE_DISCLOSURE_V1 审计，引用授权事实，不复制说明正文；审计提交失败时不返回读取结果。原 R1/T01/T03 合同以精确后继投影保留，不放宽通用注册范围。

R1 验收保持 PAUSED，R2 发布保持 NOT_GRANTED；Worker 开关默认关闭，不包含部署、自动授权、提交或推送。
