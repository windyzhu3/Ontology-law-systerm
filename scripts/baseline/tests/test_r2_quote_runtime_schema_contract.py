import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r2_quote_runtime_schema_contract import project_validated_v9, MIGRATION
from scripts.baseline import r2_quotes_schema_contract as v8
from scripts.baseline.r2_quote_transaction_schema_contract import historical_projection, project_validated_v10
from scripts.baseline.r2_schema_successor_contract import canonical_hash

ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'

class QuoteRuntimeSuccessorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from scripts.baseline.tests.historical_schema_fixture import install_historical_schema_fixture
        install_historical_schema_fixture(cls, globals(), 960)

    def test_exact_v940_and_r1_projection(self):
        manifest=json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf8'))
        self.assertEqual(v8.CONTRACT_HASH,canonical_hash(project_validated_v9(project_validated_v10(manifest))))
        self.assertEqual('52-plus-2-v1.2',historical_projection(ROOT,manifest)['contractVersion'])

    def test_current_historical_and_manifest_mutations_fail_closed(self):
        for fault in ('migration','history','extra','manifest'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root=Path(directory)/'generated'
                shutil.copytree(ROOT,root)
                manifest=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf8'))
                if fault=='migration': (root/MIGRATION).write_text('--changed',encoding='utf8')
                elif fault=='history': (root/v8.MIGRATION).write_text('--changed',encoding='utf8')
                elif fault=='extra': (root/'db/migration/V970__extra.sql').write_text('SELECT 1;',encoding='utf8')
                else:
                    manifest['applicationTableCount']+=1
                    manifest['contractSha256']=canonical_hash(manifest)
                with self.assertRaises(ValueError): historical_projection(root,manifest)
