"""Schema transitions reconcile immutable original facts, never guess a revision."""
import json
import datetime
import os
import re
import zipfile
from pathlib import Path
from . import bundle,checkpoint,database,journal,runtime
from .config import canonical,digest


def require_same_schema(current: str,candidate: str):
    if current!=candidate:raise RuntimeError('Byte publication refuses schema changes; use the reviewed upgrade operation')


def reconcile_migrations(history: list,maintenance_gate: dict,observed: dict) -> str:
    actual=observed['history']
    if actual[:len(history)]!=history or not all(row['success'] for row in actual):raise RuntimeError('Original migration prefix or success state changed')
    suffix=[r['version'] for r in actual[len(history):]]
    targets={():('1060','52-plus-2-r2-v20'),('1070',):('1070','52-plus-2-r2-v21'),('1070','1080'):('1080','52-plus-2-r2-v22')}
    if tuple(suffix) not in targets:raise RuntimeError('Unexpected successor migration history')
    target,schema=targets[tuple(suffix)];gate=observed['gate']
    expected=dict(maintenance_gate,schema_contract_version=schema,revision=maintenance_gate['revision']+len(suffix))
    for name in ['schema_contract_version','revision','operating_mode','active_release_digest','active_manifest_hash']:
        if gate.get(name)!=expected.get(name):raise RuntimeError('Observed gate does not belong to this original migration')
    return target


def status(root: Path) -> dict:
    with journal.locked(root) as root:
        op=journal.current(root)
        return {'operationId':op['operationId'],'kind':op['kind'],'phase':op['phase'],'observed':database.observe(root)}


def _candidate(directory: Path) -> dict:
    directory=Path(directory).absolute()
    if directory.resolve()!=directory:raise RuntimeError('Linked candidate bundle rejected')
    descriptor=json.loads((directory/'release.json').read_text(encoding='utf-8'))
    bundle.verify(descriptor,directory)
    return descriptor


def publish_bytes(root: Path,bundle_dir: Path) -> dict:
    with journal.locked(root) as root:
        descriptor=_candidate(bundle_dir)
        require_same_schema(database.observe(root)['gate']['schema_contract_version'],descriptor['schemaVersion'])
        return _publish_same_schema(root,bundle_dir,descriptor)


def upgrade(root: Path,bundle_dir: Path) -> dict:
    with journal.locked(root) as root:
        descriptor=_candidate(bundle_dir);observed=database.verify_schema(root,'1060')
        if observed['gate']['operating_mode']!='ACTIVE':raise RuntimeError('New upgrades require verified ACTIVE v20')
        baseline=_installed(root,observed['gate'])
        op=journal.begin(root,'upgrade',descriptor['descriptorDigest'])
        journal._write(root,root/'operations'/(op['operationId']+'-inputs.json'),{
            'bundleDirectory':str(Path(bundle_dir).absolute()),'descriptor':descriptor,'before':observed,'baseline':baseline})
        return resume(root,op['operationId'])


def resume(root: Path,operation_id: str) -> dict:
    with journal.locked(root) as root:
        op=journal.read(root,operation_id)
        if journal.current(root)['operationId']!=operation_id or op['kind'] not in {'upgrade','publish-bytes'}:raise RuntimeError('Only the current original publication may resume')
        if op['phase']=='COMPLETE':return status(root)
        if (root/'restore-plan.json').exists() and journal._read(root,root/'restore-plan.json')['operationId']==operation_id:
            raise RuntimeError('Original operation entered linked restore; continue its explicit restore command')
        inputs=journal._read(root,root/'operations'/(operation_id+'-inputs.json'))
        descriptor=_candidate(Path(inputs['bundleDirectory']))
        if descriptor!=inputs['descriptor'] or descriptor['descriptorDigest']!=op['configDigest']:raise RuntimeError('Original candidate changed')
        return _advance(root,op,inputs)


