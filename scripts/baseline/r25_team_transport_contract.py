"""R25_TEAM_MANAGEMENT_V1 exact additive projection; older contracts remain frozen."""
from copy import deepcopy
import hashlib,json
PATHS = {'/api/v1/team-management/{view}': '58e5fe8d1d754c993dc3ae53189ae86cd5e0fe9dc2940c5ef577f32548db4955', '/api/v1/team-management/{view}/{recordId}': '157e58cfb787ad7465f23bab77acc91576c38732a4f34c58fefee4e002792fb6'}
SCHEMAS = {'TeamRowV1': '2aa34f46864bebc08af9cdf329a4bf0500e8c19a77dc8465d0dbe26e28c60c54', 'TeamPageV1': '12b3bb56ae591987942670b1456a6620fdce03eb36c69b9eb8ee2f5ad0f56a78', 'TeamDetailV1': 'df57183791234f6552ad1a1af20c789cabbc8f19af6cfc4cf5fd1e7ec0af31fe'}
ENTRY = {'type': 'boolean', 'description': 'Direct HUMAN team metadata entry hint; never confers another owner command authority.'}
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def team_projection(document):
 try:
  from scripts.baseline.r25_lead_management_contract import lead_management_projection
 except ModuleNotFoundError:
  from r25_lead_management_contract import lead_management_projection
 result=lead_management_projection(document);paths=result['paths'];schemas=result['components']['schemas'];session=schemas['SessionContextV1']
 enums=[schemas['GrantableAuthorityCodeV1']['enum'],schemas['AuthorityGrantV1']['properties']['authorityCode']['enum']]
 present=any(k.startswith('/api/v1/team-management/') for k in paths) or any(k in schemas for k in SCHEMAS) or 'canReadTeamTasks' in session['properties'] or any('TEAM_TASK_READ' in values for values in enums)
 if not present:return result
 if any(k.startswith('/api/v1/team-management/') and k not in PATHS for k in paths):raise ValueError('Unregistered team management route')
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for name,pin in pins.items():
   if digest(section.pop(name,None))!=pin:raise ValueError('Exact R25 team addition required: '+name)
 if session['properties'].pop('canReadTeamTasks',None)!=ENTRY:raise ValueError('Exact team entry hint required')
 for i in (0,1,3):
  if session['allOf'][i]['then']['properties'].pop('canReadTeamTasks',None)!={'const':False}:raise ValueError('Team direct HUMAN entry boundary required')
 for values in enums:
  if values.count('TEAM_TASK_READ')!=1:raise ValueError('Exact independent team read authority required')
  values.remove('TEAM_TASK_READ')
 return result
