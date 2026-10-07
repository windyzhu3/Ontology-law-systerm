"""Linux Docker resources, registered before effects and checked before reuse."""
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import shutil
import urllib.request
import ssl
from . import journal
from .bundle import sha
from .config import digest

LABEL = 'ols.instance'


def run(args: list[str], data: bytes | None = None, timeout=60, check=True):
    try:
        result = subprocess.run([str(a) for a in args], input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError('Controlled operation unavailable or result unknown; reconcile original operation') from error
    if check and result.returncode:
        # SQL/IdP stderr can contain protected input. Never forward it into public logs.
        raise RuntimeError(f'Controlled operation failed (exit {result.returncode}); original state retained')
    return result


def private_file(path: Path, data: bytes):
    path = Path(path)
    if path.is_symlink() or path.parent.resolve() != path.parent.absolute(): raise RuntimeError('Unsafe private path')
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    temp = path.with_name('.' + path.name + '.' + secrets.token_hex(8))
    try:
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as out: out.write(data); out.flush(); os.fsync(out.fileno())
        os.replace(temp, path)
        if os.name != 'nt':
            fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
    finally:
        if temp.exists(): temp.unlink()


def inspect(kind: str, name: str):
    if kind not in {'container','volume','network','image'}: raise ValueError('Named Docker resource required')
    result = run(['docker', kind, 'inspect', name], check=False)
    if result.returncode: return None
    values = json.loads(result.stdout)
    if len(values) != 1: raise RuntimeError('Ambiguous Docker resource')
    return values[0]


def load(root: Path) -> dict:
    with journal.locked(root) as root: return journal._read(root, root/'resources.json')


def save(root: Path, resources: dict):
    with journal.locked(root) as root: journal._write(root, root/'resources.json', resources)


def owned(root: Path, kind: str, name: str) -> dict:
    root = journal.safe_root(root)
    actual = inspect(kind, name)
    labels = actual.get('Config',{}).get('Labels',{}) if kind=='container' and actual else actual.get('Labels',{}) if actual else {}
    if not actual or labels.get(LABEL) != journal._owner(root)['instanceId']:
        raise RuntimeError('Resource is missing or belongs to another instance')
    return actual


def image(repo: Path, name: str) -> str:
    path = repo/'database/schema-contract-52-plus-2/runtime/toolchain.lock.json'
    values = json.loads(path.read_text())['images']
    matches = [v for v in values if v['image']==name]
    if len(matches)!=1 or not re.fullmatch('sha256:[a-f0-9]{64}',matches[0]['digest']):
        raise RuntimeError('One locked Docker image required')
    return name+'@'+matches[0]['digest']


def validate_tls(root: Path):
    root=journal.safe_root(root)
    for name in ['ca.pem','server.crt','server.key']:
        path=root/'certs'/name
        if not path.is_file() or path.is_symlink() or path.resolve()!=path.absolute():
            raise RuntimeError('Instance TLS files unavailable')
    if os.name!='nt' and (root/'certs/server.key').stat().st_mode & 0o077:
        raise RuntimeError('TLS private key must be private')
    try:
        context=ssl.create_default_context(cafile=str(root/'certs/ca.pem'))
        context.load_cert_chain(str(root/'certs/server.crt'),str(root/'certs/server.key'))
    except (ssl.SSLError,OSError) as error:raise RuntimeError('Invalid or mismatched TLS material') from error


def prepare(root: Path, settings: dict) -> dict:
    with journal.locked(root) as root:
        operation=journal.current(root)
        if operation['kind']!='initialize' or operation['phase'] not in {'CREATED','PREPARING','INFRASTRUCTURE_READY'}:
            raise RuntimeError('Infrastructure requires the original initialization operation')
        prefix=settings['name'];repo=Path(settings['repo']).absolute()
        if not re.fullmatch('ols-[a-z0-9][a-z0-9-]{0,45}',prefix): raise ValueError('Unique ols resource prefix required')
        run(['docker','info','--format','{{.OSType}}'])
        config_digest=digest(settings)
        if (root/'resources.json').exists():
            resources=load(root)
            if resources['settingsDigest']!=config_digest: raise RuntimeError('Infrastructure settings changed; refuse old operation reuse')
        else:
            resources={'version':1,'instanceId':operation['instanceId'],'operationId':operation['operationId'],
                       'name':prefix,'repo':str(repo),'settingsDigest':config_digest,'network':prefix+'-network',
                       'volumes':[prefix+'-business-data',prefix+'-identity-data'],
                       'containers':{'businessDb':prefix+'-business-db','identityDb':prefix+'-identity-db'},
                       'ports':settings.get('ports',{}),'writers':[],'ingress':None,'postgresImage':image(repo,'postgres'),
                       'flywayImage':image(repo,'redgate/flyway')}
            for kind,names in [('network',[resources['network']]),('volume',resources['volumes']),('container',list(resources['containers'].values()))]:
                if any(inspect(kind,name) for name in names): raise RuntimeError('Requested resources already exist; not adopted by name')
            save(root,resources)
        opid=operation['operationId'];instance=operation['instanceId']
        journal.record(root,opid,{'phase':'PREPARING','settingsDigest':config_digest})
        label=[ '--label',LABEL+'='+instance,'--label','ols.operation='+opid]
        if inspect('network',resources['network']): owned(root,'network',resources['network'])
        else: run(['docker','network','create',*label,resources['network']])
        for volume in resources['volumes']:
            if inspect('volume',volume): owned(root,'volume',volume)
            else: run(['docker','volume','create',*label,volume])
        certs=root/'certs';certs.mkdir(mode=0o700,exist_ok=True)
        if not (certs/'ca.pem').exists():
            if list(certs.iterdir()): raise RuntimeError('Partial TLS effect; preserve and reconcile it')
            names=','.join('DNS:'+name for name in list(resources['containers'].values())+['localhost',prefix+'-api',prefix+'-identity'])
            script=('umask 077; cd /out; openssl req -x509 -newkey rsa:3072 -nodes -keyout ca.key -out ca.pem -days 365 '
                    '-subj /CN=OLS-Acceptance-CA >/dev/null 2>&1; '
                    'openssl req -newkey rsa:3072 -nodes -keyout server.key -out server.csr -subj /CN=localhost >/dev/null 2>&1; '
                    f"printf 'subjectAltName={names},IP:127.0.0.1\\nextendedKeyUsage=serverAuth,clientAuth\\n' > extensions; "
                    'openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.crt -days 365 -extfile extensions >/dev/null 2>&1; '
                    'chmod 600 /out/*')
            user=[] if os.name=='nt' else ['--user',str(os.getuid())+':'+str(os.getgid())]
            run(['docker','run','--rm',*label,*user,'--mount',f'type=bind,source={certs},target=/out',resources['postgresImage'],'sh','-euc',script])
        validate_tls(root)
        private_file(certs/'pg_hba.conf',b'local all all trust\nlocal replication all trust\nhostnossl all all all reject\nhostssl all all all scram-sha-256\n')
        (root/'secrets').mkdir(mode=0o700,exist_ok=True)
        for name in ['business-db','identity-db','migrator','api-db','worker-db']:
            path=root/'secrets'/(name+'.txt')
            if not path.exists(): private_file(path,secrets.token_urlsafe(32).encode())
        for index,(key,db) in enumerate([('businessDb','law_contract_runtime'),('identityDb','keycloak')]):
            name=resources['containers'][key]
            if inspect('container',name) and not owned(root,'container',name)['State']['Running']:
                raise RuntimeError('Registered database stopped; explicit original resume required')
            secret=root/'secrets'/('business-db.txt' if index==0 else 'identity-db.txt')
            start_database(root,name,resources['volumes'][index],db,secret,certs,opid,resources['ports'].get(key))
        journal.record(root,opid,{'phase':'INFRASTRUCTURE_READY','resourcesDigest':digest(resources),'caSha256':sha(certs/'ca.pem')})
        return resources


def stop_writers(root: Path, operation_id: str) -> None:
    with journal.locked(root) as root:
        journal.read(root,operation_id)
        resources=load(root)
        names=([resources['ingress']] if resources.get('ingress') else [])+resources['writers']
        for name in dict.fromkeys(names):
            if inspect('container',name) is None:continue
            actual=owned(root,'container',name)
            if actual['State']['Running']: run(['docker','stop','--time','15',name],timeout=30)
            if owned(root,'container',name)['State']['Running']: raise RuntimeError('Writer did not stop')
        from . import database
        for identity in [False,True]:
            db='keycloak' if identity else 'law_contract_runtime'
            count=database.sql(root,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{db}' AND backend_type='client backend' AND pid<>pg_backend_pid()",identity=identity)
            if count!='0':raise RuntimeError('Unregistered database sessions remain; not stopped by the controller')
        journal.record(root,operation_id,{'phase':'WRITERS_STOPPED','writers':names})


def start_database(root: Path,name: str,volume: str,db: str,secret: Path,certs: Path,operation_id: str,port=None):
    """Start only a previously registered replacement database; preserve its volume."""
    resources=load(root);journal.read(root,operation_id)
    if name not in resources['containers'].values() or volume not in resources['volumes'] or db not in {'law_contract_runtime','keycloak'}:
        raise RuntimeError('Unregistered replacement database resource')
    owned(root,'volume',volume);owned(root,'network',resources['network'])
    for path in [Path(secret),Path(certs)]:
        if path.resolve()!=path.absolute() or not path.is_relative_to(root):raise RuntimeError('Replacement secrets outside controlled runtime')
    actual=inspect('container',name)
    if actual:
        actual=owned(root,'container',name)
        if not any(m.get('Name')==volume and m.get('Destination')=='/var/lib/postgresql' for m in actual['Mounts']):raise RuntimeError('Replacement database volume differs')
        if not actual['State']['Running']:run(['docker','start',name])
    else:
        ports=[]
        if port is not None:
            if type(port)!=int or not 1024<=port<=65535:raise ValueError('Valid loopback port required')
            ports=['-p',f'127.0.0.1:{port}:5432']
        startup=('mkdir -p /tmp/ols-tls; cp /run/ols-certs/ca.pem /run/ols-certs/server.crt /run/ols-certs/server.key /run/ols-certs/pg_hba.conf /tmp/ols-tls/; '
            'cp /run/ols-password /tmp/ols-tls/password; chown -R postgres:postgres /tmp/ols-tls; chmod 700 /tmp/ols-tls; chmod 600 /tmp/ols-tls/*; '
            'exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/ols-tls/server.crt -c ssl_key_file=/tmp/ols-tls/server.key '
            '-c ssl_ca_file=/tmp/ols-tls/ca.pem -c ssl_min_protocol_version=TLSv1.3 -c hba_file=/tmp/ols-tls/pg_hba.conf')
        run(['docker','run','-d','--name',name,'--label',LABEL+'='+resources['instanceId'],'--label','ols.operation='+operation_id,
            '--network',resources['network'],*ports,'-e','POSTGRES_DB='+db,'-e','POSTGRES_PASSWORD_FILE=/tmp/ols-tls/password',
            '--mount',f'type=volume,source={volume},target=/var/lib/postgresql','--mount',f'type=bind,source={secret},target=/run/ols-password,readonly',
            '--mount',f'type=bind,source={certs},target=/run/ols-certs,readonly',resources['postgresImage'],'sh','-euc',startup])
    deadline=time.monotonic()+90
    while run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres','-d',db],check=False).returncode:
        if time.monotonic()>deadline:raise RuntimeError('Replacement database readiness unknown')
        time.sleep(.25)
    if port is not None and owned(root,'container',name)['NetworkSettings'].get('Ports',{}).get('5432/tcp')!=[{'HostIp':'127.0.0.1','HostPort':str(port)}]:
        raise RuntimeError('Replacement loopback port is unavailable')


def start_internal(root: Path, descriptor: dict) -> dict:
    with journal.locked(root) as root:
        validate_tls(root)
        resources=load(root)
        # Launch definitions are sealed by the application-config/bundle installation stages.
        launch=journal._read(root,root/'launch.json')
        if launch['descriptorDigest']!=descriptor['descriptorDigest']: raise RuntimeError('Launch bytes differ from the verified release')
        for entry in launch['containers']:
            name=entry['name']
            if name not in resources['writers'] or entry['role'] not in {'api','worker','identity','scanner'}: raise RuntimeError('Unregistered writer launch')
            if entry['role']=='identity':
                from . import identity
                plan=journal._read(root,root/'identity/plan.json')
                if name!=plan['identity'] or entry['digest']!=digest(plan) or entry['args']:
                    raise RuntimeError('Original identity launch differs')
                identity._start(root,plan)
                continue
            if inspect('container',name):
                actual=owned(root,'container',name)
                if actual['Config']['Labels'].get('ols.launch')!=entry['digest']: raise RuntimeError('Container launch configuration changed')
                if not actual['State']['Running']: run(['docker','start',name])
            else: run(entry['args'])
        return {'started':True,'descriptorDigest':descriptor['descriptorDigest']}


def open_ingress(root: Path, operation_id: str) -> None:
    with journal.locked(root) as root:
        operation=journal.read(root,operation_id)
        if operation['phase']!='RUNTIME_VERIFIED': raise RuntimeError('Public ingress requires verified runtime')
        resources=load(root)
        if not resources['ingress']: raise RuntimeError('Public ingress not configured')
        owned(root,'container',resources['ingress'])
        run(['docker','start',resources['ingress']])
        journal.record(root,operation_id,{'phase':'INGRESS_OPEN'})


def cleanup_verification(root: Path):
    """Only an explicitly marked verification instance can delete its registered resources."""
    with journal.locked(root) as root:
        resources=load(root)
        if not resources.get('verification'): raise RuntimeError('Production resource cleanup is not supported')
        for name in list(resources['containers'].values())+resources.get('writers',[]):
            if inspect('container',name): owned(root,'container',name);run(['docker','rm','-f',name])
        for name in resources['volumes']:
            if inspect('volume',name):owned(root,'volume',name);run(['docker','volume','rm',name])
        if inspect('network',resources['network']):owned(root,'network',resources['network']);run(['docker','network','rm',resources['network']])


def build_image(repo: Path, context: Path) -> str:
    """Resolve same-version Linux binaries with their explicit reviewed checksums."""
    repo,context=Path(repo).absolute(),Path(context).absolute()
    if context.resolve()!=context:raise RuntimeError('Linked build context rejected')
    lock=json.loads((repo/'deploy/linux/runtime/toolchain.lock.json').read_text())
    if lock['platform']!='linux/amd64' or lock['jdk']['version']!='25.0.4.1+1' or lock['node']['version']!='24.20.0':
        raise RuntimeError('Reviewed runtime versions required')
    if not re.fullmatch('node@sha256:[a-f0-9]{64}',lock['node']['image']):raise RuntimeError('Immutable Node base image required')
    context.mkdir(mode=0o700,parents=True,exist_ok=True)
    archive=context/'jdk.tar.gz'
    if archive.is_symlink():raise RuntimeError('Linked runtime artifact rejected')
    if not archive.exists():
        temporary=context/'jdk.tar.gz.download'
        if temporary.exists():raise RuntimeError('Partial runtime download retained; verify or explicitly replace that private cache file')
        with urllib.request.urlopen(lock['jdk']['url'],timeout=60) as response,temporary.open('xb') as out:
            while chunk:=response.read(1024*1024):out.write(chunk)
        if sha(temporary)!=lock['jdk']['sha256']:raise RuntimeError('Runtime download checksum differs; retained for inspection')
        os.replace(temporary,archive)
    if sha(archive)!=lock['jdk']['sha256']:raise RuntimeError('Existing runtime archive differs; never silently replaced')
    npm=context/'npm.tar.gz'
    if lock['npm']['version']!='11.9.0':raise RuntimeError('Reviewed npm version required')
    if npm.is_symlink():raise RuntimeError('Linked npm archive rejected')
    if not npm.exists():
        temporary=context/'npm.tar.gz.download'
        if temporary.exists():raise RuntimeError('Partial npm download retained')
        with urllib.request.urlopen(lock['npm']['url'],timeout=60) as response,temporary.open('xb') as out:
            while chunk:=response.read(1024*1024):out.write(chunk)
        if sha(temporary)!=lock['npm']['sha256']:raise RuntimeError('npm checksum differs')
        os.replace(temporary,npm)
    if sha(npm)!=lock['npm']['sha256']:raise RuntimeError('Existing npm archive differs')
    shutil.copyfile(repo/'deploy/linux/runtime/Dockerfile.app',context/'Dockerfile')
    toolchain=digest({'lock':lock,'dockerfileSha256':sha(context/'Dockerfile')})
    name='ols-linux-runtime:'+toolchain[:16]
    previous=inspect('image',name)
    if previous:
        if previous['Config'].get('Labels',{}).get('ols.toolchain')!=toolchain:raise RuntimeError('Foreign runtime image tag rejected')
        return previous['Id']
    result=run(['docker','build','--platform','linux/amd64','--label','ols.toolchain='+toolchain,'--build-arg','NODE_IMAGE='+lock['node']['image'],'-t',name,str(context)],timeout=300,check=False)
    private_file(context/'build.log',result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('Linux runtime image build failed; private build log retained')
    actual=inspect('image',name)
    if not actual:raise RuntimeError('Image build result unknown')
    return actual['Id']
