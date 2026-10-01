"""Preserve and replace only the explicitly selected, recorded Haihua SPA."""
import hashlib,json,shutil,sys
from datetime import datetime
import prepare as p
import apps

if sys.argv[1:] not in (['PERF_E1'], ['ORIGINAL']):
    raise SystemExit('PERF_E1 | ORIGINAL required')
original=p.RUNTIME
log=(original/'HH006-green.log').read_text(encoding='utf-8',errors='replace')
if 'Test Files  3 passed' not in log or 'failed' in log:
    raise RuntimeError('Affected HH006 regression must pass before deployment')
target=original if sys.argv[1]=='ORIGINAL' else original.parent/'haihua-restore-perf_e1'
prefix='ontology-law-haihua-uat' if target==original else 'ontology-law-haihua-restore-perf_e1'
live=(target/'dist').resolve()
if live.parent!=target.resolve() or not live.is_dir():raise RuntimeError('Unexpected owned artifact path')
registry=json.loads((target/'processes.json').read_text())
if 'spa' not in registry:raise RuntimeError('Own SPA must be running')
p.RUNTIME=target;p.PREFIX=prefix;p.JAR=target/'app.jar'
p.local.RUNTIME=target;p.local.PREFIX=prefix;p.local.JAR=p.JAR
apps.RUNTIME=target;apps.JAR=p.JAR
apps.build_spa()
if 'built in' not in (target/'spa-build.log').read_text(errors='replace'):raise RuntimeError('Own build required')
history=target/'artifact-history'/('HH006-SPA-'+datetime.now().strftime('%Y%m%d%H%M%S'))
history.mkdir(parents=True,exist_ok=False)
apps.stop(['spa'])
shutil.move(str(live),str(history/'dist'))
shutil.copyfile(target/'server.mjs',history/'server.mjs')
apps.start(['spa'])
digests={f.relative_to(live).as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for f in live.rglob('*') if f.is_file()}
(history/'replacement-digests.json').write_text(json.dumps(digests,indent=2),encoding='utf-8')
print('HH006 own SPA deployed; old exact bytes retained; actual navigation verification required')
