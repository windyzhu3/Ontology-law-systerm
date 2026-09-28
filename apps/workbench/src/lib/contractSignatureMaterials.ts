import type {MaterialsTransport} from './materialsTransport';
import {TransportError,type WorkbenchSession} from './sessionTransport';
import type {ContractContext} from '../features/contracts/types';
const same=(a:{id:string;revision:number}|null|undefined,b:{id:string;revision:number}|null|undefined)=>a===b||!!a&&!!b&&a.id===b.id&&a.revision===b.revision;
export async function uploadSignatureMaterial(api:MaterialsTransport,session:WorkbenchSession,context:ContractContext,file:File|undefined,uploadId:string|undefined,signal:AbortSignal):Promise<{state:string;uploadId:string;materialId?:string}>{
 const id=context.opportunity.id;let current=await api.context(session,id,signal);
 if(!current.editable||current.closed||!same(current.opportunity,context.opportunity)||!same(current.responsibilityBasis,context.responsibilityBasis)||!same(current.confirmation,context.customerConfirmation))throw new TransportError(412,'STALE_SUBJECT',true);
 const basis={expectedOpportunityRevision:context.opportunity.revision,responsibilityBasis:context.responsibilityBasis!,expectedConfirmation:context.customerConfirmation,expectedPreviousVersion:null};
 let upload=uploadId?current.pendingUploads.find(u=>u.selector.id===uploadId):undefined;
 if(!upload){if(!file)throw new TransportError(412,'STALE_EVIDENCE',true);const receipt=await api.write(session,{key:crypto.randomUUID(),opportunityId:id,command:'OPEN_OPPORTUNITY_MATERIAL_UPLOAD',body:{...basis,purpose:'CONTRACT_BUSINESS',fileName:file.name,note:'人工签署证据，尚未经授权核验'}},signal);current=await api.context(session,id,signal);upload=current.pendingUploads.find(u=>u.factRef===receipt.resultFact.factRef);}
 if(!upload)throw new TransportError(412,'STALE_EVIDENCE',true);
 if(upload.state==='OPEN'&&file)upload=await api.upload(session,id,upload.selector.id,file,signal);else upload=await api.status(session,id,upload.selector.id,signal);
 if(upload.state!=='PASSED')return {state:upload.state,uploadId:upload.selector.id};
 if(!same(upload.confirmation,context.customerConfirmation)||upload.previousVersion!==null)throw new TransportError(412,'STALE_EVIDENCE',true);
 const receipt=await api.write(session,{key:crypto.randomUUID(),opportunityId:id,command:'ACCEPT_OPPORTUNITY_MATERIAL',body:{...basis,uploadSession:upload.selector}},signal);current=await api.context(session,id,signal);const accepted=current.versions.find(v=>v.factRef===receipt.resultFact.factRef);if(!accepted)throw new TransportError(412,'STALE_EVIDENCE',true);return {state:'ACCEPTED',uploadId:upload.selector.id,materialId:accepted.selector.id};
}
