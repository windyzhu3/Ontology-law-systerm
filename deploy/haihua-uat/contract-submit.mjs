import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {selectTask} from './business-browser.mjs';
const [sales,code,step]=process.argv.slice(2);const labels={review:'提交签约前审查',approval:'提交合同审批'};const suffix={review:'/contracts/review-requests',approval:'/contracts/approval-requests'};if(!labels[step])throw Error('explicit review/approval step required');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,sales));page.setDefaultTimeout(60000);await selectTask(page,code,labels[step]);const action=step==='approval'?'提交这份合同审批':labels[step];await page.getByRole('button',{name:action,exact:true}).waitFor();
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith(suffix[step]));await page.getByRole('button',{name:action,exact:true}).click();const r=await response,receipt=await r.json();save(code+'-contract-'+step+'-request.json',{actor:sales,status:r.status(),receipt});if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('request rejected');
 await page.getByText('正在核对提交结果…',{exact:true}).waitFor({state:'hidden'});await page.screenshot({path:path.join(evidence,code+'-contract-'+step+'-requested.png'),fullPage:true});console.log(code+' contract '+step+' request UI PASS');
}catch(e){if(page)save(code+'-contract-submit-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
