import {it,expect,vi} from 'vitest';
import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {testSession,selectorId} from '../../test/fixtures';
import {TransportError} from '../../lib/sessionTransport';
import {AiCandidatePanel} from './AiCandidatePanel';
const candidate={opportunityId:selectorId,task:'FIELDS' as const,items:[{field:'contactName' as const,status:'CANDIDATE' as const,value:'林悦',citations:[{sourceId:'s1',quote:'联系人林悦'}]}],sources:[{id:'s1',kind:'TEXT' as const,label:'线索原文',text:'联系人林悦，电话未提供。'}],sourceToken:'opaque-token',generatedAt:'2026-09-28T10:00:00Z'};
const api=()=>({generate:vi.fn().mockResolvedValue(candidate),recheck:vi.fn().mockResolvedValue(undefined)});
it('generates only on request and adopts edited candidate only after source recheck',async()=>{
 const transport=api(),apply=vi.fn();render(<AiCandidatePanel session={testSession()} target={{opportunityId:selectorId}} task="FIELDS" api={transport} sourceVersion="v1" draftVersion="manual1" onApply={apply}/>);
 expect(transport.generate).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'生成字段建议'}));await screen.findByLabelText('候选联系人');fireEvent.change(screen.getByLabelText('候选联系人'),{target:{value:'林悦（已核实）'}});expect(apply).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole('button',{name:'采用联系人到原表单'}));await waitFor(()=>expect(apply).toHaveBeenCalledWith('contactName','林悦（已核实）'));expect(transport.recheck).toHaveBeenCalledTimes(1);expect(screen.getByText('联系人林悦')).toBeInTheDocument();
});
it('ignore and model failure preserve the original manual draft',async()=>{
 const transport=api(),apply=vi.fn();render(<AiCandidatePanel session={testSession()} target={{opportunityId:selectorId}} task="FIELDS" api={transport} sourceVersion="v1" draftVersion="manual1" onApply={apply}/>);
 fireEvent.click(screen.getByRole('button',{name:'生成字段建议'}));await screen.findByLabelText('候选联系人');fireEvent.click(screen.getByRole('button',{name:'忽略建议'}));expect(screen.queryByLabelText('候选联系人')).toBeNull();expect(apply).not.toHaveBeenCalled();
 transport.generate.mockRejectedValueOnce(new TransportError(503,'SERVICE_UNAVAILABLE'));fireEvent.click(screen.getByRole('button',{name:'生成字段建议'}));expect(await screen.findByText(/已填写内容保留/)).toBeInTheDocument();expect(apply).not.toHaveBeenCalled();
});
it('source changes and stale server rechecks prevent adoption',async()=>{
 const transport=api(),apply=vi.fn(),session=testSession();const props={session,target:{opportunityId:selectorId},task:'FIELDS' as const,api:transport,sourceVersion:'v1',draftVersion:'manual1',onApply:apply};const {rerender}=render(<AiCandidatePanel {...props}/>);
 fireEvent.click(screen.getByRole('button',{name:'生成字段建议'}));await screen.findByLabelText('候选联系人');rerender(<AiCandidatePanel {...props} sourceVersion="v2"/>);expect(screen.getByRole('button',{name:'采用联系人到原表单'})).toBeDisabled();
 fireEvent.click(screen.getByRole('button',{name:'重新生成并核对'}));await waitFor(()=>expect(transport.generate).toHaveBeenCalledTimes(2));transport.recheck.mockRejectedValueOnce(new TransportError(412,'STALE_SUBJECT'));fireEvent.click(screen.getByRole('button',{name:'采用联系人到原表单'}));expect(await screen.findByText(/来源已更新/)).toBeInTheDocument();expect(apply).not.toHaveBeenCalled();
});
it('a manual edit during recheck and a changed identity discard late adoption',async()=>{
 const transport=api(),apply=vi.fn(),session=testSession();let finish!:()=>void;transport.recheck.mockImplementation(()=>new Promise<void>(resolve=>{finish=resolve;}));const props={session,target:{opportunityId:selectorId},task:'FIELDS' as const,api:transport,sourceVersion:'v1',draftVersion:'manual1',onApply:apply};const {rerender}=render(<AiCandidatePanel {...props}/>);
 fireEvent.click(screen.getByRole('button',{name:'生成字段建议'}));await screen.findByLabelText('候选联系人');fireEvent.click(screen.getByRole('button',{name:'采用联系人到原表单'}));await waitFor(()=>expect(transport.recheck).toHaveBeenCalled());rerender(<AiCandidatePanel {...props} draftVersion="manual2"/>);finish();await screen.findByText(/原表单已修改/);expect(apply).not.toHaveBeenCalled();
 rerender(<AiCandidatePanel {...props} session={{...session,identityEpoch:2,actorScopeKey:'other'}}/>);expect(screen.queryByLabelText('候选联系人')).toBeNull();
});
it('lets a person acknowledge a missing material hint without inventing a form value',async()=>{
 const transport=api(),apply=vi.fn();transport.generate.mockResolvedValue({...candidate,task:'MATERIALS',items:[{field:'CORRESPONDENCE',status:'MISSING',value:null,citations:[{sourceId:'rule1',quote:'往来记录'}]}],sources:[{id:'rule1',kind:'RULE',label:'销售准备参考',text:'往来记录：销售准备参考'}]});
 render(<AiCandidatePanel session={testSession()} target={{opportunityId:selectorId}} task="MATERIALS" api={transport} sourceVersion="v1" draftVersion="manual1" onApply={apply}/>);
 fireEvent.click(screen.getByRole('button',{name:'生成材料缺项提示'}));fireEvent.click(await screen.findByRole('button',{name:'已核对相关往来记录提示'}));expect(await screen.findByText(/材料状态保持不变/)).toBeInTheDocument();expect(transport.recheck).toHaveBeenCalledTimes(1);expect(apply).not.toHaveBeenCalled();
});
