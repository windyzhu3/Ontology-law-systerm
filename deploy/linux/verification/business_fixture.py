"""Synthetic-only configuration; all case facts are created through real pages."""
from pathlib import Path
import json,shutil,secrets,uuid
from ols_linux import admin,bundle,config,database,initialize,journal,runtime

ALIASES={'sales01':'helong','sales02':'helong','sales03':'yinying','sales04':'xuemingming',
         'sales_manager01':'wanhefeng','sales_manager02':'gengtangqi','case_admin01':'yangsheng',
         'finance01':'sunronghui','finance02':'jiaoweiqi'}
STEPS=('assign-lead','contact-lead','customer','quote-prepare','quote-decide','quote-accept','direct-request',
       'direct-decide','contract-form','contract-decide','contract-submit','sign-arrange','sign-submit',
       'sign-verify','sign-archive','execution','payment','upload-material','transfer','business-browser','command-attempt')


def allow(root,parent):
    if root==parent:raise RuntimeError('Production-like initialization cannot receive synthetic business fixtures')
    pointer=journal._read(parent,parent/'verification/business-instance.json')
    if pointer['runtime']!=str(root):raise RuntimeError('Only the explicitly separate original synthetic instance is allowed')
    resources=runtime.load(root)
    if not resources.get('verification') or resources['instanceId']!=pointer['instanceId'] or journal.current(root)['phase']!='COMPLETE':
        raise RuntimeError('Original fully activated isolated synthetic instance required')
    return resources


def prepare(root,parent,repo):
    resources=allow(root,parent);state=journal._read(root,root/'initialization.json')
    authorization={'runtime':str(root),'instanceId':resources['instanceId'],'parentInstanceId':runtime.load(parent)['instanceId'],
                   'basis':'Approved four synthetic UI acceptance chains; independent LEAD_ASSIGN and reviewed test templates only in this separate instance'}
    target=root/'verification/business-authorization.json'
    if target.exists() and journal._read(root,target)!=authorization:raise RuntimeError('Original synthetic authorization changed')
    journal._write(root,target,authorization)
    credentials=root/'browser-credentials.json'
    if not credentials.exists():
        passwords={name:'Aa'+secrets.token_hex(3) for name in set(ALIASES.values())}
        value={alias:{'username':name,'password':passwords[name],'appointmentId':state['appointments'][name],'passwordUpdated':False} for alias,name in ALIASES.items()}
        runtime.private_file(credentials,config.canonical(value))
        runtime.private_file(root/'account-map.json',config.canonical({'users':{alias:{'appointmentId':state['appointments'][name],'principalId':state['principals'][name]} for alias,name in ALIASES.items()}}))
    grants=root/'verification/independent-assignment.json'
    records=journal._read(root,grants) if grants.exists() else {}
    actor={'username':'dingqiming','appointmentId':state['appointments']['dingqiming_bootstrap']}
    for name,scope in [('wanhefeng','SALES_1'),('gengtangqi','SALES_2')]:
        if name not in records:
            records[name]={'commandId':str(uuid.uuid4()),'path':'/api/v1/admin/identity/authority-grants','actor':actor,'precondition':None,
                'body':{'appointmentId':state['appointments'][name],'authorityCode':'LEAD_ASSIGN','scopeOrganizationId':state['organizations'][scope],'validFrom':state['effectiveFrom'],'validUntil':None}}
            journal._write(root,grants,records)
        admin.execute(root,state['operationId'],records[name],root/'identity/dingqiming-session.json')
    steps=root/'verification/business-steps';steps.mkdir(mode=0o700,exist_ok=True)
    sources={}
    for name in STEPS:
        source=repo/'deploy/haihua-uat'/(name+'.mjs');text=source.read_text(encoding='utf-8');sources[str(source.relative_to(repo))]=bundle.sha(source)
        text=text.replace("'@playwright/test'","'/tools/node_modules/@playwright/test/index.mjs'").replace("channel:'chrome',",'')
        if name=='transfer':text=text.replace("if(ids.length!==1)throw Error('one qualified receiver required');await select.selectOption(ids[0]);","const map=JSON.parse(fs.readFileSync(path.join(runtime,'account-map.json')));const target=map.users.case_admin01.appointmentId;if(!ids.includes(target))throw Error('Configured Yang receiver not qualified');await select.selectOption(target);").replace("import {login,save}","import fs from 'node:fs';import path from 'node:path';import {login,save,runtime}")
        if name=='payment':text=text.replace("login(browser,'finance01')","login(browser,code.includes('G03')||code.includes('G04')?'finance02':'finance01')")
        runtime.private_file(steps/(name+'.mjs'),text.encode())
    shutil.copyfile(repo/'deploy/linux/verification/business-browser.mjs',steps/'browser.mjs');(steps/'browser.mjs').chmod(0o600)
    shutil.copyfile(repo/'deploy/linux/verification/business_chain.mjs',steps/'business_chain.mjs');(steps/'business_chain.mjs').chmod(0o600)
    journal._write(root,root/'verification/business-step-adaptation.json',{'sourceFiles':sources,'adaptedFiles':bundle.inventory(steps),
        'changes':['locked Linux Chromium module','signed native isolated runtime/browser PKCE','explicit Yang qualified receiver','finance selected by department']})
    return steps
