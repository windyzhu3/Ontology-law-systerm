"""Owned technical setup for offline bootstrap and an internal-only management API."""
import base64
from datetime import datetime,timezone
import hashlib
import hmac
import json
from pathlib import Path
import secrets
import ssl
import time
import uuid
from . import bundle,database,identity,journal,release,runtime
from .config import canonical,digest

SERVICE_CODES=('R1_PROJECTION_CONSUME','CONTACT_TASK_RECOVER','ROUTING_REVIEW_TASK_RECOVER','OPPORTUNITY_TASK_ACTIVATE','OPPORTUNITY_TASK_RECOVER','OPPORTUNITY_OWNER_EXCEPTION_DISCOVER','CONTRACT_TASK_RECOVER')
KEYS=('offline-key','subject-key','encryption-key','phone-key','email-key','source-key','actor-key','api-cursor-key','candidate-key','etag-key','admin-cursor-key','payment-key')


def _secrets(root):
    path=root/'assembly/keys.json'
    if not path.exists():
        if any((root/'secrets'/(name+'.txt')).exists() for name in KEYS):raise RuntimeError('Partial unregistered application keys are not adopted')
        value={name:base64.b64encode(secrets.token_bytes(32)).decode() for name in KEYS}
        value['trust-password']=secrets.token_urlsafe(24)
        journal._write(root,path,value)
    values=journal._read(root,path)
    for key,value in values.items():
        file=root/'secrets'/(key+'.txt')
        if file.exists() and file.read_text()!=value:raise RuntimeError('Original application key changed')
        if not file.exists():runtime.private_file(file,value.encode())
    return values


def certificate_der(path):
    text=Path(path).read_text(encoding='ascii').strip()
    if text.count('-----BEGIN CERTIFICATE-----')!=1 or text.count('-----END CERTIFICATE-----')!=1:
        raise RuntimeError('One explicit trust anchor certificate required')
    return ssl.PEM_cert_to_DER_cert(text)


def ensure_trust_anchor(root,plan,alias,certificate):
    expected=certificate_der(certificate)
    args=['docker','run','--rm','--label',runtime.LABEL+'='+plan['instanceId'],
          '--mount',f'type=bind,source={root},target={root}','--entrypoint','keytool',plan['runtimeImage']]
    store=['-alias',alias,'-keystore',root/'certs/identity-trust.p12','-storetype','PKCS12',
           '-storepass:file',root/'secrets/trust-password.txt']
    observed=runtime.run([*args,'-exportcert',*store],check=False)
    if observed.returncode:
        runtime.run([*args,'-importcert','-noprompt','-file',certificate,*store])
        observed=runtime.run([*args,'-exportcert',*store])
    if observed.stdout!=expected:raise RuntimeError('Original application trust anchor differs')


def _tls(root,plan):
    path=root/'assembly/tls.json'
    if path.exists():
        hashes=journal._read(root,path)
        if any(bundle.sha(root/name)!=value for name,value in hashes.items()):raise RuntimeError('Original application TLS changed')
        return
    resources=runtime.load(root)
    # Every output is under this registered runtime; original CA/keys are reused.
    script='''umask 077; cd /out
test -f certs/server.p12 || openssl pkcs12 -export -name ols_server -inkey certs/server.key -in certs/server.crt -certfile certs/ca.pem -out certs/server.p12 -passout file:secrets/trust-password.txt
if ! test -f certs/service.key; then openssl req -newkey rsa:3072 -nodes -keyout certs/service.key -out certs/service.csr -subj /CN=ols-linux-service >/dev/null 2>&1; fi
if ! test -f certs/service.crt; then printf 'extendedKeyUsage=clientAuth\n' > certs/service.extensions; openssl x509 -req -in certs/service.csr -CA certs/ca.pem -CAkey certs/ca.key -CAcreateserial -out certs/service.crt -days 365 -extfile certs/service.extensions >/dev/null 2>&1; fi
test -f certs/service.p12 || openssl pkcs12 -export -name ols_service -inkey certs/service.key -in certs/service.crt -certfile certs/ca.pem -out certs/service.p12 -passout file:secrets/trust-password.txt
test -f certs/service-public.pem || openssl x509 -in certs/service.crt -pubkey -noout -out certs/service-public.pem
chmod 600 certs/*
'''
    runtime.run(['docker','run','--rm','--label',runtime.LABEL+'='+plan['instanceId'],'--mount',f'type=bind,source={root},target=/out',resources['postgresImage'],'sh','-euc',script])
    trust=root/'certs/identity-trust.p12'
    ensure_trust_anchor(root,plan,'ols-ca',root/'certs/ca.pem')
    trust.chmod(0o600)
    if resources.get('publicTlsHashes'):
        ensure_trust_anchor(root,plan,'ols-public-ca',root/'certs/public-ca.pem')
    names=['certs/server.p12','certs/service.key','certs/service.crt','certs/service.p12','certs/service-public.pem','certs/identity-trust.p12']
    journal._write(root,path,{name:bundle.sha(root/name) for name in names})


