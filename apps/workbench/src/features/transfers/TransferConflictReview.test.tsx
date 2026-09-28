import {fireEvent,render,screen,waitFor} from '@testing-library/react';import {it,expect,vi} from 'vitest';
import {TransferConflictReview} from './TransferConflictReview';
it('only offers outcomes allowed by the current exact conflict scan',()=>{
 render(<TransferConflictReview outcomes={['NEED_INFO','BLOCKED']} submit={vi.fn()} onDirty={()=>{}}/>);
 expect(screen.queryByRole('option',{name:'本次审查通过'})).toBeNull();
 expect(screen.getByRole('option',{name:'需要补充主体或材料'})).toBeVisible();
});
it('requires independent human confirmation of this review rather than copying precontract results',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<TransferConflictReview outcomes={['CLEAR','NEED_INFO']} submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('审查结果'),{target:{value:'CLEAR'}});fireEvent.change(screen.getByLabelText('审查说明'),{target:{value:'已核对本次准确主体和冲突依据'}});
 fireEvent.click(screen.getByRole('button',{name:'记录本次审查结果'}));expect(submit).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('checkbox'));
 fireEvent.click(screen.getByRole('button',{name:'记录本次审查结果'}));await waitFor(()=>expect(submit).toHaveBeenCalledWith({outcome:'CLEAR',explanation:'已核对本次准确主体和冲突依据',scopeChecked:true}));
});
