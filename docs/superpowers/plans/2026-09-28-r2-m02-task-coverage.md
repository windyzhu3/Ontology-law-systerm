# M02 现有任务类型接入清单

从 TaskFactory.Type 读取 36 种现有任务；当前解析器已逐项显式映射。下表分别记录接线与真实数据库来源验证，不代表全部状态、权限和浏览器验收通过。新增类型必须显式评估，不允许默认纳入。

| 任务类型 | 原命令 | 办理权限 | 原完成事实 | M02接入验收 |
| --- | --- | --- | --- | --- |
| CLASSIFY_MATTER | CLASSIFY_MATTER | MATTER_CLASSIFY | transfer.classification | 已接线；真实完成来源可读（链路测试） |
| ACCEPT_TRANSFER | RECORD_TRANSFER_INTAKE | TRANSFER_ACCEPT | transfer.intake | 已接线；真实完成来源可读（链路测试） |
| SUPPLEMENT_TRANSFER | RESUBMIT_TRANSFER | TRANSFER_SUBMIT | transfer.submission | 已接线；真实完成来源可读（链路测试） |
| PREPARE_TRANSFER | SUBMIT_TRANSFER | TRANSFER_SUBMIT | transfer.submission | 已接线；真实完成来源可读（链路测试） |
| REVIEW_TRANSFER | RECORD_TRANSFER_CONFLICT_REVIEW | TRANSFER_REVIEW | transfer.review | 已接线；真实完成来源可读（链路测试） |
| CHECK_CONTRACT_RECEIPT | RECORD_CONTRACT_RECEIPT_REVIEW | PAYMENT_CONFIRM | contract.payment_review | 已接线；原退回/补充/确认原因及拒绝、审计失败已验证 |
| SUPPLEMENT_CONTRACT_RECEIPT | SUPPLEMENT_CONTRACT_RECEIPT | PAYMENT_SUBMIT | contract.payment_review | 已接线；原退回/补充/确认原因及拒绝、审计失败已验证 |
| CHECK_CONTRACT_EXECUTION | VERIFY_CONTRACT_EXECUTION_CONDITIONS | CONTRACT_EXECUTION_VERIFY | contract.execution_verification | 已接线；真实完成来源可读（链路测试） |
| ARRANGE_CONTRACT_SIGNATURE | CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT | CONTRACT_PREPARE | contract.signature_arrangement | 已接线；真实完成来源可读（链路测试） |
| COLLECT_CONTRACT_SIGNATURE | SUBMIT_CONTRACT_SIGNATURE | CONTRACT_PREPARE | contract.signature_submission | 已接线；真实完成来源可读（链路测试） |
| VERIFY_CONTRACT_SIGNATURE | RECORD_CONTRACT_SIGNATURE_VERIFICATION | CONTRACT_SIGNATURE_VERIFY | contract.signature_verification | 已接线；真实完成来源可读（链路测试） |
| ARCHIVE_CONTRACT_SIGNATURE | ARCHIVE_CONTRACT_SIGNATURE | CONTRACT_SIGNATURE_VERIFY | contract.signature_archive | 已接线；真实完成来源可读（链路测试） |
| RESOLVE_LEAD_DUPLICATE | RESOLVE_DUPLICATE_LEAD | LEAD_INGRESS_RESOLVE | responsibility.decision_record | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| COMPLETE_LEAD_INGRESS | COMPLETE_LEAD_INGRESS | LEAD_INGRESS_COMPLETE | lead.lead | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| ASSIGN_LEAD | ASSIGN_LEAD | LEAD_ASSIGN | lead.lead_assignment | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| RESOLVE_SOURCE_REQUEST | RECORD_SOURCE_REQUEST_CONTINUATION | LEAD_ROUTING_DECIDE | responsibility.decision_record | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| RESOLVE_LEAD_ROUTING_GAP | RECORD_ROUTING_DISPOSITION | LEAD_ROUTING_DECIDE | responsibility.decision_record | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| ACK_SOURCE_INTAKE_STOP_REQUEST | ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST | SOURCE_INTAKE_REQUEST_ACK | responsibility.decision_record | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| CONTACT_LEAD | RECORD_CONTACT_RESULT | SALES_CONTACT_OWNER | lead.lead_contact_result | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| REVIEW_LEAD_VALIDITY | REVIEW_LEAD_VALIDITY | LEAD_VALIDITY_REVIEW | responsibility.decision_record | 已接线；真实命令形成的完成来源可读，实际操作者因旧记录语义保留缺失 |
| PROGRESS_OPPORTUNITY | RECORD_OPPORTUNITY_PROGRESS | SALES_OPPORTUNITY_OWNER | opportunity.opportunity_progress | 已接线；真实进展原文、时间、独立等待后继及拒绝先于解密已验证 |
| PREPARE_QUOTE | FORM_QUOTE | QUOTE_PREPARE | opportunity.quote_revision | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| SUBMIT_QUOTE_APPROVAL | REQUEST_QUOTE_APPROVAL | QUOTE_PREPARE | opportunity.quote_approval_request | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| APPROVE_QUOTE | RECORD_QUOTE_DECISION | QUOTE_APPROVE | opportunity.quote_approval_decision | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| DELIVER_QUOTE | RECORD_QUOTE_DELIVERY | QUOTE_DELIVER | opportunity.quote_issue | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| RECORD_QUOTE_REPLY | RECORD_QUOTE_RESPONSE | QUOTE_RESPONSE | opportunity.quote_response | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| RESOLVE_QUOTE_AUTHORITY | REQUEST_QUOTE_APPROVAL | QUOTE_PREPARE | opportunity.quote_approval_request | 已接线；真实报价全链当前/历史来源可读，原权限与审计负向覆盖另见记录 |
| REQUEST_CONTRACT_PREPARATION | REQUEST_CONTRACT_PREPARATION | CONTRACT_PREPARE | contract.preparation_request | 已接线；退回重提后的真实完成来源可读（Owner 夹具） |
| DECIDE_CONTRACT_PREPARATION | RECORD_CONTRACT_PREPARATION_DECISION | CONTRACT_PREPARATION_DECIDE | contract.preparation_decision | 已接线；原授权原因及拒绝、审计失败已验证 |
| PREPARE_CONTRACT | FORM_CONTRACT | CONTRACT_PREPARE | contract.contract_revision | 已接线；真实准备至形成版本完成来源可读（Owner 夹具） |
| SUBMIT_CONTRACT_REVIEW | REQUEST_CONTRACT_REVIEW | CONTRACT_PREPARE | contract.revision_review_request | 已接线；真实完成来源可读（链路测试） |
| REVIEW_CONTRACT | RECORD_CONTRACT_REVIEW | CONTRACT_REVIEW | contract.revision_review_decision | 已接线；真实完成来源可读（链路测试） |
| SUBMIT_CONTRACT_APPROVAL | REQUEST_CONTRACT_APPROVAL | CONTRACT_PREPARE | contract.revision_approval_request | 已接线；真实完成来源可读（链路测试） |
| APPROVE_CONTRACT | RECORD_CONTRACT_DECISION | CONTRACT_APPROVE | contract.revision_approval_decision | 已接线；真实完成来源可读（链路测试） |
| SUPPLEMENT_CONTRACT_REVIEW | REQUEST_CONTRACT_REVIEW | CONTRACT_PREPARE | contract.revision_review_request | 已接线；原补充审查说明、确认人和时间已验证（Owner 夹具） |
| REVIEW_CONTRACT_TERMINATION | RECORD_CONTRACT_TERMINATION_REVIEW | CONTRACT_TERMINATION_REVIEW | contract.negotiation_disposition | 已接线；真实命令及重放后，原独立主管和处理时间已验证 |

