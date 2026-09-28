import {chromium} from 'playwright';
import {createServer} from 'vite';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve,join,isAbsolute} from 'node:path';
import assert from 'node:assert/strict';

// Invocation: node e2e/tests/t01-owner-exception-browser.mjs < ephemeral-stdin-json
// stdin: {origin,bearer,actor,outputDir,expectedExceptionId?,receiverId?}
// No bearer, raw request/response body, browser state or trace is persisted or logged.
let browser;
let vite;
let page;
const traffic=[];
let stage='INPUT';
let outputDir;
try {
 let raw='';
 for await(const chunk of process.stdin){raw+=chunk;if(Buffer.byteLength(raw)>65536)throw Error('input bounds');}
 const input=JSON.parse(raw);raw='';
 const origin=new URL(input.origin);
 assert(['http:','https:'].includes(origin.protocol));
 assert(['127.0.0.1','localhost','[::1]'].includes(origin.hostname));
 assert.equal(origin.pathname,'/');assert(!origin.search&&!origin.hash&&!origin.username&&!origin.password);
 assert.equal(typeof input.bearer,'string');assert(input.bearer.length>0&&!/[\r\n]/.test(input.bearer));
 const actor=input.actor;
 assert(actor&&Number.isInteger(actor.identityEpoch));
 assert(/^ask1\.[A-Za-z0-9_-]{43}$/.test(actor.actorScopeKey));
 assert(/^[0-9a-f-]{36}$/i.test(actor.selectedAppointmentId));assert.equal(actor.selectedOnBehalfAppointmentId,null);
 assert(isAbsolute(input.outputDir));outputDir=resolve(input.outputDir);await mkdir(outputDir,{recursive:true});
 const expectedId=input.expectedExceptionId??input.expectedexceptionId??input.exceptionId;
 const receiverId=input.receiverId??input.receiverAppointmentId;
 stage='BROWSER';
 vite=await createServer({configFile:false,root:process.cwd(),server:{host:'127.0.0.1',port:0},logLevel:'silent'});await vite.listen();
 const frontendOrigin='http://127.0.0.1:'+vite.httpServer.address().port;
 browser=await chromium.launch({channel:'chrome',headless:true});
 const context=await browser.newContext({viewport:{width:1487,height:930},locale:'zh-CN'});
 await context.addInitScript(value=>{window.__t01Actor=value;},actor);
 page=await context.newPage();page.setDefaultTimeout(30000);
 let listItems=[];let transfers=0;let transferStatus=0;let transferSucceeded=false;let refreshedResolved=false;let pageErrors=0;let transportErrors=0;
 page.on('pageerror',()=>pageErrors++);
 await context.route('**/api/v1/**',async route=>{
  const incoming=route.request();const address=new URL(incoming.url());
  const path=address.pathname+address.search;
  try {
   assert.equal(address.origin,frontendOrigin);
   const headers=await incoming.allHeaders();delete headers.host;delete headers.authorization;
   headers.authorization=`Bearer ${input.bearer}`;
   const response=await context.request.fetch(origin.origin+path,{method:incoming.method(),headers,data:incoming.postDataBuffer()??undefined,maxRedirects:0,maxRetries:0,timeout:30000});
   const status=response.status();traffic.push({method:incoming.method(),path:address.pathname,status});
   if(address.pathname==='/api/v1/opportunity-owner-exceptions/commands/transfer'){
    transfers++;transferStatus=status;
    if(status===200){const receipt=await response.json();transferSucceeded=receipt.outcome==='SUCCEEDED'&&receipt.resultFact?.factType==='OPPORTUNITY_OWNER_EXCEPTION';}
   }
   if(address.pathname==='/api/v1/opportunity-owner-exceptions'&&status===200){
    const data=await response.json();assert(Array.isArray(data.items));listItems=data.items;
    if(transfers>0)refreshedResolved=listItems.some(item=>(!expectedId||item.exception?.id===expectedId)&&item.state==='RESOLVED');
   }
   await route.fulfill({response});
  } catch {transportErrors++;await route.abort('failed');}
 });
 stage='LIST';
 await page.goto(frontendOrigin+'/e2e/fixtures/t01-live-preview.html',{waitUntil:'networkidle'});
 await page.getByRole('heading',{name:'团队待办',exact:true}).waitFor();
 const selected=expectedId?listItems.find(item=>item.exception?.id===expectedId):listItems[0];
 assert(selected,'expected live exception absent');
 const label=selected.opportunityLabel||'商机责任异常';
 await page.screenshot({path:join(outputDir,'t01-live-desktop-list.png'),fullPage:true});
 stage='DETAIL';
 await page.getByRole('button',{name:label,exact:true}).first().click();
 await page.getByRole('button',{name:'安排接手人',exact:true}).waitFor();
 await page.screenshot({path:join(outputDir,'t01-live-desktop-detail.png'),fullPage:true});
 await page.setViewportSize({width:360,height:930});
 await page.screenshot({path:join(outputDir,'t01-live-mobile-detail.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),360);
 await page.setViewportSize({width:1487,height:930});
 stage='CANDIDATE';
 await page.getByRole('button',{name:'安排接手人',exact:true}).click();
 const candidates=page.getByLabel('接手人');await candidates.waitFor();
 if(receiverId)await candidates.selectOption(receiverId);
 else {const value=await candidates.locator('option').nth(1).getAttribute('value');assert(value);await candidates.selectOption(value);}
 await page.getByRole('textbox',{name:'处置说明'}).fill('真实HTTP浏览器验收：请接续当前责任，保留原期限。');
 stage='CONFIRM';
 await page.getByRole('button',{name:'核对本次交接'}).click();
 await page.getByRole('button',{name:'确认交接',exact:true}).waitFor();
 await page.screenshot({path:join(outputDir,'t01-live-desktop-confirm.png'),fullPage:true});
 stage='TRANSFER';
 await page.getByRole('button',{name:'确认交接',exact:true}).click();
 await page.getByText('交接已确认，业务事项继续办理。',{exact:true}).waitFor();
 await page.getByRole('cell',{name:'已解决',exact:true}).waitFor();
 await page.getByRole('button',{name:label,exact:true}).first().click();
 await page.getByText('此异常已处理，无需再次安排接手人。业务事项按当前责任继续办理。',{exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'安排接手人',exact:true}).count(),0);
 assert.equal(transfers,1);assert.equal(transferStatus,200);assert(transferSucceeded);assert(refreshedResolved);assert.equal(pageErrors,0);assert.equal(transportErrors,0);
 stage='COMPLETE';
 await page.screenshot({path:join(outputDir,'t01-live-desktop-completed.png'),fullPage:true});
 await page.setViewportSize({width:360,height:930});
 await page.screenshot({path:join(outputDir,'t01-live-mobile-completed.png'),fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),360);
 const evidence={kind:'REAL_HTTP_REACT_COMPONENT_FLOW',productionOAuth:false,fixtureResponses:false,transfers,transferStatus,transferSucceeded,refreshedResolved,pageErrors,transportErrors,traffic};
 await writeFile(join(outputDir,'t01-live-ui-evidence.json'),JSON.stringify(evidence,null,2)+'\n');
 process.stdout.write(JSON.stringify({ok:true,kind:evidence.kind,transfers,transferStatus,refreshedResolved})+'\n');
} catch {
 // Playwright errors may contain headers; do not print Error messages or stacks.
 if(outputDir){await writeFile(join(outputDir,'t01-live-ui-failure.json'),JSON.stringify({ok:false,stage,traffic})+'\n').catch(()=>{});if(page)await page.screenshot({path:join(outputDir,'t01-live-failure.png'),fullPage:true}).catch(()=>{});}
 process.stderr.write(JSON.stringify({ok:false,stage})+'\n');process.exitCode=1;
} finally {if(browser)await browser.close().catch(()=>{});if(vite)await vite.close().catch(()=>{});}
