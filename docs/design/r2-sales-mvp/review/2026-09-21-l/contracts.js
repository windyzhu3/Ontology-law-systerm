// Synthetic design review only; no API, persistent storage, document or approval side effects.
'use strict';
const app=document.querySelector('#app'),dialog=document.querySelector('#dialog');
let scene=new URLSearchParams(location.search).get('scene')||'prepare',dirty=false,queue=false,selected=false,error='',next=null,formScene='prepare',lastDecision='';
const origin=scene,values={scope:'审阅采购协议，提供协商及法律咨询服务',fee:'固定服务费人民币 20,000 元',payment:'签署后支付 10,000 元；完成首阶段服务后支付余款',gate:'不要求到账后才转案',signer:'李明 · 法定代表人',reason:'',decision:'请选择本次决定',requirement:'双方签字并加盖公章；核对签字人权限'};
let saved={...values};
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const b=(text,action,primary=false)=>`<button class="${primary?'primary':'link-button'}" data-action="${action}">${text}</button>`;
const facts=rows=>`<dl class="detail-facts">${rows.map(([k,v])=>`<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>`;
const field=(label,key)=>`<label class="field"><span class="field-label">${label}</span>${["fee","signer"].includes(key)?`<input data-key="${key}" value="${esc(values[key])}">`:`<textarea data-key="${key}" rows="2">${esc(values[key])}</textarea>`}</label>`;
const select=(label,key,opts)=>`<label class="field"><span class="field-label">${label}</span><select data-key="${key}">${opts.map(x=>`<option${values[key]===x?' selected':''}>${esc(x)}</option>`).join('')}</select></label>`;
const icon=name=>`<img class="icon" src="../2026-09-14-e/assets/${name}.svg" alt="">`;
const terms=()=>facts([['委托主体','海宁实业有限公司'],['需求依据','已确认的客户资料及服务需求'],['服务范围',values.scope],['费用',values.fee],['付款安排',values.payment],['转案收款条件',values.gate],['签署人',values.signer],['签署要求',values.requirement]]);
const titles={prepare:'准备委托合同',direct:'申请直接准备合同',directReview:'核对直接准备授权',directGranted:'准备委托合同',preview:'核对合同正文',created:'合同版本已形成',conflict:'办理签约前冲突审查',conflictBlocked:'处理冲突阻断',approval:'审批委托合同',returned:'修订退回合同',approved:'合同审批已完成',waiting:'查看办理进展',missing:'补齐合同依据',stale:'核对依据变化',unknown:'核对原提交结果',unassigned:'查看责任安排',readonly:'查看合同历史',revoked:'当前事项不可办理',failed:'重新读取合同',loading:'读取当前事项',ledger:'合同台账',directReturned:'补充直接授权申请',supplement:'补充冲突审查资料',handoff:'查看后继安排',done:'本次结果已记录'};
function form(){return field('服务范围','scope')+field('费用条款','fee')+field('付款安排','payment')+select('合同约定的转案收款条件','gate',['不要求到账后才转案','合同明确要求先到账才转案'])+field('签署人及权限依据','signer')+'<details class="history"><summary>签署要求与版本依据</summary>'+field('签字、盖章与归档要求','requirement')+'<p>采用已审核模板及条款版本；正文变更必须形成新合同版本。</p></details>';}
function content(){
 if(['prepare','directGranted','returned'].includes(scene))return '<h2>填写本次合同内容</h2>'+(scene==='returned'?'<p class="feedback">退回意见：明确首阶段服务的交付标准。修订形成新版本，原批准不沿用。</p>':'')+facts([['准备来源',scene==='directGranted'?'本商机、范围及收费方案的准确授权':'已批准且被客户接受的报价版本'],['模板','标准委托合同 · 已审核版本']])+form()+b('形成这份合同版本','create',true)+'<div class="secondary-line">'+b('预览合同正文','preview')+b('保存草稿','save')+'</div>';
 if(['direct','directReturned'].includes(scene))return '<h2>说明本次直接准备的依据</h2>'+(scene==='directReturned'?'<p class="feedback">退回意见：补充收费依据及客户沟通说明。</p>':'')+'<p>保留正常报价路径。本次授权只允许准备准确范围的合同，不代替合同审批或客户签署。</p>'+field('服务范围','scope')+field('拟收费方案','fee')+field('申请原因与沟通依据','reason')+b('提交直接准备申请','requestDirect',true)+b('保存草稿','save');
 if(['directReview','approval','conflict'].includes(scene))return '<h2>核对准确依据并作出决定</h2>'+terms()+(scene==='conflict'?facts([['审查目的','签约前冲突审查'],['审查范围','本次委托方、相对方、关联方及服务事项'],['待核对事项','合成示例：同名关联方，需核对主体身份']])+'<p>仅显示本次审查有权查看的最小信息，不展示其他客户案件正文。</p>':b('查看合同内容与依据','document'))+select('本次决定','decision',scene==='conflict'?['请选择本次决定','审查通过','要求补充','阻断']:['请选择本次决定','批准','退回'])+field('决定说明','reason')+b('确认本次决定','decide',true);
 if(scene==='preview')return '<h2>委托合同正文预览</h2><div class="document-preview"><h3>法律服务委托合同</h3><p class="doc-meta">仅为高保真合成内容，不可用于签署</p>'+terms()+'<p>其他条款来自已审核模板，实际生成时绑定完整正文及内容摘要。</p></div>'+b('返回合同填写','returnForm',true);
 if(scene==='created')return '<h2>本次合同版本已形成</h2>'+facts([['当前版本','第 1 版'],['下一责任','销售提交签约前审查'],['当前状态','尚未批准，不可签署']])+b('提交签约前审查','submitReview',true)+b('查看准确正文','document');
 if(scene==='approved')return '<h2>合同审批已完成</h2>'+facts([['已批准','准确合同版本及正文'],['下一责任','销售准备签署材料'],['合同状态','尚未签署']])+'<p>签字件提交与核验属于后续责任；本次批准不代表合同已执行或案件已生成。</p>'+b('查看后继安排','handoff',true);
 if(scene==='waiting'||scene==='handoff'||scene==='done')return '<h2>'+ (scene==='handoff'?'结果已记录，后继正在同步':scene==='done'?'你的本次办理已完成':'正在等待有权人员办理')+'</h2>'+facts([['当前进展',scene==='handoff'?'正在核对后继责任，不重复提交':scene==='done'?lastDecision:'已提交，等待当前审查或授权责任'],['下一行动','由实际有权责任人继续；可从我的待办处理其他事项']])+b('查看我的待办','queue',true);
 const notices={conflictBlocked:['签约前审查被阻断','本版不能进入合同审批或签署。销售根据审查意见处理业务；不能自行解除阻断。'],missing:['缺少准备依据','客户主体或需求尚未确认；通过已有客户与材料入口补齐，不另建客户资料。'],stale:['办理依据已经变化','重新读取客户、来源及当前合同版本；旧批准和旧正文不能用于新版本。'],unknown:['原操作结果尚未确认','核对原命令回执，不生成新命令重提合同。核对期间禁止切换业务或重复提交。'],unassigned:['暂时没有合格责任人','主管收到责任异常；原期限和业务来源保留，安排后继续原链路。'],revoked:['当前权限已经变化','客户、合同正文和可办理操作已清除。'],failed:['当前事项读取失败','本次没有提交业务结果，可以重新读取。'],loading:['正在读取当前事项','请稍候。'],supplement:['补充审查所需材料','请补充关联方身份说明，并逐项回应审查意见。上传不等于审查通过。']};
 if(notices[scene]){const [title,note]=notices[scene];return '<h2>'+title+'</h2><p role="status">'+note+'</p>'+(scene==='supplement'?field('本次补充说明','reason')+b('提交补充资料','supplementSubmit',true):scene==='unknown'?b('核对原结果','resolve',true):scene==='loading'?'':scene==='stale'||scene==='failed'?b('重新读取','reload',true):b('查看我的待办','queue',true));}
 return '<h2>合同版本与业务记录</h2>'+terms()+'<details class="history"><summary>历史与准确版本</summary><p>保留准备来源、正文、审批和退回记录；只读权限不允许改状态。</p></details>';
}
function render(){
 const role=origin==='approval'?'张敏 · 合同审批人':origin==='directReview'?'刘敏 · 授权人':origin==='conflict'?'周宁 · 冲突审查人':'林悦 · 客户顾问';
 const header=`<header class="app-header"><div class="brand">${icon('Scales-green')}律所工作助手</div><div class="session">${b(scene==='ledger'?'工作台':'业务管理',scene==='ledger'?'prepare':'ledger')}<span class="role-label">${role}</span></div></header>`;
 if(scene==='ledger'){app.innerHTML=header+`<div class="admin-shell"><nav class="sidebar" aria-label="业务管理"><h2>业务管理</h2><p>商机与合同</p><p>合同台账</p></nav><main id="main" class="admin-main"><h1>合同台账</h1><p>查看当前有权合同；业务办理仍回到工作卡。</p><div class="ledger-split"><section class="list-pane"><label class="field"><span class="field-label">搜索客户</span><input id="search" placeholder="客户名称"></label><table class="record-table"><thead><tr><th>客户与合同</th><th>当前事项</th></tr></thead><tbody id="rows"><tr><td>${b('海宁实业有限公司','select')}<small>委托合同 · 第 1 版</small></td><td>准备合同 · 林悦</td></tr></tbody></table></section><section class="detail-pane">${selected?'<h2>海宁实业有限公司</h2>'+facts([['当前事项','准备委托合同'],['负责人','林悦'],['期限','今天 17:00 前']])+b('前往办理','prepare',true)+b('查看合同历史','readonly'):'<p>选择一条记录查看合同详情。</p>'}</section></div></main></div>`;return;}
 app.innerHTML=header+`<main id="main" class="workbench" tabindex="-1"><div class="today"><p class="today-copy">${icon('CheckCircle')}<span>按当前责任办理，提交后查看下一步安排。</span></p><button class="task-switch" data-action="queue" ${scene==='unknown'?'disabled':''}>我的待办</button></div>${queue?'<section class="queue"><h2>我的待办</h2><p>仅展示本人有权事项，不切换成其他审批角色。</p>'+b('收起待办','queue')+b('海宁公司 · 当前合同事项','resume')+b('明川公司 · 查看已确认客户需求','other')+'</section>':''}<article class="work-card"><div class="context"><p class="eyebrow">当前责任</p><h1>${titles[scene]||'查看当前事项'}</h1>${scene==='revoked'?'':`<p class="subject">海宁实业有限公司</p><p class="owner-line">${icon('User')}<span>${role}</span><span>· 今天 17:00 前</span></p><p class="known-title">已知信息</p><ul class="facts"><li>${icon('Briefcase')}<span>来源：有效首联与当前商机</span></li><li>${icon('CheckCircle')}<span>客户资料与服务需求已确认</span></li></ul><details class="history"><summary>客户与材料依据</summary><p>引用已有客户确认及已接收材料，不复制第二套客户信息。</p></details>`}</div><section class="card-action">${content()}${error?'<p role="alert">'+esc(error)+'</p>':''}</section></article></main>`;
}
function go(target){scene=target;error='';queue=false;render();}
function guard(action){if(scene==='unknown'){error='请先核对原提交结果。';render();return;}if(!dirty){action();return;}next=action;document.querySelector('#dialog-body').innerHTML='<h2>保留当前填写内容？</h2><p>保存草稿不形成合同或审批决定。</p><div class="dialog-actions">'+b('继续填写','stay',true)+b('保存草稿并切换','saveLeave')+b('放弃填写并切换','discard')+'</div>';dialog.showModal();}
document.addEventListener('input',e=>{if(e.target.dataset.key){values[e.target.dataset.key]=e.target.value;dirty=true;}if(e.target.id==='search'){selected=false;document.querySelector('.detail-pane').innerHTML='<p>选择一条记录查看合同详情。</p>';document.querySelector('#rows').hidden=!('海宁实业有限公司'.includes(e.target.value));}});
document.addEventListener('click',e=>{const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='stay'){dialog.close();next=null;return;}if(a==='saveLeave'||a==='discard'){if(a==='saveLeave')saved={...values};else Object.assign(values,saved);dirty=false;dialog.close();const run=next;next=null;run?.();return;}
 if(a==='save'){saved={...values};dirty=false;error='草稿已保存（本次审阅内存），尚未提交业务。';render();return;}
 if(a==='queue'){queue=!queue;render();return;}if(a==='resume'){queue=false;render();return;}
 if(a==='other'){guard(()=>{document.querySelector('#dialog-body').innerHTML='<h2>明川公司 · 已确认客户需求</h2><p>合成的另一项本人有权资料；不改变当前合同责任。</p>'+b('返回当前事项','stay',true);dialog.showModal();});return;}
 if(a==='select'){selected=true;render();return;}
 if(a==='document'){document.querySelector('#dialog-body').innerHTML='<h2>准确合同内容</h2>'+terms()+b('返回当前事项','stay',true);dialog.showModal();return;}
 if(a==='preview'){formScene=scene;go('preview');return;}if(a==='returnForm'){go(formScene);return;}
 if(['create','requestDirect','decide','supplementSubmit'].includes(a)){
  if((a==='create'&&(!values.scope.trim()||!values.fee.trim()||!values.payment.trim()||!values.signer.trim()))||(['requestDirect','decide','supplementSubmit'].includes(a)&&!values.reason.trim())||(a==='decide'&&values.decision==='请选择本次决定')){error='请补齐本次必要内容；决定与说明不能留空。';render();return;}
  lastDecision=values.decision==='退回'?'退回已记录，销售收到补充或修订责任':values.decision==='阻断'?'阻断已记录，不能进入签署':values.decision==='要求补充'?'补充要求已记录，销售收到补充责任':'本次批准已记录，等待全部所需条件齐备后继续';dirty=false;saved={...values};go(a==='create'?'created':a==='decide'?'done':'waiting');return;
 }
 if(a==='submitReview'){dirty=false;go('waiting');return;}if(a==='resolve'){go('handoff');return;}if(a==='reload'){dirty=false;go('readonly');return;}
 guard(()=>go(a));
});
render();
