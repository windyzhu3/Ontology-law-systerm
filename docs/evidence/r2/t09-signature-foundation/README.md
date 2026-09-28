# T09 / SIGN-01 基础规则实施进展

日期：2026-09-23。状态：PARTIAL_IMPLEMENTATION，未接通签署业务运行时。

已新增 ManualSignaturePlan 和 ManualSignatureProtocol。输入绑定租户、合同、准确版本、readiness、正文、主体、审查、审批、签署安排及条款依据；签字件和未签正文使用不同摘要。结构规则区分必需签字、必需盖章及两者，保留部分完成；同版材料补证、同版登记补正和合同正文变化分别流转。

这些类是纯输入和状态规则，不能作为权限、文件扫描、签署事实或审计成功的证明。Owner 必须从准确持久化事实构造内部输入，不能把 VerifiedSlot 当作客户端可以提交的完成声明。

先执行失败测试，再实现规则。最终命令：

`./mvnw.cmd -q -f backend/pom.xml -Dtest=ManualSignature*Test,ContractVersionInputTest,ContractWorkflowProtocolTest,ContractCanonicalJsonTest compiler:compile compiler:testCompile surefire:test`

31 项测试通过：16 项新增签署规则测试与 15 项原合同规则回归；无失败、错误或跳过，见 test-results.json。日志位于 output/sign01-*-red.log 及 output/sign01-final-green.log。

用户已确认存量合同允许销售按批准正文登记，由授权核验人对照核验。N1 仅补充登记、核验、登记补正三个字段状态，9 项布局、5 项交互检查通过；4 份样式文件与现有产品逐字节一致。业务选择已确认，新增字段布局仍待确认。

尚未完成：数据库具名演进、签署 Owner、正式接口、责任接续及后台恢复、真实页面接入、实际材料与签署全链验收。本轮未迁移或部署运行系统，没有创建真实签署或合同执行事实。T09、SIGN-01 整体验收及 R2 MVP 不能据此标为完成。

继续依据 docs/superpowers/plans/2026-09-23-r2-manual-signature.md。R1 PAUSED / R2 NOT_GRANTED 保持。

## N1 确认后的规则对齐

2026-09-23：N1 已获用户确认。补上同一签署主体不可重复登记、同一批准参与项不可冒用于不同主体的后端基础规则；先观察两项测试失败，再实现校验。最新定向回归 33 项全部通过（18 项签署规则，15 项既有规则），详见 test-results.json。日志：output/sign-n1-duplicates-red.log、output/sign-n1-foundation-green.log。

这仍是基础规则验证，不是数据库、接口、待办或产品页面接入验收；未部署签署业务。
