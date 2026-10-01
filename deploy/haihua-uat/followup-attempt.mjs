import {chromium} from '@playwright/test';import fs from 'node:fs';import path from 'node:path';import {login,save,runtime} from './browser.mjs';import {selectTask} from './business-browser.mjs';
const restart=process.argv[2]==='restart';
const suffix=restart?'-restart':'';
if(fs.existsSync(path.join(runtime,'HH-B09-20261001'+suffix+'-attempt-receipt.json')))throw Error('Original attempt receipt preserved; no replay');
const code='HH-B09-20261001',browser=await chromium.launch({channel:'chrome',headless:true});let page;
try{({page}=await login(browser,'sales03'));await selectTask(page,code,'推进');await page.getByText('其他业务处理',{exact:true}).click();await page.getByRole('button',{name:'记录联系尝试，尚无有效进展',exact:true}).click();await page.getByRole('heading',{name:'记录本次联系尝试',exact:true}).first().waitFor();
 await page.getByLabel(/^本次情况/).selectOption('NO_EFFECTIVE_PROGRESS');
 const dates=await page.evaluate(()=>{const local=d=>d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+'T'+[d.getHours(),d.getMinutes()].map(n=>String(n).padStart(2,'0')).join(':');return {at:local(new Date(Date.now()-60000)),next:local(new Date(Date.now()+180000))};});
 await page.getByLabel('实际联系时间',{exact:true}).fill(dates.at);await page.getByLabel('下次联系时间',{exact:true}).fill(dates.next);await page.getByLabel('本次尝试与后续安排',{exact:true}).fill('合成客户暂无明确进展，本次不能计为有效进展，约定三分钟后再次联系；保留原责任和期限。');
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.includes('followup-attempt'));
 await page.getByRole('button',{name:'记录尝试并安排下次联系',exact:true}).click();const r=await response,receipt=await r.json();save(code+suffix+'-attempt-receipt.json',{actor:'sales03',status:r.status(),receipt,dates});if(r.status()!==200||receipt.outcome!=='SUCCEEDED')throw Error('attempt rejected');await page.getByRole('heading',{name:'本次安排已记录',exact:true}).waitFor();save(code+suffix+'-attempt-waiting.txt',await page.locator('body').innerText());console.log(code+' near-future attempt recorded, browser closing; actual Worker recovery pending');
}catch(e){if(page)save(code+suffix+'-attempt-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
