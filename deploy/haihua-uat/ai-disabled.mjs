import {chromium} from '@playwright/test';import {login,save} from './browser.mjs';
const opportunities=['01a0f596-49aa-7959-abff-eabc165df804','01a0f597-813f-71c4-85c5-a71878653a02','01a0f597-cc49-78a2-a847-9c08e5fbc0f9','01a0f599-207c-78a3-a0ab-80dc7cc6c68e','01a0f599-92a3-74fc-8139-4f90bf1aacfc'];
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{for(let i=0;i<5;i++){const actor='sales0'+(i+1),{page,context,apiHeaders}=await login(browser,actor);await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});for(const task of ['FIELDS','SUMMARY','MATERIALS']){
 const r=await page.evaluate(async({path,headers})=>{const response=await fetch(path,{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:'{}',cache:'no-store'});return {status:response.status,data:await response.json(),cacheControl:response.headers.get('Cache-Control')};},{path:'/api/v1/opportunities/'+opportunities[i]+'/ai-candidates/'+task,headers:apiHeaders()});
 // R1ApiServices exposes generic public errors: disabled ->503; empty confirmed progress ->400.
 const pass=(task==='SUMMARY'?r.status===400&&r.data.code==='VALIDATION_FAILED':r.status===503&&r.data.code==='SERVICE_UNAVAILABLE')&&r.cacheControl==='no-store';results.push({actor,task,case:'G0'+(i+1),expected:task==='SUMMARY'?'No confirmed progress basis; do not invent a summary':'Provider unconfigured; safe degradation',...r,result:pass?'PASS':'FAIL',provider:'NOT_RUN - no external configuration'});save('ai-disabled-results.json',results);console.log(actor+' '+task+' disabled boundary '+(pass?'PASS':'FAIL')+' '+r.status+' '+r.data.code);
 }await context.close();}}finally{await browser.close();}
if(results.some(r=>r.result==='FAIL'))process.exitCode=1;
