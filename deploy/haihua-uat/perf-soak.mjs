import {chromium} from '@playwright/test';import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import {execFileSync} from 'node:child_process';
import {login,save,runtime,origin} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Separate performance instance required');
const background=JSON.parse(fs.readFileSync(path.join(runtime,'performance-background.json'),'utf8'));
assert.equal(background.cases.length,100);assert.equal(new Set(background.cases.map(c=>c.contractId)).size,100);
if(fs.existsSync(path.join(runtime,'performance-soak.json')))throw Error('Preserve existing performance run');
const browser=await chromium.launch({channel:'chrome',headless:true});let actors=[];
const results={backgroundContracts:100,backgroundStage:'PREPARE_CONTRACT',startedAt:new Date().toISOString(),durationTargetMinutes:60,concurrency:5,samples:[],pageSamples:[],resourceFailures:[],sessionFailures:[]};
async function sessions(){for(const actor of actors)await actor.context.close();actors=[];for(let i=1;i<=5;i++){const username='sales0'+i;let admitted=false;for(let attempt=1;attempt<=3;attempt++){try{actors.push({...await login(browser,username),username});admitted=true;break;}catch(e){results.sessionFailures.push({username,attempt,at:new Date().toISOString(),message:e.message});save('performance-soak.json',results);}}if(!admitted)throw Error(username+' unavailable after three recorded actual login attempts');}}
async function sample(actor,endpoint){const started=performance.now();const r=await actor.page.evaluate(async({url,headers})=>{try{const r=await fetch(url,{headers,cache:'no-store',signal:AbortSignal.timeout(15000)});const data=await r.json();return {status:r.status,rows:data.items?.length,taskCount:data.myTasks?.length,error:r.ok?null:data.code};}catch{return {status:0,error:'TRANSPORT_UNKNOWN'};}},{url:origin+endpoint,headers:actor.apiHeaders()});results.samples.push({actor:actor.username,endpoint,ms:performance.now()-started,at:new Date().toISOString(),...r});}
function resources(){try{execFileSync('D:/soft/python3/python.exe',['-X','utf8','deploy/haihua-uat/perf-resources.py'],{windowsHide:true,stdio:'pipe'});}catch{results.resourceFailures.push(new Date().toISOString());}}
try{
 await sessions();resources();
 for(let round=0;round<20;round++){await Promise.all(actors.map(a=>sample(a,'/api/v1/workcards/current')));save('performance-soak.json',results);}
 for(let round=0;round<20;round++){await Promise.all(actors.map(a=>sample(a,'/api/v1/contracts?limit=20')));save('performance-soak.json',results);}
 const began=Date.now();results.soakStartedAt=new Date(began).toISOString();
 for(let minute=0;minute<60;minute++){
  if(minute>0&&minute%2===0)await sessions();
  await Promise.all(actors.map(async a=>{await sample(a,'/api/v1/workcards/current');await sample(a,'/api/v1/contracts?limit=20');}));
  if(minute%5===0)resources();results.elapsedSoakMs=Date.now()-began;save('performance-soak.json',results);console.log('Actual controlled soak '+minute+'/60 minutes; '+results.samples.length+' requests');
  const remaining=began+(minute+1)*60000-Date.now();if(remaining>0)await new Promise(resolve=>setTimeout(resolve,remaining));
 }
 results.completedAt=new Date().toISOString();results.elapsedSoakMs=Date.now()-began;resources();save('performance-soak.json',results);console.log('60-minute controlled soak completed; evaluate all samples and failures');
}catch(e){results.failure=e.message;save('performance-soak.json',results);throw e;}finally{await browser.close();}
