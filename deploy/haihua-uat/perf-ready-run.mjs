import fs from 'node:fs';import path from 'node:path';import {execFile} from 'node:child_process';import {promisify} from 'node:util';
import {runtime,save} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Separate performance instance required');
const run=promisify(execFile);
for(;;){
 const file=path.join(runtime,'performance-background.json');
 const state=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):null;
 const integration=fs.readFileSync('.superpowers/haihua-uat-runtime/P3-workflow-boundaries.log','utf8');
 if(state?.cases.length===100&&/BUILD (?:SUCCESS|FAILURE)/.test(integration)&&fs.existsSync(path.join(runtime,'HH005-deployment.json')))break;
 await new Promise(resolve=>setTimeout(resolve,10000));
}
const sql="select json_build_object('contracts',(select count(*) from contract.contract),'openTasks',(select count(*) from responsibility.task_occurrence where state='OPEN'),'waitingTasks',(select count(*) from responsibility.task_occurrence where state='WAITING'))";
const footprint=await run('docker',['exec','ontology-law-haihua-restore-perf_e1-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-c',sql],{windowsHide:true});
const counts=JSON.parse(footprint.stdout);if(counts.contracts!==100||counts.openTasks<20)throw Error('Actual database scale does not meet approved background');save('performance-footprint.json',counts);
for(const name of ['perf-page-readiness.mjs','perf-soak.mjs']){
 try{const r=await run(process.execPath,['deploy/haihua-uat/'+name],{windowsHide:true,maxBuffer:10*1024*1024});save(name+'.stdout.txt',r.stdout);}
 catch(e){save(name+'.failure.txt',String(e.stderr??e.message));if(name==='perf-soak.mjs')throw Error('Controlled soak failed; diagnostics preserved');}
}
console.log('Independent performance run ended; evaluate raw measurements and any failures');
