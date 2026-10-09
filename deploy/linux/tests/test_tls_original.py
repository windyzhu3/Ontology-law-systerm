import importlib
from pathlib import Path
import tempfile
import unittest
from tls_fixtures import materials


class OriginalTlsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'materials')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def module(self):
        try:return importlib.import_module('ols_linux.tls_original')
        except ImportError:self.fail('Per-target original TLS bindings missing')
    def fixture(self):
        f=self.f
        from ols_linux import tls_material
        import ssl
        native={'leafDerSha256':f['fingerprint'](f['old-leaf']),'notAfter':int(ssl.cert_time_to_seconds(tls_material.metadata(f['old-leaf'])['notAfter']))}
        old={'version':0,'generationId':'a'*64,'candidate':native,'paths':{'certificate':str(f['old-leaf'])}}
        registration={'version':2,'services':[{'role':'nginx','tlsPaths':{'certificate':str(f['new-leaf']),'privateKey':str(f['directory']/'new-leaf.key')}}]}
        return old,registration
    def test_distinct_original_public_and_native_leaves_are_bound_separately(self):
        m=self.module();old,registration=self.fixture()
        bound=m.capture(old,registration)
        self.assertEqual(bound['originalPublicTargets']['nativeIdentity']['leafDerSha256'],self.f['fingerprint'](self.f['old-leaf']))
        self.assertEqual(bound['originalPublicTargets']['publicIdentity']['leafDerSha256'],self.f['fingerprint'](self.f['new-leaf']))
        self.assertEqual(old.get('originalPublicTargets'),None)
        m.validate(bound,now=self.f['now'])
    def test_valid_external_certificate_does_not_mask_expired_native_original(self):
        m=self.module();old,registration=self.fixture();bound=m.capture(old,registration)
        cutoff=bound['originalPublicTargets']['nativeIdentity']['notAfter']
        with self.assertRaisesRegex(RuntimeError,'Expired'):m.validate(bound,now=cutoff)
    def test_live_symlink_retargeting_is_not_original_rollback_evidence(self):
        m=self.module();old,registration=self.fixture()
        with tempfile.TemporaryDirectory() as folder:
            link=Path(folder)/'live.crt';link.symlink_to(self.f['new-leaf'])
            registration['services'][0]['tlsPaths']['certificate']=str(link)
            bound=m.capture(old,registration);link.unlink();link.symlink_to(self.f['old-leaf'])
            with self.assertRaises(RuntimeError):m.validate(bound,now=self.f['now'])
    def test_managed_generation_drift_is_not_silently_adopted(self):
        m=self.module();old,registration=self.fixture();old['version']=1
        with self.assertRaisesRegex(RuntimeError,'managed'):m.capture(old,registration)
    def test_new_candidate_cannot_be_shorter_than_the_external_original(self):
        m=self.module();old,registration=self.fixture();bound=m.capture(old,registration)
        with self.assertRaises(RuntimeError):m.require_newer(bound,{'notAfter':old['candidate']['notAfter']+1})
