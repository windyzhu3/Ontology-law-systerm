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

    def test_expiry_proof_rejects_unavailable_peers_and_other_tls_errors(self):
        import runner,ssl
        self.assertTrue(callable(getattr(runner,'expired_peer',None)))
        for error in [ConnectionRefusedError('offline'),ssl.SSLError('handshake failure')]:
            with patch.object(runner,'request',side_effect=error):
                with self.assertRaises(type(error)):runner.expired_peer(29843)
        expired=ssl.SSLCertVerificationError(1,'expired');expired.verify_code=10
        wrong_ca=ssl.SSLCertVerificationError(1,'unknown CA');wrong_ca.verify_code=20
        with patch.object(runner,'request',side_effect=wrong_ca):
            with self.assertRaisesRegex(RuntimeError,'expiry'):runner.expired_peer(29843)
        with patch.object(runner,'request',side_effect=expired):self.assertEqual(runner.expired_peer(29843)['verifyCode'],10)

    def test_admission_checks_var_lib_disk_not_run_tmpfs(self):
        import guard
        from types import SimpleNamespace
        metrics={'time':1,'available':2*1024**3,'swapPages':0,'psi':0,'load':0}
        def read(path,*args,**kwargs):
            if str(path)=='/proc/1/comm':return 'systemd\n'
            if str(path)=='/sys/fs/cgroup/cgroup.controllers':return 'memory cpu\n'
            raise AssertionError(str(path))
        with patch.object(guard.os,'geteuid',return_value=0),patch.object(Path,'read_text',read),patch.object(guard.sd,'_binary_sha',side_effect=[v[1] for v in fixture.BINARIES.values()]),patch.object(guard,'metrics',side_effect=[metrics,dict(metrics,time=6)]),patch.object(guard.time,'sleep'),patch.object(guard.os,'statvfs',return_value=SimpleNamespace(f_bavail=22*1024**3,f_frsize=1)) as statvfs,patch.object(guard,'health'),patch.object(guard,'baseline',return_value={}):
            guard.admission({})
        self.assertTrue(statvfs.called)
        self.assertEqual({call.args[0] for call in statvfs.call_args_list},{'/var/lib'})

    def test_package_builder_is_deterministic_and_excludes_runtime_files(self):
        import build_package,gzip,hashlib,io,tarfile
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary);(base/'ols_linux').mkdir();fixture_dir=base/'verification/systemd_qualification';fixture_dir.mkdir(parents=True)
            (base/'ols_linux/a.py').write_text('x=1\n');(fixture_dir/'bootstrap.py').write_text('# synthetic\n')
            (fixture_dir/'private-runtime.json').write_text('must not be packaged')
            first,manifest=build_package.build(base);second,_=build_package.build(base)
            self.assertEqual(first,second);self.assertEqual(hashlib.sha256(gzip.decompress(first)).hexdigest(),manifest['uncompressedTarSha256'])
            with tarfile.open(fileobj=io.BytesIO(first),mode='r:gz') as archive:self.assertEqual(archive.getnames(),['ols_linux/a.py','verification/systemd_qualification/bootstrap.py'])

    def test_bootstrap_admits_only_empty_implicit_fixture_slice(self):
        import bootstrap
        self.assertTrue(callable(getattr(bootstrap,'admit_unit_state',None)))
        empty={'LoadState':'loaded','ActiveState':'inactive','SubState':'dead','FragmentPath':'','SourcePath':'','DropInPaths':'','ControlGroup':'','Transient':'no'}
        name='ols-tls-qualification.slice'
        bootstrap.admit_unit_state(name,empty,cgroup_exists=False)
        for key,value in [('ActiveState','active'),('SubState','running'),('FragmentPath','/etc/systemd/system/foreign.slice'),('SourcePath','/run/generator/foreign'),('DropInPaths','/etc/systemd/system/foreign.conf'),('ControlGroup','/foreign'),('Transient','yes'),('LoadState','masked')]:
            with self.subTest(key=key),self.assertRaises(RuntimeError):bootstrap.admit_unit_state(name,dict(empty,**{key:value}),cgroup_exists=False)
        with self.assertRaises(RuntimeError):bootstrap.admit_unit_state(name,empty,cgroup_exists=True)
        for other in ['ols-tls-qualification-nginx.service','ols-tls-qualification-control.scope','ols.slice']:
            with self.subTest(name=other),self.assertRaises(RuntimeError):bootstrap.admit_unit_state(other,empty,cgroup_exists=False)
        bootstrap.admit_unit_state('ols-tls-qualification-nginx.service',dict(empty,LoadState='not-found'),cgroup_exists=False)
