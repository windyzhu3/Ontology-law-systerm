import copy,json,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'database/schema-contract-52-plus-2'))
from contract.schema_contract import SCHEMAS
from scripts.baseline.r2_schema_successor_contract import historical_projection
class ConfigurableRolesSchemaTest(unittest.TestCase):
 def setUp(self):
  self.generated=ROOT/'database/schema-contract-52-plus-2/generated'
  self.manifest=json.loads((self.generated/'schema-contract-manifest.json').read_text())
 def test_exact_successor_projects_to_frozen_identity_without_rewriting_migrations(self):
  previous=historical_projection(self.generated,self.manifest)
  self.assertEqual('52-plus-2-v1.2',previous['contractVersion'])
 def test_catalogue_has_immutable_code_and_same_tenant_appointment_reference(self):
  tables={t.name:t for s in SCHEMAS if s.name=='identity' for t in s.tables}
  role=tables['appointment_role'];self.assertEqual(('display_name','state','revision'),role.mutable_columns)
  self.assertIn(('INACTIVE','ACTIVE'),role.state_transitions)
  relation=next(f for f in tables['appointment'].foreign_keys if f.parent_table=='appointment_role')
  self.assertEqual(('tenant_id','role_code'),relation.columns)
 def test_projection_rejects_changed_catalogue_and_legacy_inventory(self):
  changed=copy.deepcopy(self.manifest);changed['generatedArtifactSha256'].pop('db/migration/V1060__r25_contract_responsibility_recovery.sql')
  with self.assertRaises(ValueError):historical_projection(self.generated,changed)
if __name__=='__main__':unittest.main()
