import {chromium} from '@playwright/test';import fs from 'node:fs';import path from 'node:path';
import {login,save,runtime,evidence} from './browser.mjs';
const [actor,code]=process.argv.slice(2);if(!['HH-B06-20261001','HH-B09-20261001'].includes(code))throw Error('approved wait fixture only');
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,actor));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 const section=page.getByRole('region',{name:'我的等待事项',exact:true});
 // section uses aria-label on a semantic section, with the exact shared component.
 const panel=page.locator('section.personal-waiting');const toggle=panel.getByRole('button',{name:/查看等待事项/});await toggle.click();
 const row=panel.getByRole('button',{name:new RegExp(code+' 合成客户')});await row.click();
 await panel.getByRole('heading',{name:/未到约定时间|已到核对时间|已可办理/}).waitFor();
 save(code+'-waiting-initial.txt',await page.locator('body').innerText());
 await page.screenshot({path:path.join(evidence,code+'-waiting-initial.png'),fullPage:true});
 if(code.includes('B06')){if(await panel.getByRole('button',{name:'前往办理',exact:true}).count())throw Error('future contact cannot handle');console.log(code+' actual future wait reason/detail and unavailable handle PASS');}
 else{
  const stop=path.join(runtime,'B09-waiting-stop.json');
  for(let n=0;n<12;n++){
   await panel.getByRole('button',{name:'重新核对状态',exact:true}).click();
   await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
   if(await panel.getByRole('heading',{name:'已可办理',exact:true}).isVisible()){
    save(code+'-waiting-ready.txt',await page.locator('body').innerText());
    await panel.getByRole('button',{name:'前往办理',exact:true}).click();
    await page.locator('.current-card .subject-title').filter({hasText:code}).waitFor();
    save(code+'-worker-reopened-current.txt',await page.locator('body').innerText());console.log(code+' actual Worker READY recheck/original task navigation PASS');break;
   }
   if(n===11)throw Error('actual Worker did not reopen within six minutes');
   await page.waitForTimeout(30000);
  }
 }
}catch(e){if(page)save(code+'-waiting-worker-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
