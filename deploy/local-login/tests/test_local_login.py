import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'local_login.py'


class LocalConfigurationTest(unittest.TestCase):
    def module(self):
        self.assertTrue(MODULE.exists(), 'local login runner is missing')
        spec = importlib.util.spec_from_file_location('local_login', MODULE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_verifier_stdout_requires_one_exact_final_result_after_unstructured_logs(self):
        module = self.module()
        self.assertTrue(hasattr(module, 'require_original_verification_output'), 'strict verifier output boundary missing')
        result = b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{}}'
        logs = (b'09:00:00.000 [main] INFO org.jooq.Constants -- \n'
                b'@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n'
                b'@@  jOOQ synthetic startup banner  @@\n'
                b'09:00:00.010 [main] INFO org.jooq.impl.DefaultExecuteContext.logVersionSupport -- '
                b'Version : Database version is supported by dialect POSTGRES: 18.0\n')
        for output in (result, logs + result + b'\r\n\r\n'):
            module.require_original_verification_output(output)

    def test_verifier_stdout_rejects_missing_malformed_ambiguous_or_non_verification_outcomes(self):
        module = self.module()
        self.assertTrue(hasattr(module, 'require_original_verification_output'), 'strict verifier output boundary missing')
        good = b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{}}'
        for output in (
            b'', b'logger only\n', b'{malformed}', good + b'\ntrailing log',
            good + b'\n' + good, good + good,
            b'{"mode":"EXECUTED","plannedDelta":{}}\n' + good,
            b'INFO previous result: {"mode":"EXECUTED"}\n' + good,
            b'{malformed earlier result\n' + good,
            b'{"mode":"VERIFIED_ORIGINAL"}',
            b'{"mode":"DRY_RUN","plannedDelta":{}}',
            b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{"principal":0}}',
            b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":[]}',
            b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{},"extra":"diagnostic-sentinel"}',
            b'{"mode":"FAILED","mode":"VERIFIED_ORIGINAL","plannedDelta":{}}',
            b'{"mode":"VERIFIED_ORIGINAL","plannedDelta":{"principal":1},"plannedDelta":{}}',
            b'\xff\n' + good,
        ):
            with self.subTest(output=output), self.assertRaisesRegex(RuntimeError, '^original bootstrap verification output unavailable$'):
                module.require_original_verification_output(output)

    def test_secret_bundle_survives_restart_and_rejects_partial_state(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            first = module.create_secret_bundle(path)
            self.assertEqual(first, module.secret_bundle(path))
            self.assertEqual(len(set(first.values())), len(first))
            (path / 'secrets' / 'offline-key.txt').unlink()
            with self.assertRaisesRegex(RuntimeError, 'partial'):
                module.secret_bundle(path)

    def test_realm_uses_exact_callback_and_only_readonly_directory_roles(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            data = module.realm(module.create_secret_bundle(Path(directory)))
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

    def test_loading_a_missing_bundle_never_creates_secrets(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with self.assertRaisesRegex(RuntimeError, 'missing|partial'):
                module.secret_bundle(path)
            self.assertFalse((path / 'secrets').exists())

    def test_legacy_resume_checks_jar_drift_then_requires_snapshot_without_building(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            module.RUNTIME = Path(directory)
            module.JAR = module.RUNTIME / 'legacy.jar'
            module.JAR.write_bytes(b'legacy')
            module.save('deployment.json', {'releaseDigest': '0' * 64})
            with patch.object(module.socket, 'socket'), patch.object(module.subprocess, 'run', side_effect=AssertionError('must not build or launch')):
                with self.assertRaisesRegex(RuntimeError, 'Jar changed'):
                    module.start_apps()
                import hashlib
                module.save('deployment.json', {'releaseDigest': hashlib.sha256(b'legacy').hexdigest()})
                with self.assertRaisesRegex(RuntimeError, 'snapshot-release'):
                    module.start_apps()

    def test_prepare_and_application_config_preserve_surviving_state_when_whole_bundle_is_missing(self):
        for operation in ('initialize', 'application_config'):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                module = self.module()
                module.RUNTIME = path = Path(directory)
                (path / 'certs').mkdir()
                for name in ('ca.pem', 'server.crt', 'server.key', 'server.pfx', 'pfx-password.txt', 'database.crt', 'database.key'):
                    (path / 'certs' / name).write_text('synthetic certificate fixture')
                (path / 'service.crt').write_text('-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----\n')
                (path / 'deployment.json').write_text(json.dumps({'tenantId': '00000000-0000-4000-8000-000000000001', 'releaseDigest': '0' * 64, 'manifestHash': '1' * 64}))
                (path / 'service-fixture.json').write_text(json.dumps({'principalId': '00000000-0000-4000-8000-000000000002', 'appointmentId': '00000000-0000-4000-8000-000000000003'}))
                (path / 'application.properties').write_text('existing-config-must-survive')
                before = {file.name: file.read_bytes() for file in path.iterdir() if file.is_file()}
                def unexpected_external_operation(*args):
                    raise RuntimeError('unexpected external operation reached')
                module.run = unexpected_external_operation
                with self.assertRaisesRegex(RuntimeError, 'missing|partial'):
                    getattr(module, operation)()
                self.assertFalse((path / 'secrets').exists())
                self.assertEqual(before, {file.name: file.read_bytes() for file in path.iterdir() if file.is_file()})


if __name__ == '__main__':
    unittest.main()
