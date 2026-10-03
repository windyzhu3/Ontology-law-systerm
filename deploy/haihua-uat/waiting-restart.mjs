import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {login,save} from './browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{
 ({page}=await login(browser,'sales03'));
 const panel=page.locator('section.personal-waiting');
 await panel.getByRole('button',{name:/查看等待事项/}).click();
 await panel.getByRole('button',{name:/HH-B09-20261001 合成客户/}).click();
 await panel.getByRole('heading',{name:'未到约定时间',exact:true}).waitFor();
 save('B09-restart-future.txt',await page.locator('body').innerText());
 for(let i=0;i<12;i++){
  await panel.getByRole('button',{name:'重新核对状态',exact:true}).click();
  await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
  if(await panel.getByRole('heading',{name:'已到核对时间',exact:true}).isVisible())break;
  if(i===11)throw Error('Due state not reached with Worker stopped');
  await page.waitForTimeout(30000);
 }
 assert.equal(await panel.getByRole('button',{name:'前往办理',exact:true}).count(),0);
 save('B09-restart-WAIT_DUE.txt',await page.locator('body').innerText());
 execFileSync('D:/soft/python3/python.exe',['-X','utf8','deploy/haihua-uat/apps.py','start','worker'],{stdio:'pipe',windowsHide:true});
 for(let i=0;i<12;i++){
  await panel.getByRole('button',{name:'重新核对状态',exact:true}).click();
  await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
  if(await panel.getByRole('heading',{name:'已可办理',exact:true}).isVisible())break;
  if(i===11)throw Error('Worker restart did not restore responsibility');
  await page.waitForTimeout(30000);
 }
 save('B09-restart-READY.txt',await page.locator('body').innerText());
 await panel.getByRole('button',{name:'前往办理',exact:true}).click();
 await page.locator('.current-card .subject-title').filter({hasText:'HH-B09-20261001'}).waitFor();
 save('B09-restart-PASS.json',{future:true,dueWithoutHandle:true,workerRestart:true,originalTaskNavigation:true});
 console.log('B09 real-clock FUTURE → WAIT_DUE without Worker → READY after restart → original work card PASS');
}catch(e){if(page)save('B09-restart-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
