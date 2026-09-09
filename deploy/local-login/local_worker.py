"""Approved local infrastructure assembly of the existing production Worker.

No bootstrap, business command, migration, identity replacement, or grant deletion.
Private material and uncertain-operation journals always remain in the protected runtime.
"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

from local_release import atomic, digest, encoded, read_json, regular, literal, GATE_SQL, OWNER_SQL
from local_release import owned_process, LocalRelease

CODES = ('R1_PROJECTION_CONSUME', 'CONTACT_TASK_RECOVER', 'ROUTING_REVIEW_TASK_RECOVER')
BASIS = 'APPROVED_LOCAL_WORKER_INFRASTRUCTURE_V1; original bootstrap founder setup grantor; no HTTP action or receipt'


def validate_alias(alias):
    if alias != 'local_service':
        raise RuntimeError('exact local Worker binding alias required')


def identity_valid(identity):
    if set(identity) != {'tenantId', 'principalId', 'appointmentId'}:
        raise RuntimeError('original SERVICE fixture required')
    for value in identity.values():
        if str(uuid.UUID(value)) != value:
            raise RuntimeError('canonical SERVICE id required')


def prerequisites(identity, facts, now):
    identity_valid(identity)
    try:
        t, p, a, r, f, fa, bootstrap = [facts[k] for k in
            ('tenant', 'principal', 'appointment', 'root', 'founder', 'founderAppointment', 'bootstrap')]
        if any(x['tenant_id'] != identity['tenantId'] or x['state'] != 'ACTIVE' or x['revision'] != 0 for x in (t,p,a,r,f,fa)):
            raise ValueError()
        if (t['tenant_code'] != 'LOCAL_R1' or p['principal_id'] != identity['principalId']
            or p['principal_kind'] != 'SERVICE' or p['identity_provider_code'] != 'LOCAL_SERVICE'
            or a['appointment_id'] != identity['appointmentId'] or a['principal_id'] != p['principal_id']
            or a['role_code'] != 'SERVICE' or a['organization_unit_id'] != r['organization_unit_id']
            or r['unit_code'] != 'ROOT' or r['parent_organization_unit_id'] is not None
            or bootstrap['rootOrganizationId'] != r['organization_unit_id']
            or bootstrap['founderPrincipalId'] != f['principal_id'] or bootstrap['appointmentId'] != fa['appointment_id']
            or f['principal_kind'] != 'HUMAN' or f['identity_provider_code'] != 'LOCAL_R1'
            or fa['principal_id'] != f['principal_id'] or fa['organization_unit_id'] != r['organization_unit_id']
            or fa['role_code'] != 'IDENTITY_ADMIN'):
            raise ValueError()
        for appointment in (a, fa):
            if (datetime.fromisoformat(appointment['effective_from']) > now
                or appointment['effective_until'] is not None or appointment.get('ended_at') is not None):
                raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise RuntimeError('exact original SERVICE/ROOT/founder prerequisites unavailable') from None


def grant_rows(identity, facts, ids, at):
    return [{'tenant_id': identity['tenantId'], 'authority_grant_id': id,
        'grantee_appointment_id': identity['appointmentId'],
        'granted_by_appointment_id': facts['founderAppointment']['appointment_id'],
        'scope_organization_unit_id': facts['root']['organization_unit_id'], 'authority_code': code,
        'valid_from': at, 'valid_until': None, 'state': 'ACTIVE', 'created_at': at,
        'revoked_at': None, 'revocation_reason_code': None, 'revision': 0} for code, id in zip(CODES, ids)]


def grant_plan(identity, facts, now):
    prerequisites(identity, facts, now)
    if facts['grants']:
        raise RuntimeError('preexisting SERVICE grants without original operation inventory')
    at = now.isoformat(timespec='microseconds')
    return {'version': 1, 'basis': BASIS, 'identity': identity, 'original': facts,
            'grants': grant_rows(identity, facts, [str(uuid.uuid4()) for _ in CODES], at)}


def grant_delta(plan, current):
    try:
        if set(plan) != {'version', 'basis', 'identity', 'original', 'grants'} or plan['version'] != 1 or plan['basis'] != BASIS:
            raise ValueError()
        rows = plan['grants']
        if len(rows) != 3 or len({row['authority_grant_id'] for row in rows}) != 3:
            raise ValueError()
        ids = [str(uuid.UUID(row['authority_grant_id'])) for row in rows]
        at = rows[0]['created_at']
        when = datetime.fromisoformat(at)
        prerequisites(plan['identity'], plan['original'], when)
        if when.tzinfo is None or plan['original']['grants'] or rows != grant_rows(plan['identity'], plan['original'], ids, at):
            raise ValueError()
        original = {**current, 'grants': []}
        if original != plan['original']:
            raise ValueError()
        if not current['grants']:
            return 3
        def normalize(grants):
            return sorted([{**row, **{key: datetime.fromisoformat(row[key]).isoformat(timespec='microseconds')
                for key in ('valid_from','created_at')}} for row in grants],key=lambda x:x['authority_code'])
        if normalize(current['grants']) == normalize(rows):
            return 0
    except (ValueError, KeyError, TypeError):
        pass
    raise RuntimeError('original grant operation or exact existing grant shape conflicts; no writes')


def worker_properties(runtime, package, identity, fingerprint, db_secret, tls_secret):
    identity_valid(identity)
    for value in (package.get('release'), package.get('manifest'), fingerprint):
        if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value) or value == '0'*64:
            raise RuntimeError('current release, manifest and certificate required')
    if not runtime.is_absolute() or not package['jar'].is_absolute() or not db_secret or not tls_secret:
        raise RuntimeError('absolute controlled paths and existing secrets required')
    path = lambda name: (runtime / name).as_posix()
    props = {'ols.runtime-role': 'worker', 'spring.main.web-application-type': 'none',
        'spring.main.banner-mode': 'off', 'logging.level.root': 'WARN',
        'logging.level.io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth': 'INFO',
        'logging.pattern.console': '%d{yyyy-MM-dd\'T\'HH:mm:ss.SSSXXX} %level ${PID} --- [%thread] %logger : %msg%n',
        'ols.worker.semantic-baseline': 'MVP-2026-09-08.3', 'ols.worker.node': 'LOCAL_LOGIN_WORKER',
        'ols.worker.api-origin': 'https://localhost:19445',
        'ols.worker.database.url': 'jdbc:postgresql://localhost:19446/law_contract_runtime?sslmode=verify-full&sslrootcert=' + path('certs/ca.pem'),
        'ols.worker.database.username': 'law_worker_login', 'ols.worker.database.password': db_secret,
        'ols.worker.database.schema-version': '52-plus-2-v1.2',
        'ols.worker.database.release-digest': package['release'], 'ols.worker.database.manifest-hash': package['manifest']}
    binding = {'tenant-id': identity['tenantId'], 'principal-id': identity['principalId'], 'appointment-id': identity['appointmentId'],
        'credential-alias': 'local_service', 'certificate-sha256': fingerprint,
        'key-store-path': path('worker/service.p12'), 'key-store-password': tls_secret,
        'trust-store-path': path('worker/trust.p12'), 'trust-store-password': tls_secret}
    props.update({'ols.worker.bindings[0].' + key: value for key,value in binding.items()})
    if any('\n' in value or '\r' in value or '\\' in value for value in props.values()):
        raise RuntimeError('unsafe properties value')
    return ''.join(key + '=' + value + '\n' for key,value in props.items())


def log_ready(text, started, pid):
    pattern = re.compile(r'^(\S+) INFO\s+' + str(pid) + r' --- \[[^\r\n]+\] io\.github\.windyzhu3\.ontologylaw\.worker\.WorkerRuntimeHealth : (R1_WORKER_ASSEMBLY_ISOLATED|R1_WORKER_READY|R1_WORKER_UNAVAILABLE)$')
    isolated, ready = False, False
    for line in text.splitlines():
        match = pattern.fullmatch(line)
        if not match:
            continue
        try: at = datetime.fromisoformat(match[1])
        except ValueError: return False
        if at.tzinfo is None or at < started:
            continue
        if match[2] == 'R1_WORKER_ASSEMBLY_ISOLATED': isolated, ready = True, False
        else: ready = isolated and match[2] == 'R1_WORKER_READY'
    return ready


def worker_command(runner, package):
    return [str(runner.JAVA), '-Xmx384m', '-jar', str(package/'app.jar'),
            '--spring.config.location=' + (runner.RUNTIME/'worker/application.properties').as_uri(),
            '--ols.runtime-role=worker', '--spring.main.web-application-type=none']


# Local JDK source launcher adapter, not product Java or a new application main.
# It loads no API properties/Owner secrets and emits only a digest or fixed status.
CERTIFICATE_ADAPTER = r'''import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
class LocalWorkerCertificate {
 static KeyStore store(Path p,char[] password)throws Exception{var k=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(p)){k.load(in,password);}return k;}
 static X509Certificate cert(Path p)throws Exception{try(var in=Files.newInputStream(p)){return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}}
 static X509TrustManager trust(KeyStore k)throws Exception{var f=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());f.init(k);return (X509TrustManager)f.getTrustManagers()[0];}
 public static void main(String[] args){try{execute(args);}catch(Exception failure){System.err.println("LOCAL_WORKER_CERTIFICATE_UNAVAILABLE");System.exit(2);}}
 static void execute(String[] args)throws Exception{
  Path root=Path.of(args[0]);char[] pass=Files.readString(root.resolve("secrets/trust-password.txt")).toCharArray();
  var original=store(root.resolve("service.p12"),pass);var copied=store(root.resolve("worker/service.p12"),pass);
  var certificate=cert(root.resolve("service.crt"));certificate.checkValidity();
  if(original.size()!=1||copied.size()!=1||!original.isKeyEntry("local-service")||!copied.isKeyEntry("local_service")
     ||!Arrays.equals(original.getCertificate("local-service").getEncoded(),certificate.getEncoded())
     ||!Arrays.equals(copied.getCertificate("local_service").getEncoded(),certificate.getEncoded())
     ||!Arrays.equals(original.getKey("local-service",pass).getEncoded(),copied.getKey("local_service",pass).getEncoded())
     ||certificate.getExtendedKeyUsage()==null||!certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"))throw new Exception();
  trust(store(root.resolve("server-client-trust.p12"),pass)).checkClientTrusted(new X509Certificate[]{certificate},"RSA");
  var server=cert(root.resolve("certs/server.crt"));server.checkValidity();
  var trusted=store(root.resolve("worker/trust.p12"),pass);trust(trusted).checkServerTrusted(new X509Certificate[]{server},"RSA");
  if(args[1].equals("readiness")){
   var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(copied,pass);
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trusted);
   var tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
   try(var client=HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()){
    var request=HttpRequest.newBuilder(URI.create("https://localhost:19445/internal/v1/projections/r1/readiness")).timeout(Duration.ofSeconds(10)).GET().build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
    try(var body=response.body()){if(response.statusCode()!=204||body.read()!=-1||response.headers().firstValue("ETag").isPresent()
      ||!response.headers().allValues("Cache-Control").stream().flatMap(x->Arrays.stream(x.split(","))).anyMatch(x->x.strip().equalsIgnoreCase("no-store")))throw new Exception();}
   }
   System.out.println("LOCAL_WORKER_MTLS_READY");
  }else if(args[1].equals("inspect")) System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
  else throw new Exception();Arrays.fill(pass,'\0');
 }
}'''


def certificate_probe(runner, mode):
    source = runner.RUNTIME/'worker/LocalWorkerCertificate.java'
    if source.exists():
        if regular(source).read_text(encoding='utf-8') != CERTIFICATE_ADAPTER:
            raise RuntimeError('local certificate adapter drift')
    else:
        atomic(source, CERTIFICATE_ADAPTER.encode())
    result = subprocess.run([str(runner.JAVA), '-Xmx128m', str(source), str(runner.RUNTIME), mode],
        capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError('local Worker certificate or mTLS unavailable')
    value = result.stdout.decode().strip()
    if mode == 'inspect' and not re.fullmatch('[0-9a-f]{64}', value) or mode == 'readiness' and value != 'LOCAL_WORKER_MTLS_READY':
        raise RuntimeError('ambiguous certificate result')
    return value


def prepare_certificate(runner):
    root = runner.RUNTIME
    originals = {name: digest(regular(root/name).read_bytes()) for name in
                 ('service.p12', 'service.crt', 'server-client-trust.p12', 'identity-trust.p12', 'certs/server.crt')}
    destination = root/'worker/service.p12'
    if not destination.exists():
        shutil.copy2(root/'service.p12', destination)
        # The private key and DER stay unchanged; only the alias in a new copy changes.
        result = subprocess.run([str(runner.KEYTOOL), '-changealias', '-alias', 'local-service', '-destalias', 'local_service',
            '-keystore', str(destination), '-storepass:file', str(root/'secrets/trust-password.txt')],
            capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            raise RuntimeError('alias copy incomplete; preserve and inspect')
    trust = root/'worker/trust.p12'
    if not trust.exists(): shutil.copy2(root/'identity-trust.p12', trust)
    if digest(regular(trust).read_bytes()) != originals['identity-trust.p12']:
        raise RuntimeError('Worker trust copy drift')
    fingerprint = certificate_probe(runner, 'inspect')
    if any(digest(regular(root/name).read_bytes()) != value for name,value in originals.items()):
        raise RuntimeError('original certificate material drift')
    return fingerprint


def verify_certificate(runner):
    root = runner.RUNTIME
    for name in ('worker/service.p12','worker/trust.p12','worker/LocalWorkerCertificate.java'):
        regular(root/name)
    if digest(regular(root/'worker/trust.p12').read_bytes()) != digest(regular(root/'identity-trust.p12').read_bytes()):
        raise RuntimeError('Worker trust copy drift')
    return certificate_probe(runner,'inspect')


def facts_query(identity, command):
    identity_valid(identity)
    command = str(uuid.UUID(command))
    tenant, principal, appointment = [literal(identity[k]) + '::uuid' for k in ('tenantId','principalId','appointmentId')]
    audit = "(SELECT to_jsonb(x) FROM audit.audit_entry x WHERE tenant_id="+tenant+" AND command_id="+literal(command)+"::uuid AND command_type='BOOTSTRAP_IDENTITY_ADMIN')"
    summary = '('+audit+"->'change_summary')"
    row = lambda table, condition: '(SELECT to_jsonb(x) FROM '+table+' x WHERE tenant_id='+tenant+' AND '+condition+')'
    root = "("+summary+"->>'rootOrganizationId')::uuid"
    founder = "("+summary+"->>'founderPrincipalId')::uuid"
    founder_appointment = "("+summary+"->>'appointmentId')::uuid"
    aggregate = lambda table,condition,order: "(SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY "+order+"),'[]'::jsonb) FROM "+table+" x WHERE tenant_id="+tenant+" AND "+condition+")"
    fields = {'tenant':row('identity.tenant', 'tenant_id='+tenant),
        'principal':row('identity.principal','principal_id='+principal),
        'appointment':row('identity.appointment','appointment_id='+appointment),
        'root':row('identity.organization_unit','organization_unit_id='+root),
        'founder':row('identity.principal','principal_id='+founder),
        'founderAppointment':row('identity.appointment','appointment_id='+founder_appointment),
        'bootstrap':summary, 'originalAudit':audit,
        'founderGrants':aggregate('identity.authority_grant','authority_grant_id IN (SELECT jsonb_array_elements_text('+summary+"->'grantIds')::uuid)",'authority_grant_id'),
        'originalSlot':row('execution.command_execution_slot', 'command_id='+literal(command)+'::uuid'),
        'originalReceipt':row('execution.command_receipt', 'command_execution_slot_id=(SELECT command_execution_slot_id FROM execution.command_execution_slot WHERE tenant_id='+tenant+' AND command_id='+literal(command)+'::uuid)'),
        'grants':aggregate('identity.authority_grant','grantee_appointment_id='+appointment,'authority_code')}
    return 'SELECT jsonb_build_object('+','.join(literal(k)+','+v for k,v in fields.items())+')'


class WorkerBoundary:
    def __init__(self, runner, release_boundary):
        self.runner, self.release_boundary = runner, release_boundary

    def verify_original(self):
        release = LocalRelease(self.runner.ROOT, self.runner.RUNTIME, self.release_boundary)
        operator = release.derived_operator()
        paths = release.paths()
        output = self.runner.run([self.runner.JAVA,
            '-Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand', '-cp',paths['jar'],
            'org.springframework.boot.loader.launch.PropertiesLauncher', 'verify',operator,
            self.runner.RUNTIME/'original-manifest.json'], 'worker-original-bootstrap-verify')
        self.runner.require_original_verification_output(output)
        release.paths()

    def facts(self, identity, command):
        return json.loads(self.release_boundary.sql("BEGIN READ ONLY; SET LOCAL TIME ZONE 'UTC'; "
            + facts_query(identity,command) + '; COMMIT;'))

    def certificate(self):
        return verify_certificate(self.runner)

    def prepare_certificate(self):
        return prepare_certificate(self.runner)

    def mtls(self):
        certificate_probe(self.runner, 'readiness')

    def database(self, paths):
        # Use the actual Worker login and original Worker secret, never Owner/API.
        lock = read_json(self.runner.ROOT/'deploy/identity/identity-toolchain.lock.json')['identityDatabase']
        args = ['docker','run','--rm','--pull=never','-i','--network',self.runner.PREFIX+'-business',
            '--mount','type=bind,source='+str(self.runner.RUNTIME/'secrets/worker-db.txt')+',target=/run/password,readonly',
            '--mount','type=bind,source='+str(self.runner.RUNTIME/'certs/ca.pem')+',target=/run/ca.pem,readonly',
            '--entrypoint','/bin/bash',lock['image']+'@'+lock['digest'],'-ec',
            'export PGPASSWORD="$(</run/password)"; exec psql -X -qAt -v ON_ERROR_STOP=1 '
            '"host=business-db dbname=law_contract_runtime user=law_worker_login sslmode=verify-full sslrootcert=/run/ca.pem"']
        checks = """DO $cap$ BEGIN
 IF session_user<>'law_worker_login' OR current_user<>session_user OR
 NOT (SELECT rolcanlogin AND NOT (rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname=session_user) OR
 (SELECT count(*) FROM pg_auth_members WHERE member=session_user::regrole)<>1 OR
 NOT EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid WHERE m.member=session_user::regrole AND r.rolname='law_app_worker' AND NOT m.admin_option AND NOT m.inherit_option AND m.set_option AND NOT(r.rolcanlogin OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)) OR
 EXISTS(SELECT 1 FROM pg_auth_members WHERE member='law_app_worker'::regrole) OR
 NOT has_database_privilege(session_user,current_database(),'CONNECT') OR has_database_privilege(session_user,current_database(),'CREATE,TEMPORARY') OR
 (SELECT count(*) FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a WHERE d.datname=current_database() AND a.grantee=session_user::regrole AND a.privilege_type='CONNECT' AND NOT a.is_grantable)<>1 OR
 EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=session_user::regrole) OR
 EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND (n.nspowner=session_user::regrole OR has_schema_privilege(session_user,n.oid,'USAGE,CREATE'))) OR
 EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND (c.relowner=session_user::regrole OR EXISTS(SELECT 1 FROM aclexplode(c.relacl) a WHERE a.grantee IN(0,session_user::regrole::oid)))) OR
 EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN LATERAL aclexplode(a.attacl) acl WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND acl.grantee IN(0,session_user::regrole::oid)) OR
 EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND (p.proowner=session_user::regrole OR EXISTS(SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee IN(0,session_user::regrole::oid))))
 THEN RAISE EXCEPTION 'Worker capability unavailable'; END IF; END $cap$;"""
        sql = "BEGIN READ ONLY; SET LOCAL statement_timeout='15s'; "+checks+" SET LOCAL ROLE law_app_worker; SELECT "+GATE_SQL+" FROM platform_meta.deployment_state WHERE deployment_state_key='PRIMARY'; COMMIT;"
        result = subprocess.run(args,input=sql.encode(),capture_output=True,timeout=30,creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            raise RuntimeError('current Worker database capability unavailable')
        gate = json.loads(result.stdout)
        if (gate['operating_mode']!='ACTIVE' or gate['schema_contract_version']!='52-plus-2-v1.2'
            or gate['active_release_digest']!=paths['release'] or gate['active_manifest_hash']!=paths['manifest']):
            raise RuntimeError('current Worker database gate mismatch')

    def unregistered(self):
        # This is only a refusal scan, never process ownership or authority to stop.
        path = str(self.runner.RUNTIME/'worker/application.properties').replace("'","''")
        uri = (self.runner.RUNTIME/'worker/application.properties').as_uri().replace("'","''")
        script = "$ErrorActionPreference='Stop'; @(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -ne $PID -and $_.CommandLine -and ($_.CommandLine.Contains('"+path+"') -or $_.CommandLine.Contains('"+uri+"'))}).Count"
        result = subprocess.run(['pwsh','-NoProfile','-NonInteractive','-Command',script],capture_output=True,
            timeout=15,creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode or result.stdout.strip()!=b'0':
            raise RuntimeError('uncertain existing Worker process; inspect exact ownership')

    def listeners(self,pid):
        if type(pid) is not int or pid < 1: raise RuntimeError('invalid Worker PID')
        script = "$ErrorActionPreference='Stop'; $tcp=@(Get-NetTCPConnection -State Listen | Where-Object OwningProcess -eq "+str(pid)+"); $udp=@(Get-NetUDPEndpoint | Where-Object OwningProcess -eq "+str(pid)+"); $tcp.Count+$udp.Count"
        result = subprocess.run(['pwsh','-NoProfile','-NonInteractive','-Command',script],capture_output=True,
            timeout=15,creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode: raise RuntimeError('Worker listener evidence unavailable')
        return int(result.stdout.strip())

    def apply_grants(self, plan, current, query):
        delta = grant_delta(plan, current)
        tenant = plan['identity']['tenantId']  # grant_delta validated the exact canonical original tenant.
        fences = [int.from_bytes(bytes.fromhex(digest((prefix + tenant).encode('utf-8')))[:8],
                                 byteorder='big', signed=True)
                  for prefix in ('R1_BUSINESS_TENANT_LOCK_V1:', 'R1_IDENTITY_TENANT_LOCK_V1:')]
        # Join the existing business -> identity mutation protocol before table
        # locks. Table locks additionally cover snapshot phantoms, not that fence.
        # PERFORM avoids adding result rows to the fixed transaction acknowledgement.
        acquire = 'DO $worker_fences$ BEGIN ' + ''.join(
            'PERFORM pg_advisory_xact_lock(' + str(key) + '); ' for key in fences) + 'END $worker_fences$; '
        # Whole local identity tables are locked briefly to cover insertion phantoms as
        # well as exact original row updates. No application or receipt row is written.
        statement = ("BEGIN ISOLATION LEVEL READ COMMITTED; SET LOCAL TIME ZONE 'UTC'; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; " + acquire +
            "LOCK TABLE identity.tenant,identity.principal,identity.organization_unit,identity.appointment,identity.authority_grant,"
            "audit.audit_entry,execution.command_execution_slot,execution.command_receipt IN SHARE ROW EXCLUSIVE MODE; "
            "DO $worker$ DECLARE affected integer; BEGIN " + OWNER_SQL +
            ' IF ('+query+') IS DISTINCT FROM '+literal(encoded(current).decode())+"::jsonb THEN RAISE EXCEPTION 'original Worker facts changed'; END IF; ")
        if delta:
            statement += 'INSERT INTO identity.authority_grant SELECT * FROM jsonb_populate_recordset(NULL::identity.authority_grant,' + literal(encoded(plan['grants']).decode()) + '::jsonb); GET DIAGNOSTICS affected = ROW_COUNT; '
            statement += "IF affected <> 3 THEN RAISE EXCEPTION 'Worker grant delta conflict'; END IF; "
        statement += "END $worker$; COMMIT; SELECT 'LOCAL_WORKER_GRANTS_"+str(delta)+"';"
        if self.release_boundary.sql(statement) != 'LOCAL_WORKER_GRANTS_'+str(delta):
            raise RuntimeError('uncertain Worker grant transaction; retain original inventory and reconcile')
        return delta


class LocalWorker:
    def __init__(self, runner, release, boundary):
        self.runner, self.release, self.boundary = runner, release, boundary
        self.runtime = runner.RUNTIME
        self.folder = self.runtime/'worker'

    def context(self):
        self.boundary.release_boundary.protect()
        paths = self.release.paths()
        identity = read_json(regular(self.runtime/'service-fixture.json'))
        identity_valid(identity)
        command = read_json(regular(self.runtime/'original-manifest.json'))['commandId']
        return paths, identity, command

    def grant(self):
        paths,identity,command = self.context()
        self.boundary.database(paths)
        facts = self.boundary.facts(identity,command)
        self.boundary.verify_original()
        if facts != self.boundary.facts(identity,command):
            raise RuntimeError('original facts changed during bootstrap verification')
        self.folder.mkdir(exist_ok=True)
        inventory = self.folder/'grants.json'
        if inventory.exists(): plan = read_json(regular(inventory))
        else:
            plan = grant_plan(identity,facts,datetime.now(timezone.utc))
            atomic(inventory,plan)  # Must precede any database write, including a lost response.
        if plan['identity'] != identity:
            raise RuntimeError('original SERVICE fixture changed')
        delta = grant_delta(plan,facts)
        self.release.paths()
        actual = self.boundary.apply_grants(plan,facts,facts_query(identity,command))
        if actual != delta or grant_delta(plan,self.boundary.facts(identity,command)) != 0:
            raise RuntimeError('Worker grant verification uncertain; retain original inventory')
        atomic(self.folder/'grant-result.json',{'basis':BASIS,'delta':actual,'state':'VERIFIED'})
        return actual

    def desired(self):
        paths,identity,command = self.context()
        plan = read_json(regular(self.folder/'grants.json'))
        if plan['identity'] != identity or grant_delta(plan,self.boundary.facts(identity,command)) != 0:
            raise RuntimeError('fixed Worker grants unavailable')
        self.boundary.database(paths)
        fingerprint = self.boundary.certificate()
        config = worker_properties(self.runtime,paths,identity,fingerprint,
            regular(self.runtime/'secrets/worker-db.txt').read_text(encoding='utf-8'),
            regular(self.runtime/'secrets/trust-password.txt').read_text(encoding='utf-8'))
        return paths, config.encode()

    def prepare(self):
        self.boundary.release_boundary.protect()
        saved = read_json(self.runtime/'processes.json')
        self.boundary.release_boundary.processes()
        if 'worker' in saved and self.boundary.release_boundary.process(saved['worker']['pid']) is not None:
            raise RuntimeError('stop owned Worker before preparing configuration')
        self.boundary.prepare_certificate()
        paths,config = self.desired()
        path = self.folder/'application.properties'
        registration = self.folder/'configuration.json'
        if path.exists():
            old = regular(path).read_bytes()
            if digest(old) != read_json(regular(registration))['configHash']:
                raise RuntimeError('existing Worker configuration drift')
            atomic(self.folder/('config-'+digest(old)+'.properties'),old)
        atomic(path,config)
        atomic(registration,{'configHash':digest(config),'jar':str(paths['jar']),
            'release':paths['release'],'manifest':paths['manifest']})
        self.release.paths()

    def validate(self):
        paths,config = self.desired()
        if regular(self.folder/'application.properties').read_bytes()!=config or read_json(regular(self.folder/'configuration.json')) != {
            'configHash':digest(config),'jar':str(paths['jar']),'release':paths['release'],'manifest':paths['manifest']}:
            raise RuntimeError('Worker configuration/current release drift; explicit prepare required')
        return paths

    def start(self):
        paths = self.validate()
        if any((self.runtime/name).exists() for name in ('apps-start.pending','worker-start.pending')):
            raise RuntimeError('interrupted process launch; reconcile original marker')
        saved = read_json(self.runtime/'processes.json')
        self.boundary.release_boundary.processes()
        if 'worker' in saved and self.boundary.release_boundary.process(saved['worker']['pid']) is not None:
            raise RuntimeError('owned Worker already running')
        self.boundary.unregistered()
        with (self.runtime/'worker-start.pending').open('x') as marker:
            marker.write('retain exact Worker launch evidence; reconcile before retry')
        command = worker_command(self.runner,paths['jar'].parent)
        began = datetime.now(timezone.utc).isoformat(timespec='milliseconds')
        for name in ('worker.stdout','worker.stderr'):
            old = self.runtime/name
            if old.exists():
                data = regular(old).read_bytes()
                atomic(self.folder/('previous-'+digest(data)+'-'+name),data)
        # Limit Spring/JVM environment overrides to the generated Worker config.
        environment = {k:v for k,v in os.environ.items() if k.upper() in ('SYSTEMROOT','WINDIR','TEMP','TMP','PATH','COMSPEC','PATHEXT')}
        with (self.runtime/'worker.stdout').open('wb') as out,(self.runtime/'worker.stderr').open('wb') as errors:
            child = subprocess.Popen(command,cwd=self.folder,env=environment,stdout=out,stderr=errors,
                creationflags=subprocess.CREATE_NO_WINDOW)
        saved['worker'] = {'pid':child.pid,'executable':command[0],'args':command[1:],'startedAt':began}
        atomic(self.runtime/'processes.json',saved)
        actual = self.boundary.release_boundary.process(child.pid)
        if actual is None: raise RuntimeError('Worker exited during startup; retain launch marker')
        owned_process(saved['worker'],actual)
        saved['worker']['created'] = actual['created']
        atomic(self.runtime/'processes.json',saved)
        (self.runtime/'worker-start.pending').unlink()
        return {'state':'STARTED','readiness':'UNVERIFIED'}

    def health(self):
        self.validate()
        self.boundary.release_boundary.processes()
        expected = read_json(self.runtime/'processes.json').get('worker')
        if not expected or 'created' not in expected or 'startedAt' not in expected:
            raise RuntimeError('Worker startup evidence unavailable')
        started = datetime.fromisoformat(expected['startedAt'])
        created = datetime.fromisoformat(expected['created'])
        if (started.tzinfo is None or created.tzinfo is None or created < started
            or (created-started).total_seconds() > 30):
            raise RuntimeError('Worker launch/creation time conflict')
        started = created
        def current():
            actual = self.boundary.release_boundary.process(expected['pid'])
            if actual is None: raise RuntimeError('Worker stopped')
            owned_process(expected,actual)
            if not log_ready(regular(self.runtime/'worker.stdout').read_text(encoding='utf-8'),started,expected['pid']):
                raise RuntimeError('latest Worker aggregate health unavailable')
        current()
        self.boundary.mtls()
        if self.boundary.listeners(expected['pid']) != 0:
            raise RuntimeError('Worker has an unexpected listener')
        current()
        return {'state':'READY','requiredLoops':3,'listeners':0,'database':'READY','mtls':'READY'}
