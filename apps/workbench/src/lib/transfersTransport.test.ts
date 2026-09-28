import {beforeEach,it,expect,vi} from 'vitest';
import {createTransfersTransport} from './transfersTransport';
import {RecoveryStore} from '../features/session/recoveryMarker';
import {testSession,taskId,selectorId} from '../test/fixtures';
beforeEach(()=>sessionStorage.clear());
const write={key:taskId,opportunityId:selectorId,command:'SUBMIT_TRANSFER' as const,body:{expectedOpportunityRevision:0,expectedWorkflow:{id:selectorId,revision:0},values:{explanation:'私密交接说明'}}};
it('recovers the original transfer receipt without posting the business command again',async()=>{
 const receipt={commandId:taskId,receiptId:selectorId,completedAt:'2026-09-27T00:00:00Z',outcome:'SUCCEEDED',resultFact:{factType:'TRANSFER_SUBMISSION',factRef:'r'.repeat(43),revision:0}},fetcher=vi.fn<typeof fetch>().mockRejectedValueOnce(Error('lost')).mockResolvedValueOnce(new Response(JSON.stringify(receipt))),recovery=new RecoveryStore(sessionStorage),api=createTransfersTransport(recovery,fetcher),session=testSession();
 await expect(api.write(session,write,new AbortController().signal)).rejects.toThrow();expect(fetcher.mock.calls[0][0]).toBe('/api/v1/opportunities/'+selectorId+'/transfers/submissions');expect(sessionStorage.getItem('r1.pending-command')).not.toContain('私密');await api.receipt(session,new AbortController().signal);expect(fetcher.mock.calls[1][1]?.method).toBe('GET');expect(recovery.read()).toBeNull();
});
it('rejects a fabricated transfer result without clearing the pending original command',async()=>{
 const recovery=new RecoveryStore(sessionStorage),session=testSession();recovery.reserveWrite(taskId,'SUBMIT_TRANSFER',session.actorScopeKey,{});const fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({commandId:taskId,receiptId:selectorId,completedAt:'2026-09-27T00:00:00Z',outcome:'SUCCEEDED',resultFact:{factType:'TRANSFER_SUBMISSION',factRef:'r'.repeat(43),revision:1}})));await expect(createTransfersTransport(recovery,fetcher).receipt(session,new AbortController().signal)).rejects.toThrow();expect(recovery.read()?.commandId).toBe(taskId);
});
it('a stale independent review receipt closes the original recovery and requires fresh context',async()=>{
 const recovery=new RecoveryStore(sessionStorage),session=testSession();recovery.reserveWrite(taskId,'RECORD_TRANSFER_INTAKE',session.actorScopeKey,{});const fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({commandId:taskId,receiptId:selectorId,completedAt:'2026-09-27T00:00:00Z',outcome:'REJECTED',rejectionCode:'STALE_REVIEW'})));await expect(createTransfersTransport(recovery,fetcher).receipt(session,new AbortController().signal)).rejects.toMatchObject({status:412,provenOutcome:true});expect(recovery.read()).toBeNull();
});

it('downloads only through the authenticated current-task route without creating a command marker',async()=>{
 const recovery=new RecoveryStore(sessionStorage),session=testSession(),fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response('synthetic proof'));
 const data=await createTransfersTransport(recovery,fetcher).download(session,taskId,selectorId,new AbortController().signal);
 expect(data.size).toBe(15);expect(data.type).toContain('text/plain');expect(fetcher).toHaveBeenCalledWith('/api/v1/transfer-tasks/'+taskId+'/materials/'+selectorId+'/content',expect.objectContaining({cache:'no-store',headers:expect.any(Object)}));expect(recovery.read()).toBeNull();
});
it('never returns a denied material response as downloadable content',async()=>{
 const fetcher=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({code:'NOT_AUTHORIZED'}),{status:403}));
 await expect(createTransfersTransport(new RecoveryStore(sessionStorage),fetcher).download(testSession(),taskId,selectorId,new AbortController().signal)).rejects.toMatchObject({status:403});
});
it('reads exact correction context without creating a task or command and rejects a substituted opportunity',async()=>{
 const value={opportunityId:selectorId,expectedOpportunityRevision:1,expectedWorkflow:{id:taskId,revision:0},customerName:'合成客户',matter:{id:selectorId,number:'001'},category:'GENERAL',recipient:null,receivers:[]};const fetcher=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(value))).mockResolvedValueOnce(new Response(JSON.stringify({...value,opportunityId:taskId}))),api=createTransfersTransport(new RecoveryStore(sessionStorage),fetcher),session=testSession();await expect(api.classificationContext(session,selectorId,new AbortController().signal)).resolves.toEqual(value);expect(fetcher.mock.calls[0][1]?.method).toBe('GET');expect(api.recovery.read()).toBeNull();await expect(api.classificationContext(session,selectorId,new AbortController().signal)).rejects.toThrow();
});
