"""Exact additive R management read contract, restoring the preceding frozen contract."""
from copy import deepcopy
import hashlib,json
PATHS={'/api/v1/business-management/{view}': '36fa3de0b31eb0d427c27efd7f51907c5298bb09b5de41967881db0cd0304078', '/api/v1/business-management/{view}/{requestId}': '111540f2bea447bab1e19e74a140ce53c9423718fbfbe4e2cb36d89ab60cdbe9'}
SCHEMAS={'ManagementRowV1': '632dbd0856f2a3bda07fdabeb5d7f3aad8b9a135f7803222cdb5cb4c6d499965', 'ManagementPageV1': 'abfcd4f0480ba8bf611efc674455eb26a018858d55346dca7ef2c1e63939e103', 'ManagementDetailV1': '20f5e52bd1e949aca1d556ac1d3a51ac99e30a98ad750a845cff421da3c1479e'}
ENTRY={'type': 'boolean', 'description': 'Direct HUMAN management entry hint; each read rechecks exact scope.'}
VIEWS={'type': 'array', 'maxItems': 3, 'items': {'type': 'string', 'enum': ['contracts', 'payments', 'transfer']}, 'description': 'Current direct HUMAN view entry hints; every data request rechecks exact scope.'}
CODES=['PAYMENT_LEDGER_READ','TRANSFER_LEDGER_READ']
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def management_projection(document):
 try:
  from scripts.baseline.r25_team_transport_contract import team_projection
 except ModuleNotFoundError:
  from r25_team_transport_contract import team_projection
 result=team_projection(document);paths=result['paths'];schemas=result['components']['schemas'];session=schemas['SessionContextV1']
 enums=[schemas['GrantableAuthorityCodeV1']['enum'],schemas['AuthorityGrantV1']['properties']['authorityCode']['enum']]
 present=any(k in paths for k in PATHS) or any(k in schemas for k in SCHEMAS) or 'canReadBusinessManagement' in session['properties'] or 'businessManagementViews' in session['properties'] or any(code in values for values in enums for code in CODES)
 if not present:return result
 if any(k.startswith('/api/v1/business-management/') and k not in PATHS for k in paths):raise ValueError('Unregistered management route')
 for section,pins in ((paths,PATHS),(schemas,SCHEMAS)):
  for name,pin in pins.items():
   if digest(section.pop(name,None))!=pin:raise ValueError('Exact R management addition required: '+name)
 if session['properties'].pop('canReadBusinessManagement',None)!=ENTRY:raise ValueError('Exact management entry hint required')
 if session['properties'].pop('businessManagementViews',None)!=VIEWS:raise ValueError('Exact management view hints required')
 for i in (0,1,3):
  if session['allOf'][i]['then']['properties'].pop('businessManagementViews',None)!={'maxItems':0}:raise ValueError('Management views require direct HUMAN selection')
  if session['allOf'][i]['then']['properties'].pop('canReadBusinessManagement',None)!={'const':False}:raise ValueError('Management direct HUMAN entry boundary required')
 for values in enums:
  for code in CODES:
   if values.count(code)!=1:raise ValueError('Exact management authority addition required')
   values.remove(code)
 return result
