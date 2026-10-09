"""Controller regression: legacy rollback uses original sealed proxy targets.

Real journal/selection, collect/summarize and loopback TLS handshakes; database,
container lifecycle and application HTTP are isolated boundary substitutes.
This is not a live systemd or full application acceptance claim.
"""
from contextlib import ExitStack
import hashlib,json,socketserver,ssl,tempfile,threading
from pathlib import Path
import unittest
from unittest.mock import patch
from ols_linux import journal,runtime,tls_generation,tls_original,tls_rotation,identity,verify
from tls_fixtures import materials,instance


class LegacyRollbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.fixture=materials(Path(cls.tmp.name)/'materials')
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()

    def test_controller_rollback_reopens_only_after_all_original_targets_pass(self):
        f=self.fixture
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):pass
        with socketserver.TCPServer(('127.0.0.1',0),Handler) as server:
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(f['old-leaf'],f['directory']/'old-leaf.key')
            server.socket=context.wrap_socket(server.socket,server_side=True)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                with tempfile.TemporaryDirectory() as directory,ExitStack() as stack:
                    root=instance(Path(directory),f)
                    runtime.private_file(root/'certs/http-trust.pem',f['old'].read_bytes())
                    original=journal.current(root);journal.record(root,original['operationId'],{'phase':'COMPLETE'})
                    resources=runtime.load(root)
                    resources.update(containers=dict.fromkeys(['api','worker','identity','scanner','entry'],'fixture'),ingress='fixture')
                    runtime.save(root,resources)
                    port=server.server_address[1];origin=f'https://127.0.0.1:{port}'
                    plan={'issuer':origin+'/realms/test','origin':origin,'apiOrigin':origin,'ports':{'identity':port,'entry':port}}
                    journal._write(root,root/'identity/plan.json',plan)
                    html='<html>synthetic fixture</html>';descriptor={'spaFiles':{'index.html':hashlib.sha256(html.encode()).hexdigest()}}
                    journal._write(root,root/'current-release.json',{'descriptor':descriptor})
                    old=tls_original.capture(tls_generation.resolve(root),{'version':2,'services':[{'role':'nginx','tlsPaths':{'certificate':str(f['old-leaf']),'privateKey':str(f['directory']/'old-leaf.key')}}]})
                    self.assertNotIn('deployment',old)
                    targets=[{'role':role,'connectHost':'127.0.0.1','connectPort':port,'verifyHost':'127.0.0.1'} for role in ('bridgeIdentity','bridgeEntry','publicIdentity','publicEntry')]
                    expected_targets=json.loads(json.dumps(targets))
                    op=journal.begin(root,'rotate-public-tls','a'*64);opid=op['operationId']
                    before={'deployment_state_key':'PRIMARY','operating_mode':'ACTIVE','active_release_digest':'a'*64,'active_manifest_hash':'b'*64,'schema_contract_version':'test','revision':1,'changed_at':'2026-10-08T00:00:00+00:00'}
                    tls_rotation._save(root,opid,{'previousGeneration':old,'generationId':'candidate','probeTargets':targets,'previousResources':resources,'previousLaunch':{},'release':{'descriptor':descriptor},'before':{'gate':before}})
                    # A later caller mutation must not replace the sealed original targets.
                    targets[0]['connectPort']=1
                    current_gate=[before]
                    def cas(root,prior,expected):
                        self.assertEqual(prior,current_gate[0]);current_gate[0]=expected;return expected
                    denial={'status':401,'body':json.dumps({'code':'UNAUTHENTICATED','status':401,'type':'urn:ontology-law:problem:UNAUTHENTICATED','instance':'/problems/00000000-0000-0000-0000-000000000001'})}
                    def http(root,url,**kwargs):
                        if url.endswith('/.well-known/openid-configuration'):return {'status':200,'body':json.dumps({'issuer':plan['issuer']})}
                        if url==origin+'/':return {'status':200,'body':html}
                        return denial
                    for obj,name,kwargs in [
                        (tls_generation,'read',{'return_value':{'deployment':{'httpHelper':str(root/'synthetic-helper')}}}),
                        (runtime,'stop_writers',{}),(tls_rotation.tls_deployment,'copy_identity',{}),
                        (tls_rotation.tls_proxy,'apply',{'return_value':{'closed':True}}),(tls_rotation,'_start',{}),
                        (tls_rotation.database,'observe',{'side_effect':lambda root:{'gate':current_gate[0]}}),
                        (tls_rotation.release,'_cas_gate',{'side_effect':cas}),
                        (verify,'runtime_ready',{'return_value':{'fullRuntime':{'identity':'VERIFIED_TLS_DISCOVERY','api':'AUTHENTICATED_MTLS_READY','worker':'CURRENT_BOOT_READY','scanner':'REAL_PONG'}}}),
                        (identity,'http',{'side_effect':http}),
                    ]:stack.enter_context(patch.object(obj,name,**kwargs))
                    result=tls_rotation.rollback(root,opid,now=f['now'])
                    self.assertEqual(result['outcome'],'ROLLED_BACK');self.assertEqual(result['operationId'],opid)
                    proof=journal._read(root,root/'operations'/(opid+'-tls-proof-all.json'))
                    self.assertEqual(proof['status'],'PASS');self.assertEqual(len(proof['targets']),6);self.assertEqual(len(proof['consumers']),6)
                    self.assertEqual(tls_generation.resolve(root)['deployment']['probeTargets'],expected_targets)
                    self.assertEqual(journal.current(root)['phase'],'COMPLETE')
            finally:server.shutdown();thread.join()
