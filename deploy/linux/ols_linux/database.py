"""Locked Flyway execution and non-disclosing PostgreSQL verification."""
import json
from pathlib import Path
import re
import zlib
from . import journal,runtime
from .bundle import inventory,sha,GENERATED


def expected_versions(repo: Path, target: str|None=None) -> list[str]:
    versions=sorted((re.match(r'V(\d+)__',name)[1] for name in inventory(Path(repo)/GENERATED/'db/migration')),key=int)
    return [v for v in versions if target is None or int(v)<=int(target)]


def verify_sources(repo: Path):
    repo=Path(repo)
    manifest=json.loads((repo/GENERATED/'schema-contract-manifest.json').read_text())
    actual=inventory(repo/GENERATED/'db/migration')
    frozen=json.loads((Path(__file__).resolve().parents[1]/'config/v20-migration-hashes.json').read_text())
    if len(actual)!=43 or any(manifest['generatedArtifactSha256'].get('db/migration/'+n)!=v for n,v in actual.items()):
        raise RuntimeError('Migration bytes differ from the reviewed manifest')
    if any(actual.get(n)!=v for n,v in frozen.items()) or set(actual)-set(frozen)!={'V1070__configurable_appointment_roles.sql','V1080__metadata_comments.sql'}:
        raise RuntimeError('Frozen migration prefix differs')


def migration_checksums(repo: Path,target=None) -> dict:
    # Flyway 13.4.0 ChecksumCalculator: UTF-8 CRC32, BOM and CR/LF independent.
    # https://github.com/flyway/flyway/blob/flyway-13.4.0/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java
    directory=Path(repo)/GENERATED/'db/migration';result={}
    for version in expected_versions(repo,target):
        matches=list(directory.glob('V'+version+'__*.sql'))
        if len(matches)!=1:raise RuntimeError('One reviewed SQL file per migration required')
        path=matches[0];text=path.read_text(encoding='utf-8-sig').replace('\r\n','\n').replace('\r','\n')
        checksum=zlib.crc32(''.join(text.split('\n')).encode('utf-8'))
        result[version]={'script':path.name,'checksum':checksum if checksum<2**31 else checksum-2**32}
    return result


def verify_history(repo: Path,history: list,target=None):
    checksums=migration_checksums(repo,target)
    versioned=[row for row in history if row['version']]
    unversioned=[row for row in history if not row['version']]
    if [row['version'] for row in versioned]!=list(checksums) or len(unversioned)!=1 or unversioned[0].get('type')!='SCHEMA' or not all(row.get('success') is True for row in history):
        raise RuntimeError('Unexpected exact Flyway history')
    for row in versioned:
        if row.get('type')!='SQL' or any(row.get(key)!=value for key,value in checksums[row['version']].items()):raise RuntimeError('Applied migration checksum or file differs')


def sql(root: Path, statement: str, identity=False, *, containers=None) -> str:
    resources=runtime.load(root)
    selected=containers if containers is not None else resources['containers']
    name=selected['identityDb' if identity else 'businessDb']
    if name not in resources['containers'].values():raise RuntimeError('Unregistered database target')
    runtime.owned(root,'container',name)
    result=runtime.run(['docker','exec','-i',name,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres',
                       '-d','keycloak' if identity else 'law_contract_runtime','-Atq'],statement.encode('utf-8'))
    return result.stdout.decode('utf-8').strip()


def roles(root: Path, operation_id: str):
    with journal.locked(root) as root:
        operation=journal.read(root,operation_id)
        if operation['kind']!='initialize':raise RuntimeError('Role preparation is first initialization only')
        if sql(root,"SELECT count(*) FROM pg_roles WHERE rolname='law_schema_migrator'")!='0':
            raise RuntimeError('Role preparation already has effects; reconcile original phase')
        password=(root/'secrets/migrator.txt').read_text()
        if not re.fullmatch('[A-Za-z0-9_-]{20,100}',password):raise RuntimeError('Invalid controlled migrator secret')
        journal.record(root,operation_id,{'phase':'ROLES_PREPARING'})
        statement=f"BEGIN; CREATE ROLE law_schema_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{password}'; ALTER DATABASE law_contract_runtime OWNER TO law_schema_migrator;"
        for role in ['law_app_command','law_app_query','law_app_worker','law_audit_append']:
            statement+=f' CREATE ROLE {role} NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;'
        sql(root,statement+' COMMIT;')
        journal.record(root,operation_id,{'phase':'ROLES_READY'})


