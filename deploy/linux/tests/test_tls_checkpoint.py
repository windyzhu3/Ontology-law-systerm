from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_generation,checkpoint,release
from ols_linux.bundle import inventory,sha
from ols_linux.config import digest
from tls_fixtures import materials,instance


class TlsCheckpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.materials=tempfile.TemporaryDirectory();cls.fixture=materials(Path(cls.materials.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.materials.cleanup()
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=instance(Path(self.temp.name),self.fixture)
        init=journal.current(self.root);journal.record(self.root,init['operationId'],{'phase':'COMPLETE'})
        self.op=journal.begin(self.root,'restore','a'*64)
        journal.record(self.root,self.op['operationId'],{'phase':'RESTORED_MAINTENANCE'})
        self.legacy=tls_generation.resolve(self.root)
        self.value={'files':{'assets/certs/'+n:h for n,h in inventory(self.root/'certs').items()}}
    def binding(self,**kw):
        self.assertTrue(callable(getattr(tls_generation,'restore_binding',None)),'TLS checkpoint binding missing')
        return tls_generation.restore_binding(self.root,self.value,now=kw.get('now',self.fixture['now']))
    def test_legacy_checkpoint_selects_zero(self):
        result=self.binding()
        self.assertEqual(result['generationId'],self.legacy['generationId']);self.assertEqual(result['version'],0)
        self.assertTrue(result['canActivate'])
    def test_checkpoint_restores_exact_generation_not_current_registry(self):
        self.value['tlsBinding']={'version':1,'generationId':'expected'}
        runtime.save(self.root,dict(runtime.load(self.root),tlsGenerationId='newer'))
        with self.assertRaisesRegex(RuntimeError,'checkpoint'):self.binding()
    def test_expired_restored_certificate_keeps_original_restore_pending(self):
        result=self.binding(now=self.fixture['now']+3*86400)
        self.assertFalse(result['canActivate']);self.assertEqual(result['reasonCode'],'CERTIFICATE_EXPIRED')
        self.assertEqual(journal.current(self.root)['operationId'],self.op['operationId'])
        self.assertEqual(journal.current(self.root)['phase'],'RESTORED_MAINTENANCE')
    def test_newer_tls_audit_is_preserved_in_quarantine(self):
        directory=self.root/'checkpoints'/('b'*32);checkpoint._copy(self.root/'certs',directory/'assets/certs')
        runtime.private_file(self.root/'tls/generations/new/audit.json',b'newer-generation-evidence')
        journal._write(self.root,self.root/'tls-pending.json',{'operationId':'c'*32})
        release._restore_assets(self.root,directory,self.op['operationId'],self.value)
        self.assertEqual((self.root/'quarantine'/self.op['operationId']/'assets/tls/generations/new/audit.json').read_bytes(),b'newer-generation-evidence')
        self.assertTrue((self.root/'tls-pending.json').exists(),'Pending journal is an instance control, not a restored asset')
        self.assertTrue(self.binding()['canActivate'])
    def test_missing_active_reference_does_not_downgrade_selected_generation(self):
        journal._write(self.root,self.root/'tls-selection.json',{'generationId':'f'*64,'operationId':'e'*32})
        with self.assertRaisesRegex(RuntimeError,'reference'):tls_generation.resolve(self.root)
    def test_restored_files_must_match_before_legacy_selection(self):
        runtime.private_file(self.root/'certs/public.crt',self.fixture['new-leaf'].read_bytes())
        with self.assertRaisesRegex(RuntimeError,'checkpoint'):self.binding()
    def test_start_refuses_expired_checkpoint_before_activation(self):
        self.value.update(tlsBinding={'version':0,'generationId':self.legacy['generationId']})
        plan={'operationId':self.op['operationId'],'sourceOperationId':'b'*32,'checkpointDigest':digest(self.value)}
        journal._write(self.root,self.root/'restore-plan.json',plan)
        journal._write(self.root,self.root/'current-release.json',{'directory':str(self.root),'descriptor':{}})
        with patch('ols_linux.bundle.verify'),patch.object(checkpoint,'verified',return_value=self.value),patch.object(release,'_assert_restored'),patch.object(release,'_restore_runtime_registry'),patch.object(release,'_activate') as activate,patch('time.time',return_value=self.fixture['now']+3*86400):
            with self.assertRaisesRegex(RuntimeError,'CERTIFICATE_EXPIRED'):release.start(self.root)
        activate.assert_not_called()
        self.assertEqual(journal.current(self.root)['phase'],'RESTORED_MAINTENANCE')
    def test_restored_generation_wins_over_newer_registry(self):
        from ols_linux import tls_material
        from tls_fixtures import inputs
        journal.record(self.root,self.op['operationId'],{'phase':'COMPLETE'})
        rotation=journal.begin(self.root,'rotate-public-tls','c'*64)
        candidate=tls_material.stage(self.root,inputs(self.fixture),now=self.fixture['now'])
        generation=tls_generation.seal(self.root,rotation['operationId'],candidate,{'files':{}},{'files':{}})
        journal.record(self.root,rotation['operationId'],{'phase':'SWITCHING'})
        tls_generation.select(self.root,rotation['operationId'],self.legacy['generationId'],generation['generationId'])
        self.value['files'].update({'assets/tls/'+name:h for name,h in inventory(self.root/'tls').items()})
        self.value['tlsBinding']={'version':1,'generationId':generation['generationId']}
        journal.record(self.root,rotation['operationId'],{'phase':'COMPLETE'})
        self.op=journal.begin(self.root,'restore','d'*64)
        journal.record(self.root,self.op['operationId'],{'phase':'RESTORED_MAINTENANCE'})
        runtime.save(self.root,dict(runtime.load(self.root),tlsGenerationId='newer-registry-is-not-evidence'))
        result=self.binding()
        self.assertTrue(result['canActivate']);self.assertEqual(result['generationId'],generation['generationId'])
        self.assertEqual(tls_generation.resolve(self.root)['generationId'],generation['generationId'])
    def test_restore_start_resumes_after_native_ingress_open_response_loss(self):
        plan={'operationId':self.op['operationId'],'sourceOperationId':'b'*32,'checkpointDigest':digest(self.value)}
        journal._write(self.root,self.root/'restore-plan.json',plan)
        journal._write(self.root,self.root/'current-release.json',{'directory':str(self.root),'descriptor':{}})
        journal.record(self.root,self.op['operationId'],{'phase':'INGRESS_OPEN'})
        with patch('ols_linux.bundle.verify'),patch.object(checkpoint,'verified',return_value=self.value),patch.object(release,'_restore_runtime_registry'),patch.object(release,'_activate',return_value={'operationId':self.op['operationId']}) as activate:
            self.assertEqual(release.start(self.root)['operationId'],self.op['operationId'])
        self.assertTrue(activate.call_args.kwargs['restored'])
    def test_legacy_native_probe_context_preserves_checkpoint_certificate_bytes(self):
        from ols_linux import tls_restore,identity
        before=(self.root/'certs/public.crt').read_bytes()
        state={'before':{'probeTargets':[]},'target':{'proxies':[{'service':{'role':'nginx'},'configuration':'fixture'}]}}
        tls_restore._legacy_context(self.root,self.legacy,state)
        selected=tls_generation.resolve(self.root)
        self.assertEqual(selected['generationId'],self.legacy['generationId'])
        self.assertEqual(identity.http_helper(self.root),selected['deployment']['httpHelper'])
        self.assertEqual((self.root/'certs/public.crt').read_bytes(),before)
        self.assertTrue(self.binding()['canActivate'])
        Path(selected['deployment']['httpHelper']).write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError,'helper'):tls_generation.resolve(self.root)
    def test_expired_late_restore_completes_failure_cleanup(self):
        plan={'operationId':self.op['operationId'],'sourceOperationId':'b'*32,'checkpointDigest':digest(self.value)}
        journal._write(self.root,self.root/'restore-plan.json',plan)
        descriptor={'descriptorDigest':'a'*64}
        journal._write(self.root,self.root/'current-release.json',{'directory':str(self.root),'descriptor':descriptor})
        activation_file=self.root/'operations'/(self.op['operationId']+'-restored-activation.json')
        journal._write(self.root,activation_file,{'descriptorDigest':'a'*64})
        for phase in ['INGRESS_OPEN','ACTIVATION_FAILING','ACTIVATION_UNKNOWN','RUNTIME_VERIFIED']:
            journal.record(self.root,self.op['operationId'],{'phase':phase})
            with patch.object(checkpoint,'verified',return_value=self.value),patch.object(release,'_finish_activation_failure') as cleanup,patch('time.time',return_value=self.fixture['now']+3*86400):
                with self.assertRaisesRegex(RuntimeError,'CERTIFICATE_EXPIRED'):release.start(self.root)
                cleanup.assert_called_once()
                self.assertEqual(journal.current(self.root)['operationId'],self.op['operationId'])
                self.assertEqual(journal.current(self.root)['phase'],'ACTIVATION_FAILING')
    def test_expired_restore_retries_interrupted_close_before_blocking(self):
        from ols_linux import tls_restore,database
        descriptor={'descriptorDigest':'a'*64};gate={'operating_mode':'ACTIVE','revision':4}
        plan={'operationId':self.op['operationId'],'sourceOperationId':'b'*32,'checkpointDigest':digest(self.value)}
        journal._write(self.root,self.root/'restore-plan.json',plan)
        journal._write(self.root,self.root/'current-release.json',{'directory':str(self.root),'descriptor':descriptor})
        journal._write(self.root,self.root/'operations'/(self.op['operationId']+'-restored-activation.json'),{'descriptorDigest':'a'*64,'expected':gate})
        journal._write(self.root,self.root/'operations'/(self.op['operationId']+'-restore-tls.json'),{'checkpointDigest':digest(self.value)})
        journal.record(self.root,self.op['operationId'],{'phase':'INGRESS_OPEN'})
        with patch.object(checkpoint,'verified',return_value=self.value),patch('time.time',return_value=self.fixture['now']+3*86400),patch.object(tls_restore,'close',side_effect=[RuntimeError('close response lost'),None]) as close,patch.object(runtime,'stop_writers') as stop,patch.object(database,'observe',return_value={'gate':gate}),patch.object(release,'_cas_gate') as cas:
            with self.assertRaisesRegex(RuntimeError,'close response lost'):release.start(self.root)
            self.assertEqual(journal.current(self.root)['phase'],'ACTIVATION_FAILING')
            stop.assert_not_called()
            with self.assertRaisesRegex(RuntimeError,'CERTIFICATE_EXPIRED'):release.start(self.root)
            self.assertEqual(close.call_count,2);stop.assert_called_once();cas.assert_called_once()
            self.assertEqual(cas.call_args.args[2]['operating_mode'],'BLOCKED')
        self.assertEqual(journal.current(self.root)['phase'],'ACTIVATION_FAILED')
        self.assertEqual(journal.current(self.root)['operationId'],self.op['operationId'])
