"""Static resource bounds and actual generated synthetic certificate semantics."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

PACKAGE=Path(__file__).resolve().parents[1]/'verification/systemd_qualification'
sys.path.insert(0,str(PACKAGE))
import fixture


class PackageTests(unittest.TestCase):
    def test_units_and_configs_use_only_fixed_test_resources(self):
        for role in fixture.NAMES:
            unit=fixture.unit(role)
            self.assertIn('Slice=ols-tls-qualification.slice',unit)
            self.assertIn('MemorySwapMax=0',unit)
            self.assertNotIn('ExecReload=',unit if role=='caddy' else '')
            self.assertNotIn('49.235.',unit)
        texts='\n'.join(p.read_text() for p in (PACKAGE/'templates').iterdir())
        self.assertNotIn('49.235.',texts);self.assertNotIn('2484',texts)
        self.assertIn('listen 127.0.0.1:29848 ssl default_server',texts)
        self.assertIn('protocols h1 h2',texts)
        self.assertNotIn('tls_insecure_skip_verify',texts)
        self.assertIn('proxy_ssl_verify on',texts)

    def test_new_materials_have_distinct_originals_and_real_expired_negative(self):
        import ssl,time
        from ols_linux import tls_material
        with tempfile.TemporaryDirectory() as directory,patch.object(fixture,'BASE',Path(directory)):
            fixture.materials()
            old=fixture.certificate('old-native');external=fixture.certificate('old-public');new=fixture.certificate('new-public')
            self.assertLess(old['notAfter'],external['notAfter']);self.assertLess(external['notAfter'],new['notAfter'])
            self.assertNotEqual(old['leafDerSha256'],external['leafDerSha256'])
            self.assertLess(fixture.certificate('native-expired')['notAfter'],time.time())
            materials=Path(directory)/'materials'
            self.assertEqual((materials/'identity.crt').read_bytes(),(materials/'app.crt').read_bytes())
            with self.assertRaisesRegex(RuntimeError,'regenerate'):fixture.materials()

    def test_guard_always_attempts_all_stops_even_when_evidence_is_full(self):
        import guard
        from types import SimpleNamespace
        instance=guard.Guard({}, {'metrics':{'load':0}})
        with patch.object(guard,'put',side_effect=OSError('disk full')),patch.object(guard.runtime,'run',side_effect=[RuntimeError('timeout'),SimpleNamespace(returncode=0),SimpleNamespace(returncode=0)]) as run:
            with self.assertRaises(OSError):instance.fail(RuntimeError('unsafe'))
            self.assertEqual([call.args[0] for call in run.call_args_list],[['systemctl','stop',name] for name in fixture.NAMES.values()])
        with self.assertRaisesRegex(RuntimeError,'unsafe'):instance.check()

    def test_pending_health_observation_blocks_finalization(self):
        import guard
        from unittest.mock import Mock
        instance=guard.Guard({}, {'metrics':{'load':0}})
        instance.thread=Mock();instance.thread.is_alive.return_value=False
        instance.health_thread=Mock();instance.health_thread.is_alive.return_value=True
        with self.assertRaisesRegex(RuntimeError,'finalization'):instance.close()

    def test_final_report_never_written_after_stop_or_guard_failure(self):
        import runner
        from unittest.mock import Mock
        for failure in ('stop','guard'):
            watch=Mock()
            if failure=='guard':watch.close.side_effect=RuntimeError('late health failure')
            with patch.object(runner,'stop_tests',side_effect=RuntimeError('stop unknown') if failure=='stop' else None),patch.object(runner.journal,'_write') as write:
                with self.assertRaises(RuntimeError):runner.finalize({'status':'PASS'},watch)
                write.assert_not_called()
