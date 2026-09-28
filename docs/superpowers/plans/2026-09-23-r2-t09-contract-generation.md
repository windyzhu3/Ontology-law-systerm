# T09 合同生成及审批链补齐实施计划

> 使用 executing-plans 内联实施，先失败测试再实现；保留既有未提交工作，不提交或推送。

**Goal:** 已审核模板生成完整 PDF，经人工核对及原材料校验后形成准确版本，接原审查审批链。
**Architecture:** Contract Owner 保持唯一真源；PDFBox 填充模板预定义字段，生成凭据绑定准确输入；材料复用 T06，版本命令复用 T08。
**Tech Stack:** 既有 Java/PDFBox/PostgreSQL/React，不新增依赖或服务。
**Spec:** ../specs/2026-09-23-r2-t09-contract-completeness-design.md，M 已确认。

## Global Constraints

复用当前 MyTasksControl、工作台壳及表单样式。无模板编辑器、电子签、到账或转案开发。R1 PAUSED / R2 NOT_GRANTED。生成不是版本提交。授权及时间重验、同步审计、回执恢复不变。

## Tasks

- [x] T09-02 PDF 模板渲染：ContractDocumentRenderer 及纯测试；保留模板全文、准确业务值，缺字段/未知字段/不支持字体/文字溢出拒绝，不静默截断；相同输入生成相同字节。
- [x] T09-03 受控生成接口及准确凭据：原 ContractReadRuntime 授权审计边界取快照，事务外渲染，响应前重验；凭据绑定租户、商机、参与方、来源、模板、正文及输入，形成版本时验证。
- [x] T09-04 接原材料上传扫描与接收；沿用幂等命令和回执，失败重试不多建合同版本，未知结果保留恢复入口。
- [x] T09-05 ContractCard/RuntimeCard/transport 接入，生成候选、预览核对、确认及返回修改；不新增样式系统，不丢原上传正文路径。
- [x] T09-06 两入口与审查补正/阻断/审批退回全链回归，准确版本、撤权、审计回滚、责任接续与 SLA 不变。
- [x] T09-07 本地备份部署及合成案例真实 HTTP/页面验收，明确范围内完成与正式模板限制。

## Review Focus

生成期间来源变化；候选被套用到另一商机；长中文文本被截断；上传结果未知时重复接收；旧批准用于修订正文。各项必须有定向失败与通过证据。

## Execution ledger

2026-09-23：M 确认，当前工作树 codex/r2-sales-mvp 沿用。新增填写式 PDF 模板的渲染契约，不改已发布模板；不兼容模板明确不可自动生成，原准确正文路径保留。

2026-09-23: Implemented and locally verified. Evidence: ../../evidence/r2/t09-generation/README.md. Frontend 814 tests; backend 90 unit and 17 targeted integration tests; baseline development checks pass. R1 PAUSED / R2 NOT_GRANTED remain. Synthetic-only PDF template.
