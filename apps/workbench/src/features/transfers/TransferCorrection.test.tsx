import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {TransferCorrection} from './TransferCorrection';
it('responds to every exact returned item with accepted material without adding classification fields',async()=>{
 const submit=vi.fn().mockResolvedValue(true);
 render(<TransferCorrection items={[{id:'item-a',label:'委托主体证明',reason:'请补齐完整主体依据'}]} materials={[{id:'proof',sha256:'a'.repeat(64),fileName:'主体证明.pdf'}]} submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('补充委托主体证明'),{target:{value:'proof'}});
 fireEvent.change(screen.getByLabelText('逐项回应：委托主体证明'),{target:{value:'已补齐主体证明的缺失页'}});
 fireEvent.click(screen.getByRole('button',{name:'补正并重新提交'}));
 await waitFor(()=>expect(submit).toHaveBeenCalledWith({corrections:[{returnItemId:'item-a',response:'已补齐主体证明的缺失页',material:{versionId:'proof',sha256:'a'.repeat(64)}}]}));
 expect(screen.queryByText('业务分类')).not.toBeInTheDocument();
});
it('requires every returned item response before sending',async()=>{
 const submit=vi.fn();render(<TransferCorrection items={[{id:'item-a',label:'委托主体证明',reason:'请补齐完整主体依据'}]} materials={[]} submit={submit} onDirty={()=>{}}/>);
 fireEvent.submit(screen.getByRole('button',{name:'补正并重新提交'}).closest('form')!);
 expect(submit).not.toHaveBeenCalled();
 expect(await screen.findByRole('alert')).toBeInTheDocument();
});

it('allows a handover explanation correction without forcing a new attachment',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<TransferCorrection items={[{id:'explanation',label:'交接说明',reason:'请说明服务范围',requiresMaterial:false}]} materials={[]} submit={submit} onDirty={()=>{}}/>);
 expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
 fireEvent.change(screen.getByLabelText('逐项回应：交接说明'),{target:{value:'已补充合同约定的完整服务范围'}});
 fireEvent.click(screen.getByRole('button',{name:'补正并重新提交'}));
 await waitFor(()=>expect(submit).toHaveBeenCalledWith({corrections:[{returnItemId:'explanation',response:'已补充合同约定的完整服务范围'}]}));
});
