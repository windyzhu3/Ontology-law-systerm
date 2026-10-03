"""One approved V1070 upgrade of the original UAT; never reinitialize its accounts or facts."""
from datetime import datetime
import hashlib,json,shutil,subprocess,sys
import prepare as p
import apps

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def tree_sha(directory):
 entries=[(file.relative_to(directory).as_posix(),sha(file)) for file in sorted(directory.rglob('*')) if file.is_file()]
 if not entries or not (directory/'index.html').is_file():raise RuntimeError('SPA build incomplete')
 return hashlib.sha256(json.dumps(entries,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def sql(query):
 result=subprocess.run(['docker','exec',p.PREFIX+'-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-v','ON_ERROR_STOP=1','-c',query],capture_output=True,check=True)
 return result.stdout.decode().strip()
def snapshot():
 # Old configuration and core business facts only. Audit/receipts and technical schedules may legitimately append.
 result={}
 for table in ('identity.principal','identity.organization_unit','identity.appointment','identity.authority_grant','lead.lead','opportunity.opportunity','contract.contract','transfer.transfer_request','transfer.intake'):
  result[table]=json.loads(sql("select json_build_object('count',count(*),'digest',md5(coalesce(string_agg(to_jsonb(t)::text,E'\\n' order by to_jsonb(t)::text),''))) from "+table+" t"))
 result['humanAppointments']=int(sql("select count(*) from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id where p.principal_kind='HUMAN'"))
 result['acceptedMatters']=int(sql("select count(*) from transfer.intake where outcome_code='ACCEPT'"))
 return result

def main():
 if p.RUNTIME!=p.ROOT/'.superpowers/haihua-uat-runtime':raise RuntimeError('Original approved UAT required')
 journal=p.RUNTIME/'ROLE_CONFIG-upgrade.json'
 if journal.exists():raise RuntimeError('Existing upgrade journal: inspect exact stage; never repeat fresh upgrade')
 proof=json.loads((p.RUNTIME/'ROLE_CONFIG-verification.json').read_text())
 source=p.ROOT/'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
 if proof['jarSha256']!=sha(source) or proof['manifestHash']!=sha(p.ROOT/'database/schema-contract-52-plus-2/generated/schema-contract-manifest.json'):raise RuntimeError('Verified artifact differs')
 if proof['spaTreeSha256']!=tree_sha(p.ROOT/'apps/workbench/dist') or proof['spaServerSha256']!=sha(p.ROOT/'deploy/haihua-uat/server.mjs'):raise RuntimeError('Verified SPA artifact differs')
 if not proof.get('backendPassed') or not proof.get('frontendPassed') or not proof.get('typecheckPassed') or not proof.get('buildPassed'):raise RuntimeError('Required verification missing')
 checkpoint=p.RUNTIME/'checkpoints/ROLE_CONFIG_BEFORE'
 for entry in json.loads((checkpoint/'checkpoint-manifest.json').read_text())['databases']:
  if sha(checkpoint/entry['file'])!=entry['sha256']:raise RuntimeError('Backup digest mismatch')
 p.run([p.NODE,'--input-type=module','-e',"import {assertRuntimeActive} from './deploy/haihua-uat/runtime-ownership.mjs'; assertRuntimeActive('.superpowers/haihua-uat-runtime');"],'ROLE_CONFIG-own-runtime')
 old=p.deployment()
 if old['schemaVersion']!='52-plus-2-r2-v20' or sha(p.JAR)!=old['releaseDigest']:raise RuntimeError('Expected retained V1060 artifact')
 before=snapshot()
 if before['humanAppointments']!=10 or before['acceptedMatters']!=8:raise RuntimeError('Approved UAT facts differ')
 history=p.RUNTIME/'artifact-history'/('ROLE_CONFIG-'+datetime.now().strftime('%Y%m%d%H%M%S'));history.mkdir(parents=True,exist_ok=False)
 for name in ('app.jar','application.properties','worker.properties','deployment.json','operator.json','server.mjs'):
  if (p.RUNTIME/name).exists():shutil.copyfile(p.RUNTIME/name,history/name)
 state={'stage':'VERIFIED','previousArtifactDirectory':str(history),'before':before,'old':old,'verified':proof}
 with journal.open('x',encoding='utf-8') as handle:json.dump(state,handle,ensure_ascii=False,indent=2)
 def stage(value):state['stage']=value;journal.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
 apps.stop(['worker','api','spa']);stage('STOPPED')
 shutil.move(str(p.RUNTIME/'dist'),str(history/'dist'))
 lock=json.loads((p.ROOT/'database/schema-contract-52-plus-2/runtime/toolchain.lock.json').read_text())
 image=next(item['image']+'@'+item['digest'] for item in lock['images'] if item['image']=='redgate/flyway')
 p.local.docker('run','--rm','--name',p.PREFIX+'-roles-flyway','--memory','768m','--network',p.PREFIX+'-business',
  '-v',str(p.RUNTIME/'flyway.conf')+':/flyway/conf/local.conf:ro','-v',str(p.RUNTIME/'certs/ca.pem')+':/flyway/local-ca.pem:ro',
  '-v',str(p.ROOT/'database/schema-contract-52-plus-2/generated/db/migration')+':/flyway/sql:ro',image,'-configFiles=/flyway/conf/local.conf','migrate','validate',label='ROLE_CONFIG-flyway')
 stage('MIGRATED')
 # This is the explicit deployment boundary, not a business-fact write.
 new=proof['jarSha256'];manifest=proof['manifestHash']
 changed=sql("update platform_meta.deployment_state set active_release_digest=decode('"+new+"','hex'),active_manifest_hash=decode('"+manifest+"','hex'),revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY' and schema_contract_version='52-plus-2-r2-v21' and active_release_digest=decode('"+old['releaseDigest']+"','hex') and active_manifest_hash=decode('"+old['manifestHash']+"','hex') returning revision")
 if 'UPDATE 1' not in changed:raise RuntimeError('Exact deployment boundary failed')
 for name in ('application.properties','worker.properties'):
  file=p.RUNTIME/name;content=file.read_text()
  for prior,current in ((old['releaseDigest'],new),(old['manifestHash'],manifest),(old['schemaVersion'],'52-plus-2-r2-v21')):
   if prior not in content:raise RuntimeError('Expected configuration value absent')
   content=content.replace(prior,current)
  file.write_text(content)
 dep={**old,'releaseDigest':new,'manifestHash':manifest,'schemaVersion':'52-plus-2-r2-v21'};p.save('deployment.json',dep)
 operator_file=p.RUNTIME/'operator.json';operator=json.loads(operator_file.read_text());operator['database'].update(schemaVersion=dep['schemaVersion'],releaseDigest=new,manifestHash=manifest);operator_file.write_text(json.dumps(operator,ensure_ascii=False,indent=2))
 shutil.copyfile(source,p.JAR);stage('CONFIGURED')
 apps.start(['api','worker','spa']);stage('STARTED')
 if proof['spaTreeSha256']!=tree_sha(p.RUNTIME/'dist') or proof['spaServerSha256']!=sha(p.RUNTIME/'server.mjs'):stage('SPA_MISMATCH');raise RuntimeError('Installed SPA differs from verified artifact')
 after=snapshot();state['after']=after
 if after!=before:stage('FACT_MISMATCH');raise RuntimeError('Old facts changed during migration; inspect retained evidence')
 stage('FACTS_PRESERVED');print('V1070 applied; original 10 human appointments and 8 accepted cases preserved; readiness/UI verification pending')
def repair_api():
 # The one upgrade journal is retained. A separately verified API-only repair
 # cannot repeat migration, replace the SPA, or change business configuration.
 journal=p.RUNTIME/'ROLE_CONFIG-session-repair.json'
 if journal.exists():raise RuntimeError('Existing repair journal: inspect original attempt')
 upgrade=json.loads((p.RUNTIME/'ROLE_CONFIG-upgrade.json').read_text())
 if upgrade['stage']!='FACTS_PRESERVED':raise RuntimeError('Complete original upgrade required')
 proof=json.loads((p.RUNTIME/'ROLE_CONFIG-session-verification.json').read_text())
 source=p.ROOT/'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar';old=p.deployment()
 if old['schemaVersion']!='52-plus-2-r2-v21' or sha(p.JAR)!=old['releaseDigest']:raise RuntimeError('Expected exact upgraded artifact')
 if not proof.get('backendPassed') or sha(source)!=proof['jarSha256'] or sha(p.ROOT/'database/schema-contract-52-plus-2/generated/schema-contract-manifest.json')!=proof['manifestHash'] or old['manifestHash']!=proof['manifestHash']:raise RuntimeError('Verified repair differs')
 if tree_sha(p.RUNTIME/'dist')!=proof['spaTreeSha256'] or sha(p.RUNTIME/'server.mjs')!=proof['spaServerSha256']:raise RuntimeError('Retained verified SPA changed')
 if proof['jarSha256']==old['releaseDigest']:raise RuntimeError('Repair already deployed')
 p.run([p.NODE,'--input-type=module','-e',"import {assertRuntimeActive} from './deploy/haihua-uat/runtime-ownership.mjs'; assertRuntimeActive('.superpowers/haihua-uat-runtime');"],'ROLE_CONFIG-repair-own-runtime')
 before=snapshot()
 if before!=upgrade['before']:raise RuntimeError('Original business facts changed before repair')
 history=p.RUNTIME/'artifact-history'/('ROLE_CONFIG-session-'+datetime.now().strftime('%Y%m%d%H%M%S'));history.mkdir(parents=True,exist_ok=False)
 for name in ('app.jar','application.properties','worker.properties','deployment.json','operator.json'):shutil.copyfile(p.RUNTIME/name,history/name)
 state={'stage':'VERIFIED','old':old,'verified':proof,'before':before,'previousArtifactDirectory':str(history)}
 with journal.open('x') as handle:json.dump(state,handle,indent=2)
 def stage(value):state['stage']=value;journal.write_text(json.dumps(state,indent=2))
 apps.stop(['worker','api']);stage('STOPPED')
 new=proof['jarSha256']
 changed=sql("update platform_meta.deployment_state set active_release_digest=decode('"+new+"','hex'),revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY' and schema_contract_version='52-plus-2-r2-v21' and active_release_digest=decode('"+old['releaseDigest']+"','hex') and active_manifest_hash=decode('"+old['manifestHash']+"','hex') returning revision")
 if 'UPDATE 1' not in changed:raise RuntimeError('Exact repair deployment guard failed')
 for name in ('application.properties','worker.properties'):
  file=p.RUNTIME/name;content=file.read_text()
  if old['releaseDigest'] not in content:raise RuntimeError('Expected old release missing')
  file.write_text(content.replace(old['releaseDigest'],new))
 p.save('deployment.json',{**old,'releaseDigest':new})
 operator_file=p.RUNTIME/'operator.json';operator=json.loads(operator_file.read_text());operator['database']['releaseDigest']=new;operator_file.write_text(json.dumps(operator,ensure_ascii=False,indent=2))
 shutil.copyfile(source,p.JAR);stage('CONFIGURED');apps.start(['api','worker']);stage('STARTED')
 state['after']=snapshot()
 if state['after']!=before:stage('FACT_MISMATCH');raise RuntimeError('Old facts changed during repair')
 stage('FACTS_PRESERVED');print('Verified session serialization repair deployed; original facts and SPA preserved')
if __name__=='__main__':
 if sys.argv[1:]==['--verified-session-repair']:repair_api()
 elif not sys.argv[1:]:main()
 else:raise SystemExit('No arguments for migration, or --verified-session-repair after verification')
