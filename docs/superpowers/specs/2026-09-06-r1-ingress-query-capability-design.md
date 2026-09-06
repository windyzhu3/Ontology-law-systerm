# R1 补全联系方式最小 QUERY 读取能力修订

日期：2026-09-06。状态：DRAFT，待用户确认本书面规格。

基准提交：`d41ee8da491dde9a06a2a525165851c1e431fe66`。用户已同意先进行最小必要的只读能力设计与合同受控修订，再继续Task5；尚未把本文件具体字段清单和后继版本登记为活动合同。本文件不修改当前权限，不构成Task5完成或部署授权。

## 1. 问题和选择

[Task矩阵](../../contracts/r1/R1-TASK-COMPLETION-MATRIX.md)的`R1_DUPLICATE_CANDIDATE_V1`要求原始及补全phone/email HMAC一起参与同Tenant候选匹配；[披露规格5.2](2026-09-05-r1-business-closure-alignment-design.md)限定同连接QUERY读取，审计提交后才返回200/304；[V850](../../../database/schema-contract-52-plus-2/generated/db/migration/V850__lead_ingress_completion_slot.sql)却明确禁止QUERY读取所有补全槽列。只比较原始值会漏选或错排候选。

| 方案 | 结论 |
|---|---|
| 只开放两个补全HMAC | 能修复匹配，但仍不能通过QUERY取得后补联系方式用于已有敏感展示；功能读取缺口仍在。 |
| 仅开放phone/email各自密文与HMAC，共四列 | 推荐：覆盖精确候选计算与受控联系方式读取，保留其余补全元数据禁读。 |
| QUERY切到COMMAND、提权函数或整表SELECT | 不采用：前两者引入提权通路，后者超出必要列范围。 |

## 2. 唯一新增授权面

仅`${app_query_role}`对`lead.lead`增加以下四列的列级`SELECT`，不带`GRANT OPTION`：

| 列 | 必要性与使用边界 |
|---|---|
| `ingress_completion_phone_hmac` | Lead Owner内部精确电话匹配；不交给纯Query、HTTP、Worker或日志。 |
| `ingress_completion_email_hmac` | Lead Owner内部精确邮箱匹配；不交给纯Query、HTTP、Worker或日志。 |
| `ingress_completion_phone_ciphertext` | Lead Owner使用现有`INGRESS_PHONE`字段绑定解密；只有经逐来源授权及披露审计的必要展示值可进入既有响应。 |
| `ingress_completion_email_ciphertext` | 同上，使用现有`INGRESS_EMAIL`字段绑定；不暴露密文。 |

QUERY继续禁止读取其余五列：`ingress_completion_source_code`、`ingress_completion_source_summary_ciphertext`、`ingress_completed_by_appointment_id`、`ingress_completed_at`、`ingress_completion_digest`。不得新增Lead整表SELECT；`SELECT * FROM lead.lead`仍应因禁读列而失败。四列之外的既有SELECT集合不得扩大。

不增加QUERY的INSERT/UPDATE/DELETE/TRUNCATE或函数执行能力；COMMAND现有权限不变；WORKER、AUDIT、PUBLIC及各登录角色权限/成员关系不变。API登录角色仍NOINHERIT，通过既有能力执行器选择角色，不获得权限并集。

密文与HMAC仍是敏感受保护数据，不因新增数据库SELECT就自动获得业务披露授权。这是明确的数据库只读能力扩大，不应称为“权限完全未改变”。

## 3. 历史不可变与后继迁移

采用现有ContractEvolution机制追加`V860__lead_ingress_query_read_capability.sql`，提出后继物理能力合同版本`52-plus-2-v1.2`。保持V001–V850全部字节、既有历史证据和其当时的权限断言不变；不得重写V850去掩盖差异。

V860只应用上节四列授权及受控部署合同版本推进，并验证最终权限集合。它不改表、列、类型、约束、索引、外键、业务触发器或已存在业务数据；13 Schema、52应用表加2技术表保持不变。旧`52-plus-2-v1.1`数据库必须通过新迁移升级，不能仅修改制品期望版本让校验放行。

生成器、当前manifest及其摘要、运行时合同版本/迁移完整性检查和readiness需要同步承认新后继；历史manifest、旧版本测试与证据保持准确。旧迁移阶段的禁读测试继续通过；新的最终阶段验证四列可读、另五列仍拒绝，不能把旧阶段断言从历史删除。

