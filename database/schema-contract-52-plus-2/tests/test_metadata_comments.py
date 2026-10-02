from dataclasses import replace
import hashlib
import json
from pathlib import Path
import re
import unittest

from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS


class MetadataCommentsTest(unittest.TestCase):
    def test_only_one_column_description_changes_and_sql_is_comments_only(self):
        from contract.evolutions.v1080_metadata_comments import EVOLUTION, FUNCTION_COMMENTS, ARGUMENT_FUNCTION_COMMENTS, TRIGGER_COMMENTS, COLUMN_COMMENT
        before = BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version < 1080:
                before = evolution.apply(before)
        after = EVOLUTION.apply(before)
        restored = tuple(replace(schema, tables=tuple(
            replace(table, columns=tuple(replace(column, comment='STOP_UNSIGNED/REQUEST_REVIEW/STOP_REVIEWED/CONTINUE。')
                if schema.name == 'contract' and table.name == 'negotiation_disposition' and column.name == 'kind'
                else column for column in table.columns)) for table in schema.tables)) for schema in after)
        self.assertEqual(before, restored)
        sql = EVOLUTION.render_sql(before, after)
        comments, count = re.subn(r"COMMENT ON (?:FUNCTION [a-z0-9_.]+\([^)]*\)|TRIGGER [a-z0-9_]+ ON [a-z0-9_.]+|COLUMN contract\.negotiation_disposition\.kind) IS '[^']+';\n", '', sql)
        self.assertEqual(150, count)
        self.assertEqual(1, len(ARGUMENT_FUNCTION_COMMENTS))
        self.assertEqual(94, len(TRIGGER_COMMENTS))
        self.assertEqual(54, len(FUNCTION_COMMENTS))
        self.assertTrue(all(re.search(r'[\u4e00-\u9fff]', text) for text in [COLUMN_COMMENT, *FUNCTION_COMMENTS.values()]))
        self.assertNotRegex(comments, r'\b(?:CREATE|ALTER|DROP|GRANT|REVOKE|INSERT|DELETE)\b')
        self.assertEqual(1, comments.count('UPDATE platform_meta.deployment_state'))
        self.assertIn("schema_contract_version='52-plus-2-r2-v21'", comments)
        self.assertIn("schema_contract_version='52-plus-2-r2-v22'", comments)
        self.assertIn("IF NOT FOUND THEN RAISE EXCEPTION", comments)

    def test_all_preexisting_migration_bytes_are_preserved(self):
        root = Path(__file__).resolve().parents[1] / 'generated'
        expected = json.loads((Path(__file__).with_name('fixtures') / 'v21_migration_hashes.json').read_text(encoding='utf-8'))
        self.assertEqual(42, len(expected))
        for name, digest in expected.items():
            self.assertEqual(digest, hashlib.sha256((root / name).read_bytes()).hexdigest(), name)
