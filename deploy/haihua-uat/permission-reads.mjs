import {chromium} from '@playwright/test';
import {login,save} from './browser.mjs';
const s1='01a0f596-49aa-7959-abff-eabc165df804',s2='01a0f599-207c-78a3-a0ab-80dc7cc6c68e',peer='01a0f597-813f-71c4-85c5-a71878653a02';
const cases=[
 ['sales01','own-opportunity',`/api/v1/opportunities/${s1}`,true],
 ['sales01','peer-opportunity-owner',`/api/v1/opportunities/${peer}`,false],
 ['sales01','cross-department-opportunity',`/api/v1/opportunities/${s2}`,false],
 ['sales01','same-department-contract-read',`/api/v1/opportunities/${peer}/contracts`,true],
 ['sales01','cross-department-contract',`/api/v1/opportunities/${s2}/contracts`,false],
 ['sales01','cross-department-materials',`/api/v1/opportunities/${s2}/materials`,false],
 ['sales01','forged-appointment',`/api/v1/opportunities/${s2}/contracts`,false,'FORGED'],
 ['sales01','classification-write-context',`/api/v1/opportunities/${s1}/transfers/classification-context`,false],
 ['sales_manager01','own-department-opportunity',`/api/v1/opportunities/${s1}`,true],
 ['sales_manager01','cross-department-opportunity',`/api/v1/opportunities/${s2}`,false],
 ['sales_manager02','cross-department-contract',`/api/v1/opportunities/${s1}/contracts`,false],
 ['sys_manager01','admin-no-business-contract',`/api/v1/opportunities/${s1}/contracts`,false],
 ['sys_manager01','admin-no-business-opportunity',`/api/v1/opportunities/${s1}`,false],
 ['case_admin01','authorized-classification-context',`/api/v1/opportunities/${s1}/transfers/classification-context`,true],
 ['finance01','no-classification-context',`/api/v1/opportunities/${s1}/transfers/classification-context`,false],
];
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{for(const actor of [...new Set(cases.map(c=>c[0]))]){
 const {page,context,apiHeaders}=await login(browser,actor);await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 for(const [,name,path,allowed,forged] of cases.filter(c=>c[0]===actor)){
  const headers=apiHeaders();if(forged)headers['X-Appointment-Id']='00000000-0000-4000-8000-000000000001';
  const response=await page.evaluate(async({path,headers})=>{const r=await fetch(path,{headers,cache:'no-store'});return {status:r.status,text:await r.text()};},{path,headers});
  const denied=[400,401,403,404].includes(response.status),leaked=!allowed&&/HH-G0[1-5]|合成委托组织|合成客户/.test(response.text);
  const passed=(allowed?response.status===200:denied)&&!leaked;
  results.push({actor,name,path,expected:allowed?'ALLOW':'DENY',status:response.status,leaked,result:passed?'PASS':'FAIL'});save('permission-read-results.json',results);save('permission-read-'+actor+'-'+name+'.json',response);
  console.log(actor+' '+name+' '+(passed?'PASS':'FAIL')+' HTTP'+response.status);
 }await context.close();
}}finally{await browser.close();}
if(results.some(r=>r.result!=='PASS'))process.exitCode=1;