本轮证据（2026-09-28）：`TeamWorkflowCoverageIT` 实际执行退回补正、重新审查、接收及分类，再逐项读取数据库产生的责任历史，共覆盖 15 种 DONE 类型。`TeamPaymentHistoryIT` 验证各次原始财务说明和保护边界；相关 17 项测试通过。其他状态及未列明场景仍待验收，不能用枚举映射或测试总数代替。

`TeamQuoteCoverageIT` 实际执行报价准备、缺审批配置修复、退回后新版本再审、批准、送达和客户接受；6 类报价责任均有真实完成来源及当前/历史读取证据。该证据不替代全部状态和浏览器验收。

补充证据：`TeamEarlyHistoryIT` 实际执行补全、分配、代办联系、重复核对、路由请求→来源确认→续接，以及联系等待→恢复→有效性复核。原确认草稿先独立授权后读取，并核对准确版本与摘要。旧草稿和早期决策保存的是被代表任职，不能据此证明实际点击确认的人；8 类早期历史明确显示缺失，原时间、原因和版本保留。`TeamDecisionHistoryIT` 已修正旧字符串匹配的误判。该组 4 项通过。

`TeamContractRemainderCoverageIT` 补齐请求重提、形成合同和补充审查的真实 Owner 完成事实；其业务写入资格仍使用已有显式夹具，不冒充完整 HTTP/角色验收。`TeamProgressCoverageIT` 验证真实不可变进展与独立等待任务、明确拒绝先于解密；`TeamTerminationCoverageIT` 用真实授权/命令/审计组合验证终止复核及重放，保留原独立主管。该组 3 项通过。至此 36 种类型均有完成来源读取证据；全部状态、异常恢复、6 类任职及浏览器组合仍未完成。
