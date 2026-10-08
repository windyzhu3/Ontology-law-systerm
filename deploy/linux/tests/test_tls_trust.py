import tempfile
from pathlib import Path
import unittest
from ols_linux import journal,tls_generation as g,tls_material
from tls_fixtures import materials,instance,inputs

class TrustTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        self.tmp2=tempfile.TemporaryDirectory();self.addCleanup(self.tmp2.cleanup)
        self.root=instance(Path(self.tmp2.name),self.f)
        self.assertTrue(callable(getattr(g,'build_trust',None)),'transition trust generation missing')
        c=tls_material.stage(self.root,inputs(self.f),now=self.f['now']);self.c=c
        self.output=self.root/'tls/trust-test'
    def test_pem_and_store_exact_der_anchor_set(self):
        trust=g.build_trust(self.root,self.c,self.output)
        self.assertEqual(set(trust['anchorFingerprints']),{self.f['fingerprint'](self.f[n]) for n in ['internal','old','new']})
        self.assertIsNone(g.verify_trust(self.root,trust))
        self.assertFalse((self.root/'certs/identity-trust.p12').exists())
    def test_alias_collision_and_unauthorized_anchor_refused(self):
        trust=g.build_trust(self.root,self.c,self.output)
        self.assertEqual(g.build_trust(self.root,self.c,self.output),trust)
        pem=Path(trust['httpTrust']);pem.write_bytes(self.f['untrusted'].read_bytes())
        with self.assertRaises(RuntimeError):g.verify_trust(self.root,trust)
        with self.assertRaises(RuntimeError):g.build_trust(self.root,self.c,self.output)

    def test_partial_import_resumes(self):
        from unittest.mock import patch
        real=tls_material.tool
        count=[0]
        def interrupt(root,binary,args,**kwargs):
            if '-importcert' in args:
                count[0]+=1
                if count[0]==2:raise RuntimeError('response unavailable')
            return real(root,binary,args,**kwargs)
        with patch.object(tls_material,'tool',side_effect=interrupt):
            with self.assertRaises(RuntimeError):g.build_trust(self.root,self.c,self.output)
        trust=g.build_trust(self.root,self.c,self.output)
        self.assertIsNone(g.verify_trust(self.root,trust))
    def test_unexpected_alias_in_partial_store_is_refused(self):
        from unittest.mock import patch
        real=tls_material.tool;count=[0]
        def interrupt(root,binary,args,**kwargs):
            if '-importcert' in args:
                count[0]+=1
                if count[0]==2:raise RuntimeError('interrupted')
            return real(root,binary,args,**kwargs)
        with patch.object(tls_material,'tool',side_effect=interrupt):
            with self.assertRaises(RuntimeError):g.build_trust(self.root,self.c,self.output)
        real(self.root,'keytool',['-importcert','-noprompt','-alias','unexpected-root','-file',self.f['untrusted'],'-keystore',self.output/'identity-trust.p12','-storetype','PKCS12','-storepass:file',self.root/'secrets/trust-password.txt'])
        with self.assertRaisesRegex(RuntimeError,'inventory'):g.build_trust(self.root,self.c,self.output)
    def test_next_trust_keeps_verified_active_anchors(self):
        from unittest.mock import patch
        from ols_linux import runtime
        trust=g.build_trust(self.root,self.c,self.output)
        candidate=dict(self.c,directory=str(self.root/'next-candidate'),inputDigest='b'*64)
        runtime.private_file(Path(candidate['directory'])/'anchors.pem',self.f['old'].read_bytes())
        with patch.object(g,'resolve',return_value={'version':1,'trust':trust}):
            next_trust=g.build_trust(self.root,candidate,self.root/'tls/next-trust')
        self.assertEqual(next_trust['anchorFingerprints'],trust['anchorFingerprints'])
