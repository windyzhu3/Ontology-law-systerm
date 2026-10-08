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
        journal._write(root,path,{'status':'RUNNING','scenario':scenario,'before':before,'previousGeneration':old['generationId']})
        result=tls_rotation.begin(root,inputs,now=now)
        proof=tls_probe.collect(root,tls_generation.resolve(root),scope='all',now=int(time.time()));require_proof(proof)
        forwarding=authenticated_forwarding(root);after=facts(root)
        if before!=after:raise RuntimeError('Protected identity or business facts changed')
        report={'status':'PASS','scenario':scenario,'operationId':result['operationId'],'generationId':result['generationId'],'preservation':True,'authenticatedForwarding':forwarding,'proof':proof}
        journal._write(root,path,dict(report,before=before,after=after));return report


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--runtime',type=Path,required=True);p.add_argument('--scenario',choices=sorted(SCENARIOS),required=True);p.add_argument('--inputs-file',type=Path,required=True);a=p.parse_args()
    try:print(json.dumps(rotate(a.runtime,a.scenario,a.inputs_file)))
    except Exception:print(json.dumps({'status':'INCOMPLETE','scenario':a.scenario,'reason':'Original private evidence retained; reconcile original operation'}));raise SystemExit(1)
