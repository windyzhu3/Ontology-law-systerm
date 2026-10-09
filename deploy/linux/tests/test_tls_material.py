import importlib
import os
from pathlib import Path
import tempfile
import unittest
from tls_fixtures import materials,instance,inputs


class MaterialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.base=Path(cls.tmp.name);cls.f=materials(cls.base/'source')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=instance(Path(self.temp.name),self.f)
        try:self.m=importlib.import_module('ols_linux.tls_material')
        except ImportError:self.fail('staged public TLS validation is missing')
    def test_ip_chain_key_and_anchor_admission(self):
        for leaf in ['new-leaf','rsa-leaf']:
            c=self.m.stage(self.root,inputs(self.f,leaf),now=self.f['now'])
            self.assertEqual(c['leafDerSha256'],self.f['fingerprint'](self.f[leaf]))
            self.assertEqual(c['anchorFingerprints'],[self.f['fingerprint'](self.f['new'])])
    def test_snapshot_survives_source_replacement(self):
        c=self.m.stage(self.root,inputs(self.f),now=self.f['now'])
        original=self.f['new-leaf'].read_bytes()
        try:
            self.f['new-leaf'].write_bytes(b'changed')
            self.assertEqual(self.m.verify(Path(c['directory']),c,now=self.f['now'])['leafDerSha256'],c['leafDerSha256'])
        finally:self.f['new-leaf'].write_bytes(original)
        (Path(c['directory'])/'certificate.pem').write_bytes(b'changed')
        with self.assertRaises(RuntimeError):self.m.verify(Path(c['directory']),c,now=self.f['now'])
    def test_link_fifo_permissions_and_intermediate_as_anchor_refused(self):
        values=inputs(self.f);link=Path(self.temp.name)/'linked';link.symlink_to(self.f['new-leaf'])
        values['certificate']=str(link)
        with self.assertRaises(RuntimeError):self.m.stage(self.root,values,now=self.f['now'])
        link.unlink();os.mkfifo(link);values['certificate']=str(link)
        with self.assertRaises(RuntimeError):self.m.stage(self.root,values,now=self.f['now'])
        values=inputs(self.f);values['approvedAnchors']=[str(self.f['new-leaf'])]
        with self.assertRaises(RuntimeError):self.m.stage(self.root,values,now=self.f['now'])
        key=self.f['directory']/'new-leaf.key';key.chmod(0o644)
        try:
            with self.assertRaises(RuntimeError):self.m.stage(self.root,inputs(self.f),now=self.f['now'])
        finally:key.chmod(0o600)
    def test_validity_and_unknown_fields_refused(self):
        for changes in [{'origins':['https://192.0.2.1']},{'privateKey':str(self.f['directory']/'rsa-leaf.key')},{'extra':True},{'approvedAnchors':[str(self.f['untrusted'])]}]:
            with self.assertRaises(RuntimeError):self.m.stage(self.root,dict(inputs(self.f),**changes),now=self.f['now'])
        with self.assertRaises(RuntimeError):self.m.stage(self.root,inputs(self.f),now=self.f['now']+24*86400)
        with self.assertRaises(RuntimeError):self.m.stage(self.root,inputs(self.f),now=self.f['now']-86400)
