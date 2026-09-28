import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {CustomerRequirementsCard} from '../opportunities/CustomerRequirementsCard';
import {MaterialsCard} from '../opportunities/MaterialsCard';
import {CurrentCard} from '../workcard/CurrentCard';
import {parseEnvelope} from '../workcard/contract';
import {opportunityEnvelope} from '../../test/opportunityFixtures';
import {testSession,selectorId,taskId} from '../../test/fixtures';
import {RecoveryStore} from '../session/recoveryMarker';
import type {AiTask,AiField} from '../../lib/aiCandidateTransport';
const detail={opportunity:{id:selectorId,revision:0},customerLabel:'合成客户',ownerLabel:'销售',taskState:'OPEN' as const,canHandle:true,task:{id:taskId,revision:0},nextActionLabel:'跟进事项'};
function ai(task:AiTask,field:AiField,value:string){return {generate:vi.fn().mockResolvedValue({opportunityId:selectorId,task,items:[{field,status:'CANDIDATE',value,citations:[{sourceId:'s1',quote:'合成来源'}]}],sources:[{id:'s1',kind:task==='MATERIALS'?'RULE':'TEXT',label:'原始依据',text:'合成来源'}],sourceToken:'opaque-token',generatedAt:'2026-09-28T10:00:00Z'}),recheck:vi.fn().mockResolvedValue(undefined)};}
it('adopts contact into the original customer draft and activates its existing leave guard',async()=>{
 const document={participants:[],unknownOpponent:true,matterName:'合成事项',customerGoal:'人工目标',serviceScope:'人工范围',knownConstraints:'',contactName:'原联系人',contactPhone:''};
 const context={opportunity:detail.opportunity,responsibilityBasis:detail.opportunity,editable:true,closed:false,draftConsumed:false,currentOwnerAppointmentId:taskId,canCreateParty:true,canMaintainSharedParty:false,source:{lead:{id:selectorId,revision:0}},parties:[],history:[],draft:{selector:{id:taskId,revision:0},factRef:'a'.repeat(43),document,partySnapshots:[],actorAppointmentId:taskId,createdAt:'2026-09-28T10:00:00Z'}};
 const api={context:vi.fn().mockResolvedValue(context),recovery:new RecoveryStore(sessionStorage),write:vi.fn(),receipt:vi.fn(),search:vi.fn()},onBack=vi.fn();
 render(<CustomerRequirementsCard session={testSession()} detail={detail as never} api={api as never} aiApi={ai('FIELDS','contactName','林悦')} onBack={onBack} onContinue={vi.fn()} onDenied={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:'生成字段建议'}));fireEvent.click(await screen.findByRole('button',{name:'采用联系人到原表单'}));await waitFor(()=>expect(screen.getByLabelText('联系人')).toHaveValue('林悦'));expect(screen.getByLabelText('客户目标 *')).toHaveValue('人工目标');expect(api.write).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole('button',{name:'返回商机台账'}));expect(screen.getByText('本次有未保存的修改')).toBeInTheDocument();expect(onBack).not.toHaveBeenCalled();
});
it('adopts summary into the original work card without saving or recording progress',async()=>{
 const data=parseEnvelope(opportunityEnvelope()),save=vi.fn(),submit=vi.fn(),dirty=vi.fn(),transport=ai('SUMMARY','progressSummary','经核对的历史摘要');
 render(<CurrentCard session={testSession()} aiApi={transport} card={data.currentCard!} composer={data.chatComposer} busy={false} blocked={false} save={save} submit={submit} onDirtyChange={dirty}/>);
 fireEvent.click(screen.getByRole('button',{name:'生成跟进摘要'}));fireEvent.click(await screen.findByRole('button',{name:'采用跟进摘要到原表单'}));await waitFor(()=>expect(screen.getByLabelText('进展摘要 *')).toHaveValue('经核对的历史摘要'));expect(save).not.toHaveBeenCalled();expect(submit).not.toHaveBeenCalled();expect(dirty).toHaveBeenLastCalledWith(true);expect(transport.generate.mock.calls[0][1]).toEqual({taskId:data.currentCard!.taskId});
});
it('acknowledges a material suggestion without accepting, uploading or changing material state',async()=>{
 const api={context:vi.fn().mockResolvedValue({opportunity:detail.opportunity,responsibilityBasis:detail.opportunity,editable:true,closed:false,versions:[],pendingUploads:[]}),recovery:new RecoveryStore(sessionStorage),write:vi.fn(),upload:vi.fn(),receipt:vi.fn(),content:vi.fn(),status:vi.fn()};
 render(<MaterialsCard session={testSession()} detail={detail as never} api={api as never} aiApi={ai('MATERIALS','CORRESPONDENCE','请核对当前可见往来材料')} onBack={vi.fn()} onContinue={vi.fn()} onDenied={vi.fn()}/>);
 fireEvent.click(await screen.findByRole('button',{name:'生成材料缺项提示'}));fireEvent.click(await screen.findByRole('button',{name:'已核对相关往来记录提示'}));expect(await screen.findByText(/材料状态保持不变/)).toBeInTheDocument();expect(screen.getByText('尚未接收材料。')).toBeInTheDocument();expect(api.write).not.toHaveBeenCalled();expect(api.upload).not.toHaveBeenCalled();
});
