import {it,expect,vi} from 'vitest';
import {testSession,selectorId,jsonResponse} from '../test/fixtures';
import {createBusinessOverviewTransport} from './businessOverviewTransport';
const metrics=[['leads','新增线索'],['opportunities','有效商机'],['signedContracts','签署归档合同'],['acceptedMatters','已接收案件'],['overdueTasks','当前逾期待办']].map(([key,label])=>({key,label,status:'AVAILABLE',count:1}));
const summary={month:'2026-09',asOf:'2026-09-28T01:00:00Z',metrics};
it('reads authenticated no-store totals and bounded same-month details',async()=>{
 const f=vi.fn<typeof fetch>().mockResolvedValueOnce(jsonResponse(summary)).mockResolvedValueOnce(jsonResponse({month:summary.month,asOf:summary.asOf,metric:'leads',items:[{id:selectorId,customerLabel:'合成客户',occurredAt:summary.asOf,stateLabel:'首次接入'}],nextCursor:null}));const api=createBusinessOverviewTransport(f),s=testSession(),signal=new AbortController().signal;
 expect((await api.summary(s,'2026-09',signal)).metrics).toHaveLength(5);await api.details(s,'leads','2026-09',undefined,signal);
 expect(String(f.mock.calls[1][0])).toBe('/api/v1/business-overview/leads?month=2026-09&limit=20');expect(f.mock.calls.every(([,o])=>o?.cache==='no-store'&&o.method==='GET')).toBe(true);
});
it.each(['duplicate','missing','forbidden-zero','negative','fraction','extra','wrong-month'])('rejects inconsistent summary %s',async fault=>{
 const body=structuredClone(summary);
 if(fault==='duplicate')body.metrics[1]=body.metrics[0];else if(fault==='missing')body.metrics.pop();else if(fault==='forbidden-zero'){body.metrics[0].status='FORBIDDEN';body.metrics[0].count=0;}else if(fault==='negative')body.metrics[0].count=-1;else if(fault==='fraction')body.metrics[0].count=.5;else if(fault==='extra')Object.assign(body,{hidden:'expanded'});else body.month='2026-08';
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(body));await expect(createBusinessOverviewTransport(f).summary(testSession(),'2026-09',new AbortController().signal)).rejects.toThrow();
});
it('does not accept detail from another metric and discards old-identity responses',async()=>{
 const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({month:summary.month,asOf:summary.asOf,metric:'signedContracts',items:[],nextCursor:null}));await expect(createBusinessOverviewTransport(f).details(testSession(),'leads','2026-09',undefined,new AbortController().signal)).rejects.toThrow();
 const s=testSession();f.mockImplementation(async()=>{s.isCurrent=()=>false;return jsonResponse(summary);});await expect(createBusinessOverviewTransport(f).summary(s,undefined,new AbortController().signal)).rejects.toThrow();
});
it('keeps forbidden scope and expired login distinct',async()=>{for(const status of [403,401]){const s=testSession();s.invalidate=vi.fn();const f=vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({code:'NOT_AUTHORIZED'},status));await expect(createBusinessOverviewTransport(f).summary(s,undefined,new AbortController().signal)).rejects.toThrow();if(status===401)expect(s.invalidate).toHaveBeenCalledWith(401);else expect(s.invalidate).not.toHaveBeenCalled();}});
