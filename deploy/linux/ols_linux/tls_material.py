"""Snapshot public TLS inputs and validate only explicitly admitted trust anchors."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import ssl
import stat
import tempfile
from urllib.parse import urlsplit
from . import journal,runtime
from .config import digest

PEM=re.compile(rb'-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----')


def read_private(path):
    """Open every path component without following links; bound and snapshot bytes."""
    path=Path(path)
    if not path.is_absolute() or '..' in path.parts:raise RuntimeError('Absolute private material path required')
    fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY)
    try:
        for part in path.parts[1:-1]:
            nxt=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=nxt
        parent=os.fstat(fd)
        if parent.st_uid!=os.getuid() or parent.st_mode&0o077:raise RuntimeError('Material parent must be owned and private')
        file=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
        try:
            before=os.fstat(file)
            if not stat.S_ISREG(before.st_mode) or before.st_uid!=os.getuid() or before.st_mode&0o077 or before.st_nlink!=1 or before.st_size>1024*1024:
                raise RuntimeError('Unsafe TLS material file')
            data=b''
            while chunk:=os.read(file,65536):
                data+=chunk
                if len(data)>1024*1024:raise RuntimeError('TLS material too large')
            after=os.fstat(file)
            if (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):raise RuntimeError('TLS material changed during read')
            return data
        finally:os.close(file)
    except OSError:raise RuntimeError('Private material unavailable or linked') from None
    finally:os.close(fd)


def certificates(data):
    blocks=PEM.findall(data)
    if not blocks or PEM.sub(b'',data).strip():raise RuntimeError('Only PEM certificates accepted')
    return [block+b'\n' for block in blocks]


def fingerprint(data):
    blocks=certificates(data)
    if len(blocks)!=1:raise RuntimeError('One explicit certificate required')
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(blocks[0].decode('ascii'))).hexdigest()


def openssl(*args,data=None):
    return runtime.run(['openssl',*args],data=data).stdout


def metadata(path):
    try:return ssl._ssl._test_decode_cert(str(path))
    except (ssl.SSLError,OSError,ValueError):raise RuntimeError('Invalid TLS certificate') from None


def approved(root):
    values=json.loads((Path(__file__).parents[1]/'config/public-tls-trust-anchors.json').read_text())
    result={a['sha256'] for a in values['anchors'] if a['status']=='VERIFIED'}
    if runtime.load(root).get('verification') is True and (root/'tls-test-anchors.json').exists():
        result.update(journal._read(root,root/'tls-test-anchors.json')['fingerprints'])
    for name in ['ca.pem','public-ca.pem']:
        if (root/'certs'/name).exists():result.add(fingerprint(read_private(root/'certs'/name)))
    return result


def freeze(root: Path,inputs: dict) -> dict:
    if set(inputs)!={'certificate','privateKey','intermediates','approvedAnchors','origins','provenance'}:raise RuntimeError('Exact TLS input fields required')
    resources=runtime.load(root)
    expected=[resources['publicOrigin'],resources['identityOrigin']]
    if inputs['origins']!=expected:raise RuntimeError('Original HTTPS origins required')
    from .public_runtime import origin
    hosts=[urlsplit(origin(o)).hostname for o in expected]
    if not isinstance(inputs['intermediates'],list) or not isinstance(inputs['approvedAnchors'],list) or not inputs['approvedAnchors']:raise RuntimeError('Explicit certificate chain inputs required')
    certs=certificates(read_private(inputs['certificate']))
    for p in inputs['intermediates']:certs+=certificates(read_private(p))
    key=read_private(inputs['privateKey']);anchors={}
    allowed=approved(root)
    for path in inputs['approvedAnchors']:
        data=read_private(path);fp=fingerprint(data)
        if fp not in allowed:raise RuntimeError('Unapproved public trust anchor')
        anchors[fp]=certificates(data)[0]
    files={'certificate.pem':b''.join(certs),'private.key':key,'anchors.pem':b''.join(anchors[k] for k in sorted(anchors))}
    candidate={'version':1,'originHosts':hosts,'anchorFingerprints':sorted(anchors),
               'files':{n:hashlib.sha256(d).hexdigest() for n,d in files.items()},'provenanceDigest':digest(inputs['provenance'])}
    candidate['inputDigest']=digest(candidate)
    return {'candidate':candidate,'bytes':files}


def stage(root: Path, inputs: dict, *, now: int, frozen: dict | None=None) -> dict:
    with journal.locked(root) as root:
        frozen=freeze(root,inputs) if frozen is None else frozen
        base=root/'tls/staging'
        if base.resolve()!=base.absolute():raise RuntimeError('Unsafe staging directory')
        base.mkdir(mode=0o700,parents=True,exist_ok=True)
        directory=Path(tempfile.mkdtemp(prefix='candidate-',dir=base))
        for name,data in frozen['bytes'].items():runtime.private_file(directory/name,data)
        candidate=dict(frozen['candidate'],directory=str(directory))
        info=verify(directory,candidate,now=now)
        candidate.update(info)
        previous=metadata(root/'certs/public.crt')
        if (root/'tls/active.json').exists():
            from .tls_generation import resolve
            if candidate['notAfter']<=resolve(root)['candidate']['notAfter']:raise RuntimeError('New certificate must expire later')
        elif candidate['notAfter']<=ssl.cert_time_to_seconds(previous['notAfter']):raise RuntimeError('New certificate must expire later')
        return candidate


def verify(directory: Path, expected: dict, *, now: int) -> dict:
    root=next((p for p in directory.parents if (p/'instance.json').is_file()),None)
    if root is None:raise RuntimeError('Registered material runtime required')
    execute=lambda *args,data=None:tool(root,'openssl',list(args),data=data)
    if type(now)!=int:raise RuntimeError('UTC epoch required')
    for name,value in expected['files'].items():
        if Path(name).name!=name or hashlib.sha256(read_private(directory/name)).hexdigest()!=value:raise RuntimeError('Staged TLS bytes changed')
    chain=certificates(read_private(directory/'certificate.pem'));info=metadata(directory/'certificate.pem')
    before=int(ssl.cert_time_to_seconds(info['notBefore']));after=int(ssl.cert_time_to_seconds(info['notAfter']))
    if not before<=now or after-now<7*86400:raise RuntimeError('Certificate invalid or insufficient remaining lifetime')
    san=info.get('subjectAltName',())
    for host in expected['originHosts']:
        try:
            address=ipaddress.ip_address(host)
            if not any(kind=='IP Address' and ipaddress.ip_address(value)==address for kind,value in san):raise RuntimeError('IP SAN missing')
        except ValueError:
            if not any(kind=='DNS' and ssl._dnsname_match(value,host) for kind,value in san):raise RuntimeError('DNS SAN missing')
    public=execute('x509','-pubkey','-noout',data=chain[0])
    private=execute('pkey','-in',directory/'private.key','-pubout','-passin','pass:')
    if public!=private:raise RuntimeError('Certificate and private key differ')
    with tempfile.TemporaryDirectory(dir=directory) as work:
        leaf=Path(work)/'leaf.pem';leaf.write_bytes(chain[0])
        intermediate=Path(work)/'chain.pem';intermediate.write_bytes(b''.join(chain[1:]))
        args=['verify','-no-CApath','-no-CAstore','-CAfile',directory/'anchors.pem','-purpose','sslserver','-attime',str(now)]
        if len(chain)>1:args+=['-untrusted',intermediate]
        execute(*args,leaf)
        for anchor in certificates(read_private(directory/'anchors.pem')):
            text=execute('x509','-text','-noout',data=anchor)
            if b'CA:TRUE' not in text:raise RuntimeError('Trust anchor is not a CA')
    return {'leafDerSha256':fingerprint(chain[0]),'notBefore':before,'notAfter':after}


def tool(root: Path, binary: str, args: list, *, data=None, writable=None):
    """Production validation uses the instance's locked images, never host PATH."""
    resources=runtime.load(root)
    if binary not in {'openssl','keytool'}:raise RuntimeError('Unsupported TLS tool')
    if resources.get('verification') is True:
        return runtime.run([binary,*args],data=data).stdout
    image=resources['postgresImage' if binary=='openssl' else 'runtimeImage']
    if not re.search(r'(?:@|^)sha256:[a-f0-9]{64}$',image):raise RuntimeError('Locked TLS tool image required')
    command=['docker','run','--rm','-i','--network','none','--user',str(os.getuid())+':'+str(os.getgid()),
             '--label',runtime.LABEL+'='+resources['instanceId'],'--mount',f'type=bind,source={root},target={root},readonly']
    if writable:
        writable=Path(writable)
        if writable.resolve()!=writable.absolute() or not writable.is_relative_to(root):raise RuntimeError('TLS output escapes instance')
        command+=['--mount',f'type=bind,source={writable},target={writable}']
    return runtime.run([*command,'--entrypoint',binary,image,*args],data=data).stdout
