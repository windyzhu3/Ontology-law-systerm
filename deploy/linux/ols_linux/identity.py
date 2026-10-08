"""Original-operation realm import and offline bootstrap. No HUMAN password grant."""
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import re
import secrets
import tarfile
import time
import uuid
from urllib.parse import urlencode,urlsplit
from . import journal, runtime, database, tls_generation
from .config import canonical, digest
from .bundle import sha


def secret_file(path: Path) -> str:
    path=Path(path).absolute()
    if path.resolve()!=path or not path.is_file(): raise RuntimeError('Protected secret file missing or linked')
    if os.name!='nt' and path.stat().st_mode & 0o077: raise RuntimeError('Secret file must have mode 0600')
    value=path.read_text(encoding='utf-8').strip()
    if not value or '\n' in value or '\r' in value: raise ValueError('One protected secret value required')
    return value


def _empty_identity_database(root):
    if database.sql(root,"SELECT to_regclass('public.realm') IS NULL",identity=True)=='t':return True
    return database.sql(root,'SELECT count(*) FROM public.realm',identity=True)=='0'


def _new_plan(root, config, password):
    resources=runtime.load(root);op=journal.current(root)
    if op['kind']!='initialize':raise RuntimeError('Identity preparation requires original initialization')
    if not _empty_identity_database(root):raise RuntimeError('Existing identity realm is not adopted')
    name=resources['name'];repo=Path(resources['repo'])
    if not re.fullmatch('ols-[a-z0-9][a-z0-9-]{0,45}',name):raise RuntimeError('Registered prefix required')
    image=resources.get('runtimeImage')
    if not image or not re.fullmatch('sha256:[a-f0-9]{64}',image):raise RuntimeError('Locked Linux application image required')
    ports={key:resources.get('ports',{}).get(key,default) for key,default in [('identity',24843),('api',24845),('entry',24844)]}
    if len(set(ports.values()))!=3 or any(type(p)!=int or not 1024<=p<=65535 for p in ports.values()):raise ValueError('Three distinct controlled ports required')
    for key in ['pod','identity']:
        if runtime.inspect('container',name+'-'+key):raise RuntimeError('Existing named runtime is not adopted')
    realm=name;spa=name+'-spa';audience=name+'-api';directory=name+'-directory'
    from .public_runtime import origins
    public=origins(resources,ports);origin=public['origin'];issuer=public['identityOrigin']+'/realms/'+realm
    values={'IDENTITY_REALM':realm,'SPA_CLIENT_ID':spa,'SPA_REDIRECT_URI':origin+'/auth/callback',
        'SPA_ORIGIN':origin,'SPA_LOGOUT_REDIRECT_URI':origin+'/login','API_AUDIENCE':audience,'DIRECTORY_CLIENT_ID':directory}
    template=(repo/'deploy/identity/realm-template.json').read_text(encoding='utf-8')
    for key,value in values.items():template=template.replace('${'+key+'}',value)
    if '${' in template:raise RuntimeError('Unresolved realm template')
    representation=json.loads(template);representation['passwordPolicy']='length(8) and notUsername and notEmail'
    marker={'olsOperation':[op['operationId']],'olsInstance':[op['instanceId']],'olsConfig':[digest(config)]}
    representation['attributes']={key:value[0] for key,value in marker.items()}
    profile={'attributes':[
        {'name':'username','displayName':'${username}','validations':{'length':{'min':3,'max':255},'username-prohibited-characters':{}},'permissions':{'view':['admin','user'],'edit':['admin']}},
        {'name':'email','displayName':'${email}','validations':{'email':{},'length':{'max':255}},'permissions':{'view':['admin','user'],'edit':['admin','user']}},
        *[{'name':name,'displayName':'${'+name+'}','validations':{'length':{'max':255},'person-name-prohibited-characters':{}},'permissions':{'view':['admin','user'],'edit':['admin','user']}} for name in ['firstName','lastName']],
        *[{'name':name,'validations':{'length':{'max':128}},'permissions':{'view':['admin'],'edit':['admin']}} for name in marker]]}
    representation['components']={'org.keycloak.userprofile.UserProfileProvider':[{'providerId':'declarative-user-profile',
        'config':{'kc.user.profile.config':[canonical(profile).decode('utf-8')]}}]}
    subjects={p['username']:str(uuid.uuid4()) for p in config['people']}
    representation['users']=[{'id':subjects[p['username']],'username':p['username'],'firstName':p['name'][1:],'lastName':p['name'][0],
        'enabled':True,'emailVerified':True,'attributes':marker,'requiredActions':['UPDATE_PASSWORD'],
        'credentials':[{'type':'password','value':password,'temporary':True}]} for p in config['people']]
    for client,secret_name in [(representation['clients'][1],'introspection'),(representation['clients'][2],'directory')]:
        client['secret']=secrets.token_urlsafe(32)
        runtime.private_file(root/'secrets'/(secret_name+'.txt'),client['secret'].encode())
    representation['users'].append({'id':str(uuid.uuid4()),'username':'service-account-'+directory,'enabled':True,
        'serviceAccountClientId':directory,'attributes':marker,'clientRoles':{'realm-management':['query-users','view-users']}})
    lock=json.loads((repo/'deploy/identity/identity-toolchain.lock.json').read_text())['keycloak']
    image_digest=lock['platformDigest']
    if lock['version']!='26.7.3' or not re.fullmatch('sha256:[a-f0-9]{64}',image_digest):raise RuntimeError('Reviewed identity image required')
    plan={'version':1,'operationId':op['operationId'],'instanceId':op['instanceId'],'tenantId':str(uuid.uuid4()),'configDigest':digest(config),
        'initialPasswordDigest':digest({'value':password}),'subjects':subjects,'marker':marker,'realm':realm,
        'issuer':issuer,'origin':origin,'apiOrigin':public['apiOrigin'],
        'spaClient':spa,'audience':audience,'directoryClient':directory,'ports':ports,'runtimeImage':image,
        'keycloakImage':lock['image']+'@'+image_digest,'pod':name+'-pod','identity':name+'-identity'}
    runtime.private_file(root/'identity/realm.json',canonical(representation))
    plan['realmSha256']=sha(root/'identity/realm.json')
    journal._write(root,root/'identity/plan.json',plan)
    return plan


