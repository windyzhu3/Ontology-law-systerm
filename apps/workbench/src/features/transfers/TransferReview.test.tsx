import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {TransferReview} from './TransferReview';

it('returns a precise missing item and reason without an acceptance confirmation',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<TransferReview canAccept submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('接收结果'),{target:{value:'RETURN'}});
 expect(screen.queryByRole('checkbox')).toBeNull();
 fireEvent.change(screen.getByLabelText('需要补正的项目'),{target:{value:'CLIENT_IDENTITY'}});
 fireEvent.change(screen.getByLabelText('处理说明'),{target:{value:'请补充完整主体证明'}});
 fireEvent.click(screen.getByRole('button',{name:'记录接收结果'}));
 await waitFor(()=>expect(submit).toHaveBeenCalledWith({decision:'RETURN',requirement:'CLIENT_IDENTITY',explanation:'请补充完整主体证明'}));
 expect(screen.getByRole('button',{name:'记录接收结果'})).toBeDisabled();
});
it('does not offer acceptance when the exact review is not clear',()=>{
 render(<TransferReview canAccept={false} submit={vi.fn()} onDirty={()=>{}}/>);
 expect(screen.queryByRole('option',{name:'接收并生成案件'})).toBeNull();
 expect(screen.queryByRole('checkbox')).toBeNull();
});
it('requires human acceptance and leaves case identity to the server',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<TransferReview canAccept submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('处理说明'),{target:{value:'材料与批准合同一致'}});
 fireEvent.click(screen.getByRole('button',{name:'记录接收结果'}));expect(submit).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole('checkbox'));fireEvent.click(screen.getByRole('button',{name:'记录接收结果'}));
 await waitFor(()=>expect(submit).toHaveBeenCalledWith({decision:'ACCEPT',explanation:'材料与批准合同一致',acceptanceChecked:true}));
 expect(screen.queryByLabelText('业务分类')).toBeNull();
});
it('locks an unknown acceptance result instead of generating a second write',async()=>{
 const submit=vi.fn().mockRejectedValue(new Error('lost response'));render(<TransferReview canAccept submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('处理说明'),{target:{value:'核对完整'}});fireEvent.click(screen.getByRole('checkbox'));
 fireEvent.click(screen.getByRole('button',{name:'记录接收结果'}));
 expect(await screen.findByRole('alert')).toHaveTextContent('核对原提交结果');
 expect(screen.getByRole('button',{name:'记录接收结果'})).toBeDisabled();expect(submit).toHaveBeenCalledOnce();
});
