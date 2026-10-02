import unittest,copy,hashlib,json,subprocess
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[3]
PATHS=['/api/v1/admin/audit-records','/api/v1/admin/audit-records/{auditRecordId}','/api/v1/admin/audit-records/{auditRecordId}/related']
class AuditQueryContract(unittest.TestCase):
 def setUp(self): self.doc=yaml.safe_load((ROOT/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
 def test_three_closed_read_only_routes(self):
  for p in PATHS:
   self.assertIn(p,self.doc['paths']);self.assertEqual(set(self.doc['paths'][p]),{'get'})
   op=self.doc['paths'][p]['get'];self.assertEqual(op['x-authority-code'],'AUDIT_READ');self.assertEqual(op['x-on-behalf-selection'],'REJECT')
  self.assertFalse(any('export' in p for p in self.doc['paths'] if p.startswith('/api/v1/admin/audit')))
 def test_one_independent_permission_without_export(self):
  codes=self.doc['components']['schemas']['GrantableAuthorityCodeV1']['enum'];self.assertIn('AUDIT_READ',codes);self.assertNotIn('AUDIT_EXPORT',codes)
  self.assertIn('AUDIT_READ',self.doc['components']['schemas']['AuthorityGrantV1']['properties']['authorityCode']['enum'])
 def test_minimal_safe_dto(self):
  schema=self.doc['components']['schemas']['AuditRecordV1'];self.assertFalse(schema['additionalProperties']);props=schema['properties']
  self.assertIn('trustedAt',props);self.assertIn('summary',props)
  for name in ['authorizationPathLabel','recordOrganizationLabel','onBehalfLabel']:self.assertIn(name,props)
  for name in ['change_summary','changeSummary','authorizationEvidence','sessionIdHmac','clientIpCiphertext','executionNodeCode','commandId','traceId','sourceRecordKeyDigest']:self.assertNotIn(name,props)
 def test_optional_entry_flag(self):
  schema=self.doc['components']['schemas']['SessionContextV1'];self.assertEqual(schema['properties']['canReadAuditRecords'],{'type':'boolean'});self.assertNotIn('canReadAuditRecords',schema['required'])
 def test_bounded_page(self):
  schema=self.doc['components']['schemas']['AuditRecordPageV1'];self.assertEqual(schema['properties']['items']['maxItems'],50)
 def test_projection_is_exact_and_rejects_drift(self):
  from scripts.baseline.adm07_audit_query_contract import audit_query_projection
  previous=yaml.safe_load(subprocess.run(['git','show','d48f9df:contracts/openapi/ontology-law-api.yaml'],cwd=ROOT,capture_output=True,check=True).stdout)
  self.assertEqual(audit_query_projection(self.doc),previous)
  altered=copy.deepcopy(self.doc);altered['paths'][PATHS[0]]['get']['x-authority-code']='IDENTITY_AUTHORITY_MANAGE'
  with self.assertRaises(ValueError):audit_query_projection(altered)
if __name__=='__main__':unittest.main()
