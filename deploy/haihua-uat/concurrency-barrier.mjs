import fs from 'node:fs';import path from 'node:path';import {runtime,save} from './browser.mjs';
export async function barrier(actor,enabled){
 if(!enabled)return;if(!runtime.endsWith('haihua-uat-runtime')||!['finance','intake'].includes(actor))throw Error('Approved original-runtime concurrency barrier required');
 const release=path.join(runtime,'P4-finance-intake-release.json');if(fs.existsSync(release))throw Error('Prior release preserved; do not replay');save('P4-finance-intake-ready-'+actor+'.json',{actor,readyAt:new Date().toISOString()});
 const deadline=Date.now()+300000;while(!fs.existsSync(release)){if(Date.now()>deadline)throw Error('Prepared concurrency barrier expired without release');await new Promise(resolve=>setTimeout(resolve,50));}
}
