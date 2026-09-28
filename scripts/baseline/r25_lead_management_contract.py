"""R25_LEAD_MANAGEMENT_V1 exact additive projection; every older boundary remains frozen."""
from copy import deepcopy
import hashlib,json
PATHS = {'/api/v1/lead-management/leads': '4932e183f90cb9386eaee888c46622d5659d150ec5059f5b8d872162d815742a', '/api/v1/lead-management/leads/{leadId}': 'a9977f00ccb45cb672812dcc7d0710f279b9b706d8a56672b1df482b06c4c48e', '/api/v1/lead-management/sources': '811307a9c0535072d9d348c43b34124fe54810d0b95e46aaf7a734f249bf0381'}
SCHEMAS = {'LeadManagementRowV1': '500d740daa8892700ea901684c693e64a506a014eb1ab87d1808d92bac3a3d48', 'LeadManagementPageV1': '7a938454fbdb96919b7a56bb5cef9807cc0bb2767d671ca4d28fd98bed0b79f0', 'LeadManagementDetailV1': '85c6266198b4b94898867a924c2b85fb0666527d7a546364431117742e235a05', 'LeadManagementSourceV1': '6ba305212afe7003a32e2d89055251063a6a49558ed40ba134c6ba1e0bddd922', 'LeadManagementSourcesV1': '923f133364b1ac00f1f07363600c7ca02e82e320ca2f404d14c6848c6008486a'}
ENTRY = {'type': 'boolean', 'description': 'Direct HUMAN lead management entry hint; each exact source is freshly authorized and audited.'}
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def lead_management_projection(document):
 try:
  from scripts.baseline.r25_business_overview_contract import overview_projection
 except ModuleNotFoundError:
  from r25_business_overview_contract import overview_projection
 result=overview_projection(document);paths=result['paths'];schemas=result['components']['schemas'];session=schemas['SessionContextV1']
 enums=[schemas['GrantableAuthorityCodeV1']['enum'],schemas['AuthorityGrantV1']['properties']['authorityCode']['enum']]
 present=any(k.startswith('/api/v1/lead-management/') for k in paths) or any(k in schemas for k in SCHEMAS) or 'canReadLeadManagement' in session['properties'] or any('LEAD_MANAGEMENT_READ' in values for values in enums)
 if not present:return result
 if any(k.startswith('/api/v1/lead-management/') and k not in PATHS for k in paths):raise ValueError('Unregistered lead management route')
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for name,pin in pins.items():
   if digest(section.pop(name,None))!=pin:raise ValueError('Exact R25 lead addition required: '+name)
 if session['properties'].pop('canReadLeadManagement',None)!=ENTRY:raise ValueError('Exact lead management entry hint required')
 for i in (0,1,3):
  if session['allOf'][i]['then']['properties'].pop('canReadLeadManagement',None)!={'const':False}:raise ValueError('Lead management direct HUMAN entry boundary required')
 for values in enums:
  if values.count('LEAD_MANAGEMENT_READ')!=1:raise ValueError('Exact independent lead read authority required')
  values.remove('LEAD_MANAGEMENT_READ')
 return result
