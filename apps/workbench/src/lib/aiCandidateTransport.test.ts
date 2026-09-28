import {it,expect,vi} from 'vitest';
import {testSession,selectorId,taskId,jsonResponse} from '../test/fixtures';
import {createAiCandidateTransport} from './aiCandidateTransport';
const candidate={task:'FIELDS',items:[{field:'contactName',status:'CANDIDATE',value:'林悦',citations:[{sourceId:'s1',quote:'联系人林悦'}]}],sources:[{id:'s1',kind:'TEXT',label:'线索原文',text:'联系人林悦，电话未提供。'}],sourceToken:'opaque-source-token',generatedAt:'2026-09-28T10:00:00Z'};
it('posts only the candidate kind and rechecks its token with authenticated no-store requests',async()=>{
 const f=vi.fn<typeof fetch>().mockResolvedValueOnce(jsonResponse(candidate)).mockResolvedValueOnce(new Response(null,{status:204}));const api=createAiCandidateTransport(f);const s=testSession(),signal=new AbortController().signal,target={opportunityId:selectorId};
 const result=await api.generate(s,target,'FIELDS',signal);await api.recheck(s,target,result,signal);
 expect(f.mock.calls[0][0]).toBe('/api/v1/opportunities/'+selectorId+'/ai-candidates/FIELDS');expect(f.mock.calls[0][1]?.body).toBe('{}');expect(JSON.parse(String(f.mock.calls[1][1]?.body))).toEqual({sourceToken:candidate.sourceToken});expect(f.mock.calls.every(([,o])=>o?.method==='POST'&&o.cache==='no-store')).toBe(true);
});
it.each(['task','field','quote','reference','missing-value','command','duplicate','oversize'])('rejects invalid candidate %s',async fault=>{
 const d=structuredClone(candidate);
 if(fault==='task')d.task='SUMMARY';else if(fault==='field')d.items[0].field='approveContract';else if(fault==='quote')d.items[0].citations[0].quote='伪造来源';else if(fault==='reference')d.items[0].citations[0].sourceId='other';else if(fault==='missing-value')d.items[0].status='MISSING';else if(fault==='command')Object.assign(d,{command:'EXECUTE'});else if(fault==='duplicate')d.items.push(d.items[0]);else d.sources[0].text='x'.repeat(32001);
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(d));await expect(createAiCandidateTransport(f).generate(testSession(),{opportunityId:selectorId},'FIELDS',new AbortController().signal)).rejects.toThrow();
});
it('never accepts late identity responses and keeps scoped denial distinct from login expiry',async()=>{
 for(const status of [401,403]){const s=testSession();s.invalidate=vi.fn();const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({code:'NOT_AUTHORIZED'},status));await expect(createAiCandidateTransport(f).generate(s,{opportunityId:selectorId},'FIELDS',new AbortController().signal)).rejects.toThrow();if(status===401)expect(s.invalidate).toHaveBeenCalledWith(401);else expect(s.invalidate).not.toHaveBeenCalled();}
 const s=testSession();const f=vi.fn<typeof fetch>().mockImplementation(async()=>{s.isCurrent=()=>false;return jsonResponse(candidate);});await expect(createAiCandidateTransport(f).generate(s,{opportunityId:selectorId},'FIELDS',new AbortController().signal)).rejects.toThrow();
});
it('resolves the exact current progress task for summary and rechecks that task before adoption',async()=>{
 const detail={opportunity:{id:selectorId,revision:0},customerLabel:'合成客户',ownerLabel:'销售',taskState:'OPEN',canHandle:true,task:{id:taskId,revision:0,etag:'tag'}};
 const summary={...candidate,task:'SUMMARY',items:[{...candidate.items[0],field:'progressSummary'}]};
 const f=vi.fn<typeof fetch>().mockResolvedValueOnce(jsonResponse(detail)).mockResolvedValueOnce(jsonResponse(summary)).mockResolvedValueOnce(jsonResponse({...detail,canHandle:false,task:undefined}));
 const api=createAiCandidateTransport(f),s=testSession(),signal=new AbortController().signal,target={taskId};const result=await api.generate(s,target,'SUMMARY',signal);
 expect(f.mock.calls[0][0]).toBe('/api/v1/opportunity-tasks/'+taskId+'/context');await expect(api.recheck(s,target,result,signal)).rejects.toThrow();expect(f.mock.calls).toHaveLength(3);
});
