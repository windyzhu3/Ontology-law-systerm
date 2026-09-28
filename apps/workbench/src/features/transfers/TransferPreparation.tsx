import {transferText,useTransferSubmission} from './useTransferSubmission';

export type TransferMaterial={id:string;sha256:string;fileName:string};
export type TransferPreparationInput={clientIdentity:{versionId:string;sha256:string};signatureArchive:{versionId:string;sha256:string};explanation:string;consistencyChecked:true;corrections:[]};
export function TransferPreparation({materials,submit,onDirty,disabled=false}:{materials:TransferMaterial[];submit:(input:TransferPreparationInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean}){
 const {send,locked,error}=useTransferSubmission(submit,disabled);
 function material(value:FormDataEntryValue|null){const exact=materials.find(m=>m.id===value);if(!exact)throw new Error('请选择本商机当前已接收的材料。');return {versionId:exact.id,sha256:exact.sha256};}
 return <><h2>核对合同、客户及转案材料</h2><form onChange={onDirty} onSubmit={e=>{e.preventDefault();const data=new FormData(e.currentTarget);void send(()=>{
  if(data.get('checked')!=='on')throw new Error('请先核对材料与批准合同、客户主体的一致性。');
  return {clientIdentity:material(data.get('identity')),signatureArchive:material(data.get('archive')),explanation:transferText(data.get('explanation')),consistencyChecked:true,corrections:[]};
 });}}><fieldset disabled={locked}><legend className="sr-only">本次转案资料</legend>
 {([['identity','委托主体证明'],['archive','完整签署归档']] as const).map(([name,label])=><label className="field" key={name}><span className="field-label">{label}</span><select name={name} required defaultValue=""><option value="">请选择已接收材料</option>{materials.map(m=><option key={m.id} value={m.id}>{m.fileName} · 已接收</option>)}</select></label>)}
 <label className="field"><span className="field-label">交接说明</span><textarea name="explanation" required rows={3} placeholder="说明委托范围、交接重点及需要案管关注的事项"/></label>
 <label className="checked-label contract-checkbox"><input name="checked" type="checkbox" required/>已核对本次材料与批准合同、客户主体一致</label>
 <div className="ledger-detail-actions"><button type="submit" className="primary">提交案管接收</button></div></fieldset>{error&&<p role="alert">{error}</p>}</form>
 <p>案管接收后才生成案件，此处不选择综法、执行等业务分类。</p></>;
}
