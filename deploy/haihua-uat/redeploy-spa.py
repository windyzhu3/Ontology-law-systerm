"""Guarded front-end update of only the approved isolated UAT runtime."""
from pathlib import Path
from datetime import datetime
import json,shutil,hashlib
from prepare import ROOT,RUNTIME
from apps import stop,start
log=RUNTIME/'HH004-final-green.log'
if '97 passed' not in log.read_text(encoding='utf-8'):raise RuntimeError('HH004 affected regression evidence required')
if 'built in' not in (RUNTIME/'spa-build.log').read_text(encoding='utf-8'):raise RuntimeError('own OIDC build required')
live=(RUNTIME/'dist').resolve()
if live.parent!=RUNTIME.resolve() or not live.is_dir():raise RuntimeError('unexpected live artifact path')
backup=RUNTIME/'artifact-history'/('HH004-SPA-'+datetime.now().strftime('%Y%m%d%H%M%S'))
backup.mkdir(parents=True,exist_ok=False)
stop(['spa'])
shutil.move(str(live),str(backup/'dist'))
shutil.copyfile(RUNTIME/'server.mjs',backup/'server.mjs')
start(['spa'])
files={p.relative_to(live).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in live.rglob('*') if p.is_file()}
(backup/'replacement-digests.json').write_text(json.dumps(files,indent=2))
print('Only own SPA updated; previous exact files preserved; actual browser verification required')
