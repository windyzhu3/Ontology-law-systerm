import {it,expect,vi} from 'vitest';
import {render,screen,fireEvent,waitFor} from '@testing-library/react';
import {testSession,selectorId} from '../../test/fixtures';
import {TransportError} from '../../lib/sessionTransport';
import {aiLabels,type AiTask,type AiField,type AiCandidate} from '../../lib/aiCandidateTransport';
import {AiCandidatePanel} from './AiCandidatePanel';

// Protocol-only synthetic samples. These do not claim external provider calls.
for(const task of ['FIELDS','SUMMARY','MATERIALS'] as AiTask[]){
 for(const sample of ['NORMAL','MISSING','CONFLICT','REVOKED','FAILURE'] as const){
  it(`Haihua synthetic ${task}/${sample} preserves human confirmation and manual values`,async()=>{
   const field:AiField=task==='FIELDS'?'contactName':task==='SUMMARY'?'progressSummary':'CORRESPONDENCE';
   const label=aiLabels[field];
   const status=sample==='MISSING'?'MISSING':sample==='CONFLICT'?'CONFLICT':'CANDIDATE';
   const candidate:AiCandidate={opportunityId:selectorId,task,items:[{field,status,value:status==='CANDIDATE'?'纯合成候选':null,citations:[{sourceId:'s1',quote:'纯合成依据'}]}],sources:[{id:'s1',kind:task==='MATERIALS'?'RULE':task==='SUMMARY'?'PROGRESS':'TEXT',label:'明确标记的合成样本',text:'纯合成依据，不用于真实业务。'}],sourceToken:'synthetic-only-token',generatedAt:'2026-10-01T00:00:00Z'};
   const transport={generate:vi.fn().mockResolvedValue(candidate),recheck:vi.fn().mockResolvedValue(undefined)},apply=vi.fn();
   if(sample==='FAILURE')transport.generate.mockRejectedValueOnce(new TransportError(503,'SERVICE_UNAVAILABLE'));
   if(sample==='REVOKED')transport.recheck.mockRejectedValueOnce(new TransportError(403,'NOT_AUTHORIZED'));
   render(<AiCandidatePanel session={testSession()} target={{opportunityId:selectorId}} task={task} api={transport} sourceVersion="original-source" draftVersion="original-manual-draft" onApply={apply}/>);
   expect(transport.generate).not.toHaveBeenCalled();
   fireEvent.click(screen.getByRole('button',{name:task==='FIELDS'?'生成字段建议':task==='SUMMARY'?'生成跟进摘要':'生成材料缺项提示'}));
   if(sample==='FAILURE'){await screen.findByText(/已填写内容保留/);expect(apply).not.toHaveBeenCalled();expect(transport.recheck).not.toHaveBeenCalled();return;}
   await screen.findByText('纯合成依据');expect(apply).not.toHaveBeenCalled();
   if(sample==='MISSING'||sample==='CONFLICT'){
    expect(await screen.findByText(sample==='MISSING'?new RegExp('未找到足够依据'):new RegExp('依据有冲突'))).toBeVisible();
    expect(screen.queryByRole('button',{name:'采用'+label+'到原表单'})).toBeNull();
    fireEvent.click(screen.getByRole('button',{name:'忽略建议'}));expect(apply).not.toHaveBeenCalled();return;
   }
   fireEvent.change(screen.getByLabelText('候选'+label),{target:{value:'人工核实并修改的合成内容'}});expect(apply).not.toHaveBeenCalled();
   fireEvent.click(screen.getByRole('button',{name:task==='MATERIALS'?'已核对'+label+'提示':'采用'+label+'到原表单'}));
   await waitFor(()=>expect(transport.recheck).toHaveBeenCalledTimes(1));
   if(sample==='REVOKED'){await screen.findByText(/建议已清除/);expect(screen.queryByLabelText('候选'+label)).toBeNull();expect(apply).not.toHaveBeenCalled();}
   else if(task==='MATERIALS'){await screen.findByText(/材料状态保持不变/);expect(apply).not.toHaveBeenCalled();}
   else await waitFor(()=>expect(apply).toHaveBeenCalledWith(field,'人工核实并修改的合成内容'));
  });
 }
}
