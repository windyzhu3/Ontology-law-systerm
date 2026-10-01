import {chromium} from '@playwright/test';
import {login,save} from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const [actor,code,purpose='']=process.argv.slice(2);const browser=await chromium.launch({channel:'chrome',headless:true});
try{const {page}=await login(browser,actor);page.setDefaultTimeout(60000);await selectTask(page,code,purpose);await page.getByText('正在读取转案资料…',{exact:true}).waitFor({state:'hidden'});await page.getByText('正在读取有权合同事项…',{exact:true}).waitFor({state:'hidden'});const text=await page.locator('body').innerText();save(code+'-'+actor+'-task.txt',text);console.log(text);console.log(JSON.stringify(await page.locator('select').evaluateAll(es=>es.map(e=>({label:e.closest('label')?.innerText,options:[...e.options].map(o=>({label:o.text,value:o.value}))})))));}finally{await browser.close();}
