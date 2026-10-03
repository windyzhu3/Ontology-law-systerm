import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
import {login,save} from './browser.mjs';
import {selectTask} from './business-browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[];
try{
 for(const actor of ['sales04','finance01','sys_manager01']){
  const a=await login(browser,actor),page=a.page;let writes=0;
  page.on('request',r=>{if(['POST','PUT','PATCH','DELETE'].includes(r.method())&&new URL(r.url()).pathname.startsWith('/api/'))writes++;});
  if(actor==='sales04'){await selectTask(page,'HH-B15-20261001-R2','');await page.getByRole('button',{name:'生成合同正文',exact:true}).waitFor();}
  else if(actor==='finance01'){await selectTask(page,'HH-G03-20261001-R2','到账');await page.getByRole('button',{name:'记录核对结果',exact:true}).waitFor();}
  else {await page.getByRole('button',{name:'新增身份主体',exact:true}).click();await page.getByRole('heading',{name:'建立身份主体',exact:true}).waitFor();}
  for(const width of [1440,390,360]){
   await page.setViewportSize({width,height:1000});
   const buttons=await page.locator('main button,[role="dialog"] button').evaluateAll(es=>[...new Set(es)].filter(e=>e.getBoundingClientRect().width&&e.getBoundingClientRect().height).map(e=>{const s=getComputedStyle(e);return {label:e.textContent.trim(),className:e.className,disabled:e.disabled,fontFamily:s.fontFamily,fontSize:s.fontSize,fontWeight:s.fontWeight,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,minHeight:s.minHeight,height:e.getBoundingClientRect().height};}));
   assert.ok(buttons.length>0);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
   results.push({actor,width,buttons,writes});save('footer-style-ui-results.json',results);
  }
  assert.equal(writes,0);await a.context.close();
 }
 console.log('Actual loaded contract, finance, and admin form footer style samples recorded at three widths; zero writes');
}finally{await browser.close();}
