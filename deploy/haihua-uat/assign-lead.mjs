import { chromium } from '@playwright/test';
import fs from 'node:fs';import path from 'node:path';
import { login,evidence,save,runtime } from './browser.mjs';
const [manager,sales,code]=process.argv.slice(2);
const map=JSON.parse(fs.readFileSync(path.join(runtime,'account-map.json'),'utf8'));
const prior=path.join(runtime,code+'-assign-receipt.json');
if(fs.existsSync(prior)&&JSON.parse(fs.readFileSync(prior,'utf8')).receipt.outcome==='SUCCEEDED'){console.log(code+' original successful assignment preserved');process.exit(0);}
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 ({page}=await login(browser,manager));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 if(!await page.locator('.current-card .subject-title').filter({hasText:code}).count()){
  await page.locator('summary').filter({hasText:'我的待办'}).click();await page.getByRole('button',{name:new RegExp(code+' .*分配')}).click();
 }
 await page.getByRole('heading',{name:'分配联系负责人',exact:true}).waitFor();
 await page.locator('select[name=ownerAppointmentId]').selectOption(map.users[sales].appointmentId);
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/commands/assign-lead'));
 await page.getByRole('button',{name:'分配联系负责人',exact:true}).click();
 const r=await response,receipt=await r.json();save(code+'-assign-receipt.json',{actor:manager,target:sales,status:r.status(),receipt});
 if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('assignment rejected '+r.status());
 await page.locator('.current-card .subject-title').filter({hasText:code}).waitFor({state:'hidden'});
 await page.screenshot({path:path.join(evidence,code+'-assign.png'),fullPage:true});console.log(code+' actual manager assignment PASS');
}catch(e){if(page)save(code+'-assign-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
