import importlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_material,tls_generation
from tls_fixtures import materials,instance,inputs

class RotationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'source',expired_old=True)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup);self.root=instance(Path(self.t.name),self.f)
        try:self.m=importlib.import_module('ols_linux.tls_rotation')
        except ImportError:self.fail('original TLS rotation controller missing')
        self.oldop=journal.current(self.root);journal.record(self.root,self.oldop['operationId'],{'phase':'COMPLETE'})
        self.gate={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'52-plus-2-r2-v22','revision':29,'changed_at':'2026-10-08T00:00:00+00:00'}
        journal._write(self.root,self.root/'current-release.json',{'descriptor':{'descriptorDigest':'d'*64}})
        journal._write(self.root,self.root/'launch.json',{'containers':[]})
    def begin(self):
        with patch.object(self.m.database,'observe',return_value={'gate':self.gate}),patch.object(self.m.release,'_installed'),patch.object(runtime,'validate_tls'),patch.object(self.m.tls_proxy,'validate_registration'),patch.object(self.m,'resume',side_effect=lambda root,opid,**kw:{'operationId':opid}):
            return self.m.begin(self.root,{'materials':inputs(self.f),'proxies':{'version':1,'services':[]},'probeTargets':[]},now=self.f['now'])
    def test_expired_old_certificate_can_rotate_forward(self):
        before=(self.root/'operations'/(self.oldop['operationId']+'.json')).read_bytes()
        result=self.begin()
        self.assertNotEqual(result['operationId'],self.oldop['operationId'])
        self.assertEqual((self.root/'operations'/(self.oldop['operationId']+'.json')).read_bytes(),before)
    def test_pending_registration_recovers_same_id(self):
        result=self.begin();opid=result['operationId']
        journal._write(self.root,self.root/'current-operation.json',{'operationId':self.oldop['operationId']})
        self.assertEqual(self.m.reconcile_pending(self.root)['operationId'],opid)
        self.assertEqual(journal.current(self.root)['operationId'],opid)
    def test_other_operation_cannot_start_or_publish(self):
        result=self.begin()
        with self.assertRaises(RuntimeError):journal.begin(self.root,'publish-bytes','c'*64)
        from ols_linux import release
        with self.assertRaises(RuntimeError):release.start(self.root)
        self.assertEqual(journal.current(self.root)['operationId'],result['operationId'])
    def test_old_expired_rollback_cannot_open(self):
        result=self.begin()
        with patch.object(self.m.tls_proxy,'apply') as effects:
            with self.assertRaises(RuntimeError):self.m.rollback(self.root,result['operationId'],now=self.f['now'])
            effects.assert_not_called()
    def test_candidate_expired_on_resume_stays_blocked(self):
        result=self.begin();opid=result['operationId']
        with self.assertRaises(RuntimeError):self.m.resume(self.root,opid,now=self.f['now']+31*86400)
        self.assertNotEqual(journal.current(self.root)['phase'],'COMPLETE')
    def test_preregistered_rotation_blocks_competing_operation(self):
        result=self.begin();opid=result['operationId']
        journal._write(self.root,self.root/'current-operation.json',{'operationId':self.oldop['operationId']})
        with self.assertRaisesRegex(RuntimeError,'pending TLS'):journal.begin(self.root,'publish-bytes','c'*64)
        self.assertEqual(self.m.reconcile_pending(self.root)['operationId'],opid)
