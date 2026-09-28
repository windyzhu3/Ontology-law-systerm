import {it,expect} from 'vitest';
import {parseEnvelope} from './contract';
import {opportunityEnvelope} from '../../test/opportunityFixtures';
const stages=[['PREPARE_TRANSFER','SUBMIT_TRANSFER','TRANSFER_SUBMISSION'],['SUPPLEMENT_TRANSFER','RESUBMIT_TRANSFER','TRANSFER_SUBMISSION'],['REVIEW_TRANSFER','RECORD_TRANSFER_CONFLICT_REVIEW','TRANSFER_CONFLICT_REVIEW'],['ACCEPT_TRANSFER','RECORD_TRANSFER_INTAKE','TRANSFER_INTAKE'],['CLASSIFY_MATTER','CLASSIFY_MATTER','MATTER_CLASSIFICATION']];
it.each(stages)('accepts %s only with its matching transfer action and completion', (taskType,action,fact)=>{
 const base=opportunityEnvelope(),card={...base.currentCard,taskType,businessPurpose:{code:taskType,label:'转案办理'},primaryCommand:{code:action,label:'办理转案事项',enabled:true},expectedCompletionFact:fact,commandForm:{actionCode:action,schemaVersion:1,values:{},fields:[]}};
 expect(parseEnvelope({...base,currentCard:card}).currentCard?.taskType).toBe(taskType);
 expect(()=>parseEnvelope({...base,currentCard:{...card,expectedCompletionFact:'CONTRACT_REVISION'}})).toThrow();
 expect(()=>parseEnvelope({...base,currentCard:{...card,primaryCommand:{...card.primaryCommand,code:'FORM_CONTRACT'}}})).toThrow();
 expect(()=>parseEnvelope({...base,currentCard:{...card,commandForm:{...card.commandForm,values:{decision:'ACCEPT'}}}})).toThrow();
});
