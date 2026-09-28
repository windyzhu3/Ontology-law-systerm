import json
import shutil
import tempfile
import unittest
from pathlib import Path
from scripts.baseline.r1_business_closure_contract import validate_ingress_query_capability

ROOT=Path(__file__).resolve().parents[3]
GENERATED=Path('database/schema-contract-52-plus-2/generated')
class R2SchemaSuccessorTest(unittest.TestCase):
    def test_r2_requires_explicit_development_profile(self):
        self.assertTrue(validate_ingress_query_capability(ROOT))
        self.assertEqual([],validate_ingress_query_capability(ROOT,allow_r2_schema=True))
    def test_historical_projection_passes_unchanged_strict_validator(self):
        from scripts.baseline.r2_schema_successor_contract import historical_projection, MIGRATION
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);shutil.copytree(ROOT/GENERATED,root/GENERATED)
            generated=root/GENERATED;path=generated/'schema-contract-manifest.json'
            current=json.loads(path.read_text(encoding='utf8'))
            manifest=historical_projection(generated,current)
            path.write_text(json.dumps(manifest,ensure_ascii=False),encoding='utf8')
            for artifact in set(current['generatedArtifactSha256'])-set(manifest['generatedArtifactSha256']):
                (generated/artifact).unlink()
            self.assertEqual([],validate_ingress_query_capability(root))
            self.assertEqual([],validate_ingress_query_capability(root,allow_r2_schema=True))
    def test_exact_successor_rejects_all_inventory_manifest_and_field_mutations(self):
        for fault in ['missing','changed','extra','historical','manifest','field','version']:
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root=Path(directory);shutil.copytree(ROOT/GENERATED,root/GENERATED)
                generated=root/GENERATED
                migration=generated/'db/migration/V870__r2_lead_independent_names.sql'
                manifest=generated/'schema-contract-manifest.json'
                if fault=='missing': migration.unlink()
                if fault=='changed': migration.write_text(migration.read_text(encoding='utf8')+'-- drift',encoding='utf8')
                if fault=='extra': (migration.parent/'V880__unexpected.sql').write_text('SELECT 1;',encoding='utf8')
                if fault=='historical': (migration.parent/'V860__lead_ingress_query_read_capability.sql').write_text('SELECT 1;',encoding='utf8')
                if fault=='manifest':
                    m=json.loads(manifest.read_text(encoding='utf8'));m['schemas'][0]['comment']='drift';manifest.write_text(json.dumps(m),encoding='utf8')
                if fault=='field': (generated/'field-contract.md').write_text('drift',encoding='utf8')
                if fault=='version': manifest.write_text(manifest.read_text(encoding='utf8').replace(json.loads(manifest.read_text(encoding='utf8'))['contractVersion'],'52-plus-2-r2-v999'),encoding='utf8')
                self.assertTrue(validate_ingress_query_capability(root,allow_r2_schema=True))
