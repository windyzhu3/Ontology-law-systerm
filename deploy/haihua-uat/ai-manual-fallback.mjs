import {chromium} from '@playwright/test';import assert from 'node:assert/strict';
import {login,save} from './browser.mjs';import {selectTask,openOpportunity} from './business-browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});const results=[];
try{for(const task of ['SUMMARY','FIELDS','MATERIALS']){
 const {page,context}=await login(browser,'sales01');let businessWrites=0;const aiRequests=[];
 page.on('request',r=>{const p=new URL(r.url()).pathname;if(['POST','PUT','PATCH'].includes(r.method())&&p.startsWith('/api/')&&!p.includes('ai-candidates'))businessWrites++;});
 page.on('response',async r=>{if(new URL(r.url()).pathname.includes('ai-candidates')&&r.request().method()==='POST')aiRequests.push({path:new URL(r.url()).pathname,status:r.status(),code:(await r.json()).code});});
 let input;
 if(task==='SUMMARY'){await selectTask(page,'HH-SETUP-20261001','推进');input=page.getByLabel('进展摘要 *',{exact:true});}
 if(task==='FIELDS'){await selectTask(page,'HH-SETUP-20261001','推进');await page.getByText('其他业务处理',{exact:true}).click();await page.getByRole('button',{name:'维护客户与需求',exact:true}).click();await page.getByRole('heading',{name:'核对客户与需求',exact:true}).waitFor();input=page.getByLabel(/^客户目标/);}
 if(task==='MATERIALS'){await openOpportunity(page,'HH-SETUP-20261001');await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();}
 const text='纯合成人工内容：AI 不可用时保留原表单，不提交业务。';if(input)await input.fill(text);
 await page.getByRole('button',{name:task==='SUMMARY'?'生成跟进摘要':task==='FIELDS'?'生成字段建议':'生成材料缺项提示',exact:true}).click();
 await page.getByText('暂时无法生成或核对建议。已填写内容保留，可以继续手工办理。',{exact:true}).waitFor();
 if(input)assert.equal(await input.inputValue(),text);assert.equal(businessWrites,0);
 results.push({task,manualContentPreserved:!!input,businessWrites,aiRequests});save('ai-actual-manual-fallback.json',results);save('ai-actual-'+task+'-manual-fallback.txt',await page.locator('body').innerText());await context.close();console.log(task+' actual unavailable/no-basis AI leaves manual flow and business facts unchanged PASS');
}}finally{await browser.close();}
