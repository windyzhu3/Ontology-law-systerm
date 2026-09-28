import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {OpportunityClosure} from './OpportunityClosure';
import {testSession} from '../../test/fixtures';
import {RecoveryStore} from '../session/recoveryMarker';
const context={opportunity:{id:'opp-a',revision:1},status:'READY' as const,expectedResponsibility:{type:'OPPORTUNITY_RESPONSIBILITY',id:'basis',revision:1},expectedTask:null,expectedWait:null};
it('requires reason and summary, reviews waiting impact, and sends only once',async()=>{
 const api={recovery:new RecoveryStore(sessionStorage),context:vi.fn().mockResolvedValue(context),write:vi.fn().mockResolvedValue({}),receipt:vi.fn()};
 render(<OpportunityClosure session={testSession()} opportunityId="opp-a" taskState="WAITING" api={api} onLock={vi.fn()} onRefresh={vi.fn()} onDenied={vi.fn()} blocked={false}/>);
 fireEvent.click(await screen.findByRole('button',{name:'结束本次商机'}));fireEvent.click(screen.getByText('核对结束影响'));expect(await screen.findByRole('alert')).toHaveTextContent('请填写');expect(api.write).not.toHaveBeenCalled();
 fireEvent.change(screen.getByLabelText('结束原因 *'),{target:{value:'CLIENT_DECLINED'}});fireEvent.change(screen.getByLabelText('情况说明 *'),{target:{value:'客户确认结束。'}});fireEvent.click(screen.getByText('核对结束影响'));expect(screen.getByText('取消等待中的普通跟进，不会提前办理')).toBeInTheDocument();fireEvent.click(screen.getByText('确认结束本次商机'));await waitFor(()=>expect(api.write).toHaveBeenCalledTimes(1));
});
it('retains reviewed input in memory after unknown response and only queries receipt',async()=>{
 const api={recovery:new RecoveryStore(sessionStorage),context:vi.fn().mockResolvedValue(context),write:vi.fn().mockRejectedValue(Error('lost')),receipt:vi.fn().mockRejectedValue(Error('404'))};
 render(<OpportunityClosure session={testSession()} opportunityId="opp-a" taskState="OPEN" api={api} onLock={vi.fn()} onRefresh={vi.fn()} onDenied={vi.fn()} blocked={false}/>);
 fireEvent.click(await screen.findByRole('button',{name:'结束本次商机'}));fireEvent.change(screen.getByLabelText('结束原因 *'),{target:{value:'OTHER'}});fireEvent.change(screen.getByLabelText('情况说明 *'),{target:{value:'私人说明'}});fireEvent.click(screen.getByText('核对结束影响'));fireEvent.click(screen.getByText('确认结束本次商机'));fireEvent.click(await screen.findByText('核对本次结果'));await waitFor(()=>expect(api.receipt).toHaveBeenCalledTimes(1));expect(api.write).toHaveBeenCalledTimes(1);expect(screen.getByText('私人说明')).toBeInTheDocument();expect(screen.queryByText('返回修改')).toBeNull();
});
it.each(['READ_ONLY','BLOCKED','CLOSED'] as const)('never offers closure from %s',async status=>{const api={recovery:new RecoveryStore(sessionStorage),context:vi.fn().mockResolvedValue({opportunity:context.opportunity,status}),write:vi.fn(),receipt:vi.fn()};render(<OpportunityClosure session={testSession()} opportunityId="opp-a" taskState="NONE" api={api} onLock={vi.fn()} onRefresh={vi.fn()} onDenied={vi.fn()} blocked={false}/>);await waitFor(()=>expect(api.context).toHaveBeenCalled());expect(screen.queryByRole('button',{name:'结束本次商机'})).toBeNull();});
it('describes no-task closure without suggesting that an existing task will be cancelled',async()=>{
 const api={recovery:new RecoveryStore(sessionStorage),context:vi.fn().mockResolvedValue(context),write:vi.fn(),receipt:vi.fn()};render(<OpportunityClosure session={testSession()} opportunityId="opp-a" taskState="NONE" api={api} onLock={vi.fn()} onRefresh={vi.fn()} onDenied={vi.fn()} blocked={false}/>);
 fireEvent.click(await screen.findByRole('button',{name:'结束本次商机'}));fireEvent.change(screen.getByLabelText('结束原因 *'),{target:{value:'OTHER'}});fireEvent.change(screen.getByLabelText('情况说明 *'),{target:{value:'客户确认需求取消。'}});fireEvent.click(screen.getByText('核对结束影响'));expect(screen.getByText('当前无普通跟进事项；结束后不再生成普通跟进')).toBeInTheDocument();expect(screen.queryByText('停止当前普通跟进，不记录虚假的完成进展')).toBeNull();
});