def flyway(root: Path, operation_id: str, action: str, target: str|None=None) -> dict:
    if action not in {'validate','migrate'} or target not in {None,'1060','1070','1080'}:
        raise ValueError('Only controlled validation and reviewed forward targets are allowed')
    with journal.locked(root) as root:
        operation=journal.read(root,operation_id);resources=runtime.load(root);repo=Path(resources['repo'])
        verify_sources(repo)
        if operation['kind'] not in {'initialize','upgrade'}:raise RuntimeError('Migration is not a byte publication')
        if operation['kind']=='upgrade' and operation['phase'] not in {'CHECKPOINT_VERIFIED','MIGRATION_UNKNOWN','MIGRATED','SCHEMA_VERIFIED'}:
            raise RuntimeError('Upgrade requires its verified checkpoint and stopped writers')
        runtime.validate_tls(root)
        properties={'flyway.url':f'jdbc:postgresql://{resources["containers"]["businessDb"]}:5432/law_contract_runtime?sslmode=verify-full&sslrootcert=/run/ca.pem',
                    'flyway.user':'law_schema_migrator','flyway.password':(root/'secrets/migrator.txt').read_text(),
                    'flyway.defaultSchema':'platform_meta','flyway.schemas':'identity,audit,responsibility,execution,external_action,evidence,party,lead,opportunity,conflict,contract,transfer,platform_meta',
                    'flyway.locations':'filesystem:/flyway/sql','flyway.cleanDisabled':'true','flyway.baselineOnMigrate':'false',
                    'flyway.validateMigrationNaming':'true','flyway.connectRetries':'10'}
        # Existing-prefix preflight may have only the two reviewed successors pending.
        # The caller also verifies exact history, so this cannot admit arbitrary future SQL.
        applied=observe(root)['history']
        if action=='validate' and applied and [r['version'] for r in applied if r['version']]!=expected_versions(repo,target):
            properties['flyway.ignoreMigrationPatterns']='*:pending'
        for key,value in {'app_command_role':'law_app_command','app_query_role':'law_app_query','app_worker_role':'law_app_worker','audit_append_role':'law_audit_append'}.items():properties['flyway.placeholders.'+key]=value
        if target:properties['flyway.target']=target
        path=root/'flyway.conf';runtime.private_file(path,('\n'.join(k+'='+v for k,v in properties.items())+'\n').encode())
        journal.record(root,operation_id,{'phase':'MIGRATION_UNKNOWN' if action=='migrate' else operation['phase'],'flywayAction':action,'target':target})
        result=runtime.run(['docker','run','--rm','--user','0','--name',resources['name']+'-flyway','--label','ols.instance='+resources['instanceId'],
                            '--network',resources['network'],'--mount',f'type=bind,source={path},target=/run/flyway.conf,readonly',
                            '--mount',f'type=bind,source={root/"certs/ca.pem"},target=/run/ca.pem,readonly',
                            '--mount',f'type=bind,source={repo/GENERATED/"db/migration"},target=/flyway/sql,readonly',
                            resources['flywayImage'],'-configFiles=/run/flyway.conf',action],timeout=300,check=False)
        runtime.private_file(root/('flyway-'+action+'.log'),result.stdout+result.stderr)
        if result.returncode: raise RuntimeError('Flyway failed; private diagnostic and original operation retained at '+str(root))
        observed=observe(root)
        journal.record(root,operation_id,{'phase':'MIGRATED' if action=='migrate' else operation['phase'],'flywayAction':action,'observedVersions':[r['version'] for r in observed['history'] if r['version']]})
        return observed


