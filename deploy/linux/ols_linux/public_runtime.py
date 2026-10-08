"""Public names/certificates are execution inputs; DB/API remain on owned private TLS."""
import os
from pathlib import Path
import ssl
from urllib.parse import urlsplit
from . import bundle,runtime

FILES={'certificate':'public.crt','privateKey':'public.key','certificateAuthority':'public-ca.pem'}


def origin(value):
    if not isinstance(value,str):raise ValueError('Explicit HTTPS origin required')
    parsed=urlsplit(value)
    try:port=parsed.port
    except ValueError:raise ValueError('Invalid public port') from None
    if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment or port is not None and not 1<=port<=65535:
        raise ValueError('An HTTPS origin without credentials, path or query is required')
    return value


def origins(settings,ports):
    names={'publicOrigin','identityOrigin'}&settings.keys()
    if names and len(names)!=2:raise ValueError('Public workbench and identity origins must be explicit together')
    return {'origin':origin(settings['publicOrigin']) if names else 'https://localhost:'+str(ports['entry']),
            'identityOrigin':origin(settings['identityOrigin']) if names else 'https://localhost:'+str(ports['identity']),
            'apiOrigin':'https://localhost:'+str(ports['api'])}


def inputs(settings):
    if not ({'publicOrigin','identityOrigin'}&settings.keys()):
        if 'publicTlsFiles' in settings:raise RuntimeError('Public TLS requires explicit public origins')
        return {}
    origins(settings,{'entry':24844,'identity':24843,'api':24845})
    files=settings.get('publicTlsFiles',{})
    if set(files)!=set(FILES):raise RuntimeError('Public certificate, private key and explicit trust anchor are required')
    result={}
    for name,target in FILES.items():
        path=Path(files[name])
        if not path.is_absolute() or path.resolve()!=path or not path.is_file():raise RuntimeError('Absolute unlinked public TLS inputs required')
        if name=='privateKey' and os.name!='nt' and path.stat().st_mode&0o077:raise RuntimeError('Public TLS key must be private')
        result['certs/'+target]=bundle.sha(path)
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(files['certificate'],files['privateKey'])
    ssl.create_default_context(cafile=files['certificateAuthority'])
    return result


def install(root,settings,expected):
    for name,target in FILES.items():
        path=root/'certs'/target;data=Path(settings['publicTlsFiles'][name]).read_bytes()
        if __import__('hashlib').sha256(data).hexdigest()!=expected['certs/'+target]:raise RuntimeError('Original public TLS input changed')
        if path.exists() and path.read_bytes()!=data:raise RuntimeError('Original installed public TLS differs')
        if not path.exists():runtime.private_file(path,data)
    combined=(root/'certs/ca.pem').read_bytes()+b'\n'+(root/'certs/public-ca.pem').read_bytes()
    path=root/'certs/http-trust.pem'
    if path.exists() and path.read_bytes()!=combined:raise RuntimeError('Original HTTP trust changed')
    if not path.exists():runtime.private_file(path,combined)


def effective_paths(root):
    from . import tls_generation
    if (root/'tls/active.json').exists():return tls_generation.paths(root,tls_generation.resolve(root))
    return {'certificate':str(root/'certs/public.crt'),'privateKey':str(root/'certs/public.key'),
            'httpTrust':str(root/'certs/http-trust.pem'),'javaTrustStore':str(root/'certs/identity-trust.p12')}