新建库完整迁移和现有v1.1库追加升级均须测试。升级失败必须回滚授权/部署状态，不允许保留半升级数据库。Flyway重启验证不得出现旧迁移checksum漂移；应用发现版本或manifest不匹配必须失败关闭，不自动修复生产权限。

## 4. 活动合同的唯一受控例外

提出ADR-0010与基线后继`MVP-2026-09-06.2`，只替代原闭环规格、计划及活动数据库合同中与上述四列禁读/固定v1.1版本直接矛盾的条款。原“不得修改物理合同”的约束仅对此具名能力后继豁免，其他字段/权限/模型限制全部保留。

合同源、生成SQL、manifest、基线/运行时verifier、相关正负测试及活动引用必须在同一受控实现单元中一致落地。不得全仓机械替换历史版本，也不得只改授权SQL或放宽测试。OpenAPI、公共DTO、15个operation、事件Schema、Task/命令代码、完成矩阵及模块DAG不变。

R1-BACKEND、SPA、E2E、容量和发布状态不因权限修订自动升级；新版本的本地测试不继承旧版本的已验收结论。容量环境及容量向量冲突仍独立待处理。

## 5. Task5消费者边界

修订验证通过后继续原Task5，不扩展Task6–10。Lead Owner使用显式列清单读取，不在QUERY下复用`selectFrom(lead)`整行命令读口。候选复用原匹配、排序、创建时间边界和准确Party规则，返回准确来源selector而非HMAC。

保留`CurrentLeadReader`既有`capturedPhone/capturedEmail`的“原始捕获值”语义。需要有效联系方式时添加具名只读结果：每个渠道优先取非空原始值，否则取补全值；不得改写数据库、混用加密字段AAD、从Draft/Audit/Receipt反推Fact，或把读失败伪装成空值。两者都无值才是真正缺失；解密失败按既有500安全错误规则处理。

所有实际展示的联系方式沿用冻结DTO允许的敏感字段，不增加公共字段、第二个Draft GET、详情API或联系发送能力。准确Lead revision覆盖原始/补全来源；实际披露来源、授权锚点和ETag仍按原5.2/5.3规则绑定。

SensitiveReadRuntime仍是同一READ COMMITTED连接：QUERY → Tenant shared fence → 来源读取/授权 → identity shared最终重读 → AUDIT append/commit → 200或304。不切COMMAND、不独立审计事务、不缓存或提前序列化敏感字节。候选不可见处理不得更改冻结主命令所要求的候选身份。

## 6. 有限验收清单

1. 生成与历史字节：V001–V850逐文件摘要不变；只增加V860和获准的当前版本产物，数据库对象/业务字段数量不变。
2. 权限实库：QUERY四列SELECT成功，另五列SELECT、整行SELECT、全部业务写入仍拒绝；WORKER/AUDIT/PUBLIC及登录角色的直接权限和成员关系不变。API登录仍不能直接继承读取，只有经既有能力切换进入QUERY后得到这四列的新增读取能力；Worker登录无法进入QUERY。
3. 升级：新库全迁移、v1.1真实数据升级、故障回滚、重启验证、错误版本/manifest readiness拒绝；补全槽一次写入/不可覆盖/不可清空规则不变。
4. 匹配：原始↔原始、原始↔补全、补全↔原始、补全↔补全、phone/email组合与空值；跨Tenant不匹配，冻结排序及时间边界不变；与真实主命令候选一致。
5. 解密读取：原始值语义不变、仅补全的有效联系方式可读、正确Tenant/字段AAD、缺失与解密失败区分；禁止密文/HMAC进入公开响应和日志。
6. Task5继续原有七卡、准确来源、因果选择、逐来源DENY、200/304、多条Audit、Nth append故障、提交确认丢失和并发复验；无未审计响应或命令副作用。

第1–3项是能力修订自身退出门禁，第4–6项是继续Task5时的功能完整性门禁，不能用前者通过冒充Task5完成。固定运行时、原始退出码和实际用例身份须分别记录，并进行独立规格/质量评审。

## 7. 交付边界与自审

本书面规格阶段不执行授权或迁移。确认后先生成有限实施计划，完成并评审能力合同修订，再恢复Task5。不得推送、合并、部署或修改外部环境；无新依赖、表、业务动作、Provider、AI、管理界面、通用权限平台或后续MVP范围。

自审：四列ALLOW、五列继续DENY明确；旧迁移与新最终权限阶段区分；密文/HMAC不等于业务可读授权；现有字段语义和DTO不变；后继版本是待激活提案而非已接受事实；能力修订与Task5/整体R1验收分开。没有未定义字段或宽泛“必要时开放全部”的授权。
