"""Bounded local synthetic login operations. No credential values are printed."""
import base64
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tarfile
import time
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / '.superpowers/sdd/2026-09-08-task9-real-user-access-plan/local-login-runtime'
TOOLS = Path('C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb')
JAVA = TOOLS / 'jdk-25.0.4.1+1/bin/java.exe'
KEYTOOL = JAVA.with_name('keytool.exe')
PREFIX = 'ontology-law-local-login'
ISSUER = 'https://localhost:19443/realms/local-r1'
ORIGIN = 'https://localhost:19444'
JAR = ROOT / 'backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar'
SECRET_NAMES = ('identity-db', 'business-db', 'migrator', 'api-db', 'worker-db',
                'introspection', 'directory', 'founder-password', 'unmapped-password',
                'trust-password', 'offline-key', 'subject-key', 'actor-key',
                'encryption-key', 'phone-key', 'email-key', 'source-key',
                'api-cursor-key', 'candidate-key', 'etag-key', 'admin-cursor-key')


def secret_bundle(runtime):
    folder = runtime / 'secrets'
    paths = [folder / (name + '.txt') for name in SECRET_NAMES]
    if not folder.is_dir():
        raise RuntimeError('missing secret bundle; preserve state and investigate')
    if not all(path.is_file() and path.stat().st_size > 0 for path in paths):
        raise RuntimeError('partial secret bundle; preserve state and investigate')
    return {name: path.read_text(encoding='utf-8') for name, path in zip(SECRET_NAMES, paths)}


def create_secret_bundle(runtime):
    # Only the certificate provisioning directory may precede first preparation.
    # Any other surviving artifact means missing keys are partial existing state.
    if any(entry.name != 'certs' or not entry.is_dir() for entry in runtime.iterdir()):
        raise RuntimeError('partial initialized runtime; refuse replacement secrets')
    folder = runtime / 'secrets'
    folder.mkdir()
    result = {name: base64.b64encode(secrets.token_bytes(32)).decode() for name in SECRET_NAMES}
    for name in SECRET_NAMES:
        (folder / (name + '.txt')).write_text(result[name], encoding='utf-8')
    return result


def realm(values):
    template = (ROOT / 'deploy/identity/realm-template.json').read_text(encoding='utf-8')
    replacements = {'IDENTITY_REALM': 'local-r1', 'SPA_CLIENT_ID': 'local-r1-spa',
                    'SPA_REDIRECT_URI': ORIGIN + '/auth/callback', 'SPA_ORIGIN': ORIGIN,
                    'SPA_LOGOUT_REDIRECT_URI': ORIGIN + '/login', 'API_AUDIENCE': 'local-r1-api',
                    'DIRECTORY_CLIENT_ID': 'local-r1-directory'}
    for name, value in replacements.items():
        template = template.replace('${' + name + '}', value)
    if '${' in template:
        raise RuntimeError('unresolved realm placeholder')
    result = json.loads(template)
    result['clients'][1]['secret'] = values['introspection']
    result['clients'][2]['secret'] = values['directory']
    result['users'] = [{'username': 'synthetic-' + kind, 'enabled': True,
                        'emailVerified': True, 'firstName': 'Synthetic', 'lastName': kind,
                        'email': 'synthetic-' + kind + '@example.invalid',
                        'credentials': [{'type': 'password', 'value': values[kind + '-password'], 'temporary': False}]}
                       for kind in ('founder', 'unmapped')]
    result['users'].append({'username': 'service-account-local-r1-directory', 'enabled': True,
                            'serviceAccountClientId': 'local-r1-directory',
                            'clientRoles': {'realm-management': ['query-users', 'view-users']}})
    return result


def save(name, value):
    path = RUNTIME / name
    path.write_text(json.dumps(value, indent=2) if not isinstance(value, str) else value, encoding='utf-8')
    return path


def run(args, label, input_bytes=None):
    result = subprocess.run([str(x) for x in args], input=input_bytes, capture_output=True, cwd=ROOT)
    (RUNTIME / (label + '.stdout')).write_bytes(result.stdout)
    (RUNTIME / (label + '.stderr')).write_bytes(result.stderr)
    print(label + ': exit ' + str(result.returncode))
    if result.returncode:
        raise RuntimeError(label + ' failed; inspect protected diagnostic files')
    return result.stdout


def docker(*args, label):
    return run(['docker', *args], label)


def copy_owned(container, destination, files, uid):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as archive:
        for name, data in files.items():
            entry = tarfile.TarInfo(name)
            entry.uid, entry.gid, entry.mode, entry.size = uid, uid if uid == 999 else 0, 0o600, len(data)
            archive.addfile(entry, io.BytesIO(data))
    run(['docker', 'cp', '-', container + ':' + destination], 'copy-' + container, buffer.getvalue())