def migration_complete(repo,history):
    versions=[r['version'] for r in history if r['version']]
    expected=database.expected_versions(repo)
    if versions!=expected[:len(versions)]:raise RuntimeError('Original initialization migration prefix differs')
    if history:database.verify_history(repo,history,versions[-1] if versions else '0')
    return versions==expected


def _schema(root,opid,schema_version='52-plus-2-r2-v22'):
    target={'52-plus-2-r2-v20':'1060','52-plus-2-r2-v22':'1080'}.get(schema_version)
    if target is None:raise RuntimeError('Only the frozen v20 foundation or reviewed v22 initialization is supported')
    path=root/'assembly/schema.json'
    if not path.exists():
        if database.observe(root)['history'] or database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_schema_migrator','law_api_login','law_worker_login')")!='0':raise RuntimeError('Existing unregistered application database is not adopted')
        journal._write(root,path,{'operationId':opid})
    if journal._read(root,path)['operationId']!=opid:raise RuntimeError('Original schema setup belongs to another operation')
    count=database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_schema_migrator','law_app_command','law_app_query','law_app_worker','law_audit_append')")
    if count=='0':database.roles(root,opid)
    elif count!='5' or database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_schema_migrator','law_app_command','law_app_query','law_app_worker','law_audit_append') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit")!='5':raise RuntimeError('Original migration role attributes differ')
    history=database.observe(root)['history']
    if not migration_complete(Path(runtime.load(root)['repo']),history):database.flyway(root,opid,'migrate',target)
    database.flyway(root,opid,'validate',target);database.verify_schema(root,target)
    count=database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_api_login','law_worker_login')")
    if count=='0':database.runtime_logins(root,opid)
    elif count!='2' or database.sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_api_login','law_worker_login') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolinherit")!='2':raise RuntimeError('Original application login attributes differ')


def bootstrap(root: Path,config: dict,descriptor: dict,directory: Path) -> dict:
    with journal.locked(root) as root:
        op=journal.current(root);plan=journal._read(root,root/'identity/plan.json')
        if op['kind']!='initialize' or op['configDigest']!=digest(config):raise RuntimeError('Original initialization configuration required')
        bundle.verify(descriptor,directory)
        path=root/'assembly/bundle.json'
        if path.exists() and journal._read(root,path)!=descriptor:raise RuntimeError('Original bootstrap release changed')
        if not path.exists():journal._write(root,path,descriptor)
        installed=release._install_bundle(root,directory,descriptor)
        _schema(root,op['operationId'],descriptor['schemaVersion']);values=_secrets(root);_tls(root,plan)
        gate_path=root/'assembly/initial-gate.json'
        if not gate_path.exists():
            before=database.observe(root)['gate']
            if before['operating_mode']!='BLOCKED' or before['active_release_digest']!='0'*64 or before['active_manifest_hash']!='0'*64:raise RuntimeError('First initialization requires its empty migrated gate')
            expected=dict(before,operating_mode='ACTIVE',active_release_digest=descriptor['files'][descriptor['jar']],active_manifest_hash=descriptor['manifestHash'],revision=before['revision']+1,changed_at=datetime.now(timezone.utc).isoformat(timespec='microseconds'))
            journal._write(root,gate_path,{'before':before,'expected':expected})
        gate=journal._read(root,gate_path);actual=database.observe(root)['gate']
        if release._same_gate(actual,gate['before']):release._cas_gate(root,actual,gate['expected'])
        elif not release._same_gate(actual,gate['expected']):raise RuntimeError('Original initialization gate conflict')
        file=lambda name:str(root/name)
        database_settings={'url':'jdbc:postgresql://'+runtime.load(root)['containers']['businessDb']+':5432/law_contract_runtime?sslmode=verify-full&sslrootcert='+file('certs/ca.pem'),'username':'law_api_login','passwordPath':file('secrets/api-db.txt'),'schemaVersion':descriptor['schemaVersion'],'releaseDigest':descriptor['files'][descriptor['jar']],'manifestHash':descriptor['manifestHash']}
        operator={'semanticBaseline':'MVP-2026-09-08.3','tenantId':plan['tenantId'],'tenantCode':'HAIHUA','identityProviderCode':'HAIHUA','issuer':plan['issuer'],'apiAudience':plan['audience'],'directoryClientId':plan['directoryClient'],'directorySecretPath':file('secrets/directory.txt'),'operatorAssertion':'Approved isolated Linux Haihua initialization '+plan['operationId'],'node':'LINUX_BOOTSTRAP','activeBootstrapKeyId':'linux-offline-v1','bootstrapKeyPaths':{'linux-offline-v1':file('secrets/offline-key.txt')},'subjectHmacPath':file('secrets/subject-key.txt'),'identityTrustStorePath':file('certs/identity-trust.p12'),'identityTrustStorePasswordPath':file('secrets/trust-password.txt'),'database':database_settings}
        operator_path=root/'identity/operator.json'
        if operator_path.exists() and json.loads(operator_path.read_text())!=operator:raise RuntimeError('Original bootstrap operator changed')
        runtime.private_file(operator_path,canonical(operator));runtime.private_file(root/'identity/founder-identifier.txt',b'dingqiming')
        journal._write(root,root/'identity/bootstrap-launch.json',{'jar':str(installed/descriptor['jar'])})
        result=identity.bootstrap(root,config)
        return dict(result,descriptorDigest=descriptor['descriptorDigest'])


