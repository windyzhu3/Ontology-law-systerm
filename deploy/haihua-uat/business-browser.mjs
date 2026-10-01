export async function openOpportunity(page,code){
 await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 await page.getByText('正在读取有权报价事项…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取本次报价…',{exact:true}).waitFor({state:'hidden'});
 if(await page.getByRole('button',{name:'返回商机台账',exact:true}).isVisible())await page.getByRole('button',{name:'返回商机台账',exact:true}).click();
 else{await page.getByRole('button',{name:'业务管理',exact:true}).click();await page.getByRole('button',{name:'商机台账',exact:true}).click();}
 await page.getByRole('heading',{name:'商机台账',exact:true}).waitFor();await page.getByLabel('搜索客户',{exact:true}).fill(code);
 await page.getByRole('button',{name:code+' 合成客户',exact:true}).click();await page.getByRole('heading',{name:code+' 合成客户',exact:true}).waitFor();
}
export async function selectTask(page,code,purpose){
 await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取有权合同事项…',{exact:true}).waitFor({state:'hidden'});
 if(!await page.locator('.current-card .subject-title:visible,.work-card .subject:visible').filter({hasText:code}).count()){
  const menu=page.locator('summary').filter({hasText:'我的待办'});await menu.click();
  const target=page.getByRole('button',{name:new RegExp(code+' .*'+purpose)});
  for(let attempt=0;attempt<12&&!await target.isVisible();attempt++){
   await page.getByRole('button',{name:'刷新当前责任',exact:true}).click();await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
   if(!await menu.evaluate(e=>e.parentElement.open))await menu.click();
   if(!await target.isVisible())await page.waitForTimeout(5000);
  }
  if(await target.isDisabled()&&await target.getAttribute('aria-current')==='true')return;
  await target.click();
  await page.locator('body').filter({hasText:code}).waitFor();
 }
}
