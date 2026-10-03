"""Redeploy a verified artifact only in the isolated Haihua runtime; preserve previous artifact."""
from pathlib import Path
import hashlib,json,shutil,subprocess,sys
from datetime import datetime
from prepare import ROOT,RUNTIME,PREFIX,deployment,save,JAR
import apps
from apps import stop,start
import prepare as p
source=ROOT/'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
args=sys.argv[1:]
if args not in (['HH003'],['HH005'],['HH005','PERF_E1'],['HH007'],['HH008'],['HH009'],['HH010']):raise SystemExit('explicit verified change identifier required')
change=args[0]
original_runtime=RUNTIME
log=(original_runtime/{'HH003':'HH003-refined-green.log','HH005':'HH005-green.log','HH007':'HH007-green.log','HH008':'HH008-green.log','HH009':'HH009-green.log','HH010':'HH010-green.log'}[change]).read_text(errors='replace')
if 'BUILD SUCCESS' not in log:raise RuntimeError('required regression not successful')
if change=='HH005':
 if 'BUILD SUCCESS' not in (original_runtime/'HH005-http-green.log').read_text(errors='replace'):raise RuntimeError('AI HTTP/source regressions required')
if args==['HH005','PERF_E1']:
 RUNTIME=original_runtime.parent/'haihua-restore-perf_e1';PREFIX='ontology-law-haihua-restore-perf_e1';JAR=RUNTIME/'app.jar'
 if len(json.loads((RUNTIME/'performance-background.json').read_text())['cases'])!=100:raise RuntimeError('Complete background before controlled redeploy')
 p.RUNTIME=RUNTIME;p.PREFIX=PREFIX;p.JAR=JAR;p.local.RUNTIME=RUNTIME;p.local.PREFIX=PREFIX;p.local.JAR=JAR;apps.RUNTIME=RUNTIME;apps.JAR=JAR
new=hashlib.sha256(source.read_bytes()).hexdigest();dep=deployment();old=dep['releaseDigest']
if new==old:raise RuntimeError('preserve already deployed artifact')
if hashlib.sha256(JAR.read_bytes()).hexdigest()!=old:raise RuntimeError('old artifact integrity differs')
stop(['worker','api'])
history=RUNTIME/'artifact-history'/(change+'-'+datetime.now().strftime('%Y%m%d%H%M%S'));history.mkdir(parents=True)
for item in ['app.jar','application.properties','worker.properties','deployment.json']:shutil.copyfile(RUNTIME/item,history/item)
sql="update platform_meta.deployment_state set active_release_digest=decode('"+new+"','hex'),revision=revision+1,changed_at=clock_timestamp() where active_release_digest=decode('"+old+"','hex') returning revision;"
r=subprocess.run(['docker','exec',PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-v','ON_ERROR_STOP=1','-At','-c',sql],capture_output=True,check=True)
if 'UPDATE 1' not in r.stdout.decode():raise RuntimeError('exact old deployment guard did not update one row')
shutil.copyfile(source,JAR);dep['releaseDigest']=new;save('deployment.json',dep)
for name in ['application.properties','worker.properties']:
 p=RUNTIME/name;content=p.read_text();
 if old not in content:raise RuntimeError('config lacks exact old artifact digest')
 p.write_text(content.replace(old,new))
start(['api','worker']);save(change+'-deployment.json',{'oldSha256':old,'newSha256':new,'previousArtifactDirectory':str(history),'readinessVerified':False})
print('Verified artifact deployed in own runtime; readiness and actual login still require checks')