def prepare(root: Path, config: dict, initial_password_file: Path) -> dict:
    with journal.locked(root) as root:
        password=secret_file(initial_password_file)
        if len(password)<8:raise ValueError('Initial password requires at least eight characters')
        op=journal.current(root)
        if op['kind']!='initialize' or op['configDigest']!=digest(config):raise RuntimeError('Initialization configuration changed')
        path=root/'identity/plan.json'
        plan=journal._read(root,path) if path.exists() else _new_plan(root,config,password)
        if plan['configDigest']!=digest(config) or plan['initialPasswordDigest']!=digest({'value':password}) or plan['operationId']!=op['operationId']:
            raise RuntimeError('Original identity configuration or password changed')
        if sha(root/'identity/realm.json')!=plan['realmSha256']:raise RuntimeError('Original import bytes changed')
        journal.record(root,op['operationId'],{'phase':'IDENTITY_IMPORT_UNKNOWN','realmSha256':plan['realmSha256']})
        _start(root,plan)
        result=verify(root)
        journal.record(root,op['operationId'],{'phase':'IDENTITY_VERIFIED','humanAccounts':17,'passwordUpdatesRequired':result['passwordUpdatesRequired']})
        return {'subjects':plan['subjects'],'issuer':plan['issuer']}


def _copy_files(container, destination, files, uid=1000):
    if destination.endswith('/import'):
        directory=io.BytesIO()
        with tarfile.open(fileobj=directory,mode='w') as archive:
            entry=tarfile.TarInfo('import');entry.type=tarfile.DIRTYPE;entry.uid=uid;entry.gid=uid;entry.mode=0o700
            archive.addfile(entry)
        runtime.run(['docker','cp','-',container+':'+destination.rsplit('/',1)[0]],directory.getvalue())
    data=io.BytesIO()
    with tarfile.open(fileobj=data,mode='w') as archive:
        for name,content in files.items():
            entry=tarfile.TarInfo(name);entry.uid=uid;entry.gid=uid;entry.mode=0o600;entry.size=len(content)
            archive.addfile(entry,io.BytesIO(content))
    runtime.run(['docker','cp','-',container+':'+destination],data.getvalue())


