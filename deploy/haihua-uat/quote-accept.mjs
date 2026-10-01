import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';import {openOpportunity} from './business-browser.mjs';
const [sales,code,reply='ACCEPTED']=process.argv.slice(2);if(reply!=='ACCEPTED'&&!((sales==='sales04'&&code==='HH-B10-20261001-R2'&&reply==='AMBIGUOUS')||(sales==='sales05'&&code==='HH-B11-20261001-R2'&&reply==='REJECTED')))throw Error('Explicit approved reply branch required');const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
const chooseOne=async label=>{const select=page.getByLabel(new RegExp('^'+label));const options=await select.locator('option').evaluateAll(es=>es.filter(x=>x.value).map(x=>({id:x.value,label:x.text})));if(options.length!==1)throw Error('one exact '+label+' required');await select.selectOption(options[0].id);};
const actualTime=()=>page.evaluate(()=>{const d=new Date();return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+'T'+[d.getHours(),d.getMinutes(),d.getSeconds()].map(n=>String(n).padStart(2,'0')).join(':');});
try{({page}=await login(browser,sales));await openOpportunity(page,code);
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/quotes/')){const data=await r.json();writes.push({path:new URL(r.url()).pathname,status:r.status(),receipt:data});save(code+'-quote-accept-receipts.json',writes);}});
 await page.getByText('收费方案与报价',{exact:true}).click();await page.getByRole('button',{name:'查看与办理报价',exact:true}).click();
 await page.getByRole('heading',{name:'记录本次人工交付',exact:true}).waitFor();await chooseOne('本次接收委托方');
 await page.getByLabel(/^实际接收人/).fill('Synthetic Client Signer '+Number(sales.slice(-2)));await page.getByLabel(/^交付方式/).selectOption('IN_PERSON');
 await page.getByLabel(/^实际交付时间/).fill(await actualTime());await chooseOne('本次交付证明');
 await page.getByRole('button',{name:'记录本次报价交付',exact:true}).click();await page.getByRole('heading',{name:'客户如何回复本版报价？',exact:true}).waitFor();
 await page.getByLabel(/^客户回复/).selectOption(reply);await page.getByLabel(/^客户原意与核对说明/).fill(reply==='ACCEPTED'?'合成客户明确接受本版报价、10000元费用、服务范围及本版付款条件，模拟当面接受，非发送成功或沉默推定。':reply==='AMBIGUOUS'?'合成客户要求解释服务范围与付款安排，回复尚未表明接受或拒绝；约定继续核实，不推定接受。':'合成客户明确拒绝本版10000元报价，费用超出预算；约定讨论修订可能，不推定接受。');
 if(reply!=='ACCEPTED')await page.getByLabel('下次跟进时间',{exact:true}).fill(await page.evaluate(()=>{const d=new Date(Date.now()+2*60*60*1000);return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')+'T'+String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');}));
 await page.getByLabel(/^实际回复时间/).fill(await actualTime());await chooseOne('本次客户回复证据');
 await page.getByRole('button',{name:'记录客户报价回复',exact:true}).click();await page.getByRole('heading',{name:reply==='ACCEPTED'?'客户接受已记录':reply==='AMBIGUOUS'?'核实客户报价回复':'处理报价拒绝',exact:true}).last().waitFor();
 await page.screenshot({path:path.join(evidence,code+'-quote-'+reply+'.png'),fullPage:true});console.log(code+' exact quote delivery/'+reply+' UI PASS');
}catch(e){if(page)save(code+'-quote-accept-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
