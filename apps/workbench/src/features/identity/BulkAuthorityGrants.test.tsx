import { act,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import { expect,it } from "vitest";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import { fixture,json,success } from "./identityWriteFixtures";
import { draftId,selectorId,deferred,testSession } from "../../test/fixtures";
const path="/admin/identity/authority-grants" as const;
function mount(f:ReturnType<typeof fixture>){return render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);}
async function prepare(){
 fireEvent.click(await screen.findByRole("button",{name:"新增直接授权"}));
 await screen.findByRole("option",{name:"陈晓"});
 fireEvent.change(screen.getByLabelText("授权任职"),{target:{value:selectorId}});
 fireEvent.change(screen.getByLabelText("组织范围"),{target:{value:draftId}});
 fireEvent.change(screen.getByLabelText("生效时间"),{target:{value:"2026-10-02T09:00"}});
 for(const label of ["线索接入","首联处置","业务材料查看"])fireEvent.click(await screen.findByRole("checkbox",{name:label}));
 screen.getByRole("button",{name:"核对授权清单"}).focus();fireEvent.click(screen.getByRole("button",{name:"核对授权清单"}));
 return screen.getByRole("dialog",{name:"确认批量授权"});
}
it("previews common scope/time and sends distinct existing commands only after confirmation",async()=>{
 const f=fixture(path);mount(f);const dialog=await prepare();expect(f.writes).toHaveLength(0);
 expect(dialog).toHaveTextContent("陈晓");expect(dialog).toHaveTextContent("销售二组");expect(dialog).toHaveTextContent("3 项");
 fireEvent.click(within(dialog).getByRole("button",{name:"确认授予 3 项权限"}));
 await waitFor(()=>expect(f.writes).toHaveLength(3));
 await screen.findByText("批量办理结束：成功 3 项，拒绝 0 项，结果未确认 0 项，未提交 0 项。");
 const bodies=await Promise.all(f.writes.map(r=>r.clone().json()));
 expect(new Set(f.writes.map(r=>r.headers.get('Idempotency-Key'))).size).toBe(3);
 expect(new Set(bodies.map(b=>b.authorityCode))).toEqual(new Set(['LEAD_CAPTURE','SALES_CONTACT_OWNER','MATERIALS_READ']));
 for(const b of bodies)expect(b).toMatchObject({appointmentId:selectorId,scopeOrganizationId:draftId,validFrom:new Date('2026-10-02T09:00').toISOString(),validUntil:null});
 expect(f.api.recovery.read()).toBeNull();
});
it("pauses after ambiguous second result, checks the original receipt, and never resumes the remainder automatically",async()=>{
 let attempted:Request|undefined;
 const f=fixture(path,{handle:async request=>{
  if(request.method==='POST'&&f.writes.length===2){attempted=request.clone();throw Error('connection lost');}
  if(request.method==='GET'&&new URL(request.url).pathname.includes('/receipt'))return json({commandId:attempted!.headers.get('Idempotency-Key'),receiptId:draftId,outcome:'SUCCEEDED',completedAt:'2026-10-02T02:00:00Z',resultFact:{factType:'AUTHORITY_GRANT',factRef:'safe-reference',revision:0}});
 }});mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));
 await screen.findByText('批量办理暂停：成功 1 项，拒绝 0 项，结果未确认 1 项，未提交 1 项。');
 expect(f.writes).toHaveLength(2);expect(f.api.recovery.read()?.commandId).toBe(attempted!.headers.get('Idempotency-Key'));
 fireEvent.click(screen.getByRole('button',{name:'查询原回执'}));
 await screen.findByText('批量办理暂停：成功 2 项，拒绝 0 项，结果未确认 0 项，未提交 1 项。');
 expect(f.writes).toHaveLength(2);expect(f.api.recovery.read()).toBeNull();
});
it("does not dispatch another item while the first is pending or after a proven refusal",async()=>{
 let finish:(r:Response)=>void=()=>{};
 const f=fixture(path,{handle:async request=>request.method==='POST'?new Promise<Response>(resolve=>{finish=resolve;}):undefined});mount(f);
 const dialog=await prepare();const confirm=within(dialog).getByRole('button',{name:'确认授予 3 项权限'});fireEvent.click(confirm);fireEvent.click(confirm);
 await waitFor(()=>expect(f.writes).toHaveLength(1));
 finish(json({type:'https://example.test/problem',title:'未提交',status:409,code:'IDENTITY_STATE_CONFLICT',detail:'safe',instance:'/problems/fixture',retryPolicy:'NEW_KEY_AFTER_REFRESH'},409));
 await screen.findByText('批量办理暂停：成功 0 项，拒绝 1 项，结果未确认 0 项，未提交 2 项。');
 expect(f.writes).toHaveLength(1);
});
it("group selection is explicit, reversible, and submitting an empty set is rejected",async()=>{
 const f=fixture(path);mount(f);fireEvent.click(await screen.findByRole('button',{name:'新增直接授权'}));
 const group=await screen.findByRole('group',{name:'线索权限'});
 const all=within(group).getByRole('checkbox',{name:'全选线索权限'});fireEvent.click(all);
 expect(within(group).getByRole('checkbox',{name:'线索接入'})).toBeChecked();fireEvent.click(all);
 expect(within(group).getByRole('checkbox',{name:'线索接入'})).not.toBeChecked();
 fireEvent.click(screen.getByRole('button',{name:'核对授权清单'}));expect(await screen.findByText('请至少选择一项当前可授予权限。')).toBeVisible();expect(f.writes).toHaveLength(0);
});

