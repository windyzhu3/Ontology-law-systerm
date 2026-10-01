import {chromium} from '@playwright/test';import {login,save,origin,runtime} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Independent performance instance required');
const browser=await chromium.launch({channel:'chrome',headless:true});
try{const {page,apiHeaders}=await login(browser,process.argv[2]??'sales01');const r=await page.evaluate(async({url,headers})=>{const r=await fetch(url,{headers,cache:'no-store'});return {status:r.status,data:await r.json()};},{url:origin+(process.argv[3]??'/api/v1/workcards/current'),headers:apiHeaders()});save('perf-current-probe.json',r);console.log(JSON.stringify(r.data.currentCard??r.data));}finally{await browser.close();}
