import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {login,save,runtime} from './browser.mjs';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
const x=JSON.parse(fs.readFileSync(path.join(runtime,'HH-G03-20261001-R2-payment-HH-B19-FIRST-4000-CONFIRM-receipt.json'),'utf8'));
assert.equal(x.receipt.code,'PAYMENT_ALREADY_RECORDED');const b=await chromium.launch({channel:'chrome',headless:true});
try{
 const a=await login(b,'finance01'),endpoint='https://localhost:20545/api/v1/opportunities/'+x.requestBody.responsibilityBasis.id+'/contracts/receipt-reviews',headers={...a.apiHeaders(),'Idempotency-Key':x.commandId};
 const responses=await Promise.all([a.page.request.post(endpoint,{headers,data:x.requestBody}),a.page.request.post(endpoint,{headers,data:x.requestBody})]);
 const responsesSeen=[];for(const r of responses){const value=await r.json();responsesSeen.push({status:r.status(),value});save('B18-duplicate-replay-observed.json',responsesSeen);assert.equal(r.status(),409);assert.equal(value.code,'PAYMENT_ALREADY_RECORDED');assert.equal(value.receiptRef.commandId,x.commandId);}
 const original=await a.page.request.get('https://localhost:20545/api/v1/commands/'+x.commandId+'/receipt',{headers:a.apiHeaders()}),receipt=await original.json();save('B18-duplicate-original-receipt-observed.json',{status:original.status(),receipt});assert.equal(original.status(),200);assert.equal(receipt.outcome,'REJECTED');assert.equal(receipt.rejectionCode,'PAYMENT_ALREADY_RECORDED');assert.equal(receipt.commandId,x.commandId);
 const changed=structuredClone(x.requestBody);changed.values.amountMinor++;
 const conflict=await a.page.request.post(endpoint,{headers,data:changed}),problem=await conflict.json();assert.equal(conflict.status(),409);assert.equal(problem.code,'COMMAND_PAYLOAD_CONFLICT');
 save('B18-duplicate-replay-PASS.json',{status:'PASS',scope:'Actual original rejected key concurrent command replays and original receipt query; not initial business concurrency',originalCommandId:x.commandId,receipt,replays:responsesSeen.map(v=>({status:v.status,commandId:v.value.receiptRef.commandId})),payloadConflict:problem.code});console.log('Actual duplicate original key concurrent replays and payload conflict PASS');
}finally{await b.close();}
