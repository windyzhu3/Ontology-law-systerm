import importlib
from pathlib import Path
import unittest
import tempfile
from ols_linux import journal
from ols_linux.bundle import sha

class QualificationTests(unittest.TestCase):
    def test_new_report_amendment_is_bound_to_original_registration_and_operation(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'production';op=journal.begin(root,'rotate-public-tls','a'*64)
            journal.record(root,op['operationId'],{'phase':'ROLLBACK_BLOCKED'})
            proofroot=Path(directory)/'proof';journal.begin(proofroot,'rotate-public-tls','b'*64)
            evidence=proofroot/'verification/Q.json';journal._write(proofroot,evidence,{'syntheticUnitTest':True})
            report=proofroot/'verification/systemd-proxy-qualification.json'
            journal._write(proofroot,report,{'status':'PASS','implementationDigest':m.implementation_digest(),'manager':{'comm':'systemd','pid':1},'cases':{name:{'status':'PASS'} for name in m.CASES},'evidence':{'verification/Q.json':sha(evidence)},'binaries':{'nginx':'binary'}})
            proof={'mode':'report','root':str(proofroot),'sha256':sha(report)}
            registration={'qualification':dict(proof,sha256='0'*64),'services':[{'role':'nginx','image':'binary'}]}
            with self.assertRaises(RuntimeError):m.require(root,registration)
            m.amend(root,op['operationId'],registration,proof)
            m.require(root,registration)
            with self.assertRaises(RuntimeError):m.require(root,dict(registration,services=[{'role':'nginx','image':'other'}]))
            receipt=root/'operations'/(op['operationId']+'-systemd-qualification-amendment.json')
            saved=receipt.read_bytes();m.amend(root,op['operationId'],registration,proof)
            self.assertEqual(receipt.read_bytes(),saved)
            journal.record(root,op['operationId'],{'phase':'COMPLETE'})
            m.require(root,dict(registration,qualification=proof))
            evidence.write_bytes(b'changed')
            with self.assertRaises(RuntimeError):m.require(root,registration)
    def module(self):
        try:return importlib.import_module('ols_linux.tls_systemd_qualification')
        except ImportError:self.fail('Systemd qualification admission missing')
    def test_fixture_exception_cannot_admit_production_names_or_paths(self):
        m=self.module();root=Path('/run/ols-tls-qualification/runtime')
        rows=[{'role':role,'name':'ols-tls-qualification-'+role+'.service','config':'/run/ols-tls-qualification/'+role+'/config','tlsPaths':{},'systemd':{'immutableFiles':[],'properties':{'LoadCredential':''},'listeners':[{'address':'127.0.0.1','port':port} for port in ((29845,29848) if role=='nginx' else (29846,29847))]}} for role in ('nginx','caddy')]
        m.fixture_scope(root,rows)
        with self.assertRaises(RuntimeError):m.fixture_scope(Path('/var/lib/production'),rows)
        with self.assertRaises(RuntimeError):m.fixture_scope(root,[dict(rows[0],name='production.service'),rows[1]])
        with self.assertRaises(RuntimeError):m.fixture_scope(root,[dict(rows[0],config='/etc/nginx/production.conf'),rows[1]])
    def test_arbitrary_pass_boolean_is_not_a_qualification(self):
        m=self.module()
        with self.assertRaises(RuntimeError):m.require(Path('/runtime'),{'qualification':{'status':'PASS'},'services':[]})

    def test_fixture_dropin_is_exact_and_listener_scope_cannot_broaden(self):
        m=self.module();root=Path('/run/ols-tls-qualification/runtime')
        rows=[{'role':role,'name':'ols-tls-qualification-'+role+'.service','config':'/run/ols-tls-qualification/'+role+'/config','tlsPaths':{},'systemd':{'immutableFiles':[],'properties':{'LoadCredential':''},'listeners':[{'address':'127.0.0.1','port':port} for port in ((29845,29848) if role=='nginx' else (29846,29847))]}} for role in ('nginx','caddy')]
        rows[1]['systemd']['immutableFiles']=[{'path':'/etc/systemd/system/ols-tls-qualification-caddy.service.d/50-ols-public-tls.conf'}]
        m.fixture_scope(root,rows)
        rows[0]['systemd']['listeners'][0]['address']='0.0.0.0'
        with self.assertRaises(RuntimeError):m.fixture_scope(root,rows)
        rows[0]['systemd']['listeners'][0]['address']='127.0.0.1'
        rows[1]['systemd']['immutableFiles'][0]['path']='/etc/systemd/system/foreign.service.d/50-ols-public-tls.conf'
        with self.assertRaises(RuntimeError):m.fixture_scope(root,rows)
