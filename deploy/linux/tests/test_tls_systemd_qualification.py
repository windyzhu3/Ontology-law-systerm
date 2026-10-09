import importlib
from pathlib import Path
import unittest

class QualificationTests(unittest.TestCase):
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
