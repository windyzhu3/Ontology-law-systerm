import {chromium} from '@playwright/test';import crypto from 'node:crypto';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {login,save,runtime,origin} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Explicit isolated performance fixtures required');
const fixtures=JSON.parse(fs.readFileSync(path.join(runtime,'performance-background.json'),'utf8')).cases;
const one=fixtures.find(c=>c.actor==='sales01'),two=fixtures.find(c=>c.actor==='sales04');assert.ok(one&&two);
const browser=await chromium.launch({channel:'chrome',headless:true}),sessions={},results=[];
async function call(actor,endpoint,method='GET',body){const s=sessions[actor];return s.page.evaluate(async({url,headers,method,body})=>{const r=await fetch(url,{headers,method,cache:'no-store',body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json()};},{url:origin+endpoint,headers:{...s.apiHeaders(),...(body?{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()}:{})},method,body});}
try{
 for(const actor of ['sales01','sales02','sales_manager01','sales_manager02','finance01','case_admin01','sys_manager01'])sessions[actor]=await login(browser,actor);
 const basis=(await call('sales01','/api/v1/opportunities/'+one.opportunityId+'/contracts')).data;
 const body={expectedOpportunityRevision:basis.opportunity.revision,responsibilityBasis:basis.responsibilityBasis,customerConfirmation:basis.customerConfirmation,expectedContract:basis.contract.selector,expectedDraft:basis.draft?.selector??null,expectedVersion:basis.contract.currentRevision,expectedWorkflow:basis.workflow?.selector??null,values:{decision:'APPROVED',reason:'纯合成权限负向请求，不应创建决定。',expectedTermination:null}};
 for(const [actor,id,suffix] of [['sales01',one.opportunityId,'preparation-decisions'],['sales02',one.opportunityId,'preparation-requests'],['sales_manager02',one.opportunityId,'preparation-decisions'],['sales_manager01',two.opportunityId,'preparation-decisions'],['finance01',one.opportunityId,'decisions'],['case_admin01',one.opportunityId,'decisions'],['sys_manager01',one.opportunityId,'preparation-requests']]){
  const r=await call(actor,'/api/v1/opportunities/'+id+'/contracts/'+suffix,'POST',body);assert.equal(r.status,403);assert.equal(r.data.code,'NOT_AUTHORIZED');
  assert.ok(!JSON.stringify(r.data).includes('HH-PERF'));results.push({actor,opportunityId:id,command:suffix,status:r.status,code:r.data.code});save('permission-write-results.json',results);
 }
 const after=(await call('sales01','/api/v1/opportunities/'+one.opportunityId+'/contracts')).data;
 assert.deepEqual(after.contract,basis.contract);assert.deepEqual(after.workflow,basis.workflow);assert.deepEqual(after.customerConfirmation,basis.customerConfirmation);
 save('permission-write-boundary.json',{negativeCommands:results.length,contractUnchanged:true,workflowUnchanged:true,confirmationUnchanged:true});console.log('7 actual wrong-role/peer-owner/cross-department/admin business command denials PASS; target facts unchanged');
}finally{await browser.close();}
