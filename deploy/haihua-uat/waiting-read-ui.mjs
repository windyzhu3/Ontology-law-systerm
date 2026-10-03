import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import {login,save,runtime} from './browser.mjs';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{
 for(const username of ['sales01','sales02','sales03','sales04','sales05','sales_manager01','sales_manager02','finance01','case_admin01']){
  const a=await login(browser,username),page=a.page,panel=page.locator('section.personal-waiting');
  await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
  await page.getByText('正在读取有权合同事项…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取转案资料…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取本次合同…',{exact:true}).waitFor({state:'hidden'});
  const selected=await page.locator('.current-card .subject-title:visible,.work-card .subject:visible').allTextContents();
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/workbench/waiting');
  await panel.getByRole('button',{name:/查看等待事项/}).click();
  let r=await response;assert.equal(r.status(),200);let data=await r.json();const total=data.totalCount,rows=[...data.waitingItems];let pages=1;
  await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
  while(data.nextCursor){
   assert.ok(pages<100);const next=page.waitForResponse(x=>new URL(x.url()).pathname==='/api/v1/workbench/waiting'&&new URL(x.url()).searchParams.has('cursor'));
   await panel.getByRole('button',{name:'加载更多等待事项',exact:true}).click();r=await next;assert.equal(r.status(),200);data=await r.json();assert.equal(data.totalCount,total);rows.push(...data.waitingItems);pages++;
   await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
  }
  assert.equal(new Set(rows.map(x=>x.id)).size,total);assert.equal(rows.length,total);
  assert.equal(await panel.locator('.personal-wait-row').count(),total);
  assert.match(await panel.getByRole('button',{name:/收起/}).innerText(),new RegExp('等待 '+total+' 项'));
  if(!total)await panel.getByText('当前任职没有等待事项。',{exact:true}).waitFor();
  const details=[];
  for(let index=0;index<rows.length;index++){
   const row=rows[index],pending=page.waitForResponse(x=>new URL(x.url()).pathname==='/api/v1/workbench/waiting/'+row.id);
   await panel.locator('.personal-wait-row').nth(index).click();const fetched=await pending;assert.equal(fetched.status(),200);const item=(await fetched.json()).waitingDetail;
   assert.equal(item.id,row.id);assert.equal(item.canHandle,item.state==='READY');
   await panel.getByRole('region',{name:item.customerLabel+'等待详情',exact:true}).waitFor();
   if(!item.canHandle)assert.equal(await panel.getByRole('button',{name:'前往办理',exact:true}).count(),0);
   details.push({id:row.id,state:item.state,canHandle:item.canHandle});
  }
  const observed=await page.locator('.current-card .subject-title:visible,.work-card .subject:visible').allTextContents();save('waiting-selection-'+username+'.json',{selected,observed});assert.deepEqual(observed,selected);
  // An actual browser read failure must clear stale details and retain the current card.
  const failureRoute='**/api/v1/workbench/waiting?*';await page.route(failureRoute,route=>route.abort('failed'));
  await panel.getByRole('button',{name:'重新核对状态',exact:true}).click();await panel.getByRole('alert').waitFor();
  assert.equal(await panel.locator('.personal-wait-detail').count(),0);assert.equal(await panel.locator('.personal-wait-row').count(),0);
  assert.match(await panel.getByRole('button',{name:/收起/}).innerText(),/等待 — 项/);
  assert.deepEqual(await page.locator('.current-card .subject-title:visible,.work-card .subject:visible').allTextContents(),selected);
  await page.unroute(failureRoute);const retry=page.waitForResponse(x=>new URL(x.url()).pathname==='/api/v1/workbench/waiting');await panel.getByRole('button',{name:'重新查询',exact:true}).click();assert.equal((await retry).status(),200);await panel.getByText('正在核对等待事项…',{exact:true}).waitFor({state:'hidden'});
  results.push({username,total,pages,details,status:'PASS',readFailureClearedDetails:true,currentSelectionPreserved:true});save('waiting-read-ui-results.json',results);await a.context.close();
 }
 console.log('Nine actual actors waiting count/list/detail/empty/read-failure/retry PASS; no business commands');
}finally{await browser.close();}
