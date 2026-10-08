import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime
from ols_linux.config import digest


class TlsRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'runtime';self.op=journal.begin(self.root,'restore','a'*64)
        try:self.m=importlib.import_module('ols_linux.tls_restore')
        except ImportError:self.fail('Original restore TLS coordinator missing')
        self.value={'files':{},'tlsBinding':{'version':0,'generationId':'old'}}
        journal._write(self.root,self.root/'restore-plan.json',{'operationId':self.op['operationId'],'checkpointDigest':digest(self.value)})
        self.service={'role':'nginx','transport':'docker','name':'registered','identity':'i','image':'sha256:test','config':str(self.root/'proxy/nginx.conf'),'tlsPaths':{'certificate':'/old/cert'},'configSha256':'test'}
        self.before={'version':1,'generationId':'new','paths':{'certificate':'/new/cert'},'proxies':[{'service':self.service,'configuration':'cert /new/cert;'}],'probeTargets':[]}
    def prepare(self):
        runtime.private_file(self.root/'certs/public.crt',b'fixture')
        with patch.object(self.m.tls_generation,'checkpoint_binding',return_value=self.before):self.m.prepare(self.root,self.value)
    def test_close_requires_observed_stop(self):
        self.prepare()
        with patch.object(self.m.tls_proxy,'_service',return_value={'running':True}),patch.object(self.m.tls_proxy,'_action'):
            with self.assertRaisesRegex(RuntimeError,'stop'):self.m.close(self.root)
        self.assertNotEqual(journal.current(self.root)['phase'],'COMPLETE')
    def test_foreign_proxy_refused_without_effect(self):
        self.prepare()
        with patch.object(self.m.tls_proxy,'_service',side_effect=RuntimeError('identity differs')),patch.object(self.m.tls_proxy,'_action') as action:
            with self.assertRaises(RuntimeError):self.m.close(self.root)
            action.assert_not_called()
    def test_open_requires_native_and_bridge_proofs_before_public_action(self):
        self.prepare();events=[]
        def probe(root,generation,**kw):events.append(kw['scope']);return {'status':'UNKNOWN' if kw['scope']=='bridge' else 'PASS'}
        with patch.object(self.m.tls_generation,'resolve',return_value={'generationId':'old'}),patch.object(self.m.tls_probe,'collect',side_effect=probe),patch.object(self.m.tls_proxy,'_action',side_effect=lambda *a:events.append('open')):
            with self.assertRaises(RuntimeError):self.m.open_verified(self.root,now=1)
        self.assertEqual(events,['native','bridge'])
    def test_restore_proxy_plan_cannot_be_used_by_another_operation(self):
        self.prepare();journal.record(self.root,self.op['operationId'],{'phase':'COMPLETE'})
        journal.begin(self.root,'runtime-control','b'*64)
        with patch.object(self.m.tls_proxy,'_action') as action:
            with self.assertRaises(RuntimeError):self.m.close(self.root)
            action.assert_not_called()
    def test_legacy_proxy_configuration_is_derived_from_saved_registration(self):
        self.prepare();journal.record(self.root,self.op['operationId'],{'phase':'ACTIVATION_UNKNOWN'})
        generation={'generationId':'old','version':0,'paths':{'certificate':'/restored/cert'},'candidate':{'notAfter':100}}
        state={'running':False};events=[]
        with patch.object(self.m.tls_generation,'resolve',return_value=generation),patch.object(self.m.tls_proxy,'_service',return_value=state),patch.object(self.m.tls_proxy,'_check',side_effect=lambda *a:events.append('validate')),patch.object(self.m,'_legacy_context'),patch('ols_linux.tls_maintenance.start') as maintenance:
            self.m.activate(self.root,now=1)
        self.assertEqual((self.root/'proxy/nginx.conf').read_text(),'cert /restored/cert;')
        self.assertEqual(events,['validate'])
        maintenance.assert_called_once()
    def test_restore_reconciles_exact_maintenance_bytes_before_checkpoint_validation(self):
        from ols_linux import tls_maintenance
        import hashlib
        self.prepare();opid=self.op['operationId'];journal.record(self.root,opid,{'phase':'ACTIVATION_UNKNOWN'})
        state=self.m._state(self.root);state['activatedProxies']=[{'service':self.service,'configuration':'checkpoint-config'}]
        journal._write(self.root,self.m._path(self.root,opid),state)
        runtime.private_file(Path(self.service['config']),b'maintenance-config')
        journal._write(self.root,self.root/'operations'/(opid+'-issuer-maintenance.json'),{'service':self.service,'configuration':'maintenance-config'})
        value={'files':{'assets/proxy/nginx.conf':hashlib.sha256(b'checkpoint-config').hexdigest()}}
        self.assertTrue(callable(getattr(self.m,'reconcile_maintenance',None)),'Restore maintenance reconciliation missing')
        running=[True]
        with patch.object(self.m.tls_proxy,'_service',side_effect=lambda s:{'running':running[0]}),patch.object(self.m.tls_proxy,'_action',side_effect=lambda s,a:running.__setitem__(0,False)):
            self.m.reconcile_maintenance(self.root,value)
        self.assertFalse(running[0]);self.assertEqual(Path(self.service['config']).read_bytes(),b'checkpoint-config')
        self.assertEqual(journal.current(self.root)['operationId'],opid)
    def test_restore_maintenance_uses_checkpoint_bridge_targets(self):
        self.assertTrue(callable(getattr(self.m,'_targets',None)),'Checkpoint bridge target selection missing')
        state={'target':{'probeTargets':[{'connectPort':24846}]},'before':{'probeTargets':[{'connectPort':25846}]}}
        self.assertEqual(self.m._targets(state),state['target']['probeTargets'])
        self.assertEqual(self.m._targets(dict(state,target=None)),state['before']['probeTargets'])
