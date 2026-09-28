# R25-AI-CANDIDATES-V1

状态：IMPLEMENTING。依据已批准 R2.5 与 T 补稿；真实模型与 15 份人工验收尚未完成。

## 三项边界

| 项目 | 服务端准确来源 | 候选回填位置 |
| --- | --- | --- |
| FIELDS | 当前有权线索的姓名、联系方式和需求原文 | 原客户需求表单的联系人、电话、客户目标；客户名称供人工核对主体，不自动创建或选择 Party |
| SUMMARY | 已确认的 opportunity_progress 原记录，按时间有界读取 | 原跟进表单 progressSummary；有效进展类型、发生时间和下一次时间仍由人填写 |
| MATERIALS | T06 当前静态销售准备目录（合同及业务资料、相关往来记录）及有权的已接收材料当前版本 | 原材料区的待核建议；不确认材料、不改变原业务准入 |

字段提取首片选择线索文本，分别保留新版 customerName、原 capturedName、联系人、原录入电话和需求原文，不把原电话冒充后补的当前联系方式；不增加 OCR、Office 解析或向外部上传原文件。T 中主体证明、签署归档材料、交接说明属于演示数据，不能覆盖 T06 已批准目录。材料没有对应可见记录时只称“当前可见清单未找到”，不把受限材料推断为不存在。

## 读取和候选

源数据由后端现有 Owner 端口读取。先做准确事实授权、披露审计并提交读取事务，然后才允许传给已配置模型；不在网络调用期间占用数据库事务、业务锁或连接。返回候选前再次读取、授权和审计，对比相同来源摘要；权限失效或来源改变则丢弃候选。采纳仍需校验当前来源，随后只回填浏览器草稿，原业务命令负责最终确认。

输入最多 50 个有名称的来源、合计 32,000 字符；超界报错，不静默截断。模型只看到任务、来源编号、展示名和选定原文，不收到租户、任职、权限、密钥或命令对象。来源内容是数据，不能作为模型指令。输出仅允许封闭字段、值、状态和准确原文引用；拒绝未知字段、重复 JSON 键、未知来源、伪造引文、越界输出、拒绝回答、不完整响应和工具调用。候选必须有人核对，引用匹配并不证明模型解释正确。

FIELDS 仅 customerName/contactName/contactPhone/customerGoal；SUMMARY 仅 progressSummary；MATERIALS 仅 CONTRACT_BUSINESS/CORRESPONDENCE。状态仅 CANDIDATE/MISSING/CONFLICT；缺失和冲突不能直接回填值。每项必须至少一条可核对引用，材料建议必须引用目录依据。

## 模型适配器

独立的 Responses 协议适配器使用显式配置的模型和服务端秘密，地址固定为 OpenAI 官方 HTTPS Responses 端点；默认关闭，无默认模型。仅单次结构化输出请求，store=false，无工具、流、会话续接、文件上传或自动重试。设置输入/输出大小及 45 秒请求时限；失败给出固定错误码，不回显供应商正文、密钥或原文。

运行配置仅在 `ols.api.ai.enabled=true` 时读取；还须明确 `ols.api.ai.provider=openai-responses`、`ols.api.ai.model` 和 `ols.api.ai.api-key-path`（工作区外绝对路径的秘密文件）。缺少任一项拒绝该配置，不读取通用环境凭据、不猜默认模型。当前评审环境保持关闭。

同一 OpenAPI 的 `POST /api/v1/opportunities/{opportunityId}/ai-candidates/{task}` 仅收空对象；`.../{task}/recheck` 仅收来源令牌。令牌绑定本人、任职、租户、商机、任务种类与准确来源，600 秒失效。接口无业务命令标识、回执或业务写入；返回前的审计允许失败回滚。AI 无来源/输入超界为 400，来源变化为 412，模型关闭/超时/无效输出为 503；这些均保留原手工草稿。

协议依据：[Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)、[Responses 迁移说明](https://developers.openai.com/api/docs/guides/migrate-to-responses)。store=false 只是请求不保存响应状态，不能替代供应商数据保留政策与组织的数据出境配置。

## 验证与剩余门槛

本地 HTTP 替身只验证协议与拒绝路径，不计真实 AI 样本。生产接线、原表单采纳/修改/忽略、输入变化失效与关闭后的完整手工链须分别验证。至少 15 份授权合成/脱敏样本、每项至少 5 份，保存真实调用及人工修改和原命令结果后，才可将 R25-05 标记完成。
