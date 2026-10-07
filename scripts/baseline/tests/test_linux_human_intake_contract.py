import copy
import importlib
from pathlib import Path
import unittest
import yaml
from scripts.baseline.r2_intake_sources_contract import SCHEMAS,intake_transport_projection

ROOT=Path(__file__).resolve().parents[3]
PROFILE='LINUX_HUMAN_INTAKE_BINDING_V1'
FIELD={'type':'string','enum':['BOUND_TO_PRINCIPAL','SELECTABLE']}

class HumanIntakeContractTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((ROOT/'scripts/baseline/linux_human_intake_contract.py').is_file(),'Named source-binding contract projection missing')
        self.m=importlib.import_module('scripts.baseline.linux_human_intake_contract')
        self.old={'components':{'schemas':copy.deepcopy(SCHEMAS)},'paths':{}}
        self.new=copy.deepcopy(self.old);schema=self.new['components']['schemas']['LeadIntakeSourcesV1']
        schema['properties']['sourceSelection']=copy.deepcopy(FIELD);schema['x-contract-extension']=PROFILE
    def test_exact_named_addition_projects_to_unchanged_old_schema(self):
        self.assertEqual(self.m.human_intake_projection(self.new),self.old)
        self.assertEqual(self.m.human_intake_projection(self.old),self.old)
        self.assertIn('sourceSelection',self.new['components']['schemas']['LeadIntakeSourcesV1']['properties'])
    def test_partial_activation_and_unknown_metadata_are_rejected(self):
        for change in [lambda s:s.pop('x-contract-extension'),lambda s:s['properties'].pop('sourceSelection'),lambda s:s['properties'].update(principalId={'type':'string'}),lambda s:s.update(required=['sources','sourceSelection'])]:
            altered=copy.deepcopy(self.new);change(altered['components']['schemas']['LeadIntakeSourcesV1'])
            with self.assertRaises(ValueError):self.m.human_intake_projection(altered)
    def test_enum_scalar_and_old_source_bounds_cannot_be_loosened(self):
        for change in [lambda s:s['properties']['sourceSelection'].update(enum=['AUTO']),lambda s:s['properties']['sourceSelection'].update(type='boolean'),lambda s:s['properties']['sources'].update(maxItems=51),lambda s:s.update(additionalProperties=0)]:
            altered=copy.deepcopy(self.new);change(altered['components']['schemas']['LeadIntakeSourcesV1'])
            with self.assertRaises(ValueError):self.m.human_intake_projection(altered)
    def test_current_openapi_still_passes_the_original_intake_projection(self):
        document=yaml.safe_load((ROOT/'contracts/openapi/ontology-law-api.yaml').read_text(encoding='utf-8'))
        expected=intake_transport_projection(self.m.human_intake_projection(document))
        self.assertEqual(intake_transport_projection(document),expected)

if __name__=='__main__':unittest.main()