def _database_login(root):
    path=root/'identity/db-login.json';password_path=root/'secrets/identity-app.txt'
    original=journal.current(root)
    if (root/'identity/plan.json').exists():
        identity_plan=journal._read(root,root/'identity/plan.json')
        original=journal.read(root,identity_plan['operationId'])
        if identity_plan['instanceId']!=journal._owner(root)['instanceId']:raise RuntimeError('Original identity instance differs')
    if original['kind']!='initialize':raise RuntimeError('Original identity initialization login required')
    if not path.exists():
        if database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname='ols_identity_login'",identity=True)!='0':raise RuntimeError('Existing identity login is not adopted')
        runtime.private_file(password_path,secrets.token_urlsafe(32).encode())
        journal._write(root,path,{'operationId':original['operationId'],'passwordDigest':sha(password_path)})
    plan=journal._read(root,path)
    if plan['operationId']!=original['operationId'] or sha(password_path)!=plan['passwordDigest']:raise RuntimeError('Original identity database login changed')
    count=database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname='ols_identity_login'",identity=True)
    if count=='0':
        password=secret_file(password_path)
        if not re.fullmatch('[A-Za-z0-9_-]{20,100}',password):raise RuntimeError('Controlled identity database secret invalid')
        database.sql(root,"BEGIN; CREATE ROLE ols_identity_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password+"'; ALTER DATABASE keycloak OWNER TO ols_identity_login; COMMIT;",identity=True)
    elif database.sql(root,"SELECT count(*) FROM pg_roles r JOIN pg_database d ON d.datdba=r.oid WHERE r.rolname='ols_identity_login' AND d.datname='keycloak' AND r.rolcanlogin AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolinherit",identity=True)!='1':
        raise RuntimeError('Original identity database owner or login attributes differ')


def _start(root, plan):
    resources=runtime.load(root);instance=plan['instanceId'];opid=plan['operationId'];repo=resources['repo']
    labels=['--label',runtime.LABEL+'='+instance,'--label','ols.operation='+opid]
    launch_digest=digest(plan)
    for key in ['pod','identity']:
        existing=resources['containers'].get(key)
        if existing and existing!=plan[key]:raise RuntimeError('Original identity resource changed')
        resources['containers'][key]=plan[key]
    if plan['identity'] not in resources['writers']:resources['writers'].append(plan['identity'])
    runtime.save(root,resources)
    pod=runtime.inspect('container',plan['pod'])
    if not pod:
        publish=[arg for port in plan['ports'].values() for arg in ['-p',f'127.0.0.1:{port}:{port}']]
        runtime.run(['docker','run','-d','--name',plan['pod'],*labels,'--label','ols.identity-plan='+launch_digest,
            '--network',resources['network'],'--network-alias',resources['name']+'-api',*publish,
            '--mount',f'type=bind,source={repo}/deploy/linux,target=/opt/ols,readonly',
            '--mount',f'type=bind,source={root},target={root},readonly','--entrypoint','sh',plan['runtimeImage'],'-c','exec sleep infinity'])
    else:
        pod=runtime.owned(root,'container',plan['pod'])
        if pod['Config']['Labels'].get('ols.identity-plan')!=launch_digest:raise RuntimeError('Original pod differs')
        if not pod['State']['Running']:runtime.run(['docker','start',plan['pod']])
    ports=runtime.owned(root,'container',plan['pod'])['NetworkSettings'].get('Ports',{})
    if any(ports.get(str(port)+'/tcp')!=[{'HostIp':'127.0.0.1','HostPort':str(port)}] for port in plan['ports'].values()):raise RuntimeError('Published identity/runtime port unavailable')
    if not tls_generation.managed(root):_database_login(root)
    existing=runtime.inspect('container',plan['identity'])
    if not existing:
        env={'KC_DB':'postgres','KC_DB_URL':'jdbc:postgresql://'+resources['containers']['identityDb']+':5432/keycloak?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/ca.pem',
            'KC_DB_USERNAME':'ols_identity_login','KC_HOSTNAME':plan['issuer'].split('/realms/')[0],'KC_HOSTNAME_STRICT':'true',
            'KC_HTTP_ENABLED':'false','KC_HTTPS_PORT':str(plan['ports']['identity']),
            'KC_HTTPS_CERTIFICATE_FILE':'/opt/keycloak/conf/server.crt','KC_HTTPS_CERTIFICATE_KEY_FILE':'/opt/keycloak/conf/server.key',
            'KC_FEATURES_DISABLED':'impersonation,parameterized-scopes','KC_HTTP_ACCESS_LOG_ENABLED':'false'}
        envargs=[arg for key,value in env.items() for arg in ['-e',key+'='+value]]
        runtime.run(['docker','create','--name',plan['identity'],*labels,'--label','ols.identity-plan='+launch_digest,
            '--network','container:'+plan['pod'],'--memory','2g','--cpus','2',*envargs,'--entrypoint','/bin/bash',plan['keycloakImage'],
            '-ec','export KC_DB_PASSWORD="$(</opt/keycloak/conf/db-password)"; exec /opt/keycloak/bin/kc.sh start --import-realm'])
    else:
        actual=runtime.owned(root,'container',plan['identity'])
        if actual['Config']['Labels'].get('ols.identity-plan')!=launch_digest:raise RuntimeError('Original identity launch differs')
    actual=runtime.owned(root,'container',plan['identity'])
    if not actual['State']['Running']:
        # Recopy only the original sealed import/secret bytes into the registered stopped container.
        if tls_generation.managed(root):
            from . import tls_deployment
            tls_deployment.copy_identity(root,plan['identity'],tls_generation.resolve(root))
        else:
            public=bool(resources.get('publicTlsHashes'))
            _copy_files(plan['identity'],'/opt/keycloak/conf',{'ca.pem':(root/'certs/ca.pem').read_bytes(),
                'server.crt':(root/'certs'/('public.crt' if public else 'server.crt')).read_bytes(),
                'server.key':(root/'certs'/('public.key' if public else 'server.key')).read_bytes(),
                'db-password':(root/'secrets/identity-app.txt').read_bytes()})
            _copy_files(plan['identity'],'/opt/keycloak/data/import',{plan['realm']+'-realm.json':(root/'identity/realm.json').read_bytes()})
        runtime.run(['docker','start',plan['identity']])
    deadline=time.monotonic()+150
    while True:
        if not runtime.owned(root,'container',plan['identity'])['State']['Running']:raise RuntimeError('Registered identity startup failed; private diagnostics and original import retained')
        try:
            response=http(root,plan['issuer']+'/.well-known/openid-configuration')
            if response['status']==200 and json.loads(response['body'])['issuer']==plan['issuer']:return
        except (RuntimeError,ValueError,KeyError):pass
        if time.monotonic()>deadline:raise RuntimeError('Identity readiness unknown; retain original import')
        time.sleep(.5)


def http_helper(root):
    if tls_generation.managed(root):
        from .tls_generation import resolve
        return resolve(root)['deployment']['httpHelper']
    path=root/'installed-candidate.json'
    if not path.exists():return '/opt/ols/runtime/https-json.mjs'
    installed=journal._read(root,path);descriptor=installed['descriptor']
    if descriptor.get('version')!=2:return '/opt/ols/runtime/https-json.mjs'
    directory=Path(installed['directory']);name='deploy/linux/runtime/https-json.mjs';helper=directory/name
    if directory.parent!=root/'releases' or directory.name!=descriptor['descriptorDigest'] or helper.resolve()!=helper.absolute() or sha(helper)!=descriptor['files'].get(name):
        raise RuntimeError('Installed HTTPS helper differs from sealed release')
    return str(helper)


def http(root, url, *, method='GET', headers=None, body=None, client_certificate=False):
    plan=journal._read(root,root/'identity/plan.json');runtime.owned(root,'container',plan['pod'])
    allowed=list({urlsplit(plan[name]).scheme+'://'+urlsplit(plan[name]).netloc for name in ('origin','issuer','apiOrigin')})
    parsed=urlsplit(url)
    if parsed.scheme!='https' or parsed.username or parsed.password or parsed.scheme+'://'+parsed.netloc not in allowed:
        raise RuntimeError('Only the original registered HTTPS origins are allowed')
    request={'url':url,'method':method,'headers':headers or {},'body':body,'allowedOrigins':allowed,
             'ca':str(root/('certs/http-trust.pem' if runtime.load(root).get('publicTlsHashes') else 'certs/ca.pem'))}
    if tls_generation.managed(root):
        from .public_runtime import effective_paths
        request['ca']=effective_paths(root)['httpTrust']
        selected=urlsplit(url)
        identity_origin=urlsplit(plan['issuer'])
        port=plan['ports']['identity'] if selected.netloc==identity_origin.netloc else plan['ports']['entry'] if selected.netloc==urlsplit(plan['origin']).netloc else plan['ports']['api']
        request.update(connectHost='127.0.0.1',connectPort=port)
    if client_certificate:
        request.update(certificate=str(root/'certs/service.crt'),privateKey=str(root/'certs/service.key'))
    result=runtime.run(['docker','exec','-i',plan['pod'],'node',http_helper(root)],canonical(request),timeout=25)
    return json.loads(result.stdout)


def _directory(root, plan):
    form=urlencode({'grant_type':'client_credentials','client_id':plan['directoryClient'],'client_secret':secret_file(root/'secrets/directory.txt')})
    token=http(root,plan['issuer']+'/protocol/openid-connect/token',method='POST',headers={'Content-Type':'application/x-www-form-urlencoded'},body=form)
    if token['status']!=200:raise RuntimeError('Original directory authentication unavailable')
    access=json.loads(token['body'])['access_token']
    base=plan['issuer'].replace('/realms/','/admin/realms/')
    response=http(root,base+'/users?first=0&max=100',headers={'Authorization':'Bearer '+access})
    if response['status']!=200:raise RuntimeError('Directory read unavailable')
    return json.loads(response['body'])


def verify(root: Path) -> dict:
    with journal.locked(root) as root:
        plan=journal._read(root,root/'identity/plan.json')
        users=_directory(root,plan)
        humans=[u for u in users if not u.get('serviceAccountClientId')]
        if len(humans)!=17 or {u.get('username') for u in humans}!=set(plan['subjects']):raise RuntimeError('Exact original HUMAN roster differs')
        pending=0
        for user in humans:
            if user.get('id')!=plan['subjects'][user['username']] or user.get('enabled') is not True or user.get('attributes')!=plan['marker']:
                raise RuntimeError('Original subject, owner marker or enabled state differs')
            actions=user.get('requiredActions',[])
            if any(action!='UPDATE_PASSWORD' for action in actions):raise RuntimeError('Unexpected account required action')
            pending+=int('UPDATE_PASSWORD' in actions)
        return {'status':'PASS','humanAccounts':17,'passwordUpdatesRequired':pending}


def bootstrap_output(data: bytes) -> dict:
    values=[]
    for line in data.decode('utf-8').splitlines():
        if line.startswith('{'):
            try:value=json.loads(line)
            except ValueError:raise RuntimeError('Original bootstrap JSON protocol malformed') from None
            if not isinstance(value,dict):raise RuntimeError('Original bootstrap JSON object required')
            values.append(value)
    if len(values)!=1:raise RuntimeError('Exactly one original bootstrap result required; retain private diagnostics')
    return values[0]


def _bootstrap_command(root, mode):
    plan=journal._read(root,root/'identity/plan.json')
    launch=journal._read(root,root/'identity/bootstrap-launch.json')
    args=['docker','exec',plan['pod'],'java','-Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand',
        '-cp',launch['jar'],'org.springframework.boot.loader.launch.PropertiesLauncher',mode,str(root/'identity/operator.json'),
        str(root/'identity/founder-identifier.txt' if mode=='candidate' else root/'identity/original-manifest.json')]
    if mode=='execute':args.append('--confirm-bootstrap')
    result=runtime.run(args,check=False,timeout=30)
    runtime.private_file(root/'identity'/('bootstrap-'+mode+'.stdout'),result.stdout)
    runtime.private_file(root/'identity'/('bootstrap-'+mode+'.stderr'),result.stderr)
    if result.returncode:raise RuntimeError('Original bootstrap failed or uncertain; retain manifest and key')
    return bootstrap_output(result.stdout)


def _bootstrap_absent(root):
    # An empty, fully migrated business identity/command/audit set proves no original
    # bootstrap committed. Any partial or other initialization remains closed.
    return database.sql(root,'SELECT (SELECT count(*) FROM identity.tenant)+(SELECT count(*) FROM execution.command_execution_slot)+(SELECT count(*) FROM audit.audit_entry)')=='0'


def bootstrap(root: Path, config: dict) -> dict:
    with journal.locked(root) as root:
        plan=journal._read(root,root/'identity/plan.json')
        if plan['configDigest']!=digest(config):raise RuntimeError('Bootstrap configuration changed')
        path=root/'identity/bootstrap.json'
        if path.exists():
            original=journal._read(root,path)
            manifest_path=root/'identity/original-manifest.json'
            if not manifest_path.is_file() or manifest_path.resolve()!=manifest_path.absolute() or sha(manifest_path)!=original['manifestDigest']:raise RuntimeError('Original bootstrap manifest changed')
            if original['state'] in {'EXECUTE_UNKNOWN','VERIFIED'}:
                try:result=_bootstrap_command(root,'verify')
                except RuntimeError:
                    if original['state']!='EXECUTE_UNKNOWN' or not _bootstrap_absent(root):raise
                    _bootstrap_command(root,'execute')
                    result=_bootstrap_command(root,'verify')
                if result['mode']!='VERIFIED_ORIGINAL':raise RuntimeError('Original bootstrap not confirmed')
                journal._write(root,path,dict(original,state='VERIFIED'))
                return result
        else:
            if (root/'identity/original-manifest.json').exists():raise RuntimeError('Partial original bootstrap manifest retained; no new candidate or key')
            selector=_bootstrap_command(root,'candidate')['providerUserSelector']
            manifest={'profile':'R1_IDENTITY_BOOTSTRAP_V1','commandId':str(uuid.uuid4()),'tenantCode':'HAIHUA',
                'tenantDisplayName':config['tenantName'],'rootCode':'ROOT','rootDisplayName':config['tenantName'],
                'identityProviderCode':'HAIHUA','issuer':plan['issuer'],'providerUserSelector':selector,
                'principalDisplayName':next(p['name'] for p in config['people'] if p['username']=='dingqiming'),
                'effectiveFrom':(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat().replace('+00:00','Z'),
                'operatorAssertion':'Approved isolated Linux Haihua initialization '+plan['operationId']}
            runtime.private_file(root/'identity/original-manifest.json',canonical(manifest))
            original={'state':'PREPARED','manifestDigest':digest(manifest)};journal._write(root,path,original)
        if sha(root/'identity/original-manifest.json')!=original['manifestDigest']:raise RuntimeError('Original bootstrap manifest changed')
        _bootstrap_command(root,'dry-run')
        journal._write(root,path,dict(original,state='EXECUTE_UNKNOWN'))
        _bootstrap_command(root,'execute')
        result=_bootstrap_command(root,'verify')
        if result['mode']!='VERIFIED_ORIGINAL':raise RuntimeError('Original bootstrap not confirmed')
        journal._write(root,path,dict(original,state='VERIFIED'))
        return result
