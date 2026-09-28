from copy import deepcopy
from pathlib import Path
import unittest,yaml
class MaterialsTransportTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls): cls.doc=yaml.safe_load(Path('contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf8'))
 def test_exact_predecessor(self):
  from scripts.baseline.r2_materials_transport_contract import materials_projection,PATH_DIGESTS,SCHEMA_DIGESTS
  from scripts.baseline.r2_customer_requirements_transport_contract import customer_requirements_projection
  old=materials_projection(self.doc);self.assertEqual(old,materials_projection(old));customer_requirements_projection(old)
  for section,pins in [('paths',PATH_DIGESTS),('schemas',SCHEMA_DIGESTS)]:
   for k in pins:
    bad=deepcopy(self.doc);target=bad['paths'] if section=='paths' else bad['components']['schemas'];target.pop(k)
    with self.assertRaises(ValueError): materials_projection(bad)
 def test_real_bytes_and_scoped_content(self):
  paths=self.doc['paths'];prefix='/api/v1/opportunities/{opportunityId}'
  upload=paths[prefix+'/material-uploads/{uploadSessionId}/content']['put']
  self.assertIn('application/octet-stream',upload['requestBody']['content'])
  for path,value in paths.items():
   if '/materials' not in path and '/material-uploads' not in path: continue
   for op in value.values():
    self.assertEqual([{'publicBearer':[]}],op['security']);self.assertEqual('REJECT',op['x-on-behalf-selection'])
  schema=self.doc['components']['schemas']['AcceptOpportunityMaterialV1']
  self.assertNotIn('fileName',schema['properties']);self.assertIn('uploadSession',schema['required']);self.assertFalse(schema['additionalProperties'])
