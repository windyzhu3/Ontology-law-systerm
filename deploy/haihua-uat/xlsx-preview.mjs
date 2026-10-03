import {chromium} from '@playwright/test';
import {zipSync,strToU8} from 'fflate';
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';
import {login,runtime,save} from './browser.mjs';
const account=process.argv[2]??'sales02';if(!['sales02','sales05'].includes(account))throw Error('Approved B05 account required');const tag=account==='sales05'?'B05-S2':'B05';
const ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main',rel='http://schemas.openxmlformats.org/package/2006/relationships';
const c=(ref,text)=>`<c r="${ref}" t="inlineStr"><is><t>${text}</t></is></c>`;
const header=`<row r="1">${['来源记录号','客户名称','手机号','需求描述'].map((x,i)=>c('ABCD'[i]+'1',x)).join('')}</row>`;
const valid=`<row r="2">${c('A2','0001')}${c('B2','HH-B05 合成客户')}${c('C2','+8613900050001')}${c('D2','纯合成咨询')}</row>`;
const numeric=`<row r="3"><c r="A3"><v>123</v></c>${c('B3','数值编号')}${c('C3','+8613900050002')}${c('D3','纯合成咨询')}</row>`;
const dated=`<row r="4">${c('A4','HH-B05-DATE')}${c('B4','日期误填电话')}<c r="C4" t="d"><v>2026-10-01T00:00:00</v></c>${c('D4','纯合成咨询')}</row>`;
function file(name,rows){const files={'[Content_Types].xml':`<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>`,'xl/workbook.xml':`<workbook xmlns="${ns}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="线索" sheetId="1" r:id="rId1"/></sheets></workbook>`,'xl/_rels/workbook.xml.rels':`<Relationships xmlns="${rel}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/intake.xml"/></Relationships>`,'xl/worksheets/intake.xml':`<worksheet xmlns="${ns}"><sheetData>${header}${rows}</sheetData></worksheet>`};const target=path.join(runtime,name);fs.writeFileSync(target,zipSync(Object.fromEntries(Object.entries(files).map(([k,v])=>[k,strToU8(v)]))));return target;}
const browser=await chromium.launch({channel:'chrome',headless:true});let page;let writes=0;
try{({page}=await login(browser,account));
 page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/v1/leads')writes++;});
 await page.getByRole('button',{name:'录入线索',exact:true}).click();await page.getByRole('button',{name:'批量导入',exact:true}).click();
 await page.getByLabel('来源',{exact:true}).selectOption(account==='sales05'?'HH_S2_MANUAL':'HH_S1_MANUAL');
 await page.getByLabel('选择文件',{exact:true}).setInputFiles(file('HH-'+tag+'-numeric.xlsx',valid+numeric));
 await page.getByText('可导入 1 条 · 需修正 1 条',{exact:true}).waitFor();
 await page.getByText('来源记录号必须为文本，请核对前导零及完整编号。',{exact:true}).waitFor();
 save(tag+'-numeric-preview.txt',await page.locator('body').innerText());assert.equal(writes,0);
 await page.getByRole('button',{name:'更换文件',exact:true}).click();
 if(await page.getByRole('button',{name:'放弃并返回',exact:true}).isVisible())await page.getByRole('button',{name:'放弃并返回',exact:true}).click();
 await page.getByLabel('选择文件',{exact:true}).setInputFiles(file('HH-'+tag+'-date.xlsx',valid+dated));
 await page.getByRole('alert').waitFor();save(tag+'-date-preview.txt',await page.locator('body').innerText());assert.equal(writes,0);
 save(tag+'-preview-PASS.json',{textLeadingZeroAccepted:true,numericSourceRejected:true,dateCellRejected:true,actualWrites:0});console.log('B05 actual XLSX preview, numeric identifiers/date cells rejected, zero business writes PASS');
}catch(e){if(page)save(tag+'-preview-failure.txt',await page.locator('body').innerText());throw e;}finally{await browser.close();}
