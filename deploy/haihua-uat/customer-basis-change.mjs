import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {login,save,runtime} from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const code='HH-B10-20261001-R2',file='B13-confirmation-original.json';
if(!runtime.endsWith('haihua-uat-runtime')||fs.existsSync(path.join(runtime,file)))throw Error('Approved single basis change only; investigate recorded command');
const b=await chromium.launch({channel:'chrome',headless:true});let page;
try{const a=await login(b,'sales04');page=a.page;await openOpportunity(page,code);
 await page.getByText('客户与需求资料',{exact:true}).click();await page.getByRole('button',{name:'维护客户与需求',exact:true}).click();await page.getByRole('heading',{name:'核对客户与需求',exact:true}).waitFor();
 await page.getByLabel(/^客户目标/).fill('合成B13：客户调整目标，要求先核实争议事实与证据，再评估解决路径；原报价不得推定覆盖新确认。');
 await page.getByRole('button',{name:'核对本次资料',exact:true}).click();
 page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname.includes('/customer-requirements/'))save(file,{phase:'REQUESTED',commandId:r.headers()['idempotency-key'],path:new URL(r.url()).pathname,body:r.postDataJSON()});});
 const result=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.includes('/customer-requirements/'));await page.getByRole('button',{name:'确认客户与需求',exact:true}).click();const r=await result,receipt=await r.json();save(file,{status:r.status(),receipt,commandId:r.request().headers()['idempotency-key']});assert.equal(r.status(),200);assert.equal(receipt.outcome,'SUCCEEDED');await page.getByRole('heading',{name:'客户与需求已确认',exact:true}).waitFor();
 const oid=JSON.parse(fs.readFileSync(path.join(runtime,'quote-reply-independent-verification.json'),'utf8')).find(x=>x.code===code).context.opportunity.id;
 const response=await page.request.get('https://localhost:20545/api/v1/opportunities/'+oid+'/quotes',{headers:a.apiHeaders()}),current=await response.json();save('B13-quote-after-confirmation.json',{status:response.status(),context:current});assert.equal(response.status(),200);assert.ok(!current.allowedActions.includes('RECORD_QUOTE_RESPONSE'));assert.ok(!current.allowedActions.includes('RECORD_QUOTE_DELIVERY'));assert.ok(current.allowedActions.includes('FORM_QUOTE'));
 save('B13-basis-change-PASS.json',{status:'PASS',scope:'Actual revised confirmation; original quote preserved, old basis response/delivery removed, revision required',allowedActions:current.allowedActions});console.log('Actual revised customer basis requires quote revision, old response/delivery blocked PASS');
}catch(e){if(page)save('B13-basis-change-failure.txt',await page.locator('body').innerText());throw e;}finally{await b.close();}
