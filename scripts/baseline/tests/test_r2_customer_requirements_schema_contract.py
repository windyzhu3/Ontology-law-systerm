import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r2_customer_requirements_schema_contract import historical_projection, project_validated_v6, MIGRATION
from scripts.baseline import r2_opportunity_closure_schema_contract as v5
from scripts.baseline.r2_schema_successor_contract import canonical_hash

ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'

class CustomerRequirementsSchemaSuccessorTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  from scripts.baseline.tests.historical_schema_fixture import install_historical_schema_fixture
  install_historical_schema_fixture(cls, globals(), 920)

 def test_exact_v920_projects_to_identical_v910_and_frozen_r1(self):
  manifest=json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf-8'))
  self.assertEqual(v5.CONTRACT_HASH,canonical_hash(project_validated_v6(manifest)))
  result=historical_projection(ROOT,manifest)
  self.assertEqual('52-plus-2-v1.2',result['contractVersion'])
  self.assertEqual(54,result['physicalTableCountAfterFlywayBootstrap'])

 def test_t05_and_historical_drift_fail_closed(self):
  for fault in ('field','target','v920','v910','extra','renamed','manifest_rehashed'):
   with self.subTest(fault=fault),tempfile.TemporaryDirectory() as directory:
    root=Path(directory)/'generated';shutil.copytree(ROOT,root)
    manifest=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf-8'))
    if fault=='field':
     table=next(t for s in manifest['schemas'] for t in s['tables'] if t['qualifiedName']=='opportunity.customer_requirement_draft')
     next(c for c in table['columns'] if c['name']=='owner_appointment_id')['nullable']=True
    if fault=='target':manifest['typedReferenceRegistry']['responsibility.task_occurrence.cancellation_fact']['allowedTargetTypes'].append('opportunity.customer_requirement_confirmation')
    if fault=='v920':(root/MIGRATION).write_text('--changed',encoding='utf-8')
    if fault=='v910':(root/v5.MIGRATION).write_text('--changed',encoding='utf-8')
    if fault=='extra':(root/'db/migration/V930__unreviewed.sql').write_text('SELECT 1',encoding='utf-8')
    if fault=='renamed':manifest['contractVersion']='52-plus-2-r2-v5'
    if fault=='manifest_rehashed':manifest['applicationTableCount']+=1;manifest['contractSha256']=canonical_hash(manifest)
    with self.assertRaises(ValueError):historical_projection(root,manifest)
