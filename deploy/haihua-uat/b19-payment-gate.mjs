import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';
import {login,save,runtime} from './browser.mjs';
const [mode]=process.argv.slice(2),code='HH-B19-20261001-R2';
assert.equal(mode,'gate4000');
assert.ok(runtime.endsWith('haihua-uat-runtime'));
const tag='B19-'+mode;
if(fs.existsSync(path.join(runtime,tag+'-PASS.json')))throw Error('Recorded successful step; never repeat');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 ({page}=await login(browser,'sales02'));
 await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 await page.getByRole('button',{name:'业务管理',exact:true}).click();
 await page.getByRole('button',{name:'合同台账',exact:true}).click();
 await page.getByRole('heading',{name:'合同台账',exact:true}).waitFor();
 await page.getByLabel('搜索客户',{exact:true}).fill(code);
 if(mode==='gate4000'){
  const contextResponse=page.waitForResponse(r=>r.request().method()==='GET'&&r.ok()&&new URL(r.url()).pathname.startsWith('/api/v1/opportunities/')&&new URL(r.url()).pathname.endsWith('/contracts'));
  await page.locator('.record-table button').filter({hasText:code}).click();
  const r=await contextResponse,c=await r.json();save(tag+'-context.json',c);
  assert.equal(c.execution.workflow.stage,'WAIT_RECEIPT');
  assert.equal(c.contract.document.paymentGate.requiredMinor,1000000);
  assert.equal(c.allowedActions.includes('VERIFY_CONTRACT_EXECUTION_CONDITIONS'),false);
  await page.locator('.detail-pane h2').filter({hasText:code}).waitFor();
  await page.getByRole('button',{name:'前往办理',exact:true}).click();
  await page.getByText('所选事项已不可处理，已返回当前可处理事项。请从我的待办重新选择。',{exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'确认执行条件成立',exact:true}).count(),0);
  assert.equal(await page.getByRole('button',{name:'提交案管接收',exact:true}).count(),0);
  const body=await page.locator('body').innerText();assert.ok(body.includes('等待 1 项'));
  save(tag+'-UI.txt',body);save(tag+'-PASS.json',{status:'PASS',stage:c.execution.workflow.stage,requiredMinor:1000000,actualFirstPaymentMinor:400000,executionAllowed:false,transferAllowed:false,note:'Ledger shows waiting. Its handle link rechecks the original task, refuses a WAITING task and returns to the current view with one waiting item; execution and transfer actions absent.'});
 }
 console.log(tag+' actual UI PASS');
}catch(e){if(page)save(tag+'-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