it("retries only the unknown original with the same key and body after token rotation",async()=>{
 let token="old";const f=fixture(path,{handle:async r=>{if(r.method==='POST'&&f.writes.length===2)throw Error('lost');return undefined;}});
 f.session={...f.session,getValidAccessToken:async()=>token};const view=mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));
 await screen.findByText('批量办理暂停：成功 1 项，拒绝 0 项，结果未确认 1 项，未提交 1 项。');const original=f.writes[1];
 token='rotated';view.rerender(<IdentityAdminApplication session={{...f.session}} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);
 fireEvent.click(screen.getByRole('button',{name:'重试原请求'}));await screen.findByText('批量办理暂停：成功 2 项，拒绝 0 项，结果未确认 0 项，未提交 1 项。');
 expect(f.writes).toHaveLength(3);expect(f.writes[2].headers.get('Idempotency-Key')).toBe(original.headers.get('Idempotency-Key'));expect(await f.writes[2].clone().text()).toBe(await original.clone().text());expect(f.writes[2].headers.get('Authorization')).toBe('Bearer rotated');expect(f.api.recovery.read()).toBeNull();
});
it("keeps the unknown original after receipt 404 without submitting remaining permissions",async()=>{
 const f=fixture(path,{handle:async r=>{if(r.method==='POST')throw Error('lost');if(r.url.includes('/receipt'))return json({},404);}});mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));
 await screen.findByText('批量办理暂停：成功 0 项，拒绝 0 项，结果未确认 1 项，未提交 2 项。');fireEvent.click(screen.getByRole('button',{name:'查询原回执'}));await screen.findByRole('button',{name:'查询原回执'});expect(f.writes).toHaveLength(1);expect(f.api.recovery.read()).not.toBeNull();
});
it("halts and drops sensitive batch state after the actor epoch changes during dispatch",async()=>{
 const pending=deferred<Response>();const f=fixture(path,{handle:async r=>r.method==='POST'?pending.promise:undefined});const view=mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));await waitFor(()=>expect(f.writes).toHaveLength(1));const marker=f.api.recovery.read();
 view.rerender(<IdentityAdminApplication session={testSession(2)} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);pending.resolve(success(f.writes[0]));await act(async()=>{});
 expect(f.writes).toHaveLength(1);expect(f.api.recovery.read()).toEqual(marker);expect(screen.queryByRole('region',{name:'批量授权结果'})).not.toBeInTheDocument();
});
it.each([401,403])("halts on %i qualification loss and retains the original recovery clue",async status=>{
 const f=fixture(path,{handle:async r=>r.method==='POST'?json({code:'NOT_AUTHORIZED'},status):undefined});const invalidations:number[]=[];f.session={...f.session,invalidate:n=>invalidations.push(n)};mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));await waitFor(()=>expect(invalidations).toContain(status));expect(f.writes).toHaveLength(1);expect(screen.queryByRole('dialog')).not.toBeInTheDocument();expect(screen.queryByText('批量授权结果')).not.toBeInTheDocument();expect(f.api.recovery.read()).not.toBeNull();
});
it("retains successful rows when the final list refresh fails, without sending any new write",async()=>{
 const f=fixture(path,{handle:async r=>{if(r.method==='GET'&&!r.url.includes('options')&&f.writes.length===3)return json({},503);}});mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));await screen.findByText('批量办理结束：成功 3 项，拒绝 0 项，结果未确认 0 项，未提交 0 项。');fireEvent.click(await screen.findByRole('button',{name:'重读列表'}));await waitFor(()=>expect(f.requests.filter(r=>r.method==='GET'&&!r.url.includes('options')).length).toBeGreaterThanOrEqual(3));expect(f.writes).toHaveLength(3);expect(f.api.recovery.read()).toBeNull();
});
it("returns from preview to the retained selection without writing",async()=>{
 const f=fixture(path);mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'返回修改'}));await waitFor(()=>expect(screen.getByRole('button',{name:'核对授权清单'})).toHaveFocus());expect(screen.getByRole('checkbox',{name:'首联处置'})).toBeChecked();expect(f.writes).toHaveLength(0);
});

it("does not allow a failed list refresh to discard an unknown write or start a new batch",async()=>{
 let failRead=true;const f=fixture(path,{handle:async r=>{if(r.method==='POST')throw Error('lost');if(r.method==='GET'&&!r.url.includes('options')&&f.writes.length&&failRead)return json({},503);}});mount(f);const dialog=await prepare();fireEvent.click(within(dialog).getByRole('button',{name:'确认授予 3 项权限'}));await screen.findByText('批量办理暂停：成功 0 项，拒绝 0 项，结果未确认 1 项，未提交 2 项。');
 const clue=f.api.recovery.read();expect(screen.queryByRole('button',{name:'重读列表'})).not.toBeInTheDocument();failRead=false;fireEvent.click(screen.getByRole('button',{name:'新增直接授权'}));expect(screen.queryByLabelText('授权任职')).not.toBeInTheDocument();expect(f.api.recovery.read()).toEqual(clue);expect(f.writes).toHaveLength(1);
});
