import {chromium} from '@playwright/test';
import {login,save} from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const code='HH-B01-20261001';const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,'sales_manager01'));await selectTask(page,code,'补全');await page.getByRole('heading',{name:'请补全客户联系方式',exact:true}).waitFor();
 let writes=0;page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/complete-lead-ingress'))writes++;});
 await page.getByRole('button',{name:'补全联系方式',exact:true}).click();await page.waitForTimeout(300);
 if(writes!==0)throw Error('missing contact must not submit');save(code+'-missing-required-blocked.txt',await page.locator('body').innerText());
 await page.locator('input[name=phone]').fill('+8613900030001');await page.locator('select[name=sourceCode]').selectOption('CUSTOMER_PROVIDED');await page.locator('textarea[name=sourceSummary]').fill('合成客户主动补充联系方式，主管独立核对来源。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/complete-lead-ingress'));await page.getByRole('button',{name:'补全联系方式',exact:true}).click();
 const r=await response,receipt=await r.json();save(code+'-complete-receipt.json',{actor:'sales_manager01',status:r.status(),receipt});if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('completion rejected');await page.getByRole('heading',{name:'请补全客户联系方式',exact:true}).waitFor({state:'hidden'});console.log(code+' missing-required blocked and actual supervisor completion PASS');
}catch(e){if(page)save(code+'-complete-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
