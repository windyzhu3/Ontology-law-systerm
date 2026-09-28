import {it,expect,vi} from 'vitest';
import {render,screen,fireEvent,waitFor,act,within} from '@testing-library/react';
import {LeadManagementPage} from './LeadManagementPage';
import {type LeadDetail,type LeadManagementTransport} from '../../lib/leadManagementTransport';
import {TransportError} from '../../lib/sessionTransport';
import {testSession,selectorId,taskId,deferred} from '../../test/fixtures';
const row={id:selectorId,customerLabel:'当前合成线索',contactLabel:'联系人',sourceLabel:'受控来源',ownerId:taskId,ownerLabel:'当前主管',state:'INCOMPLETE' as const,stateLabel:'待补齐资料',capturedAt:'2026-09-28T01:00:00Z'};
const detail:LeadDetail={...row,facts:[['联系电话','13800000000']],nextAction:'核对原资料',taskId,opportunityId:null,action:{kind:'task',label:'前往原工作卡'}};
const fixture=()=>({list:vi.fn().mockResolvedValue({items:[row],nextCursor:null}),detail:vi.fn().mockResolvedValue(detail),sources:vi.fn().mockResolvedValue({items:[{code:'FIXTURE',label:'受控来源',channel:'TEST',assignmentMode:'MANUAL',intakeLabel:'接入组',supervisorLabel:'主管组'}]})});
it('renders T filters and source configuration without creating business commands',async()=>{
 const api=fixture(),onTask=vi.fn();render(<LeadManagementPage session={testSession()} api={api} onTask={onTask}/>);
 await screen.findByRole('button',{name:row.customerLabel});expect(screen.getByLabelText('客户或联系方式')).toBeVisible();expect(screen.getByLabelText('负责人')).toBeVisible();
 fireEvent.change(screen.getByLabelText('负责人'),{target:{value:taskId}});await waitFor(()=>expect(api.list.mock.calls.at(-1)?.[1].owner).toBe(taskId));
 fireEvent.click(screen.getByRole('button',{name:'来源与责任'}));await within(screen.getByLabelText('来源详情')).findByText('接入组');expect(screen.getByText('人工指定')).toBeVisible();expect(onTask).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole('button',{name:'查询此来源线索'}));await waitFor(()=>expect(api.list.mock.calls.at(-1)?.[1].source).toBe('FIXTURE'));
});
it('clears rows detail and owner choices after a denied detail and ignores old identity data',async()=>{
 const api=fixture();api.detail.mockRejectedValue(new TransportError(403,'NOT_AUTHORIZED'));const s=testSession();const r=render(<LeadManagementPage session={s} api={api} onTask={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:row.customerLabel}));await screen.findByText('查看权限已变化，列表与详情已清除。');expect(screen.queryByText(row.customerLabel)).toBeNull();expect(screen.queryByRole('option',{name:row.ownerLabel})).toBeNull();
 const pending=deferred<LeadDetail>();const old=fixture();old.detail.mockReturnValue(pending.promise);r.rerender(<LeadManagementPage session={testSession()} api={old} onTask={vi.fn()}/>);fireEvent.click(await screen.findByRole('button',{name:row.customerLabel}));
 const fresh=fixture();fresh.list.mockResolvedValue({items:[],nextCursor:null});r.rerender(<LeadManagementPage session={{...testSession(),identityEpoch:77}} api={fresh} onTask={vi.fn()}/>);
 await act(async()=>{pending.resolve(detail);await Promise.resolve();});expect(screen.queryByText('13800000000')).toBeNull();
});
it('rechecks the original task and refuses an action that disappeared',async()=>{
 const api=fixture(),onTask=vi.fn();api.detail.mockResolvedValueOnce(detail).mockResolvedValueOnce({...detail,action:null,taskId:null});render(<LeadManagementPage session={testSession()} api={api as LeadManagementTransport} onTask={onTask}/>);
 fireEvent.click(await screen.findByRole('button',{name:row.customerLabel}));fireEvent.click(await screen.findByRole('button',{name:'前往原工作卡'}));await screen.findByText('当前事项已变化，请重新查询后办理。');expect(onTask).not.toHaveBeenCalled();
});
