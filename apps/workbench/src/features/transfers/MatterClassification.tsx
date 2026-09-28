import {transferText,useTransferSubmission} from './useTransferSubmission';
const categories={GENERAL:'综法业务',ENFORCEMENT:'执行业务',OTHER:'其他业务'} as const;
export type MatterClassificationInput={matterId:string;classification:keyof typeof categories;receivingAppointmentId:string;explanation:string};
export function MatterClassification({matter,receivers,submit,onDirty,disabled=false,correction}:{matter:{id:string;number:string}|null;receivers:{id:string;label:string}[];submit:(input:MatterClassificationInput)=>Promise<boolean>;onDirty:()=>void;disabled?:boolean;correction?:{category:keyof typeof categories;recipientId:string|null;recipientLabel:string}}){
 const {send,locked,error}=useTransferSubmission(submit,disabled);
 if(!matter)return <p>案管接收生成案件后，才能确认业务分类及承接。</p>;
 return <><h2>{correction?'核对后更正分类及承接':'为已接收案件确认分类及承接'}</h2><dl className="detail-facts"><div><dt>案件编号</dt><dd>{matter.number}</dd></div>{correction&&<><div><dt>当前分类</dt><dd>{categories[correction.category]}</dd></div><div><dt>当前承接人</dt><dd>{correction.recipientLabel}</dd></div></>}</dl>
 <form onChange={onDirty} onSubmit={e=>{e.preventDefault();const data=new FormData(e.currentTarget);void send(()=>{
  const classification=String(data.get('classification')) as keyof typeof categories,receiver=receivers.find(r=>r.id===data.get('receiver'));
  if(!Object.hasOwn(categories,classification)||!receiver)throw new Error('请选择业务分类和当前有权承接的任职。');
  return {matterId:matter.id,classification,receivingAppointmentId:receiver.id,explanation:transferText(data.get('explanation'))};
 });}}><fieldset disabled={locked}><legend className="sr-only">案件分类及承接</legend>
 <label className="field"><span className="field-label">业务分类</span><select name="classification" required defaultValue={correction?.category??''}><option value="">请选择业务分类</option>{Object.entries(categories).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
 <label className="field"><span className="field-label">承接人</span><select name="receiver" required defaultValue={receivers.some(r=>r.id===correction?.recipientId)?correction!.recipientId!:''}><option value="">请选择有权承接的任职</option>{receivers.map(r=><option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
 <label className="field"><span className="field-label">{correction?'更正说明':'分类说明'}</span><textarea name="explanation" required rows={3}/></label>
 <div className="ledger-detail-actions"><button type="submit" className="primary">{correction?'确认更正':'确认分类及承接'}</button></div></fieldset>{error&&<p role="alert">{error}</p>}</form>
 <p>更新分类仍使用同一个案件身份，不重复转案或生成第二案件。</p></>;
}
