"""Strict TLS observations of each registered native and proxy hop."""
import hashlib
import json
import socket
import ssl
from pathlib import Path
from urllib.parse import urlsplit
from . import journal,runtime,tls_generation,tls_material

ROLES={'nativeIdentity','nativeEntry','bridgeIdentity','bridgeEntry','publicIdentity','publicEntry'}
CONSUMERS={'identity','api','worker','scanner','javaTrust','nodeTrust'}


def handshake(target: dict,trust_path: Path,expected_leaf_sha256: str,*,now: int) -> dict:
    if set(target)!={'connectHost','connectPort','verifyHost','role'} or target['role'] not in ROLES or type(target['connectPort'])!=int or not 1<=target['connectPort']<=65535:
        raise RuntimeError('Exact registered TLS target required')
    try:
        context=ssl.create_default_context(cafile=str(trust_path));context.minimum_version=ssl.TLSVersion.TLSv1_3
        with socket.create_connection((target['connectHost'],target['connectPort']),timeout=5) as plain:
            with context.wrap_socket(plain,server_hostname=target['verifyHost']) as secure:
                cert=secure.getpeercert();fp=hashlib.sha256(secure.getpeercert(binary_form=True)).hexdigest()
                valid=ssl.cert_time_to_seconds(cert['notBefore'])<=now<ssl.cert_time_to_seconds(cert['notAfter'])
                return {'status':'PASS' if fp==expected_leaf_sha256 and valid else 'BLOCKED','leafDerSha256':fp,'chainVerified':True,'notAfter':int(ssl.cert_time_to_seconds(cert['notAfter']))}
    except ssl.SSLCertVerificationError:return {'status':'BLOCKED','reasonCode':'TLS_CERTIFICATE_REJECTED'}
    except (OSError,ssl.SSLError):return {'status':'UNKNOWN','reasonCode':'TLS_OBSERVATION_UNAVAILABLE'}


def summarize(targets,consumers,*,scope):
    required={'nativeIdentity','nativeEntry'} if scope=='native' else {'bridgeIdentity','bridgeEntry'} if scope=='bridge' else ROLES-{'nativeIdentity','nativeEntry'} if scope=='proxy' else ROLES
    if any(v.get('status')=='BLOCKED' for v in targets.values()):return 'BLOCKED'
    if not required<=targets.keys() or any(targets[k].get('status')!='PASS' for k in required):return 'UNKNOWN'
    if scope not in {'proxy','bridge'} and (set(consumers)!=CONSUMERS or any(v!='PASS' for v in consumers.values())):return 'UNKNOWN'
    return 'PASS'


def same_denial(first,second):
    if first['status']!=401 or second['status']!=401:return False
    import re
    bodies=[json.loads(x['body']) for x in [first,second]]
    for body in bodies:
        if body.get('code')!='UNAUTHENTICATED' or body.get('status')!=401 or body.get('type')!='urn:ontology-law:problem:UNAUTHENTICATED':return False
        if not re.fullmatch('/problems/[0-9a-f-]{36}',body.pop('instance','')):return False
    return bodies[0]==bodies[1]


def collect(root: Path,generation: dict,*,scope: str,now: int) -> dict:
    if scope not in {'native','proxy','bridge','all'}:raise RuntimeError('Named TLS probe scope required')
    with journal.locked(root) as root:
        current=tls_generation.resolve(root)
        if current['generationId']!=generation['generationId']:raise RuntimeError('Probe generation is not active')
        plan=journal._read(root,root/'identity/plan.json');hosts={
            'Identity':urlsplit(plan['issuer']).hostname,'Entry':urlsplit(plan['origin']).hostname}
        targets={}
        for suffix,key in [('Identity','identity'),('Entry','entry')]:
            targets['native'+suffix]={'connectHost':'127.0.0.1','connectPort':plan['ports'][key],'verifyHost':hosts[suffix],'role':'native'+suffix}
        for target in generation.get('deployment',{}).get('probeTargets',[]):
            role=target.get('role');suffix='Identity' if role and role.endswith('Identity') else 'Entry'
            identity=target.get('tlsIdentity','public')
            expected_host='localhost' if identity=='internal' else hosts[suffix]
            if identity not in {'public','internal'} or identity=='internal' and not role.startswith('bridge'):raise RuntimeError('Only registered bridges may use the original internal TLS identity')
            if role not in ROLES-{'nativeIdentity','nativeEntry'} or target.get('verifyHost')!=expected_host or target.get('connectHost') not in {'127.0.0.1','::1',hosts[suffix]}:raise RuntimeError('Unregistered probe target')
            targets[role]=target
        evidence={}
        for role,target in targets.items():
            if scope=='native' and not role.startswith('native') or scope=='proxy' and role.startswith('native'):continue
            if scope=='bridge' and not role.startswith('bridge'):continue
            expected=generation['candidate']['leafDerSha256']
            if target.get('tlsIdentity')=='internal':expected=tls_material.fingerprint(tls_material.certificates(tls_material.read_private(root/'certs/server.crt'))[0])
            connection={key:value for key,value in target.items() if key!='tlsIdentity'}
            evidence[role]=handshake(connection,Path(generation['paths']['httpTrust']),expected,now=now)
        consumers={};http_evidence={}
        if scope not in {'proxy','bridge'}:
            try:
                if generation['version']==1:tls_generation.verify_trust(root,generation['trust'])
                from . import verify
                descriptor=journal._read(root,root/'current-release.json')['descriptor']
                result=verify.runtime_ready(root,descriptor)
                full=result.get('fullRuntime',{})
                expected={'identity':'VERIFIED_TLS_DISCOVERY','api':'AUTHENTICATED_MTLS_READY','worker':'CURRENT_BOOT_READY','scanner':'REAL_PONG'}
                consumers={name:'PASS' for name,value in expected.items() if full.get(name)==value}
                if consumers.get('api')=='PASS':consumers['javaTrust']='PASS'
                from . import identity
                forwarded=identity.http(root,plan['origin']+'/api/v1/session/context')
                direct=identity.http(root,plan['apiOrigin']+'/api/v1/session/context')
                if same_denial(forwarded,direct):
                    consumers['nodeTrust']='PASS'
                    http_evidence['nativeEntryApi']='VERIFIED_TLS_UNAUTHENTICATED_API_RESPONSE'
            except (RuntimeError,ValueError,KeyError,OSError):pass
        public_ok=True
        if scope in {'all','proxy'}:
            try:
                from . import identity
                descriptor=journal._read(root,root/'current-release.json')['descriptor']
                discovery=identity.http(root,plan['issuer']+'/.well-known/openid-configuration',public=True)
                entry=identity.http(root,plan['origin']+'/',public=True)
                forwarded=identity.http(root,plan['origin']+'/api/v1/session/context',public=True)
                direct=identity.http(root,plan['apiOrigin']+'/api/v1/session/context')
                public_ok=(discovery['status']==entry['status']==200 and json.loads(discovery['body'])['issuer']==plan['issuer'] and hashlib.sha256(entry['body'].encode()).hexdigest()==descriptor['spaFiles']['index.html'] and same_denial(forwarded,direct))
            except (RuntimeError,ValueError,KeyError,OSError):public_ok=False
            http_evidence['publicRoutes']='PASS' if public_ok else 'BLOCKED'
        return {'status':summarize(evidence,consumers,scope=scope) if public_ok else 'BLOCKED','observedAt':now,'generationId':generation['generationId'],'targets':evidence,'consumers':consumers,'http':http_evidence}