def initialize():
    if not RUNTIME.is_dir():
        raise RuntimeError('protected runtime must be provisioned first')
    for name in ('ca.pem', 'server.crt', 'server.key', 'server.pfx', 'pfx-password.txt', 'database.crt', 'database.key'):
        if not (RUNTIME / 'certs' / name).is_file():
            raise RuntimeError('required certificate file missing: ' + name)
    values = secret_bundle(RUNTIME) if (RUNTIME / 'secrets').exists() else create_secret_bundle(RUNTIME)
    save('local-r1-realm.json', realm(values))
    save('browser-credentials.json', {kind: {'username': 'synthetic-' + kind, 'password': values[kind + '-password']}
                                      for kind in ('founder', 'unmapped')})
    if not (RUNTIME / 'deployment.json').exists():
        save('deployment.json', {'tenantId': str(uuid.uuid4()), 'tenantCode': 'LOCAL_R1',
             'releaseDigest': hashlib.sha256(JAR.read_bytes()).hexdigest(),
             'manifestHash': hashlib.sha256((ROOT / 'database/schema-contract-52-plus-2/runtime/toolchain.lock.json').read_bytes()).hexdigest()})
    if not (RUNTIME / 'identity-trust.p12').exists():
        run([KEYTOOL, '-importcert', '-noprompt', '-alias', 'local-login-ca', '-file', RUNTIME / 'certs/ca.cer',
             '-keystore', RUNTIME / 'identity-trust.p12', '-storetype', 'PKCS12',
             '-storepass:file', RUNTIME / 'secrets/trust-password.txt'], 'private-truststore')
    print('prepared protected runtime; credentials at ' + str(RUNTIME / 'browser-credentials.json'))


def require_protected_runtime():
    # Windows current-user and SYSTEM only, including inherited write/read permissions.
    quoted = RUNTIME.as_posix().replace("'", "''")
    script = "$ErrorActionPreference='Stop'; $base=Get-Item -LiteralPath '" + quoted + "'; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; "
    script += "if(-not (Get-Acl -LiteralPath $base.FullName).AreAccessRulesProtected){exit 2}; "
    script += "function Check-Tree($item){if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){exit 4}; $acl=Get-Acl -LiteralPath $item.FullName; foreach($rule in $acl.Access){$id=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value; if($id -notin @($sid,'S-1-5-18') -or $rule.AccessControlType -ne 'Allow'){exit 3}}; if($item.PSIsContainer){foreach($child in Get-ChildItem -LiteralPath $item.FullName -Force){Check-Tree $child}}}; Check-Tree $base; exit 0"
    result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-Command', script], capture_output=True)
    ignored = subprocess.run(['git', 'check-ignore', '--quiet', str(RUNTIME)], cwd=ROOT, capture_output=True)
    if result.returncode or ignored.returncode:
        print('runtime protection check exit ACL=' + str(result.returncode) + ', ignore=' + str(ignored.returncode))
        raise RuntimeError('runtime must be ignored and ACL protected for current user plus SYSTEM only')


def infrastructure():
    values = secret_bundle(RUNTIME)
    lock = json.loads((ROOT / 'deploy/identity/identity-toolchain.lock.json').read_text())
    pg = lock['identityDatabase']['image'] + '@' + lock['identityDatabase']['digest']
    kc = lock['keycloak']['image'] + '@' + lock['keycloak']['platformDigest']
    for port in (19443, 19444, 19445, 19446):
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                raise RuntimeError('fixed port occupied: ' + str(port)) from None
    # Fail on existing containers. Resume is an explicit operation; never replace volumes.
    existing = subprocess.run(['docker', 'ps', '-a', '--filter', 'name=' + PREFIX, '--format', '{{.Names}}'], capture_output=True, text=True)
    if existing.stdout.strip():
        raise RuntimeError('existing local resources; use resume or inspect, not reinitialize')
    docker('network', 'create', '--internal', PREFIX + '-identity', label='network-identity')
    docker('network', 'create', '--internal', PREFIX + '-business', label='network-business')
    for kind in ('identity', 'business'):
        docker('network', 'create', '--opt', 'com.docker.network.bridge.enable_icc=false',
               PREFIX + '-' + kind + '-ingress', label='network-' + kind + '-ingress')
    for kind, db in (('identity', 'identity_only'), ('business', 'law_contract_runtime')):
        name = PREFIX + '-' + kind + '-db'
        docker('volume', 'create', name + '-data', label='volume-' + kind)
        options = ['create', '--name', name, '--network', PREFIX + '-' + kind, '--network-alias', kind + '-db',
                   '--memory', '768m', '--cpus', '1', '-e', 'POSTGRES_DB=' + db,
                   '-e', 'POSTGRES_USER=' + ('identity_only' if kind == 'identity' else 'postgres'),
                   '-e', 'POSTGRES_PASSWORD_FILE=/tmp/local-password', '-v', name + '-data:/var/lib/postgresql']
        if kind == 'business':
            options += ['-p', '127.0.0.1:19446:5432']
        docker(*options, pg, 'postgres', '-c', 'ssl=on', '-c', 'ssl_cert_file=/tmp/database.crt',
               '-c', 'ssl_key_file=/tmp/database.key', '-c', 'log_statement=none', '-c', 'log_min_error_statement=panic', label='create-' + kind)
        copy_owned(name, '/tmp', {'local-password': values[kind + '-db'].encode(),
            'database.crt': (RUNTIME / 'certs/database.crt').read_bytes(), 'database.key': (RUNTIME / 'certs/database.key').read_bytes()}, 999)
        docker('start', name, label='start-' + kind)
        if kind == 'business':
            docker('network', 'connect', PREFIX + '-business-ingress', name, label='business-loopback-ingress')
        for attempt in range(60):
            ready = subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'identity_only' if kind == 'identity' else 'postgres'], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError(kind + ' database not ready')
    start_keycloak()


