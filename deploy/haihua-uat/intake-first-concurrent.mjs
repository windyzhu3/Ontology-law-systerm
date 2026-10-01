import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {login,save,runtime} from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const code='HH-B24-20261001-R2',tag='B24-first-intake-concurrent';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
if(fs.existsSync(path.join(runtime,tag+'-original.json')))throw Error('Original concurrent commands recorded; recover them, never dispatch again');
const browser=await chromium.launch({channel:'chrome',headless:true});
let routed,resolve,reject,posts=0;
const completed=new Promise((yes,no)=>{resolve=yes;reject=no;});
try{
 const actor=await login(browser,'case_admin01'),page=actor.page;
 await selectTask(page,code,'接收');await page.getByRole('heading',{name:'核对准确版本和材料完整性',exact:true}).waitFor();
 await page.locator('input[name=checked]').check();await page.locator('textarea[name=explanation]').fill('纯合成首次并发接收：同一准确已审查版本、完整主体及签署材料，只允许接收一次并产生一个案件。');
 await page.route('**/transfers/intake-decisions',async route=>{
  if(route.request().method()!=='POST'){await route.continue();return;}
  posts++;assert.equal(posts,1);routed=route;
  const commandId=route.request().headers()['idempotency-key'],secondKey=randomUUID(),requestBody=route.request().postDataJSON(),endpoint=new URL(route.request().url()).pathname,startedAt=new Date().toISOString();
  save(tag+'-original.json',{phase:'REQUESTED',commandId,secondKey,requestBody,endpoint,startedAt});
  try{
   const [first,second]=await Promise.all([route.fetch({timeout:120000}),page.request.post(route.request().url(),{headers:{...actor.apiHeaders(),'Idempotency-Key':secondKey},data:requestBody,timeout:120000})]);
   const results=[{commandId,status:first.status(),body:await first.json()},{commandId:secondKey,status:second.status(),body:await second.json()}];
   save(tag+'-results.json',{startedAt,finishedAt:new Date().toISOString(),results});
   await route.fulfill({response:first});routed=null;resolve(results);
  }catch(error){await route.abort('failed');routed=null;reject(Error(String(error.message).split('\n')[0]));}
 });
 await page.getByRole('button',{name:'记录接收结果',exact:true}).click();const results=await completed;
 const winners=results.filter(x=>x.status===200&&x.body.outcome==='SUCCEEDED');assert.equal(winners.length,1);
 const loser=results.find(x=>x!==winners[0]);assert.ok([403,409,412].includes(loser.status));assert.ok(['NOT_AUTHORIZED','STALE_SUBJECT','STALE_REVIEW','STALE_TRANSFER_REVIEW_BASIS'].includes(loser.body.code));
 const recovered=await page.request.get('https://localhost:20545/api/v1/commands/'+winners[0].commandId+'/receipt',{headers:actor.apiHeaders()});assert.equal(recovered.status(),200);const receipt=await recovered.json();assert.equal(receipt.commandId,winners[0].commandId);assert.equal(receipt.outcome,'SUCCEEDED');
 save(tag+'-PASS.json',{status:'PASS',actualUiPostCount:posts,actualConcurrentCommands:2,results,originalWinnerReceipt:receipt,scope:'First intake two different keys and identical version/body dispatched concurrently; independent one-intake/one-Matter check required'});
 console.log('Actual first intake two-command concurrency PASS; one winner and closed stale/permission loser');
}finally{if(routed)await routed.abort('failed');await browser.close();}
