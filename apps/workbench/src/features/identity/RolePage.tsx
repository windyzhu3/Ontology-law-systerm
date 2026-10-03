import { useCallback } from "react";
import type { components } from "../../generated/api/schema";
import type { WorkbenchSession } from "../../lib/api";
import type { IdentityApi } from "./identityApi";
import type { IdentityCommand } from "./useIdentityCommand";
import { useIdentityList } from "./useIdentityList";
import { DetailRow, DetailRows, IdentityActions, IdentityListPage, InfoNote, StatusBadge } from "./IdentityListPage";
import { identityDetailEditor } from "./IdentityDetailEditor";
import { IdentityCommandFeedback } from "./IdentityActionConfirmation";
type Role = components["schemas"]["AppointmentRoleV1"];
export function RolePage({session,api,command}:{session:WorkbenchSession;api:IdentityApi;command:IdentityCommand}) {
 const load=useCallback((query:{limit:number;cursor?:string},signal:AbortSignal)=>api.listAppointmentRoles(session,query,signal) as Promise<{data:{items:Role[];nextCursor:string|null}}>,[api,session]);
 const list=useIdentityList(session,load);command.bindRefresh(list.reload);
 const selected=list.items?.find(item=>item.id===list.selectedId)??null;
 return <IdentityListPage title="岗位管理" description="配置租户内岗位名称与启用状态，岗位本身不授予权限" createLabel="新增岗位"
 count={list.items?.length??null} loading={list.loading} error={list.error} empty={list.items?.length===0} canPrevious={list.canPrevious} canNext={list.canNext}
 onPrevious={()=>command.leave(list.previous)} onNext={()=>command.leave(list.next)} onReload={()=>command.leave(()=>void list.reload())}
 onCreate={()=>command.open({kind:"create",page:"ROLES"})} editing={!!command.editor&&command.editor.kind!=="action"}
 feedback={command.editor?.kind!=="action"&&<IdentityCommandFeedback command={command}/>}
 list={<table className="identity-table"><thead><tr><th>岗位名称</th><th>岗位代码</th><th>状态</th></tr></thead><tbody>{list.items?.map(role=><tr key={role.id} className={role.id===list.selectedId?"selected":undefined}><td><button className="identity-row-button" onClick={()=>command.leave(()=>list.setSelectedId(role.id))}>{role.displayName}</button></td><td>{role.code}</td><td><StatusBadge state={role.state} label={role.state==="ACTIVE"?"已启用":"已停用"}/></td></tr>)}</tbody></table>}
 detail={identityDetailEditor(command,session,api)??(selected&&<>
 <div className="identity-detail-title"><h2>{selected.displayName}</h2><StatusBadge state={selected.state} label={selected.state==="ACTIVE"?"已启用":"已停用"}/></div>
 <DetailRows><DetailRow label="岗位代码">{selected.code}</DetailRow></DetailRows>
 <InfoNote>岗位代码创建后不可修改。停用只禁止新增任职，已有任职、授权和业务责任继续有效；权限须在直接授权中单独管理。</InfoNote>
 <IdentityActions variant="full"><button onClick={()=>command.open({kind:"rename",commandType:"RENAME_APPOINTMENT_ROLE",targetId:selected.id,ifMatch:selected.etag,displayName:selected.displayName})}>修改岗位名称</button>
 <button className={selected.state==="ACTIVE"?"danger":undefined} onClick={event=>{event.currentTarget.focus();const active=selected.state==="ACTIVE";command.open({kind:"action",commandType:active?"DEACTIVATE_APPOINTMENT_ROLE":"REACTIVATE_APPOINTMENT_ROLE",targetId:selected.id,ifMatch:selected.etag,targetName:selected.displayName,label:active?"停用岗位":"恢复岗位",verb:active?"停用":"恢复",impact:active?"停用后不能新建该岗位任职。已有任职、授权和业务责任不会被暂停或终止。":"恢复后可用于新建任职，不自动授予任何权限。"});}}>{selected.state==="ACTIVE"?"停用岗位":"恢复岗位"}</button></IdentityActions>
 </>)} />;
}
