import importlib
from pathlib import Path
import tempfile
import unittest
from ols_linux import journal,runtime
from tls_fixtures import materials,instance,inputs
from ols_linux import tls_material

class GenerationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        self.tmp2=tempfile.TemporaryDirectory();self.addCleanup(self.tmp2.cleanup)
        self.root=instance(Path(self.tmp2.name),self.f)
        try:self.m=importlib.import_module('ols_linux.tls_generation')
        except ImportError:self.fail('TLS generation resolver missing')
    def test_legacy_integrity_is_preserved(self):
        before=runtime.load(self.root)
        self.assertEqual(self.m.resolve(self.root)['version'],0)
        self.assertEqual(runtime.load(self.root),before)
    def test_invalid_active_never_falls_back(self):
        runtime.private_file(self.root/'tls/active.json',b'{}')
        with self.assertRaises(RuntimeError):self.m.resolve(self.root)
    def prepare(self):
        old=self.m.resolve(self.root); op=journal.current(self.root);journal.record(self.root,op['operationId'],{'phase':'COMPLETE'})
        op=journal.begin(self.root,'rotate-public-tls','a'*64)
        c=tls_material.stage(self.root,inputs(self.f),now=self.f['now'])
        layout=self.m.layout(self.root,op['operationId'],c)
        out=Path(layout['paths']['httpTrust']).parent
        runtime.private_file(out/'http-trust.pem',self.f['internal'].read_bytes()+self.f['old'].read_bytes()+self.f['new'].read_bytes())
        runtime.private_file(out/'identity-trust.p12',b'test-store')
        from ols_linux.bundle import sha
        trust={'files':{str((out/n).relative_to(self.root)):sha(out/n) for n in ['http-trust.pem','identity-trust.p12']},'anchorFingerprints':[]}
        return old,op,self.m.seal(self.root,op['operationId'],c,trust,{'files':{}})
    def test_select_requires_current_operation_and_parent(self):
        old,op,g=self.prepare()
        with self.assertRaises(RuntimeError):self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        journal.record(self.root,op['operationId'],{'phase':'SWITCHING'})
        with self.assertRaises(RuntimeError):self.m.select(self.root,op['operationId'],'wrong',g['generationId'])
        self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        self.assertEqual(self.m.resolve(self.root),g)
        self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
    def test_partial_generation_is_not_visible(self):
        old,op,g=self.prepare()
        self.assertEqual(self.m.resolve(self.root),old)
        Path(g['paths']['certificate']).write_bytes(b'changed')
        journal.record(self.root,op['operationId'],{'phase':'SWITCHING'})
        with self.assertRaises(RuntimeError):self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
    def test_original_candidate_can_be_reselected_after_previous_generation_selected(self):
        old,op,g=self.prepare();opid=op['operationId']
        journal.record(self.root,opid,{'phase':'SWITCHING'})
        self.m.select(self.root,opid,old['generationId'],g['generationId'])
        # Reproduce the exact legacy selection shape written by rollback.
        journal._write(self.root,self.root/'tls-selection.json',{'generationId':old['generationId'],'operationId':opid})
        journal._write(self.root,self.root/'tls/active.json',{'legacy':old,'operationId':opid})
        self.assertEqual(self.m.resolve(self.root)['generationId'],old['generationId'])
        self.m.select(self.root,opid,g['parentGenerationId'],g['generationId'])
        self.assertEqual(self.m.resolve(self.root)['generationId'],g['generationId'])
    def test_selection_intent_response_loss_recovers_without_legacy_fallback(self):
        from unittest.mock import patch
        old,op,g=self.prepare();journal.record(self.root,op['operationId'],{'phase':'SWITCHING'})
        write=journal._write
        def lost(root,path,payload):
            write(root,path,payload)
            if path.name=='tls-selection.json':raise SystemExit('intent persisted, pointer not written')
        with patch.object(journal,'_write',side_effect=lost):
            with self.assertRaises(SystemExit):self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        with self.assertRaises(RuntimeError):self.m.resolve(self.root)
        self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        self.assertEqual(self.m.resolve(self.root)['generationId'],g['generationId'])
    def test_second_selection_intent_loss_recovers(self):
        from unittest.mock import patch
        old,op,g=self.prepare();journal.record(self.root,op['operationId'],{'phase':'SWITCHING'})
        self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        with patch.object(tls_material,'stage',return_value=g['candidate']):old,op,g=self.prepare()
        journal.record(self.root,op['operationId'],{'phase':'SWITCHING'})
        write=journal._write
        def lost(root,path,payload):
            write(root,path,payload)
            if path.name=='tls-selection.json':raise SystemExit('lost')
        with patch.object(journal,'_write',side_effect=lost):
            with self.assertRaises(SystemExit):self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        with self.assertRaises(RuntimeError):self.m.resolve(self.root)
        self.m.select(self.root,op['operationId'],old['generationId'],g['generationId'])
        self.assertEqual(self.m.resolve(self.root),g)
