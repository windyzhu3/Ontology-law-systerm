import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const [manager,code,decision]=process.argv.slice(2);if(!['APPROVED','RETURNED'].includes(decision))throw Error('explicit decision required');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,manager));
 await page.getByText('正在读取有权报价事项…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取本次报价…',{exact:true}).waitFor({state:'hidden'});
 if(!await page.locator('.work-card .subject').filter({hasText:code}).count()){
  await openOpportunity(page,code);await page.getByText('收费方案与报价',{exact:true}).click();await page.getByRole('button',{name:'查看与办理报价',exact:true}).click();
 }
 await page.getByRole('heading',{name:'核对并作出报价决定',exact:true}).waitFor();await page.getByLabel(/^本次决定/).selectOption(decision);
 await page.getByLabel(/^决定说明/).fill(decision==='RETURNED'?'合成审批退回：请明确服务范围与付款安排后重新提交本版。':'合成主管审批：已核对准确客户需求、范围、10000元费用及付款条件，批准本版。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/quotes/decisions'));
 await page.getByRole('button',{name:'确认本次报价决定',exact:true}).click();const r=await response;const receipt=await r.json();save(code+'-quote-'+decision+'-receipt.json',{actor:manager,status:r.status(),receipt});
 if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('decision rejected '+r.status());
 await page.getByRole('heading',{name:'核对并作出报价决定',exact:true}).waitFor({state:'hidden'});await page.screenshot({path:path.join(evidence,code+'-quote-'+decision+'.png'),fullPage:true});console.log(code+' actual manager quote '+decision+' PASS');
}catch(e){if(page)save(code+'-quote-decision-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
