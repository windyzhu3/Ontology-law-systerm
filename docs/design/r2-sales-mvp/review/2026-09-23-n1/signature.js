// Local design review only. No API, storage, real document or business mutation.
'use strict';
const app=document.querySelector('#app'),dialog=document.querySelector('#dialog');
let scene=new URLSearchParams(location.search).get('scene')||'prepare',dirty=false,queue=false,selected=false,error='',saved=false;
const origin=scene;
const titles={prepare:'核对本版签署安排',submit:'提交客户签字件',scanning:'等待材料检查',waiting:'查看签署核验进度',review:'核验客户签署',supplement:'补充本版签署材料',changed:'处理签署内容变化',partial:'完成剩余签署事项',archive:'核对签署归档',complete:'签署核验已完成',unknown:'核对原提交结果',unassigned:'查看核验责任安排',revoked:'当前事项不可办理',readonly:'查看签署记录'};
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=n=>`<img class="icon" src="../2026-09-14-e/assets/${n}.svg" alt="">`;
const btn=(label,action,primary=false,disabled=false)=>`<button type="button" class="${primary?'primary':'link-button'}" data-action="${action}" ${disabled?'disabled':''}>${label}</button>`;
const tasks=()=>`<button class="workbench-control" data-action="tasks" ${scene==='unknown'?'disabled':''}>${icon('ListChecks')}<span>我的待办（3）</span></button>`;
const actions=(main,action,extra='')=>`<div class="ledger-detail-actions">${btn(main,action,true)}${extra}</div>`;
const facts=rows=>`<dl class="detail-facts">${rows.map(([k,v])=>`<div><dt>${k}</dt><dd>${v}</dd></div>`).join('')}</dl>`;
const check=(text,id)=>`<label class="checked-label contract-checkbox"><input id="${id}" type="checkbox">${text}</label>`;
const note=label=>`<label class="field"><span class="field-label">${label}</span><textarea id="note" rows="3"></textarea></label>`;
const version=()=>facts([['准确批准版本','海宁公司委托合同 · 第 1 版'],['委托主体','海宁实业有限公司'],['签署要求','客户授权代表签字、双方盖章并归档完整签字件']])+btn('查看批准合同正文','document');
const materials=()=>facts([['签字件','海宁公司合同签字件.pdf · 已检查'],['代表权限依据','授权委托书.pdf · 已检查']])+btn('查看本次签字件及权限依据','evidence');
const upload=()=>`<label class="field"><span class="field-label">签字件（复用材料上传与检查）</span><input type="file" aria-label="签字件" accept=".pdf,.png,.jpg,.jpeg"></label><label class="field"><span class="field-label">代表权限依据</span><select aria-label="代表权限依据"><option>请选择已确认材料</option><option>授权委托书.pdf</option></select></label><p>检查通过后再提交；上传本身不会确认签署。</p>`;
function content(){
 if(scene==='reviewed'||scene==='returnedEvidence')return '<h2>本次核验结果已记录</h2>'+facts([['本次结果',scene==='reviewed'?'本次签署核验通过':'同版本补充材料'],['下一责任','林悦 · 销售'],['后续事项',scene==='reviewed'?'完成剩余用印与归档要求':'补充清晰完整文件后重新提交核验']])+'<p>当前核验人不代替销售补证。可从我的待办处理其他有权事项。</p>'+btn('查看本次处理记录','history');

 if(scene==='prepare')return '<h2>按批准条款核对签署安排</h2>'+version()+facts([['委托方','海宁实业有限公司 · 授权代表签字并盖章'],['受托方','本律所 · 按批准要求用印'],['归档要求','合同正文、签字页及授权依据齐全']])+check('以上安排与本版批准合同一致，不改变主体及条款','confirmed')+actions('确认并准备签字材料','prepare-submit',btn('查看历史依据','history'));
 if(['submit','supplement'].includes(scene))return '<h2>提交当前版本的完整材料</h2>'+(scene==='supplement'?'<p class="feedback">核验意见：第 4 页不清晰，请补充清晰完整文件。原文件和意见保留。</p>':'')+version()+upload()+'<label class="field"><span class="field-label">实际签署日期</span><input id="signed-at" type="date"></label>'+note(scene==='supplement'?'本次补充说明':'签署情况说明')+check('已核对签字件对应本版批准合同','confirmed')+actions('提交签署核验','submit',btn('保存草稿','save'));
 if(scene==='review')return '<h2>核对证据后记录结论</h2>'+version()+materials()+check('签署正文与批准版本一致','body-ok')+check('签署人身份及代表权限有效','authority-ok')+check('本次签字、盖章及文件完整性符合要求','evidence-ok')+'<label class="field"><span class="field-label">核验结果</span><select id="decision"><option value="">请选择</option><option value="pass">本次签署核验通过</option><option value="supplement">同版本补充材料</option><option value="changed">正文或主体变化，返回合同修订</option></select></label>'+note('核验说明')+actions('记录本次核验结果','decide');
 if(scene==='partial')return '<h2>已完成事项保留，继续剩余要求</h2>'+facts([['客户签署','已核验通过 · 第 1 版'],['律所用印','尚未提交完整证据'],['完整归档','等待剩余签署要求完成']])+actions('补充剩余签署材料','remaining');
 if(scene==='archive')return '<h2>核对本版完整签署归档</h2>'+version()+facts([['客户签字及盖章','已核验通过'],['律所用印','已核验通过'],['完整签字件','海宁公司合同完整归档.pdf · 已检查']])+btn('查看完整归档材料','evidence')+check('已核对正文、全部签字盖章及权限依据归档完整','confirmed')+note('归档核对说明')+actions('确认完整归档','archive');
 if(scene==='complete')return '<h2>本版签署要求已完成</h2>'+facts([['签署','全部必需签署及用印已核验'],['归档','完整材料已确认'],['后续事项','合同执行条件核验'],['收款要求','按本合同约定核对，不将签署完成当作到账']])+'<p>已形成可追溯的后续交接。当前不代表合同执行条件全部满足或已转案。</p>'+actions('查看后续安排','handoff');
 if(scene==='changed')return '<h2>先处理合同版本变化</h2><p class="feedback">核验发现服务范围与批准版本不一致。该材料不能作为旧版签署通过依据。</p>'+facts([['需处理事项','修改合同正文并形成新版本'],['审查及审批','对新版本重新办理'],['原记录','保留原批准、材料及核验意见']])+(origin==='review'?'<p>已转交销售返回合同修订，原批准不会沿用到新版本。</p>':actions('返回合同修订','revise'));
 if(scene==='scanning')return '<h2>材料正在检查</h2><p role="status">签字件已上传，尚未检查完成。保留本次上传记录，完成后继续提交。</p>'+actions('查询材料检查结果','scan');
 if(scene==='waiting')return '<h2>签署证据已提交</h2>'+facts([['当前责任','赵欣 · 授权签署核验人'],['待办事项','核验当前版本的签署证据'],['状态','待核验，尚未确认签署完成']])+'<p>可从我的待办继续其他有权事项。</p>'+btn('查看本次提交记录','history');
 if(scene==='unknown')return '<h2>先确认原操作结果</h2><p role="status">本次提交的结果尚未确认。核对原回执后再继续，不能重复提交或覆盖材料。</p>'+actions('核对原结果','recover');
 if(scene==='unassigned')return '<h2>核验责任正在安排</h2><p>当前暂无合格核验人，已交有权主管协调。签字材料保留，原期限不会因重新安排而重置。</p>'+btn('查看责任协调记录','history');
 if(scene==='revoked')return '<h2>当前权限已变化</h2><p>客户、合同和材料内容已清除，请从我的待办重新选择有权事项。</p>'+actions('查看我的待办','tasks');
 if(scene==='readonly')return '<h2>本版签署记录</h2>'+version()+facts([['提交人','林悦 · 销售'],['核验人','赵欣 · 授权核验人'],['签署与归档','本版已完成'],['原始材料和补证','按次保留，准确版本可追溯']])+btn('查看签署证据','evidence');
 return '<h2>已进入对应责任事项</h2><p>原业务和处理依据保留。本稿只演示页面接续，未写入业务数据。</p>'+btn('查看我的待办','tasks');
}
function render(){
 const locked=scene==='unknown',role=['review','archive'].includes(origin)?'赵欣 · 授权核验人':'林悦 · 销售';
 const header=`<header class="app-header"><div class="brand">${icon('Scales-green')}律所工作助手</div><div class="session"><button class="workbench-control" data-action="opportunities" ${locked?'disabled':''}>商机台账</button><span>${role}</span></div></header>`;
 if(scene==='ledger'||scene==='opportunities'){const contract=scene==='ledger';app.innerHTML=header+`<div class="admin-shell"><aside class="sidebar"><p>业务管理</p><button data-action="opportunities" class="${contract?'':'active'}">商机台账</button><button data-action="ledger" class="${contract?'active':''}">合同台账</button>${tasks()}</aside><main id="main" class="admin-main"><h1>${contract?'合同':'商机'}台账</h1><p>沿用当前查询、列表及详情结构；办理仍进入工作台。</p><div class="ledger-split"><section class="list-pane"><label class="field"><span class="field-label">搜索客户</span><input id="search"></label><table class="record-table"><thead><tr><th>客户与合同</th><th>当前事项</th></tr></thead><tbody><tr><td>${btn('海宁实业有限公司','select')}<small>委托合同 · 第 1 版</small></td><td>待提交签字件</td></tr></tbody></table></section><section class="detail-pane">${selected?'<h2>海宁实业有限公司</h2>'+facts([['当前事项','提交客户签字件'],['负责人','林悦'],['签署状态','尚未核验']])+actions('前往办理','handle',btn('查看签署历史','readonly')):'<p>选择一条记录查看详情。</p>'}</section></div></main></div>`;return;}
 app.innerHTML=header+`<main id="main" class="workbench"><div class="task-header"><div class="today-summary">${icon('CheckCircle')}<p>请先完成当前责任事项。</p><button class="refresh-button workbench-control" data-action="refresh" aria-label="刷新当前责任" ${locked?'disabled':''}>↻<span>刷新</span></button></div><div class="my-tasks">${tasks()}</div></div>${queue?'<section class="queue"><h2>我的待办</h2><p>仅展示当前任职有权事项。</p>'+btn('海宁公司 · 当前签署事项','resume')+btn('明川公司 · 客户跟进','other')+'</section>':''}<article class="work-card"><div class="context"><p class="eyebrow">当前责任</p><h1>${titles[scene]||'继续办理事项'}</h1>${scene==='revoked'?'':`<p class="subject">海宁实业有限公司</p><p class="owner-line">${icon('User')}<span>${role} · 今天 17:00 前</span></p><p class="known-title">已知信息</p><ul class="facts"><li>当前合同 · 第 1 版</li><li>签约前审查与全部必要审批已通过</li></ul><details class="history"><summary>合同与历史依据</summary><p>报价接受或准确直接授权均汇入本签署流程；不提前进行案件业务分类。</p></details>`}</div><section class="card-action">${content()}${error?'<p role="alert">'+esc(error)+'</p>':''}${saved?'<p role="status">草稿已保存，未提交业务。</p>':''}</section></article></main>`;
}
function move(next){scene=next;dirty=false;error='';saved=false;render();}
function modal(html){document.querySelector('#dialog-body').innerHTML=html;dialog.showModal();}
function fail(text){error=text;let p=document.querySelector('[role="alert"]');if(!p){p=document.createElement('p');p.role='alert';document.querySelector('.card-action').append(p);}p.textContent=text;}
document.addEventListener('input',e=>{if(e.target.closest('.card-action'))dirty=true;});
document.addEventListener('click',e=>{
 const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='refresh'){if(dirty){fail('当前内容尚未保存，请先完成或保存草稿。');return;}render();return;}
 if(a==='close'){dialog.close();return;}
 if(['document','evidence','history','handoff'].includes(a)){modal('<h2>'+({document:'批准合同正文',evidence:'本次证据材料',history:'处理记录',handoff:'后续责任安排'})[a]+'</h2><p>此处展示有权查看的准确版本与依据。合成审阅稿不提供真实文件，不产生签署或执行事实。</p>'+btn('返回当前事项','close'));return;}
 if(['tasks','ledger','opportunities','other'].includes(a)&&dirty){modal('<h2>当前填写内容尚未提交</h2><p>可返回继续填写，或放弃本次未保存内容。</p>'+actions('继续填写','close',btn('放弃未保存内容并切换','discard')));return;}
 if(a==='discard'){dialog.close();dirty=false;queue=true;render();return;}
 if(a==='tasks'){queue=!queue;render();return;}if(a==='resume'){queue=false;render();return;}if(a==='other'){move('other');return;}
 if(a==='ledger'||a==='opportunities'||a==='readonly'){move(a);return;}if(a==='select'){selected=true;render();return;}if(a==='handle'){move('submit');return;}
 if(a==='save'){dirty=false;saved=true;let p=document.createElement('p');p.role='status';p.textContent='草稿已保存，未提交业务。';document.querySelector('.card-action').append(p);return;}
 if(a==='prepare-submit'){if(!document.querySelector('#confirmed').checked){fail('请先核对本版签署安排。');return;}move('submit');return;}
 if(a==='submit'){if(!document.querySelector('input[type=file]').files.length||document.querySelector('select').selectedIndex===0||!document.querySelector('#signed-at').value||!document.querySelector('#confirmed').checked){fail('请补全签字件、代表权限依据、签署日期，并核对批准版本。');return;}move('waiting');return;}
 if(a==='decide'){const decision=document.querySelector('#decision').value;if(!decision||!document.querySelector('#note').value.trim()){fail('请选择核验结果并填写说明。');return;}if(decision==='pass'&&['body-ok','authority-ok','evidence-ok'].some(id=>!document.getElementById(id).checked)){fail('核验通过前，请逐项核对内容、代表权限及签署完整性。');return;}move(decision==='pass'?'reviewed':decision==='changed'?'changed':'returnedEvidence');return;}
 if(a==='archive'){if(!document.querySelector('#confirmed').checked||!document.querySelector('#note').value.trim()){fail('请核对归档完整性并填写说明。');return;}move('complete');return;}
 if(a==='scan'){move('submit');return;}if(a==='recover'){move('waiting');return;}if(a==='remaining'){move('submit');return;}if(a==='revise'){move('revision');return;}
});render();
