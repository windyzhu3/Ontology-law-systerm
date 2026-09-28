import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {ContractRuntimeCard} from './ContractRuntimeCard';
import {RecoveryStore} from '../session/recoveryMarker';
import type {ContractsTransport} from '../../lib/contractsTransport';
import type {ContractContext} from './types';
import {testSession,taskId,selectorId} from '../../test/fixtures';
const selector={id:selectorId,revision:1};
const context:ContractContext={opportunity:selector,responsibilityBasis:selector,customerConfirmation:selector,customerName:'实际有权客户',readonly:false,contract:null,draft:null,workflow:{selector,stage:'DIRECT_REQUEST',task:selector,ownerAppointmentId:taskId},review:null,approvals:[],allowedActions:['REQUEST_CONTRACT_PREPARATION'],blockers:[],history:[],receiptBoundary:null};
function setup(){const api={recovery:new RecoveryStore(sessionStorage),taskContext:vi.fn().mockResolvedValue({opportunityId:selectorId,context}),context:vi.fn().mockResolvedValue(context),write:vi.fn().mockResolvedValue({}),receipt:vi.fn().mockResolvedValue({}),download:vi.fn(),list:vi.fn()} satisfies ContractsTransport;return {api,session:testSession(),onTasks:vi.fn()};}
it.each([true,false])('confirmed receipt follows only the same actor next responsibility (own=%s)',async own=>{
 sessionStorage.clear();const props=setup(),onContinueTask=vi.fn(),nextId='019c7000-0000-7000-8000-000000000099';
 props.api.context.mockResolvedValue({...context,workflow:{...context.workflow!,task:{id:nextId,revision:0},ownerAppointmentId:own?props.session.selectedAppointmentId:'another-owner'}});
 props.api.recovery.reserveWrite(taskId,'REQUEST_CONTRACT_PREPARATION',props.session.actorScopeKey,{});
 render(<ContractRuntimeCard {...props} taskId={taskId} onContinueTask={onContinueTask}/>);
 fireEvent.click(await screen.findByRole('button',{name:'核对原结果'}));await waitFor(()=>expect(props.api.context).toHaveBeenCalled());
 await waitFor(()=>own?expect(onContinueTask).toHaveBeenCalledWith(nextId):expect(screen.queryByRole('button',{name:'核对原结果'})).toBeNull());
 if(!own)expect(onContinueTask).not.toHaveBeenCalled();expect(props.api.write).not.toHaveBeenCalled();
});
it('fetches current task context before exposing any contract action',async()=>{const props=setup();let resolve!:(v:unknown)=>void;props.api.taskContext.mockReturnValue(new Promise(r=>resolve=r));render(<ContractRuntimeCard {...props} taskId={taskId}/>);expect(screen.queryByRole('button',{name:'提交直接准备申请'})).toBeNull();resolve({opportunityId:selectorId,context});expect(await screen.findByRole('button',{name:'提交直接准备申请'})).toBeVisible();expect(props.api.taskContext).toHaveBeenCalledWith(props.session,taskId,expect.any(AbortSignal));});
it('an unavailable backend exposes an error and retry, never a synthetic working form',async()=>{const props=setup();props.api.context.mockRejectedValue(Error('offline'));render(<ContractRuntimeCard {...props} opportunityId={selectorId}/>);expect(await screen.findByRole('button',{name:'重新读取合同责任'})).toBeVisible();expect(screen.queryByRole('button',{name:'提交直接准备申请'})).toBeNull();expect(screen.queryByText('实际有权客户')).toBeNull();});
it('a committed receipt followed by failed context refresh stays completed and reloadable',async()=>{const props=setup();props.api.context.mockRejectedValue(Error('read failed'));render(<ContractRuntimeCard {...props} taskId={taskId}/>);fireEvent.change(await screen.findByLabelText('服务范围'),{target:{value:'咨询'}});fireEvent.change(screen.getByLabelText('收费项目 1'),{target:{value:'固定费用'}});fireEvent.change(screen.getByLabelText('金额（元）1'),{target:{value:'12.34'}});fireEvent.change(screen.getByLabelText('付款安排'),{target:{value:'签署后支付'}});fireEvent.change(screen.getByLabelText('申请原因与沟通依据'),{target:{value:'客户已沟通'}});fireEvent.click(screen.getByRole('button',{name:'提交直接准备申请'}));await waitFor(()=>expect(props.api.write).toHaveBeenCalledOnce());expect(props.api.write.mock.calls[0][1].body).toMatchObject({expectedOpportunityRevision:1,expectedContract:null,expectedVersion:null,values:{commercial:{lines:[{amountMinor:1234}]}}});expect(await screen.findByRole('button',{name:'查看下一责任安排'})).toBeVisible();expect(screen.queryByRole('button',{name:'核对原结果'})).toBeNull();});
it('reload with original pending marker recovers receipt instead of exposing another command',async()=>{const props=setup();props.api.recovery.reserveWrite(taskId,'REQUEST_CONTRACT_PREPARATION',props.session.actorScopeKey,{});render(<ContractRuntimeCard {...props} taskId={taskId}/>);expect(await screen.findByRole('button',{name:'核对原结果'})).toBeVisible();expect(screen.queryByRole('button',{name:'提交直接准备申请'})).toBeNull();fireEvent.click(screen.getByRole('button',{name:'核对原结果'}));await waitFor(()=>expect(props.api.receipt).toHaveBeenCalledOnce());expect(props.api.write).not.toHaveBeenCalled();});

