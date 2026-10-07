"""Regression coverage for the whole-branch review's interruption boundaries."""
import contextlib
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

LINUX = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LINUX))
from ols_linux import checkpoint, journal, runtime, release, database, bundle, verify


class ReviewRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)/'runtime'
        self.op = journal.begin(self.root, 'upgrade', 'a'*64)

    def restore_fixture(self):
        directory = self.root/'checkpoints'/self.op['operationId']
        directory.mkdir(parents=True, mode=0o700)
        for key in ['businessDb', 'identityDb']:
            (directory/(key+'-roles.sql')).write_bytes(b'CREATE ROLE "postgres";\nCREATE ROLE "original_login";\n')
            (directory/(key+'.dump')).write_bytes(b'original archive fixture')
        value = {'observed': {'history': [{'version': '1060'}], 'gate': {'revision': 8}, 'tables': ['lead.lead']},
                 'businessFacts': {'lead.lead': {'count': 1, 'digest': 'b'*64}},
                 'identityFacts': {'public.user_entity': {'count': 2, 'digest': 'c'*64}},
                 'clusterFacts': {key: {'rolesDigest': 'd'*64, 'membersDigest': 'e'*64, 'databaseOwner': 'postgres'} for key in ['businessDb', 'identityDb']}}
        journal._write(self.root, directory/'checkpoint.json', value)
        runtime.save(self.root, {'containers': {'businessDb': 'own-business', 'identityDb': 'own-identity'}})
        return directory, value

    def exercise_database_interruption(self, loss):
        directory, value = self.restore_fixture()
        restored = set(); roles = set(); calls = []
        fault = [True]
        def facts(root, identity=False, **kwargs):
            key = 'identityDb' if identity else 'businessDb'
            return value['identityFacts' if identity else 'businessFacts'] if key in restored else {}
        def clusters(*args, **kwargs):
            return {key: dict(value['clusterFacts'][key], rolesDigest='d'*64 if key in roles else 'f'*64) for key in value['clusterFacts']}
        def run(args, data=None, **kwargs):
            if 'psql' in args:
                key = 'businessDb' if args[3]=='own-business' else 'identityDb'
                calls.append(('roles', key))
                if key in roles: raise RuntimeError('Duplicate CREATE ROLE')
                roles.add(key)
                if loss=='roles' and fault[0]:
                    fault[0]=False
                    raise RuntimeError('Roles committed, response lost')
            return SimpleNamespace(returncode=0, stdout=b'', stderr=b'')
        def restore(args, **kwargs):
            key = 'businessDb' if args[3]=='own-business' else 'identityDb'
            calls.append(('archive', key))
            if key in restored: raise RuntimeError('Duplicate original database restore')
            restored.add(key)
            if loss=='database' and fault[0]:
                fault[0]=False
                raise RuntimeError('First database committed, response lost')
            return SimpleNamespace(returncode=0)
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(runtime, 'owned'))
            stack.enter_context(patch.object(runtime, 'run', side_effect=run))
            stack.enter_context(patch.object(checkpoint.subprocess, 'run', side_effect=restore))
            stack.enter_context(patch.object(checkpoint, 'table_facts', side_effect=facts))
            stack.enter_context(patch.object(checkpoint, 'cluster_facts', side_effect=clusters))
            stack.enter_context(patch.object(database, 'observe', side_effect=lambda *a,**kw: value['observed'] if 'businessDb' in restored else {'history':[], 'gate':None, 'tables':[]}))
            stack.enter_context(patch.object(checkpoint, 'restore_database_acl'))
            with self.assertRaises(RuntimeError): checkpoint._restore_databases(self.root, directory)
            checkpoint._restore_databases(self.root, directory)
        self.assertEqual(restored, {'businessDb','identityDb'})
        self.assertEqual(calls, [('roles','businessDb'),('archive','businessDb'),('roles','identityDb'),('archive','identityDb')])

    def test_first_database_committed_response_loss_continues_only_second_database(self):
        self.exercise_database_interruption('database')

    def test_roles_committed_response_loss_does_not_import_roles_again(self):
        self.exercise_database_interruption('roles')

    @unittest.skipIf(os.name=='nt', 'POSIX directory link boundary is verified on Linux')
    def test_certificate_directory_link_is_rejected_before_any_docker_command(self):
        target=Path(self.tmp.name)/'outside'; target.mkdir()
        (self.root/'certs').symlink_to(target, target_is_directory=True)
        # Use an initialization operation, as a real original PREPARING resume would.
        journal.record(self.root, self.op['operationId'], {'phase':'COMPLETE'})
        journal.begin(self.root, 'initialize', 'b'*64)
        with patch.object(runtime, 'run') as effects:
            with self.assertRaises(RuntimeError):
                runtime.prepare(self.root, {'name':'ols-review-cert', 'repo':str(LINUX.parents[1])})
        effects.assert_not_called()
        self.assertEqual(list(target.iterdir()), [])

    def exercise_activation_interruption(self, loss):
        descriptor={'descriptorDigest':'a'*64,'schemaVersion':'52-plus-2-r2-v22','jar':'app.jar','files':{'app.jar':'b'*64},'manifestHash':'c'*64}
        journal._write(self.root,self.root/'launch.json',{'descriptorDigest':'a'*64})
        journal._write(self.root,self.root/'installed-candidate.json',{'directory':str(self.root/'release'),'descriptor':descriptor})
        observed=[{'deployment_state_key':'PRIMARY','schema_contract_version':descriptor['schemaVersion'],'operating_mode':'MAINTENANCE','revision':2,
                   'active_release_digest':'d'*64,'active_manifest_hash':'e'*64,'changed_at':'2026-10-07T00:00:00+00:00'}]
        fault=[True]; health=[False]
        def cas(root, old, new):
            self.assertEqual(old, observed[0]); observed[0]=new
            if loss=='blocked' and new['operating_mode']=='BLOCKED' and fault[0]:
                fault[0]=False; raise RuntimeError('BLOCKED committed, response lost')
            return new
        def stop(root, opid, **kwargs):
            journal.record(root,opid,{'phase':kwargs.get('phase','WRITERS_STOPPED')})
            if loss=='stop' and fault[0]:
                fault[0]=False; raise RuntimeError('Exited after writers stopped')
        def ready(*args):
            if not health[0]: raise RuntimeError('Original readiness failure')
            return {'status':'PASS'}
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(database,'observe',side_effect=lambda *a,**kw:{'gate':observed[0]}))
            stack.enter_context(patch.object(release,'_cas_gate',side_effect=cas))
            stack.enter_context(patch.object(bundle,'verify'))
            stack.enter_context(patch.object(runtime,'start_internal'))
            stack.enter_context(patch.object(runtime,'stop_writers',side_effect=stop))
            stack.enter_context(patch.object(runtime,'open_ingress'))
            stack.enter_context(patch.object(verify,'runtime_ready',side_effect=ready))
            stack.enter_context(patch.object(verify,'ingress_ready',return_value={'status':'PASS'}))
            with self.assertRaises(RuntimeError): release._activate(self.root,self.op['operationId'],{'descriptor':descriptor})
            self.assertEqual(journal.current(self.root)['phase'],'ACTIVATION_FAILING')
            health[0]=True
            result=release._advance(self.root,journal.current(self.root),{'descriptor':descriptor})
        self.assertEqual(result['phase'],'COMPLETE')
        self.assertEqual(journal.current(self.root)['operationId'],self.op['operationId'])
        self.assertEqual(observed[0]['operating_mode'],'ACTIVE')

    def test_activation_failure_stop_interruption_retains_activation_phase(self):
        self.exercise_activation_interruption('stop')

    def test_activation_failure_blocked_cas_response_loss_resumes_original_gate(self):
        self.exercise_activation_interruption('blocked')


if __name__=='__main__': unittest.main()
