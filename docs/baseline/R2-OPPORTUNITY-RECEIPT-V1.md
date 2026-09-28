# R2_OPPORTUNITY_RECEIPT_V1

范围：商机正式进展回执通过现有 GET /api/v1/commands/{commandId}/receipt 恢复，不增加页面、路径、权限、命令提交入口或业务状态。

唯一传输增量是在 PublicFactRef.oneOf 末尾追加 OpportunityProgressFactRefV1，并新增该 closed object schema。必需字段为 factType（常量 OPPORTUNITY_PROGRESS）、factRef（原 OpaqueRef）和 digest（原 Digest32）。不允许 revision、内部 ID、进展正文或额外属性。原 PublicFactType、其他回执和各 R1 操作的定向完成事实类型保持。

scripts/baseline/r2_opportunity_receipt_contract.py 只接受此完整精确增量，剥离后继续执行原 R1 全文哈希及既有增量校验。缺失 schema、缺失/重复/重排 oneOf 分支、错误字段、扩大对象、更改旧接口安全声明均不被本增量掩盖。无此增量的历史契约仍可验证。后端 wire model 与前端生成类型同步生成；前端只接受摘要型商机进展引用。

默认回执读取组合商机 Owner 当前身份读取。成功/拒绝回执与草稿回执均沿用原命令的精确身份和当前授权检查；已完成待办不阻断历史回执恢复，撤权后不能披露。商机身份读取不需要解密进展正文，也不要求为回执服务增加密钥。返回 no-store，并通过原披露审计提交边界后才返回响应。

本增量不等于商机提交接口或工作卡上线，不触发自动进展、报价或转案；业务及设计范围继续采用批准的高保真 C。R1 验收 PAUSED，R2 发布验收 NOT_GRANTED。