def _advance(root: Path,op: dict,inputs: dict) -> dict:
    operation_id=op['operationId'];phase=op['phase']
    if phase in {'CREATED','MAINTENANCE_UNKNOWN'}:
        _enter_maintenance(root,operation_id,inputs)
        phase=journal.current(root)['phase']
    if phase in {'MAINTENANCE','WRITERS_STOPPED','CHECKPOINT_CAPTURING'}:
        runtime.stop_writers(root,operation_id)
        directory=root/'checkpoints'/operation_id
        if not (directory/'checkpoint.json').exists():checkpoint.capture(root,operation_id)
        checkpoint.verify_restore(root,operation_id)
        phase=journal.current(root)['phase']
    if phase in {'CHECKPOINT_CAPTURED','CHECKPOINT_RESTORING_PROOF'}:
        checkpoint.verify_restore(root,operation_id);phase=journal.current(root)['phase']
    if phase in {'CHECKPOINT_VERIFIED','MIGRATION_UNKNOWN','MIGRATED','SCHEMA_VERIFIED'}:
        cp=checkpoint.verified(root,operation_id)
        if op['kind']=='publish-bytes':
            observed=_same_schema_verified(root)
            if observed['history']!=cp['observed']['history'] or not _same_gate(observed['gate'],cp['observed']['gate']):raise RuntimeError('Same-schema publication gate or history changed')
        else:
            sources={'source':Path(inputs['bundleDirectory'])} if inputs['descriptor'].get('version')==2 else {}
            target=reconcile_migrations(cp['observed']['history'],cp['observed']['gate'],database.observe(root))
            if target!='1080':
                database.flyway(root,operation_id,'validate',**sources)
                database.flyway(root,operation_id,'migrate',**sources)
            database.flyway(root,operation_id,'validate',**sources)
            observed=database.verify_schema(root,**sources)
            reconcile_migrations(cp['observed']['history'],cp['observed']['gate'],observed)
        checkpoint.assert_preserved(cp['businessFacts'],checkpoint.table_facts(root))
        journal.record(root,operation_id,{'phase':'SCHEMA_VERIFIED','gate':observed['gate']})
        phase='SCHEMA_VERIFIED'
    if phase in {'SCHEMA_VERIFIED','INSTALL_UNKNOWN'}:
        journal.record(root,operation_id,{'phase':'INSTALL_UNKNOWN','descriptorDigest':inputs['descriptor']['descriptorDigest']})
        _install_bundle(root,Path(inputs['bundleDirectory']),inputs['descriptor'])
        journal.record(root,operation_id,{'phase':'BUNDLE_INSTALLED','descriptorDigest':inputs['descriptor']['descriptorDigest']})
        phase='BUNDLE_INSTALLED'
    if phase in {'BUNDLE_INSTALLED','ACTIVATION_UNKNOWN','ACTIVATION_FAILING','ACTIVATION_FAILED','RUNTIME_VERIFIED','INGRESS_OPEN'}:
        if phase=='BUNDLE_INSTALLED' and inputs['descriptor'].get('version')==2:
            from .deployment import bind_release
            bind_release(root,inputs['descriptor'])
        return _activate(root,operation_id,inputs)
    raise RuntimeError('Original operation requires explicit reconciliation of its recorded phase')


def _install_bundle(root: Path,source: Path,descriptor: dict) -> Path:
    directory=root/'releases'/descriptor['descriptorDigest']
    if directory.resolve()!=directory:raise RuntimeError('Linked installed release rejected')
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    files=dict(descriptor['files'])
    files.update({descriptor['spa']+'/'+name:h for name,h in descriptor['spaFiles'].items()})
    files.update({bundle.GENERATED+'/db/migration/'+name:h for name,h in descriptor['migrations'].items()})
    unexpected=set(bundle.inventory(directory))-set(files)-{'release.json'}
    if unexpected:raise RuntimeError('Unexpected bytes in original installation directory')
    for name,expected in files.items():
        target=directory/name
        if target.exists():
            if target.resolve()!=target.absolute() or bundle.sha(target)!=expected:raise RuntimeError('Original install bytes differ')
        else:runtime.private_file(target,(source/name).read_bytes())
    release_file=directory/'release.json'
    if release_file.exists() and json.loads(release_file.read_text(encoding='utf-8'))!=descriptor:raise RuntimeError('Installed descriptor changed')
    runtime.private_file(release_file,canonical(descriptor))
    bundle.verify(descriptor,directory)
    journal._write(root,root/'installed-candidate.json',{'directory':str(directory),'descriptor':descriptor})
    return directory


def _same_gate(first: dict,second: dict) -> bool:
    left=dict(first);right=dict(second)
    for value in [left,right]:
        if value.get('changed_at'):value['changed_at']=datetime.datetime.fromisoformat(value['changed_at']).astimezone(datetime.timezone.utc)
    return left==right


def _cas_gate(root: Path,old: dict,new: dict) -> dict:
    expected={'deployment_state_key','operating_mode','active_release_digest','active_manifest_hash','schema_contract_version','revision','changed_at'}
    if set(old)!=expected or set(new)!=expected or old['deployment_state_key']!='PRIMARY' or new['deployment_state_key']!='PRIMARY' or new['revision']!=old['revision']+1 or new['schema_contract_version']!=old['schema_contract_version']:
        raise RuntimeError('Exact same-schema gate CAS required')
    literal=lambda value:"'"+str(value).replace("'","''")+"'"
    clauses=[]
    for key in expected:
        if key in {'active_release_digest','active_manifest_hash'}:clauses.append(f"{key}=decode({literal(old[key])},'hex')")
        elif key=='changed_at':clauses.append(f"{key}={literal(old[key])}::timestamptz")
        else:clauses.append(f"{key}={literal(old[key])}")
    setters=[f"{key}=decode({literal(new[key])},'hex')" for key in ['active_release_digest','active_manifest_hash']]
    setters += [f"operating_mode={literal(new['operating_mode'])}",f"revision={new['revision']}",f"changed_at={literal(new['changed_at'])}::timestamptz"]
    statement="BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; DO $gate$ DECLARE n integer; BEGIN UPDATE platform_meta.deployment_state SET "+','.join(setters)+' WHERE '+' AND '.join(clauses)+"; GET DIAGNOSTICS n=ROW_COUNT; IF n<>1 THEN RAISE EXCEPTION 'gate CAS conflict'; END IF; END $gate$; COMMIT;"
    database.sql(root,statement)
    actual=database.observe(root)['gate']
    if not _same_gate(actual,new):raise RuntimeError('Gate result differs; original operation retained')
    return actual


