import {fireEvent,render,screen,waitFor} from '@testing-library/react';import {it,expect,vi} from 'vitest';
import {MatterClassification} from './MatterClassification';
it('does not classify before intake has produced a case identity',()=>{
 render(<MatterClassification matter={null} receivers={[]} submit={vi.fn()} onDirty={()=>{}}/>);
 expect(screen.queryByRole('button',{name:'确认分类及承接'})).toBeNull();expect(screen.getByText('案管接收生成案件后，才能确认业务分类及承接。')).toBeVisible();
});
it('selects a qualified appointment for the existing case without requesting a second case',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<MatterClassification matter={{id:'case-one',number:'2026-001'}} receivers={[{id:'qualified',label:'王宁 · 业务承接'}]} submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('业务分类'),{target:{value:'GENERAL'}});fireEvent.change(screen.getByLabelText('承接人'),{target:{value:'qualified'}});fireEvent.change(screen.getByLabelText('分类说明'),{target:{value:'依接收范围分类'}});
 fireEvent.click(screen.getByRole('button',{name:'确认分类及承接'}));await waitFor(()=>expect(submit).toHaveBeenCalledWith({matterId:'case-one',classification:'GENERAL',receivingAppointmentId:'qualified',explanation:'依接收范围分类'}));
});
it('prefills correction without carrying old reason and retains the case identity',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<MatterClassification matter={{id:'case-one',number:'2026-001'}} receivers={[{id:'qualified',label:'王宁 · 业务承接'}]} correction={{category:'GENERAL',recipientId:'qualified',recipientLabel:'王宁 · 业务承接'}} submit={submit} onDirty={()=>{}}/>);
 expect(screen.getByLabelText('业务分类')).toHaveValue('GENERAL');expect(screen.getByLabelText('承接人')).toHaveValue('qualified');expect(screen.getByLabelText('更正说明')).toHaveValue('');
 fireEvent.change(screen.getByLabelText('业务分类'),{target:{value:'ENFORCEMENT'}});fireEvent.change(screen.getByLabelText('更正说明'),{target:{value:'核对后更正'}});fireEvent.click(screen.getByRole('button',{name:'确认更正'}));await waitFor(()=>expect(submit).toHaveBeenCalledWith({matterId:'case-one',classification:'ENFORCEMENT',receivingAppointmentId:'qualified',explanation:'核对后更正'}));
});
