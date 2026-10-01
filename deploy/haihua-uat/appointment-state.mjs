import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
const [username,operation]=process.argv.slice(2);
if(!runtime.endsWith('haihua-uat-runtime')||!['sales02','sales03','sales04','sales_manager02'].includes(username)||!['suspend','resume'].includes(operation))throw Error('Approved B23/B24/B25 original-environment action required');
if(!fs.existsSync(path.join(runtime,'checkpoints/P3_ADMIN_BEFORE/checkpoint-manifest.json')))throw Error('Create checkpoint before temporary identity changes');
const file=username+'-'+operation+'-appointment-ui.json';if(fs.existsSync(path.join(runtime,file)))throw Error('Recorded command must be investigated, not replayed');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 ({page}=await login(browser,'sys_manager01'));await page.getByRole('link',{name:'任职管理',exact:true}).click();await page.getByRole('button',{name:username,exact:true}).click();await page.getByRole('heading',{name:username+'的任职',exact:true}).waitFor();
 const action=operation==='suspend'?'暂停任职':'恢复任职',verb=operation==='suspend'?'暂停':'恢复';await page.getByRole('button',{name:action,exact:true}).click();await page.getByRole('dialog',{name:action,exact:true}).waitFor();await page.getByLabel('操作原因',{exact:true}).selectOption('ADMINISTRATIVE_ACTION');
 page.on('request',request=>{if(request.method()==='POST'&&new URL(request.url()).pathname.endsWith('/'+operation))save(file,{actor:'sys_manager01',username,operation,commandId:request.headers()['idempotency-key'],phase:'REQUESTED'});});
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/'+operation));await page.getByRole('button',{name:'确认'+verb,exact:true}).click();const r=await response,receipt=await r.json();save(file,{actor:'sys_manager01',username,operation,status:r.status(),commandId:r.request().headers()['idempotency-key'],receipt});assert.equal(r.status(),200);assert.equal(receipt.outcome,'SUCCEEDED');await page.getByText(/结果已记录，当前页已重新读取/).waitFor();console.log(username+' '+operation+' appointment via actual admin UI PASS');
}catch(e){if(page)save(username+'-'+operation+'-appointment-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
