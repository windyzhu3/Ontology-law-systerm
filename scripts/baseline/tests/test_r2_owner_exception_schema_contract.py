import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r2_owner_exception_schema_contract import historical_projection, MIGRATION
ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'
class OwnerExceptionSchemaSuccessorTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  import sys
  from unittest.mock import patch
  sys.path.insert(0,str(ROOT.parent))
  from contract import schema_contract as contract
  from contract.render import generate_all
  cls._temporary=tempfile.TemporaryDirectory();cls.addClassCleanup(cls._temporary.cleanup)
  old=contract.BASE_SCHEMAS;evolutions=tuple(e for e in contract.EVOLUTIONS if e.version<=900)
  for e in evolutions:old=e.apply(old)
  with patch.object(contract,'SCHEMAS',old),patch.object(contract,'EVOLUTIONS',evolutions),patch.object(contract,'CONTRACT_VERSION','52-plus-2-r2-v4'):
   generate_all(Path(cls._temporary.name))
  globals()['ROOT']=Path(cls._temporary.name)

 def test_exact_v900_projects_all_the_way_to_frozen_r1(self):
  result=historical_projection(ROOT,json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf-8')))
  self.assertEqual('52-plus-2-v1.2',result['contractVersion'])
  self.assertEqual(54,result['physicalTableCountAfterFlywayBootstrap'])
 def test_any_artifact_or_model_drift_fails_closed(self):
  for fault in ('missing','changed','extra','old','manifest','field','rename'):
   with self.subTest(fault=fault), tempfile.TemporaryDirectory() as d:
    root=Path(d)/'generated';shutil.copytree(ROOT,root)
    m=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf-8'))
    if fault=='missing':(root/MIGRATION).unlink()
    if fault=='changed':(root/MIGRATION).write_text('--changed',encoding='utf-8')
    if fault=='extra':(root/'db/migration/V901__extra.sql').write_text('SELECT 1',encoding='utf-8')
    if fault=='old':(root/'db/migration/V890__r2_opportunity_checkpoint.sql').write_text('--changed',encoding='utf-8')
    if fault=='manifest':m['applicationTableCount']=52
    if fault=='field':(root/'field-contract.md').write_text('changed',encoding='utf-8')
    if fault=='rename':m['contractVersion']='52-plus-2-r2-v3'
    with self.assertRaises(ValueError):historical_projection(root,m)