def start_keycloak():
    values = secret_bundle(RUNTIME)
    lock = json.loads((ROOT / 'deploy/identity/identity-toolchain.lock.json').read_text())
    kc = lock['keycloak']['image'] + '@' + lock['keycloak']['platformDigest']
    name = PREFIX + '-keycloak'
    env = {'KC_DB': 'postgres', 'KC_DB_URL': 'jdbc:postgresql://identity-db:5432/identity_only?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/ca.pem',
           'KC_DB_USERNAME': 'identity_only', 'KC_HOSTNAME': 'https://localhost:19443', 'KC_HOSTNAME_STRICT': 'true',
           'KC_HTTP_ENABLED': 'false', 'KC_HTTPS_CERTIFICATE_FILE': '/opt/keycloak/conf/server.crt',
           'KC_HTTPS_CERTIFICATE_KEY_FILE': '/opt/keycloak/conf/server.key', 'KC_FEATURES_DISABLED': 'impersonation,parameterized-scopes',
           'KC_HTTP_ACCESS_LOG_ENABLED': 'false'}
    options = ['create', '--name', name, '--network', PREFIX + '-identity', '--memory', '2g', '--cpus', '2', '-p', '127.0.0.1:19443:8443']
    for key, value in env.items():
        options += ['-e', key + '=' + value]
    docker(*options, '--entrypoint', '/bin/bash', kc, '-ec',
           'export KC_DB_PASSWORD="$(</opt/keycloak/conf/db-password)"; exec /opt/keycloak/bin/kc.sh start --import-realm', label='create-keycloak')
    copy_owned(name, '/opt/keycloak/conf', {'db-password': values['identity-db'].encode(), **{
        f: (RUNTIME / 'certs' / f).read_bytes() for f in ('ca.pem', 'server.crt', 'server.key')}}, 1000)
    # Parent import directory is made in the stopped container using a tar directory entry.
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w') as archive:
        entry = tarfile.TarInfo('import'); entry.type = tarfile.DIRTYPE; entry.uid = 1000; entry.mode = 0o700
        archive.addfile(entry)
    run(['docker', 'cp', '-', name + ':/opt/keycloak/data'], 'mkdir-import', data.getvalue())
    copy_owned(name, '/opt/keycloak/data/import', {'local-r1-realm.json': (RUNTIME / 'local-r1-realm.json').read_bytes()}, 1000)
    docker('start', name, label='start-keycloak')
    docker('network', 'connect', PREFIX + '-identity-ingress', name, label='identity-loopback-ingress')


def health():
    context = ssl.create_default_context(cafile=str(RUNTIME / 'certs/ca.pem'))
    with urllib.request.urlopen(ISSUER + '/.well-known/openid-configuration', context=context, timeout=5) as response:
        body = json.load(response)
        if body['issuer'] != ISSUER:
            raise RuntimeError('issuer mismatch')
        print('Keycloak discovery: 200; exact issuer and verified TLS')


