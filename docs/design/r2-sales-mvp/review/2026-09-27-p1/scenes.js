const scenes={"check": "01 非先款合同 · 核对执行条件", "prepay": "02 先款合同 · 等待财务", "payment": "03 财务 · 核对到账", "partial": "04 部分到账 · 原责任继续", "mismatch": "05 凭证不符 · 退回补正", "ready": "06 条件成立 · 交转案准备", "unassigned": "07 暂无合格财务负责人", "revoked": "08 财务权限已变化", "unknown": "09 提交结果待确认"};
const picker=document.querySelector('#scene'),frame=document.querySelector('iframe');for(const [key,label] of Object.entries(scenes)){const option=document.createElement('option');option.value=key;option.textContent=label;picker.append(option);}picker.onchange=()=>frame.src='app.html?scene='+picker.value;document.querySelector('#width').onchange=e=>frame.style.width=e.target.value;

document.querySelector('#scene').value='payment';