def observe(root: Path, *, containers=None) -> dict:
    query=lambda statement:sql(root,statement,containers=containers)
    exists=query("SELECT to_regclass('platform_meta.flyway_schema_history') IS NOT NULL")=='t'
    if not exists:return {'gate':None,'history':[],'tables':[]}
    history=json.loads(query("SELECT coalesce(json_agg(h ORDER BY installed_rank),'[]'::json) FROM platform_meta.flyway_schema_history h"))
    gate_exists=query("SELECT to_regclass('platform_meta.deployment_state') IS NOT NULL")=='t'
    gate=json.loads(query("SELECT json_build_object('deployment_state_key',deployment_state_key,'operating_mode',operating_mode,'active_release_digest',encode(active_release_digest,'hex'),'active_manifest_hash',encode(active_manifest_hash,'hex'),'schema_contract_version',schema_contract_version,'revision',revision,'changed_at',changed_at) FROM platform_meta.deployment_state")) if gate_exists else None
    tables=json.loads(query("SELECT coalesce(json_agg(schemaname||'.'||tablename ORDER BY schemaname,tablename),'[]'::json) FROM pg_tables WHERE schemaname IN ('identity','audit','responsibility','execution','external_action','evidence','party','lead','opportunity','conflict','contract','transfer','platform_meta')"))
    return {'gate':gate,'history':history,'tables':tables}


def runtime_logins(root: Path, operation_id: str):
    with journal.locked(root) as root:
        resources=runtime.load(root);repo=Path(resources['repo'])
        if sql(root,"SELECT count(*) FROM pg_roles WHERE rolname IN ('law_api_login','law_worker_login')")!='0':
            raise RuntimeError('Application login preparation already began; preserve original state')
        journal.record(root,operation_id,{'phase':'APPLICATION_LOGINS_PREPARING'})
        statement=(repo/'backend/src/test/resources/db/bootstrap-runtime-logins.sql').read_text()
        for role,secret in [('law_api_login','api-db'),('law_worker_login','worker-db')]:
            password=(root/'secrets'/(secret+'.txt')).read_text()
            if not re.fullmatch('[A-Za-z0-9_-]{20,100}',password):raise RuntimeError('Invalid controlled database secret')
            statement+=f" ALTER ROLE {role} PASSWORD '{password}';"
        sql(root,'BEGIN; '+statement+' COMMIT;')
        journal.record(root,operation_id,{'phase':'APPLICATION_LOGINS_READY'})


def verify_schema(root: Path, target='1080') -> dict:
    resources=runtime.load(root);repo=Path(resources['repo']);observed=observe(root)
    history=observed['history'];versions=[r['version'] for r in history if r['version']]
    if versions!=expected_versions(repo,target) or not all(r['success'] for r in history):raise RuntimeError('Unexpected Flyway history')
    verify_sources(repo);verify_history(repo,history,target)
    expected='52-plus-2-r2-v22' if target=='1080' else '52-plus-2-r2-v21' if target=='1070' else '52-plus-2-r2-v20'
    if observed['gate']['schema_contract_version']!=expected:raise RuntimeError('Schema gate differs from actual migration target')
    if target=='1080':
        manifest=json.loads((repo/GENERATED/'schema-contract-manifest.json').read_text())
        if len(observed['tables'])!=manifest['physicalTableCountAfterFlywayBootstrap']:raise RuntimeError('Catalog table count differs')
        if sql(root,"SELECT count(*) FROM pg_constraint WHERE conrelid='identity.appointment'::regclass AND contype='f' AND confrelid='identity.appointment_role'::regclass")!='1':raise RuntimeError('Appointment role foreign key absent')
        if sql(root,"SELECT count(*) FROM pg_trigger WHERE tgrelid='identity.appointment'::regclass AND NOT tgisinternal AND tgenabled='O' AND pg_get_triggerdef(oid) LIKE '%INSERT%'")=='0':raise RuntimeError('Appointment insert guard absent')
    # These assertions roll back even the successful temporary function.
    assertion="BEGIN; DO $$ BEGIN "
    for role in ['law_app_command','law_app_query','law_app_worker','law_audit_append']:
        assertion+=f"EXECUTE 'SET LOCAL ROLE {role}'; BEGIN EXECUTE 'CREATE TABLE identity.forbidden_linux_ddl(id int)'; RAISE EXCEPTION 'DDL permitted'; EXCEPTION WHEN insufficient_privilege THEN NULL; END; RESET ROLE; "
    assertion+="END $$; ROLLBACK;"
    sql(root,assertion)
    return observed
