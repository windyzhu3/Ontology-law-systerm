"""Isolated real SQL migration and closed activation with structural JAR fixtures.
Application readiness is intentionally not claimed by this verification.
"""
import argparse
import json
from pathlib import Path
import tempfile
from harness import create,REPO
from fixtures.bundles import create as bundle_fixture
from ols_linux import bundle,checkpoint,database,journal,release,runtime


def verify(root: Path):
    initialization=journal.current(root)['operationId']
    database.roles(root,initialization);database.flyway(root,initialization,'migrate','1060')
    database.runtime_logins(root,initialization);database.verify_schema(root,'1060')
    database.sql(root,(REPO/'deploy/linux/verification/fixtures/v20_facts.sql').read_text(encoding='utf-8'))
    runtime.private_file(root/'materials/fixture.bin',b'Synthetic immutable material')
    runtime.private_file(root/'config/fixture.json',b'{"environment":"isolated-v20-verification"}')
    staged=root/'legacy-staging';old=bundle_fixture(REPO,staged,'v20')
    old_dir=root/'releases'/old['descriptorDigest'];old_dir.parent.mkdir(mode=0o700);staged.rename(old_dir)
    jar_hash=old['files'][old['jar']]
    database.sql(root,"UPDATE platform_meta.deployment_state SET operating_mode='ACTIVE',active_release_digest=decode('"+jar_hash+"','hex'),active_manifest_hash=decode('"+old['manifestHash']+"','hex'),revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY'")
    journal._write(root,root/'current-release.json',{'directory':str(old_dir),'descriptor':old})
    before=database.observe(root);facts=checkpoint.table_facts(root)
    for name in ['identity.appointment','identity.authority_grant','lead.lead','responsibility.task_occurrence','audit.audit_entry']:assert facts[name]['count']>0
    journal.record(root,initialization,{'phase':'COMPLETE','verificationOnly':True,'applicationReadiness':False})
    candidate_parent=Path(tempfile.mkdtemp(prefix='ols-migration-candidate-'))
    candidate=candidate_parent/'bundle';new=bundle_fixture(REPO,candidate,'v22')
    try:release.publish_bytes(root,candidate)
    except RuntimeError:pass
    else:raise AssertionError('Byte publication accepted schema change')
    assert journal.current(root)['operationId']==initialization
    original_flyway=database.flyway
    def interrupted(target_root,operation_id,action,target=None):
        if action=='migrate' and target is None:
            result=original_flyway(target_root,operation_id,action,'1070')
            journal.record(target_root,operation_id,{'phase':'MIGRATION_UNKNOWN','injectedFailure':'after-V1070'})
            raise RuntimeError('Injected response loss after committed V1070')
        return original_flyway(target_root,operation_id,action,target)
    database.flyway=interrupted
    try:
        try:release.upgrade(root,candidate)
        except RuntimeError:pass
        else:raise AssertionError('Interrupted migration unexpectedly completed')
    finally:database.flyway=original_flyway
    opid=journal.current(root)['operationId'];cp=checkpoint.verified(root,opid)
    assert journal.current(root)['phase']=='MIGRATION_UNKNOWN'
    assert release.reconcile_migrations(cp['observed']['history'],cp['observed']['gate'],database.observe(root))=='1070'
    try:release.upgrade(root,candidate)
    except RuntimeError:pass
    else:raise AssertionError('New upgrade accepted v21')
    assert journal.current(root)['operationId']==opid
    try:release.resume(root,opid)
    except RuntimeError:pass
    else:raise AssertionError('Non-executable fixture reported application readiness')
    assert journal.current(root)['phase']=='ACTIVATION_UNKNOWN'
    after=database.observe(root)
    assert after['history'][:len(before['history'])]==before['history']
    assert [r['version'] for r in after['history'][len(before['history']):]]==['1070','1080']
    checkpoint.assert_preserved(facts,checkpoint.table_facts(root))
    assert database.sql(root,"SELECT count(*) FROM identity.appointment_role WHERE role_code='OWNER'")=='1'
    assert after['gate']['operating_mode']=='MAINTENANCE' and runtime.load(root)['ingress'] is None
    journal.record(root,opid,{'phase':'MIGRATION_UNKNOWN','injectedFailure':'committed-before-client-result'})
    calls=[]
    def observed_call(*args,**kwargs):
        calls.append(args[2]);return original_flyway(*args,**kwargs)
    database.flyway=observed_call
    try:
        try:release.resume(root,opid)
        except RuntimeError:pass
        else:raise AssertionError('Missing runtime configuration accepted')
    finally:database.flyway=original_flyway
    assert calls==['validate']
    assert database.observe(root)['history']==after['history']
    restored=checkpoint.restore(root,opid)
    assert restored['status']=='RESTORED_MAINTENANCE'
    assert database.observe(root)==cp['observed']
    assert bundle.sha(old_dir/old['jar'])==jar_hash
    assert checkpoint.table_facts(root)==cp['businessFacts']
    # Original restore is verified again without a new operation or replacement target.
    assert checkpoint.restore(root,opid)['operationId']==opid
    return {'status':'PASS','oldFactTables':len(facts),'migrationsAppended':['1070','1080'],'checkpointRestoreVerified':True,
        'originalResumeOnlyRemainingMigration':True,'committedUnknownDidNotRemigrate':True,'oldArtifactBytesRestored':True,
        'applicationReadiness':False,'finalPhase':journal.current(root)['phase'],'runtime':str(root)}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);args=parser.parse_args()
    root=create(args.run_id)
    try:print(json.dumps(verify(root)))
    except Exception:
        print('Migration verification resources retained: '+str(root));raise
    else:runtime.cleanup_verification(root)