def service_statement(value: dict,subject_digest: str, *, schema_version='52-plus-2-r2-v22') -> str:
    if schema_version not in {'52-plus-2-r2-v20','52-plus-2-r2-v22'}:raise RuntimeError('Unsupported SERVICE foundation')
    for key in ('tenantId','principalId','appointmentId','roleId'):uuid.UUID(value[key])
    if set(value['grants'])!=set(SERVICE_CODES) or len(subject_digest)!=64 or any(c not in '0123456789abcdef' for c in subject_digest):raise ValueError('Exact technical service inventory required')
    for id in value['grants'].values():uuid.UUID(id)
    t,p,a=value['tenantId'],value['principalId'],value['appointmentId']
    sql=f"BEGIN; INSERT INTO identity.appointment_role(tenant_id,appointment_role_id,role_code,display_name,state,created_at) VALUES ('{t}','{value['roleId']}','SERVICE','Linux infrastructure','ACTIVE',clock_timestamp()); INSERT INTO identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) VALUES ('{t}','{p}','SERVICE','LINUX_SERVICE',decode('{subject_digest}','hex'),'Linux infrastructure','ACTIVE',clock_timestamp()); INSERT INTO identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) SELECT '{t}','{a}','{p}',organization_unit_id,'SERVICE',clock_timestamp(),'ACTIVE',clock_timestamp() FROM identity.organization_unit WHERE tenant_id='{t}' AND unit_code='ROOT';"
    if schema_version=='52-plus-2-r2-v20':
        sql='BEGIN; INSERT INTO identity.principal'+sql.split(' INSERT INTO identity.principal',1)[1]
        sql=sql.replace("organization_unit_id,'SERVICE',clock_timestamp()","organization_unit_id,'CONTACT_OPERATOR',clock_timestamp()")
    for code,id in value['grants'].items():
        sql+=f" INSERT INTO identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) SELECT '{t}','{id}','{a}',ap.appointment_id,o.organization_unit_id,'{code}',clock_timestamp(),'ACTIVE',clock_timestamp() FROM identity.organization_unit o JOIN identity.appointment ap ON ap.tenant_id=o.tenant_id AND ap.organization_unit_id=o.organization_unit_id JOIN identity.principal pr ON pr.tenant_id=ap.tenant_id AND pr.principal_id=ap.principal_id WHERE o.tenant_id='{t}' AND o.unit_code='ROOT' AND ap.role_code='IDENTITY_ADMIN' AND pr.principal_kind='HUMAN';"
    return sql+' COMMIT;'


