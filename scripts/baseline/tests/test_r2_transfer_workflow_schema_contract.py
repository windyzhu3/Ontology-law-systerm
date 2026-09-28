import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r2_transfer_workflow_schema_contract import historical_projection,ADDITIONS

ROOT=Path(__file__).resolve().parents[3]
GENERATED=ROOT/'database/schema-contract-52-plus-2/generated'

class TransferSchemaProjectionTest(unittest.TestCase):
 def test_exact_current_projects_to_unchanged_r1(self):
  manifest=json.loads((GENERATED/'schema-contract-manifest.json').read_text(encoding='utf8'))
  previous=historical_projection(GENERATED,manifest)
  self.assertEqual('52-plus-2-v1.2',previous['contractVersion'])
  self.assertEqual(21,len(previous['generatedArtifactSha256']))

 def test_new_migrations_and_inventory_cannot_be_modified(self):
  for change in [*sorted(ADDITIONS),'extra','field','manifest']:
   with self.subTest(change=change),tempfile.TemporaryDirectory() as directory:
    generated=Path(directory)/'generated';shutil.copytree(GENERATED,generated)
    manifest=json.loads((generated/'schema-contract-manifest.json').read_text(encoding='utf8'))
    if change in ADDITIONS:
     p=generated/change;p.write_bytes(p.read_bytes()+b'\n-- changed\n')
    elif change=='extra':(generated/'db/migration/V1060__unapproved.sql').write_text('SELECT 1;')
    elif change=='field':(generated/'field-contract.md').write_text('changed')
    else:manifest['applicationTableCount']+=1
    with self.assertRaises(ValueError):historical_projection(generated,manifest)
