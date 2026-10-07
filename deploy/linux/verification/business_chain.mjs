// Real pages only. An interrupted step is retained; there is no blind rerun.
import {chromium} from '/tools/node_modules/@playwright/test/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {active,credentials,evidence,login,runtime,save} from './browser.mjs';
active();
const [phase,selected]=process.argv.slice(2);
const cases=[1,2,3,4].map(i=>({code:`HH-G0${i}-20261001-R2`,sales:`sales0${i}`,manager:i<3?'sales_manager01':'sales_manager02',department:i<3?'SALES_1':'SALES_2',entry:i%2?'QUOTE':'DIRECT'}));
const ledgerFile='native-ui-steps.json';const ledger=fs.existsSync(path.join(runtime,ledgerFile))?JSON.parse(fs.readFileSync(path.join(runtime,ledgerFile))):{};
async function step(key,fn){if(ledger[key]?.state==='COMPLETE')return;if(ledger[key])throw Error('Original interrupted step requires explicit receipt reconciliation: '+key);ledger[key]={state:'STARTED',at:Date.now()};save(ledgerFile,ledger);await fn();ledger[key]={...ledger[key],state:'COMPLETE',finishedAt:Date.now()};save(ledgerFile,ledger);console.log('NATIVE_UI_STEP_PASS '+key);}
function module(name,...args){execFileSync(process.execPath,[path.join(import.meta.dirname,name+'.mjs'),...args],{env:process.env,stdio:'inherit',timeout:240000});}
async function capture(c){
 const browser=await chromium.launch({headless:true});let page;
 try{({page}=await login(browser,c.sales));await page.getByRole('button',{name:'录入线索',exact:true}).click();await page.getByRole('heading',{name:'新增线索',exact:true}).waitFor();assert.equal(await page.getByLabel('来源',{exact:true}).count(),0,'Source must follow actual HUMAN account');
 const phone='1390002100'+c.sales.slice(-1);const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/leads');
 if(c.sales==='sales04'){
  await page.getByRole('button',{name:'批量导入',exact:true}).click();await page.getByLabel('选择文件',{exact:true}).setInputFiles({name:'native-own-source.csv',mimeType:'text/csv',buffer:Buffer.from('来源记录号,联系人,手机号,客户名称,需求描述\n'+c.code+','+c.code+' 合成联系人,+86'+phone+','+c.code+' 合成客户,纯合成合同纠纷测试，不用于实际委托。\n')});await page.getByRole('button',{name:'确认导入 1 条',exact:true}).click();
 }else{
  await page.getByLabel('联系人',{exact:true}).fill(c.code+' 合成联系人');await page.getByLabel('手机号',{exact:true}).fill(phone);await page.getByLabel('客户名称',{exact:true}).fill(c.code+' 合成客户');await page.getByLabel('需求描述',{exact:true}).fill('纯合成合同纠纷咨询，不用于实际委托和签约。');await page.getByRole('button',{name:'保存线索',exact:true}).click();
 }
 const r=await response,receipt=await r.json();assert.equal(r.status(),201);assert.equal(receipt.outcome,'SUCCEEDED');const body=r.request().postDataJSON();assert.equal(body.sourceAccountCode,credentials[c.sales].username);
 const index=fs.existsSync(path.join(runtime,'business-index.json'))?JSON.parse(fs.readFileSync(path.join(runtime,'business-index.json'))):{};assert(!index[c.code]);index[c.code]={username:c.sales,label:c.code+' 合成客户',status:r.status(),commandId:r.request().headers()['idempotency-key'],receipt,source:body.sourceAccountCode,mode:c.sales==='sales04'?'BATCH':'MANUAL'};save('business-index.json',index);await page.screenshot({path:path.join(evidence,c.code+'-native-capture.png'),fullPage:true});
 }catch(error){if(page)save('capture-failure.txt',await page.locator('body').innerText());throw error;}finally{await browser.close();}
}
for(const c of cases.filter(c=>!selected||c.code===selected)){
 if(phase==='leads'){
  await step(c.code+':capture',()=>capture(c));await step(c.code+':assign',()=>module('assign-lead',c.manager,c.sales,c.code));await step(c.code+':contact',()=>module('contact-lead',c.sales,c.code));await step(c.code+':customer',()=>module('customer',c.sales,c.code));
 }else if(phase==='template-upload'){
  if(c.sales!=='sales01')continue;
  for(const name of ['consulting','civil-litigation','enforcement'])await step('template-upload:'+name,()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',name+'.pdf')));
 }else if(phase==='contract'){
  if(c.entry==='QUOTE'){
   await step(c.code+':quote-proof',()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',c.code+'-quote-proof-SYNTHETIC-NOT-LEGAL.pdf')));await step(c.code+':quote-prepare',()=>module('quote-prepare',c.sales,c.code));await step(c.code+':quote-decide',()=>module('quote-decide',c.manager,c.code,'APPROVED'));await step(c.code+':quote-accept',()=>module('quote-accept',c.sales,c.code));
  }else{await step(c.code+':direct-request',()=>module('direct-request',c.sales,c.code));await step(c.code+':direct-decide',()=>module('direct-decide',c.manager,c.code));}
  await step(c.code+':form',()=>module('contract-form',c.sales,c.code));await step(c.code+':review',()=>module('contract-decide','case_admin01',c.code,'review','CLEAR'));await step(c.code+':submit',()=>module('contract-submit',c.sales,c.code));await step(c.code+':approval',()=>module('contract-decide',c.manager,c.code,'approval','APPROVED'));
 }else if(phase==='sign-transfer'){
  await step(c.code+':arrange',()=>module('sign-arrange',c.sales,c.code));
  for(const slot of ['1','2']){await step(c.code+':sign-submit:'+slot,()=>module('sign-submit',c.sales,c.code,slot));await step(c.code+':sign-verify:'+slot,()=>module('sign-verify',c.code,slot,'VERIFIED'));}
  await step(c.code+':archive',()=>module('sign-archive','case_admin01',c.code));await step(c.code+':execution',()=>module('execution',c.sales,c.code));
  await step(c.code+':payment-proof',()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',c.code+'-NATIVE-G0'+c.sales.slice(-1)+'-SYNTHETIC-payment.pdf')));await step(c.code+':payment',()=>module('payment',c.code,'10000','NATIVE-G0'+c.sales.slice(-1)));
  await step(c.code+':transfer-prepare',()=>module('transfer',c.sales,c.code,'prepare'));for(const stage of ['review','intake','classify'])await step(c.code+':transfer-'+stage,()=>module('transfer','case_admin01',c.code,stage));
 }else throw Error('Explicit bounded phase required');
}
