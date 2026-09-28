import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r25_contract_recovery_schema_contract import historical_projection, MIGRATION

ROOT=Path(__file__).resolve().parents[3]
GENERATED=ROOT/'database/schema-contract-52-plus-2/generated'

class ContractRecoveryProjectionTest(unittest.TestCase):
 def test_exact_successor_projects_back_to_the_frozen_r1_contract(self):
  manifest=json.loads((GENERATED/'schema-contract-manifest.json').read_text(encoding='utf-8'))
  projected=historical_projection(GENERATED,manifest)
  self.assertEqual('52-plus-2-v1.2',projected['contractVersion'])
  self.assertEqual('a4beeb91ed93be455736eafa3abb829f6a94fed3a263be5996832e458b7c4b39',projected['contractSha256'])
 def test_altered_manifest_or_migration_cannot_use_the_projection(self):
  manifest=json.loads((GENERATED/'schema-contract-manifest.json').read_text(encoding='utf-8'))
  altered=copy.deepcopy(manifest);altered['contractVersion']='52-plus-2-r2-v21'
  with self.assertRaisesRegex(ValueError,'exact reviewed manifest'):historical_projection(GENERATED,altered)
  with tempfile.TemporaryDirectory() as directory:
   target=Path(directory)/'generated';shutil.copytree(GENERATED,target)
   for relative in (MIGRATION,'db/migration/V980__r2_contract_versions.sql'):
    path=target/relative;original=path.read_bytes();path.write_bytes(original+b'\n-- changed\n')
    with self.assertRaisesRegex(ValueError,'migration bytes changed'):historical_projection(target,manifest)
    path.write_bytes(original)
