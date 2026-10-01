import {chromium} from '@playwright/test';import assert from 'node:assert/strict';import {login,save,runtime} from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
if(!runtime.endsWith('haihua-uat-runtime'))throw Error('Original UAT only');
const b=await chromium.launch({channel:'chrome',headless:true});let page;let posts=0;const results=[];
try{({page}=await login(b,'sales01'));await openOpportunity(page,'HH-B23-20261001-R2');await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();await page.getByRole('button',{name:'接收本次材料',exact:true}).click();
 page.on('request',r=>{if(['POST','PUT'].includes(r.method())&&new URL(r.url()).pathname.includes('/materials'))posts++;});
 for(const [name,mimeType,buffer] of [['unsupported.txt','text/plain',Buffer.from('Synthetic test only')],['oversize.pdf','application/pdf',Buffer.alloc(20971521,32)],['empty.pdf','application/pdf',Buffer.alloc(0)]]){
 await page.getByLabel('选择文件',{exact:true}).setInputFiles({name,mimeType,buffer});await page.getByRole('button',{name:'上传并核对',exact:true}).click();await page.getByText('请选择不超过 20 MB 的 PDF、JPG 或 PNG 文件。',{exact:true}).waitFor();assert.equal(posts,0);results.push({name,size:buffer.length,status:'REJECTED_BEFORE_WRITE',posts});
 }save('B14-selection-negative-PASS.json',{status:'PASS',scope:'Actual UI unsupported extension, >20MiB and empty; no write. Scanner-negative/version separate.',results});console.log('Actual material three invalid selections PASS, zero POST/PUT');
}catch(e){if(page)save('B14-selection-negative-failure.txt',await page.locator('body').innerText());throw e;}finally{await b.close();}
