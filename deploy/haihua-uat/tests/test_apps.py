"""Retained-instance restart must not replace deployed bytes or partially start."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apps


class RetainedRestartTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        self.jar = self.runtime / 'app.jar'
        self.jar.write_bytes(b'verified backend')
        for name, value in [('ROOT', self.root), ('RUNTIME', self.runtime),
                            ('JAR', self.jar)]:
            p = patch.object(apps, name, value)
            p.start()
            self.addCleanup(p.stop)
        self.deployment = patch.object(apps, 'deployment', return_value={
            'releaseDigest': hashlib.sha256(self.jar.read_bytes()).hexdigest()})
        self.deployment.start()
        self.addCleanup(self.deployment.stop)
        self.launch = patch.object(apps.subprocess, 'Popen', return_value=Mock(pid=123))
        self.process = self.launch.start()
        self.addCleanup(self.launch.stop)
        windows_flag = patch.object(apps.subprocess, 'CREATE_NO_WINDOW', 0, create=True)
        windows_flag.start()
        self.addCleanup(windows_flag.stop)
        p = patch.object(apps, 'save', side_effect=lambda name, data:
                         (self.runtime / name).write_text(json.dumps(data)))
        p.start()
        self.addCleanup(p.stop)

    def retained(self):
        (self.runtime / 'dist').mkdir()
        (self.runtime / 'dist/index.html').write_bytes(b'previous deployed SPA')
        (self.runtime / 'server.mjs').write_bytes(b'previous deployed server')

    def test_start_reuses_retained_spa_without_copying_new_build(self):
        self.retained()
        before = {p.name: p.read_bytes() for p in [
            self.runtime / 'dist/index.html', self.runtime / 'server.mjs']}
        apps.start(['spa'])
        self.process.assert_called_once()
        self.assertEqual(before, {p.name: p.read_bytes() for p in [
            self.runtime / 'dist/index.html', self.runtime / 'server.mjs']})
        self.assertEqual(123, json.loads((self.runtime / 'processes.json').read_text())['spa']['pid'])

    def test_incomplete_retained_spa_refuses_before_starting_api(self):
        (self.runtime / 'dist').mkdir()
        with self.assertRaises(RuntimeError):
            apps.start(['api', 'spa'])
        self.process.assert_not_called()

    def test_registered_process_refuses_all_requested_starts(self):
        self.retained()
        (self.runtime / 'processes.json').write_text(json.dumps({'spa': {'pid': 123}}))
        with self.assertRaises(RuntimeError):
            apps.start(['api', 'spa'])
        self.process.assert_not_called()


if __name__ == '__main__':
    unittest.main()
