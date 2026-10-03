import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class R1SchemaProjectionTest(unittest.TestCase):
    def test_isolated_schema_uses_exact_historical_bytes_and_current_harness(self):
        from scripts.ci.verify_r1_schema_projection import prepare_schema
        with tempfile.TemporaryDirectory() as directory:
            schema, manifest = prepare_schema(ROOT, Path(directory))
            self.assertEqual('52-plus-2-v1.2', manifest['contractVersion'])
            files = list((schema / 'generated/db/migration').glob('*.sql'))
            self.assertEqual(21, len(files))
            self.assertFalse(any(int(path.name[1:].split('__')[0]) > 860 for path in files))
            for path in files:
                self.assertEqual(path.read_bytes(), (ROOT / 'database/schema-contract-52-plus-2/generated/db/migration' / path.name).read_bytes())
            self.assertEqual((schema / 'runtime/verify_runtime.py').read_bytes(), (ROOT / 'database/schema-contract-52-plus-2/runtime/verify_runtime.py').read_bytes())
            self.assertEqual(manifest, json.loads((schema / 'generated/schema-contract-manifest.json').read_text(encoding='utf-8')))
