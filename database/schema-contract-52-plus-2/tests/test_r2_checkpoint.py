import json
from pathlib import Path
import tempfile
import unittest
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS
from contract.render import generate_all

class R2CheckpointContractTest(unittest.TestCase):
    def test_checkpoint_is_only_new_table_and_only_worker_writable(self):
        self.assertIn(890, [e.version for e in EVOLUTIONS])
        old = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version < 890: old = evolution.apply(old)
        evolution = next(e for e in EVOLUTIONS if e.version == 890)
        before = {f'{s.name}.{t.name}': t for s in old for t in s.tables}
        after = {f'{s.name}.{t.name}': t for s in evolution.apply(old) for t in s.tables}
        self.assertEqual({'platform_meta.r2_opportunity_checkpoint'}, after.keys() - before.keys())
        for name, table in before.items(): self.assertEqual(table, after[name])
        table = after['platform_meta.r2_opportunity_checkpoint']
        self.assertEqual(('tenant_id','principal_id','appointment_id','scan_kind'), table.primary_key)
        self.assertEqual(('checkpoint_body','revision','updated_at'), table.mutable_columns)
        sql = evolution.render_sql(old, evolution.apply(old))
        self.assertIn('octet_length(checkpoint_body) BETWEEN 1 AND 65536', sql)
        self.assertIn('GRANT SELECT, INSERT ON platform_meta.r2_opportunity_checkpoint TO ${app_worker_role}', sql)
        self.assertIn('GRANT UPDATE (checkpoint_body, revision, updated_at)', sql)
        self.assertIn('NEW.revision <> OLD.revision + 1', sql)
        self.assertIn('NEW.updated_at := clock_timestamp()', sql)
        self.assertIn('V890 expected 55 managed tables', sql)
        self.assertNotIn('UPDATE execution.domain_event_outbox', sql)
    def test_successor_reports_real_table_counts_and_preserves_old_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory); generate_all(generated)
            manifest = json.loads((generated/'schema-contract-manifest.json').read_text(encoding='utf-8'))
            self.assertEqual('52-plus-2-r2-v13', manifest['contractVersion'])
            self.assertEqual(100, manifest['applicationTableCount'])
            self.assertEqual(2, manifest['selfManagedPlatformTableCount'])
            self.assertEqual(103, manifest['physicalTableCountAfterFlywayBootstrap'])
            checked = Path(__file__).resolve().parents[1]/'generated/db/migration'
            for old in checked.glob('*.sql'):
                if int(old.name[1:4]) < 890: self.assertEqual(old.read_bytes(), (generated/'db/migration'/old.name).read_bytes(), old.name)