it('embedded contract uses the parent workbench and reports dirty state',async()=>{sessionStorage.clear();const props=setup(),dirty=vi.fn();render(<ContractRuntimeCard {...props} taskId={taskId} embedded onDirtyChange={dirty}/>);fireEvent.change(await screen.findByLabelText('服务范围'),{target:{value:'待保存'}});expect(dirty).toHaveBeenLastCalledWith(true);expect(screen.queryByRole('banner')).toBeNull();expect(screen.queryByRole('main')).toBeNull();expect(screen.queryByRole('button',{name:'我的待办'})).toBeNull();});

it('execution completion cannot fall back to an old preparation responsibility',async()=>{
 sessionStorage.clear();const props=setup(),onContinueTask=vi.fn(),onReceiptConfirmed=vi.fn();
 props.api.context.mockResolvedValue({...context,workflow:{...context.workflow!,task:selector,ownerAppointmentId:props.session.selectedAppointmentId},execution:{handoff:selector,workflow:{selector,stage:'READY_TRANSFER',task:null,ownerAppointmentId:props.session.selectedAppointmentId,ownerLabel:'销售',dueAt:null,message:'执行条件已确认'}}});
 props.api.recovery.reserveWrite(taskId,'VERIFY_CONTRACT_EXECUTION_CONDITIONS',props.session.actorScopeKey,{});
 render(<ContractRuntimeCard {...props} taskId={taskId} onContinueTask={onContinueTask} onReceiptConfirmed={onReceiptConfirmed}/>);
 fireEvent.click(await screen.findByRole('button',{name:'核对原结果'}));
 await waitFor(()=>expect(onReceiptConfirmed).toHaveBeenCalledOnce());
 expect(await screen.findByText('执行条件已确认，下一步准备转案')).toBeVisible();expect(onContinueTask).not.toHaveBeenCalled();expect(props.api.write).not.toHaveBeenCalled();
});

it('refreshing a completed finance task does not jump to a parallel sales task',async()=>{
 const props=setup(),onContinueTask=vi.fn();const payment={selector,request:selector,stage:'CHECK_RECEIPT' as const,targetStage:'CHECK_RECEIPT' as const,task:{id:taskId,revision:0},taskIds:[taskId],ownerAppointmentId:props.session.selectedAppointmentId,ownerLabel:'财务',dueAt:'2026-09-27T10:00:00Z',accountLabel:'合成基本户',explanation:null,allowedActions:['RECORD_CONTRACT_RECEIPT_REVIEW'] as ContractContext['allowedActions']};
 props.api.taskContext.mockResolvedValue({opportunityId:selectorId,context:{...context,payments:[payment],documents:[{id:selectorId,label:'到账凭证',bodySha256:'a'.repeat(64)}]}});
 props.api.context.mockResolvedValue({...context,payments:[{...payment,stage:'COMPLETE',targetStage:'COMPLETE',task:null,allowedActions:[]}],workflow:{...context.workflow!,task:selector,ownerAppointmentId:props.session.selectedAppointmentId}});
 render(<ContractRuntimeCard {...props} taskId={taskId} onContinueTask={onContinueTask}/>);
 await screen.findByRole('heading',{name:'核对本笔收款',level:1});fireEvent.change(screen.getByLabelText('本笔核对凭证'),{target:{value:selectorId}});fireEvent.change(screen.getByLabelText('银行交易流水号'),{target:{value:'TX-UI'}});fireEvent.change(screen.getByLabelText('本笔实收金额（元）'),{target:{value:'1.00'}});fireEvent.change(screen.getByLabelText('实际到账时间'),{target:{value:'2026-01-01T10:30'}});fireEvent.change(screen.getByLabelText('核对说明'),{target:{value:'本笔银行流水已人工核对'}});fireEvent.click(screen.getByRole('checkbox'));fireEvent.click(screen.getByRole('button',{name:'记录核对结果'}));
 await screen.findByRole('heading',{name:'本笔收款已核对',level:1});expect(onContinueTask).not.toHaveBeenCalled();expect(props.api.write).toHaveBeenCalledTimes(1);
});
