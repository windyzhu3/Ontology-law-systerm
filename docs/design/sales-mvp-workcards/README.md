# 销售MVP工作卡高保真验收集

> 状态：FROZEN
> Bundle版本：visual-bundle-2026-08-27
> Owner：Product Design
> 确认日期：2026-08-27

本目录是既有规格的视觉证据索引，不是新的产品规格。视觉稿只冻结验收画面，不产生领域、拓扑或生命周期规则；冲突时以[当前MVP基线](../../baseline/CURRENT-MVP-BASELINE.md)为准。所有画面共用风格1：成熟SaaS扁平响应式布局、暖白背景、石墨文字、翡翠绿唯一主强调色、浅薄荷辅助色、1px边框与弱阴影。

## 不可妥协的验收基准

- 同一Chat入口，不增加普通用户模块页。
- 同一时刻只有一张完整WorkCard、一个Owner、一个业务目的、一个固定主命令和一个明确结果。
- 选项是主命令参数，不表现为第二主命令。
- Evidence接收不代表业务核验通过；AI不代替Owner决定。
- 退回、重试、补正与后续行动创建新Task，旧Task不重开、不复用。
- 普通用户可见文案不泄漏内部状态码、表名或流程实现。

## 已冻结基准状态（12张）

`frozen/`保存此前逐组确认的正常责任态：首联复核、商机进展、报价准备、合同审批、首款跟进、转案补正上传、冲突输入、合同准备、签字证据、报价回复、财务到账与案管接收。

| 编号 | 画面 | 高保真 |
|---|---|---|
| BASE-01 | 首联复核 | [查看](frozen/01-contact-lead-review.png) |
| BASE-02 | 商机进展 | [查看](frozen/02-opportunity-progress.png) |
| BASE-03 | 报价准备 | [查看](frozen/03-prepare-quote.png) |
| BASE-04 | 合同审批 | [查看](frozen/04-contract-approval.png) |
| BASE-05 | 首款跟进 | [查看](frozen/05-follow-first-payment.png) |
| BASE-06 | 转案补正上传 | [查看](frozen/06-fix-transfer-upload.png) |
| BASE-07 | 冲突输入 | [查看](frozen/07-conflict-input.png) |
| BASE-08 | 合同准备 | [查看](frozen/08-prepare-contract.png) |
| BASE-09 | 签字证据 | [查看](frozen/09-submit-signature-evidence.png) |
| BASE-10 | 报价回复 | [查看](frozen/10-quote-response.png) |
| BASE-11 | 财务到账 | [查看](frozen/11-confirm-payment.png) |
| BASE-12 | 案管接收 | [查看](frozen/12-transfer-accept.png) |

## P0链路补齐（15张，已冻结）

| 编号 | 画面 | 唯一主命令 | 高保真 |
|---|---|---|---|
| P0-01 | 疑似重复线索 | 确认线索归属 | [查看](p0/P0-01-duplicate-lead.png) |
| P0-02 | 联系方式缺失 | 保存并继续分配 | [查看](p0/P0-02-missing-contact.png) |
| P0-03 | 人工指定Owner | 分配给所选销售 | [查看](p0/P0-03-manual-owner.png) |
| P0-04 | 零候选调配 | 记录本次调配处置 | [查看](p0/P0-04-zero-candidate.png) |
| P0-05 | 疑似无效复核 | 记录复核决定 | [查看](p0/P0-05-invalid-lead-review.png) |
| P0-06 | 商机停滞或报价拒绝处置 | 记录处置决定 | [查看](p0/P0-06-opportunity-disposition.png) |
| P0-07 | 提交报价审批 | 提交这份报价审批 | [查看](p0/P0-07-quote-approval-request.png) |
| P0-08 | 报价授权决定 | 记录报价授权决定 | [查看](p0/P0-08-quote-authorization.png) |
| P0-09 | 报价发出 | 发送这份报价给客户 | [查看](p0/P0-09-quote-send.png) |
| P0-10 | 报价发送修正 | 按修正信息重新发送 | [查看](p0/P0-10-quote-send-correction.png) |
| P0-11 | 不可直接重发处置 | 记录发送处置 | [查看](p0/P0-11-quote-unknown-disposition.png) |
| P0-12 | 冲突Finding决定 | 记录冲突决定 | [查看](p0/P0-12-conflict-finding-decision.png) |
| P0-13 | 首次转案 | 提交案管审核 | [查看](p0/P0-13-first-transfer.png) |
| P0-14 | 案管RETURN | 退回销售补正 | [查看](p0/P0-14-transfer-return.png) |
| P0-15 | 补正重提 | 重新提交案管审核 | [查看](p0/P0-15-transfer-resubmit.png) |

P0图位于 `p0/`，文件名与编号一一对应。P0-13至P0-15必须结合规格中的Snapshot/Review实例约束验收，视觉文案不得被解释为复用旧实例。
