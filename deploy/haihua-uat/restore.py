"""Independent physical restore instance, sequentially using the exact canonical OIDC ports.

Never replaces or deletes the original databases, volumes, files or containers.
Prepare captures supplementary immutable infrastructure inputs; activate stops only
the original recorded applications/containers; return restores their availability.
"""
from pathlib import Path
import sys,json,shutil,subprocess,hashlib,os,time,ssl,urllib.request
import prepare as p
import apps
original=p.RUNTIME;original_prefix=p.PREFIX
operation,checkpoint=sys.argv[1:3]
if checkpoint not in ('E1','G_COMPLETED','PERF_E1'):raise SystemExit('approved checkpoints only')
source=original/'checkpoints'/('E1' if checkpoint=='PERF_E1' else checkpoint)
target=original.parent/('haihua-restore-'+checkpoint.lower())
prefix='ontology-law-haihua-restore-'+checkpoint.lower()
def command(args,data=None):
 r=subprocess.run([str(x) for x in args],input=data,capture_output=True)
 if r.returncode:
  (target/('operation-failure-'+str(time.time_ns())+'.txt')).write_bytes(r.stderr+r.stdout)
  raise RuntimeError('restore operation failed; private diagnostics saved, preserve resources')
 return r.stdout
def save(name,value):
 (target/name).write_text(json.dumps(value,indent=2) if not isinstance(value,str) else value,encoding='utf-8')
def point():
 p.RUNTIME=target;p.PREFIX=prefix;p.JAR=target/'app.jar';p.local.RUNTIME=target;p.local.PREFIX=prefix;p.local.JAR=p.JAR
 apps.RUNTIME=target;apps.JAR=p.JAR
def issuer_ready(folder):
 deadline=time.monotonic()+60;context=ssl.create_default_context(cafile=str(folder/'certs/ca.pem'))
 while time.monotonic()<deadline:
  try:
   with urllib.request.urlopen(p.ISSUER+'/.well-known/openid-configuration',context=context,timeout=2) as response:
    if response.status==200:return
  except Exception:time.sleep(1)
 raise RuntimeError('issuer not ready within 60 seconds; preserve and inspect')
