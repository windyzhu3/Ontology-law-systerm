import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r2_opportunity_closure_schema_contract import historical_projection,project_validated_v5,MIGRATION
from scripts.baseline import r2_owner_exception_schema_contract as v4
from scripts.baseline.r2_schema_successor_contract import canonical_hash
ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'

class ClosureSchemaSuccessorTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  import sys
  from unittest.mock import patch
  sys.path.insert(0,str(ROOT.parent))
  from contract import schema_contract as contract
  from contract.render import generate_all
  cls._temporary=tempfile.TemporaryDirectory();cls.addClassCleanup(cls._temporary.cleanup)
  old=contract.BASE_SCHEMAS;evolutions=tuple(e for e in contract.EVOLUTIONS if e.version<=910)
  for e in evolutions:old=e.apply(old)
  with patch.object(contract,'SCHEMAS',old),patch.object(contract,'EVOLUTIONS',evolutions),patch.object(contract,'CONTRACT_VERSION','52-plus-2-r2-v5'):
   generate_all(Path(cls._temporary.name))
  globals()['ROOT']=Path(cls._temporary.name)

 def test_exact_v910_projects_to_identical_v900_and_frozen_r1(self):
  m=json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf-8'))
  v900=project_validated_v5(m)
  self.assertEqual(v4.CONTRACT_HASH,canonical_hash(v900))
  result=historical_projection(ROOT,m)
  self.assertEqual('52-plus-2-v1.2',result['contractVersion'])
  self.assertEqual(54,result['physicalTableCountAfterFlywayBootstrap'])
 def test_named_closure_field_target_and_each_migration_drift_fail_closed(self):
  for fault in ('field','target','v910','v900','extra','renamed','manifest_rehashed'):
   with self.subTest(fault=fault),tempfile.TemporaryDirectory() as d:
    root=Path(d)/'generated';shutil.copytree(ROOT,root);m=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf-8'))
    tables={t['qualifiedName']:t for s in m['schemas'] for t in s['tables']}
    if fault=='field':next(c for c in tables['opportunity.closure']['columns'] if c['name']=='task_occurrence_id')['nullable']=False
    if fault=='target':m['typedReferenceRegistry']['responsibility.task_occurrence.cancellation_fact']['allowedTargetTypes'].append('responsibility.decision_record')
    if fault=='v910':(root/MIGRATION).write_text('--changed',encoding='utf-8')
    if fault=='v900':(root/v4.MIGRATION).write_text('--changed',encoding='utf-8')
    if fault=='extra':(root/'db/migration/V920__unreviewed.sql').write_text('SELECT 1',encoding='utf-8')
    if fault=='renamed':m['contractVersion']='52-plus-2-r2-v4'
    if fault=='manifest_rehashed':m['applicationTableCount']+=1;m['contractSha256']=canonical_hash(m)
    with self.assertRaises(ValueError):historical_projection(root,m)
