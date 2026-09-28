// Review prototype only: synthetic data, no network/storage/file APIs.
const allowed=['entry','list','receive','selected','checking','review','saved','version','history','waiting','unconfirmed','stale','unknown','rejected','readonly','closed','revoked','failed','loading'];
let scene=new URLSearchParams(location.search).get('scene')||'entry';if(!allowed.includes(scene))scene='entry';
const origin=scene;let picked=['selected','checking','review','unknown','version','rejected'].includes(scene),dirty=false,leave=false,error='',preview=false,backTo='list',name='采购合同.pdf',purpose='合同及业务资料',note='',isNewVersion=scene==='version',query='',filter='all';
const app=document.querySelector('#app'),esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=n=>`<img class="icon" src="../2026-09-14-e/assets/${n}.svg" alt="" aria-hidden="true">`;
const btn=(t,a,primary=false,disabled=false)=>`<button data-action="${a}"${primary?' class="primary"':''}${disabled?' disabled':''}>${t}</button>`;
const facts=rows=>`<dl class="detail-facts">${rows.map(([k,v])=>`<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>`;
function summary(){return facts([['材料',name],['归属','海宁公司 · 当前商机'],['用途',purpose],['本次版本',isNewVersion?'补交第 2 版，原版本保留':'首次接收'],['客户资料',origin==='unconfirmed'?'尚未确认；仅归属当前商机':'已确认客户与需求'],['说明',note||'暂无补充']]);}
function list(){return `<h2>本次商机材料</h2><p class="help">收齐材料后仍需业务核对；本页不判断能否报价或签约。</p>${facts([['合同及业务资料','已收 1 份 · 采购合同.pdf'],['相关往来记录','待补充'],['其他材料','暂未提供']])}<p class="help">以上为本次销售准备参考项。缺项可继续跟进，不提前作为签约或转案的必备清单。</p>${btn('查看采购合同','preview')}<details class="history"><summary>版本与接收记录</summary><p>采购合同.pdf · 第 1 版 · 林悦接收</p>${btn('查看原版本','history')}${!['readonly','closed'].includes(origin)?btn('补交新版','version'):''}</details>${['readonly','closed'].includes(origin)?'<p>当前只可查看有权材料，不能接收或修改。</p>':`<div class="action-area">${btn('接收材料','receive',true)}<div class="secondary-line">${btn(origin==='waiting'?'返回商机台账':'继续原跟进事项','continue')}</div></div>`}`;}
function content(){
 if(scene==='loading')return '<p role="status">正在读取材料清单…</p>';
 if(scene==='revoked')return '<h2>当前无法查看材料</h2><p>权限已变化，文件名称、预览与操作已清除。</p>'+btn('返回商机台账','entry');
 if(scene==='failed')return '<h2>材料读取失败</h2><p>请重新读取后继续。</p>'+btn('重新读取','list',true);
 if(leave)return '<h2>本次有未确认的材料</h2><p>离开后不提交为商机材料，原已接收版本保留。</p>'+btn('继续核对','stay',true)+btn('放弃本次操作并返回','discard');
 if(preview)return '<h2>采购合同.pdf</h2><p class="help">第 1 版 · 仅显示当前有权材料</p><div class="document-preview"><h3>设备采购合同</h3><p class="doc-meta">高保真合成预览，无真实文件</p><p>委托方：海宁实业有限公司</p><p>本文件用于材料接收页面审阅，正文不构成合同。</p></div><p class="help">正式实现按权限预览或下载准确文件版本。</p>'+btn('返回材料','closePreview',true)+btn('下载此版本','download')+(error?`<p role="status">${esc(error)}</p>`:'');
 if(scene==='history')return '<h2>已接收的原版本</h2>'+facts([['文件','采购合同.pdf'],['版本','第 1 版'],['接收人','林悦'],['状态','历史保留；已有引用不改变']])+btn('预览此版本','preview',true)+btn('返回材料清单','list');
 if(scene==='stale')return '<h2>本次接收依据已变化</h2><p>负责人、商机或所选版本已变化。重新读取并核对后继续，旧核对内容不能直接提交。</p>'+btn('重新读取材料','reload',true);
 if(scene==='unknown')return '<h2>接收结果尚未确认</h2><p>正在核对本次操作，请勿重新上传或重复接收。</p>'+summary()+btn('核对本次结果','resolve',true);
 if(scene==='checking')return '<h2>正在检查文件</h2><p role="status">采购合同.pdf · 文件内容与安全检查中</p><p>通过检查后再核对材料归属，当前尚未成为已接收材料。</p>'+btn('查看检查结果','checked',true);
 if(scene==='rejected')return '<h2>本次文件未能接收</h2><p>文件检查未通过，请选择符合要求的文件。原已接收材料不受影响。</p><p class="help">若暂时无法检查，请稍后核对状态；不会将未通过文件列为已收材料。</p>'+btn('重新选择文件','retry',true)+btn('返回材料清单','list');
 if(scene==='saved')return '<h2>材料已接收</h2>'+summary()+'<p role="status">'+(origin==='waiting'?'原约定联系时间不变，跟进不会提前恢复。':'原跟进事项和时限不变，可接着办理。')+'</p><div class="action-area">'+btn(origin==='waiting'?'返回商机台账':'继续原跟进事项','continue',true)+'<div class="secondary-line">'+btn('查看材料清单','list')+'</div></div>';
 if(scene==='review')return '<h2>核对本次材料接收</h2>'+summary()+'<p class="help">文件检查已通过。接收表示材料已登记，不代表内容属实、签署有效或款项到账。</p><div class="action-area">'+btn('确认接收材料','submit',true)+'<div class="secondary-line">'+btn('返回修改','selected')+'</div></div>';
 if(['receive','selected','version','unconfirmed'].includes(scene))return `<h2>${isNewVersion?'补交材料新版':'接收本次材料'}</h2><p class="help">归属当前商机；每次接收一个文件。</p>${isNewVersion?'<p class="feedback">原文件：采购合同.pdf · 第 1 版。新版本接收后保留原文件，已引用的旧版本不自动替换。</p>':''}${origin==='unconfirmed'?'<p class="feedback">客户与需求尚未确认，材料可以先归属当前商机；不自动认定委托主体。</p>':''}<label class="field">材料用途<select id="purpose">${['合同及业务资料','相关往来记录','其他材料'].map(x=>`<option${x===purpose?' selected':''}>${x}</option>`).join('')}</select></label><p>${picked?esc(name)+' · PDF · 1.2 MB':'尚未选择文件'}</p>${btn(picked?'重新选择文件':'选择文件','pick')}<p class="help">首版拟支持 PDF、JPG、PNG，单文件不超过 20 MB。</p><details class="history"><summary>材料说明（需要时填写）</summary><label class="field">材料说明<textarea id="note" maxlength="500">${esc(note)}</textarea></label></details>${error?`<p role="alert">${esc(error)}</p>`:''}<div class="action-area">${btn('上传并核对','upload',true)}<div class="secondary-line">${btn('返回材料清单','back')}</div></div>`;
 return list();
}
function render(){
 const locked=['unknown','checking'].includes(scene),redacted=scene==='revoked';
 const header=`<header class="app-header"><div class="brand">${icon('Scales-green')}律所工作助手</div><div class="session">${btn('返回商机台账','back',false,locked)}<span class="role-label">林悦 · 销售</span><span class="avatar" aria-label="林悦 · 销售">林</span></div></header>`;
 if(scene==='entry'){
 app.innerHTML=header+`<div class="admin-shell"><aside class="sidebar"><p>业务管理</p><button class="active" aria-current="page">${icon('Briefcase')} 商机台账</button>${btn('我的待办','continue')}</aside><main id="main" class="admin-main"><div class="admin-heading"><div><h1>商机台账</h1><p>查看有权业务记录，接着办理当前事项。</p></div></div><div class="ledger-split"><section class="list-pane" id="records"><div class="toolbar"><label class="search">${icon('MagnifyingGlass')}<span class="sr-only">搜索客户</span><input id="query" value="${esc(query)}" placeholder="搜索客户"></label></div><table class="record-table"><thead><tr><th>客户</th><th>状态</th><th>下一事项</th></tr></thead><tbody>${!query||'海宁公司'.includes(query)?`<tr class="selected"><td>${btn('海宁公司','focus')}</td><td data-label="状态">${origin==='waiting'?'等待约定时间':'商机跟进'}</td><td data-label="下一事项">联系客户并记录进展</td></tr>`:'<tr><td colspan="3">没有匹配记录</td></tr>'}</tbody></table><p class="list-foot">仅显示当前有权查看的商机。</p></section><section class="detail-pane" id="detail" tabindex="-1"><button class="mobile-return" data-action="records">返回列表</button><h2>海宁公司</h2><p class="status">商机跟进</p>${facts([['负责人','林悦'],['最近进展','已核对客户与需求'],['下一事项','联系客户并记录进展'],['约定时间','9月21日 10:00']])}<details><summary>客户与需求资料</summary><p>海宁实业有限公司 · 咨询及协商服务</p></details><details open><summary>业务材料</summary><p>已收 1 份 · 相关往来记录待补充</p>${btn('查看与接收材料','list')}</details><div class="compact-confirm">${btn('办理本次跟进','continue',true)}</div></section></div></main></div>`;
 }else app.innerHTML=header+`<main id="main" class="workbench"><div class="today"><p class="today-copy">${icon('CheckCircle')}<span>收好本次材料，接着办理原跟进事项。</span></p></div><article class="work-card" aria-label="商机材料卡"><div class="context"><p class="eyebrow">本次材料维护</p><h1>接收商机材料</h1>${redacted?'':`<p class="subject">海宁公司 · 设备采购事项</p><p class="owner-line">${icon('User')}林悦负责 · 原跟进安排不变</p><p class="known-title">已知信息</p><ul class="facts"><li>${icon('CheckCircle')}<span>客户资料：${origin==='unconfirmed'?'尚未确认':'海宁实业有限公司，已人工确认'}</span></li><li>${icon('Clock')}<span>${origin==='waiting'?'9月21日10:00再联系，当前未到期':origin==='closed'?'商机已结束，只读':'当前事项：联系客户并记录进展'}</span></li></ul><p class="context-note">材料接收不代替报价、合同签署或到账确认。原文件及历史引用保留。</p><details class="history"><summary>来源与业务记录</summary><p>线索 → 有效首联 → 商机 → 客户与需求确认。</p></details>`}</div><section class="card-action">${content()}</section></article></main>`;
}
app.addEventListener('input',e=>{if(e.target.id==='note'){note=e.target.value;dirty=true;}if(e.target.id==='query'){query=e.target.value;const start=e.target.selectionStart;render();const input=document.querySelector('#query');input.focus();input.setSelectionRange(start,start);}});
app.addEventListener('change',e=>{if(e.target.id==='purpose'){purpose=e.target.value;dirty=true;}});
app.addEventListener('click',e=>{const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='focus'){document.querySelector('#detail').focus();document.querySelector('#detail').scrollIntoView({block:'start'});return;}if(a==='records'){document.querySelector('#records').scrollIntoView({block:'start'});return;}
 if(a==='pick'){picked=true;dirty=true;error='';scene='selected';}
 else if(a==='upload'){if(!picked){error='请先选择要接收的文件。';render();return;}scene='checking';}
 else if(a==='checked')scene='review';
 else if(a==='submit'||a==='resolve'){scene='saved';dirty=false;}
 else if(a==='version'){isNewVersion=true;picked=false;scene='version';}
 else if(a==='preview'){preview=true;backTo=scene;error='';}
 else if(a==='closePreview'){preview=false;scene=backTo;error='';}
 else if(a==='download'){error='审阅稿：正式实现将下载当前有权版本，本稿不提供真实文件。';}
 else if(a==='back'){if(dirty){leave=true;}else{scene='entry';preview=false;}}
 else if(a==='stay'){leave=false;}
 else if(a==='discard'){dirty=false;leave=false;picked=false;scene='entry';}
 else if(a==='reload'){dirty=false;picked=false;scene='list';}
 else if(a==='retry'){picked=false;dirty=false;scene='receive';}
 else if(a==='continue'){app.innerHTML=headerForContinue()+`<main id="main" class="workbench"><article class="work-card"><div class="context"><p class="eyebrow">原跟进事项</p><h1>${origin==='waiting'?'等待约定联系时间':'记录商机实质进展'}</h1><p class="subject">海宁公司</p><p>原任务、负责人及约定时间保持。</p></div><section class="card-action"><h2>${origin==='waiting'?'尚未到期，无需重复提交':'继续当前跟进'}</h2><p>正式系统返回有权原事项；材料接收未代替完成本次跟进。</p>${btn('返回商机台账','entry',true)}</section></article></main>`;return;}
 else if(allowed.includes(a)){scene=a;preview=false;error='';}
 render();});
function headerForContinue(){return '<header class="app-header"><div class="brand">'+icon('Scales-green')+'律所工作助手</div></header>';}
render();
