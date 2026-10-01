"""Execute approved real UI branch steps once; stop and preserve uncertain writes."""
import json,os,subprocess,sys
from pathlib import Path
import prepare as p
branch=sys.argv[1]
cases={'B10':('sales04','sales_manager02'),'B11':('sales05','sales_manager02'),'B22':('sales01','sales_manager01'),'B23':('sales03','sales_manager01'),'B24':('sales04','sales_manager02'),'B19':('sales02','sales_manager01'),'B15':('sales04','sales_manager02')}
if branch not in cases or len(sys.argv)!=2:raise SystemExit('Approved B10/B11/B15/B19/B22/B23/B24 only')
sales,manager=cases[branch];code='HH-'+branch+'-20261001-R2'
steps=[('capture',['capture-lead',sales,code]),('assign',['assign-lead',manager,sales,code]),('contact',['contact-lead',sales,code]),('customer',['customer',sales,code])]
if branch=='B15':steps[-1][1].append('opposing-existing')
if branch in ('B10','B11'):steps += [('proof',['upload-material',sales,code,str(p.RUNTIME/'fixtures'/f'{code}-quote-proof-SYNTHETIC-NOT-LEGAL.pdf')]),('quote',['quote-prepare',sales,code]),('approval',['quote-decide',manager,code,'APPROVED']),('reply',['quote-accept',sales,code,'AMBIGUOUS' if branch=='B10' else 'REJECTED'])]
state=p.RUNTIME/(code+'-branch-run.json')
records=json.loads(state.read_text()) if state.exists() else {}
env=os.environ.copy();env['HAIHUA_UAT_RUNTIME']='.superpowers/haihua-uat-runtime';env['NODE_EXTRA_CA_CERTS']=str(p.RUNTIME/'certs/ca.pem')
for name,args in steps:
    if name in records:
        if records[name]['status']=='PASS':continue
        raise RuntimeError('Recorded uncertain step; inspect original receipt, never automatically replay: '+name)
    records[name]={'status':'DISPATCHED'};state.write_text(json.dumps(records,indent=2),encoding='utf-8')
    with (p.RUNTIME/(code+'-branch-'+name+'.log')).open('wb') as out:
        result=subprocess.run([str(p.NODE),str(p.ROOT/'deploy/haihua-uat'/(args[0]+'.mjs')),*args[1:]],cwd=p.ROOT,env=env,stdout=out,stderr=subprocess.STDOUT)
    records[name]={'status':'PASS' if result.returncode==0 else 'INSPECT_REQUIRED','exitCode':result.returncode};state.write_text(json.dumps(records,indent=2),encoding='utf-8')
    if result.returncode:raise RuntimeError('Actual step failed; private log and facts preserved: '+name)
    print(code+' actual UI '+name+' PASS',flush=True)
