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
