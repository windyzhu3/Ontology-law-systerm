import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const [sales,code]=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
try{({page}=await login(browser,sales));await openOpportunity(page,code);
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/quotes/')){const data=await r.json();writes.push({path:new URL(r.url()).pathname,status:r.status(),receipt:data});save(code+'-quote-prepare-receipts.json',writes);}});
 await page.getByText('收费方案与报价',{exact:true}).click();await page.getByRole('button',{name:'查看与办理报价',exact:true}).click();
 await page.getByText('正在读取本次报价…',{exact:true}).waitFor({state:'hidden'});if(await page.getByRole('button',{name:'开始报价准备',exact:true}).isVisible())await page.getByRole('button',{name:'开始报价准备',exact:true}).click();await page.getByRole('heading',{name:/^(准备|修订)本次收费方案$/}).waitFor();
 await page.getByLabel(/^拟服务范围/).fill('合同纠纷法律咨询及人工明确约定范围的代理，合成验收不用于实际签约。');
 await page.getByLabel(/^收费项目/).fill('合成咨询服务费');await page.getByLabel(/^本项金额/).fill('10000.00');
 await page.getByLabel(/^付款安排/).fill(code.includes('G02')?'合同生效后先支付10000元，确认到账达到约定额后方可转案。':'合同生效后按约定支付10000元，本合同不要求到账后才转案。');
 await page.getByLabel(/^有效期至/).fill('2026-10-31');
 await page.getByRole('button',{name:'形成这份报价版本',exact:true}).click();await page.getByRole('heading',{name:'核对本次报价版本',exact:true}).waitFor();
 await page.getByRole('button',{name:'确认形成报价版本',exact:true}).click();await page.getByRole('button',{name:'提交这份报价审批',exact:true}).waitFor();
 await page.getByRole('button',{name:'提交这份报价审批',exact:true}).click();await page.getByRole('heading',{name:'报价等待审批',exact:true}).waitFor();
 await page.screenshot({path:path.join(evidence,code+'-quote-await-approval.png'),fullPage:true});console.log(code+' quote formed/submitted via '+sales+' UI');
}catch(e){if(page)save(code+'-quote-prepare-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
