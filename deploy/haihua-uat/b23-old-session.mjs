import {chromium} from '@playwright/test';import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import {execFile} from 'node:child_process';import {promisify} from 'node:util';import {randomUUID} from 'node:crypto';
import {login,save,runtime} from './browser.mjs';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
if(fs.existsSync(path.join(runtime,'B23-old-session-original.json')))throw Error('Recorded negative command must not be repeated');
const run=promisify(execFile),b=await chromium.launch({channel:'chrome',headless:true});let actor,paused=false;
const code='HH-B24-20261001-R2';
async function admin(operation){await run(process.execPath,['deploy/haihua-uat/appointment-state.mjs','sales04',operation],{cwd:process.cwd(),env:process.env});}
try{
 actor=await login(b,'sales04');await actor.page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 // Resolve the exact object from the recorded successful UI request path.
 const records=JSON.parse(fs.readFileSync(path.join(runtime,code+'-direct-request-receipts.json'),'utf8'));
 const record=records.find(v=>v.path.endsWith('/preparation-requests')&&v.receipt.outcome==='SUCCEEDED');
 assert.ok(record);const oid=record.path.split('/')[4];
 assert.ok(oid);const endpoint='https://localhost:20545/api/v1/opportunities/'+oid+'/contracts';
 const beforeR=await actor.page.request.get(endpoint,{headers:actor.apiHeaders()}),before=await beforeR.json();assert.equal(beforeR.status(),200);assert.ok(before.allowedActions.includes('START_CONTRACT_PREPARATION'));
 const body={expectedOpportunityRevision:before.opportunity.revision,responsibilityBasis:before.responsibilityBasis,customerConfirmation:before.customerConfirmation,expectedContract:before.contract?.selector??null,expectedDraft:before.draft?.selector??null,expectedVersion:before.contract?.currentRevision??null,expectedWorkflow:before.workflow?.selector??null,values:{expectedTermination:before.termination?.selector??null}};
 await admin('suspend');paused=true;
 const staleRead=await actor.page.request.get(endpoint,{headers:actor.apiHeaders()});assert.ok([401,403].includes(staleRead.status()));
 const commandId=randomUUID();save('B23-old-session-original.json',{commandId,endpoint:endpoint+'/start',body,phase:'REQUESTED'});
 const denied=await actor.page.request.post(endpoint+'/start',{headers:{...actor.apiHeaders(),'Idempotency-Key':commandId},data:body});const rejection=await denied.json();save('B23-old-session-original.json',{commandId,status:denied.status(),rejection});assert.ok([401,403].includes(denied.status()));
 await actor.page.getByRole('button',{name:'刷新当前责任',exact:true}).click();await actor.page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 const ui=await actor.page.locator('body').innerText();save('B23-old-session-revoked-UI.txt',ui);assert.equal(await actor.page.locator('.current-card .subject-title:visible,.work-card .subject:visible').filter({hasText:code}).count(),0);
 await admin('resume');paused=false;
 const restored=await login(b,'sales04'),afterR=await restored.page.request.get(endpoint,{headers:restored.apiHeaders()}),after=await afterR.json();assert.equal(afterR.status(),200);assert.deepEqual(after.workflow.selector,before.workflow.selector);assert.deepEqual(after.contract,before.contract);assert.deepEqual(after.customerConfirmation,before.customerConfirmation);
 save('B23-old-session-PASS.json',{status:'PASS',scope:'Actual sys UI pause/resume, existing session read/write denied, old subject cleared on refresh; resumed exact preparation unchanged. Other save/confirm/pagination phases remain separate.',staleRead:staleRead.status(),staleWrite:denied.status(),restored:afterR.status()});console.log('Actual paused old session read/write denial and UI clearing PASS; appointment restored');
}finally{if(paused)await admin('resume');await b.close();}
