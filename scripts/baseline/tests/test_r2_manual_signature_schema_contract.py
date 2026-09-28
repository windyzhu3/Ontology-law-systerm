import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r1_business_closure_contract import validate_ingress_query_capability
ROOT=Path(__file__).resolve().parents[3]
class ManualSignatureSuccessorTest(unittest.TestCase):
 def test_named_manual_signature_schema_is_development_only(self):
  self.assertEqual([],validate_ingress_query_capability(ROOT,allow_r2_schema=True))
  self.assertTrue(validate_ingress_query_capability(ROOT))
 def test_manual_and_historical_bytes_are_pinned(self):
  for name in ('V990__r2_manual_signature.sql','V980__r2_contract_versions.sql'):
   with self.subTest(name=name),tempfile.TemporaryDirectory() as directory:
    root=Path(directory); relative='database/schema-contract-52-plus-2/generated'
    shutil.copytree(ROOT/relative,root/relative)
    p=root/relative/'db/migration'/name;p.write_bytes(p.read_bytes()+b'\n-- drift')
    self.assertTrue(validate_ingress_query_capability(root,allow_r2_schema=True))
if __name__=='__main__':unittest.main()
