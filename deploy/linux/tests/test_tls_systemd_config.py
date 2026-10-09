"""Bounded nginx configuration graph and metadata preservation."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_systemd


class SystemdConfigTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'runtime';self.op=journal.begin(self.root,'rotate-public-tls','a'*64)
        base=Path(self.tmp.name)/'nginx';base.mkdir();self.main=base/'nginx.conf';self.conf=base/'conf.d';self.conf.mkdir()
        self.http=self.conf/'http.conf';self.https=self.conf/'https.conf'
        self.http.write_text('server { listen 29845; location /challenge { return 200; } }\n')
        self.https.write_text('server { listen 29848 ssl default_server;\n ssl_certificate /old/public.crt;\n ssl_certificate_key /old/public.key;\n}\n')
        self.mime=base/'mime.types';self.mime.write_text('types {}\n')
        self.main.write_text('events {}\nhttp { include '+str(self.mime)+'; include '+str(self.conf/'*.conf')+'; }\n')
        for path in (self.main,self.http,self.https):path.chmod(0o644)
        def record(path):
            st=path.stat();return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'uid':st.st_uid,'gid':st.st_gid,'mode':st.st_mode&0o777}
        self.service={'role':'nginx','transport':'systemd','name':'fixture.service','config':str(self.https),'configSha256':record(self.https)['sha256'],
                      'tlsPaths':{'certificate':'/old/public.crt','privateKey':'/old/public.key'},
                      'systemd':{'mainConfig':str(self.main),'immutableFiles':[record(p) for p in [self.mime,self.main,self.http,self.https]],'includes':{str(self.conf/'*.conf'):sorted([str(self.http),str(self.https)])}}}

    def test_candidate_checks_complete_graph_and_keeps_http_fragment_untouched(self):
        self.assertTrue(callable(getattr(tls_systemd,'nginx_candidate',None)),'Complete nginx graph candidate missing')
        candidate=self.root/'proxy/new.conf';runtime.private_file(candidate,b'server { listen 29848 ssl; }\n')
        before=self.http.read_bytes()
        main=tls_systemd.nginx_candidate(self.service,candidate)
        text=main.read_text();self.assertIn(str(candidate),text);self.assertIn(str(self.http),text)
        self.assertNotIn(str(self.conf/'*.conf'),text);self.assertEqual(self.http.read_bytes(),before)
        (self.conf/'foreign.conf').write_text('foreign')
        with self.assertRaises(RuntimeError):tls_systemd.nginx_candidate(self.service,candidate)

    def test_atomic_live_config_write_preserves_registered_owner_and_mode(self):
        self.assertTrue(callable(getattr(tls_systemd,'write_configuration',None)),'Metadata-preserving configuration install missing')
        self.https.chmod(0o644);record=self.service['systemd']['immutableFiles'][-1];record['mode']=0o644
        before=self.https.read_bytes();after=b'server { listen 29848 ssl; }\n'
        tls_systemd.write_configuration(self.service,after,allowed=[before,after])
        self.assertEqual(self.https.read_bytes(),after);self.assertEqual(self.https.stat().st_mode&0o777,0o644)
        self.https.write_text('foreign')
        with self.assertRaises(RuntimeError):tls_systemd.write_configuration(self.service,before,allowed=[before,after])

    def test_only_exact_declared_tls_directives_are_replaced(self):
        self.assertTrue(callable(getattr(tls_systemd,'replace_public_paths',None)),'Bounded nginx TLS replacement missing')
        data=self.https.read_bytes();paths={'certificate':'/new/cert','privateKey':'/new/key'}
        result=tls_systemd.replace_public_paths(self.service,data,paths)
        self.assertIn(b'ssl_certificate /new/cert;',result);self.assertIn(b'ssl_certificate_key /new/key;',result)
        with self.assertRaises(RuntimeError):tls_systemd.replace_public_paths(self.service,data+b'ssl_certificate /foreign;\n',paths)

    def test_v2_registration_seals_external_graph_without_chmod_or_relocation(self):
        from ols_linux import tls_proxy,tls_systemd_qualification
        before={str(p):p.read_bytes() for p in [self.main,self.http,self.https]}
        service=dict(self.service,identity='a'*64,image='sha256:'+'b'*64)
        registration={'version':2,'qualification':{'mode':'report','root':'/private/report','sha256':'c'*64},'services':[service]}
        with patch.object(tls_systemd_qualification,'require'),patch.object(tls_systemd,'observe_service',return_value={'running':True,'process':{'pid':1},'credentials':{}}):
            self.assertEqual(tls_proxy.validate_registration(self.root,registration),registration)
            row=tls_proxy.prepare(self.root,self.op['operationId'],registration,{'certificate':'/new/cert','privateKey':'/new/key','httpTrust':'/new/trust'})['services'][0]
        self.assertIn('ssl_certificate /new/cert;',row['after'])
        self.assertEqual(before,{str(p):p.read_bytes() for p in [self.main,self.http,self.https]})
        self.assertEqual(self.https.stat().st_mode&0o777,0o644)

class GraphClosureTests(unittest.TestCase):
    def test_nested_nginx_include_and_caddy_import_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary);main=base/'nginx.conf';http=base/'http.conf';https=base/'https.conf';mime=base/'mime.types'
            main.write_text('http { include '+str(mime)+'; include '+str(base/'*.conf')+'; }')
            http.write_text('server {}');https.write_text('server {}');mime.write_text('types {}')
            service={'role':'nginx','config':str(https),'systemd':{'mainConfig':str(main),'immutableFiles':[{'path':str(p)} for p in (main,http,https,mime)],'includes':{str(base/'*.conf'):[str(http),str(https)]}}}
            tls_systemd.verify_configuration_graph(service)
            http.write_text('include /unregistered/secret.conf;')
            with self.assertRaisesRegex(RuntimeError,'Nested'):tls_systemd.verify_configuration_graph(service)
            caddy=base/'bridge.Caddyfile';caddy.write_text('import /unregistered/config\n')
            with self.assertRaisesRegex(RuntimeError,'import'):tls_systemd.verify_configuration_graph({'role':'caddy','config':str(caddy),'systemd':{'mainConfig':str(caddy),'immutableFiles':[{'path':str(caddy)}],'includes':{}}})
