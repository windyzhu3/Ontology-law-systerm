import importlib
from pathlib import Path
import tempfile
import threading
import socketserver
import ssl
import unittest
from tls_fixtures import materials

class ProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.f=materials(Path(cls.tmp.name)/'source')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def setUp(self):
        try:self.m=importlib.import_module('ols_linux.tls_probe')
        except ImportError:self.fail('native TLS fingerprint probe missing')
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):pass
        self.server=socketserver.TCPServer(('127.0.0.1',0),Handler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(self.f['old-leaf'],self.f['directory']/'old-leaf.key')
        self.server.socket=context.wrap_socket(self.server.socket,server_side=True)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.addCleanup(self.server.server_close);self.addCleanup(self.server.shutdown)
        self.target={'connectHost':'127.0.0.1','connectPort':self.server.server_address[1],'verifyHost':'127.0.0.1','role':'nativeIdentity'}
    def test_proxy_new_native_old_is_blocked(self):
        result=self.m.handshake(self.target,self.f['old'],self.f['fingerprint'](self.f['new-leaf']),now=self.f['now'])
        self.assertEqual(result['status'],'BLOCKED');self.assertEqual(result['leafDerSha256'],self.f['fingerprint'](self.f['old-leaf']))
    def test_connect_address_does_not_replace_verified_ip(self):
        result=self.m.handshake(dict(self.target,verifyHost='192.0.2.1'),self.f['old'],self.f['fingerprint'](self.f['old-leaf']),now=self.f['now'])
        self.assertEqual(result['status'],'BLOCKED')
    def test_rejected_chain_never_emits_pass(self):
        result=self.m.handshake(self.target,self.f['new'],self.f['fingerprint'](self.f['old-leaf']),now=self.f['now'])
        self.assertEqual(result['status'],'BLOCKED')
    def test_missing_consumer_evidence_is_unknown(self):
        self.assertEqual(self.m.summarize({'nativeIdentity':{'status':'PASS'}},{},scope='all'),'UNKNOWN')

    def test_native_http_keeps_original_host_and_tls(self):
        import http.server,json,subprocess
        observed=[]
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                observed.append(self.headers['Host']);self.send_response(200);self.end_headers();self.wfile.write(b'ok')
            def log_message(self,*args):pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(self.f['old-leaf'],self.f['directory']/'old-leaf.key');server.socket=context.wrap_socket(server.socket,server_side=True)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            helper=Path(__file__).parents[1]/'runtime/tls-https-json.mjs'
            self.assertTrue(helper.is_file(),'sealed native routing helper missing')
            request={'url':'https://localhost/path','allowedOrigins':['https://localhost'],'ca':str(self.f['old']),
                     'connectHost':'127.0.0.1','connectPort':server.server_address[1]}
            result=subprocess.run(['node',str(helper)],input=json.dumps(request).encode(),capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(json.loads(result.stdout)['status'],200)
            self.assertEqual(observed,['localhost'])
            request['url']='https://192.0.2.1/path';request['allowedOrigins']=['https://192.0.2.1']
            denied=subprocess.run(['node',str(helper)],input=json.dumps(request).encode(),capture_output=True)
            self.assertNotEqual(denied.returncode,0)
        finally:server.shutdown();server.server_close()
    def test_registered_internal_bridge_keeps_its_original_tls_identity(self):
        from unittest.mock import patch
        from ols_linux import journal,runtime
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'runtime';journal.begin(root,'initialize','a'*64)
            runtime.private_file(root/'certs/server.crt',self.f['old-leaf'].read_bytes())
            journal._write(root,root/'identity/plan.json',{'issuer':'https://127.0.0.1:25443/realms/test','origin':'https://127.0.0.1:25444','ports':{'identity':24843,'entry':24844}})
            targets=[{'role':role,'connectHost':'127.0.0.1','connectPort':port,'verifyHost':'localhost','tlsIdentity':'internal'} for role,port in [('bridgeIdentity',24846),('bridgeEntry',24847)]]
            generation={'generationId':'fixture','paths':{'httpTrust':str(self.f['old'])},'candidate':{'leafDerSha256':'new-public-leaf'},'deployment':{'probeTargets':targets}}
            with patch.object(self.m.tls_generation,'resolve',return_value=generation),patch.object(self.m,'handshake',return_value={'status':'PASS'}) as handshake:
                result=self.m.collect(root,generation,scope='bridge',now=self.f['now'])
            self.assertEqual(result['status'],'PASS')
            self.assertTrue(all(call.args[2]==self.f['fingerprint'](self.f['old-leaf']) for call in handshake.call_args_list))
            generation['deployment']['probeTargets']=[dict(targets[0],role='publicIdentity')]
            with patch.object(self.m.tls_generation,'resolve',return_value=generation):
                with self.assertRaises(RuntimeError):self.m.collect(root,generation,scope='proxy',now=self.f['now'])
    def test_correct_tls_but_failed_public_http_cannot_pass(self):
        from unittest.mock import patch
        from ols_linux import journal,runtime,identity,verify
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'runtime';journal.begin(root,'initialize','a'*64)
            plan={'issuer':'https://127.0.0.1:25443/realms/test','origin':'https://127.0.0.1:25444','ports':{'identity':24843,'entry':24844}}
            journal._write(root,root/'identity/plan.json',plan);journal._write(root,root/'current-release.json',{'descriptor':{'spaFiles':{'index.html':'a'*64}}})
            generation={'version':0,'generationId':'test','paths':{'httpTrust':str(self.f['old'])},'candidate':{'leafDerSha256':'x'},'deployment':{'probeTargets':[dict(self.target,role=r,connectPort=p) for r,p in [('bridgeIdentity',24846),('bridgeEntry',24847),('publicIdentity',25443),('publicEntry',25444)]]}}
            with patch.object(self.m.tls_generation,'resolve',return_value=generation),patch.object(self.m,'handshake',return_value={'status':'PASS'}),patch.object(verify,'runtime_ready',return_value={'status':'PASS'}),patch.object(identity,'http',return_value={'status':502,'body':'upstream failed'}):
                result=self.m.collect(root,generation,scope='all',now=self.f['now'])
            self.assertNotEqual(result['status'],'PASS')
    def test_forwarded_api_proof_compares_contract_not_random_problem_id(self):
        import json
        self.assertTrue(callable(getattr(self.m,'same_denial',None)),'API denial contract comparison missing')
        body={'code':'UNAUTHENTICATED','status':401,'type':'urn:ontology-law:problem:UNAUTHENTICATED','instance':'/problems/00000000-0000-0000-0000-000000000001'}
        a={'status':401,'body':json.dumps(body)};b={'status':401,'body':json.dumps(dict(body,instance='/problems/00000000-0000-0000-0000-000000000002'))}
        self.assertTrue(self.m.same_denial(a,b));self.assertFalse(self.m.same_denial(a,{'status':502,'body':'bad gateway'}))
        self.assertFalse(self.m.same_denial(a,{'status':401,'body':'{}'}))
