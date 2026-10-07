"""Registered verification browser; trusted certificates, no insecure TLS flags."""
from pathlib import Path
import json
from ols_linux import config,identity,journal,runtime

STARTUP='''set -eu
apt-get update >/tmp/ols-browser-tools.log 2>&1
apt-get install -y --no-install-recommends libnss3-tools >>/tmp/ols-browser-tools.log 2>&1
mkdir -p /root/.local/share/pki/nssdb
timeout 10s certutil -N -d sql:/root/.local/share/pki/nssdb --empty-password </dev/null >/dev/null 2>&1
timeout 10s certutil -A -d sql:/root/.local/share/pki/nssdb -n OLS-Native -t 'C,,' -i "$1/certs/ca.pem" </dev/null >/dev/null 2>&1
if [ -f "$1/certs/public-ca.pem" ]; then timeout 10s certutil -A -d sql:/root/.local/share/pki/nssdb -n OLS-Public -t 'C,,' -i "$1/certs/public-ca.pem" </dev/null >/dev/null 2>&1; fi
test "$(node -p 'require("/tools/node_modules/@playwright/test/package.json").version')" = 1.63.0
exec tail -f /dev/null
'''


def prepare(root,modules):
    resources=runtime.load(root);plan=journal._read(root,root/'identity/plan.json')
    if not resources.get('verification') or journal.current(root)['phase']!='COMPLETE':raise RuntimeError('Active registered native verification instance required')
    repo=Path(resources['repo']);lock=json.loads((repo/'deploy/identity/identity-toolchain.lock.json').read_text())['browserTests']
    image=lock['image']+':'+lock['tag']+'@'+lock['platformDigest']
    if lock['version']!='1.63.0':raise RuntimeError('Exact locked browser version required')
    name=resources['name']+'-business-browser';launch=config.digest({'image':image,'startup':STARTUP,'modules':modules,'root':str(root)})
    actual=runtime.inspect('container',name)
    if actual:
        if resources['containers'].get('businessBrowser')!=name:raise RuntimeError('Unregistered browser not adopted')
        actual=runtime.owned(root,'container',name)
        if actual['Config']['Labels'].get('ols.browser-launch')!=launch:raise RuntimeError('Original browser launch changed')
        if not actual['State']['Running']:runtime.run(['docker','start',name])
    else:
        resources['containers']['businessBrowser']=name;runtime.save(root,resources)
        runtime.run(['docker','run','-d','--name',name,'--label',runtime.LABEL+'='+resources['instanceId'],'--label','ols.browser-launch='+launch,
            '--network','container:'+plan['pod'],'--mount',f'type=bind,source={root},target={root}',
            '--mount',f'type=bind,source={modules},target=/tools/node_modules,readonly',
            '--log-driver','local','--log-opt','max-size=10m','--log-opt','max-file=3','--entrypoint','sh',image,'-ec',STARTUP,'ols-browser',str(root)])
    # Readiness is the exact completed tools installation and live node process.
    import time
    deadline=time.monotonic()+120
    while True:
        actual=runtime.owned(root,'container',name)
        if not actual['State']['Running']:raise RuntimeError('Original native browser startup failed')
        if runtime.run(['docker','exec',name,'test','-f','/root/.local/share/pki/nssdb/cert9.db'],check=False).returncode==0:
            if runtime.run(['docker','exec',name,'sh','-c',"ps -eo args | grep -q '^tail -f /dev/null$'"],check=False).returncode==0:return name
        if time.monotonic()>deadline:raise RuntimeError('Original native browser startup unknown')
        time.sleep(1)


def run(root,file,args=(),timeout=1200):
    name=runtime.load(root)['containers']['businessBrowser'];runtime.owned(root,'container',name)
    result=runtime.run(['docker','exec','-e','OLS_NATIVE_BUSINESS_RUNTIME='+str(root),name,'node',file,*args],timeout=timeout,check=False)
    target=root/'verification'/('browser-'+Path(file).stem+'-'+str(__import__('time').time_ns())+'.log')
    runtime.private_file(target,result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('Original native browser step failed; protected diagnostics and commands retained')
    print(result.stdout.decode('utf-8'),flush=True)
