"""Native probing preserves the shared public authority's path routing."""
import json,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from ols_linux import identity


class SharedOriginTests(unittest.TestCase):
    def request(self,url,*,shared=True,public=False):
        plan={'pod':'pod','issuer':'https://203.0.113.10/realms/fixture','origin':'https://203.0.113.10' if shared else 'https://203.0.113.11','apiOrigin':'https://localhost:24845','ports':{'identity':24843,'entry':24844,'api':24845}}
        with patch.object(identity.journal,'_read',return_value=plan),patch.object(identity.runtime,'owned'),patch.object(identity.runtime,'load',return_value={}),patch.object(identity.tls_generation,'managed',return_value=True),patch('ols_linux.public_runtime.effective_paths',return_value={'httpTrust':'/trust'}),patch.object(identity,'http_helper',return_value='/helper'),patch.object(identity.runtime,'run',return_value=SimpleNamespace(stdout=b'{"status":401,"body":"unchanged"}')) as run:
            response=identity.http(Path('/runtime'),url,public=public)
            self.assertEqual(response,{'status':401,'body':'unchanged'})
            return json.loads(run.call_args.args[1])
    def test_shared_origin_entry_and_direct_api_keep_their_native_ports(self):
        for path in ['/','/api/v1/session/context','/assets/app.js','/auth/callback','/workbench','/admin/people','/realms/fixture-other','/resources-other','/admin/realms/fixture-other/users','/?next=/realms/fixture']:
            with self.subTest(path=path):self.assertEqual(self.request('https://203.0.113.10'+path)['connectPort'],24844)
        self.assertEqual(self.request('https://localhost:24845/api/v1/session/context')['connectPort'],24845)
    def test_shared_issuer_realm_resources_and_directory_paths_keep_identity_port(self):
        for path in ['/realms/fixture','/realms/fixture/.well-known/openid-configuration','/realms/fixture/protocol/openid-connect/token','/realms/fixture/protocol/openid-connect/certs','/realms/fixture/protocol/openid-connect/token/introspect','/resources/keycloak/login.css','/admin/realms/fixture/users?first=0']:
            with self.subTest(path=path):self.assertEqual(self.request('https://203.0.113.10'+path)['connectPort'],24843)
    def test_separate_authorities_and_public_route_do_not_change(self):
        self.assertEqual(self.request('https://203.0.113.10/anything',shared=False)['connectPort'],24843)
        self.assertEqual(self.request('https://203.0.113.11/api/v1/session/context',shared=False)['connectPort'],24844)
        for path in ['/','/api/v1/session/context','/realms/fixture/protocol/openid-connect/certs']:
            result=self.request('https://203.0.113.10'+path,public=True)
            self.assertNotIn('connectHost',result);self.assertNotIn('connectPort',result)
    def test_unregistered_authority_or_ambiguous_native_path_is_rejected(self):
        for url in ['https://203.0.113.10.evil/realms/fixture','https://203.0.113.10:444/realms/fixture','https://user@203.0.113.10/realms/fixture','http://203.0.113.10/realms/fixture','https://203.0.113.10/realms/fixture/../api','https://203.0.113.10/realms/fixture%2fprotocol','https://203.0.113.10//realms/fixture','https://203.0.113.10/realms/fixture\\api']:
            with self.subTest(url=url),self.assertRaises(RuntimeError):self.request(url)
