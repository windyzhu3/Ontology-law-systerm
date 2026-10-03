import unittest,copy,subprocess
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[3]
MANAGEMENT=['IDENTITY_PRINCIPAL_MANAGE','IDENTITY_ORGANIZATION_MANAGE','IDENTITY_APPOINTMENT_MANAGE','IDENTITY_AUTHORITY_MANAGE']
class SecondaryAdminContract(unittest.TestCase):
 def test_only_four_management_codes_extend_existing_transport(self):
  doc=yaml.safe_load((ROOT/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
  prior=yaml.safe_load(subprocess.run(['git','show','ece7a20:contracts/openapi/ontology-law-api.yaml'],cwd=ROOT,capture_output=True,check=True).stdout)
  codes=doc['components']['schemas']['GrantableAuthorityCodeV1']['enum']
  self.assertTrue(all(code in codes for code in MANAGEMENT))
  projected=copy.deepcopy(doc);projected['components']['schemas']['GrantableAuthorityCodeV1']['enum']=[code for code in codes if code not in MANAGEMENT]
  self.assertEqual(projected,prior)
if __name__=='__main__':unittest.main()
