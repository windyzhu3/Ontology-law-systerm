import { useEffect,useRef,useState } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import type { IdentityApi } from "./identityApi";
import type { IdentityCommand } from "./useIdentityCommand";
import { useIdentityOptions } from "./useIdentityOptions";
import { IdentityField,IdentityOptionField } from "./IdentityFormFields";
import { authorityLabels } from "./identityLabels";
type Code=components["schemas"]["GrantableAuthorityCodeV1"];
export function authorityGroup(code:Code){
 if(/^(LEAD_|SOURCE_INTAKE_|SALES_CONTACT_)/.test(code))return "线索";
 if(code.startsWith("CONTRACT_"))return "合同";
 if(code.startsWith("PAYMENT_"))return "财务";
 if(/^(TRANSFER_|MATTER_)/.test(code))return "案管";
 if(code.startsWith("IDENTITY_"))return "系统管理";
 if(code.startsWith("TEAM_"))return "团队查询";
 return "商机";
}
export function BulkAuthorityGrantForm({session,api,command}:{session:WorkbenchSession;api:IdentityApi;command:IdentityCommand}){
 const person=useIdentityOptions(session,api,"AUTHORITY_GRANTS","APPOINTMENT"),organization=useIdentityOptions(session,api,"AUTHORITY_GRANTS","ORGANIZATION");
 const [selected,setSelected]=useState<Code[]>([]),[start,setStart]=useState(""),[end,setEnd]=useState(""),[errors,setErrors]=useState<Record<string,string>>({});
 const form=useRef<HTMLFormElement>(null);useEffect(()=>{form.current?.querySelector("select")?.focus();},[]);
 const choices=person.authorityCodes,active=selected.filter(code=>choices.includes(code));
 const groups=[...new Set(choices.map(authorityGroup))];
 const dirty=()=>command.setDirty(true);
 function review(){
  const next:Record<string,string>={};
  if(!person.selected)next.person="请选择本页获权任职。";
  if(!organization.selected)next.organization="请选择本页获权组织。";
  if(!active.length)next.authorities="请至少选择一项当前可授予权限。";
  if(!start||!Number.isFinite(Date.parse(start)))next.start="请填写有效生效时间。";
  if(end&&(!Number.isFinite(Date.parse(end))||Date.parse(end)<=Date.parse(start)))next.end="结束时间须晚于生效时间。";
  setErrors(next);if(Object.keys(next).length)return;
  const validFrom=new Date(start).toISOString(),validUntil=end?new Date(end).toISOString():null;
  command.prepareBatch(active.map(authorityCode=>({commandType:"CREATE_AUTHORITY_GRANT",body:{appointmentId:person.selected,authorityCode,scopeOrganizationId:organization.selected,validFrom,validUntil}})),{appointment:person.items.find(item=>item.id===person.selected)!.label,organization:organization.items.find(item=>item.id===organization.selected)!.label,validFrom,validUntil});
 }
 return <form ref={form} className="identity-edit-form" noValidate onSubmit={event=>{event.preventDefault();review();}}>
  <h2>建立直接授权</h2><p className="identity-form-help">选择一个任职及多项权限，统一填写组织范围和有效期，再核对完整清单。</p>
  <fieldset disabled={!command.canSubmit}>
   <IdentityOptionField label="授权任职" options={person} error={errors.person} onDirty={dirty}/>
   <IdentityOptionField label="组织范围" options={organization} error={errors.organization} onDirty={dirty}/>
   <p>已选择 {active.length} 项权限</p>{errors.authorities&&<p role="alert" className="identity-field-error">{errors.authorities}</p>}
   <div className="identity-authority-groups" aria-label="可授予权限">
    {groups.map(group=>{const codes=choices.filter(code=>authorityGroup(code)===group),all=codes.every(code=>active.includes(code));return <fieldset key={group} className="identity-authority-group" aria-label={`${group}权限`}>
     <legend>{group}</legend><label className="identity-authority-choice identity-authority-select-all"><input type="checkbox" checked={all} onChange={()=>{setSelected(all?active.filter(code=>!codes.includes(code)):[...new Set([...active,...codes])]);dirty();}} aria-label={`全选${group}权限`}/><span>全选本组（{codes.length} 项）</span></label>
     {codes.map(code=><label className="identity-authority-choice" key={code}><input type="checkbox" value={code} data-authority-code={code} checked={active.includes(code)} onChange={()=>{setSelected(active.includes(code)?active.filter(item=>item!==code):[...active,code]);dirty();}}/><span>{authorityLabels[code]}</span></label>)}
    </fieldset>;})}
    {!choices.length&&!person.loading&&!person.error&&<p>当前没有可授予权限，请重读任职候选。</p>}
   </div>
   <p className="identity-form-help">本机时区：{Intl.DateTimeFormat().resolvedOptions().timeZone}。提交时转换为 UTC 时间。</p>
   <IdentityField label="生效时间" type="datetime-local" value={start} error={errors.start} onChange={value=>{setStart(value);dirty();}}/>
   <IdentityField label="结束时间（可留空）" type="datetime-local" value={end} error={errors.end} onChange={value=>{setEnd(value);dirty();}}/>
   <p className="identity-form-help">权限仍逐项校验任职、组织范围和有效期，不会覆盖或续期已有授权。</p>
  </fieldset>
  <div className="identity-form-actions"><button className="identity-primary" disabled={!command.canSubmit}>核对授权清单</button><button type="button" disabled={command.locked} onClick={command.cancel}>取消</button></div>
 </form>;
}
