"""Real temporary-file transactions; systemd boundary is simulated here only."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_systemd,tls_proxy


class CredentialTransactionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name);self.root=self.base/'runtime'
        self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        journal.record(self.root,self.op['operationId'],{'phase':'PROXY_SWITCHING'})
        self.unit=self.base/'isolated-caddy.service';self.unit.write_text('immutable original unit')
        st=self.unit.stat();record={'path':str(self.unit),'sha256':hashlib.sha256(self.unit.read_bytes()).hexdigest(),'uid':st.st_uid,'gid':st.st_gid,'mode':st.st_mode&0o777}
        sources={name:str(self.base/name) for name in ['server.crt','server.key','internal-ca.pem','issuer-ca.pem']}
        profile={'unitFile':record,'immutableFiles':[],'properties':{'DropInPaths':'','LoadCredential':' '.join(k+':'+v for k,v in sorted(sources.items()))},'credentialNames':sorted(sources)}
        self.service={'role':'caddy','transport':'systemd','name':self.unit.name,'identity':record['sha256'],'systemd':profile}
        journal._write(self.root,self.root/'operations'/(self.op['operationId']+'-proxy.json'),{'services':[{'service':self.service}]})
        self.gid='b'*64;self.trust=self.root/'tls/generations'/self.gid/'http-trust.pem';runtime.private_file(self.trust,b'synthetic new root')
        self.dropin=self.unit.parent/(self.unit.name+'.d')/'50-ols-public-tls.conf'

    def invoke(self,action='forward'):
        self.assertTrue(callable(getattr(tls_systemd,'switch_credential_sources',None)),'Original-operation credential source transaction missing')
        with patch.object(tls_proxy,'observe',return_value={'closed':True}),patch.object(runtime,'run') as run:
            result=tls_systemd.switch_credential_sources(self.root,self.op['operationId'],self.service,self.gid,action)
        return result,run

    def test_new_override_is_bounded_sealed_and_original_unit_is_unchanged(self):
        result,run=self.invoke()
        self.assertEqual(self.unit.read_text(),'immutable original unit')
        self.assertIn(str(self.trust),self.dropin.read_text())
        self.assertEqual(self.dropin.stat().st_mode&0o777,0o600)
        self.assertEqual(result['systemd']['properties']['DropInPaths'],str(self.dropin))
        self.assertEqual(run.call_args.args[0],['systemctl','daemon-reload'])

    def test_resume_preserves_exact_intent_and_rollback_removes_only_owned_override(self):
        self.invoke();before=self.dropin.read_bytes();self.invoke();self.assertEqual(self.dropin.read_bytes(),before)
        journal.record(self.root,self.op['operationId'],{'phase':'ROLLBACK_SWITCHING'})
        result,_=self.invoke('rollback');self.assertFalse(self.dropin.exists());self.assertEqual(result,self.service)
        self.assertEqual(self.unit.read_text(),'immutable original unit')

    def test_unregistered_dropin_or_trust_drift_is_refused_before_daemon_reload(self):
        self.invoke();self.dropin.with_name('foreign.conf').write_text('unknown unit override')
        with patch.object(tls_proxy,'observe',return_value={'closed':True}),patch.object(runtime,'run') as run:
            with self.assertRaises(RuntimeError):
                tls_systemd.switch_credential_sources(self.root,self.op['operationId'],self.service,self.gid,'forward')
            run.assert_not_called()
        self.dropin.with_name('foreign.conf').unlink();self.trust.write_bytes(b'changed')
        with self.assertRaises(RuntimeError):self.invoke()
