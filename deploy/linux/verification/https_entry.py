"""Real TLS and immutable-file boundary probe; not application readiness."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
from ols_linux import journal,runtime


def verify(root: Path,image: str):
    if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Built immutable runtime image required')
    resources=runtime.load(root)
    if not resources.get('verification'):raise RuntimeError('TLS fixture is verification-only')
    fixture=root/'https-fixture';fixture.mkdir(mode=0o700)
    (fixture/'spa').mkdir();page=b'<html>Linux HTTPS boundary fixture</html>'
    runtime.private_file(fixture/'spa/index.html',page)
    shutil.copyfile(Path(resources['repo'])/'deploy/linux/runtime/server.mjs',fixture/'server.mjs')
    configuration={'dist':'/fixture/spa','hostHeader':'localhost','apiOrigin':'https://localhost:9','identityOrigin':'https://localhost:9',
        'ca':'/certs/ca.pem','certificate':'/certs/server.crt','privateKey':'/certs/server.key','spaFiles':{'index.html':hashlib.sha256(page).hexdigest()}}
    runtime.private_file(fixture/'entry.json',json.dumps(configuration).encode())
    name=resources['name']+'-https-probe'
    if runtime.inspect('container',name):raise RuntimeError('Probe name already exists')
    resources['containers']['httpsProbe']=name;runtime.save(root,resources)
    runtime.run(['docker','run','-d','--name',name,'--label','ols.instance='+resources['instanceId'],'--label','ols.operation='+journal.current(root)['operationId'],
        '--network',resources['network'],'-p','127.0.0.1::8444','--mount',f'type=bind,source={fixture},target=/fixture,readonly',
        '--mount',f'type=bind,source={root/"certs"},target=/certs,readonly','--entrypoint','node',image,'/fixture/server.mjs','/fixture/entry.json'])
    runtime.owned(root,'container',name)
    probe="""const https=require('https'),fs=require('fs');const [path,host,trusted]=process.argv.slice(1);
    const req=https.get({hostname:'localhost',servername:'localhost',port:8444,path,headers:{Host:host},minVersion:'TLSv1.3',rejectUnauthorized:true,
    ca:trusted==='yes'?fs.readFileSync('/certs/ca.pem'):undefined},res=>{let b='';res.on('data',d=>b+=d);res.on('end',()=>console.log(JSON.stringify({status:res.statusCode,headers:res.headers,body:b})));});
    req.on('error',e=>{console.log(JSON.stringify({error:e.code}));});req.setTimeout(3000,()=>req.destroy());"""
    def request(path='/',host='localhost',trusted=True):
        result=runtime.run(['docker','exec',name,'node','-e',probe,path,host,'yes' if trusted else 'no'])
        return json.loads(result.stdout)
    deadline=time.monotonic()+20
    while True:
        try:
            response=request()
            if response.get('error'):raise OSError('Entry not ready')
            break
        except OSError:
            if time.monotonic()>deadline:raise RuntimeError('HTTPS probe startup failed; resources retained')
            time.sleep(.25)
    assert response['status']==200 and response['body']==page.decode() and response['headers']['cache-control']=='no-store'
    assert request(host='foreign.example')['status']==421
    assert request('/%2e%2e/secrets')['status']==404
    assert request('/api/probe')['status']==502
    assert request(trusted=False).get('error') in {'UNABLE_TO_VERIFY_LEAF_SIGNATURE','SELF_SIGNED_CERT_IN_CHAIN','UNABLE_TO_GET_ISSUER_CERT_LOCALLY'}
    runtime.private_file(fixture/'spa/index.html',b'changed')
    assert request()['status']==503
    journal.record(root,journal.current(root)['operationId'],{'phase':'HTTPS_BOUNDARY_VERIFIED','checks':6,'runtimeImage':image})
    return {'status':'PASS','checks':6,'runtimeImage':image}
