import importlib
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

LINUX = Path(__file__).resolve().parents[1]
REPO = LINUX.parents[1]
sys.path.insert(0, str(LINUX))


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((LINUX / 'ols_linux/bundle.py').is_file(), 'release descriptor missing')
        self.m = importlib.import_module('ols_linux.bundle')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_non_jar_or_missing_spa_cannot_be_a_release(self):
        jar = self.root / 'fake.jar'
        jar.write_bytes(b'not a production jar')
        dist = self.root / 'dist'
        dist.mkdir()
        (dist / 'index.html').write_text('<html>fake</html>')
        with self.assertRaises(RuntimeError): self.m.describe(REPO, jar, dist, 'a'*40)

    def test_manifest_prefix_and_exact_tree_are_verified(self):
        import zipfile
        repo = self.root / 'repo'
        source = 'database/schema-contract-52-plus-2/generated'
        shutil.copytree(REPO / source, repo / source)
        for path in ['database/schema-contract-52-plus-2/runtime/toolchain.lock.json','deploy/identity/identity-toolchain.lock.json']:
            (repo / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / path, repo / path)
        shutil.copytree(LINUX / 'config', repo / 'deploy/linux/config')
        jar = repo / 'app.jar'
        with zipfile.ZipFile(jar, 'w') as z:
            z.writestr('BOOT-INF/classes/schema-contract/schema-contract-manifest.json', (repo / source / 'schema-contract-manifest.json').read_bytes())
            z.writestr('META-INF/MANIFEST.MF', 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\n')
            z.writestr('BOOT-INF/classes/schema-contract/build-source.json', json.dumps({'commit':'a'*40,'manifestHash':hashlib.sha256((repo / source / 'schema-contract-manifest.json').read_bytes()).hexdigest()}))
        dist = repo / 'dist'
        dist.mkdir()
        (dist / 'index.html').write_text('<html>fixture</html>')
        proof = {'commit':'a'*40, 'jarExitCode':0, 'spaExitCode':0,
                 'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
                 'spaFiles':{'index.html':hashlib.sha256((dist / 'index.html').read_bytes()).hexdigest()}}
        (repo / '.artifacts').mkdir()
        (repo / '.artifacts/linux-build-proof.json').write_text(json.dumps(proof))
        # This fixture tests descriptor bytes, not executable application readiness.
        descriptor = self.m.describe(repo, jar, dist, 'a'*40)
        self.assertEqual(descriptor['schemaVersion'], '52-plus-2-r2-v22')
        self.assertEqual(len(descriptor['migrations']), 43)
        self.m.verify(descriptor, repo)
        proof['jarExitCode'] = 1
        (repo / '.artifacts/linux-build-proof.json').write_text(json.dumps(proof))
        with self.assertRaises(RuntimeError): self.m.describe(repo, jar, dist, 'a'*40)
        proof['jarExitCode'] = 0
        (repo / '.artifacts/linux-build-proof.json').write_text(json.dumps(proof))
        (dist / 'unexpected.js').write_text('drift')
        with self.assertRaises(RuntimeError): self.m.verify(descriptor, repo)
        (dist / 'unexpected.js').unlink()
        migration = next((repo / source / 'db/migration').glob('V001*'))
        migration.write_bytes(migration.read_bytes() + b'\n-- tampered')
        with self.assertRaises(RuntimeError): self.m.verify(descriptor, repo)


if __name__ == '__main__': unittest.main()
