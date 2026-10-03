import {chromium} from '@playwright/test';import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import {createHash} from 'node:crypto';import {login,save,runtime} from './browser.mjs';
const mode=process.argv[2];assert.ok(['before','after'].includes(mode));assert.ok(runtime.endsWith('haihua-uat-runtime'));
const oid='01a0f6dd-ca59-782f-9077-6e2cc509e8c7',code='HH-B23-20261001-R2',base='https://localhost:20545/api/v1/opportunities/'+oid+'/materials';
const b=await chromium.launch({channel:'chrome',headless:true});
try{
 const a=await login(b,'sales01'),r=await a.page.request.get(base,{headers:a.apiHeaders()}),c=await r.json();assert.equal(r.status(),200);assert.equal(c.opportunity.id,oid);save('B14-material-'+mode+'-context.json',c);
 if(mode==='before'){
  assert.equal(c.versions.length,0);assert.ok(c.pendingUploads.some(u=>u.fileName===code+'-SYNTHETIC-client-identity.pdf'&&u.state==='SCAN_UNAVAILABLE'));
 }else{
  const v1=c.versions.find(v=>v.fileName===code+'-SYNTHETIC-client-identity.pdf'),v2=c.versions.find(v=>v.fileName===code+'-SYNTHETIC-client-identity-v2.pdf');
  assert.ok(v1&&v2);assert.equal(v1.previousVersionId,null);assert.equal(v2.previousVersionId,v1.selector.id);assert.equal(v2.itemId,v1.itemId);assert.equal(c.versions.length,2);
  for(const v of [v1,v2]){const body=await a.page.request.get(base+'/versions/'+v.selector.id+'/content?disposition=attachment',{headers:a.apiHeaders()});assert.equal(body.status(),200);const sha=x=>createHash('sha256').update(x).digest('hex');assert.equal(sha(await body.body()),sha(fs.readFileSync(path.join(runtime,'fixtures',v.fileName))));}
 }
 save('B14-material-'+mode+'-PASS.json',{status:'PASS',receivedVersions:c.versions.length,scope:mode==='before'?'Unavailable scan created no received version; exact pending upload remains unavailable':'Second version points to first; same item, both accurate original byte downloads preserved'});console.log('B14 actual authorized material '+mode+' read PASS');
}finally{await b.close();}
