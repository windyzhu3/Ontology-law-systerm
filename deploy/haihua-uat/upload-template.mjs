import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save,runtime } from './browser.mjs';
const code='HH-SETUP-20261001';const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
try{
 ({page}=await login(browser,'sales01'));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 page.on('response',async r=>{const u=new URL(r.url());if(u.pathname.includes('/materials')&&(r.headers()['content-type']??'').includes('json')){const data=await r.json();if(r.request().method()!=='GET'){writes.push({path:u.pathname,status:r.status(),data});save('template-upload-receipts.json',writes);}else if(u.pathname.endsWith('/materials'))save('template-material-context.json',{path:u.pathname,status:r.status(),data});}});
 await page.getByRole('button',{name:'业务管理',exact:true}).click();await page.getByRole('button',{name:'商机台账',exact:true}).click();
 await page.getByRole('heading',{name:'商机台账',exact:true}).waitFor();await page.getByLabel('搜索客户',{exact:true}).fill(code);
 await page.getByRole('button',{name:code+' 合成客户',exact:true}).click();
 await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();
 await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();await page.getByRole('button',{name:'接收本次材料',exact:true}).click();
 await page.getByLabel(/^材料用途/).selectOption('CONTRACT_BUSINESS');
 await page.getByLabel('选择文件',{exact:true}).setInputFiles(path.resolve('.local/t09/synthetic-fillable-template.pdf'));
 await page.getByRole('button',{name:'上传并核对',exact:true}).click();
 await page.getByRole('button',{name:'查看检查结果',exact:true}).waitFor();await page.getByRole('button',{name:'查看检查结果',exact:true}).click();
 await page.getByRole('heading',{name:'核对本次材料接收',exact:true}).waitFor();await page.getByRole('button',{name:'确认接收材料',exact:true}).click();
 await page.getByRole('heading',{name:'材料已接收',exact:true}).waitFor();await page.screenshot({path:path.join(evidence,code+'-template-received.png'),fullPage:true});
 await page.getByRole('button',{name:'查看材料清单',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();console.log('Synthetic source template accepted through actual material UI and scanner');
}catch(e){if(page){save('template-upload-failure.txt',await page.locator('body').innerText());await page.screenshot({path:path.join(evidence,'template-upload-failure.png'),fullPage:true});}throw e;}finally{await browser.close();}
