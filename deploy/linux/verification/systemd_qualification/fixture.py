"""Fixed synthetic systemd fixture. Never imports or copies production materials."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import ssl
import sys
import time
from ols_linux import journal,runtime,tls_material,tls_systemd as sd

BASE=Path('/run/ols-tls-qualification');ROOT=BASE/'runtime'
NAMES={role:'ols-tls-qualification-'+role+'.service' for role in ('nginx','caddy','upstream')}
SLICE='ols-tls-qualification.slice';SCOPE='ols-tls-qualification-control.scope'
DROPIN=Path('/etc/systemd/system')/(NAMES['caddy']+'.d')/'50-ols-public-tls.conf'
BINARIES={'nginx':('/usr/sbin/nginx','9cf471abeb2d00b07ab215264fdd87100f5b5e52ccc5f6045acb469929dfb576'),
          'caddy':('/usr/bin/caddy','33cd4c300c46fef824abe017fb3c5698698c41cd7eaf9cf097126d5ac6e4cf4a')}
SLICE_TEXT='[Slice]\nMemoryMax=512M\nMemoryHigh=256M\nMemorySwapMax=0\nCPUQuota=50%\nTasksMax=128\n'


def put(path,data,mode=0o600):
    runtime.private_file(Path(path),data.encode() if isinstance(data,str) else data);os.chmod(path,mode)


def record(path):
    data,st=sd._read(path)
    return {'path':str(path),'sha256':hashlib.sha256(data).hexdigest(),'uid':st.st_uid,'gid':st.st_gid,'mode':st.st_mode&0o777}


def unit(role):
    common=f'[Unit]\nDescription=Isolated synthetic TLS qualification {role}\nAfter=network.target\n[Service]\nType=simple\nSlice={SLICE}\nMemorySwapMax=0\nNoNewPrivileges=yes\nUMask=0077\nLimitFSIZE=16777216\nStandardOutput=null\nStandardError=append:{BASE}/evidence/{role}.stderr\n'
    if role=='nginx':
        return common+f'ExecStartPre=/usr/sbin/nginx -t -c {BASE}/nginx/nginx.conf\nExecStart=/usr/sbin/nginx -c {BASE}/nginx/nginx.conf -g "daemon off;"\nExecReload=/usr/sbin/nginx -t -c {BASE}/nginx/nginx.conf\nExecReload=/bin/kill -HUP $MAINPID\nKillSignal=SIGQUIT\nTimeoutStopSec=30\nRestart=on-failure\nMemoryMax=64M\n'
    credentials={'server.crt':'bridge.crt','server.key':'bridge.key','internal-ca.pem':'internal-ca.pem','issuer-ca.pem':'issuer-ca.pem'} if role=='caddy' else {r+'.'+ext:r+'.'+ext for r in ('identity','app') for ext in ('crt','key')}
    text=common+'User=caddy\nGroup=caddy\n'+''.join(f'LoadCredential={name}:{BASE}/materials/{file}\n' for name,file in credentials.items())
    text+='PrivateTmp=yes\nPrivateDevices=yes\nProtectHome=yes\nProtectSystem=strict\nProtectKernelTunables=yes\nProtectKernelModules=yes\nProtectControlGroups=yes\nRestrictAddressFamilies=AF_UNIX AF_INET AF_INET6\nCapabilityBoundingSet=\n'
    if role=='caddy':
        text+=f'ExecStartPre=/usr/bin/caddy validate --config {BASE}/caddy/bridge.Caddyfile --adapter caddyfile\nExecStart=/usr/bin/caddy run --config {BASE}/caddy/bridge.Caddyfile --adapter caddyfile\nStateDirectory=ols-tls-qualification/caddy\nStateDirectoryMode=0700\nEnvironment=XDG_DATA_HOME=/var/lib/ols-tls-qualification/caddy XDG_CONFIG_HOME=/var/lib/ols-tls-qualification/caddy GOMEMLIMIT=128MiB\nMemoryMax=256M\nRestart=on-failure\nRestartSec=5\nTimeoutStopSec=15\n'
    else:text+=f'ExecStart={sys.executable} -B {BASE}/package/verification/systemd_qualification/upstream.py --config {BASE}/caddy/upstreams.json\nMemoryMax=128M\nTimeoutStopSec=15\n'
    return text


def materials():
    directory=BASE/'materials'
    if directory.exists():raise RuntimeError('Refusing to regenerate original synthetic material')
    directory.mkdir(mode=0o700)
    now=datetime.datetime.now(datetime.timezone.utc)
    def date(seconds):return (now+datetime.timedelta(seconds=seconds)).strftime('%Y%m%d%H%M%SZ')
    openssl=shutil.which('openssl')
    if not openssl:raise RuntimeError('Existing openssl required')
    def command(*args):runtime.run([openssl,*args],timeout=30)
    for ca in ('internal','old','new','wrong'):
        key=directory/(ca+'-ca.key');cert=directory/(ca+'-ca.pem')
        command('req','-x509','-newkey','rsa:2048','-nodes','-sha256','-days','3','-subj','/CN=ISOLATED-QUALIFICATION-'+ca,'-keyout',key,'-out',cert)
        database=directory/(ca+'-database');database.mkdir(mode=0o700)
        put(database/'index','');put(database/'serial','1000\n')
        put(directory/(ca+'.cnf'),f'[ca]\ndefault_ca=fixture\n[fixture]\ndatabase={database}/index\nnew_certs_dir={database}\nserial={database}/serial\ncertificate={cert}\nprivate_key={key}\ndefault_md=sha256\ndefault_days=2\npolicy=policy\ncopy_extensions=copy\nx509_extensions=leaf\n[policy]\ncommonName=supplied\n[leaf]\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    # Same native leaf for both upstreams; deliberately different external leaf.
    definitions=[('old-native','old','IP:127.0.0.1',360),('old-public','old','IP:127.0.0.1',900),('new-public','new','IP:127.0.0.1',172800),('bridge','internal','DNS:localhost',172800)]
    for prefix,ca,san in [('native','old','IP:127.0.0.1'),('bridge','internal','DNS:localhost')]:
        definitions.extend([(prefix+'-wrong-ca','wrong',san,172800),(prefix+'-wrong-san',ca,'DNS:wrong.invalid',172800),(prefix+'-expired',ca,san,-60)])
    for name,ca,san,end in definitions:
        command('req','-new','-newkey','rsa:2048','-nodes','-sha256','-subj','/CN=ISOLATED-'+name,'-addext','subjectAltName='+san,'-keyout',directory/(name+'.key'),'-out',directory/(name+'.csr'))
        command('ca','-batch','-config',directory/(ca+'.cnf'),'-in',directory/(name+'.csr'),'-out',directory/(name+'.crt'),'-notext','-startdate',date(-120),'-enddate',date(end))
    for role in ('identity','app'):
        for ext in ('crt','key'):put(directory/(role+'.'+ext),(directory/('old-native.'+ext)).read_bytes())
    put(directory/'issuer-ca.pem',(directory/'old-ca.pem').read_bytes())
    for file in directory.rglob('*'):
        if file.is_file():os.chmod(file,0o600)


def prepare():
    os.umask(0o077)
    if (ROOT/'instance.json').exists():raise RuntimeError('Original fixture already exists; use run/resume')
    for name in ('inputs','evidence','operations'):(BASE/name).mkdir(mode=0o700,exist_ok=True)
    for name in ('nginx/conf.d','caddy','challenges/.well-known/acme-challenge','challenges/.well-known/pki-validation'):
        path=BASE/name;path.mkdir(parents=True,exist_ok=True)
        while path!=BASE:os.chmod(path,0o755);path=path.parent
    (BASE/"nginx/temp").mkdir(mode=0o700)
    nginx=pwd.getpwnam("nginx");os.chown(BASE/"nginx/temp",nginx.pw_uid,nginx.pw_gid)
    materials()
    op=journal.begin(ROOT,'rotate-public-tls','a'*64)
    runtime.save(ROOT,{'verification':True,'fixtureKind':'systemd-proxy-qualification'})
    journal._write(ROOT,ROOT/'identity/plan.json',{'issuer':'https://127.0.0.1:29848/realms/qualification','origin':'https://127.0.0.1:29848','pod':'none-synthetic-loopback'})
    put(ROOT/'certs/ca.pem',(BASE/'materials/internal-ca.pem').read_bytes())
    templates=Path(__file__).parent/'templates'
    for name,target in [('nginx.conf',BASE/'nginx/nginx.conf'),('http.conf',BASE/'nginx/conf.d/http.conf'),('https.conf',BASE/'nginx/conf.d/https.conf'),('bridge.Caddyfile',BASE/'caddy/bridge.Caddyfile')]:put(target,(templates/name).read_bytes(),0o644)
    for path in (BASE/'challenges/.well-known/acme-challenge/token',BASE/'challenges/.well-known/pki-validation/QUALIFICATION.txt'):put(path,'synthetic-challenge\n',0o644)
    put(BASE/'caddy/upstreams.json',json.dumps({'fixtureKind':'systemd-proxy-qualification','identityPort':29843,'appPort':29844,'runId':op['operationId']}),0o644)
    # Caddy may traverse only its non-secret fixture configuration and this input.
    installed={str(Path('/etc/systemd/system')/NAMES[role]):{'path':str(Path('/etc/systemd/system')/NAMES[role]),'sha256':hashlib.sha256(unit(role).encode()).hexdigest(),'uid':0,'gid':0,'mode':0o644} for role in NAMES}
    installed[str(Path('/etc/systemd/system')/SLICE)]=record(Path('/etc/systemd/system')/SLICE)
    # Record exact ownership before the first unit-file effect, including partial setup.
    journal._write(ROOT,ROOT/'verification/installed.json',installed)
    for role in NAMES:
        path=Path('/etc/systemd/system')/NAMES[role]
        if path.exists() or path.is_symlink():raise RuntimeError('Unit name collision')
        put(path,unit(role),0o644)
    runtime.run(['systemctl','daemon-reload'])
    return op


def registration():
    services=[]
    for role in ('nginx','caddy'):
        unit_file=Path('/etc/systemd/system')/NAMES[role]
        config=BASE/('nginx/conf.d/https.conf' if role=='nginx' else 'caddy/bridge.Caddyfile')
        main=BASE/('nginx/nginx.conf' if role=='nginx' else 'caddy/bridge.Caddyfile')
        snap=sd.unit_snapshot(NAMES[role],dict.fromkeys(sd.UNIT_PROPERTIES,''))
        process=sd.read_process(int(snap['state']['MainPID']))
        if snap['properties']['DropInPaths'] or snap['state']['NeedDaemonReload']!='no':raise RuntimeError('Unexpected original fixture drop-in/manager drift')
        files=[config]
        includes={}
        if role=='nginx':
            files.extend([main,BASE/'nginx/conf.d/http.conf',Path('/etc/nginx/mime.types')]);includes={str(BASE/'nginx/conf.d/*.conf'):sorted([str(config),str(BASE/'nginx/conf.d/http.conf')])}
        else:files.extend(BASE/'materials'/name for name in ('bridge.crt','bridge.key','internal-ca.pem','issuer-ca.pem'))
        expected_uid=0 if role=='nginx' else pwd.getpwnam('caddy').pw_uid
        expected_gid=0 if role=='nginx' else pwd.getpwnam('caddy').pw_gid
        expected_group='/ols.slice/ols-tls.slice/'+SLICE+'/'+NAMES[role]
        if (process['uid'],process['gid'],process['cgroup'])!=(expected_uid,expected_gid,expected_group):raise RuntimeError('Fixture process ownership differs')
        profile={'unitFile':record(unit_file),'immutableFiles':[record(p) for p in files],'mainConfig':str(main),'includes':includes,'properties':snap['properties'],'process':{k:process[k] for k in ('executable','argv','uid','gid','cgroup')},'credentialNames':[] if role=='nginx' else sorted(['server.crt','server.key','internal-ca.pem','issuer-ca.pem']),'listeners':[{'address':'127.0.0.1','port':p} for p in ((29845,29848) if role=='nginx' else (29846,29847))]}
        services.append({'role':role,'transport':'systemd','name':NAMES[role],'identity':profile['unitFile']['sha256'],'image':'sha256:'+BINARIES[role][1],'config':str(config),'configSha256':record(config)['sha256'],'tlsPaths':{'certificate':str(BASE/'materials/old-public.crt'),'privateKey':str(BASE/'materials/old-public.key')} if role=='nginx' else {},'systemd':profile})
    return {'version':2,'qualification':{'mode':'isolated'},'services':services}


def certificate(name):
    path=BASE/'materials'/(name+'.crt');data=path.read_bytes()
    return {'leafDerSha256':tls_material.fingerprint(tls_material.certificates(data)[0]),'notAfter':int(ssl.cert_time_to_seconds(tls_material.metadata(path)['notAfter']))}