def service(root,plan):
    schema=database.observe(root)['gate']['schema_contract_version']
    role='CONTACT_OPERATOR' if schema=='52-plus-2-r2-v20' else 'SERVICE'
    path=root/'assembly/service.json'
    if not path.exists():
        if database.sql(root,"SELECT count(*) FROM identity.principal WHERE principal_kind='SERVICE'")!='0':raise RuntimeError('Existing SERVICE is not adopted')
        journal._write(root,path,{'tenantId':plan['tenantId'],'principalId':str(uuid.uuid4()),'appointmentId':str(uuid.uuid4()),'roleId':str(uuid.uuid4()),'grants':{code:str(uuid.uuid4()) for code in SERVICE_CODES}})
    value=journal._read(root,path)
    if 'roleId' not in value:
        if database.sql(root,"SELECT count(*) FROM identity.appointment_role WHERE role_code='SERVICE'")!='0' or database.sql(root,"SELECT count(*) FROM identity.principal WHERE principal_kind='SERVICE'")!='0':raise RuntimeError('Partial old SERVICE transaction cannot be adopted')
        value=dict(value,roleId=str(uuid.uuid4()));journal._write(root,path,value)
    count=database.sql(root,"SELECT count(*) FROM identity.principal WHERE principal_kind='SERVICE'")
    if count=='0':
        key=base64.b64decode(identity.secret_file(root/'secrets/subject-key.txt'))
        database.sql(root,service_statement(value,hmac.new(key,b'linux-infrastructure',hashlib.sha256).hexdigest(),schema_version=schema))
    elif count!='1':raise RuntimeError('SERVICE inventory differs')
    # Exact UUIDs and technical authority set, not matching by display name.
    actual=json.loads(database.sql(root,"SELECT json_build_object('principalId',p.principal_id,'appointmentId',a.appointment_id,'kind',p.principal_kind,'provider',p.identity_provider_code,'role',a.role_code,'codes',(SELECT json_agg(authority_code ORDER BY authority_code) FROM identity.authority_grant WHERE grantee_appointment_id=a.appointment_id)) FROM identity.principal p JOIN identity.appointment a ON a.tenant_id=p.tenant_id AND a.principal_id=p.principal_id WHERE p.principal_kind='SERVICE'"))
    if actual!={'principalId':value['principalId'],'appointmentId':value['appointmentId'],'kind':'SERVICE','provider':'LINUX_SERVICE','role':role,'codes':sorted(SERVICE_CODES)}:raise RuntimeError('Original SERVICE references or authority inventory differs')
    return value


def properties(root,descriptor,plan,service):
    values=_secrets(root);file=lambda name:str(root/name);dep=json.loads((root/'identity/operator.json').read_text())['database']
    props={'ols.runtime-role':'api','server.address':'0.0.0.0','server.port':str(plan['ports']['api']),'spring.main.banner-mode':'off','logging.level.root':'WARN','server.ssl.client-auth':'want','server.ssl.key-store':file('certs/server.p12'),'server.ssl.key-store-password':values['trust-password'],'server.ssl.key-store-type':'PKCS12','server.ssl.trust-store':file('certs/identity-trust.p12'),'server.ssl.trust-store-password':values['trust-password'],'server.ssl.trust-store-type':'PKCS12','server.ssl.enabled-protocols':'TLSv1.3','ols.api.semantic-baseline':'MVP-2026-09-08.3','ols.api.node':'LINUX_API','ols.api.cursor-key':values['api-cursor-key'],'ols.api.identity-trust-store-path':file('certs/identity-trust.p12'),'ols.api.identity-trust-store-password-path':file('secrets/trust-password.txt')}
    props.update({'ols.api.database.'+key:value for key,value in {'url':dep['url'],'username':'law_api_login','password':identity.secret_file(root/'secrets/api-db.txt'),'schema-version':descriptor['schemaVersion'],'release-digest':descriptor['files'][descriptor['jar']],'manifest-hash':descriptor['manifestHash']}.items()})
    human={'issuer':plan['issuer'],'audience':plan['audience'],'identity-provider-code':'HAIHUA','tenant-id':plan['tenantId'],'introspection-client-id':plan['audience'],'introspection-secret-path':file('secrets/introspection.txt'),'directory-client-id':plan['directoryClient'],'directory-secret-path':file('secrets/directory.txt')}
    registration={'issuer':'urn:ols:linux:service','audience':plan['audience'],'identity-provider-code':'LINUX_SERVICE','tenant-id':plan['tenantId'],'principal-id':service['principalId'],'appointment-id':service['appointmentId'],'principal-kind':'SERVICE','source-account-codes[0]':'LINUX_SERVICE_CLOSED'}
    certificate={'sha256':hashlib.sha256(__import__('ssl').PEM_cert_to_DER_cert((root/'certs/service.crt').read_text())).hexdigest(),**{key:registration[key] for key in ('identity-provider-code','tenant-id','principal-id','appointment-id')}}
    for prefix,fields in [('human-trusts[0]',human),('registrations[0]',registration),('certificates[0]',certificate),('trusts[0]',{'issuer':registration['issuer'],'audience':plan['audience'],'verification-key-path':file('certs/service-public.pem')})]:props.update({'ols.api.'+prefix+'.'+k:v for k,v in fields.items()})
    for k,v in {'encryption':'encryption-key','phone-hmac':'phone-key','email-hmac':'email-key','source-hmac':'source-key','credential-subject-hmac':'subject-key','actor-scope-hmac':'actor-key'}.items():props['ols.api.tenant-keys['+plan['tenantId']+'].'+k]=values[v]
    for k,v in {'active-candidate-key-id':'linux-online-v1','candidate-keys[linux-online-v1]':values['candidate-key'],'etag-key':values['etag-key'],'cursor-key':values['admin-cursor-key']}.items():props['ols.api.identity-administration.'+k]=v
    for k,v in {'assignment-mode':'MANUAL','routing-organization-root-codes[0]':'ROOT','routing-supervisor-root-code':'ROOT','source-intake-root-code':'ROOT','business-timezone':'Asia/Shanghai'}.items():props['ols.api.sources[LINUX_SERVICE_CLOSED].'+k]=v
    # Enable the configured tenant's fail-closed guard using the real SERVICE UUID:
    # no HUMAN matches it. It is replaced with the 17 exact HUMAN bindings in L08.
    props.update({'ols.api.human-intake-bindings[0].tenant-id':plan['tenantId'],'ols.api.human-intake-bindings[0].principal-id':service['principalId'],'ols.api.human-intake-bindings[0].source-account-code':'LINUX_SERVICE_CLOSED'})
    return props


