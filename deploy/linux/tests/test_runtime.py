import importlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

LINUX = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LINUX))


class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX/'ols_linux/runtime.py').exists(), 'Linux runtime boundary missing')
        self.m = importlib.import_module('ols_linux.runtime')
        from ols_linux import journal
        self.j = journal
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)/'runtime'
        self.op = journal.begin(self.root,'initialize','a'*64)

    def test_no_windows_commands_and_foreign_resources_rejected(self):
        source=(LINUX/'ols_linux/runtime.py').read_text()
        for command in ['pwsh','icacls','certutil']:
            self.assertNotIn(command,source)
        fake={'Name':'/ols-test-api','Config':{'Labels':{'ols.instance':'foreign'}},'State':{'Running':True}}
        with patch.object(self.m,'inspect',return_value=fake):
            with self.assertRaises(RuntimeError): self.m.owned(self.root,'container','ols-test-api')

    def test_ingress_opens_only_after_confirmed_health_phase(self):
        with self.assertRaises(RuntimeError): self.m.open_ingress(self.root,self.op['operationId'])
        self.assertEqual(self.j.read(self.root,self.op['operationId'])['phase'],'CREATED')

    def test_owned_stop_must_be_observed_and_tls_missing_refused(self):
        with self.assertRaises(RuntimeError): self.m.validate_tls(self.root)
        self.m.save(self.root,{'writers':['ols-test-api'],'ingress':None})
        actual={'State':{'Running':True},'Config':{'Labels':{'ols.instance':self.op['instanceId']}}}
        with patch.object(self.m,'inspect',return_value=actual),patch.object(self.m,'run',return_value=SimpleNamespace(returncode=0)):
            with self.assertRaises(RuntimeError): self.m.stop_writers(self.root,self.op['operationId'])
        self.assertEqual(self.j.read(self.root,self.op['operationId'])['phase'],'CREATED')

    def test_toolchain_archive_drift_is_not_silently_redownloaded(self):
        self.assertTrue(callable(getattr(self.m,'build_image',None)), 'locked application runtime build missing')
        cache=Path(self.tmp.name)/'build';cache.mkdir()
        (cache/'jdk.tar.gz').write_bytes(b'different version')
        with self.assertRaises(RuntimeError):self.m.build_image(LINUX.parents[1],cache)

    def test_runtime_cache_includes_dockerfile_build_input(self):
        from ols_linux.config import digest
        lock=json.loads((LINUX/'runtime/toolchain.lock.json').read_text())
        cache=Path(self.tmp.name)/'build';cache.mkdir();(cache/'jdk.tar.gz').write_bytes(b'fixture');(cache/'npm.tar.gz').write_bytes(b'fixture')
        old={'Id':'stale','Config':{'Labels':{'ols.toolchain':digest(lock)}}}
        checksum=lambda p:lock['jdk']['sha256'] if Path(p).name=='jdk.tar.gz' else lock['npm']['sha256'] if Path(p).name=='npm.tar.gz' else 'b'*64
        with patch.object(self.m,'sha',side_effect=checksum),patch.object(self.m,'inspect',return_value=old):
            with self.assertRaises(RuntimeError):self.m.build_image(LINUX.parents[1],cache)

    def test_npm_archive_is_pinned_independently_of_node_image(self):
        lock=json.loads((LINUX/'runtime/toolchain.lock.json').read_text())
        self.assertEqual(lock.get('npm',{}).get('version'),'11.9.0')
        self.assertRegex(lock['npm']['sha256'],r'^[a-f0-9]{64}$')
        source=(LINUX/'runtime/Dockerfile.app').read_text()
        self.assertIn('npm.tar.gz',source)

    def test_existing_but_invalid_tls_files_are_rejected(self):
        certs=self.root/'certs';certs.mkdir()
        for name in ['ca.pem','server.crt','server.key']:
            (certs/name).write_bytes(b'not a certificate');(certs/name).chmod(0o600)
        with self.assertRaises(RuntimeError):self.m.validate_tls(self.root)

    def test_external_volume_and_existing_container_are_never_adopted(self):
        for conflict in ['volume','container']:
            with patch.object(self.m,'run',return_value=SimpleNamespace(returncode=0)),patch.object(self.m,'inspect',side_effect=lambda kind,name:{'Labels':{'ols.instance':'foreign'}} if kind==conflict else None):
                with self.assertRaises(RuntimeError):self.m.prepare(self.root,{'name':'ols-unit-existing','repo':str(LINUX.parents[1])})
            self.assertFalse((self.root/'resources.json').exists())

    def test_unregistered_database_client_prevents_stop_confirmation(self):
        self.m.save(self.root,{'writers':[],'ingress':None})
        with patch('ols_linux.database.sql',return_value='1'):
            with self.assertRaises(RuntimeError):self.m.stop_writers(self.root,self.op['operationId'])
        self.assertEqual(self.j.current(self.root)['phase'],'CREATED')


if __name__=='__main__':unittest.main()
