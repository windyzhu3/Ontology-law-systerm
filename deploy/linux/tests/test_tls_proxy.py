import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime
from ols_linux.bundle import sha

class ProxyTests(unittest.TestCase):
    def setUp(self):
        self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.root=Path(self.t.name)/'runtime';self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        try:self.m=importlib.import_module('ols_linux.tls_proxy')
        except ImportError:self.fail('registered proxy TLS transitions missing')
        runtime.save(self.root,{'instanceId':self.op['instanceId']})
        self.path=self.root/'proxy/nginx.conf';runtime.private_file(self.path,b'certificate /old/cert; key /old/key;')
        self.registration={'version':1,'services':[{'role':'nginx','transport':'docker','name':'nginx-fixture','identity':'i'*64,'image':'sha256:'+'a'*64,'config':str(self.path),'configSha256':sha(self.path),'tlsPaths':{'certificate':'/old/cert','privateKey':'/old/key'}}]}
        self.actual={'Id':'i'*64,'Image':'sha256:'+'a'*64,'State':{'Running':True},'Mounts':[{'Source':str(self.root),'Destination':str(self.root),'Type':'bind'}]}
    def test_unregistered_service_or_command_refused_before_effect(self):
        with patch.object(runtime,'run') as run:
            with self.assertRaises(RuntimeError):self.m.validate_registration(self.root,dict(self.registration,command='bad'))
            run.assert_not_called()
    def prepared(self):
        with patch.object(runtime,'inspect',return_value=self.actual):
            return self.m.prepare(self.root,self.op['operationId'],self.registration,{'certificate':'/new/cert','privateKey':'/new/key','httpTrust':'/new/trust'})
    def test_config_check_precedes_reload(self):
        self.prepared();calls=[]
        from types import SimpleNamespace
        def execute(args,**kw):calls.append(args);return SimpleNamespace(stdout=b'',returncode=0)
        with patch.object(runtime,'inspect',return_value=self.actual),patch.object(runtime,'run',side_effect=execute):
            self.m.apply(self.root,self.op['operationId'],'switch')
        self.assertTrue(any('-t' in c for c in calls))
        self.assertNotIn('reload',[x for c in calls for x in c]) # nginx remains closed until open
    def test_close_is_observed_not_assumed(self):
        self.prepared()
        with patch.object(runtime,'inspect',return_value=self.actual),patch.object(runtime,'run'):
            self.assertFalse(self.m.apply(self.root,self.op['operationId'],'close')['closed'])
    def test_reload_response_loss_reconciles_generation(self):
        self.prepared()
        with patch.object(runtime,'inspect',return_value=self.actual),patch.object(runtime,'run',side_effect=RuntimeError('unknown')):
            with self.assertRaises(RuntimeError):self.m.apply(self.root,self.op['operationId'],'switch')
        self.assertEqual(self.path.read_bytes(),b'certificate /old/cert; key /old/key;')
    def test_maintenance_preserves_only_registered_probe_path(self):
        self.prepared()
        stopped=dict(self.actual,State={'Running':False})
        with patch.object(runtime,'inspect',return_value=stopped),patch.object(runtime,'run') as run:
            self.assertTrue(self.m.apply(self.root,self.op['operationId'],'close')['closed'])
            run.assert_not_called()
