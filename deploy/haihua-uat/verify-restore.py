"""Read-only restore counts, material/key/artifact digest comparisons. Login is separate."""
from pathlib import Path
import sys,hashlib,json,subprocess
from prepare import RUNTIME
checkpoint=sys.argv[1]
if checkpoint not in ('E1','G_COMPLETED'):raise SystemExit('approved checkpoint only')
source=RUNTIME/'checkpoints'/checkpoint;target=RUNTIME.parent/('haihua-restore-'+checkpoint.lower());prefix='ontology-law-haihua-restore-'+checkpoint.lower()
records=[]
for item in ('app.jar','identity-trust.p12','service.p12','browser-credentials.json'):
 equal=hashlib.sha256((source/item).read_bytes()).digest()==hashlib.sha256((target/item).read_bytes()).digest();records.append({'item':item,'result':'PASS' if equal else 'FAIL'})
for folder in ('materials','secrets','certs','dist'):
 files=list((source/folder).rglob('*'));total=0;bad=[]
 for file in files:
  if not file.is_file():continue
  relative=file.relative_to(source);other=target/relative;total+=1
  if not other.is_file() or hashlib.sha256(file.read_bytes()).digest()!=hashlib.sha256(other.read_bytes()).digest():bad.append(str(relative))
 records.append({'item':folder,'files':total,'mismatches':bad,'result':'PASS' if not bad else 'FAIL'})
sql="select (select count(*) from lead.lead),(select count(*) from opportunity.opportunity),(select count(*) from contract.contract),(select count(distinct matter_id) from transfer.transfer_request where matter_id is not null),(select count(*) from identity.authority_grant where state='ACTIVE');"
r=subprocess.run(['docker','exec',prefix+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-c',sql],capture_output=True,check=True)
counts=list(map(int,r.stdout.decode().strip().split('|')));expected=[1,1,0,0,168] if checkpoint=='E1' else [13,11,10,5,168]
records.append({'item':'restored-fact-counts','order':['leads','opportunities','contracts','uniqueMatters','activeGrants'],'expected':expected,'actual':counts,'result':'PASS' if counts==expected else 'FAIL'})
(target/'restore-verification.json').write_text(json.dumps(records,indent=2))
for record in records:print(record['item']+': '+record['result'])
if any(r['result']=='FAIL' for r in records):raise SystemExit(1)
