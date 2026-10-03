import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {selectTask} from './business-browser.mjs';
const [sales,code]=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
try{({page}=await login(browser,sales));await selectTask(page,code,'推进');
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/contracts/')){writes.push({path:new URL(r.url()).pathname,status:r.status(),receipt:await r.json()});save(code+'-direct-request-receipts.json',writes);}});
 await page.getByText('其他业务处理',{exact:true}).click();await page.getByRole('button',{name:'推进或结束本次商机',exact:true}).click();
 await page.getByRole('button',{name:'申请直接准备合同',exact:true}).click();await page.getByRole('button',{name:'提交直接准备申请',exact:true}).waitFor();
 await page.getByLabel(/^服务范围/).fill('合同纠纷法律咨询及人工明确约定范围的代理，合成验收不用于实际签约。');
 await page.getByLabel(/^收费项目 1/).fill('合成咨询服务费');await page.getByLabel(/^金额（元）1/).fill('10000.00');
 await page.getByLabel(/^付款安排/).fill((code.includes('G04')||code==='HH-B19-20261001-R2')?'合同生效后先支付10000元，确认到账达到约定额后方可转案。':'合同生效后按约定支付10000元，本合同不要求到账后才转案。');
 await page.getByLabel(/^申请原因与沟通依据/).fill('合成客户选择直接准备合同，已确认准确主体、需求和服务范围，请本部门主管明确授权。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/contracts/preparation-requests'));
 await page.getByRole('button',{name:'提交直接准备申请',exact:true}).click();const r=await response,receipt=await r.json();save(code+'-direct-request-receipt.json',{status:r.status(),receipt});if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('direct request rejected '+r.status());await page.getByText('正在核对提交结果…',{exact:true}).waitFor({state:'hidden'});
 await page.screenshot({path:path.join(evidence,code+'-direct-request.png'),fullPage:true});save(code+'-direct-request.txt',await page.locator('body').innerText());console.log(code+' direct preparation request UI submitted');
}catch(e){if(page)save(code+'-direct-request-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
