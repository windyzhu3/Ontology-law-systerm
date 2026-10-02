"""Deploy the verified logout-route fix only to the retained original UAT SPA."""
from datetime import datetime
import hashlib
import json
import shutil
import prepare as p
import apps

if p.RUNTIME != p.ROOT / '.superpowers/haihua-uat-runtime':
    raise RuntimeError('Original approved runtime required')
log = (p.RUNTIME / 'account-switch-green.log').read_text(encoding='utf-8')
if '80 passed' not in log or 'failed' in log.lower():
    raise RuntimeError('Affected session and OIDC regressions must pass')
if 'built in' not in (p.RUNTIME / 'spa-build.log').read_text(encoding='utf-8'):
    raise RuntimeError('Own configured SPA build required')
live = (p.RUNTIME / 'dist').resolve()
if live.parent != p.RUNTIME.resolve() or not live.is_dir():
    raise RuntimeError('Unexpected original SPA artifact path')
history = p.RUNTIME / 'artifact-history' / ('HH011-SPA-' + datetime.now().strftime('%Y%m%d%H%M%S'))
history.mkdir(parents=True, exist_ok=False)
apps.stop(['spa'])
shutil.move(str(live), str(history / 'dist'))
shutil.copyfile(p.RUNTIME / 'server.mjs', history / 'server.mjs')
apps.start(['spa'])
digests = {f.relative_to(live).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest()
           for f in live.rglob('*') if f.is_file()}
(history / 'replacement-digests.json').write_text(json.dumps(digests, indent=2), encoding='utf-8')
print('HH011 original SPA deployed; old bytes retained; actual account switch verification required')
