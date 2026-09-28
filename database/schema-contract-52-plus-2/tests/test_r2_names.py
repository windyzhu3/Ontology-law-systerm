import hashlib
import json
import unittest
from pathlib import Path
from contract.schema_contract import BASE_SCHEMAS, EVOLUTIONS, SCHEMAS, CONTRACT_VERSION
from contract.evolutions.v870_r2_lead_independent_names import NAMES

class R2NamesContractTest(unittest.TestCase):
    def test_r2_adds_exactly_two_nullable_immutable_encrypted_columns(self):
        before=BASE_SCHEMAS
        for evolution in EVOLUTIONS:
            if evolution.version<870: before=evolution.apply(before)
        old={f"{s.name}.{t.name}":t for s in before for t in s.tables}
        new={f"{s.name}.{t.name}":t for s in next(e for e in EVOLUTIONS if e.version==870).apply(before) for t in s.tables}
        self.assertEqual(old.keys(),new.keys())
        for key in old:
            if key!='lead.lead': self.assertEqual(old[key],new[key])
        lead=new['lead.lead'];previous=old['lead.lead']
        self.assertEqual(previous.columns,lead.columns[:-2])
        self.assertEqual(NAMES,tuple(c.name for c in lead.columns[-2:]))
        self.assertTrue(all(c.nullable and c.sql_type=='bytea' for c in lead.columns[-2:]))
        self.assertEqual(previous.mutable_columns,lead.mutable_columns)
        self.assertEqual(previous.write_once_columns,lead.write_once_columns)
        self.assertEqual('52-plus-2-r2-v1',next(e for e in EVOLUTIONS if e.version==870).contract_version)
        self.assertEqual(870,next(e for e in EVOLUTIONS if e.version==870).version)
    def test_manifest_binds_exact_r2_migration(self):
        root=Path(__file__).resolve().parents[1]/'generated'
        manifest=json.loads((root/'schema-contract-manifest.json').read_text(encoding='utf-8'))
        name='db/migration/V870__r2_lead_independent_names.sql'
        self.assertEqual(hashlib.sha256((root/name).read_bytes()).hexdigest(),manifest['generatedArtifactSha256'][name])
        self.assertEqual(34,len(manifest['generatedArtifactSha256']))
