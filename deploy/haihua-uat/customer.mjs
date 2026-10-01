import { chromium } from '@playwright/test';import path from 'node:path';
import { login,evidence,save } from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const [sales,code,mode]=process.argv.slice(2);if(mode&&!((mode==='same-name'&&code==='HH-B03-20261001'&&sales==='sales03')||(mode==='opposing-existing'&&code==='HH-B15-20261001-R2'&&sales==='sales04')))throw Error('Approved named party branch only');const browser=await chromium.launch({channel:'chrome',headless:true});let page;const writes=[];
try{
 ({page}=await login(browser,sales));await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 await selectTask(page,code,'推进');
 page.on('response',async r=>{if(r.request().method()==='POST'&&r.url().includes('/customer')){writes.push({path:new URL(r.url()).pathname,status:r.status(),receipt:await r.json()});save(code+'-customer-receipts.json',writes);}});
 await page.getByText('其他业务处理',{exact:true}).click();await page.getByRole('button',{name:'维护客户与需求',exact:true}).click();
 await page.getByRole('heading',{name:'核对客户与需求',exact:true}).waitFor();
 await page.getByRole('button',{name:'查找或添加准确主体',exact:true}).click();
 const partyName=mode==='same-name'?'HH-PERF-0003 CLIENT 合成组织':code+' 合成委托组织';
 await page.getByLabel('查找已有主体',{exact:true}).fill(partyName);
 const search=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname.endsWith('/customer-requirements/parties'));
 await page.getByRole('button',{name:'查询有权主体',exact:true}).click();
 const matches=await (await search).json();save(code+'-party-search.json',matches);
 if(mode==='same-name'){if(!matches.items?.some(p=>p.name===partyName))throw Error('Existing same-name party must actually be visible');}
 else await page.getByText('未找到有权查看的匹配主体。请调整名称，或核实后建立独立主体。',{exact:true}).waitFor();
 await page.getByText('都不是本次主体',{exact:true}).click();await page.getByRole('button',{name:'新建本次主体',exact:true}).click();
 await page.getByLabel('已核实这是独立主体，与已有同名主体不同',{exact:true}).check();
 if(code.endsWith('-R2')){
 await page.getByText('更多参与方',{exact:true}).click();await page.getByLabel('对方尚待核实，不建立占位主体',{exact:true}).uncheck();await page.getByRole('button',{name:'添加已核实参与方',exact:true}).click();
 if(mode==='opposing-existing'){
  const opposite='HH-G04-20261001-R2 合成委托组织';await page.getByLabel(/^本次参与方角色/).selectOption('OPPONENT');await page.getByLabel('查找已有主体',{exact:true}).fill(opposite);await page.getByRole('button',{name:'查询有权主体',exact:true}).click();await page.getByRole('region',{name:'主体查找',exact:true}).locator('.compact-confirm').filter({hasText:opposite}).getByRole('button',{name:'使用此主体',exact:true}).click();
 }else{await page.getByLabel('查找已有主体',{exact:true}).fill(code+' 合成对方组织');await page.getByRole('button',{name:'查询有权主体',exact:true}).click();await page.getByText('未找到有权查看的匹配主体。请调整名称，或核实后建立独立主体。',{exact:true}).waitFor();await page.getByText('都不是本次主体',{exact:true}).click();await page.getByRole('button',{name:'新建本次主体',exact:true}).click();await page.getByLabel('已核实这是独立主体，与已有同名主体不同',{exact:true}).last().check();}
 }
 await page.getByLabel('事项名称 *',{exact:true}).fill(code+' 合同纠纷合成咨询');
 await page.getByLabel(/^客户目标/).fill('核对事实并评估可行解决路径。纯合成测试，不用于实际签约。');
 await page.getByLabel(/^拟服务范围/).fill('约定合同纠纷法律咨询；代理范围以人工确认正文为准。');
 await page.getByRole('button',{name:'核对本次资料',exact:true}).click();await page.getByRole('heading',{name:'核对本次资料确认',exact:true}).waitFor();
 await page.getByRole('button',{name:'确认客户与需求',exact:true}).click();await page.getByRole('heading',{name:'客户与需求已确认',exact:true}).waitFor();
 await page.screenshot({path:path.join(evidence,code+'-customer.png'),fullPage:true});console.log(code+' customer/party confirmation UI PASS');
}catch(e){if(page)save(code+'-customer-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
