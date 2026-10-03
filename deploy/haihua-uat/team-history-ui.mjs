import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import {login,save} from './browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});const results=[];let activePage;
try{for(const actor of ['sales_manager01','sales_manager02']){
 const {page,context}=await login(browser,actor);
 activePage=page;
 await page.getByRole('button',{name:'业务管理',exact:true}).click();await page.locator('aside.business-navigation').getByRole('button',{name:'团队待办',exact:true}).click();
 await page.getByLabel('查看内容',{exact:true}).selectOption('history');await page.getByLabel('搜索客户或责任人',{exact:true}).fill('HH-B07-20261001');
 await page.getByText('正在读取团队事项…',{exact:true}).waitFor({state:'hidden'});
 const rows=page.locator('.list-pane .record-table tbody tr');
 if(actor==='sales_manager01'){
  const target=page.getByRole('button',{name:'HH-B07-20261001 合成客户',exact:true}).first();
  for(let cursor=0;cursor<60&&!await target.isVisible();cursor++){
   const next=page.getByRole('button',{name:'下一页',exact:true});await next.waitFor();
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/team-management/history'&&new URL(r.url()).searchParams.has('cursor'));
   await next.click();await response;await page.getByText(/^正在读取.*[.…]$/).first().waitFor({state:'hidden'});
  }
  await target.waitFor();
  const reviews=rows.filter({hasText:'复核线索有效性'});assert.equal(await reviews.count(),2);
  const details=[];
  for(let i=0;i<2;i++){
   await reviews.nth(i).getByRole('button',{name:'HH-B07-20261001 合成客户',exact:true}).click();
   const pane=page.locator('.detail-pane');await pane.getByRole('heading',{name:'HH-B07-20261001 合成客户',exact:true}).waitFor();
   await page.waitForFunction(()=>document.querySelector('.detail-pane')?.textContent?.includes('复核'));
   assert.equal(await pane.getByRole('button',{name:'前往办理',exact:true}).count(),0);details.push(await pane.innerText());
  }
  assert.ok(details.some(t=>t.includes('重新安排')||t.includes('REOPEN_CONTACT')));assert.ok(details.some(t=>t.includes('确认本条无效')||t.includes('CONFIRM_INVALID')));
  results.push({actor,reviewHistories:2,details,historyHasNoAction:true});
 }else{
  for(let cursor=0;cursor<60;cursor++){
   await page.getByText('当前筛选下没有事项，不代表全部业务已完成。',{exact:true}).waitFor();assert.equal(await rows.count(),0);
   const next=page.getByRole('button',{name:'下一页',exact:true});if(!await next.isVisible())break;
   if(cursor===59)throw Error('Cross-department cursor traversal incomplete');
   const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/team-management/history'&&new URL(r.url()).searchParams.has('cursor'));
   await next.click();await response;await page.getByText(/^正在读取.*[.…]$/).first().waitFor({state:'hidden'});
  }
  results.push({actor,crossDepartmentRows:0,allFilteredPagesTraversed:true});
 }
 save('B07-team-history-ui.json',results);await context.close();console.log(actor+' scoped B07 immutable team history UI verified');
}}catch(e){if(activePage)save('B07-team-history-ui-failure.txt',await activePage.locator('body').innerText());throw e;}finally{await browser.close();}
