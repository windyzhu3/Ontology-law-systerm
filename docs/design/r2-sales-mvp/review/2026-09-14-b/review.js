const content = document.querySelector('#content');
const scene = document.querySelector('#scene');
const canvas = document.querySelector('#canvas');
const note = document.querySelector('#note');
let aiState = 'candidate';
const option = document.createElement('option'); option.value = 'manual-unknown'; option.textContent = '16 单条录入结果待确认'; scene.append(option);
const aiControl = document.createElement('label'); aiControl.innerHTML = 'AI 演示状态<select id="ai-state"><option value="candidate">待人工核对</option><option value="confirmed">已人工确认</option><option value="stale">材料已变化</option><option value="unavailable">服务不可用</option></select>'; document.querySelector('.review-controls').append(aiControl);
const button = (text, next, primary = false, disabled = false) => `<button ${next ? `data-next="${next}"` : ''} ${primary ? 'class="primary"' : ''} ${disabled ? 'disabled' : ''}>${text}</button>`;
const steps = n => `<div class="steps" aria-label="导入步骤">${['选择文件','核对预览','查看结果'].map((x,i)=>`${i ? '<span aria-hidden="true">—</span>' : ''}<${i===n?'b':'span'}>${i+1} ${x}</${i===n?'b':'span'}>`).join('')}</div>`;
const heading = (title, subtitle, step = null) => `<header class="heading"><h1>${title}</h1><p>${subtitle}</p>${step===null?'':steps(step)}</header>`;
const banner = (title, text, warning = false) => `<div class="banner ${warning?'warning':''}" role="status"><strong>${title}</strong><p>${text}</p></div>`;
const field = (label, body, required = false, help = '') => `<div class="field"><label>${label}${required?'<span class="required" aria-hidden="true">*</span>':''}${body}</label>${help?`<small>${help}</small>`:''}</div>`;
const source = () => field('来源', '<select aria-label="来源"><option>客户转介绍</option><option>手工录入</option></select>', true, '仅显示当前任职有权录入的来源。');
const table = (rows, columns=['来源行','客户／联系人','录入结果','下一步']) => `<table><thead><tr>${columns.map(x=>`<th scope="col">${x}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr class="${r.cls||''}">${r.cells.map((x,i)=>`<td data-label="${columns[i]}">${x}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
const tag = (text, kind = '') => `<span class="tag ${kind}">${text}</span>`;
function fileView(){
  return heading('导入线索','先选择来源和文件，再核对需要录入的内容。',0)+`<div class="grid"><section class="panel">${source()}<div class="upload"><svg width="38" height="42" viewBox="0 0 38 42" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="M7 2h15l9 9v29H7zM22 2v10h9M12 23h14M12 29h14M12 17h6"/></svg><h2>选择 CSV 或 XLSX 文件</h2><p>每次一个文件，最多 200 条记录、1 MB。</p>${button('选择文件','preview')}</div><p class="help">文件先在当前页面核对，确认导入后才提交有效行。</p></section><aside class="panel"><h2>文件准备</h2><ul class="rules"><li>来源记录号、需求描述不能为空。</li><li>联系人、手机号、客户名称可按已知信息填写。</li><li>XLSX 仅支持一个可见工作表；不支持公式、宏或合并单元格。</li><li>电话需带国家或地区代码，来源记录号请保留文本格式。</li></ul><details><summary>查看字段要求</summary><p class="help">手机号示例：+8613800000000（虚构）。来源记录号用于识别同一来源记录；不要改号重复导入。</p></details></aside></div><div class="actionbar"><p>选择文件后进入核对预览。</p><div class="actions">${button('返回新增线索','manual')}${button('核对预览',null,true,true)}</div></div>`;
}
function previewView(){
  const rows = [
    {cells:['2','华启制造',tag('检查通过'),'需求完整；可录入']},
    {cells:['3','明川商贸',tag('检查通过'),'需求完整；可录入']},
    {cells:['4','远航科技',tag('检查通过'),'需求完整；可录入']},
    {cls:'issue',cells:['5','启明服务',tag('需修正','warn'),'来源记录号与第 6 行重复；缺少需求描述']},
    {cls:'issue',cells:['6','新程咨询',tag('需修正','warn'),'来源记录号与第 5 行重复']}
  ];
  return heading('核对导入内容','预览通过后再提交，错误行不会被自动录入。',1)+`<section class="panel"><div class="table-heading"><div><strong>九月线索.xlsx</strong><p class="help">来源：客户转介绍 · 共 5 条记录</p></div><button class="link" data-leave>更换文件</button></div><div class="mapping">${['来源编号 → 来源记录号','客户 → 客户名称','联系人 → 联系人','联系电话 → 手机号','咨询内容 → 需求描述'].map(x=>`<label>文件列映射<select aria-label="${x}"><option>${x}</option></select></label>`).join('')}</div><div class="counts"><span>可导入 3 条</span><span>需修正 2 条</span></div>${table(rows,['来源行','客户／联系人','检查结果','说明'])}<p class="help">请在原文件修正错误后重新选择。保持来源记录号不变；已录入行不会被覆盖。</p></section><div class="actionbar"><p>本次仅提交检查通过的 3 条记录。</p><div class="actions">${button('取消导入','file')}${button('确认导入 3 条','submitting',true)}</div></div>`;
}
function resultView(mode){
  const busy = mode==='submitting', unknown = mode==='unknown', resolved = mode==='resolved';
  const second = busy?'提交中':unknown?'结果待确认':'已录入';
  const rows = [
    {cells:['2','华启制造',tag('已录入'),'后续责任以工作台显示为准']},
    {cls:'selected',cells:['3','明川商贸',tag(second,unknown?'warn':busy?'quiet':''),busy?'正在提交本行':unknown?'核对本次结果，不要重复提交':'已核对原请求，确认录入成功']},
    {cells:['4','远航科技',tag('未提交','quiet'),'等待人工继续']}
  ];
  return heading('导入结果','查看已录入、未确认和未提交的记录。',2)+banner(busy?'正在提交第 2 条，共 3 条':unknown?'有 1 条提交结果尚未确认':'原请求已核对，2 条已录入',busy?'当前提交结束前不能更换文件或再次提交。':unknown?'已暂停后续提交。核对原请求的结果，不会再创建一条线索。':'还有 1 条未提交。由你确认后继续，不会自动提交剩余行。',unknown)+`<div class="grid"><section class="panel table-panel"><div class="table-heading"><h2>本次录入</h2><span class="help">客户转介绍</span></div>${table(rows)}<p class="footer-note">本次 3 条有效记录 · 原始行号保持不变</p></section><aside class="panel detail"><h2>${resolved?'继续本次导入':'明川商贸'}</h2><dl><dt>当前结果</dt><dd>${resolved?'原请求已确认成功':second}</dd><dt>接下来</dt><dd>${resolved?'提交远航科技这一条剩余记录':unknown?'只核对原请求，不发起新录入':'等待当前请求完成'}</dd></dl><p class="help">线索录入后可能进入去重、补齐、分配或联系，具体待办由系统确认。</p><div class="actions">${button(busy?'正在提交…':unknown?'核对提交结果':'继续提交剩余 1 条',busy?null:unknown?'resolved':'success',true,busy)}${button('返回线索入口','file',false,busy)}</div></aside></div>`;
}
function successView(){
  const rows=[{cells:['2','华启制造',tag('已录入'),'联系客户 · 陈晓']},{cells:['3','明川商贸',tag('已录入'),'补齐联系方式 · 来源负责人']},{cells:['4','远航科技',tag('已录入'),'后继责任正在确认']}];
  return heading('录入完成','本次 3 条线索已录入，后续按实际待办继续处理。',2)+banner('3 条已录入 · 无未决提交','已录入记录无需重复提交；后续待办以实际显示为准。')+`<div class="grid"><section class="panel table-panel"><div class="table-heading"><h2>录入结果</h2><span class="help">本次 3 条</span></div>${table(rows)}</section><aside class="panel detail"><h2>下一步</h2><dl><dt>我的待办</dt><dd>华启制造 · 联系客户</dd><dt>负责人</dt><dd>陈晓 · 销售一组</dd><dt>计划时间</dt><dd>今天 15:00 前联系</dd></dl><p class="help">只提供你当前有权处理的事项。其他负责人的待办由对应人员处理。</p><div class="actions">${button('前往我的待办','workbench',true)}${button('继续录入','manual')}</div></aside></div><div class="panel" style="margin-top:20px"><h3>远航科技：后继责任正在确认</h3><p class="help">线索已经录入，请勿重复提交。刷新后查看实际进展；如果持续没有后继责任，由有权人员在团队待办中处理。</p><button class="link" data-refresh>刷新后续进展</button></div>`;
}
function lostView(){
  return heading('先核对上次录入','重新登录后，仅处理属于当前任职的未决请求。')+`<section class="panel empty"><div class="status-icon">!</div><h2>上次有一条录入结果未确认</h2><p>请先核对原请求，确认是否已经录入。</p><p class="help">重新打开页面后，原文件和未提交行可能已不在内存中。核对成功不代表整个批次已经完成。</p><div class="actions">${button('核对原请求','recovered-lost',true)}</div><p class="help" style="margin-top:26px">当前任职：陈晓 · 销售一组<br>其他身份的未决记录不会在这里显示或处理。</p></section>`;
}
function recoveredLost(){
  return heading('原请求已确认','已确认一条线索录入成功。')+banner('这条线索已录入','本页没有保留原批次的全部明细，不能自动续提。')+`<section class="panel"><h2>核对剩余记录后继续</h2><ol class="rules"><li>先查看线索与客户台账，核对已录入记录。</li><li>确认哪些记录尚未提交，再重新选择文件核对。</li><li>保留来源及来源记录号，不以改号方式重复录入。</li></ol><div class="actions" style="margin-top:24px">${button('查看线索与客户','ledger',true)}${button('返回线索入口','file')}</div></section>`;
}
function noSource(){
  return heading('新增线索','使用当前本人任职录入客户需求。')+`<section class="panel empty"><div class="status-icon">—</div><h2>当前没有可用的录入来源</h2><p>请确认已选择正确任职，或联系有权人员核对来源配置与录入权限。</p><p class="help">当前任职：陈晓 · 销售一组<br>来源不可用时，不能保存线索或发起导入。</p><div class="actions">${button('刷新来源','file',true)}${button('返回工作台','workbench')}</div></section>`;
}
function manualView(){
  const confirmed=aiState==='confirmed', stale=aiState==='stale', unavailable=aiState==='unavailable';
  return heading('新增线索','先录入联系信息和需求，业务分类在案件形成后完成。')+`<div class="grid"><section class="panel">${source()}${field('联系人',`<input id="contact" aria-label="联系人" placeholder="待填写" value="${confirmed?'王女士':''}">`)}${field('手机号','<input aria-label="手机号" placeholder="请输入手机号">')}${field('客户名称',`<input id="customer" aria-label="客户名称" placeholder="待填写" value="${confirmed?'华启智能制造有限公司':''}">`)}${field('需求描述','<textarea id="need" aria-label="需求描述" required>公司计划新建生产线，希望安排律师沟通项目合规事项。</textarea>',true)}<small>来源记录由系统保留，无需手工填写内部编号。</small></section><aside class="panel ai-panel"><h2>从材料提取字段</h2><p class="help">粘贴来电记录或其他文本，可选择由 AI 提取候选后逐项核对。</p><label class="field">材料文本<textarea id="material" aria-label="材料文本">王女士来电，华启智能制造有限公司计划新建生产线，希望咨询项目合规事项。</textarea></label><div class="actions"><button data-extract ${unavailable?'disabled':''}>${unavailable?'暂时不可用':'提取候选'}</button></div>${unavailable?banner('字段提取暂时不可用','你可以继续手工填写并保存，不影响录入流程。',true):stale?banner('材料已变化，旧候选不可采用','请重新提取并核对。已经填写的表单保留，保存前请自行检查。',true):`<div class="ai-box"><h3>AI 候选 · ${confirmed?'已人工确认':'待人工确认'}</h3><small>来源：粘贴材料</small><div class="ai-row"><label for="candidate-contact">联系人</label><input id="candidate-contact" aria-label="候选联系人" value="王女士"></div><div class="ai-row"><label for="candidate-customer">客户名称</label><input id="candidate-customer" aria-label="候选客户名称" value="华启智能制造有限公司"></div></div>${confirmed?'<p class="help">已确认内容已填入表单，保存前仍可修改。</p>':'<label class="check"><input id="confirm-ai" type="checkbox">我已逐项核对，采用以上候选填写表单</label><button class="link" data-ignore>忽略候选，手工填写</button>'}`}</aside></div><div class="actionbar"><p>保存后按实际情况进入分配、补齐或联系流程。</p><div class="actions"><button data-leave>返回</button><button class="link" data-leave>批量导入</button>${button('保存线索','manual-success',true)}</div></div>`;
}
function manualUnknown(){return heading('录入结果待确认','尚未确认这条线索是否录入，请勿再次保存。')+banner('已暂停新的录入','只核对本次原请求；不使用新的请求重复创建。',true)+`<section class="panel"><h2>华启智能制造有限公司</h2><p>来源：客户转介绍</p><p class="help">核对完成后显示实际录入结果。尚未确认前，不能切换到批量导入。</p><div class="actions" style="margin-top:24px">${button('核对提交结果','manual-success',true)}${button('保存线索',null,false,true)}</div></section>`;}
function manualSuccess(){return heading('线索已录入','录入结果已确认，不需要再次保存。')+banner('华启智能制造有限公司','后续责任已确认：联系客户。')+`<section class="panel"><h2>接下来联系客户</h2><div class="facts"><div class="fact"><small>当前待办</small>联系客户</div><div class="fact"><small>负责人</small>陈晓 · 销售一组</div><div class="fact"><small>计划时间</small>今天 15:00 前联系</div></div><div class="actions">${button('继续录入','manual')}${button('前往我的待办','workbench',true)}</div></section>`;}
const notes={file:'07：选择来源和文件。取消不提交；文件限制在选择前可见。',preview:'08：沿用已确认核对预览；补小屏逐行卡片。重复来源记录号的所有行均标错。',submitting:'09：提交中只显示一个不可重复点击的主操作；演示不会自动提交，请使用顶部场景切换查看后续。',unknown:'10：核对原请求后停在下一步确认，不自动继续提交。',resolved:'11：继续提交需要单独操作，已成功行不重提。此场景与 09/10 使用同一组 3 条有效记录。',success:'12：后继责任必须来自服务器；未取得责任时只说明正在确认，不编造负责人。前往我的待办仍接受既有授权和选卡保护。',lost:'13：重载后只恢复准确未决请求，不承诺保存原文件、客户材料或未提交行。','no-source':'14：无来源不等于无任何业务权限；只阻断当前录入入口，不暴露来源账号或权限代码。',manual:'15：AI 状态可在顶部切换。候选未确认不填表；材料变化后旧候选失效；AI 不可用时手工路径仍可完成。','manual-unknown':'16：手工录入同样遵守未决请求保护，不以新的保存操作重试。'};
function show(key, focus=false){
  document.querySelector('#leave').close();
  aiControl.hidden=key!=='manual';
  const views={file:fileView,preview:previewView,submitting:()=>resultView('submitting'),unknown:()=>resultView('unknown'),resolved:()=>resultView('resolved'),success:successView,lost:lostView,'no-source':noSource,manual:manualView,'manual-unknown':manualUnknown,'manual-success':manualSuccess,'recovered-lost':recoveredLost};
  if(key==='workbench'||key==='ledger'){
    content.innerHTML=heading(key==='workbench'?'前往我的待办':'查看线索与客户','此处衔接已有界面。')+`<section class="panel"><h2>继续到既有业务入口</h2><p>正式实现将打开当前用户有权查看的待办或台账，不在本原型重复设计这些界面。</p><button data-next="success">返回本次录入结果</button></section>`;
    note.textContent='原型衔接说明，不是新增产品页面；生产实现直接复用既有入口。';
  }else{key=views[key]?key:'file';content.innerHTML=views[key]();note.textContent=notes[key]||'衍生结果状态：只确认当前可证明的结果，再由人工选择下一步。';}
  if([...scene.options].some(o=>o.value===key))scene.value=key;
  if(key==='manual'&&aiState==='confirmed')document.querySelectorAll('.ai-row input').forEach(input=>input.readOnly=true);
  history.replaceState(null,'','#'+key);
  if(focus)content.focus({preventScroll:true});
}
scene.addEventListener('change',()=>show(scene.value,true));
document.querySelector('#ai-state').addEventListener('change',e=>{aiState=e.target.value;show('manual',true)});
document.querySelector('#size').addEventListener('click',e=>{const small=canvas.classList.toggle('phone');e.target.setAttribute('aria-pressed',String(small));e.target.textContent=small?'查看桌面':'查看小屏';});
document.addEventListener('click',e=>{
  const b=e.target.closest('button');if(!b||b.disabled)return;
  if(b.dataset.next){if(b.dataset.next==='manual-success'&&document.querySelector('#need')&&!document.querySelector('#need').reportValidity())return;show(b.dataset.next,true);}
  if(b.hasAttribute('data-leave'))document.querySelector('#leave').showModal();
  if(b.hasAttribute('data-close'))document.querySelector('#leave').close();
  if(b.hasAttribute('data-extract')){const values=['contact','customer','need','material'].map(id=>[id,document.getElementById(id).value]);aiState='candidate';document.querySelector('#ai-state').value=aiState;show('manual');for(const [id,value] of values)document.getElementById(id).value=value;}
  if(b.hasAttribute('data-ignore')){document.querySelector('.ai-box')?.remove();document.querySelector('.check')?.remove();b.replaceWith(Object.assign(document.createElement('p'),{className:'help',textContent:'已忽略候选，请继续手工填写。'}));}
  if(b.hasAttribute('data-refresh')){b.textContent='已刷新，后继责任仍在确认';b.disabled=true;}
});
document.addEventListener('change',e=>{if(e.target.id==='confirm-ai'&&e.target.checked){document.querySelector('#contact').value=document.querySelector('#candidate-contact').value;document.querySelector('#customer').value=document.querySelector('#candidate-customer').value;document.querySelector('.ai-box h3').textContent='AI 候选 · 已人工确认';document.querySelectorAll('.ai-row input').forEach(input=>input.readOnly=true);e.target.disabled=true;}});
document.addEventListener('input',e=>{if(e.target.id==='material'){
  document.querySelector('.ai-box')?.remove();document.querySelector('.check')?.remove();document.querySelector('[data-ignore]')?.remove();
  if(!document.querySelector('#stale-note')){const el=document.createElement('div');el.id='stale-note';el.className='banner warning';el.textContent='材料已变化，旧候选不可采用。请重新提取并核对；已填写的表单保留。';document.querySelector('.ai-panel').append(el);}
}});
show(location.hash.slice(1)||'file');


