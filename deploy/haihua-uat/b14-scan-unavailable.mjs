import {chromium} from '@playwright/test';import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import {execFile} from 'node:child_process';import {promisify} from 'node:util';
import {login,save,runtime} from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
assert.ok(runtime.endsWith('haihua-uat-runtime'));
if(['B14-scan-original.json','B14-scan-injection.json'].some(file=>fs.existsSync(path.join(runtime,file))))throw Error('Recorded injection/upload must not be repeated');
const run=promisify(execFile),name='ontology-law-haihua-uat-clamav',code='HH-B23-20261001-R2';
const container=JSON.parse((await run('docker',['inspect',name])).stdout)[0];
assert.equal(container.Name,'/'+name);assert.equal(container.State.Running,true);
assert.ok(Object.values(container.NetworkSettings.Ports).flat().some(p=>p?.HostPort==='20547'));
save('B14-scan-injection.json',{kind:'Owned isolated scanner stop/start only',containerId:container.Id,name,image:container.Image});
const b=await chromium.launch({channel:'chrome',headless:true});let page,stopped=false;const requests=[];
try{
 const a=await login(b,'sales01');page=a.page;await openOpportunity(page,code);
 await page.getByText('业务材料',{exact:true}).click();await page.getByRole('button',{name:'查看与接收材料',exact:true}).click();await page.getByRole('heading',{name:'已接收材料',exact:true}).waitFor();
 page.on('request',r=>{const p=new URL(r.url()).pathname;if(['POST','PUT'].includes(r.method())&&p.includes('/material')){requests.push({method:r.method(),path:p,commandId:r.headers()['idempotency-key']??null});save('B14-scan-original.json',{phase:'REQUESTED',requests});}});
 // Read the existing material context through its normal UI refresh.
 await page.getByRole('button',{name:'接收本次材料',exact:true}).click();
 await page.getByLabel(/^材料用途/).selectOption('CONTRACT_BUSINESS');await page.getByLabel('选择文件',{exact:true}).setInputFiles(path.join(runtime,'fixtures',code+'-SYNTHETIC-client-identity.pdf'));
 await run('docker',['stop','--time','5',container.Id]);stopped=true;
 const writes=[];page.on('response',async r=>{const p=new URL(r.url()).pathname;if(p.includes('/material')&&r.request().method()!=='GET'&&(r.headers()['content-type']??'').includes('json')){writes.push({path:p,status:r.status(),body:await r.json()});save('B14-scan-responses.json',writes);}});
 await page.getByRole('button',{name:'上传并核对',exact:true}).click();
 for(let n=0;n<30;n++){
  if(await page.getByRole('heading',{name:'本次文件未能接收',exact:true}).isVisible())break;
  const poll=page.getByRole('button',{name:'查看检查结果',exact:true});if(await poll.isVisible())await poll.click();await page.waitForTimeout(1000);
 }
 await page.getByRole('heading',{name:'本次文件未能接收',exact:true}).waitFor();await page.getByText('安全检查暂不可用，本次文件未保存；稍后重新选择文件。',{exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'确认接收材料',exact:true}).count(),0);
 assert.equal(requests.some(w=>w.path.endsWith('/materials/accept')),false);
 save('B14-scan-unavailable-UI.txt',await page.locator('body').innerText());save('B14-scan-unavailable-PASS.json',{status:'PASS',kind:'Infrastructure fault injection; actual normal material UI refuses unavailable scanner and never offers acceptance',writes:writes.map(w=>({path:w.path,status:w.status})),scannerRestoreRequired:true});
 console.log('Actual scanner-unavailable UI refuses material acceptance PASS');
}catch(e){if(page)save('B14-scan-unavailable-failure.txt',await page.locator('body').innerText());throw e;}finally{
 if(stopped){await run('docker',['start',container.Id]);save('B14-scan-restored.json',{containerId:container.Id,started:true,healthPending:true});}
 await b.close();
}
