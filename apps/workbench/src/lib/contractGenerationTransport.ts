import {createMaterialsTransport,type MaterialsTransport} from './materialsTransport';
import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
import type {ContractContext,GeneratedContract} from '../features/contracts/types';
import type {ContractWrite} from './contractsTransport';
import type {RecoveryStore} from '../features/session/recoveryMarker';
const same=(a:{id:string;revision:number}|null|undefined,b:{id:string;revision:number}|null|undefined)=>a===b||!!a&&!!b&&a.id===b.id&&a.revision===b.revision;
const bytes=(base64:string)=>Uint8Array.from(atob(base64),ch=>ch.charCodeAt(0));
export class ContractGenerationFailure extends TransportError {
 constructor(status:number,code:string|undefined,readonly context:ContractContext){super(status,code,true);}
}
export async function acceptGeneratedMaterial(api:MaterialsTransport,session:WorkbenchSession,context:ContractContext,candidate:GeneratedContract,signal:AbortSignal):Promise<string>{
 const id=context.opportunity.id,name='contract-generated-'+candidate.bodySha256+'.pdf';
 let current=await api.context(session,id,signal);
 if(!current.editable||current.closed||!same(current.opportunity,context.opportunity)||!same(current.responsibilityBasis,context.responsibilityBasis)||!same(current.confirmation,context.customerConfirmation))throw new TransportError(412,'STALE_SUBJECT',true);
 for(const existing of current.versions.filter(v=>v.fileName===name&&v.purpose==='CONTRACT_BUSINESS'&&v.mediaType==='application/pdf')){const blob=await api.content(session,id,existing,false,signal);const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer())),v=>v.toString(16).padStart(2,'0')).join('');if(digest===candidate.bodySha256)return existing.selector.id;}
 let upload=current.pendingUploads.find(u=>u.fileName===name&&u.purpose==='CONTRACT_BUSINESS'&&!['EXPIRED','REJECTED','ACCEPTED'].includes(u.state));
 const basis={expectedOpportunityRevision:context.opportunity.revision,responsibilityBasis:context.responsibilityBasis!,expectedConfirmation:context.customerConfirmation,expectedPreviousVersion:null};
 if(!upload){const receipt=await api.write(session,{key:crypto.randomUUID(),opportunityId:id,command:'OPEN_OPPORTUNITY_MATERIAL_UPLOAD',body:{...basis,purpose:'CONTRACT_BUSINESS',fileName:name,note:'系统生成合同正文，尚未签署'}},signal);current=await api.context(session,id,signal);upload=current.pendingUploads.find(u=>u.factRef===receipt.resultFact.factRef);}
 if(!upload)throw new TransportError(412,'STALE_EVIDENCE',true);
 if(!same(upload.confirmation,context.customerConfirmation)||upload.previousVersion!==null)throw new TransportError(412,'STALE_EVIDENCE',true);
 if(upload.state==='OPEN')upload=await api.upload(session,id,upload.selector.id,new File([bytes(candidate.pdfBase64)],name,{type:'application/pdf'}),signal);
 else upload=await api.status(session,id,upload.selector.id,signal);
 if(upload.state!=='PASSED')throw new TransportError(503,'SERVICE_UNAVAILABLE',true);
 const receipt=await api.write(session,{key:crypto.randomUUID(),opportunityId:id,command:'ACCEPT_OPPORTUNITY_MATERIAL',body:{...basis,uploadSession:upload.selector}},signal);
 current=await api.context(session,id,signal);const accepted=current.versions.find(v=>v.factRef===receipt.resultFact.factRef);if(!accepted)throw new TransportError(412,'STALE_EVIDENCE',true);return accepted.selector.id;
}
export function createContractGenerationTransport(recovery:RecoveryStore,fetcher:typeof fetch=fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false),materials=createMaterialsTransport(recovery,fetcher);
 return {materials,
  async generate(s:WorkbenchSession,id:string,body:ContractWrite['body'],signal:AbortSignal):Promise<GeneratedContract>{
   const headers=await auth(s,signal);const response=await fetcher('/api/v1/opportunities/'+encodeURIComponent(id)+'/contracts/generate',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify(body),cache:'no-store',signal});assertCurrent(s,signal);const value:unknown=await response.json();checked(s,signal,{response,data:value,error:value});
   if(!value||typeof value!=='object')throw Error('生成结果无法核对');const v=value as GeneratedContract;
   if(typeof v.pdfBase64!=='string'||v.pdfBase64.length>27962028||!/^JVBER/.test(v.pdfBase64)||typeof v.previewText!=='string'||!v.previewText.trim()||v.previewText.length>2000000||typeof v.generationProof!=='string'||v.generationProof.length>2048||!Number.isFinite(Date.parse(v.expiresAt))||! /^[0-9a-f]{64}$/.test(v.bodySha256))throw Error('生成结果无法核对');
   const pdf=bytes(v.pdfBase64);const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',pdf)),v=>v.toString(16).padStart(2,'0')).join('');assertCurrent(s,signal);if(digest!==v.bodySha256)throw Error('生成正文摘要不一致');return v;
  },
  accept:(s:WorkbenchSession,c:ContractContext,g:GeneratedContract,signal:AbortSignal)=>acceptGeneratedMaterial(materials,s,c,g,signal),
 };
}
