import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import path from 'node:path';
import {login,save,evidence} from './browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});const results=[];
try{for(const actor of ['sales01','sales_manager01','finance01','case_admin01']){
 const {page,context}=await login(browser,actor);const identity=await page.locator('.session-identity').filter({visible:true}).first().innerText();
 await page.getByRole('button',{name:'业务管理',exact:true}).click();
 const nav=page.locator('aside.business-navigation');await nav.waitFor();
 const entries=await nav.getByRole('button').allTextContents();
 for(const label of entries){
  if(label.includes('我的待办'))continue;
  await nav.getByRole('button',{name:label.trim(),exact:true}).click();await nav.waitFor();
  await page.getByText(/^正在(?:读取|核对|查询).*[.…]$/).first().waitFor({state:'hidden'});
  assert.deepEqual(await nav.getByRole('button').allTextContents(),entries);
  assert.equal(await page.locator('.session-identity').filter({visible:true}).first().innerText(),identity);
  assert.equal(context.pages().length,1);
  assert.equal(await nav.locator('button').count(),await nav.locator('button svg').count());
  for(const width of [1440,390,360]){
   await page.setViewportSize({width,height:1000});
   const dimensions=await page.evaluate(()=>({body:document.documentElement.scrollWidth,viewport:innerWidth}));
   results.push({actor,page:label.trim(),width,identityStable:true,menuStable:true,icons:true,singleWindow:true,...dimensions,overflow:dimensions.body>dimensions.viewport+1});
   await page.screenshot({path:path.join(evidence,'navigation-'+actor+'-'+label.trim()+'-'+width+'.png'),fullPage:true});
  }
  await page.setViewportSize({width:1440,height:1000});save('ui-navigation-audit.json',results);
 }
 await context.close();console.log(actor+' shared navigation/identity/icon and 3viewport audit recorded');
}}catch(e){save('ui-navigation-audit-failure.json',{message:e.message,completed:results.length});throw e;}finally{await browser.close();}
