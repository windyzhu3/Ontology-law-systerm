import {it,expect,vi} from 'vitest';
import {testSession,taskId,selectorId,jsonResponse} from '../test/fixtures';
import {createLeadManagementTransport} from './leadManagementTransport';
const row={id:selectorId,customerLabel:'合成客户',contactLabel:'联系人',sourceLabel:'受控来源',ownerId:taskId,ownerLabel:'主管',state:'INCOMPLETE',stateLabel:'待补齐资料',capturedAt:'2026-09-28T01:00:00Z'};
const detail={...row,facts:[['状态','待补齐']],nextAction:'核对原卡',taskId,opportunityId:null,action:{kind:'task',label:'前往原工作卡'}};
it('uses bounded authorized no-store queries and preserves all four filters',async()=>{
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({items:[row],nextCursor:null}));const s=testSession();
 await createLeadManagementTransport(f).list(s,{search:'甲 & 乙',source:'FIXTURE',owner:taskId,state:'INCOMPLETE'},new AbortController().signal);
 const [path,options]=f.mock.calls[0];const q=new URL(String(path),'https://local').searchParams;
 expect(Object.fromEntries(q)).toEqual({limit:'20',search:'甲 & 乙',source:'FIXTURE',owner:taskId,state:'INCOMPLETE'});expect(options?.cache).toBe('no-store');expect(options?.method).toBe('GET');
});
it.each([{taskId:null},{action:null},{id:taskId},{action:{kind:'command',label:'自动办理'}},{unexpected:'hidden'}])('rejects mismatched or expanded detail %j',async change=>{
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({...detail,...change}));await expect(createLeadManagementTransport(f).detail(testSession(),selectorId,new AbortController().signal)).rejects.toThrow();
});
it('accepts an exact task target and rejects a late response from an old identity',async()=>{
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(detail));expect((await createLeadManagementTransport(f).detail(testSession(),selectorId,new AbortController().signal)).taskId).toBe(taskId);
 const s=testSession();const late=vi.fn<typeof fetch>().mockImplementation(async()=>{s.isCurrent=()=>false;return jsonResponse({items:[row],nextCursor:null});});await expect(createLeadManagementTransport(late).list(s,{},new AbortController().signal)).rejects.toThrow();
});
it('keeps local denied reads distinct from expired login',async()=>{for(const status of [403,401]){const s=testSession();s.invalidate=vi.fn();const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({code:'NOT_AUTHORIZED'},status));await expect(createLeadManagementTransport(f).list(s,{},new AbortController().signal)).rejects.toThrow();if(status===401)expect(s.invalidate).toHaveBeenCalledWith(401);else expect(s.invalidate).not.toHaveBeenCalled();}});
