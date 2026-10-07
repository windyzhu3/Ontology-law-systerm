import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
export const runtime=path.resolve(process.env.OLS_NATIVE_BUSINESS_RUNTIME??'');
assert.equal(fs.realpathSync(runtime),runtime);
const canonical=v=>v===null||typeof v!=='object'?JSON.stringify(v):Array.isArray(v)?'['+v.map(canonical).join(',')+']':'{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';
export function sealed(name){const value=JSON.parse(fs.readFileSync(path.join(runtime,name)));assert.equal(value.mac,crypto.createHmac('sha256',fs.readFileSync(path.join(runtime,'journal.key'))).update(canonical(value.payload)).digest('hex'));return value.payload;}
const owner=JSON.parse(fs.readFileSync(path.join(runtime,'instance.json')));assert.equal(owner.runtime,runtime);
const plan=sealed('identity/plan.json');
export const origin=plan.origin;
export const evidence=path.join(runtime,'browser-evidence');fs.mkdirSync(evidence,{recursive:true,mode:0o700});
export const credentials=JSON.parse(fs.readFileSync(path.join(runtime,'browser-credentials.json')));
export function save(name,value){assert(!name.includes('/')&&!name.includes('..'));fs.writeFileSync(path.join(runtime,name),typeof value==='string'?value:JSON.stringify(value,null,2),{mode:0o600});}
export function active(){assert.equal(sealed('resources.json').verification,true);const fixture=sealed('verification/business-authorization.json');assert.equal(fixture.instanceId,owner.instanceId);assert.equal(fixture.runtime,runtime);const id=sealed('current-operation.json').operationId;assert.equal(sealed('operations/'+id+'.json').phase,'COMPLETE');}
const mutating=m=>['POST','PUT','PATCH','DELETE'].includes(m);
export const guardedRouteFetch=async(route,options)=>{active();return route.fetch(options);};
export const guardedRouteContinue=async(route,options)=>{active();return route.continue(options);};
export const guardedApiPost=async(api,url,options)=>{active();return api.post(url,options);};
process.on('uncaughtException',error=>{save('safe-browser-failure-'+Date.now()+'.json',{name:error.name,message:String(error.message).split('\n')[0],locations:String(error.stack).split('\n').filter(x=>x.trim().startsWith('at '))});console.error('Native browser step failed; private evidence retained');process.exit(1);});
export async function login(browser,alias){
 active();const c=credentials[alias];assert(c);const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
 await context.route('**/api/**',async route=>{const r=route.request();if(mutating(r.method())){active();const h=r.headers(),id=h['idempotency-key'];if(id){assert.match(id,/^[a-f0-9-]{36}$/);const file=path.join(runtime,'browser-commands',id+'.json');fs.mkdirSync(path.dirname(file),{mode:0o700,recursive:true});const value={actor:alias,appointmentId:c.appointmentId,method:r.method(),path:new URL(r.url()).pathname,body:r.postData(),ifMatch:h['if-match']??null,ifNoneMatch:h['if-none-match']??null,state:'DISPATCH_UNKNOWN'};if(fs.existsSync(file)){const old=JSON.parse(fs.readFileSync(file));for(const key of ['actor','appointmentId','method','path','body','ifMatch','ifNoneMatch'])assert.deepEqual(old[key],value[key]);}else{const fd=fs.openSync(file,'wx',0o600);try{fs.writeFileSync(fd,JSON.stringify(value));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}}}}await route.fallback();});
 const page=await context.newPage();page.setDefaultTimeout(60000);let actorHeaders;
 page.on('response',async response=>{const r=response.request(),id=r.headers()['idempotency-key'];if(id&&new URL(r.url()).pathname.startsWith('/api/')&&mutating(r.method())){const file=path.join(runtime,'browser-commands',id+'.json');if(fs.existsSync(file)){const old=JSON.parse(fs.readFileSync(file));let receipt;try{receipt=await response.json();}catch{return;}fs.writeFileSync(file,JSON.stringify({...old,state:'OBSERVED',status:response.status(),receipt}),{mode:0o600});}}});
 page.on('request',r=>{const h=r.headers();if(new URL(r.url()).pathname.startsWith('/api/')&&h.authorization&&h['x-appointment-id'])actorHeaders={Authorization:h.authorization,'X-Appointment-Id':h['x-appointment-id']};});
 await page.goto(origin+'/workbench');await page.getByRole('button',{name:'登录工作台',exact:true}).click();await page.locator('#username').fill(c.username);await page.locator('#password').fill(c.passwordUpdated?c.password:fs.readFileSync(path.join(runtime,'secrets/initial-password.txt'),'utf8').trim());await page.locator('#kc-login').click();
 if(!c.passwordUpdated){await page.locator('#password-new').waitFor();await page.locator('#password-new').fill(c.password);await page.locator('#password-confirm').fill(c.password);await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();c.passwordUpdated=true;for(const value of Object.values(credentials))if(value.username===c.username)value.passwordUpdated=true;save('browser-credentials.json',credentials);}
 await page.getByRole('heading',{name:'请选择本次办理身份'}).waitFor();const select=page.getByLabel('本人任职',{exact:true});await select.selectOption(c.appointmentId);if(await page.getByRole('button',{name:'确认任职',exact:true}).isVisible())await page.getByRole('button',{name:'确认任职',exact:true}).click();await page.getByRole('button',{name:'确认本次身份',exact:true}).click();await page.getByRole('heading',{name:'请选择本次办理身份'}).waitFor({state:'hidden'});
 save(alias+'-native-pkce.json',{username:c.username,appointmentId:c.appointmentId,issuer:plan.issuer,realBrowser:true});return {page,context,appointment:{value:c.appointmentId},apiHeaders:()=>{assert(actorHeaders);return {...actorHeaders};}};
}
