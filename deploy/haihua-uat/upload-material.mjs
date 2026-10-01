import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save,runtime } from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const [sales,code,file,mode,previousFile]=process.argv.slice(2);if(!file)throw Error('explicit synthetic file required');if(mode&&mode!=='revise')throw Error('Explicit material revision only');const tag=code+'-'+path.basename(file);const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
try{
 ({page}=await login(browser,sales));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 page.on('response',async r=>{const u=new URL(r.url());if(u.pathname.includes('/materials')&&(r.headers()['content-type']??'').includes('json')){const data=await r.json();if(r.request().method()!=='GET'){writes.push({path:u.pathname,status:r.status(),data});save(tag+'-upload-receipts.json',writes);}else if(u.pathname.endsWith('/materials'))save(code+'-material-context.json',{path:u.pathname,status:r.status(),data});}});
 await openOpportunity(page,code);
 await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();
 await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();if(mode==='revise'){if(!previousFile)throw Error('Exact previous file required');await page.locator('.compact-confirm').filter({has:page.getByRole('heading',{name:previousFile,exact:true})}).getByRole('button',{name:'补交新版',exact:true}).click();await page.getByRole('heading',{name:'补交材料新版',exact:true}).waitFor();}else await page.getByRole('button',{name:'接收本次材料',exact:true}).click();
 await page.getByLabel(/^材料用途/).selectOption('CONTRACT_BUSINESS');
 await page.getByLabel('选择文件',{exact:true}).setInputFiles(path.resolve(file));
 await page.getByRole('button',{name:'上传并核对',exact:true}).click();
 await page.getByRole('button',{name:'查看检查结果',exact:true}).waitFor();await page.getByRole('button',{name:'查看检查结果',exact:true}).click();
 await page.getByRole('heading',{name:'核对本次材料接收',exact:true}).waitFor();await page.getByRole('button',{name:'确认接收材料',exact:true}).click();
 await page.getByRole('heading',{name:'材料已接收',exact:true}).waitFor();await page.screenshot({path:path.join(evidence,tag+'-received.png'),fullPage:true});
 await page.getByRole('button',{name:'查看材料清单',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();console.log(tag+' accepted through actual material UI/scanner');
}catch(e){if(page){save(tag+'-upload-failure.txt',await page.locator('body').innerText());await page.screenshot({path:path.join(evidence,tag+'-upload-failure.png'),fullPage:true});}throw e;}finally{await browser.close();}
