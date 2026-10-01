import { chromium } from '@playwright/test';import path from 'node:path';import fs from 'node:fs';
import { login,evidence,save,runtime } from './browser.mjs';import {selectTask} from './business-browser.mjs';
const [sales,code,mode]=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
const template=JSON.parse(fs.readFileSync(path.join(runtime,'synthetic-template.json'),'utf8'));
try{({page}=await login(browser,sales));page.setDefaultTimeout(60000);await selectTask(page,code,mode==='revise'?'修订':'准备委托合同');
 const trace=[];page.on('request',r=>{if(r.url().includes('/api/')){trace.push({event:'request',method:r.method(),path:new URL(r.url()).pathname,at:new Date().toISOString()});save(code+'-contract-network.json',trace);}});page.on('response',r=>{if(r.url().includes('/api/')){trace.push({event:'response',status:r.status(),path:new URL(r.url()).pathname,at:new Date().toISOString()});save(code+'-contract-network.json',trace);}});
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/contracts/')&&(r.headers()['content-type']??'').includes('json')){const data=await r.json();if(!r.url().endsWith('/generate')){writes.push({path:new URL(r.url()).pathname,status:r.status(),receipt:data});save(code+'-contract-form-receipts.json',writes);}else save(code+'-generation-metadata.json',{status:r.status(),sha256:data.bodySha256,opportunityId:data.opportunityId});}});
 await page.getByRole('button',{name:/^(接续合同准备|生成合同正文)$/}).first().waitFor();
 if(await page.getByRole('button',{name:'接续合同准备',exact:true}).isVisible()){const started=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/contracts/start'));await page.getByRole('button',{name:'接续合同准备',exact:true}).click();const sr=await started;if(sr.status()!==200)throw Error('contract start rejected');}
 await page.getByRole('button',{name:'生成合同正文',exact:true}).waitFor();
 if(mode==='revise'){await page.getByLabel(/^服务范围/).fill('合同纠纷法律咨询及人工明确约定范围的代理，合成验收不用于实际签约。');await page.getByLabel('收费项目 1',{exact:true}).fill('合成咨询服务费');await page.getByLabel('金额（元）1',{exact:true}).fill('10000.00');await page.getByLabel(/^付款安排/).fill('合同生效后按约定支付10000元，本合同不要求到账后才转案。');}
 await page.getByLabel(/^合同约定的转案收款条件/).selectOption(code.includes('G02')||code.includes('G04')||code==='HH-B19-20261001-R2'?'required':'not-required');
 if(code.includes('G02')||code.includes('G04')||code==='HH-B19-20261001-R2')await page.getByLabel(/^转案前应到账金额/).fill('10000.00');
 await page.getByLabel(/^签署人权限、签字、盖章与归档要求/).fill('双方有权代表签字并盖章，核对权限依据及完整合同归档。合成样本不用于实际签约。');
 await page.getByLabel(/^已审核模板/).selectOption(template.templateId);
 await page.getByRole('button',{name:'生成合同正文',exact:true}).click();await page.getByRole('heading',{name:'核对本次生成结果',exact:true}).waitFor();
 const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:'下载生成正文',exact:true}).click();const download=await downloadPromise;const bodyPath=path.join(runtime,'fixtures',code+'-generated-approved-body.pdf');if(['revise','re-review'].includes(mode)&&fs.existsSync(bodyPath))fs.renameSync(bodyPath,path.join(runtime,'fixtures',code+'-returned-v1-body.pdf'));await download.saveAs(bodyPath);
 await page.getByLabel('我已核对正文、主体、费用、付款及签署要求',{exact:true}).check();
 const formed=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/contracts/form'),{timeout:120000});
 await page.getByRole('button',{name:'确认并形成合同版本',exact:true}).click();const f=await formed,receipt=await f.json();if(f.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('form rejected '+f.status());
 await page.getByRole('button',{name:'提交签约前审查',exact:true}).waitFor();
 const reviewed=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/contracts/review-requests'));
 await page.getByRole('button',{name:'提交签约前审查',exact:true}).click();const rr=await reviewed,reviewReceipt=await rr.json();if(rr.status()!==200||reviewReceipt.outcome!=='SUCCEEDED')throw Error('review request rejected');
 await page.getByText('正在核对提交结果…',{exact:true}).waitFor({state:'hidden'});await page.screenshot({path:path.join(evidence,code+'-contract-await-review.png'),fullPage:true});console.log(code+' generated exact contract/scan/form/review request UI PASS');
}catch(e){if(page){save(code+'-contract-form-failure.txt',await page.locator('body').innerText());await page.screenshot({path:path.join(evidence,code+'-contract-form-failure.png'),fullPage:true});}throw e;}finally{await browser.close();}
