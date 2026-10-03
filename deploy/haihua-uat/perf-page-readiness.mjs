import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Separate performance instance required');
const background=JSON.parse(fs.readFileSync(path.join(runtime,'performance-background.json'),'utf8'));assert.equal(background.cases.length,100);
if(fs.existsSync(path.join(runtime,'performance-page-readiness.json')))throw Error('Preserve prior measurements');
const results=[],browser=await chromium.launch({channel:'chrome',headless:true});
try{const actors=[];for(let i=1;i<=5;i++)actors.push({...await login(browser,'sales0'+i),username:'sales0'+i});
 await Promise.all(actors.map(async({page,username})=>{
  const cases=background.cases.filter(c=>c.actor===username).slice(0,2);
  for(let round=0;round<20;round++){
   const menu=page.locator('summary').filter({hasText:'我的待办'});await menu.waitFor();
   if(!await menu.evaluate(e=>e.parentElement.open))await menu.click();
   await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
   let selected=cases[round%2];let target=page.getByRole('button',{name:new RegExp(selected.code+' .*准备委托合同')});await target.waitFor();
   if(await target.getAttribute('aria-current')==='true'){selected=cases[(round+1)%2];target=page.getByRole('button',{name:new RegExp(selected.code+' .*准备委托合同')});await target.waitFor();}
   const started=performance.now();await target.click();
   await page.locator('.work-card .subject').filter({hasText:selected.code}).waitFor();
   const form=page.getByRole('button',{name:'生成合同正文',exact:true});await form.waitFor();assert.ok(await form.isEnabled());
   results.push({actor:username,kind:'card-click-to-enabled-form',round,ms:performance.now()-started});save('performance-page-readiness.json',results);
  }
  await page.getByRole('button',{name:'业务管理',exact:true}).click();
  const nav=page.locator('aside.business-navigation');await nav.waitFor();
  for(let round=0;round<20;round++){
   if(round>0)await nav.getByRole('button',{name:'经营概览',exact:true}).click();
   const started=performance.now();await nav.getByRole('button',{name:'合同台账',exact:true}).click();
   await page.getByText('正在读取合同…',{exact:true}).waitFor({state:'hidden'});
   await page.getByRole('button',{name:'重新查询',exact:true}).waitFor();
   await page.waitForFunction(()=>document.querySelectorAll('.list-pane .record-table tbody tr').length===20);
   assert.ok(await page.getByRole('button',{name:'重新查询',exact:true}).isEnabled());
   results.push({actor:username,kind:'ledger-navigation-to-20-rows-operable',round,ms:performance.now()-started,rows:20});save('performance-page-readiness.json',results);
  }
 }));console.log('5 concurrent browsers ×20 card selections and20 actual 20-row ledger readiness samples recorded');
}catch(e){save('performance-page-readiness-failure.json',{message:e.message,samples:results.length});throw e;}finally{await browser.close();}
