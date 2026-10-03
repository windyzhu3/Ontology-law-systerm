import { chromium } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';
import { login,evidence,save,runtime } from './browser.mjs';
import { reserveAttempt } from './command-attempt.mjs';
const username=process.argv[2];const code=process.argv[3];
if(!/^sales0[1-5]$/.test(username)||!/^HH-(SETUP|G0[1-5]|B(?:0[1-9]|1[0-9]|2[0-5]))-20261001(?:-R2)?$/.test(code))throw Error('explicit approved account/case required');
const file=path.join(runtime,'business-index.json');const records=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{};
if(records[code])throw Error('Existing captured lead preserved; inspect receipt instead of resubmitting');
const attempt=reserveAttempt(runtime,code+'-capture',{username,code});
const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try {
 ({page}=await login(browser,username));
 await page.getByRole('button',{name:'录入线索',exact:true}).click();
 await page.getByRole('heading',{name:'新增线索',exact:true}).waitFor();
 await page.getByLabel('来源',{exact:true}).selectOption(username==='sales04'||username==='sales05'?'HH_S2_MANUAL':'HH_S1_MANUAL');
 await page.getByLabel('联系人',{exact:true}).fill(code+' 合成联系人');
 const branch=code.match(/HH-B(\d{2})-/)?.[1];
 const phone=branch==='01'?'':branch==='02'?'13900021001':branch?'1390003'+branch.padStart(4,'0'):(code.includes('SETUP')?'1390000':code.endsWith('-R2')?'1390002':'1390001')+({sales01:'1001',sales02:'1002',sales03:'1003',sales04:'1004',sales05:'1005'}[username]);
 await page.getByLabel('手机号',{exact:true}).fill(phone);
 await page.getByLabel('客户名称',{exact:true}).fill(code+' 合成客户');
 await page.getByLabel('需求描述',{exact:true}).fill('纯合成测试，不用于实际委托：核对合同纠纷事实、提供法律咨询及明确约定范围的代理服务。测试样本不用于实际签约。');
 await page.route('**/api/v1/leads',async route=>{
  if(route.request().method()!=='POST'){await route.fallback();return;}
  try{attempt.dispatch(route.request());}catch{await route.abort('blockedbyclient');return;}
  await route.fallback();
 });
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/leads');
 await page.getByRole('button',{name:'保存线索',exact:true}).click();
 const r=await response;const receipt=await r.json();
 attempt.complete(r.status(),receipt);
 records[code]={username,label:code+' 合成客户',status:r.status(),commandId:r.request().headers()['idempotency-key'],receipt};save('business-index.json',records);
 if(r.status()!==201||receipt.outcome!=='SUCCEEDED')throw Error('capture failed: '+r.status());
 await page.getByRole('heading',{name:'后续事项',exact:true}).waitFor();
 await page.getByText('正在读取当前任职可处理的后续事项…',{exact:true}).waitFor({state:'hidden'});
 await page.screenshot({path:path.join(evidence,code+'-capture.png'),fullPage:true});
 save(code+'-capture.txt',await page.locator('body').innerText());
 console.log(code+' lead captured through '+username+' UI');
} catch(e){if(page){save(code+'-failure.txt',await page.locator('body').innerText());await page.screenshot({path:path.join(evidence,code+'-failure.png'),fullPage:true});}throw e;}finally{await browser.close();}
