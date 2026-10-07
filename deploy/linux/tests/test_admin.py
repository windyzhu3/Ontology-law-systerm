import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import base64
import time
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from ols_linux import journal, runtime


class AdminTests(unittest.TestCase):
    def setUp(self):
        try:self.m=importlib.import_module('ols_linux.admin')
        except ImportError:self.fail('Original HUMAN administration command adapter missing')
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';self.op=journal.begin(self.root,'initialize','a'*64)
        self.command={'commandId':'00000000-0000-4000-8000-000000000001','path':'/api/v1/admin/identity/organizations','body':{'code':'SALES_1'},'precondition':None,'actor':{'username':'dingqiming','appointmentId':'00000000-0000-4000-8000-000000000002'}}
        self.session=self.root/'session.json';runtime.private_file(self.session,b'{}')
        patch.object(self.m,'_headers',return_value={'Authorization':'Bearer protected'}).start()
        self.addCleanup(patch.stopall)
        self.receipt={'commandId':self.command['commandId'],'outcome':'SUCCEEDED','resultFact':{'factRef':'protected'}}

    def test_lost_response_preserves_original_key_body_then_reads_receipt(self):
        def uncertain(*args,**kwargs):
            saved=journal._read(self.root,self.root/'admin-commands'/ (self.command['commandId']+'.json'))
            self.assertEqual(saved['command'],self.command);self.assertEqual(saved['state'],'DISPATCH_UNKNOWN')
            raise RuntimeError('Lost response')
        with patch.object(self.m,'_request',side_effect=uncertain):
            with self.assertRaises(RuntimeError):self.m.execute(self.root,self.op['operationId'],self.command,self.session)
        with patch.object(self.m,'_request',return_value={'status':200,'body':json.dumps(self.receipt)}):
            result=self.m.reconcile(self.root,self.op['operationId'],self.command['commandId'],self.session)
            self.assertEqual(result,self.receipt)
        with patch.object(self.m,'_request',side_effect=AssertionError('Completed commands must not redispatch')):
            self.assertEqual(self.m.execute(self.root,self.op['operationId'],self.command,self.session),self.receipt)

    def test_same_key_changed_body_or_actor_is_rejected_without_write(self):
        with patch.object(self.m,'_request',return_value={'status':201,'body':json.dumps(self.receipt)}):self.m.execute(self.root,self.op['operationId'],self.command,self.session)
        for change in [{'body':{'code':'SALES_2'}},{'actor':{'username':'huangxuexue','appointmentId':self.command['actor']['appointmentId']}}]:
            with patch.object(self.m,'_request',side_effect=AssertionError('Changed original must never be sent')):
                with self.assertRaises(RuntimeError):self.m.execute(self.root,self.op['operationId'],dict(self.command,**change),self.session)

    def test_unknown_reconciliation_never_allocates_or_sends_new_command(self):
        with patch.object(self.m,'_request',side_effect=RuntimeError('Lost response')):
            with self.assertRaises(RuntimeError):self.m.execute(self.root,self.op['operationId'],self.command,self.session)
        with patch.object(self.m,'_request',return_value={'status':503,'body':'{}'}) as req:
            with self.assertRaises(RuntimeError):self.m.reconcile(self.root,self.op['operationId'],self.command['commandId'],self.session)
            self.assertEqual(req.call_count,1)
        record=journal._read(self.root,self.root/'admin-commands'/(self.command['commandId']+'.json'))
        self.assertEqual(record['state'],'DISPATCH_UNKNOWN');self.assertEqual(record['command'],self.command)

    def test_technical_service_and_unowned_operation_rejected(self):
        with self.assertRaises((ValueError,RuntimeError)):self.m.execute(self.root,self.op['operationId'],dict(self.command,actor={'username':'service','appointmentId':self.command['actor']['appointmentId']}),self.session)
        with self.assertRaises(RuntimeError):self.m.execute(self.root,'b'*32,self.command,self.session)


