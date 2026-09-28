import {transferText,useTransferSubmission} from './useTransferSubmission';
import type {TransferMaterial} from './TransferPreparation';
export type TransferReturnItem={id:string;label:string;reason:string;requiresMaterial?:boolean};
export type TransferCorrectionInput={corrections:{returnItemId:string;response:string;material?:{versionId:string;sha256:string}}[]};
export function TransferCorrection({items,materials,submit,onDirty,disabled=false}:{items:TransferReturnItem[];materials:TransferMaterial[];submit:(input:TransferCorrectionInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean}){
 const {send,locked,error}=useTransferSubmission(submit,disabled);
 return <><h2>按案管指出的缺项补正</h2><form onChange={onDirty} onSubmit={e=>{e.preventDefault();const data=new FormData(e.currentTarget);void send(()=>{
  if(!items.length||new Set(items.map(i=>i.id)).size!==items.length)throw new Error('请重新读取本次完整退回项目。');
  return {corrections:items.map(item=>{
   const material=materials.find(m=>m.id===data.get(`material-${item.id}`));if(item.requiresMaterial!==false&&!material)throw new Error('请选择本商机当前已接收的补充材料。');
   return {returnItemId:item.id,response:transferText(data.get(`response-${item.id}`)),...(material?{material:{versionId:material.id,sha256:material.sha256}}:{})};
  })};
 });}}><fieldset disabled={locked}><legend className="sr-only">逐项补正本次退回材料</legend>
 {items.map(item=><div key={item.id}>
 <dl className="detail-facts"><div><dt>退回项目</dt><dd>{item.label}</dd></div><div><dt>案管说明</dt><dd>{item.reason}</dd></div></dl>
 {item.requiresMaterial!==false&&<label className="field"><span className="field-label">补充{item.label}</span><select name={`material-${item.id}`} required defaultValue=""><option value="">请选择已接收材料</option>{materials.map(m=><option key={m.id} value={m.id}>{m.fileName} · 已接收</option>)}</select></label>}
 <label className="field"><span className="field-label">逐项回应：{item.label}</span><textarea name={`response-${item.id}`} required rows={3} placeholder="说明如何回应本次缺项"/></label>
 </div>)}
 <div className="ledger-detail-actions"><button type="submit" className="primary">补正并重新提交</button></div></fieldset>{error&&<p role="alert">{error}</p>}</form>
 <p>重新提交形成新快照，旧提交与退回记录保留。案管核对新快照后才能接收。</p></>;
}