def sql(statement, label):
    return run(['docker', 'exec', '-i', PREFIX + '-business-db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                '-U', 'postgres', '-d', 'law_contract_runtime', '-At'], label, statement.encode())


def migrate():
    values = secret_bundle(RUNTIME)
    exists = sql("select count(*) from pg_roles where rolname='law_schema_migrator';", 'migrator-exists').decode().strip()
    if exists != '0':
        raise RuntimeError('business initialization already began; inspect and preserve state')
    statements = "CREATE ROLE law_schema_migrator LOGIN NOINHERIT PASSWORD '" + values['migrator'] + "';\nALTER DATABASE law_contract_runtime OWNER TO law_schema_migrator;\n"
    for role in ('law_app_command', 'law_app_query', 'law_audit_append', 'law_app_worker'):
        statements += 'CREATE ROLE ' + role + ' NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;\n'
    sql(statements, 'database-roles')
    properties = {'flyway.url': 'jdbc:postgresql://business-db:5432/law_contract_runtime?sslmode=verify-full&sslrootcert=/flyway/local-ca.pem',
        'flyway.user': 'law_schema_migrator', 'flyway.password': values['migrator'], 'flyway.defaultSchema': 'platform_meta',
        'flyway.schemas': 'identity,audit,responsibility,execution,external_action,evidence,party,lead,opportunity,conflict,contract,transfer,platform_meta',
        'flyway.locations': 'filesystem:/flyway/sql', 'flyway.cleanDisabled': 'true', 'flyway.baselineOnMigrate': 'false', 'flyway.validateMigrationNaming': 'true',
        **{'flyway.placeholders.' + key: value for key, value in {'app_command_role': 'law_app_command', 'app_query_role': 'law_app_query', 'audit_append_role': 'law_audit_append', 'app_worker_role': 'law_app_worker'}.items()}}
    conf = save('flyway.conf', '\n'.join(key + '=' + value for key, value in properties.items()) + '\n')
    lock = json.loads((ROOT / 'database/schema-contract-52-plus-2/runtime/toolchain.lock.json').read_text())
    image = next(item['image'] + '@' + item['digest'] for item in lock['images'] if item['image'] == 'redgate/flyway')
    docker('run', '--rm', '--name', PREFIX + '-flyway', '--memory', '768m', '--network', PREFIX + '-business',
        '-v', str(conf) + ':/flyway/conf/local.conf:ro', '-v', str(RUNTIME / 'certs/ca.pem') + ':/flyway/local-ca.pem:ro',
        '-v', str(ROOT / 'database/schema-contract-52-plus-2/generated/db/migration') + ':/flyway/sql:ro',
        image, '-configFiles=/flyway/conf/local.conf', 'migrate', 'validate', label='flyway-migrate-validate')
    statements = (ROOT / 'backend/src/test/resources/db/bootstrap-runtime-logins.sql').read_text()
    statements += "\nALTER ROLE law_api_login PASSWORD '" + values['api-db'] + "';\nALTER ROLE law_worker_login PASSWORD '" + values['worker-db'] + "';\n"
    deployment = json.loads((RUNTIME / 'deployment.json').read_text())
    statements += "UPDATE platform_meta.deployment_state SET operating_mode='ACTIVE', active_release_digest=decode('" + deployment['releaseDigest'] + "','hex'), active_manifest_hash=decode('" + deployment['manifestHash'] + "','hex'), revision=revision+1, changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY';"
    sql(statements, 'database-runtime-logins-and-release')


def bootstrap_config():
    if (RUNTIME / 'current-release.json').exists():
        raise RuntimeError('original operator is frozen; use bootstrap-verify-current-release')
    deployment = json.loads((RUNTIME / 'deployment.json').read_text())
    file = lambda name: str((RUNTIME / name).resolve())
    save('operator.json', {'semanticBaseline': 'MVP-2026-09-08.3', 'tenantId': deployment['tenantId'],
        'tenantCode': 'LOCAL_R1', 'identityProviderCode': 'LOCAL_R1', 'issuer': ISSUER,
        'apiAudience': 'local-r1-api', 'directoryClientId': 'local-r1-directory',
        'directorySecretPath': file('secrets/directory.txt'), 'operatorAssertion': 'User approved isolated synthetic local login',
        'node': 'LOCAL_LOGIN_OFFLINE', 'activeBootstrapKeyId': 'local-offline-v1',
        'bootstrapKeyPaths': {'local-offline-v1': file('secrets/offline-key.txt')}, 'subjectHmacPath': file('secrets/subject-key.txt'),
        'identityTrustStorePath': file('identity-trust.p12'), 'identityTrustStorePasswordPath': file('secrets/trust-password.txt'),
        'database': {'url': 'jdbc:postgresql://localhost:19446/law_contract_runtime?sslmode=verify-full&sslrootcert=' + file('certs/ca.pem').replace('\\', '/'),
            'username': 'law_api_login', 'passwordPath': file('secrets/api-db.txt'), 'schemaVersion': '52-plus-2-v1.2',
            'releaseDigest': deployment['releaseDigest'], 'manifestHash': deployment['manifestHash']}})
    save('founder-identifier.txt', 'synthetic-founder')


def bootstrap(mode):
    import datetime
    bootstrap_config()
    command = [JAVA, '-Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand', '-cp', JAR,
               'org.springframework.boot.loader.launch.PropertiesLauncher']
    if mode == 'dry-run':
        if (RUNTIME / 'original-manifest.json').exists():
            raise RuntimeError('original manifest exists; preserve and verify original operation')
        candidate = run([*command, 'candidate', RUNTIME / 'operator.json', RUNTIME / 'founder-identifier.txt'], 'bootstrap-candidate')
        save('original-manifest.json', {'profile': 'R1_IDENTITY_BOOTSTRAP_V1', 'commandId': str(uuid.uuid4()),
            'tenantCode': 'LOCAL_R1', 'tenantDisplayName': 'Synthetic local login tenant', 'rootCode': 'ROOT',
            'rootDisplayName': 'Synthetic local root', 'identityProviderCode': 'LOCAL_R1', 'issuer': ISSUER,
            'providerUserSelector': json.loads(candidate)['providerUserSelector'], 'principalDisplayName': 'Synthetic local founder',
            'effectiveFrom': (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat().replace('+00:00', 'Z'),
            'operatorAssertion': 'User approved isolated synthetic local login'})
    args = [*command, mode, RUNTIME / 'operator.json', RUNTIME / 'original-manifest.json']
    if mode == 'execute':
        args.append('--confirm-bootstrap')
    result = run(args, 'bootstrap-' + mode)
    if mode == 'dry-run':
        print(result.decode())  # This entrypoint's preview deliberately omits selector/subject/secret.


def service_fixture():
    deployment = json.loads((RUNTIME / 'deployment.json').read_text())
    tenant = deployment['tenantId']
    if (RUNTIME / 'service-fixture.json').exists():
        raise RuntimeError('service fixture already prepared; inspect original state')
    # This is the reviewed isolated SERVICE-only infrastructure transaction, not HUMAN initialization.
    fixture = {'tenantId': tenant, 'principalId': str(uuid.uuid4()), 'appointmentId': str(uuid.uuid4())}
    save('service-fixture.json', fixture)
    values = secret_bundle(RUNTIME)
    subject = hmac.new(base64.b64decode(values['subject-key']), b'local-infrastructure-service', hashlib.sha256).hexdigest()
    statement = "BEGIN; DO $$ BEGIN IF (SELECT count(*) FROM identity.tenant WHERE tenant_id='" + tenant + "' AND tenant_code='LOCAL_R1' AND state='ACTIVE')<>1 OR (SELECT count(*) FROM identity.organization_unit WHERE tenant_id='" + tenant + "' AND unit_code='ROOT' AND state='ACTIVE' AND parent_organization_unit_id IS NULL)<>1 THEN RAISE EXCEPTION 'Local bootstrap prerequisite missing'; END IF; END $$;\n"
    statement += "INSERT INTO identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) VALUES ('" + tenant + "','" + fixture['principalId'] + "','SERVICE','LOCAL_SERVICE',decode('" + subject + "','hex'),'Synthetic local infrastructure','ACTIVE',clock_timestamp());\n"
    statement += "INSERT INTO identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) SELECT '" + tenant + "','" + fixture['appointmentId'] + "','" + fixture['principalId'] + "',organization_unit_id,'SERVICE',clock_timestamp(),'ACTIVE',clock_timestamp() FROM identity.organization_unit WHERE tenant_id='" + tenant + "' AND unit_code='ROOT'; COMMIT;"
    sql(statement, 'service-infrastructure-only')
    run([KEYTOOL, '-genkeypair', '-alias', 'local-service', '-keyalg', 'RSA', '-keysize', '3072',
         '-dname', 'CN=local-service', '-validity', '7', '-ext', 'EKU=clientAuth', '-keystore', RUNTIME / 'service.p12',
         '-storetype', 'PKCS12', '-storepass:file', RUNTIME / 'secrets/trust-password.txt'], 'service-certificate')
    run([KEYTOOL, '-exportcert', '-rfc', '-alias', 'local-service', '-keystore', RUNTIME / 'service.p12',
         '-storepass:file', RUNTIME / 'secrets/trust-password.txt', '-file', RUNTIME / 'service.crt'], 'service-public-certificate')
    run([KEYTOOL, '-importcert', '-noprompt', '-alias', 'local-service', '-file', RUNTIME / 'service.crt',
         '-keystore', RUNTIME / 'server-client-trust.p12', '-storetype', 'PKCS12',
         '-storepass:file', RUNTIME / 'secrets/trust-password.txt'], 'server-client-truststore')
    node = TOOLS / 'node-v24.20.0-win-x64/node.exe'
    pem = run([node, '-e', "const fs=require('fs'),{X509Certificate}=require('crypto');process.stdout.write(new X509Certificate(fs.readFileSync(process.argv[1])).publicKey.export({type:'spki',format:'pem'}));", RUNTIME / 'service.crt'], 'service-public-key')
    save('service-public.pem', pem.decode())


def application_config():
    if (RUNTIME / 'current-release.json').exists():
        raise RuntimeError('controlled release configuration is immutable; use stage-release')
    deployment = json.loads((RUNTIME / 'deployment.json').read_text())
    fixture = json.loads((RUNTIME / 'service-fixture.json').read_text())
    values = secret_bundle(RUNTIME)
    file = lambda name: str((RUNTIME / name).resolve()).replace('\\', '/')
    properties = {'ols.runtime-role': 'api', 'server.address': '127.0.0.1', 'server.port': '19445',
        'spring.main.banner-mode': 'off', 'logging.level.root': 'WARN', 'server.ssl.client-auth': 'want',
        'server.ssl.key-store': file('certs/server.pfx'), 'server.ssl.key-store-password': (RUNTIME / 'certs/pfx-password.txt').read_text().strip(),
        'server.ssl.key-store-type': 'PKCS12', 'server.ssl.trust-store': file('server-client-trust.p12'),
        'server.ssl.trust-store-password': values['trust-password'], 'server.ssl.trust-store-type': 'PKCS12',
        'ols.api.semantic-baseline': 'MVP-2026-09-08.3', 'ols.api.node': 'LOCAL_LOGIN_API',
        'ols.api.cursor-key': values['api-cursor-key'], 'ols.api.database.url': 'jdbc:postgresql://localhost:19446/law_contract_runtime?sslmode=verify-full&sslrootcert=' + file('certs/ca.pem'),
        'ols.api.database.username': 'law_api_login', 'ols.api.database.password': values['api-db'],
        'ols.api.database.schema-version': '52-plus-2-v1.2', 'ols.api.database.release-digest': deployment['releaseDigest'],
        'ols.api.database.manifest-hash': deployment['manifestHash'],
        'ols.api.identity-trust-store-path': file('identity-trust.p12'), 'ols.api.identity-trust-store-password-path': file('secrets/trust-password.txt'),
        'ols.api.trusts[0].issuer': 'urn:local-login:service', 'ols.api.trusts[0].audience': 'local-r1-api',
        'ols.api.trusts[0].verification-key-path': file('service-public.pem')}
    human = {'issuer': ISSUER, 'audience': 'local-r1-api', 'identity-provider-code': 'LOCAL_R1', 'tenant-id': deployment['tenantId'],
             'introspection-client-id': 'local-r1-api', 'introspection-secret-path': file('secrets/introspection.txt'),
             'directory-client-id': 'local-r1-directory', 'directory-secret-path': file('secrets/directory.txt')}
    registration = {'issuer': 'urn:local-login:service', 'audience': 'local-r1-api', 'identity-provider-code': 'LOCAL_SERVICE',
                    'tenant-id': deployment['tenantId'], 'principal-id': fixture['principalId'], 'appointment-id': fixture['appointmentId'],
                    'principal-kind': 'SERVICE', 'source-account-codes[0]': 'LOCAL_SYNTHETIC'}
    certificate = {'sha256': hashlib.sha256(ssl.PEM_cert_to_DER_cert((RUNTIME / 'service.crt').read_text())).hexdigest(),
                   **{key: registration[key] for key in ('identity-provider-code', 'tenant-id', 'principal-id', 'appointment-id')}}
    for prefix, fields in [('human-trusts[0]', human), ('registrations[0]', registration), ('certificates[0]', certificate)]:
        properties.update({'ols.api.' + prefix + '.' + key: value for key, value in fields.items()})
    keys = {'encryption': 'encryption-key', 'phone-hmac': 'phone-key', 'email-hmac': 'email-key', 'source-hmac': 'source-key',
            'credential-subject-hmac': 'subject-key', 'actor-scope-hmac': 'actor-key'}
    properties.update({'ols.api.tenant-keys[' + deployment['tenantId'] + '].' + key: values[value] for key, value in keys.items()})
    source = {'assignment-mode': 'MANUAL', 'routing-organization-root-codes[0]': 'ROOT', 'routing-supervisor-root-code': 'ROOT',
              'source-intake-root-code': 'ROOT', 'business-timezone': 'Asia/Shanghai'}
    properties.update({'ols.api.sources[LOCAL_SYNTHETIC].' + key: value for key, value in source.items()})
    admin = {'active-candidate-key-id': 'local-online-v1', 'candidate-keys[local-online-v1]': values['candidate-key'],
             'etag-key': values['etag-key'], 'cursor-key': values['admin-cursor-key']}
    properties.update({'ols.api.identity-administration.' + key: value for key, value in admin.items()})
    save('application.properties', '\n'.join(key + '=' + value for key, value in properties.items()) + '\n')
    print('production API configuration prepared in protected file')


def start_apps():
    if not (RUNTIME / 'current-release.json').exists():
        deployment = json.loads((RUNTIME / 'deployment.json').read_text())
        if hashlib.sha256(JAR.read_bytes()).hexdigest() != deployment['releaseDigest']:
            raise RuntimeError('Jar changed since local release activation; controlled redeployment required')
        raise RuntimeError('snapshot-release required before starting saved local artifacts')
    import local_release
    boundary = local_release.RuntimeBoundary(sys.modules[__name__])
    release = local_release.LocalRelease(ROOT, RUNTIME, boundary)
    paths = release.paths()
    boundary.stopped()
    for port in (19444, 19445):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', port))
    processes = local_release.read_json(RUNTIME / 'processes.json')
    commands = local_release.app_commands(sys.modules[__name__], paths['jar'].parent)
    # A pending start blocks a second start if the process result was lost.
    marker = RUNTIME / 'apps-start.pending'
    with marker.open('x', encoding='utf-8') as stream:
        stream.write('inspect saved process registry before explicitly clearing this marker')
    for name, args in commands.items():
        with (RUNTIME / (name + '.stdout')).open('wb') as out, (RUNTIME / (name + '.stderr')).open('wb') as errors:
            process = subprocess.Popen([str(arg) for arg in args], cwd=ROOT, stdout=out, stderr=errors,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
            # Record the PID immediately; a partial registry fails closed.
            processes[name] = {'pid': process.pid, 'executable': args[0], 'args': args[1:]}
            local_release.atomic(RUNTIME / 'processes.json', processes)
            actual = boundary.process(process.pid)
            if actual is None:
                raise RuntimeError('owned process exited during startup')
            local_release.owned_process(processes[name], actual)
            processes[name]['created'] = actual['created']
            local_release.atomic(RUNTIME / 'processes.json', processes)
    marker.unlink()
    print('API and SPA processes started; readiness must be checked separately')
    if 'worker' in processes:
        worker_operation('worker-prepare')
        worker_operation('worker-start')


def protocol_check(kind='founder'):
    import http.cookiejar
    import html
    import re
    import urllib.parse
    import urllib.error
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    context = ssl.create_default_context(cafile=str(RUNTIME / 'certs/ca.pem'))
    client = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())
    credentials = json.loads((RUNTIME / 'browser-credentials.json').read_text())[kind]
    verifier = secrets.token_urlsafe(48)
    state, nonce = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    redirect = ORIGIN + '/auth/callback'
    query = urllib.parse.urlencode({'client_id': 'local-r1-spa', 'redirect_uri': redirect, 'response_type': 'code',
        'scope': 'openid', 'state': state, 'nonce': nonce, 'code_challenge': challenge, 'code_challenge_method': 'S256'})
    with client.open(ISSUER + '/protocol/openid-connect/auth?' + query) as response:
        page = response.read().decode()
    action = html.unescape(re.search(r'action="([^"]+)"', page).group(1))
    if not action.startswith('https://localhost:19443/'):
        raise RuntimeError('unexpected authorization form origin')
    try:
        client.open(action, data=urllib.parse.urlencode(credentials).encode())
        raise RuntimeError('login did not produce expected callback')
    except urllib.error.HTTPError as response:
        if response.code != 302:
            raise RuntimeError('login response not302') from None
        location = response.headers['Location']
    if not location.startswith(redirect + '?'):
        raise RuntimeError('unexpected callback origin')
    fields = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    if fields['state'] != [state]:
        raise RuntimeError('state mismatch')
    with client.open(ISSUER + '/protocol/openid-connect/token', data=urllib.parse.urlencode({
        'grant_type': 'authorization_code', 'client_id': 'local-r1-spa', 'redirect_uri': redirect,
        'code': fields['code'][0], 'code_verifier': verifier}).encode()) as response:
        tokens = json.load(response)
    claims = lambda token: json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
    identity, access = claims(tokens['id_token']), claims(tokens['access_token'])
    checks = {'idNonceMatches': identity.get('nonce') == nonce,
        'idAuthTimePresent': isinstance(identity.get('auth_time'), int), 'idIssuerMatches': identity.get('iss') == ISSUER,
        'idAudienceMatches': identity.get('aud') == 'local-r1-spa', 'accessAudiencePresent': 'local-r1-api' in access.get('aud', []),
        'accessIssuerMatches': access.get('iss') == ISSUER}
    if not all(checks.values()):
        raise RuntimeError('diagnostic required claim validation failed')
    print(json.dumps({'tokenExchange': 200, **checks}))
    request = urllib.request.Request(ORIGIN + '/api/v1/session/context', headers={'Authorization': 'Bearer ' + tokens['access_token']})
    try:
        with client.open(request) as response:
            payload = json.load(response)
            if kind != 'founder' or response.status != 200 or payload.get('state') != 'READY' or payload.get('canEnterWorkbench') is not False or payload.get('canEnterIdentityAdmin') is not True:
                raise RuntimeError('unexpected local identity qualification')
            print(json.dumps({'selfStatus': response.status, 'state': payload.get('state'),
                'canEnterWorkbench': payload.get('canEnterWorkbench'), 'canEnterIdentityAdmin': payload.get('canEnterIdentityAdmin')}))
    except urllib.error.HTTPError as response:
        if kind != 'unmapped' or response.code != 401:
            raise RuntimeError('unexpected SELF rejection') from None
        print('SELF status ' + str(response.code))
    with client.open(ISSUER + '/protocol/openid-connect/logout', data=urllib.parse.urlencode({
        'client_id': 'local-r1-spa', 'refresh_token': tokens['refresh_token']}).encode()) as response:
        if response.status != 204:
            raise RuntimeError('diagnostic session logout failed')
        print('diagnostic session logout ' + str(response.status))


def stop():
    stop_apps()
    for name in ('keycloak', 'business-db', 'identity-db'):
        docker('stop', PREFIX + '-' + name, label='stop-' + name)
    print('local services stopped; all databases, secrets, certificates and original bootstrap preserved')


def stop_apps():
    import local_release
    local_release.RuntimeBoundary(sys.modules[__name__]).stop()
    print('owned API/SPA and registered Worker stopped; identity and database services retained')


def worker_operation(operation):
    import local_release
    import local_worker
    boundary = local_release.RuntimeBoundary(sys.modules[__name__])
    release = local_release.LocalRelease(ROOT, RUNTIME, boundary)
    worker = local_worker.LocalWorker(sys.modules[__name__],release,local_worker.WorkerBoundary(sys.modules[__name__],boundary))
    if operation == 'worker-grant':
        result = {'state':'VERIFIED','delta':worker.grant(),'basis':'APPROVED_LOCAL_INFRASTRUCTURE'}
    elif operation == 'worker-prepare':
        worker.prepare()
        result = {'state':'PREPARED','readiness':'UNVERIFIED'}
    elif operation == 'worker-start': result = worker.start()
    elif operation == 'worker-health': result = worker.health()
    else: raise RuntimeError('unknown local Worker operation')
    print(json.dumps(result,sort_keys=True))


def require_original_verification_output(output):
    """Accept only the launcher's unique final verify result, never a log scan.

    jOOQ may write an unstructured banner/version prefix on stdout. Any earlier
    object delimiters or outcome fields make that prefix ambiguous and fail
    closed, even if the last line claims success. Diagnostic text is not echoed.
    """
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate result field')
            result[key] = value
        return result

    try:
        lines = [line.strip() for line in output.decode('utf-8').splitlines() if line.strip()]
        if not lines or any(any(marker in line for marker in ('{', '}', '"mode"', '"plannedDelta"'))
                            for line in lines[:-1]):
            raise ValueError('ambiguous result framing')
        result = json.loads(lines[-1], object_pairs_hook=unique_object)
        if result != {'mode': 'VERIFIED_ORIGINAL', 'plannedDelta': {}}:
            raise ValueError('unexpected verification result')
    except (UnicodeError, ValueError):
        raise RuntimeError('original bootstrap verification output unavailable') from None


def release_operation(operation, arguments):
    import local_release
    boundary = local_release.RuntimeBoundary(sys.modules[__name__])
    release = local_release.LocalRelease(ROOT, RUNTIME, boundary)
    boundary.protect()
    if operation == 'release-status':
        print(json.dumps(release.status(), sort_keys=True))
        return
    lock = RUNTIME / 'release-operation.lock'
    with lock.open('x', encoding='utf-8') as stream:
        stream.write(str(os.getpid()))
    try:
        if operation == 'snapshot-release':
            print('saved release: ' + release.snapshot())
        elif operation == 'capture-build-inputs':
            release.capture_inputs(arguments[0])
            print('before-build inputs recorded; execute reviewed builds separately')
        elif operation == 'describe-candidate':
            if len(arguments) != 3:
                raise RuntimeError('exact commit and actual Jar/SPA build exit codes required')
            request = release.describe(arguments[0], {'jar': int(arguments[1]), 'spa': int(arguments[2])})
            local_release.atomic(RUNTIME / 'candidate-release.json', request)
            print('candidate descriptor saved for operator review; no activation')
        elif operation == 'stage-release':
            print('staged release: ' + release.stage(local_release.read_json(RUNTIME / 'candidate-release.json')))
        elif operation == 'stage-local-auto-source':
            if len(arguments) != 1:
                raise RuntimeError('fixed source staging requires only exact operator commit')
            from local_source_release import stage_local_auto_source
            print('staged release: ' + stage_local_auto_source(release, arguments[0]))
        elif operation == 'activate-release':
            release.activate(arguments[0])
            print('release bytes and gate activated; apps remain stopped, readiness unverified')
        elif operation == 'rollback-release':
            release.rollback()
            print('saved bytes and gate restored; apps remain stopped, business facts retained')
        elif operation == 'recover-release':
            release.recover(arguments[0])
            print('explicit release reconciliation complete; apps remain stopped')
        elif operation == 'bootstrap-verify-current-release':
            operator = release.derived_operator()
            paths = release.paths()
            command = [JAVA, '-Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand',
                       '-cp', paths['jar'], 'org.springframework.boot.loader.launch.PropertiesLauncher',
                       'verify', operator, RUNTIME / 'original-manifest.json']
            result = run(command, 'bootstrap-verify-current-release')
            require_original_verification_output(result)
            release.paths()
            print('VERIFIED_ORIGINAL with current release expectations; original operator preserved')
        else:
            raise RuntimeError('unknown release operation')
    finally:
        lock.unlink()


def resume():
    for name in ('identity-db', 'business-db', 'keycloak'):
        docker('start', PREFIX + '-' + name, label='resume-' + name)
    for attempt in range(40):
        try:
            health()
            break
        except (OSError, urllib.error.URLError):
            time.sleep(1)
    else:
        raise RuntimeError('identity readiness unavailable; state preserved')
    start_apps()


if __name__ == '__main__':
    try:
        require_protected_runtime()
        if sys.argv[1] not in ('prepare', 'stop', 'stop-apps', 'release-status', 'worker-grant', 'worker-prepare', 'worker-start', 'worker-health'):
            secret_bundle(RUNTIME)
        if sys.argv[1] in ('snapshot-release', 'capture-build-inputs', 'describe-candidate', 'stage-release', 'stage-local-auto-source', 'activate-release',
                          'rollback-release', 'release-status', 'recover-release', 'bootstrap-verify-current-release'):
            release_operation(sys.argv[1], sys.argv[2:])
            sys.exit(0)
        if sys.argv[1] in ('start-apps', 'stop-apps', 'stop', 'resume', 'worker-grant', 'worker-prepare', 'worker-start', 'worker-health'):
            lock = RUNTIME / 'release-operation.lock'
            with lock.open('x', encoding='utf-8') as stream:
                stream.write(str(os.getpid()))
            try:
                if sys.argv[1].startswith('worker-'):
                    if len(sys.argv) != 2:
                        raise RuntimeError('local Worker operations do not accept arbitrary identities or grant options')
                    worker_operation(sys.argv[1])
                else:
                    {'start-apps': start_apps, 'stop-apps': stop_apps, 'stop': stop, 'resume': resume}[sys.argv[1]]()
            finally:
                lock.unlink()
            sys.exit(0)
        {'prepare': initialize, 'infrastructure': infrastructure, 'health': health, 'migrate': migrate,
         'bootstrap-dry-run': lambda: bootstrap('dry-run'), 'bootstrap-execute': lambda: bootstrap('execute'),
         'bootstrap-verify': lambda: bootstrap('verify'), 'keycloak-create': start_keycloak,
         'service-fixture': service_fixture, 'application-config': application_config, 'start-apps': start_apps,
         'protocol-check': protocol_check, 'protocol-check-unmapped': lambda: protocol_check('unmapped'),
         'stop': stop, 'stop-apps': stop_apps, 'resume': resume}[sys.argv[1]]()
    except Exception as error:
        print('local-login operation failed: ' + type(error).__name__ + '; protected diagnostics retained', file=sys.stderr)
        sys.exit(1)
