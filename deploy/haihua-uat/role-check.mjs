import { chromium } from '@playwright/test';
import path from 'node:path';
import { login, evidence, save } from './browser.mjs';
const users=['sales01','sales02','sales03','sales04','sales05','sales_manager01','sales_manager02','finance01','case_admin01','sys_manager01'];
const browser=await chromium.launch({channel:'chrome',headless:true});
const results=[];
try {
 for(const username of users){
  let admitted;
  const originalNew=browser.newContext.bind(browser);
  // Observe actual session responses; tokens remain in browser memory only.
  browser.newContext=async (...args)=>{const ctx=await originalNew(...args);ctx.on('response',async r=>{if(new URL(r.url()).pathname==='/api/v1/session/context'&&r.status()===200){const v=await r.json();if(v.state==='READY')admitted=v;}});return ctx;};
  const {context,page,appointment}=await login(browser,username);
  browser.newContext=originalNew;
  await page.locator('main:visible').first().waitFor();
  await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
  if(await page.getByRole('alert').count())throw Error(username+' entry returned alert: '+await page.getByRole('alert').allTextContents());
  await page.screenshot({path:path.join(evidence,'role-'+username+'.png'),fullPage:true});
  const body=await page.locator('body').innerText();
  if(!body.includes(username))throw Error(username+' display mismatch');
  if(!admitted)throw Error(username+' missing READY response');
  if(username==='sys_manager01'&&admitted.canEnterWorkbench)throw Error('system admin unexpectedly has business entry');
  if(username.startsWith('sales')&&!username.startsWith('sales_manager')&&admitted.canReadTeamTasks)throw Error('sales unexpectedly has team read');
  results.push({username,appointment:appointment.label,session:admitted,status:'PASS',buttons:await page.getByRole('button').allTextContents()});
  save('role-check-results.json',results);
  await context.close();console.log(username+' actual login/entry PASS');
 }
} finally {await browser.close();}
