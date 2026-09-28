# R2 商机工作卡授权读取 V1

本片属于已批准高保真 C 的商机单卡和“我的待办”接线，不增加页面、输入字段或管理范围。第十七批已通过 R2_OPPORTUNITY_CARD_V1 接通生产 HTTP 装配及前端办理，首次激活与到期唤醒仍待接续。

## 数据和业务身份

`OpportunityWorkCardQuery` 使用独立的 Opportunity 数据类型，保留当前商机修订、原责任及当前 Owner。来源 Lead 只提供有权披露的客户名称、联系人名称和已确认需求。卡片主体为 OPPORTUNITY，待办的公共引用也指向 Opportunity，不将商机当作 Lead 处理。

无草稿时，表单 values 为空；查询不制造发生时间、进展类型或下一次跟进。存在草稿时，核对其任务、动作、版本、规范正文及摘要后返回同一份值。历史草稿中的下一次时间已经过去，不影响读取和修正；正式确认仍由命令和前端候选校验执行时间约束。

## 授权、审计及选择

- 只组合当前本人任职的商机责任；要求准确当前商机修订、相同 Owner、商机未关闭，以及当前直接权限。
- Task、Opportunity、来源 Lead 分别授权；草稿存在时还授权准确 Draft。任一对象被拒绝，事项从当前卡和我的待办中隐藏。已取消、已完成事项不再发放。
- 商机与 R1 事项共用排序和选择机制。主动选择另一有权 R1 事项时，系统推荐仍指向原推荐项；所选项失效则返回当前推荐及原有中文提示。
- 确认商机进展后，原事项完成，独立 WAITING 后继计入等待数，不作为可办理事项返回。此读取不负责唤醒任务。
- 复用 SensitiveReadRuntime 的事务栅栏、身份锁后重读、审计提交后披露及 304 重新披露审计。新增 R2_CURRENT_WORKCARD_DISCLOSURE_V1 / R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1；原 R1 审计规则与序列化保持。
- R2 商机必须以自身为授权锚点，R2 Draft 必须自行通过对象权限；原 R1 Draft 继续使用原 Task 锚点，不放宽为任意对象绑定。

## 同批修复的前端连续保存问题

商机 PUT 创建草稿接受 201，修改已有草稿接受 200。响应一致性比较只对时间做语义等价规范化，保持微秒精度，不能把实际不同时间当成相同值。候选输出与 Java Instant 的零/三/六位小数表示一致；微秒级未来发生时间也会拒绝。原草稿 ID、修订、摘要和正文校验继续保留。

## 生产接线与剩余接续

四参数 CurrentWorkCardDisclosureService 构造器限 API 包内部组合；R1ApiServices 现将商机保护口传入。完整 Opportunity 卡类型、生成类型、前端严格解析和 CurrentCard / useCurrentCard 的保存、确认及 dirty / pending 状态已通过命名传输增量接通。仍需完成初始激活、历史接续和可靠到期唤醒。

新联合保留原七类卡片；真实 HTTP 回归验证 R1 / R2 的 taskType 均正确披露。未通过浏览器读取偷偷生成或唤醒任务。R1 验收继续 PAUSED，R2 发布验收未授予。

## 验证证据

R2OpportunityWorkcardIT 通过真实 R1 有效联系创建商机，再通过当前 Owner 服务创建测试责任；覆盖商机读/选/缓存审计、来源拒绝、R1/R2 混合选择、草稿权限、取消隐藏及真实 HTTP 确认后的等待状态。初始责任创建在测试中完成，不等于生产激活消费者已经接通。

前端八组回归 110 项、TypeScript 7.0.2 检查及开发门禁通过。后端分组共 63 项通过：架构 13、新商机卡 6、原卡查询 6、我的待办 3、披露 16，以及审计边界 2、权限 6、并发 3、原 HTTP 8。两批 Maven 均退出 0。日志位于 output/r2-card-read-third.log、output/r2-card-client-regression.log、output/r2-card-authorization-regression.log 和 output/r2-card-development-gate.log。
