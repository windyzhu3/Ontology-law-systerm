"""Non-destructive whole-runtime checkpoint for isolated Haihua UAT only."""
from pathlib import Path
import sys,subprocess,shutil,hashlib,json,re
from prepare import RUNTIME,PREFIX,save
name=sys.argv[1]
if not re.fullmatch(r"[A-Z][A-Z0-9_-]{1,50}",name):raise SystemExit('invalid checkpoint name')
dest=RUNTIME/'checkpoints'/name
if dest.exists():raise SystemExit('preserve existing checkpoint')
dest.mkdir(parents=True)
records=[]
for container,database in [('business-db','law_contract_runtime'),('identity-db','identity_only')]:
 target=dest/(container+'.dump')
 with target.open('wb') as out:
  r=subprocess.run(['docker','exec',PREFIX+'-'+container,'pg_dump','-U',('identity_only' if container=='identity-db' else 'postgres'),'-d',database,'-Fc'],stdout=out,stderr=subprocess.PIPE)
 if r.returncode:raise RuntimeError('checkpoint dump failed: '+r.stderr.decode(errors='replace'))
 records.append({'file':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
 global_file=dest/(container+'-globals.sql')
 with global_file.open('wb') as out:
  result=subprocess.run(['docker','exec',PREFIX+'-'+container,'pg_dumpall','-U',('identity_only' if container=='identity-db' else 'postgres'),'--globals-only'],stdout=out,stderr=subprocess.PIPE)
 if result.returncode:raise RuntimeError('Global role checkpoint failed; inspect protected diagnostics')
 records.append({'file':global_file.name,'sha256':hashlib.sha256(global_file.read_bytes()).hexdigest()})
 metadata=subprocess.run(['docker','exec',PREFIX+'-'+container,'psql','-U',('identity_only' if container=='identity-db' else 'postgres'),'-d',database,'-At','-c',"select json_build_object('database',datname,'owner',pg_get_userbyid(datdba),'acl',datacl::text) from pg_database where datname=current_database()"],capture_output=True,check=True)
 metadata_file=dest/(container+'-database-boundary.json')
 metadata_file.write_bytes(metadata.stdout)
 records.append({'file':metadata_file.name,'sha256':hashlib.sha256(metadata_file.read_bytes()).hexdigest()})
for item in ['materials','secrets','certs','dist']:
 if (RUNTIME/item).exists():shutil.copytree(RUNTIME/item,dest/item)
for item in ['deployment.json','account-map.json','service-fixture.json','original-manifest.json','application.properties','worker.properties','app.jar','identity-trust.p12','server-client-trust.p12','service-public.pem','service.p12','service.crt','realm.json','server.mjs','browser-credentials.json']:
 if (RUNTIME/item).exists():shutil.copyfile(RUNTIME/item,dest/item)
(dest/'checkpoint-manifest.json').write_text(json.dumps({'checkpoint':name,'databases':records,'scope':'DB, identity, materials, secrets, certificates, artifacts, exact runtime configuration; restore not yet verified'},indent=2))
print('checkpoint saved; restore verification remains required: '+name)