def _enter_maintenance(root: Path,operation_id: str,inputs: dict):
    old=inputs['before']['gate']
    transition=root/'operations'/(operation_id+'-maintenance.json')
    if transition.exists():new=journal._read(root,transition)
    else:
        new=dict(old,operating_mode='MAINTENANCE',revision=old['revision']+1,changed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds'))
        journal._write(root,transition,new)
    journal.record(root,operation_id,{'phase':'MAINTENANCE_UNKNOWN','expectedGate':new})
    actual=database.observe(root)['gate']
    if _same_gate(actual,old):actual=_cas_gate(root,old,new)
    elif not _same_gate(actual,new):raise RuntimeError('Original maintenance CAS conflicts with observed gate')
    journal.record(root,operation_id,{'phase':'MAINTENANCE','gate':actual})


def _installed(root: Path,gate: dict) -> dict:
    try:
        pointer=journal._read(root,root/'current-release.json')
        directory=Path(pointer['directory']);descriptor=pointer['descriptor']
        if directory.resolve()!=directory.absolute() or directory.parent!=root/'releases' or directory.name!=descriptor['descriptorDigest']:
            raise RuntimeError('Installed release path differs')
        if descriptor['schemaVersion']!=gate['schema_contract_version'] or descriptor['manifestHash']!=gate['active_manifest_hash'] or descriptor['files'][descriptor['jar']]!=gate['active_release_digest']:
            raise RuntimeError('Installed release does not match the database gate')
        if descriptor['schemaVersion']=='52-plus-2-r2-v22':bundle.verify(descriptor,directory)
        else:_verify_legacy(descriptor,directory)
        return pointer
    except (KeyError,TypeError,OSError,ValueError) as error:raise RuntimeError('Verified installed baseline unavailable') from error


def _verify_legacy(descriptor: dict,directory: Path):
    unsigned=dict(descriptor);seal=unsigned.pop('descriptorDigest')
    if seal!=digest(unsigned) or descriptor['schemaVersion']!='52-plus-2-r2-v20' or not re.fullmatch('[a-f0-9]{40}',descriptor['commit']):raise RuntimeError('Invalid frozen v20 baseline')
    frozen=json.loads((Path(__file__).resolve().parents[1]/'config/v20-migration-hashes.json').read_text(encoding='utf-8'))
    if descriptor['migrations']!=frozen or bundle.inventory(directory/bundle.GENERATED/'db/migration')!=frozen:raise RuntimeError('Frozen v20 prefix differs')
    for name,expected in descriptor['files'].items():
        path=directory/name
        if path.resolve()!=path.absolute() or not path.is_relative_to(directory) or not path.is_file() or bundle.sha(path)!=expected:raise RuntimeError('Frozen v20 baseline file differs')
    manifest=directory/bundle.GENERATED/'schema-contract-manifest.json'
    if bundle.sha(manifest)!=descriptor['manifestHash'] or json.loads(manifest.read_text(encoding='utf-8'))['contractVersion']!=descriptor['schemaVersion']:raise RuntimeError('Old manifest is not v20')
    jar=directory/descriptor['jar']
    try:
        with zipfile.ZipFile(jar) as archive:
            if archive.read('BOOT-INF/classes/schema-contract/schema-contract-manifest.json')!=manifest.read_bytes() or json.loads(archive.read('BOOT-INF/classes/schema-contract/build-source.json'))!={'commit':descriptor['commit'],'manifestHash':descriptor['manifestHash']} or b'JarLauncher' not in archive.read('META-INF/MANIFEST.MF'):
                raise RuntimeError('Legacy JAR build evidence differs')
    except (KeyError,zipfile.BadZipFile):raise RuntimeError('Executable frozen legacy JAR unavailable')
    if bundle.inventory(directory/descriptor['spa'])!=descriptor['spaFiles'] or 'index.html' not in descriptor['spaFiles']:raise RuntimeError('Legacy SPA differs')
    proof=json.loads((directory/'.artifacts/linux-build-proof.json').read_text(encoding='utf-8'))
    if proof!={'commit':descriptor['commit'],'jarExitCode':0,'spaExitCode':0,'jarSha256':bundle.sha(jar),'spaFiles':descriptor['spaFiles']}:raise RuntimeError('Old successful build evidence unavailable')


def _same_schema_verified(root):
    version=database.observe(root)['gate']['schema_contract_version']
    target={'52-plus-2-r2-v20':'1060','52-plus-2-r2-v22':'1080'}.get(version)
    if target is None:raise RuntimeError('Byte publication refuses intermediate or unknown schema')
    return database.verify_schema(root,target)


def _publish_same_schema(root: Path,bundle_dir: Path,descriptor: dict) -> dict:
    observed=_same_schema_verified(root)
    if observed['gate']['operating_mode']!='ACTIVE':raise RuntimeError('Byte publication requires verified ACTIVE runtime')
    baseline=_installed(root,observed['gate'])
    op=journal.begin(root,'publish-bytes',descriptor['descriptorDigest'])
    journal._write(root,root/'operations'/(op['operationId']+'-inputs.json'),{'bundleDirectory':str(Path(bundle_dir).absolute()),'descriptor':descriptor,'before':observed,'baseline':baseline})
    return resume(root,op['operationId'])


def _activate(root: Path,operation_id: str,inputs: dict, *, restored=False) -> dict:
    descriptor=inputs['descriptor'];activation_file=root/'operations'/(operation_id+('-restored-activation.json' if restored else '-activation.json'))
    if journal.read(root,operation_id)['phase']=='ACTIVATION_FAILING':
        activation=journal._read(root,activation_file)
        if activation['descriptorDigest']!=descriptor['descriptorDigest']:raise RuntimeError('Original failing activation differs')
        _finish_activation_failure(root,operation_id,descriptor,activation_file,activation)
    journal.record(root,operation_id,{'phase':'ACTIVATION_UNKNOWN','descriptorDigest':descriptor['descriptorDigest']})
    if not (root/'launch.json').exists():raise RuntimeError('Prepared native Linux runtime configuration unavailable; ingress stays closed')
    launch=journal._read(root,root/'launch.json')
    if launch['descriptorDigest']!=descriptor['descriptorDigest']:
        # The trusted initialization assembly owns property/secret/UUID rebinding.
        # It never imports a user-provided command or replays the bootstrap command.
        try:from .initialize import bind_release
        except ImportError:raise RuntimeError('Native runtime configuration binding unavailable; ingress stays closed')
        bind_release(root,descriptor)
        launch=journal._read(root,root/'launch.json')
        if launch['descriptorDigest']!=descriptor['descriptorDigest']:raise RuntimeError('Prepared runtime release differs')
    candidate=journal._read(root,root/'installed-candidate.json')
    if candidate['descriptor']!=descriptor:raise RuntimeError('Installed candidate differs')
    bundle.verify(descriptor,Path(candidate['directory']))
    if activation_file.exists():activation=journal._read(root,activation_file)
    else:
        old=database.observe(root)['gate']
        if old['schema_contract_version']!=descriptor['schemaVersion'] or old['operating_mode']!='MAINTENANCE':raise RuntimeError('Activation requires the verified maintained schema')
        new=dict(old,operating_mode='ACTIVE',active_release_digest=descriptor['files'][descriptor['jar']],active_manifest_hash=descriptor['manifestHash'],revision=old['revision']+1,changed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds'))
        activation={'before':old,'expected':new,'descriptorDigest':descriptor['descriptorDigest']}
        journal._write(root,activation_file,activation)
    actual=database.observe(root)['gate']
    if activation.get('failed') and _same_gate(actual,activation['failed']):
        activation['before']=actual;activation['expected']=dict(actual,operating_mode='ACTIVE',revision=actual['revision']+1,changed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds'))
        activation.pop('failed');journal._write(root,activation_file,activation)
    if _same_gate(actual,activation['expected']):pass
    elif _same_gate(actual,activation['before']):actual=_cas_gate(root,actual,activation['expected'])
    else:raise RuntimeError('Original activation gate conflicts')
    try:
        if restored:
            import time
            from . import tls_restore
            tls_restore.activate(root,now=int(time.time()))
        runtime.start_internal(root,descriptor)
        try:from .verify import runtime_ready
        except ImportError:raise RuntimeError('Actual Linux runtime verification unavailable')
        evidence=runtime_ready(root,descriptor)
        if evidence.get('status')!='PASS':raise RuntimeError('Runtime readiness refused')
        journal._write(root,root/'current-release.json',candidate)
        journal.record(root,operation_id,{'phase':'RUNTIME_VERIFIED','descriptorDigest':descriptor['descriptorDigest'],'evidenceDigest':digest(evidence)})
        runtime.open_ingress(root,operation_id)
        if restored:tls_restore.open_verified(root,now=int(time.time()))
        from .verify import ingress_ready
        ingress_ready(root,descriptor)
        journal.record(root,operation_id,{'phase':'COMPLETE','descriptorDigest':descriptor['descriptorDigest']})
        return {'operationId':operation_id,'kind':journal.read(root,operation_id)['kind'],'phase':'COMPLETE','descriptorDigest':descriptor['descriptorDigest']}
    except Exception:
        journal.record(root,operation_id,{'phase':'ACTIVATION_FAILING','descriptorDigest':descriptor['descriptorDigest']})
        _finish_activation_failure(root,operation_id,descriptor,activation_file,activation)
        raise RuntimeError('Runtime activation failed; writers stopped, ingress closed, original operation retained') from None


def _finish_activation_failure(root,operation_id,descriptor,activation_file,activation):
    if (root/'operations'/(operation_id+'-restore-tls.json')).exists():
        from . import tls_restore
        tls_restore.close(root)
    runtime.stop_writers(root,operation_id,phase='ACTIVATION_FAILING')
    current=database.observe(root)['gate']
    if 'failed' not in activation:
        if not _same_gate(current,activation['expected']):raise RuntimeError('Activation failure gate conflicts; original state retained')
        activation['failed']=dict(current,operating_mode='BLOCKED',revision=current['revision']+1,changed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds'))
        journal._write(root,activation_file,activation)
    blocked=activation['failed']
    if _same_gate(current,activation['expected']):_cas_gate(root,current,blocked)
    elif not _same_gate(current,blocked):raise RuntimeError('Original failed activation gate conflicts')
    journal.record(root,operation_id,{'phase':'ACTIVATION_FAILED','gate':blocked,'descriptorDigest':descriptor['descriptorDigest']})


def _retain_completed_restore_plan(root,operation_id,checkpoint_digest):
    path=root/'restore-plan.json'
    if not path.exists():return
    plan=journal._read(root,path)
    if plan['operationId']==operation_id:
        if plan['checkpointDigest']!=checkpoint_digest:raise RuntimeError('Original linked restore differs')
        return
    previous=journal.read(root,plan['operationId'])
    if previous['phase']!='COMPLETE':raise RuntimeError('Another original restore is still pending')
    retained=root/'operations'/(previous['operationId']+'-restore-plan.json')
    if retained.exists() and journal._read(root,retained)!=plan:raise RuntimeError('Completed restore evidence conflicts')
    if retained.resolve()!=retained.absolute():raise RuntimeError('Linked completed restore evidence refused')
    path.replace(retained)


def restore_checkpoint(root: Path,source_operation_id: str,value: dict) -> dict:
    """Restore both databases into new owned volumes, verify, then switch names and assets."""
    with journal.locked(root) as root:
        cp_digest=digest(value);directory=root/'checkpoints'/source_operation_id
        current=journal.current(root)
        if current['phase']=='COMPLETE':current=journal.begin(root,'restore',cp_digest)
        elif current['operationId']!=source_operation_id and not(current['kind']=='restore' and current['configDigest']==cp_digest):
            raise RuntimeError('Another original operation is pending')
        operation_id=current['operationId'];_retain_completed_restore_plan(root,operation_id,cp_digest);plan_path=root/'restore-plan.json'
        if plan_path.exists():
            plan=journal._read(root,plan_path)
            if plan['operationId']!=operation_id or plan['checkpointDigest']!=cp_digest:raise RuntimeError('Original linked restore differs')
        else:
            resources=runtime.load(root);suffix=operation_id[:8]
            plan={'operationId':operation_id,'sourceOperationId':source_operation_id,'checkpointDigest':cp_digest,
                'before':database.observe(root),'sourceContainers':{k:resources['containers'][k] for k in ['businessDb','identityDb']},
                'sourceVolumes':list(resources['volumes'][:2]),'replacementContainers':{},'replacementVolumes':{},'quarantineContainers':{}}
            for key,short in [('businessDb','business'),('identityDb','identity')]:
                plan['replacementContainers'][key]=resources['name']+'-restore-'+short+'-'+suffix
                plan['replacementVolumes'][key]=resources['name']+'-restore-'+short+'-data-'+suffix
                plan['quarantineContainers'][key]=resources['name']+'-before-restore-'+short+'-'+suffix
                if any(runtime.inspect(kind,name) for kind,name in [('container',plan['replacementContainers'][key]),('container',plan['quarantineContainers'][key]),('volume',plan['replacementVolumes'][key])]):
                    raise RuntimeError('Linked restore target names already exist; not adopted')
            journal._write(root,plan_path,plan)
        from . import tls_restore
        tls_restore.prepare(root,value)
        tls_restore.close(root)
        source_gate=plan['before']['gate']
        if source_gate['operating_mode']!='MAINTENANCE' and current['phase'] not in {'RESTORE_REPLACEMENT_READY','RESTORE_SWITCH_UNKNOWN','RESTORE_ASSETS_UNKNOWN','RESTORED_MAINTENANCE'}:
            if 'sourceMaintenanceGate' not in plan:
                plan['sourceMaintenanceGate']=dict(source_gate,operating_mode='MAINTENANCE',revision=source_gate['revision']+1,changed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='microseconds'))
                journal._write(root,plan_path,plan)
            expected=plan['sourceMaintenanceGate']
            journal.record(root,operation_id,{'phase':'RESTORE_MAINTENANCE_UNKNOWN','expectedGate':expected})
            observed=database.observe(root)['gate']
            if _same_gate(observed,source_gate):_cas_gate(root,source_gate,expected)
            elif not _same_gate(observed,expected):raise RuntimeError('Original restore maintenance gate conflicts')
        if current['phase']=='RESTORED_MAINTENANCE':
            _assert_restored(root,value)
            return {'status':'RESTORED_MAINTENANCE','operationId':operation_id,'schemaVersion':value['observed']['gate']['schema_contract_version']}
        if current['phase'] not in {'RESTORE_REPLACEMENT_READY','RESTORE_SWITCH_UNKNOWN','RESTORE_ASSETS_UNKNOWN'}:
            runtime.stop_writers(root,operation_id)
            journal.record(root,operation_id,{'phase':'RESTORE_PREPARING','checkpointDigest':cp_digest})
            resources=runtime.load(root)
            for key in ['businessDb','identityDb']:
                resources['containers']['restore'+key]=plan['replacementContainers'][key]
                if plan['replacementVolumes'][key] not in resources['volumes']:resources['volumes'].append(plan['replacementVolumes'][key])
            runtime.save(root,resources)
            for key,db,secret in [('businessDb','law_contract_runtime','business-db.txt'),('identityDb','keycloak','identity-db.txt')]:
                volume=plan['replacementVolumes'][key]
                if runtime.inspect('volume',volume):runtime.owned(root,'volume',volume)
                else:runtime.run(['docker','volume','create','--label','ols.instance='+resources['instanceId'],'--label','ols.operation='+operation_id,volume])
                runtime.start_database(root,plan['replacementContainers'][key],volume,db,directory/'assets/secrets'/secret,directory/'assets/certs',operation_id)
            selected=plan['replacementContainers']
            journal.record(root,operation_id,{'phase':'RESTORE_DATA_UNKNOWN','checkpointDigest':cp_digest})
            checkpoint._restore_databases(root,directory,containers=selected,value=value)
            checkpoint.restore_owners_and_acl(root,directory,value['clusterFacts'],containers=selected)
            _assert_restored(root,value,containers=selected,assets=False)
            journal.record(root,operation_id,{'phase':'RESTORE_REPLACEMENT_READY','checkpointDigest':cp_digest})
        resources=runtime.load(root)
        journal.record(root,operation_id,{'phase':'RESTORE_SWITCH_UNKNOWN','checkpointDigest':cp_digest})
        for index,(key,db,secret) in enumerate([('businessDb','law_contract_runtime','business-db.txt'),('identityDb','keycloak','identity-db.txt')]):
            original=plan['sourceContainers'][key];quarantine=plan['quarantineContainers'][key]
            resources['containers']['beforeRestore'+key]=quarantine;runtime.save(root,resources)
            actual=runtime.inspect('container',original)
            if actual:
                actual=runtime.owned(root,'container',original)
                volume_names={m.get('Name') for m in actual['Mounts'] if m.get('Type')=='volume'}
                if plan['sourceVolumes'][index] in volume_names:
                    if runtime.inspect('container',quarantine):raise RuntimeError('Quarantine name conflicts with original database')
                    if actual['State']['Running']:runtime.run(['docker','stop','--time','15',original])
                    runtime.run(['docker','rename',original,quarantine])
                elif plan['replacementVolumes'][key] not in volume_names:raise RuntimeError('Database switch target differs')
            if runtime.inspect('container',quarantine):
                quarantined=runtime.owned(root,'container',quarantine)
                if resources['network'] in quarantined['NetworkSettings']['Networks']:
                    runtime.run(['docker','network','disconnect',resources['network'],quarantine])
            temporary=plan['replacementContainers'][key]
            if runtime.inspect('container',temporary):
                runtime.owned(root,'container',temporary);runtime.run(['docker','stop','--time','15',temporary]);runtime.run(['docker','rm',temporary])
            runtime.start_database(root,original,plan['replacementVolumes'][key],db,directory/'assets/secrets'/secret,directory/'assets/certs',operation_id,resources['ports'].get(key))
        live_volumes=[plan['replacementVolumes'][key] for key in ['businessDb','identityDb']]
        resources['volumes']=live_volumes+[volume for volume in resources['volumes'] if volume not in live_volumes]
        runtime.save(root,resources)
        journal.record(root,operation_id,{'phase':'RESTORE_ASSETS_UNKNOWN','checkpointDigest':cp_digest})
        _restore_assets(root,directory,operation_id,value)
        _assert_restored(root,value)
        if (root/'certs/public.crt').exists():
            import time
            from . import tls_generation
            tls_generation.restore_binding(root,value,now=int(time.time()))
        if (root/'launch.json').exists():_restore_runtime_registry(root)
        journal.record(root,operation_id,{'phase':'RESTORED_MAINTENANCE','checkpointDigest':cp_digest,'gate':value['observed']['gate']})
        return {'status':'RESTORED_MAINTENANCE','operationId':operation_id,'schemaVersion':value['observed']['gate']['schema_contract_version']}


def _restore_runtime_registry(root: Path):
    current=journal._read(root,root/'current-release.json');launch=journal._read(root,root/'launch.json')
    directory=Path(current['directory']);descriptor=current['descriptor']
    if directory.resolve()!=directory.absolute() or not directory.is_relative_to(root/'releases') or launch['descriptorDigest']!=descriptor['descriptorDigest']:
        raise RuntimeError('Restored release or launch does not belong to this instance')
    bundle.verify(descriptor,directory)
    resources=runtime.load(root);known=set(resources['containers'].values())|set(resources['writers'])
    if any(e['name'] not in known or e['name'] not in resources['writers'] for e in launch['containers']):
        raise RuntimeError('Restored writer was never registered; it is not adopted')
    ingress=launch.get('ingress')
    if ingress and ingress['name'] not in resources['containers'].values():raise RuntimeError('Restored ingress was never registered')
    for entry in launch['containers']:resources['containers'][entry['role']]=entry['name']
    # Compatibility with already verified older checkpoints whose file-only
    # inventories omitted an empty material store. Never replace any material.
    materials=root/'materials'
    if not materials.exists() and (root/'restore-plan.json').exists():
        plan=journal._read(root,root/'restore-plan.json');cp=checkpoint.verified(root,plan['sourceOperationId'])
        if any(name.startswith('assets/materials/') for name in cp['files']):raise RuntimeError('Original restored material bytes are missing')
        api=next((entry for entry in launch['containers'] if entry['role']=='api'),None)
        if api is None or 'type=bind,source='+str(materials)+',target='+str(materials) not in api['args']:
            raise RuntimeError('Original empty material store mount unavailable')
        materials.mkdir(mode=0o700)
    resources['repo']=str(directory);resources['ingress']=ingress['name'] if ingress else None
    if ingress:resources['containers']['entry']=ingress['name']
    runtime.save(root,resources)


def health(root: Path) -> dict:
    from . import verify
    with journal.locked(root) as root:
        current=journal._read(root,root/'current-release.json')
        bundle.verify(current['descriptor'],Path(current['directory']))
        return {'operationId':journal.current(root)['operationId'],'status':'PASS',
                'runtime':verify.runtime_ready(root,current['descriptor']),'ingress':verify.ingress_ready(root,current['descriptor'])}


def stop(root: Path) -> dict:
    with journal.locked(root) as root:
        op=journal.current(root)
        if op['phase'] not in {'COMPLETE','STOP_REQUESTED','WRITERS_STOPPED','STOPPED'} and not(op['kind']=='runtime-control' and op['phase']=='CREATED'):
            raise RuntimeError('Pending publication or restore must use its original continuation')
        current=journal._read(root,root/'current-release.json')
        bundle.verify(current['descriptor'],Path(current['directory']))
        if op['phase']=='COMPLETE':op=journal.begin(root,'runtime-control',current['descriptor']['descriptorDigest'])
        path=root/'operations'/(op['operationId']+'-manual-stop.json')
        record={'operationId':op['operationId'],'descriptorDigest':current['descriptor']['descriptorDigest']}
        if path.exists() and journal._read(root,path)!=record:raise RuntimeError('Original stopped release changed')
        if not path.exists():journal._write(root,path,record)
        journal.record(root,op['operationId'],{'phase':'STOP_REQUESTED'})
        runtime.stop_writers(root,op['operationId'])
        journal.record(root,op['operationId'],{'phase':'STOPPED'})
        return {'operationId':op['operationId'],'phase':'STOPPED','ingress':'CLOSED','writers':'OBSERVED_STOPPED'}


def start(root: Path) -> dict:
    with journal.locked(root) as root:
        op=journal.current(root);restored=False
        if (root/'restore-plan.json').exists() and journal._read(root,root/'restore-plan.json')['operationId']==op['operationId'] and op['phase'] in {'RESTORED_MAINTENANCE','ACTIVATION_UNKNOWN','ACTIVATION_FAILING','ACTIVATION_FAILED','RUNTIME_VERIFIED','INGRESS_OPEN'}:
            plan=journal._read(root,root/'restore-plan.json')
            if plan['operationId']!=op['operationId']:raise RuntimeError('Start belongs to another restore')
            value=checkpoint.verified(root,plan['sourceOperationId'])
            if digest(value)!=plan['checkpointDigest']:raise RuntimeError('Linked original restore changed')
            if op['phase']=='RESTORED_MAINTENANCE':_assert_restored(root,value)
            if (root/'certs/public.crt').exists():
                import time
                from . import tls_generation
                binding=tls_generation.restore_binding(root,value,now=int(time.time()))
                if not binding['canActivate']:raise RuntimeError('Restored TLS blocked: '+binding['reasonCode'])
            _restore_runtime_registry(root);restored=True
        elif op['phase'] not in {'COMPLETE','STOP_REQUESTED','WRITERS_STOPPED','STOPPED'} and not(op['kind']=='runtime-control' and op['phase']=='CREATED') and not (
            op['phase'] in {'ACTIVATION_UNKNOWN','ACTIVATION_FAILING','ACTIVATION_FAILED'} and
            (root/'operations'/(op['operationId']+'-manual-stop.json')).exists()):
            raise RuntimeError('Unknown publication phase cannot start writers')
        current=journal._read(root,root/'current-release.json')
        descriptor=current['descriptor'];bundle.verify(descriptor,Path(current['directory']))
        if not restored:
            _installed(root,database.observe(root)['gate'])
            gate=database.observe(root)['gate']
            if op['phase']=='COMPLETE':op=journal.begin(root,'runtime-control',descriptor['descriptorDigest'])
            if op['kind']=='runtime-control' and op['configDigest']!=descriptor['descriptorDigest']:raise RuntimeError('Original runtime control release changed')
            anchor=root/'operations'/(op['operationId']+'-manual-stop.json')
            if op['kind']=='runtime-control' and not anchor.exists():
                if op['phase']!='CREATED' or gate['operating_mode']!='ACTIVE':raise RuntimeError('Original manual start anchor unavailable')
                journal._write(root,anchor,{'operationId':op['operationId'],'descriptorDigest':descriptor['descriptorDigest']})
            if gate['operating_mode']!='ACTIVE':
                activation=journal._read(root,root/'operations'/(op['operationId']+'-activation.json'))
                if op['phase'] not in {'ACTIVATION_UNKNOWN','ACTIVATION_FAILING','ACTIVATION_FAILED'} or activation['descriptorDigest']!=descriptor['descriptorDigest'] or not any(_same_gate(gate,g) for g in [activation.get('failed',{}),activation.get('expected',{})] if g):
                    raise RuntimeError('Manual start cannot change an unrecognized deployment gate')
            if op['phase']!='COMPLETE':
                saved=journal._read(root,root/'operations'/(op['operationId']+'-manual-stop.json'))
                if saved!={'operationId':op['operationId'],'descriptorDigest':descriptor['descriptorDigest']}:
                    raise RuntimeError('Exact originally stopped release required')
            activation=root/'operations'/(op['operationId']+'-activation.json')
            if not activation.exists():
                if gate['operating_mode']!='ACTIVE':raise RuntimeError('Only an already active verified release can restart')
                journal._write(root,activation,{'before':gate,'expected':gate,'descriptorDigest':descriptor['descriptorDigest'],'manualRestart':True})
        return _activate(root,op['operationId'],{'descriptor':descriptor},restored=restored)


def _assert_restored(root: Path,value: dict, *, containers=None,assets=True):
    if database.observe(root,containers=containers)!=value['observed'] or checkpoint.table_facts(root,containers=containers)!=value['businessFacts'] or checkpoint.table_facts(root,True,containers=containers)!=value['identityFacts']:
        raise RuntimeError('Linked restored database facts differ; ingress remains closed')
    checkpoint.assert_cluster_facts(value['clusterFacts'],checkpoint.cluster_facts(root,containers=containers))
    if assets:
        actual={};expected={name[7:]:h for name,h in value['files'].items() if name.startswith('assets/') and name.split('/')[1] not in checkpoint.INSTANCE_CONTROLS}
        for name in {name.split('/')[0] for name in expected}:
            path=root/name
            if path.is_dir():actual.update({name+'/'+child:h for child,h in bundle.inventory(path).items()})
            elif path.is_file() and path.resolve()==path.absolute():actual[name]=bundle.sha(path)
        if actual!=expected:raise RuntimeError('Restored asset inventory differs')
        for name,expected in value['files'].items():
            if name.startswith('assets/') and name.split('/')[1] not in checkpoint.INSTANCE_CONTROLS:
                path=root/name[7:]
                if path.resolve()!=path.absolute() or not path.is_file() or bundle.sha(path)!=expected:raise RuntimeError('Linked restored asset differs')
        for name in value.get('assetDirectories',[]):
            if name.split('/')[0] in checkpoint.INSTANCE_CONTROLS:continue
            path=root/name
            if path.resolve()!=path.absolute() or not path.is_dir():raise RuntimeError('Linked restored asset directory differs')


def _restore_assets(root: Path,directory: Path,operation_id: str,value: dict):
    controls=checkpoint.INSTANCE_CONTROLS
    expected_roots={name.split('/')[1] for name in value['files'] if name.startswith('assets/')}
    expected_roots.update(name.split('/')[0] for name in value.get('assetDirectories',[]))
    archive=root/'quarantine'/operation_id/'assets'
    if archive.resolve()!=archive.absolute() or not archive.is_relative_to(root):raise RuntimeError('Linked asset quarantine directory refused')
    archive.mkdir(mode=0o700,parents=True,exist_ok=True)
    for path in list(root.iterdir()):
        if path.name in controls or path.name.endswith('.log') or path.name=='flyway.conf':continue
        saved=archive/path.name
        if path.resolve()!=path.absolute():raise RuntimeError('Linked live asset refused')
        if saved.exists():
            if path.name not in expected_roots:raise RuntimeError('Unexpected asset remains after original restore')
        else:os.replace(path,saved)
    # Older sealed checkpoints included historical control plans/quarantine.
    # Their hashes remain verified in the checkpoint, but restoring them would
    # overwrite this operation's journal and preserved live diagnostic history.
    for path in (directory/'assets').iterdir():
        if path.name not in controls:checkpoint._copy(path,root/path.name)
