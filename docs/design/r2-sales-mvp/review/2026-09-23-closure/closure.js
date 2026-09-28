// Synthetic design review only. No network writes, storage or business facts.
'use strict';
const app=document.querySelector('#app'),dialog=document.querySelector('#dialog');
let scene=new URLSearchParams(location.search).get('scene')||'progress',dirty=false,queue=false,error='',saved=false,returnTo='prepareQuote';
const origin=scene,fields={};
const titles={progress:'商机跟进与客户资料',choose:'推进或结束本次商机',missing:'补齐报价准备依据',customer:'确认客户与需求',prepareQuote:'开始报价准备',direct:'申请直接准备合同',attempt:'记录本次联系尝试',quoteAttempt:'安排报价后续联系',endQuote:'结束本次报价办理',endUnsigned:'结束本次签约办理',requestStop:'申请主管核对终止签约',supervisor:'核对终止签约请求',source:'确认线索后续安排',waiting:'已交下一责任人',unknown:'核对原提交结果',failed:'本次提交未成功',unassigned:'责任安排待处理',readonly:'本次办理已停止',revoked:'当前事项不可办理',quoteReady:'准备本次收费方案',done:'本次安排已记录',contactReady:'联系新分配的线索',sourceClosed:'本线索已结束',sourceWait:'等待约定复查',continued:'已交回继续办理',followup:'等待约定联系时间'};
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=n=>'<img class="icon" src="../2026-09-14-e/assets/'+n+'.svg" alt="">';
const btn=(label,action,primary=false,disabled=false)=>'<button type="button" class="'+(primary?'primary':'link-button')+'" data-action="'+action+'" '+(disabled?'disabled':'')+'>'+label+'</button>';
const facts=rows=>'<dl class="detail-facts">'+rows.map(([k,v])=>'<div><dt>'+k+'</dt><dd>'+v+'</dd></div>').join('')+'</dl>';
const actions=(label,action,extra='')=>'<div class="ledger-detail-actions">'+btn(label,action,true)+extra+'</div>';
const input=(label,id,type='text')=>'<label class="field"><span class="field-label">'+label+'</span><input id="'+id+'" type="'+type+'" value="'+esc(fields[id]||'')+'"></label>';
const note=label=>'<label class="field"><span class="field-label">'+label+'</span><textarea id="reason" rows="3">'+esc(fields.reason||'')+'</textarea></label>';
const select=(label,id,options)=>'<label class="field"><span class="field-label">'+label+'</span><select id="'+id+'"><option value="">请选择</option>'+options.map(([v,l])=>'<option value="'+v+'"'+(fields[id]===v?' selected':'')+'>'+l+'</option>').join('')+'</select></label>';
const check=(label,id)=>'<label class="checked-label contract-checkbox"><input type="checkbox" id="'+id+'" '+(fields[id]?'checked':'')+'>'+label+'</label>';
const tasks=()=>'<button class="workbench-control" data-action="tasks" '+(scene==='unknown'?'disabled':'')+'>'+icon('ListChecks')+'<span>我的待办（3）</span></button>';
const reason=()=>note('处理原因')+check('已核对当前业务和准确版本','confirmed');
function content(){
 switch(scene){
 case 'progress':return '<h2>记录本次有效进展</h2>'+note('本次进展')+select('有效联系类型','kind',[['phone','电话有效沟通'],['visit','客户拜访'],['meeting','会议沟通'],['wechat','微信有效沟通']])+input('下次跟进时间','next','datetime-local')+actions('记录本次进展','progress-submit')+'<details class="history"><summary>其他业务处理</summary><p>准备进入下一环节时，无需先填写本次进展或下次时间。</p>'+btn('推进或结束本次商机','choose')+btn('记录联系尝试，尚无有效进展','attempt')+btn('维护客户与需求','customer')+'</details>';
 case 'choose':return '<h2>选择本次要办理的事项</h2><p>打开入口不会结束当前跟进。提交下一事项并成功接管后，才结束原责任。</p><div class="ledger-detail-actions">'+btn('准备报价','missing')+btn('申请直接准备合同','direct')+btn('结束本次商机','endQuote')+'</div>'+btn('返回当前跟进','progress');
 case 'missing':return '<h2>先确认客户与需求</h2>'+facts([['尚缺依据','委托主体、服务需求的客户确认'],['当前责任','商机跟进仍由你负责'],['已具备','有效首联及商机来源']])+'<p>维护资料后回到本次准备；不自动生成报价。</p>'+actions('补齐客户与需求','customer',btn('返回当前跟进','progress'));
 case 'customer':return '<h2>核对客户确认的需求</h2><p>沿用已有客户资料卡；本场景仅演示补齐后返回原办理上下文。</p>'+input('委托主体','customer')+note('客户确认的服务需求')+check('上述内容已由客户确认','confirmed')+actions('确认客户与需求','customer-submit',btn('返回原事项','return'));
 case 'prepareQuote':return '<h2>确认进入报价准备</h2>'+facts([['客户与需求','已确认准确版本'],['本次负责','林悦 · 销售'],['接续事项','准备本次收费方案']])+'<p>确认后由报价准备接管当前跟进；无需虚填下次联系时间。尚未形成报价或提交审批。</p>'+actions('开始报价准备','quote-submit',btn('返回当前跟进','progress'));
 case 'direct':return '<h2>申请授权后直接准备合同</h2>'+facts([['客户与需求','已确认准确版本'],['审批接收','周宁 · 销售主管']])+note('不经过正式报价的申请原因')+input('拟定收费金额（元）','amount','number')+check('申请对应本次客户需求与收费安排','confirmed')+'<p>沿用既有直接授权流程；主管批准前不能形成合同。</p>'+actions('提交直接合同授权申请','direct-submit',btn('返回当前跟进','progress'));
 case 'attempt':case 'quoteAttempt':return '<h2>'+ (scene==='quoteAttempt'?'客户尚未回复本版报价':'记录本次联系尝试')+'</h2>'+select('本次情况','attemptKind',[['unreached','未接通'],['noReply','尚未回复'],['noProgress','已联系，暂无有效进展']])+input('实际联系时间','at','datetime-local')+note('本次尝试与后续安排')+input('下次联系时间','next','datetime-local')+'<p>本次仅记录联系尝试，不计有效进展'+(scene==='quoteAttempt'?'，不生成客户接受或拒绝事实':'')+'。原到期时间和逾期历史保留。</p>'+actions('记录尝试并安排下次联系','attempt-submit',btn('返回原事项','return'));
 case 'endQuote':return '<h2>确认结束本次销售办理</h2>'+facts([['当前阶段','报价等待审批（合成场景）'],['处理范围','本商机及未完成的相关报价责任'],['历史记录','已形成报价、交付及客户回复仍保留']])+reason()+'<p>存在合同接管时，须转合同办理规则，不能从此处绕过。</p>'+actions('确认结束本次办理','end-submit',btn('返回原事项','return'));
 case 'endUnsigned':return '<h2>核对未签署后结束办理</h2>'+facts([['合同版本','海宁公司委托合同 · 第 1 版'],['签署事实','无签署记录，也无待核验签字材料'],['处理结果','结束本次销售办理，保留合同及批准历史']])+reason()+'<p>提交时会再次核对；若出现签署事实，转主管处理。</p>'+actions('确认结束本次签约办理','end-submit',btn('返回原事项','return'));
 case 'requestStop':return '<h2>交主管核对后处置</h2>'+facts([['合同版本','海宁公司委托合同 · 第 1 版'],['签署情况','客户签字件已提交，尚待核验'],['接收责任','周宁 · 销售主管']])+reason()+'<p>提交后等待主管核对，相关签署办理暂停；不会自动撤销签署或解除合同。</p>'+actions('提交终止核对请求','stop-submit',btn('返回原事项','return'));
 case 'supervisor':return '<h2>核对事实并决定后续办理</h2>'+facts([['销售申请','客户提出暂停本次委托'],['签署情况','客户签字件已提交，核验尚未完成'],['执行与转案','尚未形成；已有事实时必须另行核对']])+btn('查看合同与签署依据','history')+select('本次决定','decision',[['continue','退回继续办理'],['stop','确认停止本次办理']])+note('核对依据与后续说明')+check('已核对签署、执行、转案及尚存责任','confirmed')+'<p>停止本次办理不等于解除合同；已有财务、执行、转案责任不会自动取消。</p>'+actions('记录本次核对结果','supervisor-submit');
 case 'source':return '<h2>确认现有线索的去向</h2>'+facts([['来源请求','已确认收到停止接入请求'],['本线索','尚未分配，无有效首联'],['主管责任','明确继续分配、复查或结束']])+select('后续安排','disposition',[['assign','继续分配'],['review','约时复查'],['close','明确结束本线索']])+(fields.disposition==='assign'?select('合格销售','assignee',[['lin','林悦 · 销售']]):fields.disposition==='review'?input('复查时间','next','datetime-local'):'')+note('安排依据')+'<p>本操作只处理该线索，不改变来源渠道的全局状态。</p>'+actions('确认线索后续安排','source-submit');
 case 'sourceClosed':return '<h2>本线索已明确结束</h2>'+facts([['处理原因',esc(fields.reason)],['主管责任','已完成'],['后续安排','不再恢复首联；来源渠道配置不变']])+btn('查看线索处理记录','history');
 case 'sourceWait':return '<h2>复查已安排</h2>'+facts([['下一责任','周宁 · 销售主管'],['复查时间',esc(fields.next)],['后续事项','到期重新核对线索去向']])+'<p>原期限与历史保留，可从我的待办继续其他事项。</p>'+btn('查看处理记录','history');
 case 'continued':return '<h2>已退回继续办理</h2>'+facts([['下一责任','林悦 · 销售'],['下一事项','按核对意见继续当前签署材料办理'],['期限','沿用原办理期限，历史保留']])+btn('查看本次核对记录','history');
 case 'waiting':return '<h2>本次提交已交接</h2>'+facts([['下一责任','周宁 · 销售主管'],['当前事项','核对本次申请并决定后续'],['到期时间','2026-09-25 17:00'],['你的原责任','本次提交已完成，等待对方办理']])+'<p>无需切换身份，可从我的待办继续其他有权事项。</p>'+btn('查看本次处理记录','history');
 case 'unknown':return '<h2>先确认原操作结果</h2><p role="status">原提交结果尚未确认。保留原回执，不重复提交、不跳到其他事项覆盖本次输入。</p>'+actions('核对原结果','recover');
 case 'failed':return '<h2>本次未提交成功</h2><p role="alert">连接中断发生在发送前，输入仍保留，当前责任未改变。</p>'+note('处理原因')+actions('重新检查并提交','retry');
 case 'unassigned':return '<h2>等待安排合格责任人</h2><p>当前暂无有权主管，已形成可见的责任协调事项。申请和原期限保留，不能由销售代替主管核对。</p>'+btn('查看责任协调记录','history');
 case 'readonly':return '<h2>本次销售办理已停止</h2>'+facts([['处理依据','主管核对后确认停止本次办理'],['历史事实','合同版本、批准、签署及核对记录均保留'],['独立责任','已形成的财务或转案责任继续按其状态处理']])+'<p>此结果不表示合同解除。</p>'+btn('查看处理记录与历史依据','history');
 case 'revoked':return '<h2>当前权限已变化</h2><p>客户及合同内容已清除，请重新选择当前任职有权事项。</p>'+actions('查看我的待办','tasks');
 case 'quoteReady':return '<h2>报价准备已接管</h2>'+facts([['原跟进','已由报价准备接管'],['当前事项','准备本次收费方案'],['负责人','林悦 · 销售']])+'<p>沿用已冻结的报价准备卡继续填写、形成报价和提交审批。本稿不生成真实报价。</p>'+btn('查看本次处理记录','history');
 case 'contactReady':return '<h2>线索已分配</h2>'+facts([['下一责任','林悦 · 销售'],['后续事项','联系本次新分配线索'],['原主管事项','已完成']])+'<p>主管可继续我的待办；不自动切换为销售身份。</p>'+btn('查看分配记录','history');
 case 'followup':return '<h2>后续联系已安排</h2>'+facts([['本次结果','联系尝试已记录'],['有效进展','未增加'],['下一时间',esc(fields.next||'2026-09-25 10:00')],['下一责任','原销售负责人']])+'<p>到期后待办自动恢复，原到期及逾期历史保留。</p>'+btn('查看处理记录','history');
 default:return '<h2>本次安排已记录</h2><p>当前事项已完成，下一责任和时间保留在处理记录中。</p>'+btn('查看处理记录','history');
 }
}
function render(){
 const finished=['readonly','sourceClosed','contactReady','waiting','continued','followup','done','sourceWait'].includes(scene);
 const known=origin==='source'?'来源停止请求已收到</li><li>现有线索尚待明确去向':['endUnsigned','requestStop','supervisor','readonly'].includes(origin)?'委托合同 · 第 1 版</li><li>'+ (origin==='endUnsigned'?'尚无签署记录或签字材料':'客户签字件及核对记录保留'):'客户希望了解委托服务与费用</li><li>'+ (['progress','choose','missing','customer'].includes(scene)?'客户与需求尚待确认':'客户与需求已确认');
 const locked=scene==='unknown',supervisor=['supervisor','source'].includes(origin),role=supervisor?'周宁 · 销售主管':'林悦 · 销售';
 app.innerHTML='<header class="app-header"><div class="brand">'+icon('Scales-green')+'律所工作助手</div><div class="session"><button class="workbench-control" data-action="ledger" '+(locked?'disabled':'')+'>商机台账</button><span>'+role+'</span></div></header><main id="main" class="workbench"><div class="task-header"><div class="today-summary">'+icon('CheckCircle')+'<p>'+(finished?'本次结果已记录，可继续其他待办。':'请先完成当前责任事项。')+'</p><button class="refresh-button workbench-control" data-action="refresh" aria-label="刷新当前责任" '+(locked?'disabled':'')+'>↻<span>刷新</span></button></div><div class="my-tasks">'+tasks()+'</div></div>'+(queue?'<section class="queue"><h2>我的待办</h2><p>仅展示当前任职有权事项。</p>'+btn('继续当前事项','resume')+btn('明川公司 · 客户跟进','other')+'</section>':'')+'<article class="work-card"><div class="context"><p class="eyebrow">当前责任</p><h1>'+titles[scene]+'</h1>'+(scene==='revoked'?'':'<p class="subject">海宁实业有限公司</p><p class="owner-line">'+icon('User')+'<span>'+role+' · 今天 17:00 前</span></p><p class="known-title">已知信息</p><ul class="facts"><li>'+known+'</li></ul><details class="history"><summary>业务与历史依据</summary><p>原责任、期限及已形成事实均保留；结束办理不代表撤销历史事实。</p>'+btn('查看处理记录','history')+'</details>')+'</div><section class="card-action">'+content()+(error?'<p role="alert">'+esc(error)+'</p>':'')+(saved?'<p role="status">审阅草稿已保留，尚未提交业务。</p>':'')+'</section></article></main>';
}
function move(next){scene=next;dirty=false;error='';saved=false;render();}
function fail(message){error=message;let p=document.querySelector('[role="alert"]');if(!p){p=document.createElement('p');p.role='alert';document.querySelector('.card-action').append(p);}p.textContent=message;}
function modal(html){document.querySelector('#dialog-body').innerHTML=html;dialog.showModal();}
function ready(ids){if(ids.some(id=>!fields[id]||!String(fields[id]).trim())){fail('请补齐本次所需信息并核对确认。');return false;}return true;}
function navigate(target){if(dirty){modal('<h2>保留当前填写内容？</h2><p>离开不会提交业务，也不会结束原责任。</p>'+actions('保留草稿并前往','leave:'+target,btn('继续填写','close')));return;}move(target);}
document.addEventListener('input',e=>{if(e.target.closest('.card-action')){fields[e.target.id]=e.target.type==='checkbox'?e.target.checked:e.target.value;dirty=true;}});
document.addEventListener('change',e=>{if(e.target.id==='disposition'){fields.disposition=e.target.value;dirty=true;render();}});
document.addEventListener('click',e=>{
 const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='close'){dialog.close();return;}
 if(a.startsWith('leave:')){dialog.close();move(a.slice(6));return;}
 if(a==='history'){modal('<h2>处理记录与依据</h2>'+facts([['原责任','销售跟进／主管处置'],['原期限','2026-09-24 17:00'],['处理依据','准确业务版本、操作者及原因'],['后继','与当前结果一致的责任或明确终点']])+btn('关闭','close'));return;}
 if(a==='ledger'){modal('<h2>业务管理入口</h2><p>沿用既有商机／合同台账与左侧导航。本稿只补新增办理卡，不改管理页。</p>'+btn('关闭','close'));return;}
 if(a==='tasks'||a==='resume'){if(dirty){fail('请先保留当前草稿或完成填写，再切换事项。');return;}queue=!queue;render();return;}
 if(a==='other'){navigate('progress');return;}
 if(a==='refresh'){if(dirty){fail('当前内容尚未保存，请先处理本次输入。');return;}render();return;}
 if(a==='return'){navigate(origin==='customer'?'progress':origin);return;}
 if(a==='customer'){returnTo=scene==='missing'?'prepareQuote':'progress';navigate('customer');return;}
 if(a==='customer-submit'){if(ready(['customer','reason','confirmed']))move(returnTo);return;}
 if(a==='quote-submit'){move('quoteReady');return;}
 if(a==='direct-submit'){if(ready(['reason','amount','confirmed'])&&Number(fields.amount)>0)move('waiting');else fail('请说明申请原因、填写有效收费金额并核对。');return;}
 if(a==='progress-submit'){if(ready(['reason','kind','next'])){move('followup');document.querySelector('.card-action').innerHTML='<h2>本次有效进展已记录</h2>'+facts([['当前事项','本次跟进已完成'],['下一责任','林悦 · 销售'],['下次跟进',esc(fields.next)]])+'<p>到期继续跟进，原处理记录保留。</p>';};return;}
 if(a==='attempt-submit'){if(ready(['attemptKind','at','reason','next'])){if(new Date(fields.next)<=new Date(fields.at)){fail('下次联系时间应晚于实际联系时间。');return;}move('followup');}return;}
 if(a==='end-submit'){if(ready(['reason','confirmed']))move('readonly');return;}
 if(a==='stop-submit'){if(ready(['reason','confirmed']))move('waiting');return;}
 if(a==='supervisor-submit'){if(ready(['decision','reason','confirmed']))move(fields.decision==='stop'?'readonly':'continued');return;}
 if(a==='source-submit'){if(ready(['disposition','reason',...(fields.disposition==='assign'?['assignee']:fields.disposition==='review'?['next']:[])]))move(fields.disposition==='assign'?'contactReady':fields.disposition==='close'?'sourceClosed':'sourceWait');return;}
 if(a==='recover'){move('waiting');return;}
 if(a==='retry'){if(ready(['reason']))move('waiting');return;}
 if(titles[a])navigate(a);
});
render();

