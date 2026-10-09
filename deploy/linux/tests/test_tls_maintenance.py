"""Policy-generation checks; actual nginx denial/forwarding is verified separately."""
import importlib
import unittest

class MaintenanceTests(unittest.TestCase):
    def module(self):
        try:return importlib.import_module('ols_linux.tls_maintenance')
        except ImportError:self.fail('Restricted issuer maintenance implementation missing')
    def args(self):
        return dict(listeners=['127.0.0.1:25443','127.0.0.1:25444'],identity_port=25443,realm='fixture',sources=['127.0.0.1','172.18.0.4'],upstream_port=24846,certificate='/runtime/new/cert.pem',private_key='/runtime/new/key.pem',trust='/runtime/certs/ca.pem')
    def test_policy_keeps_only_exact_issuer_health_paths(self):
        text=self.module().render(**self.args())
        self.assertIn('location = /realms/fixture/protocol/openid-connect/certs',text)
        self.assertIn('location = /realms/fixture/protocol/openid-connect/token/introspect',text)
        self.assertNotIn('location /admin',text)
        self.assertIn('location / { return 503; }',text)
        self.assertIn('allow 172.18.0.4;',text);self.assertIn('deny all;',text)
        self.assertIn('proxy_ssl_verify on;',text);self.assertIn('proxy_ssl_name localhost;',text)
        self.assertEqual(text.count('listen '),2)
    def test_shared_https_listener_has_one_server_and_restricted_identity_routes(self):
        text=self.module().render(**dict(self.args(),listeners=['443'],identity_port=443))
        self.assertEqual(text.count('listen 443 ssl;'),1)
        self.assertEqual(text.count('proxy_pass '),2)
        self.assertIn('location / { return 503; }',text)
        self.assertIn('deny all;',text)
        self.assertIn('proxy_ssl_verify on;',text)
    def test_duplicate_listener_input_is_refused_instead_of_creating_duplicate_servers(self):
        with self.assertRaises(ValueError):
            self.module().render(**dict(self.args(),listeners=['443','443'],identity_port=443))
    def test_shared_origin_and_issuer_require_one_original_listening_port(self):
        self.assertEqual(self.module().original_listeners('listen 443 ssl;',443,443),['443'])
        self.assertEqual(self.module().original_listeners('listen 25443 ssl; listen 25444 ssl;',25443,25444),['25443','25444'])
        for config in ['listen 443 ssl; listen 443 ssl;','listen 444 ssl;','listen 443 ssl; listen 80;']:
            with self.subTest(config=config),self.assertRaises(RuntimeError):
                self.module().original_listeners(config,443,443)
    def test_network_ranges_and_injection_are_refused(self):
        for key,value in [('sources',['172.18.0.0/16']),('realm','x; allow all'),('listeners',['0.0.0.0:25443;']),('trust','/x;bad')]:
            with self.subTest(key=key),self.assertRaises(ValueError):self.module().render(**dict(self.args(),**{key:value}))
    def test_maintenance_has_no_browser_token_or_admin_access(self):
        text=self.module().render(**self.args())
        self.assertEqual(text.count('proxy_pass '),2)
        self.assertIn('if ($request_method != GET) { return 405; }',text)
        self.assertIn('if ($request_method != POST) { return 405; }',text)
        self.assertNotIn('ssl_verify off',text)
    def test_restricted_proxy_starts_before_native_consumers(self):
        from unittest.mock import patch
        from pathlib import Path
        import tempfile
        from ols_linux import tls_rotation as r,journal
        events=[]
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);op=journal.begin(root,'rotate-public-tls','a'*64)
            journal.record(root,op['operationId'],{'phase':'ACTIVATING'})
            journal._write(root,root/'operations'/(op['operationId']+'-proxy.json'),{'services':[]})
            journal._write(root,root/'launch.json',{'ingress':{'name':'entry','digest':'d'}})
            with patch.object(r.runtime,'validate_tls'),patch.object(r,'_prepare_entry',return_value={'name':'entry','digest':'d'}),patch.object(r.tls_proxy,'apply',side_effect=lambda *a:events.append(a[-1])),patch.object(self.module(),'start',side_effect=lambda *a:events.append('restricted')),patch.object(r.runtime,'start_internal',side_effect=lambda *a:events.append('native')),patch.object(r.runtime,'owned',return_value={'Config':{'Labels':{'ols.launch':'d'}},'State':{'Running':True}}):
                r._start(root,op['operationId'],{'paths':{},'deployment':{'probeTargets':[]}}, {'release':{'descriptor':{}},'probeTargets':[]})
            self.assertLess(events.index('restricted') if 'restricted' in events else 999,events.index('native'))
    def test_public_http_does_not_silently_use_native_route(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from pathlib import Path
        import json
        from ols_linux import identity
        plan={'pod':'pod','issuer':'https://127.0.0.1:25443/realms/test','origin':'https://127.0.0.1:25444','apiOrigin':'https://localhost:24845','ports':{'identity':24843,'entry':24844,'api':24845}}
        with patch.object(identity.journal,'_read',return_value=plan),patch.object(identity.runtime,'owned'),patch.object(identity.runtime,'load',return_value={'publicTlsHashes':{}}),patch.object(identity.tls_generation,'managed',return_value=True),patch('ols_linux.public_runtime.effective_paths',return_value={'httpTrust':'/new/trust'}),patch.object(identity,'http_helper',return_value='/helper'),patch.object(identity.runtime,'run',return_value=SimpleNamespace(stdout=b'{}')) as run:
            identity.http(Path('/runtime'),plan['origin']+'/',public=True)
            request=json.loads(run.call_args.args[1]);self.assertNotIn('connectHost',request);self.assertEqual(request['ca'],'/new/trust')
    def test_unused_nginx_config_and_wrapper_commands_are_refused(self):
        module=self.module();self.assertTrue(callable(getattr(module,'validate_start',None)),'Actual nginx startup binding missing')
        from pathlib import Path
        service={'transport':'docker','config':'/runtime/proxy/nginx.conf'}
        for actual in [{'Config':{'Entrypoint':['sh'],'Cmd':['-c','nginx']}},{'Config':{'Entrypoint':['nginx'],'Cmd':['-c','/different.conf','-g','daemon off;']}}]:
            with self.assertRaises(RuntimeError):module.validate_start(service,actual,Path('/runtime'))
        module.validate_start(service,{'Config':{'Entrypoint':['nginx'],'Cmd':['-c',service['config'],'-g','daemon off;']}},Path('/runtime'))
    def test_shadowed_runtime_mount_is_refused(self):
        from pathlib import Path
        service={'transport':'docker','config':'/runtime/proxy/nginx.conf'}
        for destination in ['/runtime/proxy','/runtime/proxy/nginx.conf','/runtime/certs']:
            actual={'Config':{'Entrypoint':['nginx'],'Cmd':['-c',service['config'],'-g','daemon off;']},'Mounts':[{'Destination':destination}]}
            with self.assertRaises(RuntimeError):self.module().validate_start(service,actual,Path('/runtime'))
    def test_committed_start_response_loss_can_reconcile_same_configuration(self):
        from pathlib import Path
        from unittest.mock import patch
        from types import SimpleNamespace
        import tempfile
        from ols_linux import journal,tls_proxy,runtime
        module=self.module()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);op=journal.begin(root,'rotate-public-tls','a'*64);journal.record(root,op['operationId'],{'phase':'ACTIVATING'})
            service={'config':str(root/'proxy/nginx.conf'),'name':'nginx','transport':'docker'}
            value={'service':service,'configuration':'restricted'};running=[False];lost=[False]
            def action(service,action):
                running[0]=action=='start'
                if action=='start' and not lost[0]:lost[0]=True;raise RuntimeError('start response lost')
            with patch.object(module,'prepare',return_value=value),patch.object(tls_proxy,'_service',side_effect=lambda s:{'running':running[0]}),patch.object(tls_proxy,'_action',side_effect=action),patch.object(runtime,'run',return_value=SimpleNamespace(stdout=b'restricted')):
                with self.assertRaisesRegex(RuntimeError,'response lost'):module.start(root,op['operationId'],[],[],{})
                module.start(root,op['operationId'],[],[],{})
                self.assertTrue(running[0]);self.assertEqual(Path(service['config']).read_text(),'restricted')
    def test_effective_container_config_mismatch_prevents_maintenance_success(self):
        from pathlib import Path
        from unittest.mock import patch
        from types import SimpleNamespace
        import tempfile
        from ols_linux import journal,tls_proxy,runtime
        module=self.module()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);op=journal.begin(root,'rotate-public-tls','a'*64);journal.record(root,op['operationId'],{'phase':'ACTIVATING'})
            service={'config':str(root/'proxy/nginx.conf'),'name':'nginx','transport':'docker'}
            value={'service':service,'configuration':'restricted'};running=[False]
            with patch.object(module,'prepare',return_value=value),patch.object(tls_proxy,'_service',side_effect=lambda s:{'running':running[0]}),patch.object(tls_proxy,'_action',side_effect=lambda s,a:running.__setitem__(0,a=='start')),patch.object(runtime,'run',return_value=SimpleNamespace(stdout=b'other-config')):
                with self.assertRaisesRegex(RuntimeError,'effective'):module.start(root,op['operationId'],[],[],{})
