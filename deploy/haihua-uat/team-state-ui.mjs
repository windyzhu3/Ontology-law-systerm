import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import {login,save,runtime} from './browser.mjs';
if(!runtime.endsWith('haihua-uat-runtime'))throw Error('Original complete business scenario required');
const browser=await chromium.launch({channel:'chrome',headless:true}),pages=[];let page;
const responseFor=view=>page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/team-management/'+view);
try{for(const actor of ['sales_manager01','sales_manager02','case_admin01']){
 const admitted=await login(browser,actor);page=admitted.page;await page.getByRole('button',{name:'业务管理',exact:true}).click();let response=responseFor('tasks');await page.locator('aside.business-navigation').getByRole('button',{name:'团队待办',exact:true}).click();
 for(const view of ['tasks','waiting','exceptions','history']){
  if(view!=='tasks'){response=responseFor(view);await page.getByLabel('查看内容',{exact:true}).selectOption(view);}
  for(let index=0;index<100;index++){
   const r=await response;assert.equal(r.status(),200);const data=await r.json();assert.ok(Array.isArray(data.items));
   await page.getByText(/^正在读取.*事项[.…]$/).first().waitFor({state:'hidden'});const rows=page.locator('.list-pane .record-table tbody tr');
   if(data.items.length){const first=data.items[0];await page.waitForFunction(({label,purpose,state})=>{const row=document.querySelector('.list-pane .record-table tbody tr');return row?.textContent?.includes(label)&&row?.textContent?.includes(purpose)&&row?.textContent?.includes(state);},{label:first.customerLabel,purpose:first.purposeLabel,state:first.stateLabel});}
   else await page.getByText('当前筛选下没有事项，不代表全部业务已完成。',{exact:true}).waitFor();
   assert.equal(await rows.count(),data.items.length);const rendered=await rows.allTextContents();data.items.forEach((item,i)=>assert.ok(rendered[i].includes(item.customerLabel)&&rendered[i].includes(item.purposeLabel)&&rendered[i].includes(item.stateLabel)));
   pages.push({actor,view,index,status:r.status(),items:data.items,rendered,hasNext:!!data.nextCursor});save('team-state-ui-pages.json',pages);
   if(!data.nextCursor)break;if(index===99)throw Error('UI traversal bound exceeded');response=responseFor(view);await page.getByRole('button',{name:'下一页',exact:true}).click();
  }
 }
 await admitted.context.close();console.log(actor+' actual team four-view complete cursor traversal recorded');
}save('team-state-ui-PASS.json',{status:'PASS',pageCount:pages.length,actors:3,views:4,scope:'Actual rendered rows and authorized responses; missing task/state types remain uncovered'});
}catch(e){if(page)save('team-state-ui-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
