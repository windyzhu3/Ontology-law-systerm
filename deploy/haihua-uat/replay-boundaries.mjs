import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {login,save,runtime,origin} from './browser.mjs';
if(!runtime.endsWith('haihua-uat-runtime'))throw Error('Original business UAT required');const kind=process.argv[2];if(!['finance','intake'].includes(kind))throw Error('Explicit recorded recovery required');
const source=JSON.parse(fs.readFileSync(path.join(runtime,kind+'-unknown-original.json'),'utf8'));
let recoveryScope='Actual unknown UI and original receipt query';
if(kind==='intake'&&!fs.existsSync(path.join(runtime,'intake-unknown-PASS.json'))){
 const verified=JSON.parse(fs.readFileSync(path.join(runtime,'P4-intake-original-verified.json'),'utf8'));assert.equal(verified.length,1);assert.equal(verified[0].commandId,source.commandId);assert.equal(verified[0].status,200);source.receipt=verified[0].receipt;recoveryScope='Actual committed intake and independently queried original receipt; unknown UI remains unverified';
}else assert.ok(fs.existsSync(path.join(runtime,kind+'-unknown-PASS.json')));
assert.equal(source.receipt.outcome,'SUCCEEDED');save(kind+'-replay-prerequisite-scope.json',{recoveryScope,commandId:source.commandId,originalReceiptVerified:true});
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{const actor=await login(browser,kind==='finance'?'finance01':'case_admin01');
 async function request(endpoint,body){return actor.page.evaluate(async({url,body,headers})=>{const r=await fetch(url,{method:body===undefined?'GET':'POST',headers,cache:'no-store',body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json()};},{url:origin+endpoint,body,headers:{...actor.apiHeaders(),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':source.commandId})}});}
 const context='/api/v1/opportunities/'+source.endpoint.split('/')[4]+'/contracts',before=await request(context);assert.equal(before.status,200);
 const same=await request(source.endpoint,source.requestBody);results.push({kind:'same-key-same-body',...same});save(kind+'-replay-boundaries.json',results);assert.equal(same.status,200);assert.equal(same.data.receiptId,source.receipt.receiptId);assert.deepEqual(same.data.resultFact,source.receipt.resultFact);
 const different=structuredClone(source.requestBody);different.values.explanation+=' 同键不同正文负向测试';const conflict=await request(source.endpoint,different);results.push({kind:'same-key-different-body',...conflict});save(kind+'-replay-boundaries.json',results);assert.equal(conflict.status,409);assert.equal(conflict.data.code,'COMMAND_PAYLOAD_CONFLICT');assert.ok(!JSON.stringify(conflict.data).includes(source.receipt.resultFact.factRef));
 const after=await request(context);assert.equal(after.status,200);for(const key of ['contract','payments','transfer'])assert.deepEqual(after.data[key],before.data[key]);save(kind+'-replay-business-before-after.json',{before:before.data,after:after.data,status:'PASS'});console.log(kind+' same receipt replay, different payload conflict, unchanged authorized business facts PASS');
}finally{await browser.close();}
