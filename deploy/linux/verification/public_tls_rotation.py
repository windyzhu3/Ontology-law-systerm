"""Real owned-fixture TLS acceptance. Missing scenarios never qualify as PASS.

Secrets stay in the private runtime. This driver never creates/reinitializes an
instance and never accepts production resources. Run scenarios serially.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import admin,checkpoint,identity,journal,runtime,tls_generation,tls_material,tls_probe,tls_rotation

SCENARIOS={'same-ca','cross-ca','expired-old','stale-native','unregistered-client','crash','checkpoint','issuer-blocked'}
STABLE_SCHEMAS={'identity','party','lead','opportunity','conflict','contract','transfer','responsibility','external_action','evidence'}
IDP_TABLES={'public.user_entity','public.credential','public.user_required_action','public.user_attribute','public.realm','public.client'}


def require_coverage(reports):
    if set(reports)!=SCENARIOS or any(v.get('status')!='PASS' for v in reports.values()):raise RuntimeError('TLS acceptance INCOMPLETE: every real scenario required')


def require_fixture(root):
    root=journal.safe_root(root);resources=runtime.load(root)
    if resources.get('verification') is not True:raise RuntimeError('Explicit isolated verification instance required')
    for name in resources['containers'].values():
        if runtime.inspect('container',name):runtime.owned(root,'container',name)
    op=journal.current(root)
    if op['phase']!='COMPLETE':raise RuntimeError('Continue the original pending operation before acceptance')
    return root


def require_proof(proof):
    if proof.get('status')!='PASS' or set(proof.get('targets',{}))!=tls_probe.ROLES or set(proof.get('consumers',{}))!=tls_probe.CONSUMERS or any(v!='PASS' for v in proof.get('consumers',{}).values()) or proof.get('http',{}).get('publicRoutes')!='PASS':raise RuntimeError('Complete native, bridge, public HTTP and consumer proof required')


def facts(root):
    business={k:v for k,v in checkpoint.table_facts(root).items() if k.split('.')[0] in STABLE_SCHEMAS}
    idp={k:v for k,v in checkpoint.table_facts(root,identity=True).items() if k in IDP_TABLES}
    if set(idp)!=IDP_TABLES:raise RuntimeError('Exact IdP preservation inventory missing')
    init=journal._read(root,root/'identity/plan.json')['operationId']
    return {'business':business,'identity':idp,'originalInitializationSha256':hashlib.sha256((root/'operations'/(init+'.json')).read_bytes()).hexdigest(),
            'originalPublicHashes':runtime.load(root)['publicTlsHashes']}


def authenticated_forwarding(root):
    plan=journal._read(root,root/'identity/plan.json');state=journal._read(root,root/'initialization.json')
    user='dingqiming';value=admin.session(root,root/'identity'/('dingqiming-session.json'),user)
    appointment=state['appointments']['dingqiming_bootstrap']
    response=identity.http(root,plan['origin']+'/api/v1/session/context',headers={'Authorization':'Bearer '+value['accessToken'],'X-Appointment-Id':appointment},public=True)
    body=json.loads(response['body'])
    if response['status']!=200 or body.get('state')!='READY' or body.get('selectedAppointmentId')!=appointment:raise RuntimeError('Authenticated public forwarding failed')
    return {'status':'PASS','path':'/api/v1/session/context','originalAppointment':True}


def match_operation(record,data):
    if record['candidateInputDigest']!=data['candidate']['inputDigest'] or record['previousGeneration']!=data['previousGeneration']['generationId']:
        raise RuntimeError('Acceptance continuation belongs to another original candidate')


def finish(root,scenario,operation_id):
    with journal.locked(root) as root:
        if runtime.load(root).get('verification') is not True:raise RuntimeError('Verification instance required')
        path=root/'verification'/('tls-'+scenario+'-acceptance.json');record=journal._read(root,path)
        op=journal.current(root)
        if op['operationId']!=operation_id or op['kind']!='rotate-public-tls':raise RuntimeError('Exact original TLS operation required')
        match_operation(record,journal._read(root,root/'operations'/(operation_id+'-tls.json')))
        if op['phase']!='COMPLETE':tls_rotation.resume(root,operation_id,now=int(time.time()))
        generation=tls_generation.resolve(root)
        if generation['candidate']['inputDigest']!=record['candidateInputDigest']:raise RuntimeError('Original candidate is not selected')
        proof=tls_probe.collect(root,generation,scope='all',now=int(time.time()));require_proof(proof)
        forwarding=authenticated_forwarding(root);after=facts(root)
        if record['before']!=after:raise RuntimeError('Protected identity or business facts changed')
        report={'status':'PASS','scenario':scenario,'operationId':operation_id,'generationId':generation['generationId'],'preservation':True,'authenticatedForwarding':forwarding,'proof':proof}
        for key in ['processExit','faultPhase','closedObserved']:
            if key in record:report[key]=record[key]
        if scenario=='crash':require_crash(report.get('processExit'),report.get('closedObserved'))
        journal._write(root,path,dict(record,**report,after=after));return report


def rotate(root,scenario,inputs_file):
    if scenario not in {'same-ca','cross-ca','expired-old'}:raise RuntimeError('This runner requires a named implemented rotation scenario')
    with journal.locked(root) as root:
        require_fixture(root);old=tls_generation.resolve(root);now=int(time.time())
        inputs=json.loads(tls_material.read_private(inputs_file));candidate=tls_material.freeze(root,inputs['materials'])['candidate']
        old_roots=set(old.get('trust',{}).get('anchorFingerprints',[]))
        if old['version']==0:old_roots={tls_material.fingerprint(c) for c in tls_material.certificates(tls_material.read_private(root/'certs/public-ca.pem'))}
        added=set(candidate['anchorFingerprints'])-old_roots
        if scenario=='same-ca' and added or scenario=='cross-ca' and not added:raise RuntimeError('Actual candidate does not exercise the named CA scenario')
        if scenario=='expired-old' and old['candidate']['notAfter']>now:raise RuntimeError('Old certificate is not actually expired; simulated time is insufficient')
        before=facts(root)
        path=root/'verification'/('tls-'+scenario+'-acceptance.json')
        if path.exists():raise RuntimeError('Original scenario evidence exists; do not overwrite it')
        journal._write(root,path,{'status':'RUNNING','scenario':scenario,'before':before,'previousGeneration':old['generationId'],'candidateInputDigest':candidate['inputDigest']})
        result=tls_rotation.begin(root,inputs,now=now)
        return finish(root,scenario,result['operationId'])


def require_crash(returncode,closed):
    if returncode!=-9 or closed is not True:raise RuntimeError('Actual SIGKILL and observed closed writers required')


def crash_rotate(root,inputs_file):
    import subprocess,os
    root=require_fixture(root);before=facts(root);previous=tls_generation.resolve(root)['generationId']
    path=root/'verification/tls-crash-acceptance.json'
    if path.exists():raise RuntimeError('Original crash evidence exists; continue its operation explicitly')
    candidate=tls_material.freeze(root,json.loads(tls_material.read_private(inputs_file))['materials'])['candidate']
    journal._write(root,path,{'status':'RUNNING','before':before,'previousGeneration':previous,'candidateInputDigest':candidate['inputDigest']})
    code="""import os,signal,json,sys,time
