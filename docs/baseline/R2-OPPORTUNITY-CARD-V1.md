# R2_OPPORTUNITY_CARD_V1

依据已批准高保真 C，将商机跟进接入原工作台单卡及我的待办，不增加页面、字段或管理范围。

## 精确传输增量

新增 R2OpportunitySubjectV1、R2OpportunityFormV1、R2OpportunityCurrentCardV1、R2CurrentCardV1 四个 schema。原 CurrentCard 七类分支、TaskType、ActionCode 保持；CurrentWorkCardEnvelope 的可空卡片引用切换到包含原七类与商机卡的准确联合。

商机卡仅接受 OPPORTUNITY 主体、PROGRESS_OPPORTUNITY 责任、RECORD_OPPORTUNITY_PROGRESS 主命令和 OPPORTUNITY_PROGRESS 完成事实。表单只能是无草稿的空对象或已有四字段进展值；fields 固定为空数组，由已有静态表单组件呈现批准的三个人工输入项，不允许服务端任意扩展字段。

scripts/baseline/r2_opportunity_card_contract.py 对完整增量作精确比对后剥离，再执行原提交、回执及冻结契约检查。Java 通过封闭类型分派绑定新联合，原七类卡片使用明确 taskType 属性序列化，避免原 discriminator 接口在新联合中抑制该字段。未手改生成文件或扩宽原枚举。

## 单卡连续办理

生产 R1ApiServices 在有商机保护口时组合已有授权商机读取；无保护口的兼容装配仍不披露商机。CurrentCard / useCurrentCard / API 复用原保存、确认、未保存内容保护、未决写入和恢复状态，正式确认后刷新授权事项。时间规范化保留微秒精度。

无草稿卡不自动制造发生时间；首次人工编辑记录发生时间。未保存或编辑后未重存时不能确认。可从商机切换有权 R1 事项并回到系统推荐；响应丢失后保留原未决写入并禁止切换。人工确认与后继 WAITING 创建由原后台事务保证。

## 交付边界

本增量接通已有商机责任的页面办理，不等于生产首次激活及到期唤醒已接通。测试中的初始责任仍由测试装配建立。后续需接通可靠后台接续，再实现已批准的报价、授权直接准备合同、签署和案管接收；不能把等待计数当作自动唤醒证据。

不改变付款按合同约定阻断、案管接收后建案再分类，以及 AI 仅辅助且人工确认的既定范围。R1 PAUSED / R2 NOT_GRANTED 保持。
