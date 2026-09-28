from scripts.baseline.r2_management_transport_contract import management_projection
from copy import deepcopy
from pathlib import Path
import unittest,yaml
from scripts.baseline.r2_business_authorities_transport_contract import business_authorities_projection,ADDITIONS
class BusinessAuthoritiesTransportTest(unittest.TestCase):
 def test_named_authorities_are_identical_in_grant_writes_and_projected_reads(self):
  doc=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'));prior=business_authorities_projection(doc)
  self.assertEqual(prior,business_authorities_projection(prior))
  for code in ADDITIONS:
   altered=deepcopy(doc);altered['components']['schemas']['GrantableAuthorityCodeV1']['enum'].remove(code)
   with self.assertRaises(ValueError):business_authorities_projection(altered)
  self.assertNotIn('SYSTEM_ADMIN',doc['components']['schemas']['GrantableAuthorityCodeV1']['enum'])
  restored=deepcopy(prior)
  restored['components']['schemas']['GrantableAuthorityCodeV1']['enum']+=ADDITIONS
  restored['components']['schemas']['AuthorityGrantV1']['properties']['authorityCode']['enum']+=ADDITIONS
  self.assertEqual(management_projection(doc),restored)