from pathlib import Path
from ols_linux import tls_rotation,tls_material,runtime
root=Path(sys.argv[1]);assert runtime.load(root)['verification'] is True
original=tls_rotation._phase
def interrupt(root,opid,phase,**evidence):
    original(root,opid,phase,**evidence)
    if phase=='SWITCHED':os.kill(os.getpid(),signal.SIGKILL)
tls_rotation._phase=interrupt
tls_rotation.begin(root,json.loads(tls_material.read_private(Path(sys.argv[2]))),now=int(time.time()))
"""
    env=dict(os.environ,PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    child=subprocess.run([sys.executable,'-c',code,str(root),str(inputs_file)],env=env,capture_output=True)
    runtime.private_file(root/'verification/tls-crash-child.stderr',child.stderr)
    op=journal.current(root)
    if op['kind']!='rotate-public-tls' or op['phase']!='SWITCHED':raise RuntimeError('Expected original switch interruption absent')
    from ols_linux import tls_proxy
    closed=tls_proxy.observe(root,op['operationId'])['closed'];resources=runtime.load(root)
    for name in set(resources['writers'])|{resources['ingress']}:
        if runtime.inspect('container',name) and runtime.owned(root,'container',name)['State']['Running']:closed=False
    require_crash(child.returncode,closed)
    record=journal._read(root,path);record.update(processExit=child.returncode,faultPhase='SWITCHED',closedObserved=closed)
    journal._write(root,path,record)
    return finish(root,'crash',op['operationId'])


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--runtime',type=Path,required=True);p.add_argument('--scenario',choices=sorted(SCENARIOS),required=True);p.add_argument('--inputs-file',type=Path);p.add_argument('--operation-id');a=p.parse_args()
    try:print(json.dumps(finish(a.runtime,a.scenario,a.operation_id) if a.operation_id else crash_rotate(a.runtime,a.inputs_file) if a.scenario=='crash' else rotate(a.runtime,a.scenario,a.inputs_file)))
    except Exception:print(json.dumps({'status':'INCOMPLETE','scenario':a.scenario,'reason':'Original private evidence retained; reconcile original operation'}));raise SystemExit(1)
