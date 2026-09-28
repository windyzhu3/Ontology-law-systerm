import json, shutil, tempfile, unittest
from pathlib import Path
from scripts.baseline.r2_quote_transaction_schema_contract import historical_projection, project_validated_v10, MIGRATION
from scripts.baseline import r2_quote_runtime_schema_contract as v9
from scripts.baseline.r2_schema_successor_contract import canonical_hash
ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'
class QuoteTransactionSuccessorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from scripts.baseline.tests.historical_schema_fixture import install_historical_schema_fixture
        install_historical_schema_fixture(cls, globals(), 960)

    def test_exact_v950_and_r1_projection(self):
        m=json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf8'))
        self.assertEqual(v9.CONTRACT_HASH,canonical_hash(project_validated_v10(m)))
        self.assertEqual('52-plus-2-v1.2',historical_projection(ROOT,m)['contractVersion'])
    def test_mutations_fail_closed(self):
        for fault in ('migration','history','extra','manifest'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory() as d:
                root=Path(d)/'generated';shutil.copytree(ROOT,root)
                m=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf8'))
                if fault=='migration':(root/MIGRATION).write_text('--changed',encoding='utf8')
                elif fault=='history':(root/v9.MIGRATION).write_text('--changed',encoding='utf8')
                elif fault=='extra':(root/'db/migration/V970__extra.sql').write_text('SELECT 1;',encoding='utf8')
                else:m['applicationTableCount']+=1;m['contractSha256']=canonical_hash(m)
                with self.assertRaises(ValueError):historical_projection(root,m)
