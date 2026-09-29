async (page) => {
 const base='http://127.0.0.1:8985/docs/design/r2-sales-mvp/review/2026-09-29-u-waiting/app.html?scene=';
 const results=[];
 for(const width of [1440,390,360]){
  await page.setViewportSize({width,height:1000});
  for(const scene of ['collapsed','expanded','detail','due','ready','dirty','changed','loading','error','revoked','empty','no-current','many']){
   await page.goto(base+scene);
   if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Overflow '+width+' '+scene);
   if(!['ready','dirty','changed'].includes(scene)&&await page.getByRole('button',{name:'前往办理',exact:true}).count())throw Error('Unexpected write entry');
   results.push({width,scene,overflow:false});
   if(['detail','due','dirty'].includes(scene))await page.screenshot({path:'.local/u-review/'+width+'-'+scene+'.png',fullPage:true});
  }
 }
 await page.goto(base+'collapsed');
 await page.getByLabel('进展摘要').fill('未保存的合成填写');
 await page.getByRole('button',{name:'等待 3 项 · 查看等待事项',exact:true}).click();
 await page.getByRole('button',{name:/远山商贸有限公司/}).click();
 if(await page.getByLabel('进展摘要').inputValue()!=='未保存的合成填写')throw Error('Draft lost');
 await page.getByRole('button',{name:'等待 3 项 · 收起',exact:true}).click();
 if(await page.getByLabel('进展摘要').inputValue()!=='未保存的合成填写')throw Error('Draft lost on collapse');
 await page.goto(base+'dirty');await page.getByRole('button',{name:'前往办理',exact:true}).click();
 if(!await page.getByRole('dialog').isVisible())throw Error('Missing guard');
 await page.getByRole('button',{name:'继续填写',exact:true}).click();
 if(!(await page.getByLabel('进展摘要').inputValue()).includes('尚未保存'))throw Error('Guard lost draft');
 await page.getByRole('button',{name:'前往办理',exact:true}).click();await page.getByRole('button',{name:'保存草稿并前往',exact:true}).click();
 if(await page.locator('#subject').innerText()!=='远山商贸有限公司')throw Error('Did not open original card');
 if(!await page.evaluate(()=>sessionStorage.getItem('u-preview-draft')?.includes('尚未保存')))throw Error('Save lost');
 if(!await page.getByRole('button',{name:'等待 2 项 · 收起',exact:true}).count())throw Error('Count drift');
 await page.goto(base+'changed');await page.getByRole('button',{name:'前往办理',exact:true}).click();
 if(await page.locator('.wait-detail').count())throw Error('Stale detail');
 await page.goto(base+'error');await page.getByRole('button',{name:'重新查询',exact:true}).click();
 if(await page.locator('.wait-item').count()!==3)throw Error('Retry failed');
 await page.goto(base+'many');await page.getByRole('button',{name:'加载更多等待事项'}).click();
 if(await page.locator('.wait-item').count()!==12)throw Error('Paging failed');
 return {layouts:results,interactions:'draft-preservation, collapse, guarded navigation, save, revocation, retry, paging passed'};
}
