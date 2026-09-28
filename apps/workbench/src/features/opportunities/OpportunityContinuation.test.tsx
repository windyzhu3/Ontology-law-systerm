import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {OpportunityContinuation} from './OpportunityContinuation';
import {testSession,taskId,selectorId} from '../../test/fixtures';
vi.mock('./QuoteCard',()=>({QuoteCard:({onCustomer,onBack}:any)=><><h2>报价准备子卡</h2><button onClick={onCustomer}>补齐报价依据</button><button onClick={onBack}>返回原跟进</button></>}));
vi.mock('./CustomerRequirementsCard',()=>({CustomerRequirementsCard:({onContinue}:any)=><button onClick={onContinue}>确认资料并返回</button>}));
vi.mock('../contracts/ContractRuntimeCard',()=>({ContractRuntimeCard:()=> <h2>直接合同申请子卡</h2>}));
function setup(){const customerApi={context:vi.fn().mockResolvedValue({confirmation:null})};const ledgerApi={taskContext:vi.fn().mockResolvedValue({opportunity:{id:selectorId,revision:0},customerLabel:'合成商机',ownerLabel:'销售',taskState:'OPEN',canHandle:true,task:{id:taskId,revision:0,etag:'task'},nextActionLabel:'记录进展'})},onBack=vi.fn();render(<OpportunityContinuation session={testSession()} taskId={taskId} ledgerApi={ledgerApi as never} quotesApi={{} as never} customerApi={customerApi as never} materialsApi={{} as never} contractsApi={{} as never} closureApi={{} as never} onBack={onBack} onTasks={vi.fn()}/>);return {ledgerApi,onBack,customerApi};}
it('resolves the exact current task and exposes only secondary business entries',async()=>{
 const {ledgerApi}=setup();await screen.findByText('选择本次要办理的事项');
 expect(ledgerApi.taskContext).toHaveBeenCalledWith(expect.anything(),taskId,expect.any(AbortSignal));
 expect(screen.getByRole('button',{name:'准备报价'})).toBeVisible();expect(screen.getByRole('button',{name:'申请直接准备合同'})).toBeVisible();expect(screen.getByRole('button',{name:'结束本次商机'})).toBeVisible();
 expect(document.querySelectorAll('.primary')).toHaveLength(0);
});
it('customer completion returns to the original quotation preparation without a business write',async()=>{
 setup();fireEvent.click(await screen.findByRole('button',{name:'准备报价'}));fireEvent.click(screen.getByRole('button',{name:'补齐报价依据'}));fireEvent.click(screen.getByRole('button',{name:'确认资料并返回'}));await screen.findByRole('heading',{name:'报价准备子卡'});
});
it('can return to the current responsibility without submitting progress',async()=>{
 const {onBack}=setup();await screen.findByText('选择本次要办理的事项');fireEvent.click(screen.getByRole('button',{name:'返回当前跟进'}));await waitFor(()=>expect(onBack).toHaveBeenCalledOnce());
});

it('direct-contract preparation routes missing customer facts back to the intended card',async()=>{
 const {customerApi}=setup();fireEvent.click(await screen.findByRole('button',{name:'申请直接准备合同'}));
 fireEvent.click(await screen.findByRole('button',{name:'补齐客户与需求'}));fireEvent.click(screen.getByRole('button',{name:'确认资料并返回'}));
 await screen.findByRole('heading',{name:'直接合同申请子卡'});expect(customerApi.context).toHaveBeenCalledWith(expect.anything(),selectorId,expect.any(AbortSignal));
});
