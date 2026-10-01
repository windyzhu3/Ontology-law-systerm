"""Start/stop only the recorded processes belonging to this isolated UAT runtime."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from prepare import ROOT,RUNTIME,JAVA,NODE,JAR,save,deployment
import hashlib

def start(names):
    registry=json.loads((RUNTIME/'processes.json').read_text()) if (RUNTIME/'processes.json').exists() else {}
    if len(names)!=len(set(names)) or any(name in registry for name in names):
        raise RuntimeError('requested process already registered or repeated; refuse partial start')
    if hashlib.sha256(JAR.read_bytes()).hexdigest()!=deployment()['releaseDigest']:raise RuntimeError('runtime artifact digest changed')
    if 'spa' in names:
        if (RUNTIME/'dist').exists():
            if not (RUNTIME/'dist/index.html').is_file() or not (RUNTIME/'server.mjs').is_file():
                raise RuntimeError('retained SPA artifact incomplete; refuse start')
            # A normal restart must reuse the exact deployed SPA, not the latest build.
        else:
            shutil.copytree(ROOT/'apps/workbench/dist',RUNTIME/'dist')
            shutil.copyfile(ROOT/'deploy/haihua-uat/server.mjs',RUNTIME/'server.mjs')
    commands={'api':[JAVA,'-Xmx768m','-jar',JAR,'--spring.config.additional-location='+(RUNTIME/'application.properties').as_uri()],
              'worker':[JAVA,'-Xmx384m','-jar',JAR,'--spring.config.location='+(RUNTIME/'worker.properties').as_uri(),'--ols.runtime-role=worker','--spring.main.web-application-type=none'],
              'spa':[NODE,RUNTIME/'server.mjs',RUNTIME]}
    env=os.environ.copy();env.update(OLS_MATERIAL_STORE_PATH=str(RUNTIME/'materials'),OLS_CLAMD_HOST='127.0.0.1',OLS_CLAMD_PORT='20547')
    # External AI is intentionally unconfigured until actual provider prerequisites exist.
    for k in list(env):
        if k.startswith('OLS_AI_'):del env[k]
    for name in names:
        if name in registry:raise RuntimeError('process already registered: '+name)
        args=[str(a) for a in commands[name]]
        with (RUNTIME/(name+'.stdout')).open('ab') as out,(RUNTIME/(name+'.stderr')).open('ab') as err:
            p=subprocess.Popen(args,cwd=ROOT,env=env,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
        registry[name]={'pid':p.pid,'args':args}
        save('processes.json',registry)
        print(name+' started; readiness must be verified')

def stop(names):
    registry=json.loads((RUNTIME/'processes.json').read_text())
    for name in names:
        record=registry[name];pid=int(record['pid'])
        script=f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if($p){{$p | Select-Object ExecutablePath,CommandLine | ConvertTo-Json -Compress}}"
        r=subprocess.run(['pwsh','-NoProfile','-NonInteractive','-Command',script],capture_output=True,check=True)
        if r.stdout.strip():
            actual=json.loads(r.stdout)
            if Path(actual['ExecutablePath']).resolve()!=Path(record['args'][0]).resolve() or str(RUNTIME).lower().replace('\\','/') not in actual['CommandLine'].lower().replace('\\','/'):
                raise RuntimeError('PID does not belong to this UAT runtime; refuse stop')
            subprocess.run(['taskkill','/F','/PID',str(pid)],capture_output=True,check=True)
        del registry[name];save('processes.json',registry)
        print(name+' stopped; original data/artifacts preserved')

def build_spa():
    env=os.environ.copy()
    env.update(VITE_APP_ORIGIN='https://localhost:20544',VITE_OIDC_ISSUER='https://localhost:20543/realms/haihua-uat',VITE_OIDC_CLIENT_ID='haihua-uat-spa',VITE_OIDC_AUDIENCE='haihua-uat-api')
    with (RUNTIME/'spa-build.log').open('wb') as out:
        subprocess.run([str(NODE),str(ROOT/'node_modules/typescript/lib/_tsc.js'),'--noEmit'],cwd=ROOT/'apps/workbench',env=env,stdout=out,stderr=subprocess.STDOUT,check=True)
        subprocess.run([str(NODE),str(ROOT/'node_modules/vite/bin/vite.js'),'build'],cwd=ROOT/'apps/workbench',env=env,stdout=out,stderr=subprocess.STDOUT,check=True)
    print('SPA built with explicit Haihua OIDC configuration; deployment remains separate')

if __name__=='__main__':
    operation=sys.argv[1];names=sys.argv[2:]
    if operation=='build-spa' and not names:build_spa()
    elif operation in ('start','stop') and names and all(n in ('api','spa','worker') for n in names):
        (start if operation=='start' else stop)(names)
    else:raise SystemExit('build-spa | start|stop api|spa|worker ...')
