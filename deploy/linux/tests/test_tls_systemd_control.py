"""Isolated contract tests, not evidence that real systemd units were qualified."""
import hashlib
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_systemd,tls_proxy


class SystemdControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        self.service={'role':'caddy','transport':'systemd','name':'isolated-bridge.service','identity':'a'*64,'systemd':{'credentialNames':['issuer-ca.pem']}}
        journal._write(self.root,self.root/'operations'/(self.op['operationId']+'-proxy.json'),{'services':[{'service':self.service}]})
        journal.record(self.root,self.op['operationId'],{'phase':'PROXY_SWITCHING'})
        outer=patch.object(tls_proxy,'observe',return_value={'closed':True});outer.start();self.addCleanup(outer.stop)
        self.old={'running':True,'process':{'pid':10,'startTicks':20},'credentials':{'issuer-ca.pem':'a'*64}}
        self.new={'running':True,'process':{'pid':11,'startTicks':21},'credentials':{'issuer-ca.pem':'b'*64}}
        self.stopped={'running':False,'process':None,'credentials':{}}

    def control(self,action='load',expected=None):
        return tls_systemd.control(self.root,self.op['operationId'],self.service,action,expected or {'issuer-ca.pem':'b'*64})

    def test_changed_loadcredential_uses_observed_stop_start_not_reload(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped,self.new]),patch.object(runtime,'run',return_value=SimpleNamespace(returncode=0,stdout=b'')) as run:
            proof=self.control()
        self.assertEqual([c.args[0] for c in run.call_args_list],[['systemctl','stop',self.service['name']],['systemctl','start',self.service['name']]])
        self.assertEqual(proof['credentials'],self.new['credentials'])

    def test_active_state_without_qualified_process_cannot_reach_systemctl(self):
        with patch.object(tls_proxy,'_service',return_value={'running':True}),patch.object(runtime,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'Qualified'):self.control()
            run.assert_not_called()

    def test_unchanged_original_process_cannot_prove_loaded_credentials(self):
        stale=dict(self.new,process=self.old['process'])
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped,stale]),patch.object(runtime,'run'):
            with self.assertRaisesRegex(RuntimeError,'restart'):self.control()

    def test_open_outer_proxy_blocks_credential_effects(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        with patch.object(tls_proxy,'observe',return_value={'closed':False}),patch.object(tls_systemd,'observe') as observe,patch.object(runtime,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'closed'):self.control()
            observe.assert_not_called();run.assert_not_called()

    def test_unconfirmed_stop_never_starts_service(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.old]),patch.object(runtime,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'stop'):self.control()
        self.assertEqual(len(run.call_args_list),1)

    def test_stale_loaded_credentials_never_produce_success(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        stale=dict(self.new,credentials=self.old['credentials'])
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped,stale]),patch.object(runtime,'run'):
            with self.assertRaisesRegex(RuntimeError,'credential'):self.control()

    def test_start_response_loss_resumes_original_intent_without_second_restart(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped]),patch.object(runtime,'run',side_effect=[SimpleNamespace(),RuntimeError('response lost')]):
            with self.assertRaisesRegex(RuntimeError,'response lost'):self.control()
        with patch.object(tls_systemd,'observe',return_value=self.new),patch.object(runtime,'run') as run:
            self.assertEqual(self.control()['process'],self.new['process']);run.assert_not_called()

    def test_registered_service_and_original_phase_are_required_before_any_effect(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        for change in ['phase','service']:
            with self.subTest(change=change):
                if change=='phase':journal.record(self.root,self.op['operationId'],{'phase':'CREATED'})
                else:
                    journal.record(self.root,self.op['operationId'],{'phase':'PROXY_SWITCHING'})
                    self.service=dict(self.service,name='foreign.service')
                with patch.object(tls_systemd,'observe') as observe,patch.object(runtime,'run') as run:
                    with self.assertRaises(RuntimeError):self.control()
                    observe.assert_not_called();run.assert_not_called()

    def test_changed_generation_cannot_replace_original_load_intent(self):
        self.assertTrue(callable(getattr(tls_systemd,'control',None)),'Missing phase-bound systemd control')
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped]),patch.object(runtime,'run',side_effect=RuntimeError('stop response lost')):
            with self.assertRaises(RuntimeError):self.control()
        with patch.object(tls_systemd,'observe') as observe,patch.object(runtime,'run') as run:
            with self.assertRaisesRegex(RuntimeError,'intent'):self.control(expected={'issuer-ca.pem':'c'*64})
            observe.assert_not_called();run.assert_not_called()

    def test_identical_credentials_still_require_new_process_on_first_load(self):
        fresh=dict(self.new,credentials=self.old['credentials'])
        with patch.object(tls_systemd,'observe',side_effect=[self.old,self.stopped,fresh]),patch.object(runtime,'run') as run:
            self.assertEqual(self.control(expected=self.old['credentials']),fresh)
            self.assertEqual(run.call_count,2)
