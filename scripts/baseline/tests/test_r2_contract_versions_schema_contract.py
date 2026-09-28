import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r1_business_closure_contract import validate_ingress_query_capability
ROOT=Path(__file__).resolve().parents[3]
class ContractVersionsSuccessorTest(unittest.TestCase):
    def test_named_successor_is_development_only(self):
        self.assertEqual([],validate_ingress_query_capability(ROOT,allow_r2_schema=True))
        self.assertTrue(validate_ingress_query_capability(ROOT))
    def test_exact_inventory_and_bytes_remain_required(self):
        for fault in ('missing','changed','extra','manifest','field','historical'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                relative='database/schema-contract-52-plus-2/generated'
                shutil.copytree(ROOT/relative,root/relative)
                generated=root/relative
                sql=generated/'db/migration/V980__r2_contract_versions.sql'
                if fault=='missing':sql.unlink()
                elif fault=='changed':sql.write_bytes(sql.read_bytes()+b'\n-- changed')
                elif fault=='extra':(generated/'db/migration/V990__extra.sql').write_text('SELECT 1;')
                elif fault=='field':(generated/'field-contract.md').write_text('changed')
                elif fault=='historical':(generated/'db/migration/V860__lead_ingress_query_read_capability.sql').write_text('SELECT 1;')
                else:
                    path=generated/'schema-contract-manifest.json'
                    m=json.loads(path.read_text(encoding='utf8'));m['applicationTableCount']+=1
                    path.write_text(json.dumps(m),encoding='utf8')
                self.assertTrue(validate_ingress_query_capability(root,allow_r2_schema=True))
