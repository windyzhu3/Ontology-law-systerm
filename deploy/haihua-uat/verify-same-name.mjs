import {chromium} from '@playwright/test';
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {login,save,runtime,origin} from './browser.mjs';
if(!runtime.endsWith('haihua-restore-perf_e1'))throw Error('Recorded branch belongs to PERF_E1');
const receipts=JSON.parse(fs.readFileSync(path.join(runtime,'HH-B03-20261001-customer-receipts.json'),'utf8'));
const matches=JSON.parse(fs.readFileSync(path.join(runtime,'HH-B03-20261001-party-search.json'),'utf8'));
const endpoint=receipts.find(x=>x.path.endsWith('/confirm')).path.replace(/\/confirm$/,'');
const browser=await chromium.launch({channel:'chrome',headless:true});
try{const actor=await login(browser,'sales03');const result=await actor.page.evaluate(async({url,headers})=>{const r=await fetch(url,{headers,cache:'no-store'});return {status:r.status,data:await r.json()};},{url:origin+endpoint,headers:actor.apiHeaders()});assert.equal(result.status,200);save('HH-B03-confirmed-context.json',result.data);const body=JSON.stringify(result.data.confirmation);assert.ok(body.includes('HH-PERF-0003 CLIENT 合成组织'));assert.ok(!body.includes(matches.items[0].selector.id));assert.ok(result.data.confirmation);save('HH-B03-same-name-verification.json',{status:'PASS',actor:'sales03',existingPartyId:matches.items[0].selector.id,confirmation:result.data.confirmation});console.log('B03 authorized confirmation retains distinct same-name party PASS');}finally{await browser.close();}
