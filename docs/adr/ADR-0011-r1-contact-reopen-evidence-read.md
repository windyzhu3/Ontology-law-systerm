# ADR-0011：R1主管重开联系与Evidence引用只读边界

Status: Accepted

Date: 2026-09-06

Semantic baseline: MVP-2026-09-06.3

Task contract: R1-TASK-COMPLETION-V1.2

Physical capability: 52-plus-2-v1.2

## Context and named supersession

本ADR承接已批准的[主管重开与Evidence引用规格](../superpowers/specs/2026-09-06-r1-contact-reopen-evidence-read-design.md)，只替代两点：活动Task合同中把自动重试次数写成`attemptNo`且耗尽/复核因果限定为`contact_no=3`的解释；以及`evidenceSubmissionId`已有HTTP字段却缺少Evidence Fact Owner只读边界的空缺。原字段shape、15个operation、事件类型与准确事件数、Receipt/Audit/Outbox原子性、锁顺序、数据库表/迁移/角色权限及物理能力版本保持不变。

## Decision: Lead全局联系序号与自动额度分离

`contact_no`是同Tenant、同Lead从1开始、在既有Lead排他业务锁内按历史最大值加1分配的全局单调序号；主管重开、Owner任期或Assignment变化不得重置、覆盖或复用。不新增`attemptNo`持久字段或轮次字段；安全整数无法递增时在事实写入前以既有技术错误整体回滚。

无需主管新决定的自动联系额度只覆盖全局第1、2次未接通：第1次按原日历在下一当地工作日10:00恢复，第2次在15:00恢复；第3次及以后未接通均创建`REVIEW_LEAD_VALIDITY`、reason=`CONTACT_RETRY_EXHAUSTED`且不自动创建CONTACT。任意合法正序号的`CONNECTED_VALID`仍开立唯一Opportunity；任意合法正序号的`SUSPECT_INVALID`仍进入主管复核。原事件成员和数量不变，事件合法性不得受`contactNo<=3`上限约束。

主管决定仍仅为`CONFIRM_INVALID|CLOSE_UNREACHED|REOPEN_CONTACT`。`REOPEN_CONTACT`完成旧REVIEW并新建一张`OPEN/revision0` CONTACT Task，冻结当前准确Lead revision与唯一有效OPEN Assignment Owner；旧CONTACT/REVIEW永久DONE。新Task使用普通30分钟SLA，不伪造WaitReceipt。例：第1次疑似无效后主管重开，第2次未接通仍按原15:00规则自动安排第3次；第3次或更晚未接通后重开所得第4/5次仍只进入主管复核，不补充自动额度。

复核触发资格为`SUSPECT_INVALID`或`NOT_CONNECTED且contact_no>=3`。同Lead、来源CONTACT Task完成事实、时间上限与最大`(resulted_at, UUID无符号网络字节序)`规则保持；选中的结果必须是创建该REVIEW的同事务结果，不能用旧第3次结果冒充第4次结果。Task合同谓词为`contactNo<3`与`contactNo>=3`。

## Decision: Evidence引用的最小只读Owner边界

`evidenceSubmissionId`仍是可选输入；缺省时Evidence读取数为0。出现时只接受Actor Tenant中的不可变`evidence_submission`及其唯一`evidence_binding`：绑定ACTIVE且未撤回，`target_type=lead.lead`，target ID/revision准确等于业务锁内当前Task绑定的Lead selector且target hash为空。不得沿同Lead旧revision、仅ID或外键存在性猜测可见性，也不把引用解释为审核、上传、扫描、晋级或绑定动作。

最小公开读口只访问`evidence_submission`与`evidence_binding`，SQL及两张必要jOOQ生成类型属于Evidence Owner。提交selector为完整不可变行按`R1_JSON_JCS_SHA256_V1`形成的`evidence.evidence_submission@hash`，绑定selector为`evidence.evidence_binding@revision`；不得返回文件内容、文件名、object key、对象位置、下载URL或能力凭据。模块依赖只新增`lead→evidence`、`api→evidence`和`evidence→identity`，Evidence不得反向依赖Lead、responsibility、execution、api、query或worker。

授权复用首联的`ASSIGNMENT_OWNER / SALES_CONTACT_OWNER`、真实Task Owner组织scope及HUMAN `DIRECT|DELEGATED`路径。Task、Lead、Submission、Binding四个准确Subject分别检查DENY；OBJECT-only、提交人身份、跨Tenant存在或拼接不完整Grant均不能放行。不新增`EVIDENCE_READ`权限码或数据库GRANT。

Evidence读取安排在Runtime现有QUERY阶段；执行阶段只消费已验证selector，最终QUERY阶段在既有业务锁和identity shared锁下以同一连接、新鲜数据库时间重验完整关系与授权，Owner方法内部不得切换角色。无效、不具资格、跨Tenant或不可见统一安全`NOT_FOUND`：pre-slot为全零，post-slot只允许既有REJECTED Slot/Receipt/Audit delta；技术故障整体回滚。

Draft只保存候选，不证明Evidence已校验。CurrentCard只有在相同资格与逐来源授权通过后才回显ID；失效卡选择下一张或安全零态，不删除字段改变Draft digest。Submission/Binding selector及授权依赖进入Workbench ETag和披露Audit来源，200与304均先重验并在Audit提交后返回。

Binding撤回不由identity锁保护。任何未来影响R1引用的Evidence绑定写者必须先进入现有`R1_BUSINESS_TENANT_LOCK`排他围栏，并保持业务锁先于identity锁；本合同不实现该写者。

## Delivery boundary

本次只激活文档与静态验证器，不实现Java端口、jOOQ生成、Handler、CurrentCard、HTTP装配、SPA或E2E，也不改`backend/`、`apps/`、`contracts/`、`database/`和历史`docs/evidence/`。原收口计划Task 6仍须以本合同提交为新BASE完成生产实现与独立评审；R1-BACKEND、SPA、E2E、容量、发布及v1.2托管运行证据门禁均不提升。
