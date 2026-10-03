import {act,fireEvent,render,screen} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {LedgerClassificationCorrection} from './LedgerClassificationCorrection';
import {BusinessNavigationContext} from '../workcard/BusinessNavigation';
import {testSession,selectorId} from '../../test/fixtures';
import type {TransfersTransport} from '../../lib/transfersTransport';
it('guards session navigation while dirty and refuses leaving an unknown original',async()=>{
 let guard:((next:()=>void)=>void)|null=null;
 const read=vi.fn(()=>null as unknown);
 const api={recovery:{read},classificationContext:vi.fn().mockResolvedValue({opportunityId:selectorId,expectedOpportunityRevision:4,expectedWorkflow:{id:selectorId,revision:0},customerName:'合成客户',matter:{id:selectorId,number:'原案号'},category:'ENFORCEMENT',recipient:{id:selectorId,label:'案管'},receivers:[{id:selectorId,label:'案管'}]}),write:vi.fn(),receipt:vi.fn()} as unknown as TransfersTransport;
 const next=vi.fn();
 const view=render(<BusinessNavigationContext.Provider value={{registerLeaveGuard:g=>{guard=g;}}}><LedgerClassificationCorrection session={testSession()} opportunityId={selectorId} api={api} onBack={vi.fn()}/></BusinessNavigationContext.Provider>);
 fireEvent.change(await screen.findByLabelText('更正说明'),{target:{value:'尚未提交的说明'}});
 act(()=>guard!(next));expect(next).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole('button',{name:'继续编辑'}));expect(screen.getByLabelText('更正说明')).toHaveValue('尚未提交的说明');
 read.mockReturnValue({commandId:selectorId});act(()=>guard!(next));expect(screen.getByRole('alert')).toHaveTextContent('请先核对原回执');expect(next).not.toHaveBeenCalled();expect(api.write).not.toHaveBeenCalled();
 view.unmount();expect(guard).toBeNull();
});
