import {chromium} from '@playwright/test';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {login,save,runtime} from './browser.mjs';import {selectTask} from './business-browser.mjs';
const decision=process.argv[2];if(!['REOPEN_CONTACT','CONFIRM_INVALID'].includes(decision))throw Error('Approved B07 decision required');
const code='HH-B07-20261001',file=code+'-validity-'+decision+'.json';if(fs.existsSync(path.join(runtime,file)))throw Error('Preserve original validity result');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,'sales_manager01'));await selectTask(page,code,'有效性');
 await page.locator('select[name=decisionCode]').selectOption(decision);await page.locator('textarea[name=rationaleSummary]').fill(decision==='REOPEN_CONTACT'?'合成主管复核：现有联系记录不足以判断无效，重新安排销售继续联系，保留原联系事实。':'合成主管复核：第二次联系已独立核实为错误联系方式，确认本条无效，保留两次联系历史。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/review-lead-validity'));
 await page.getByRole('button',{name:'复核线索有效性',exact:true}).click();const r=await response,receipt=await r.json();save(file,{actor:'sales_manager01',status:r.status(),receipt});assert.equal(r.status(),200);assert.equal(receipt.outcome,'SUCCEEDED');console.log('B07 supervisor '+decision+' actual UI PASS');
}catch(e){if(page)save(file+'.failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
