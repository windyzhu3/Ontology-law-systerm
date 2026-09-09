import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'local_login.py'


class LocalConfigurationTest(unittest.TestCase):
    def module(self):
        self.assertTrue(MODULE.exists(), 'local login runner is missing')
        spec = importlib.util.spec_from_file_location('local_login', MODULE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_secret_bundle_survives_restart_and_rejects_partial_state(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            first = module.secret_bundle(path)
            self.assertEqual(first, module.secret_bundle(path))
            self.assertEqual(len(set(first.values())), len(first))
            (path / 'secrets' / 'offline-key.txt').unlink()
            with self.assertRaisesRegex(RuntimeError, 'partial'):
                module.secret_bundle(path)

    def test_realm_uses_exact_callback_and_only_readonly_directory_roles(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            data = module.realm(module.secret_bundle(Path(directory)))
        self.assertEqual(data['realm'], 'local-r1')
        spa, api, directory = data['clients']
        self.assertEqual(spa['redirectUris'], ['https://localhost:19444/auth/callback'])
        self.assertEqual(spa['webOrigins'], ['https://localhost:19444'])
        self.assertEqual(spa['attributes']['pkce.code.challenge.method'], 'S256')
        self.assertFalse(spa['directAccessGrantsEnabled'])
        self.assertNotEqual(api['secret'], directory['secret'])
        account = next(user for user in data['users'] if 'serviceAccountClientId' in user)
        self.assertEqual(account['clientRoles'], {'realm-management': ['query-users', 'view-users']})
        self.assertEqual(len([user for user in data['users'] if 'serviceAccountClientId' not in user]), 2)


if __name__ == '__main__':
    unittest.main()
