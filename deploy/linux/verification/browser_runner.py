"""One registered Linux browser with a private NSS CA store. No TLS bypass."""
import argparse
import json
from pathlib import Path
import re
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import identity,journal,runtime
from ols_linux.config import digest


STARTUP='''set -eu
apt-get update >/tmp/ols-browser-tools.log 2>&1
apt-get install -y --no-install-recommends libnss3-tools >>/tmp/ols-browser-tools.log 2>&1
mkdir -p /root/.local/share/pki/nssdb
if [ ! -f /root/.local/share/pki/nssdb/cert9.db ]; then timeout 10s certutil -N -d sql:/root/.local/share/pki/nssdb --empty-password </dev/null >/dev/null 2>&1; fi
timeout 10s certutil -A -d sql:/root/.local/share/pki/nssdb -n OLS-Isolated-Acceptance -t 'C,,' -i "$1/certs/ca.pem" </dev/null >/dev/null 2>&1
test "$(node -p 'require("/tools/node_modules/@playwright/test/package.json").version')" = 1.63.0
node /tools/deploy/linux/verification/identity_login.mjs --runtime "$1"
'''


def browser_resource(root, resources, plan, launch_digest):
    base=resources['name']+'-browser'
    name=resources['containers'].get('browser',base)
    if name not in {base,base+'-v2'}:raise RuntimeError('Unexpected registered browser resource')
    existing=runtime.inspect('container',name)
    if existing:
        existing=runtime.owned(root,'container',name)
        labels=existing['Config']['Labels']
        if existing['State']['Running'] or labels.get('ols.operation')!=plan['operationId']:
            raise RuntimeError('Prior browser still running or belongs to another operation')
        if labels.get('ols.browser-launch')!=launch_digest:
            if name!=base or labels.get('ols.browser-launch') is not None:
                raise RuntimeError('Original browser launch changed')
            # Preserve the stopped pre-fix diagnostic container and its NSS files.
            name=base+'-v2'
            if runtime.inspect('container',name):raise RuntimeError('Replacement browser name already exists; not adopted')
            resources['containers']['browserPrevious']=base
            resources['containers']['browser']=name
            runtime.save(root,resources)
            existing=None
    return name,existing


def run(root: Path, modules: str):
    root=journal.safe_root(root);resources=runtime.load(root)
    if not resources.get('verification'):raise RuntimeError('Browser runner accepts verification instances only')
    if not modules or '\n' in modules or ',' in modules:raise ValueError('One read-only dependency mount required')
    plan=journal._read(root,root/'identity/plan.json');repo=Path(resources['repo'])
    lock=json.loads((repo/'deploy/identity/identity-toolchain.lock.json').read_text())['browserTests']
    if lock['version']!='1.63.0' or not re.fullmatch('sha256:[a-f0-9]{64}',lock['platformDigest']):raise RuntimeError('Locked real browser required')
    image=lock['image']+':'+lock['tag']+'@'+lock['platformDigest']
    launch_digest=digest({'image':image,'startup':STARTUP,'modules':modules,'operationId':plan['operationId']})
    name,existing=browser_resource(root,resources,plan,launch_digest)
    users=identity._directory(root,plan)
    value=json.loads((root/'identity/browser-input.json').read_text())
    value['passwordUpdatesRequired']={u['username']:'UPDATE_PASSWORD' in u.get('requiredActions',[]) for u in users if u['username'] in ['dingqiming','huangxuexue']}
    runtime.private_file(root/'identity/browser-input.json',json.dumps(value).encode())
    resources['containers']['browser']=name;runtime.save(root,resources)
    # Tool installation occurs only inside this disposable, labelled browser container.
    args=['docker','run','--name',name,'--label',runtime.LABEL+'='+resources['instanceId'],'--label','ols.operation='+plan['operationId'],
        '--label','ols.browser-launch='+launch_digest,'--log-driver','local','--log-opt','max-size=10m','--log-opt','max-file=3',
        '--network','container:'+plan['pod'],'--mount',f'type=bind,source={root},target={root}',
        '--mount',f'type=bind,source={repo}/deploy/linux,target=/tools/deploy/linux,readonly',
        '--mount',f'type=bind,source={modules},target=/tools/node_modules,readonly',
        '--entrypoint','sh',image,'-ec',STARTUP,'ols-browser',str(root)]
    result=runtime.run(['docker','start','--attach',name] if existing else args,timeout=300,check=False)
    runtime.private_file(root/'identity/browser-run.stdout',result.stdout)
    runtime.private_file(root/'identity/browser-run.stderr',result.stderr)
    if result.returncode:raise RuntimeError('Actual browser ceremony failed; original private evidence retained')
    verified=identity.verify(root)
    if verified['passwordUpdatesRequired']!=15:raise RuntimeError('Only the two administrators may complete initial password updates')
    print(json.dumps({'status':'PASS','adminPasswordUpdates':2,'passwordUpdatesRequired':15,'browserImage':image}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--runtime',type=Path,required=True);p.add_argument('--modules',required=True);a=p.parse_args();run(a.runtime,a.modules)