class HumanSessionTests(unittest.TestCase):
    def setUp(self):
        self.m=importlib.import_module('ols_linux.admin')
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';journal.begin(self.root,'initialize','a'*64)
        self.plan={'issuer':'https://localhost:24843/realms/own','subjects':{'dingqiming':'own-ding','huangxuexue':'own-huang'},'spaClient':'own-spa','audience':'own-api'}
        journal._write(self.root,self.root/'identity/plan.json',self.plan)
        self.file=self.root/'session.json'

    def value(self,subject='own-ding',expires=None,refresh='original-refresh'):
        claims={'iss':self.plan['issuer'],'sub':subject,'aud':['own-api'],'exp':expires or int(time.time())+300}
        token='header.'+base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('=')+'.signature'
        return {'issuer':self.plan['issuer'],'subject':subject,'clientId':'own-spa','accessToken':token,'refreshToken':refresh}

    def test_protected_pretty_json_is_valid_but_foreign_subject_is_not(self):
        runtime.private_file(self.file,json.dumps(self.value(),indent=2).encode())
        self.assertEqual(self.m.session(self.root,self.file,'dingqiming')['subject'],'own-ding')
        runtime.private_file(self.file,json.dumps(self.value('own-huang')).encode())
        with patch.object(self.m.identity,'http',side_effect=AssertionError('Foreign session must not authenticate')):
            with self.assertRaises(RuntimeError):self.m.session(self.root,self.file,'dingqiming')

    def test_unknown_rotation_refuses_the_exact_old_refresh_without_retry(self):
        from ols_linux.config import digest
        value=self.value(expires=1);runtime.private_file(self.file,json.dumps(value).encode())
        journal._write(self.root,self.root/'identity/refresh-dingqiming.json',{'state':'UNKNOWN','refreshDigest':digest({'refresh':value['refreshToken']})})
        with patch.object(self.m.identity,'http',side_effect=AssertionError('Unknown refresh must not be retried')):
            with self.assertRaises(RuntimeError):self.m.session(self.root,self.file,'dingqiming')

    def test_fresh_original_human_browser_session_can_recover_unknown_old_rotation(self):
        from ols_linux.config import digest
        value=self.value(expires=1,refresh='fresh-browser-refresh');runtime.private_file(self.file,json.dumps(value).encode())
        journal._write(self.root,self.root/'identity/refresh-dingqiming.json',{'state':'UNKNOWN','refreshDigest':digest({'refresh':'old-unknown-refresh'})})
        fresh=self.value(refresh='rotated-fresh-refresh')
        with patch.object(self.m.identity,'http',return_value={'status':200,'body':json.dumps({'access_token':fresh['accessToken'],'refresh_token':fresh['refreshToken']})}) as request:
            result=self.m.session(self.root,self.file,'dingqiming')
        self.assertEqual(result['refreshToken'],'rotated-fresh-refresh')
        self.assertIn('refresh_token=fresh-browser-refresh',request.call_args.kwargs['body'])
        self.assertEqual(journal._read(self.root,self.root/'identity/refresh-dingqiming.json')['state'],'VERIFIED')

    def test_current_appointment_must_be_exact_and_direct(self):
        runtime.private_file(self.file,json.dumps(self.value()).encode())
        actor={'username':'dingqiming','appointmentId':'00000000-0000-4000-8000-000000000001'}
        for selected,behalf in [('00000000-0000-4000-8000-000000000002',None),(actor['appointmentId'],'00000000-0000-4000-8000-000000000003')]:
            response={'state':'READY','selectedAppointmentId':selected,'selectedOnBehalfAppointmentId':behalf,'canEnterIdentityAdmin':True}
            with patch.object(self.m,'_request',return_value={'status':200,'body':json.dumps(response)}):
                with self.assertRaises(RuntimeError):self.m._headers(self.root,actor,self.file)
