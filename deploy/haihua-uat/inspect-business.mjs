import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const [user,code,mode]=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,user));await openOpportunity(page,code);
 if(mode==='quote'){await page.getByText('收费方案与报价',{exact:true}).click();await page.getByRole('button',{name:'查看与办理报价',exact:true}).click();await page.getByText('正在读取本次报价…',{exact:true}).waitFor({state:'hidden'});}
 const text=await page.locator('body').innerText();save(code+'-'+mode+'-inspect.txt',text);console.log(text.slice(0,7000));console.log(JSON.stringify(await page.locator('input,textarea,select').evaluateAll(es=>es.map(e=>({tag:e.tagName,name:e.name,label:e.getAttribute('aria-label'),value:e.value,options:e.tagName==='SELECT'?Array.from(e.options).map(o=>({label:o.text,value:o.value})):undefined})))));await page.screenshot({path:path.join(evidence,code+'-'+mode+'-inspect.png'),fullPage:true});
}finally{await browser.close();}
