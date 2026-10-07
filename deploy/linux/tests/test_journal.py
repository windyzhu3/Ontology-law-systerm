import importlib
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

LINUX = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LINUX))


class JournalTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX / 'ols_linux/journal.py').is_file(), 'private journal missing')
        self.m = importlib.import_module('ols_linux.journal')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / '测试 实例'

    def test_pending_operation_cannot_be_replaced_and_events_preserved(self):
        op = self.m.begin(self.root, 'initialize', 'a'*64)
        self.m.record(self.root, op['operationId'], {'phase': 'MIGRATING', 'commandId': 'original'})
        result = self.m.read(self.root, op['operationId'])
        self.assertEqual(result['events'][-1]['commandId'], 'original')
        with self.assertRaises(RuntimeError): self.m.begin(self.root, 'initialize', 'a'*64)
        with self.assertRaises(RuntimeError): self.m.begin(self.root, 'upgrade', 'b'*64)
        with self.assertRaises(RuntimeError): self.m.record(self.root, 'other', {'phase': 'COMPLETE'})
        self.assertEqual(self.m.read(self.root, op['operationId'])['configDigest'], 'a'*64)

    def test_corrupt_log_and_foreign_runtime_fail_closed(self):
        op = self.m.begin(self.root, 'upgrade', 'b'*64)
        path = self.root / 'operations' / (op['operationId'] + '.json')
        value = json.loads(path.read_text(encoding='utf-8'))
        value['payload']['configDigest'] = 'c'*64
        path.write_text(json.dumps(value), encoding='utf-8')
        with self.assertRaises(RuntimeError): self.m.read(self.root, op['operationId'])
        other = Path(self.tmp.name) / 'foreign'
        other.mkdir()
        (other / 'unrelated').write_text('keep')
        with self.assertRaises(RuntimeError): self.m.begin(other, 'upgrade', 'a'*64)
        self.assertEqual((other / 'unrelated').read_text(), 'keep')

    def test_link_escape_and_operation_traversal_are_rejected(self):
        op = self.m.begin(self.root, 'upgrade', 'a'*64)
        with self.assertRaises(RuntimeError): self.m.read(self.root, '../escaped')
        link = Path(self.tmp.name) / 'linked'
        try: link.symlink_to(self.root, target_is_directory=True)
        except OSError: self.skipTest('host lacks symlink permission; mandatory Linux test also covers this')
        with self.assertRaises(RuntimeError): self.m.read(link, op['operationId'])

    def test_shared_instance_lock_blocks_a_second_process(self):
        self.m.begin(self.root, 'upgrade', 'a'*64)
        code = "from pathlib import Path\nimport sys\nfrom ols_linux import journal\ntry:\n with journal.locked(Path(sys.argv[1])): print('UNSAFE')\nexcept RuntimeError: print('BUSY')\n"
        with self.m.locked(self.root):
            result = subprocess.run([sys.executable, '-c', code, str(self.root)], env=dict(os.environ, PYTHONPATH=str(LINUX)),
                                    text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'BUSY')

    @unittest.skipIf(os.name == 'nt', 'POSIX permission assertion executes in Linux verification')
    def test_linux_private_modes_and_foreign_permissions(self):
        op = self.m.begin(self.root, 'upgrade', 'a'*64)
        self.assertEqual(self.root.stat().st_mode & 0o777, 0o700)
        for path in [self.root/'journal.key', self.root/'operations'/(op['operationId']+'.json')]:
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.root.chmod(0o755)
        with self.assertRaises(RuntimeError): self.m.read(self.root, op['operationId'])


if __name__ == '__main__': unittest.main()
