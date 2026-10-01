import { chromium } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { assertRuntimeActive } from './runtime-ownership.mjs';
import { createRequestGuards } from './request-guards.mjs';
const directory=process.env.HAIHUA_UAT_RUNTIME??'.superpowers/haihua-uat-runtime';
if(!['.superpowers/haihua-uat-runtime','.superpowers/haihua-restore-e1','.superpowers/haihua-restore-g_completed','.superpowers/haihua-restore-perf_e1'].includes(directory.replaceAll('\\','/')))throw Error('explicit approved isolated runtime only');
export const runtime = path.resolve(directory);
export const {guardedRouteFetch,guardedRouteContinue,guardedApiPost}=createRequestGuards(()=>assertRuntimeActive(runtime));
export const origin = 'https://localhost:20544';
export const credentials = JSON.parse(fs.readFileSync(path.join(runtime,'browser-credentials.json'),'utf8'));
export const evidence = path.join(runtime,'browser-evidence');
fs.mkdirSync(evidence,{recursive:true});
export function save(name,value){ fs.writeFileSync(path.join(runtime,name),typeof value==='string'?value:JSON.stringify(value,null,2)); }
// Playwright network errors can include Authorization in their call log. Never
// forward that raw error to a terminal; retain only safe locations and first line.
process.on('uncaughtException',error=>{const message=String(error?.message??error).split('\n')[0];save('safe-browser-failure-'+Date.now()+'.json',{name:error?.name,message,locations:String(error?.stack??'').split('\n').filter(line=>line.trim().startsWith('at '))});console.error((error?.name??'Error')+': '+message);process.exit(1);});
export async function login(browser,username) {
  assertRuntimeActive(runtime);
  const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
  await context.route('**/api/**', async route => {
    if (['POST','PUT','PATCH','DELETE'].includes(route.request().method())) {
      try { assertRuntimeActive(runtime); } catch { await route.abort('blockedbyclient'); return; }
    }
    await route.fallback();
  });
  const page=await context.newPage();page.setDefaultTimeout(60000);
  if(directory.endsWith('haihua-restore-perf_e1')){
    const traceFile=path.join(runtime,username+'-business-protocol.json');
    const traces=fs.existsSync(traceFile)?JSON.parse(fs.readFileSync(traceFile,'utf8')):[];
    page.on('request',request=>{const url=new URL(request.url());if(url.pathname.startsWith('/api/')&&['POST','PUT'].includes(request.method())){
      let body;try{body=request.postDataJSON();}catch{body=null;}
      traces.push({method:request.method(),path:url.pathname,commandId:request.headers()['idempotency-key'],preconditions:{ifMatch:request.headers()['if-match'],ifNoneMatch:request.headers()['if-none-match']},body});
      fs.writeFileSync(traceFile,JSON.stringify(traces,null,2));
    }});
  }
  const authTrace=[];page.on('response',async r=>{const u=new URL(r.url());if(u.pathname.endsWith('/protocol/openid-connect/token')||u.pathname==='/api/v1/session/context'){const item={path:u.pathname,status:r.status(),at:Date.now()};if(u.pathname.endsWith('/token')&&r.ok()){const payload=await r.json();for(const key of ['id_token','access_token']){const token=payload[key];if(typeof token==='string'){const claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString());item[key]={iss:claims.iss,aud:claims.aud,iat:claims.iat,exp:claims.exp,auth_time:claims.auth_time,noncePresent:!!claims.nonce};}}}authTrace.push(item);save(username+'-auth-safe-trace.json',authTrace);}});
  let actorHeaders=null;page.on('request',r=>{const h=r.headers();if(new URL(r.url()).pathname.startsWith('/api/')&&h.authorization&&h['x-appointment-id'])actorHeaders={Authorization:h.authorization,'X-Appointment-Id':h['x-appointment-id']};});
  await page.goto(origin+(username==='sys_manager01'?'/admin/identity/principals':'/workbench'));
  await page.getByRole('button',{name:'登录工作台',exact:true}).click();
  await page.locator('#username').fill(credentials[username].username);
  await page.locator('#password').fill(credentials[username].password);
  await page.locator('#kc-login').click();
  try{await page.getByRole('heading',{name:'请选择本次办理身份'}).waitFor();}catch(e){save(username+'-login-failure.txt',await page.locator('body').innerText());save(username+'-login-location.json',{origin:new URL(page.url()).origin,path:new URL(page.url()).pathname});throw e;}
  const choices=await page.getByLabel('本人任职',{exact:true}).locator('option').evaluateAll(es=>es.filter(e=>e.value).map(e=>({label:e.textContent,value:e.value})));
  if(choices.length!==1)throw Error(username+' expected one appointment, got '+choices.length);
  await page.getByLabel('本人任职',{exact:true}).selectOption(choices[0].value);
  if(await page.getByRole('button',{name:'确认任职',exact:true}).isVisible()) await page.getByRole('button',{name:'确认任职',exact:true}).click();
  await page.getByRole('button',{name:'确认本次身份',exact:true}).click();
  await page.getByRole('heading',{name:'请选择本次办理身份'}).waitFor({state:'hidden'});
  return {context,page,appointment:choices[0],apiHeaders:()=>{if(!actorHeaders)throw Error('actual selected actor headers not observed');return {...actorHeaders};}};
}
if(process.argv[2]==='inspect') {
  const browser=await chromium.launch({channel:'chrome',headless:true});
  try {
    const {page}=await login(browser,process.argv[3]??'sys_manager01');
    await page.screenshot({path:path.join(evidence,'initial-admin.png'),fullPage:true});
    save('initial-admin.txt',await page.locator('body').innerText());
    console.log((await page.locator('body').innerText()).slice(0,3500));
  } finally {await browser.close();}
}
