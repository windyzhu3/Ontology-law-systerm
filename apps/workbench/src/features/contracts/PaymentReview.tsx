import {useRef,useState} from 'react';
import {moneyMinor} from '../../lib/quotesTransport';

type Material={id:string;sha256:string;fileName:string};
export type PaymentReviewInput={decision:'RETURN';explanation:string}|{decision:'CONFIRM';explanation:string;materialVersionId:string;materialSha256:string;transactionReference:string;amountMinor:number;currency:'CNY';receivedAt:string;attributionChecked:true};
/** P1 form only: the owning workcard supplies current authority, recovery and immutable selectors. */
export function PaymentReview({accountLabel,materials,submit,onDirty,disabled=false,requiredMinor,confirmedMinor,remainingMinor}:{requiredMinor?:number|null;confirmedMinor?:number;remainingMinor?:number|null;accountLabel:string;materials:Material[];submit:(input:PaymentReviewInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean}){
 const [decision,setDecision]=useState<'CONFIRM'|'RETURN'>('CONFIRM'),[busy,setBusy]=useState(false),[error,setError]=useState(''),[uncertain,setUncertain]=useState(false);const pending=useRef(false);
 async function save(form:HTMLFormElement){
  if(disabled||pending.current||uncertain)return;
  const data=new FormData(form),explanation=String(data.get('explanation')??'').trim();let input:PaymentReviewInput;
  try{
   if(!explanation||[...explanation].length>2000)throw Error('请填写核对说明，最多 2000 字');
   if(decision==='RETURN')input={decision,explanation};
   else{
    const material=materials.find(m=>m.id===data.get('material')),transactionReference=String(data.get('reference')??'').trim(),amountMinor=moneyMinor(String(data.get('amount')??'')),receivedAt=new Date(String(data.get('receivedAt')??''));
    if(!material||!accountLabel)throw Error('请选择本商机已接收的凭证，并确认收款账户已配置');
    if(!transactionReference||[...transactionReference].length>200||/[\p{Cc}\p{Cf}\p{Cs}]/u.test(transactionReference))throw Error('请填写有效的银行交易流水号');
    if(amountMinor<=0)throw Error('本笔实收金额须大于零');
    if(!Number.isFinite(receivedAt.getTime())||receivedAt.getTime()>Date.now())throw Error('请填写有效的实际到账时间');
    if(data.get('checked')!=='on')throw Error('请先人工核对付款归属和凭证');
    input={decision,explanation,materialVersionId:material.id,materialSha256:material.sha256,transactionReference,amountMinor,currency:'CNY',receivedAt:receivedAt.toISOString(),attributionChecked:true};
   }
  }catch(e){setError(e instanceof Error?e.message:'请检查核对信息');return;}
  pending.current=true;setBusy(true);setError('');
  try{await submit(input);}catch{setUncertain(true);setError('提交结果尚未确认，请通过当前工作卡核对原提交结果。');}finally{pending.current=false;setBusy(false);}
 }
 return <><h2>核对金额、归属及凭证</h2><dl className="detail-facts">{requiredMinor!=null&&<div><dt>约定转案前到账</dt><dd>¥{(requiredMinor/100).toFixed(2)}</dd></div>}{confirmedMinor!==undefined&&<div><dt>本合同已核实到账</dt><dd>¥{(confirmedMinor/100).toFixed(2)}</dd></div>}{remainingMinor!=null&&remainingMinor>0&&<div><dt>距离约定首款尚缺</dt><dd>¥{(remainingMinor/100).toFixed(2)}</dd></div>}<div><dt>收款账户</dt><dd>{accountLabel||'尚未配置收款账户'}</dd></div></dl>
 <form onChange={onDirty} onSubmit={e=>{e.preventDefault();void save(e.currentTarget);}}>
 <fieldset disabled={disabled||busy||uncertain}><legend className="sr-only">收款核对</legend>
 <label className="field"><span className="field-label">核对结果</span><select value={decision} onChange={e=>{setDecision(e.target.value as typeof decision);setError('');}}><option value="CONFIRM">确认对应本合同到账</option><option value="RETURN">凭证或归属不符，退回补正</option></select></label>
 {decision==='CONFIRM'&&<>
 <label className="field"><span className="field-label">本笔核对凭证</span><select name="material" required defaultValue=""><option value="">请选择本商机已接收的凭证</option>{materials.map(m=><option key={m.id} value={m.id}>{m.fileName} · 已接收</option>)}</select></label>
 <label className="field"><span className="field-label">银行交易流水号</span><input name="reference" required maxLength={400}/></label>
 <label className="field"><span className="field-label">本笔实收金额（元）</span><input name="amount" type="number" required min="0.01" step="0.01"/></label>
 <label className="field"><span className="field-label">实际到账时间</span><input name="receivedAt" type="datetime-local" required/></label>
 <label className="checked-label contract-checkbox"><input name="checked" type="checkbox" required/>已人工核对付款主体、收款账户、币种、金额及凭证</label>
 </>}
 <label className="field"><span className="field-label">核对说明</span><textarea name="explanation" required rows={3} placeholder="说明本笔归属；退回时写明需要补充的材料"/></label>
 <div className="ledger-detail-actions"><button className="primary" type="submit">记录核对结果</button></div></fieldset>
 {error&&<p role="alert">{error}</p>}
 </form><p>只累计已经核实的本合同款项。没有可核对的凭证时，可说明缺项并退回销售补正。</p></>;
}
