import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {ManagementLedgerPage} from './ManagementLedgerPage';
import {testSession,selectorId,taskId} from '../../test/fixtures';
const row={id:selectorId,opportunityId:taskId,customerLabel:'管理合成客户',basisLabel:'本次收款请求',stateLabel:'待核对本笔收款',ownerLabel:'财务'};
const detail={id:selectorId,opportunityId:taskId,customerLabel:row.customerLabel,facts:[['当前事项','待核对']] as [string,string][],history:[],canHandle:true,taskId};
it('rechecks the exact request before selecting an existing workcard',async()=>{
 const api={list:vi.fn().mockResolvedValue({items:[row],nextCursor:null}),detail:vi.fn().mockResolvedValue(detail)},handle=vi.fn();
 render(<ManagementLedgerPage session={testSession()} api={api} view="payments" onView={vi.fn()} onTask={handle} onTasks={vi.fn()} onOpportunities={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:row.customerLabel}));fireEvent.click(await screen.findByRole('button',{name:'前往办理'}));await waitFor(()=>expect(handle).toHaveBeenCalledWith(taskId));expect(api.detail).toHaveBeenCalledTimes(2);
});
it('does not navigate when the responsibility has changed since selection',async()=>{
 const api={list:vi.fn().mockResolvedValue({items:[row],nextCursor:null}),detail:vi.fn().mockResolvedValueOnce(detail).mockResolvedValue({...detail,canHandle:false,taskId:null})},handle=vi.fn();
 render(<ManagementLedgerPage session={testSession()} api={api} view="payments" onView={vi.fn()} onTask={handle} onTasks={vi.fn()} onOpportunities={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:row.customerLabel}));fireEvent.click(await screen.findByRole('button',{name:'前往办理'}));await screen.findByText('当前事项已变化，请重新查询后办理。');expect(handle).not.toHaveBeenCalled();expect(screen.queryByText('待核对')).toBeNull();
});

it('keeps filters on returning within the same identity and clears them for another identity',async()=>{
 const session=testSession(),api={list:vi.fn().mockResolvedValue({items:[],nextCursor:null}),detail:vi.fn()},props={api,view:'payments' as const,onView:vi.fn(),onTask:vi.fn()};
 const first=render(<ManagementLedgerPage {...props} session={session}/>);await screen.findByText('暂无匹配记录。');fireEvent.change(screen.getByLabelText('搜索客户'),{target:{value:'合成筛选'}});await waitFor(()=>expect(api.list).toHaveBeenLastCalledWith(session,'payments',expect.objectContaining({search:'合成筛选'}),expect.any(AbortSignal)));first.unmount();
 const second=render(<ManagementLedgerPage {...props} session={session}/>);expect(screen.getByLabelText('搜索客户')).toHaveValue('合成筛选');second.unmount();
 render(<ManagementLedgerPage {...props} session={testSession()}/>);expect(screen.getByLabelText('搜索客户')).toHaveValue('');
});

it('recovers an expired next-page cursor by restarting the same filtered query',async()=>{
 const session=testSession(),api={list:vi.fn().mockResolvedValueOnce({items:[],nextCursor:'old-cursor'}).mockRejectedValueOnce(Error('expired')).mockResolvedValue({items:[],nextCursor:null}),detail:vi.fn()};
 render(<ManagementLedgerPage session={session} api={api} view="payments" onView={vi.fn()} onTask={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:'下一页'}));await screen.findByText('本次读取失败，请重新查询。');fireEvent.click(screen.getByRole('button',{name:'重新查询'}));await screen.findByText('暂无匹配记录。');expect(api.list).toHaveBeenLastCalledWith(session,'payments',expect.objectContaining({cursor:undefined}),expect.any(AbortSignal));
});
