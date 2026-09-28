'use strict';
// Review-only: all facts, identities and transitions are synthetic. No API or storage.
const scenes = {
  intake:['17 R1 线索承接','线索与客户','陈晓 · 销售','首联'],
  tasks:['18 我的待办与商机跟进','工作台','陈晓 · 销售','商机'],
  waiting:['19 已记录与下次跟进','工作台','陈晓 · 销售','商机'],
  opportunity:['20 商机与报价台账','商机与报价','陈晓 · 销售','商机'],
  quote:['21 准备报价','商机与报价','陈晓 · 销售','报价 / 授权'],
  quoteReady:['21a 报价草案已生成','商机与报价','陈晓 · 销售','报价 / 授权'],
  quoteApproval:['22 报价审批','商机与报价','刘敏 · 销售主管','报价 / 授权'],
  quoteDelivery:['23 报价交付证据','商机与报价','陈晓 · 销售','报价 / 授权'],
  response:['24 客户回复','商机与报价','陈晓 · 销售','报价 / 授权'],
  direct:['25 直接准备合同授权','商机与报价','刘敏 · 有权授权人','报价 / 授权'],
  conflict:['26 签约前冲突信息','商机与报价','陈晓 · 销售','合同'],
  risk:['27 等待风险处理','商机与报价','陈晓 · 销售','合同'],
  contract:['28 准备与预览合同','合同与收款','陈晓 · 销售','合同'],
  contractApproval:['29 合同审批与退回','合同与收款','周宁 · 合同审批人','合同'],
  contractReturned:['30 修改合同新版本','合同与收款','陈晓 · 销售','合同'],
  signature:['31 提交签字件','合同与收款','陈晓 · 销售','签署'],
  signatureReview:['32 授权核验签署','合同与收款','赵欣 · 签署核验人','签署'],
  execution:['33 必要用印与归档','合同与收款','赵欣 · 行政','签署'],
  conditions:['34 合同执行与付款条件','合同与收款','陈晓 · 销售','转案'],
  payment:['35 先款条件与到账核验','合同与收款','王悦 · 财务','转案'],
  handoff:['36 转案资料提交','转案与案件','陈晓 · 销售','转案'],
  intakeReview:['37 案管接收或退回','转案与案件','赵欣 · 案管','转案'],
  correction:['38 转案补正','转案与案件','陈晓 · 销售','转案'],
  caseCreated:['39 接收建案后分类','转案与案件','赵欣 · 案管','案件'],
  classified:['40 分类完成','转案与案件','赵欣 · 案管','案件'],
  contracts:['41 合同管理台账','合同与收款','陈晓 · 销售','签署'],
  team:['42 团队待办与承接异常','团队待办','刘敏 · 销售主管','商机'],
  recovery:['43 提交结果待核对','工作台','陈晓 · 销售','商机'],
};
const $ = s => document.querySelector(s);
const esc = x => String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let current='intake', dirty=false, pending=null, recoveryTarget='waiting', entry='quote', mustPay=false, revision=1, task='progress', ai=false, saved=false, values={};
const button=(label,next,primary=false)=>`<button ${primary?'class="primary" ':''}data-go="${next}">${label}</button>`;
const field=(label,id,value='',type='text')=>`<div class="field"><label for="${id}">${label}</label><input id="${id}" name="${id}" type="${type}" value="${esc(values[id]??value)}" required></div>`;
const area=(label,id,value='')=>`<div class="field wide"><label for="${id}">${label}</label><textarea id="${id}" name="${id}" required maxlength="2000">${esc(values[id]??value)}</textarea></div>`;
const choice=(label,id,options)=>`<div class="field"><label for="${id}">${label}</label><select id="${id}" name="${id}">${options.map(([v,l])=>`<option value="${v}" ${values[id]===v?'selected':''}>${l}</option>`).join('')}</select></div>`;
const facts=rows=>`<dl class="read-grid">${rows.map(([k,v])=>`<div><dt>${k}</dt><dd>${v}</dd></div>`).join('')}</dl>`;
const banner=(title,body,warn=false)=>`<div class="banner ${warn?'warning':''}"><strong>${title}</strong><p>${body}</p></div>`;
const action=(label,next,note,extra='')=>`<div class="actionbar"><p>${note}</p><div class="actions">${extra}<button class="primary" data-submit="${next}">${label}</button></div></div>`;
const responsibility=(title,owner,when)=>`<div class="next-owner"><small>后续责任</small><strong>${title}</strong><p>${owner} · ${when}</p></div>`;
const evidence=()=>`<div class="demo-evidence"><strong>演示材料</strong><p>海宁公司确认记录.pdf · 2026-09-14 · 已关联当前版本</p><small>审阅稿使用合成材料；正式页面须支持文件接收、权限校验和内容预览。</small></div>`;
const checks=labels=>`<div class="checks">${labels.map((x,i)=>`<label><input type="checkbox" name="check${i}" required>${x}</label>`).join('')}</div>`;
const history=()=>`<details><summary>线索来源与办理记录</summary><ol class="timeline"><li>销售导入线索 · 来源记录已保留</li><li>重复核验完成 · 联系方式已补齐</li><li>已分配陈晓 · 首联已接通，确认有效需求</li><li>由该次有效联系形成商机 · 沿用客户与需求</li></ol></details>`;
const nextNote='仅演示下一角色的有权页面；实际系统不会切换身份或自动替他人确认。';
function content(){switch(current){
case 'intake':return `<div class="panel"><span class="tag">R1 已有业务节点</span><h2>海宁公司 · 首次联系</h2>${facts([['来源','销售导入 / 九月企业客户'],['负责人','陈晓'],['联系方式','138 **** 6028'],['当前责任','联系客户并确认需求']])}<p>录入完成后仍按实际情况处理重复核验、联系方式补齐和负责人分配。只有有效联系结果才能形成商机。</p>${history()}${action('查看有效联系后的商机','tasks','演示已有 R1 有效联系事实，不重新录入客户。')}</div>`;
case 'tasks':return `<div class="task-switch" aria-label="我的有权待办"><button data-task="progress" aria-pressed="${task==='progress'}">海宁公司 · 记录跟进</button><button data-task="contact" aria-pressed="${task==='contact'}">林女士 · 首次联系</button></div>${task==='contact'?`<div class="panel"><span class="tag">首联待办</span><h2>联系林女士，确认委托需求</h2><p>属于当前销售的 R1 首联责任，沿用既有联系卡。</p>${facts([['到期','今日 16:00'],['来源','销售手工录入']])}${action('返回商机跟进演示','tasks','本页仅展示跨阶段有权事项切换。')}</div>`:`<form class="panel"><span class="tag">当前由你处理</span><h2>推进海宁公司的委托</h2><div class="meta"><span>陈晓 · 今日 17:00 前</span><span>最近有效联系：今日 10:30</span></div>${facts([['客户需求','审核合作协议并提供协商支持'],['目前状态','已确认需求，尚未报价']])}${choice('本次有效进展','progressType',[['PHONE_CONNECTED','已接通电话'],['CLIENT_VISIT','客户来访'],['MEETING','面谈'],['SITE_VISIT','外访'],['WECHAT_CONNECTED','有效微信对话']])}${area('进展摘要','summary','客户确认服务范围，约定明天下午回复材料清单。')}${field('下一次跟进时间','nextCheck','2026-09-15T15:00','datetime-local')}<button type="button" class="link" data-ai>根据已有记录生成摘要候选</button>${ai?`<div class="ai-candidate"><strong>AI 摘要候选 · 待人工确认</strong><small>依据：本次有权查看的联系记录</small><textarea id="candidate" aria-label="修改 AI 候选">客户确认服务范围，承诺次日下午提供材料。</textarea><button type="button" data-use-ai>确认采用至摘要</button><button type="button" data-ignore-ai>忽略</button></div>`:''}<p class="help">未接通电话、内部备注和草稿不会计为有效进展。AI 候选需人工确认，故障时可直接手工填写。</p>${history()}${action('保存本次进展','waiting','保存后结束本次责任，生成约定时间的下一次跟进。')}</form>`}`;
case 'waiting':return `<div class="panel">${banner('本次进展已记录','原待办已完成。下一次跟进是独立待办，约定时间到达后可办理。')}<h2>海宁公司 · 下一次跟进</h2>${facts([['进展摘要',esc(values.summary||'客户确认范围，约定下一次联系。')],['负责人','陈晓'],['计划时间',esc(values.nextCheck||'2026-09-15 15:00')],['当前状态','等待约定时间']])}<p class="help">正式运行由可靠调度唤醒，不依赖刷新页面。现在仍可切换其他有权办理的事项。</p><div class="actions">${button('我的待办','tasks')}${button('查看商机','opportunity',true)}</div></div>`;
case 'opportunity':return `<div class="split"><div class="record-list"><button class="selected" data-go="opportunity"><strong>海宁公司</strong><small>已确认需求 · 陈晓</small><span class="tag">待准备报价</span></button><p class="help">仅显示有权管理的商机，阶段由业务事实形成。</p></div><div class="panel"><h2>海宁公司 · 委托推进</h2>${facts([['最近联系','今日 10:30 · 已确认服务范围'],['下一行动','准备收费方案与报价'],['报价','尚无正式报价'],['负责人','陈晓']])}${history()}${action('准备报价','quote','保留正式报价路径。',`<button data-direct-request>申请直接准备合同</button>`)}</div></div>`;
case 'quote':return `<form class="panel"><h2>准备海宁公司的报价</h2><p class="help">${revision>1?'依据退回意见创建新版本；旧版本审批和接受不延用。':'服务范围与需求沿用已确认商机。'}</p>${area('服务范围','scope','合作协议审核与协商支持，不含诉讼代理。')}<div class="form-grid">${field('对客金额（元）','amount','20000','number')}${choice('收费方式','fee',[['FIXED','固定收费'],['PARTIAL','部分风险收费'],['RISK','纯风险收费']])}${field('有效期至','validTo','2026-09-30','date')}${field('付款安排','terms','签约后支付 10,000 元，余款按约定节点支付')}</div><details><summary>价格与审批依据</summary><p>按当前价格与授权规则决定所需审批；纯风险等专门授权必须满足对应要求。本场景演示需主管审批。</p></details>${action('生成这份报价','quoteReady','生成报价草案不代表批准或交付。')}</form>`;
case 'quoteReady':return `<div class="panel">${banner('报价草案已生成','草案责任已完成，当前是独立的报价审批申请责任。')}<h2>提交海宁公司报价审批</h2>${facts([['报价版本','第 '+revision+' 版'],['对客金额',esc(values.amount||'20000')+' 元'],['当前状态','待申请审批']])}${action('提交这份报价审批','quoteApproval',nextNote)}</div>`;
case 'quoteApproval':return `<form class="panel"><h2>审核海宁公司报价 · 第 ${revision} 版</h2>${facts([['服务范围',esc(values.scope||'合作协议审核与协商支持')],['对客金额',esc(values.amount||'20000')+' 元'],['付款安排',esc(values.terms||'按报价付款安排')],['审批对象','当前准确报价版本']])}${choice('处理意见','quoteDecision',[['approve','批准'],['return','退回修改']])}${area('审批说明','quoteReason','服务范围及报价符合本次授权。')}${action('确认报价审批意见','quote-decision',nextNote)}</form>`;
case 'quoteDelivery':return `<form class="panel"><h2>核对报价交付结果</h2>${banner('报价已批准，尚不能记为客户已收到','必须取得准确版本的交付证据；批准、下载或请求发送均不能替代交付完成。')}${facts([['报价','海宁公司 · 第 '+revision+' 版'],['交付对象','海宁公司授权联系人']])}${evidence()}${checks(['已核对报价内容、收件人及实际交付证据'])}${action('确认交付证据并继续','response','演示有证据的人工交付；不引入新的外部消息平台。')}</form>`;
case 'response':return `<form class="panel"><h2>记录客户对当前报价的回复</h2>${facts([['报价','第 '+revision+' 版 · 已确认交付'],['对客金额',esc(values.amount||'20000')+' 元']])}${choice('客户回复','response',[['accepted','明确接受'],['considering','仍在考虑'],['unclear','需要澄清'],['decline','明确不合作']])}${evidence()}${checks(['回复证据明确对应当前报价版本'])}<p class="help">“原则同意”“价格再谈”不等于接受；新版报价发出后，旧版本接受不能用于合同。</p>${action('记录客户回复','response-decision','明确接受进入合同准备前置；其他回复生成各自后续责任。')}</form>`;
case 'direct':return `<form class="panel"><h2>审核直接准备合同的申请</h2>${banner('仅授权合同准备入口','不生成虚假的报价接受，不代替冲突审查、合同审批或签署。')}${facts([['申请人','陈晓'],['商机','海宁公司 · 当前已确认需求'],['服务范围',esc(values.scope||'合作协议审核与协商支持')],['收费与付款',esc(values.amount||'20000')+' 元；'+esc(values.terms||'分两期付款')],['申请依据',esc(values.directRequest||'客户希望直接核对合同')]])}${choice('授权意见','directDecision',[['approve','批准本次准确范围与收费方案'],['return','退回补充依据']])}${area('授权依据','directReason','已核对客户需求及收费方案，可进入合同准备。')}<p class="help">范围、费用或条款变化后需重新取得准确授权。</p>${action('确认授权意见','direct-decision',nextNote)}</form>`;
case 'conflict':return `<form class="panel"><h2>补齐签约前冲突信息</h2>${banner(entry==='direct'?'合同来源：已获直接准备授权':'合同来源：客户已接受当前报价','两条来源汇合至同一冲突审查、合同审批及签署流程。')}${field('委托主体','party','海宁科技有限公司')}${field('统一社会信用代码或等效身份标识','identity','演示身份标识')}${field('已知对方与关联方','counterparty','江川贸易有限公司')}<p class="help">仅收集本次审查所需主体信息；销售不查看其他客户、案件或原始冲突命中。</p>${action('提交签约前冲突审查','risk','提交后显示实际审查结果及下一责任。')}</form>`;
case 'risk':return `<div class="panel">${banner('等待风险处理','责任人：有权风险处理人。暂无承诺反馈时间，状态变化后通知。',true)}<h2>海宁公司 · 签约前审查</h2><p>销售只查看业务说明和补充要求，不披露其他客户与案件信息。</p><p class="help">下方按钮仅切换至“审查已通过”的设计场景；正式界面等待真实审查事实，不能由销售自行放行。</p><div class="actions">${button('预览审查通过后的合同准备','contract',true)}</div></div>`;
case 'contract':return `<form class="panel"><h2>准备委托合同 · 第 ${revision} 版</h2>${facts([['合同来源',entry==='direct'?'准确直接准备授权':'当前报价明确接受'],['签约前审查','通过 · 当前主体与需求'],['合同模板','已审核标准委托模板'],['委托主体',esc(values.party||'海宁科技有限公司')]])}<div class="form-grid">${field('签署人','signer','李明')}${field('签署授权依据','signerAuthority','法定代表人身份证明')}${choice('转案前付款条件','payGate',[['parallel','无先到账前置，收款并行办理'],['required','合同明确约定先到账后转案']])}${field('约定付款金额（元）','firstPayment','10000','number')}</div><details><summary>合同正文预览与关键条款</summary><div class="file-preview">委托主体：海宁科技有限公司\n受托方：示例律师事务所\n服务范围：${esc(values.scope||'合作协议审核与协商支持')}\n费用总额：${esc(values.amount||'20000')} 元\n付款安排：${esc(values.terms||'分两期付款')}\n签署方式：人工签字件核验\n必要步骤：客户签署、律所用印、归档\n\n本框为排版示例，正式实现应生成可预览、下载的完整准确版本文件，不以本示例作为法律文本。</div></details>${checks(['已核对委托主体、服务范围、费用、付款条件及签署要求'])}${action('提交合同审批','contractApproval',nextNote)}</form>`;
case 'contractApproval':return `<form class="panel"><h2>审核合同 · 第 ${revision} 版</h2>${facts([['合同来源',entry==='direct'?'直接准备授权':'已接受报价'],['关键差异','本场景无范围与费用变更'],['付款前置',mustPay?'明确先到账后转案':'无先到账前置'],['签署要求','客户签署、律所用印、归档']])}<details><summary>查看准确合同正文</summary><p>正式页面披露当前审批版本的受控文件；审批仅对该版本有效。</p></details>${choice('处理意见','contractDecision',[['approve','批准'],['return','退回修改']])}${area('审批说明','contractReason','请按当前版本完成签署。')}${action('确认合同审批意见','contract-decision',nextNote)}</form>`;
case 'contractReturned':return `<form class="panel">${banner('合同已退回修改','原审批责任已完成，本次是新的修改责任。',true)}<h2>修正海宁公司的合同</h2>${facts([['退回说明',esc(values.contractReason||'需补齐签署人授权依据')],['当前负责人','陈晓']])}${field('补充签署授权依据','signerAuthority','法定代表人身份证明及签署授权材料')}${action('生成新版本并重新核对','contract','正文或签署要求变化后，旧批准与旧证据不能用于新版本。')}</form>`;
case 'signature':return `<form class="panel"><h2>提交客户签字证据</h2>${facts([['准确批准版本','海宁公司委托合同 · 第 '+revision+' 版'],['当前负责人','陈晓'],['签署方式','人工签字件'],['签署人',esc(values.signer||'李明')]])}${evidence()}${checks(['已确认签字件对应当前批准版本'])}<p class="help">文件上传仅代表证据已提交。尚需有权人员核验、必要用印与归档。</p>${action('提交客户签字证据','signatureReview',nextNote)}</form>`;
case 'signatureReview':return `<form class="panel"><h2>核验客户签字证据</h2>${facts([['合同','第 '+revision+' 版 · 已批准'],['核验范围','内容版本、签署人身份、代表权限、文件完整性']])}${evidence()}${choice('核验结果','signatureDecision',[['verified','核验通过'],['missing','缺页或模糊，同版本补证'],['changed','正文或签署人变化，需新版本']])}${area('核验说明','signatureReason','已核对签署内容、身份与代表权限。')}${action('记录证据核验结果','signature-decision','通过后继续必要签署步骤，不直接跳转建案。')}</form>`;
case 'execution':return `<form class="panel"><h2>完成合同必要用印与归档</h2>${banner('客户签署证据已核验','只有当前签署计划全部满足，合同才可记为签署可执行。')}${checks(['已核对准确批准版本并完成律所必要用印','已完成该签署产物的受控归档'])}<p class="help">正式实现中用印和归档各自记录独立事实与责任，本场景集中展示两项完成后的汇合状态。</p>${action('预览全部签署步骤完成','conditions','本原型演示条件汇合；不是将两项责任合并为一个生产命令。')}</form>`;
case 'conditions':return `<div class="panel"><h2>合同已签署可执行 · 准备转案</h2>${facts([['合同','第 '+revision+' 版 · 必要签署步骤已满足'],['转案付款条件',mustPay?'合同明确要求先到账':'无先到账前置约定'],['首款状态','待财务确认'],['销售责任',mustPay?'跟进先款条件与转案资料':'继续准备转案资料']])}${banner(mustPay?'转案暂受付款条件阻断':'可继续转案，收款责任并行',mustPay?'客户截图不等于到账，需财务确认准确合同的收款。':'不因首款尚未到账而阻断。建案也不会自动确认到账。',mustPay)}${responsibility(mustPay?'核验首款到账':'转案资料准备',mustPay?'王悦 · 财务':'陈晓 · 销售','按当前责任期限办理')}<div class="actions">${button(mustPay?'查看财务核验场景':'准备转案资料',mustPay?'payment':'handoff',true)}</div></div>`;
case 'payment':return `<form class="panel"><h2>核验合同首款到账</h2>${facts([['合同','海宁公司委托合同 · 第 '+revision+' 版'],['应付金额',esc(values.firstPayment||'10000')+' 元'],['可信到账记录','演示银行记录 · 同币种同金额'],['分配范围','仅本合同首款']])}${checks(['已核对可信流水、收款账户、币种、金额和合同','该笔交易尚未用于其他合同确认'])}<p class="help">付款截图仅作辅助材料。重复交易、金额不符等问题交有权人员核验，销售不可修改财务事实。</p>${action('确认到账并分配至首款','handoff','确认后仅解除本合同对应的付款前置。')}</form>`;
case 'handoff':return `<form class="panel"><h2>提交海宁公司的转案资料</h2>${facts([['合同执行','当前版本签署条件已满足'],['付款条件',mustPay?'本场景已获财务确认':'无先到账前置，收款并行'],['接收人','赵欣 · 案管'],['案件身份','尚未生成']])}${evidence()}${area('移交说明','handoffNote','委托主体与服务范围已确认，合同及主体材料已齐备。')}<details><summary>材料缺项提示 · 人工核对</summary><p>AI 提示：请核对联系人授权材料。来源：本次材料清单。可忽略候选；确定性必需材料检查仍然执行。</p><label class="check"><input type="checkbox">已人工核对该提示</label></details>${checks(['已核对合同、主体资料与本次委托信息'])}${action('提交案管审核','intakeReview','提交不生成案件，由案管审核接收后建案。')}</form>`;
case 'intakeReview':return `<form class="panel"><h2>审核转案申请 · 海宁公司</h2>${facts([['提交人','陈晓 · 销售'],['合同','准确签署版本及材料已关联'],['付款前置',mustPay?'已满足':'本合同无此前置'],['案件身份','待接收后生成']])}${choice('审核意见','intakeDecision',[['accept','接收并生成案件'],['return','退回销售补正']])}${area('审核说明','intakeReason','已核对委托主体、合同与转案资料。')}${action('确认案管审核意见','intake-decision','接收只生成一次案件；重试返回原结果，退回不建案。')}</form>`;
case 'correction':return `<form class="panel">${banner('案管已退回补正','原提交与审核记录保留，本次生成新的销售补正责任。',true)}<h2>补正转案资料</h2>${facts([['退回说明',esc(values.intakeReason||'请补充联系人授权材料')],['负责人','陈晓'],['案件身份','仍未生成']])}${area('补正说明','correctionNote','已补充缺少的主体授权材料。')}${evidence()}${action('重新提交案管审核','intakeReview','资料变化重新核对；合同正文变化应回到新版本审批。')}</form>`;
case 'caseCreated':return `<form class="panel">${banner('案管已接收，案件已生成','销售移交责任完成。以下分类由案管处理，不回填为销售准入条件。')}<h2>为新案件确认业务分类</h2>${facts([['案件编号','2026-示例-001（合成数据）'],['关联来源','海宁公司 → 商机 → 签署合同'],['当前负责人','赵欣 · 案管']])}${choice('业务分类','caseType',[['general','综法业务'],['execution','执行业务'],['other','其他业务']])}${action('保存案件分类','classified','只分类既有案件，不再次创建案件。')}</form>`;
case 'classified':return `<div class="panel">${banner('案件分类已保存','线索、联系、商机、报价或授权、合同与转案记录保持可追溯。')}<h2>海宁公司 · 案件已接收</h2>${facts([['案件编号','2026-示例-001'],['业务分类',({general:'综法业务',execution:'执行业务',other:'其他业务'})[values.caseType||'general']],['销售移交','已完成'],['收款','按财务事实独立显示']])}<p class="help">R2 到接收建案与分类；不在此扩展各类案件的专业办理流程。</p>${button('查看合同台账','contracts',true)}</div>`;
case 'contracts':return `<div class="split"><div class="record-list"><button class="selected" data-go="contracts"><strong>海宁公司委托合同</strong><small>第 ${revision} 版 · 陈晓</small><span class="tag">待签署核验</span></button></div><div class="panel"><h2>合同详情</h2>${facts([['合同来源',entry==='direct'?'直接准备授权':'报价接受'],['审批','当前版本已批准'],['签署','签字件已提交，待核验'],['收款','尚未确认'],['转案','执行条件尚未全部满足']])}<details><summary>版本与业务记录</summary><p>当前版本、审批意见、签署文件、收款及转案均通过受控引用查询。台账不能直接改阶段。</p></details>${action('查看当前签署责任','signatureReview','正式界面仅允许有权人员打开并办理；销售只查看办理进度。')}</div></div>`;
case 'team':return `<div class="panel"><h2>团队待办 · 承接异常</h2>${banner('一项商机尚未落实可执行责任','负责人任职已失效。主管可查看异常并进入有权恢复流程，不向销售发放无法办理的卡。',true)}${facts([['业务','示例公司 · 已有效联系形成商机'],['阻塞原因','原销售当前任职无效'],['最近核对','今日 14:20'],['恢复条件','确认当前有效且有对象权限的负责人']])}<p>恢复需保留原责任、处理依据和新承接关系，不重置原 SLA 或篡改 R1 联系事实。</p><details><summary>处理记录与范围</summary><p>团队页用于查看、定位异常及有权处理，不建设通用流程编辑器或任意阶段修改。</p></details><p class="help">负责人恢复操作的具体字段须按现有授权合同接入；当前原型仅演示异常披露。</p>${button('返回商机台账','opportunity',true)}</div>`;
case 'recovery':return `<div class="panel">${banner('提交结果暂未确认','请核对原请求，不能重新提交相同业务。',true)}<h2>核对本次办理结果</h2><p>当前内容与身份保留。核对完成后显示真实结果，再由你决定继续办理哪项责任。</p>${action('核对原请求结果','recover','仅恢复原请求，不发起新命令。')}</div>`;
}}
function render(){
 const s=scenes[current]; $('#scene').value=current; $('#actor').innerHTML=`${s[2]} <b>${s[2][0]}</b>`;
 document.querySelectorAll('.sidebar nav button').forEach(b=>b.classList.toggle('active',b.textContent===s[1]));
 $('#content').innerHTML=`<div class="heading"><h1>${s[1]}</h1><p>沿同一业务记录办理，下一责任清楚可见。</p></div><div class="chain" aria-label="业务阶段">${['线索','首联','商机','报价 / 授权','合同','签署','转案','案件'].map(x=>`<span class="${s[3]===x?'current':''}">${x}</span>`).join('')}</div>${content()}<p id="status" role="status" class="help"></p>`;
 $('#note').textContent=`${s[0]} · 当前为独立设计场景；状态与负责人为演示数据。正式权限、事实、可靠待办、幂等回执均需真实服务接入，原型不代表 MVP 已完成。`;
 $('#content').focus({preventScroll:true});
}
function go(next,force=false){if(!scenes[next])return;if(dirty&&!force){pending=next;$('#leave').showModal();return;}current=next;dirty=false;ai=false;render();}
function submit(next){
 const form=$('#content form');if(form&&!form.reportValidity())return;
 if(form)new FormData(form).forEach((v,k)=>values[k]=v);
 if(next==='quote-decision'){if(values.quoteDecision==='return'){revision++;next='quote';}else next='quoteDelivery';}
 if(next==='response-decision'){
   if(values.response==='accepted'){entry='quote';next='conflict';}
   else {dirty=false;$('#content').innerHTML=`<div class="panel">${banner('客户回复已记录',values.response==='decline'?'已生成销售主管的不合作处置责任。':values.response==='unclear'?'已生成销售澄清责任。':'已生成销售后续跟进责任。')}${responsibility(values.response==='decline'?'不合作处置':values.response==='unclear'?'澄清客户回复':'继续跟进客户',values.response==='decline'?'刘敏 · 销售主管':'陈晓 · 销售','按新责任期限办理')}${button('查看商机','opportunity',true)}</div>`;return;}
 }
 if(next==='direct-decision'){if(values.directDecision==='return'){go('opportunity',true);$('#status').textContent='申请已退回销售补充依据，未授予直接合同权限。';return;}entry='direct';next='conflict';}
 if(next==='contractApproval')mustPay=values.payGate==='required';
 if(next==='contract-decision')next=values.contractDecision==='return'?'contractReturned':'signature';
 if(current==='contractReturned'&&next==='contract')revision++;
 if(next==='signature-decision')next=values.signatureDecision==='missing'?'signature':values.signatureDecision==='changed'?'contractReturned':'execution';
 if(next==='intake-decision')next=values.intakeDecision==='return'?'correction':'caseCreated';
 if(next==='recover')next=recoveryTarget;
 if(next==='tasks')task='progress';
 go(next,true);
}
$('#scene').innerHTML=Object.entries(scenes).map(([k,v])=>`<option value="${k}">${v[0]}</option>`).join('');
$('#scene').addEventListener('change',e=>{const next=e.target.value;go(next);$('#scene').value=current;});
$('#size').addEventListener('click',()=>{const phone=$('#canvas').classList.toggle('phone');$('#size').setAttribute('aria-pressed',String(phone));$('#size').textContent=phone?'查看桌面':'查看小屏';});
document.addEventListener('input',e=>{if(e.target.closest('#content form')){dirty=true;if(e.target.name)values[e.target.name]=e.target.value;if(ai&&e.target.id!=='candidate'){const c=$('.ai-candidate');if(c){c.innerHTML='<strong>候选已失效</strong><p>记录已变化，请重新生成或继续手工填写。</p>';ai=false;}}}});
document.addEventListener('click',e=>{
 const b=e.target.closest('button');if(!b)return;
 if(b.closest('form'))e.preventDefault();
 if(b.dataset.go)go(b.dataset.go);
 if(b.dataset.submit)submit(b.dataset.submit);
 if(b.dataset.task){if(dirty){pending='tasks';$('#leave').showModal();$('#discard').dataset.task=b.dataset.task;}else{task=b.dataset.task;render();}}
 if(b.hasAttribute('data-direct-request')){dirty=false;$('#content').innerHTML=`<form class="panel"><h2>申请直接准备合同</h2>${area('申请依据','directRequest','客户希望直接核对合同。')}${area('准确服务范围','scope','合作协议审核与协商支持，不含诉讼代理。')}${field('固定收费金额（元）','amount','20000','number')}${field('付款安排','terms','分两期付款')}${action('提交直接准备申请','direct',nextNote)}</form>`;}
 if(b.hasAttribute('data-ai')){ai=true;render();}
 if(b.hasAttribute('data-use-ai')){values.summary=$('#candidate').value;ai=false;dirty=true;render();$('#status').textContent='已人工确认至摘要，仍需保存本次进展。';}
 if(b.hasAttribute('data-ignore-ai')){ai=false;render();}
});
$('#stay').onclick=()=>{$('#leave').close();pending=null;delete $('#discard').dataset.task;};
$('#discard').onclick=()=>{const next=pending;pending=null;$('#leave').close();if($('#discard').dataset.task){task=$('#discard').dataset.task;delete $('#discard').dataset.task;}values={};go(next,true);};
window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
render();
