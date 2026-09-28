import {transferText,useTransferSubmission} from './useTransferSubmission';
const labels={CLEAR:'本次审查通过',NEED_INFO:'需要补充主体或材料',BLOCKED:'存在未解决冲突，暂不通过'} as const;
type Outcome=keyof typeof labels;
export type TransferConflictInput={outcome:Outcome;explanation:string;scopeChecked:true};
export function TransferConflictReview({outcomes,submit,onDirty,disabled=false}:{outcomes:Outcome[];submit:(input:TransferConflictInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean}){
 const {send,locked,error}=useTransferSubmission(submit,disabled);
 return <><h2>核对本次转案的冲突审查范围</h2><form onChange={onDirty} onSubmit={e=>{e.preventDefault();const data=new FormData(e.currentTarget);void send(()=>{
  const outcome=String(data.get('outcome')) as Outcome;
  if(!outcomes.includes(outcome)||!Object.hasOwn(labels,outcome))throw new Error('请选择当前审查允许的结果。');
  if(data.get('checked')!=='on')throw new Error('请先核对本次准确快照的主体和冲突依据。');
  return {outcome,explanation:transferText(data.get('explanation')),scopeChecked:true};
 });}}><fieldset disabled={locked}><legend className="sr-only">独立转案前冲突审查</legend>
 <label className="field"><span className="field-label">审查结果</span><select name="outcome" required defaultValue=""><option value="">请选择结果</option>{Object.entries(labels).filter(([value])=>outcomes.includes(value as Outcome)).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
 <label className="field"><span className="field-label">审查说明</span><textarea name="explanation" required rows={3}/></label>
 <label className="checked-label contract-checkbox"><input name="checked" type="checkbox" required/>已对照本次准确快照核对主体和冲突依据</label>
 <div className="ledger-detail-actions"><button type="submit" className="primary">记录本次审查结果</button></div></fieldset>{error&&<p role="alert">{error}</p>}</form>
 <p>销售不能自行完成本项独立审查；审查未通过时，案管不能接收生成案件。</p></>;
}
