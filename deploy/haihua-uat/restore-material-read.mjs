import {chromium} from '@playwright/test';import {login,save} from './browser.mjs';
const browser=await chromium.launch({channel:'chrome',headless:true});
try{const {page,apiHeaders}=await login(browser,'sales01');await page.getByText('正在读取当前责任…',{exact:true}).waitFor({state:'hidden'});
 const r=await page.evaluate(async({headers})=>{const response=await fetch('/api/v1/opportunities/01a0f56d-8e39-7a66-9293-cdaa78f50fd3/materials/versions/32c60da1-416e-4704-93f8-9c9e223e1039/content?disposition=attachment',{headers,cache:'no-store'});const body=await response.arrayBuffer();const hash=await crypto.subtle.digest('SHA-256',body);return {status:response.status,size:body.byteLength,mediaType:response.headers.get('Content-Type'),sha256:Array.from(new Uint8Array(hash)).map(b=>b.toString(16).padStart(2,'0')).join('')};},{headers:apiHeaders()});
 save('restored-material-download.json',r);if(r.status!==200||r.size!==5076627||r.sha256!=='8c2ad7103bf962b450430dfb13cd97d761c7d96e52307a21119ddb69c2cd2416')throw Error('restored exact material read mismatch');console.log('Actual restored sales01 authenticated material download: exact bytes/SHA PASS');
}finally{await browser.close();}
