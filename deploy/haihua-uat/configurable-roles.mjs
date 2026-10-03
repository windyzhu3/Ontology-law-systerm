// One-shot UI verification against the retained original UAT. Unknown outcomes
// keep their exact attempts; never rerun this script with fresh command keys.
import { chromium } from '@playwright/test';
import path from 'node:path';
import { login,runtime,evidence,save,guardedRouteContinue } from './browser.mjs';
import { reserveAttempt } from './command-attempt.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});
const results=[];
try {
 const {page,context}=await login(browser,'sys_manager01');
 const errors=[];page.on('pageerror',e=>errors.push(String(e.message).split('\n')[0]));
 let pending=null;
 await page.route('**/api/v1/admin/identity/roles**',async route=>{
  if(['POST','PATCH'].includes(route.request().method())){
   if(!pending)throw Error('Unexpected role write');
   pending.dispatch(route.request());
  }
  await guardedRouteContinue(route);
 });
 async function command(name,prepare,submit){
  pending=reserveAttempt(runtime,'ROLE_CONFIG_UI_'+name,{scenario:name});
  await prepare();
  const responsePromise=page.waitForResponse(r=>new URL(r.url()).pathname.startsWith('/api/v1/admin/identity/roles')&&['POST','PATCH'].includes(r.request().method()));
  await submit();const response=await responsePromise;const receipt=await response.json();
  pending.complete(response.status(),receipt);pending=null;
  if(!response.ok()||receipt.outcome!=='SUCCEEDED')throw Error(name+' did not succeed; original receipt retained');
  await page.getByText('结果已记录，当前页已重新读取。新建记录可能不在当前页。',{exact:true}).waitFor();
  results.push({scenario:name,status:response.status(),outcome:receipt.outcome});
  console.log('Role UI '+name+': '+receipt.outcome);
 }
 await page.getByRole('link',{name:'岗位管理',exact:true}).click();
 await page.getByRole('heading',{name:'岗位管理',exact:true}).waitFor();
 await command('create',async()=>{
  await page.getByRole('button',{name:'新增岗位',exact:true}).click();
  await page.getByLabel('岗位代码',{exact:true}).fill('UAT_CUSTOM_ADVISOR');
  await page.getByLabel('显示名称',{exact:true}).fill('试验自定义顾问');
 },()=>page.getByRole('button',{name:'确认创建',exact:true}).click());
 await page.getByRole('button',{name:'试验自定义顾问',exact:true}).click();
 await command('rename',async()=>{
  await page.getByRole('button',{name:'修改岗位名称',exact:true}).click();
  await page.getByLabel('显示名称',{exact:true}).fill('试验可配置顾问');
 },()=>page.getByRole('button',{name:'保存名称',exact:true}).click());
 async function candidate(present){
  await page.getByRole('link',{name:'任职管理',exact:true}).click();
  await page.getByRole('button',{name:'新建任职',exact:true}).click();
  await page.getByLabel('岗位',{exact:true}).locator('option').filter({hasText:'SALES_REPRESENTATIVE'}).waitFor({state:'attached'});
  const options=await page.getByLabel('岗位',{exact:true}).locator('option').allTextContents();
  if(options.some(label=>label.includes('试验可配置顾问 · UAT_CUSTOM_ADVISOR'))!==present)throw Error('Active role candidate mismatch');
  results.push({scenario:present?'active_candidate':'inactive_excluded',passed:true});
  await page.getByRole('button',{name:'取消',exact:true}).click();
  await page.getByRole('link',{name:'岗位管理',exact:true}).click();
  await page.getByRole('button',{name:'试验可配置顾问',exact:true}).click();
 }
 await candidate(true);
 async function lifecycle(name,verb){
  await command(name,async()=>{
   await page.getByRole('button',{name:verb+'岗位',exact:true}).click();
   await page.getByRole('dialog').getByLabel('操作原因',{exact:true}).selectOption('ADMINISTRATIVE_ACTION');
  },()=>page.getByRole('dialog').getByRole('button',{name:'确认'+verb,exact:true}).click());
  await page.getByRole('button',{name:'试验可配置顾问',exact:true}).click();
 }
 await lifecycle('deactivate','停用');await candidate(false);
 await lifecycle('reactivate','恢复');await candidate(true);
 await lifecycle('park_inactive','停用');
 await page.screenshot({path:path.join(evidence,'configurable-roles-final.png'),fullPage:true});
 if(errors.length)throw Error('Browser application error');
 await context.close();
 for(const username of ['sales01','sales02','sales03','sales04','sales05','sales_manager01','sales_manager02','finance01','case_admin01']){
  const actor=await login(browser,username);
  await actor.page.getByRole('button',{name:'切换任职',exact:true}).waitFor();
  if((await actor.page.locator('body').innerText()).includes('当前任职不能进入'))throw Error(username+' entry qualification regressed');
  results.push({scenario:'retained_login',username,appointmentId:actor.appointment.value,label:actor.appointment.label});
  console.log(username+': original appointment login passed');
  await actor.context.close();
 }
 save('ROLE_CONFIG-browser-result.json',{passed:true,results});
 console.log('Role UI lifecycle and active candidates passed; all 10 original actors logged in; no appointments or grants created');
} finally {await browser.close();}
