"""Immutable TLS generations; legacy seals stay immutable."""
from pathlib import Path
import re
from . import journal,runtime,tls_material
from .bundle import sha
from .config import digest


def resolve(root: Path) -> dict:
    with journal.locked(root) as root:
        if (root/'tls/active.json').exists():
            selected=journal._read(root,root/'tls/active.json')
            return read(root,selected['generationId'])
        resources=runtime.load(root)
        for name,value in resources.get('publicTlsHashes',{}).items():
            if sha(root/name)!=value:raise RuntimeError('Original public TLS changed')
        paths={'certificate':str(root/'certs/public.crt'),'privateKey':str(root/'certs/public.key'),
               'httpTrust':str(root/'certs/http-trust.pem'),'javaTrustStore':str(root/'certs/identity-trust.p12')}
        info=tls_material.metadata(Path(paths['certificate']))
        import ssl
        original={n:sha(root/'certs'/n) for n in ['public.crt','public.key','public-ca.pem']}
        return {'version':0,'generationId':digest(original),'paths':paths,'candidate':{'notAfter':int(ssl.cert_time_to_seconds(info['notAfter'])),
            'leafDerSha256':tls_material.fingerprint(tls_material.certificates(Path(paths['certificate']).read_bytes())[0])},'files':{'certs/'+n:h for n,h in original.items()}}


def current(root,operation_id):
    op=journal.current(root)
    if op['operationId']!=operation_id or op['kind']!='rotate-public-tls' or op['phase']=='COMPLETE':raise RuntimeError('Current original TLS operation required')
    return op


def layout(root: Path, operation_id: str, candidate: dict) -> dict:
    with journal.locked(root) as root:
        op=current(root,operation_id);parent=resolve(root)['generationId']
        gid=digest({'instanceId':op['instanceId'],'operationId':operation_id,'parentGenerationId':parent,'inputDigest':candidate['inputDigest']})
        directory=root/'tls/generations'/gid
        names={'certificate':'certificate.pem','privateKey':'private.key','httpTrust':'http-trust.pem','javaTrustStore':'identity-trust.p12'}
        return {'generationId':gid,'parentGenerationId':parent,'paths':{k:str(directory/v) for k,v in names.items()}}


def seal(root: Path, operation_id: str, candidate: dict, trust: dict, deployment: dict) -> dict:
    with journal.locked(root) as root:
        op=current(root,operation_id);value=layout(root,operation_id,candidate)
        directory=Path(value['paths']['certificate']).parent
        for name,expected in candidate['files'].items():
            source=Path(candidate['directory'])/name;data=tls_material.read_private(source)
            import hashlib
            if hashlib.sha256(data).hexdigest()!=expected:raise RuntimeError('Candidate changed before seal')
            target=directory/name
            if target.exists() and target.read_bytes()!=data:raise RuntimeError('Existing generation differs')
            if not target.exists():runtime.private_file(target,data)
        files={str((directory/name).relative_to(root)):expected for name,expected in candidate['files'].items()}
        files.update(trust['files']);files.update(deployment['files'])
        value.update(version=1,operationId=operation_id,instanceId=op['instanceId'],candidate=candidate,trust=trust,deployment=deployment,files=files)
        value['manifestDigest']=digest(value)
        path=directory/'generation.json'
        if path.exists() and journal._read(root,path)!=value:raise RuntimeError('Sealed generation changed')
        if not path.exists():journal._write(root,path,value)
        return read(root,value['generationId'])


def read(root,gid):
    if not isinstance(gid,str) or not re.fullmatch('[a-f0-9]{64}',gid):raise RuntimeError('Exact TLS generation required')
    value=journal._read(root,root/'tls/generations'/gid/'generation.json')
    payload=dict(value);expected=payload.pop('manifestDigest')
    if digest(payload)!=expected or value['generationId']!=gid or value['instanceId']!=journal._owner(root)['instanceId']:raise RuntimeError('TLS generation binding differs')
    for name,h in value['files'].items():
        path=root/name
        if not path.is_relative_to(root) or '..' in path.parts or path.resolve()!=path.absolute() or sha(path)!=h:raise RuntimeError('TLS generation bytes differ')
    return value


def select(root: Path, operation_id: str, expected_parent: str, generation_id: str) -> None:
    with journal.locked(root) as root:
        op=current(root,operation_id)
        if op['phase'] not in {'SWITCHING','SWITCHED'}:raise RuntimeError('Stopped TLS switch required')
        value=read(root,generation_id);old=resolve(root)['generationId']
        if value['operationId']!=operation_id or value['parentGenerationId']!=expected_parent or old not in {expected_parent,generation_id}:raise RuntimeError('TLS parent conflict')
        journal._write(root,root/'tls/active.json',{'generationId':generation_id,'operationId':operation_id})


def paths(root: Path,generation: dict) -> dict:
    value=read(root,generation['generationId']) if generation['version']==1 else resolve(root)
    if value!=generation:raise RuntimeError('TLS generation differs')
    return dict(value['paths'])
