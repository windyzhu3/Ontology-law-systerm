// N1 field amendment only: extend approved N card without introducing new product styles.
titles.arrangementReturned='补正本版签署登记';
let arrangement=[{party:'海宁实业有限公司',method:'签字并盖章',required:true,basis:'第八条签署要求（合成示例）'},{party:'本律所',method:'盖章',required:true,basis:'第八条签署要求（合成示例）'}];
const parties=['海宁实业有限公司','本律所'];
const arrangementFields=()=>arrangement.map((s,i)=>`<fieldset><legend>签署要求 ${i+1}</legend><label class="field"><span class="field-label">签署主体</span><select data-slot="${i}" data-field="party">${parties.map(p=>`<option${p===s.party?' selected':''}>${p}</option>`).join('')}</select></label><label class="field"><span class="field-label">批准合同要求</span><select data-slot="${i}" data-field="method">${['签字','盖章','签字并盖章'].map(m=>`<option${m===s.method?' selected':''}>${m}</option>`).join('')}</select></label><label class="checked-label contract-checkbox"><input type="checkbox" data-slot="${i}" data-field="required" ${s.required?'checked':''}>合同明确要求必须完成</label><label class="field"><span class="field-label">条款依据</span><input data-slot="${i}" data-field="basis" value="${esc(s.basis)}"></label>${arrangement.length>1?btn('删除要求 '+(i+1),'remove-'+i):''}</fieldset>`).join('');
const nContent=content;
content=function(){
 if(scene==='prepare'||scene==='arrangementReturned')return '<h2>对照批准正文登记签署安排</h2>'+(scene==='arrangementReturned'?'<p class="feedback">核验意见：批准合同还要求律所盖章，请补齐登记。合同正文未变，不要求重新生成合同。</p>':'')+version()+'<p>仅登记本版已批准条款；实际签署完成仍需授权核验。</p>'+arrangementFields()+btn('添加签署要求','add-slot')+'<p>找不到批准主体，或需要修改合同条款时，应返回合同修订。</p>'+check('已逐项对照批准正文，登记完整且未改变主体或条款','confirmed')+actions('确认安排并准备材料','confirm-arrangement',btn('保存草稿','save'));
 if(scene==='review')return '<h2>核对登记安排及签署证据</h2>'+version()+facts([['销售登记','客户签字并盖章；律所盖章；均为合同必需'],['条款依据','第八条签署要求（合成示例）']])+check('登记的主体、方式及必需项与批准正文完整一致','arrangement-ok')+materials()+check('签署正文与批准版本一致','body-ok')+check('签署人身份及代表权限有效','authority-ok')+check('本次签字、盖章及文件完整性符合要求','evidence-ok')+'<label class="field"><span class="field-label">核验结果</span><select id="decision"><option value="">请选择</option><option value="pass">本次签署核验通过</option><option value="arrangement">同版签署安排登记需补正</option><option value="supplement">同版本补充材料</option><option value="changed">正文或主体变化，返回合同修订</option></select></label>'+note('核验说明')+actions('记录本次核验结果','decide');
 return nContent();
};
document.addEventListener('input',e=>{const i=e.target.dataset.slot,k=e.target.dataset.field;if(i!==undefined&&k){arrangement[Number(i)][k]=k==='required'?e.target.checked:e.target.value;dirty=true;}},true);
document.addEventListener('click',e=>{
 const a=e.target.closest('[data-action]')?.dataset.action;if(!a)return;
 if(a==='add-slot'){arrangement.push({party:parties[0],method:'签字',required:true,basis:''});dirty=true;render();return;}
 if(a.startsWith('remove-')){arrangement.splice(Number(a.slice(7)),1);dirty=true;render();return;}
 if(a==='confirm-arrangement'){
  if(!arrangement.some(s=>s.required)||arrangement.some(s=>!s.basis.trim())||new Set(arrangement.map(s=>s.party)).size!==arrangement.length||!document.querySelector('#confirmed').checked){fail('请逐项登记条款依据、避免重复主体，至少保留一项必需要求，并确认登记完整。');return;}
  move('submit');return;
 }
 if(a==='decide'){
  const d=document.querySelector('#decision').value;
  if(d==='pass'&&!document.querySelector('#arrangement-ok').checked){e.stopImmediatePropagation();fail('核验通过前，请对照批准正文确认登记完整。');return;}
  if(d==='arrangement'){
   e.stopImmediatePropagation();if(!document.querySelector('#note').value.trim()){fail('请说明需补正的签署安排。');return;}
   move('returnedEvidence');document.querySelector('.card-action').innerHTML='<h2>登记补正意见已记录</h2><p>已交销售按本版批准正文补正。补正安排会重新核验，不沿用旧安排的完成结论。</p>'+btn('查看本次处理记录','history');
  }
 }
},true);
render();
