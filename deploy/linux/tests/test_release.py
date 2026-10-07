import importlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
LINUX=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(LINUX))


class ReleaseTest(unittest.TestCase):
    def test_manual_start_refuses_unknown_phase_before_launching_any_writer(self):
        from ols_linux import journal,runtime
        self.assertTrue(callable(getattr(self.m,'start',None)), 'Qualified manual start missing')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';journal.begin(root,'initialize','a'*64)
            with patch.object(runtime,'start_internal') as launch:
                with self.assertRaises(RuntimeError):self.m.start(root)
                launch.assert_not_called()

    def test_restore_registry_reselects_only_previously_registered_old_containers(self):
        from ols_linux import journal,runtime,bundle
        self.assertTrue(callable(getattr(self.m,'_restore_runtime_registry',None)), 'Linked restore runtime binding missing')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';journal.begin(root,'initialize','a'*64)
            old=root/'releases'/('a'*64);old.mkdir(parents=True)
            journal._write(root,root/'current-release.json',{'directory':str(old),'descriptor':{'descriptorDigest':'a'*64}})
            journal._write(root,root/'launch.json',{'descriptorDigest':'a'*64,'containers':[{'role':'api','name':'old-api'}],
                'ingress':{'name':'old-entry'}})
            resources={'containers':{'api':'new-api','retainedapi':'old-api','entry':'new-entry','retainedentry':'old-entry'},
                       'writers':['old-api','new-api'],'repo':str(root/'new'),'ingress':'new-entry'}
            with patch.object(runtime,'load',return_value=resources),patch.object(runtime,'save') as save,patch.object(bundle,'verify'):
                self.m._restore_runtime_registry(root)
                self.assertEqual(resources['repo'],str(old));self.assertEqual(resources['containers']['api'],'old-api')
                self.assertEqual(resources['ingress'],'old-entry');save.assert_called_once()
            resources['containers'].pop('retainedapi');resources['containers']['api']='new-api';resources['writers']=['new-api']
            with patch.object(runtime,'load',return_value=resources),patch.object(runtime,'save') as save,patch.object(bundle,'verify'):
                with self.assertRaises(RuntimeError):self.m._restore_runtime_registry(root)
                save.assert_not_called()

    def setUp(self):
        self.assertTrue((LINUX/'ols_linux/release.py').is_file(),'Journaled migration capability missing')
        self.m=importlib.import_module('ols_linux.release')
        self.history=[{'version':v,'success':True,'checksum':int(v),'installed_rank':i} for i,v in enumerate(__import__('ols_linux.database',fromlist=['expected_versions']).expected_versions(LINUX.parents[1],'1060'))]
        self.gate={'schema_contract_version':'52-plus-2-r2-v20','operating_mode':'MAINTENANCE','revision':8,'active_release_digest':'a'*64,'active_manifest_hash':'b'*64}

    def test_exact_prefix_maintained_and_only_reviewed_successors_allowed(self):
        for suffix,target in [([], '1060'),(['1070'],'1070'),(['1070','1080'],'1080')]:
            history=self.history+[{'version':v,'success':True,'checksum':int(v),'installed_rank':41+i} for i,v in enumerate(suffix)]
            gate=dict(self.gate,schema_contract_version={'1060':'52-plus-2-r2-v20','1070':'52-plus-2-r2-v21','1080':'52-plus-2-r2-v22'}[target],revision=8+len(suffix))
            self.assertEqual(self.m.reconcile_migrations(self.history,self.gate,{'history':history,'gate':gate}),target)

    def test_checksum_prefix_failed_history_and_foreign_gate_refused(self):
        changed=[dict(x) for x in self.history];changed[0]['checksum']=999
        for history,gate in [(changed,self.gate),(self.history+[{'version':'1090','success':True}],self.gate),(self.history,dict(self.gate,revision=9)),(self.history,dict(self.gate,active_release_digest='c'*64))]:
            with self.assertRaises(RuntimeError):self.m.reconcile_migrations(self.history,self.gate,{'history':history,'gate':gate})

    def test_byte_publication_rejects_schema_change(self):
        with self.assertRaises(RuntimeError):self.m.require_same_schema('52-plus-2-r2-v20','52-plus-2-r2-v22')
        self.m.require_same_schema('52-plus-2-r2-v22','52-plus-2-r2-v22')

    def test_rollback_old_jar_without_linked_checkpoint_is_not_supported(self):
        self.assertFalse(hasattr(self.m,'rollback_jar'))

    def test_committed_unknown_result_is_validated_without_second_migration(self):
        self.assertTrue(callable(getattr(self.m,'_advance',None)), 'Original-operation continuation missing')
        from ols_linux import journal
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';op=journal.begin(root,'upgrade','a'*64)
            journal.record(root,op['operationId'],{'phase':'MIGRATION_UNKNOWN'})
            history=self.history+[{'version':v,'success':True} for v in ['1070','1080']]
            after={'history':history,'gate':dict(self.gate,schema_contract_version='52-plus-2-r2-v22',revision=10)}
            cp={'observed':{'history':self.history,'gate':self.gate},'businessFacts':{}}
            with patch('ols_linux.checkpoint.verified',return_value=cp),patch('ols_linux.database.observe',return_value=after),patch('ols_linux.database.verify_schema',return_value=after),patch('ols_linux.database.flyway',return_value=after) as flyway,patch('ols_linux.checkpoint.table_facts',return_value={}),patch.object(self.m,'_install_bundle',side_effect=RuntimeError('Injected install failure'),create=True):
                with self.assertRaises(RuntimeError):self.m._advance(root,journal.current(root),{'descriptor':{'descriptorDigest':'a'*64},'bundleDirectory':folder})
            self.assertEqual([call.args[2] for call in flyway.call_args_list],['validate'])
            self.assertEqual(journal.current(root)['phase'],'INSTALL_UNKNOWN')

    def test_activation_requires_prepared_runtime_and_never_opens_ingress_on_failure(self):
        self.assertTrue(callable(getattr(self.m,'_activate',None)), 'Closed activation continuation missing')
        from ols_linux import journal
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';op=journal.begin(root,'upgrade','a'*64)
            journal.record(root,op['operationId'],{'phase':'BUNDLE_INSTALLED'})
            descriptor={'descriptorDigest':'a'*64,'schemaVersion':'52-plus-2-r2-v22'}
            with patch('ols_linux.runtime.open_ingress') as ingress:
                with self.assertRaises(RuntimeError):self.m._activate(root,op['operationId'],{'descriptor':descriptor})
            ingress.assert_not_called()
            self.assertEqual(journal.current(root)['phase'],'ACTIVATION_UNKNOWN')

    def test_opened_entry_failure_blocks_same_original_release_instead_of_complete(self):
        from ols_linux import journal,verify,runtime,database,bundle
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';op=journal.begin(root,'upgrade','a'*64)
            descriptor={'descriptorDigest':'a'*64,'schemaVersion':'52-plus-2-r2-v22','jar':'app.jar','files':{'app.jar':'b'*64},'manifestHash':'c'*64}
            journal._write(root,root/'launch.json',{'descriptorDigest':'a'*64})
            journal._write(root,root/'installed-candidate.json',{'directory':str(root/'release'),'descriptor':descriptor})
            observed=[{'deployment_state_key':'PRIMARY','schema_contract_version':descriptor['schemaVersion'],'operating_mode':'MAINTENANCE','revision':2,
                'active_release_digest':'d'*64,'active_manifest_hash':'e'*64,'changed_at':'2026-10-07T00:00:00+00:00'}]
            def cas(root,old,new):
                self.assertEqual(old,observed[0]);observed[0]=new;return new
            def open_entry(root,opid):
                self.assertEqual(journal.current(root)['phase'],'RUNTIME_VERIFIED')
                journal.record(root,opid,{'phase':'INGRESS_OPEN'})
            with patch.object(database,'observe',side_effect=lambda r:{'gate':observed[0]}),patch.object(self.m,'_cas_gate',side_effect=cas),patch.object(bundle,'verify'),patch.object(runtime,'start_internal'),patch.object(runtime,'stop_writers'),patch.object(runtime,'open_ingress',side_effect=open_entry),patch.object(verify,'runtime_ready',return_value={'status':'PASS'}),patch.object(verify,'ingress_ready',side_effect=RuntimeError('Real entry failed'),create=True):
                with self.assertRaises(RuntimeError):self.m._activate(root,op['operationId'],{'descriptor':descriptor})
            self.assertEqual(observed[0]['operating_mode'],'BLOCKED')
            self.assertEqual(journal.current(root)['operationId'],op['operationId'])
            self.assertEqual(journal.current(root)['phase'],'ACTIVATION_FAILED')

    def test_unbound_legacy_baseline_refused(self):
        self.assertTrue(callable(getattr(self.m,'_installed',None)))
        from ols_linux import journal
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'runtime';journal.begin(root,'initialize','a'*64)
            journal._write(root,root/'current-release.json',{'directory':folder,'descriptor':{'schemaVersion':'52-plus-2-r2-v20'}})
            with self.assertRaises(RuntimeError):self.m._installed(root,self.gate)


if __name__=='__main__':unittest.main()
