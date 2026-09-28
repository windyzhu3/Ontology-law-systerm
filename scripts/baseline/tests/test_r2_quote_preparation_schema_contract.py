import json,shutil,tempfile,unittest
from pathlib import Path
from scripts.baseline.r1_business_closure_contract import validate_ingress_query_capability
ROOT=Path(__file__).resolve().parents[3]
class QuotePreparationSuccessorTest(unittest.TestCase):
 def test_named_preparation_schema_is_development_only(self):
  self.assertEqual([],validate_ingress_query_capability(ROOT,allow_r2_schema=True))
  self.assertTrue(validate_ingress_query_capability(ROOT))
 def test_new_and_historical_bytes_are_pinned(self):
  for name in ('V1000__r2_quote_preparation_intent.sql','V990__r2_manual_signature.sql','V950__r2_quote_runtime.sql'):
   with self.subTest(name=name),tempfile.TemporaryDirectory() as directory:
    root=Path(directory);relative='database/schema-contract-52-plus-2/generated';shutil.copytree(ROOT/relative,root/relative)
    p=root/relative/'db/migration'/name;p.write_bytes(p.read_bytes()+b'\n-- drift')
    self.assertTrue(validate_ingress_query_capability(root,allow_r2_schema=True))
 def test_future_version_is_not_implicitly_approved(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);relative='database/schema-contract-52-plus-2/generated';shutil.copytree(ROOT/relative,root/relative)
   p=root/relative/'schema-contract-manifest.json';m=json.loads(p.read_text(encoding='utf-8'));m['contractVersion']='52-plus-2-r2-v16';p.write_text(json.dumps(m),encoding='utf-8')
   self.assertTrue(validate_ingress_query_capability(root,allow_r2_schema=True))
