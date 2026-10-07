import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import https from 'node:https';

const index=process.argv.indexOf('--runtime');
if(index<0)throw Error('Explicit private verification runtime required');
const root=path.resolve(process.argv[index+1]);
if(fs.realpathSync(root)!==root)throw Error('Linked runtime refused');
const owner=JSON.parse(fs.readFileSync(path.join(root,'instance.json')));
assert.equal(owner.runtime,root);
function sealed(name){const value=JSON.parse(fs.readFileSync(path.join(root,name)));const canonical=v=>
  v===null||typeof v!=='object'?JSON.stringify(v):Array.isArray(v)?'['+v.map(canonical).join(',')+']':'{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';
  const mac=crypto.createHmac('sha256',fs.readFileSync(path.join(root,'journal.key'))).update(canonical(value.payload)).digest('hex');
  assert.equal(mac,value.mac);return value.payload;}
assert.equal(sealed('resources.json').verification,true,'Browser ceremony is isolated verification only');
const plan=sealed('identity/plan.json');
assert.equal(plan.instanceId,owner.instanceId);
const input=JSON.parse(fs.readFileSync(path.join(root,'identity/browser-input.json')));
const initial=fs.readFileSync(input.initialPasswordFile,'utf8').trim();
const save=(name,value)=>fs.writeFileSync(path.join(root,'identity',name),JSON.stringify(value),{mode:0o600});
const ca=fs.readFileSync(path.join(root,fs.existsSync(path.join(root,'certs/http-trust.pem'))?'certs/http-trust.pem':'certs/ca.pem'));
async function request(url,method='GET',body,headers={}){
  return new Promise((resolve,reject)=>{const req=https.request(url,{method,headers,ca,minVersion:'TLSv1.3',rejectUnauthorized:true,timeout:15000},res=>{
    const chunks=[];res.on('data',c=>chunks.push(c));res.on('end',()=>resolve({status:res.statusCode,body:Buffer.concat(chunks).toString('utf8')}));
  });req.on('error',()=>reject(Error('Verified identity HTTPS unavailable')));req.on('timeout',()=>req.destroy());req.end(body);});
}
const discovery=await request(plan.issuer+'/.well-known/openid-configuration');
assert.equal(discovery.status,200);assert.equal(JSON.parse(discovery.body).issuer,plan.issuer);
let callback;
const server=https.createServer({key:fs.readFileSync(path.join(root,'certs/server.key')),cert:fs.readFileSync(path.join(root,'certs/server.crt')),minVersion:'TLSv1.3'},(req,res)=>{
  if(!callback||!req.url.startsWith('/auth/callback?')){res.writeHead(404).end();return;}
  const url=new URL(req.url,plan.origin);res.writeHead(200,{'Content-Type':'text/plain','Cache-Control':'no-store'}).end('Identity verification callback');callback(url);
});
await new Promise(resolve=>server.listen(plan.ports.entry,'0.0.0.0',resolve));
const browser=await chromium.launch({headless:true});
const results=[];
try {
  for(const username of ['dingqiming','huangxuexue']){
    const context=await browser.newContext();const page=await context.newPage();page.setDefaultTimeout(30000);
    const verifier=crypto.randomBytes(32).toString('base64url');const challenge=crypto.createHash('sha256').update(verifier).digest('base64url');
    const state=crypto.randomUUID(),nonce=crypto.randomUUID();let received=false,resolveCode;
    const code=new Promise(resolve=>resolveCode=resolve);
    callback=url=>{assert.equal(url.searchParams.get('state'),state);assert.ok(url.searchParams.get('code'));received=true;resolveCode(url.searchParams.get('code'));};
    const auth=new URL(plan.issuer+'/protocol/openid-connect/auth');
    auth.search=new URLSearchParams({client_id:plan.spaClient,response_type:'code',scope:'openid',redirect_uri:plan.origin+'/auth/callback',
      state,nonce,code_challenge:challenge,code_challenge_method:'S256'}).toString();
    const needsUpdate=input.passwordUpdatesRequired?.[username]??true;
    await page.goto(auth.href);await page.locator('#username').fill(username);await page.locator('#password').fill(needsUpdate?initial:input.newPasswords[username]);await page.locator('#kc-login').click();
    if(needsUpdate){
      await page.locator('#password-new').waitFor();assert.equal(received,false,'Temporary password cannot obtain authorization code before update');
    if(username==='dingqiming'){
      for(const rejected of ['Ab12345',username]){
        await page.locator('#password-new').fill(rejected);await page.locator('#password-confirm').fill(rejected);
        await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();
        await page.locator('#password-new').waitFor();assert.equal(received,false);
        const error=await page.locator('[aria-live],.pf-m-danger,.pf-c-alert__title,.kc-feedback-text,#input-error-password').allTextContents();
        assert.ok(error.join(' ').trim(),'Password rejection must display its reason');
      }
    }
    assert.equal(input.newPasswords[username].length,8);
    await page.locator('#password-new').fill(input.newPasswords[username]);await page.locator('#password-confirm').fill(input.newPasswords[username]);
    await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();
    }
    const authorizationCode=await Promise.race([code,new Promise((_,reject)=>setTimeout(()=>reject(Error('Password update did not complete OIDC callback')),30000))]);
    const token=await request(plan.issuer+'/protocol/openid-connect/token','POST',new URLSearchParams({grant_type:'authorization_code',
      code:authorizationCode,client_id:plan.spaClient,redirect_uri:plan.origin+'/auth/callback',code_verifier:verifier}).toString(),{'Content-Type':'application/x-www-form-urlencoded'});
    assert.equal(token.status,200);let tokens=JSON.parse(token.body);
    const claims=JSON.parse(Buffer.from(tokens.access_token.split('.')[1],'base64url').toString());
    const idClaims=JSON.parse(Buffer.from(tokens.id_token.split('.')[1],'base64url').toString());
    assert.equal(claims.sub,plan.subjects[username]);assert.equal(claims.iss,plan.issuer);assert.equal(idClaims.nonce,nonce);
    assert.ok([claims.aud].flat().includes(plan.audience));
    save(username+'-session.json',{accessToken:tokens.access_token,refreshToken:tokens.refresh_token,idToken:tokens.id_token,
      issuer:plan.issuer,subject:claims.sub,clientId:plan.spaClient,expiresAt:claims.exp});
    if(username==='dingqiming'){
      // These actions use the user's real browser SSO and fresh PKCE, with no IdP
      // administrative grants or account-service audience added to the business client.
      async function requiredAction(name){
        const actionVerifier=crypto.randomBytes(32).toString('base64url'),actionState=crypto.randomUUID(),actionNonce=crypto.randomUUID();
        let resolveAction;const actionCode=new Promise(resolve=>resolveAction=resolve);
        callback=url=>{assert.equal(url.searchParams.get('state'),actionState);assert.ok(url.searchParams.get('code'));assert.equal(url.searchParams.get('kc_action_status'),'success');resolveAction(url.searchParams.get('code'));};
        const action=new URL(auth);action.searchParams.set('state',actionState);action.searchParams.set('nonce',actionNonce);
        action.searchParams.set('code_challenge',crypto.createHash('sha256').update(actionVerifier).digest('base64url'));action.searchParams.set('kc_action',name);
        await page.goto(action.href);
        return async()=>{
          let timer;
          const returned=await Promise.race([actionCode,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Required action did not complete')),30000);})]).finally(()=>clearTimeout(timer));
          const fresh=await request(plan.issuer+'/protocol/openid-connect/token','POST',new URLSearchParams({grant_type:'authorization_code',code:returned,
            client_id:plan.spaClient,redirect_uri:plan.origin+'/auth/callback',code_verifier:actionVerifier}).toString(),{'Content-Type':'application/x-www-form-urlencoded'});
          assert.equal(fresh.status,200);const freshTokens=JSON.parse(fresh.body);
          const freshId=JSON.parse(Buffer.from(freshTokens.id_token.split('.')[1],'base64url').toString());assert.equal(freshId.nonce,actionNonce);assert.equal(freshId.sub,plan.subjects[username]);
          return freshTokens;
        };
      }
      const email='dingqiming@example.invalid';
      const finishProfile=await requiredAction('UPDATE_PROFILE');
      await page.locator('#email').fill(email);await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();
      tokens=await finishProfile();
      const finishPassword=await requiredAction('UPDATE_PASSWORD');
      await page.locator('#password-new').waitFor();
      for(const rejected of ['Ab12345',username,email]){
        await page.locator('#password-new').fill(rejected);await page.locator('#password-confirm').fill(rejected);await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();
        await page.locator('#password-new').waitFor();
        const message=(await page.locator('body').innerText()).toLowerCase();assert.ok(message.includes(rejected===email?'email':rejected===username?'username':'8'),'Actual IdP must reject each invalid password');
      }
      await page.locator('#password-new').fill(input.newPasswords[username]);await page.locator('#password-confirm').fill(input.newPasswords[username]);
      await page.locator('input[type=submit]:not([name=cancel-aia]),button[type=submit]:not([name=cancel-aia])').click();
      tokens=await finishPassword();
    }
    save(username+'-session.json',{accessToken:tokens.access_token,refreshToken:tokens.refresh_token,idToken:tokens.id_token,
      issuer:plan.issuer,subject:claims.sub,clientId:plan.spaClient,expiresAt:JSON.parse(Buffer.from(tokens.access_token.split('.')[1],'base64url').toString()).exp});
    results.push({username,passwordChanged:true,pkce:'S256',issuerVerified:true,subjectVerified:true});
    await context.close();callback=null;
  }
  const denied=await request(plan.issuer+'/protocol/openid-connect/token','POST',new URLSearchParams({grant_type:'password',
    client_id:plan.spaClient,username:'dingqiming',password:input.newPasswords.dingqiming}).toString(),{'Content-Type':'application/x-www-form-urlencoded'});
  assert.equal(denied.status,400);assert.equal(JSON.parse(denied.body).error,'unauthorized_client');
  save('browser-report.json',{status:'PASS',accounts:results,minimumSevenRejected:true,usernameRejected:true,emailRejected:true,passwordGrantRejected:true});
  console.log(JSON.stringify({status:'PASS',adminPasswordUpdates:2,pkce:'S256',passwordGrantRejected:true}));
}catch(error){
  save('browser-failure.json',{name:error.name,message:String(error.message).split('\n')[0],locations:String(error.stack).split('\n').filter(x=>x.trim().startsWith('at '))});
  console.error('IDENTITY_BROWSER_VERIFICATION_FAILED; protected evidence retained');process.exitCode=2;
}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
