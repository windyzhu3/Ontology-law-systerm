"""Approved isolated Haihua UAT infrastructure. Secrets/logs stay in ignored private runtime.

Initialization never clones an existing database, creates HUMAN business grants or
writes business workflow facts. Human setup is performed through the actual admin UI.
Run stages explicitly; failed stages preserve state for inspection, not replacement.
"""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import base64
import hashlib
import hmac
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".superpowers/haihua-uat-runtime"
sys.path.insert(0, str(ROOT / "deploy/local-login"))
import local_login as local

PREFIX = "ontology-law-haihua-uat"
ISSUER = "https://localhost:20543/realms/haihua-uat"
ORIGIN = "https://localhost:20544"
AUDIENCE = "haihua-uat-api"
DIRECTORY = "haihua-uat-directory"
CLIENT = "haihua-uat-spa"
PROVIDER = "HAIHUA_UAT"
JAR = RUNTIME / "app.jar"
JAVA = local.JAVA
NODE = local.TOOLS / "node-v24.20.0-win-x64/node.exe"
OPENSSL = Path("C:/Program Files/Git/usr/bin/openssl.exe")
local.RUNTIME = RUNTIME
local.PREFIX = PREFIX
local.ISSUER = ISSUER
local.ORIGIN = ORIGIN
local.JAR = JAR

def save(name, value):
    return local.save(name, value)

def run(args, label, data=None):
    return local.run(args, label, data)

def values():
    return local.secret_bundle(RUNTIME)

def sql(statement, label):
    return local.sql(statement, label)

def deployment():
    return json.loads((RUNTIME / "deployment.json").read_text())

