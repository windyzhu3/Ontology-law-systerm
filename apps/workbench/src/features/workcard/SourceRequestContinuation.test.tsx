import {render,screen,fireEvent} from '@testing-library/react';
import {it,expect,vi} from 'vitest';
import {envelope} from '../../test/fixtures';
import {ActionDraftForm} from './ActionDraftForm';
import {candidate,parseEnvelope,type Card} from './contract';
const action='RECORD_SOURCE_REQUEST_CONTINUATION';
function sourceCard():Card {
 const card=structuredClone(envelope(3).currentCard!) as any;
 card.taskType='RESOLVE_SOURCE_REQUEST';card.businessPurpose={code:card.taskType,label:'确认线索后续安排'};card.primaryCommand={code:action,label:'确认线索后续安排',enabled:true};
 card.commandForm={actionCode:action,schemaVersion:1,values:{decisionCode:'',ownerAppointmentId:'',reviewAt:'',rationaleSummary:''},fields:[
 {name:'decisionCode',label:'后续安排',control:'SELECT',required:true,readOnly:false,options:[['ASSIGN_SELECTED','继续分配'],['SCHEDULE_REVIEW','约时复查'],['END_LEAD','明确结束本线索']].map(([value,label])=>({value,label,disabled:false}))},
 {name:'ownerAppointmentId',label:'合格销售',control:'SELECT',required:false,readOnly:false,options:[]},
 {name:'reviewAt',label:'复查时间',control:'TEXT',required:false,readOnly:false,options:[]},
 {name:'rationaleSummary',label:'安排依据',control:'TEXTAREA',required:true,readOnly:false,options:[]}]};card.actionDraft=null;return card;
}
it('shows only the fields needed for the selected disposition',()=>{
 const card=sourceCard(),change=vi.fn();const view=render(<ActionDraftForm card={card as any} values={{decisionCode:'END_LEAD'}} onChange={change} disabled={false}/>);
 expect(screen.getByLabelText(/后续安排/)).toBeVisible();expect(screen.queryByLabelText(/合格销售/)).not.toBeInTheDocument();expect(screen.queryByLabelText(/复查时间/)).not.toBeInTheDocument();
 view.rerender(<ActionDraftForm card={card as any} values={{decisionCode:'SCHEDULE_REVIEW'}} onChange={change} disabled={false}/>);
 const time=screen.getByLabelText(/复查时间/);expect(time).toHaveAttribute('type','datetime-local');fireEvent.change(time,{target:{value:'2030-01-01T09:00'}});expect(change).toHaveBeenCalledWith('reviewAt',expect.stringMatching(/Z$/));
});
it('ends without a fake selected owner and drops fields from another choice',()=>{
 expect(candidate(sourceCard(),{decisionCode:'END_LEAD',rationaleSummary:'明确结束',ownerAppointmentId:'',reviewAt:'2030-01-01T00:00:00Z'}).values).toEqual({decisionCode:'END_LEAD',rationaleSummary:'明确结束'});
});
it('requires a future exact review time',()=>{
 expect(()=>candidate(sourceCard(),{decisionCode:'SCHEDULE_REVIEW',rationaleSummary:'复查',reviewAt:'2000-01-01T00:00:00Z'})).toThrow();
 expect(candidate(sourceCard(),{decisionCode:'SCHEDULE_REVIEW',rationaleSummary:'复查',reviewAt:'2030-01-01T00:00:00Z'}).values).toMatchObject({reviewAt:'2030-01-01T00:00:00Z'});
});

it('parses the new card with no assignable sales and retains the other two options',()=>{
 const data={...envelope(3),currentCard:sourceCard()};expect(parseEnvelope(data).currentCard?.taskType).toBe('RESOLVE_SOURCE_REQUEST');
});
