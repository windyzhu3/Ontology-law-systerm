// Synthetic review only. No API, file, storage, approval or delivery side effects.
const scenes=['entry','prepare','draft','review','created','submit','pending','approval','returned','authorized','delivery','deliveryReview','issued','response','responseReview','accepted','deferred','rejected','ambiguous','revise','history','missing','expired','unassigned','stale','unknown','readonly','closed','revoked','failed','loading','handoff','waiting','withinAuthority','partialApproval'];
let scene=new URLSearchParams(location.search).get('scene')||'entry';if(!scenes.includes(scene))scene='entry';
const origin=scene,app=document.querySelector('#app');
let revision=scene==='revise'?2:1,dirty=false,leaving=false,error='',evidence=false,prior='prepare',query='',filter='all',queue=false;
const values={scope:'核对设备采购争议事实，提供沟通与代理服务方案',line:'服务费用',amount:'20000',fee:'固定收费',payment:'签署后支付 10000 元；完成约定首阶段服务后支付 10000 元',valid:'2026-10-20',recipient:'陈女士（本次客户授权联系人）',channel:'当面交付',delivered:'2026-09-20T10:00',decision:'批准',reason:'',reply:scene==='responseReview'?'接受':'请选择客户回复',replyNote:'客户已核对本次报价范围和付款安排，表示接受。',next:'2026-09-23T10:00', extraLine:'明确折扣', extraAmount:'-1000', feeCondition:'实际实现约定回款目标后，按实际回款金额计算', feeRate:'5', feeCap:'50000'};
let extra=false;
const total=()=>Number(values.amount)+(extra?Number(values.extraAmount):0);
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=n=>`<img class="icon" src="../2026-09-14-e/assets/${n}.svg" alt="" aria-hidden="true">`;
const button=(label,action,primary=false,disabled=false)=>`<button data-action="${action}"${primary?' class="primary"':''}${disabled?' disabled':''}>${label}</button>`;
const facts=rows=>`<dl class="detail-facts">${rows.map(([k,v])=>`<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>`;
const field=(label,key,type='text')=>`<label class="field">${label}<span class="required"> *</span>${type==='textarea'?`<textarea data-key="${key}">${esc(values[key])}</textarea>`:`<input data-key="${key}" type="${type}" value="${esc(values[key])}">`}</label>`;
const select=(label,key,options)=>`<label class="field">${label}<select data-key="${key}">${options.map(x=>`<option${x===values[key]?' selected':''}>${esc(x)}</option>`).join('')}</select></label>`;
const actions=(label,action,secondary='返回商机台账',back='back')=>`<div class="action-area">${button(label,action,true)}<div class="secondary-line">${button(secondary,back)}</div></div>`;
function summary(){return facts([['客户','海宁实业有限公司'],['报价','第 '+revision+' 版'],['服务范围',values.scope],['收费方式',values.fee],['报价明细',values.line+' · ¥'+Number(values.amount).toLocaleString('zh-CN')+(extra?'；'+values.extraLine+' · ¥'+values.extraAmount:'')],['固定金额合计','¥'+total().toLocaleString('zh-CN')],...(values.fee==='含结果条件的收费'?[['条件性费用',values.feeCondition+'；费率 '+values.feeRate+'%；上限 ¥'+values.feeCap]]:[]),['付款安排',values.payment],['有效期至',values.valid]]);}
const titles={prepare:'准备收费方案',draft:'准备收费方案',revise:'修订报价',review:'确认报价版本',submit:'提交报价审批',pending:'查看报价审批进展',approval:'记录报价授权决定',authorized:'办理报价交付',delivery:'记录报价交付',deliveryReview:'确认报价交付',issued:'跟进客户回复',response:'记录客户报价回复',responseReview:'确认客户报价回复',accepted:'衔接合同准备',deferred:'按约定继续跟进',ambiguous:'核实客户报价回复',rejected:'处理报价拒绝',withinAuthority:'确认权限内报价授权',partialApproval:'等待其余授权决定'};
function content(){
 if(scene==='withinAuthority')return '<h2>核对本次权限内授权</h2>'+summary()+facts([['授权依据','本人当前任职具备本版报价范围的具名授权'],['下一事项','报价交付']])+'<p>授权判断由服务端具名策略执行；不能因金额低或本人是销售就默认获权。</p>'+actions('确认本版权限内授权','authorized');
 if(scene==='partialApproval')return '<h2>报价仍等待其余批准</h2>'+facts([['已记录','本位审批人的准确决定'],['仍待处理','另一位独立有权审批人'],['可否交付','尚不可交付']])+'<p>需多位审批人时，缺少任何一项有效决定都不会生成交付责任。</p>'+actions('返回我的待办','queue');
 if(leaving)return '<h2>保留当前填写内容？</h2><p>尚未提交的内容不会形成报价或业务决定。</p>'+actions('继续填写','stay','放弃本次输入并返回','discard');
 if(scene==='loading')return '<h2>正在读取本次报价</h2><p role="status">请稍候…</p>'+button('模拟读取完成','entry');
 if(scene==='revoked')return '<h2>当前无法办理本次报价</h2><p>权限已变化，客户资料、金额与候选操作已清除。</p>'+actions('返回有权事项','queue');
 if(scene==='failed')return '<h2>报价读取失败</h2><p>没有提交业务操作。请重新读取。</p>'+actions('重新读取','entry');
 if(scene==='stale')return '<h2>本次办理依据已变化</h2><p>客户资料、服务范围、负责人或报价版本已变化。旧批准不能用于新版本。</p>'+actions('重新读取并核对','prepare');
 if(scene==='unknown')return '<h2>本次提交结果尚未确认</h2><p>请核对原操作结果，不重新保存版本、交付或记录回复。</p>'+facts([['原操作','提交本次报价审批'],['当前状态','结果核对中']])+actions('核对本次结果','resolve','查看原报价','original');
 if(scene==='missing')return '<h2>先确认客户与需求</h2><p>报价必须引用准确委托方和服务范围。现有材料可以保留，但不能代替客户资料确认。</p>'+actions('查看需补齐资料','customer');
 if(scene==='unassigned')return '<h2>报价等待安排审批人</h2><p>当前没有符合本次范围的审批任职。主管收到异常事项；未授权的报价不能交付。</p>'+facts([['下一责任','主管安排审批人'],['销售可做','继续有权客户沟通，查看处理进展']])+actions('查看处理进展','pending');
 if(scene==='expired')return '<h2>本次报价已过有效期</h2><p>旧版本不能继续作为新的交付或接受依据。需要继续时，修订为新版本并重新核对授权。</p>'+summary()+actions('准备新版本','revise');
 if(scene==='handoff')return '<h2>客户接受已记录</h2><p role="status">正在衔接下一项责任。请勿再次记录接受。</p>'+facts([['下一事项','合同准备'],['接续状态','正在同步；失败时进入主管异常处理']])+actions('查看接续状态','accepted');
 if(scene==='history')return '<h2>查看报价历史</h2>'+summary()+'<p>报价旧版本及其批准、交付、客户回复均保留准确引用。新版本不会覆盖历史。</p><details class="history"><summary>本版业务记录</summary><p>林悦准备 → 张敏批准 → 人工交付留证 → 客户回复。</p><p>以上仅为当前高保真合成场景，不是系统真实记录。</p></details>'+actions('返回当前报价','entry');
 if(scene==='readonly'||scene==='closed')return '<h2>'+(scene==='closed'?'商机已结束':'本次报价只可查看')+'</h2>'+summary()+'<p>保留历史，不允许创建新报价、交付或记录客户回复。</p>'+button('查看历史版本','history');
 if(['prepare','draft','revise'].includes(scene))return '<h2>'+ (scene==='revise'?'修订本次收费方案':'准备本次收费方案')+'</h2>'+(scene==='draft'?'<p role="status">已恢复保存的草稿，尚未提交报价。</p>':'')+(scene==='revise'?'<p class="feedback">将形成第 2 版。原报价、批准及客户回复仍属于第 1 版。</p>':'')+field('拟服务范围','scope','textarea')+select('收费方式','fee',['固定收费','含结果条件的收费'])+field('收费项目','line')+field('本项金额（元）','amount','number')+'<details class="history"><summary>更多计价行与折扣</summary>'+(extra?field('追加项目或折扣说明','extraLine')+field('追加金额（元，折扣填负数）','extraAmount','number')+button('移除追加行','removeLine'):button('添加计价或折扣行','addLine'))+'</details>'+(values.fee==='含结果条件的收费'?''+field('计费触发条件与计算依据','feeCondition','textarea')+field('约定费率（%）','feeRate','number')+field('条件性费用上限（元）','feeCap','number')+'<p class="help">条件性费用与固定金额分开核对，不能当作已确定应付金额；仍需有权审批。</p>':'')+'<details class="history"><summary>付款安排与报价有效期</summary>'+field('付款与费用条件说明','payment','textarea')+field('有效期至','valid','date')+'</details>'+actions('核对报价内容','review','保存草稿','save');
 if(scene==='review')return '<h2>核对本次报价版本</h2>'+summary()+'<p class="help">形成报价版本不代表已批准、已发给客户或已被接受。</p>'+actions('确认形成报价版本','created','返回修改','prepare');
 if(scene==='created')return '<h2>报价第 '+revision+' 版已形成</h2>'+facts([['已完成','本版收费方案准备'],['下一事项','提交本版报价审批'],['负责人','林悦']])+actions('继续提交报价审批','submit','回到我的待办','queue');
 if(scene==='submit')return '<h2>提交本版报价审批</h2>'+summary()+facts([['审批范围','本版服务范围、费用与付款条件'],['审批人','张敏 · 有权审批任职']])+actions('提交这份报价审批','pending');
 if(scene==='pending')return '<h2>报价等待审批</h2>'+facts([['报价','第 '+revision+' 版'],['当前责任人','张敏 · 有权审批任职'],['下一行动','审批人核对并作出决定'],['销售原任务','审批提交已完成，不重复挂为待提交']])+'<p>当前销售不能代审批。可继续其他有权待办。</p>'+actions('返回我的待办','queue');
 if(scene==='approval')return '<h2>核对并作出报价决定</h2>'+summary()+select('本次决定','decision',['批准','退回修改'])+field('决定说明','reason','textarea')+'<p class="help">只对本次版本和授权范围作出决定。需多位审批人时，全部满足后才可交付。</p>'+actions('确认本次报价决定','decide');
 if(scene==='returned')return '<h2>报价已退回修改</h2>'+facts([['退回说明',values.reason||'请明确分阶段服务范围和付款条件'],['下一责任','林悦修订报价'],['原版状态','保留，不可当作已获批准']])+actions('继续修订报价','revise');
 if(scene==='authorized')return '<h2>本版报价已获授权</h2>'+facts([['可交付版本','第 '+revision+' 版'],['下一事项','将准确版本交付给客户并留证'],['责任人','林悦']])+actions('继续办理报价交付','delivery');
 if(scene==='delivery')return '<h2>记录本次人工交付</h2>'+facts([['报价版本','第 '+revision+' 版 · 已获授权']])+field('实际接收人','recipient')+select('交付方式','channel',['当面交付','经人工确认的电子交付'])+field('实际交付时间','delivered','datetime-local')+'<p class="help">先实际完成交付，再选取准确版本的交付证明；下载报价不等于交付。</p>'+button(evidence?'已选：交付确认记录.png':'选取本次交付证明','evidence')+'<details class="history"><summary>查看将交付的报价</summary>'+summary()+button('查看合成报价文件说明','document')+'</details>'+actions('核对交付记录','deliveryReview');
 if(scene==='deliveryReview')return '<h2>核对交付事实</h2>'+facts([['报价','第 '+revision+' 版'],['实际接收人',values.recipient],['交付方式',values.channel],['交付时间',values.delivered],['证明','交付确认记录.png · 准确材料版本']])+'<p>人工确认的是已发生的交付，不触发系统再次发送。</p>'+actions('确认本次报价已交付','issued','返回修改','delivery');
 if(scene==='issued')return '<h2>报价已交付</h2>'+facts([['已完成','本版报价交付'],['下一事项','跟进并记录客户回复'],['责任人','林悦']])+actions('继续记录客户回复','response');
 if(scene==='response')return '<h2>客户如何回复本版报价？</h2>'+facts([['报价','第 '+revision+' 版'],['已交付给',values.recipient]])+select('客户回复','reply',['请选择客户回复','接受','暂不接受','明确拒绝','回复不明确'])+field('客户原意与核对说明','replyNote','textarea')+button(evidence?'已选：客户回复记录.png':'选取本次客户回复证据','evidence')+(values.reply!=='接受'?field('下次跟进时间','next','datetime-local'):'')+'<p class="help">只记录客户实际回复；沉默、发送成功、上传凭证都不代表接受。</p>'+actions('核对本次客户回复','responseReview');
 if(scene==='responseReview')return '<h2>核对本次客户回复</h2>'+facts([['报价','第 '+revision+' 版'],['客户回复',values.reply],['原意说明',values.replyNote],['证据','客户回复记录.png · 准确材料版本'],['后续',values.reply==='接受'?'衔接合同准备；仍须冲突审查、合同审批和签署':'创建销售后续处置或约定跟进事项']])+actions('确认记录客户回复','replyConfirmed','返回修改','response');
 if(scene==='accepted')return '<h2>客户接受已记录</h2>'+facts([['接受依据','准确报价版本、交付记录及客户回复证据'],['下一责任','合同准备'],['责任人','林悦']])+'<p>报价接受不等于合同签署、成交或案件成立。</p>'+actions('查看合同准备接续','contract','返回我的待办','queue');
 if(scene==='deferred'||scene==='ambiguous')return '<h2>'+(scene==='deferred'?'客户暂不接受':'客户回复仍需核实')+'</h2>'+facts([['原回复任务','已记录本次回复并完成'],['下一责任',scene==='deferred'?'按约定再次沟通':'核实客户对本版报价的明确意见'],['下次时间',values.next],['负责人','林悦']])+'<p>到期由待办调度继续，不依赖销售一直打开页面。</p>'+actions('返回我的待办','queue');
 if(scene==='rejected')return '<h2>客户明确拒绝本版报价</h2>'+facts([['下一责任','销售确定继续方案或结束商机'],['原报价','拒绝事实保留，不自动改为失单']])+actions('修订报价后继续沟通','revise','查看结束商机入口','closure');
 if(scene==='waiting')return '<h2>普通跟进仍按原约定等待</h2><p>保存收费方案草稿不会提前唤醒跟进或改变原期限。正式提交报价时，后继责任必须明确衔接，不重复创建普通跟进。</p>'+facts([['原约定','9 月 23 日 10:00'],['报价准备','可保存草稿；是否进入正式报价由本人确认']])+actions('准备报价草稿','prepare');
 return '';
}
function render(){
 const redacted=scene==='revoked',locked=scene==='unknown';
 const header=`<header class="app-header"><div class="brand">${icon('Scales-green')}律所工作助手</div><div class="session">${button('返回商机台账','back',false,locked)}<span class="role-label">${scene==='approval'?'张敏 · 报价审批':'林悦 · 销售'}</span><span class="avatar">${scene==='approval'?'张':'林'}</span></div></header>`;
 if(queue){app.innerHTML=header+`<main id="main" class="workbench"><div class="queue"><h2>我的待办</h2><p>当前只列本人有权办理的事项；切换不修改责任和时限。</p>${button('海宁公司 · '+(scene==='accepted'?'合同准备接续':scene==='pending'?'查看报价审批进展':'查看报价下一步'),'resume',true)}${button('其他客户 · 记录联系结果','other')}</div></main>`;return;}
 if(scene==='entry'){
 const match=!query||'海宁公司'.includes(query),shown=match&&filter==='all';
 app.innerHTML=header+`<div class="admin-shell"><aside class="sidebar"><p>业务管理</p><button class="active" aria-current="page">${icon('Briefcase')} 商机台账</button>${button('我的待办','queue')}</aside><main id="main" class="admin-main"><div class="admin-heading"><div><h1>商机台账</h1><p>查看有权业务记录，接着办理当前事项。</p></div></div><div class="ledger-split"><section class="list-pane"><div class="toolbar"><label class="search"><span class="sr-only">搜索客户</span><input id="query" placeholder="搜索客户" value="${esc(query)}"></label><label><span class="sr-only">筛选状态</span><select id="filter"><option value="all">全部状态</option><option value="closed"${filter==='closed'?' selected':''}>已结束</option></select></label></div><table class="record-table"><thead><tr><th>客户</th><th>当前事项</th><th>负责人</th></tr></thead><tbody>${shown?'<tr class="selected"><td>'+button('海宁公司','focus')+'</td><td data-label="当前事项">准备收费方案</td><td data-label="负责人">林悦</td></tr>':''}</tbody></table>${shown?'':'<p>没有匹配的记录，请调整筛选。</p>'}<p class="list-foot">仅显示当前有权查看的商机。</p></section><section class="detail-pane" id="detail" tabindex="-1">${shown?'<h2>海宁公司</h2>'+facts([['已确认','客户主体、需求与服务范围'],['业务材料','采购合同及往来记录已接收'],['下一事项','准备本次收费方案']])+actions('办理本次报价准备','prepare','回到我的待办','queue')+'<details class="history"><summary>报价与客户回复</summary>'+summary()+button('查看版本与记录','history')+'</details><details class="history"><summary>客户资料与业务材料</summary>'+button('查看已确认客户资料','customer')+button('查看业务材料','materials')+'</details>':'<p>暂无选中记录</p>'}</section></div></main></div>`;return;
 }
 app.innerHTML=header+`<main id="main" class="workbench"><div class="today"><p class="today-copy">${icon('CheckCircle')}<span>${locked?'核对原操作结果，避免重复提交。':'完成当前事项后，按明确的下一责任继续办理。'}</span></p>${button('我的待办','queue',false,locked)}</div><article class="work-card" aria-label="收费方案与报价工作卡"><div class="context"><p class="eyebrow">当前责任</p><h1>${titles[scene]||'核对本次报价状态'}</h1>${redacted?'<p>当前业务内容已清除。</p>':`<p class="subject">海宁公司 · 设备采购争议</p><p class="owner-line">${icon('User')}<span>${scene==='approval'?'张敏':'林悦'}负责</span></p><p class="known-title">办理依据</p><ul class="facts"><li>${icon('CheckCircle')}<span>已确认客户：海宁实业有限公司</span></li><li>${icon('Briefcase')}<span>来源：有效首联与本次商机</span></li><li>${icon('Clock')}<span>本次报价第 ${revision} 版 · 当前事项期限 9 月 21 日 17:00</span></li></ul><details class="history"><summary>客户资料与材料依据</summary><p>准确客户需求版本；已接收采购合同和往来记录。修订后不得静默替换已批准报价的依据。</p></details><p class="context-note">业务分类在案管接收形成案件后进行；此处不选择综法或执行类别。</p>`}</div><section class="card-action">${content()}${error?`<p role="${error.startsWith('审阅说明')?'status':'alert'}">${esc(error)}</p>`:''}</section></article></main>`;
}
function go(next){scene=next;error='';render();document.querySelector('h2')?.scrollIntoView({block:'nearest'});}
app.addEventListener('input',e=>{if(e.target.dataset.key){values[e.target.dataset.key]=e.target.value;dirty=true;}if(e.target.id==='query'){query=e.target.value;render();const el=document.querySelector('#query');el?.focus();el?.setSelectionRange(query.length,query.length);}});
app.addEventListener('change',e=>{if(e.target.dataset.key){values[e.target.dataset.key]=e.target.value;dirty=true;if(['reply','fee'].includes(e.target.dataset.key))render();}if(e.target.id==='filter'){filter=e.target.value;render();}});
app.addEventListener('click',e=>{
 const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='back'||a==='queue'){if(scene==='unknown')return;if(dirty){leaving=true;render();return;}queue=a==='queue';if(!queue)scene='entry';render();return;}
 if(a==='stay'){leaving=false;render();return;}
 if(a==='discard'){dirty=false;leaving=false;queue=false;go('entry');return;}
 if(a==='resume'){queue=false;render();return;}
 if(a==='other'){error='审阅说明：此稿聚焦 T07；正式系统沿用“我的待办”有权切换能力。';queue=false;go('entry');return;}
 if(a==='focus'){document.querySelector('#detail')?.focus();return;}
 if(['customer','materials','contract','closure','document','original'].includes(a)){error='审阅说明：'+({customer:'复用已冻结 I2 客户资料页面。未确认时先补齐，报价不建立第二个客户真源。',materials:'复用 J 材料页；本稿不真实上传或下载。',contract:'T07 输出准确报价接受依据；合同准备由后续任务实现，仍需独立冲突审查、合同审批和签署。',closure:'复用已有结束商机流程；客户拒绝报价不会自动关闭商机。',document:'当前为合成报价内容，无真实文件；正式实现须提供与准确报价版本一致的文件。',original:'原提交绑定本版报价，当前只核对原结果，不新建或重发。'})[a];render();return;}
 if(a==='addLine'||a==='removeLine'){extra=a==='addLine';dirty=true;render();return;}
 if(a==='evidence'){evidence=true;dirty=true;render();return;}
 if(a==='review'&&(!values.scope.trim()||!values.line.trim()||!Number.isFinite(Number(values.amount))||!values.amount.trim()||Number(values.amount)<0||!Number.isFinite(total())||total()<0||(extra&&!values.extraLine.trim())||(values.fee==='含结果条件的收费'&&(!values.feeCondition.trim()||Number(values.feeRate)<=0||Number(values.feeRate)>100||Number(values.feeCap)<=0))||!values.payment.trim()||!values.valid)){error='请补齐服务范围、收费项目、有效金额、付款安排和有效期。';render();return;}
 if(a==='deliveryReview'&&(!evidence||!values.recipient.trim()||!values.delivered)){error='请填写实际接收人、交付时间，并选取本次交付证明。';render();return;}
 if(a==='responseReview'&&(!['接受','暂不接受','明确拒绝','回复不明确'].includes(values.reply)||!evidence||!values.replyNote.trim()||(values.reply!=='接受'&&!values.next))){error='请填写客户原意、选取回复证据，并为继续跟进约定时间。';render();return;}
 if(a==='decide'){if(!values.reason.trim()){error='请填写本次决定说明。';render();return;}dirty=false;go(values.decision==='批准'?'authorized':'returned');return;}
 if(a==='replyConfirmed'){dirty=false;go({'接受':'accepted','暂不接受':'deferred','明确拒绝':'rejected','回复不明确':'ambiguous'}[values.reply]);return;}
 if(a==='save'){dirty=false;go('draft');return;}
 if(a==='resolve'){dirty=false;go('pending');return;}
 if(a==='revise'){revision=2;evidence=false;dirty=false;go('revise');return;}
 if(['created','pending','issued','accepted','authorized'].includes(a))dirty=false;
 if(a==='response'||a==='delivery')evidence=false;
 if(scenes.includes(a)){prior=scene;go(a);}
});
render();