def init():
    if RUNTIME.exists() and any(RUNTIME.iterdir()):
        raise RuntimeError("runtime already exists; inspect original state, never replace it")
    for port in range(20543, 20548):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    RUNTIME.mkdir(exist_ok=True)
    sid=subprocess.run(["pwsh","-NoProfile","-NonInteractive","-Command","[Security.Principal.WindowsIdentity]::GetCurrent().User.Value"],capture_output=True,check=True).stdout.decode().strip()
    result=subprocess.run(["icacls",str(RUNTIME),"/inheritance:r","/grant:r","*"+sid+":(OI)(CI)F","*S-1-5-18:(OI)(CI)F"],capture_output=True)
    if result.returncode:raise RuntimeError("runtime ACL preparation failed: "+result.stderr.decode(errors="replace"))
    c = RUNTIME / "certs"
    c.mkdir()
    local.create_secret_bundle(RUNTIME)
    v = values()
    (c / "pfx-password.txt").write_text(secrets.token_urlsafe(32))
    run([OPENSSL,"req","-x509","-newkey","rsa:3072","-sha256","-nodes","-days","30","-subj","/CN=Haihua UAT Local CA 2026-10-01","-keyout",c/"ca.key","-out",c/"ca.pem","-addext","basicConstraints=critical,CA:TRUE,pathlen:0","-addext","keyUsage=critical,keyCertSign,cRLSign"],"ca")
    for serial, (name, san, usage) in enumerate([
        ("database","DNS:identity-db,DNS:business-db,DNS:localhost,IP:127.0.0.1","serverAuth"),
        ("server","DNS:localhost,IP:127.0.0.1","serverAuth"),
        ("service","DNS:haihua-uat-worker","clientAuth"),
    ], 10001):
        (c / (name+".ext")).write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage="+usage+"\nsubjectAltName="+san+"\n")
        run([OPENSSL,"req","-new","-newkey","rsa:3072","-nodes","-subj","/CN="+name,"-keyout",c/(name+".key"),"-out",c/(name+".csr")],name+"-csr")
        run([OPENSSL,"x509","-req","-sha256","-days","30","-in",c/(name+".csr"),"-CA",c/"ca.pem","-CAkey",c/"ca.key","-set_serial",str(serial),"-extfile",c/(name+".ext"),"-out",c/(name+".crt")],name+"-sign")
    run([OPENSSL,"x509","-in",c/"ca.pem","-outform","DER","-out",c/"ca.cer"],"ca-der")
    run([OPENSSL,"pkcs12","-export","-name","haihua-server","-inkey",c/"server.key","-in",c/"server.crt","-certfile",c/"ca.pem","-out",c/"server.pfx","-passout","file:"+str(c/"pfx-password.txt")],"server-pfx")
    run([OPENSSL,"pkcs12","-export","-name","local_service","-inkey",c/"service.key","-in",c/"service.crt","-certfile",c/"ca.pem","-out",RUNTIME/"service.p12","-passout","file:"+str(RUNTIME/"secrets/trust-password.txt")],"service-pfx")
    for name in ("identity-trust.p12", "server-client-trust.p12"):
        run([local.KEYTOOL,"-importcert","-noprompt","-alias","haihua-ca","-file",c/"ca.cer","-keystore",RUNTIME/name,"-storetype","PKCS12","-storepass:file",RUNTIME/"secrets/trust-password.txt"],name)
    shutil.copyfile(c/"service.crt",RUNTIME/"service.crt")
    run([OPENSSL,"x509","-in",c/"service.crt","-pubkey","-noout","-out",RUNTIME/"service-public.pem"],"service-public-key")
    # Only this newly created local CA is added to the current user's trust store.
    run(["certutil","-user","-addstore","Root",c/"ca.cer"],"current-user-ca-trust")
    template = (ROOT/"deploy/identity/realm-template.json").read_text()
    replacements={"IDENTITY_REALM":"haihua-uat","SPA_CLIENT_ID":CLIENT,"SPA_REDIRECT_URI":ORIGIN+"/auth/callback","SPA_ORIGIN":ORIGIN,"SPA_LOGOUT_REDIRECT_URI":ORIGIN+"/login","API_AUDIENCE":AUDIENCE,"DIRECTORY_CLIENT_ID":DIRECTORY}
    for k,val in replacements.items(): template=template.replace("${"+k+"}",val)
    if "${" in template: raise RuntimeError("unresolved realm placeholder")
    realm=json.loads(template)
    realm["clients"][1]["secret"]=v["introspection"]
    realm["clients"][2]["secret"]=v["directory"]
    usernames=["sales01","sales02","sales03","sales04","sales05","sales_manager01","sales_manager02","finance01","case_admin01","sys_manager01"]
    credentials={name:{"username":name,"password":secrets.token_urlsafe(24)} for name in usernames}
    realm["users"]=[{"username":name,"enabled":True,"emailVerified":True,"firstName":name,"lastName":"海华测试","email":name+"@example.invalid","credentials":[{"type":"password","value":credentials[name]["password"],"temporary":False}]} for name in usernames]
    realm["users"].append({"username":"service-account-"+DIRECTORY,"enabled":True,"serviceAccountClientId":DIRECTORY,"clientRoles":{"realm-management":["query-users","view-users"]}})
    save("realm.json",realm)
    save("browser-credentials.json",credentials)
    (RUNTIME/"验收账号.txt").write_text("海华律所合成测试环境："+ORIGIN+"/login\n仅限本机测试。\n"+"\n".join(name+"\t"+item["password"] for name,item in credentials.items()),encoding="utf-8")
    save("deployment.json",{"tenantId":str(uuid.uuid4()),"tenantCode":"HAIHUA_UAT","runId":"HH-UAT-20261001-01","origin":ORIGIN,"issuer":ISSUER,"schemaVersion":"52-plus-2-r2-v20","createdAt":datetime.now(timezone.utc).isoformat()})
    local.require_protected_runtime()
    print("Initialized isolated private runtime and 10 identity users; no business database created yet.")

