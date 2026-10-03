import { chromium } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { login,runtime,evidence,save } from './browser.mjs';
import { reserveAttempt } from './command-attempt.mjs';
// Initial setup is one-shot. A failure requires reconciliation of original keys,
// not a second pass through forms that generate fresh create commands.
const setupFile=path.join(runtime,'admin-setup-result.json');
if(fs.existsSync(setupFile))throw Error('Existing completed setup preserved; do not rerun initialization');
reserveAttempt(runtime,'admin-setup',{operation:'initial identity setup'});
const file=path.join(runtime,'account-map.json');
const map=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{organizations:{},users:{},grants:[],uiCommands:[]};
const browser=await chromium.launch({channel:'chrome',headless:true});
let page;
const persist=()=>save('account-map.json',map);
async function select(label, match) {
  const field=page.getByLabel(label,{exact:true});
  await field.locator('option').nth(1).waitFor({state:'attached'});
  const choices=await field.locator('option').evaluateAll(es=>es.filter(e=>e.value).map(e=>({label:e.textContent,value:e.value})));
  const result=choices.filter(x=>typeof match==='string'?x.label===match:match.test(x.label));
  if(result.length!==1)throw Error(label+' expected one exact choice; matched '+result.length+' from '+choices.map(x=>x.label).join(', '));
  await field.selectOption(result[0].value);return result[0].value;
}
async function create(section,button,fill) {
  await page.getByRole('link',{name:section,exact:true}).click();
  await page.getByRole('button',{name:button,exact:true}).click();
  await fill();
  let dispatched;
  await page.route('**/api/v1/admin/identity/**',async route=>{
    if(route.request().method()!=='POST'){await route.fallback();return;}
    const request=route.request();const headers=request.headers();
    if(dispatched||!headers['idempotency-key']){await route.abort('blockedbyclient');return;}
    dispatched={state:'DISPATCHED',url:new URL(request.url()).pathname,
      commandId:headers['idempotency-key'],body:request.postDataJSON()};
    map.uiCommands.push(dispatched);persist();
    await route.fallback();
  });
  const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/api/v1/admin/identity/'));
  await page.getByRole('button',{name:'确认创建',exact:true}).click();
  const r=await response;const receipt=await r.json();
  if(!dispatched)throw Error('Original admin command not captured');
  Object.assign(dispatched,{state:'OBSERVED',status:r.status(),receipt});persist();
  await page.unroute('**/api/v1/admin/identity/**');
  if(r.status()!==201||receipt.outcome!=='SUCCEEDED')throw Error('admin create rejected '+r.status()+' '+JSON.stringify(receipt));
  await page.getByText(/结果已记录，当前页已重新读取/).waitFor();
  await page.getByRole('button',{name:button,exact:true}).waitFor();
}
const departments=[['SALES_1','销售一部'],['SALES_2','销售二部'],['FINANCE','财务部'],['CASE_ADMIN','案管部']];
const users=[['sales01','SALES_1','SALES_REPRESENTATIVE'],['sales02','SALES_1','SALES_REPRESENTATIVE'],['sales03','SALES_1','SALES_REPRESENTATIVE'],['sales04','SALES_2','SALES_REPRESENTATIVE'],['sales05','SALES_2','SALES_REPRESENTATIVE'],['sales_manager01','SALES_1','SALES_MANAGER'],['sales_manager02','SALES_2','SALES_MANAGER'],['finance01','FINANCE','FINANCE_OPERATOR'],['case_admin01','CASE_ADMIN','CASE_ADMINISTRATOR']];
const sales=['LEAD_CAPTURE','SALES_CONTACT_OWNER','SALES_OPPORTUNITY_OWNER','OPPORTUNITY_CLOSE','CUSTOMER_REQUIREMENTS_MANAGE','PARTY_PROFILE_MANAGE','MATERIALS_MANAGE','MATERIALS_READ','QUOTE_READ','QUOTE_PREPARE','QUOTE_DELIVER','QUOTE_RESPONSE','CONTRACT_READ','CONTRACT_PREPARE','CONTRACT_EXECUTION_VERIFY','PAYMENT_SUBMIT','PAYMENT_LEDGER_READ','TRANSFER_SUBMIT','TRANSFER_LEDGER_READ'];
const manager=['LEAD_MANAGEMENT_READ','TEAM_TASK_READ','OPPORTUNITY_LEDGER_READ','CONTRACT_READ','QUOTE_READ','PAYMENT_LEDGER_READ','TRANSFER_LEDGER_READ','LEAD_CAPTURE','LEAD_INGRESS_RESOLVE','LEAD_INGRESS_COMPLETE','LEAD_ASSIGN','LEAD_ROUTING_DECIDE','SOURCE_INTAKE_REQUEST_ACK','LEAD_VALIDITY_REVIEW','QUOTE_APPROVE','CONTRACT_PREPARATION_DECIDE','CONTRACT_APPROVE','CONTRACT_TERMINATION_REVIEW','OPPORTUNITY_OWNER_EXCEPTION_READ','OPPORTUNITY_OWNER_EXCEPTION_RESOLVE','OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ'];
const grants=[];
for(const [name,dept] of users) {
  if(name.startsWith('sales_manager'))for(const code of manager)grants.push([name,dept,code]);
  else if(name.startsWith('sales'))for(const code of sales)grants.push([name,dept,code]);
}
for(const dept of ['SALES_1','SALES_2']) {
  for(const code of ['CONTRACT_READ','PAYMENT_CONFIRM','PAYMENT_LEDGER_READ'])grants.push(['finance01',dept,code]);
  for(const code of ['CONTRACT_READ','CONTRACT_REVIEW','CONTRACT_SIGNATURE_VERIFY'])grants.push(['case_admin01',dept,code]);
}
grants.push(['finance01','FINANCE','PAYMENT_CONFIRM']);
for(const code of ['CONTRACT_READ','TRANSFER_REVIEW','TRANSFER_ACCEPT','MATTER_CLASSIFY','MATTER_RECEIVE','TRANSFER_LEDGER_READ','TEAM_TASK_READ'])grants.push(['case_admin01','CASE_ADMIN',code]);
try {
  const actor=await login(browser,'sys_manager01');page=actor.page;
  map.users.sys_manager01={appointmentId:actor.appointment.value,role:'IDENTITY_ADMIN',department:'HAIHUA'};persist();
  const organizations=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/admin/identity/organizations');
  await page.getByRole('link',{name:'组织架构',exact:true}).click();
  const organizationResponse=await organizations;
  if(organizationResponse.status()!==200)throw Error('organization read failed');
  for(const item of (await organizationResponse.json()).items)map.organizations[item.code]=item.id;
  persist();
  const appointments=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname==='/api/v1/admin/identity/appointments');
  await page.getByRole('link',{name:'任职管理',exact:true}).click();
  const appointmentResponse=await appointments;
  if(appointmentResponse.status()!==200)throw Error('appointment read failed');
  for(const item of (await appointmentResponse.json()).items)if(map.users[item.principal.label])map.users[item.principal.label].appointmentId=item.id;
  persist();
  for(const [code,label] of departments) {
    if(map.organizations[code])continue;
    await create('组织架构','新增组织',async()=>{
      map.organizations.HAIHUA=await select('上级组织','海华律师事务所');
      await page.getByLabel('显示名称',{exact:true}).fill(label);
      await page.getByLabel('组织代码',{exact:true}).fill(code);
    });
    // Read the new organization's precise ID from a supported UI candidate list.
    await page.getByRole('button',{name:'新增组织',exact:true}).click();
    map.organizations[code]=await select('上级组织',label);persist();
    await page.getByRole('button',{name:'取消',exact:true}).click();
    if(await page.getByRole('button',{name:'确认舍弃',exact:true}).isVisible())await page.getByRole('button',{name:'确认舍弃',exact:true}).click();
    console.log('organization created via UI: '+code);
  }
  for(const [name,dept,role] of users) {
    map.users[name]??={department:dept,role};
    if(!map.users[name].principalCreated) {
      await create('身份主体','新增身份主体',async()=>{
        await page.getByLabel('完整用户名',{exact:true}).fill(name);
        await page.getByRole('button',{name:'精确查询',exact:true}).click();
        await select('身份提供方用户',new RegExp('^'+name+'(?: |（|$)'));
        await page.getByLabel('显示名称',{exact:true}).fill(name);
      });
      map.users[name].principalCreated=true;persist();console.log('principal created via UI: '+name);
    }
    if(!map.users[name].appointmentId) {
      await create('任职管理','新建任职',async()=>{
        map.users[name].principalId=await select('身份主体',name);
        await select('所属组织',departments.find(x=>x[0]===dept)[1]);
        await page.getByLabel('岗位',{exact:true}).selectOption(role);
        await page.getByLabel('生效时间',{exact:true}).fill('2026-10-01T00:00');
      });
      await page.getByRole('link',{name:'直接授权',exact:true}).click();
      await page.getByRole('button',{name:'新增直接授权',exact:true}).click();
      map.users[name].appointmentId=await select('授权任职',new RegExp('^'+name+'(?: ·|$)'));persist();
      await page.getByRole('button',{name:'取消',exact:true}).click();
      if(await page.getByRole('button',{name:'确认舍弃',exact:true}).isVisible())await page.getByRole('button',{name:'确认舍弃',exact:true}).click();
      console.log('appointment created via UI: '+name+' '+role);
    }
  }
  for(const [name,scope,code] of grants) {
    const key=name+':'+scope+':'+code;if(map.grants.some(g=>g.key===key))continue;
    await create('直接授权','新增直接授权',async()=>{
      await select('授权任职',new RegExp('^'+name+'(?: ·|$)'));
      await select('组织范围',departments.find(x=>x[0]===scope)[1]);
      await page.getByLabel('权限',{exact:true}).selectOption(code);
      await page.getByLabel('生效时间',{exact:true}).fill('2026-10-01T00:00');
    });
    map.grants.push({key,name,scope,code});persist();console.log('grant created via UI: '+key);
  }
  await page.getByRole('link',{name:'任职管理',exact:true}).click();
  await page.screenshot({path:path.join(evidence,'appointments-complete.png'),fullPage:true});
  save('admin-setup-result.json',{status:'PASS',departments:departments.length,users:10,businessGrants:grants.length,uiCommands:map.uiCommands.length,at:new Date().toISOString()});
  console.log('Admin UI setup complete: '+grants.length+' explicit business grants.');
} catch(e) {
  if(page){save('admin-setup-failure.txt',await page.locator('body').innerText());await page.screenshot({path:path.join(evidence,'admin-setup-failure.png'),fullPage:true});}
  throw e;
} finally {await browser.close();}
