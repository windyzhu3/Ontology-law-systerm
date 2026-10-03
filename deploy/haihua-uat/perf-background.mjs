import {chromium} from '@playwright/test';import crypto from 'node:crypto';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {login,save,runtime,origin} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Separate E1-derived performance instance required');
const map=JSON.parse(fs.readFileSync(path.join(runtime,'account-map.json'),'utf8'));
const stateFile=path.join(runtime,'performance-background.json');
const state=fs.existsSync(stateFile)?JSON.parse(fs.readFileSync(stateFile,'utf8')):{cases:[],commands:[],baseline:'E1',stage:'PREPARE_CONTRACT'};
if(state.pending)throw Error('Original unresolved command requires receipt investigation; no automatic replay');
const browser=await chromium.launch({channel:'chrome',headless:true}),actors={};
async function request(actor,url,method='GET',body,extra={}){
 const a=actors[actor];const started=Date.now();
 const r=await a.page.evaluate(async({url,method,body,headers})=>{try{const response=await fetch(url,{method,headers,cache:'no-store',body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});return {status:response.status,data:await response.json()};}catch{return {status:0,data:{code:'TRANSPORT_UNKNOWN'}};}},{url:origin+url,method,body,headers:{...a.apiHeaders(),...(body===undefined?{}:{'Content-Type':'application/json'}),...extra}});
 if(r.status<200||r.status>=300)throw Error(actor+' '+method+' '+url+' '+r.status+' '+r.data.code);
 return {...r,elapsedMs:Date.now()-started};
}
async function write(actor,url,body,method='POST',extra={}){
 const key=crypto.randomUUID();state.pending={actor,url,method,key};save('performance-background.json',state);
 const r=await request(actor,url,method,body,{'Idempotency-Key':key,...extra});
 const receipt=r.data.receipt??r.data;assert.ok(receipt.outcome==='SUCCEEDED'||(method==='PUT'&&receipt.outcome==='NO_CHANGE'));
 state.commands.push({actor,url,method,key,status:r.status,elapsedMs:r.elapsedMs,receipt});delete state.pending;save('performance-background.json',state);return r.data;
}
async function task(actor,label,type){
 for(let attempt=0;attempt<20;attempt++){
  const e=(await request(actor,'/api/v1/workcards/current')).data;
  const t=e.myTasks?.find(t=>t.subjectTitle===label&&t.businessPurpose.code===type);
  if(t)return (await request(actor,'/api/v1/workcards/current?taskId='+t.taskId)).data.currentCard;
  await new Promise(resolve=>setTimeout(resolve,500));
 }
 throw Error('Authorized current task not visible: '+label+' '+type);
}
async function legacy(actor,c,action,values,suffix){
 const d=c.actionDraft&&JSON.stringify(c.actionDraft.values)===JSON.stringify(values)?{draft:c.actionDraft,preconditions:c.preconditions}:await write(actor,'/api/v1/tasks/'+c.taskId+'/draft',{actionCode:action,schemaVersion:1,values},'PUT',c.preconditions.draftETag?{'If-Match':c.preconditions.draftETag}:{'If-None-Match':'*'});
 return write(actor,'/api/v1/tasks/'+c.taskId+'/commands/'+suffix,{...values,draftId:d.draft.draftId,expectedDraftRevision:d.draft.draftRevision,draftDigest:d.draft.digest},'POST',{'If-Match':d.preconditions.taskETag});
}
const contractBody=(c,values)=>({expectedOpportunityRevision:c.opportunity.revision,responsibilityBasis:c.responsibilityBasis,customerConfirmation:c.customerConfirmation,expectedContract:c.contract?.selector??null,expectedDraft:c.draft?.selector??null,expectedVersion:c.contract?.currentRevision??null,expectedWorkflow:c.workflow?.selector??null,values:{...values,expectedTermination:c.termination?.selector??null}});
async function contract(actor,id,suffix,values){const c=(await request(actor,'/api/v1/opportunities/'+id+'/contracts')).data;await write(actor,'/api/v1/opportunities/'+id+'/contracts/'+suffix,contractBody(c,values));return (await request(actor,'/api/v1/opportunities/'+id+'/contracts')).data;}
try{
 for(const actor of ['sales01','sales02','sales03','sales04','sales05','sales_manager01','sales_manager02'])actors[actor]=await login(browser,actor);
 let refreshedAt=Date.now();
 for(let index=state.cases.length+1;index<=100;index++){
  if(Date.now()-refreshedAt>120000){for(const actor of Object.keys(actors)){await actors[actor].context.close();actors[actor]=await login(browser,actor);}refreshedAt=Date.now();}
  const actor='sales0'+((index-1)%5+1),manager=index%5===4||index%5===0?'sales_manager02':'sales_manager01';
  const code='HH-PERF-'+String(index).padStart(4,'0'),label=code+' 合成客户',phone='+8613901'+String(index).padStart(6,'0');
  const resume=state.active?.index===index?state.active:null;
  if(resume?.stage!=='CUSTOMER_DRAFT'){
  if(state.resumeCapturedIndex!==index)await write(actor,'/api/v1/leads',{sourceAccountCode:manager.endsWith('02')?'HH_S2_MANUAL':'HH_S1_MANUAL',sourceChannelCode:'HAIHUA_UAT',serviceCategoryCode:'SYNTHETIC_LEGAL',jurisdictionCode:'CN',urgencyCode:'NORMAL',sourceRecordKey:code,capturedAt:new Date().toISOString(),customerName:label,contactName:code+' 联系人',legalNeedSummary:'纯合成性能背景，不用于实际委托。',phone});
  if(state.resumeProgressIndex!==index){const assignment=await task(manager,label,'ASSIGN_LEAD');
  await legacy(manager,assignment,'ASSIGN_LEAD',{ownerAppointmentId:map.users[actor].appointmentId},'assign-lead');
  const contact=await task(actor,label,'CONTACT_LEAD');
  await legacy(actor,contact,'RECORD_CONTACT_RESULT',{...contact.commandForm.values,resultCode:'CONNECTED_VALID',contactChannelCode:'PHONE',legalNeed:'纯合成咨询',resultSummary:'合成客户已确认咨询需要，不用于实际业务。'},'record-contact-result');}
  const progress=await task(actor,label,'PROGRESS_OPPORTUNITY');
  const continuation=(await request(actor,'/api/v1/opportunity-tasks/'+progress.taskId+'/context')).data;
  const id=continuation.opportunity?.id??continuation.opportunityId??continuation.id;
  if(!id)throw Error('Public continuation opportunity ID missing');
  state.active={index,stage:'CONTACTED',id};save('performance-background.json',state);
  }
  const id=state.active.id;
  const c=(await request(actor,'/api/v1/opportunities/'+id+'/customer-requirements')).data;
  const customerBody={expectedOpportunityRevision:c.opportunity.revision,responsibilityBasis:c.responsibilityBasis,expectedDraft:c.draft?.selector??null,expectedConfirmation:c.confirmation?.selector??null};
  if(state.active.stage!=='CUSTOMER_DRAFT')await write(actor,'/api/v1/opportunities/'+id+'/customer-requirements/draft',{...customerBody,document:{participants:['CLIENT','OPPONENT'].map(role=>({role,party:null,newParty:{kind:'ORGANIZATION',name:code+' '+role+' 合成组织',distinctIdentityConfirmed:true},profileChange:null})),unknownOpponent:false,matterName:code+' 合成咨询',customerGoal:'核对合成事实',serviceScope:'合成法律咨询',knownConstraints:'',contactName:code+' 联系人',contactPhone:phone}});
  state.active.stage='CUSTOMER_DRAFT';save('performance-background.json',state);
  const fresh=(await request(actor,'/api/v1/opportunities/'+id+'/customer-requirements')).data;
  await write(actor,'/api/v1/opportunities/'+id+'/customer-requirements/confirm',{...customerBody,expectedDraft:fresh.draft.selector});
  await contract(actor,id,'preparation-requests',{commercial:{currency:'CNY',scope:'纯合成咨询',lines:[{description:'合成咨询费',amountMinor:1000000,discount:false}],conditionalFee:null,paymentTerms:'不要求先到账后转案'},reason:'合成客户选择直接准备合同，由独立主管授权。'});
  await contract(manager,id,'preparation-decisions',{decision:'APPROVED',reason:'合成主管核对主体与服务范围，批准准备，不代表签约审批。'});
  const started=await contract(actor,id,'start',{});assert.ok(started.contract?.selector?.id);
  state.cases.push({index,code,actor,manager,opportunityId:id,contractId:started.contract.selector.id,stage:started.stage});save('performance-background.json',state);
  console.log('Independent real-command background '+state.cases.length+'/100');
 }
}catch(e){save('performance-background-failure.json',{message:e.message,completed:state.cases.length,pending:state.pending});throw e;}finally{await browser.close();}
