import {guardedRouteContinue,guardedRouteFetch} from './browser.mjs';
import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const code='HH-G01-20261001';
if(fs.existsSync(path.join(runtime,'contract-unknown-original.json')))throw Error('Original commit preserved; do not replay');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;let posts=0;
try{
 ({page}=await login(browser,'case_admin01'));await selectTask(page,code,'审查');
 await page.getByRole('heading',{name:'核对准确依据并作出决定',exact:true}).waitFor();
 await page.getByLabel(/^本次决定/).selectOption('NEED_INFO');
 await page.getByLabel(/^决定说明/).fill('纯合成负面分支：准确对方主体尚未核实，审查范围不完整。要求补齐独立主体与材料后重新审查，不记录通过。');
 await page.route('**/contracts/review-decisions',async route=>{
  if(route.request().method()!=='POST'){await guardedRouteContinue(route);return;}
  posts++;assert.equal(posts,1);
  let result;try{result=await guardedRouteFetch(route);}catch{throw Error('Trusted contract request failed before response loss');}
  const receipt=await result.json();
  save('contract-unknown-original.json',{commandId:route.request().headers()['idempotency-key'],status:result.status(),receipt});
  assert.equal(receipt.outcome,'SUCCEEDED');await route.abort('failed');
 });
 await page.getByRole('button',{name:'确认本次决定',exact:true}).click();
 await page.getByRole('button',{name:'核对原结果',exact:true}).waitFor();
 save('contract-unknown-state.txt',await page.locator('body').innerText());
 await page.getByRole('button',{name:'核对原结果',exact:true}).click();
 await page.getByText('原提交结果已确认。',{exact:true}).waitFor();
 assert.equal(posts,1);
 save('contract-unknown-PASS.json',{originalOutcome:'SUCCEEDED',actualPostCount:posts,recoveryReplayed:false});
 console.log('Contract actual committed-response loss → unknown UI → original receipt recovery without replay PASS');
}catch(e){if(page)save('contract-unknown-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
