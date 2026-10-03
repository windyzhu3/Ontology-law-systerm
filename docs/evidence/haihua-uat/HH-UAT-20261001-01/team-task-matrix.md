# 团队待办 36 类型及状态覆盖

来源：`R2TeamTaskResolver.family` 的封闭类型分派。主链办理证据证明业务命令，不等同于逐类团队列表状态验收。表中未观察的 OPEN / WAITING / DONE / CANCELLED 一律不计通过；某类型是否允许相应状态还须按 Owner 规则确定，不能批量宣称不适用。

| 类型 | 本轮实际业务证据 | 团队页状态证据 |
| --- | --- | --- |
| RESOLVE_LEAD_DUPLICATE | B02 基线阻挡 | NOT_RUN |
| RESOLVE_SOURCE_REQUEST | 未触发 | NOT_RUN |
| RESOLVE_LEAD_ROUTING_GAP | 未触发 | NOT_RUN |
| ACK_SOURCE_INTAKE_STOP_REQUEST | 未触发 | NOT_RUN |
| REVIEW_LEAD_VALIDITY | B07 两次独立裁定 | DONE PASS；其他未覆盖 |
| COMPLETE_LEAD_INGRESS | B01 补齐 | 实际观察 已处理（sales_manager01）；其他状态未覆盖 |
| ASSIGN_LEAD | G01—G05、B01/B03/B07/B09 | 实际观察 已处理；已逾期；待办理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| CONTACT_LEAD | 五条主链；B06未接通；B07两次疑似无效 | 实际观察 已处理；已逾期；未到约定时间（sales_manager01/sales_manager02）；其他状态未覆盖 |
| PROGRESS_OPPORTUNITY | B09等待及重开 | 实际观察 已取消；已逾期；待办理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| PREPARE_QUOTE | G01/G02/G05 | 实际观察 已取消；已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUBMIT_QUOTE_APPROVAL | G01/G02/G05 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| APPROVE_QUOTE | G01/G02/G05，G05退回重审 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| DELIVER_QUOTE | G01/G02/G05 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| RECORD_QUOTE_REPLY | G01/G02/G05接受 | 实际观察 已到核对时间；已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| RESOLVE_QUOTE_AUTHORITY | 未触发 | NOT_RUN |
| REQUEST_CONTRACT_PREPARATION | G03/G04直接申请 | NOT_RUN |
| DECIDE_CONTRACT_PREPARATION | G03/G04主管批准 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| PREPARE_CONTRACT | 五条主链；性能实例100份停在本阶段 | 实际观察 已处理；待办理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUBMIT_CONTRACT_REVIEW | 五条主链 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| REVIEW_CONTRACT | 五条主链；初始负向夹具NEED_INFO | 实际观察 已处理；已逾期（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUBMIT_CONTRACT_APPROVAL | 五条主链 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| APPROVE_CONTRACT | 五条主链；G05退回与新版批准 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUPPLEMENT_CONTRACT_REVIEW | 初始未知对方负向夹具 | 实际观察 已逾期；待办理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| ARRANGE_CONTRACT_SIGNATURE | 五条主链 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| COLLECT_CONTRACT_SIGNATURE | 五条主链；G05缺件补正 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| VERIFY_CONTRACT_SIGNATURE | 五条主链双方核验 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| ARCHIVE_CONTRACT_SIGNATURE | 五条主链 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| CHECK_CONTRACT_EXECUTION | 五条主链；先款及非先款对照 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| CHECK_CONTRACT_RECEIPT | G02两笔，G04退回再确认 | 实际观察 已处理；已逾期；待办理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUPPLEMENT_CONTRACT_RECEIPT | G04实际补证 | 实际观察 已处理（sales_manager02）；其他状态未覆盖 |
| REVIEW_CONTRACT_TERMINATION | B20 G01独立主管STOP | 实际观察 已处理（sales_manager01）；其他状态未覆盖 |
| PREPARE_TRANSFER | 五条主链 | 实际观察 已处理（sales_manager01/sales_manager02）；其他状态未覆盖 |
| SUPPLEMENT_TRANSFER | G05主体证据补正与新快照 | 实际观察 已处理（sales_manager02）；其他状态未覆盖 |
| REVIEW_TRANSFER | 五条主链；G05退回重审 | 实际观察 已处理（case_admin01）；其他状态未覆盖 |
| ACCEPT_TRANSFER | 五条主链五个不同案件 | 实际观察 已处理（case_admin01）；其他状态未覆盖 |
| CLASSIFY_MATTER | 五条主链；G05同案件更正 | 实际观察 已处理（case_admin01）；其他状态未覆盖 |

B07 团队历史：manager01 沿全部过滤游标看到两条保留原裁定原因的历史，无办理按钮；manager02 同查询沿全部游标零记录。历史原确认人缺失时页面明确说明未取得，不以任务负责人代替。

技术证据：本轮团队/工作流综合集成组 316 项，315 PASS、1 基线迁移 FAIL；它支持 Owner 来源和边界回归，不能填平上述真实团队 UI 的未覆盖状态。

2026-10-01 HH007 修复后，三角色、四视图、23个游标页的实际渲染与响应逐行相符。以上由服务封闭类型标签映射登记，未观察类型和状态继续保留未覆盖；不从主链命令推定团队状态。

HH009及B19补测后复查：三角色四视图共24页，实际观察29/36类型；本条仅登记本次原响应与渲染，旧23页快照保留。

HH009及B19补测后复查：三角色四视图共25页，实际观察29/36类型；本条仅登记本次原响应与渲染，旧23页快照保留。

HH009及B19补测后复查：三角色四视图共25页，实际观察29/36类型；本条仅登记本次原响应与渲染，旧23页快照保留。

最终HH010、B24接收后复查：三角色四视图25页观察29/36类型。此最终原环境快照未观察七类；其中REVIEW_LEAD_VALIDITY另有独立PERF/B07的DONE实际历史样本，因此上表仅六类团队状态完全NOT_RUN。其余适用状态仍未覆盖，不将两个数据集混成同一快照。
