import {useState} from 'react';
import {transferText,useTransferSubmission} from './useTransferSubmission';

const requirements={CLIENT_IDENTITY:'委托主体证明',SIGNATURE_ARCHIVE:'完整签署归档',HANDOVER_EXPLANATION:'交接说明',OTHER_MATERIAL:'其他材料'} as const;
type Requirement=keyof typeof requirements;
export type TransferReviewInput={decision:'ACCEPT';acceptanceChecked:true;explanation:string}|{decision:'RETURN';requirement:Requirement;explanation:string};

/** Approved Q intake action only. No local case creation or classification. */
export function TransferReview({canAccept,submit,onDirty,disabled=false}:{canAccept:boolean;submit:(input:TransferReviewInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean}){
 const [decision,setDecision]=useState<'ACCEPT'|'RETURN'>(canAccept?'ACCEPT':'RETURN');
 const selected=canAccept?decision:'RETURN';
 const {send,locked,error}=useTransferSubmission(submit,disabled);
 return <><h2>核对准确版本和材料完整性</h2>
 <form onChange={onDirty} onSubmit={e=>{e.preventDefault();const data=new FormData(e.currentTarget);void send(()=>{
  const explanation=transferText(data.get('explanation'));
  if(selected==='ACCEPT'){
   if(!canAccept||data.get('checked')!=='on')throw new Error('请先核对本次准确快照、合同和材料。');
   return {decision:'ACCEPT',acceptanceChecked:true,explanation};
  }
  const requirement=String(data.get('requirement')) as Requirement;
  if(!Object.hasOwn(requirements,requirement))throw new Error('请选择需要补正的项目。');
  return {decision:'RETURN',requirement,explanation};
 });}}>
 <fieldset disabled={locked}><legend className="sr-only">转案接收核对</legend>
 <label className="field"><span className="field-label">接收结果</span><select value={selected} onChange={e=>setDecision(e.target.value as typeof decision)}>
 {canAccept&&<option value="ACCEPT">接收并生成案件</option>}<option value="RETURN">退回补正</option></select></label>
 {selected==='ACCEPT'?<label className="checked-label contract-checkbox"><input name="checked" type="checkbox" required/>已核对本次快照、委托主体、批准合同和材料，确认可以接收</label>:
 <label className="field"><span className="field-label">需要补正的项目</span><select name="requirement" required defaultValue=""><option value="">请选择缺项</option>{Object.entries(requirements).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>}
 <label className="field"><span className="field-label">处理说明</span><textarea name="explanation" required rows={3} placeholder="退回时写清缺项及需要补充的内容"/></label>
 <div className="ledger-detail-actions"><button className="primary" type="submit">记录接收结果</button></div></fieldset>
 {error&&<p role="alert">{error}</p>}</form>
 <p>接收将生成唯一案件，并交案管确认业务分类。旧版本或审查未通过时不能接收。</p></>;
}
