import {it,expect,vi} from 'vitest';
import {testSession,taskId,selectorId} from '../test/fixtures';
import {createManagementTransport} from './managementTransport';
const row={id:selectorId,opportunityId:taskId,customerLabel:'合成客户',basisLabel:'本次收款请求',stateLabel:'待核对本笔收款',ownerLabel:'财务'};
it('sends a bounded server query using current identity and no-store',async()=>{
 const fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({items:[row],nextCursor:null})));
 const api=createManagementTransport(fetcher);await api.list(testSession(),'payments',{search:'甲 & 乙',state:'CHECK_RECEIPT'},new AbortController().signal);
 const [path,options]=fetcher.mock.calls[0];expect(String(path)).toContain('/api/v1/business-management/payments?');expect(new URL(String(path),'https://local').searchParams.get('search')).toBe('甲 & 乙');expect(options?.cache).toBe('no-store');expect(options?.method).toBe('GET');
});
it('rejects mismatched details and an actionable response without an exact task',async()=>{
 const fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({...row,id:taskId,facts:[],history:[],canHandle:true,taskId:null})));
 await expect(createManagementTransport(fetcher).detail(testSession(),'payments',selectorId,new AbortController().signal)).rejects.toThrow();
});
it('cannot retain data received for an expired session',async()=>{
 const s=testSession();const fetcher=vi.fn<typeof fetch>().mockImplementation(async()=>{s.isCurrent=()=>false;return new Response(JSON.stringify({items:[row],nextCursor:null}));});
 await expect(createManagementTransport(fetcher).list(s,'payments',{},new AbortController().signal)).rejects.toThrow();
});

it('clears a denied view without revoking access to another independently granted view',async()=>{
 const s=testSession(),invalidate=vi.fn();s.invalidate=invalidate;
 const api=createManagementTransport(vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({code:'NOT_AUTHORIZED'}),{status:403})).mockResolvedValueOnce(new Response(JSON.stringify({items:[],nextCursor:null}))));
 await expect(api.list(s,'payments',{},new AbortController().signal)).rejects.toThrow();expect(invalidate).not.toHaveBeenCalled();await expect(api.list(s,'transfer',{},new AbortController().signal)).resolves.toEqual({items:[],nextCursor:null});
});

it('invalidates the session on an expired credential response',async()=>{
 const s=testSession(),invalidate=vi.fn();s.invalidate=invalidate;
 const api=createManagementTransport(vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({code:'UNAUTHENTICATED'}),{status:401})));
 await expect(api.list(s,'payments',{},new AbortController().signal)).rejects.toThrow();expect(invalidate).toHaveBeenCalledWith(401);
});
