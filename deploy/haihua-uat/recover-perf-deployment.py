"""Recover the recorded HH005 deployment-path failure; no business/DB mutations."""
import prepare as p,apps
import json,hashlib,shutil,subprocess
original=p.RUNTIME;target=original.parent/'haihua-restore-perf_e1'
history=target/'artifact-history/HH005-20261001152149'
old=json.loads((history/'deployment.json').read_text());new=json.loads((original/'deployment.json').read_text())
digest=lambda file:hashlib.sha256(file.read_bytes()).hexdigest()
assert digest(original/'app.jar')==old['releaseDigest']
assert digest(target/'app.jar')==new['releaseDigest']
assert old['releaseDigest']!=new['releaseDigest']
row=subprocess.run(['docker','exec','ontology-law-haihua-restore-perf_e1-business-db','psql','-U','postgres','-d','law_contract_runtime','-At','-c','select encode(active_release_digest,\'hex\') from platform_meta.deployment_state'],capture_output=True,check=True).stdout.decode().strip()
assert row==new['releaseDigest']
registry=json.loads((original/'processes.json').read_text());assert set(registry)=={'spa'}
assert str(target).lower() in ' '.join(registry['spa']['args']).lower()
for name in ('api','worker'):
 record=json.loads((target/'processes.json').read_text())[name]
 check=subprocess.run(['pwsh','-NoProfile','-Command',f"@(Get-Process -Id {int(record['pid'])} -ErrorAction SilentlyContinue).Count"],capture_output=True,check=True)
 assert check.stdout.decode().strip()=='0'
preserved=target/'deployment-path-failure';preserved.mkdir()
for name in ('deployment.json','processes.json'):shutil.copyfile(original/name,preserved/('original-misdirected-'+name));shutil.copyfile(target/name,preserved/('perf-before-recovery-'+name))
(original/'deployment.json').write_text(json.dumps(old,indent=2))
(original/'processes.json').write_text('{}')
(target/'deployment.json').write_text(json.dumps(new,indent=2))
(target/'processes.json').write_text(json.dumps(registry,indent=2))
p.RUNTIME=target;p.JAR=target/'app.jar';p.PREFIX='ontology-law-haihua-restore-perf_e1';p.local.RUNTIME=target;p.local.JAR=p.JAR;p.local.PREFIX=p.PREFIX;apps.RUNTIME=target;apps.JAR=p.JAR
apps.start(['api','worker'])
p.save('HH005-deployment.json',{'oldSha256':old['releaseDigest'],'newSha256':new['releaseDigest'],'previousArtifactDirectory':str(history),'readinessVerified':False,'pathFailurePreserved':str(preserved)})
print('Recorded metadata recovered; original artifact remains exact, own API/Worker started; verify readiness')
