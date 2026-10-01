import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const [manager,code]=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,manager));await selectTask(page,code,'');await page.getByRole('heading',{name:'核对直接准备授权',exact:true}).waitFor();
 if(!await page.locator('body').innerText().then(t=>t.includes(code)))throw Error('wrong direct request');
 await page.getByLabel(/^本次决定/).selectOption('APPROVED');await page.getByLabel(/^决定说明/).fill('合成主管核对：准确主体、服务范围、10000元收费及付款安排一致，明确批准准备本次范围合同，后续仍须独立审查和合同审批。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/contracts/preparation-decisions'));
 await page.getByRole('button',{name:'确认本次决定',exact:true}).click();const r=await response,receipt=await r.json();save(code+'-direct-decision-receipt.json',{actor:manager,status:r.status(),receipt});
 if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('direct authorization rejected');
 await page.getByRole('heading',{name:'核对直接准备授权',exact:true}).waitFor({state:'hidden'});await page.screenshot({path:path.join(evidence,code+'-direct-approved.png'),fullPage:true});console.log(code+' manager direct preparation authorized UI PASS');
}catch(e){if(page)save(code+'-direct-decision-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
