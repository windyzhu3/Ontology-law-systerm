// Real pages only. An interrupted step is retained; there is no blind rerun.
import {chromium} from '/tools/node_modules/@playwright/test/index.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {active,credentials,evidence,guardedRouteFetch,login,logout,origin,runtime,save} from './browser.mjs';
active();
const [phase,selected]=process.argv.slice(2);
const cases=[1,2,3,4].map(i=>({code:`HH-G0${i}-20261001-R2`,sales:`sales0${i}`,manager:i<3?'sales_manager01':'sales_manager02',department:i<3?'SALES_1':'SALES_2',entry:i%2?'QUOTE':'DIRECT'}));
const ledgerFile='native-ui-steps.json';const ledger=fs.existsSync(path.join(runtime,ledgerFile))?JSON.parse(fs.readFileSync(path.join(runtime,ledgerFile))):{};
async function step(key,fn){if(ledger[key]?.state==='COMPLETE')return;if(ledger[key])throw Error('Original interrupted step requires explicit receipt reconciliation: '+key);ledger[key]={state:'STARTED',at:Date.now()};save(ledgerFile,ledger);await fn();ledger[key]={...ledger[key],state:'COMPLETE',finishedAt:Date.now()};save(ledgerFile,ledger);console.log('NATIVE_UI_STEP_PASS '+key);}
function module(name,...args){execFileSync(process.execPath,[path.join(import.meta.dirname,name+'.mjs'),...args],{env:process.env,stdio:'inherit',timeout:240000});}
async function capture(c){
 const browser=await chromium.launch({headless:true});let page;
 try{({page}=await login(browser,c.sales));await page.getByRole('button',{name:'录入线索',exact:true}).click();await page.getByRole('heading',{name:'新增线索',exact:true}).waitFor();assert.equal(await page.getByLabel('来源',{exact:true}).count(),0,'Source must follow actual HUMAN account');
 const fault=c.sales==='sales03';let lost;
 if(fault)await page.route('**/api/v1/leads',async route=>{
  if(route.request().method()!=='POST'){await route.fallback();return;}
  const request=route.request(),h=request.headers(),id=h['idempotency-key'];assert(id);const record={actor:c.sales,appointmentId:credentials[c.sales].appointmentId,method:'POST',path:'/api/v1/leads',body:request.postData(),ifMatch:h['if-match']??null,ifNoneMatch:h['if-none-match']??null,state:'DISPATCH_UNKNOWN'};
  const file=path.join(runtime,'browser-commands',id+'.json');fs.mkdirSync(path.dirname(file),{mode:0o700,recursive:true});const fd=fs.openSync(file,'wx',0o600);try{fs.writeFileSync(fd,JSON.stringify(record));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
  const actual=await guardedRouteFetch(route);const receipt=await actual.json();assert.equal(actual.status(),201);assert.equal(receipt.outcome,'SUCCEEDED');lost={id,receipt,body:request.postDataJSON()};fs.writeFileSync(file,JSON.stringify({...record,state:'COMMITTED_RESPONSE_DISCARDED',status:201,receipt}),{mode:0o600});await route.abort('failed');
 });
 const phone='1390002100'+c.sales.slice(-1);const response=fault?null:page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/leads');
 if(c.sales==='sales04'){
  await page.getByRole('button',{name:'批量导入',exact:true}).click();await page.getByLabel('选择文件',{exact:true}).setInputFiles({name:'native-own-source.csv',mimeType:'text/csv',buffer:Buffer.from('来源记录号,联系人,手机号,客户名称,需求描述\n'+c.code+','+c.code+' 合成联系人,+86'+phone+','+c.code+' 合成客户,纯合成合同纠纷测试，不用于实际委托。\n')});await page.getByRole('button',{name:'确认导入 1 条',exact:true}).click();
 }else{
  await page.getByLabel('联系人',{exact:true}).fill(c.code+' 合成联系人');await page.getByLabel('手机号',{exact:true}).fill(phone);await page.getByLabel('客户名称',{exact:true}).fill(c.code+' 合成客户');await page.getByLabel('需求描述',{exact:true}).fill('纯合成合同纠纷咨询，不用于实际委托和签约。');await page.getByRole('button',{name:'保存线索',exact:true}).click();
 }
 let status,commandId,receipt,body;
 if(fault){await page.getByRole('heading',{name:'录入结果待确认',exact:true}).waitFor();assert(lost);const recovery=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/commands/'+lost.id+'/receipt');await page.getByRole('button',{name:'核对提交结果',exact:true}).click();const r=await recovery;receipt=await r.json();assert.equal(r.status(),200);assert.deepEqual(receipt,lost.receipt);await page.getByRole('heading',{name:'线索已录入',exact:true}).waitFor();status=201;commandId=lost.id;body=lost.body;save('native-original-command-recovery.json',{commandId,committedResponseDiscarded:true,actualUiUnknown:true,recoveredOriginalReceipt:true,noSecondPost:true});}
 else{const r=await response;receipt=await r.json();status=r.status();commandId=r.request().headers()['idempotency-key'];body=r.request().postDataJSON();}
 assert.equal(status,201);assert.equal(receipt.outcome,'SUCCEEDED');assert.equal(body.sourceAccountCode,credentials[c.sales].sourceAccount);
 const index=fs.existsSync(path.join(runtime,'business-index.json'))?JSON.parse(fs.readFileSync(path.join(runtime,'business-index.json'))):{};assert(!index[c.code]);index[c.code]={username:c.sales,label:c.code+' 合成客户',status,commandId,receipt,source:body.sourceAccountCode,mode:c.sales==='sales04'?'BATCH':'MANUAL'};save('business-index.json',index);await page.screenshot({path:path.join(evidence,c.code+'-native-capture.png'),fullPage:true});
 await page.getByText('正在读取当前任职可处理的后续事项…',{exact:true}).waitFor({state:'hidden'});await logout(page,c.sales);
 }catch(error){if(page)save('capture-failure.txt',await page.locator('body').innerText());throw error;}finally{await browser.close();}
}
for(const c of cases.filter(c=>!selected||c.code===selected)){
 if(phase==='reconcile-capture'){
  assert(selected);const matches=fs.readdirSync(path.join(runtime,'browser-commands')).map(f=>({...JSON.parse(fs.readFileSync(path.join(runtime,'browser-commands',f))),savedCommandId:path.basename(f,'.json')})).filter(r=>r.path==='/api/v1/leads'&&JSON.parse(r.body).customerName===c.code+' 合成客户');assert.equal(matches.length,1);const original=matches[0];assert.equal(JSON.parse(original.body).sourceAccountCode,credentials[c.sales].sourceAccount);
  const browser=await chromium.launch({headless:true});try{const actor=await login(browser,c.sales);assert.equal(original.appointmentId,actor.appointment.value);const commandId=original.savedCommandId;let actual=await actor.page.evaluate(async({url,headers})=>{const r=await fetch(url,{headers,cache:'no-store'});return {status:r.status,receipt:await r.json()};},{url:'/api/v1/commands/'+commandId+'/receipt',headers:actor.apiHeaders()});
   if(actual.status===404){assert(['DISPATCH_UNKNOWN','ORIGINAL_REPLAY_UNKNOWN'].includes(original.state));const file=path.join(runtime,'browser-commands',commandId+'.json');fs.writeFileSync(file,JSON.stringify({...original,state:'ORIGINAL_REPLAY_UNKNOWN'}),{mode:0o600});const headers={...actor.apiHeaders(),'Content-Type':'application/json','Idempotency-Key':commandId};if(original.ifMatch)headers['If-Match']=original.ifMatch;if(original.ifNoneMatch)headers['If-None-Match']=original.ifNoneMatch;actual=await actor.page.evaluate(async({url,headers,body})=>{const r=await fetch(url,{method:'POST',headers,body});return {status:r.status,receipt:await r.json()};},{url:original.path,headers,body:original.body});assert.equal(actual.status,201);assert.equal(actual.receipt.commandId,commandId);assert.equal(actual.receipt.outcome,'SUCCEEDED');fs.writeFileSync(file,JSON.stringify({...original,state:'OBSERVED',status:201,receipt:actual.receipt,confirmedAbsentOriginalReplay:true}),{mode:0o600});}
   else{assert.equal(actual.status,200);if(original.receipt)assert.deepEqual(actual.receipt,original.receipt);}
   const records=fs.existsSync(path.join(runtime,'business-index.json'))?JSON.parse(fs.readFileSync(path.join(runtime,'business-index.json'))):{};if(records[c.code])assert.equal(records[c.code].commandId,commandId);else{records[c.code]={username:c.sales,label:c.code+' 合成客户',status:201,commandId,receipt:actual.receipt,source:JSON.parse(original.body).sourceAccountCode,mode:'MANUAL'};save('business-index.json',records);}await logout(actor.page,c.sales);ledger[c.code+':capture']={...ledger[c.code+':capture'],state:'COMPLETE',explicitOriginalReceiptReconciliation:true};save(ledgerFile,ledger);console.log('NATIVE_ORIGINAL_CAPTURE_RECEIPT_RECONCILED');}finally{await browser.close();}
 }else if(phase==='leads'){
  await step(c.code+':capture',()=>capture(c));await step(c.code+':assign',()=>module('assign-lead',c.manager,c.sales,c.code));await step(c.code+':contact',()=>module('contact-lead',c.sales,c.code));await step(c.code+':customer',()=>module('customer',c.sales,c.code));
 }else if(phase==='template-upload'){
  if(c.sales!=='sales01')continue;
  for(const name of ['consulting','civil-litigation','enforcement'])await step('template-upload:'+name,()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',name+'.pdf')));
 }else if(phase==='contract'){
  if(c.entry==='QUOTE'){
   await step(c.code+':quote-proof',()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',c.code+'-quote-proof-SYNTHETIC-NOT-LEGAL.pdf')));await step(c.code+':quote-prepare',()=>module('quote-prepare',c.sales,c.code));await step(c.code+':quote-decide',()=>module('quote-decide',c.manager,c.code,'APPROVED'));await step(c.code+':quote-accept',()=>module('quote-accept',c.sales,c.code));
  }else{await step(c.code+':direct-request',()=>module('direct-request',c.sales,c.code));await step(c.code+':direct-decide',()=>module('direct-decide',c.manager,c.code));}
  await step(c.code+':form',()=>module('contract-form',c.sales,c.code));await step(c.code+':review',()=>module('contract-decide','case_admin01',c.code,'review','CLEAR'));await step(c.code+':submit',()=>module('contract-submit',c.sales,c.code,'approval'));await step(c.code+':approval',()=>module('contract-decide',c.manager,c.code,'approval','APPROVED'));
 }else if(phase==='sign-transfer'){
  await step(c.code+':arrange',()=>module('sign-arrange',c.sales,c.code));
  for(const slot of ['1','2']){await step(c.code+':sign-submit:'+slot,()=>module('sign-submit',c.sales,c.code,slot));await step(c.code+':sign-verify:'+slot,()=>module('sign-verify',c.code,slot,'VERIFIED'));}
  await step(c.code+':archive',()=>module('sign-archive','case_admin01',c.code));await step(c.code+':execution',()=>module('execution',c.sales,c.code));
  await step(c.code+':payment-proof',()=>module('upload-material',c.sales,c.code,path.join(runtime,'fixtures',c.code+'-NATIVE-G0'+c.sales.slice(-1)+'-SYNTHETIC-payment.pdf')));await step(c.code+':payment',()=>module('payment',c.code,'10000','NATIVE-G0'+c.sales.slice(-1)));
  await step(c.code+':transfer-prepare',()=>module('transfer',c.sales,c.code,'prepare'));for(const stage of ['review','intake','classify'])await step(c.code+':transfer-'+stage,()=>module('transfer','case_admin01',c.code,stage));
 }else throw Error('Explicit bounded phase required');
}
