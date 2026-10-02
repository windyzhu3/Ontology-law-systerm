"""Exact approved configurable-role transport successor; keep frozen validators intact."""
from copy import deepcopy
import hashlib,json
from pathlib import Path
ADDED = ['AppointmentRoleCommandReceiptV1', 'AppointmentRoleFactRefV1', 'AppointmentRolePageV1', 'AppointmentRoleV1', 'CreateAppointmentRoleV1', 'DeactivateAppointmentRoleV1', 'ReactivateAppointmentRoleV1', 'RenameAppointmentRoleV1']
CHANGED = ['AppointmentV1', 'IdentityAdminPageV1', 'IdentityChoiceV1', 'IdentityOptionKindV1', 'IdentityRoleCodeV1', 'PublicFactRef']
PATHS = ['/api/v1/admin/identity/roles', '/api/v1/admin/identity/roles/{id}/deactivate', '/api/v1/admin/identity/roles/{id}/display-name', '/api/v1/admin/identity/roles/{id}/reactivate']
PIN = 'de03e03e6c2ce31801bef48b455eebe10deb0e4407fee06c2324dbcc5adf4510'
PREVIOUS_PIN = 'f6ef06c732dfee52c8aed08f3e62042f137886b5102b3e9b7ea192f91cea0c94'
def digest(value):
 return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()
def configurable_roles_projection(document):
 schemas=document['components']['schemas'];paths=document['paths']
 if not any(k in schemas for k in ADDED) and not any(k in paths for k in PATHS):return deepcopy(document)
 fragment={'paths':{k:paths.get(k) for k in PATHS},'schemas':{k:schemas.get(k) for k in ADDED+CHANGED}}
 if digest(fragment)!=PIN:raise ValueError('Configurable roles require the exact approved transport delta')
 previous=json.loads(Path(__file__).with_name('configurable_roles_previous_transport.json').read_text(encoding='utf-8'))
 if digest(previous)!=PREVIOUS_PIN:raise ValueError('Frozen prior role transport changed')
 result=deepcopy(document)
 for key in PATHS:del result['paths'][key]
 for key in ADDED:del result['components']['schemas'][key]
 result['components']['schemas'].update(previous)
 return result