def infrastructure():
    local.require_protected_runtime()
    v=values()
    if run(["docker","ps","-a","--filter","name="+PREFIX,"--format","{{.Names}}"],"check-existing").strip():
        raise RuntimeError("existing resources; preserve and inspect rather than reinitialize")
    lock=json.loads((ROOT/"deploy/identity/identity-toolchain.lock.json").read_text())
    pg=lock["identityDatabase"]["image"]+"@"+lock["identityDatabase"]["digest"]
    kc=lock["keycloak"]["image"]+"@"+lock["keycloak"]["platformDigest"]
    for kind in ("identity","business"):
        local.docker("network","create","--internal",PREFIX+"-"+kind,label="network-"+kind)
        local.docker("network","create",PREFIX+"-"+kind+"-ingress",label="network-"+kind+"-ingress")
        name=PREFIX+"-"+kind+"-db"
        local.docker("volume","create",name+"-data",label="volume-"+kind)
        options=["create","--name",name,"--network",PREFIX+"-"+kind,"--network-alias",kind+"-db","--memory","768m","--cpus","1","-e","POSTGRES_DB="+("identity_only" if kind=="identity" else "law_contract_runtime"),"-e","POSTGRES_USER="+("identity_only" if kind=="identity" else "postgres"),"-e","POSTGRES_PASSWORD_FILE=/tmp/local-password","-v",name+"-data:/var/lib/postgresql"]
        if kind=="business":options +=["-p","127.0.0.1:20546:5432"]
        local.docker(*options,pg,"postgres","-c","ssl=on","-c","ssl_cert_file=/tmp/database.crt","-c","ssl_key_file=/tmp/database.key","-c","log_statement=none","-c","log_min_error_statement=panic",label="create-"+kind)
        local.copy_owned(name,"/tmp",{"local-password":v[kind+"-db"].encode(),"database.crt":(RUNTIME/"certs/database.crt").read_bytes(),"database.key":(RUNTIME/"certs/database.key").read_bytes()},999)
        local.docker("start",name,label="start-"+kind)
        if kind=="business":local.docker("network","connect",PREFIX+"-business-ingress",name,label="business-loopback")
        for _ in range(60):
            if subprocess.run(["docker","exec",name,"pg_isready","-U","identity_only" if kind=="identity" else "postgres"],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError("database readiness timeout")
    env={"KC_DB":"postgres","KC_DB_URL":"jdbc:postgresql://identity-db:5432/identity_only?sslmode=verify-full&sslrootcert=/opt/keycloak/conf/ca.pem","KC_DB_USERNAME":"identity_only","KC_HOSTNAME":"https://localhost:20543","KC_HOSTNAME_STRICT":"true","KC_HTTP_ENABLED":"false","KC_HTTPS_CERTIFICATE_FILE":"/opt/keycloak/conf/server.crt","KC_HTTPS_CERTIFICATE_KEY_FILE":"/opt/keycloak/conf/server.key","KC_FEATURES_DISABLED":"impersonation,parameterized-scopes","KC_HTTP_ACCESS_LOG_ENABLED":"false"}
    name=PREFIX+"-keycloak"
    opts=["create","--name",name,"--network",PREFIX+"-identity","--memory","1536m","--cpus","2","-p","127.0.0.1:20543:8443"]
    for k,val in env.items():opts +=["-e",k+"="+val]
    local.docker(*opts,"--entrypoint","/bin/bash",kc,"-ec",'export KC_DB_PASSWORD="$(</opt/keycloak/conf/db-password)"; exec /opt/keycloak/bin/kc.sh start --import-realm',label="create-keycloak")
    local.copy_owned(name,"/opt/keycloak/conf",{"db-password":v["identity-db"].encode(),**{f:(RUNTIME/"certs"/f).read_bytes() for f in ("ca.pem","server.crt","server.key")}},1000)
    import io,tarfile
    buf=io.BytesIO()
    with tarfile.open(fileobj=buf,mode="w") as archive:
        entry=tarfile.TarInfo("import");entry.type=tarfile.DIRTYPE;entry.uid=1000;entry.mode=0o700;archive.addfile(entry)
    run(["docker","cp","-",name+":/opt/keycloak/data"],"mkdir-import",buf.getvalue())
    local.copy_owned(name,"/opt/keycloak/data/import",{"haihua-uat-realm.json":(RUNTIME/"realm.json").read_bytes()},1000)
    local.docker("start",name,label="start-keycloak")
    local.docker("network","connect",PREFIX+"-identity-ingress",name,label="identity-loopback")
    print("Independent identity/business databases and Keycloak created.")

def migrate():
    shutil.copyfile(ROOT/"backend/target/ontology-law-system-0.1.0-SNAPSHOT.jar",JAR)
    dep=deployment()
    dep.update(releaseDigest=hashlib.sha256(JAR.read_bytes()).hexdigest(),manifestHash=hashlib.sha256((ROOT/"database/schema-contract-52-plus-2/generated/schema-contract-manifest.json").read_bytes()).hexdigest())
    save("deployment.json",dep)
    local.migrate()
    print("Full clean schema migration and runtime roles initialized.")

def bootstrap():
    dep=deployment();v=values();file=lambda n:(RUNTIME/n).resolve().as_posix()
    operator={"semanticBaseline":"MVP-2026-09-08.3","tenantId":dep["tenantId"],"tenantCode":"HAIHUA_UAT","identityProviderCode":PROVIDER,"issuer":ISSUER,"apiAudience":AUDIENCE,"directoryClientId":DIRECTORY,"directorySecretPath":file("secrets/directory.txt"),"operatorAssertion":"User approved Haihua isolated UAT on 2026-10-01","node":"HH_UAT_BOOTSTRAP","activeBootstrapKeyId":"local-offline-v1","bootstrapKeyPaths":{"local-offline-v1":file("secrets/offline-key.txt")},"subjectHmacPath":file("secrets/subject-key.txt"),"identityTrustStorePath":file("identity-trust.p12"),"identityTrustStorePasswordPath":file("secrets/trust-password.txt"),"database":{"url":"jdbc:postgresql://localhost:20546/law_contract_runtime?sslmode=verify-full&sslrootcert="+file("certs/ca.pem"),"username":"law_api_login","passwordPath":file("secrets/api-db.txt"),"schemaVersion":dep["schemaVersion"],"releaseDigest":dep["releaseDigest"],"manifestHash":dep["manifestHash"]}}
    if (RUNTIME/"original-manifest.json").exists():raise RuntimeError("original bootstrap manifest exists; verify, do not repeat initialization")
    save("operator.json",operator);save("founder-identifier.txt","sys_manager01")
    command=[JAVA,"-Dloader.main=io.github.windyzhu3.ontologylaw.api.IdentityBootstrapCommand","-cp",JAR,"org.springframework.boot.loader.launch.PropertiesLauncher"]
    candidate=json.loads(run([*command,"candidate",RUNTIME/"operator.json",RUNTIME/"founder-identifier.txt"],"bootstrap-candidate"))
    manifest={"profile":"R1_IDENTITY_BOOTSTRAP_V1","commandId":str(uuid.uuid4()),"tenantCode":"HAIHUA_UAT","tenantDisplayName":"海华律师事务所测试租户","rootCode":"HAIHUA","rootDisplayName":"海华律师事务所","identityProviderCode":PROVIDER,"issuer":ISSUER,"providerUserSelector":candidate["providerUserSelector"],"principalDisplayName":"sys_manager01","effectiveFrom":(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat().replace("+00:00","Z"),"operatorAssertion":operator["operatorAssertion"]}
    save("original-manifest.json",manifest)
    for mode in ("dry-run","execute","verify"):
        args=[*command,mode,RUNTIME/"operator.json",RUNTIME/"original-manifest.json"]
        if mode=="execute":args.append("--confirm-bootstrap")
        run(args,"bootstrap-"+mode)
    print("Trusted bootstrap created sys_manager01 with four root management authorities.")

def service():
    dep=deployment();v=values();tenant=dep["tenantId"]
    if (RUNTIME/"service-fixture.json").exists():raise RuntimeError("service initialization already recorded")
    fixture={"tenantId":tenant,"principalId":str(uuid.uuid4()),"appointmentId":str(uuid.uuid4())}
    save("service-fixture.json",fixture)
    subject=hmac.new(base64.b64decode(v["subject-key"]),b"haihua-uat-service",hashlib.sha256).hexdigest()
    statements=f"BEGIN; INSERT INTO identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) VALUES ('{tenant}','{fixture['principalId']}','SERVICE','LOCAL_SERVICE',decode('{subject}','hex'),'Haihua UAT infrastructure','ACTIVE',clock_timestamp()); INSERT INTO identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) SELECT '{tenant}','{fixture['appointmentId']}','{fixture['principalId']}',organization_unit_id,'SERVICE',clock_timestamp(),'ACTIVE',clock_timestamp() FROM identity.organization_unit WHERE tenant_id='{tenant}' AND unit_code='HAIHUA';"
    codes=["R1_PROJECTION_CONSUME","CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER","OPPORTUNITY_TASK_ACTIVATE","OPPORTUNITY_TASK_RECOVER","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER","CONTRACT_TASK_RECOVER"]
    for code in codes:
        statements+=f"INSERT INTO identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) SELECT '{tenant}','{uuid.uuid4()}','{fixture['appointmentId']}',a.appointment_id,o.organization_unit_id,'{code}',clock_timestamp(),'ACTIVE',clock_timestamp() FROM identity.organization_unit o JOIN identity.appointment a ON a.tenant_id=o.tenant_id AND a.organization_unit_id=o.organization_unit_id AND a.role_code='IDENTITY_ADMIN' WHERE o.tenant_id='{tenant}' AND o.unit_code='HAIHUA';"
    sql(statements+" COMMIT;","service-infrastructure-only")
    save("service-grant-inventory.json",{"basis":"Approved isolated UAT infrastructure only; no HUMAN business grant","identity":fixture,"codes":codes})
    print("SERVICE identity and explicit background authorities initialized.")

def config():
    dep=deployment();v=values();fixture=json.loads((RUNTIME/"service-fixture.json").read_text());file=lambda n:(RUNTIME/n).resolve().as_posix()
    local.application_config()
    props=dict(line.split("=",1) for line in (RUNTIME/"application.properties").read_text().splitlines())
    for k in list(props):
        if k.startswith("ols.api.sources["):del props[k]
    props.update({"server.port":"20545","ols.api.node":"HH_UAT_API","ols.api.database.url":"jdbc:postgresql://localhost:20546/law_contract_runtime?sslmode=verify-full&sslrootcert="+file("certs/ca.pem"),"ols.api.database.schema-version":dep["schemaVersion"],"ols.api.trusts[0].audience":AUDIENCE,"ols.api.human-trusts[0].audience":AUDIENCE,"ols.api.human-trusts[0].identity-provider-code":PROVIDER,"ols.api.human-trusts[0].introspection-client-id":AUDIENCE,"ols.api.human-trusts[0].directory-client-id":DIRECTORY,"ols.api.registrations[0].audience":AUDIENCE,"ols.api.registrations[0].source-account-codes[0]":"HH_S1_MANUAL"})
    for index,(dept,mode) in enumerate([("SALES_1","MANUAL"),("SALES_2","MANUAL"),("SALES_1","AUTOMATIC"),("SALES_2","AUTOMATIC")]):
        code="HH_S"+("1" if dept=="SALES_1" else "2")+"_"+("MANUAL" if mode=="MANUAL" else "AUTO")
        for k,val in {"assignment-mode":mode,"routing-organization-root-codes[0]":dept,"routing-supervisor-root-code":dept,"source-intake-root-code":dept,"business-timezone":"Asia/Shanghai"}.items():props["ols.api.sources["+code+"]."+k]=val
        for k,val in {"source-account-code":code,"display-name":"海华"+("销售一部" if dept=="SALES_1" else "销售二部")+("手工线索" if mode=="MANUAL" else "自动分配线索"),"source-channel-code":"HAIHUA_UAT","service-category-code":"SYNTHETIC_LEGAL","jurisdiction-code":"CN","urgency-code":"NORMAL"}.items():props[f"ols.api.intake-sources[{index}]."+k]=val
    transaction_key=RUNTIME/"secrets/payment-key.txt"
    if not transaction_key.exists():transaction_key.write_text(base64.b64encode(secrets.token_bytes(32)).decode())
    tk="ols.api.tenant-keys["+dep["tenantId"]+"].payment."
    props.update({tk+"transaction-hmac":transaction_key.read_text(),tk+"account-code":"HH_UAT_TEST_ACCOUNT",tk+"account-label":"海华合成测试收款账户"})
    # Destination UUID is added after the admin creates CASE_ADMIN; no fictitious organization.
    if (RUNTIME/"account-map.json").exists():
        mapping=json.loads((RUNTIME/"account-map.json").read_text())
        props["ols.api.tenant-keys["+dep["tenantId"]+"].transfer-destination-organization-id"]=mapping["organizations"]["CASE_ADMIN"]
    save("application.properties",("\n".join(k+"="+val for k,val in props.items())+"\n").encode("ascii","backslashreplace").decode())
    worker={"ols.runtime-role":"worker","spring.main.web-application-type":"none","spring.main.banner-mode":"off","logging.level.root":"WARN","logging.level.io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeHealth":"INFO","ols.worker.semantic-baseline":"MVP-2026-09-08.3","ols.worker.node":"HH_UAT_WORKER","ols.worker.api-origin":"https://localhost:20545","ols.worker.database.url":props["ols.api.database.url"],"ols.worker.database.username":"law_worker_login","ols.worker.database.password":v["worker-db"],"ols.worker.database.schema-version":dep["schemaVersion"],"ols.worker.database.release-digest":dep["releaseDigest"],"ols.worker.database.manifest-hash":dep["manifestHash"],"ols.worker.trust-store-path":file("identity-trust.p12"),"ols.worker.trust-store-password":v["trust-password"],"ols.worker.bindings[0].tenant-id":dep["tenantId"],"ols.worker.bindings[0].principal-id":fixture["principalId"],"ols.worker.bindings[0].appointment-id":fixture["appointmentId"],"ols.worker.bindings[0].credential-alias":"local_service","ols.worker.bindings[0].key-store-path":file("service.p12"),"ols.worker.bindings[0].key-store-password":v["trust-password"],"ols.worker.bindings[0].key-password":v["trust-password"],"ols.worker.bindings[0].certificate-sha256":hashlib.sha256(ssl.PEM_cert_to_DER_cert((RUNTIME/"service.crt").read_text())).hexdigest(),"ols.worker.owner-exception-observation-enabled":"true","ols.worker.opportunity-task-scheduling-enabled":"true"}
    worker["ols.worker.bindings[0].trust-store-path"]=worker.pop("ols.worker.trust-store-path")
    worker["ols.worker.bindings[0].trust-store-password"]=worker.pop("ols.worker.trust-store-password")
    save("worker.properties","\n".join(k+"="+val for k,val in worker.items())+"\n")
    (RUNTIME/"materials").mkdir(exist_ok=True)
    print("Isolated API/Worker configuration prepared; material and AI environment explicitly set at launch.")

if __name__=="__main__":
    stages={"init":init,"infrastructure":infrastructure,"health":local.health,"migrate":migrate,"bootstrap":bootstrap,"service":service,"config":config}
    if len(sys.argv)!=2 or sys.argv[1] not in stages:raise SystemExit("choose stage: "+", ".join(stages))
    stages[sys.argv[1]]()
