"""Linked, private backups; restoration is proved in a separate registered instance."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from . import database,journal,runtime
from .bundle import inventory,sha
from .config import digest

EXCLUDED={'platform_meta.deployment_state','platform_meta.flyway_schema_history'}
INSTANCE_CONTROLS={'instance.json','instance.lock','journal.key','resources.json','current-operation.json','operations','checkpoints','quarantine','restore-plan.json'}


def role_restore_sql(raw: bytes) -> bytes:
    lines=raw.splitlines(keepends=True)
    if sum(line.strip()==b'CREATE ROLE "postgres";' for line in lines)!=1:raise RuntimeError('Unexpected quoted role dump format')
    return b''.join(line for line in lines if line.strip()!=b'CREATE ROLE "postgres";')


def table_facts(root: Path,identity=False, *, containers=None) -> dict:
    tables=json.loads(database.sql(root,"SELECT coalesce(json_agg(schemaname||'.'||tablename ORDER BY schemaname,tablename),'[]'::json) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') AND schemaname NOT LIKE 'pg_toast%'",identity=identity,containers=containers))
    queries=[]
    for name in tables:
        if not identity and name in EXCLUDED:continue
        if not re.fullmatch(r'[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*',name):raise RuntimeError('Unsupported catalog identifier')
        queries.append(f"SELECT '{name}'::text AS name,(SELECT coalesce(json_agg(to_jsonb(t)),'[]'::json) FROM {name} t) AS rows")
    if not queries:return {}
    batches=json.loads(database.sql(root,"SET TIME ZONE 'UTC'; SELECT json_agg(q ORDER BY name) FROM ("+' UNION ALL '.join(queries)+") q",identity=identity,containers=containers))
    result={}
    for batch in batches:
        rows=batch['rows']
        result[batch['name']]={'count':len(rows),'digest':digest(sorted(rows,key=lambda v:json.dumps(v,sort_keys=True,separators=(',',':'))))}
    return result


def assert_preserved(before: dict,after: dict):
    if any(after.get(name)!=fact for name,fact in before.items()):raise RuntimeError('Existing row facts changed; publication blocked')


def cluster_facts(root: Path, *, containers=None) -> dict:
    result={}
    for key,identity,db in [('businessDb',False,'law_contract_runtime'),('identityDb',True,'keycloak')]:
        owner=database.sql(root,f"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='{db}'",identity=identity,containers=containers)
        roles=json.loads(database.sql(root,"SELECT json_agg(json_build_object('name',rolname,'super',rolsuper,'inherit',rolinherit,'createRole',rolcreaterole,'createDb',rolcreatedb,'canLogin',rolcanlogin,'replication',rolreplication,'bypassRls',rolbypassrls,'limit',rolconnlimit,'password',rolpassword,'validUntil',rolvaliduntil) ORDER BY rolname) FROM pg_authid WHERE rolname NOT LIKE 'pg_%'",identity=identity,containers=containers))
        members=json.loads(database.sql(root,"SELECT coalesce(json_agg(json_build_object('role',r.rolname,'member',m.rolname,'grantor',g.rolname,'admin',a.admin_option,'inherit',a.inherit_option,'set',a.set_option) ORDER BY r.rolname,m.rolname,g.rolname),'[]'::json) FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.roleid JOIN pg_roles m ON m.oid=a.member JOIN pg_roles g ON g.oid=a.grantor",identity=identity,containers=containers))
        acl=json.loads(database.sql(root,f"SELECT coalesce(json_agg(json_build_object('grantee',CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,'grantor',pg_get_userbyid(a.grantor),'privilege',a.privilege_type,'grantable',a.is_grantable) ORDER BY a.grantee=0,pg_get_userbyid(a.grantee),pg_get_userbyid(a.grantor),a.privilege_type),'[]'::json) FROM pg_database d CROSS JOIN LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a WHERE d.datname='{db}'",identity=identity,containers=containers))
        result[key]={'databaseOwner':owner,'rolesDigest':digest(roles),'membersDigest':digest(members),'databaseAclDigest':digest(acl)}
    return result


def assert_cluster_facts(expected,actual):
    if set(expected)!=set(actual):raise RuntimeError('Restored clusters differ')
    for key,value in expected.items():
        # File-only version 1 backups predate the explicit ACL digest. Their
        # original archive still supplies the exact DATABASE privilege SQL.
        if set(value) not in ({'databaseOwner','rolesDigest','membersDigest'},{'databaseOwner','rolesDigest','membersDigest','databaseAclDigest'}):raise RuntimeError('Unsupported checkpoint cluster facts')
        if any(actual[key].get(name)!=fact for name,fact in value.items()):raise RuntimeError('Restored ownership, roles or database ACL differ')


def database_acl_sql(text,db):
    if db not in {'law_contract_runtime','keycloak'}:raise RuntimeError('Unknown restore database')
    statements=[]
    for line in text.splitlines():
        if ' ON DATABASE ' not in line:continue
        match=re.fullmatch(r'(REVOKE|GRANT) ((?:CONNECT|CREATE|TEMPORARY)(?:,(?:CONNECT|CREATE|TEMPORARY))*) ON DATABASE '+db+r' (FROM|TO) (PUBLIC|[a-z][a-z0-9_]*)( WITH GRANT OPTION)?;',line)
        if not match or (match[1]=='REVOKE')!=(match[3]=='FROM'):raise RuntimeError('Unsupported original database ACL statement')
        statements.append(line)
    return '\n'.join(statements)+'\n'


def restore_database_acl(target,directory,*,containers=None):
    resources=runtime.load(target)
    for key,db in [('businessDb','law_contract_runtime'),('identityDb','keycloak')]:
        name=(containers if containers is not None else resources['containers'])[key]
        if name not in resources['containers'].values():raise RuntimeError('Unregistered database ACL target')
        runtime.owned(target,'container',name)
        # pg_restore without --create omits DATABASE ACL, although the archive
        # records it. Read that original SQL; never execute CREATE or schema SQL.
        result=runtime.run(['docker','exec','-i',name,'pg_restore','--create','--schema-only','--file','-'],(directory/(key+'.dump')).read_bytes(),timeout=300)
        sql=database_acl_sql(result.stdout.decode('utf-8'),db)
        if sql.strip():runtime.run(['docker','exec','-i',name,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],('BEGIN;\n'+sql+'COMMIT;\n').encode())


def restore_owners_and_acl(target,directory,expected,*,containers=None):
    if set(expected)!={'businessDb','identityDb'}:raise RuntimeError('Both original database owners required')
    owners={key:value['databaseOwner'] for key,value in expected.items()}
    if any(not re.fullmatch('[a-z][a-z0-9_]*',owner) for owner in owners.values()):raise RuntimeError('Unsupported original database owner')
    actual=cluster_facts(target,containers=containers)
    if any(actual[key]['databaseOwner'] not in {'postgres',owner} for key,owner in owners.items()):raise RuntimeError('Original restore database ownership conflicts')
    for key,identity,db in [('businessDb',False,'law_contract_runtime'),('identityDb',True,'keycloak')]:
        if actual[key]['databaseOwner']!=owners[key]:database.sql(target,f'ALTER DATABASE {db} OWNER TO {owners[key]}',identity=identity,containers=containers)
    # ALTER OWNER can replace the default ACL. Apply the bounded SQL from the
    # same original archive afterwards, before checking the exact ACL digest.
    restore_database_acl(target,directory,containers=containers)


def _directory(root,operation_id):
    journal.read(root,operation_id)
    directory=root/'checkpoints'/operation_id
    if directory.resolve()!=directory.absolute() or not directory.is_relative_to(root):raise RuntimeError('Linked checkpoint directory escapes instance')
    return directory


def read(root: Path,operation_id: str) -> dict:
    with journal.locked(root) as root:
        directory=_directory(root,operation_id)
        value=journal._read(root,directory/'checkpoint.json')
        if value['operationId']!=operation_id or value['instanceId']!=journal._owner(root)['instanceId']:raise RuntimeError('Foreign checkpoint')
        actual=inventory(directory)
        actual.pop('checkpoint.json',None);actual.pop('restore-proof.json',None)
        if actual!=value['files']:raise RuntimeError('Checkpoint bytes differ or are incomplete')
        if 'assetDirectories' in value and asset_directories(directory/'assets')!=value['assetDirectories']:
            raise RuntimeError('Checkpoint asset directories differ')
        return value


def _dump(root: Path,path: Path,args: list[str]):
    if path.exists() or path.is_symlink():raise RuntimeError('Existing backup is never overwritten')
    path.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    try:
        with os.fdopen(fd,'wb') as out:
            result=subprocess.run(args,stdout=out,stderr=subprocess.PIPE,timeout=300,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            out.flush();os.fsync(out.fileno())
        if result.returncode:raise RuntimeError('Backup failed; partial bytes retained')
    except (OSError,subprocess.TimeoutExpired) as error:raise RuntimeError('Backup result unknown; original checkpoint retained') from error


def _copy(source: Path,target: Path):
    if source.resolve()!=source.absolute():raise RuntimeError('Linked checkpoint input refused')
    if source.is_dir():
        inventory(source)
        if target.resolve()!=target.absolute():raise RuntimeError('Linked checkpoint target refused')
        target.mkdir(mode=0o700,parents=True,exist_ok=True)
        for child in source.rglob('*'):
            destination=target/child.relative_to(source)
            if child.is_dir():destination.mkdir(mode=0o700,parents=True,exist_ok=True)
            elif child.is_file():_copy(child,destination)
    elif source.is_file():
        if target.exists() and (target.resolve()!=target.absolute() or not target.is_file() or sha(target)!=sha(source)):
            raise RuntimeError('Original restore asset differs; never overwritten')
        runtime.private_file(target,source.read_bytes())


def asset_directories(directory):
    inventory(directory)
    return sorted(path.relative_to(directory).as_posix() for path in directory.rglob('*') if path.is_dir())


def capture(root: Path,operation_id: str) -> dict:
    with journal.locked(root) as root:
        op=journal.read(root,operation_id)
        if op['kind'] not in {'upgrade','publish-bytes'} or op['phase']!='WRITERS_STOPPED':raise RuntimeError('Checkpoint requires observed stopped writers')
        runtime.stop_writers(root,operation_id)
        directory=_directory(root,operation_id)
        if directory.exists():raise RuntimeError('Original checkpoint already has effects; verify original bytes')
        directory.mkdir(mode=0o700,parents=True)
        resources=runtime.load(root);observed=database.observe(root)
        facts=table_facts(root);identity_facts=table_facts(root,True)
        journal.record(root,operation_id,{'phase':'CHECKPOINT_CAPTURING','gate':observed['gate']})
        for key,db in [('businessDb','law_contract_runtime'),('identityDb','keycloak')]:
            name=resources['containers'][key];runtime.owned(root,'container',name)
            _dump(root,directory/(key+'.dump'),['docker','exec',name,'pg_dump','-U','postgres','-d',db,'-Fc'])
            _dump(root,directory/(key+'-roles.sql'),['docker','exec',name,'pg_dumpall','-U','postgres','--roles-only','--quote-all-identifiers'])
        controls=INSTANCE_CONTROLS
        for path in root.iterdir():
            if path.name in controls or path.name.endswith('.log') or path.name=='flyway.conf':continue
            _copy(path,directory/'assets'/path.name)
        value={'version':1,'operationId':operation_id,'instanceId':resources['instanceId'],'observed':observed,'clusterFacts':cluster_facts(root),
               'businessFacts':facts,'identityFacts':identity_facts,'files':inventory(directory),'assetDirectories':asset_directories(directory/'assets')}
        journal._write(root,directory/'checkpoint.json',value)
        journal.record(root,operation_id,{'phase':'CHECKPOINT_CAPTURED','checkpointDigest':digest(value)})
        return value


def _restore_databases(target: Path,directory: Path, *, containers=None,value=None):
    # Each cluster is reconciled independently. A successful first database is
    # never evidence that the second database or its roles were restored.
    if value is None:value=journal._read(directory.parents[1],directory/'checkpoint.json')
    resources=runtime.load(target)
    selected=containers if containers is not None else resources['containers']
    binding={'checkpointDigest':digest(value),'containers':{key:selected[key] for key in ['businessDb','identityDb']},
             'archives':{key:sha(directory/key) for key in ['businessDb.dump','identityDb.dump','businessDb-roles.sql','identityDb-roles.sql']}}
    state_path=target/'operations'/(journal.current(target)['operationId']+'-databases-'+digest(binding)+'.json')
    if state_path.exists():
        state=journal._read(target,state_path)
        if state['binding']!=binding:raise RuntimeError('Original database restore binding changed')
    else:state={'binding':binding,'rolesBefore':{}}
    for key,db in [('businessDb','law_contract_runtime'),('identityDb','keycloak')]:
        name=selected[key];identity=key=='identityDb'
        if name not in resources['containers'].values():raise RuntimeError('Unregistered restore database')
        runtime.owned(target,'container',name)
        expected=value['identityFacts' if identity else 'businessFacts']
        actual=table_facts(target,identity,containers=selected)
        observed=database.observe(target,containers=selected) if not identity else None
        complete=actual==expected and (identity or observed==value['observed'])
        roles=lambda facts:{k:facts[key][k] for k in ['rolesDigest','membersDigest']}
        original_roles=roles(value['clusterFacts']);actual_roles=roles(cluster_facts(target,containers=selected))
        if complete:
            if actual_roles!=original_roles:raise RuntimeError('Completed original database roles differ')
            continue
        if actual or (observed and observed['history']):raise RuntimeError('Partial or conflicting original restore retained')
        if actual_roles!=original_roles:
            if key not in state['rolesBefore']:
                state['rolesBefore'][key]=actual_roles;journal._write(target,state_path,state)
            elif actual_roles!=state['rolesBefore'][key]:raise RuntimeError('Unknown original role restore conflicts; target retained')
            # PostgreSQL role DDL is transactional. An interrupted import is
            # either the exact before state or the original complete role state.
            runtime.run(['docker','exec','-i',name,'psql','-X','--single-transaction','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],role_restore_sql((directory/(key+'-roles.sql')).read_bytes()))
            if roles(cluster_facts(target,containers=selected))!=original_roles:raise RuntimeError('Original restored roles differ')
        # New target databases are empty; no clean/repair/down migration is used.
        with (directory/(key+'.dump')).open('rb') as source:
            result=subprocess.run(['docker','exec','-i',name,'pg_restore','-U','postgres','-d',db,'--single-transaction','--exit-on-error'],stdin=source,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        if result.returncode:raise RuntimeError('Checkpoint database restore failed; isolated target retained')
        if table_facts(target,identity,containers=selected)!=expected or (not identity and database.observe(target,containers=selected)!=value['observed']):
            raise RuntimeError('Original database restore facts differ; target retained')
    restore_database_acl(target,directory,containers=containers)


def proof_target(root,operation_id):
    owner=journal._owner(root)
    target=root.parent/('.ols-proof-'+owner['instanceId']+'-'+operation_id)
    if target.resolve()!=target.absolute() or target.exists():raise RuntimeError('Original durable proof target is occupied or linked')
    return target


def verify_restore(root: Path,operation_id: str) -> dict:
    with journal.locked(root) as root:
        value=read(root,operation_id);directory=_directory(root,operation_id)
        if (directory/'restore-proof.json').exists():
            verified(root,operation_id)
            proof=journal._read(root,directory/'restore-proof.json')
            if journal.read(root,operation_id)['phase'] in {'CHECKPOINT_CAPTURED','CHECKPOINT_RESTORING_PROOF','CHECKPOINT_VERIFIED'}:
                journal.record(root,operation_id,{'phase':'CHECKPOINT_VERIFIED','checkpointDigest':digest(value),'restoreProofDigest':digest(proof)})
            return proof
        previous=[event for event in journal.read(root,operation_id)['events'] if event['phase']=='CHECKPOINT_RESTORING_PROOF']
        if previous:
            target=Path(previous[-1]['target']);target_op=journal.current(target)
            if target_op['instanceId']!=previous[-1]['targetInstanceId'] or target_op['configDigest']!=digest(value):raise RuntimeError('Original restore target differs')
        else:
            target=proof_target(root,operation_id)
            target_op=journal.begin(target,'initialize',digest(value))
            _copy(directory/'assets/certs',target/'certs');_copy(directory/'assets/secrets',target/'secrets')
        resources=runtime.load(root)
        name='ols-proof-'+target_op['instanceId'][:20]
        journal.record(root,operation_id,{'phase':'CHECKPOINT_RESTORING_PROOF','target':str(target),'targetInstanceId':target_op['instanceId']})
        proof_resources=runtime.prepare(target,{'name':name,'repo':resources['repo']})
        proof_resources['verification']=True;runtime.save(target,proof_resources)
        _restore_databases(target,directory,value=value)
        restore_owners_and_acl(target,directory,value['clusterFacts'])
        if database.observe(target)!=value['observed'] or table_facts(target)!=value['businessFacts'] or table_facts(target,True)!=value['identityFacts']:
            raise RuntimeError('Restored facts differ; isolated target retained')
        assert_cluster_facts(value['clusterFacts'],cluster_facts(target))
        # Material/config/secret/release bytes are checked independently of the database.
        _copy(directory/'assets',target/'restored-assets')
        expected={name[7:]:h for name,h in value['files'].items() if name.startswith('assets/')}
        if inventory(target/'restored-assets')!=expected:raise RuntimeError('Restored non-database bytes differ')
        if 'assetDirectories' in value and asset_directories(target/'restored-assets')!=value['assetDirectories']:
            raise RuntimeError('Restored non-database directories differ')
        proof={'checkpointDigest':digest(value),'targetInstanceId':target_op['instanceId'],'observed':value['observed'],'assetHashes':expected}
        journal._write(root,directory/'restore-proof.json',proof)
        runtime.cleanup_verification(target)
        journal.record(root,operation_id,{'phase':'CHECKPOINT_VERIFIED','checkpointDigest':digest(value),'restoreProofDigest':digest(proof)})
        return proof


def verified(root: Path,operation_id: str) -> dict:
    value=read(root,operation_id);directory=_directory(root,operation_id)
    proof=journal._read(root,directory/'restore-proof.json')
    if proof['checkpointDigest']!=digest(value) or proof['observed']!=value['observed']:raise RuntimeError('Linked restore proof differs')
    return value


def restore(root: Path,operation_id: str) -> dict:
    """Restore into replacement databases before switching the registered instance."""
    with journal.locked(root) as root:
        value=verified(root,operation_id)
        # Actual switching is completed with the release state machine; never restore only a JAR.
        from .release import restore_checkpoint
        return restore_checkpoint(root,operation_id,value)
