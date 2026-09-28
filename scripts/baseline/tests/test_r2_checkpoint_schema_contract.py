import json
from pathlib import Path
import shutil
import tempfile
import unittest
from scripts.baseline.r2_checkpoint_schema_contract import historical_projection, MIGRATION

ROOT=Path(__file__).resolve().parents[3]/'database/schema-contract-52-plus-2/generated'
class R2CheckpointSuccessorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # This suite remains an exact V890 historical test after later successors.
        import sys
        from unittest.mock import patch
        sys.path.insert(0,str(ROOT.parent))
        from contract import schema_contract as contract
        from contract.render import generate_all
        cls._temporary=tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls._temporary.cleanup)
        old=contract.BASE_SCHEMAS
        evolutions=tuple(e for e in contract.EVOLUTIONS if e.version<=890)
        for e in evolutions:old=e.apply(old)
        with patch.object(contract,'SCHEMAS',old),patch.object(contract,'EVOLUTIONS',evolutions),patch.object(contract,'CONTRACT_VERSION','52-plus-2-r2-v3'):
            generate_all(Path(cls._temporary.name))
        globals()['ROOT']=Path(cls._temporary.name)

    def test_exact_addition_projects_to_frozen_r1(self):
        result=historical_projection(ROOT,json.loads((ROOT/'schema-contract-manifest.json').read_text(encoding='utf-8')))
        self.assertEqual('52-plus-2-v1.2',result['contractVersion'])
        self.assertEqual(52,result['applicationTableCount'])
        self.assertEqual(54,result['physicalTableCountAfterFlywayBootstrap'])
    def test_checkpoint_or_existing_artifact_drift_fails_closed(self):
        for fault in ['missing','changed','extra','old','manifest','field']:
            with self.subTest(fault=fault),tempfile.TemporaryDirectory() as directory:
                root=Path(directory)/'generated';shutil.copytree(ROOT,root)
                manifest=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf-8'))
                if fault=='missing': (root/MIGRATION).unlink()
                if fault=='changed': (root/MIGRATION).write_text('-- drift',encoding='utf-8')
                if fault=='extra': (root/'db/migration/V900__extra.sql').write_text('SELECT 1;',encoding='utf-8')
                if fault=='old': (root/'db/migration/V880__r2_opportunity_progress.sql').write_text('-- drift',encoding='utf-8')
                if fault=='manifest': manifest['physicalTableCountAfterFlywayBootstrap']=54
                if fault=='field': (root/'field-contract.md').write_text('drift',encoding='utf-8')
                with self.assertRaises(ValueError): historical_projection(root,manifest)
