import {guardedRouteContinue,guardedRouteFetch} from './browser.mjs';
import { chromium } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {login,runtime,save,evidence} from './browser.mjs';
const account=process.argv[2]??'sales01';if(!['sales01','sales04'].includes(account))throw Error('Approved B04 account required');const tag=account==='sales04'?'HH-B04-S2':'HH-B04';
const fixture=path.join(runtime,tag+'-import.csv');
if(fs.existsSync(path.join(runtime,tag+'-import-PASS.json')))throw Error('Preserve completed import; no replay');
const headers=['来源记录号','客户名称','联系人','手机号','需求描述'];
const rows=[[tag+'-A',tag+'-A 合成客户','合成甲','+8613900040001','合成咨询，不用于实际委托'],[tag+'-B',tag+'-B 合成客户','合成乙','+8613900040002','合成咨询，不用于实际委托'],[tag+'-C',tag+'-C 合成客户','合成丙','13900040003','合成咨询'],[tag+'-D',tag+'-D 合成客户','合成丁','+8613900040004',''],[tag+'-DUP','重复一','合成戊','+8613900040005','合成咨询'],[tag+'-DUP','重复二','合成己','+8613900040006','合成咨询']];
fs.writeFileSync(fixture,'\ufeff'+[headers,...rows].map(r=>r.join(',')).join('\r\n'));
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
const writes=[];let dropped=false;
try{
 ({page}=await login(browser,account));
 page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/v1/leads')writes.push({commandId:r.headers()['idempotency-key'],body:r.postDataJSON()});});
 await page.getByRole('button',{name:'录入线索',exact:true}).click();
 await page.getByRole('button',{name:'批量导入',exact:true}).click();
 await page.getByLabel('来源',{exact:true}).selectOption(account==='sales04'?'HH_S2_MANUAL':'HH_S1_MANUAL');
 await page.getByLabel('选择文件',{exact:true}).setInputFiles(fixture);
 await page.getByText('可导入 2 条 · 需修正 4 条',{exact:true}).waitFor();
 assert.equal(writes.length,0);
 save(tag+'-preview.txt',await page.locator('body').innerText());
 await page.screenshot({path:path.join(evidence,tag+'-preview.png'),fullPage:true});
 await page.route('**/api/v1/leads',async route=>{
  if(route.request().method()!=='POST'||dropped){await guardedRouteContinue(route);return;}
  dropped=true;
  let response;try{response=await guardedRouteFetch(route);}catch{throw Error('Trusted isolated server request failed before deliberate response loss');}
  const receipt=await response.json();save(tag+'-original-committed.json',{status:response.status(),commandId:route.request().headers()['idempotency-key'],receipt});
  assert.equal(receipt.outcome,'SUCCEEDED');await route.abort('failed');
 });
 await page.getByRole('button',{name:'确认导入 2 条',exact:true}).click();
 await page.getByRole('button',{name:'核对提交结果',exact:true}).waitFor();
 assert.equal(writes.length,1);
 save(tag+'-unknown.txt',await page.locator('body').innerText());
 await page.getByRole('button',{name:'核对提交结果',exact:true}).click();
 await page.getByRole('button',{name:'继续提交剩余行',exact:true}).waitFor();
 assert.equal(writes.length,1);
 await page.getByRole('button',{name:'继续提交剩余行',exact:true}).click();
 await page.getByText('2条已录入',{exact:true}).waitFor();
 assert.equal(writes.length,2);assert.notEqual(writes[0].commandId,writes[1].commandId);
 save(tag+'-import-PASS.json',{previewWrites:0,unknownBlockedFurtherWrites:true,recoveryReplayed:false,actualPostCount:writes.length,writes});
 save(tag+'-completed.txt',await page.locator('body').innerText());
 await page.screenshot({path:path.join(evidence,tag+'-completed.png'),fullPage:true});
 console.log('B04 CSV preview, invalid/duplicate rows, committed-response loss and original receipt recovery PASS');
}catch(e){if(page)save(tag+'-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
