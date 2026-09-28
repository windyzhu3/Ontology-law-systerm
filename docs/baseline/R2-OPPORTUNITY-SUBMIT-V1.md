# R2_OPPORTUNITY_SUBMIT_V1

批准依据：高保真 C 的商机跟进 / 独立后继责任，原 R2 业务范围不变。本增量不增加管理页面、业务分类、报价、签署或转案动作。

## 传输增量

- PUT /api/v1/tasks/{taskId}/opportunity-progress-draft：专用类型的商机草稿入口，实际仍保存同一 Task 的唯一 ActionDraft。要求 Idempotency-Key 及准确 If-Match 或首次 If-None-Match: *。
- POST /api/v1/tasks/{taskId}/commands/record-opportunity-progress：正式人工确认，要求 Task ETag、草稿 ID / 修订 / 摘要和一致的正文。

两个入口均为当前任职本人处理；不支持代办、SERVICE 或请求内身份替代。原 /draft 和原 ActionCode 枚举不变，R1 线索草稿不能误收商机正文。专用协议不对应新用户页面，后续仍由现有单卡承载。

新增六个 closed schema：OpportunityProgressValuesV1、SaveOpportunityProgressDraftV1、RecordOpportunityProgressV1、OpportunityProgressDraftProjectionV1、OpportunityProgressDraftWriteResultV1、OpportunityProgressCommandReceiptV1。正文仅有进展类型、人工确认摘要、发生时间和下次跟进时间；未交付报价、业务分类、原始内部备注或任意附加属性不在输入范围。

scripts/baseline/r2_opportunity_submit_contract.py 对两路径和六 schema 作完整精确校验后剥离，继续执行既有增量及 R1 冻结全文校验；不以降低原安全声明、字段约束或删减检查换取通过。前后端类型由单一 OpenAPI 生成。

## 业务与设计一致性

处理器复用既有 CommandRuntime，提交后仍由同一事务确认草稿、记录进展、完成原待办、创建一个独立 WAITING 后继待办、写入审计 / 事件 / 回执。重复提交返回原结果；正文与草稿不一致使用原 DRAFT_DIGEST_MISMATCH / 409 规则；完成后重放保存草稿请求返回原回执及当前不可编辑草稿。

后继 SLA 的业务时区从商机因果来源 Lead 的已注册 R1 source policy 取得，不由 HTTP 输入或全局临时时区指定。原 Task 的 SLA 不改写。

商机 subject ETag 使用命名 R2_OPPORTUNITY_RESOURCE_TAG_V1，绑定准确商机修订及 Actor；R1 Lead / Task / Draft tag 算法不变。生产装配沿用租户现有加密用途的 AES key，并使用 R2 保护口独立的 AAD 版本及租户 / 商机 / 进展绑定；不新增用户配置字段。没有配置商机保护口的兼容装配对新提交入口返回 SERVICE_UNAVAILABLE，既有 R1 能力保持。

HTTP 业务闭环、原命令回归及类型检查验证通过后，仍须继续完成单卡、我的待办、生产初始激活和到期唤醒，不能把接口通路计为销售页面闭环。R1 PAUSED / R2 NOT_GRANTED 不变；本开发没有部署动作。
