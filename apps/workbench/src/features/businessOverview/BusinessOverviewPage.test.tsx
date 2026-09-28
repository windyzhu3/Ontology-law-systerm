import {it,expect,vi} from 'vitest';
import {render,screen,fireEvent,waitFor,act,within} from '@testing-library/react';
import {BusinessOverviewPage} from './BusinessOverviewPage';
import {type OverviewSummary} from '../../lib/businessOverviewTransport';
import {TransportError} from '../../lib/sessionTransport';
import {testSession,selectorId,deferred} from '../../test/fixtures';
const summary:OverviewSummary={month:'2026-09',asOf:'2026-09-28T01:00:00Z',metrics:[{key:'leads',label:'新增线索',status:'AVAILABLE',count:0},{key:'opportunities',label:'有效商机',status:'FORBIDDEN',count:null},{key:'signedContracts',label:'签署归档合同',status:'AVAILABLE',count:1},{key:'acceptedMatters',label:'已接收案件',status:'AVAILABLE',count:1},{key:'overdueTasks',label:'当前逾期待办',status:'AVAILABLE',count:2}]};
const page={month:'2026-09',asOf:summary.asOf,metric:'leads' as const,items:[{id:selectorId,customerLabel:'同口径合成记录',occurredAt:summary.asOf,stateLabel:'首次接入'}],nextCursor:'cursor-page-2'};
const fixture=()=>({summary:vi.fn().mockResolvedValue(summary),details:vi.fn().mockResolvedValue(page)});
it('renders all five T metrics with distinct zero and forbidden, then uses the exact month for drilldown',async()=>{
 const api=fixture();render(<BusinessOverviewPage session={testSession()} api={api}/>);await screen.findByText('不可查看');const row=screen.getByText('新增线索').closest('tr')!;expect(within(row).getByText('0')).toBeVisible();expect(within(screen.getByText('有效商机').closest('tr')!).queryByRole('button')).toBeNull();
 fireEvent.click(within(row).getByRole('button',{name:'查看明细'}));await screen.findByText('同口径合成记录');expect(api.details.mock.calls[0].slice(1,4)).toEqual(['leads','2026-09',undefined]);
 fireEvent.click(screen.getByRole('button',{name:'下一页'}));await waitFor(()=>expect(api.details.mock.calls.at(-1)?.[3]).toBe('cursor-page-2'));expect(screen.queryByRole('button',{name:'办理'})).toBeNull();
});
it('clears previous totals and details on revoked or unavailable reads and permits retry',async()=>{
 const api=fixture();render(<BusinessOverviewPage session={testSession()} api={api}/>);fireEvent.click(within((await screen.findByText('新增线索')).closest('tr')!).getByRole('button'));await screen.findByText('同口径合成记录');api.summary.mockRejectedValueOnce(new TransportError(403,'NOT_AUTHORIZED'));
 fireEvent.click(screen.getByRole('button',{name:'重新查询'}));await screen.findByText('查看权限已变化，概览与明细已清除。');expect(screen.queryByText('同口径合成记录')).toBeNull();expect(screen.queryByText('新增线索')).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'重新查询'}));await screen.findByText('新增线索');
});
it('does not revive old-identity totals or a superseded metric response',async()=>{
 const api=fixture(),pending=deferred<typeof page>();api.details.mockReturnValue(pending.promise);const r=render(<BusinessOverviewPage session={testSession()} api={api}/>);fireEvent.click(within((await screen.findByText('新增线索')).closest('tr')!).getByRole('button'));
 const fresh=fixture();fresh.summary.mockResolvedValue({...summary,metrics:summary.metrics.map(m=>({...m,status:'FORBIDDEN',count:null}))});r.rerender(<BusinessOverviewPage session={{...testSession(),identityEpoch:22}} api={fresh}/>);await act(async()=>{pending.resolve(page);await Promise.resolve();});expect(screen.queryByText('同口径合成记录')).toBeNull();
});
it('focuses the mounted detail pane on the first drilldown',async()=>{
 const api=fixture();render(<BusinessOverviewPage session={testSession()} api={api}/>);fireEvent.click(within((await screen.findByText('新增线索')).closest('tr')!).getByRole('button'));await screen.findByText('同口径合成记录');expect(screen.getByLabelText('同口径明细')).toHaveFocus();
});
