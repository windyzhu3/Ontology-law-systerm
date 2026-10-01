import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
import {openOpportunity} from './business-browser.mjs';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
const tag='B14-harmless-antivirus-test',code='HH-B23-20261001-R2',oid='01a0f6dd-ca59-782f-9077-6e2cc509e8c7';
if(fs.existsSync(path.join(runtime,tag+'-original.json')))throw Error('Original antivirus test upload recorded; do not repeat');
// Same non-executable standard test signature as ClamdLiveProbeTest; no fixture file written.
const pattern=Buffer.from(['X5O!P%@AP[4','\\PZX54(P^)7CC)7}$','EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'].join(''),'ascii');
const bytes=Buffer.concat([fs.readFileSync(path.join(runtime,'fixtures',code+'-SYNTHETIC-client-identity.pdf')),Buffer.from('\n'),pattern]);
const fileName=code+'-harmless-antivirus-test.pdf',base='https://localhost:20545/api/v1/opportunities/'+oid+'/materials';
const browser=await chromium.launch({channel:'chrome',headless:true});
try{
 const a=await login(browser,'sales01'),page=a.page,before=await a.page.request.get(base,{headers:a.apiHeaders()});assert.equal(before.status(),200);const previous=await before.json();assert.equal(previous.versions.length,2);
 await openOpportunity(page,code);await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();
 const requests=[],responses=[];
 page.on('request',r=>{const endpoint=new URL(r.url()).pathname;if(['POST','PUT'].includes(r.method())&&endpoint.includes('/material')){requests.push({method:r.method(),endpoint,commandId:r.headers()['idempotency-key']??null});save(tag+'-original.json',{phase:'REQUESTED',fileName,requests});}});
 page.on('response',async r=>{const endpoint=new URL(r.url()).pathname;if(endpoint.includes('/material')&&(r.headers()['content-type']??'').includes('json')){responses.push({method:r.request().method(),endpoint,status:r.status(),body:await r.json()});save(tag+'-responses.json',responses);}});
 await page.getByRole('button',{name:'接收本次材料',exact:true}).click();await page.getByLabel(/^材料用途/).selectOption('CONTRACT_BUSINESS');await page.getByLabel('选择文件',{exact:true}).setInputFiles({name:fileName,mimeType:'application/pdf',buffer:bytes});await page.getByRole('button',{name:'上传并核对',exact:true}).click();
 for(let attempt=0;attempt<30;attempt++){
  if(await page.getByRole('heading',{name:'本次文件未能接收',exact:true}).isVisible())break;
  if(await page.getByRole('button',{name:'确认接收材料',exact:true}).isVisible())throw Error('Antivirus test pattern unexpectedly passed; no acceptance attempted');
  const query=page.getByRole('button',{name:'查看检查结果',exact:true});if(await query.isVisible())await query.click();await page.waitForTimeout(1000);
 }
 await page.getByRole('heading',{name:'本次文件未能接收',exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'确认接收材料',exact:true}).count(),0);assert.equal(requests.some(x=>x.endpoint.endsWith('/materials/accept')),false);
 const result=await page.request.get(base,{headers:a.apiHeaders()});assert.equal(result.status(),200);const current=await result.json();save(tag+'-context.json',current);assert.equal(current.versions.length,2);
 assert.deepEqual(current.versions.map(v=>v.selector.id).sort(),previous.versions.map(v=>v.selector.id).sort());
 assert.ok(responses.some(x=>x.body.state==='REJECTED'));
 save(tag+'-PASS.json',{status:'PASS',receivedVersionsBefore:2,receivedVersionsAfter:2,acceptanceCommands:0,fileName,scope:'Actual normal UI/owned live scanner rejects harmless non-executable antivirus test pattern; original two material versions retained'});
 console.log('Actual harmless antivirus test rejected by live scanner UI; original versions retained PASS');
}finally{await browser.close();}
