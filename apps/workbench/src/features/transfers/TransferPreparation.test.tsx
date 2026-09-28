import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {TransferPreparation} from './TransferPreparation';
const identity={id:'identity',sha256:'a'.repeat(64),fileName:'主体证明.pdf'},archive={id:'archive',sha256:'b'.repeat(64),fileName:'签署归档.pdf'};
it('submits exact accepted material versions with human consistency and no early business classification',async()=>{
 const submit=vi.fn().mockResolvedValue(true);render(<TransferPreparation materials={[identity,archive]} submit={submit} onDirty={()=>{}}/>);
 fireEvent.change(screen.getByLabelText('委托主体证明'),{target:{value:identity.id}});fireEvent.change(screen.getByLabelText('完整签署归档'),{target:{value:archive.id}});
 fireEvent.change(screen.getByLabelText('交接说明'),{target:{value:'依批准合同移交'}});fireEvent.click(screen.getByRole('checkbox'));
 fireEvent.click(screen.getByRole('button',{name:'提交案管接收'}));
 await waitFor(()=>expect(submit).toHaveBeenCalledWith({clientIdentity:{versionId:'identity',sha256:identity.sha256},signatureArchive:{versionId:'archive',sha256:archive.sha256},explanation:'依批准合同移交',consistencyChecked:true,corrections:[]}));
 expect(screen.queryByLabelText('业务分类')).toBeNull();
});
it('rejects a material removed from the current accepted material list',()=>{
 const submit=vi.fn();const props={submit,onDirty:()=>{}};const {rerender}=render(<TransferPreparation {...props} materials={[identity,archive]}/>);
 fireEvent.change(screen.getByLabelText('委托主体证明'),{target:{value:identity.id}});fireEvent.change(screen.getByLabelText('完整签署归档'),{target:{value:archive.id}});
 fireEvent.change(screen.getByLabelText('交接说明'),{target:{value:'移交'}});fireEvent.click(screen.getByRole('checkbox'));
 rerender(<TransferPreparation {...props} materials={[archive]}/>);fireEvent.click(screen.getByRole('button',{name:'提交案管接收'}));expect(submit).not.toHaveBeenCalled();
});
