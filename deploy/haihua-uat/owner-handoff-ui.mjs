import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
const scenario=process.argv[2]??'legacy';
if(!['legacy','active'].includes(scenario))throw Error('Approved scenario required');
const code=scenario==='active'?'HH-B23-20261001-R2':'HH-G02-20261001',file=scenario==='active'?'B25-active-owner-handoff-original.json':'B25-owner-handoff-original.json';
if(!runtime.endsWith('haihua-uat-runtime')||fs.existsSync(path.join(runtime,file)))throw Error('Approved original environment; never replay recorded handoff');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 const a=await login(browser,'sales_manager01');page=a.page;
 await page.getByRole('button',{name:'业务管理',exact:true}).click();await page.locator('aside.business-navigation').getByRole('button',{name:'团队待办',exact:true}).click();await page.getByLabel('查看内容',{exact:true}).selectOption('exceptions');
 await page.getByLabel('搜索客户或责任人',{exact:true}).fill(code);
 await page.getByRole('button',{name:code+' 合成客户',exact:true}).click();
 const detail=page.getByRole('region',{name:'当前事项详情',exact:true});await detail.getByRole('button').last().click();
 await page.getByRole('button',{name:'安排接手人',exact:true}).click();const target=page.locator('main select');await target.waitFor();
 const options=await target.locator('option').evaluateAll(es=>es.map(e=>({value:e.value,label:e.textContent})));
 assert.ok(!options.some(o=>o.label.includes('sales04')||o.label.includes('sales05')));
 const receiver=options.find(o=>o.label.startsWith(scenario==='active'?'sales01':'sales03'));assert.ok(receiver);save('B25-'+scenario+'-owner-candidates.json',options);
 await target.selectOption(receiver.value);await page.getByLabel('处置说明',{exact:true}).fill('合成验收：由同部门当前合格销售承接暂停任职人员的未完责任，保留原期限和历史。');
 await page.getByRole('button',{name:'核对本次交接',exact:true}).click();save('B25-owner-handoff-confirm.txt',await page.locator('body').innerText());
 page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/transfer'))save(file,{phase:'REQUESTED',commandId:r.headers()['idempotency-key'],body:r.postDataJSON()});});
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/transfer'));await page.getByRole('button',{name:'确认交接',exact:true}).click();const r=await response,receipt=await r.json();save(file,{status:r.status(),commandId:r.request().headers()['idempotency-key'],body:r.request().postDataJSON(),receipt});assert.equal(r.status(),200);assert.equal(receipt.outcome,'SUCCEEDED');
 console.log('Actual same-department owner handoff original receipt PASS; deadline reconciliation separate');
}catch(e){if(page)save('B25-owner-handoff-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
