import { chromium } from '@playwright/test';
import fs from 'node:fs';import path from 'node:path';
import { login,evidence,save,runtime } from './browser.mjs';
const [sales,code,result='CONNECTED_VALID']=process.argv.slice(2);
if(!['CONNECTED_VALID','NOT_CONNECTED','SUSPECT_INVALID'].includes(result))throw Error('explicit contact result required');
const map=JSON.parse(fs.readFileSync(path.join(runtime,'account-map.json'),'utf8'));
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 ({page}=await login(browser,sales));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 if(!await page.locator('.current-card .subject-title').filter({hasText:code}).count()){
  await page.locator('summary').filter({hasText:'我的待办'}).click();await page.getByRole('button',{name:new RegExp(code+' .*联系')}).click();
 }
 await page.getByRole('heading',{name:'记录联系结果',exact:true}).waitFor();
 await page.locator('select[name=contactChannelCode]').selectOption('PHONE');
 await page.locator('select[name=resultCode]').selectOption(result);
 await page.locator('textarea[name=resultSummary]').fill(result==='CONNECTED_VALID'?'纯合成联系测试：确认具备法律服务需求，愿意进一步核对准确主体及委托范围。':result==='NOT_CONNECTED'?'合成测试：本次电话未接通，如实记录并按固定下一工作日安排重试。':'合成测试：本次联系信息疑似无效，交主管独立决定是否继续。');
 if(result==='CONNECTED_VALID')await page.locator('textarea[name=legalNeed]').fill('合同纠纷法律咨询及约定范围代理；测试样本不用于实际业务。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/record-contact-result'));
 await page.getByRole('button',{name:'记录联系结果',exact:true}).click();
 const r=await response,receipt=await r.json();save(code+'-contact-receipt.json',{actor:sales,status:r.status(),receipt});
 if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('contact rejected '+r.status());
 await page.locator('.current-card .subject-title').filter({hasText:code}).waitFor({state:'hidden'});
 await page.screenshot({path:path.join(evidence,code+'-contact.png'),fullPage:true});console.log(code+' actual sales contact PASS');
}catch(e){if(page)save(code+'-contact-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