def prepare():
 if target.exists():raise RuntimeError('restore instance already exists; preserve it')
 target.mkdir()
 sid=command(['pwsh','-NoProfile','-Command','[Security.Principal.WindowsIdentity]::GetCurrent().User.Value']).decode().strip()
 command(['icacls',target,'/inheritance:r','/grant:r','*'+sid+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'])
 shutil.copytree(source,target,dirs_exist_ok=True)
 if checkpoint=='PERF_E1':
  # E1 business/identity baseline; performance measures the current verified
  # runtime artifact, rather than the historical pre-fix E1 application.
  if 'BUILD SUCCESS' not in (original/'HH003-refined-green.log').read_text(errors='replace'):raise RuntimeError('Verified runtime prerequisite absent')
  shutil.move(str(target/'dist'),str(target/'e1-historical-dist'))
  shutil.copytree(original/'dist',target/'dist')
  for name in ('app.jar','application.properties','worker.properties','deployment.json'):
   shutil.copyfile(original/name,target/name)
  save('performance-artifact-inputs.json',{'businessBaseline':'E1','jarSha256':hashlib.sha256((target/'app.jar').read_bytes()).hexdigest(),'runtimeSource':'current verified original runtime','baselineUpgradeRequired':True})
 # First checkpoints omitted root trust/public-key files. Recover only immutable
 # files whose write timestamp predates that original checkpoint; never overwrite.
 supplements=[];cutoff=(source/'checkpoint-manifest.json').stat().st_mtime
 for name in ('server-client-trust.p12','service-public.pem','service.crt','realm.json'):
  if (target/name).exists():continue
  file=original/name
  if not file.is_file() or file.stat().st_mtime>cutoff:raise RuntimeError('missing checkpoint dependency cannot be reconstructed accurately: '+name)
  shutil.copyfile(file,target/name);supplements.append({'file':name,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'source':'immutable infrastructure file; last-write predates checkpoint'})
 for database,user in [('business-db','postgres'),('identity-db','identity_only')]:
  if (target/(database+'-globals.sql')).is_file():continue
  data=command(['docker','exec',original_prefix+'-'+database,'pg_dumpall','-U',user,'--globals-only'])
  (target/(database+'-globals.sql')).write_bytes(data)
  supplements.append({'file':database+'-globals.sql','sha256':hashlib.sha256(data).hexdigest(),'source':'original isolated infrastructure roles captured now; not a business-data snapshot'})
 for name in ('application.properties','worker.properties'):
  text=(target/name).read_text(encoding='utf-8').replace(original.as_posix(),target.as_posix()).replace(str(original),str(target))
  (target/name).write_text(text,encoding='utf-8')
 save('restore-inputs.json',{'checkpoint':checkpoint,'supplements':supplements,'originalPreserved':True,'ports':'same canonical issuer/ports, separate physical volumes, sequential operation'})
 command(['docker','cp',original_prefix+'-clamav:/var/lib/clamav',str(target/'scanner-db')])
 print('Independent protected restore inputs prepared; original stack still running')
def activate():
 if not (target/'restore-inputs.json').is_file():raise RuntimeError('prepare first')
 if command(['docker','ps','-a','--filter','name='+prefix,'--format','{{.Names}}']).strip():raise RuntimeError('existing restore resources; inspect original state, do not replace')
 apps.stop(['worker','api','spa'])
 command(['docker','stop',*[original_prefix+'-'+name for name in ('keycloak','business-db','identity-db','clamav')]])
 point();p.infrastructure()
 command(['docker','stop',prefix+'-keycloak'])
 for db,user,name in [('business-db','postgres','law_contract_runtime'),('identity-db','identity_only','identity_only')]:
  globals_text=(target/(db+'-globals.sql')).read_text(encoding='utf-8')
  globals_text='\n'.join(line for line in globals_text.splitlines() if line not in ('CREATE ROLE postgres;','CREATE ROLE identity_only;'))
  command(['docker','exec','-i',prefix+'-'+db,'psql','-v','ON_ERROR_STOP=1','-U',user,'-d',name],globals_text.encode())
  command(['docker','exec','-i',prefix+'-'+db,'pg_restore','--exit-on-error','--clean','--if-exists','-U',user,'-d',name],(source/(db+'.dump')).read_bytes())
 restore_database_boundary()
 if checkpoint=='PERF_E1':
  old=json.loads((source/'deployment.json').read_text())['releaseDigest'];new=json.loads((target/'deployment.json').read_text())['releaseDigest']
  sql="update platform_meta.deployment_state set active_release_digest=decode('"+new+"','hex'),revision=revision+1,changed_at=clock_timestamp() where active_release_digest=decode('"+old+"','hex');"
  command(['docker','exec','-i',prefix+'-business-db','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','law_contract_runtime'],sql.encode())
 command(['docker','start',prefix+'-keycloak'])
 clam=json.loads(command(['docker','inspect',original_prefix+'-clamav']))[0]
 command(['docker','volume','create',prefix+'-clamav-data'])
 command(['docker','create','--name',prefix+'-clamav','--memory',str(clam['HostConfig']['Memory']),'--cpus',str(clam['HostConfig']['NanoCpus']/1000000000),'-v',prefix+'-clamav-data:/var/lib/clamav','-p','127.0.0.1:20547:3310',clam['Config']['Image'],*(clam['Config']['Cmd'] or [])]);command(['docker','cp',str(target/'scanner-db')+'/.',prefix+'-clamav:/var/lib/clamav']);command(['docker','start',prefix+'-clamav'])
 # The checkpoint already contains exact front-end bytes; apps.start refuses
 # an existing dist, so temporarily preserve it and restore before launch.
 held=target/'restored-dist';shutil.move(str(target/'dist'),str(held))
 issuer_ready(target);apps.start(['api','worker','spa'])
 apps.stop(['spa']);built=target/'dist';shutil.move(str(built),str(target/'unused-current-build'));shutil.move(str(held),str(built))
 # Launch restored SPA without copying newer files.
 registry=json.loads((target/'processes.json').read_text());args=[str(p.NODE),str(target/'server.mjs'),str(target)]
 with (target/'spa.stdout').open('ab') as out,(target/'spa.stderr').open('ab') as err:
  process=subprocess.Popen(args,cwd=p.ROOT,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
 registry['spa']={'pid':process.pid,'args':args};save('processes.json',registry)
 save('restore-state.json',{'checkpoint':checkpoint,'state':'STARTED_VERIFY_REQUIRED','originalVolumesPreserved':True})
 print('Separate restored instance started with exact checkpoint DB/artifact/materials; verification pending')
def return_original():
 point();apps.stop(['worker','api','spa'])
 command(['docker','stop',*[prefix+'-'+name for name in ('keycloak','business-db','identity-db','clamav')]])
 command(['docker','start',*[original_prefix+'-'+name for name in ('identity-db','business-db','keycloak','clamav')]])
 p.RUNTIME=original;p.PREFIX=original_prefix;p.JAR=original/'app.jar';p.local.RUNTIME=original;p.local.PREFIX=original_prefix;p.local.JAR=p.JAR;apps.RUNTIME=original;apps.JAR=p.JAR
 held=original/'dist-return-held'
 if held.exists():raise RuntimeError('existing preserved front-end artifact')
 shutil.move(str(original/'dist'),str(held));issuer_ready(original);apps.start(['api','worker','spa']);apps.stop(['spa']);shutil.move(str(original/'dist'),str(original/('dist-return-build-'+str(time.time_ns()))));shutil.move(str(held),str(original/'dist'))
 registry=json.loads((original/'processes.json').read_text());args=[str(p.NODE),str(original/'server.mjs'),str(original)]
 with (original/'spa.stdout').open('ab') as out,(original/'spa.stderr').open('ab') as err:process=subprocess.Popen(args,cwd=p.ROOT,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
 registry['spa']={'pid':process.pid,'args':args};(original/'processes.json').write_text(json.dumps(registry,indent=2))
 print('Original physical stack resumed; restored physical volumes and evidence preserved')
def restart_restored_apps():
 point()
 with urllib.request.urlopen(p.ISSUER+'/.well-known/openid-configuration',context=ssl.create_default_context(cafile=str(target/'certs/ca.pem')),timeout=10) as response:
  if response.status!=200:raise RuntimeError('restored issuer not ready')
 apps.stop(['worker','api']);apps.start(['api','worker'])
 print('Restored issuer verified with its trusted CA; API/Worker restarted, readiness pending')
def restore_database_boundary():
 # pg_dump without --create omits database owner and database ACL. Restore the
 # exact original V830 + runtime bootstrap boundary, never grant object access.
 boundary='ALTER DATABASE law_contract_runtime OWNER TO law_schema_migrator; REVOKE ALL PRIVILEGES ON DATABASE law_contract_runtime FROM PUBLIC; GRANT CONNECT ON DATABASE law_contract_runtime TO law_api_login,law_worker_login;'
 command(['docker','exec','-i',prefix+'-business-db','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','law_contract_runtime'],boundary.encode())
 save('database-boundary-restored.json',{'source':'V830__application_privileges.sql and bootstrap-runtime-logins.sql','owner':'law_schema_migrator','publicPrivileges':[],'runtimePrivileges':['CONNECT'],'businessObjectGrantsAdded':False})
if operation=='prepare':prepare()
elif operation=='activate':activate()
elif operation=='return':return_original()
elif operation=='restart-apps':restart_restored_apps()
elif operation=='restore-database-boundary':restore_database_boundary()
else:raise SystemExit('prepare | activate | return with E1 or G_COMPLETED')
