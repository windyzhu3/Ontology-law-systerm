"""Strict TLS observations of each registered native and proxy hop."""
import hashlib
import socket
import ssl
from pathlib import Path
from urllib.parse import urlsplit
from . import journal,runtime,tls_generation

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
    required={'nativeIdentity','nativeEntry'} if scope=='native' else ROLES-{'nativeIdentity','nativeEntry'} if scope=='proxy' else ROLES
    if any(v.get('status')=='BLOCKED' for v in targets.values()):return 'BLOCKED'
    if not required<=targets.keys() or any(targets[k].get('status')!='PASS' for k in required):return 'UNKNOWN'
    if scope!='proxy' and (set(consumers)!=CONSUMERS or any(v!='PASS' for v in consumers.values())):return 'UNKNOWN'
    return 'PASS'


def collect(root: Path,generation: dict,*,scope: str,now: int) -> dict:
    if scope not in {'native','proxy','all'}:raise RuntimeError('Named TLS probe scope required')
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
            if role not in ROLES-{'nativeIdentity','nativeEntry'} or target.get('verifyHost')!=hosts[suffix] or target.get('connectHost') not in {'127.0.0.1','::1',hosts[suffix]}:raise RuntimeError('Unregistered probe target')
            targets[role]=target
        evidence={}
        for role,target in targets.items():
            if scope=='native' and not role.startswith('native') or scope=='proxy' and role.startswith('native'):continue
            evidence[role]=handshake(target,Path(generation['paths']['httpTrust']),generation['candidate']['leafDerSha256'],now=now)
        consumers={}
        if scope!='proxy':
            try:
                if generation['version']==1:tls_generation.verify_trust(root,generation['trust'])
                from . import verify
                descriptor=journal._read(root,root/'current-release.json')['descriptor']
                result=verify.runtime_ready(root,descriptor)
                if result['status']=='PASS':consumers={name:'PASS' for name in CONSUMERS}
            except (RuntimeError,ValueError,KeyError,OSError):pass
        return {'status':summarize(evidence,consumers,scope=scope),'observedAt':now,'generationId':generation['generationId'],'targets':evidence,'consumers':consumers}