def start_admin(root,descriptor):
    with journal.locked(root) as root:
        plan=journal._read(root,root/'identity/plan.json');svc=service(root,plan)
        props=properties(root,descriptor,plan,svc);data=('\n'.join(k+'='+v for k,v in props.items())+'\n').encode('ascii','backslashreplace')
        file=root/'config/initial-admin.properties'
        if file.exists() and file.read_bytes()!=data:raise RuntimeError('Original management configuration changed')
        runtime.private_file(file,data)
        installed=journal._read(root,root/'installed-candidate.json');jar=Path(installed['directory'])/descriptor['jar']
        resources=runtime.load(root);name=resources['name']+'-api-admin';resources['containers']['api']=name
        if name not in resources['writers']:resources['writers'].append(name)
        runtime.save(root,resources)
        sealed=digest({'descriptor':descriptor['descriptorDigest'],'config':bundle.sha(file),'pod':plan['pod'],'image':plan['runtimeImage']})
        args=['docker','run','-d','--name',name,'--label',runtime.LABEL+'='+plan['instanceId'],'--label','ols.operation='+plan['operationId'],'--label','ols.launch='+sealed,'--network','container:'+plan['pod'],'--memory','1536m','--cpus','2','--log-driver','local','--log-opt','max-size=10m','--mount',f'type=bind,source={root},target={root},readonly',plan['runtimeImage'],'-Xmx768m','-jar',str(jar),'--spring.config.additional-location=file:'+str(file)]
        launch={'descriptorDigest':descriptor['descriptorDigest'],'containers':[{'name':name,'role':'api','digest':sealed,'args':args}]}
        journal._write(root,root/'launch.json',launch);runtime.start_internal(root,descriptor)
        deadline=time.monotonic()+120
        while True:
            if not runtime.owned(root,'container',name)['State']['Running']:raise RuntimeError('Registered management API startup failed; retain original diagnostics')
            try:
                if service_ready(root,plan):return {'status':'PASS','businessIngress':'CLOSED'}
            except (RuntimeError,ValueError,KeyError):pass
            if time.monotonic()>deadline:raise RuntimeError('Management API readiness unknown; ingress stays closed')
            time.sleep(.5)


def service_ready(root,plan):
    return identity.http(root,plan['apiOrigin']+'/internal/v1/projections/r1/readiness',client_certificate=True)['status']==204
