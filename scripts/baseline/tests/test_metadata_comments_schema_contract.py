import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
GENERATED = ROOT / 'database/schema-contract-52-plus-2/generated'


class MetadataCommentsSchemaContractTest(unittest.TestCase):
    def test_current_projects_to_exact_frozen_r1_and_rejects_each_manifest_change(self):
        from scripts.baseline.metadata_comments_schema_contract import historical_projection
        from scripts.baseline.r2_schema_successor_contract import R1_CONTRACT_HASH, canonical_hash
        manifest = json.loads((GENERATED / 'schema-contract-manifest.json').read_text(encoding='utf-8'))
        projected = historical_projection(GENERATED, manifest)
        self.assertEqual('52-plus-2-v1.2', projected['contractVersion'])
        self.assertEqual(R1_CONTRACT_HASH, canonical_hash(projected))
        for key in ['contractVersion', 'contractSha256', 'fieldContractSha256', 'applicationTableCount']:
            with self.subTest(key=key):
                altered = copy.deepcopy(manifest)
                altered[key] = 'UNAPPROVED'
                with self.assertRaises(ValueError): historical_projection(GENERATED, altered)

    def test_missing_extra_and_changed_sql_or_field_contract_are_rejected(self):
        from scripts.baseline.metadata_comments_schema_contract import historical_projection, MIGRATION
        manifest = json.loads((GENERATED / 'schema-contract-manifest.json').read_text(encoding='utf-8'))
        for mutation in ['missing', 'extra', 'changed', 'old-changed', 'field-changed']:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                shutil.copytree(GENERATED, root, dirs_exist_ok=True)
                if mutation == 'missing': (root / MIGRATION).unlink()
                elif mutation == 'extra': (root / 'db/migration/V1090__unapproved.sql').write_text('-- unexpected', encoding='utf-8')
                else:
                    path = root / ('field-contract.md' if mutation == 'field-changed' else 'db/migration/V1070__configurable_appointment_roles.sql' if mutation == 'old-changed' else MIGRATION)
                    path.write_bytes(path.read_bytes() + b'\n-- drift\n')
                with self.assertRaises(ValueError): historical_projection(root, manifest)
